package io.evitadb.roaringbitmap.kernel;

import javax.annotation.Nonnull;

/**
 * Word-level kernels over the `long[]` word array that backs a dense roaring container.
 *
 * Every dense container is a `long[1024]` (8 KiB) covering one 65536-wide chunk, and the arithmetic the
 * engine spends most of its bitmap time in is a word-wise boolean operation, a population count of the
 * result, or both at once. This interface names those loops so that a SIMD implementation can replace
 * them wholesale while {@link ScalarBitmapKernels} stays the reference the vector twin is checked against.
 *
 * **Contract shared by every method.** The kernels read exactly `a.length` words; `b` and `out` must be at
 * least that long. `out` may be the very same array as `a` or as `b`, which is how the in-place container
 * operators use the fused variants — the kernels never read a word after writing it. Nothing is allocated,
 * nothing is validated: these are inner loops, and the callers are the container operators in this module.
 *
 * The fused `and`/`or`/`xor`/`andNot` variants write the result **and** return its population count in one
 * pass over the words, which is what turns a container operator from two passes (count, then store) into one.
 * Where the caller has to know the cardinality *before* it can decide which container type to allocate, it
 * calls the matching `...Cardinality` kernel first and the fused kernel afterwards.
 *
 * @see ScalarBitmapKernels the reference implementation, and the fallback when no vector one is usable
 * @see VectorKernels the holder that picks between the two
 */
public interface BitmapKernels {

	/**
	 * Population count of the whole word array.
	 *
	 * @param a word array to count
	 * @return the number of set bits in `a`
	 */
	int cardinality(@Nonnull long[] a);

	/**
	 * Population count of `a & b` without materializing it.
	 *
	 * @param a first word array
	 * @param b second word array, at least as long as `a`
	 * @return the number of set bits the two arrays share
	 */
	int andCardinality(@Nonnull long[] a, @Nonnull long[] b);

	/**
	 * Population count of `a | b` without materializing it.
	 *
	 * @param a first word array
	 * @param b second word array, at least as long as `a`
	 * @return the number of set bits present in at least one of the two arrays
	 */
	int orCardinality(@Nonnull long[] a, @Nonnull long[] b);

	/**
	 * Population count of `a ^ b` without materializing it.
	 *
	 * @param a first word array
	 * @param b second word array, at least as long as `a`
	 * @return the number of set bits present in exactly one of the two arrays
	 */
	int xorCardinality(@Nonnull long[] a, @Nonnull long[] b);

	/**
	 * Population count of `a & ~b` without materializing it.
	 *
	 * @param a first word array
	 * @param b second word array, at least as long as `a`
	 * @return the number of set bits of `a` that are not set in `b`
	 */
	int andNotCardinality(@Nonnull long[] a, @Nonnull long[] b);

	/**
	 * Writes `a & b` into `out` and returns its population count, in a single pass over the words.
	 *
	 * @param a   first word array
	 * @param b   second word array, at least as long as `a`
	 * @param out destination, at least as long as `a`; may be `a` or `b` itself
	 * @return the number of set bits written to `out`
	 */
	int and(@Nonnull long[] a, @Nonnull long[] b, @Nonnull long[] out);

	/**
	 * Writes `a | b` into `out` and returns its population count, in a single pass over the words.
	 *
	 * @param a   first word array
	 * @param b   second word array, at least as long as `a`
	 * @param out destination, at least as long as `a`; may be `a` or `b` itself
	 * @return the number of set bits written to `out`
	 */
	int or(@Nonnull long[] a, @Nonnull long[] b, @Nonnull long[] out);

	/**
	 * Writes `a ^ b` into `out` and returns its population count, in a single pass over the words.
	 *
	 * @param a   first word array
	 * @param b   second word array, at least as long as `a`
	 * @param out destination, at least as long as `a`; may be `a` or `b` itself
	 * @return the number of set bits written to `out`
	 */
	int xor(@Nonnull long[] a, @Nonnull long[] b, @Nonnull long[] out);

	/**
	 * Writes `a & ~b` into `out` and returns its population count, in a single pass over the words.
	 *
	 * @param a   first word array
	 * @param b   second word array, at least as long as `a`
	 * @param out destination, at least as long as `a`; may be `a` or `b` itself
	 * @return the number of set bits written to `out`
	 */
	int andNot(@Nonnull long[] a, @Nonnull long[] b, @Nonnull long[] out);

	/**
	 * Exact population count of the bits at absolute bit indices `[start, end)`, masking the partial first
	 * and last words so that only bits inside the range are counted. Returns `0` when `start >= end`.
	 *
	 * The interior word count is arbitrary here, so unlike the whole-array kernels this one is not a
	 * multiple of any vector length and a vector implementation has to keep a scalar tail.
	 *
	 * @param a     word array to count in
	 * @param start first bit index (inclusive)
	 * @param end   bit index one past the last (exclusive)
	 * @return number of set bits within the range
	 */
	int cardinalityInRange(@Nonnull long[] a, int start, int end);

}
