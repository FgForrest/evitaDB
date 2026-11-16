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

import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Region probes, escaped probes, colliding keys, capacity and copying of the open-addressed table behind both routers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
@DisplayName("Region routing table")
class RegionRoutingTableTest {

	/**
	 * Lookups by a region of a longer string.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("region lookup")
	class RegionLookup {

		/** An empty table answers every region with null. */
		@Test
		@DisplayName("empty table finds nothing")
		void shouldReturnNullWhenTableIsEmpty() {
			final RegionRoutingTable<String> table = new RegionRoutingTable<>(Map.of());

			assertNull(table.get("/anything", 0, 9));
			assertNull(table.get("/anything", 1, 0));
		}

		/** A key matches only a region of exactly its length and content, wherever the region starts. */
		@Test
		@DisplayName("matches a key only on its exact region")
		void shouldMatchKeyOnlyOnExactRegion() {
			final RegionRoutingTable<String> table = new RegionRoutingTable<>(Map.of("foobar", "value"));

			assertEquals("value", table.get("/x/foobar/y", 3, 6));
			assertNull(table.get("/x/foobar/y", 3, 3));
			assertNull(table.get("/x/foobar/y", 3, 7));
			assertNull(table.get("/x/foobaz/y", 3, 6));
		}

		/** The empty key is what endpoint tries store for empty segments, so a zero-length region finds it. */
		@Test
		@DisplayName("finds the empty key at any start")
		void shouldFindEmptyKeyAtAnyStart() {
			final RegionRoutingTable<String> table = new RegionRoutingTable<>(Map.of("", "empty", "a", "letter"));

			for (int start = 0; start <= 3; start++) {
				assertEquals("empty", table.get("abc", start, 0), "start " + start);
			}
			assertEquals("letter", table.get("abc", 0, 1));
			assertNull(table.get("abc", 1, 1));
		}
	}

	/**
	 * Probe chains and the guaranteed free slot that ends every miss.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("collisions and capacity")
	class CollisionsAndCapacity {

		/** Keys sharing one `String#hashCode` share one probe chain; each still resolves to its own value. */
		@Test
		@DisplayName("resolves keys that share one hash code")
		void shouldResolveKeysSharingOneHashCode() {
			final String[] fourKeys = {"AaAa", "AaBB", "BBAa", "BBBB"};
			final String[] sixteenKeys = new String[16];
			for (int bits = 0; bits < sixteenKeys.length; bits++) {
				final StringBuilder key = new StringBuilder(8);
				for (int block = 0; block < 4; block++) {
					key.append((bits & (1 << block)) == 0 ? "Aa" : "BB");
				}
				sixteenKeys[bits] = key.toString();
			}

			for (final String[] keys : new String[][]{fourKeys, sixteenKeys}) {
				final Map<String, String> entries = CollectionUtils.createHashMap(keys.length);
				for (final String key : keys) {
					assertEquals(keys[0].hashCode(), key.hashCode(), "fixture keys must collide: " + key);
					entries.put(key, "value of " + key);
				}
				final RegionRoutingTable<String> table = new RegionRoutingTable<>(entries);

				for (final String key : keys) {
					assertEquals("value of " + key, table.get("/" + key + "/", 1, key.length()));
				}
			}
			// `C#` collides with `Aa`, so this unregistered key walks the whole chain before reaching a free slot
			assertEquals("AaAa".hashCode(), "AaC#".hashCode());
			final Map<String, String> entries = CollectionUtils.createHashMap(fourKeys.length);
			for (final String key : fourKeys) {
				entries.put(key, key);
			}
			assertNull(new RegionRoutingTable<>(entries).get("AaC#", 0, 4));
		}

		/** Every table size finds all of its keys, and a miss on a table at its maximum load still terminates. */
		@Test
		@DisplayName("finds every key and terminates every miss for sizes 1 to 33")
		void shouldFindEveryKeyAndTerminateMissesForEverySize() {
			// a capacity without a free slot would make a miss probe forever; the timeout turns that into a failure
			assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
				for (int size = 1; size <= 33; size++) {
					final Map<String, String> entries = CollectionUtils.createHashMap(size);
					for (int i = 0; i < size; i++) {
						entries.put("k" + i, "v" + i);
					}
					final RegionRoutingTable<String> table = new RegionRoutingTable<>(entries);

					for (final Map.Entry<String, String> entry : entries.entrySet()) {
						assertEquals(entry.getValue(), table.get(entry.getKey(), 0, entry.getKey().length()));
					}
					final String absent = "k" + size;
					assertNull(table.get(absent, 0, absent.length()), "size " + size);
					assertNull(table.get("absent", 0, 6), "size " + size);
				}
			});
		}
	}

	/**
	 * Probes by escaped request text, which the table compares with its keys character by decoded character.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("escaped lookup")
	class EscapedLookup {
		/**
		 * Keys long enough for the hash spread to matter, covering every UTF-8 width; `Aa…` and `BB…` share a hash
		 * code and so a probe chain, which the lookup must resolve by content. No key is a prefix of another.
		 */
		private static final String[] KEYS = {
			"plain-ascii-key", "žluťoučký-kůň", "price-in-€-only", "emoji-😀-inside", "😀😀-leading-pair",
			"Aa-collision", "BB-collision"
		};

		/** Every key is found by its raw, upper-case, lower-case and partly escaped spelling at an offset. */
		@Test
		@DisplayName("finds every key through any escaped spelling inside a longer path")
		void shouldFindEveryKeyThroughItsEscapedSpelling() {
			final Map<String, String> entries = entries();
			final RegionRoutingTable<String> table = new RegionRoutingTable<>(entries);

			for (final String key : KEYS) {
				final int firstLength = Character.charCount(key.codePointAt(0));
				final String[] spellings = {
					key, escape(key, true), escape(key, false),
					escape(key.substring(0, firstLength), true) + key.substring(firstLength)
				};
				for (final String spelling : spellings) {
					final String path = "/api/" + spelling + "/tail";
					assertSame(entries.get(key), table.getEscaped(path, 5, 5 + spelling.length()), spelling);
				}
				assertSame(table.get(key, 0, key.length()), table.getEscaped(key, 0, key.length()), key);
			}
		}

		/** Maps every key to its own value object. */
		@Nonnull
		private static Map<String, String> entries() {
			final Map<String, String> entries = CollectionUtils.createHashMap(KEYS.length);
			for (final String key : KEYS) {
				entries.put(key, "value of " + key);
			}
			return entries;
		}

		/** Percent-encodes every UTF-8 byte of the text with hexadecimal digits of the requested case. */
		@Nonnull
		private static String escape(@Nonnull String text, boolean upperCase) {
			final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
			final StringBuilder escaped = new StringBuilder(bytes.length * 3);
			for (final byte value : bytes) {
				escaped.append('%').append(String.format(upperCase ? "%02X" : "%02x", value & 0xff));
			}
			return escaped.toString();
		}
	}

	/**
	 * Copying the bindings out for a writer.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("copy")
	class Copy {

		/** A copy holds every entry, including the empty key, and every value keeps its identity. */
		@Test
		@DisplayName("copies every entry and keeps value identity")
		void shouldCopyEveryEntryKeepingValueIdentity() {
			final Map<String, Object> entries = Map.of("a", new Object(), "b", new Object(), "", new Object());
			final RegionRoutingTable<Object> table = new RegionRoutingTable<>(entries);
			final Map<String, Object> copy = CollectionUtils.createHashMap(entries.size());

			table.copyTo(copy);

			assertEquals(entries, copy);
			for (final Map.Entry<String, Object> entry : entries.entrySet()) {
				assertSame(entry.getValue(), copy.get(entry.getKey()));
			}
		}
	}
}
