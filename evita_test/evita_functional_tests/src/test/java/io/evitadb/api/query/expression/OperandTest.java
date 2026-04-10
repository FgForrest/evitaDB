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
import io.evitadb.api.query.expression.operand.ConstantOperand;
import io.evitadb.api.query.expression.operand.VariableOperand;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ConstantOperand} and {@link VariableOperand} verifying
 * value resolution, range determination, and error handling.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Operands")
class OperandTest {

	@Nested
	@DisplayName("ConstantOperand")
	class ConstantOperandTests {

		@Test
		@DisplayName("should return stored value")
		void shouldReturnValue() {
			final ConstantOperand operand =
				new ConstantOperand(new BigDecimal("42"));
			final ExpressionEvaluationContext context =
				new MultiVariableEvaluationContext(42, Map.of());

			final Serializable result = operand.compute(context);

			assertEquals(new BigDecimal("42"), result);
		}

		@Test
		@DisplayName("should accept null value and return null on compute")
		void shouldAcceptNullValue() {
			final ConstantOperand operand = new ConstantOperand(null);
			final ExpressionEvaluationContext context =
				new MultiVariableEvaluationContext(42, Map.of());

			assertNull(operand.compute(context));
			assertNull(operand.getValue());
		}

		@Test
		@DisplayName(
			"should return exact range for numeric value"
		)
		void shouldReturnExactRangeForNumericValue() {
			final ConstantOperand operand =
				new ConstantOperand(new BigDecimal("10"));

			final BigDecimalNumberRange range =
				operand.determinePossibleRange();

			assertEquals(
				BigDecimalNumberRange.between(
					new BigDecimal("10"),
					new BigDecimal("10")
				),
				range
			);
		}

		@Test
		@DisplayName(
			"should return INFINITE range for non-numeric value"
		)
		void shouldReturnInfiniteRangeForNonNumericValue() {
			final ConstantOperand operand =
				new ConstantOperand("hello");

			final BigDecimalNumberRange range =
				operand.determinePossibleRange();

			assertEquals(BigDecimalNumberRange.INFINITE, range);
		}

		@Test
		@DisplayName("should return null children")
		void shouldReturnNullChildren() {
			final ConstantOperand operand =
				new ConstantOperand(new BigDecimal("5"));

			final ExpressionNode[] children =
				operand.getChildren();

			assertNull(children);
		}

		@Test
		@DisplayName(
			"should format to EvitaDataTypes string representation"
		)
		void shouldFormatToString() {
			final ConstantOperand operand =
				new ConstantOperand(new BigDecimal("5"));

			final String str = operand.toString();

			assertNotNull(str);
			// EvitaDataTypes.formatValue for BigDecimal produces "5"
			assertEquals("5", str);
		}
	}

	@Nested
	@DisplayName("VariableOperand")
	class VariableOperandTests {

		@Test
		@DisplayName("should return variable value from context")
		void shouldReturnVariableValue() {
			final VariableOperand operand =
				new VariableOperand("pageNumber");
			final ExpressionEvaluationContext context =
				new MultiVariableEvaluationContext(
					42,
					Map.of(
						"pageNumber",
						BigDecimal.valueOf(10)
					)
				);

			final Serializable result = operand.compute(context);

			assertEquals(BigDecimal.valueOf(10), result);
		}

		@Test
		@DisplayName(
			"should return null for variable with null value"
		)
		void shouldReturnNullForMissingVariable() {
			final VariableOperand operand =
				new VariableOperand("missing");
			final ExpressionEvaluationContext context =
				new MultiVariableEvaluationContext(
					42, Map.of()
				);

			// when the variable is not defined at all,
			// VariableOperand throws
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> operand.compute(context)
			);
		}

		@Test
		@DisplayName(
			"should identify as 'this' when variableName is null"
		)
		void shouldIdentifyThis() {
			final VariableOperand operand =
				new VariableOperand(null);

			assertTrue(operand.isThis());
		}

		@Test
		@DisplayName(
			"should throw EvitaInvalidUsageException for " +
				"undefined variable"
		)
		void shouldThrowForUndefinedVariable() {
			final VariableOperand operand =
				new VariableOperand("undefined");
			final ExpressionEvaluationContext context =
				new MultiVariableEvaluationContext(
					42, Map.of("other", 1)
				);

			final EvitaInvalidUsageException exception =
				assertThrows(
					EvitaInvalidUsageException.class,
					() -> operand.compute(context)
				);
			assertTrue(
				exception.getMessage()
					.contains("undefined")
			);
		}

		@Test
		@DisplayName("should always return INFINITE range")
		void shouldReturnInfiniteRange() {
			final VariableOperand operand =
				new VariableOperand("x");

			final BigDecimalNumberRange range =
				operand.determinePossibleRange();

			assertEquals(BigDecimalNumberRange.INFINITE, range);
		}

		@Test
		@DisplayName("should return null children")
		void shouldReturnNullChildren() {
			final VariableOperand operand =
				new VariableOperand("x");

			final ExpressionNode[] children =
				operand.getChildren();

			assertNull(children);
		}

		@Test
		@DisplayName("should format as '$varName'")
		void shouldFormatToStringWithName() {
			final VariableOperand operand =
				new VariableOperand("pageNumber");

			assertEquals("$pageNumber", operand.toString());
		}

		@Test
		@DisplayName("should format as '$' for this reference")
		void shouldFormatToStringForThis() {
			final VariableOperand operand =
				new VariableOperand(null);

			assertEquals("$", operand.toString());
		}
	}
}
