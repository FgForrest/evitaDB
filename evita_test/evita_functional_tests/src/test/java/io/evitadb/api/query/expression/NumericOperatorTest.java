/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

import io.evitadb.api.query.expression.evaluate.MultiVariableEvaluationContext;
import io.evitadb.api.query.expression.exception.ParserException;
import io.evitadb.api.query.expression.numeric.AdditionOperator;
import io.evitadb.api.query.expression.numeric.DivisionOperator;
import io.evitadb.api.query.expression.numeric.ModuloOperator;
import io.evitadb.api.query.expression.numeric.MultiplicationOperator;
import io.evitadb.api.query.expression.numeric.NegativeOperator;
import io.evitadb.api.query.expression.numeric.PositiveOperator;
import io.evitadb.api.query.expression.numeric.SubtractionOperator;
import io.evitadb.api.query.expression.operand.ConstantOperand;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.ExpressionEvaluationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for all numeric operators in the expression language verifying
 * arithmetic computation, range determination, and error handling.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Numeric operators")
class NumericOperatorTest {

	private static final ExpressionEvaluationContext CONTEXT =
		new MultiVariableEvaluationContext(42, Map.of());

	/**
	 * Creates a {@link ConstantOperand} wrapping the given integer as
	 * {@link BigDecimal}.
	 */
	@Nonnull
	private static ExpressionNode num(int value) {
		return new ConstantOperand(new BigDecimal(value));
	}

	/**
	 * Creates a {@link ConstantOperand} wrapping the given
	 * {@link BigDecimal} string.
	 */
	@Nonnull
	private static ExpressionNode num(@Nonnull String value) {
		return new ConstantOperand(new BigDecimal(value));
	}

	@Nested
	@DisplayName("Addition")
	class AdditionOperatorTests {

		@Test
		@DisplayName("should add two integers")
		void shouldAddTwoIntegers() {
			final AdditionOperator op =
				new AdditionOperator(num(3), num(5));

			final BigDecimal result =
				(BigDecimal) op.compute(CONTEXT);

			assertEquals(new BigDecimal("8"), result);
		}

		@Test
		@DisplayName("should add BigDecimal values with precision")
		void shouldAddBigDecimalWithPrecision() {
			final AdditionOperator op =
				new AdditionOperator(num("1.1"), num("2.2"));

			final BigDecimal result =
				(BigDecimal) op.compute(CONTEXT);

			assertEquals(new BigDecimal("3.3"), result);
		}

		@Test
		@DisplayName("should combine ranges")
		void shouldCombineRanges() {
			final AdditionOperator op =
				new AdditionOperator(num(3), num(5));

			final BigDecimalNumberRange range =
				op.determinePossibleRange();

			assertEquals(
				BigDecimalNumberRange.between(
					new BigDecimal("8"),
					new BigDecimal("8")
				),
				range
			);
		}

		@Test
		@DisplayName("should format as 'left + right'")
		void shouldFormatToString() {
			final AdditionOperator op =
				new AdditionOperator(num(3), num(5));

			assertEquals("3 + 5", op.toString());
		}
	}

	@Nested
	@DisplayName("Subtraction")
	class SubtractionOperatorTests {

		@Test
		@DisplayName("should subtract two integers")
		void shouldSubtractTwoIntegers() {
			final SubtractionOperator op =
				new SubtractionOperator(num(10), num(3));

			final BigDecimal result =
				(BigDecimal) op.compute(CONTEXT);

			assertEquals(new BigDecimal("7"), result);
		}

		@Test
		@DisplayName("should combine ranges")
		void shouldCombineRanges() {
			final SubtractionOperator op =
				new SubtractionOperator(num(10), num(3));

			final BigDecimalNumberRange range =
				op.determinePossibleRange();

			assertEquals(
				BigDecimalNumberRange.between(
					new BigDecimal("7"),
					new BigDecimal("7")
				),
				range
			);
		}

		@Test
		@DisplayName("should format as 'left - right'")
		void shouldFormatToString() {
			final SubtractionOperator op =
				new SubtractionOperator(num(10), num(3));

			assertEquals("10 - 3", op.toString());
		}
	}

	@Nested
	@DisplayName("Multiplication")
	class MultiplicationOperatorTests {

		@Test
		@DisplayName("should multiply two integers")
		void shouldMultiplyTwoIntegers() {
			final MultiplicationOperator op =
				new MultiplicationOperator(num(4), num(5));

			final BigDecimal result =
				(BigDecimal) op.compute(CONTEXT);

			assertEquals(new BigDecimal("20"), result);
		}

		@Test
		@DisplayName("should combine ranges")
		void shouldCombineRanges() {
			final MultiplicationOperator op =
				new MultiplicationOperator(num(4), num(5));

			final BigDecimalNumberRange range =
				op.determinePossibleRange();

			assertEquals(
				BigDecimalNumberRange.between(
					new BigDecimal("20"),
					new BigDecimal("20")
				),
				range
			);
		}

		@Test
		@DisplayName("should format as 'left * right'")
		void shouldFormatToString() {
			final MultiplicationOperator op =
				new MultiplicationOperator(num(4), num(5));

			assertEquals("4 * 5", op.toString());
		}
	}

	@Nested
	@DisplayName("Division")
	class DivisionOperatorTests {

		@Test
		@DisplayName("should divide two integers")
		void shouldDivideTwoIntegers() {
			final DivisionOperator op =
				new DivisionOperator(num(10), num(2));

			final BigDecimal result =
				(BigDecimal) op.compute(CONTEXT);

			assertEquals(
				0,
				new BigDecimal("5").compareTo(result)
			);
		}

		@Test
		@DisplayName(
			"should throw ArithmeticException for division by zero"
		)
		void shouldThrowWhenDivisionByZero() {
			final DivisionOperator op =
				new DivisionOperator(num(10), num(0));

			assertThrows(
				ArithmeticException.class,
				() -> op.compute(CONTEXT)
			);
		}

		@Test
		@DisplayName("should format as 'left / right'")
		void shouldFormatToString() {
			final DivisionOperator op =
				new DivisionOperator(num(10), num(2));

			assertEquals("10 / 2", op.toString());
		}
	}

	@Nested
	@DisplayName("Modulo")
	class ModuloOperatorTests {

		@Test
		@DisplayName("should compute modulo of two integers")
		void shouldComputeModulo() {
			final ModuloOperator op =
				new ModuloOperator(num(10), num(3));

			final BigDecimal result =
				(BigDecimal) op.compute(CONTEXT);

			assertEquals(new BigDecimal("1"), result);
		}

		@Test
		@DisplayName(
			"should throw ArithmeticException for modulo by zero"
		)
		void shouldThrowWhenModuloByZero() {
			final ModuloOperator op =
				new ModuloOperator(num(10), num(0));

			assertThrows(
				ArithmeticException.class,
				() -> op.compute(CONTEXT)
			);
		}

		@Test
		@DisplayName("should format as 'left % right'")
		void shouldFormatToString() {
			final ModuloOperator op =
				new ModuloOperator(num(10), num(3));

			assertEquals("10 % 3", op.toString());
		}
	}

	@Nested
	@DisplayName("Positive")
	class PositiveOperatorTests {

		@Test
		@DisplayName("should delegate to child and return same value")
		void shouldDelegateToChild() {
			final PositiveOperator op = new PositiveOperator(num(5));

			final BigDecimal result =
				op.compute(CONTEXT, BigDecimal.class);

			assertEquals(new BigDecimal("5"), result);
		}

		@Test
		@DisplayName(
			"should throw for null operand with correct message"
		)
		void shouldThrowForNullOperandWithCorrectMessage() {
			final ParserException exception = assertThrows(
				ParserException.class,
				() -> new PositiveOperator(null)
			);
			assertEquals(
				"Positive operator must have at least one operand!",
				exception.getMessage()
			);
		}

		@Test
		@DisplayName("should delegate range to child")
		void shouldDelegateRange() {
			final PositiveOperator op = new PositiveOperator(num(5));

			final BigDecimalNumberRange range =
				op.determinePossibleRange();

			assertEquals(
				BigDecimalNumberRange.between(
					new BigDecimal("5"),
					new BigDecimal("5")
				),
				range
			);
		}

		@Test
		@DisplayName("should format as '+operand'")
		void shouldFormatToString() {
			final PositiveOperator op = new PositiveOperator(num(5));

			assertEquals("+5", op.toString());
		}
	}

	@Nested
	@DisplayName("Negative")
	class NegativeOperatorTests {

		@Test
		@DisplayName("should negate positive value")
		void shouldNegatePositiveValue() {
			final NegativeOperator op = new NegativeOperator(num(5));

			final BigDecimal result =
				(BigDecimal) op.compute(CONTEXT);

			assertEquals(new BigDecimal("-5"), result);
		}

		@Test
		@DisplayName("should negate negative value to positive")
		void shouldNegateNegativeValue() {
			final NegativeOperator op =
				new NegativeOperator(num("-5"));

			final BigDecimal result =
				(BigDecimal) op.compute(CONTEXT);

			assertEquals(new BigDecimal("5"), result);
		}

		@Test
		@DisplayName(
			"should throw ParserException for null operand"
		)
		void shouldThrowForNullOperand() {
			assertThrows(
				ParserException.class,
				() -> new NegativeOperator(null)
			);
		}

		@Test
		@DisplayName("should transform range with negation")
		void shouldTransformRange() {
			final NegativeOperator op = new NegativeOperator(num(5));

			final BigDecimalNumberRange range =
				op.determinePossibleRange();

			assertNotNull(range);
			assertEquals(
				BigDecimalNumberRange.between(
					new BigDecimal("-5"),
					new BigDecimal("-5")
				),
				range
			);
		}

		@Test
		@DisplayName("should format as '-operand'")
		void shouldFormatToString() {
			final NegativeOperator op = new NegativeOperator(num(5));

			assertEquals("-5", op.toString());
		}
	}
}
