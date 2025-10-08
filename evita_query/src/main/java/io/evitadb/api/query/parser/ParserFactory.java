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

package io.evitadb.api.query.parser;

import io.evitadb.api.query.parser.exception.BailErrorStrategy;
import io.evitadb.api.query.parser.exception.SyntaxErrorReporter;
import io.evitadb.api.query.parser.grammar.EvitaQLLexer;
import io.evitadb.api.query.parser.grammar.EvitaQLParser;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import javax.annotation.Nonnull;

/**
 * Factory for creating ready-to-use {@link EvitaQLParser}s with all needed configurations.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ParserFactory {

	/**
	 * Returns new preconfigured Evita QL parser with preconfigured lexer to string that is being parsed
	 */
	public static EvitaQLParser getParser(@Nonnull String stringToParse) {
		final EvitaQLLexer lexer = new EvitaQLLexer(CharStreams.fromString(stringToParse));
		lexer.removeErrorListeners();
		lexer.addErrorListener(SyntaxErrorReporter.getInstance());

		final EvitaQLParser parser = new EvitaQLParser(new CommonTokenStream(lexer));
		parser.setErrorHandler(new BailErrorStrategy());
		parser.removeErrorListeners();
		parser.addErrorListener(SyntaxErrorReporter.getInstance());

		return parser;
	}
}
