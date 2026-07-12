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

package io.evitadb.test.client.query.graphql;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link GraphQLInputJsonPrinter} renders JSON in the relaxed GraphQL-input shape —
 * field names emitted without quotes, known enum constants and currency codes stripped of their
 * quotes, locale language tags rewritten with an underscore, and genuinely unknown strings left
 * quoted intact.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(GRAPHQL)
@Tag(SERIALIZATION)
@DisplayName("GraphQLInputJsonPrinter GraphQL-input rendering")
class GraphQLInputJsonPrinterTest {

	private final GraphQLInputJsonPrinter printer = new GraphQLInputJsonPrinter();

	@Test
	@DisplayName("Should print field names without surrounding quotes")
	void shouldPrintFieldNamesUnquoted() {
		final ObjectNode node = JsonNodeFactory.instance.objectNode();
		node.put("someField", 42);

		final String printed = printer.print(node);

		assertTrue(printed.contains("someField"));
		assertFalse(printed.contains("\"someField\""));
	}

	@Test
	@DisplayName("Should strip the quotes from known enum constant values")
	void shouldUnquoteKnownEnumValues() {
		final ObjectNode node = JsonNodeFactory.instance.objectNode();
		node.put("direction", "ASC");

		final String printed = printer.print(node);

		assertTrue(printed.contains("ASC"));
		assertFalse(printed.contains("\"ASC\""));
	}

	@Test
	@DisplayName("Should keep unknown string values wrapped in quotes")
	void shouldKeepUnknownStringsQuoted() {
		final ObjectNode node = JsonNodeFactory.instance.objectNode();
		node.put("label", "hello");

		final String printed = printer.print(node);

		assertTrue(printed.contains("\"hello\""));
	}

	@Test
	@DisplayName("Should unquote a locale tag and replace its hyphen with an underscore")
	void shouldConvertLocaleTagHyphenToUnderscore() {
		final ObjectNode node = JsonNodeFactory.instance.objectNode();
		node.put("locale", "cs-CZ");

		final String printed = printer.print(node);

		assertTrue(printed.contains("cs_CZ"));
		assertFalse(printed.contains("cs-CZ"));
		assertFalse(printed.contains("\"cs_CZ\""));
	}

	@Test
	@DisplayName("Should strip the quotes from known currency codes")
	void shouldLeaveCurrencyCodesUnquoted() {
		final ObjectNode node = JsonNodeFactory.instance.objectNode();
		node.put("currency", "CZK");

		final String printed = printer.print(node);

		assertTrue(printed.contains("CZK"));
		assertFalse(printed.contains("\"CZK\""));
	}
}
