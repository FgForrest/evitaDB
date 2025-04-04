/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.query.parser.visitor;

import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EvitaQLParameterVisitor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class EvitaQLParameterVisitorTest {

	@Test
	void shouldParsePositionalParameter() {
		assertEquals(1, parsePositionalParameter("?", 1));
		assertEquals(List.of(1, 2), parsePositionalParameter("?", List.of(1, 2)));
	}

	@Test
	void shouldNotParsePositionalParameter() {
		assertThrows(EvitaSyntaxException.class, () -> parsePositionalParameter("?"));
	}

	@Test
	void shouldParseNamedParameter() {
		assertEquals(1, parseNamedParameter("@param", Map.of("param", 1)));
		assertEquals(List.of(1, 2), parseNamedParameter("@param", Map.of("param", List.of(1, 2))));
	}

	@Test
	void shouldNotParseNamedParameter() {
		assertThrows(EvitaSyntaxException.class, () -> parseNamedParameter("@param", Map.of()));
	}


	/**
	 * Using generated EvitaQL parser tries to parse string as grammar rule "positionalParameter"
	 *
	 * @param string string to parse
	 * @param positionalArguments positional arguments to substitute
	 * @return parsed classifier
	 */
	private Object parsePositionalParameter(@Nonnull String string, @Nonnull Object... positionalArguments) {
		return ParserExecutor.execute(
			new ParseContext(positionalArguments),
			() -> ParserFactory.getParser(string).positionalParameter().accept(new EvitaQLParameterVisitor())
		);
	}

	/**
	 * Using generated EvitaQL parser tries to parse string as grammar rule "namedParameter"
	 *
	 * @param string string to parse
	 * @param namedArguments named arguments to substitute
	 * @return parsed classifier
	 */
	private Object parseNamedParameter(@Nonnull String string, @Nonnull Map<String, Object> namedArguments) {
		return ParserExecutor.execute(
			new ParseContext(namedArguments),
			() -> ParserFactory.getParser(string).namedParameter().accept(new EvitaQLParameterVisitor())
		);
	}
}
