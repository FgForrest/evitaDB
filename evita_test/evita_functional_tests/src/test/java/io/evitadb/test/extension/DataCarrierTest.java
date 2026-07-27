/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.test.extension;

import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.extension.DataCarrier.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the dual-index storage contract of {@link DataCarrier}: values are addressable both by
 * their declared name and by their runtime type, type lookup falls back to an assignable supertype
 * when the exact class is absent, anonymous (unnamed) access is mutually exclusive with named
 * storage, and each construction flavour (varargs, tuples, entry set, positional name/value pairs)
 * populates both indexes consistently.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(CONTRACT)
@Tag(DATA_TYPE)
@DisplayName("DataCarrier named/typed storage contract")
class DataCarrierTest {

	@Nested
	@Tag(CONTRACT)
	@Tag(DATA_TYPE)
	@DisplayName("Named and typed retrieval")
	class Retrieval {

		@Test
		@DisplayName("stores and retrieves a value by its name")
		void shouldStoreAndRetrieveByName() {
			final DataCarrier carrier = new DataCarrier("greeting", "hello");

			assertEquals("hello", carrier.getValueByName("greeting"));
		}

		@Test
		@DisplayName("stores and retrieves a value by its exact runtime type")
		void shouldStoreAndRetrieveByExactType() {
			final DataCarrier carrier = new DataCarrier("greeting", "hello");

			assertEquals("hello", carrier.getValueByType(String.class));
		}

		@Test
		@DisplayName("retrieves a value by a supertype when the exact type is absent")
		void shouldRetrieveBySupertypeWhenExactTypeAbsent() {
			final ArrayList<String> stored = new ArrayList<>(List.of("a", "b"));
			final DataCarrier carrier = new DataCarrier("items", stored);

			// stored under ArrayList.class; List.class lookup must resolve via assignable-from fallback
			assertSame(stored, carrier.getValueByType(List.class));
		}

		@Test
		@DisplayName("returns null when no value is stored under the requested name")
		void shouldReturnNullWhenNameAbsent() {
			final DataCarrier carrier = new DataCarrier("greeting", "hello");

			assertNull(carrier.getValueByName("missing"));
		}
	}

	@Nested
	@Tag(CONTRACT)
	@Tag(DATA_TYPE)
	@DisplayName("Mutation and anonymous access")
	class MutationAndAnonymousAccess {

		@Test
		@DisplayName("rejects putting a second value under an already-used name")
		void shouldRejectDuplicateNameOnPut() {
			final DataCarrier carrier = new DataCarrier("greeting", "hello");

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> carrier.put("greeting", "other")
			);
			assertTrue(error.getMessage().contains("greeting"));
		}

		@Test
		@DisplayName("exposes named entries through the entry set")
		void shouldExposeNamedEntriesViaEntrySet() {
			final DataCarrier carrier = new DataCarrier("greeting", "hello", "count", 3);

			final Map<String, Object> asMap = new LinkedHashMap<>();
			carrier.entrySet().forEach(entry -> asMap.put(entry.getKey(), entry.getValue()));

			assertEquals(2, asMap.size());
			assertEquals("hello", asMap.get("greeting"));
			assertEquals(3, asMap.get("count"));
		}

		@Test
		@DisplayName("returns typed values as anonymous values when no named values exist")
		void shouldReturnTypedValuesAsAnonymousWhenNoNamedValues() {
			// a non-String first argument forces the Object... constructor (no names stored)
			final DataCarrier carrier = new DataCarrier(42, "hello");

			final Collection<Object> anonymous = carrier.anonymousValues();

			assertEquals(2, anonymous.size());
			assertTrue(anonymous.contains("hello"));
			assertTrue(anonymous.contains(42));
		}

		@Test
		@DisplayName("returns an empty anonymous collection when named values are present")
		void shouldReturnEmptyAnonymousWhenNamedValuesPresent() {
			final DataCarrier carrier = new DataCarrier("greeting", "hello");

			assertTrue(carrier.anonymousValues().isEmpty());
		}
	}

	@Nested
	@Tag(CONTRACT)
	@Tag(DATA_TYPE)
	@DisplayName("Construction flavours")
	class Construction {

		@Test
		@DisplayName("indexes varargs-constructed values by their runtime type")
		void shouldIndexVarargsConstructorByType() {
			// a non-String first argument forces the Object... constructor (type index only)
			final DataCarrier carrier = new DataCarrier(42, "hello");

			assertEquals("hello", carrier.getValueByType(String.class));
			assertEquals(42, carrier.getValueByType(Integer.class));
		}

		@Test
		@DisplayName("builds from a varargs array of name/value tuples")
		void shouldBuildFromTupleVarargs() {
			final DataCarrier carrier = new DataCarrier(
				tuple("greeting", "hello"), tuple("count", 42)
			);

			assertEquals("hello", carrier.getValueByName("greeting"));
			assertEquals(42, carrier.getValueByName("count"));
			assertEquals("hello", carrier.getValueByType(String.class));
			assertEquals(42, carrier.getValueByType(Integer.class));
		}

		@Test
		@DisplayName("builds from an existing set of name/value entries")
		void shouldBuildFromEntrySet() {
			final DataCarrier source = new DataCarrier(
				tuple("greeting", "hello"), tuple("count", 42)
			);

			final DataCarrier carrier = new DataCarrier(source.entrySet());

			assertEquals("hello", carrier.getValueByName("greeting"));
			assertEquals(42, carrier.getValueByName("count"));
			assertEquals("hello", carrier.getValueByType(String.class));
		}

		@Test
		@DisplayName("builds from four positional name/value pairs")
		void shouldBuildFromFourNameValuePairs() {
			final DataCarrier carrier = new DataCarrier(
				"a", "first",
				"b", 2,
				"c", 3L,
				"d", true
			);

			assertEquals("first", carrier.getValueByName("a"));
			assertEquals(2, carrier.getValueByName("b"));
			assertEquals(3L, carrier.getValueByName("c"));
			assertEquals(true, carrier.getValueByName("d"));
		}
	}
}
