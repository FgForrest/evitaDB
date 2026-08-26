/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.trigram;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;

/**
 * Packs a trigram - three consecutive Unicode **code points** of a normalized attribute value - into a single
 * `long`, and extracts the set of distinct trigrams a value contributes to a {@link TrigramIndex}.
 *
 * # The 63-bit key
 *
 * A Unicode code point never exceeds `0x10FFFF`, so 21 bits hold one exactly and three fit in 63 bits - the sign
 * bit is always clear and a packed trigram is therefore always a non-negative `long`. The layout is:
 *
 * ```text
 * cp1: bits  0..20   (the FIRST code point of the trigram)
 * cp2: bits 21..41
 * cp3: bits 42..62
 * ```
 *
 * Because the key is self-describing, the index needs no string dictionary for its keys - a primitive-long-keyed
 * structure is enough. The clear sign bit is not decoration either: it is the witness that the packing never
 * overflows, since a third code point whose high bit spilled into it would collide with a different trigram.
 *
 * # Normalization - the false-negative rule
 *
 * The index is a candidate generator whose candidates are verified exactly afterwards, so a false positive is
 * harmless. A false NEGATIVE is not: it silently removes a matching entity from a query result. Extraction must
 * therefore see exactly the text the exact predicate sees, and must never normalize more aggressively than the
 * predicate does.
 *
 * This class consequently does **no normalization of its own**. Every value it is given has already been through
 * the owning attribute's normalizer (`FilterIndex#getNormalizer`, today Unicode NFD and case-sensitive for
 * `String` attributes) - the write path hands it the very key the shared value tree stores, and the query path
 * must hand it a pattern put through the same normalizer. When issue #545 moves a locale-aware case fold into that
 * normalizer, extraction inherits it with no change here.
 *
 * Two consequences of NFD that a reader of the memory numbers should know:
 *
 * - NFD decomposes `é` into `e` + a combining acute accent, so an accented value carries more code points - and
 *   more trigrams - than its precomposed form, and some trigrams straddle a grapheme boundary. That inflation is
 *   accepted deliberately; a grapheme-cluster codec was the declared fallback and the corpus measurements did not
 *   call for it.
 * - Extraction runs over code points, never over UTF-16 `char` values, so a surrogate pair occupies one position
 *   and never splits into two halves that would key a trigram nothing can ever match.
 *
 * # Distinct trigrams per value
 *
 * A value contributes each distinct trigram **once**: `"aaaaa"` yields a single `aaa` membership, not three. That
 * is what makes the membership count a function of a value's distinct trigrams rather than of its length, and it
 * is what makes an update that does not change a value's trigram set a no-op for the index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class TrigramCodec {

	/**
	 * Bits one Unicode code point occupies in a packed trigram; `3 * 21 = 63` leaves the sign bit clear.
	 */
	public static final int CODE_POINT_BITS = 21;

	/**
	 * Code points one packed trigram key holds. Fixed by the 63-bit layout above, not by any indexing policy.
	 */
	public static final int CODE_POINTS_PER_TRIGRAM = 3;

	/**
	 * Shortest value - in code points - that contributes any trigram at all. A value shorter than this is absent
	 * from the index altogether, and a query pattern shorter than this cannot be served by it.
	 *
	 * It equals {@link #CODE_POINTS_PER_TRIGRAM} and derives from it rather than restating the number, because the
	 * two are different facts that merely coincide: this one is the indexing policy, that one is what the packing
	 * physically holds. Should the policy ever move off the key's width, only the sites that mean the policy follow.
	 */
	public static final int MINIMAL_INDEXABLE_LENGTH = CODE_POINTS_PER_TRIGRAM;

	/**
	 * Shared empty result for values too short to carry a trigram.
	 */
	public static final long[] NO_TRIGRAMS = new long[0];

	/**
	 * Mask isolating one packed code point.
	 */
	private static final long CODE_POINT_MASK = (1L << CODE_POINT_BITS) - 1L;

	private TrigramCodec() {
		throw new UnsupportedOperationException("TrigramCodec is a static utility and must not be instantiated!");
	}

	/**
	 * Packs three Unicode code points into one trigram key.
	 *
	 * Each code point MUST be a real one - `0` through {@link Character#MAX_CODE_POINT}. The bound is not decoration:
	 * a negative argument sets every high bit and drives its neighbours' fields and the sign bit through the `or`,
	 * while anything above `0x1FFFFF` overflows its own 21-bit field into the next. Either way the key silently
	 * becomes a DIFFERENT trigram's key rather than a rejected one, so the corruption would surface as a divergence
	 * between the index and the shared value tree far from the write that caused it - and a key holding a
	 * non-code-point would make {@link #toDisplayString} throw while rendering the very message meant to report it.
	 *
	 * Surrogate code points (`0xD800` - `0xDFFF`) are deliberately ACCEPTED. A Java `String` may legally hold an
	 * unpaired surrogate, and this codec's contract is to see exactly the text the exact predicate sees - refusing
	 * one here would turn a value the predicate matches happily into a hard failure on the write path, which is the
	 * false-negative rule of this class read at its sharpest. They render fine too: `StringBuilder#appendCodePoint`
	 * refuses only non-code-points, so the diagnostic survives them.
	 *
	 * @param first  first code point of the trigram
	 * @param second second code point of the trigram
	 * @param third  third code point of the trigram
	 * @return the packed key, always non-negative
	 * @throws io.evitadb.exception.GenericEvitaInternalError when any argument is not a Unicode code point
	 */
	public static long pack(int first, int second, int third) {
		// hand-rolled bounds checks keep this hot-path packer zero-allocation on success (no eager string, no lambda)
		checkCodePoint(first, "first");
		checkCodePoint(second, "second");
		checkCodePoint(third, "third");
		return ((long) first)
			| (((long) second) << CODE_POINT_BITS)
			| (((long) third) << (2 * CODE_POINT_BITS));
	}

	/**
	 * Refuses an argument of {@link #pack(int, int, int)} that is not a Unicode code point, and therefore cannot be
	 * packed into a 21-bit field without changing which trigram the key names.
	 *
	 * @param codePoint the code point to validate
	 * @param position  the name of the trigram position it was passed for, for the refusal message
	 */
	private static void checkCodePoint(int codePoint, @Nonnull String position) {
		if (codePoint < 0 || codePoint > Character.MAX_CODE_POINT) {
			throw new GenericEvitaInternalError(
				"Value " + codePoint + " is not a Unicode code point and cannot be packed as the " + position +
					" code point of a trigram key."
			);
		}
	}

	/**
	 * Unpacks one code point out of a trigram key.
	 *
	 * @param trigram  packed trigram key
	 * @param position zero-based position within the trigram (`0`, `1` or `2`)
	 * @return the code point at that position
	 */
	public static int codePointAt(long trigram, int position) {
		return (int) ((trigram >>> (position * CODE_POINT_BITS)) & CODE_POINT_MASK);
	}

	/**
	 * Renders a trigram key back into its three characters, for diagnostics and error messages that need to name a
	 * concrete trigram.
	 *
	 * @param trigram packed trigram key
	 * @return the three-code-point string the key encodes
	 */
	@Nonnull
	public static String toDisplayString(long trigram) {
		final StringBuilder result = new StringBuilder(6);
		for (int position = 0; position < CODE_POINTS_PER_TRIGRAM; position++) {
			result.appendCodePoint(codePointAt(trigram, position));
		}
		return result.toString();
	}

	/**
	 * Extracts the distinct trigrams a value contributes, sorted ascending.
	 *
	 * The value must ALREADY be in the canonical form the shared value tree stores it in - see the normalization
	 * section of this class. Nothing here normalizes, deliberately, so that the write path and the query path
	 * cannot end up applying the normalizer a different number of times.
	 *
	 * @param normalizedValue value in its canonical stored form
	 * @return ascending array of distinct packed trigrams; {@link #NO_TRIGRAMS} when the value holds fewer than
	 * {@link #MINIMAL_INDEXABLE_LENGTH} code points
	 */
	@Nonnull
	public static long[] extractUniqueTrigrams(@Nonnull String normalizedValue) {
		final int length = normalizedValue.length();
		if (length < MINIMAL_INDEXABLE_LENGTH) {
			// a trigram needs three code points and a code point needs at least one char, so this is a cheap
			// pre-test that never rejects a value the full code-point count would have accepted
			return NO_TRIGRAMS;
		}
		final int codePoints = normalizedValue.codePointCount(0, length);
		if (codePoints < MINIMAL_INDEXABLE_LENGTH) {
			return NO_TRIGRAMS;
		}
		final long[] extracted = new long[codePoints - 2];
		// rolling window over code points; -1 is not a valid code point, so it is a safe "not filled yet" marker
		int first = -1;
		int second = -1;
		int written = 0;
		int offset = 0;
		while (offset < length) {
			final int current = normalizedValue.codePointAt(offset);
			offset += Character.charCount(current);
			if (first >= 0) {
				extracted[written++] = pack(first, second, current);
			}
			first = second;
			second = current;
		}
		Arrays.sort(extracted);
		return deduplicateSorted(extracted);
	}

	/**
	 * Extracts the distinct trigrams of a value that is known to be a `String`, refusing anything else.
	 *
	 * The refusal is the point: the SUBSTRING capability is accepted by the schema only on `String` / `String[]`
	 * attributes, so a value of any other type arriving here means the capability was maintained for an attribute
	 * it was never allowed on, and silently indexing its `toString()` would produce an index that answers plausible
	 * nonsense.
	 *
	 * @param normalizedValue the normalized value the shared value tree holds
	 * @return ascending array of distinct packed trigrams
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the value is not a `String`
	 */
	@Nonnull
	public static long[] extractUniqueTrigramsOfValue(@Nonnull Serializable normalizedValue) {
		Assert.isPremiseValid(
			normalizedValue instanceof String,
			() -> "The trigram index can only index String values, but was handed a `" +
				normalizedValue.getClass().getName() + "` - the SUBSTRING filter capability is accepted by the " +
				"schema on String and String[] attributes only, so this attribute should never have reached it."
		);
		return extractUniqueTrigrams((String) normalizedValue);
	}

	/**
	 * Collapses runs of equal keys in an already sorted array, returning an exact-size copy.
	 *
	 * Keeping the result exact-size matters because the array is walked immediately by the caller and then dropped:
	 * slack capacity would be pure garbage on the write path, which pays this once per value born.
	 *
	 * @param sorted ascending array, possibly holding duplicates
	 * @return ascending array of distinct entries
	 */
	@Nonnull
	private static long[] deduplicateSorted(@Nonnull long[] sorted) {
		if (sorted.length == 0) {
			return NO_TRIGRAMS;
		}
		int distinct = 1;
		for (int i = 1; i < sorted.length; i++) {
			if (sorted[i] != sorted[i - 1]) {
				sorted[distinct++] = sorted[i];
			}
		}
		return distinct == sorted.length ? sorted : Arrays.copyOf(sorted, distinct);
	}

}
