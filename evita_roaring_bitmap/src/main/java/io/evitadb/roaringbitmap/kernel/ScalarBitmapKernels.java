package io.evitadb.roaringbitmap.kernel;

import javax.annotation.Nonnull;

/**
 * The reference implementation of {@link BitmapKernels}: plain word loops over `long[]`, exactly the shape
 * the dense container operators carried before the kernels were extracted.
 *
 * It is both the fallback (whenever a vector implementation is missing, disabled or unusable) and the
 * oracle the vector twin is differentially tested against, so it is deliberately the most obvious code that
 * can be written for each operation. HotSpot's auto-vectorizer already widens most of these loops on its
 * own.
 *
 * **The fused kernels here are single-pass, and that is load-bearing rather than cosmetic.** The measured
 * gain of the whole kernel exercise is pass fusion — one load, one boolean operation, one store and one
 * population count per word instead of a counting pass followed by a storing pass — and an auto-vectorized
 * scalar loop captures essentially all of it. Writing these as "count, then store" would hand the container
 * operators a regression on every JVM that has no vector implementation available.
 *
 * Every fused kernel loads both operands before it stores, which is what lets `out` be `a` or `b`.
 *
 * Stateless and therefore safe to share: {@link #INSTANCE} is the only instance anyone needs.
 */
public final class ScalarBitmapKernels implements BitmapKernels {
	/**
	 * The shared instance. The class holds no state, so a second one would buy nothing.
	 */
	public static final ScalarBitmapKernels INSTANCE = new ScalarBitmapKernels();

	@Override
	public int cardinality(@Nonnull final long[] a) {
		int count = 0;
		for (int k = 0; k < a.length; k++) {
			count += Long.bitCount(a[k]);
		}
		return count;
	}

	@Override
	public int andCardinality(@Nonnull final long[] a, @Nonnull final long[] b) {
		int count = 0;
		for (int k = 0; k < a.length; k++) {
			count += Long.bitCount(a[k] & b[k]);
		}
		return count;
	}

	@Override
	public int orCardinality(@Nonnull final long[] a, @Nonnull final long[] b) {
		int count = 0;
		for (int k = 0; k < a.length; k++) {
			count += Long.bitCount(a[k] | b[k]);
		}
		return count;
	}

	@Override
	public int xorCardinality(@Nonnull final long[] a, @Nonnull final long[] b) {
		int count = 0;
		for (int k = 0; k < a.length; k++) {
			count += Long.bitCount(a[k] ^ b[k]);
		}
		return count;
	}

	@Override
	public int andNotCardinality(@Nonnull final long[] a, @Nonnull final long[] b) {
		int count = 0;
		for (int k = 0; k < a.length; k++) {
			count += Long.bitCount(a[k] & (~b[k]));
		}
		return count;
	}

	@Override
	public int and(@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final long[] out) {
		int count = 0;
		for (int k = 0; k < a.length; k++) {
			final long word = a[k] & b[k];
			out[k] = word;
			count += Long.bitCount(word);
		}
		return count;
	}

	@Override
	public int or(@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final long[] out) {
		int count = 0;
		for (int k = 0; k < a.length; k++) {
			final long word = a[k] | b[k];
			out[k] = word;
			count += Long.bitCount(word);
		}
		return count;
	}

	@Override
	public int xor(@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final long[] out) {
		int count = 0;
		for (int k = 0; k < a.length; k++) {
			final long word = a[k] ^ b[k];
			out[k] = word;
			count += Long.bitCount(word);
		}
		return count;
	}

	@Override
	public int andNot(@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final long[] out) {
		int count = 0;
		for (int k = 0; k < a.length; k++) {
			final long word = a[k] & (~b[k]);
			out[k] = word;
			count += Long.bitCount(word);
		}
		return count;
	}

	@Override
	public int cardinalityInRange(@Nonnull final long[] a, final int start, final int end) {
		if (start >= end) {
			return 0;
		}
		final int firstWord = start / 64;
		final int endWord = (end - 1) / 64;
		if (firstWord == endWord) {
			return Long.bitCount(a[firstWord] & ((~0L << start) & (~0L >>> -end)));
		}
		int count = Long.bitCount(a[firstWord] & (~0L << start));
		for (int k = firstWord + 1; k < endWord; k++) {
			count += Long.bitCount(a[k]);
		}
		count += Long.bitCount(a[endWord] & (~0L >>> -end));
		return count;
	}

}
