/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.query.algebra.base;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Differential test for EVERY implementation of the signed-multiplicity computation - {@link RangeCountKernel}'s
 * per-chunk scatter at both counter widths, and {@link RangeBitSlicedKernel}'s binary planes of roaring bitmaps.
 * Every case is checked against an independent map-based reference that shares no code with any of them.
 *
 * Holding independent implementations to the same oracle is deliberate: they fail differently, so a case they all
 * pass is far stronger evidence than a case one passes.
 *
 * The two counter widths are not independent implementations but hand copies of one another, and they are covered
 * for the opposite reason: the `int` copy exists for an operand count no production shape reaches, so nothing else
 * in this repository would notice it drifting away from the `short` one. See `assertAgrees`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(QUERY)
@DisplayName("Every range counting kernel equals an independent counting reference")
class RangeCountKernelTest {

	/**
	 * Independent reference: counts memberships in a `TreeMap` and keeps the strictly-positive ones.
	 *
	 * This deliberately shares no code with either kernel and no code with the formulas that used to compute this.
	 * It is a direct transcription of the specification - `{ v : count(plus, v) - count(minus, v) > 0 }` - so a
	 * bug would have to appear identically in a map-based counter, a chunked scatter and a bit-sliced plane
	 * network to go unnoticed.
	 *
	 * The `JoinFormula` -> `DisentangleFormula` pair was the oracle while it still existed, and both kernels were
	 * confirmed byte-identical to it across this whole suite before it was removed; this reference reproduces that
	 * contract without depending on deleted code.
	 *
	 * @param plus  bitmaps contributing `+1`
	 * @param minus bitmaps contributing `-1`
	 * @return record ids whose signed count is strictly positive, ascending
	 */
	@Nonnull
	private static int[] reference(@Nonnull Bitmap[] plus, @Nonnull Bitmap[] minus) {
		final TreeMap<Integer, Integer> counts = new TreeMap<>();
		for (final Bitmap bitmap : plus) {
			for (final int value : bitmap.getArray()) {
				counts.merge(value, 1, Integer::sum);
			}
		}
		for (final Bitmap bitmap : minus) {
			for (final int value : bitmap.getArray()) {
				counts.merge(value, -1, Integer::sum);
			}
		}
		return counts.entrySet().stream()
			.filter(it -> it.getValue() > 0)
			.mapToInt(Map.Entry::getKey)
			.toArray();
	}

	/**
	 * Asserts every implementation and the reference agree, reporting the operands when they do not.
	 *
	 * Three implementations are checked, not two. `RangeCountKernel.compute` selects its counter width from the
	 * operand count, so on every ordinary family it runs the `short` kernel and the `int` sibling is never entered -
	 * which is exactly why the `int` sibling is also called here EXPLICITLY, on the same operands. It is a hand copy
	 * of the `short` kernel that production traffic never reaches, so without this line a scatter fix applied to one
	 * copy and not the other would pass the entire suite. Running it on inputs far below the width it exists for is
	 * the point: correctness is width-independent, so the copies must agree everywhere, not only past the threshold.
	 *
	 * @param plus  bitmaps contributing `+1`
	 * @param minus bitmaps contributing `-1`
	 */
	private static void assertAgrees(@Nonnull Bitmap[] plus, @Nonnull Bitmap[] minus) {
		final int[] expected = reference(plus, minus);
		final int[] scatter = RangeCountKernel.compute(plus, minus).getArray();
		final int[] wide = RangeCountKernel.computeWide(plus, minus).getArray();
		final int[] sliced = RangeBitSlicedKernel.compute(plus, minus).getArray();
		assertArrayEquals(
			expected, scatter,
			() -> "SCATTER kernel disagrees with the counting reference\n  plus  = " + describe(plus) +
				"\n  minus = " + describe(minus) +
				"\n  expected = " + Arrays.toString(expected) + "\n  actual   = " + Arrays.toString(scatter)
		);
		assertArrayEquals(
			expected, wide,
			() -> "WIDE scatter kernel disagrees with the counting reference - the `int` copy has diverged from " +
				"the `short` one\n  plus  = " + describe(plus) +
				"\n  minus = " + describe(minus) +
				"\n  expected = " + Arrays.toString(expected) + "\n  actual   = " + Arrays.toString(wide)
		);
		assertArrayEquals(
			expected, sliced,
			() -> "BIT-SLICED kernel disagrees with the counting reference\n  plus  = " + describe(plus) +
				"\n  minus = " + describe(minus) +
				"\n  expected = " + Arrays.toString(expected) + "\n  actual   = " + Arrays.toString(sliced)
		);
	}

	/**
	 * Renders a family compactly for a failure message.
	 *
	 * @param family the operands to render
	 * @return a readable rendering
	 */
	@Nonnull
	private static String describe(@Nonnull Bitmap[] family) {
		final StringBuilder sb = new StringBuilder(128);
		sb.append('[');
		for (int i = 0; i < family.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(Arrays.toString(family[i].getArray()));
		}
		return sb.append(']').toString();
	}

	/**
	 * Builds a bitmap from the given record ids.
	 *
	 * @param values the record ids
	 * @return a bitmap holding them, or the empty singleton
	 */
	@Nonnull
	private static Bitmap bitmap(int... values) {
		return values.length == 0 ? EmptyBitmap.INSTANCE : new BaseBitmap(values);
	}

	/**
	 * Builds one randomised family of operands.
	 *
	 * @param random       source of randomness
	 * @param maxOperands  upper bound on operands
	 * @param maxPerBitmap upper bound on record ids per operand
	 * @param idSpace      exclusive upper bound on a record id
	 * @return the family, possibly containing empty bitmaps
	 */
	@Nonnull
	private static Bitmap[] randomFamily(@Nonnull Random random, int maxOperands, int maxPerBitmap, int idSpace) {
		final int operandCount = random.nextInt(maxOperands + 1);
		final Bitmap[] family = new Bitmap[operandCount];
		for (int i = 0; i < operandCount; i++) {
			final int size = random.nextInt(maxPerBitmap + 1);
			final TreeSet<Integer> values = new TreeSet<>();
			for (int j = 0; j < size; j++) {
				values.add(random.nextInt(idSpace) + 1);
			}
			final int[] array = new int[values.size()];
			int index = 0;
			for (final Integer value : values) {
				array[index++] = value;
			}
			family[i] = bitmap(array);
		}
		return family;
	}

	@Nested
	@DisplayName("Hand-written cases")
	class Explicit {

		@Test
		@DisplayName("A duplicate-carrying family against a wider counterpart")
		void shouldCountDuplicatesAcrossOperandsOfOneFamily() {
			// counts, not sets: 3 is carried by TWO plus operands and TWO minus operands, so it cancels; 9 is
			// carried once on the plus side and not at all on the minus side, so it survives. Written out, the
			// multiplicities are [3,3,6,9,12] against [2,3,3,4,6,8,10,12] and the answer is [9].
			assertAgrees(
				new Bitmap[]{bitmap(3, 6, 9, 12), bitmap(3)},
				new Bitmap[]{bitmap(2, 3, 4, 6, 8, 10, 12), bitmap(3)}
			);
		}

		@Test
		@DisplayName("An empty minus family degenerates to the union of the plus family")
		void shouldDegenerateToUnionWhenMinusIsEmpty() {
			assertAgrees(new Bitmap[]{bitmap(1, 5, 9), bitmap(5, 7)}, new Bitmap[0]);
		}

		@Test
		@DisplayName("An empty plus family yields nothing whatever the minus family holds")
		void shouldYieldNothingWhenPlusIsEmpty() {
			assertAgrees(new Bitmap[0], new Bitmap[]{bitmap(1, 2, 3)});
		}

		@Test
		@DisplayName("Equal multiplicity cancels, strictly greater survives")
		void shouldKeepOnlyStrictlyPositiveCounts() {
			// 7 appears twice on each side (cancels); 8 appears twice on the plus side and once on the minus (survives)
			assertAgrees(
				new Bitmap[]{bitmap(7, 8), bitmap(7, 8)},
				new Bitmap[]{bitmap(7, 8), bitmap(7)}
			);
		}

		@Test
		@DisplayName("Operands spanning several 65536-value chunks")
		void shouldSpanMultipleChunks() {
			assertAgrees(
				new Bitmap[]{bitmap(1, 70_000, 140_000), bitmap(70_000, 200_001)},
				new Bitmap[]{bitmap(1, 140_000)}
			);
		}

		@Test
		@DisplayName("An operand that crosses a chunk boundary only on a REFILL, not on its first batch")
		void shouldCrossAChunkBoundaryDiscoveredByARefill() {
			// shouldSpanMultipleChunks crosses a boundary too, but its operands are small enough to arrive whole in
			// the first batch, so the crossing is always found inside a batch the cursor is already draining. The
			// other half of the mechanism is a cursor that drains a batch to its END while still inside the chunk
			// and only learns of the boundary from the values the NEXT batch brings - it then files itself into a
			// higher bucket having consumed nothing from that batch. Nothing else in this suite reaches it: the
			// randomised sweeps cap an operand at 64 ids, below the batch size, so they never refill at all.
			//
			// Putting a whole power-of-two count of ids in the low chunk is what forces the split to land on a batch
			// boundary rather than inside one. 1,024 keeps that true for any power-of-two batch size up to 1,024,
			// so the fixture does not silently stop testing this if the kernel's BATCH_SIZE is retuned.
			final int[] values = new int[1_026];
			for (int i = 0; i < 1_024; i++) {
				values[i] = 64_000 + i;
			}
			values[1_024] = 70_000;
			values[1_025] = 70_001;
			assertAgrees(
				new Bitmap[]{bitmap(values)},
				new Bitmap[]{bitmap(64_000, 70_000)}
			);
		}

		@Test
		@DisplayName("Identical families cancel to nothing without needing a special guard")
		void shouldCancelWhenBothFamiliesAreIdentical() {
			// the pair being replaced needed an explicit `disentangle(X, X) = empty` guard because FormulaCloner /
			// FormulaDeduplicator could unify its two positional siblings. RangeCountFormula holds bitmaps rather
			// than child formulas, so there is nothing to unify - but the ARITHMETIC must still land on empty on
			// its own, which is what this asserts.
			final Bitmap[] family = {bitmap(1, 2, 3), bitmap(2, 5), bitmap(9)};
			assertAgrees(family, family);
		}

		@Test
		@DisplayName("A record present in every operand of both families")
		void shouldHandleTotalOverlap() {
			assertAgrees(
				new Bitmap[]{bitmap(5), bitmap(5), bitmap(5)},
				new Bitmap[]{bitmap(5), bitmap(5), bitmap(5)}
			);
		}

		@Test
		@DisplayName("A negative counter left by one chunk does not survive into the next")
		void shouldClearNegativeCountersBeforeTheNextChunk() {
			// Offset 1 of chunk 0 ends at -1. Offset 1 of chunk 1 (record 65,537) ends at +1 and must be emitted -
			// which it only can be if the emission walk zeroed the negative counter unconditionally rather than
			// clearing "only what was emitted". Leave the -1 behind and 65,537 nets to 0 and silently disappears.
			//
			// The two chunks share one pooled counter array, so this is also the cheapest check that a chunk hands
			// that array on clean.
			assertAgrees(
				new Bitmap[]{bitmap(1, 60_000, 65_537, 125_536)},
				new Bitmap[]{bitmap(1), bitmap(1)}
			);
		}

		@Test
		@DisplayName("An offset whose counter passes back through zero mid-scatter")
		void shouldHandleAnOffsetWhoseCounterReturnsToZeroMidScatter() {
			// Offset 9 is written three times: +1 by the plus operand, then -1 and -1 by the two minus operands.
			// The counter therefore sits at zero after the second write and leaves zero again on the third. This is
			// the sequence that rules out ever deciding membership of the touched map from the counter's value - the
			// map records that an offset was WRITTEN, never that it currently holds something, which is why the bit
			// is set on every write and cleared only by emission.
			assertAgrees(
				new Bitmap[]{bitmap(9, 40_000)},
				new Bitmap[]{bitmap(9), bitmap(9)}
			);
		}

		@Test
		@DisplayName("A chunk written past the touched list's capacity falls back to the span scan")
		void shouldFallBackToTheSpanScanWhenTheTouchedListOverflows() {
			// 20 operands of 500 ids each put 10,000 writes into chunk 0 across a span of 59,999 - far more than any
			// one chunk carries on the shapes the randomised sweeps generate, and more than the 8,192 the touched
			// list holds. So this is the ONLY case that reaches the overflow fallback: the list is abandoned partway
			// and emission scans [minLow, maxLow] instead. Both paths read the same counters - the scatter's
			// `counters[low] += sign` runs whether or not the list is still usable - so what this pins is that the
			// answer and the state handed on are the same either way.
			final Bitmap[] plus = new Bitmap[20];
			for (int operand = 0; operand < plus.length; operand++) {
				final int[] values = new int[500];
				for (int i = 0; i < values.length; i++) {
					// stride 120 keeps every id distinct inside an operand AND across operands, so the write count
					// is exactly 20 * 500 and does not depend on a set collapsing duplicates
					values[i] = 1 + operand + i * 120;
				}
				plus[operand] = bitmap(values);
			}
			// 60,000 is not of the form 1 + operand + i * 120 for operand < 20, so it is cancelled and nothing else is
			final Bitmap[] minus = {bitmap(60_000)};
			assertAgrees(plus, minus);

			// an emission that missed offsets would leave dirty counters in the POOLED array, so a second, tiny
			// computation on the same thread is the cheapest available check that the array came back clean
			assertAgrees(new Bitmap[]{bitmap(3, 7)}, new Bitmap[]{bitmap(7)});
		}

		@Test
		@DisplayName("Record ids at the top of the integer range")
		void shouldCountRecordIdsAtTheTopOfTheIntegerRange() {
			// the highest chunk index is 32,767 and its base is 32,767 << 16 = 2,147,418,112 - still positive, which
			// is the property emission addressing depends on. Integer.MAX_VALUE is that chunk's last offset.
			final int topChunkBase = 32_767 << 16;
			assertAgrees(
				new Bitmap[]{bitmap(1, topChunkBase, Integer.MAX_VALUE)},
				new Bitmap[]{bitmap(topChunkBase)}
			);
		}
	}

	@Nested
	@DisplayName("Randomised families")
	class Randomised {

		/**
		 * Compares kernel and reference over randomised families.
		 *
		 * @param iterations   how many families to try
		 * @param maxOperands  upper bound on operands per family
		 * @param maxPerBitmap upper bound on record ids per operand
		 * @param idSpace      exclusive upper bound on a record id
		 */
		private static void runSweep(int iterations, int maxOperands, int maxPerBitmap, int idSpace) {
			for (int iteration = 0; iteration < iterations; iteration++) {
				final Random random = new Random(iteration * 7919L + 13L);
				final Bitmap[] plus = randomFamily(random, maxOperands, maxPerBitmap, idSpace);
				final Bitmap[] minus = randomFamily(random, maxOperands, maxPerBitmap, idSpace);
				assertAgrees(plus, minus);
			}
		}

		@Test
		@DisplayName("Dense small-id families agree over many seeds")
		void shouldAgreeOnDenseSmallIdFamilies() {
			runSweep(1_000, 12, 512, 4);
		}

		@Test
		@DisplayName("Sparse wide-id families spanning many chunks agree over many seeds")
		void shouldAgreeOnSparseWideIdFamilies() {
			runSweep(400, 8, 64, 5_000_000);
		}

		@Test
		@DisplayName("Many operands - the high-cardinality shape a real range query builds")
		void shouldAgreeOnHighCardinalityFamilies() {
			runSweep(60, 250, 40, 20_000);
		}

	}

	@Nested
	@DisplayName("Counterfactual - the test must be able to fail")
	class Counterfactual {

		@Test
		@DisplayName("The reference itself distinguishes a strictly-positive count from a cancelled one")
		void shouldProveTheOracleIsSensitive() {
			// if this ever stops holding, every assertAgrees above is comparing two constants and proves nothing
			assertArrayEquals(
				new int[]{8},
				reference(new Bitmap[]{bitmap(7, 8), bitmap(7, 8)}, new Bitmap[]{bitmap(7, 8), bitmap(7)})
			);
			assertEquals(
				0,
				reference(new Bitmap[]{bitmap(7), bitmap(7)}, new Bitmap[]{bitmap(7), bitmap(7)}).length
			);
		}
	}

	@Nested
	@DisplayName("Concurrent reuse of the kernel's pooled scratch")
	class ConcurrentReuse {

		@Test
		@DisplayName("Threads borrowing the shared counter pools all get their own answer")
		void shouldServeConcurrentComputationsFromTheSharedPools() {
			// The kernel keeps its counter arrays, touched maps and cursor state in static pools of capacity 8.
			// Sequential
			// reuse is well covered by the randomised sweeps above; CONCURRENT reuse is what this adds, because the
			// failure mode - a dirty array handed back to the pool - corrupts a LATER, unrelated query far away from
			// whatever caused it.
			//
			// This assertion is negative in shape: every interleaving must produce the right answer, so a loaded
			// machine can only explore FEWER interleavings, never fail spuriously. That is why it belongs in the
			// fast loop and why it needs no timing tuning - do not convert the latch below into a sleep loop.
			final int threadCount = 12;
			try (
				ExecutorService executor = Executors.newFixedThreadPool(
					threadCount,
					runnable -> {
						final Thread thread = new Thread(runnable, "range-count-kernel-pool-probe");
						thread.setDaemon(true);
						return thread;
					}
				)
			) {
				try {
					final CountDownLatch start = new CountDownLatch(1);
					final CountDownLatch finished = new CountDownLatch(threadCount);
					final AtomicReference<Throwable> failure = new AtomicReference<>();
					for (int worker = 0; worker < threadCount; worker++) {
						final long seedBase = worker * 1_000_003L;
						executor.execute(() -> {
							try {
								start.await();
								for (int iteration = 0; iteration < 150; iteration++) {
									final Random random = new Random(seedBase + iteration);
									assertAgrees(
										randomFamily(random, 10, 48, 250_000),
										randomFamily(random, 10, 48, 250_000)
									);
								}
							} catch (final Throwable ex) {
								failure.compareAndSet(null, ex);
							} finally {
								finished.countDown();
							}
						});
					}
					start.countDown();
					// positive wait - generous on purpose; it returns the instant the work completes and only a genuine
					// hang can exhaust it
					assertTrue(
						finished.await(30, TimeUnit.SECONDS),
						"The concurrent kernel computations did not finish within 30 seconds"
					);
					final Throwable observed = failure.get();
					if (observed != null) {
						fail("A concurrent kernel computation disagreed with the reference", observed);
					}
				} catch (final InterruptedException ex) {
					Thread.currentThread().interrupt();
					fail("Interrupted while waiting for the concurrent kernel computations", ex);
				} finally {
					executor.shutdownNow();
				}
			}
		}
	}

	@Nested
	@DisplayName("Counter width selection")
	class CounterWidth {

		@Test
		@DisplayName("A record present in every operand survives at the widest narrow-counter family")
		void shouldCountARecordScatteredByEveryOperandAtTheNarrowLimit() {
			// Short.MAX_VALUE operands in total - the widest family the `short` counters are still exact for. A
			// bitmap is a set, so record 7 collects exactly one increment per operand and its final count is 32,766:
			// inside the short range, and therefore a case the scatter kernel must answer WITHOUT widening anything.
			final Bitmap[] plus = new Bitmap[Short.MAX_VALUE - 1];
			Arrays.fill(plus, bitmap(7));
			// a non-empty minus family, so the computation is the real two-family one rather than a plain union
			final Bitmap[] minus = {bitmap(8)};

			assertAgrees(plus, minus);
			assertArrayEquals(new int[]{7}, RangeCountKernel.compute(plus, minus).getArray());
		}

		@Test
		@DisplayName("A record whose signed count passes the short range is counted on the wide kernel")
		void shouldCountARecordWhoseSignedCountPassesTheShortRange() {
			final Bitmap seven = bitmap(7);
			final Bitmap[] plus = new Bitmap[32_768];
			Arrays.fill(plus, seven);
			final Bitmap[] minus = {bitmap(8)};

			// 32,769 operands in total, so the width selection takes the `int` kernel - and it has to, because
			// record 7's final count is 32,768, one past what a `short` counter can represent. Accumulated in a
			// short it wraps to Short.MIN_VALUE and the `count > 0` emission test discards a plainly valid record.
			assertAgrees(plus, minus);
			assertArrayEquals(new int[]{7}, RangeCountKernel.compute(plus, minus).getArray());
		}

		@Test
		@DisplayName("A signed count that would wrap a short counter to exactly zero is still counted")
		void shouldCountARecordWhoseSignedCountWrapsAShortCounterToZero() {
			final Bitmap seven = bitmap(7);
			final Bitmap[] plus = new Bitmap[65_536];
			Arrays.fill(plus, seven);
			final Bitmap[] minus = {bitmap(8)};

			// the sibling case of the one above, and the one a sign-only fix would still get wrong: 65,536 is
			// exactly 2^16, so a short counter wraps back to ZERO rather than to a negative value, and `count > 0`
			// rejects it just the same. Only the reference and the scatter kernel are compared here - the bit-sliced
			// sibling sizes its planes from the operand count and so cannot express this failure mode at all, and
			// its plane build is O(operands x planes), which at this width is pure cost for no extra evidence.
			assertArrayEquals(new int[]{7}, reference(plus, minus));
			assertArrayEquals(new int[]{7}, RangeCountKernel.compute(plus, minus).getArray());
		}
	}
}
