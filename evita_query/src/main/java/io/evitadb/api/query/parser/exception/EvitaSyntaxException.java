/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when EvitaQL query parsing fails due to syntax errors or logical inconsistencies.
 *
 * This exception is the primary error signaling mechanism for the EvitaQL parser. It extends
 * {@link EvitaInvalidUsageException} to indicate that the user provided malformed or semantically
 * invalid query text. Unlike internal parser errors, this exception is always thrown as a result
 * of client-side mistakes and should be surfaced to the API consumer with actionable feedback.
 *
 * ## Error Context
 *
 * The exception captures precise error location information:
 * - Line number where the error occurred (1-based)
 * - Character position within the line (1-based, adjusted from ANTLR's 0-based indexing)
 * - Human-readable error message describing the problem
 *
 * This information is formatted into a standardized error message:
 * `Invalid syntax, error at position {line}:{charPosition}: {reason}`
 *
 * ## Usage in Parser Infrastructure
 *
 * This exception is thrown by:
 * - {@link SyntaxErrorReporter}: ANTLR error listener that converts lexer/parser errors
 * - {@link BailErrorStrategy}: ANTLR error recovery strategy that wraps recognition exceptions
 * - Custom visitor validation logic in `io.evitadb.api.query.parser.visitor` classes
 * - {@link io.evitadb.api.query.parser.ParserExecutor}: Unwraps this exception from
 *   {@link org.antlr.v4.runtime.misc.ParseCancellationException}
 *
 * ## Exception Flow
 *
 * 1. ANTLR parser encounters syntax error
 * 2. {@link SyntaxErrorReporter} or {@link BailErrorStrategy} creates `EvitaSyntaxException`
 * 3. `BailErrorStrategy` wraps it in `ParseCancellationException` (ANTLR convention)
 * 4. `ParserExecutor` catches `ParseCancellationException` and unwraps the original
 *    `EvitaSyntaxException`
 * 5. Exception propagates to calling code with full context
 *
 * ## Thread Safety
 *
 * This exception class is immutable and thread-safe. All fields are final and set during construction.
 *
 * ## Example Error Scenarios
 *
 * - Missing closing parenthesis: `query(collection('Product'`
 * - Unknown constraint name: `query(unknownConstraint())`
 * - Type mismatch: `attributeEquals('code', true)` when attribute is numeric
 * - Missing required arguments: `query(collection())`
 * - Invalid parameter reference: `query(collection(@undefined))`
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class EvitaSyntaxException extends EvitaInvalidUsageException {

	@Serial private static final long serialVersionUID = -4647520133884807181L;

	/**
	 * Standard error message format template. Produces messages like:
	 * "Invalid syntax, error at position 1:15: unexpected token ')'"
	 */
	private static final String DEFAULT_ERROR_MSG = "Invalid syntax, error at position %d:%d: %s";

	/**
	 * Line number where the syntax error occurred (1-based indexing).
	 */
	@Getter
	private final int line;

	/**
	 * Character position within the line where the error occurred (1-based indexing).
	 * ANTLR provides 0-based positions, which are incremented by 1 in all constructors
	 * for consistency with standard text editor conventions.
	 */
	@Getter
	private final int charPositionInLine;

	/**
	 * Human-readable explanation of what went wrong, without positional information.
	 * This is the raw error message suitable for display in development tools or logs.
	 * Examples: "unexpected token ')'", "expected constraint name", "unknown attribute 'xyz'"
	 */
	@Getter
	@Nonnull
	private final String reason;

	/**
	 * Creates syntax exception from an ANTLR parser rule context.
	 *
	 * This constructor is typically used within ANTLR visitor implementations when semantic validation
	 * fails during AST traversal. The error position is extracted from the starting token of the parser
	 * rule context.
	 *
	 * Character position is adjusted from ANTLR's 0-based indexing to 1-based indexing to match
	 * standard text editor conventions.
	 *
	 * @param ctx ANTLR parser rule context where the error occurred
	 * @param publicMessage human-readable error description (e.g., "attribute 'xyz' is not defined")
	 */
	public EvitaSyntaxException(@Nonnull ParserRuleContext ctx, @Nonnull String publicMessage) {
		super(String.format(DEFAULT_ERROR_MSG, ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine() + 1, publicMessage));
		this.line = ctx.getStart().getLine();
		this.charPositionInLine = ctx.getStart().getCharPositionInLine() + 1;
		this.reason = publicMessage;
	}

	/**
	 * Creates syntax exception from an ANTLR token.
	 *
	 * This constructor is used by {@link BailErrorStrategy} when wrapping ANTLR's
	 * {@link org.antlr.v4.runtime.RecognitionException}. The offending token is the one that caused
	 * the parser to fail (e.g., unexpected token type, missing expected token).
	 *
	 * Character position is adjusted from ANTLR's 0-based indexing to 1-based indexing to match
	 * standard text editor conventions.
	 *
	 * @param offendingToken the ANTLR token that triggered the syntax error
	 * @param publicMessage human-readable error description (e.g., "unexpected token ')'")
	 */
	public EvitaSyntaxException(@Nonnull Token offendingToken, @Nonnull String publicMessage) {
		super(String.format(DEFAULT_ERROR_MSG, offendingToken.getLine(), offendingToken.getCharPositionInLine() + 1, publicMessage));
		this.line = offendingToken.getLine();
		this.charPositionInLine = offendingToken.getCharPositionInLine() + 1;
		this.reason = publicMessage;
	}

	/**
	 * Creates syntax exception from explicit line and character position.
	 *
	 * This constructor is used by {@link SyntaxErrorReporter} when ANTLR's error listener is invoked.
	 * It's also used for programmatic error creation when AST nodes are not available but position
	 * information is known from other sources.
	 *
	 * Note: The constructor accepts 0-based character position (matching ANTLR convention) and
	 * automatically adjusts it to 1-based indexing for storage and display.
	 *
	 * @param line line number where error occurred (1-based)
	 * @param charPositionInLine character position within line (0-based, will be adjusted to 1-based)
	 * @param publicMessage human-readable error description (e.g., "syntax error: missing ','")
	 */
	public EvitaSyntaxException(int line, int charPositionInLine, @Nonnull String publicMessage) {
		super(String.format(DEFAULT_ERROR_MSG, line, charPositionInLine + 1, publicMessage));
		this.line = line;
		this.charPositionInLine = charPositionInLine + 1;
		this.reason = publicMessage;
	}
}
