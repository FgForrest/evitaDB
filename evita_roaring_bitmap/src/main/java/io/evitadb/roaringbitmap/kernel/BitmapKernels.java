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
 * **The extraction kernels come in a hint-less and a hinted form, and the hint is purely advisory.** A
 * caller that already knows how many values it is about to decode passes that count, and an implementation
 * may use it to pick between two equally correct strategies — block skipping pays on a sparse word array and
 * costs on a dense one. The hint never changes what is written: both forms decode exactly the set bits, in
 * the same order, and a wrong hint costs speed and nothing else. The hint-less forms therefore stay
 * available for the callers that genuinely cannot know (a lazy container carries no population count), and
 * they behave as if a sparse container had been announced.
 *
 * Only the kernels with a counting caller in this module carry a hinted form. `extractAnd` has none,
 * because the intersection operators fuse the words first and then decode the result through the
 * single-operand `extract`, so nothing ever reaches `extractAnd` holding a count.
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
	 * Decodes every set bit of the word array into `out` in ascending order, each bit written as its
	 * position `64 * wordIndex + trailingZeros` cast to a `char`.
	 *
	 * This is the extraction half of the dense/sparse round trip: a lazy union promotes a sparse chunk to
	 * an 8 KiB word array, and a repair that finds few bits set reads them straight back out through this
	 * kernel. A repaired union bitmap is therefore mostly **empty words**, which is what a vector
	 * implementation exploits — it tests a whole block of words for zero and skips it — while the bits of
	 * a non-empty word are still decoded one `tzcnt`/`blsr` step at a time.
	 *
	 * Positions above `65535` wrap, as the `char` result type implies; the dense container this kernel
	 * serves is 1024 words, where they cannot arise.
	 *
	 * @param words source word array
	 * @param out   destination, at least as long as the word array's population count, filled from index `0`
	 * @return the number of values written, i.e. the population count of `words`
	 */
	int extract(@Nonnull long[] words, @Nonnull char[] out);

	/**
	 * {@link #extract(long[], char[])} for a caller that already knows the population count it is about to
	 * decode, so that an implementation can choose a strategy suited to the density.
	 *
	 * The count is advisory: the values written and their order are exactly those of the hint-less form,
	 * whatever is passed. A count that does not match the word array — a lazy container's `-1`, say — costs
	 * only the wrong strategy.
	 *
	 * @param words       source word array
	 * @param out         destination, at least as long as the word array's population count, filled from
	 *                    index `0`
	 * @param cardinality number of set bits the caller expects `words` to hold
	 * @return the number of values written, i.e. the population count of `words`
	 */
	int extract(@Nonnull long[] words, @Nonnull char[] out, int cardinality);

	/**
	 * Decodes every set bit of the word array into `out` in ascending order, each bit written as
	 * `base + 64 * wordIndex + trailingZeros`.
	 *
	 * The `int` twin of {@link #extract(long[], char[])}, for the callers that need the value's high bits
	 * carried along rather than the 16-bit position on its own. `base` is added, not OR-ed, which is the
	 * same thing whenever it has no bits below `2^16` — as a container's key-derived prefix never does.
	 *
	 * @param words     source word array
	 * @param out       destination, with room for the population count from `outOffset`
	 * @param outOffset first write position in `out`
	 * @param base      value added to every decoded position
	 * @return the number of values written, i.e. the population count of `words`
	 */
	int extract(@Nonnull long[] words, @Nonnull int[] out, int outOffset, int base);

	/**
	 * {@link #extract(long[], int[], int, int)} for a caller that already knows the population count it is
	 * about to decode, so that an implementation can choose a strategy suited to the density.
	 *
	 * The count is advisory, exactly as it is for {@link #extract(long[], char[], int)}.
	 *
	 * @param words       source word array
	 * @param out         destination, with room for the population count from `outOffset`
	 * @param outOffset   first write position in `out`
	 * @param base        value added to every decoded position
	 * @param cardinality number of set bits the caller expects `words` to hold
	 * @return the number of values written, i.e. the population count of `words`
	 */
	int extract(@Nonnull long[] words, @Nonnull int[] out, int outOffset, int base, int cardinality);

	/**
	 * Decodes every set bit of `a & b` into `out` in ascending order, as {@link #extract(long[], char[])}
	 * does for a single word array, without materializing the intersection.
	 *
	 * @param a   first word array
	 * @param b   second word array, at least as long as `a`
	 * @param out destination, at least as long as the intersection's cardinality, filled from index `0`
	 * @return the number of values written
	 */
	int extractAnd(@Nonnull long[] a, @Nonnull long[] b, @Nonnull char[] out);

	/**
	 * Decodes every set bit of `a & ~b` into `out` in ascending order, without materializing the
	 * difference.
	 *
	 * @param a   first word array
	 * @param b   second word array, at least as long as `a`
	 * @param out destination, at least as long as the difference's cardinality, filled from index `0`
	 * @return the number of values written
	 */
	int extractAndNot(@Nonnull long[] a, @Nonnull long[] b, @Nonnull char[] out);

	/**
	 * {@link #extractAndNot(long[], long[], char[])} for a caller that already counted the difference — as
	 * the out-of-place `andNot` operator does, since it has to size the destination container first.
	 *
	 * The count is advisory, exactly as it is for {@link #extract(long[], char[], int)}.
	 *
	 * @param a           first word array
	 * @param b           second word array, at least as long as `a`
	 * @param out         destination, at least as long as the difference's cardinality, filled from index
	 *                    `0`
	 * @param cardinality number of set bits the caller expects `a & ~b` to hold
	 * @return the number of values written
	 */
	int extractAndNot(@Nonnull long[] a, @Nonnull long[] b, @Nonnull char[] out, int cardinality);

	/**
	 * Decodes every set bit of `a ^ b` into `out` in ascending order, without materializing the symmetric
	 * difference.
	 *
	 * @param a   first word array
	 * @param b   second word array, at least as long as `a`
	 * @param out destination, at least as long as the symmetric difference's cardinality, filled from
	 *            index `0`
	 * @return the number of values written
	 */
	int extractXor(@Nonnull long[] a, @Nonnull long[] b, @Nonnull char[] out);

	/**
	 * {@link #extractXor(long[], long[], char[])} for a caller that already counted the symmetric
	 * difference — as the `xor` operator does, since it has to size the destination container first.
	 *
	 * The count is advisory, exactly as it is for {@link #extract(long[], char[], int)}.
	 *
	 * @param a           first word array
	 * @param b           second word array, at least as long as `a`
	 * @param out         destination, at least as long as the symmetric difference's cardinality, filled
	 *                    from index `0`
	 * @param cardinality number of set bits the caller expects `a ^ b` to hold
	 * @return the number of values written
	 */
	int extractXor(@Nonnull long[] a, @Nonnull long[] b, @Nonnull char[] out, int cardinality);

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
