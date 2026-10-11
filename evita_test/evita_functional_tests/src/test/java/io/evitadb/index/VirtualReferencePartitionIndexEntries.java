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

import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.TreeSet;

/**
 * Counts the entries of a {@link VirtualReferencePartitionIndex} - owner rows, representative tuples, sort values and
 * chain predecessors together - through its public read surface, for tests asserting that a write left exactly the
 * entries it should. The dictionaries and the declared keys are not entries.
 *
 * The owner rows and the representative tuples are walked slot by slot, so every one of them is counted. A sort value
 * and a chain predecessor are read by the primary key of their partition, so they are counted for every partition an
 * owner row, a representative tuple or the sort index of a declared sort key names, and for every partition the
 * caller probes in addition. A test proving that a partition left nothing behind probes the partition, because a
 * value of a partition no walk names would otherwise go uncounted. The values of a key that is no longer declared
 * cannot be read at all - {@link VirtualReferencePartitionIndex#isEmpty()} is the reading that sees them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class VirtualReferencePartitionIndexEntries {

	private VirtualReferencePartitionIndexEntries() {
		throw new UnsupportedOperationException("This class cannot be instantiated!");
	}

	/**
	 * Counts the entries of the index - see the class documentation for which sort values and chain predecessors are
	 * counted.
	 *
	 * @param index                     the index whose entries are counted
	 * @param probedPartitionPrimaryKeys partitions whose sort values and chain predecessors are counted even when no
	 *                                   owner row, representative tuple or sort index names them
	 * @return the number of entries
	 */
	public static int count(@Nonnull VirtualReferencePartitionIndex index, @Nonnull int... probedPartitionPrimaryKeys) {
		final Set<Integer> partitions = new TreeSet<>();
		final int[] slotEntries = new int[1];
		index.forEachEntry(
			VirtualReferencePartitionSlot.PARTITION_OWNER,
			(partitionPrimaryKey, payload) -> {
				partitions.add(partitionPrimaryKey);
				slotEntries[0]++;
			}
		);
		index.forEachEntry(
			VirtualReferencePartitionSlot.REPRESENTATIVE_TUPLE,
			(partitionPrimaryKey, payload) -> {
				partitions.add(partitionPrimaryKey);
				slotEntries[0]++;
			}
		);
		final Set<AttributeIndexKey> sortKeys = index.getDeclaredSortKeys();
		for (final AttributeIndexKey sortKey : sortKeys) {
			final OwnerSortIndex sortIndex = index.getSortIndex(sortKey);
			if (sortIndex != null) {
				for (final int partitionPrimaryKey : sortIndex.getSortedRecords()) {
					partitions.add(partitionPrimaryKey);
				}
			}
		}
		for (final int partitionPrimaryKey : probedPartitionPrimaryKeys) {
			partitions.add(partitionPrimaryKey);
		}
		final Set<AttributeIndexKey> chainKeys = index.getDeclaredChainKeys();
		int count = slotEntries[0];
		for (final int partitionPrimaryKey : partitions) {
			for (final AttributeIndexKey sortKey : sortKeys) {
				if (index.hasSortValue(sortKey, partitionPrimaryKey)) {
					count++;
				}
			}
			for (final AttributeIndexKey chainKey : chainKeys) {
				if (index.getChainPredecessor(chainKey, partitionPrimaryKey).isPresent()) {
					count++;
				}
			}
		}
		return count;
	}

}
