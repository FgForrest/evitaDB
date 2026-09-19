package io.evitadb.roaringbitmap.kernel;

import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
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
 * **The extraction kernels are a different animal and are vectorized for a different reason.** Decoding a
 * word's set bits into a value list is an inherently serial `tzcnt`/`blsr` walk with a data-dependent trip
 * count, and it stays scalar here. What the vector step buys is the *negative* answer: one compare tests a
 * whole block of words for zero, and an empty block is skipped without entering the decode loop at all.
 * That is worth having because of what these kernels are actually handed — a repaired union bitmap holding
 * a few dozen values across 1024 words, where almost every block is empty.
 *
 * **Four of these kernels are deliberately not vectorized, and that is a measurement rather than an
 * omission.** The two-operand *counting* reductions — `andCardinality`, `orCardinality`, `xorCardinality`,
 * `andNotCardinality` — run 0.83-0.90x of the scalar loop on OpenJDK 21 and a Zen 5 core, because C2
 * already auto-vectorizes `bitCount(a[k] & b[k])` and does it better than an explicit lane accumulator with
 * a cross-lane reduction at the end. They therefore delegate to {@link ScalarBitmapKernels}, keeping the
 * numbers next to the code they condemn. `cardinality` (1.18-1.23x), `cardinalityInRange` (1.39x), the four
 * fused store-and-count kernels and the extraction kernels stay vector.
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

	/**
	 * Measured **0.83-0.90x** of {@link ScalarBitmapKernels} under JMH (3 forks, 10 iterations, 2 s each,
	 * on a quiet box): 79.5-86.9 ns against 71.6-72.4 ns. C2 already auto-vectorizes the scalar
	 * `bitCount(a[k] & b[k])` reduction, and an explicit lane loop with its own accumulator and final
	 * cross-lane reduction does not beat what the compiler produces. Delegated rather than deleted so the
	 * measurement stays visible at the site it applies to.
	 */
	@Override
	public int andCardinality(@Nonnull final long[] a, @Nonnull final long[] b) {
		return ScalarBitmapKernels.INSTANCE.andCardinality(a, b);
	}

	/**
	 * Delegated to {@link ScalarBitmapKernels} for the reason measured on {@link #andCardinality}: the
	 * two-operand counting reductions differ only in which boolean operation they fold, so the 0.83-0.90x
	 * result carries to all four of them.
	 */
	@Override
	public int orCardinality(@Nonnull final long[] a, @Nonnull final long[] b) {
		return ScalarBitmapKernels.INSTANCE.orCardinality(a, b);
	}

	/**
	 * Delegated to {@link ScalarBitmapKernels}; see {@link #andCardinality} for the measurement.
	 */
	@Override
	public int xorCardinality(@Nonnull final long[] a, @Nonnull final long[] b) {
		return ScalarBitmapKernels.INSTANCE.xorCardinality(a, b);
	}

	/**
	 * Delegated to {@link ScalarBitmapKernels}; see {@link #andCardinality} for the measurement.
	 */
	@Override
	public int andNotCardinality(@Nonnull final long[] a, @Nonnull final long[] b) {
		return ScalarBitmapKernels.INSTANCE.andNotCardinality(a, b);
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
	public int extract(@Nonnull final long[] words, @Nonnull final char[] out) {
		int pos = 0;
		final int bound = SPECIES.loopBound(words.length);
		int k = 0;
		for (; k < bound; k += LANES) {
			long lanes = nonZeroLanes(LongVector.fromArray(SPECIES, words, k));
			// the whole point of the block: a repaired union bitmap is mostly empty words, and an empty
			// block is skipped without ever entering the per-word decode loop
			while (lanes != 0) {
				final int word = k + Long.numberOfTrailingZeros(lanes);
				lanes &= (lanes - 1);
				final int base = word << 6;
				long bitset = words[word];
				while (bitset != 0) {
					out[pos++] = (char) (base + Long.numberOfTrailingZeros(bitset));
					bitset &= (bitset - 1);
				}
			}
		}
		for (; k < words.length; k++) {
			final int base = k << 6;
			long bitset = words[k];
			while (bitset != 0) {
				out[pos++] = (char) (base + Long.numberOfTrailingZeros(bitset));
				bitset &= (bitset - 1);
			}
		}
		return pos;
	}

	@Override
	public int extract(
		@Nonnull final long[] words,
		@Nonnull final int[] out,
		final int outOffset,
		final int base
	) {
		int pos = outOffset;
		final int bound = SPECIES.loopBound(words.length);
		int k = 0;
		for (; k < bound; k += LANES) {
			long lanes = nonZeroLanes(LongVector.fromArray(SPECIES, words, k));
			while (lanes != 0) {
				final int word = k + Long.numberOfTrailingZeros(lanes);
				lanes &= (lanes - 1);
				final int wordBase = base + (word << 6);
				long bitset = words[word];
				while (bitset != 0) {
					out[pos++] = wordBase + Long.numberOfTrailingZeros(bitset);
					bitset &= (bitset - 1);
				}
			}
		}
		for (; k < words.length; k++) {
			final int wordBase = base + (k << 6);
			long bitset = words[k];
			while (bitset != 0) {
				out[pos++] = wordBase + Long.numberOfTrailingZeros(bitset);
				bitset &= (bitset - 1);
			}
		}
		return pos - outOffset;
	}

	@Override
	public int extractAnd(@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final char[] out) {
		int pos = 0;
		final int bound = SPECIES.loopBound(a.length);
		int k = 0;
		for (; k < bound; k += LANES) {
			// the combined block is zero-testable exactly like a single one, so a block whose operands
			// share no bit costs one compare rather than eight
			long lanes = nonZeroLanes(
				LongVector.fromArray(SPECIES, a, k).and(LongVector.fromArray(SPECIES, b, k))
			);
			while (lanes != 0) {
				final int word = k + Long.numberOfTrailingZeros(lanes);
				lanes &= (lanes - 1);
				final int base = word << 6;
				long bitset = a[word] & b[word];
				while (bitset != 0) {
					out[pos++] = (char) (base + Long.numberOfTrailingZeros(bitset));
					bitset &= (bitset - 1);
				}
			}
		}
		for (; k < a.length; k++) {
			final int base = k << 6;
			long bitset = a[k] & b[k];
			while (bitset != 0) {
				out[pos++] = (char) (base + Long.numberOfTrailingZeros(bitset));
				bitset &= (bitset - 1);
			}
		}
		return pos;
	}

	@Override
	public int extractAndNot(@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final char[] out) {
		int pos = 0;
		final int bound = SPECIES.loopBound(a.length);
		int k = 0;
		for (; k < bound; k += LANES) {
			long lanes = nonZeroLanes(
				LongVector.fromArray(SPECIES, a, k)
					.lanewise(VectorOperators.AND_NOT, LongVector.fromArray(SPECIES, b, k))
			);
			while (lanes != 0) {
				final int word = k + Long.numberOfTrailingZeros(lanes);
				lanes &= (lanes - 1);
				final int base = word << 6;
				long bitset = a[word] & (~b[word]);
				while (bitset != 0) {
					out[pos++] = (char) (base + Long.numberOfTrailingZeros(bitset));
					bitset &= (bitset - 1);
				}
			}
		}
		for (; k < a.length; k++) {
			final int base = k << 6;
			long bitset = a[k] & (~b[k]);
			while (bitset != 0) {
				out[pos++] = (char) (base + Long.numberOfTrailingZeros(bitset));
				bitset &= (bitset - 1);
			}
		}
		return pos;
	}

	@Override
	public int extractXor(@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final char[] out) {
		int pos = 0;
		final int bound = SPECIES.loopBound(a.length);
		int k = 0;
		for (; k < bound; k += LANES) {
			long lanes = nonZeroLanes(
				LongVector.fromArray(SPECIES, a, k)
					.lanewise(VectorOperators.XOR, LongVector.fromArray(SPECIES, b, k))
			);
			while (lanes != 0) {
				final int word = k + Long.numberOfTrailingZeros(lanes);
				lanes &= (lanes - 1);
				final int base = word << 6;
				long bitset = a[word] ^ b[word];
				while (bitset != 0) {
					out[pos++] = (char) (base + Long.numberOfTrailingZeros(bitset));
					bitset &= (bitset - 1);
				}
			}
		}
		for (; k < a.length; k++) {
			final int base = k << 6;
			long bitset = a[k] ^ b[k];
			while (bitset != 0) {
				out[pos++] = (char) (base + Long.numberOfTrailingZeros(bitset));
				bitset &= (bitset - 1);
			}
		}
		return pos;
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
	 * Which lanes of a block hold a non-zero word, as a bit set indexed by lane.
	 *
	 * The comparison is one instruction and its result is one mask register, so a block of eight empty
	 * words is recognised and skipped for the price of a single test — which is the whole reason the
	 * extraction kernels are worth vectorizing at all, given that decoding the bits of a non-empty word
	 * stays scalar.
	 *
	 * `anyTrue()` is asked first because the mask-to-`long` move is the more expensive of the two and the
	 * empty block is the common case here.
	 *
	 * @param block the words to test
	 * @return bit `i` set when lane `i` of `block` is non-zero; `0` when the whole block is empty
	 */
	private static long nonZeroLanes(@Nonnull final LongVector block) {
		final VectorMask<Long> nonZero = block.compare(VectorOperators.NE, 0L);
		return nonZero.anyTrue() ? nonZero.toLong() : 0L;
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
