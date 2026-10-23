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

package io.evitadb.index;

import io.evitadb.index.VirtualReferencePartitionIndex.PartitionOwnerCursor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the entries of a {@link VirtualReferencePartitionIndex} - owner rows, representative tuples, sort values and
 * chain predecessors - through its public read surface, for tests asserting that a write left exactly the entries it
 * should. The dictionaries and the declared keys are not entries.
 *
 * The owner rows are walked with the cursor the read resolution walks them with
 * ({@link VirtualReferencePartitionIndex#seekPartitionOwner(int)}), so every one of them is read. Every other entry
 * is read by the primary key of its partition, so it is read for every partition an owner row names and for every
 * partition the caller probes in addition. A test proving that a partition left nothing behind probes the partition,
 * because an entry of a partition no owner row names would otherwise go unread - the index has no reading that walks
 * such entries, and a reload of the index does not reject them either (its load checks the dictionaries against the
 * entries, never an entry against an owner row). The values of a key that is no longer declared cannot be read at
 * all - {@link VirtualReferencePartitionIndex#isEmpty()} is the reading that sees them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class VirtualReferencePartitionIndexEntries {

	private VirtualReferencePartitionIndexEntries() {
		throw new UnsupportedOperationException("This class cannot be instantiated!");
	}

	/**
	 * Visits every owner row of the index in ascending partition primary key order.
	 *
	 * @param index    the index
	 * @param consumer invoked with the partition primary key and the row's `pack(ownerPrimaryKey, targetPrimaryKey)`
	 */
	public static void forEachOwnerRow(
		@Nonnull VirtualReferencePartitionIndex index,
		@Nonnull PartitionEntryConsumer consumer
	) {
		final PartitionOwnerCursor cursor = index.seekPartitionOwner(0);
		while (cursor.next()) {
			consumer.accept(cursor.partitionPrimaryKey(), cursor.ownerAndTarget());
		}
	}

	/**
	 * Visits the representative tuple of every partition an owner row names or the caller probes, in ascending
	 * partition primary key order - see the class documentation for the partitions it cannot see.
	 *
	 * @param index                      the index
	 * @param consumer                   invoked with the partition primary key and the tuple id of its tuple
	 * @param probedPartitionPrimaryKeys partitions whose tuple is read even when no owner row names them
	 */
	public static void forEachRepresentativeTuple(
		@Nonnull VirtualReferencePartitionIndex index,
		@Nonnull PartitionEntryConsumer consumer,
		@Nonnull int... probedPartitionPrimaryKeys
	) {
		for (final int partitionPrimaryKey : partitionsOf(index, probedPartitionPrimaryKeys)) {
			final int tupleId = index.getRepresentativeTupleId(partitionPrimaryKey);
			if (tupleId != VirtualReferencePartitionIndex.NO_TUPLE_ID) {
				consumer.accept(partitionPrimaryKey, tupleId);
			}
		}
	}

	/**
	 * Visits the distinct values of a declared sort key in the order of the key's sort index - the order
	 * {@link VirtualReferencePartitionIndex#forEachSortValueId} walks them in - each with the partitions holding it, in
	 * ascending order, among those an owner row names or the caller probes (see the class documentation). A value none
	 * of those partitions holds is still visited, with no partition, so a holder the walk cannot see shows up as an
	 * empty block rather than not at all. A key that is not declared visits nothing.
	 *
	 * @param index                      the index
	 * @param sortKey                    the sort key
	 * @param consumer                   invoked with each value, normalized as the registry stores it, and its holders
	 * @param probedPartitionPrimaryKeys partitions whose values are read even when no owner row names them
	 */
	public static void forEachSortValueBlock(
		@Nonnull VirtualReferencePartitionIndex index,
		@Nonnull AttributeIndexKey sortKey,
		@Nonnull SortValueBlockConsumer consumer,
		@Nonnull int... probedPartitionPrimaryKeys
	) {
		final Map<Integer, List<Integer>> holders = new HashMap<>();
		for (final int partitionPrimaryKey : partitionsOf(index, probedPartitionPrimaryKeys)) {
			final int valueId = index.getSortValueId(sortKey, partitionPrimaryKey);
			if (valueId != VirtualReferencePartitionIndex.NO_SORT_VALUE_ID) {
				holders.computeIfAbsent(valueId, __ -> new ArrayList<>(2)).add(partitionPrimaryKey);
			}
		}
		final List<Integer> valueIds = new ArrayList<>(holders.size());
		index.forEachSortValueId(sortKey, false, valueIds::add);
		assertTrue(
			valueIds.containsAll(holders.keySet()),
			"Every value a partition holds for `" + sortKey + "` must be among the values its sort index walks: " +
				holders.keySet() + " vs. " + valueIds
		);
		for (final Integer valueId : valueIds) {
			final List<Integer> partitions = holders.getOrDefault(valueId, List.of());
			consumer.accept(
				index.getNormalizedSortValue(sortKey, valueId),
				partitions.stream().mapToInt(Integer::intValue).toArray()
			);
		}
	}

	/**
	 * Counts the entries of the index - see the class documentation for which partitions they are counted for.
	 *
	 * @param index                      the index whose entries are counted
	 * @param probedPartitionPrimaryKeys partitions whose entries are counted even when no owner row names them
	 * @return the number of entries
	 */
	public static int count(@Nonnull VirtualReferencePartitionIndex index, @Nonnull int... probedPartitionPrimaryKeys) {
		final Set<AttributeIndexKey> sortKeys = index.getDeclaredSortKeys();
		final Set<AttributeIndexKey> chainKeys = index.getDeclaredChainKeys();
		final int[] count = {0};
		forEachOwnerRow(index, (partitionPrimaryKey, payload) -> count[0]++);
		for (final int partitionPrimaryKey : partitionsOf(index, probedPartitionPrimaryKeys)) {
			if (index.getRepresentativeTupleId(partitionPrimaryKey) != VirtualReferencePartitionIndex.NO_TUPLE_ID) {
				count[0]++;
			}
			for (final AttributeIndexKey sortKey : sortKeys) {
				if (index.hasSortValue(sortKey, partitionPrimaryKey)) {
					count[0]++;
				}
			}
			for (final AttributeIndexKey chainKey : chainKeys) {
				if (index.getChainPredecessor(chainKey, partitionPrimaryKey).isPresent()) {
					count[0]++;
				}
			}
		}
		return count[0];
	}

	/**
	 * Returns the partitions the owner rows of the index name, together with the probed ones.
	 *
	 * @param index                      the index
	 * @param probedPartitionPrimaryKeys the probed partitions
	 * @return the partition primary keys in ascending order
	 */
	@Nonnull
	private static Set<Integer> partitionsOf(
		@Nonnull VirtualReferencePartitionIndex index,
		@Nonnull int... probedPartitionPrimaryKeys
	) {
		final Set<Integer> partitions = new TreeSet<>();
		forEachOwnerRow(index, (partitionPrimaryKey, payload) -> partitions.add(partitionPrimaryKey));
		for (final int partitionPrimaryKey : probedPartitionPrimaryKeys) {
			partitions.add(partitionPrimaryKey);
		}
		return partitions;
	}

	/**
	 * Receives one value of a sort key with the partitions holding it.
	 */
	@FunctionalInterface
	public interface SortValueBlockConsumer {

		/**
		 * Receives one value of a sort key with the partitions holding it.
		 *
		 * @param value                the value, normalized as the registry stores it
		 * @param partitionPrimaryKeys the partitions holding the value, in ascending order
		 */
		void accept(@Nonnull Serializable value, @Nonnull int[] partitionPrimaryKeys);

	}

	/**
	 * Receives one entry of the index.
	 */
	@FunctionalInterface
	public interface PartitionEntryConsumer {

		/**
		 * Receives one entry of the index.
		 *
		 * @param partitionPrimaryKey the partition primary key
		 * @param payload             the payload of the entry
		 */
		void accept(int partitionPrimaryKey, long payload);

	}

}
