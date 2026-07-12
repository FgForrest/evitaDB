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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.Locale;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.builder.JsonArrayBuilder.jsonArray;
import static io.evitadb.test.builder.JsonObjectBuilder.jsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the behaviour of {@link JsonObjectBuilder} — null-safe stringification of {@link Locale}
 * and {@link Currency}, and nesting of child object and array builders.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(EXTERNAL_API)
@Tag(SERIALIZATION)
@DisplayName("JsonObjectBuilder JSON object assembly")
class JsonObjectBuilderTest {

	private static final Locale LOCALE_CS_CZ = Locale.forLanguageTag("cs-CZ");
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");

	@Nested
	@DisplayName("Ordering and scalar values")
	class ScalarValues {

		@Test
		@DisplayName("Should stringify a non-null Currency and Locale via toString")
		void shouldStringifyCurrencyAndLocale() {
			final ObjectNode result = jsonObject()
				.e("currency", CURRENCY_CZK)
				.e("locale", LOCALE_CS_CZ)
				.build();

			assertTrue(result.get("currency").isTextual());
			assertEquals("CZK", result.get("currency").asText());
			assertTrue(result.get("locale").isTextual());
			assertEquals(LOCALE_CS_CZ.toString(), result.get("locale").asText());
			assertEquals("cs_CZ", result.get("locale").asText());
		}

		@Test
		@DisplayName("Should emit a JSON null node for a null Currency and Locale instead of failing")
		void shouldEmitNullNodeForNullCurrencyAndLocale() {
			final ObjectNode result = jsonObject()
				.e("currency", (Currency) null)
				.e("locale", (Locale) null)
				.build();

			assertTrue(result.get("currency").isNull());
			assertTrue(result.get("locale").isNull());
		}
	}

	@Nested
	@DisplayName("Nesting raw nodes and child builders")
	class Nesting {

		@Test
		@DisplayName("Should nest an object built by a child JsonObjectBuilder")
		void shouldNestObjectBuilder() {
			final ObjectNode result = jsonObject()
				.e("child", jsonObject().e("inner", "value"))
				.build();

			assertTrue(result.get("child").isObject());
			assertEquals("value", result.get("child").get("inner").asText());
		}

		@Test
		@DisplayName("Should nest an array built by a child JsonArrayBuilder")
		void shouldNestArrayBuilder() {
			final ObjectNode result = jsonObject()
				.e("items", jsonArray().add(1).add(2))
				.build();

			assertTrue(result.get("items").isArray());
			assertEquals(2, result.get("items").size());
			assertEquals(1, result.get("items").get(0).intValue());
			assertEquals(2, result.get("items").get(1).intValue());
		}
	}
}
