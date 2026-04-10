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

package io.evitadb.api.query.parser.exception;

import lombok.NoArgsConstructor;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import javax.annotation.Nonnull;

/**
 * ANTLR error listener that converts lexer and parser syntax errors into {@link EvitaSyntaxException}s.
 *
 * This class implements ANTLR's error listener interface to intercept syntax errors during both lexical
 * analysis (tokenization) and parsing (grammar rule matching). Unlike the default ANTLR error listener
 * which writes errors to standard error output, this listener throws {@link EvitaSyntaxException}
 * immediately, ensuring that syntax errors are handled as first-class exceptions in the evitaDB
 * query processing pipeline.
 *
 * ## Error Listener Integration
 *
 * ANTLR supports attaching multiple error listeners to a lexer or parser. This listener is installed by
 * {@link io.evitadb.api.query.parser.ParserFactory} on both the lexer and parser:
 *
 * ```java
 * EvitaQLLexer lexer = new EvitaQLLexer(...);
 * lexer.removeErrorListeners();  // Remove default stderr listener
 * lexer.addErrorListener(SyntaxErrorReporter.getInstance());
 *
 * EvitaQLParser parser = new EvitaQLParser(...);
 * parser.removeErrorListeners();  // Remove default stderr listener
 * parser.addErrorListener(SyntaxErrorReporter.getInstance());
 * ```
 *
 * ## Singleton Pattern
 *
 * This class is implemented as a singleton because:
 * 1. It is stateless — all error context is passed via method parameters
 * 2. Creating a new instance for each parser/lexer would be wasteful
 * 3. Thread-safety is guaranteed by immutability
 *
 * ## Error Handling Flow
 *
 * **Lexer errors** (character-level):
 * - Invalid characters in input (e.g., `query(collection('Product'€))` with Euro symbol)
 * - Unterminated string literals (e.g., `query(collection('Product)` missing closing quote)
 * - Malformed numeric literals (e.g., `123.45.67`)
 *
 * **Parser errors** (token-level):
 * - Unexpected token sequences (handled primarily by {@link BailErrorStrategy})
 * - Grammar rule violations
 * - Some cases of missing or misplaced tokens
 *
 * When ANTLR detects any of these errors, it calls `syntaxError()` with precise location information
 * and a human-readable message. This method then throws `EvitaSyntaxException`, which propagates up
 * through {@link io.evitadb.api.query.parser.ParserExecutor} to the calling code.
 *
 * ## Relationship with BailErrorStrategy
 *
 * This class complements {@link BailErrorStrategy}:
 * - `SyntaxErrorReporter`: handles errors reported via ANTLR's error listener mechanism
 * - `BailErrorStrategy`: handles errors during parser recovery attempts
 *
 * Both produce `EvitaSyntaxException` but via different ANTLR extension points. Together, they provide
 * complete coverage of all syntax error scenarios.
 *
 * ## Thread Safety
 *
 * This class is stateless and thread-safe. The singleton instance can be safely shared across multiple
 * threads, with each parser/lexer instance using it independently. All state (error location, message)
 * is passed via method parameters and immediately converted to an exception, so no mutable state is held.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class SyntaxErrorReporter extends BaseErrorListener {

	/**
	 * Singleton instance shared across all EvitaQL parsers and lexers.
	 * Accessed via {@link #getInstance()} to ensure single instance is used throughout the application.
	 */
	private static final SyntaxErrorReporter INSTANCE = new SyntaxErrorReporter();

	/**
	 * Returns the singleton instance of the syntax error reporter.
	 *
	 * This method is called by {@link io.evitadb.api.query.parser.ParserFactory} when configuring
	 * each newly created parser and lexer instance.
	 *
	 * @return the shared singleton instance
	 */
	@Nonnull
	public static SyntaxErrorReporter getInstance() {
		return INSTANCE;
	}

	/**
	 * Handles syntax errors by immediately throwing {@link EvitaSyntaxException}.
	 *
	 * This method is invoked by ANTLR when the lexer or parser encounters a syntax error. Instead of
	 * logging or collecting errors, we throw an exception immediately to fail fast. The error message
	 * is prefixed with "Syntax error: " for consistency and clarity.
	 *
	 * Location information (`line` and `charPositionInLine`) is provided directly by ANTLR and passed
	 * to the exception constructor. Note that `charPositionInLine` uses 0-based indexing (ANTLR
	 * convention), which `EvitaSyntaxException` automatically adjusts to 1-based indexing for display.
	 *
	 * @param recognizer the ANTLR recognizer (lexer or parser) that encountered the error
	 * @param offendingSymbol the symbol (token or character) that caused the error; may be null for
	 *                        lexer errors
	 * @param line the line number where the error occurred (1-based)
	 * @param charPositionInLine the character position within the line (0-based)
	 * @param msg ANTLR's default error message describing what went wrong
	 * @param e the recognition exception that triggered this error; may be null if error was detected
	 *          without exception
	 * @throws EvitaSyntaxException always thrown to signal parsing failure
	 */
	@Override
	public void syntaxError(
		@Nonnull Recognizer<?, ?> recognizer,
		Object offendingSymbol,
		int line,
		int charPositionInLine,
		@Nonnull String msg,
		RecognitionException e
	) {
		throw new EvitaSyntaxException(line, charPositionInLine, "Syntax error: " + msg);
	}
}
