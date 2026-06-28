/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.spi.store.catalog.persistence.storageParts.index;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.Scope;
import io.evitadb.index.CatalogIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.RecordWithCompressedId;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;

/**
 * Filter index container stores index for single {@link AttributeSchema} of the single
 * {@link EntitySchema}. This container object serves only as a storage carrier for
 * {@link io.evitadb.index.attribute.GlobalUniqueIndex} which is a live memory representation of the data stored in this
 * container.
 *
 * A large index persists its value-to-entity-tuple bucket tree GRANULARLY as individual
 * {@link GlobalUniqueIndexLeafPagePart} leaf pages (the `PAGED` shape), so a single edit rewrites one ~KB leaf rather
 * than the whole value map; the small-index case keeps every entry inline as the positionally-aligned {@link #values} /
 * {@link #payloads} columns (the `SINGLE` shape). The locale map {@link #localeIndex} stays INLINE on the root in both
 * shapes — only the value→tuple data pages out.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@ToString(of = "attributeKey")
public class GlobalUniqueIndexStoragePart implements StoragePart, RecordWithCompressedId<AttributeKey> {
	// the serialVersionUID was bumped from -7216725334566367295L (the released 2025.1–2026.1 inline-map format that
	// stored (entityType, primaryKey, locale) ints per entry) when the SINGLE shape switched to the packed value/payload
	// columns with a PAGED discriminator; old records still carry the previous UID and are read by
	// GlobalUniqueIndexStoragePartSerializer_2026_1
	@Serial private static final long serialVersionUID = 4823100764501982337L;

	/**
	 * Scope of the {@link CatalogIndex} this unique index belongs to.
	 */
	@Getter private final Scope scope;
	/**
	 * Contains name and locale of the indexed attribute.
	 */
	@Getter private final AttributeKey attributeKey;
	/**
	 * Contains type of the attribute.
	 */
	@Getter private final Class<? extends Serializable> type;
	/**
	 * The `SINGLE`-shape value column: the indexed unique values in ascending key order, positionally aligned with
	 * {@link #payloads}. `null` for a `PAGED` root (the entries live in {@link GlobalUniqueIndexLeafPagePart} leaf pages,
	 * rebuilt from the assembled pages on load). This is the same column shape a leaf page carries — the small index is
	 * simply a single embedded leaf kept inline on the root rather than paged out to a separate part.
	 */
	@Getter @Nullable private final Serializable[] values;
	/**
	 * The `SINGLE`-shape payload column: each entry's packed `long` (entity tuple), positionally aligned with
	 * {@link #values}; `null` for a `PAGED` root. The packing convention is owned by
	 * {@link io.evitadb.index.attribute.GlobalUniqueIndex}; this carrier treats the payloads as opaque longs.
	 */
	@Getter @Nullable private final long[] payloads;
	/**
	 * Keeps the internal index of primary keys assigned to locales. Stays INLINE on the root in both the `SINGLE` and
	 * `PAGED` shapes — only the value→tuple data pages out.
	 */
	@Getter private final Map<Integer, Locale> localeIndex;
	/**
	 * The `PAGED`/`SINGLE` discriminator. When `true` the value-to-tuple bucket tree is persisted as individual
	 * {@link GlobalUniqueIndexLeafPagePart} leaf pages keyed by `pack(streamId, pageSequence)` and the inline
	 * {@link #values} / {@link #payloads} columns are `null`; when `false` (the small-index case) every entry lives
	 * inline in the {@link #values} / {@link #payloads} columns. The page stream id is deliberately NOT persisted here — it is the
	 * {@link GlobalUniqueLeafStreamKey}'s compressed id, recomputed at load from the `(scope, attributeKey)` identity via
	 * the catalog's read-only {@code KeyCompressor}.
	 */
	@Getter private final boolean paged;
	/**
	 * The high-water `pageSequence` of the stream (the maximum `pageSequence` ever allocated) for a `PAGED` root; `-1`
	 * otherwise. Persisted explicitly rather than derived as `max(pageSequence)` over live pages, so a freed max page
	 * cannot let a reused id be handed out while an older catalog version still references it.
	 */
	@Getter private final int highWaterPageSequence;
	/**
	 * The leaf pages of a `PAGED` root, listed in ascending key order — exactly the order in which the load path reads
	 * them back and reassembles the spine (the spine is NOT persisted; it is reconstructed at load). Empty otherwise.
	 */
	@Nonnull @Getter private final int[] leafPageSequences;
	/**
	 * Id used for lookups in persistent storage for this particular container.
	 */
	@Nullable @Getter @Setter private Long storagePartPK;

	/**
	 * Builds a `SINGLE` root carrying the inline value/payload columns and the inline locale map.
	 *
	 * @param scope        scope of the owning catalog index
	 * @param attributeKey the indexed attribute key
	 * @param type         the indexed value type
	 * @param values       the inline value column (ascending key order)
	 * @param payloads     the inline packed-`long` payload column, positionally aligned with `values`
	 * @param localeIndex  the inline internal locale-id map
	 */
	public GlobalUniqueIndexStoragePart(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> type,
		@Nonnull Serializable[] values,
		@Nonnull long[] payloads,
		@Nonnull Map<Integer, Locale> localeIndex
	) {
		this(scope, attributeKey, type, values, payloads, localeIndex, null);
	}

	/**
	 * Builds a `SINGLE` root carrying the inline value/payload columns and the inline locale map, with a pre-computed
	 * storage part id (load path). This is the canonical inline constructor used by the runtime and the serializer.
	 *
	 * @param scope         scope of the owning catalog index
	 * @param attributeKey  the indexed attribute key
	 * @param type          the indexed value type
	 * @param values        the inline value column (ascending key order)
	 * @param payloads      the inline packed-`long` payload column, positionally aligned with `values`
	 * @param localeIndex   the inline internal locale-id map
	 * @param storagePartPK the already-assigned storage part PK, or `null`
	 */
	public GlobalUniqueIndexStoragePart(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> type,
		@Nonnull Serializable[] values,
		@Nonnull long[] payloads,
		@Nonnull Map<Integer, Locale> localeIndex,
		@Nullable Long storagePartPK
	) {
		this(scope, attributeKey, type, values, payloads, localeIndex, false, -1, ArrayUtils.EMPTY_INT_ARRAY, storagePartPK);
	}

	/**
	 * Builds a `PAGED` root: the value-to-tuple entries live in {@link GlobalUniqueIndexLeafPagePart} leaf pages, so the
	 * root carries the explicit high-water `pageSequence`, the ordered leaf-page list (ascending key order) and the
	 * INLINE locale map — but NO inline value map and NO page-stream id (it is recomputed at load from the
	 * `(scope, attributeKey)` identity — see {@link #paged}).
	 *
	 * @param scope                 scope of the owning catalog index
	 * @param attributeKey          the indexed attribute key
	 * @param type                  the indexed value type
	 * @param highWaterPageSequence the maximum `pageSequence` ever allocated for the stream
	 * @param leafPageSequences     the leaf pages in ascending key order
	 * @param localeIndex           the inline internal locale-id map (stays on the root)
	 * @param storagePartPK         the already-assigned storage part PK, or `null`
	 * @return the paged global unique index storage part
	 */
	@Nonnull
	public static GlobalUniqueIndexStoragePart paged(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> type,
		int highWaterPageSequence,
		@Nonnull int[] leafPageSequences,
		@Nonnull Map<Integer, Locale> localeIndex,
		@Nullable Long storagePartPK
	) {
		return new GlobalUniqueIndexStoragePart(
			scope, attributeKey, type, null, null, localeIndex, true, highWaterPageSequence, leafPageSequences, storagePartPK
		);
	}

	/**
	 * Canonical constructor carrying every field — the optional inline value/payload columns, the inline locale map, the
	 * `PAGED`/`SINGLE` page metadata and the already-assigned storage part PK.
	 */
	private GlobalUniqueIndexStoragePart(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> type,
		@Nullable Serializable[] values,
		@Nullable long[] payloads,
		@Nonnull Map<Integer, Locale> localeIndex,
		boolean paged,
		int highWaterPageSequence,
		@Nonnull int[] leafPageSequences,
		@Nullable Long storagePartPK
	) {
		this.scope = scope;
		this.attributeKey = attributeKey;
		this.type = type;
		this.values = values;
		this.payloads = payloads;
		this.localeIndex = localeIndex;
		this.paged = paged;
		this.highWaterPageSequence = highWaterPageSequence;
		this.leafPageSequences = leafPageSequences;
		this.storagePartPK = storagePartPK;
	}

	/**
	 * Method computes unique part id as long, that composes of integer primary key of the {@link io.evitadb.index.EntityIndex}
	 * attributes belong to and compressed attribute key integer that is assigned as soon as attribute is first stored.
	 */
	public static long computeUniquePartId(@Nonnull Scope scope, @Nonnull AttributeKey attributeKey, @Nonnull KeyCompressor keyCompressor) {
		return NumberUtils.pack(scope.ordinal(), keyCompressor.getId(attributeKey));
	}

	/**
	 * Method computes `uniquePartId` for the current container using {@link KeyCompressor} in the parameter and sets
	 * the uniquePartId to local container so that it doesn't need to be computed again.
	 */
	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		final long computedUniquePartId = computeUniquePartId(getScope(), getAttributeKey(), keyCompressor);
		final Long uniquePartId = getStoragePartPK();
		if (uniquePartId == null) {
			setStoragePartPK(computedUniquePartId);
		} else {
			Assert.isTrue(uniquePartId == computedUniquePartId, "Unique part ids must never differ!");
		}
		return computedUniquePartId;
	}

	@Override
	public AttributeKey getStoragePartSourceKey() {
		return this.attributeKey;
	}

}
