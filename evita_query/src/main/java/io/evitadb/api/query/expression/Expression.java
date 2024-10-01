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

package io.evitadb.api.query.expression;

import io.evitadb.api.query.expression.parser.exception.ExpressionEvaluationException;
import io.evitadb.api.query.expression.parser.exception.ParserException;
import io.evitadb.api.query.expression.parser.grammar.ExpressionLexer;
import io.evitadb.api.query.expression.parser.grammar.ExpressionParser;
import io.evitadb.api.query.expression.parser.visitor.DefaultExpressionVisitor;
import io.evitadb.api.query.expression.parser.visitor.operators.ExpressionNode;
import io.evitadb.api.query.parser.exception.BailErrorStrategy;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.api.query.parser.exception.SyntaxErrorReporter;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.misc.ParseCancellationException;

import javax.annotation.Nonnull;

/**
 * The Expression interface provides methods for parsing an expression string and generating the corresponding
 * {@link ExpressionNode} object. {@link ExpressionNode} objects represent the parsed expression and can be used to evaluate
 * the expression.
 *
 * @author Lukáš Hornych, 2024
 */
public interface Expression {

	/**
	 * Parses a given expression string and returns the corresponding ExpressionNode object.
	 *
	 * @param expression the input expression string to be parsed
	 * @return the ExpressionNode object resulting from parsing the input expression
	 */
	@Nonnull
	static ExpressionNode parse(@Nonnull String expression) {
		try {
			final ExpressionNode result = getParser(expression).expression().accept(new DefaultExpressionVisitor());
			Assert.isPremiseValid(
				result != null,
				"Result of parse execution is null."
			);
			return result;
		} catch (EvitaInvalidUsageException e) {
			// simply rethrow
			throw new ExpressionEvaluationException(
				"Failed to interpret expression to a value: " + expression,
				"Failed to interpret expression to a value.",
				e
			);
		} catch (ParseCancellationException e) {
			final Throwable cause = e.getCause();
			if (cause instanceof EvitaSyntaxException evitaSyntaxException) {
				throw evitaSyntaxException;
			} else {
				// probably missed to wrap error with EvitaInvalidUsageException exception, therefore it should be checked
				throw new ParserException(cause.getMessage(), "Internal error occurred during expression parsing.", cause);
			}
		} catch (Exception e) {
			throw new ParserException(
				"Internal error occurred during expression parsing: " + e.getMessage(),
				"Internal error occurred during expression parsing.",
				e);
		}
	}

	/**
	 * Returns new preconfigured expression parser with preconfigured lexer to string that is being parsed.
	 *
	 * @param stringToParse the input string to be parsed by the Expression
	 * @return a configured Expression instance ready to parse the input string
	 */
	@Nonnull
	private static ExpressionParser getParser(@Nonnull String stringToParse) {
		final ExpressionLexer lexer = new ExpressionLexer(CharStreams.fromString(stringToParse));
		lexer.removeErrorListeners();
		lexer.addErrorListener(SyntaxErrorReporter.getInstance());

		final ExpressionParser parser = new ExpressionParser(new CommonTokenStream(lexer));
		parser.setErrorHandler(new BailErrorStrategy());
		parser.removeErrorListeners();
		parser.addErrorListener(SyntaxErrorReporter.getInstance());

		return parser;
	}

}
