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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.stream.IntStream;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link ReferencePartitions#split(io.evitadb.index.bitmap.Bitmap, ReferencedTypeEntityIndex...)}: candidate
 * partition primary keys are split into those a registry holds a row for and the rest, with the owner, the target and
 * the type index of every virtual one, in one merge of the candidates with the rows - across the rows a cursor steps
 * along and the far ones it reaches by a descent, adjacent and isolated candidates alike, and across the registries of
 * both scopes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Split of candidate partitions by representation")
@Tag(INDEXING)
@Tag(QUERY)
@Tag(REFERENCE)
class ReferencePartitionSplitTest {
	private static final String REFERENCE_NAME = "media";

	@Test
	@DisplayName("no candidate splits into nothing")
	void shouldSplitNoCandidate() {
		final ReferencedTypeEntityIndex live = typeIndexWithRows(Scope.LIVE, 1, 2, 3);
		final ReferencePartitionSplit split = ReferencePartitions.split(EmptyBitmap.INSTANCE, live);

		assertEquals(0, split.getVirtualCount());
		assertEquals(0, split.getMaterializedPrimaryKeys().size());
	}

	@Test
	@DisplayName("without a registry to read every candidate is materialized")
	void shouldKeepEveryCandidateWithoutRegistry() {
		final BaseBitmap candidates = new BaseBitmap(1, 2, 3);
		final ReferencePartitionSplit split = ReferencePartitions.split(candidates);

		assertEquals(0, split.getVirtualCount());
		assertSame(candidates, split.getMaterializedPrimaryKeys());
	}

	@Test
	@DisplayName("candidates no registry holds a row for are all materialized")
	void shouldKeepEveryCandidateWithoutRow() {
		final ReferencedTypeEntityIndex live = typeIndexWithRows(Scope.LIVE, 2, 4, 6);
		final ReferencePartitionSplit split = ReferencePartitions.split(new BaseBitmap(1, 3, 5, 7), live);

		assertEquals(0, split.getVirtualCount());
		assertArrayEquals(new int[]{1, 3, 5, 7}, split.getMaterializedPrimaryKeys().getArray());
	}

	@Test
	@DisplayName("candidates the registry holds a row for are all virtual")
	void shouldSplitOffEveryCandidateWithRow() {
		final ReferencedTypeEntityIndex live = typeIndexWithRows(Scope.LIVE, 1, 2, 3, 5, 8);
		final ReferencePartitionSplit split = ReferencePartitions.split(new BaseBitmap(2, 3, 8), live);

		assertVirtual(split, live, 2, 3, 8);
		assertEquals(0, split.getMaterializedPrimaryKeys().size());
	}

	@Test
	@DisplayName("adjacent candidates split one by one, each with its owner and target")
	void shouldSplitAdjacentCandidates() {
		final ReferencedTypeEntityIndex live = typeIndexWithRows(Scope.LIVE, 11, 12, 14);
		final ReferencePartitionSplit split = ReferencePartitions.split(new BaseBitmap(10, 11, 12, 13, 14, 15), live);

		assertVirtual(split, live, 11, 12, 14);
		assertArrayEquals(new int[]{10, 13, 15}, split.getMaterializedPrimaryKeys().getArray());
	}

	@Test
	@DisplayName("candidates beyond long runs of rows are reached by a descent, the row right after it included")
	void shouldSplitCandidatesAcrossGapsInTheRows() {
		// rows 1-200: a candidate further than the cursor steps along is reached by a descent; the candidates right
		// after a descent target - present or absent from the rows - catch a cursor that skips a row after it
		final int[] rows = IntStream.rangeClosed(1, 200).filter(it -> it != 120).toArray();
		final ReferencedTypeEntityIndex live = typeIndexWithRows(Scope.LIVE, rows);
		final ReferencePartitionSplit split = ReferencePartitions.split(
			new BaseBitmap(1, 50, 51, 119, 120, 121, 199, 200, 201, 500), live
		);

		assertVirtual(split, live, 1, 50, 51, 119, 121, 199, 200);
		assertArrayEquals(new int[]{120, 201, 500}, split.getMaterializedPrimaryKeys().getArray());
	}

	@Test
	@DisplayName("the rows of both scopes interleave into one ascending split")
	void shouldSplitAcrossTheRegistriesOfBothScopes() {
		final ReferencedTypeEntityIndex live = typeIndexWithRows(Scope.LIVE, 2, 4, 30);
		final ReferencedTypeEntityIndex archived = typeIndexWithRows(Scope.ARCHIVED, 3, 5, 40);
		final ReferencePartitionSplit split = ReferencePartitions.split(
			new BaseBitmap(1, 2, 3, 4, 5, 6, 30, 40), live, archived
		);

		assertEquals(6, split.getVirtualCount());
		final int[] expected = {2, 3, 4, 5, 30, 40};
		final ReferencedTypeEntityIndex[] holders = {live, archived, live, archived, live, archived};
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], split.getVirtualPrimaryKey(i));
			assertSame(holders[i], split.getVirtualTypeIndex(i));
			assertEquals(ownerOf(expected[i]), split.getVirtualOwnerPrimaryKey(i));
			assertEquals(targetOf(expected[i]), split.getVirtualTargetPrimaryKey(i));
			assertEquals(holders[i].getIndexKey().scope(), split.getVirtualPartition(i).getIndexKey().scope());
		}
		assertArrayEquals(new int[]{1, 6}, split.getMaterializedPrimaryKeys().getArray());
	}

	@Test
	@DisplayName("a candidate both registries hold a row for is rejected")
	void shouldRejectARowInBothRegistries() {
		final ReferencedTypeEntityIndex live = typeIndexWithRows(Scope.LIVE, 2, 4);
		final ReferencedTypeEntityIndex archived = typeIndexWithRows(Scope.ARCHIVED, 4);

		assertThrows(
			GenericEvitaInternalError.class, () -> ReferencePartitions.split(new BaseBitmap(2, 4), live, archived)
		);
	}

	@Test
	@DisplayName("the handle of a virtual candidate carries the representative values of its row")
	void shouldBuildTheHandleOfAVirtualCandidate() {
		final ReferencedTypeEntityIndex live = typeIndexWithRows(Scope.LIVE, 7);
		live.getVirtualReferencePartitionIndex().putRepresentativeTuple(7, new Serializable[]{"detail"});
		final ReferencePartitionSplit split = ReferencePartitions.split(new BaseBitmap(7), live);

		final VirtualReferencePartition partition = split.getVirtualPartition(0);
		assertEquals(7, partition.getPrimaryKey());
		assertEquals(ownerOf(7), partition.ownerPrimaryKey());
		assertEquals(
			new RepresentativeReferenceKey(
				new ReferenceKey(REFERENCE_NAME, targetOf(7)), new Serializable[]{"detail"}
			),
			partition.getRepresentativeReferenceKey()
		);
		assertEquals(Scope.LIVE, partition.getIndexKey().scope());
		assertSame(live, partition.typeIndex());
	}

	/**
	 * Asserts the virtual candidates of a split are exactly the given ones, each with the row of the fixture.
	 *
	 * @param split     the split
	 * @param typeIndex the type index holding every row
	 * @param expected  the expected virtual partition primary keys, ascending
	 */
	private static void assertVirtual(
		@Nonnull ReferencePartitionSplit split,
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		@Nonnull int... expected
	) {
		final int[] actual = new int[split.getVirtualCount()];
		for (int i = 0; i < actual.length; i++) {
			actual[i] = split.getVirtualPrimaryKey(i);
			assertEquals(ownerOf(actual[i]), split.getVirtualOwnerPrimaryKey(i));
			assertEquals(targetOf(actual[i]), split.getVirtualTargetPrimaryKey(i));
			assertSame(typeIndex, split.getVirtualTypeIndex(i));
		}
		assertArrayEquals(expected, actual);
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
			1, "product", new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, REFERENCE_NAME)
		);
		final VirtualReferencePartitionIndex registry = typeIndex.getVirtualReferencePartitionIndex();
		for (final int partition : partitions) {
			registry.putPartitionOwner(partition, ownerOf(partition), targetOf(partition));
			// a row of another slot after each owner row - a cursor must not read past the owner slot
			registry.putRepresentativeTuple(partition, new Serializable[]{"role"});
		}
		return typeIndex;
	}

	/**
	 * Returns the owner the fixture gives a partition - a negative one, as owner primary keys are external.
	 *
	 * @param partition the partition primary key
	 * @return the owner
	 */
	private static int ownerOf(int partition) {
		return -1_000 - partition;
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

}
