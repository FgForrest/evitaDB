/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.query.predicate.parser;

import io.evitadb.api.query.predicate.parser.grammar.PredicateLexer;
import io.evitadb.api.query.predicate.parser.grammar.PredicateParser;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import javax.annotation.Nonnull;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2024
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class ParserFactory {

	/**
	 * Returns new preconfigured Evita QL parser with preconfigured lexer to string that is being parsed
	 */
	public static PredicateParser getParser(@Nonnull String stringToParse) {
		final PredicateLexer lexer = new PredicateLexer(CharStreams.fromString(stringToParse));
		lexer.removeErrorListeners();
		// todo lho impl
//		lexer.addErrorListener(EvitaQLErrorReporter.getInstance());

		final PredicateParser parser = new PredicateParser(new CommonTokenStream(lexer));
		// todo lho impl
//		parser.setErrorHandler(new EvitaQLBailErrorStrategy());
//		parser.removeErrorListeners();
//		parser.addErrorListener(EvitaQLErrorReporter.getInstance());

		return parser;
	}}
