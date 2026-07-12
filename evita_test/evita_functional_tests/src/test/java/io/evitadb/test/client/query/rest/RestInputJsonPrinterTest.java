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

package io.evitadb.test.client.query.rest;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link RestInputJsonPrinter} pretty-prints strict JSON — field names stay wrapped in
 * quotes (unlike the GraphQL printer, which unquotes them).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(REST)
@Tag(SERIALIZATION)
@DisplayName("RestInputJsonPrinter REST-input rendering")
class RestInputJsonPrinterTest {

	private final RestInputJsonPrinter printer = new RestInputJsonPrinter();

	@Test
	@DisplayName("Should pretty-print with quoted field names across multiple lines")
	void shouldPrettyPrintWithQuotedFieldNames() {
		final ObjectNode node = JsonNodeFactory.instance.objectNode();
		node.put("name", "value");

		final String printed = printer.print(node);

		assertTrue(printed.contains("\"name\""));
		assertTrue(printed.contains("\"value\""));
		assertTrue(printed.contains(System.lineSeparator()));
	}
}
