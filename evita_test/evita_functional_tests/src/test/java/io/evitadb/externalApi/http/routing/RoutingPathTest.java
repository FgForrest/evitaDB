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

package io.evitadb.externalApi.http.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Request-target bounds, strict UTF-8 validation and allocation-free decoded comparison shared by both routers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
@DisplayName("Routing path")
class RoutingPathTest {

	/**
	 * The packed end and escape flag that every dispatch starts from.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("scan")
	class Scan {

		/**
		 * The end excludes the query and trailing slashes, the flag reports escapes before the query only, and a
		 * truncated or non-hexadecimal triplet anywhere before the query rejects the whole target.
		 */
		@ParameterizedTest
		@CsvSource(delimiter = '|', value = {
			"/a//|2|false", "/|1|false", "///|1|false", "/a?x=%|2|false", "/a?x=%GG|2|false",
			"/a%41|5|true", "/a%41//?x=%|5|true", "/a%4|-1|false", "/a%4?q|-1|false", "/a%|-1|false",
			"/a%?q|-1|false", "/%41/%GG|-1|false"
		})
		@DisplayName("reports the bounded end and the escape flag")
		void shouldReportEndAndEscapeFlag(@Nonnull String target, int end, boolean escaped) {
			final long expected = end < 0 ? -1 : ((long) end << 1) | (escaped ? 1 : 0);

			assertEquals(expected, RoutingPath.scan(target));
		}
	}

	/**
	 * Escaped byte sequences compared with the strict JDK decoder, the reference for what routing must accept.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("strict UTF-8 validation")
	class StrictUtf8Validation {

		/**
		 * Every one- and two-byte sequence plus three- and four-byte sequences around each range boundary, each alone
		 * and followed by a literal character: the scan accepts exactly what the JDK decodes, the capture decoder
		 * yields the same text, and the allocation-free hash and comparison agree with that text.
		 */
		@Test
		@DisplayName("accepts exactly the escaped byte sequences the strict JDK decoder accepts")
		void shouldAcceptExactlyWhatTheStrictJdkDecoderAccepts() {
			final int[] middle = {0x7f, 0x80, 0x8f, 0x90, 0x9f, 0xa0, 0xbf, 0xc0};
			final int[] last = {0x7f, 0x80, 0xbf, 0xc0};
			for (int first = 0; first <= 0xff; first++) {
				assertAgreesWithJdk(first);
				for (int second = 0; second <= 0xff; second++) {
					assertAgreesWithJdk(first, second);
				}
			}
			for (int first = 0xe0; first <= 0xf5; first++) {
				for (final int second : middle) {
					for (final int third : last) {
						assertAgreesWithJdk(first, second, third);
						for (final int fourth : last) {
							assertAgreesWithJdk(first, second, third, fourth);
						}
					}
				}
			}
		}

		/** Checks one byte sequence on its own and followed by a literal character. */
		private static void assertAgreesWithJdk(@Nonnull int... bytes) {
			final StringBuilder escaped = new StringBuilder(bytes.length * 3 + 1);
			final byte[] raw = new byte[bytes.length + 1];
			for (int i = 0; i < bytes.length; i++) {
				escaped.append('%').append(String.format("%02X", bytes[i]));
				raw[i] = (byte) bytes[i];
			}
			raw[bytes.length] = 'x';
			assertAgreesWithJdk(escaped.toString(), jdkDecode(raw, bytes.length));
			assertAgreesWithJdk(escaped + "x", jdkDecode(raw, raw.length));
		}

		/** Compares every routing view of an escaped text with the JDK's decoding of the same bytes. */
		private static void assertAgreesWithJdk(@Nonnull String text, @Nullable String expected) {
			assertEquals(expected != null, RoutingPath.scan("/" + text) >= 0, text);
			assertEquals(expected, RoutingPath.decode(text, 0, text.length(), true), text);
			if (expected != null) {
				assertEquals(expected.hashCode(), RoutingPath.decodedHash(text, 0, text.length()), text);
				assertTrue(RoutingPath.decodedEquals(text, 0, text.length(), expected), text);
			}
		}

		/** Decodes the leading bytes with the JDK's strict UTF-8 decoder, or returns null when they are malformed. */
		@Nullable
		private static String jdkDecode(@Nonnull byte[] bytes, int length) {
			try {
				return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes, 0, length))
					.toString();
			} catch (CharacterCodingException exception) {
				return null;
			}
		}
	}

	/**
	 * Allocation-free comparison of an escaped region with a stored key.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("decoded comparison")
	class DecodedComparison {

		/** A key equals the region only at exactly its decoded length, a surrogate pair included. */
		@Test
		@DisplayName("equals only a key of exactly the decoded length")
		void shouldRejectShorterAndLongerKeys() {
			// the region `a%F0%9F%98%80b` spans indexes 1 to 15 and decodes to `a😀b`
			final String path = "/a%F0%9F%98%80b/";

			assertTrue(RoutingPath.decodedEquals(path, 1, 15, "a😀b"));
			assertFalse(RoutingPath.decodedEquals(path, 1, 15, "a😀"));
			assertFalse(RoutingPath.decodedEquals(path, 1, 15, "a😀bc"));
			assertFalse(RoutingPath.decodedEquals(path, 1, 15, "a\uD83D"));
			assertFalse(RoutingPath.decodedEquals(path, 1, 15, "a😁b"));
		}
	}
}
