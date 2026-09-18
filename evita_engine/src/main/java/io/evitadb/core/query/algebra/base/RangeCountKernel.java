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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.BatchIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Computes the *signed multiplicity* of two families of bitmaps:
 *
 * ```
 * result = { v : count(plus, v) - count(minus, v) > 0 },   count(F, v) = |{ i : v in F[i] }|
 * ```
 *
 * This is exactly what a `JoinFormula` feeding a `DisentangleFormula` used to compute in two passes, and it is what
 * every {@link io.evitadb.index.range.RangeIndex} query needs: a record is valid at a threshold when it started more
 * times than it ended over the scanned prefix (or ended more times than it started over the scanned suffix).
 *
 * ## How the output is produced
 *
 * The whole kernel is one idea: **add up `+1`s and `-1`s in a plain array, one 65 536-id chunk at a time.**
 * Everything else is bookkeeping around that.
 *
 * The two axes are easy to confuse, so they are worth stating once. An OPERAND is one threshold point: `plus[i]`
 * holds the record ids whose range STARTS at the i-th collected threshold, `minus[j]` the ids whose range ENDS at the
 * j-th. The record ids INSIDE those bitmaps are what gets counted. Everything below partitions RECORD IDS and never
 * thresholds - by the time the kernel runs, the thresholds have already been reduced to the choice of which bitmaps
 * were passed in, and {@link io.evitadb.index.range.RangeIndex} made that choice when it stopped its walk.
 *
 * One counter per record id would need an array the size of the id space, so an id is split into a chunk and an
 * offset inside it - `chunk = id >>> 16`, `offset = id & 0xFFFF` - and only one chunk is ever open. Its counters are
 * a single reusable `short[65536]`: 128 KiB, cache-resident, indexed directly by the offset.
 *
 * Every operand bitmap gets a cursor yielding its ids in ascending order, in batches, carrying the sign its family
 * contributes - `+1` for `plus`, `-1` for `minus`. Ascending order is the property everything below rests on. Then,
 * for each chunk in ascending order:
 *
 * 1. **Take the chunk's cursors** - every cursor whose next id falls in this chunk is already filed in this chunk's
 *    bucket, so they are reached by walking one intrusive linked list. Nothing scans the operands. See
 *    {@link CursorSet} for why that matters more than it sounds.
 * 2. **Scatter** - every cursor in the bucket drains its ids for this chunk, doing `counters[offset] += sign` and
 *    appending the offset to a touched list, and nothing else. No comparisons between cursors, no ordering, no
 *    merging; just adds into an array and a sequential append. A cursor that survives the chunk re-files itself
 *    into the bucket of the chunk its next id belongs to.
 * 3. **Emit** - the inputs are ascending and every cursor has now moved past this chunk, so no id in it can ever be
 *    touched again: the counters ARE the final signed differences. The touched list is walked once; each offset
 *    reads its counter, is zeroed, and contributes a bit to a 1 024-word bitmap when the counter is `> 0`.
 * 4. **Hand the chunk over whole** - that word bitmap is the chunk's output in the `long[1024]` shape
 *    {@link RoaringBitmapWriter#addChunk} takes, so it is given to the writer as one container instead of one
 *    `add` per id.
 *
 * Two things about step 2 are worth stating, because both look wrong until measured. The append is **sequential**;
 * setting a bit in the word bitmap there instead would deduplicate for free and remove the capacity bound, and was
 * measured **5x more expensive in the scatter loop's self time** - a random read-modify-write carries a load-use
 * dependency an append does not. And the list records **every** write, duplicates included, which needs no
 * visited-set because the first visit to an offset zeroes its counter and every later one then reads zero.
 *
 * A tiny worked example - `plus = [{3, 70000}, {3, 9}]`, `minus = [{9}]`:
 *
 * ```
 * chunk 0  (ids 0 .. 65 535)        counters[3]    = +1 +1 = +2  ->  emit 3
 *                                   counters[9]    = +1 -1 =  0  ->  dropped
 * chunk 1  (ids 65 536 .. 131 071)  counters[4464] = +1          ->  emit 65 536 | 4464 = 70 000
 *
 * result = {3, 70 000}
 * ```
 *
 * Record 9 is the case the whole design exists for: it appears in both families and disappears by ARITHMETIC, with
 * no cancellation step anywhere - which is what the replaced pair needed a second full merge pass to achieve.
 *
 * ## Why counting beats merging
 *
 * The pair it replaces paid `O(N log k)` for the k-way merge that materialized the duplicates plus `O(N)` for the
 * merge that cancelled them, with a virtual call and a priority-queue reheap per element. The duplicates were never
 * an output - only an encoding of the counts - so this kernel accumulates the counts directly and never materializes
 * them. It is `O(N)` scatters plus one bounded word pass per touched 65 536-value chunk.
 *
 * ## Counter width, and how it is chosen
 *
 * The accumulation is deliberately allowed to wrap. Two's-complement addition is exact modulo the counter width, so
 * the accumulated value always equals the true signed difference modulo that width regardless of the order the
 * operands were applied in - an intermediate that overflows cancels back correctly. Only the **final** difference has
 * to be representable, and that is what selects the width.
 *
 * A chunk partitions record IDS, not ranges. A record has exactly one id, so every range it holds scatters into that
 * record's single chunk: the final difference is bounded by the record's TOTAL range count and not by any per-chunk
 * share of it. What does bound it is the operand count - a bitmap is a set, so one record id is scattered at most once
 * per operand, which leaves the final difference for any slot inside `[-minus.length, +plus.length]`.
 *
 * {@link #compute(Bitmap[], Bitmap[])} therefore selects the width once per computation, before the first scatter:
 * up to {@link Short#MAX_VALUE} operands across both families run the `short` kernel, anything wider runs the `int`
 * sibling. The two are otherwise identical - same chunking, same emission, same pooling discipline - and the
 * selection costs one comparison per call, nothing inside the scatter loop.
 *
 * ## Container access
 *
 * The kernel deliberately works through the public {@link BatchIterator} API rather than the roaring containers. The
 * vendored module exposes `getContainerPointer()` but types it with a package-private interface, so container-level
 * dispatch (a run-container prefix scan, bit-sliced adders over dense containers) is not reachable from this module
 * without widening that fork. That widening is a separate decision and belongs with the container kernels, not here.
 *
 * Two *additive* widenings the fork does carry for this kernel, neither of which exposes a container:
 * {@link BatchIterator#nextBatch(int[], int, int)}, so thousands of cursors can share one arena array instead of each
 * allocating a buffer, and {@link RoaringBitmapWriter#addChunk(char, long[], int, int)}, so a finished chunk crosses
 * into the writer as words rather than as one call per id.
 *
 * ## Where the cost actually sits
 *
 * Measured at k = 64 over N = 29,159 (JMH sampled stacks, both arms on one box): emission ~154 us, container
 * materialisation inside the writer ~68 us, the scatter loop itself ~12 us, cursor refills ~27 us. **Emission
 * dominates and the scatter does not**, which is why this class spends its complexity budget on step 3 and leaves
 * step 2 as a bare add plus an append.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@SuppressWarnings("CheckForOutOfMemoryOnLargeArrayAllocation")
final class RangeCountKernel {
	/**
	 * Number of low bits addressed by one counter chunk - the roaring container granularity.
	 */
	private static final int CHUNK_BITS = 16;
	/**
	 * Number of counters in one chunk.
	 */
	private static final int CHUNK_SIZE = 1 << CHUNK_BITS;
	/**
	 * Mask extracting the in-chunk offset of a record id.
	 */
	private static final int LOW_MASK = CHUNK_SIZE - 1;
	/**
	 * Largest slice of the shared arena a single cursor may own; mirrors the batch size `JoinFormula` used.
	 */
	private static final int BATCH_SIZE = 256;
	/**
	 * Words in one chunk's touched map - `1024` longs = 65 536 bits, the same layout
	 * {@link RoaringBitmapWriter#addChunk(char, long[], int, int)} consumes.
	 */
	private static final int WORD_COUNT = CHUNK_SIZE >>> 6;
	/**
	 * How many scatter writes one chunk may record before emission falls back to scanning its whole span.
	 *
	 * This is a **capacity bound, not a crossover**. While the list fits, walking it is unconditionally cheaper
	 * than scanning the span - at most 8 192 reads against up to 65 536 - so there is nothing to tune and no
	 * density predicate to get wrong. A chunk that overruns it is by construction denser than one write per eight
	 * offsets, which is exactly the chunk a span scan handles well.
	 */
	private static final int TOUCHED_CAPACITY = 8192;
	/**
	 * Largest combined operand count whose signed counts are guaranteed to fit a `short` counter.
	 *
	 * A bitmap is a set, so one record id is scattered at most once per operand and the final difference for any slot
	 * lies in `[-minus.length, +plus.length]`. Staying at or below {@link Short#MAX_VALUE} operands in total therefore
	 * keeps both ends of that interval representable, whatever the operands hold.
	 */
	private static final int NARROW_COUNTER_LIMIT = Short.MAX_VALUE;
	/**
	 * Largest cursor-slot count a {@link CursorSet} may carry back into the pool.
	 *
	 * The three retention caps exist because {@link CursorSet}'s arrays are sized by the computation that borrowed
	 * it and never shrink: without them a single outsized query would park its arrays in the pool for the lifetime
	 * of the JVM. Each is set an order of magnitude above the shape the kernel was measured on, so the common path
	 * always pools and only a genuine outlier is dropped.
	 */
	private static final int MAX_POOLED_CURSORS = 1 << 14;
	/**
	 * Largest shared batch arena, in ints, a {@link CursorSet} may carry back into the pool - 256 KiB.
	 */
	private static final int MAX_POOLED_ARENA = 1 << 16;
	/**
	 * Largest bucket table, in chunks, a {@link CursorSet} may carry back into the pool - 4 096 chunks, or a record
	 * id space of 268 million.
	 */
	private static final int MAX_POOLED_BUCKETS = 1 << 12;
	/**
	 * Bounded pool of the 128 KiB counter arrays. A bounded pool rather than a {@link ThreadLocal}: per-thread
	 * striping keeps one array alive per carrier thread, which is hostile to virtual threads and was rejected for
	 * that reason when the usage-statistics work faced the same choice.
	 */
	private static final ArrayBlockingQueue<short[]> COUNTER_POOL = new ArrayBlockingQueue<>(8);
	/**
	 * Bounded pool of the 256 KiB counter arrays the wide kernel uses. Kept apart from {@link #COUNTER_POOL} so a
	 * single wide computation cannot evict the narrow arrays the common path borrows.
	 */
	private static final ArrayBlockingQueue<int[]> WIDE_COUNTER_POOL = new ArrayBlockingQueue<>(8);
	/**
	 * Bounded pool of the per-chunk emission scratch - the touched-offset list and the word bitmap the surviving
	 * ids are gathered into before they cross into the writer.
	 */
	private static final ArrayBlockingQueue<Emission> EMISSION_POOL = new ArrayBlockingQueue<>(8);
	/**
	 * Bounded pool of the per-call cursor state. Pooling it is the point of {@link CursorSet} existing at all: the
	 * arrays it holds are sized by the operand count, which is the one input that grows without bound, so allocating
	 * them per call is exactly the cost this structure was introduced to remove.
	 */
	private static final ArrayBlockingQueue<CursorSet> CURSOR_SET_POOL = new ArrayBlockingQueue<>(8);

	private RangeCountKernel() {
		throw new UnsupportedOperationException("Kernel holder must not be instantiated!");
	}

	/**
	 * Computes the signed multiplicity result of the two families, over counters wide enough to represent it.
	 *
	 * The width is decided here and nowhere else: a record id is scattered at most once per operand, so up to
	 * {@link #NARROW_COUNTER_LIMIT} operands the `short` kernel is provably exact, and past it the `int` sibling
	 * runs instead. The comparison is the only overhead the guard adds - the scatter loops themselves are untouched.
	 *
	 * @param plus  bitmaps contributing `+1` per membership
	 * @param minus bitmaps contributing `-1` per membership
	 * @return records whose signed count is strictly positive
	 */
	@Nonnull
	static Bitmap compute(@Nonnull Bitmap[] plus, @Nonnull Bitmap[] minus) {
		return (long) plus.length + minus.length <= NARROW_COUNTER_LIMIT ?
			computeNarrow(plus, minus) : computeWide(plus, minus);
	}

	/**
	 * Computes the signed multiplicity result over `short` counters - the path every realistic query takes.
	 *
	 * @param plus  bitmaps contributing `+1` per membership
	 * @param minus bitmaps contributing `-1` per membership
	 * @return records whose signed count is strictly positive
	 */
	@Nonnull
	private static Bitmap computeNarrow(@Nonnull Bitmap[] plus, @Nonnull Bitmap[] minus) {
		final CursorSet cursors = borrowCursorSet();
		if (!cursors.open(plus, minus)) {
			returnCursorSet(cursors);
			return EmptyBitmap.INSTANCE;
		}
		final short[] counters = borrowCounters();
		final Emission emission = borrowEmission();
		final long[] words = emission.words;
		final int[] touched = emission.touched;
		// the scratch only returns to the pools when the emission pass ran to completion and therefore zeroed every
		// counter and word it touched, and left every bucket drained; an abort half-way through would hand the next
		// borrower dirty state, so on that path the scratch is dropped instead - losing one pooled buffer on an
		// exceptional path is the cheap side of this
		boolean scratchIsClean = false;
		try {
			final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			final int[] arena = cursors.arena;
			final int[] position = cursors.position;
			final int[] limit = cursors.limit;
			final int[] successors = cursors.next;
			final short[] signs = cursors.sign;
			for (int chunk = cursors.minChunk; cursors.liveCursors > 0; chunk++) {
				int cursor = cursors.takeBucket(chunk);
				if (cursor < 0) {
					continue;
				}
				int minLow = LOW_MASK;
				int maxLow = 0;
				int touchedCount = 0;
				boolean listUsable = true;
				do {
					// captured before the cursor is re-filed, which overwrites its link
					final int successor = successors[cursor];
					final short sign = signs[cursor];
					int p = position[cursor];
					int end = limit[cursor];
					boolean live = true;
					while (true) {
						while (p < end) {
							final int value = arena[p];
							if ((value >>> CHUNK_BITS) != chunk) {
								break;
							}
							final int low = value & LOW_MASK;
							counters[low] += sign;
							if (low < minLow) {
								minLow = low;
							}
							if (low > maxLow) {
								maxLow = low;
							}
							// a SEQUENTIAL append, deliberately. Setting a bit in a word map here instead - which
							// would deduplicate for free and need no capacity bound - was MEASURED 5x more
							// expensive in this loop's self time (12 us -> 69 us at k=64, N=29,159), because a
							// random read-modify-write carries a load-use dependency where an append does not.
							// Do not re-propose it without a measurement.
							if (listUsable) {
								if (touchedCount < TOUCHED_CAPACITY) {
									touched[touchedCount++] = low;
								} else {
									listUsable = false;
								}
							}
							p++;
						}
						// the batch is spent only when the cursor consumed all of it; otherwise it stopped on a value
						// belonging to a later chunk and must keep the remainder for the next round
						if (p < end) {
							break;
						}
						final int loaded = cursors.refill(cursor);
						if (loaded == 0) {
							live = false;
							break;
						}
						p = cursors.base[cursor];
						end = p + loaded;
					}
					if (live) {
						position[cursor] = p;
						limit[cursor] = end;
						cursors.file(cursor, arena[p] >>> CHUNK_BITS);
					} else {
						cursors.liveCursors--;
					}
					cursor = successor;
				} while (cursor >= 0);
				emitNarrow(writer, counters, words, touched, touchedCount, listUsable, chunk, minLow, maxLow);
			}
			final PersistentRoaringBitmap result = writer.get();
			scratchIsClean = true;
			return result.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(result);
		} finally {
			if (scratchIsClean) {
				returnCounters(counters);
				returnEmission(emission);
				returnCursorSet(cursors);
			}
		}
	}

	/**
	 * Wide sibling of {@link #computeNarrow(Bitmap[], Bitmap[])}: the same kernel over `int` counters, taken when the
	 * combined operand count could drive a `short` counter past its range.
	 *
	 * It is a deliberate copy rather than a width-parameterised generalisation. The scatter loop is this class's whole
	 * cost, and neither an accessor call nor a width branch inside it would be worth the duplication it saves. Only
	 * the two loops that touch `counters` are duplicated - cursor bookkeeping lives in {@link CursorSet} and is
	 * shared, because none of it depends on the counter width.
	 *
	 * The copy's real hazard is divergence, not duplication: production never reaches this path - the largest observed
	 * operand family is an order of magnitude below the threshold - so a fix applied to the narrow sibling and not to
	 * this one would be caught by nothing. It is therefore visible to the test in this package, which runs it against
	 * the same counting reference over every case in the suite, on inputs far below the width it exists for.
	 *
	 * @param plus  bitmaps contributing `+1` per membership
	 * @param minus bitmaps contributing `-1` per membership
	 * @return records whose signed count is strictly positive
	 */
	@Nonnull
	static Bitmap computeWide(@Nonnull Bitmap[] plus, @Nonnull Bitmap[] minus) {
		final CursorSet cursors = borrowCursorSet();
		if (!cursors.open(plus, minus)) {
			returnCursorSet(cursors);
			return EmptyBitmap.INSTANCE;
		}
		final int[] counters = borrowWideCounters();
		final Emission emission = borrowEmission();
		final long[] words = emission.words;
		final int[] touched = emission.touched;
		// same pooling discipline as computeNarrow: the scratch only returns to the pools when the emission pass ran
		// to completion, and an abort half-way through drops it instead
		boolean scratchIsClean = false;
		try {
			final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			final int[] arena = cursors.arena;
			final int[] position = cursors.position;
			final int[] limit = cursors.limit;
			final int[] successors = cursors.next;
			final short[] signs = cursors.sign;
			for (int chunk = cursors.minChunk; cursors.liveCursors > 0; chunk++) {
				int cursor = cursors.takeBucket(chunk);
				if (cursor < 0) {
					continue;
				}
				int minLow = LOW_MASK;
				int maxLow = 0;
				int touchedCount = 0;
				boolean listUsable = true;
				do {
					// captured before the cursor is re-filed, which overwrites its link
					final int successor = successors[cursor];
					final short sign = signs[cursor];
					int p = position[cursor];
					int end = limit[cursor];
					boolean live = true;
					while (true) {
						while (p < end) {
							final int value = arena[p];
							if ((value >>> CHUNK_BITS) != chunk) {
								break;
							}
							final int low = value & LOW_MASK;
							counters[low] += sign;
							if (low < minLow) {
								minLow = low;
							}
							if (low > maxLow) {
								maxLow = low;
							}
							// a SEQUENTIAL append, deliberately. Setting a bit in a word map here instead - which
							// would deduplicate for free and need no capacity bound - was MEASURED 5x more
							// expensive in this loop's self time (12 us -> 69 us at k=64, N=29,159), because a
							// random read-modify-write carries a load-use dependency where an append does not.
							// Do not re-propose it without a measurement.
							if (listUsable) {
								if (touchedCount < TOUCHED_CAPACITY) {
									touched[touchedCount++] = low;
								} else {
									listUsable = false;
								}
							}
							p++;
						}
						// the batch is spent only when the cursor consumed all of it; otherwise it stopped on a value
						// belonging to a later chunk and must keep the remainder for the next round
						if (p < end) {
							break;
						}
						final int loaded = cursors.refill(cursor);
						if (loaded == 0) {
							live = false;
							break;
						}
						p = cursors.base[cursor];
						end = p + loaded;
					}
					if (live) {
						position[cursor] = p;
						limit[cursor] = end;
						cursors.file(cursor, arena[p] >>> CHUNK_BITS);
					} else {
						cursors.liveCursors--;
					}
					cursor = successor;
				} while (cursor >= 0);
				emitWide(writer, counters, words, touched, touchedCount, listUsable, chunk, minLow, maxLow);
			}
			final PersistentRoaringBitmap result = writer.get();
			scratchIsClean = true;
			return result.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(result);
		} finally {
			if (scratchIsClean) {
				returnWideCounters(counters);
				returnEmission(emission);
				returnCursorSet(cursors);
			}
		}
	}

	/**
	 * Emits one chunk by turning the counters the scatter left behind into the chunk's output, and hands that
	 * output to the writer as one container's worth of words.
	 *
	 * The touched list carries duplicates and needs no visited-set: the first visit to an offset reads its counter
	 * and immediately zeroes it, so any later visit reads zero and contributes nothing. That also clears negative
	 * counters, which a "clear only what was emitted" pass would leave behind to poison the next chunk. Building
	 * the list by appending only when the previous count was zero would NOT be safe - a `+1, -1, +1` sequence
	 * revisits zero - which is why every write is recorded.
	 *
	 * The span scan is an **overflow fallback, not a density choice**. While the list fits it is unconditionally
	 * the cheaper walk, so there is no crossover to tune; `listUsable` is false only when the chunk recorded more
	 * writes than {@link #TOUCHED_CAPACITY} allows, and a partial list cannot be walked safely.
	 *
	 * Output goes through a word bitmap because the touched list is unordered - it is scattered operand by operand
	 * - while the writer requires ascending input. Gathering the survivors there costs nothing extra, because the
	 * words are then handed over whole through {@link RoaringBitmapWriter#addChunk(char, long[], int, int)}: the
	 * constant-memory writer keeps a buffer of exactly this shape, so the alternative is decomposing these words
	 * into ints only for it to set the same bits again. The word range is cleared on the way IN rather than on the
	 * way out, so nothing has to tidy up after the last chunk.
	 *
	 * @param writer       receives the surviving record ids, one chunk at a time, in ascending order
	 * @param counters     the signed counters for this chunk, left fully zeroed on return
	 * @param words        scratch the survivors are gathered into; only `[firstWord, lastWord]` is touched
	 * @param touched      offsets the scatter recorded, duplicates included
	 * @param touchedCount how many entries of `touched` are valid
	 * @param listUsable   FALSE when the chunk overran `touched` and the span must be scanned instead
	 * @param chunk        the chunk's high half, unshifted - the container key
	 * @param minLow       lowest offset written
	 * @param maxLow       highest offset written
	 */
	private static void emitNarrow(
		@Nonnull RoaringBitmapWriter<PersistentRoaringBitmap> writer,
		@Nonnull short[] counters,
		@Nonnull long[] words,
		@Nonnull int[] touched,
		int touchedCount,
		boolean listUsable,
		int chunk,
		int minLow,
		int maxLow
	) {
		if (minLow > maxLow) {
			// nothing reached this chunk - unreachable while a bucket entry means a value in it, and kept as one
			// predictable branch so the word arithmetic below is always well formed
			return;
		}
		final int firstWord = minLow >>> 6;
		final int lastWord = maxLow >>> 6;
		Arrays.fill(words, firstWord, lastWord + 1, 0L);
		boolean anySurvivor = false;
		if (listUsable) {
			for (int i = 0; i < touchedCount; i++) {
				final int low = touched[i];
				final short count = counters[low];
				if (count != 0) {
					counters[low] = 0;
					if (count > 0) {
						words[low >>> 6] |= 1L << low;
						anySurvivor = true;
					}
				}
			}
		} else {
			for (int low = minLow; low <= maxLow; low++) {
				final short count = counters[low];
				if (count != 0) {
					counters[low] = 0;
					if (count > 0) {
						words[low >>> 6] |= 1L << low;
						anySurvivor = true;
					}
				}
			}
		}
		if (anySurvivor) {
			writer.addChunk((char) chunk, words, firstWord, lastWord + 1);
		}
	}

	/**
	 * Wide sibling of
	 * {@link #emitNarrow(RoaringBitmapWriter, short[], long[], int[], int, boolean, int, int, int)} - the same
	 * emission over `int` counters. Every invariant that one documents holds here unchanged; see it for why the
	 * touched list needs no visited-set, why the span scan is an overflow fallback rather than a density choice,
	 * and why the survivors cross into the writer as words.
	 *
	 * @param writer       receives the surviving record ids, one chunk at a time, in ascending order
	 * @param counters     the signed counters for this chunk, left fully zeroed on return
	 * @param words        scratch the survivors are gathered into; only `[firstWord, lastWord]` is touched
	 * @param touched      offsets the scatter recorded, duplicates included
	 * @param touchedCount how many entries of `touched` are valid
	 * @param listUsable   FALSE when the chunk overran `touched` and the span must be scanned instead
	 * @param chunk        the chunk's high half, unshifted - the container key
	 * @param minLow       lowest offset written
	 * @param maxLow       highest offset written
	 */
	private static void emitWide(
		@Nonnull RoaringBitmapWriter<PersistentRoaringBitmap> writer,
		@Nonnull int[] counters,
		@Nonnull long[] words,
		@Nonnull int[] touched,
		int touchedCount,
		boolean listUsable,
		int chunk,
		int minLow,
		int maxLow
	) {
		if (minLow > maxLow) {
			return;
		}
		final int firstWord = minLow >>> 6;
		final int lastWord = maxLow >>> 6;
		Arrays.fill(words, firstWord, lastWord + 1, 0L);
		boolean anySurvivor = false;
		if (listUsable) {
			for (int i = 0; i < touchedCount; i++) {
				final int low = touched[i];
				final int count = counters[low];
				if (count != 0) {
					counters[low] = 0;
					if (count > 0) {
						words[low >>> 6] |= 1L << low;
						anySurvivor = true;
					}
				}
			}
		} else {
			for (int low = minLow; low <= maxLow; low++) {
				final int count = counters[low];
				if (count != 0) {
					counters[low] = 0;
					if (count > 0) {
						words[low >>> 6] |= 1L << low;
						anySurvivor = true;
					}
				}
			}
		}
		if (anySurvivor) {
			writer.addChunk((char) chunk, words, firstWord, lastWord + 1);
		}
	}

	/**
	 * Takes a zeroed counter array from the pool, allocating one when the pool is empty.
	 *
	 * @return a counter array whose every element is zero
	 */
	@Nonnull
	private static short[] borrowCounters() {
		final short[] pooled = COUNTER_POOL.poll();
		return pooled == null ? new short[CHUNK_SIZE] : pooled;
	}

	/**
	 * Returns a counter array to the pool. The array is already zeroed - the emission pass clears every counter it
	 * touched - so nothing has to be wiped here.
	 *
	 * @param counters the array to return
	 */
	private static void returnCounters(@Nonnull short[] counters) {
		COUNTER_POOL.offer(counters);
	}

	/**
	 * Takes a zeroed wide counter array from the pool, allocating one when the pool is empty.
	 *
	 * @return a counter array whose every element is zero
	 */
	@Nonnull
	private static int[] borrowWideCounters() {
		final int[] pooled = WIDE_COUNTER_POOL.poll();
		return pooled == null ? new int[CHUNK_SIZE] : pooled;
	}

	/**
	 * Returns a wide counter array to the pool. The array is already zeroed - the emission pass clears every counter
	 * it touched - so nothing has to be wiped here.
	 *
	 * @param counters the array to return
	 */
	private static void returnWideCounters(@Nonnull int[] counters) {
		WIDE_COUNTER_POOL.offer(counters);
	}

	/**
	 * Takes emission scratch from the pool, allocating it when the pool is empty.
	 *
	 * Neither array carries a cleanliness contract: the touched list is written before it is read, and the word
	 * bitmap has its range cleared on the way into every emission.
	 *
	 * @return scratch ready for use, contents arbitrary
	 */
	@Nonnull
	private static Emission borrowEmission() {
		final Emission pooled = EMISSION_POOL.poll();
		return pooled == null ? new Emission() : pooled;
	}

	/**
	 * Returns emission scratch to the pool.
	 *
	 * @param emission the scratch to return
	 */
	private static void returnEmission(@Nonnull Emission emission) {
		EMISSION_POOL.offer(emission);
	}

	/**
	 * Per-chunk emission scratch: the offsets a chunk's scatter touched, and the word bitmap the survivors are
	 * gathered into before they cross into the writer.
	 */
	private static final class Emission {
		/**
		 * Offsets written during the current chunk's scatter, duplicates included.
		 */
		private final int[] touched = new int[TOUCHED_CAPACITY];
		/**
		 * One chunk's survivors as 1 024 64-bit words - the ordering vehicle and the writer's own layout, cleared
		 * per chunk over the touched span.
		 */
		private final long[] words = new long[WORD_COUNT];
	}

	/**
	 * Takes cursor state from the pool, allocating a fresh set when the pool is empty.
	 *
	 * @return a cursor set whose bucket table is fully empty
	 */
	@Nonnull
	private static CursorSet borrowCursorSet() {
		final CursorSet pooled = CURSOR_SET_POOL.poll();
		return pooled == null ? new CursorSet() : pooled;
	}

	/**
	 * Releases the operand references a cursor set pins and returns it to the pool when its arrays are small enough
	 * to be worth keeping.
	 *
	 * @param cursors the cursor state to return
	 */
	private static void returnCursorSet(@Nonnull CursorSet cursors) {
		cursors.release();
		if (cursors.isPoolable()) {
			CURSOR_SET_POOL.offer(cursors);
		}
	}

	/**
	 * Cursor state for one computation, held as parallel arrays rather than one object per operand.
	 *
	 * ## Why this is not an array of cursor objects
	 *
	 * The shape this kernel exists for is *many tiny operands*: a production range query was measured at k = 7,602
	 * operands over N = 29,159 endpoints - 3.8 record ids per operand. One object per operand, each owning its own
	 * batch buffer, put roughly a megabyte of small scattered objects between the kernel and 114 KB of actual data,
	 * and every pass over the operands became a pointer chase through it.
	 *
	 * Here every per-cursor field is a slot in a pooled primitive array, and every cursor's batch lives in one shared
	 * {@link #arena}, sized to the operands and filled through {@link BatchIterator#nextBatch(int[], int, int)}. The
	 * arrays are reused across calls, so the steady-state allocation for cursor state is the {@link BatchIterator}
	 * per operand and nothing else.
	 *
	 * ## Why the buckets matter more than that
	 *
	 * The cursors are not scanned to find the next chunk. Each cursor is *filed* into {@link #bucketHead} under the
	 * chunk its next id belongs to, and re-filed whenever it crosses into a later one, so opening a chunk is reading
	 * one array slot and walking an intrusive list of exactly the cursors that have data there. The cost of driving
	 * the merge is therefore `k` filings plus one re-filing per chunk a cursor actually spans, rather than a full
	 * `O(k)` sweep of the operands per chunk - on the production shape the difference is ~15,000 operations against
	 * ~213,000, none of which moved a single record id.
	 *
	 * A cursor only ever moves to a HIGHER chunk, and chunks are consumed in ascending order, so a cursor filed while
	 * chunk `c` is being drained always lands in a bucket that has not been visited yet.
	 */
	private static final class CursorSet {
		/**
		 * Batch iterator per live operand, indexed by cursor slot.
		 *
		 * Reading a small operand whole through `getArray()` instead was tried and MEASURED SLOWER - 41 % at
		 * k=256 and 6 % at k=7,602 - because `getArray()` allocates and copies the operand's array, where the
		 * batch iterator refills a buffer this set already owns. Do not re-propose it without a measurement.
		 */
		@Nonnull private BatchIterator[] iterators = new BatchIterator[0];
		/**
		 * First index of each cursor's slice in {@link #arena}.
		 */
		@Nonnull private int[] base = new int[0];
		/**
		 * Length of each cursor's slice in {@link #arena} - how many ids one refill may load.
		 */
		@Nonnull private int[] capacity = new int[0];
		/**
		 * Index in {@link #arena} of each cursor's next unconsumed value.
		 */
		@Nonnull private int[] position = new int[0];
		/**
		 * Index in {@link #arena} one past each cursor's last loaded value.
		 */
		@Nonnull private int[] limit = new int[0];
		/**
		 * Sign contributed by every element of each cursor's operand - `+1` or `-1`.
		 */
		@Nonnull private short[] sign = new short[0];
		/**
		 * Intrusive bucket links: the cursor filed after this one under the same chunk, or `-1` at the end of the
		 * list. Overwritten every time a cursor is re-filed, which is why a walk captures it before draining.
		 */
		@Nonnull private int[] next = new int[0];
		/**
		 * Batch storage shared by every cursor, carved into one fixed slice per cursor.
		 *
		 * Slices are sized to the operand rather than to {@link RangeCountKernel#BATCH_SIZE}, because the shape this
		 * kernel exists for is *many tiny operands*: at k = 7,602 over N = 29,159 endpoints a fixed 256-int buffer
		 * each would reserve 7.8 MB of scratch to carry 114 KB of data.
		 */
		@Nonnull private int[] arena = new int[0];
		/**
		 * Chunk-indexed heads of the intrusive bucket lists; `-1` where no cursor is waiting.
		 *
		 * Left fully `-1` between computations: {@link #takeBucket(int)} clears every head it reads, and the main
		 * loop visits every chunk from {@link #minChunk} up to the last one any cursor reaches, so a completed
		 * computation drains the table it filled.
		 */
		@Nonnull private int[] bucketHead = new int[0];
		/**
		 * Number of cursor slots in use - operands that were non-empty.
		 */
		private int count;
		/**
		 * Number of cursors that still hold unconsumed values; the main loop runs until this reaches zero.
		 */
		private int liveCursors;
		/**
		 * Lowest chunk any cursor starts in - where the main loop begins.
		 */
		private int minChunk;
		/**
		 * Highest chunk any operand reaches, taken from each operand's last value; sizes {@link #bucketHead}.
		 */
		private int maxChunk;
		/**
		 * Running total of the slice lengths handed out in {@link #arena}.
		 */
		private int arenaSize;

		/**
		 * Opens one cursor per non-empty bitmap, primes each with its first batch and files it into the bucket of
		 * the chunk it starts in.
		 *
		 * @param plus  bitmaps contributing `+1`
		 * @param minus bitmaps contributing `-1`
		 * @return `false` when every operand was empty and there is nothing to compute
		 */
		private boolean open(@Nonnull Bitmap[] plus, @Nonnull Bitmap[] minus) {
			ensureCursors(plus.length + minus.length);
			this.count = 0;
			this.arenaSize = 0;
			this.maxChunk = 0;
			collect(plus, (short) 1);
			collect(minus, (short) -1);
			if (this.count == 0) {
				return false;
			}
			ensureArena(this.arenaSize);
			ensureBuckets(this.maxChunk + 1);
			prime();
			return true;
		}

		/**
		 * Reserves a cursor slot and an arena slice for every non-empty bitmap of one family, and tracks the highest
		 * chunk the family reaches. No iterator is read here - only sized.
		 *
		 * @param family the bitmaps to open cursors over
		 * @param sign   the sign their memberships contribute
		 */
		private void collect(@Nonnull Bitmap[] family, short sign) {
			for (final Bitmap bitmap : family) {
				if (bitmap.isEmpty()) {
					continue;
				}
				final PersistentRoaringBitmap roaring = RoaringBitmapBackedBitmap.getRoaringBitmap(bitmap);
				final int slot = this.count++;
				this.iterators[slot] = roaring.getBatchIterator();
				this.sign[slot] = sign;
				final int slice = Math.clamp(bitmap.size(), 1, BATCH_SIZE);
				this.base[slot] = this.arenaSize;
				this.capacity[slot] = slice;
				this.arenaSize += slice;
				final int lastChunk = roaring.last() >>> CHUNK_BITS;
				if (lastChunk > this.maxChunk) {
					this.maxChunk = lastChunk;
				}
			}
		}

		/**
		 * Loads every cursor's first batch and files it under the chunk it starts in.
		 */
		private void prime() {
			this.minChunk = Integer.MAX_VALUE;
			this.liveCursors = this.count;
			for (int slot = 0; slot < this.count; slot++) {
				final int start = this.base[slot];
				final int loaded = this.iterators[slot].nextBatch(this.arena, start, this.capacity[slot]);
				if (loaded == 0) {
					throw new GenericEvitaInternalError(
						"Non-empty operand produced an empty first batch - the bitmap and its batch iterator disagree!"
					);
				}
				this.position[slot] = start;
				this.limit[slot] = start + loaded;
				final int chunk = this.arena[start] >>> CHUNK_BITS;
				if (chunk < this.minChunk) {
					this.minChunk = chunk;
				}
				file(slot, chunk);
			}
		}

		/**
		 * Loads the next batch into a cursor's own arena slice.
		 *
		 * @param cursor the cursor slot to refill
		 * @return how many values were loaded, `0` once the operand is exhausted
		 */
		private int refill(int cursor) {
			return this.iterators[cursor].nextBatch(this.arena, this.base[cursor], this.capacity[cursor]);
		}

		/**
		 * Pushes a cursor onto the front of a chunk's bucket list.
		 *
		 * @param cursor the cursor slot to file
		 * @param chunk  the chunk its next unconsumed value belongs to
		 */
		private void file(int cursor, int chunk) {
			this.next[cursor] = this.bucketHead[chunk];
			this.bucketHead[chunk] = cursor;
		}

		/**
		 * Detaches a chunk's bucket list, leaving the head empty for the next computation.
		 *
		 * @param chunk the chunk being opened
		 * @return the first cursor filed under it, or `-1` when no cursor has data there
		 */
		private int takeBucket(int chunk) {
			if (chunk >= this.bucketHead.length) {
				// the loop runs while cursors are live, and no cursor can be filed above maxChunk, so reaching this
				// means a cursor outlived the last value its own operand reported
				throw new GenericEvitaInternalError(
					"Range counting kernel walked past the highest chunk its operands can reach!"
				);
			}
			final int head = this.bucketHead[chunk];
			this.bucketHead[chunk] = -1;
			return head;
		}

		/**
		 * Grows the per-cursor arrays when the operand count outruns them. They are replaced rather than copied -
		 * nothing in them survives a computation.
		 *
		 * @param required number of cursor slots this computation may need
		 */
		private void ensureCursors(int required) {
			if (this.iterators.length >= required) {
				return;
			}
			this.iterators = new BatchIterator[required];
			this.base = new int[required];
			this.capacity = new int[required];
			this.position = new int[required];
			this.limit = new int[required];
			this.sign = new short[required];
			this.next = new int[required];
		}

		/**
		 * Grows the shared batch arena when the reserved slices outrun it.
		 *
		 * @param required total slice length this computation handed out
		 */
		private void ensureArena(int required) {
			if (this.arena.length < required) {
				this.arena = new int[required];
			}
		}

		/**
		 * Grows the bucket table when the operands reach a higher chunk than it covers, filling the fresh table with
		 * the `-1` empty marker.
		 *
		 * @param required one past the highest chunk any operand reaches
		 */
		private void ensureBuckets(int required) {
			if (this.bucketHead.length < required) {
				this.bucketHead = new int[required];
				Arrays.fill(this.bucketHead, -1);
			}
		}

		/**
		 * Drops the operand references this set pins, so returning it to the pool cannot keep bitmaps alive.
		 */
		private void release() {
			Arrays.fill(this.iterators, 0, this.count, null);
			this.count = 0;
			this.liveCursors = 0;
		}

		/**
		 * Tells whether this set is small enough to keep. One outsized computation would otherwise park its arrays
		 * in the pool for the lifetime of the JVM, which is the opposite of what pooling is for here.
		 *
		 * @return `true` when every array is within its retention cap
		 */
		private boolean isPoolable() {
			return this.iterators.length <= MAX_POOLED_CURSORS
				&& this.arena.length <= MAX_POOLED_ARENA
				&& this.bucketHead.length <= MAX_POOLED_BUCKETS;
		}
	}

}
