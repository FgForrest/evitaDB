/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.query.expression.bool.EqualsOperator;
import io.evitadb.api.query.expression.bool.GreaterThanEqualsOperator;
import io.evitadb.api.query.expression.bool.GreaterThanOperator;
import io.evitadb.api.query.expression.bool.LesserThanEqualsOperator;
import io.evitadb.api.query.expression.bool.LesserThanOperator;
import io.evitadb.api.query.expression.bool.NotEqualsOperator;
import io.evitadb.api.query.expression.evaluate.MultiVariableEvaluationContext;
import io.evitadb.api.query.expression.operand.ConstantOperand;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Map;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.EXPRESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that {@link ExpressionFactory#parse(String)} reads the whole text it is given: a text that is not an
 * expression as a whole is refused rather than cut down to its longest valid prefix, a minus sign right before a
 * number after an operand subtracts it, and operators of one precedence level evaluate from left to right.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Expression parsing should")
@Tag(CONTRACT)
@Tag(EXPRESSION)
class ExpressionParsingTest {

	@DisplayName("subtract a number written right after the minus sign")
	@Test
	void shouldSubtractNumberWrittenRightAfterMinusSign() {
		assertComputes("2", "5-3");
		assertComputes("4", "$a-1");
		assertComputes("5", "2*3-1");
		assertComputes("-1.5", "1-2.5");
	}

	@DisplayName("refuse input that follows a complete expression")
	@ParameterizedTest
	@ValueSource(strings = {"1 + 2 3", "abs(-4).foo", "$a $b", "1)", "true false"})
	void shouldRefuseInputFollowingCompleteExpression(String text) {
		assertThrows(EvitaInvalidUsageException.class, () -> ExpressionFactory.parse(text));
	}

	@DisplayName("keep a minus sign before a number as part of the number")
	@Test
	void shouldKeepMinusSignBeforeNumberAsPartOfIt() {
		final Expression integer = ExpressionFactory.parse("-4");
		assertEquals(-4L, assertInstanceOf(ConstantOperand.class, integer.getOperand()).getValue());
		assertEquals("-4", integer.toExpressionString());

		final Expression decimal = ExpressionFactory.parse("-4.5");
		assertEquals(
			new BigDecimal("-4.5"), assertInstanceOf(ConstantOperand.class, decimal.getOperand()).getValue()
		);
		assertEquals("-4.5", decimal.toExpressionString());
	}

	@DisplayName("compute with negative numbers as operands and arguments")
	@Test
	void shouldComputeWithNegativeNumbers() {
		assertComputes("-6", "2 * -3");
		assertComputes("2", "1 - -1");
		assertComputes("4", "abs(-4)");
	}

	@DisplayName("evaluate arithmetic operators of one level from left to right")
	@Test
	void shouldEvaluateArithmeticOperatorsOfOneLevelFromLeftToRight() {
		assertComputes("11", "10 - 2 + 3");
		assertComputes("8", "8 / 2 * 2");
		assertComputes("6", "7 % 4 * 2");
		assertComputes("1", "9 * 3 % 2");
		assertComputes("5", "$a - 1 + 1");
	}

	@DisplayName("group comparison operators of one level from left to right")
	@Test
	void shouldGroupComparisonOperatorsOfOneLevelFromLeftToRight() {
		// the operator written last is the root: the ones before it form its left operand
		assertInstanceOf(EqualsOperator.class, ExpressionFactory.parse("$a != $b == $c").getOperand());
		assertInstanceOf(NotEqualsOperator.class, ExpressionFactory.parse("$a == $b != $c").getOperand());
		assertInstanceOf(GreaterThanOperator.class, ExpressionFactory.parse("$a < $b > $c").getOperand());
		assertInstanceOf(LesserThanOperator.class, ExpressionFactory.parse("$a <= $b < $c").getOperand());
		assertInstanceOf(GreaterThanEqualsOperator.class, ExpressionFactory.parse("$a > $b >= $c").getOperand());
		assertInstanceOf(LesserThanEqualsOperator.class, ExpressionFactory.parse("$a >= $b <= $c").getOperand());
	}

	/**
	 * Asserts that the text parses and computes to the expected number, with the variable `a` set to 5.
	 *
	 * @param expected the expected number
	 * @param text     the expression text
	 */
	private static void assertComputes(String expected, String text) {
		final Object result = ExpressionFactory.parse(text)
			.compute(new MultiVariableEvaluationContext(Map.of("a", 5)));
		assertEquals(
			0, new BigDecimal(expected).compareTo(new BigDecimal(String.valueOf(result))),
			() -> "`" + text + "` computed " + result + ", expected " + expected
		);
	}


}
