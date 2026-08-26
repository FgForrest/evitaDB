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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.text.Normalizer;
import java.util.Arrays;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the trigram key codec: that a key round-trips through its 63-bit packing whatever code points it holds,
 * that extraction counts positions in Unicode code points rather than UTF-16 units, and that a value contributes
 * each of its trigrams exactly once.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("Trigram key codec")
class TrigramCodecTest {

	/**
	 * `COMBINING ACUTE ACCENT` — the mark NFD splits off an `é`, canonical combining class 230.
	 */
	private static final int COMBINING_ACUTE_ACCENT = 0x0301;

	/**
	 * `COMBINING CEDILLA`, canonical combining class 202 — lower than the acute accent's, so canonical ordering
	 * places it first when both sit on the same base character.
	 */
	private static final int COMBINING_CEDILLA = 0x0327;

	/**
	 * Normalizes to the form the shared value tree stores a `String` attribute in, so the fixtures describe the same
	 * text the index really sees.
	 *
	 * @param value the raw text
	 * @return the NFD form
	 */
	@Nonnull
	private static String nfd(@Nonnull String value) {
		return Normalizer.normalize(value, Normalizer.Form.NFD);
	}

	@Nested
	@DisplayName("packing three code points into one long")
	class Packing {

		@Test
		@DisplayName("a packed trigram is never negative, whatever code points it holds")
		void shouldNeverPackToANegativeKey() {
			// the highest code point in all three slots is the worst case for the sign bit: if the third one's high
			// bit could spill into it, two different trigrams would pack to the same key
			assertTrue(TrigramCodec.pack(0x10FFFF, 0x10FFFF, 0x10FFFF) >= 0L);
			assertTrue(TrigramCodec.pack(0, 0, 0) >= 0L);
			assertTrue(TrigramCodec.pack('a', 'b', 'c') >= 0L);
		}

		@Test
		@DisplayName("every slot round-trips, including the maximal code point")
		void shouldRoundTripEverySlot() {
			final long key = TrigramCodec.pack(0, 0x10FFFF, 0x1F600);
			assertEquals(0, TrigramCodec.codePointAt(key, 0));
			assertEquals(0x10FFFF, TrigramCodec.codePointAt(key, 1));
			assertEquals(0x1F600, TrigramCodec.codePointAt(key, 2));
		}

		@Test
		@DisplayName("distinct trigrams pack to distinct keys")
		void shouldPackDistinctTrigramsToDistinctKeys() {
			assertEquals(3, Arrays.stream(new long[]{
				TrigramCodec.pack('a', 'b', 'c'),
				TrigramCodec.pack('b', 'c', 'a'),
				TrigramCodec.pack('c', 'a', 'b')
			}).distinct().count());
		}

		@Test
		@DisplayName("a key renders back into the three characters it encodes")
		void shouldRenderKeyBackIntoText() {
			assertEquals("abc", TrigramCodec.toDisplayString(TrigramCodec.pack('a', 'b', 'c')));
		}

		@Test
		@DisplayName("a key of three supplementary code points renders back as six chars")
		void shouldRenderASupplementaryTrigramBackIntoText() {
			// the rendering is what names a trigram in the divergence refusals an operator reads, and a trigram can
			// hold three surrogate pairs - which is why the builder is sized at six rather than three
			final String text = "😀😁😂";
			final long trigram = TrigramCodec.pack(
				text.codePointAt(0), text.codePointAt(2), text.codePointAt(4));
			assertEquals(text, TrigramCodec.toDisplayString(trigram));
			assertEquals(6, TrigramCodec.toDisplayString(trigram).length());
		}

		@Test
		@DisplayName("an argument that is not a code point is refused in every slot")
		void shouldRefuseAnArgumentThatIsNotACodePoint() {
			// silently truncating would be the dangerous outcome: the key would name a DIFFERENT, perfectly valid
			// trigram, so the corruption would surface as an index/tree divergence far from the write that caused it -
			// and a key holding a non-code-point makes toDisplayString throw while rendering the very refusal that
			// reports the divergence
			final int[] notCodePoints = {-1, Integer.MIN_VALUE, Character.MAX_CODE_POINT + 1, 0x200000};
			for (int slot = 0; slot < 3; slot++) {
				for (final int notACodePoint : notCodePoints) {
					final int[] arguments = {'a', 'b', 'c'};
					arguments[slot] = notACodePoint;
					final GenericEvitaInternalError error = assertThrows(
						GenericEvitaInternalError.class,
						() -> TrigramCodec.pack(arguments[0], arguments[1], arguments[2]),
						"slot " + slot + " accepted " + notACodePoint
					);
					assertTrue(
						error.getPrivateMessage().contains("is not a Unicode code point"),
						"the refusal must say what is wrong with the argument, but was: " + error.getPrivateMessage()
					);
				}
			}
			// the boundaries themselves are valid and must keep packing
			assertTrue(TrigramCodec.pack(0, Character.MAX_CODE_POINT, 0) >= 0L);
			// an unpaired surrogate is a legal char of a Java String, so it is deliberately packable rather than
			// refused - refusing it would fail the write path on a value the exact predicate matches happily
			assertEquals("a\uD800c", TrigramCodec.toDisplayString(TrigramCodec.pack('a', 0xD800, 'c')));
		}

	}

	@Nested
	@DisplayName("extracting the trigrams of a value")
	class Extraction {

		@Test
		@DisplayName("a repeated trigram contributes exactly one membership")
		void shouldContributeARepeatedTrigramOnce() {
			final long[] trigrams = TrigramCodec.extractUniqueTrigrams("aaaaa");
			assertArrayEquals(new long[]{TrigramCodec.pack('a', 'a', 'a')}, trigrams);
		}

		@Test
		@DisplayName("every window of the value is extracted, ascending")
		void shouldExtractEveryWindowAscending() {
			final long[] trigrams = TrigramCodec.extractUniqueTrigrams("abcd");
			assertArrayEquals(
				new long[]{TrigramCodec.pack('a', 'b', 'c'), TrigramCodec.pack('b', 'c', 'd')},
				trigrams
			);
		}

		@Test
		@DisplayName("a value below three code points contributes nothing")
		void shouldContributeNothingBelowTheIndexableLength() {
			assertEquals(0, TrigramCodec.extractUniqueTrigrams("").length);
			assertEquals(0, TrigramCodec.extractUniqueTrigrams("a").length);
			assertEquals(0, TrigramCodec.extractUniqueTrigrams("ab").length);
			assertEquals(1, TrigramCodec.extractUniqueTrigrams("abc").length);
		}

		@Test
		@DisplayName("the boundary counts code points, not UTF-16 units")
		void shouldCountTheBoundaryInCodePoints() {
			// two emoji occupy four UTF-16 units, so they walk straight past a naive length() pre-test - and must
			// still be rejected, because a trigram built out of surrogate halves keys nothing a query can ever hit
			final String twoEmoji = "😀😀";
			assertEquals(4, twoEmoji.length());
			assertEquals(2, twoEmoji.codePointCount(0, twoEmoji.length()));
			assertEquals(0, TrigramCodec.extractUniqueTrigrams(twoEmoji).length);
		}

		@Test
		@DisplayName("a supplementary code point occupies one whole slot")
		void shouldTreatASurrogatePairAsOnePosition() {
			final String value = "a😀b";
			assertEquals(4, value.length());
			final long[] trigrams = TrigramCodec.extractUniqueTrigrams(value);
			assertArrayEquals(new long[]{TrigramCodec.pack('a', value.codePointAt(1), 'b')}, trigrams);
		}

		@Test
		@DisplayName("decomposition is visible: an accented value carries more trigrams than its precomposed form")
		void shouldSeeNfdDecomposition() {
			// the shared value tree stores the NFD form, so this is the text extraction really sees; the accent
			// inflation is accepted deliberately and a codec that stopped inflating would index different keys than
			// the query path derives from a normalized pattern
			assertEquals(2, TrigramCodec.extractUniqueTrigrams("café").length);
			final long[] decomposed = TrigramCodec.extractUniqueTrigrams(nfd("café"));
			assertEquals(3, decomposed.length);
			assertTrue(
				Arrays.binarySearch(decomposed, TrigramCodec.pack('f', 'e', COMBINING_ACUTE_ACCENT)) >= 0,
				"one trigram must straddle the boundary between `f` and the decomposed `é`"
			);
		}

		@Test
		@DisplayName("combining marks can lift a value across the indexable boundary")
		void shouldLiftAnAccentedValueAcrossTheBoundary() {
			// `éé` is two code points precomposed - below the boundary - and four in NFD
			assertEquals(0, TrigramCodec.extractUniqueTrigrams("éé").length);
			final long[] trigrams = TrigramCodec.extractUniqueTrigrams(nfd("éé"));
			assertArrayEquals(
				new long[]{
					TrigramCodec.pack('e', COMBINING_ACUTE_ACCENT, 'e'),
					TrigramCodec.pack(COMBINING_ACUTE_ACCENT, 'e', COMBINING_ACUTE_ACCENT)
				},
				sorted(trigrams)
			);
		}

		@Test
		@DisplayName("canonical ordering makes stacked marks stable")
		void shouldExtractStackedCombiningMarksInCanonicalOrder() {
			// one base character carrying two stacked marks is one visible character and exactly one trigram;
			// canonical ordering sorts the marks by combining class whichever order the source text used
			final String stacked = nfd("a" + (char) COMBINING_ACUTE_ACCENT + (char) COMBINING_CEDILLA);
			assertEquals(3, stacked.codePointCount(0, stacked.length()));
			assertArrayEquals(
				new long[]{TrigramCodec.pack('a', COMBINING_CEDILLA, COMBINING_ACUTE_ACCENT)},
				TrigramCodec.extractUniqueTrigrams(stacked)
			);
		}

		@Test
		@DisplayName("three supplementary code points are exactly one trigram")
		void shouldExtractOneTrigramFromThreeSupplementaryCodePoints() {
			// the complement of the two-emoji rejection: six `char`s and three code points is the SHORTEST indexable
			// supplementary value there is, and the only fixture that exercises the code-point sizing of the result
			// array together with the surrogate-pair stride over the same value
			final String value = "😀😁😂";
			assertEquals(6, value.length());
			assertEquals(3, value.codePointCount(0, value.length()));

			final long[] trigrams = TrigramCodec.extractUniqueTrigrams(value);

			assertEquals(1, trigrams.length);
			assertEquals(value.codePointAt(0), TrigramCodec.codePointAt(trigrams[0], 0));
			assertEquals(value.codePointAt(2), TrigramCodec.codePointAt(trigrams[0], 1));
			assertEquals(value.codePointAt(4), TrigramCodec.codePointAt(trigrams[0], 2));
		}

		@Test
		@DisplayName("the value overload delegates a String to the extraction the write path uses")
		void shouldExtractThroughTheValueOverloadForAString() {
			// only the refusal arm of this overload is covered elsewhere, yet the positive one is what every write to
			// a capability-declaring attribute actually calls
			assertArrayEquals(
				TrigramCodec.extractUniqueTrigrams("abcd"),
				TrigramCodec.extractUniqueTrigramsOfValue("abcd")
			);
		}

		@Test
		@DisplayName("an unpaired surrogate is indexed rather than rejected or dropped")
		void shouldTolerateAnUnpairedSurrogate() {
			// a stored `String` may legally hold a lone surrogate, and this path's contract is to never normalize and
			// never reject - the surrogate is its own code point, packs into 21 bits without colliding, and must not
			// cost the value the position it occupies
			final String value = "a\uD83Db";
			assertEquals(3, value.codePointCount(0, value.length()));

			final long[] trigrams = TrigramCodec.extractUniqueTrigrams(value);

			assertArrayEquals(new long[]{TrigramCodec.pack('a', 0xD83D, 'b')}, trigrams);
		}

		@Test
		@DisplayName("a non-String value is refused rather than indexed through toString")
		void shouldRefuseANonStringValue() {
			// the capability is accepted by the schema on String / String[] attributes only, so anything else here
			// means the index is being maintained for an attribute it was never allowed on
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> TrigramCodec.extractUniqueTrigramsOfValue(42)
			);
			assertTrue(
				error.getPrivateMessage().contains("java.lang.Integer"),
				"the refusal must name the offending type, but was: " + error.getPrivateMessage()
			);
		}

		/**
		 * @param trigrams the keys to order
		 * @return the same keys, ascending
		 */
		@Nonnull
		private long[] sorted(@Nonnull long[] trigrams) {
			final long[] copy = trigrams.clone();
			Arrays.sort(copy);
			return copy;
		}

	}

}
