/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.transaction.memory;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.list.TransactionalList;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.set.TransactionalSet;
import io.evitadb.test.annotation.RequiresDefaultWarmUpWritePath;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;
import static io.evitadb.utils.AssertionUtils.assertWarmUpSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertWarmUpSavepointRollbackRestores;

/**
 * Validates the per-entity savepoint fuzz / oracle framework itself. Drives randomized
 * baseline + in-savepoint mutation batches through
 * {@link io.evitadb.utils.AssertionUtils#assertSavepointRollbackRestores} and
 * {@link io.evitadb.utils.AssertionUtils#assertSavepointCommitKeeps} against two structures whose delta types are
 * already {@code Snapshotable} ({@link TransactionalMap} via {@code MapChanges} and {@link TransactionalBitmap} via
 * {@code BitmapChanges}). Proving the harness on known-good structures both retro-validates the snapshot
 * implementations and establishes the harness as a trustworthy oracle for the remaining (delicate) layer types.
 *
 * The same oracle also drives the **WARM_UP** counterpart, where there is no transaction and no diff layer at all:
 * writes go straight to the delegate and a {@code WarmUpSavepoint} has to rewind them from the inverses the
 * structures record themselves. {@link io.evitadb.utils.AssertionUtils#assertWarmUpSavepointRollbackRestores} is that
 * mode's entry point, and {@link TransactionalMap}, {@link TransactionalSet}, {@link TransactionalList} and
 * {@link TransactionalBitmap} are covered in it. What the randomization is really testing for the first three is
 * composition: they record one inverse PER OPERATION, so a batch mixing puts, bulk operations, view-iterator removals
 * and (for the list) position-shifting inserts is the only thing that can disprove the claim that replaying them
 * newest-first lands back on the pre-savepoint state. The bitmap instead captures its delegate ONCE on first touch, so
 * what its randomized batch probes is the opposite property - that a single copy-on-write clone taken before the first
 * write still describes the pre-savepoint members after an arbitrary mix of single and bulk adds and removals has
 * copied containers out from under it.
 *
 * Two B+ trees ({@link TransactionalObjectBPlusTree} and {@link TransactionalBucketBPlusTree}) run in warm-up mode as
 * well, and they probe a third property again: they journal per NODE, so what a randomized batch can disprove is that
 * the set of nodes recording a first touch covers every node the batch actually mutated. Node block sizes are set
 * small enough that a batch of ten operations routinely splits, borrows, merges and collapses - the structural
 * operations that reach beyond the node their mutator was called on, into a sibling or a parent.
 *
 * Each case asserts the in-savepoint batch actually changed the structure (non-vacuous), so a no-op rollback could
 * not pass by accident. The run is time-bounded; the random seed is echoed on failure for deterministic reproduction.
 *
 * **This suite stays hand-written, on purpose.** Every other `LongRunningSavepoint*` suite now declares its scenario
 * once and inherits both phases from {@link AbstractSavepointFuzzTest}. This one drives the four
 * {@link io.evitadb.utils.AssertionUtils} entry points directly, because it is what validates THEM — the harness is
 * built on those helpers, so a harness-based self-validation would inherit any bug it was supposed to find. It is also
 * the only warm-up fuzz coverage of {@link TransactionalMap} and {@link TransactionalBitmap}, which have no
 * `LongRunningSavepoint*` suite of their own.
 *
 * Its warm-up cases do share the harness's flag discipline (see {@link #runWarmUpFuzz}): they switch the mechanism on
 * so the delegate-branch backstop is live, and therefore hold the same exclusive resource lock while doing so.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Savepoint fuzz/oracle framework self-validation")
@Tag(INDEXING)
@Tag(TRANSACTION)
class LongRunningSavepointFuzzFrameworkTest implements TimeBoundedTestSupport {
	private static final int KEY_SPACE = 64;

	@ParameterizedTest(name = "TransactionalMap: savepoint rollback restores the exact pre-savepoint contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalMap: savepoint rollback restores the exact pre-savepoint contents")
	void shouldRollBackTransactionalMapToSavepoint(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final TransactionalMap<Integer, Integer> map = newSeededMap(random);
			assertSavepointRollbackRestores(
				map,
				tested -> applyRandomMapOps(tested, random, 1 + random.nextInt(8)),
				HashMap::new,
				tested -> {
					applyRandomMapOps(tested, random, 1 + random.nextInt(8));
					// applied LAST: a marker applied first is an entry like any other, and the random
					// batch that follows it could take it straight back out
					tested.put(KEY_SPACE + 1, Integer.MIN_VALUE);
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "TransactionalMap: savepoint commit keeps the in-savepoint contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalMap: savepoint commit keeps the in-savepoint contents")
	void shouldCommitTransactionalMapSavepoint(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final TransactionalMap<Integer, Integer> map = newSeededMap(random);
			assertSavepointCommitKeeps(
				map,
				tested -> applyRandomMapOps(tested, random, 1 + random.nextInt(8)),
				HashMap::new,
				tested -> applyRandomMapOps(tested, random, 1 + random.nextInt(8))
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "TransactionalBitmap: savepoint rollback restores the exact pre-savepoint record set")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalBitmap: savepoint rollback restores the exact pre-savepoint record set")
	void shouldRollBackTransactionalBitmapToSavepoint(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final TransactionalBitmap bitmap = newSeededBitmap(random);
			assertSavepointRollbackRestores(
				bitmap,
				tested -> applyRandomBitmapOps(tested, random, 1 + random.nextInt(8)),
				LongRunningSavepointFuzzFrameworkTest::bitmapContents,
				tested -> {
					applyRandomBitmapOps(tested, random, 1 + random.nextInt(8));
					// applied LAST: a marker applied first is an entry like any other, and the random
					// batch that follows it could take it straight back out
					tested.add(KEY_SPACE + 1);
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "TransactionalBitmap: savepoint commit keeps the in-savepoint record set")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalBitmap: savepoint commit keeps the in-savepoint record set")
	void shouldCommitTransactionalBitmapSavepoint(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final TransactionalBitmap bitmap = newSeededBitmap(random);
			assertSavepointCommitKeeps(
				bitmap,
				tested -> applyRandomBitmapOps(tested, random, 1 + random.nextInt(8)),
				LongRunningSavepointFuzzFrameworkTest::bitmapContents,
				tested -> applyRandomBitmapOps(tested, random, 1 + random.nextInt(8))
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "TransactionalMap: warm-up savepoint rollback restores the exact pre-savepoint contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalMap: warm-up savepoint rollback restores the exact pre-savepoint contents")
	@ResourceLock(value = RequiresDefaultWarmUpWritePath.RESOURCE, mode = ResourceAccessMode.READ_WRITE)
	void shouldRollBackTransactionalMapToWarmUpSavepoint(@Nonnull GenerationalTestInput input) {
		runWarmUpFuzz(input, (random, iteration) -> {
			final TransactionalMap<Integer, Integer> map = newSeededMap(random);
			assertWarmUpSavepointRollbackRestores(
				map,
				tested -> applyRandomMapOps(tested, random, 1 + random.nextInt(8)),
				HashMap::new,
				tested -> {
					applyRandomMapOps(tested, random, 1 + random.nextInt(8));
					// applied LAST: a marker applied first is an entry like any other, and the random
					// batch that follows it could take it straight back out
					tested.put(KEY_SPACE + 1, Integer.MIN_VALUE);
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "TransactionalMap: warm-up savepoint commit keeps the in-savepoint contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalMap: warm-up savepoint commit keeps the in-savepoint contents")
	@ResourceLock(value = RequiresDefaultWarmUpWritePath.RESOURCE, mode = ResourceAccessMode.READ_WRITE)
	void shouldCommitTransactionalMapWarmUpSavepoint(@Nonnull GenerationalTestInput input) {
		runWarmUpFuzz(input, (random, iteration) -> {
			final TransactionalMap<Integer, Integer> map = newSeededMap(random);
			assertWarmUpSavepointCommitKeeps(
				map,
				tested -> applyRandomMapOps(tested, random, 1 + random.nextInt(8)),
				HashMap::new,
				tested -> applyRandomMapOps(tested, random, 1 + random.nextInt(8))
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "TransactionalSet: warm-up savepoint rollback restores the exact pre-savepoint elements")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalSet: warm-up savepoint rollback restores the exact pre-savepoint elements")
	@ResourceLock(value = RequiresDefaultWarmUpWritePath.RESOURCE, mode = ResourceAccessMode.READ_WRITE)
	void shouldRollBackTransactionalSetToWarmUpSavepoint(@Nonnull GenerationalTestInput input) {
		runWarmUpFuzz(input, (random, iteration) -> {
			final TransactionalSet<Integer> set = newSeededSet(random);
			assertWarmUpSavepointRollbackRestores(
				set,
				tested -> applyRandomSetOps(tested, random, 1 + random.nextInt(8)),
				HashSet::new,
				tested -> {
					applyRandomSetOps(tested, random, 1 + random.nextInt(8));
					// applied LAST: a marker applied first is an entry like any other, and the random
					// batch that follows it could take it straight back out
					tested.add(KEY_SPACE + 1);
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "TransactionalSet: warm-up savepoint commit keeps the in-savepoint elements")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalSet: warm-up savepoint commit keeps the in-savepoint elements")
	@ResourceLock(value = RequiresDefaultWarmUpWritePath.RESOURCE, mode = ResourceAccessMode.READ_WRITE)
	void shouldCommitTransactionalSetWarmUpSavepoint(@Nonnull GenerationalTestInput input) {
		runWarmUpFuzz(input, (random, iteration) -> {
			final TransactionalSet<Integer> set = newSeededSet(random);
			assertWarmUpSavepointCommitKeeps(
				set,
				tested -> applyRandomSetOps(tested, random, 1 + random.nextInt(8)),
				HashSet::new,
				tested -> applyRandomSetOps(tested, random, 1 + random.nextInt(8))
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "TransactionalList: warm-up savepoint rollback restores the exact pre-savepoint order")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalList: warm-up savepoint rollback restores the exact pre-savepoint order")
	@ResourceLock(value = RequiresDefaultWarmUpWritePath.RESOURCE, mode = ResourceAccessMode.READ_WRITE)
	void shouldRollBackTransactionalListToWarmUpSavepoint(@Nonnull GenerationalTestInput input) {
		runWarmUpFuzz(input, (random, iteration) -> {
			final TransactionalList<Integer> list = newSeededList(random);
			assertWarmUpSavepointRollbackRestores(
				list,
				tested -> applyRandomListOps(tested, random, 1 + random.nextInt(8)),
				ArrayList::new,
				tested -> {
					applyRandomListOps(tested, random, 1 + random.nextInt(8));
					// applied LAST: a marker applied first is an entry like any other, and the random
					// batch that follows it could take it straight back out
					tested.add(KEY_SPACE + 1);
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "TransactionalList: warm-up savepoint commit keeps the in-savepoint order")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalList: warm-up savepoint commit keeps the in-savepoint order")
	@ResourceLock(value = RequiresDefaultWarmUpWritePath.RESOURCE, mode = ResourceAccessMode.READ_WRITE)
	void shouldCommitTransactionalListWarmUpSavepoint(@Nonnull GenerationalTestInput input) {
		runWarmUpFuzz(input, (random, iteration) -> {
			final TransactionalList<Integer> list = newSeededList(random);
			assertWarmUpSavepointCommitKeeps(
				list,
				tested -> applyRandomListOps(tested, random, 1 + random.nextInt(8)),
				ArrayList::new,
				tested -> applyRandomListOps(tested, random, 1 + random.nextInt(8))
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(
		name = "TransactionalBitmap: warm-up savepoint rollback restores the exact pre-savepoint record set"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalBitmap: warm-up savepoint rollback restores the exact pre-savepoint record set")
	@ResourceLock(value = RequiresDefaultWarmUpWritePath.RESOURCE, mode = ResourceAccessMode.READ_WRITE)
	void shouldRollBackTransactionalBitmapToWarmUpSavepoint(@Nonnull GenerationalTestInput input) {
		runWarmUpFuzz(input, (random, iteration) -> {
			final TransactionalBitmap bitmap = newSeededBitmap(random);
			assertWarmUpSavepointRollbackRestores(
				bitmap,
				tested -> applyRandomBitmapOps(tested, random, 1 + random.nextInt(8)),
				LongRunningSavepointFuzzFrameworkTest::bitmapContents,
				tested -> {
					applyRandomBitmapBulkOps(tested, random, 1 + random.nextInt(8));
					// applied LAST: a marker applied first is an entry like any other, and the random
					// batch that follows it could take it straight back out
					tested.add(KEY_SPACE + 1);
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "TransactionalBitmap: warm-up savepoint commit keeps the in-savepoint record set")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalBitmap: warm-up savepoint commit keeps the in-savepoint record set")
	@ResourceLock(value = RequiresDefaultWarmUpWritePath.RESOURCE, mode = ResourceAccessMode.READ_WRITE)
	void shouldCommitTransactionalBitmapWarmUpSavepoint(@Nonnull GenerationalTestInput input) {
		runWarmUpFuzz(input, (random, iteration) -> {
			final TransactionalBitmap bitmap = newSeededBitmap(random);
			assertWarmUpSavepointCommitKeeps(
				bitmap,
				tested -> applyRandomBitmapOps(tested, random, 1 + random.nextInt(8)),
				LongRunningSavepointFuzzFrameworkTest::bitmapContents,
				tested -> applyRandomBitmapBulkOps(tested, random, 1 + random.nextInt(8))
			);
			return iteration + 1;
		});
	}


	/**
	 * Runs a warm-up generation loop with the atomicity mechanism switched ON, so the delegate-branch backstop
	 * ({@code WarmUpSavepoint#verifyRollbackSupported}) is live while fuzzing rather than only the journalling being
	 * exercised. The flag is a process-wide static, which is why every caller carries the exclusive resource lock
	 * declared on the methods above — see {@link AbstractSavepointFuzzTest} for the full reasoning and for why the
	 * budget is seconds rather than the minute the transactional cases get.
	 *
	 * @param input     the generational input carrying the random seed
	 * @param testLogic one generation
	 * @return the iteration count the loop finished on
	 */
	private long runWarmUpFuzz(
		@Nonnull GenerationalTestInput input,
		@Nonnull BiFunction<Random, Long, Long> testLogic
	) {
		final long[] finished = new long[1];
		AbstractSavepointFuzzTest.runWithWarmUpAtomicityEnabled(
			() -> finished[0] = runForSeconds(
				input, AbstractSavepointFuzzTest.warmUpFuzzSeconds(), 1000, 0L, testLogic
			)
		);
		return finished[0];
	}

	/**
	 * Builds a fresh non-transactional map seeded with a random subset of the key space.
	 */
	@Nonnull
	private static TransactionalMap<Integer, Integer> newSeededMap(@Nonnull Random random) {
		final Map<Integer, Integer> seed = new HashMap<>();
		final int size = random.nextInt(KEY_SPACE);
		for (int i = 0; i < size; i++) {
			seed.put(random.nextInt(KEY_SPACE), random.nextInt());
		}
		return new TransactionalMap<>(seed);
	}

	@ParameterizedTest(
		name = "TransactionalObjectBPlusTree: warm-up savepoint rollback restores the exact pre-savepoint contents"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalObjectBPlusTree: warm-up savepoint rollback restores the exact pre-savepoint contents")
	@ResourceLock(value = RequiresDefaultWarmUpWritePath.RESOURCE, mode = ResourceAccessMode.READ_WRITE)
	void shouldRollBackObjectTreeToWarmUpSavepoint(@Nonnull GenerationalTestInput input) {
		runWarmUpFuzz(input, (random, iteration) -> {
			final TransactionalObjectBPlusTree<Integer, Integer> tree = newSeededObjectTree(random);
			assertWarmUpSavepointRollbackRestores(
				tree,
				tested -> applyRandomObjectTreeOps(tested, random, 1 + random.nextInt(10)),
				LongRunningSavepointFuzzFrameworkTest::objectTreeContents,
				tested -> {
					applyRandomObjectTreeOps(tested, random, 1 + random.nextInt(10));
					// applied LAST: a marker applied first is an entry like any other, and the random
					// batch that follows it could take it straight back out
					tested.insert(KEY_SPACE + 1, Integer.MAX_VALUE);
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "TransactionalObjectBPlusTree: warm-up savepoint commit keeps the in-savepoint contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalObjectBPlusTree: warm-up savepoint commit keeps the in-savepoint contents")
	@ResourceLock(value = RequiresDefaultWarmUpWritePath.RESOURCE, mode = ResourceAccessMode.READ_WRITE)
	void shouldCommitObjectTreeWarmUpSavepoint(@Nonnull GenerationalTestInput input) {
		runWarmUpFuzz(input, (random, iteration) -> {
			final TransactionalObjectBPlusTree<Integer, Integer> tree = newSeededObjectTree(random);
			assertWarmUpSavepointCommitKeeps(
				tree,
				tested -> applyRandomObjectTreeOps(tested, random, 1 + random.nextInt(10)),
				LongRunningSavepointFuzzFrameworkTest::objectTreeContents,
				tested -> applyRandomObjectTreeOps(tested, random, 1 + random.nextInt(10))
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(
		name = "TransactionalBucketBPlusTree: warm-up savepoint rollback restores the exact pre-savepoint buckets"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalBucketBPlusTree: warm-up savepoint rollback restores the exact pre-savepoint buckets")
	@ResourceLock(value = RequiresDefaultWarmUpWritePath.RESOURCE, mode = ResourceAccessMode.READ_WRITE)
	void shouldRollBackBucketTreeToWarmUpSavepoint(@Nonnull GenerationalTestInput input) {
		runWarmUpFuzz(input, (random, iteration) -> {
			final TransactionalBucketBPlusTree<Integer> tree = newSeededBucketTree(random);
			assertWarmUpSavepointRollbackRestores(
				tree,
				tested -> applyRandomBucketTreeOps(tested, random, 1 + random.nextInt(10)),
				LongRunningSavepointFuzzFrameworkTest::bucketTreeContents,
				tested -> {
					applyRandomBucketTreeOps(tested, random, 1 + random.nextInt(10));
					// applied LAST: a marker applied first is an entry like any other, and the random
					// batch that follows it could take it straight back out
					tested.addRecord(KEY_SPACE + 1, 1);
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "TransactionalBucketBPlusTree: warm-up savepoint commit keeps the in-savepoint buckets")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("TransactionalBucketBPlusTree: warm-up savepoint commit keeps the in-savepoint buckets")
	@ResourceLock(value = RequiresDefaultWarmUpWritePath.RESOURCE, mode = ResourceAccessMode.READ_WRITE)
	void shouldCommitBucketTreeWarmUpSavepoint(@Nonnull GenerationalTestInput input) {
		runWarmUpFuzz(input, (random, iteration) -> {
			final TransactionalBucketBPlusTree<Integer> tree = newSeededBucketTree(random);
			assertWarmUpSavepointCommitKeeps(
				tree,
				tested -> applyRandomBucketTreeOps(tested, random, 1 + random.nextInt(10)),
				LongRunningSavepointFuzzFrameworkTest::bucketTreeContents,
				tested -> applyRandomBucketTreeOps(tested, random, 1 + random.nextInt(10))
			);
			return iteration + 1;
		});
	}

	/**
	 * Builds a fresh non-transactional object B+ tree with block sizes small enough that a handful of operations
	 * splits and merges its nodes, seeded with a random subset of the key space.
	 */
	@Nonnull
	private static TransactionalObjectBPlusTree<Integer, Integer> newSeededObjectTree(@Nonnull Random random) {
		final TransactionalObjectBPlusTree<Integer, Integer> tree = new TransactionalObjectBPlusTree<>(
			8, 3, 7, 3, Integer.class, Integer.class
		);
		final int size = random.nextInt(KEY_SPACE);
		for (int i = 0; i < size; i++) {
			tree.insert(random.nextInt(KEY_SPACE), random.nextInt());
		}
		return tree;
	}

	/**
	 * Applies `count` random insert / overwrite / remove operations to the object tree. The contents are read fresh
	 * each op so removals and overwrites always target a key that exists.
	 */
	private static void applyRandomObjectTreeOps(
		@Nonnull TransactionalObjectBPlusTree<Integer, Integer> tree, @Nonnull Random random, int count
	) {
		for (int i = 0; i < count; i++) {
			final Map<Integer, Integer> contents = objectTreeContents(tree);
			if (contents.isEmpty() || random.nextInt(3) == 0) {
				tree.insert(random.nextInt(KEY_SPACE), random.nextInt());
			} else {
				final Integer key = pickKey(contents, random);
				if (random.nextBoolean()) {
					tree.insert(key, random.nextInt());
				} else {
					tree.delete(key);
				}
			}
		}
	}

	/**
	 * Reads the object tree's whole logical content into an `.equals`-comparable reference value.
	 */
	@Nonnull
	private static Map<Integer, Integer> objectTreeContents(
		@Nonnull TransactionalObjectBPlusTree<Integer, Integer> tree
	) {
		final Map<Integer, Integer> contents = new TreeMap<>();
		final Iterator<TransactionalObjectBPlusTree.Entry<Integer, Integer>> it = tree.entryIterator();
		while (it.hasNext()) {
			final TransactionalObjectBPlusTree.Entry<Integer, Integer> entry = it.next();
			contents.put(entry.key(), entry.value());
		}
		return contents;
	}

	/**
	 * Builds a fresh non-transactional bucket B+ tree with block sizes small enough that a handful of operations
	 * splits and merges its nodes, seeded with a random subset of the key space.
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree<Integer> newSeededBucketTree(@Nonnull Random random) {
		final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
			8, 3, 7, 3, Integer.class, null
		);
		final int size = random.nextInt(KEY_SPACE);
		for (int i = 0; i < size; i++) {
			tree.addRecord(random.nextInt(KEY_SPACE), 1 + random.nextInt(KEY_SPACE));
		}
		return tree;
	}

	/**
	 * Applies `count` random bucket operations to the bucket tree: create a bucket, grow an existing one (which may
	 * promote it from the primitive single-record column to an overflow bitmap), or remove a record from one (which
	 * may drain and delete the bucket).
	 */
	private static void applyRandomBucketTreeOps(
		@Nonnull TransactionalBucketBPlusTree<Integer> tree, @Nonnull Random random, int count
	) {
		for (int i = 0; i < count; i++) {
			final Map<Integer, List<Integer>> contents = bucketTreeContents(tree);
			if (contents.isEmpty() || random.nextInt(3) == 0) {
				tree.addRecord(random.nextInt(KEY_SPACE), 1 + random.nextInt(KEY_SPACE));
			} else {
				final Integer key = pickKey(contents, random);
				if (random.nextBoolean()) {
					tree.addRecord(key, 1 + random.nextInt(KEY_SPACE));
				} else {
					final List<Integer> records = contents.get(key);
					tree.removeRecord(key, records.get(random.nextInt(records.size())));
				}
			}
		}
	}

	/**
	 * Reads the bucket tree's whole value-to-records mapping into an `.equals`-comparable reference value.
	 */
	@Nonnull
	private static Map<Integer, List<Integer>> bucketTreeContents(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
		final Map<Integer, List<Integer>> contents = new TreeMap<>();
		final BucketCursor<Integer> cursor = tree.cursor();
		while (cursor.next()) {
			final int[] array = cursor.records().getArray();
			final List<Integer> records = new ArrayList<>(array.length);
			for (final int record : array) {
				records.add(record);
			}
			contents.put(cursor.value(), records);
		}
		return contents;
	}

	/**
	 * Picks a random key present in the given contents.
	 */
	@Nonnull
	private static Integer pickKey(@Nonnull Map<Integer, ?> contents, @Nonnull Random random) {
		int index = random.nextInt(contents.size());
		for (final Integer key : contents.keySet()) {
			if (index-- == 0) {
				return key;
			}
		}
		throw new IllegalStateException("unreachable - the map was not empty");
	}

	/**
	 * Builds a fresh non-transactional bitmap seeded with a random subset of the key space.
	 */
	@Nonnull
	private static TransactionalBitmap newSeededBitmap(@Nonnull Random random) {
		final TransactionalBitmap bitmap = new TransactionalBitmap();
		final int size = random.nextInt(KEY_SPACE);
		for (int i = 0; i < size; i++) {
			bitmap.add(1 + random.nextInt(KEY_SPACE));
		}
		return bitmap;
	}

	/**
	 * Applies `count` random put / remove operations to the map within the current transaction.
	 */
	private static void applyRandomMapOps(@Nonnull TransactionalMap<Integer, Integer> map, @Nonnull Random random, int count) {
		for (int i = 0; i < count; i++) {
			// view-iterator / bulk-view ops (choices >= 2) mutate the diff layer through the entry-set / key-set views,
			// so they require the layer to already exist; when it does not yet, fall back to the direct put/remove that
			// creates it (this is the write path the maintainer's first-touch snapshotting relies on). Outside a
			// transaction there is no layer to wait for - the views write straight to the delegate and are always
			// usable
			final boolean viewOpsUsable = !Transaction.isTransactionAvailable()
				|| Transaction.getTransactionalMemoryLayerIfExists(map) != null;
			switch (random.nextInt(viewOpsUsable ? 5 : 2)) {
				case 0 -> map.remove(random.nextInt(KEY_SPACE));
				case 1 -> map.put(random.nextInt(KEY_SPACE), random.nextInt());
				case 2 -> removeOneViaEntryIterator(map, random);        // entrySet().iterator().remove()
				case 3 -> setOneViaEntryIterator(map, random);           // entry.setValue() (in-place overwrite)
				case 4 -> map.keySet().removeAll(randomKeySubset(random)); // AbstractSet#removeAll -> merged iterator remove
				default -> throw new IllegalStateException("unreachable map op choice");
			}
		}
	}

	/**
	 * Removes a single (randomly positioned) entry through the entry-set iterator, exercising the collection-view
	 * removal path that bypasses the direct mutators.
	 */
	private static void removeOneViaEntryIterator(@Nonnull TransactionalMap<Integer, Integer> map, @Nonnull Random random) {
		final int size = map.size();
		if (size == 0) {
			return;
		}
		int target = random.nextInt(size);
		final Iterator<Entry<Integer, Integer>> it = map.entrySet().iterator();
		while (it.hasNext()) {
			it.next();
			if (target-- == 0) {
				it.remove();
				return;
			}
		}
	}

	/**
	 * Overwrites a single (randomly positioned) entry's value in place through the entry-set view's setValue proxy.
	 */
	private static void setOneViaEntryIterator(@Nonnull TransactionalMap<Integer, Integer> map, @Nonnull Random random) {
		final int size = map.size();
		if (size == 0) {
			return;
		}
		int target = random.nextInt(size);
		final Iterator<Entry<Integer, Integer>> it = map.entrySet().iterator();
		while (it.hasNext()) {
			final Entry<Integer, Integer> entry = it.next();
			if (target-- == 0) {
				entry.setValue(random.nextInt());
				return;
			}
		}
	}

	/**
	 * Builds a small random subset of the key space to drive {@code keySet().removeAll} through the view.
	 */
	@Nonnull
	private static Set<Integer> randomKeySubset(@Nonnull Random random) {
		final Set<Integer> subset = new HashSet<>();
		final int n = 1 + random.nextInt(4);
		for (int i = 0; i < n; i++) {
			subset.add(random.nextInt(KEY_SPACE));
		}
		return subset;
	}

	/**
	 * Applies `count` random add / remove operations to the bitmap within the current transaction.
	 */
	private static void applyRandomBitmapOps(@Nonnull TransactionalBitmap bitmap, @Nonnull Random random, int count) {
		for (int i = 0; i < count; i++) {
			final int recordId = 1 + random.nextInt(KEY_SPACE);
			if (random.nextInt(3) == 0) {
				bitmap.remove(recordId);
			} else {
				bitmap.add(recordId);
			}
		}
	}

	/**
	 * Applies `count` random operations to the bitmap, drawing from EVERY mutator kind rather than only the two
	 * single-record ones: the bulk `addAll` / `removeAll` overloads take their own delegate branch, and the
	 * `Bitmap`-argument overloads reach the roaring `andNot` fast path that mutates whole containers at once - the
	 * shape most likely to write through a copy-on-write slot the first-touch clone still shares.
	 */
	private static void applyRandomBitmapBulkOps(@Nonnull TransactionalBitmap bitmap, @Nonnull Random random, int count) {
		for (int i = 0; i < count; i++) {
			final int[] recordIds = randomRecordIds(random);
			switch (random.nextInt(6)) {
				case 0 -> bitmap.add(recordIds[0]);
				case 1 -> bitmap.remove(recordIds[0]);
				case 2 -> bitmap.addAll(recordIds);
				case 3 -> bitmap.removeAll(recordIds);
				case 4 -> bitmap.addAll(new BaseBitmap(recordIds));
				default -> bitmap.removeAll(new BaseBitmap(recordIds));
			}
		}
	}

	/**
	 * Draws one to four distinct record ids from the key space, sorted ascending as the bulk mutators expect.
	 */
	@Nonnull
	private static int[] randomRecordIds(@Nonnull Random random) {
		final Set<Integer> ids = new HashSet<>();
		final int n = 1 + random.nextInt(4);
		for (int i = 0; i < n; i++) {
			ids.add(1 + random.nextInt(KEY_SPACE));
		}
		final int[] result = new int[ids.size()];
		int index = 0;
		for (final Integer id : ids) {
			result[index++] = id;
		}
		Arrays.sort(result);
		return result;
	}

	/**
	 * Builds a fresh non-transactional set seeded with a random subset of the key space.
	 */
	@Nonnull
	private static TransactionalSet<Integer> newSeededSet(@Nonnull Random random) {
		final Set<Integer> seed = new HashSet<>();
		final int size = random.nextInt(KEY_SPACE);
		for (int i = 0; i < size; i++) {
			seed.add(random.nextInt(KEY_SPACE));
		}
		return new TransactionalSet<>(seed);
	}

	/**
	 * Applies `count` random operations to the set, spread across every mutating path it exposes — the single-element
	 * mutators, the three bulk operations (whose delegate-branch implementations differ from one another) and removal
	 * through the iterator, which reaches the delegate without passing through any mutator at all.
	 */
	private static void applyRandomSetOps(@Nonnull TransactionalSet<Integer> set, @Nonnull Random random, int count) {
		for (int i = 0; i < count; i++) {
			switch (random.nextInt(6)) {
				case 0 -> set.add(random.nextInt(KEY_SPACE));
				case 1 -> set.remove(random.nextInt(KEY_SPACE));
				case 2 -> set.addAll(randomKeySubset(random));
				case 3 -> set.removeAll(randomKeySubset(random));
				case 4 -> set.retainAll(randomKeySubset(random));
				case 5 -> removeOneViaIterator(set, random);
				default -> throw new IllegalStateException("unreachable set op choice");
			}
		}
	}

	/**
	 * Removes a single (randomly positioned) element through the set's iterator.
	 */
	private static void removeOneViaIterator(@Nonnull TransactionalSet<Integer> set, @Nonnull Random random) {
		final int size = set.size();
		if (size == 0) {
			return;
		}
		int target = random.nextInt(size);
		final Iterator<Integer> it = set.iterator();
		while (it.hasNext()) {
			it.next();
			if (target-- == 0) {
				it.remove();
				return;
			}
		}
	}

	/**
	 * Builds a fresh non-transactional list seeded with a random sequence drawn from the key space.
	 */
	@Nonnull
	private static TransactionalList<Integer> newSeededList(@Nonnull Random random) {
		final List<Integer> seed = new ArrayList<>();
		final int size = random.nextInt(KEY_SPACE);
		for (int i = 0; i < size; i++) {
			seed.add(random.nextInt(KEY_SPACE));
		}
		return new TransactionalList<>(seed);
	}

	/**
	 * Applies `count` random operations to the list. The positional mutators are the point of this one: an insertion
	 * or a removal shifts every element after it, so their inverses are only correct if the journal's reverse replay
	 * un-shifts them in the right order — which a randomized interleaving is far better at disproving than any
	 * hand-written sequence.
	 */
	private static void applyRandomListOps(
		@Nonnull TransactionalList<Integer> list, @Nonnull Random random, int count
	) {
		for (int i = 0; i < count; i++) {
			final int size = list.size();
			// every choice but the plain append needs a position to address, so an empty list only appends
			switch (size == 0 ? 0 : random.nextInt(6)) {
				case 0 -> list.add(random.nextInt(KEY_SPACE));
				case 1 -> list.add(random.nextInt(size + 1), random.nextInt(KEY_SPACE));
				case 2 -> list.set(random.nextInt(size), random.nextInt(KEY_SPACE));
				case 3 -> list.remove(random.nextInt(size));
				case 4 -> list.remove(Integer.valueOf(random.nextInt(KEY_SPACE)));
				case 5 -> removeOneViaIterator(list, random);
				default -> throw new IllegalStateException("unreachable list op choice");
			}
		}
	}

	/**
	 * Removes a single (randomly positioned) element through the list's iterator.
	 */
	private static void removeOneViaIterator(@Nonnull TransactionalList<Integer> list, @Nonnull Random random) {
		final int size = list.size();
		if (size == 0) {
			return;
		}
		int target = random.nextInt(size);
		final Iterator<Integer> it = list.iterator();
		while (it.hasNext()) {
			it.next();
			if (target-- == 0) {
				it.remove();
				return;
			}
		}
	}

	/**
	 * Reads the bitmap's logical contents into an `.equals`-comparable ordered list.
	 */
	@Nonnull
	private static List<Integer> bitmapContents(@Nonnull TransactionalBitmap bitmap) {
		final int[] array = bitmap.getArray();
		final List<Integer> contents = new ArrayList<>(array.length);
		for (final int value : array) {
			contents.add(value);
		}
		return contents;
	}

}
