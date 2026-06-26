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

import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;

/**
 * Validates the per-entity savepoint fuzz / oracle framework itself. Drives randomized
 * baseline + in-savepoint mutation batches through
 * {@link io.evitadb.utils.AssertionUtils#assertSavepointRollbackRestores} and
 * {@link io.evitadb.utils.AssertionUtils#assertSavepointCommitKeeps} against two structures whose delta types are
 * already {@code Snapshotable} ({@link TransactionalMap} via {@code MapChanges} and {@link TransactionalBitmap} via
 * {@code BitmapChanges}). Proving the harness on known-good structures both retro-validates the snapshot
 * implementations and establishes the harness as a trustworthy oracle for the remaining (delicate) layer types.
 *
 * Each case asserts the in-savepoint batch actually changed the structure (non-vacuous), so a no-op rollback could
 * not pass by accident. The run is time-bounded; the random seed is echoed on failure for deterministic reproduction.
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
					// a marker key outside the random range guarantees the in-savepoint batch changes the map,
					// so the rollback assertion is never vacuously satisfied by a no-op batch
					tested.put(KEY_SPACE + 1, Integer.MIN_VALUE);
					applyRandomMapOps(tested, random, 1 + random.nextInt(8));
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
					// a record id outside the random range guarantees the in-savepoint batch changes the bitmap
					tested.add(KEY_SPACE + 1);
					applyRandomBitmapOps(tested, random, 1 + random.nextInt(8));
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
			final int key = random.nextInt(KEY_SPACE);
			if (random.nextInt(3) == 0) {
				map.remove(key);
			} else {
				map.put(key, random.nextInt());
			}
		}
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
