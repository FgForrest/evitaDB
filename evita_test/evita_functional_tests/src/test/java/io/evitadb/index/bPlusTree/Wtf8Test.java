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

package io.evitadb.index.bPlusTree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static io.evitadb.index.bPlusTree.ValueColumnTestSupport.describe;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@link Wtf8} codec that {@link FrontCodedStringColumn} stores its keys with.
 *
 * Three properties carry the whole design and each has its own nested class: the encoding is **byte-identical to
 * UTF-8** for every input UTF-8 can represent (which is why the column's blob size, prefix sharing and fast-path
 * threshold do not move); it **round-trips an unpaired surrogate**, which UTF-8 cannot; and its **byte order agrees
 * with {@link String#compareTo}** over the BMP, which is what lets a lone surrogate stay on the column's
 * allocation-free byte-compare path.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("WTF-8 codec")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class Wtf8Test {

	/**
	 * Values UTF-8 represents perfectly well - the codec must not touch any of them, and must not take its by-hand
	 * path for any of them either.
	 */
	private static final String[] WELL_FORMED = {
		"",
		"a",
		"plain-ascii-identifier",
		"a?c",                                  // a GENUINE question mark - the byte the old encoder substituted
		"????",
		"caf\u00e9",                            // Latin-1 supplement, 2-byte
		"A\u0301",                              // NFD: base letter + combining acute accent
		"\u4f60\u597d",                         // CJK, 3-byte
		"\ud83d\ude00",                         // emoji - a WELL-FORMED pair, 4-byte supplementary form
		"a\ud83d\ude00c",
		"\ud840\udc0b",                         // CJK extension B - another well-formed pair
		"\ue000",                               // the first code point ABOVE the surrogate block
		"\ud7ff"                                // the last code point BELOW it - shares the 0xED lead byte
	};

	/**
	 * Every shape of unpaired surrogate the codec has to carry.
	 */
	private static final String[] UNPAIRED = {
		"\ud800",                               // lone high, alone
		"\udfff",                               // lone low, alone
		"a\ud800c",                             // lone high, embedded
		"a\udc00c",                             // lone low, embedded
		"a\ud800",                              // lone high, at the very end
		"\ud800a",                              // lone high, at the very start
		"\ud800\ud800",                         // two highs in a row - neither forms a pair
		"\udc00\ud800",                         // low then high - the wrong order, so still no pair
		"a\ud800b\udc00c",                      // one of each, interleaved
		"\ud83d\ude00\ud800",                   // a well-formed pair AND a lone surrogate in one value
		"\ud800\ud83d\ude00"
	};

	@Nested
	@DisplayName("byte-identical to UTF-8 wherever UTF-8 works")
	class Utf8Compatibility {

		@Test
		@DisplayName("every well-formed string encodes to exactly its UTF-8 bytes")
		void shouldProduceTheSameBytesAsUtf8ForWellFormedInput() {
			// This is the property that makes the codec free: the column's blob size, its prefix sharing, its restart
			// spacing and its `>= 0xF0` supplementary threshold are all defined over the encoded bytes, and none of
			// them moves for any input UTF-8 could already represent.
			for (final String value : WELL_FORMED) {
				assertArrayEquals(
					value.getBytes(StandardCharsets.UTF_8), Wtf8.encode(value),
					"WTF-8 must equal UTF-8 for " + describe(value)
				);
			}
		}

		@Test
		@DisplayName("a well-formed surrogate pair keeps the 4-byte supplementary form, not two 3-byte halves")
		void shouldEncodeAPairAsASupplementaryCharacter() {
			// The line between WTF-8 and CESU-8, and the reason the column's supplementary threshold stays exact: an
			// emoji must still start with a byte >= 0xF0, so it still falls OFF the byte-compare fast path, where
			// UTF-8 byte order and String#compareTo disagree.
			final byte[] encoded = Wtf8.encode("\ud83d\ude00");
			assertEquals(4, encoded.length, "a supplementary character must occupy 4 bytes, not 6");
			assertTrue(
				(encoded[0] & 0xFF) >= 0xF0,
				"a supplementary character's lead byte must stay above the column's BMP threshold"
			);
		}

		@Test
		@DisplayName("a genuine question mark is not mistaken for a substitution")
		void shouldNotTakeTheByHandPathForARealQuestionMark() {
			// The encoder's fast-path test looks for the substitution byte 0x3F first, which a value containing a real
			// '?' also produces - so the second stage has to settle it by looking at the code units.
			assertFalse(Wtf8.hasUnpairedSurrogate("a?c"), "a real '?' is not an unpaired surrogate");
			assertArrayEquals("a?c".getBytes(StandardCharsets.UTF_8), Wtf8.encode("a?c"));
			assertEquals("a?c", roundTrip("a?c"));
		}

	}

	@Nested
	@DisplayName("carries what UTF-8 loses")
	class UnpairedSurrogateFidelity {

		@Test
		@DisplayName("every unpaired-surrogate shape round-trips")
		void shouldRoundTripEveryUnpairedShape() {
			for (final String value : UNPAIRED) {
				assertEquals(value, roundTrip(value), "must round-trip " + describe(value));
			}
		}

		@Test
		@DisplayName("every well-formed shape round-trips too")
		void shouldRoundTripEveryWellFormedShape() {
			for (final String value : WELL_FORMED) {
				assertEquals(value, roundTrip(value), "must round-trip " + describe(value));
			}
		}

		@Test
		@DisplayName("UTF-8 loses exactly the shapes WTF-8 keeps, and no others")
		void shouldLoseUnderUtf8ExactlyWhereItIsUnpaired() {
			// Pins the boundary from both sides, so a future change that widens or narrows `hasUnpairedSurrogate`
			// cannot pass unnoticed: the predicate must agree, value for value, with whether UTF-8 actually loses it.
			//
			// What it does NOT guard, so nobody reads it as the codec's safety net: it touches only the predicate and
			// the JDK's own codec, never `encode` or `decode`, and stays green with WTF-8 reverted to plain UTF-8. The
			// round-trip cases above are what fail then.
			for (final String value : WELL_FORMED) {
				assertFalse(Wtf8.hasUnpairedSurrogate(value), describe(value) + " must not be flagged");
				assertEquals(value, utf8RoundTrip(value), "UTF-8 must already carry " + describe(value));
			}
			for (final String value : UNPAIRED) {
				assertTrue(Wtf8.hasUnpairedSurrogate(value), describe(value) + " must be flagged");
				assertNotEquals(
					value, utf8RoundTrip(value), "UTF-8 must genuinely lose " + describe(
						value) + " - otherwise this codec is unnecessary for it"
				);
			}
		}

		@Test
		@DisplayName("an encoded surrogate is detected, and an ordinary 0xED-led character is not")
		void shouldDetectAnEncodedSurrogateWithoutFalsePositives() {
			// U+D7FF and U+D800 both encode with the 0xED lead byte and differ only in the second byte, which is what
			// the detector keys on. Confusing them would send every CJK-adjacent blob down the slow decode path (or,
			// worse, mis-decode one).
			final byte[] surrogate = Wtf8.encode("\ud800");
			assertTrue(Wtf8.containsEncodedSurrogate(surrogate, 0, surrogate.length), "U+D800 must be detected");

			final byte[] justBelow = Wtf8.encode("\ud7ff");
			assertEquals(0xED, justBelow[0] & 0xFF, "U+D7FF shares the surrogate lead byte - that is the point");
			assertFalse(
				Wtf8.containsEncodedSurrogate(justBelow, 0, justBelow.length),
				"U+D7FF is an ordinary BMP character and must not be mistaken for a surrogate"
			);

			final byte[] emoji = Wtf8.encode("\ud83d\ude00");
			assertFalse(
				Wtf8.containsEncodedSurrogate(emoji, 0, emoji.length),
				"a well-formed pair is encoded as a supplementary character and holds no surrogate sequence"
			);
		}

		@Test
		@DisplayName("a surrogate between two multi-byte runs disturbs neither of them")
		void shouldDecodeASurrogateBetweenTwoMultiByteRuns() {
			// `decode` hands each surrogate-free run to the JDK whole and copies the result in at a running output
			// offset, so a value carrying a MULTI-BYTE run on BOTH sides of a surrogate is the shape where an offset
			// accumulated in the wrong unit - bytes counted where code units were meant, or the reverse - would show.
			// Every shape in `UNPAIRED` has at most one such run, where that accumulation is trivially right.
			final String[] values = {
				"\u4f60\ud800\u597d",                   // 3-byte CJK either side
				"\ud800\u4f60\ud800",                   // a multi-byte run delimited by surrogates on both sides
				"caf\u00e9\ud800caf\u00e9",             // 2-byte runs either side
				"\ud83d\ude00\ud800\ud83d\ude00"          // 4-byte supplementary runs either side
			};
			for (final String value : values) {
				assertEquals(value, roundTrip(value), "must round-trip " + describe(value));
			}
		}

		@Test
		@DisplayName("decoding honours the offset and length rather than the whole array")
		void shouldDecodeASubRange() {
			// The column decodes out of a reused scratch buffer whose tail holds stale bytes from a longer
			// predecessor, so an explicit length is load-bearing rather than cosmetic.
			final byte[] value = Wtf8.encode("a\ud800c");
			final byte[] padded = new byte[value.length + 6];
			Arrays.fill(padded, (byte) 'X');
			System.arraycopy(value, 0, padded, 3, value.length);

			assertEquals("a\ud800c", Wtf8.decode(padded, 3, value.length), "only the named range may be decoded");
		}

		@Test
		@DisplayName("detection honours the offset and length too, including a range that stops mid-sequence")
		void shouldDetectAnEncodedSurrogateWithinASubRange() {
			// The column never asks the detector about a whole array: it asks about ONE key inside a shared flat
			// buffer, at a non-zero offset and for fewer bytes than the buffer holds. An offset bug here would be
			// invisible in this class and would surface only as a mis-decoded column key.
			final byte[] surrogate = Wtf8.encode("\ud800");
			final byte[] plain = Wtf8.encode("\ud7ff");
			final byte[] buffer = new byte[plain.length + surrogate.length + plain.length];
			System.arraycopy(plain, 0, buffer, 0, plain.length);
			System.arraycopy(surrogate, 0, buffer, plain.length, surrogate.length);
			System.arraycopy(plain, 0, buffer, plain.length + surrogate.length, plain.length);

			assertTrue(
				Wtf8.containsEncodedSurrogate(buffer, plain.length, surrogate.length),
				"the range holding the surrogate must be reported"
			);
			assertFalse(
				Wtf8.containsEncodedSurrogate(buffer, 0, plain.length),
				"a range stopping short of the surrogate must not be reported"
			);
			assertFalse(
				Wtf8.containsEncodedSurrogate(buffer, plain.length + surrogate.length, plain.length),
				"a range starting after the surrogate must not be reported"
			);
			// and the property the column's whole-key scan is built on: a range ending INSIDE the sequence reports
			// nothing, because only a complete three-byte sequence counts. That is precisely why the column may not
			// scan suffixes - a front-coded suffix is exactly such a partial range
			assertFalse(
				Wtf8.containsEncodedSurrogate(buffer, 0, plain.length + 1),
				"a range ending inside the surrogate's own sequence must not report a whole one"
			);
		}

	}

	@Nested
	@DisplayName("byte order agrees with String#compareTo over the BMP")
	class Ordering {

		@Test
		@DisplayName("a lone surrogate sorts against BMP neighbours exactly as String#compareTo does")
		void shouldOrderALoneSurrogateAsStringComparisonWould() {
			// This is what lets the column keep a lone surrogate on its allocation-free byte-compare path: over the
			// code-point range 0x0000-0xFFFF, WTF-8 byte order IS code-point order IS UTF-16 code-unit order.
			final String[] values = {"a?c", "a\ud7ffc", "a\ud800c", "a\udfffc", "a\ue000c", "abc"};
			for (final String s : values) {
				for (final String value : values) {
					assertEquals(
						Integer.signum(s.compareTo(value)),
						Integer.signum(compareUnsigned(Wtf8.encode(s), Wtf8.encode(value))),
						"byte order must agree with String order for "
							+ describe(s) + " vs " + describe(value)
					);
				}
			}
		}

		@Test
		@DisplayName("a supplementary character is the ONE exception, and the column's threshold excludes it")
		void shouldExcludeSupplementaryCharactersFromTheAgreement() {
			// The agreement above holds only while every code point stays at or below U+FFFF. A well-formed PAIR is one
			// code point ABOVE it, and there UTF-8 byte order and String#compareTo famously disagree - which is exactly
			// why the column gates its byte-compare fast path on the `>= 0xF0` supplementary lead byte.
			//
			// This case is about that gate, not about the codec: substitute `0x3F` for the lone surrogate and all four
			// assertions still hold, so it stays green with WTF-8 reverted to plain UTF-8. That is deliberate - what it
			// pins is the threshold the column admits a lone surrogate through, which the codec did not introduce.
			final String lone = "\udafb";                 // a lone HIGH surrogate - one BMP code point
			final String supplementary = "\ud99e\udc59";  // a WELL-FORMED pair - one code point above the BMP

			assertTrue(
				lone.compareTo(supplementary) > 0,
				"String#compareTo compares code UNITS, and 0xDAFB > 0xD99E"
			);
			assertTrue(
				compareUnsigned(Wtf8.encode(lone), Wtf8.encode(supplementary)) < 0,
				"byte order compares code POINTS, and U+DAFB < U+66459 - the two genuinely disagree here"
			);

			// so the column must never compare this pair by bytes, and its threshold is what stops it - note the lone
			// surrogate itself does NOT trip the threshold, which is what keeps it on the fast path where it is correct
			assertTrue(
				hasSupplementaryLeadByte(Wtf8.encode(supplementary)),
				"a supplementary character must carry a byte >= 0xF0, or the column's gate could not see it"
			);
			assertFalse(
				hasSupplementaryLeadByte(Wtf8.encode(lone)),
				"a lone surrogate is a BMP code point and must stay below the threshold"
			);
		}

		@Test
		@DisplayName("randomized input orders identically wherever the column's threshold admits it")
		void shouldAgreeWithStringComparisonWhereverTheThresholdAdmits() {
			// The pairwise table above names the interesting boundaries; this covers the space between them, including
			// values that mix lone surrogates with ordinary characters at random positions. The generator deliberately
			// also emits well-formed pairs, so the admission test below is exercised from both sides rather than being
			// vacuously true - the invariant asserted is the column's own gate, not a wish about all input.
			final Random random = new Random(42);
			int admitted = 0;
			int excluded = 0;
			for (int round = 0; round < 5_000; round++) {
				final String left = randomBmpString(random);
				final String right = randomBmpString(random);
				final byte[] leftBytes = Wtf8.encode(left);
				final byte[] rightBytes = Wtf8.encode(right);
				if (hasSupplementaryLeadByte(leftBytes) || hasSupplementaryLeadByte(rightBytes)) {
					excluded++;
					continue;
				}
				admitted++;
				assertEquals(
					Integer.signum(left.compareTo(right)),
					Integer.signum(compareUnsigned(leftBytes, rightBytes)),
					"byte order must agree with String order for " + describe(left) + " vs " + describe(right)
				);
			}
			assertTrue(admitted > 1_000, "the admitted branch must carry the bulk of the rounds, not a handful");
			assertTrue(excluded > 0, "the generator must also produce supplementary characters, or the gate is untested");
		}

		/**
		 * Builds a short random string of BMP code units, drawn so that surrogates are over-represented relative to
		 * their share of the code space - the point is to exercise them, not to sample uniformly.
		 *
		 * @param random the source of randomness
		 * @return a random string of 0 to 5 BMP code units
		 */
		@Nonnull
		private static String randomBmpString(@Nonnull Random random) {
			final int length = random.nextInt(6);
			final StringBuilder result = new StringBuilder(length);
			for (int i = 0; i < length; i++) {
				result.append(
					random.nextInt(3) == 0
						? (char) (0xD800 + random.nextInt(0x0800))
						: (char) random.nextInt(0xD800)
				);
			}
			return result.toString();
		}

		/**
		 * Answers whether `bytes` holds a supplementary-character lead byte - the column's own byte-compare
		 * admission test, reproduced here so the invariant is stated in the same terms the column enforces it in.
		 *
		 * @param bytes the encoded key
		 * @return {@code true} if any byte is {@code >= 0xF0}
		 */
		private static boolean hasSupplementaryLeadByte(@Nonnull byte[] bytes) {
			for (final byte candidate : bytes) {
				if ((candidate & 0xFF) >= 0xF0) {
					return true;
				}
			}
			return false;
		}

	}

	/**
	 * Encodes and decodes `value` through the codec under test.
	 *
	 * @param value the value to round-trip
	 * @return the value as it comes back out
	 */
	@Nonnull
	private static String roundTrip(@Nonnull String value) {
		final byte[] encoded = Wtf8.encode(value);
		return Wtf8.decode(encoded, 0, encoded.length);
	}

	/**
	 * Encodes and decodes `value` through the JDK's UTF-8 codec - what the column used to do.
	 *
	 * @param value the value to round-trip
	 * @return the value as UTF-8 gives it back, which for an unpaired surrogate is a different string
	 */
	@Nonnull
	private static String utf8RoundTrip(@Nonnull String value) {
		return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
	}

	/**
	 * Compares two encoded keys the way the column's byte-compare fast path does - unsigned, lexicographic, shorter
	 * key first on a common prefix.
	 *
	 * @param left  the left operand's bytes
	 * @param right the right operand's bytes
	 * @return a negative / zero / positive value in the usual comparator sense
	 */
	private static int compareUnsigned(@Nonnull byte[] left, @Nonnull byte[] right) {
		final int shared = Math.min(left.length, right.length);
		for (int i = 0; i < shared; i++) {
			final int difference = (left[i] & 0xFF) - (right[i] & 0xFF);
			if (difference != 0) {
				return difference;
			}
		}
		return left.length - right.length;
	}

}
