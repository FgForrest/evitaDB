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
import io.evitadb.spi.store.catalog.persistence.storageParts.RecordWithCompressedId;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Persists the root of a {@link io.evitadb.index.VirtualReferencePartitionIndex}: its slot-keyed entries and its
 * representative tuple dictionary.
 *
 * The entries are carried in one of two shapes. A registry that fits one bucket-tree leaf is written inline on this
 * root as positionally-aligned `(keys, payloads)` columns (the SINGLE shape). A larger one is paged out as individual
 * {@link VirtualReferencePartitionIndexLeafPagePart} records, and this root then holds only the page-stream high-water
 * and the ordered live leaf-page list (the PAGED shape).
 *
 * The tuple dictionary rides inline on the root in both shapes, as positionally-aligned `(tupleIds, tuples)` columns
 * in ascending id order. It holds only the tuples and their ids; how many partitions share each tuple is derived from
 * the entries on load, so a partition switching between existing tuples never rewrites this root.
 *
 * The primary key is `pack(entityIndexPrimaryKey, id(ReferenceNameKey))` - the same number the reference-type
 * cardinality root of the same entity index carries, which cannot collide with it, because the offset index keys
 * every record by its record type as well.
 *
 * Only an index holding at least one entry is ever persisted: an empty one has no root at all, and the loader reads
 * an absent root as an empty index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ToString(of = {"entityIndexPrimaryKey", "referenceName", "paged"})
public class VirtualReferencePartitionIndexStoragePart implements StoragePart, RecordWithCompressedId<String> {
	@Serial private static final long serialVersionUID = -4582829681021372925L;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex}.
	 */
	@Getter private final int entityIndexPrimaryKey;
	/**
	 * Name of the reference the index belongs to.
	 */
	@Nonnull @Getter private final String referenceName;
	/**
	 * Whether the entries are paged out as leaf-page records (`true`) or carried inline on this root (`false`).
	 */
	@Getter private final boolean paged;
	/**
	 * SINGLE shape only: the `pack(slotId, partitionPrimaryKey)` keys in ascending order, positionally aligned with
	 * {@link #payloads}; `null` for a PAGED root.
	 */
	@Nullable @Getter private final long[] keys;
	/**
	 * SINGLE shape only: the payload of each key, positionally aligned with {@link #keys}; `null` for a PAGED root.
	 */
	@Nullable @Getter private final long[] payloads;
	/**
	 * PAGED shape only: the largest page sequence ever allocated for the entry page stream; `0` for a SINGLE root.
	 */
	@Getter private final int highWaterPageSequence;
	/**
	 * PAGED shape only: every live leaf's page sequence in ascending key order; `null` for a SINGLE root.
	 */
	@Nullable @Getter private final int[] leafPageSequences;
	/**
	 * The ids of the dictionary tuples in ascending order, positionally aligned with {@link #tuples}.
	 */
	@Nonnull @Getter private final int[] tupleIds;
	/**
	 * The representative attribute values of each dictionary tuple, positionally aligned with {@link #tupleIds}.
	 */
	@Nonnull @Getter private final Serializable[][] tuples;
	/**
	 * Id used for lookups in file offset index for this particular container.
	 */
	@Nullable @Getter @Setter private Long storagePartPK;

	/**
	 * Canonical all-fields constructor.
	 */
	private VirtualReferencePartitionIndexStoragePart(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName,
		boolean paged,
		@Nullable long[] keys,
		@Nullable long[] payloads,
		int highWaterPageSequence,
		@Nullable int[] leafPageSequences,
		@Nonnull int[] tupleIds,
		@Nonnull Serializable[][] tuples,
		@Nullable Long storagePartPK
	) {
		Assert.isPremiseValid(
			tupleIds.length == tuples.length, "Tuple ids and tuples must be positionally aligned!"
		);
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.referenceName = referenceName;
		this.paged = paged;
		this.keys = keys;
		this.payloads = payloads;
		this.highWaterPageSequence = highWaterPageSequence;
		this.leafPageSequences = leafPageSequences;
		this.tupleIds = tupleIds;
		this.tuples = tuples;
		this.storagePartPK = storagePartPK;
	}

	/**
	 * Creates a SINGLE root carrying the entries inline.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param referenceName         the reference name of the index
	 * @param keys                  the entry keys in ascending order
	 * @param payloads              the entry payloads, aligned with `keys`
	 * @param tupleIds              the dictionary tuple ids in ascending order
	 * @param tuples                the dictionary tuples, aligned with `tupleIds`
	 * @param storagePartPK         the already-known primary key, or `null` on the write path
	 * @return the SINGLE root storage part
	 */
	@Nonnull
	public static VirtualReferencePartitionIndexStoragePart single(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName,
		@Nonnull long[] keys,
		@Nonnull long[] payloads,
		@Nonnull int[] tupleIds,
		@Nonnull Serializable[][] tuples,
		@Nullable Long storagePartPK
	) {
		Assert.isPremiseValid(keys.length == payloads.length, "Keys and payloads must be positionally aligned!");
		return new VirtualReferencePartitionIndexStoragePart(
			entityIndexPrimaryKey, referenceName, false, keys, payloads, 0, null, tupleIds, tuples, storagePartPK
		);
	}

	/**
	 * Creates a PAGED root carrying the page-stream metadata; the entries live in separate
	 * {@link VirtualReferencePartitionIndexLeafPagePart} records.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param referenceName         the reference name of the index
	 * @param highWaterPageSequence the largest page sequence ever allocated for the stream
	 * @param leafPageSequences     every live leaf's page sequence in ascending key order
	 * @param tupleIds              the dictionary tuple ids in ascending order
	 * @param tuples                the dictionary tuples, aligned with `tupleIds`
	 * @param storagePartPK         the already-known primary key, or `null` on the write path
	 * @return the PAGED root storage part
	 */
	@Nonnull
	public static VirtualReferencePartitionIndexStoragePart paged(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName,
		int highWaterPageSequence,
		@Nonnull int[] leafPageSequences,
		@Nonnull int[] tupleIds,
		@Nonnull Serializable[][] tuples,
		@Nullable Long storagePartPK
	) {
		return new VirtualReferencePartitionIndexStoragePart(
			entityIndexPrimaryKey, referenceName, true, null, null,
			highWaterPageSequence, leafPageSequences, tupleIds, tuples, storagePartPK
		);
	}

	/**
	 * Computes the primary key of the root of the index of the given reference in the given entity index.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param referenceNameId       the key-compressor id of the reference name
	 * @return the root's primary key
	 */
	public static long computeUniquePartId(int entityIndexPrimaryKey, int referenceNameId) {
		return NumberUtils.pack(entityIndexPrimaryKey, referenceNameId);
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		final long computedUniquePartId = computeUniquePartId(
			this.entityIndexPrimaryKey, keyCompressor.getId(new ReferenceNameKey(this.referenceName))
		);
		Assert.isPremiseValid(
			this.storagePartPK == null || this.storagePartPK == computedUniquePartId,
			() -> "The storage part id was already set to different value!"
		);
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

	@Override
	public String getStoragePartSourceKey() {
		return this.referenceName;
	}

}
