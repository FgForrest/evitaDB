/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;

/**
 * The one hash every {@link Container} encoding computes, defined over the chunk's **canonical word form**
 * rather than over whatever the container happens to store.
 *
 * A chunk is a set of 16-bit values, and that set has exactly one 1024-word bitmap. An {@link ArrayContainer},
 * a {@link RunContainer} and a {@link BitmapContainer} holding the same set therefore agree here by
 * construction — each one walks its own storage and reports the same words, without materializing them.
 * That is what makes the hash obey the {@link Object#hashCode()} contract across encodings: `runOptimize()`
 * re-encodes a chunk without changing the set, and `equals` compares sets, so the hash has to as well.
 *
 * ## The three upstream defects this replaces
 *
 * All three are RoaringBitmap's, present in the released artifacts (verified in `0.9.15` bytecode), and none
 * has been reported upstream — this fork diverges rather than waiting.
 *
 * 1. **`ArrayContainer.hashCode` and `RunContainer.hashCode` read only their last seven entries.** The
 *    recurrence is written `hash += 31 * hash + entry`, and the `+=` makes it `hash = 32 * hash + entry` —
 *    base `2^5`. The entry `j` places from the end carries the coefficient `2^(5 * j)`, and `2^35` is `0` in
 *    32-bit arithmetic, so everything before the last seven entries is annihilated. A container holding up to
 *    4,096 values was hashed from seven of them, and even inside that window only the low `32 - 5j` bits of
 *    each survive. Two containers differing anywhere but in their last seven entries collide.
 * 2. **The two disagreed with each other.** `RunContainer` folded the interleaved `value, length` run pairs
 *    while `ArrayContainer` folded values, so the same set hashed differently once `runOptimize()` had run —
 *    while `RunContainer.equals(ArrayContainer)` reported the two equal.
 * 3. **`BitmapContainer.hashCode` was `Arrays.hashCode(long[])`, which cannot see an all-ones word.** That
 *    method folds `(int) (e ^ (e >>> 32))`, which is `0` for `-1L` exactly as it is for `0L`. Two disjoint
 *    dense chunks whose every word is saturated hashed identically — reachable with nothing but `add(int)`.
 *
 * ## Why the weights, and why not `Arrays.hashCode`
 *
 * The word at index `i` is folded as `mix(word) * WORD_WEIGHT[i]`, and the contributions are summed. Summing
 * position-weighted terms rather than running a sequential polynomial is what lets a sparse container skip the
 * words it does not occupy: an empty word contributes `mix(0) == 0`, so skipping it and folding it are the
 * same act. A sequential `hash = 31 * hash + word` could not skip, and would cost every container 1,024 steps.
 *
 * {@link #mixWord(long)} is a 64-to-32 finalizer rather than `Long#hashCode`, precisely because the latter
 * collapses `-1L` onto `0L` (defect 3). It maps `0L` to `0`, which is what the skip relies on; it is not
 * injective and nothing here assumes it is — see {@link #SEED}.
 *
 * ## Cost
 *
 * `BitmapContainer` walks its 1,024 words and `ArrayContainer` its values (at most 4,096) — neither is
 * proportional to a chunk's 65,536-value capacity, which a value-by-value fold would have been for the dense
 * encodings. `RunContainer` takes one step per word each of its runs touches, which is `nbrruns + 1023` at
 * worst. Neither term bounds it on its own: runs are disjoint and ascending, so one run covering the chunk
 * crosses all 1,023 internal boundaries for 1,024 steps at `nbrruns == 1`, while 32,768 single-value runs
 * cross none and cost 32,768 steps over 1,024 distinct words. `runOptimize()` never produces that second
 * shape — it picks the run encoding only where it is the smaller one — but `new RunContainer()` and `add`
 * reach it. The number of *folds* is at most 1,024 in every case, a fold happening only on a word change.
 *
 * ## On re-sync
 *
 * Upstream has no counterpart to this class and its `hashCode` methods are the defective ones described above.
 * An upstream diff touching any of the three will not merge cleanly; **keep this side**. The differential
 * tests in `ContainerHashCodeTest` pin every claim made here, including a reference implementation that
 * materializes the 1,024 words the slow way and hashes those.
 */
final class ContainerHash {
	/**
	 * `31^i` for each word index, so a container can fold word `i` without walking the words before it.
	 *
	 * The base is the conventional odd multiplier: 31 is coprime with `2^32`, so no power of it is ever zero
	 * and no word position is ever annihilated — the property upstream's base-32 recurrence lost.
	 */
	private static final int[] WORD_WEIGHT = new int[BitmapContainer.MAX_CAPACITY / 64];

	/**
	 * Constant offset carried by every hash, so that an empty container does not answer `0`.
	 *
	 * That is the whole of it. The fold is a sum, so the seed shifts every result by the same amount and
	 * separates no two sets that the sum does not separate by itself — in particular **`hash == SEED` is not
	 * an emptiness test.** {@link #mixWord(long)} is a 64-to-32 map, so roughly `2^32` words mix to `0`, and
	 * a container holding one of those and nothing else lands back exactly on the seed; a word mixing to
	 * `-SEED` exists on the same argument and makes a non-empty container hash to `0`. Both witnesses are
	 * constructed in `ContainerHashCodeTest`.
	 */
	private static final int SEED = 0x9E3779B9;

	static {
		int weight = 1;
		for (int i = 0; i < WORD_WEIGHT.length; i++) {
			WORD_WEIGHT[i] = weight;
			weight *= 31;
		}
	}

	private ContainerHash() {
	}

	/**
	 * Folds one 64-bit word of the canonical form into an accumulator.
	 *
	 * Callers pass each occupied word exactly once, in any order — the fold is a sum of independent
	 * position-weighted terms, so order does not matter, but a word counted twice would corrupt the result.
	 * Empty words may be passed or skipped interchangeably; both contribute nothing.
	 *
	 * @param hash      accumulator, seeded by {@link #seed()}
	 * @param wordIndex index of the word within the chunk's 1,024-word form
	 * @param word      the word's bits
	 * @return the accumulator with this word folded in
	 */
	static int fold(final int hash, final int wordIndex, final long word) {
		return hash + mixWord(word) * WORD_WEIGHT[wordIndex];
	}

	/**
	 * Starting value for a fold; the hash of a container occupying no words.
	 */
	static int seed() {
		return SEED;
	}

	/**
	 * 64-to-32 finalizer over one word, mapping `0L` to `0` and `-1L` to something else.
	 *
	 * The multiply-xorshift is MurmurHash3's. Exactly two of its properties are load-bearing: `mix(0L)` is
	 * `0`, which is what makes skipping an empty word identical to folding it, and `mix(-1L)` is not `0`,
	 * which is the whole of defect 3 — `Long#hashCode` folds `e ^ (e >>> 32)` and answers `0` for both. Being
	 * a 64-to-32 map it is many-to-one everywhere, `0` included, and no caller may assume otherwise.
	 */
	static int mixWord(final long word) {
		long mixed = word * 0xFF51AFD7ED558CCDL;
		mixed ^= mixed >>> 33;
		return (int) mixed;
	}

	/**
	 * Reference implementation, for tests and for reading: hashes a chunk by materializing its canonical
	 * 1,024-word form. Every container's own `hashCode` must agree with this for the set it holds.
	 *
	 * @param words the chunk's canonical word form, exactly {@link BitmapContainer#MAX_CAPACITY} bits
	 * @return the canonical hash of the set those words describe
	 */
	static int ofWords(@Nonnull final long[] words) {
		int hash = SEED;
		for (int i = 0; i < words.length; i++) {
			if (words[i] != 0L) {
				hash = fold(hash, i, words[i]);
			}
		}
		return hash;
	}
}
