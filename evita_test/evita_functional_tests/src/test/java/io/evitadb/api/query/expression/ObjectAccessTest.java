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

import io.evitadb.api.query.expression.coalesce.NullCoalesceOperator;
import io.evitadb.api.query.expression.coalesce.SpreadNullCoalesceOperator;
import io.evitadb.api.query.expression.evaluate.MultiVariableEvaluationContext;
import io.evitadb.api.query.expression.exception.ParserException;
import io.evitadb.api.query.expression.object.ElementAccessStep;
import io.evitadb.api.query.expression.object.NullSafeAccessStep;
import io.evitadb.api.query.expression.object.ObjectAccessOperator;
import io.evitadb.api.query.expression.object.PropertyAccessStep;
import io.evitadb.api.query.expression.object.SpreadAccessStep;
import io.evitadb.api.query.expression.operand.ConstantOperand;
import io.evitadb.api.query.expression.operand.VariableOperand;
import io.evitadb.api.query.expression.utility.NestedOperator;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.ExpressionEvaluationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for object access operators, access steps, null coalescing, and
 * the nested operator verifying property/element resolution and null safety.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Object access and utility operators")
class ObjectAccessTest {

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
	 * Creates a {@link ConstantOperand} wrapping the given string.
	 */
	@Nonnull
	private static ExpressionNode str(@Nonnull String value) {
		return new ConstantOperand(value);
	}

	@Nested
	@DisplayName("ObjectAccessOperator")
	class ObjectAccessOperatorTests {

		@Test
		@DisplayName(
			"should return INFINITE range from operand"
		)
		void shouldReturnInfiniteRange() {
			final ObjectAccessOperator op =
				new ObjectAccessOperator(
					new VariableOperand("obj"),
					new PropertyAccessStep("prop", null)
				);

			final BigDecimalNumberRange range =
				op.determinePossibleRange();

			assertEquals(BigDecimalNumberRange.INFINITE, range);
		}

		@Test
		@DisplayName("should format as 'operand.chain'")
		void shouldFormatToString() {
			final ObjectAccessOperator op =
				new ObjectAccessOperator(
					new VariableOperand("obj"),
					new PropertyAccessStep("prop", null)
				);

			assertEquals("$obj.prop", op.toString());
		}

		@Test
		@DisplayName("should have children containing operand")
		void shouldHaveChildren() {
			final ObjectAccessOperator op =
				new ObjectAccessOperator(
					new VariableOperand("obj"),
					new PropertyAccessStep("prop", null)
				);

			final ExpressionNode[] children = op.getChildren();

			assertNotNull(children);
			assertEquals(1, children.length);
		}
	}

	@Nested
	@DisplayName("PropertyAccessStep")
	class PropertyAccessStepTests {

		@Test
		@DisplayName(
			"should throw when operand is null"
		)
		void shouldThrowWhenOperandIsNull() {
			final PropertyAccessStep step =
				new PropertyAccessStep("name", null);

			assertThrows(
				ExpressionEvaluationException.class,
				() -> step.compute(CONTEXT, null)
			);
		}

		@Test
		@DisplayName("should return property identifier")
		void shouldReturnPropertyIdentifier() {
			final PropertyAccessStep step =
				new PropertyAccessStep("name", null);

			assertEquals("name", step.getPropertyIdentifier());
		}

		@Test
		@DisplayName("should return null next step when terminal")
		void shouldReturnNullNextWhenTerminal() {
			final PropertyAccessStep step =
				new PropertyAccessStep("name", null);

			assertNull(step.getNext());
		}

		@Test
		@DisplayName("should format as '.propertyName'")
		void shouldFormatToString() {
			final PropertyAccessStep step =
				new PropertyAccessStep("name", null);

			assertEquals(".name", step.toString());
		}

		@Test
		@DisplayName(
			"should format chained steps correctly"
		)
		void shouldFormatChainedSteps() {
			final PropertyAccessStep inner =
				new PropertyAccessStep("inner", null);
			final PropertyAccessStep outer =
				new PropertyAccessStep("outer", inner);

			assertEquals(".outer.inner", outer.toString());
		}
	}

	@Nested
	@DisplayName("ElementAccessStep")
	class ElementAccessStepTests {

		@Test
		@DisplayName(
			"should throw when operand is null"
		)
		void shouldThrowWhenOperandIsNull() {
			final ElementAccessStep step =
				new ElementAccessStep(
					new ConstantOperand(0L), null
				);

			assertThrows(
				ExpressionEvaluationException.class,
				() -> step.compute(CONTEXT, null)
			);
		}

		@Test
		@DisplayName(
			"should return element identifier operand"
		)
		void shouldReturnElementIdentifier() {
			final ConstantOperand indexOperand =
				new ConstantOperand(0L);
			final ElementAccessStep step =
				new ElementAccessStep(indexOperand, null);

			assertEquals(
				indexOperand,
				step.getElementIdentifierOperand()
			);
		}

		@Test
		@DisplayName("should format as '[identifier]'")
		void shouldFormatToString() {
			final ElementAccessStep step =
				new ElementAccessStep(
					new ConstantOperand(0L), null
				);

			assertEquals("[0]", step.toString());
		}
	}

	@Nested
	@DisplayName("NullSafeAccessStep")
	class NullSafeAccessStepTests {

		@Test
		@DisplayName(
			"should return null when operand is null"
		)
		void shouldReturnNullWhenOperandIsNull() {
			final PropertyAccessStep inner =
				new PropertyAccessStep("name", null);
			final NullSafeAccessStep step =
				new NullSafeAccessStep(inner);

			final Serializable result =
				step.compute(CONTEXT, null);

			assertNull(result);
		}

		@Test
		@DisplayName("should return next step")
		void shouldReturnNextStep() {
			final PropertyAccessStep inner =
				new PropertyAccessStep("name", null);
			final NullSafeAccessStep step =
				new NullSafeAccessStep(inner);

			assertEquals(inner, step.getNext());
		}

		@Test
		@DisplayName("should format as '?next'")
		void shouldFormatToString() {
			final PropertyAccessStep inner =
				new PropertyAccessStep("name", null);
			final NullSafeAccessStep step =
				new NullSafeAccessStep(inner);

			assertEquals("?.name", step.toString());
		}
	}

	@Nested
	@DisplayName("SpreadAccessStep")
	class SpreadAccessStepTests {

		@Test
		@DisplayName(
			"should throw when operand is null"
		)
		void shouldThrowWhenOperandIsNull() {
			final SpreadAccessStep step =
				new SpreadAccessStep(
					new VariableOperand(null),
					false,
					null
				);

			assertThrows(
				ExpressionEvaluationException.class,
				() -> step.compute(CONTEXT, null)
			);
		}

		@Test
		@DisplayName("should format as '.*[expr]'")
		void shouldFormatToString() {
			final SpreadAccessStep step =
				new SpreadAccessStep(
					new VariableOperand(null),
					false,
					null
				);

			assertEquals(".*[$]", step.toString());
		}

		@Test
		@DisplayName(
			"should format compact variant as '.*![expr]'"
		)
		void shouldFormatCompactToString() {
			final SpreadAccessStep step =
				new SpreadAccessStep(
					new VariableOperand(null),
					true,
					null
				);

			assertEquals(".*![$]", step.toString());
		}
	}

	@Nested
	@DisplayName("NullCoalesceOperator")
	class NullCoalesceOperatorTests {

		@Test
		@DisplayName(
			"should return value when value is not null"
		)
		void shouldReturnValueWhenNotNull() {
			final NullCoalesceOperator op =
				new NullCoalesceOperator(num(5), num(10));

			final Serializable result = op.compute(CONTEXT);

			assertEquals(new BigDecimal("5"), result);
		}

		@Test
		@DisplayName(
			"should return default when value is null"
		)
		void shouldReturnDefaultWhenValueIsNull() {
			// use a variable operand that resolves to null
			// via 'this' which is empty
			final VariableOperand thisRef =
				new VariableOperand(null);
			final NullCoalesceOperator op =
				new NullCoalesceOperator(thisRef, num(10));

			final Serializable result = op.compute(CONTEXT);

			assertEquals(new BigDecimal("10"), result);
		}

		@Test
		@DisplayName("should have two children")
		void shouldHaveTwoChildren() {
			final NullCoalesceOperator op =
				new NullCoalesceOperator(num(5), num(10));

			final ExpressionNode[] children = op.getChildren();

			assertNotNull(children);
			assertEquals(2, children.length);
		}

		@Test
		@DisplayName("should format as 'value ?? default'")
		void shouldFormatToString() {
			final NullCoalesceOperator op =
				new NullCoalesceOperator(num(5), num(10));

			assertEquals("5 ?? 10", op.toString());
		}
	}

	@Nested
	@DisplayName("SpreadNullCoalesceOperator")
	class SpreadNullCoalesceOperatorTests {

		@Test
		@DisplayName(
			"should throw when value is null and not null-safe"
		)
		void shouldThrowWhenNotNullSafe() {
			final SpreadNullCoalesceOperator op =
				new SpreadNullCoalesceOperator(
					false,
					new VariableOperand(null),
					num(10)
				);

			assertThrows(
				ExpressionEvaluationException.class,
				() -> op.compute(CONTEXT)
			);
		}

		@Test
		@DisplayName(
			"should return null when value is null and null-safe"
		)
		void shouldReturnNullWhenNullSafe() {
			final SpreadNullCoalesceOperator op =
				new SpreadNullCoalesceOperator(
					true,
					new VariableOperand(null),
					num(10)
				);

			final Serializable result = op.compute(CONTEXT);

			assertNull(result);
		}

		@Test
		@DisplayName(
			"should coalesce null elements in a list"
		)
		void shouldCoalesceNullElementsInList() {
			final ExpressionEvaluationContext ctx =
				new MultiVariableEvaluationContext(
					42,
					Map.of(
						"list",
						(Serializable) new java.util.ArrayList<>(
							java.util.Arrays.asList(1, null, 3)
						)
					)
				);
			final SpreadNullCoalesceOperator op =
				new SpreadNullCoalesceOperator(
					false,
					new VariableOperand("list"),
					new ConstantOperand(0L)
				);

			final Serializable result = op.compute(ctx);

			assertNotNull(result);
			assertTrue(result instanceof List<?>);
			final List<?> resultList = (List<?>) result;
			assertEquals(3, resultList.size());
			assertEquals(1, resultList.get(0));
			assertEquals(0L, resultList.get(1));
			assertEquals(3, resultList.get(2));
		}

		@Test
		@DisplayName("should format as 'value *? default'")
		void shouldFormatToString() {
			final SpreadNullCoalesceOperator op =
				new SpreadNullCoalesceOperator(
					false,
					new VariableOperand("x"),
					num(10)
				);

			assertEquals("$x *? 10", op.toString());
		}

		@Test
		@DisplayName(
			"should format null-safe as 'value ?*? default'"
		)
		void shouldFormatNullSafeToString() {
			final SpreadNullCoalesceOperator op =
				new SpreadNullCoalesceOperator(
					true,
					new VariableOperand("x"),
					num(10)
				);

			assertEquals("$x ?*? 10", op.toString());
		}
	}

	@Nested
	@DisplayName("NestedOperator")
	class NestedOperatorTests {

		@Test
		@DisplayName("should delegate computation to wrapped operator")
		void shouldDelegateComputation() {
			final NestedOperator op =
				new NestedOperator(num(42));

			final Serializable result = op.compute(CONTEXT);

			assertEquals(new BigDecimal("42"), result);
		}

		@Test
		@DisplayName("should delegate range to wrapped operator")
		void shouldDelegateRange() {
			final NestedOperator op =
				new NestedOperator(num(42));

			final BigDecimalNumberRange range =
				op.determinePossibleRange();

			assertEquals(
				BigDecimalNumberRange.between(
					new BigDecimal("42"),
					new BigDecimal("42")
				),
				range
			);
		}

		@Test
		@DisplayName(
			"should throw for null operand with correct message"
		)
		void shouldThrowForNullOperandWithCorrectMessage() {
			final ParserException exception = assertThrows(
				ParserException.class,
				() -> new NestedOperator(null)
			);
			assertEquals(
				"Nested operator must have at least one operand!",
				exception.getMessage()
			);
		}

		@Test
		@DisplayName("should have single child")
		void shouldHaveSingleChild() {
			final NestedOperator op =
				new NestedOperator(num(42));

			final ExpressionNode[] children = op.getChildren();

			assertNotNull(children);
			assertEquals(1, children.length);
		}

		@Test
		@DisplayName("should format as '(operand)'")
		void shouldFormatToString() {
			final NestedOperator op =
				new NestedOperator(num(42));

			assertEquals("(42)", op.toString());
		}
	}
}
