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

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.ParseCancellationException;

/**
 * ANTLR error recovery strategy that converts parser errors into descriptive {@link EvitaSyntaxException}s.
 *
 * This class extends ANTLR's {@link org.antlr.v4.runtime.BailErrorStrategy} to customize error handling
 * during EvitaQL query parsing. Unlike the default ANTLR error strategy which attempts error recovery
 * and continues parsing, this strategy immediately aborts on the first syntax error and provides rich
 * error context through {@link EvitaSyntaxException}.
 *
 * ## Bail-on-Error Behavior
 *
 * The parent class `BailErrorStrategy` is designed to:
 * 1. Throw {@link ParseCancellationException} instead of attempting error recovery
 * 2. Prevent the parser from generating misleading cascading errors
 * 3. Ensure fast failure for invalid input
 *
 * This strategy is appropriate for evitaDB because queries must be syntactically correct to execute —
 * partial or "best-effort" parsing would not be meaningful.
 *
 * ## Error Wrapping Pattern
 *
 * Both overridden methods follow the same pattern:
 * 1. Call the parent implementation (which throws `ParseCancellationException`)
 * 2. Catch that exception
 * 3. Wrap it with a new `ParseCancellationException` containing `EvitaSyntaxException` as the cause
 *
 * This wrapping is required by ANTLR's parser contract. The outer `ParseCancellationException` signals
 * to ANTLR that parsing should stop, while the inner `EvitaSyntaxException` provides evitaDB-specific
 * error details. {@link io.evitadb.api.query.parser.ParserExecutor} later unwraps the exception to
 * expose only the `EvitaSyntaxException` to calling code.
 *
 * ## Integration with Parser
 *
 * This error strategy is installed via {@link io.evitadb.api.query.parser.ParserFactory}:
 * ```java
 * EvitaQLParser parser = new EvitaQLParser(...);
 * parser.setErrorHandler(new BailErrorStrategy());
 * ```
 *
 * It works in conjunction with {@link SyntaxErrorReporter} to provide comprehensive error reporting:
 * - `BailErrorStrategy`: handles parser-level errors (unexpected tokens, rule violations)
 * - `SyntaxErrorReporter`: handles lexer-level errors (invalid characters, malformed literals)
 *
 * ## Thread Safety
 *
 * This class is stateless and thread-safe. A single instance can be shared across multiple parsers,
 * though in practice {@link io.evitadb.api.query.parser.ParserFactory} creates a new instance for
 * each parser to align with ANTLR conventions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class BailErrorStrategy extends org.antlr.v4.runtime.BailErrorStrategy {

	/**
	 * Handles parser errors by aborting and throwing an exception with detailed context.
	 *
	 * This method is called when the parser encounters an error and would normally attempt recovery
	 * (e.g., by inserting or deleting tokens). Instead, we immediately throw an exception containing
	 * the offending token and original error message.
	 *
	 * @param recognizer the ANTLR parser instance that encountered the error
	 * @param e the recognition exception containing error details and offending token
	 * @throws ParseCancellationException wrapping {@link EvitaSyntaxException} with error context
	 */
	@Override
	public void recover(Parser recognizer, RecognitionException e) {
		try {
			// Parent throws ParseCancellationException immediately (no recovery attempt)
			super.recover(recognizer, e);
		} catch (ParseCancellationException ex) {
			// Wrap with EvitaSyntaxException for evitaDB-specific error handling
			throw new ParseCancellationException(
				new EvitaSyntaxException(e.getOffendingToken(), e.getMessage())
			);
		}
	}

	/**
	 * Handles inline token mismatches by aborting and throwing an exception with expected tokens.
	 *
	 * This method is called when the parser expected a specific token type but found something else
	 * (e.g., expected ',' but found ')'). The error message includes the list of expected tokens to
	 * help users understand what syntax would be valid at that position.
	 *
	 * @param recognizer the ANTLR parser instance that encountered the error
	 * @return never returns (always throws exception)
	 * @throws RecognitionException propagated from parent implementation
	 * @throws ParseCancellationException wrapping {@link EvitaSyntaxException} with expected token list
	 */
	@Override
	public Token recoverInline(Parser recognizer) throws RecognitionException {
		try {
			// Parent throws ParseCancellationException immediately (no token insertion)
			return super.recoverInline(recognizer);
		} catch (ParseCancellationException e) {
			// Build error message with expected tokens for better developer feedback
			throw new ParseCancellationException(
				new EvitaSyntaxException(
					recognizer.getCurrentToken(),
					"Unexpected token, expected: " + recognizer.getExpectedTokens().toString(recognizer.getVocabulary())
				)
			);
		}
	}
}
