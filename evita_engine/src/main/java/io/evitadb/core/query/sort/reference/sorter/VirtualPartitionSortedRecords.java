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


package io.evitadb.core.query.sort.reference.sorter;

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.attribute.ReferenceSortedRecordsProvider;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.OptionalInt;

/**
 * The sorted records of one virtual partition - a partition read through the registry of single-owner partitions
 * ({@link VirtualReferencePartitionIndex}) rather than through a reduced entity index - for the orderings that sort the
 * partitions of a reference block by block: `traverseByEntityProperty`, the chains of `Predecessor` and
 * `ReferencedEntityPredecessor` attributes and the `referenceContent(orderBy)` chains.
 *
 * A single-owner partition holds one row, so each of its sort and chain indexes would be a singleton: one record at
 * position `0`. The providers built here are exactly that singleton, read out of the partition's registry row with one
 * or two `O(log n)` lookups - never through a walk of the registry's sort index of the key, which would cost the
 * whole family of the reference per partition. A partition whose row carries no value (no predecessor) for the key
 * has no provider, as a reduced entity index without the record in its sort (chain) index contributes nothing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class VirtualPartitionSortedRecords {
	/**
	 * The position of the only record of a singleton provider.
	 */
	private static final int[] SINGLE_POSITION = {0};

	private VirtualPartitionSortedRecords() {
		// static helpers only
	}

	/**
	 * Returns the singleton provider of the partition's sort value of a sort key - the counterpart of the provider of
	 * the partition's own sort index of the attribute or compound. The record is the owner, its value the normalized
	 * value the registry holds under the partition's value id. Two `O(log n)` registry lookups at most.
	 *
	 * @param partition the virtual partition
	 * @param sortKey   the sort key - the attribute or compound with its locale
	 * @return the provider, or `null` when the partition holds no value for the key
	 */
	@Nullable
	public static SortedRecordsProvider ofSortValue(
		@Nonnull VirtualReferencePartition partition,
		@Nonnull AttributeIndexKey sortKey
	) {
		final VirtualReferencePartitionIndex registry = partition.typeIndex().getVirtualReferencePartitionIndex();
		final int valueId = registry.getSortValueId(sortKey, partition.primaryKey());
		if (valueId == VirtualReferencePartitionIndex.NO_SORT_VALUE_ID) {
			return null;
		}
		final Serializable value = registry.getNormalizedSortValue(sortKey, valueId);
		return singleton(partition, partition.ownerPrimaryKey(), value);
	}

	/**
	 * Returns the records of the partition whose sort value of a sort key equals the given value - the counterpart of
	 * {@link io.evitadb.index.attribute.SortIndex#getRecordsEqualTo(Serializable)} on the partition's own sort index:
	 * the owner when its value equals, nothing otherwise. The partition's own row is read and its value compared with
	 * the given one normalized as the key's sort index normalizes it, in the equality the buckets of that sort index
	 * follow (see {@link VirtualReferencePartitionIndex#hasSortValue(AttributeIndexKey, int, Serializable)}) - two
	 * `O(log n)` lookups of the row. The value bucket of the sort index is never read: it holds every partition of the
	 * family sharing the value.
	 *
	 * @param partition the virtual partition
	 * @param sortKey   the sort key - the attribute with its locale
	 * @param value     the value to compare with
	 * @return the owner or an empty bitmap
	 */
	@Nonnull
	public static Bitmap getRecordsEqualTo(
		@Nonnull VirtualReferencePartition partition,
		@Nonnull AttributeIndexKey sortKey,
		@Nonnull Serializable value
	) {
		final VirtualReferencePartitionIndex registry = partition.typeIndex().getVirtualReferencePartitionIndex();
		final int partitionPrimaryKey = partition.primaryKey();
		// the first test answers FALSE for a key that is not declared, which the value comparison refuses
		return registry.hasSortValue(sortKey, partitionPrimaryKey) &&
			registry.hasSortValue(sortKey, partitionPrimaryKey, value) ?
			new BaseBitmap(partition.ownerPrimaryKey()) : EmptyBitmap.INSTANCE;
	}

	/**
	 * Returns the singleton provider of the partition's chain of a chain key - the counterpart of the provider of the
	 * partition's own chain index, which holds the partition's only row at position `0`. The record is the one the
	 * chain index of the partition is keyed by: the referenced entity for a `ReferencedEntityPredecessor` of the
	 * collection's own reference, the owner otherwise. One `O(log n)` registry lookup.
	 *
	 * @param partition               the virtual partition
	 * @param chainKey                the chain key - the chained attribute with its locale
	 * @param keyedByReferencedEntity TRUE when the chain is keyed by the referenced entity
	 * @return the provider, or `null` when the partition is not chained under the key
	 */
	@Nullable
	public static SortedRecordsProvider ofChain(
		@Nonnull VirtualReferencePartition partition,
		@Nonnull AttributeIndexKey chainKey,
		boolean keyedByReferencedEntity
	) {
		final VirtualReferencePartitionIndex registry = partition.typeIndex().getVirtualReferencePartitionIndex();
		if (!registry.isChainKeyDeclared(chainKey)) {
			return null;
		}
		final OptionalInt predecessor = registry.getChainPredecessor(chainKey, partition.primaryKey());
		if (predecessor.isEmpty()) {
			return null;
		}
		final int recordId = keyedByReferencedEntity ?
			partition.getReferenceKey().primaryKey() : partition.ownerPrimaryKey();
		return singleton(partition, recordId, recordId);
	}

	/**
	 * Returns the factory of the singleton chain provider of the partition - see
	 * {@link #ofChain(VirtualReferencePartition, AttributeIndexKey, boolean)}. A singleton reads the same in both
	 * directions, so both directions answer one provider.
	 *
	 * @param partition               the virtual partition
	 * @param chainKey                the chain key - the chained attribute with its locale
	 * @param keyedByReferencedEntity TRUE when the chain is keyed by the referenced entity
	 * @return the factory, or `null` when the partition is not chained under the key
	 */
	@Nullable
	public static SortedRecordsSupplierFactory ofChainFactory(
		@Nonnull VirtualReferencePartition partition,
		@Nonnull AttributeIndexKey chainKey,
		boolean keyedByReferencedEntity
	) {
		final SortedRecordsProvider provider = ofChain(partition, chainKey, keyedByReferencedEntity);
		return provider == null ? null : new SingletonSortedRecordsSupplierFactory(provider);
	}

	/**
	 * Returns the singleton provider of the partition ordered by the primary key of its referenced entity - the
	 * counterpart of the provider a reduced entity index builds over its owners for `entityPrimaryKeyNatural`. No
	 * registry lookup: the handle carries both the owner and the target.
	 *
	 * @param partition the virtual partition
	 * @return the provider
	 */
	@Nonnull
	public static SortedRecordsProvider ofReferencedPrimaryKey(@Nonnull VirtualReferencePartition partition) {
		return singleton(partition, partition.ownerPrimaryKey(), partition.getReferenceKey().primaryKey());
	}

	/**
	 * Creates the singleton provider of one record carrying the partition's representative reference key, so the
	 * merge of a reference block finds it among the providers of the block.
	 *
	 * @param partition the virtual partition
	 * @param recordId  the only record
	 * @param value     the value of the record the provider's seeker answers
	 * @return the provider
	 */
	@Nonnull
	private static SortedRecordsProvider singleton(
		@Nonnull VirtualReferencePartition partition,
		int recordId,
		@Nonnull Serializable value
	) {
		final RepresentativeReferenceKey representativeReferenceKey = partition.getRepresentativeReferenceKey();
		return new ReferenceSortedRecordsProvider(
			partition.primaryKey(), new int[]{recordId}, SINGLE_POSITION, new BaseBitmap(recordId),
			position -> value, representativeReferenceKey
		);
	}

	/**
	 * The factory of a singleton provider, which reads the same in both directions.
	 *
	 * @param provider the singleton provider
	 */
	private record SingletonSortedRecordsSupplierFactory(
		@Nonnull SortedRecordsProvider provider
	) implements SortedRecordsSupplierFactory {

		@Nonnull
		@Override
		public SortedRecordsProvider getAscendingOrderRecordsSupplier() {
			return this.provider;
		}

		@Nonnull
		@Override
		public SortedRecordsProvider getDescendingOrderRecordsSupplier() {
			return this.provider;
		}

	}

}
