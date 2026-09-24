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

package io.evitadb.spike.trigram;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

/**
 * Packs a trigram - three consecutive Unicode **code points** of a normalized attribute value - into a single
 * `long`, and extracts the set of distinct trigrams a value contributes to a trigram index. This is the shared
 * codec of the P8 trigram-substring-index spike: both the corpus statistics (Stage 0) and the in-memory
 * prototype (Stage 1) must agree on it byte for byte, or their numbers describe different indexes.
 *
 * # The 63-bit key
 *
 * A Unicode code point never exceeds `0x10FFFF`, so 21 bits hold one exactly and three fit in 63 bits - the
 * sign bit is always clear and a packed trigram is therefore always a non-negative `long`. The layout is the
 * one the research brief pins (§11):
 *
 * ```text
 * cp1: bits  0..20   (the FIRST code point of the trigram)
 * cp2: bits 21..41
 * cp3: bits 42..62
 * ```
 *
 * Because the key is self-describing, the index needs no string dictionary and no FST for its keys - a plain
 * primitive-long-keyed structure is enough.
 *
 * # Normalization - the false-negative rule
 *
 * A trigram index may hand the verification step candidates that do not match (false positives are removed by
 * exact verification), but it must **never** hide a value that does match. That forces extraction to normalize
 * exactly the way the exact predicate does, and never more aggressively.
 *
 * evitaDB's contract today is `Normalizer.Form.NFD`, case-sensitive and accent-sensitive - see
 * `FilterIndex#getNormalizer`, the `String` branch, which this class mirrors in {@link #normalize(String)}.
 * Extraction therefore does **not** case-fold: {@link #foldCase(String, Locale)} exists only so the analyzer
 * can quantify what issue #545 (case-insensitive attributes) would merge, and is never applied on the default
 * path. When #545 lands, the fold moves into the normalizer itself and extraction inherits it for free.
 *
 * Two consequences of NFD that the numbers must be read with:
 *
 * - NFD decomposes `é` into `e` + a combining acute accent, so a value's code-point count - and with it its
 *   trigram count - grows on accented text, and some trigrams straddle a grapheme boundary. The brief accepts
 *   that inflation deliberately (a grapheme-cluster codec is the declared fallback if Stage 0 shows it is
 *   material), which is one of the things the statistics run measures.
 * - Extraction runs over code points and never over UTF-16 `char` values, so a surrogate pair is one position
 *   and never splits into two halves that would key a trigram nothing can ever match.
 *
 * # Distinct trigrams per value
 *
 * A value contributes each distinct trigram **once**: `"aaaaa"` yields a single `aaa` membership, not three.
 * That is what makes the membership count `E` a function of distinct trigrams rather than of value length, and
 * it is also what makes an update that does not change a value's trigram set a no-op for the index.
 *
 * @author Claude (P8 trigram-substring-index spike), FG Forrest a.s. (c) 2026
 */
public final class SpikeTrigramCodec {

	/**
	 * Bits one Unicode code point occupies in a packed trigram; `3 * 21 = 63` leaves the sign bit clear.
	 */
	public static final int CODE_POINT_BITS = 21;

	/**
	 * Shortest value - in code points, after normalization - that contributes any trigram at all. Anything
	 * shorter falls back to the existing scan at query time and is simply absent from the index.
	 */
	public static final int MINIMAL_INDEXABLE_LENGTH = 3;

	/**
	 * Mask isolating one packed code point.
	 */
	private static final long CODE_POINT_MASK = (1L << CODE_POINT_BITS) - 1L;

	/**
	 * `COMBINING ACUTE ACCENT` - the mark NFD splits off an `é`, canonical combining class 230. Named because
	 * {@link #selfCheck()} asserts on the exact trigrams accented text decomposes into.
	 */
	private static final int COMBINING_ACUTE_ACCENT = 0x0301;

	/**
	 * `COMBINING CEDILLA`, canonical combining class 202 - lower than the acute accent's, so canonical ordering
	 * places it first when both sit on the same base character.
	 */
	private static final int COMBINING_CEDILLA = 0x0327;

	/**
	 * {@link #COMBINING_ACUTE_ACCENT} as text, for building self-check fixtures without an unreadable literal.
	 */
	private static final String COMBINING_ACUTE_ACCENT_TEXT = String.valueOf((char) COMBINING_ACUTE_ACCENT);

	/**
	 * {@link #COMBINING_CEDILLA} as text, for building self-check fixtures without an unreadable literal.
	 */
	private static final String COMBINING_CEDILLA_TEXT = String.valueOf((char) COMBINING_CEDILLA);

	/**
	 * Shared empty result for values too short to carry a trigram.
	 */
	private static final long[] NO_TRIGRAMS = new long[0];

	private SpikeTrigramCodec() {
		throw new UnsupportedOperationException("SpikeTrigramCodec is a static utility and must not be instantiated!");
	}

	/**
	 * Normalizes a raw attribute value into the canonical form the filter index stores, so that trigram
	 * extraction and the exact predicate see identical text. Mirrors `FilterIndex#getNormalizer`'s `String`
	 * branch verbatim.
	 *
	 * @param value raw attribute value, may be `null`
	 * @return the NFD-normalized value, or `null` when the input was `null`
	 */
	@Nullable
	public static String normalize(@Nullable String value) {
		return value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFD);
	}

	/**
	 * Applies the locale-aware case fold that issue #545 would move into the normalizer, and re-normalizes the
	 * result - lower-casing can re-compose characters (Turkish dotted capital I is the classic case), and the
	 * canonical form must stay NFD whatever the fold did.
	 *
	 * Used **only** to quantify how many distinct values a case-insensitive attribute would merge; it is never
	 * part of the default extraction path, which must not fold what the exact predicate does not fold.
	 *
	 * @param normalizedValue value already passed through {@link #normalize(String)}
	 * @param locale          locale whose casing rules apply; `Locale.ROOT` for a non-localized attribute
	 * @return the folded, re-normalized value
	 */
	@Nonnull
	public static String foldCase(@Nonnull String normalizedValue, @Nonnull Locale locale) {
		return Normalizer.normalize(normalizedValue.toLowerCase(locale), Normalizer.Form.NFD);
	}

	/**
	 * Packs three Unicode code points into one trigram key.
	 *
	 * @param first  first code point of the trigram
	 * @param second second code point of the trigram
	 * @param third  third code point of the trigram
	 * @return the packed key, always non-negative
	 */
	public static long pack(int first, int second, int third) {
		return ((long) first)
			| (((long) second) << CODE_POINT_BITS)
			| (((long) third) << (2 * CODE_POINT_BITS));
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
	 * Renders a trigram key back into its three characters, for report lines that name a concrete trigram.
	 *
	 * @param trigram packed trigram key
	 * @return the three-code-point string the key encodes
	 */
	@Nonnull
	public static String toDisplayString(long trigram) {
		final StringBuilder result = new StringBuilder(6);
		for (int position = 0; position < 3; position++) {
			result.appendCodePoint(codePointAt(trigram, position));
		}
		return result.toString();
	}

	/**
	 * Counts the value's length in Unicode code points - the length the memory model of the brief (§8) is
	 * written in terms of, and not the same as `String#length()` on text outside the basic plane.
	 *
	 * @param value value to measure
	 * @return number of code points
	 */
	public static int codePointCount(@Nonnull String value) {
		return value.codePointCount(0, value.length());
	}

	/**
	 * Extracts the distinct trigrams a normalized value contributes, sorted ascending.
	 *
	 * The caller is responsible for having passed the value through {@link #normalize(String)} first - this
	 * method deliberately does not normalize, so that a caller measuring an alternative normalization (the
	 * case-folded variant, for instance) gets exactly the form it asked for.
	 *
	 * @param normalizedValue value in its canonical stored form
	 * @return ascending array of distinct packed trigrams; empty when the value holds fewer than
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
	 * Collapses runs of equal keys in an already sorted array, returning an exact-size copy. Keeping the result
	 * exact-size matters: these arrays are held per distinct value while the analyzer builds both index
	 * variants, so slack capacity would show up in the heap numbers as index cost that the index never pays.
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

	/**
	 * Asserts the extraction invariants every number the spike reports depends on. Cheap enough to run at the
	 * start of every analyzer run, which is exactly where it belongs: a silently broken codec would not fail
	 * anything, it would just make the memory table describe an index nobody is proposing.
	 *
	 * @throws IllegalStateException when any invariant is violated
	 */
	public static void selfCheck() {
		// packing round-trips across the whole code-point range, including the highest supplementary plane
		final long extreme = pack(0, 0x10FFFF, 0x10FFFF);
		assertState(extreme >= 0L, "packed trigram must never be negative");
		assertState(codePointAt(extreme, 0) == 0, "code point 0 must round-trip");
		assertState(codePointAt(extreme, 1) == 0x10FFFF, "maximal code point must round-trip at position 1");
		assertState(codePointAt(extreme, 2) == 0x10FFFF, "maximal code point must round-trip at position 2");

		// repeated trigrams inside one value contribute a single membership
		final long[] repeated = extractUniqueTrigrams("aaaaa");
		assertState(repeated.length == 1, "`aaaaa` must contribute exactly one distinct trigram");
		assertState(repeated[0] == pack('a', 'a', 'a'), "`aaaaa`'s single trigram must be `aaa`");

		// distinct trigrams come back sorted and complete
		final long[] abcd = extractUniqueTrigrams("abcd");
		assertState(abcd.length == 2, "`abcd` must contribute two trigrams");
		assertState(abcd[0] == pack('a', 'b', 'c') && abcd[1] == pack('b', 'c', 'd'), "`abcd` trigrams wrong");

		// the short-value boundary: nothing below three code points is indexable, and exactly three are enough
		assertState(extractUniqueTrigrams("").length == 0, "empty value must contribute nothing");
		assertState(extractUniqueTrigrams("a").length == 0, "one-code-point value must contribute nothing");
		assertState(extractUniqueTrigrams("ab").length == 0, "two-code-point value must contribute nothing");
		assertState(extractUniqueTrigrams("abc").length == 1, "three-code-point value must contribute one trigram");

		// the boundary is counted in CODE POINTS and not in UTF-16 units: two supplementary code points occupy
		// four chars, so they walk straight past the cheap `length()` pre-test and must still be rejected by the
		// code-point count - getting this wrong would index a trigram made of surrogate halves
		final String twoSupplementary = "😀😀";
		assertState(twoSupplementary.length() == 4, "two-emoji fixture must be four UTF-16 units long");
		assertState(codePointCount(twoSupplementary) == 2, "two-emoji fixture must be two code points long");
		assertState(
			extractUniqueTrigrams(twoSupplementary).length == 0,
			"a two-code-point value must contribute nothing however many UTF-16 units it occupies"
		);

		// surrogate pairs count as ONE position - the emoji below is a single supplementary code point, so
		// "a" + emoji + "b" is a three-code-point value carrying exactly one trigram whose middle slot holds
		// the whole code point rather than a lone surrogate half
		final String supplementary = "a😀b";
		assertState(supplementary.length() == 4, "test fixture must be four UTF-16 units long");
		final long[] withEmoji = extractUniqueTrigrams(supplementary);
		assertState(withEmoji.length == 1, "a value of three code points must contribute one trigram");
		assertState(
			withEmoji[0] == pack('a', supplementary.codePointAt(1), 'b'),
			"the supplementary code point must occupy one whole trigram slot"
		);

		// NFD decomposition is visible to extraction: the precomposed `é` becomes two code points, so the
		// normalized value is longer than the raw one and yields more trigrams. The counts are asserted exactly,
		// because the inflation factor is the thing the brief accepts deliberately (§19) and a codec that stopped
		// inflating would silently describe a different index than every measured number does
		final String precomposed = "café";
		final String decomposed = Normalizer.normalize(precomposed, Normalizer.Form.NFD);
		assertState(decomposed.length() == 5, "NFD must decompose the accented character");
		assertState(extractUniqueTrigrams(precomposed).length == 2, "precomposed `café` must carry two trigrams");
		final long[] decomposedTrigrams = extractUniqueTrigrams(decomposed);
		assertState(decomposedTrigrams.length == 3, "NFD `café` must carry three trigrams");
		assertState(
			Arrays.binarySearch(decomposedTrigrams, pack('f', 'e', COMBINING_ACUTE_ACCENT)) >= 0,
			"one NFD trigram must straddle the grapheme boundary between `f` and the decomposed `é`"
		);

		// combining marks can lift a value across the indexable boundary rather than merely inflating it: `éé` is
		// two code points precomposed - below the boundary, contributing nothing - and four in NFD, contributing
		// exactly two trigrams, both of which contain a combining mark
		final String twoAccented = "éé";
		final String twoAccentedNfd = Normalizer.normalize(twoAccented, Normalizer.Form.NFD);
		assertState(codePointCount(twoAccented) == 2, "precomposed `éé` must be two code points long");
		assertState(codePointCount(twoAccentedNfd) == 4, "NFD `éé` must be four code points long");
		assertState(extractUniqueTrigrams(twoAccented).length == 0, "precomposed `éé` is below the boundary");
		final long[] accentTrigrams = extractUniqueTrigrams(twoAccentedNfd);
		assertState(accentTrigrams.length == 2, "NFD `éé` must contribute exactly two trigrams");
		assertState(
			Arrays.binarySearch(accentTrigrams, pack('e', COMBINING_ACUTE_ACCENT, 'e')) >= 0 &&
				Arrays.binarySearch(accentTrigrams, pack(COMBINING_ACUTE_ACCENT, 'e', COMBINING_ACUTE_ACCENT)) >= 0,
			"NFD `éé`'s two trigrams must be the two windows over `e` + acute + `e` + acute"
		);

		// a whole trigram can live inside a single grapheme cluster: one base character carrying two stacked
		// combining marks is one visible character, three code points and exactly one trigram - and canonical
		// ordering fixes the marks' order (cedilla, combining class 202, before acute, 230) whichever order the
		// source text used, so extraction over NFD is stable against that variation
		final String stacked = Normalizer.normalize(
			"a" + COMBINING_ACUTE_ACCENT_TEXT + COMBINING_CEDILLA_TEXT, Normalizer.Form.NFD
		);
		assertState(codePointCount(stacked) == 3, "base plus two combining marks must be three code points");
		assertState(
			stacked.equals("a" + COMBINING_CEDILLA_TEXT + COMBINING_ACUTE_ACCENT_TEXT),
			"canonical ordering must sort the combining marks by their combining class"
		);
		final long[] stackedTrigrams = extractUniqueTrigrams(stacked);
		assertState(stackedTrigrams.length == 1, "one grapheme cluster of three code points is exactly one trigram");
		assertState(
			stackedTrigrams[0] == pack('a', COMBINING_CEDILLA, COMBINING_ACUTE_ACCENT),
			"the single trigram must span the whole grapheme cluster in canonical order"
		);

		// the folded form is what issue #545 would store, and it must stay in NFD
		final String folded = foldCase(Normalizer.normalize("CAFÉ", Normalizer.Form.NFD), Locale.ROOT);
		assertState(
			folded.equals(decomposed),
			"case folding must reach the same canonical form as the lower-case input"
		);
	}

	/**
	 * Throws when an invariant of {@link #selfCheck()} does not hold.
	 *
	 * @param condition the invariant
	 * @param message   what was expected
	 */
	private static void assertState(boolean condition, @Nonnull String message) {
		if (!condition) {
			throw new IllegalStateException("SpikeTrigramCodec self-check failed: " + message);
		}
	}
}
