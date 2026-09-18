package io.evitadb.roaringbitmap.kernel;

import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

import javax.annotation.Nonnull;

/**
 * {@link BitmapKernels} implemented on the JDK Vector API (`jdk.incubator.vector`).
 *
 * Each kernel walks the word array a full vector at a time: the boolean operation is a single lanewise
 * instruction, the population count is `BIT_COUNT` (a native `vpopcntq` on AVX-512 VPOPCNTDQ, a table-driven
 * sequence elsewhere), and the per-lane counts are accumulated in a `LongVector` that is reduced exactly
 * once, at the end. The fused variants store the result of the boolean operation in the very same pass, so
 * an operator that used to count and then store touches each cache line once instead of twice.
 *
 * **This class must never be referenced directly.** It is loaded reflectively by {@link VectorKernels},
 * which is what keeps a missing, half-present or unusable `jdk.incubator.vector` module a fallback to
 * {@link ScalarBitmapKernels} rather than a `NoClassDefFoundError` at the first bitmap operation. For the
 * same reason {@link #isSupported()} lives here and not in the holder: it answers a question only this
 * module's types can ask.
 *
 * The loops keep the shape the JIT is able to intrinsify: static species constants, no allocation, no
 * lambdas, no polymorphism inside the loop body. A scalar tail follows every loop because the kernels are
 * written for an arbitrary word count; for the 1024-word dense container the tail is never entered, since
 * 1024 is a multiple of every vector length the API offers.
 *
 * **Every fused iteration loads both operands before it stores.** That ordering is the whole reason `out`
 * may be `a` or `b`, and it is a contract of {@link BitmapKernels} rather than an accident of how these
 * loops happen to be written.
 *
 * **How much this buys is a measured question, and on some shapes the answer is "little".** On OpenJDK 21
 * and a Zen 5 core, HotSpot already auto-vectorizes the scalar `bitCount(a[k] & b[k])` reduction, so the
 * explicit `andCardinality` kernel here lands within noise of {@link ScalarBitmapKernels}; the win that the
 * container operators actually collect comes from *fusing* a store and a population count into one pass,
 * and the scalar twin is fused too. The provider therefore keeps a per-kernel switch so that a family can
 * be defaulted back to scalar on evidence rather than on a code change.
 */
public final class VectorBitmapKernels implements BitmapKernels {
	/**
	 * The widest `long` lane shape the running CPU offers, chosen once per JVM.
	 */
	private static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;
	/**
	 * Number of `long` lanes in {@link #SPECIES}, i.e. the step of every loop below.
	 */
	private static final int LANES = SPECIES.length();
	/**
	 * Narrowest vector width worth leaving the scalar path for. At 128 bits (two `long` lanes, e.g. plain
	 * NEON) the kernels do the same number of memory accesses as the scalar loop the JIT already widens,
	 * so the indirection buys nothing and the provider stays on {@link ScalarBitmapKernels}.
	 */
	private static final int MINIMUM_USEFUL_BIT_SIZE = 256;

	/**
	 * Answers whether the running CPU offers a vector shape wide enough for these kernels to pay off.
	 *
	 * Invoked reflectively by {@link VectorKernels} before the first instance is created, because
	 * `VectorShape` cannot be named from the holder — the holder has to stay loadable on a JVM where the
	 * incubator module is absent.
	 *
	 * @return `true` when the preferred vector shape is at least {@link #MINIMUM_USEFUL_BIT_SIZE} bits wide
	 */
	public static boolean isSupported() {
		return VectorShape.preferredShape().vectorBitSize() >= MINIMUM_USEFUL_BIT_SIZE;
	}

	/**
	 * Width of the vector shape these kernels run on, in bits. Reported in
	 * {@link VectorKernels#summary()} so that an operator can tell a 512-bit lane from a 256-bit one in the
	 * log, and invoked reflectively for the same reason {@link #isSupported()} is.
	 *
	 * @return the vector width in bits, e.g. `512`
	 */
	public static int vectorBitSize() {
		return SPECIES.vectorBitSize();
	}

	@Override
	public int cardinality(@Nonnull final long[] a) {
		return countWords(a, 0, a.length);
	}

	@Override
	public int andCardinality(@Nonnull final long[] a, @Nonnull final long[] b) {
		LongVector accumulator = LongVector.zero(SPECIES);
		final int bound = SPECIES.loopBound(a.length);
		int k = 0;
		for (; k < bound; k += LANES) {
			final LongVector word = LongVector.fromArray(SPECIES, a, k)
				.and(LongVector.fromArray(SPECIES, b, k));
			accumulator = accumulator.add(word.lanewise(VectorOperators.BIT_COUNT));
		}
		int count = (int) accumulator.reduceLanes(VectorOperators.ADD);
		for (; k < a.length; k++) {
			count += Long.bitCount(a[k] & b[k]);
		}
		return count;
	}

	@Override
	public int orCardinality(@Nonnull final long[] a, @Nonnull final long[] b) {
		LongVector accumulator = LongVector.zero(SPECIES);
		final int bound = SPECIES.loopBound(a.length);
		int k = 0;
		for (; k < bound; k += LANES) {
			final LongVector word = LongVector.fromArray(SPECIES, a, k)
				.or(LongVector.fromArray(SPECIES, b, k));
			accumulator = accumulator.add(word.lanewise(VectorOperators.BIT_COUNT));
		}
		int count = (int) accumulator.reduceLanes(VectorOperators.ADD);
		for (; k < a.length; k++) {
			count += Long.bitCount(a[k] | b[k]);
		}
		return count;
	}

	@Override
	public int xorCardinality(@Nonnull final long[] a, @Nonnull final long[] b) {
		LongVector accumulator = LongVector.zero(SPECIES);
		final int bound = SPECIES.loopBound(a.length);
		int k = 0;
		for (; k < bound; k += LANES) {
			final LongVector word = LongVector.fromArray(SPECIES, a, k)
				.lanewise(VectorOperators.XOR, LongVector.fromArray(SPECIES, b, k));
			accumulator = accumulator.add(word.lanewise(VectorOperators.BIT_COUNT));
		}
		int count = (int) accumulator.reduceLanes(VectorOperators.ADD);
		for (; k < a.length; k++) {
			count += Long.bitCount(a[k] ^ b[k]);
		}
		return count;
	}

	@Override
	public int andNotCardinality(@Nonnull final long[] a, @Nonnull final long[] b) {
		LongVector accumulator = LongVector.zero(SPECIES);
		final int bound = SPECIES.loopBound(a.length);
		int k = 0;
		for (; k < bound; k += LANES) {
			final LongVector word = LongVector.fromArray(SPECIES, a, k)
				.lanewise(VectorOperators.AND_NOT, LongVector.fromArray(SPECIES, b, k));
			accumulator = accumulator.add(word.lanewise(VectorOperators.BIT_COUNT));
		}
		int count = (int) accumulator.reduceLanes(VectorOperators.ADD);
		for (; k < a.length; k++) {
			count += Long.bitCount(a[k] & (~b[k]));
		}
		return count;
	}

	@Override
	public int and(@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final long[] out) {
		LongVector accumulator = LongVector.zero(SPECIES);
		final int bound = SPECIES.loopBound(a.length);
		int k = 0;
		for (; k < bound; k += LANES) {
			final LongVector word = LongVector.fromArray(SPECIES, a, k)
				.and(LongVector.fromArray(SPECIES, b, k));
			word.intoArray(out, k);
			accumulator = accumulator.add(word.lanewise(VectorOperators.BIT_COUNT));
		}
		int count = (int) accumulator.reduceLanes(VectorOperators.ADD);
		for (; k < a.length; k++) {
			final long word = a[k] & b[k];
			out[k] = word;
			count += Long.bitCount(word);
		}
		return count;
	}

	@Override
	public int or(@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final long[] out) {
		LongVector accumulator = LongVector.zero(SPECIES);
		final int bound = SPECIES.loopBound(a.length);
		int k = 0;
		for (; k < bound; k += LANES) {
			final LongVector word = LongVector.fromArray(SPECIES, a, k)
				.or(LongVector.fromArray(SPECIES, b, k));
			word.intoArray(out, k);
			accumulator = accumulator.add(word.lanewise(VectorOperators.BIT_COUNT));
		}
		int count = (int) accumulator.reduceLanes(VectorOperators.ADD);
		for (; k < a.length; k++) {
			final long word = a[k] | b[k];
			out[k] = word;
			count += Long.bitCount(word);
		}
		return count;
	}

	@Override
	public int xor(@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final long[] out) {
		LongVector accumulator = LongVector.zero(SPECIES);
		final int bound = SPECIES.loopBound(a.length);
		int k = 0;
		for (; k < bound; k += LANES) {
			final LongVector word = LongVector.fromArray(SPECIES, a, k)
				.lanewise(VectorOperators.XOR, LongVector.fromArray(SPECIES, b, k));
			word.intoArray(out, k);
			accumulator = accumulator.add(word.lanewise(VectorOperators.BIT_COUNT));
		}
		int count = (int) accumulator.reduceLanes(VectorOperators.ADD);
		for (; k < a.length; k++) {
			final long word = a[k] ^ b[k];
			out[k] = word;
			count += Long.bitCount(word);
		}
		return count;
	}

	@Override
	public int andNot(@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final long[] out) {
		LongVector accumulator = LongVector.zero(SPECIES);
		final int bound = SPECIES.loopBound(a.length);
		int k = 0;
		for (; k < bound; k += LANES) {
			final LongVector word = LongVector.fromArray(SPECIES, a, k)
				.lanewise(VectorOperators.AND_NOT, LongVector.fromArray(SPECIES, b, k));
			word.intoArray(out, k);
			accumulator = accumulator.add(word.lanewise(VectorOperators.BIT_COUNT));
		}
		int count = (int) accumulator.reduceLanes(VectorOperators.ADD);
		for (; k < a.length; k++) {
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
		// the two boundary words are masked so that only the requested bits count; the interior is a
		// whole-word population count of an arbitrary length, hence the tail inside `countWords`
		int count = Long.bitCount(a[firstWord] & (~0L << start));
		count += countWords(a, firstWord + 1, endWord);
		count += Long.bitCount(a[endWord] & (~0L >>> -end));
		return count;
	}

	/**
	 * Population count of the whole words `[from, to)`, vectorized with a scalar tail.
	 *
	 * Per-lane counts never exceed 64 and the longest run this module asks for is 1024 words, so the
	 * `long` accumulator cannot overflow before it is reduced.
	 *
	 * @param a    word array to count in
	 * @param from first word index (inclusive)
	 * @param to   word index one past the last (exclusive)
	 * @return number of set bits in the given words
	 */
	private static int countWords(@Nonnull final long[] a, final int from, final int to) {
		LongVector accumulator = LongVector.zero(SPECIES);
		final int bound = from + SPECIES.loopBound(to - from);
		int k = from;
		for (; k < bound; k += LANES) {
			accumulator = accumulator.add(
				LongVector.fromArray(SPECIES, a, k).lanewise(VectorOperators.BIT_COUNT)
			);
		}
		int count = (int) accumulator.reduceLanes(VectorOperators.ADD);
		for (; k < to; k++) {
			count += Long.bitCount(a[k]);
		}
		return count;
	}

}
