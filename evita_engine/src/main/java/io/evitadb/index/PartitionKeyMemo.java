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

import com.carrotsearch.hppc.IntLongHashMap;
import com.carrotsearch.hppc.IntObjectHashMap;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * The query-local memo through which a query resolves a partition by its key while it reads a collection virtually.
 *
 * The registry of single-owner partitions has no `(target, representative values) -> partition` structure, so a
 * by-key resolution of a registered partition has to find it among the partitions of its target. Doing that per
 * lookup - one copy of the target's family per lookup - costs `Ω(N²)` for `N` owners sharing one target in a single
 * query, because each of their rows looks its own partition up. The memo therefore builds, **once per target and
 * query**, the map from the registry's tuple id to the registered partition of that tuple: one copy of the target's
 * family from the type index ({@link ReferencedTypeEntityIndex#getAllReferenceIndexes(int)}) and two registry lookups
 * per member. A lookup is then one dictionary probe for the tuple id and one hash probe.
 *
 * A key the memo cannot answer - a tuple the registry does not know, or a tuple no registered partition of the target
 * holds - falls through to the direct key lookup of the object-only resolver
 * ({@link ReferencePartitions#resolveIfExists(Function, Scope, RepresentativeReferenceKey)}), which answers the
 * partition's reduced entity index or nothing.
 *
 * The memo is keyed by the identity of the referenced-type entity index it read, which names the collection, the
 * scope and the reference - and, beyond them, the very snapshot of the index the query reads. It is created by and
 * lives in one query planning context, so it never outlives the snapshot it was built from: the next query builds
 * its own. It is not thread-safe, as the planning context is not.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PartitionKeyMemo {
	/**
	 * Per referenced-type entity index, per target primary key: the registered partitions of the target by the tuple
	 * id of their representative values, each as `pack(partitionPrimaryKey, ownerPrimaryKey)`.
	 */
	@Nonnull private final Map<ReferencedTypeEntityIndex, IntObjectHashMap<IntLongHashMap>> families =
		new IdentityHashMap<>(4);
	// TODO JNO - remove at the end of #1615
	/**
	 * How many target families the memo has built - observed by the operation-count tests.
	 */
	private int familyBuildCount;
	// TODO JNO - remove at the end of #1615
	/**
	 * How many family members the memo has visited while building them - observed by the operation-count tests.
	 */
	private int visitedEntryCount;

	/**
	 * Resolves the partition of a representative reference key in a scope, for a query - see the class documentation.
	 *
	 * @param indexByKey                 lookup of the collection's indexes by key, answering `null` when absent
	 * @param virtualTypeIndex           the referenced-type entity index of the key's reference in the scope when the
	 *                                   query reads the collection virtually, `null` when it does not
	 * @param scope                      the scope of the partition
	 * @param representativeReferenceKey the representative reference key of the partition
	 * @return the virtual partition when the registry holds the partition's row, its reduced entity index otherwise,
	 *         `null` when the collection holds no partition under the key
	 * @throws GenericEvitaInternalError when the key holds an index that is not a reference partition
	 */
	@Nullable
	public ReferencePartition resolveIfExists(
		@Nonnull Function<EntityIndexKey, ? extends Index<?>> indexByKey,
		@Nullable ReferencedTypeEntityIndex virtualTypeIndex,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey representativeReferenceKey
	) {
		if (virtualTypeIndex != null) {
			ReferencePartitions.assertTypeIndexOf(
				virtualTypeIndex, scope, representativeReferenceKey.referenceName()
			);
			final VirtualReferencePartitionIndex registry = virtualTypeIndex.getVirtualReferencePartitionIndex();
			final Serializable[] tuple = representativeReferenceKey.representativeAttributeValues();
			// the empty tuple is never in the dictionary - the registry stands for it by the absence of a tuple id
			final int tupleId = tuple.length == 0 ?
				VirtualReferencePartitionIndex.NO_TUPLE_ID : registry.getTupleId(tuple);
			if (tuple.length == 0 || tupleId != VirtualReferencePartitionIndex.NO_TUPLE_ID) {
				final IntLongHashMap family = getFamily(virtualTypeIndex, representativeReferenceKey.primaryKey());
				final int slot = family.indexOf(tupleId);
				if (family.indexExists(slot)) {
					final long partitionAndOwner = family.indexGet(slot);
					return ReferencePartitions.toVirtualPartition(
						virtualTypeIndex,
						NumberUtils.unpackHigh(partitionAndOwner),
						NumberUtils.unpackLow(partitionAndOwner),
						representativeReferenceKey
					);
				}
			}
		}
		// a key the registry does not hold - the partition, if any, is an index object
		return ReferencePartitions.resolveIfExists(indexByKey, scope, representativeReferenceKey);
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Returns how many target families the memo has built since it was created.
	 *
	 * @return the number of family builds
	 */
	public int getFamilyBuildCount() {
		return this.familyBuildCount;
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Returns how many family members the memo has visited while building its families since it was created.
	 *
	 * @return the number of visited family members
	 */
	public int getVisitedEntryCount() {
		return this.visitedEntryCount;
	}

	/**
	 * Returns the registered partitions of a target by tuple id, building them on the first request.
	 *
	 * @param typeIndex        the referenced-type entity index of the reference in the scope
	 * @param targetPrimaryKey the primary key of the target entity
	 * @return the registered partitions of the target by the tuple id of their representative values
	 */
	@Nonnull
	private IntLongHashMap getFamily(@Nonnull ReferencedTypeEntityIndex typeIndex, int targetPrimaryKey) {
		final IntObjectHashMap<IntLongHashMap> byTarget = this.families.computeIfAbsent(
			typeIndex, __ -> new IntObjectHashMap<>(16)
		);
		IntLongHashMap family = byTarget.get(targetPrimaryKey);
		if (family == null) {
			family = buildFamily(typeIndex, targetPrimaryKey);
			byTarget.put(targetPrimaryKey, family);
		}
		return family;
	}

	/**
	 * Builds the registered partitions of a target by tuple id from one copy of the target's family.
	 *
	 * @param typeIndex        the referenced-type entity index of the reference in the scope
	 * @param targetPrimaryKey the primary key of the target entity
	 * @return the registered partitions of the target by the tuple id of their representative values
	 * @throws GenericEvitaInternalError when a row names another target, or two registered partitions of the target
	 *                                   share their representative values
	 */
	@Nonnull
	private IntLongHashMap buildFamily(@Nonnull ReferencedTypeEntityIndex typeIndex, int targetPrimaryKey) {
		final VirtualReferencePartitionIndex registry = typeIndex.getVirtualReferencePartitionIndex();
		final int[] partitionPrimaryKeys = typeIndex.getAllReferenceIndexes(targetPrimaryKey);
		this.familyBuildCount++;
		final IntLongHashMap family = new IntLongHashMap(partitionPrimaryKeys.length);
		for (final int partitionPrimaryKey : partitionPrimaryKeys) {
			this.visitedEntryCount++;
			final OptionalLong row = registry.getPartitionOwner(partitionPrimaryKey);
			if (row.isEmpty()) {
				// a partition with several owners - it is read through its index object
				continue;
			}
			final long ownerAndTarget = row.getAsLong();
			if (NumberUtils.unpackLow(ownerAndTarget) != targetPrimaryKey) {
				throw new GenericEvitaInternalError(
					"Type index `" + typeIndex.getIndexKey() + "` lists partition " + partitionPrimaryKey +
						" for target " + targetPrimaryKey + ", but its registry row names target " +
						NumberUtils.unpackLow(ownerAndTarget) + "!"
				);
			}
			final int tupleId = registry.getRepresentativeTupleId(partitionPrimaryKey);
			final long partitionAndOwner =
				NumberUtils.pack(partitionPrimaryKey, NumberUtils.unpackHigh(ownerAndTarget));
			final int slot = family.indexOf(tupleId);
			if (family.indexExists(slot)) {
				throw new GenericEvitaInternalError(
					"Partitions " + NumberUtils.unpackHigh(family.indexGet(slot)) + " and " + partitionPrimaryKey +
						" of target " + targetPrimaryKey + " in `" + typeIndex.getIndexKey() + "` share tuple id " +
						tupleId + "!"
				);
			}
			family.indexInsert(slot, tupleId, partitionAndOwner);
		}
		return family;
	}

}
