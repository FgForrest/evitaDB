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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.attribute.ReferenceSortedRecordsProvider;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies how {@link SequentialSorter} drives its embedded sorters block by block: a block holding none of the
 * records still to be sorted is skipped without invoking any embedded sorter, and the walk ends once every record is
 * claimed.
 *
 * The blocks are real reduced indexes - counting how often their owner formula is read, so a test sees which blocks
 * the sorter looked at - sorted by a real
 * {@link PreSortedRecordsSorter} over array-backed providers shaped like those of an ordering by the referenced
 * primary key - every owner carries the target's primary key, owners follow their own primary keys. Such a provider
 * resolves positions by the array merge walk, which reads the query's pooled scratch buffers; the tests hand the
 * sorter buffers full of stale owner ids, the state that made an embedded sorter run on an empty block report an
 * owner that was not there.
 *
 * Blocks: target 1 holds owners 1 and 2, target 2 holds owners 1, 2 and 3, target 3 holds owner 4.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Sequential sorter of traversal blocks")
@Tag(ENGINE)
@Tag(ORDER)
class SequentialSorterTest {
	/**
	 * The owner id every pooled scratch buffer holds when the sorter borrows it - the first owner of the block of
	 * target 2, i.e. exactly the id the merge walk of that block compares against first.
	 */
	private static final int STALE_OWNER = 1;
	/**
	 * How many scratch buffers the query context is primed with - more than one sorter call borrows at once.
	 */
	private static final int PRIMED_BUFFERS = 8;

	/**
	 * The fixture providing the execution context of the query.
	 */
	private PickFirstReducedIndexFixture fixture;
	/**
	 * The reduced indexes of the blocks, in traversal order.
	 */
	private CountingReducedEntityIndex[] indexes;
	/**
	 * The blocks, one reduced index each, in traversal order.
	 */
	private ReducedEntityIndex[][] blocks;
	/**
	 * The block keys the embedded sorter was invoked for, in order.
	 */
	private List<RepresentativeReferenceKey> invokedBlocks;

	@BeforeEach
	void setUp() {
		this.fixture = new PickFirstReducedIndexFixture();
		this.indexes = new CountingReducedEntityIndex[]{
			new CountingReducedEntityIndex(1, 1, 1, 2),
			new CountingReducedEntityIndex(2, 2, 1, 2, 3),
			new CountingReducedEntityIndex(3, 3, 4)
		};
		this.blocks = new ReducedEntityIndex[][]{{this.indexes[0]}, {this.indexes[1]}, {this.indexes[2]}};
		this.invokedBlocks = new ArrayList<>(4);
	}

	@Test
	@DisplayName("should skip a block that holds none of the remaining records")
	void shouldSkipBlockWithoutRemainingRecords() {
		// block 1 claims owners 1 and 2, so block 2 holds none of the remaining owners and block 3 claims owner 4
		final SortOutcome outcome = sort(primedExecutionContext(), 0, 10, 1, 2, 4);

		assertAll(
			() -> assertArrayEquals(new int[]{1, 2, 4}, outcome.written()),
			() -> assertArrayEquals(new int[0], outcome.remaining()),
			() -> assertEquals(
				List.of(
					this.blocks[0][0].getRepresentativeReferenceKey(),
					this.blocks[2][0].getRepresentativeReferenceKey()
				),
				this.invokedBlocks,
				"the embedded sorter must not run for the block of target 2"
			)
		);
	}

	@Test
	@DisplayName("should stop walking the blocks once every record is claimed")
	void shouldStopOnceEveryRecordIsClaimed() {
		final SortOutcome outcome = sort(primedExecutionContext(), 0, 10, 1, 2);

		assertAll(
			() -> assertArrayEquals(new int[]{1, 2}, outcome.written()),
			() -> assertArrayEquals(new int[0], outcome.remaining()),
			() -> assertEquals(List.of(this.blocks[0][0].getRepresentativeReferenceKey()), this.invokedBlocks),
			// the blocks after the one claiming the last record are not even intersected with the selection
			() -> assertArrayEquals(
				new int[]{1, 0, 0}, formulaReads(),
				"the owner formulas of blocks 2 and 3 must never be read"
			)
		);
	}

	@Test
	@DisplayName("should pass on records no block holds, skipping every block")
	void shouldPassOnRecordsNoBlockHolds() {
		final SortOutcome outcome = sort(primedExecutionContext(), 0, 10, 7, 8);

		assertAll(
			() -> assertArrayEquals(new int[0], outcome.written()),
			() -> assertArrayEquals(new int[]{7, 8}, outcome.remaining()),
			() -> assertEquals(List.of(), this.invokedBlocks)
		);
	}

	@Test
	@DisplayName("should keep counting skipped records across a skipped block")
	void shouldCountSkippedRecordsAcrossSkippedBlock() {
		// the page starts at position 1: owner 1 is skipped in block 1, block 2 is skipped as a whole
		final SortOutcome outcome = sort(primedExecutionContext(), 1, 10, 1, 2, 4);

		assertAll(
			() -> assertArrayEquals(new int[]{2, 4}, outcome.written()),
			() -> assertArrayEquals(new int[]{1}, outcome.skipped()),
			() -> assertArrayEquals(new int[0], outcome.remaining())
		);
	}

	@Test
	@DisplayName("should carry an outstanding offset across a skipped block into the next sorter")
	void shouldCarryOutstandingOffsetAcrossSkippedBlockIntoNextSorter() {
		// slice [3, 5): block 1 skips owners 1 and 2, block 2 holds none of the rest and is skipped, block 3 skips
		// owner 4 - three records skipped, none written; owners 7 and 8 belong to no block and the next sorter
		// writes them onto the page
		final int[] result = new int[2];
		final List<Integer> skipped = new ArrayList<>(4);
		final SortingContext afterTraversal = createSorter().sortAndSlice(
			new SortingContext(primedExecutionContext(), new BaseBitmap(1, 2, 4, 7, 8), 3, 5, 0, 0),
			result,
			skipped::add
		);
		final SortingContext afterFallback = NoSorter.INSTANCE.sortAndSlice(afterTraversal, result, skipped::add);

		assertAll(
			() -> assertEquals(3, afterTraversal.skipped(), "the traversal skipped three records"),
			() -> assertEquals(0, afterTraversal.peak(), "the traversal wrote nothing onto the page"),
			() -> assertArrayEquals(new int[]{7, 8}, afterTraversal.nonSortedKeys().getArray()),
			() -> assertArrayEquals(new int[]{1, 2, 4}, skipped.stream().mapToInt(Integer::intValue).toArray()),
			() -> assertEquals(2, afterFallback.peak(), "the next sorter filled the page"),
			() -> assertArrayEquals(new int[]{7, 8}, result)
		);
	}

	/**
	 * Returns how many times the owner formula of each block's index was read, in block order.
	 *
	 * @return the read counts
	 */
	@Nonnull
	private int[] formulaReads() {
		return Arrays.stream(this.indexes).mapToInt(CountingReducedEntityIndex::getFormulaReads).toArray();
	}

	/**
	 * Creates the sorter over the blocks of the fixture, recording each invocation of its embedded sorter.
	 *
	 * @return the sorter
	 */
	@Nonnull
	private SequentialSorter createSorter() {
		return new SequentialSorter(
			this.blocks, new ArrayList<>(List.of(new RecordingSorter(createPreSortedSorter(), this.invokedBlocks)))
		);
	}

	/**
	 * Runs the sorter over the blocks of the fixture.
	 *
	 * @param executionContext the execution context of the query
	 * @param startIndex       the first requested position
	 * @param endIndex         the position after the last requested one
	 * @param selection        the owners to sort
	 * @return what the sorter wrote, skipped and left unsorted
	 */
	@Nonnull
	private SortOutcome sort(
		@Nonnull QueryExecutionContext executionContext,
		int startIndex,
		int endIndex,
		@Nonnull int... selection
	) {
		final SequentialSorter sorter = createSorter();
		final int[] result = new int[endIndex - startIndex];
		final List<Integer> skipped = new ArrayList<>(4);
		final SortingContext output = sorter.sortAndSlice(
			new SortingContext(executionContext, new BaseBitmap(selection), startIndex, endIndex, 0, 0),
			result,
			skipped::add
		);
		return new SortOutcome(
			Arrays.copyOf(result, output.peak()),
			skipped.stream().mapToInt(Integer::intValue).toArray(),
			output.nonSortedKeys().getArray()
		);
	}

	/**
	 * Creates the embedded sorter walking the providers of every block one after another, as a traversal does.
	 *
	 * @return the sorter
	 */
	@Nonnull
	private Sorter createPreSortedSorter() {
		return new PreSortedRecordsSorter(
			MergeMode.APPEND_ALL,
			Comparator.naturalOrder(),
			OrderDirection.ASC,
			() -> Arrays.stream(this.blocks)
				.flatMap(Arrays::stream)
				.map(SequentialSorterTest::createProvider)
				.toArray(ReferenceSortedRecordsProvider[]::new)
		);
	}

	/**
	 * Creates the array-backed provider of one reduced index: its owners in ascending order, each carrying the
	 * primary key of the index's target.
	 *
	 * @param index the reduced index
	 * @return the provider
	 */
	@Nonnull
	private static ReferenceSortedRecordsProvider createProvider(@Nonnull ReducedEntityIndex index) {
		final Bitmap owners = index.getAllPrimaryKeys();
		final int[] ownersAscending = owners.getArray();
		final int[] positions = new int[ownersAscending.length];
		for (int i = 0; i < positions.length; i++) {
			positions[i] = i;
		}
		final int target = index.getReferenceKey().primaryKey();
		return new ReferenceSortedRecordsProvider(
			index.getPrimaryKey(), ownersAscending, positions, owners, position -> target,
			index.getRepresentativeReferenceKey()
		);
	}

	/**
	 * Creates an execution context whose pooled scratch buffers hold stale owner ids, as they do after an earlier
	 * sort of the same query - {@link QueryExecutionContext#borrowBuffer()} hands out what the previous borrower left.
	 *
	 * @return the execution context
	 */
	@Nonnull
	private QueryExecutionContext primedExecutionContext() {
		final QueryExecutionContext executionContext = this.fixture.createExecutionContext();
		final int[][] buffers = new int[PRIMED_BUFFERS][];
		for (int i = 0; i < PRIMED_BUFFERS; i++) {
			buffers[i] = executionContext.borrowBuffer();
			Arrays.fill(buffers[i], STALE_OWNER);
		}
		for (int[] buffer : buffers) {
			executionContext.returnBuffer(buffer);
		}
		return executionContext;
	}

	/**
	 * What one run of the sorter produced.
	 *
	 * @param written   the records the sorter wrote, in order
	 * @param skipped   the records it skipped before the requested page, in order
	 * @param remaining the records no block claimed
	 */
	private record SortOutcome(@Nonnull int[] written, @Nonnull int[] skipped, @Nonnull int[] remaining) {
	}

	/**
	 * A real reduced index of the `items` reference in the live scope that counts how often its owner formula is
	 * read - the read {@link SequentialSorter} makes to intersect a block with the records still to be sorted.
	 */
	private static final class CountingReducedEntityIndex extends ReducedEntityIndex {
		/**
		 * How many times {@link #getAllPrimaryKeysFormula()} was called.
		 */
		private int formulaReads;

		/**
		 * Creates the index and fills it with the owners.
		 *
		 * @param primaryKey primary key of the index
		 * @param target     primary key of the referenced entity
		 * @param owners     owners the index holds
		 */
		CountingReducedEntityIndex(int primaryKey, int target, @Nonnull int... owners) {
			super(
				primaryKey, PickFirstReducedIndexFixture.OWNER_TYPE,
				new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
					new RepresentativeReferenceKey(new ReferenceKey(PickFirstReducedIndexFixture.REFERENCE_NAME, target))
				)
			);
			for (int owner : owners) {
				insertPrimaryKeyIfMissing(owner);
			}
		}

		@Nonnull
		@Override
		public Formula getAllPrimaryKeysFormula() {
			this.formulaReads++;
			return super.getAllPrimaryKeysFormula();
		}

		/**
		 * Returns how many times the owner formula was read.
		 *
		 * @return the number of reads
		 */
		int getFormulaReads() {
			return this.formulaReads;
		}
	}

	/**
	 * Delegating sorter that records the block key of every invocation.
	 *
	 * @param delegate      the sorter doing the work
	 * @param invokedBlocks the list receiving the block keys
	 */
	private record RecordingSorter(
		@Nonnull Sorter delegate,
		@Nonnull List<RepresentativeReferenceKey> invokedBlocks
	) implements Sorter {

		@Nonnull
		@Override
		public SortingContext sortAndSlice(
			@Nonnull SortingContext sortingContext,
			@Nonnull int[] result,
			@Nullable IntConsumer skippedRecordsConsumer
		) {
			this.invokedBlocks.add(sortingContext.referenceKey());
			return this.delegate.sortAndSlice(sortingContext, result, skippedRecordsConsumer);
		}
	}

}
