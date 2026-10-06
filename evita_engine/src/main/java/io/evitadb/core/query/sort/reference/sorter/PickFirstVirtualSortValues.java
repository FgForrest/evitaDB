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

import io.evitadb.api.query.order.PickFirstByEntityProperty;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.function.IntPredicate;

/**
 * The sorted value of the virtual partitions a {@link PickFirstByEntityProperty} ordering claims - partitions read
 * through the registry of single-owner partitions ({@link VirtualReferencePartitionIndex}) rather than through a
 * reduced entity index with a sort index of its own. {@link PickFirstReferenceSorter} asks for the value of a
 * virtual partition only once its owner has been admitted, groups the admitted owners by value id and orders the
 * groups through this interface, so it never touches a sort index of the registry except in the dense walk.
 *
 * Value ids are scoped to the registry of one referenced-type entity index - two scopes number their values
 * independently - which is why every value-level method names the type index the id came from.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface PickFirstVirtualSortValues {
	/**
	 * Returned by {@link #getValueId(VirtualReferencePartition)} for a partition whose row carries no sorted value.
	 */
	int NO_VALUE = VirtualReferencePartitionIndex.NO_SORT_VALUE_ID;

	/**
	 * Returns the values of a sortable attribute or sortable attribute compound of the reference, read out of the
	 * registry of the partition's referenced-type entity index under the sort key.
	 *
	 * @param sortKey the sort key - the attribute or compound with its locale, as the registry declares it
	 * @return the value source
	 */
	@Nonnull
	static PickFirstVirtualSortValues ofSortKey(@Nonnull AttributeIndexKey sortKey) {
		return new RegistrySortValues(sortKey);
	}

	/**
	 * Returns the values of the `entityPrimaryKeyNatural` ordering of the reference: the value of a partition is the
	 * primary key of its target, which is also its value id. There is no ordered walk over the values, so the virtual
	 * run is always prepared by sorting the claimed values.
	 *
	 * @return the value source
	 */
	@Nonnull
	static PickFirstVirtualSortValues ofReferencedPrimaryKey() {
		return ReferencedPrimaryKeyValues.INSTANCE;
	}

	/**
	 * Returns the id of the value the partition's row carries - `O(log n)` in the rows of the registry - or
	 * {@link #NO_VALUE} when it carries none.
	 *
	 * @param partition the virtual partition
	 * @return the value id or {@link #NO_VALUE}
	 */
	int getValueId(@Nonnull VirtualReferencePartition partition);

	/**
	 * Returns the value of an id the partition of the type index handed out, in the normalized form the value seekers
	 * of a sort index emit, so it compares against the values of the materialized runs with the same comparator.
	 *
	 * @param typeIndex the referenced-type entity index the id is scoped to
	 * @param valueId   the value id
	 * @return the normalized value
	 */
	@Nonnull
	Serializable getValue(@Nonnull ReferencedTypeEntityIndex typeIndex, int valueId);

	/**
	 * Returns the number of distinct values of the registry of the type index - the length of a full
	 * {@link #forEachValueId(ReferencedTypeEntityIndex, boolean, IntPredicate)} walk - or {@link Integer#MAX_VALUE}
	 * when the source offers no ordered walk.
	 *
	 * @param typeIndex the referenced-type entity index
	 * @return the number of distinct values
	 */
	int getDistinctValueCount(@Nonnull ReferencedTypeEntityIndex typeIndex);

	/**
	 * Walks the value ids of the registry of the type index in value order - ascending, or descending when asked -
	 * until the consumer declines to continue.
	 *
	 * @param typeIndex  the referenced-type entity index
	 * @param descending TRUE to walk from the largest value down
	 * @param consumer   receives the value ids in order, returns FALSE to stop
	 */
	void forEachValueId(
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		boolean descending,
		@Nonnull IntPredicate consumer
	);

	/**
	 * Values of one sort key of the registry.
	 *
	 * @param sortKey the sort key
	 */
	record RegistrySortValues(@Nonnull AttributeIndexKey sortKey) implements PickFirstVirtualSortValues {

		@Override
		public int getValueId(@Nonnull VirtualReferencePartition partition) {
			return partition.typeIndex().getVirtualReferencePartitionIndex()
				.getSortValueId(this.sortKey, partition.primaryKey());
		}

		@Nonnull
		@Override
		public Serializable getValue(@Nonnull ReferencedTypeEntityIndex typeIndex, int valueId) {
			return typeIndex.getVirtualReferencePartitionIndex().getNormalizedSortValue(this.sortKey, valueId);
		}

		@Override
		public int getDistinctValueCount(@Nonnull ReferencedTypeEntityIndex typeIndex) {
			return typeIndex.getVirtualReferencePartitionIndex().getDistinctSortValueCount(this.sortKey);
		}

		@Override
		public void forEachValueId(
			@Nonnull ReferencedTypeEntityIndex typeIndex,
			boolean descending,
			@Nonnull IntPredicate consumer
		) {
			typeIndex.getVirtualReferencePartitionIndex().forEachSortValueId(this.sortKey, descending, consumer);
		}

	}

	/**
	 * Values of the `entityPrimaryKeyNatural` ordering - the target primary key of every row.
	 */
	final class ReferencedPrimaryKeyValues implements PickFirstVirtualSortValues {
		/**
		 * The stateless instance.
		 */
		static final ReferencedPrimaryKeyValues INSTANCE = new ReferencedPrimaryKeyValues();

		private ReferencedPrimaryKeyValues() {
			// use INSTANCE
		}

		@Override
		public int getValueId(@Nonnull VirtualReferencePartition partition) {
			return partition.getReferenceKey().primaryKey();
		}

		@Nonnull
		@Override
		public Serializable getValue(@Nonnull ReferencedTypeEntityIndex typeIndex, int valueId) {
			return valueId;
		}

		@Override
		public int getDistinctValueCount(@Nonnull ReferencedTypeEntityIndex typeIndex) {
			return Integer.MAX_VALUE;
		}

		@Override
		public void forEachValueId(
			@Nonnull ReferencedTypeEntityIndex typeIndex,
			boolean descending,
			@Nonnull IntPredicate consumer
		) {
			throw new GenericEvitaInternalError(
				"The referenced primary key order has no ordered walk - its virtual run is always sorted."
			);
		}

	}

}
