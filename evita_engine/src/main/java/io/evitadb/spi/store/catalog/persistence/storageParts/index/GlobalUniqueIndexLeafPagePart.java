/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import io.evitadb.dataType.Scope;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * One persisted leaf page of a granular catalog-level {@link io.evitadb.index.attribute.GlobalUniqueIndex} bucket tree.
 * Under the tree-as-pages layout each leaf of the {@code TransactionalBucketBPlusTree} backing a
 * {@link io.evitadb.index.attribute.GlobalUniqueIndex} is stored as its own record, so a transaction (re)writes only the
 * leaf pages it actually changed instead of re-materializing the whole value-to-entity-tuple map.
 *
 * A unique value maps to exactly one entity tuple — a packed `long` payload (an
 * {@link io.evitadb.index.attribute.GlobalUniqueIndex.EntityWithTypeTuple} folded into `locale:16 | entityType:16 | pk:32`)
 * — so this page stores the slimmest possible payload: two positionally-aligned columns, the values (in ascending key
 * order) and their single `long` payloads. This mirrors {@link UniqueIndexLeafPagePart} but carries a `long[]` payload
 * column rather than an `int[]` record-id column.
 *
 * The `(streamId, pageSequence)` identity, primary-key packing and two-phase stream-id resolution are inherited from
 * {@link AbstractLeafPagePart}. This page's `streamId` is the {@link KeyCompressor} id of the sub-index's
 * {@link GlobalUniqueLeafStreamKey} (one dictionary entry per persisted global-unique sub-index, distinguished by
 * `(scope, attributeKey)`).
 *
 * Mirroring {@link UniqueIndexLeafPagePart}: a write-path page carries the sub-index `(scope, attributeKey)` identity and
 * resolves (and caches) `streamId` store-side in {@link #resolveStreamId} (the engine that emits the page has no
 * compressor); a read-path page (rehydrated by the serializer) carries the already-known `streamId` and PK and leaves the
 * identity null.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class GlobalUniqueIndexLeafPagePart extends AbstractLeafPagePart {
	@Serial private static final long serialVersionUID = -4827619035481276590L;

	/**
	 * Scope of the owning {@link io.evitadb.index.CatalogIndex} — write-path identity used to resolve the stream id
	 * store-side; `null` on a rehydrated (read-path) page.
	 */
	@Nullable @Getter private final Scope scope;
	/**
	 * The attribute identity of the sub-index — write-path identity used to resolve the stream id store-side; `null`
	 * on a rehydrated (read-path) page.
	 */
	@Nullable @Getter private final AttributeKey attributeKey;
	/**
	 * The leaf's values in ascending key order, positionally aligned with {@link #payloads}.
	 */
	@Nonnull @Getter private final Serializable[] values;
	/**
	 * The single packed `long` entity-tuple payload owning each value, positionally aligned with {@link #values}.
	 */
	@Nonnull @Getter private final long[] payloads;

	/**
	 * Computes the storage-part primary key for a leaf page from its resolved identifying pair. Retained for callers that
	 * address it through this concrete type; it delegates to {@link AbstractLeafPagePart#computeUniquePartId} via
	 * {@link NumberUtils#pack}.
	 *
	 * @param streamId     the resolved stream id
	 * @param pageSequence the page sequence within the stream
	 * @return the 64-bit storage-part primary key
	 */
	public static long computeUniquePartId(int streamId, int pageSequence) {
		return NumberUtils.pack(streamId, pageSequence);
	}

	/**
	 * Creates a WRITE-PATH leaf page carrying the sub-index identity; its `streamId` and primary key are resolved
	 * store-side on first {@link #computeUniquePartIdAndSet(KeyCompressor)}.
	 *
	 * @param scope        scope of the owning catalog index
	 * @param attributeKey the attribute identity of the sub-index
	 * @param pageSequence the page sequence within the stream
	 * @param values       the leaf's values in ascending key order
	 * @param payloads     the single packed `long` payload owning each value, aligned with `values`
	 */
	public GlobalUniqueIndexLeafPagePart(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		int pageSequence,
		@Nonnull Serializable[] values,
		@Nonnull long[] payloads
	) {
		super(pageSequence);
		Assert.isPremiseValid(values.length == payloads.length, "Values and payloads must be positionally aligned!");
		this.scope = scope;
		this.attributeKey = attributeKey;
		this.values = values;
		this.payloads = payloads;
	}

	/**
	 * Creates a READ-PATH leaf page with an already-resolved `streamId` and primary key (used when rehydrating from
	 * storage); the write-path identity is left null.
	 *
	 * @param streamId      the resolved stream id
	 * @param pageSequence  the page sequence within the stream
	 * @param values        the leaf's values in ascending key order
	 * @param payloads      the single packed `long` payload owning each value, aligned with `values`
	 * @param storagePartPK the precomputed primary key
	 */
	public GlobalUniqueIndexLeafPagePart(
		int streamId,
		int pageSequence,
		@Nonnull Serializable[] values,
		@Nonnull long[] payloads,
		@Nonnull Long storagePartPK
	) {
		super(streamId, pageSequence, storagePartPK);
		Assert.isPremiseValid(values.length == payloads.length, "Values and payloads must be positionally aligned!");
		this.scope = null;
		this.attributeKey = null;
		this.values = values;
		this.payloads = payloads;
	}

	@Override
	protected int resolveStreamId(@Nonnull KeyCompressor keyCompressor) {
		// write path: resolve the stream id from the sub-index identity via the writable compressor
		Assert.isPremiseValid(
			this.scope != null && this.attributeKey != null,
			"A leaf page must carry its sub-index identity to resolve the stream id!"
		);
		return keyCompressor.getId(new GlobalUniqueLeafStreamKey(this.scope, this.attributeKey));
	}
}
