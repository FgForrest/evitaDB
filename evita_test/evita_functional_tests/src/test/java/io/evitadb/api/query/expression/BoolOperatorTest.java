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

import io.evitadb.api.query.expression.bool.ConjunctionOperator;
import io.evitadb.api.query.expression.bool.DisjunctionOperator;
import io.evitadb.api.query.expression.bool.EqualsOperator;
import io.evitadb.api.query.expression.bool.GreaterThanEqualsOperator;
import io.evitadb.api.query.expression.bool.GreaterThanOperator;
import io.evitadb.api.query.expression.bool.InverseOperator;
import io.evitadb.api.query.expression.bool.LesserThanEqualsOperator;
import io.evitadb.api.query.expression.bool.LesserThanOperator;
import io.evitadb.api.query.expression.bool.NotEqualsOperator;
import io.evitadb.api.query.expression.bool.XorOperator;
import io.evitadb.api.query.expression.evaluate.MultiVariableEvaluationContext;
import io.evitadb.api.query.expression.exception.ParserException;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.api.query.expression.operand.ConstantOperand;
import io.evitadb.api.query.expression.operand.VariableOperand;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
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
 * Tests for all boolean operators in the expression language verifying
 * logical computation and possible range determination.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Boolean operators")
class BoolOperatorTest {

	private static final ExpressionEvaluationContext CONTEXT =
		new MultiVariableEvaluationContext(42, Map.of());

	/**
	 * Creates a {@link ConstantOperand} wrapping the given boolean value.
	 */
	@Nonnull
	private static ExpressionNode bool(boolean value) {
		return new ConstantOperand(value);
	}

	/**
	 * Creates a {@link ConstantOperand} wrapping the given integer as a
	 * {@link BigDecimal}.
	 */
	@Nonnull
	private static ExpressionNode num(int value) {
		return new ConstantOperand(new BigDecimal(value));
	}

	/**
	 * Creates a {@link ConstantOperand} wrapping the given string.
	 */
	@Nonnull
	private static ExpressionNode str(@Nonnull String value) {
		return new ConstantOperand(value);
	}

	@Nested
	@DisplayName("Conjunction (AND)")
	class ConjunctionOperatorTests {

		@Test
		@DisplayName("should return true when both operands are true")
		void shouldReturnTrueWhenBothTrue() {
			final ConjunctionOperator op =
				new ConjunctionOperator(bool(true), bool(true));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(true, result);
		}

		@Test
		@DisplayName("should return false when left is true and right is false")
		void shouldReturnFalseWhenTrueAndFalse() {
			final ConjunctionOperator op =
				new ConjunctionOperator(bool(true), bool(false));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(false, result);
		}

		@Test
		@DisplayName("should return false when both operands are false")
		void shouldReturnFalseWhenBothFalse() {
			final ConjunctionOperator op =
				new ConjunctionOperator(bool(false), bool(false));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(false, result);
		}

		@Test
		@DisplayName("should intersect ranges of children")
		void shouldIntersectPossibleRanges() {
			final ConjunctionOperator op =
				new ConjunctionOperator(bool(true), bool(false));

			final BigDecimalNumberRange range = op.determinePossibleRange();

			assertNotNull(range);
		}

		@Test
		@DisplayName("should have two children")
		void shouldHaveTwoChildren() {
			final ConjunctionOperator op =
				new ConjunctionOperator(bool(true), bool(false));

			final ExpressionNode[] children = op.getChildren();

			assertNotNull(children);
			assertEquals(2, children.length);
		}

		@Test
		@DisplayName("should format as 'left && right'")
		void shouldFormatToString() {
			final ConjunctionOperator op =
				new ConjunctionOperator(bool(true), bool(false));

			assertEquals("true && false", op.toString());
		}
	}

	@Nested
	@DisplayName("Disjunction (OR)")
	class DisjunctionOperatorTests {

		@Test
		@DisplayName("should return true when left is true and right is false")
		void shouldReturnTrueWhenTrueOrFalse() {
			final DisjunctionOperator op =
				new DisjunctionOperator(bool(true), bool(false));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(true, result);
		}

		@Test
		@DisplayName("should return false when both operands are false")
		void shouldReturnFalseWhenBothFalse() {
			final DisjunctionOperator op =
				new DisjunctionOperator(bool(false), bool(false));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(false, result);
		}

		@Test
		@DisplayName("should return true when both operands are true")
		void shouldReturnTrueWhenBothTrue() {
			final DisjunctionOperator op =
				new DisjunctionOperator(bool(true), bool(true));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(true, result);
		}

		@Test
		@DisplayName("should format as 'left || right'")
		void shouldFormatToString() {
			final DisjunctionOperator op =
				new DisjunctionOperator(bool(true), bool(false));

			assertEquals("true || false", op.toString());
		}
	}

	@Nested
	@DisplayName("XOR")
	class XorOperatorTests {

		@Test
		@DisplayName("should return true when operands differ")
		void shouldReturnTrueWhenOperandsDiffer() {
			final XorOperator op =
				new XorOperator(bool(true), bool(false));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(true, result);
		}

		@Test
		@DisplayName("should return false when both operands are true")
		void shouldReturnFalseWhenBothTrue() {
			final XorOperator op =
				new XorOperator(bool(true), bool(true));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(false, result);
		}

		@Test
		@DisplayName("should return false when both operands are false")
		void shouldReturnFalseWhenBothFalse() {
			final XorOperator op =
				new XorOperator(bool(false), bool(false));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(false, result);
		}

		@Test
		@DisplayName("should format as 'left ^ right'")
		void shouldFormatToString() {
			final XorOperator op =
				new XorOperator(bool(true), bool(false));

			assertEquals("true ^ false", op.toString());
		}
	}

	@Nested
	@DisplayName("Inverse (NOT)")
	class InverseOperatorTests {

		@Test
		@DisplayName("should return false when operand is true")
		void shouldReturnFalseWhenTrue() {
			final InverseOperator op = new InverseOperator(bool(true));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(false, result);
		}

		@Test
		@DisplayName("should return true when operand is false")
		void shouldReturnTrueWhenFalse() {
			final InverseOperator op = new InverseOperator(bool(false));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(true, result);
		}

		@Test
		@DisplayName(
			"should throw ParserException for null operand"
		)
		void shouldThrowForNullOperand() {
			assertThrows(
				ParserException.class,
				() -> new InverseOperator(null)
			);
		}

		@Test
		@DisplayName("should format as '!operand'")
		void shouldFormatToString() {
			final InverseOperator op = new InverseOperator(bool(true));

			assertEquals("!true", op.toString());
		}
	}

	@Nested
	@DisplayName("Equals")
	class EqualsOperatorTests {

		@Test
		@DisplayName("should return true for equal integers")
		void shouldReturnTrueForEqualIntegers() {
			final EqualsOperator op =
				new EqualsOperator(num(5), num(5));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(true, result);
		}

		@Test
		@DisplayName("should return true for equal strings")
		void shouldReturnTrueForEqualStrings() {
			final EqualsOperator op =
				new EqualsOperator(str("a"), str("a"));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(true, result);
		}

		@Test
		@DisplayName("should return true for equal booleans")
		void shouldReturnTrueForEqualBooleans() {
			final EqualsOperator op =
				new EqualsOperator(bool(true), bool(true));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(true, result);
		}

		@Test
		@DisplayName("should return false for different integers")
		void shouldReturnFalseForDifferentIntegers() {
			final EqualsOperator op =
				new EqualsOperator(num(5), num(6));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(false, result);
		}

		@Test
		@DisplayName(
			"should return intersection range for equal constants"
		)
		void shouldReturnIntersectionRangeForEqualConstants() {
			final EqualsOperator op =
				new EqualsOperator(num(5), num(5));

			final BigDecimalNumberRange range =
				op.determinePossibleRange();

			assertEquals(
				BigDecimalNumberRange.between(
					new BigDecimal("5"), new BigDecimal("5")
				),
				range
			);
		}

		@Test
		@DisplayName(
			"should return INFINITE range when one operand is non-numeric"
		)
		void shouldReturnInfiniteWhenNonNumeric() {
			final EqualsOperator op =
				new EqualsOperator(bool(true), bool(true));

			final BigDecimalNumberRange range =
				op.determinePossibleRange();

			assertEquals(BigDecimalNumberRange.INFINITE, range);
		}

		@Test
		@DisplayName("should format as 'left == right'")
		void shouldFormatToString() {
			final EqualsOperator op =
				new EqualsOperator(num(5), num(5));

			assertEquals("5 == 5", op.toString());
		}
	}

	@Nested
	@DisplayName("Not equals")
	class NotEqualsOperatorTests {

		@Test
		@DisplayName("should return true for different integers")
		void shouldReturnTrueForDifferentIntegers() {
			final NotEqualsOperator op =
				new NotEqualsOperator(num(5), num(4));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(true, result);
		}

		@Test
		@DisplayName("should return false for equal integers")
		void shouldReturnFalseForEqualIntegers() {
			final NotEqualsOperator op =
				new NotEqualsOperator(num(5), num(5));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(false, result);
		}

		@Test
		@DisplayName("should always return INFINITE range")
		void shouldReturnInfiniteRange() {
			final NotEqualsOperator op =
				new NotEqualsOperator(num(5), num(4));

			assertEquals(
				BigDecimalNumberRange.INFINITE,
				op.determinePossibleRange()
			);
		}

		@Test
		@DisplayName("should format as 'left != right'")
		void shouldFormatToString() {
			final NotEqualsOperator op =
				new NotEqualsOperator(num(5), num(4));

			assertEquals("5 != 4", op.toString());
		}
	}

	@Nested
	@DisplayName("Greater than")
	class GreaterThanOperatorTests {

		@Test
		@DisplayName("should return true when left is greater")
		void shouldReturnTrueWhenLeftGreater() {
			final GreaterThanOperator op =
				new GreaterThanOperator(num(10), num(5));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(true, result);
		}

		@Test
		@DisplayName("should return false when left is smaller")
		void shouldReturnFalseWhenLeftSmaller() {
			final GreaterThanOperator op =
				new GreaterThanOperator(num(5), num(10));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(false, result);
		}

		@Test
		@DisplayName("should return false when operands are equal")
		void shouldReturnFalseWhenEqual() {
			final GreaterThanOperator op =
				new GreaterThanOperator(num(5), num(5));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(false, result);
		}

		@Test
		@DisplayName(
			"should throw with correct error message for " +
				"non-comparable operand"
		)
		void shouldThrowWithCorrectErrorMessage() {
			// Use a VariableOperand referencing 'this' which
			// resolves to null in an empty context -- null is not
			// an instance of Comparable, triggering the error.
			final GreaterThanOperator op =
				new GreaterThanOperator(
					new VariableOperand(null), num(5)
				);

			final ExpressionEvaluationException exception = assertThrows(
				ExpressionEvaluationException.class,
				() -> op.compute(CONTEXT)
			);
			assertEquals(
				"Greater than function operand " +
					"must be comparable!",
				exception.getMessage()
			);
		}

		@Test
		@DisplayName("should compute from range with epsilon offset")
		void shouldComputeFromRangeWithEpsilon() {
			final GreaterThanOperator op =
				new GreaterThanOperator(num(10), num(5));

			final BigDecimalNumberRange range =
				op.determinePossibleRange();

			assertEquals(
				BigDecimalNumberRange.from(
					new BigDecimal("5.0000000000000001")
				),
				range
			);
		}

		@Test
		@DisplayName("should format as 'left > right'")
		void shouldFormatToString() {
			final GreaterThanOperator op =
				new GreaterThanOperator(num(10), num(5));

			assertEquals("10 > 5", op.toString());
		}
	}

	@Nested
	@DisplayName("Greater than or equals")
	class GreaterThanEqualsOperatorTests {

		@Test
		@DisplayName("should return true when left is greater")
		void shouldReturnTrueWhenLeftGreater() {
			final GreaterThanEqualsOperator op =
				new GreaterThanEqualsOperator(num(10), num(5));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(true, result);
		}

		@Test
		@DisplayName("should return true when operands are equal")
		void shouldReturnTrueWhenEqual() {
			final GreaterThanEqualsOperator op =
				new GreaterThanEqualsOperator(num(5), num(5));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(true, result);
		}

		@Test
		@DisplayName("should return false when left is smaller")
		void shouldReturnFalseWhenLeftSmaller() {
			final GreaterThanEqualsOperator op =
				new GreaterThanEqualsOperator(num(4), num(5));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(false, result);
		}

		@Test
		@DisplayName("should compute from range without epsilon")
		void shouldComputeFromRangeWithoutEpsilon() {
			final GreaterThanEqualsOperator op =
				new GreaterThanEqualsOperator(num(10), num(5));

			final BigDecimalNumberRange range =
				op.determinePossibleRange();

			assertEquals(
				BigDecimalNumberRange.from(new BigDecimal("5")),
				range
			);
		}

		@Test
		@DisplayName("should format as 'left >= right'")
		void shouldFormatToString() {
			final GreaterThanEqualsOperator op =
				new GreaterThanEqualsOperator(num(10), num(5));

			assertEquals("10 >= 5", op.toString());
		}
	}

	@Nested
	@DisplayName("Lesser than")
	class LesserThanOperatorTests {

		@Test
		@DisplayName("should return true when left is smaller")
		void shouldReturnTrueWhenLeftSmaller() {
			final LesserThanOperator op =
				new LesserThanOperator(num(5), num(10));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(true, result);
		}

		@Test
		@DisplayName("should return false when left is greater")
		void shouldReturnFalseWhenLeftGreater() {
			final LesserThanOperator op =
				new LesserThanOperator(num(10), num(5));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(false, result);
		}

		@Test
		@DisplayName("should return false when operands are equal")
		void shouldReturnFalseWhenEqual() {
			final LesserThanOperator op =
				new LesserThanOperator(num(5), num(5));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(false, result);
		}

		@Test
		@DisplayName("should compute to range with epsilon offset")
		void shouldComputeToRangeWithEpsilon() {
			final LesserThanOperator op =
				new LesserThanOperator(num(5), num(10));

			final BigDecimalNumberRange range =
				op.determinePossibleRange();

			assertEquals(
				BigDecimalNumberRange.to(
					new BigDecimal("4.9999999999999999")
				),
				range
			);
		}

		@Test
		@DisplayName("should format as 'left < right'")
		void shouldFormatToString() {
			final LesserThanOperator op =
				new LesserThanOperator(num(5), num(10));

			assertEquals("5 < 10", op.toString());
		}
	}

	@Nested
	@DisplayName("Lesser than or equals")
	class LesserThanEqualsOperatorTests {

		@Test
		@DisplayName("should return true when left is smaller")
		void shouldReturnTrueWhenLeftSmaller() {
			final LesserThanEqualsOperator op =
				new LesserThanEqualsOperator(num(5), num(10));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(true, result);
		}

		@Test
		@DisplayName("should return true when operands are equal")
		void shouldReturnTrueWhenEqual() {
			final LesserThanEqualsOperator op =
				new LesserThanEqualsOperator(num(5), num(5));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(true, result);
		}

		@Test
		@DisplayName("should return false when left is greater")
		void shouldReturnFalseWhenLeftGreater() {
			final LesserThanEqualsOperator op =
				new LesserThanEqualsOperator(num(6), num(5));

			final Boolean result = (Boolean) op.compute(CONTEXT);

			assertEquals(false, result);
		}

		@Test
		@DisplayName("should compute to range without epsilon")
		void shouldComputeToRangeWithoutEpsilon() {
			final LesserThanEqualsOperator op =
				new LesserThanEqualsOperator(num(5), num(10));

			final BigDecimalNumberRange range =
				op.determinePossibleRange();

			assertEquals(
				BigDecimalNumberRange.to(new BigDecimal("5")),
				range
			);
		}

		@Test
		@DisplayName("should format as 'left <= right'")
		void shouldFormatToString() {
			final LesserThanEqualsOperator op =
				new LesserThanEqualsOperator(num(5), num(10));

			assertEquals("5 <= 10", op.toString());
		}
	}
}
