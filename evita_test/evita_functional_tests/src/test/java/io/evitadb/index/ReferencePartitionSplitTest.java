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
import io.evitadb.utils.NumberUtils;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	/**
	 * How many partitions each of the two long interleaved runs holds.
	 */
	private static final int LONG_RUN_LENGTH = 50_000;

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
	@DisplayName("long interleaved runs of both scopes merge into one ascending split")
	void shouldSplitLongInterleavedRunsOfBothScopes() {
		// the even partitions are LIVE, the odd ones ARCHIVED - every row of one registry falls between two rows of
		// the other
		final ReferencedTypeEntityIndex live = typeIndexWithRows(
			Scope.LIVE, IntStream.rangeClosed(1, LONG_RUN_LENGTH).map(it -> it * 2).toArray()
		);
		final ReferencedTypeEntityIndex archived = typeIndexWithRows(
			Scope.ARCHIVED, IntStream.rangeClosed(1, LONG_RUN_LENGTH).map(it -> it * 2 - 1).toArray()
		);
		final ReferencePartitionSplit split = ReferencePartitions.split(
			new BaseBitmap(IntStream.rangeClosed(1, 2 * LONG_RUN_LENGTH).toArray()), live, archived
		);

		assertEquals(2 * LONG_RUN_LENGTH, split.getVirtualCount());
		for (int i = 0; i < split.getVirtualCount(); i++) {
			final int partition = i + 1;
			assertEquals(partition, split.getVirtualPrimaryKey(i));
			assertSame(partition % 2 == 0 ? live : archived, split.getVirtualTypeIndex(i));
			assertEquals(ownerOf(partition), split.getVirtualOwnerPrimaryKey(i));
			assertEquals(targetOf(partition), split.getVirtualTargetPrimaryKey(i));
		}
		assertEquals(0, split.getMaterializedPrimaryKeys().size());
	}

	@Test
	@DisplayName("two long interleaved runs merge with a number of writes linear in their length")
	void shouldMergeInterleavedRunsInLinearWrites() {
		final ReferencedTypeEntityIndex live = typeIndexWithRows(Scope.LIVE);
		final ReferencedTypeEntityIndex archived = typeIndexWithRows(Scope.ARCHIVED);
		final int count = 2 * LONG_RUN_LENGTH;
		final int[] primaryKeys = new int[count];
		final long[] ownersAndTargets = new long[count];
		final ReferencedTypeEntityIndex[] holders = new ReferencedTypeEntityIndex[count];
		// the first run holds the even partitions of LIVE, the second the odd ones of ARCHIVED
		for (int i = 0; i < LONG_RUN_LENGTH; i++) {
			fillEntry(primaryKeys, ownersAndTargets, holders, i, 2 * (i + 1), live);
			fillEntry(primaryKeys, ownersAndTargets, holders, LONG_RUN_LENGTH + i, 2 * i + 1, archived);
		}

		final int writes = ReferencePartitions.mergeByPrimaryKey(
			primaryKeys, ownersAndTargets, holders, LONG_RUN_LENGTH, count
		);

		// the copy of the second run plus one write per merged entry - an insertion sort would shift ~1.25e9 entries
		assertTrue(
			writes <= count + LONG_RUN_LENGTH,
			"The merge must write each entry a bounded number of times, but wrote " + writes + " for " + count +
				" entries"
		);
		for (int i = 0; i < count; i++) {
			final int partition = i + 1;
			assertEquals(partition, primaryKeys[i]);
			assertEquals(ownerAndTargetOf(partition), ownersAndTargets[i]);
			assertSame(partition % 2 == 0 ? live : archived, holders[i]);
		}
	}

	@Test
	@DisplayName("a second run after the first, or wholly before it, merges correctly")
	void shouldMergeRunsThatDoNotInterleave() {
		final ReferencedTypeEntityIndex live = typeIndexWithRows(Scope.LIVE);
		final ReferencedTypeEntityIndex archived = typeIndexWithRows(Scope.ARCHIVED);
		final int[] primaryKeys = new int[5];
		final long[] ownersAndTargets = new long[5];
		final ReferencedTypeEntityIndex[] holders = new ReferencedTypeEntityIndex[5];

		// the second run follows the first - nothing moves
		fillEntry(primaryKeys, ownersAndTargets, holders, 0, 1, live);
		fillEntry(primaryKeys, ownersAndTargets, holders, 1, 2, live);
		fillEntry(primaryKeys, ownersAndTargets, holders, 2, 3, archived);
		fillEntry(primaryKeys, ownersAndTargets, holders, 3, 4, archived);
		fillEntry(primaryKeys, ownersAndTargets, holders, 4, 5, archived);
		assertEquals(0, ReferencePartitions.mergeByPrimaryKey(primaryKeys, ownersAndTargets, holders, 2, 5));
		assertArrayEquals(new int[]{1, 2, 3, 4, 5}, primaryKeys);

		// the second run precedes the whole first one
		fillEntry(primaryKeys, ownersAndTargets, holders, 0, 4, live);
		fillEntry(primaryKeys, ownersAndTargets, holders, 1, 5, live);
		fillEntry(primaryKeys, ownersAndTargets, holders, 2, 1, archived);
		fillEntry(primaryKeys, ownersAndTargets, holders, 3, 2, archived);
		fillEntry(primaryKeys, ownersAndTargets, holders, 4, 3, archived);
		ReferencePartitions.mergeByPrimaryKey(primaryKeys, ownersAndTargets, holders, 2, 5);
		assertArrayEquals(new int[]{1, 2, 3, 4, 5}, primaryKeys);
		assertArrayEquals(
			new ReferencedTypeEntityIndex[]{archived, archived, archived, live, live}, holders
		);
		for (int i = 0; i < 5; i++) {
			assertEquals(ownerAndTargetOf(i + 1), ownersAndTargets[i]);
		}
	}

	@Test
	@DisplayName("a partition both runs of a merge hold is rejected, however deep in the runs it lies")
	void shouldRejectAPartitionInBothRunsOfAMerge() {
		final ReferencedTypeEntityIndex live = typeIndexWithRows(Scope.LIVE);
		final ReferencedTypeEntityIndex archived = typeIndexWithRows(Scope.ARCHIVED);
		final int[] primaryKeys = new int[6];
		final long[] ownersAndTargets = new long[6];
		final ReferencedTypeEntityIndex[] holders = new ReferencedTypeEntityIndex[6];
		// 3 is in both runs, with larger entries interleaved after it on both sides
		fillEntry(primaryKeys, ownersAndTargets, holders, 0, 1, live);
		fillEntry(primaryKeys, ownersAndTargets, holders, 1, 3, live);
		fillEntry(primaryKeys, ownersAndTargets, holders, 2, 6, live);
		fillEntry(primaryKeys, ownersAndTargets, holders, 3, 3, archived);
		fillEntry(primaryKeys, ownersAndTargets, holders, 4, 4, archived);
		fillEntry(primaryKeys, ownersAndTargets, holders, 5, 7, archived);

		final GenericEvitaInternalError error = assertThrows(
			GenericEvitaInternalError.class,
			() -> ReferencePartitions.mergeByPrimaryKey(primaryKeys, ownersAndTargets, holders, 3, 6)
		);
		assertTrue(error.getMessage().contains("Partition 3 "), error.getMessage());
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
	 * Fills one entry of the aligned columns of a split with the row the fixture gives a partition.
	 *
	 * @param primaryKeys      the partition primary keys
	 * @param ownersAndTargets the rows
	 * @param holders          the type indexes holding the rows
	 * @param index            the entry to fill
	 * @param partition        the partition primary key
	 * @param holder           the type index holding the row
	 */
	private static void fillEntry(
		@Nonnull int[] primaryKeys,
		@Nonnull long[] ownersAndTargets,
		@Nonnull ReferencedTypeEntityIndex[] holders,
		int index,
		int partition,
		@Nonnull ReferencedTypeEntityIndex holder
	) {
		primaryKeys[index] = partition;
		ownersAndTargets[index] = ownerAndTargetOf(partition);
		holders[index] = holder;
	}

	/**
	 * Returns the row the fixture gives a partition, packed as the registry packs it.
	 *
	 * @param partition the partition primary key
	 * @return `pack(owner, target)`
	 */
	private static long ownerAndTargetOf(int partition) {
		return NumberUtils.pack(ownerOf(partition), targetOf(partition));
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
