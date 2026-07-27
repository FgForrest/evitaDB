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

import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * One persisted leaf page of a granular {@link io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex} bucket tree.
 * Under the tree-as-pages layout each leaf of the `LongPayloadBucketTree` backing the cardinality member of a
 * {@link io.evitadb.index.ReferencedTypeEntityIndex} is stored as its own record, so a transaction (re)writes only the
 * leaf pages it actually changed instead of re-materializing the whole composed-key → count map (the second-largest churn wall at
 * scale).
 *
 * A composed signed `long` key maps to exactly one `long` count (the cardinality widened to `long`, read back verbatim —
 * no bit-packing), so this page stores the slimmest possible payload: two positionally-aligned primitive columns, the
 * keys (in ascending signed-long key order) and their counts. This mirrors {@link GlobalUniqueIndexLeafPagePart} but
 * carries a primitive `long[]` key column (the keys are composed longs, not heterogeneous attribute values) rather than a
 * Kryo-serialized `Serializable[]` value column.
 *
 * The `(streamId, pageSequence)` identity, primary-key packing and two-phase stream-id resolution are inherited from
 * {@link AbstractLeafPagePart}. This page's `streamId` is the {@link KeyCompressor} id of the sub-index's
 * {@link ReferenceTypeCardinalityLeafStreamKey} (one dictionary entry per persisted cardinality sub-index, distinguished
 * by `(entityIndexPrimaryKey, referenceName)`).
 *
 * Mirroring {@link GlobalUniqueIndexLeafPagePart}: a write-path page carries the sub-index
 * `(entityIndexPrimaryKey, referenceName)` identity and resolves (and caches) `streamId` store-side in
 * {@link #resolveStreamId} (the engine that emits the page has no compressor); a read-path page (rehydrated by the
 * serializer) carries the already-known `streamId` and PK and leaves the identity null.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ReferenceTypeCardinalityIndexLeafPagePart extends AbstractLeafPagePart {
	@Serial private static final long serialVersionUID = -2734061857204619833L;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex} — write-path identity used to resolve the stream id
	 * store-side; `null` on a rehydrated (read-path) page.
	 */
	@Nullable @Getter private final Integer entityIndexPrimaryKey;
	/**
	 * The reference name of the sub-index — write-path identity used to resolve the stream id store-side; `null` on a
	 * rehydrated (read-path) page.
	 */
	@Nullable @Getter private final String referenceName;
	/**
	 * The leaf's composed signed `long` keys in ascending key order, positionally aligned with {@link #payloads}.
	 */
	@Nonnull @Getter private final long[] keys;
	/**
	 * The cardinality count (widened to `long`) owning each key, positionally aligned with {@link #keys}.
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
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param referenceName         the reference name of the sub-index
	 * @param pageSequence          the page sequence within the stream
	 * @param keys                  the leaf's composed signed `long` keys in ascending key order
	 * @param payloads              the cardinality count owning each key, aligned with `keys`
	 */
	public ReferenceTypeCardinalityIndexLeafPagePart(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName,
		int pageSequence,
		@Nonnull long[] keys,
		@Nonnull long[] payloads
	) {
		super(pageSequence);
		Assert.isPremiseValid(keys.length == payloads.length, "Keys and payloads must be positionally aligned!");
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.referenceName = referenceName;
		this.keys = keys;
		this.payloads = payloads;
	}

	/**
	 * Creates a READ-PATH leaf page with an already-resolved `streamId` and primary key (used when rehydrating from
	 * storage); the write-path identity is left null.
	 *
	 * @param streamId      the resolved stream id
	 * @param pageSequence  the page sequence within the stream
	 * @param keys          the leaf's composed signed `long` keys in ascending key order
	 * @param payloads      the cardinality count owning each key, aligned with `keys`
	 * @param storagePartPK the precomputed primary key
	 */
	public ReferenceTypeCardinalityIndexLeafPagePart(
		int streamId,
		int pageSequence,
		@Nonnull long[] keys,
		@Nonnull long[] payloads,
		@Nonnull Long storagePartPK
	) {
		super(streamId, pageSequence, storagePartPK);
		Assert.isPremiseValid(keys.length == payloads.length, "Keys and payloads must be positionally aligned!");
		this.entityIndexPrimaryKey = null;
		this.referenceName = null;
		this.keys = keys;
		this.payloads = payloads;
	}

	@Override
	protected int resolveStreamId(@Nonnull KeyCompressor keyCompressor) {
		// write path: resolve the stream id from the sub-index identity via the writable compressor
		Assert.isPremiseValid(
			this.entityIndexPrimaryKey != null && this.referenceName != null,
			"A leaf page must carry its sub-index identity to resolve the stream id!"
		);
		return keyCompressor.getId(
			new ReferenceTypeCardinalityLeafStreamKey(this.entityIndexPrimaryKey, this.referenceName)
		);
	}
}
