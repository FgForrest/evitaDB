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

package io.evitadb.core.query.filter;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitionSplit;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies {@link SplitTargetPartitions}, the partitions of the hierarchy targets of a query split by representation:
 * the owner rows and the owner formula they contribute together, and {@link SplitTargetPartitions#getPartitions()},
 * which hands the partitions of a hierarchy option to a block-by-block ordering - the scopes read through the index
 * objects alone first and unchanged, then each scope read virtually with its materialized and virtual partitions merged
 * into ascending partition primary key order, the order the type index lists the partitions of a target in.
 *
 * Reduced entity indexes, type indexes and their registries are real; the splits come from
 * {@link ReferencePartitions#split(Bitmap, ReferencedTypeEntityIndex...)}, as the planning context builds them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Partitions of hierarchy targets split by representation")
@Tag(INDEXING)
@Tag(QUERY)
@Tag(HIERARCHY)
@Tag(REFERENCE)
class SplitTargetPartitionsTest {
	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "sections";

	/**
	 * Creates a reduced entity index of the fixture's reference holding the given owners.
	 *
	 * @param scope      the scope of the partition
	 * @param primaryKey the partition primary key
	 * @param owners     the owners
	 * @return the index
	 */
	@Nonnull
	private static ReducedEntityIndex materialized(@Nonnull Scope scope, int primaryKey, @Nonnull int... owners) {
		final ReducedEntityIndex index = new ReducedEntityIndex(
			primaryKey, ENTITY_TYPE,
			ReferencePartitions.createKey(
				scope, new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, targetOf(primaryKey)))
			)
		);
		for (final int owner : owners) {
			index.insertPrimaryKeyIfMissing(owner);
		}
		return index;
	}

	/**
	 * Creates a type index of the fixture's reference whose registry holds a row for each given partition.
	 *
	 * @param scope      the scope
	 * @param partitions the partitions with a row
	 * @return the type index
	 */
	@Nonnull
	private static ReferencedTypeEntityIndex typeIndexWithRows(@Nonnull Scope scope, @Nonnull int... partitions) {
		final ReferencedTypeEntityIndex typeIndex = new ReferencedTypeEntityIndex(
			1, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, REFERENCE_NAME)
		);
		for (final int partition : partitions) {
			typeIndex.insertPrimaryKeyIfMissing(partition, targetOf(partition));
			typeIndex.getVirtualReferencePartitionIndex().putPartitionOwner(
				partition, ownerOf(partition), targetOf(partition)
			);
		}
		return typeIndex;
	}

	/**
	 * Returns the only owner the fixture gives a registered partition.
	 *
	 * @param partition the partition primary key
	 * @return the owner
	 */
	private static int ownerOf(int partition) {
		return 1_000 + partition;
	}

	/**
	 * Returns the target the fixture gives a partition.
	 *
	 * @param partition the partition primary key
	 * @return the target
	 */
	private static int targetOf(int partition) {
		return 10_000 + partition;
	}

	/**
	 * Returns the primary keys of the partitions in their order.
	 *
	 * @param partitions the partitions
	 * @return their primary keys
	 */
	@Nonnull
	private static int[] primaryKeysOf(@Nonnull List<? extends ReferencePartition> partitions) {
		return partitions.stream().mapToInt(ReferencePartition::getPrimaryKey).toArray();
	}

	/**
	 * Asserts a partition is the virtual handle of a registered partition of the type index, with its row.
	 *
	 * @param partition the partition
	 * @param typeIndex the type index holding the row
	 */
	private static void assertVirtual(@Nonnull ReferencePartition partition, @Nonnull ReferencedTypeEntityIndex typeIndex) {
		final VirtualReferencePartition virtual = assertInstanceOf(VirtualReferencePartition.class, partition);
		assertEquals(ownerOf(virtual.primaryKey()), virtual.ownerPrimaryKey());
		assertEquals(targetOf(virtual.primaryKey()), virtual.getReferenceKey().primaryKey());
		assertSame(typeIndex, virtual.typeIndex());
	}

	@Nested
	@DisplayName("partitions of a hierarchy option")
	class Partitions {

		@Test
		@DisplayName("without a partition read virtually the materialized list itself is answered")
		void shouldAnswerTheMaterializedListWithoutSplits() {
			final List<ReducedEntityIndex> materialized = List.of(
				materialized(Scope.LIVE, 3, 1), materialized(Scope.LIVE, 1, 2)
			);
			final SplitTargetPartitions partitions = new SplitTargetPartitions(materialized, EmptyBitmap.INSTANCE, 0);

			assertSame(materialized, partitions.getPartitions());
		}

		@Test
		@DisplayName("virtual partitions before, between and after the materialized ones merge in primary key order")
		void shouldMergeOneScopeInPrimaryKeyOrder() {
			final ReferencedTypeEntityIndex live = typeIndexWithRows(Scope.LIVE, 1, 4, 5, 9);
			final ReferencePartitionSplit split = ReferencePartitions.split(new BaseBitmap(1, 3, 4, 5, 6, 9), live);
			assertArrayEquals(new int[]{3, 6}, split.getMaterializedPrimaryKeys().getArray());
			final SplitTargetPartitions partitions = new SplitTargetPartitions(
				List.of(materialized(Scope.LIVE, 3, 7, 8), materialized(Scope.LIVE, 6, 9)),
				new BaseBitmap(ownerOf(1), ownerOf(4), ownerOf(5), ownerOf(9)), 4,
				new ReferencePartitionSplit[]{split}, new int[]{0}
			);

			final List<? extends ReferencePartition> result = partitions.getPartitions();

			assertArrayEquals(new int[]{1, 3, 4, 5, 6, 9}, primaryKeysOf(result));
			for (final ReferencePartition partition : result) {
				if (partition.getPrimaryKey() == 3 || partition.getPrimaryKey() == 6) {
					assertInstanceOf(ReducedEntityIndex.class, partition);
				} else {
					assertVirtual(partition, live);
				}
			}
		}

		@Test
		@DisplayName("the index-only prefix comes first, then a virtual-only scope, then a materialized-only one")
		void shouldKeepThePrefixAndMergeAVirtualOnlyAndAMaterializedOnlyScope() {
			final ReferencedTypeEntityIndex live = typeIndexWithRows(Scope.LIVE, 2, 8);
			final ReferencedTypeEntityIndex archived = typeIndexWithRows(Scope.ARCHIVED);
			final ReferencePartitionSplit liveSplit = ReferencePartitions.split(new BaseBitmap(2, 8), live);
			final ReferencePartitionSplit archivedSplit = ReferencePartitions.split(new BaseBitmap(11, 13), archived);
			// the prefix of the scopes read through the index objects alone is in the order it was resolved in
			final SplitTargetPartitions partitions = new SplitTargetPartitions(
				List.of(
					materialized(Scope.LIVE, 50, 1), materialized(Scope.LIVE, 20, 2),
					materialized(Scope.ARCHIVED, 11, 3), materialized(Scope.ARCHIVED, 13, 4)
				),
				new BaseBitmap(ownerOf(2), ownerOf(8)), 2,
				new ReferencePartitionSplit[]{liveSplit, archivedSplit}, new int[]{2, 2}
			);

			final List<? extends ReferencePartition> result = partitions.getPartitions();

			assertArrayEquals(new int[]{50, 20, 2, 8, 11, 13}, primaryKeysOf(result));
			assertVirtual(result.get(2), live);
			assertVirtual(result.get(3), live);
		}

		@Test
		@DisplayName("each of two scopes read virtually merges its own partitions, the first ending where the second starts")
		void shouldMergeEachOfTwoMixedScopes() {
			final ReferencedTypeEntityIndex live = typeIndexWithRows(Scope.LIVE, 2, 8);
			final ReferencedTypeEntityIndex archived = typeIndexWithRows(Scope.ARCHIVED, 12);
			final ReferencePartitionSplit liveSplit = ReferencePartitions.split(new BaseBitmap(2, 5, 8), live);
			final ReferencePartitionSplit archivedSplit = ReferencePartitions.split(new BaseBitmap(11, 12, 13), archived);
			final SplitTargetPartitions partitions = new SplitTargetPartitions(
				List.of(
					materialized(Scope.LIVE, 50, 1),
					materialized(Scope.LIVE, 5, 2),
					materialized(Scope.ARCHIVED, 11, 3), materialized(Scope.ARCHIVED, 13, 4)
				),
				new BaseBitmap(ownerOf(2), ownerOf(8), ownerOf(12)), 3,
				new ReferencePartitionSplit[]{liveSplit, archivedSplit}, new int[]{1, 2}
			);

			final List<? extends ReferencePartition> result = partitions.getPartitions();

			// a virtual partition of the first scope must not be merged past the materialized ones of the second
			assertArrayEquals(new int[]{50, 2, 5, 8, 11, 12, 13}, primaryKeysOf(result));
			assertVirtual(result.get(1), live);
			assertVirtual(result.get(3), live);
			assertVirtual(result.get(5), archived);
		}

	}

	@Nested
	@DisplayName("owners")
	class Owners {

		@Test
		@DisplayName("the owner rows are the sizes of the materialized partitions plus one per virtual partition")
		void shouldCountTheOwnerRowsOfBothRepresentations() {
			final SplitTargetPartitions partitions = new SplitTargetPartitions(
				List.of(materialized(Scope.LIVE, 3, 1, 2, 3), materialized(Scope.LIVE, 6, 3)),
				new BaseBitmap(7, 8), 3
			);

			// owner 3 is counted once per partition holding it, and the virtual count rather than the owner count
			assertEquals(7, partitions.getOwnerRowCount());
		}

		@Test
		@DisplayName("the owner formula unites the materialized and the virtual owners")
		void shouldUniteTheOwnersOfBothRepresentations() {
			final SplitTargetPartitions partitions = new SplitTargetPartitions(
				List.of(materialized(Scope.LIVE, 3, 1, 2), materialized(Scope.LIVE, 6, 5)),
				new BaseBitmap(2, 7), 2
			);

			assertArrayEquals(new int[]{1, 2, 5, 7}, partitions.createOwnersFormula(null).compute().getArray());
		}

		@Test
		@DisplayName("a restriction narrows the owners of every partition and of the virtual ones")
		void shouldRestrictEveryOperand() {
			final SplitTargetPartitions partitions = new SplitTargetPartitions(
				List.of(materialized(Scope.LIVE, 3, 1, 2), materialized(Scope.LIVE, 6, 5)),
				new BaseBitmap(2, 7, 9), 3
			);

			assertArrayEquals(
				new int[]{2, 5, 9},
				partitions.createOwnersFormula(new ConstantFormula(new BaseBitmap(2, 5, 9))).compute().getArray()
			);
		}

		@Test
		@DisplayName("no partition answers the empty formula, virtual partitions alone one constant of their owners")
		void shouldAnswerTheEmptyFormulaOrTheVirtualConstant() {
			assertSame(
				EmptyFormula.INSTANCE,
				new SplitTargetPartitions(List.of(), EmptyBitmap.INSTANCE, 0).createOwnersFormula(null)
			);
			final Formula virtualOnly = new SplitTargetPartitions(
				List.of(), new BaseBitmap(4, 6), 2, new ReferencePartitionSplit[0], ArrayUtils.EMPTY_INT_ARRAY
			).createOwnersFormula(null);
			assertInstanceOf(ConstantFormula.class, virtualOnly);
			assertArrayEquals(new int[]{4, 6}, virtualOnly.compute().getArray());
		}

	}

}
