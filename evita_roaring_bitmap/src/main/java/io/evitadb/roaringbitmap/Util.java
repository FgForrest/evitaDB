/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static java.lang.Long.numberOfTrailingZeros;

/**
 * Low-level static helpers shared by the Roaring bitmap container implementations: unsigned
 * binary/galloping searches over sorted `char[]` arrays, merge primitives for the set operations
 * (intersection, union, difference, symmetric difference), branch-free bit selection, bit-range
 * word manipulation and the partial radix sort used while building bitmaps.
 *
 * Throughout this class `char` values are treated as **unsigned** 16-bit integers (`0..65535`): a
 * container key occupies the high 16 bits of a 32-bit value ({@link #highbits}) and a stored value
 * the low 16 bits ({@link #lowbits}). Comparisons rely on `char` promoting to a non-negative `int`,
 * so the ordering is unsigned without any masking.
 *
 * Vendored (hard fork) from the RoaringBitmap library and kept in sync with upstream
 * `org.roaringbitmap.Util`.
 */
public final class Util {

	/**
	 * Selects the hybrid binary search variant ({@link #hybridUnsignedBinarySearch}) over the plain
	 * branchy one ({@link #branchyUnsignedBinarySearch}) inside {@link #unsignedBinarySearch}. The
	 * hybrid form narrows the window with binary search and finishes with a cache-friendly sequential
	 * scan; kept as a flag so the two strategies can be benchmarked against each other.
	 */
	public static final boolean USE_HYBRID_BINSEARCH = true;

	/**
	 * Adds the unsigned constant `offsets` to every value of `source`, producing two fresh
	 * containers: element `0` holds values that stayed within the low 16-bit range and element `1`
	 * holds values that overflowed into the next container key. `source` is left unchanged. The
	 * returned containers are *not* normalised (their type may no longer match their cardinality), so
	 * callers must repair/convert them before use.
	 *
	 * @param source  source container, not modified
	 * @param offsets unsigned value added to each entry of the container
	 * @return two-element array `{low, high}` split at the 16-bit boundary
	 * @throws RuntimeException if `source` is of an unknown container type
	 */
	@Nonnull
	public static Container[] addOffset(@Nonnull final Container source, final char offsets) {
		if (source instanceof ArrayContainer) {
			return addOffsetArray((ArrayContainer) source, offsets);
		} else if (source instanceof BitmapContainer) {
			return addOffsetBitmap((BitmapContainer) source, offsets);
		} else if (source instanceof RunContainer) {
			return addOffsetRun((RunContainer) source, offsets);
		}
		throw new RuntimeException("unknown container type");
	}

	/**
	 * Galloping (exponential) search for the smallest index strictly greater than `pos` whose value
	 * is at least `min`, comparing stored `char`s as unsigned. Doubles the probe span forward from
	 * `pos` until it brackets `min`, then binary-searches that window; a sequential fast-path handles
	 * the common case where the very next slot already qualifies. Based on code by O. Kaser.
	 *
	 * Complexity: `O(log gap)`, where `gap` is the distance from `pos` to the returned index.
	 *
	 * @param array  array sorted in unsigned-ascending order
	 * @param pos    index the search starts strictly after
	 * @param length number of leading entries of `array` that are valid
	 * @param min    minimum (unsigned) value sought
	 * @return smallest index `> pos` with `array[index] >= min`, or `length` if no such value exists
	 */
	public static int advanceUntil(@Nonnull final char[] array, final int pos, final int length, final char min) {
		int lower = pos + 1;

		// special handling for a possibly common sequential case
		if (lower >= length || (array[lower]) >= (int) (min)) {
			return lower;
		}

		int spansize = 1; // could set larger
		// bootstrap an upper limit

		while (lower + spansize < length && (array[lower + spansize]) < (int) (min)) {
			spansize *= 2; // hoping for compiler will reduce to
		}
		// shift
		int upper = (lower + spansize < length) ? lower + spansize : length - 1;

		// maybe we are lucky (could be common case when the seek ahead
		// expected
		// to be small and sequential will otherwise make us look bad)
		if (array[upper] == min) {
			return upper;
		}

		if ((array[upper]) < (int) (min)) {
			// means array has no item >= min pos = array.length;
			return length;
		}

		// we know that the next-smallest span was too small
		lower += (spansize >>> 1);

		// else begin binary search
		// invariant: array[lower]<min && array[upper]>min
		while (lower + 1 != upper) {
			final int mid = (lower + upper) >>> 1;
			final char arraymid = array[mid];
			if (arraymid == min) {
				return mid;
			} else if ((arraymid) < (int) (min)) {
				lower = mid;
			} else {
				upper = mid;
			}
		}
		return upper;
	}

	/**
	 * Mirror of {@link #advanceUntil} that gallops *backwards*: finds the largest index strictly less
	 * than `pos` whose value is at most `max`, comparing stored `char`s as unsigned. Doubles the
	 * probe span backward until it brackets `max`, then binary-searches that window; a sequential
	 * fast-path handles the common case where the immediately preceding slot already qualifies. Based
	 * on code by O. Kaser.
	 *
	 * Complexity: `O(log gap)`, where `gap` is the distance from `pos` to the returned index.
	 *
	 * @param array  array sorted in unsigned-ascending order
	 * @param pos    index the search starts strictly before
	 * @param length number of leading entries of `array` that are valid
	 * @param max    maximum (unsigned) value sought
	 * @return largest index `< pos` with `array[index] <= max`, or `0` if no such value exists
	 */
	public static int reverseUntil(@Nonnull final char[] array, final int pos, final int length, final char max) {
		int lower = pos - 1;

		// special handling for a possibly common sequential case
		if (lower <= 0 || (array[lower]) <= (int) (max)) {
			return lower;
		}

		int spansize = 1; // could set larger
		// bootstrap an upper limit

		while (lower - spansize > 0 && (array[lower - spansize]) > (int) (max)) {
			spansize *= 2; // hoping for compiler will reduce to
		}
		// shift
		int upper = (lower - spansize > 0) ? lower - spansize : 0;

		// maybe we are lucky (could be common case when the seek ahead
		// expected
		// to be small and sequential will otherwise make us look bad)
		if (array[upper] == max) {
			return upper;
		}

		if ((array[upper]) > (int) (max)) {
			// means array has no item >= min pos = array.length;
			return 0;
		}

		// we know that the next-smallest span was too small
		lower -= (spansize >>> 1);

		// else begin binary search
		// invariant: array[lower]<min && array[upper]>min
		while (lower - 1 != upper) {
			final int mid = (lower + upper) >>> 1;
			final char arraymid = array[mid];
			if (arraymid == max) {
				return mid;
			} else if ((arraymid) > (int) (max)) {
				lower = mid;
			} else {
				upper = mid;
			}
		}
		return upper;
	}

	/**
	 * Plain forward linear scan for the smallest index at or after `pos` whose value is at least
	 * `min`. Unlike {@link #advanceUntil} the scan starts *at* `pos` (inclusive) and `min` is passed
	 * as an already-unsigned `int`. Preferred over galloping when the target is expected to be close.
	 *
	 * Complexity: `O(gap)`, where `gap` is the distance scanned.
	 *
	 * @param array  array sorted in unsigned-ascending order
	 * @param pos    index at which the scan begins (inclusive)
	 * @param length number of leading entries of `array` that are valid
	 * @param min    minimum (unsigned) value sought
	 * @return smallest index `>= pos` with `array[index] >= min`, or `length` if no such value exists
	 */
	public static int iterateUntil(@Nonnull final char[] array, int pos, final int length, final int min) {
		while (pos < length && (array[pos]) < min) {
			pos++;
		}
		return pos;
	}

	/**
	 * Decodes the positions of the bits set in `bitmap1 AND bitmap2` into `container` in ascending
	 * order, each position emitted as its low 16-bit `char`. `container` must be large enough to hold
	 * the intersection cardinality.
	 *
	 * Complexity: `O(words + popcount)`.
	 *
	 * @param container output array receiving the set-bit positions, filled from index `0`
	 * @param bitmap1   first word array
	 * @param bitmap2   second word array (must have the same length as `bitmap1`)
	 * @throws IllegalArgumentException if the two word arrays differ in length
	 */
	public static void fillArrayAND(
		@Nonnull final char[] container, @Nonnull final long[] bitmap1, @Nonnull final long[] bitmap2) {
		int pos = 0;
		if (bitmap1.length != bitmap2.length) {
			throw new IllegalArgumentException("not supported");
		}
		for (int k = 0; k < bitmap1.length; ++k) {
			long bitset = bitmap1[k] & bitmap2[k];
			while (bitset != 0) {
				container[pos++] = (char) (k * 64 + numberOfTrailingZeros(bitset));
				bitset &= (bitset - 1);
			}
		}
	}

	/**
	 * Decodes the positions of the bits set in `bitmap1 AND NOT bitmap2` (values present in the first
	 * word array but absent from the second) into `container` in ascending order, each position
	 * emitted as its low 16-bit `char`. `container` must hold the difference cardinality.
	 *
	 * Complexity: `O(words + popcount)`.
	 *
	 * @param container output array receiving the set-bit positions, filled from index `0`
	 * @param bitmap1   first word array
	 * @param bitmap2   second word array (must have the same length as `bitmap1`)
	 * @throws IllegalArgumentException if the two word arrays differ in length
	 */
	public static void fillArrayANDNOT(
		@Nonnull final char[] container, @Nonnull final long[] bitmap1, @Nonnull final long[] bitmap2) {
		int pos = 0;
		if (bitmap1.length != bitmap2.length) {
			throw new IllegalArgumentException("not supported");
		}
		for (int k = 0; k < bitmap1.length; ++k) {
			long bitset = bitmap1[k] & (~bitmap2[k]);
			while (bitset != 0) {
				container[pos++] = (char) (k * 64 + numberOfTrailingZeros(bitset));
				bitset &= (bitset - 1);
			}
		}
	}

	/**
	 * Decodes the positions of the bits set in `bitmap1 XOR bitmap2` (values in exactly one of the
	 * two word arrays) into `container` in ascending order, each position emitted as its low 16-bit
	 * `char`. `container` must hold the symmetric-difference cardinality.
	 *
	 * Complexity: `O(words + popcount)`.
	 *
	 * @param container output array receiving the set-bit positions, filled from index `0`
	 * @param bitmap1   first word array
	 * @param bitmap2   second word array (must have the same length as `bitmap1`)
	 * @throws IllegalArgumentException if the two word arrays differ in length
	 */
	public static void fillArrayXOR(
		@Nonnull final char[] container, @Nonnull final long[] bitmap1, @Nonnull final long[] bitmap2) {
		int pos = 0;
		if (bitmap1.length != bitmap2.length) {
			throw new IllegalArgumentException("not supported");
		}
		for (int k = 0; k < bitmap1.length; ++k) {
			long bitset = bitmap1[k] ^ bitmap2[k];
			while (bitset != 0) {
				container[pos++] = (char) (k * 64 + numberOfTrailingZeros(bitset));
				bitset &= (bitset - 1);
			}
		}
	}

	/**
	 * Toggles (XOR) the bits at absolute bit indices `[start, end)` within the word array in place,
	 * masking only the affected end words and negating the fully covered interior words.
	 *
	 * Complexity: `O((end - start) / 64)`.
	 *
	 * @param bitmap array of 64-bit words to be modified
	 * @param start  first bit index to flip (inclusive)
	 * @param end    bit index one past the last bit to flip (exclusive)
	 */
	public static void flipBitmapRange(@Nonnull final long[] bitmap, final int start, final int end) {
		if (start == end) {
			return;
		}
		final int firstword = start / 64;
		final int endword = (end - 1) / 64;
		bitmap[firstword] ^= ~(~0L << start);
		for (int i = firstword; i < endword; i++) {
			bitmap[i] = ~bitmap[i];
		}
		bitmap[endword] ^= ~0L >>> -end;
	}

	/**
	 * Hamming weight (population count) of every *whole* word that overlaps the bit range
	 * `[start, end)`, i.e. of words `floor(start/64) .. floor((end-1)/64)` inclusive. Because it
	 * counts full words rather than only the requested bits, it may over-count the partial end words;
	 * prefer {@link #cardinalityInBitmapRange} for an exact bit count.
	 *
	 * Complexity: `O((end - start) / 64)`.
	 *
	 * @param bitmap array of words representing a bitset
	 * @param start  first bit index (inclusive)
	 * @param end    bit index one past the last (exclusive)
	 * @return combined population count of the overlapping words
	 * @deprecated word-granular counting over-counts partial words; use
	 * {@link #cardinalityInBitmapRange}
	 */
	@Deprecated
	public static int cardinalityInBitmapWordRange(@Nonnull final long[] bitmap, final int start, final int end) {
		if (start >= end) {
			return 0;
		}
		final int firstword = start / 64;
		final int endword = (end - 1) / 64;
		int answer = 0;
		for (int i = firstword; i <= endword; i++) {
			answer += Long.bitCount(bitmap[i]);
		}
		return answer;
	}

	/**
	 * Exact Hamming weight (population count) of the bits set at absolute bit indices `[start, end)`,
	 * masking the partial first and last words so only bits inside the range are counted.
	 *
	 * Complexity: `O((end - start) / 64)`.
	 *
	 * @param bitmap array of words representing a bitset
	 * @param start  first bit index (inclusive)
	 * @param end    bit index one past the last (exclusive)
	 * @return number of set bits within the range, `0` when `start >= end`
	 */
	public static int cardinalityInBitmapRange(@Nonnull final long[] bitmap, final int start, final int end) {
		if (start >= end) {
			return 0;
		}
		final int firstword = start / 64;
		final int endword = (end - 1) / 64;
		if (firstword == endword) {
			return Long.bitCount(bitmap[firstword] & ((~0L << start) & (~0L >>> -end)));
		}
		int answer = Long.bitCount(bitmap[firstword] & (~0L << start));
		for (int i = firstword + 1; i < endword; i++) {
			answer += Long.bitCount(bitmap[i]);
		}
		answer += Long.bitCount(bitmap[endword] & (~0L >>> -end));
		return answer;
	}

	/**
	 * Largest value that fits in the low 16 bits (`0xFFFF`), i.e. the highest position a single
	 * container can address.
	 *
	 * @return `0xFFFF`
	 */
	public static int maxLowBitAsInteger() {
		return 0xFFFF;
	}

	/**
	 * Clears (sets to `0`) the bits at absolute bit indices `[start, end)` within the word array in
	 * place, masking the partial end words and zeroing the fully covered interior words.
	 *
	 * Complexity: `O((end - start) / 64)`.
	 *
	 * @param bitmap array of 64-bit words to be modified
	 * @param start  first bit index to clear (inclusive)
	 * @param end    bit index one past the last bit to clear (exclusive)
	 */
	public static void resetBitmapRange(@Nonnull final long[] bitmap, final int start, final int end) {
		if (start == end) {
			return;
		}
		final int firstword = start / 64;
		final int endword = (end - 1) / 64;

		if (firstword == endword) {
			bitmap[firstword] &= ~((~0L << start) & (~0L >>> -end));
			return;
		}
		bitmap[firstword] &= ~(~0L << start);
		for (int i = firstword + 1; i < endword; i++) {
			bitmap[i] = 0;
		}
		bitmap[endword] &= ~(~0L >>> -end);
	}

	/**
	 * Intersects `bitmap` in place with the set of bit indices listed in `array[0..length)`: every
	 * word of `bitmap` is ANDed with the mask built from the array entries that fall in it, and words
	 * not referenced by any entry are zeroed. Each `array` entry `v` addresses word `v >>> 6` and bit
	 * `1L << v`, so `array` must be sorted in unsigned-ascending order (entries grouped by word).
	 *
	 * Complexity: `O(length + words)`.
	 *
	 * @param bitmap the bitmap, modified in place to hold the intersection
	 * @param array  bit indices to intersect against, sorted ascending, not modified
	 * @param length number of leading `array` entries to consume
	 * @return cardinality of the resulting bitmap (bits still set after the intersection)
	 */
	public static int intersectArrayIntoBitmap(
		@Nonnull final long[] bitmap, @Nonnull final char[] array, final int length) {
		int lastWordIndex = 0;
		int wordIndex = 0;
		long word = 0L;
		int cardinality = 0;
		for (int i = 0; i < length; ++i) {
			wordIndex = array[i] >>> 6;
			if (wordIndex != lastWordIndex) {
				bitmap[lastWordIndex] &= word;
				cardinality += Long.bitCount(bitmap[lastWordIndex]);
				word = 0L;
				Arrays.fill(bitmap, lastWordIndex + 1, wordIndex, 0L);
				lastWordIndex = wordIndex;
			}
			word |= 1L << array[i];
		}
		if (word != 0L) {
			bitmap[wordIndex] &= word;
			cardinality += Long.bitCount(bitmap[lastWordIndex]);
		}
		if (wordIndex < bitmap.length) {
			Arrays.fill(bitmap, wordIndex + 1, bitmap.length, 0L);
		}
		return cardinality;
	}

	/**
	 * Returns the bit position (`0..63`) of the `j`-th set bit of `w`, counting from the least
	 * significant bit. Branch-light divide-and-conquer: it halves the word (`64 -> 32 -> 16 -> 8`
	 * bits) by comparing population counts, then scans the final byte. `j` is zero-based and `w` must
	 * contain more than `j` set bits, otherwise the result is undefined.
	 *
	 * Complexity: `O(1)` (fixed number of steps).
	 *
	 * @param w the word to inspect
	 * @param j zero-based rank of the set bit to locate
	 * @return zero-based position of the `j`-th set bit
	 */
	public static int select(final long w, int j) {
		int seen = 0;
		// Divide 64bit
		int part = (int) w;
		int n = Integer.bitCount(part);
		if (n <= j) {
			part = (int) (w >>> 32);
			seen += 32;
			j -= n;
		}
		int ww = part;

		// Divide 32bit
		part = ww & 0xFFFF;

		n = Integer.bitCount(part);
		if (n <= j) {

			part = ww >>> 16;
			seen += 16;
			j -= n;
		}
		ww = part;

		// Divide 16bit
		part = ww & 0xFF;
		n = Integer.bitCount(part);
		if (n <= j) {
			part = ww >>> 8;
			seen += 8;
			j -= n;
		}
		ww = part;

		// Lookup in final byte
		int counter;
		for (counter = 0; counter < 8; counter++) {
			j -= (ww >>> counter) & 1;
			if (j < 0) {
				break;
			}
		}
		return seen + counter;
	}

	/**
	 * Sets (to `1`) the bits at absolute bit indices `[start, end)` within the word array in place,
	 * masking the partial end words and filling the fully covered interior words with all ones.
	 *
	 * Complexity: `O((end - start) / 64)`.
	 *
	 * @param bitmap array of 64-bit words to be modified
	 * @param start  first bit index to set (inclusive)
	 * @param end    bit index one past the last bit to set (exclusive)
	 */
	public static void setBitmapRange(@Nonnull final long[] bitmap, final int start, final int end) {
		if (start == end) {
			return;
		}
		final int firstword = start / 64;
		final int endword = (end - 1) / 64;
		if (firstword == endword) {
			bitmap[firstword] |= (~0L << start) & (~0L >>> -end);
			return;
		}
		bitmap[firstword] |= ~0L << start;
		for (int i = firstword + 1; i < endword; i++) {
			bitmap[i] = ~0L;
		}
		bitmap[endword] |= ~0L >>> -end;
	}

	/**
	 * Sets the bits at `[start, end)` (see {@link #setBitmapRange}) and returns the net change in
	 * population count. The change is measured over whole overlapping words, which is exact because
	 * the same words are counted before and after.
	 *
	 * @param bitmap array of 64-bit words to be modified
	 * @param start  first bit index to set (inclusive)
	 * @param end    bit index one past the last bit to set (exclusive)
	 * @return number of bits that flipped from `0` to `1`
	 * @deprecated relies on the deprecated {@link #cardinalityInBitmapWordRange}
	 */
	@Deprecated
	public static int setBitmapRangeAndCardinalityChange(@Nonnull final long[] bitmap, final int start, final int end) {
		final int cardbefore = cardinalityInBitmapWordRange(bitmap, start, end);
		setBitmapRange(bitmap, start, end);
		final int cardafter = cardinalityInBitmapWordRange(bitmap, start, end);
		return cardafter - cardbefore;
	}

	/**
	 * Flips the bits at `[start, end)` (see {@link #flipBitmapRange}) and returns the net change in
	 * population count (positive when more bits ended up set than cleared). The change is measured
	 * over whole overlapping words, which is exact because the same words are counted before and
	 * after.
	 *
	 * @param bitmap array of 64-bit words to be modified
	 * @param start  first bit index to flip (inclusive)
	 * @param end    bit index one past the last bit to flip (exclusive)
	 * @return signed change in population count
	 * @deprecated relies on the deprecated {@link #cardinalityInBitmapWordRange}
	 */
	@Deprecated
	public static int flipBitmapRangeAndCardinalityChange(
		@Nonnull final long[] bitmap, final int start, final int end) {
		final int cardbefore = cardinalityInBitmapWordRange(bitmap, start, end);
		flipBitmapRange(bitmap, start, end);
		final int cardafter = cardinalityInBitmapWordRange(bitmap, start, end);
		return cardafter - cardbefore;
	}

	/**
	 * Clears the bits at `[start, end)` (see {@link #resetBitmapRange}) and returns the net change in
	 * population count (normally negative or zero). The change is measured over whole overlapping
	 * words, which is exact because the same words are counted before and after.
	 *
	 * @param bitmap array of 64-bit words to be modified
	 * @param start  first bit index to clear (inclusive)
	 * @param end    bit index one past the last bit to clear (exclusive)
	 * @return signed change in population count (bits cleared reported as a negative delta)
	 * @deprecated relies on the deprecated {@link #cardinalityInBitmapWordRange}
	 */
	@Deprecated
	public static int resetBitmapRangeAndCardinalityChange(
		@Nonnull final long[] bitmap, final int start, final int end) {
		final int cardbefore = cardinalityInBitmapWordRange(bitmap, start, end);
		resetBitmapRange(bitmap, start, end);
		final int cardafter = cardinalityInBitmapWordRange(bitmap, start, end);
		return cardafter - cardbefore;
	}

	/**
	 * Unsigned binary search for `k` in the sorted range `array[begin..end)`. Values are compared as
	 * unsigned 16-bit integers. Delegates to {@link #hybridUnsignedBinarySearch} or
	 * {@link #branchyUnsignedBinarySearch} depending on {@link #USE_HYBRID_BINSEARCH}.
	 *
	 * Complexity: `O(log n)` over the searched range.
	 *
	 * @param array array sorted in unsigned-ascending order
	 * @param begin first index of the search range (inclusive)
	 * @param end   index one past the last of the search range (exclusive)
	 * @param k     value to look for
	 * @return the index of `k` if present, otherwise `-(insertionPoint + 1)`, where `insertionPoint`
	 * is the index at which `k` would be inserted to keep the range sorted
	 */
	public static int unsignedBinarySearch(
		@Nonnull final char[] array, final int begin, final int end, final char k) {
		if (USE_HYBRID_BINSEARCH) {
			return hybridUnsignedBinarySearch(array, begin, end, k);
		} else {
			return branchyUnsignedBinarySearch(array, begin, end, k);
		}
	}

	/**
	 * Two-way merge computing the set difference `set1 \ set2` of two unsigned-ascending sorted
	 * lists, writing the surviving values of `set1` to `buffer` in order. `buffer` must hold at least
	 * `length1` entries.
	 *
	 * Complexity: `O(length1 + length2)`.
	 *
	 * @param set1    first (minuend) array, sorted ascending
	 * @param length1 number of leading entries of `set1` to consider
	 * @param set2    second (subtrahend) array, sorted ascending
	 * @param length2 number of leading entries of `set2` to consider
	 * @param buffer  output array receiving the difference, filled from index `0`
	 * @return number of values written to `buffer` (cardinality of the difference)
	 */
	public static int unsignedDifference(
		@Nonnull final char[] set1,
		final int length1,
		@Nonnull final char[] set2,
		final int length2,
		@Nonnull final char[] buffer
	) {
		int pos = 0;
		int k1 = 0, k2 = 0;
		if (0 == length2) {
			System.arraycopy(set1, 0, buffer, 0, length1);
			return length1;
		}
		if (0 == length1) {
			return 0;
		}
		char s1 = set1[k1];
		char s2 = set2[k2];
		while (true) {
			if (s1 < s2) {
				buffer[pos++] = s1;
				++k1;
				if (k1 >= length1) {
					break;
				}
				s1 = set1[k1];
			} else if (s1 == s2) {
				++k1;
				++k2;
				if (k1 >= length1) {
					break;
				}
				if (k2 >= length2) {
					System.arraycopy(set1, k1, buffer, pos, length1 - k1);
					return pos + length1 - k1;
				}
				s1 = set1[k1];
				s2 = set2[k2];
			} else { // if (val1>val2)
				++k2;
				if (k2 >= length2) {
					System.arraycopy(set1, k1, buffer, pos, length1 - k1);
					return pos + length1 - k1;
				}
				s2 = set2[k2];
			}
		}
		return pos;
	}

	/**
	 * Streaming variant of {@link #unsignedDifference(char[], int, char[], int, char[])} that merges
	 * two ascending {@link CharIterator}s, writing the values of `set1` absent from `set2` to
	 * `buffer` in order. `buffer` must be large enough for the whole difference.
	 *
	 * Complexity: `O(n1 + n2)` in the numbers of values produced by the two iterators.
	 *
	 * @param set1   first (minuend) iterator, ascending
	 * @param set2   second (subtrahend) iterator, ascending
	 * @param buffer output array receiving the difference, filled from index `0`
	 * @return number of values written to `buffer` (cardinality of the difference)
	 */
	public static int unsignedDifference(
		@Nonnull final CharIterator set1, @Nonnull final CharIterator set2, @Nonnull final char[] buffer) {
		int pos = 0;
		if (!set2.hasNext()) {
			while (set1.hasNext()) {
				buffer[pos++] = set1.next();
			}
			return pos;
		}
		if (!set1.hasNext()) {
			return 0;
		}
		char v1 = set1.next();
		char v2 = set2.next();
		while (true) {
			if ((v1) < (v2)) {
				buffer[pos++] = v1;
				if (!set1.hasNext()) {
					return pos;
				}
				v1 = set1.next();
			} else if (v1 == v2) {
				if (!set1.hasNext()) {
					break;
				}
				if (!set2.hasNext()) {
					while (set1.hasNext()) {
						buffer[pos++] = set1.next();
					}
					return pos;
				}
				v1 = set1.next();
				v2 = set2.next();
			} else { // if (val1>val2)
				if (!set2.hasNext()) {
					buffer[pos++] = v1;
					while (set1.hasNext()) {
						buffer[pos++] = set1.next();
					}
					return pos;
				}
				v2 = set2.next();
			}
		}
		return pos;
	}

	/**
	 * Two-way merge computing the symmetric difference (exclusive union, `set1 XOR set2`) of two
	 * unsigned-ascending sorted lists: values present in exactly one input are written to `buffer` in
	 * order, matching values are dropped. `buffer` must hold up to `length1 + length2` entries.
	 *
	 * Complexity: `O(length1 + length2)`.
	 *
	 * @param set1    first array, sorted ascending
	 * @param length1 number of leading entries of `set1` to consider
	 * @param set2    second array, sorted ascending
	 * @param length2 number of leading entries of `set2` to consider
	 * @param buffer  output array receiving the symmetric difference, filled from index `0`
	 * @return number of values written to `buffer` (cardinality of the exclusive union)
	 */
	public static int unsignedExclusiveUnion2by2(
		@Nonnull final char[] set1,
		final int length1,
		@Nonnull final char[] set2,
		final int length2,
		@Nonnull final char[] buffer
	) {
		int pos = 0;
		int k1 = 0, k2 = 0;
		if (0 == length2) {
			System.arraycopy(set1, 0, buffer, 0, length1);
			return length1;
		}
		if (0 == length1) {
			System.arraycopy(set2, 0, buffer, 0, length2);
			return length2;
		}
		char s1 = set1[k1];
		char s2 = set2[k2];
		while (true) {
			if (s1 < s2) {
				buffer[pos++] = s1;
				++k1;
				if (k1 >= length1) {
					System.arraycopy(set2, k2, buffer, pos, length2 - k2);
					return pos + length2 - k2;
				}
				s1 = set1[k1];
			} else if (s1 == s2) {
				++k1;
				++k2;
				if (k1 >= length1) {
					System.arraycopy(set2, k2, buffer, pos, length2 - k2);
					return pos + length2 - k2;
				}
				if (k2 >= length2) {
					System.arraycopy(set1, k1, buffer, pos, length1 - k1);
					return pos + length1 - k1;
				}
				s1 = set1[k1];
				s2 = set2[k2];
			} else { // if (val1>val2)
				buffer[pos++] = s2;
				++k2;
				if (k2 >= length2) {
					System.arraycopy(set1, k1, buffer, pos, length1 - k1);
					return pos + length1 - k1;
				}
				s2 = set2[k2];
			}
		}
	}

	/**
	 * Intersects two unsigned-ascending sorted lists, writing the common values to `buffer` in order.
	 * Adaptively picks the algorithm: when one input is more than 25x longer than the other it
	 * gallops the shorter set through the longer one, otherwise it runs a linear two-way merge.
	 * `buffer` must hold at least `min(length1, length2)` entries.
	 *
	 * Complexity: `O(length1 + length2)` for the merge path, `O(m log n)` for the galloping path
	 * (`m` = shorter length, `n` = longer length).
	 *
	 * @param set1    first array, sorted ascending
	 * @param length1 number of leading entries of `set1` to consider
	 * @param set2    second array, sorted ascending
	 * @param length2 number of leading entries of `set2` to consider
	 * @param buffer  output array receiving the intersection, filled from index `0`
	 * @return number of values written to `buffer` (cardinality of the intersection)
	 */
	public static int unsignedIntersect2by2(
		@Nonnull final char[] set1,
		final int length1,
		@Nonnull final char[] set2,
		final int length2,
		@Nonnull final char[] buffer
	) {
		final int THRESHOLD = 25;
		if (set1.length * THRESHOLD < set2.length) {
			return unsignedOneSidedGallopingIntersect2by2(set1, length1, set2, length2, buffer);
		} else if (set2.length * THRESHOLD < set1.length) {
			return unsignedOneSidedGallopingIntersect2by2(set2, length2, set1, length1, buffer);
		} else {
			return unsignedLocalIntersect2by2(set1, length1, set2, length2, buffer);
		}
	}

	/**
	 * Tests whether two unsigned-ascending sorted lists share at least one value, short-circuiting on
	 * the first match without writing anything. Returns `false` when either input is empty.
	 *
	 * Complexity: `O(length1 + length2)`.
	 *
	 * @param set1    first array, sorted ascending
	 * @param length1 number of leading entries of `set1` to consider
	 * @param set2    second array, sorted ascending
	 * @param length2 number of leading entries of `set2` to consider
	 * @return `true` if the two ranges have a common value
	 */
	public static boolean unsignedIntersects(
		@Nonnull final char[] set1, final int length1, @Nonnull final char[] set2, final int length2) {
		// galloping might be faster, but we do not expect this function to be slow
		if ((0 == length1) || (0 == length2)) {
			return false;
		}
		int k1 = 0;
		int k2 = 0;
		char s1 = set1[k1];
		char s2 = set2[k2];
		mainwhile:
		while (true) {
			if (s2 < s1) {
				do {
					++k2;
					if (k2 == length2) {
						break mainwhile;
					}
					s2 = set2[k2];
				} while (s2 < s1);
			}
			if (s1 < s2) {
				do {
					++k1;
					if (k1 == length1) {
						break mainwhile;
					}
					s1 = set1[k1];
				} while (s1 < s2);
			} else {
				return true;
			}
		}
		return false;
	}

	/**
	 * Counts the values common to two unsigned-ascending sorted lists via a linear two-way merge,
	 * without materialising the intersection (no output buffer). Behaves like
	 * {@link #unsignedLocalIntersect2by2} but returns only the count.
	 *
	 * Complexity: `O(length1 + length2)`.
	 *
	 * @param set1    first array, sorted ascending
	 * @param length1 number of leading entries of `set1` to consider
	 * @param set2    second array, sorted ascending
	 * @param length2 number of leading entries of `set2` to consider
	 * @return cardinality of the intersection
	 */
	public static int unsignedLocalIntersect2by2Cardinality(
		@Nonnull final char[] set1, final int length1, @Nonnull final char[] set2, final int length2) {
		if ((0 == length1) || (0 == length2)) {
			return 0;
		}
		int k1 = 0;
		int k2 = 0;
		int pos = 0;
		char s1 = set1[k1];
		char s2 = set2[k2];

		mainwhile:
		while (true) {
			int v1 = s1;
			int v2 = s2;
			if (v2 < v1) {
				do {
					++k2;
					if (k2 == length2) {
						break mainwhile;
					}
					s2 = set2[k2];
					v2 = s2;
				} while (v2 < v1);
			}
			if (v1 < v2) {
				do {
					++k1;
					if (k1 == length1) {
						break mainwhile;
					}
					s1 = set1[k1];
					v1 = s1;
				} while (v1 < v2);
			} else {
				// (set2[k2] == set1[k1])
				pos++;
				++k1;
				if (k1 == length1) {
					break;
				}
				++k2;
				if (k2 == length2) {
					break;
				}
				s1 = set1[k1];
				s2 = set2[k2];
			}
		}
		return pos;
	}

	/**
	 * Two-way merge computing the union of two unsigned-ascending sorted lists, writing the distinct
	 * values to `buffer` in order (shared values appear once). Each input is read over the half-open
	 * index window `[offsetN, offsetN + lengthN)`, letting callers unite sub-slices without copying.
	 * `buffer` must hold up to `length1 + length2` entries.
	 *
	 * Complexity: `O(length1 + length2)`.
	 *
	 * @param set1    first array, sorted ascending
	 * @param offset1 index of the first entry of `set1` to consider
	 * @param length1 number of entries of `set1` to consider from `offset1`
	 * @param set2    second array, sorted ascending
	 * @param offset2 index of the first entry of `set2` to consider
	 * @param length2 number of entries of `set2` to consider from `offset2`
	 * @param buffer  output array receiving the union, filled from index `0`
	 * @return number of values written to `buffer` (cardinality of the union)
	 */
	public static int unsignedUnion2by2(
		@Nonnull final char[] set1,
		final int offset1,
		final int length1,
		@Nonnull final char[] set2,
		final int offset2,
		final int length2,
		@Nonnull final char[] buffer
	) {
		if (0 == length2) {
			System.arraycopy(set1, offset1, buffer, 0, length1);
			return length1;
		}
		if (0 == length1) {
			System.arraycopy(set2, offset2, buffer, 0, length2);
			return length2;
		}
		int pos = 0;
		int k1 = offset1, k2 = offset2;
		char s1 = set1[k1];
		char s2 = set2[k2];
		while (true) {
			int v1 = s1;
			int v2 = s2;
			if (v1 < v2) {
				buffer[pos++] = s1;
				++k1;
				if (k1 >= length1 + offset1) {
					System.arraycopy(set2, k2, buffer, pos, length2 - k2 + offset2);
					return pos + length2 - k2 + offset2;
				}
				s1 = set1[k1];
			} else if (v1 == v2) {
				buffer[pos++] = s1;
				++k1;
				++k2;
				if (k1 >= length1 + offset1) {
					System.arraycopy(set2, k2, buffer, pos, length2 - k2 + offset2);
					return pos + length2 - k2 + offset2;
				}
				if (k2 >= length2 + offset2) {
					System.arraycopy(set1, k1, buffer, pos, length1 - k1 + offset1);
					return pos + length1 - k1 + offset1;
				}
				s1 = set1[k1];
				s2 = set2[k2];
			} else { // if (set1[k1]>set2[k2])
				buffer[pos++] = s2;
				++k2;
				if (k2 >= length2 + offset2) {
					System.arraycopy(set1, k1, buffer, pos, length1 - k1 + offset1);
					return pos + length1 - k1 + offset1;
				}
				s2 = set2[k2];
			}
		}
	}

	/**
	 * Converts the argument to a {@code long} by an unsigned conversion. In an unsigned conversion to
	 * a {@code long}, the high-order 32 bits of the {@code long} are zero and the low-order 32 bits
	 * are equal to the bits of the integer argument.
	 *
	 * Consequently, zero and positive {@code int} values are mapped to a numerically equal
	 * {@code long} value and negative {@code
	 * int} values are mapped to a {@code long} value equal to the input plus `2^32`.
	 *
	 * @param x the value to convert to an unsigned {@code long}
	 * @return the argument converted to {@code long} by an unsigned conversion
	 * @since 1.8
	 */
	// Duplicated from jdk8 Integer.toUnsignedLong
	public static long toUnsignedLong(final int x) {
		return ((long) x) & 0xffffffffL;
	}

	/**
	 * Sorts `data` in place by its most significant 16 bits (the Roaring container key) using a
	 * two-pass LSD radix sort over bytes `16..23` then `24..31`. On return values are ordered by
	 * their high 16 bits only; entries sharing a prefix stay in arbitrary relative order (their low
	 * 16 bits are left unsorted). Ints are treated as unsigned (`0 .. 2^32`). Each byte pass is
	 * skipped when that byte is already uniform, so pre-grouped input avoids the extra passes.
	 *
	 * Complexity: `O(n)` (up to two counting passes plus histograms).
	 *
	 * @param data the data, sorted in place by its 16-bit prefix
	 */
	public static void partialRadixSort(@Nonnull final int[] data) {
		final int[] low = new int[257];
		final int[] high = new int[257];
		for (final int value : data) {
			++low[((value >>> 16) & 0xFF) + 1];
			++high[(value >>> 24) + 1];
		}
		// avoid passes over the data if it's not required
		final boolean sortLow = low[1] < data.length;
		final boolean sortHigh = high[1] < data.length;
		if (!sortLow && !sortHigh) {
			return;
		}
		final int[] copy = new int[data.length];
		if (sortLow) {
			for (int i = 1; i < low.length; ++i) {
				low[i] += low[i - 1];
			}
			for (final int value : data) {
				copy[low[(value >>> 16) & 0xFF]++] = value;
			}
		}
		if (sortHigh) {
			for (int i = 1; i < high.length; ++i) {
				high[i] += high[i - 1];
			}
			if (sortLow) {
				for (final int value : copy) {
					data[high[value >>> 24]++] = value;
				}
			} else {
				for (final int value : data) {
					copy[high[value >>> 24]++] = value;
				}
				System.arraycopy(copy, 0, data, 0, data.length);
			}
		} else {
			System.arraycopy(copy, 0, data, 0, data.length);
		}
	}

	/**
	 * Decodes every set bit of the word array into `array` in ascending order, each bit emitted as
	 * its low 16-bit position (`64 * wordIndex + trailingZeros`). `array` must hold at least the
	 * bitmap's population count.
	 *
	 * Complexity: `O(words + popcount)`.
	 *
	 * @param bitmap source word array representing a bitset
	 * @param array  output array receiving the set-bit positions, filled from index `0`
	 */
	public static void fillArray(@Nonnull final long[] bitmap, @Nonnull final char[] array) {
		int pos = 0;
		int base = 0;
		for (int k = 0; k < bitmap.length; ++k) {
			long bitset = bitmap[k];
			while (bitset != 0) {
				array[pos++] = (char) (base + numberOfTrailingZeros(bitset));
				bitset &= (bitset - 1);
			}
			base += 64;
		}
	}

	/**
	 * Plain (fully branching) unsigned binary search over `array[begin..end)` with a fast-path for
	 * the common append case where `k` exceeds the last element. Same return contract as
	 * {@link #unsignedBinarySearch}: index of `k`, or `-(insertionPoint + 1)` if absent.
	 *
	 * Complexity: `O(log n)`.
	 *
	 * @param array array sorted in unsigned-ascending order
	 * @param begin first index of the search range (inclusive)
	 * @param end   index one past the last of the search range (exclusive)
	 * @param k     value to look for
	 * @return the index of `k` if present, otherwise `-(insertionPoint + 1)`
	 */
	static int branchyUnsignedBinarySearch(
		@Nonnull final char[] array, final int begin, final int end, final char k) {
		// next line accelerates the possibly common case where the value would
		// be inserted at the end
		if ((end > 0) && ((array[end - 1]) < (int) (k))) {
			return -end - 1;
		}
		int low = begin;
		int high = end - 1;
		while (low <= high) {
			final int middleIndex = (low + high) >>> 1;
			final int middleValue = (array[middleIndex]);

			if (middleValue < (int) (k)) {
				low = middleIndex + 1;
			} else if (middleValue > (int) (k)) {
				high = middleIndex - 1;
			} else {
				return middleIndex;
			}
		}
		return -(low + 1);
	}

	/**
	 * High 16 bits of `x` — the Roaring container key that selects which container a value belongs
	 * to.
	 *
	 * @param x 32-bit value
	 * @return the container key (`x >>> 16`)
	 */
	static char highbits(final int x) {
		return (char) (x >>> 16);
	}

	/**
	 * High 16 bits of the low 32 bits of `x` — the Roaring container key.
	 *
	 * @param x 64-bit value
	 * @return the container key (`x >>> 16`)
	 */
	static char highbits(final long x) {
		return (char) (x >>> 16);
	}

	/**
	 * Low 16 bits of `x` — the value's position within its container.
	 *
	 * @param x 32-bit value
	 * @return the in-container position as an unsigned `char`
	 */
	static char lowbits(final int x) {
		return (char) x;
	}

	/**
	 * Low 16 bits of `x` — the value's position within its container.
	 *
	 * @param x 64-bit value
	 * @return the in-container position as an unsigned `char`
	 */
	static char lowbits(final long x) {
		return (char) x;
	}

	/**
	 * Low 16 bits of `x` as a non-negative `int` (`0..65535`), avoiding the sign issues of a raw
	 * `char` cast in `int` arithmetic.
	 *
	 * @param x 32-bit value
	 * @return the in-container position in the range `0..65535`
	 */
	static int lowbitsAsInteger(final int x) {
		return x & 0xFFFF;
	}

	/**
	 * Low 16 bits of `x` as a non-negative `int` (`0..65535`).
	 *
	 * @param x 64-bit value
	 * @return the in-container position in the range `0..65535`
	 */
	static int lowbitsAsInteger(final long x) {
		return (int) (x & 0xFFFF);
	}

	/**
	 * Linear two-way merge intersection of two comparably sized unsigned-ascending lists, writing the
	 * common values to `buffer` in order. Backs {@link #unsignedIntersect2by2} on the non-galloping
	 * path.
	 *
	 * Complexity: `O(length1 + length2)`.
	 *
	 * @param set1    first array, sorted ascending
	 * @param length1 number of leading entries of `set1` to consider
	 * @param set2    second array, sorted ascending
	 * @param length2 number of leading entries of `set2` to consider
	 * @param buffer  output array receiving the intersection, filled from index `0`
	 * @return number of values written to `buffer` (cardinality of the intersection)
	 */
	static int unsignedLocalIntersect2by2(
		@Nonnull final char[] set1,
		final int length1,
		@Nonnull final char[] set2,
		final int length2,
		@Nonnull final char[] buffer
	) {
		if ((0 == length1) || (0 == length2)) {
			return 0;
		}
		int k1 = 0;
		int k2 = 0;
		int pos = 0;
		char s1 = set1[k1];
		char s2 = set2[k2];

		mainwhile:
		while (true) {
			int v1 = (s1);
			int v2 = s2;
			if (v2 < v1) {
				do {
					++k2;
					if (k2 == length2) {
						break mainwhile;
					}
					s2 = set2[k2];
					v2 = s2;
				} while (v2 < v1);
			}
			if (v1 < v2) {
				do {
					++k1;
					if (k1 == length1) {
						break mainwhile;
					}
					s1 = set1[k1];
					v1 = s1;
				} while (v1 < v2);
			} else {
				// (set2[k2] == set1[k1])
				buffer[pos++] = s1;
				++k1;
				if (k1 == length1) {
					break;
				}
				++k2;
				if (k2 == length2) {
					break;
				}
				s1 = set1[k1];
				s2 = set2[k2];
			}
		}
		return pos;
	}

	/**
	 * Computes the container keys (high 16 bits) common to every bitmap in `bitmaps` — the set of
	 * containers a multi-way AND would have to visit. The first bitmap's keys are painted into the
	 * `words` scratch bitset, which is then intersected in place against each remaining bitmap's keys
	 * via {@link #intersectArrayIntoBitmap}, short-circuiting once the running set becomes empty.
	 *
	 * @param words   scratch word array large enough to address all 16-bit keys, cleared on entry
	 * @param bitmaps bitmaps whose keys are intersected (at least one, index `0` seeds the set)
	 * @return the shared keys in ascending order, or an empty array when no key is common to all
	 */
	@Nonnull
	static char[] intersectKeys(@Nonnull final long[] words, @Nonnull final PersistentRoaringBitmap[] bitmaps) {
		final PersistentRoaringBitmap first = bitmaps[0];
		for (int i = 0; i < first.highLowContainer.size; ++i) {
			final char key = first.highLowContainer.keys[i];
			words[key >>> 6] |= 1L << key;
		}
		int numContainers = first.highLowContainer.size;
		for (int i = 1; i < bitmaps.length && numContainers > 0; ++i) {
			numContainers =
				Util.intersectArrayIntoBitmap(
					words, bitmaps[i].highLowContainer.keys, bitmaps[i].highLowContainer.size);
		}
		if (numContainers == 0) {
			return new char[0];
		}
		return BitSetUtil.arrayContainerBufferOf(0, words.length, numContainers, words);
	}

	/**
	 * {@link #addOffset} for array containers: binary-searches the split point at which shifted
	 * values cross the 16-bit boundary, then copies the two halves into low/high array containers.
	 */
	@Nonnull
	private static Container[] addOffsetArray(@Nonnull final ArrayContainer source, final char offsets) {
		int splitIndex;
		if (source.first() + offsets > 0xFFFF) {
			splitIndex = 0;
		} else if (source.last() + offsets < 0xFFFF) {
			splitIndex = source.cardinality;
		} else {
			splitIndex =
				Util.unsignedBinarySearch(
					source.content, 0, source.cardinality, (char) (0x10000 - offsets));
			if (splitIndex < 0) {
				splitIndex = -splitIndex - 1;
			}
		}

		final ArrayContainer low = splitIndex == 0 ? new ArrayContainer() : new ArrayContainer(splitIndex);
		final ArrayContainer high =
			source.cardinality - splitIndex == 0
				? new ArrayContainer()
				: new ArrayContainer(source.cardinality - splitIndex);

		int lowCardinality = 0;
		for (int k = 0; k < splitIndex; k++) {
			final int val = source.content[k] + offsets;
			low.content[lowCardinality++] = (char) val;
		}
		low.cardinality = lowCardinality;

		int highCardinality = 0;
		for (int k = splitIndex; k < source.cardinality; k++) {
			final int val = source.content[k] + offsets;
			high.content[highCardinality++] = (char) val;
		}
		high.cardinality = highCardinality;

		return new Container[]{low, high};
	}

	/**
	 * {@link #addOffset} for bitmap containers: shifts the whole word array left by `offsets` bits
	 * (word shift `b`, in-word shift `i`), spilling the words that overflow the 16-bit range into the
	 * high container, then repairs the lazily built cardinalities.
	 */
	@Nonnull
	private static Container[] addOffsetBitmap(@Nonnull final BitmapContainer source, final char offsets) {
		final BitmapContainer low = new BitmapContainer();
		final BitmapContainer high = new BitmapContainer();
		low.cardinality = -1;
		high.cardinality = -1;
		final int b = (int) offsets >>> 6;
		final int i = (int) offsets % 64;
		if (i == 0) {
			System.arraycopy(source.bitmap, 0, low.bitmap, b, 1024 - b);
			System.arraycopy(source.bitmap, 1024 - b, high.bitmap, 0, b);
		} else {
			low.bitmap[b] = source.bitmap[0] << i;
			for (int k = 1; k < 1024 - b; k++) {
				low.bitmap[b + k] = (source.bitmap[k] << i) | (source.bitmap[k - 1] >>> (64 - i));
			}
			for (int k = 1024 - b; k < 1024; k++) {
				high.bitmap[k - (1024 - b)] = (source.bitmap[k] << i) | (source.bitmap[k - 1] >>> (64 - i));
			}
			high.bitmap[b] = source.bitmap[1024 - 1] >>> (64 - i);
		}
		return new Container[]{low.repairAfterLazy(), high.repairAfterLazy()};
	}

	/**
	 * {@link #addOffset} for run containers: shifts each run by `offsets`, splitting any run that
	 * straddles the 16-bit boundary into a low tail and a high head appended to the two output
	 * containers.
	 */
	@Nonnull
	private static Container[] addOffsetRun(@Nonnull final RunContainer source, final char offsets) {
		final RunContainer low = new RunContainer();
		final RunContainer high = new RunContainer();
		for (int k = 0; k < source.nbrruns; k++) {
			final int val = source.getValue(k) + offsets;
			final int finalval = val + source.getLength(k);
			if (val <= 0xFFFF) {
				if (finalval <= 0xFFFF) {
					low.smartAppend((char) val, source.getLength(k));
				} else {
					low.smartAppend((char) val, (char) (0xFFFF - val));
					high.smartAppend((char) 0, (char) finalval);
				}
			} else {
				high.smartAppend((char) val, source.getLength(k));
			}
		}
		return new Container[]{low, high};
	}

	/**
	 * Hybrid unsigned binary search: narrows the window with binary search until it spans one cache
	 * line (32 entries), then finishes with a sequential scan that is friendlier to the CPU
	 * prefetcher. Includes the same append fast-path as the branchy variant and returns the same
	 * value as {@link #unsignedBinarySearch}: index of `k`, or `-(insertionPoint + 1)` if absent.
	 *
	 * Complexity: `O(log n)`.
	 *
	 * @param array array sorted in unsigned-ascending order
	 * @param begin first index of the search range (inclusive)
	 * @param end   index one past the last of the search range (exclusive)
	 * @param k     value to look for
	 * @return the index of `k` if present, otherwise `-(insertionPoint + 1)`
	 */
	private static int hybridUnsignedBinarySearch(
		@Nonnull final char[] array, final int begin, final int end, final char k) {
		// next line accelerates the possibly common case where the value would
		// be inserted at the end
		if ((end > 0) && ((array[end - 1]) < (int) k)) {
			return -end - 1;
		}
		int low = begin;
		int high = end - 1;
		// 32 in the next line matches the size of a cache line
		while (low + 32 <= high) {
			final int middleIndex = (low + high) >>> 1;
			final int middleValue = (array[middleIndex]);

			if (middleValue < (int) k) {
				low = middleIndex + 1;
			} else if (middleValue > (int) k) {
				high = middleIndex - 1;
			} else {
				return middleIndex;
			}
		}
		// we finish the job with a sequential search
		int x = low;
		for (; x <= high; ++x) {
			final int val = (array[x]);
			if (val >= (int) k) {
				if (val == (int) k) {
					return x;
				}
				break;
			}
		}
		return -(x + 1);
	}

	/**
	 * Galloping intersection for lopsided inputs: walks the small set linearly and, for each of its
	 * values, gallops ({@link #advanceUntil}) forward in the large set, writing matches to `buffer`
	 * in order. Backs {@link #unsignedIntersect2by2} when one input dwarfs the other.
	 *
	 * Complexity: `O(smallLength * log largeLength)`.
	 *
	 * @param smallSet    the shorter array driving the walk, sorted ascending
	 * @param smallLength number of leading entries of `smallSet` to consider
	 * @param largeSet    the longer array searched by galloping, sorted ascending
	 * @param largeLength number of leading entries of `largeSet` to consider
	 * @param buffer      output array receiving the intersection, filled from index `0`
	 * @return number of values written to `buffer` (cardinality of the intersection)
	 */
	private static int unsignedOneSidedGallopingIntersect2by2(
		@Nonnull final char[] smallSet,
		final int smallLength,
		@Nonnull final char[] largeSet,
		final int largeLength,
		@Nonnull final char[] buffer
	) {
		if (0 == smallLength) {
			return 0;
		}
		int k1 = 0;
		int k2 = 0;
		int pos = 0;
		char s1 = largeSet[k1];
		char s2 = smallSet[k2];
		while (true) {
			if (s1 < s2) {
				k1 = advanceUntil(largeSet, k1, largeLength, s2);
				if (k1 == largeLength) {
					break;
				}
				s1 = largeSet[k1];
			}
			if (s2 < s1) {
				++k2;
				if (k2 == smallLength) {
					break;
				}
				s2 = smallSet[k2];
			} else {
				// (set2[k2] == set1[k1])
				buffer[pos++] = s2;
				++k2;
				if (k2 == smallLength) {
					break;
				}
				s2 = smallSet[k2];
				k1 = advanceUntil(largeSet, k1, largeLength, s2);
				if (k1 == largeLength) {
					break;
				}
				s1 = largeSet[k1];
			}
		}
		return pos;
	}

	/**
	 * Private constructor to prevent instantiation of utility class
	 */
	private Util() {
	}
}
