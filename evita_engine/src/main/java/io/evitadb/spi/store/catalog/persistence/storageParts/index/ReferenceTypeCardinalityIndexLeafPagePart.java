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
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import lombok.Setter;

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
 * Identity is the pair `(streamId, pageSequence)`, packed into the storage-part primary key via {@link NumberUtils#pack}.
 * `streamId` is the {@link KeyCompressor} id of the sub-index's {@link ReferenceTypeCardinalityLeafStreamKey} (one
 * dictionary entry per persisted cardinality sub-index, distinguished by `(entityIndexPrimaryKey, referenceName)`);
 * `pageSequence` is the advance-only, never-reused page sequence within that stream.
 *
 * Mirroring {@link GlobalUniqueIndexLeafPagePart}: a write-path page carries the sub-index
 * `(entityIndexPrimaryKey, referenceName)` identity and resolves (and caches) `streamId` store-side in
 * {@link #computeUniquePartIdAndSet} (the engine that emits the page has no compressor); a read-path page (rehydrated by
 * the serializer) carries the already-known `streamId` and PK and leaves the identity null/zero.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ReferenceTypeCardinalityIndexLeafPagePart implements StoragePart {
	@Serial private static final long serialVersionUID = -2734061857204619833L;

	/**
	 * Sentinel for a `streamId` not yet resolved (a write-path page before {@link #computeUniquePartIdAndSet}).
	 */
	public static final int UNRESOLVED_STREAM_ID = -1;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex} — write-path identity used to resolve
	 * {@link #streamId} store-side; `null` on a rehydrated (read-path) page.
	 */
	@Nullable @Getter private final Integer entityIndexPrimaryKey;
	/**
	 * The reference name of the sub-index — write-path identity used to resolve {@link #streamId} store-side; `null` on a
	 * rehydrated (read-path) page.
	 */
	@Nullable @Getter private final String referenceName;
	/**
	 * The {@link KeyCompressor} id of the sub-index stream this page belongs to. {@link #UNRESOLVED_STREAM_ID} on a
	 * write-path page until {@link #computeUniquePartIdAndSet} resolves it from the identity; already known on a
	 * rehydrated (read-path) page.
	 */
	@Getter private int streamId;
	/**
	 * The advance-only, never-reused page sequence of this leaf within its stream.
	 */
	@Getter private final int pageSequence;
	/**
	 * The leaf's composed signed `long` keys in ascending key order, positionally aligned with {@link #payloads}.
	 */
	@Nonnull @Getter private final long[] keys;
	/**
	 * The cardinality count (widened to `long`) owning each key, positionally aligned with {@link #keys}.
	 */
	@Nonnull @Getter private final long[] payloads;
	/**
	 * The storage-part primary key `pack(streamId, pageSequence)`; `null` until assigned by
	 * {@link #computeUniquePartIdAndSet(KeyCompressor)} (write path) or supplied at rehydration (read path).
	 */
	@Nullable @Getter @Setter private Long storagePartPK;

	/**
	 * Computes the storage-part primary key for a leaf page from its resolved identifying pair.
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
		Assert.isPremiseValid(keys.length == payloads.length, "Keys and payloads must be positionally aligned!");
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.referenceName = referenceName;
		this.streamId = UNRESOLVED_STREAM_ID;
		this.pageSequence = pageSequence;
		this.keys = keys;
		this.payloads = payloads;
		this.storagePartPK = null;
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
		Assert.isPremiseValid(keys.length == payloads.length, "Keys and payloads must be positionally aligned!");
		this.entityIndexPrimaryKey = null;
		this.referenceName = null;
		this.streamId = streamId;
		this.pageSequence = pageSequence;
		this.keys = keys;
		this.payloads = payloads;
		this.storagePartPK = storagePartPK;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		if (this.streamId == UNRESOLVED_STREAM_ID) {
			// write path: resolve the stream id from the sub-index identity via the writable compressor (allocates a
			// dictionary entry on the first PAGED write of this sub-index, returns the stable id thereafter)
			Assert.isPremiseValid(
				this.entityIndexPrimaryKey != null && this.referenceName != null,
				"A leaf page must carry its sub-index identity to resolve the stream id!"
			);
			this.streamId = keyCompressor.getId(
				new ReferenceTypeCardinalityLeafStreamKey(this.entityIndexPrimaryKey, this.referenceName)
			);
		}
		final long computedUniquePartId = computeUniquePartId(this.streamId, this.pageSequence);
		if (this.storagePartPK == null) {
			this.storagePartPK = computedUniquePartId;
		} else {
			Assert.isTrue(this.storagePartPK == computedUniquePartId, "Unique part ids must never differ!");
		}
		return computedUniquePartId;
	}
}
