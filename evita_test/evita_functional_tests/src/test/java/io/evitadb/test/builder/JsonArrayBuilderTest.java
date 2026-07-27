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

package io.evitadb.test.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.builder.JsonArrayBuilder.jsonArray;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the behaviour of {@link JsonArrayBuilder} — the scalar overload dispatch to the correct
 * Jackson node types, null-safe handling of {@link Locale} and {@link Currency}, nesting and the
 * runtime-type dispatch of the list / varargs factory methods.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(EXTERNAL_API)
@Tag(SERIALIZATION)
@DisplayName("JsonArrayBuilder JSON array assembly")
class JsonArrayBuilderTest {

	private static final Locale LOCALE_CS_CZ = Locale.forLanguageTag("cs-CZ");
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");

	@Nested
	@DisplayName("Ordering and scalar values")
	class ScalarValues {

		@Test
		@DisplayName("Should stringify a non-null Locale and Currency via toString")
		void shouldStringifyLocaleAndCurrency() {
			final ArrayNode result = jsonArray()
				.add(LOCALE_CS_CZ)
				.add(CURRENCY_CZK)
				.build();

			assertEquals(2, result.size());
			assertTrue(result.get(0).isTextual());
			assertEquals(LOCALE_CS_CZ.toString(), result.get(0).asText());
			assertEquals("cs_CZ", result.get(0).asText());
			assertTrue(result.get(1).isTextual());
			assertEquals("CZK", result.get(1).asText());
		}

		@Test
		@DisplayName("Should emit a JSON null node for a null Locale and Currency instead of failing")
		void shouldEmitNullNodeForNullLocaleAndCurrency() {
			final ArrayNode result = jsonArray()
				.add((Locale) null)
				.add((Currency) null)
				.build();

			assertEquals(2, result.size());
			assertTrue(result.get(0).isNull());
			assertTrue(result.get(1).isNull());
		}
	}

	@Nested
	@DisplayName("Nesting and factory methods")
	class NestingAndFactories {

		@Test
		@DisplayName("Should append a nested object built by JsonObjectBuilder")
		void shouldAddNestedObjectBuilder() {
			final ArrayNode result = jsonArray()
				.add(JsonObjectBuilder.jsonObject().e("key", "value"))
				.build();

			assertEquals(1, result.size());
			assertTrue(result.get(0).isObject());
			assertEquals("value", result.get(0).get("key").asText());
		}

		@Test
		@DisplayName("Should build from a list dispatching each element by its runtime type")
		void shouldBuildFromListDispatchingByRuntimeType() {
			final JsonNode rawNode = JsonNodeFactory.instance.textNode("raw");
			final List<Object> items = new ArrayList<>(11);
			items.add(rawNode);
			items.add(7);
			items.add(8L);
			items.add("s");
			items.add('A');
			items.add(Boolean.TRUE);
			items.add(new BigDecimal("1.5"));
			items.add((short) 9);
			items.add((byte) 3);
			items.add(LOCALE_CS_CZ);
			items.add(CURRENCY_CZK);

			final ArrayNode result = jsonArray(items);

			assertEquals(11, result.size());
			// raw JsonNode is appended as-is
			assertTrue(result.get(0).isTextual());
			assertEquals("raw", result.get(0).asText());
			// Integer
			assertTrue(result.get(1).isInt());
			assertEquals(7, result.get(1).intValue());
			// Long
			assertTrue(result.get(2).isLong());
			assertEquals(8L, result.get(2).longValue());
			// String
			assertEquals("s", result.get(3).asText());
			// Character -> char code
			assertTrue(result.get(4).isInt());
			assertEquals('A', result.get(4).intValue());
			// Boolean
			assertTrue(result.get(5).booleanValue());
			// BigDecimal
			assertEquals(0, result.get(6).decimalValue().compareTo(new BigDecimal("1.5")));
			// Short
			assertEquals((short) 9, result.get(7).shortValue());
			// Byte widened to short
			assertEquals((short) 3, result.get(8).shortValue());
			// Locale stringified via toString
			assertEquals("cs_CZ", result.get(9).asText());
			// Currency stringified via toString
			assertEquals("CZK", result.get(10).asText());
		}

		@Test
		@DisplayName("Should build from varargs delegating to the list factory")
		void shouldBuildFromVarargs() {
			final ArrayNode result = jsonArray(1, "two", Boolean.TRUE);

			assertEquals(3, result.size());
			assertTrue(result.get(0).isInt());
			assertEquals(1, result.get(0).intValue());
			assertEquals("two", result.get(1).asText());
			assertTrue(result.get(2).booleanValue());
		}

		@Test
		@DisplayName("Should throw a generic internal error for an unsupported list item")
		void shouldThrowGenericErrorForUnsupportedListItem() {
			final List<Object> items = List.of(new Object());

			assertThrows(GenericEvitaInternalError.class, () -> jsonArray(items));
		}
	}
}
