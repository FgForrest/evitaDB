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
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.BatchIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
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
 * ## Why counting beats merging
 *
 * The pair it replaces paid `O(N log k)` for the k-way merge that materialized the duplicates plus `O(N)` for the
 * merge that cancelled them, with a virtual call and a priority-queue reheap per element. The duplicates were never
 * an output - only an encoding of the counts - so this kernel accumulates the counts directly and never materializes
 * them. It is `O(N)` scatters plus one bounded scan per touched 65 536-value chunk.
 *
 * ## Counter width, and why no overflow guard is needed
 *
 * Counters are `short` and the accumulation is deliberately allowed to wrap. Two's-complement addition is exact
 * modulo 2^16, so the accumulated value always equals the true signed difference modulo 2^16 regardless of the order
 * the operands were applied in - an intermediate that overflows cancels back correctly. Only the **final** difference
 * has to be representable, and it is bounded by the number of ranges a single record holds inside one chunk, which is
 * a handful for every realistic validity history. Intermediate magnitude is therefore irrelevant and no guard,
 * detector or re-run is required.
 *
 * ## Container access
 *
 * The kernel deliberately works through the public {@link BatchIterator} API rather than the roaring containers. The
 * vendored module exposes `getContainerPointer()` but types it with a package-private interface, so container-level
 * dispatch (a run-container prefix scan, bit-sliced adders over dense containers) is not reachable from this module
 * without widening that fork. That widening is a separate decision and belongs with the container kernels, not here.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
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
	 * Size of the per-cursor batch buffer; mirrors the batch size `JoinFormula` used.
	 */
	private static final int BATCH_SIZE = 256;
	/**
	 * How many touched offsets one chunk may record before the sparse emission path gives up and the span scan
	 * takes over. A chunk denser than this is exactly the chunk a span scan handles well, so the cap costs nothing
	 * and bounds the scratch.
	 */
	private static final int SPARSE_CAPACITY = 8192;
	/**
	 * Walking the touched list is preferred while `touchedCount * SPARSE_FACTOR < span`.
	 *
	 * **This value is a provisional estimate, not a measured crossover.** It is set from the cost shapes - the
	 * sparse walk touches `touchedCount` counters plus a bounded word pass, the dense walk touches `span` - and
	 * chosen conservatively so the sparse path is taken only when it is clearly ahead. It has NOT been swept.
	 * On the production operand dump it selects sparse for 10 of 14 chunks and dense for the other 4, which is the
	 * intended split (chunk 7 holds 19,196 ids in a 56,974 span and genuinely wants the span scan), but "the split
	 * looks sensible" is not the same as "4 is the optimum".
	 */
	private static final int SPARSE_FACTOR = 4;
	/**
	 * Bounded pool of the 128 KiB counter arrays. A bounded pool rather than a {@link ThreadLocal}: per-thread
	 * striping keeps one array alive per carrier thread, which is hostile to virtual threads and was rejected for
	 * that reason when the usage-statistics work faced the same choice.
	 */
	private static final ArrayBlockingQueue<short[]> COUNTER_POOL = new ArrayBlockingQueue<>(8);
	/**
	 * Bounded pool of the sparse-emission scratch: the touched-offset list and the 1 024-word output bitmap.
	 */
	private static final ArrayBlockingQueue<SparseScratch> SPARSE_POOL = new ArrayBlockingQueue<>(8);

	private RangeCountKernel() {
		throw new UnsupportedOperationException("Kernel holder must not be instantiated!");
	}

	/**
	 * Computes the signed multiplicity result of the two families.
	 *
	 * @param plus  bitmaps contributing `+1` per membership
	 * @param minus bitmaps contributing `-1` per membership
	 * @return records whose signed count is strictly positive
	 */
	@Nonnull
	static Bitmap compute(@Nonnull Bitmap[] plus, @Nonnull Bitmap[] minus) {
		final Cursor[] cursors = openCursors(plus, minus);
		if (cursors.length == 0) {
			return EmptyBitmap.INSTANCE;
		}
		final short[] counters = borrowCounters();
		final SparseScratch scratch = borrowSparseScratch();
		// the array only returns to the pool when the emission pass ran to completion and therefore zeroed every
		// counter it touched; an abort half-way through would hand the next borrower a dirty array, so on that path
		// the array is dropped instead - losing one pooled buffer on an exceptional path is the cheap side of this
		boolean countersAreClean = false;
		try {
			final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			int liveCursors = cursors.length;
			while (liveCursors > 0) {
				// the lowest chunk any cursor still has data in - a k-way merge at CHUNK granularity, not per element
				int chunk = Integer.MAX_VALUE;
				for (final Cursor cursor : cursors) {
					if (cursor.live) {
						final int cursorChunk = cursor.buffer[cursor.position] >>> CHUNK_BITS;
						if (cursorChunk < chunk) {
							chunk = cursorChunk;
						}
					}
				}

				int minLow = LOW_MASK;
				int maxLow = 0;
				int touchedCount = 0;
				boolean sparseUsable = true;
				for (final Cursor cursor : cursors) {
					if (!cursor.live || (cursor.buffer[cursor.position] >>> CHUNK_BITS) != chunk) {
						continue;
					}
					final short sign = cursor.sign;
					do {
						final int[] buffer = cursor.buffer;
						int position = cursor.position;
						final int limit = cursor.length;
						while (position < limit) {
							final int value = buffer[position];
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
							// record every write, duplicates included - see emitSparse for why that needs no
							// separate visited-set; once the list overflows the span scan has to take over,
							// because a partial list cannot be walked safely
							if (sparseUsable) {
								if (touchedCount < SPARSE_CAPACITY) {
									scratch.touched[touchedCount++] = low;
								} else {
									sparseUsable = false;
								}
							}
							position++;
						}
						cursor.position = position;
						// the batch is spent only when the cursor consumed all of it; otherwise it stopped on a value
						// belonging to a later chunk and must keep the remainder for the next round
						if (position < limit) {
							break;
						}
					} while (cursor.refill());
					if (!cursor.live) {
						liveCursors--;
					}
				}

				if (minLow <= maxLow) {
					final int base = chunk << CHUNK_BITS;
					final int span = maxLow - minLow + 1;
					// walking the touched list beats scanning the span once the chunk is sparse enough. On the
					// measured production shape a chunk holds ~1,325 ids inside a span of nearly 65,536, so the
					// span scan would read ~50x more counters than were ever written.
					if (sparseUsable && (long) touchedCount * SPARSE_FACTOR < span) {
						emitSparse(writer, counters, scratch, touchedCount, base, minLow, maxLow);
					} else {
						// clearing as it goes so the array leaves this iteration zeroed for the next chunk
						// without a separate fill pass
						for (int low = minLow; low <= maxLow; low++) {
							final short count = counters[low];
							if (count > 0) {
								writer.add(base | low);
							}
							counters[low] = 0;
						}
					}
				}
			}
			final PersistentRoaringBitmap result = writer.get();
			countersAreClean = true;
			return result.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(result);
		} finally {
			if (countersAreClean) {
				returnCounters(counters);
				SPARSE_POOL.offer(scratch);
			}
		}
	}

	/**
	 * Opens one cursor per non-empty bitmap, primed with its first batch.
	 *
	 * @param plus  bitmaps contributing `+1`
	 * @param minus bitmaps contributing `-1`
	 * @return the primed cursors; exhausted and empty inputs are dropped
	 */
	@Nonnull
	private static Cursor[] openCursors(@Nonnull Bitmap[] plus, @Nonnull Bitmap[] minus) {
		final Cursor[] scratch = new Cursor[plus.length + minus.length];
		int count = 0;
		for (final Bitmap bitmap : plus) {
			final Cursor cursor = Cursor.open(bitmap, (short) 1);
			if (cursor != null) {
				scratch[count++] = cursor;
			}
		}
		for (final Bitmap bitmap : minus) {
			final Cursor cursor = Cursor.open(bitmap, (short) -1);
			if (cursor != null) {
				scratch[count++] = cursor;
			}
		}
		if (count == scratch.length) {
			return scratch;
		}
		final Cursor[] result = new Cursor[count];
		System.arraycopy(scratch, 0, result, 0, count);
		return result;
	}

	/**
	 * Emits one chunk by walking only the offsets the scatter actually wrote, instead of scanning the whole span.
	 *
	 * The touched list carries duplicates and needs no separate visited-set: the first visit to an offset reads its
	 * counter and immediately zeroes it, so any later visit reads zero and contributes nothing. That also clears
	 * negative counters, which a "clear only what was emitted" pass would leave behind and poison the next chunk
	 * with. Building the list by appending only when the previous count was zero would NOT be safe - a `+1, -1, +1`
	 * sequence revisits zero - which is why every write is recorded.
	 *
	 * Output goes through a word bitmap rather than straight to the writer because the touched list is unordered
	 * (it is scattered operand by operand) while {@link RoaringBitmapWriter} requires ascending input; the word
	 * array restores that order for the price of one bounded pass.
	 *
	 * @param writer       receives the surviving record ids in ascending order
	 * @param counters     the signed counters for this chunk, left fully zeroed on return
	 * @param scratch      the touched list and the word bitmap
	 * @param touchedCount how many offsets the scatter recorded
	 * @param base         the chunk's high half, shifted into place
	 * @param minLow       lowest offset written
	 * @param maxLow       highest offset written
	 */
	private static void emitSparse(
		@Nonnull RoaringBitmapWriter<PersistentRoaringBitmap> writer,
		@Nonnull short[] counters,
		@Nonnull SparseScratch scratch,
		int touchedCount,
		int base,
		int minLow,
		int maxLow
	) {
		final int[] touched = scratch.touched;
		final long[] words = scratch.words;
		final int firstWord = minLow >>> 6;
		final int lastWord = maxLow >>> 6;
		for (int i = firstWord; i <= lastWord; i++) {
			words[i] = 0L;
		}
		for (int i = 0; i < touchedCount; i++) {
			final int low = touched[i];
			final short count = counters[low];
			if (count != 0) {
				counters[low] = 0;
				if (count > 0) {
					words[low >>> 6] |= 1L << low;
				}
			}
		}
		for (int wordIndex = firstWord; wordIndex <= lastWord; wordIndex++) {
			long word = words[wordIndex];
			final int wordBase = base | (wordIndex << 6);
			while (word != 0L) {
				writer.add(wordBase | Long.numberOfTrailingZeros(word));
				word &= word - 1L;
			}
		}
	}

	/**
	 * Takes the sparse-emission scratch from the pool, allocating when the pool is empty.
	 *
	 * @return scratch whose word array is cleared per chunk before use
	 */
	@Nonnull
	private static SparseScratch borrowSparseScratch() {
		final SparseScratch pooled = SPARSE_POOL.poll();
		return pooled == null ? new SparseScratch() : pooled;
	}

	/**
	 * Scratch for the sparse emission path: the offsets a chunk's scatter touched, and the word bitmap those
	 * offsets are re-ordered through.
	 */
	private static final class SparseScratch {
		/**
		 * Offsets written during the current chunk's scatter, duplicates included.
		 */
		private final int[] touched = new int[SPARSE_CAPACITY];
		/**
		 * One chunk's output as 1 024 64-bit words - the ordering vehicle, cleared per chunk over the touched span.
		 */
		private final long[] words = new long[CHUNK_SIZE >>> 6];
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
	 * One input bitmap being consumed in ascending order, carrying the sign its memberships contribute.
	 */
	private static final class Cursor {
		/**
		 * Batch iterator over the backing roaring bitmap.
		 *
		 * Reading a small operand whole through `getArray()` instead was tried and MEASURED SLOWER - 41 % at
		 * k=256 and 6 % at k=7,602 - because `getArray()` allocates and copies the operand's array, where the
		 * batch iterator refills a buffer this cursor already owns. Do not re-propose it without a measurement.
		 */
		private final BatchIterator iterator;
		/**
		 * Sign contributed by every element of this bitmap - `+1` or `-1`.
		 */
		private final short sign;
		/**
		 * Current batch of values in ascending order.
		 *
		 * Sized to the operand rather than to {@link #BATCH_SIZE}, because the shape this kernel exists for is
		 * *many tiny operands*: a production range query was measured at k = 7,602 operands over N = 29,159
		 * endpoints - 3.8 elements per operand. A fixed 256-int buffer each would allocate 7.8 MB of scratch to
		 * carry 114 KB of data, which is precisely what makes the formula being replaced expensive.
		 */
		private final int[] buffer;
		/**
		 * Index of the next unconsumed value in {@link #buffer}.
		 */
		private int position;
		/**
		 * Number of valid values in {@link #buffer}.
		 */
		private int length;
		/**
		 * FALSE once the iterator is exhausted and the buffer fully consumed.
		 */
		private boolean live;

		private Cursor(@Nonnull BatchIterator iterator, short sign, int bufferSize) {
			this.iterator = iterator;
			this.sign = sign;
			this.buffer = new int[bufferSize];
		}

		/**
		 * Opens a cursor over `bitmap`, or returns null when it holds nothing.
		 *
		 * @param bitmap the bitmap to iterate
		 * @param sign   the sign its memberships contribute
		 * @return a primed cursor, or null for an empty bitmap
		 */
		@javax.annotation.Nullable
		private static Cursor open(@Nonnull Bitmap bitmap, short sign) {
			if (bitmap.isEmpty()) {
				return null;
			}
			final Cursor cursor = new Cursor(
				RoaringBitmapBackedBitmap.getRoaringBitmap(bitmap).getBatchIterator(),
				sign,
				Math.min(BATCH_SIZE, Math.max(1, bitmap.size()))
			);
			return cursor.refill() ? cursor : null;
		}

		/**
		 * Loads the next batch.
		 *
		 * @return true when the cursor holds unconsumed values
		 */
		private boolean refill() {
			this.length = this.iterator.nextBatch(this.buffer);
			this.position = 0;
			this.live = this.length > 0;
			return this.live;
		}
	}

}
