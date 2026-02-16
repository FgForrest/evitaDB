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

package io.evitadb.api.query.parser;

import io.evitadb.api.query.parser.exception.BailErrorStrategy;
import io.evitadb.api.query.parser.exception.SyntaxErrorReporter;
import io.evitadb.api.query.parser.grammar.EvitaQLParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ParserFactory} verifying parser creation
 * and configuration.
 *
 * @author evitaDB
 */
@DisplayName("ParserFactory")
class ParserFactoryTest {

	@Test
	@DisplayName("should return non-null parser for valid input")
	void shouldReturnNonNullParserForValidInput() {
		final EvitaQLParser parser = ParserFactory.getParser("query(collection('a'))");

		assertNotNull(parser);
	}

	@Test
	@DisplayName("should configure BailErrorStrategy on parser")
	void shouldConfigureBailErrorStrategy() {
		final EvitaQLParser parser = ParserFactory.getParser("query(collection('a'))");

		assertInstanceOf(BailErrorStrategy.class, parser.getErrorHandler());
	}

	@Test
	@DisplayName("should configure SyntaxErrorReporter on parser")
	void shouldConfigureSyntaxErrorReporter() {
		final EvitaQLParser parser = ParserFactory.getParser("query(collection('a'))");

		final boolean hasSyntaxErrorReporter = parser.getErrorListeners().stream()
			.anyMatch(SyntaxErrorReporter.class::isInstance);

		assertTrue(hasSyntaxErrorReporter, "Parser should have SyntaxErrorReporter as error listener");
	}

	@Test
	@DisplayName("should return parser for empty string input")
	void shouldReturnParserForEmptyString() {
		final EvitaQLParser parser = ParserFactory.getParser("");

		assertNotNull(parser);
	}
}
