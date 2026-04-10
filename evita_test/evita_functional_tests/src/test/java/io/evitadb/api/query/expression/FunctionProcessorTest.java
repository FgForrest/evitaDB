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
import io.evitadb.api.query.expression.function.FunctionOperator;
import io.evitadb.api.query.expression.function.processor.AbsFunctionProcessor;
import io.evitadb.api.query.expression.function.processor.CeilFunctionProcessor;
import io.evitadb.api.query.expression.function.processor.FloorFunctionProcessor;
import io.evitadb.api.query.expression.function.processor.FunctionProcessorRegistry;
import io.evitadb.api.query.expression.function.processor.LogFunctionProcessor;
import io.evitadb.api.query.expression.function.processor.MaxFunctionProcessor;
import io.evitadb.api.query.expression.function.processor.MinFunctionProcessor;
import io.evitadb.api.query.expression.function.processor.PowFunctionProcessor;
import io.evitadb.api.query.expression.function.processor.RandomFunctionProcessor;
import io.evitadb.api.query.expression.function.processor.RoundFunctionProcessor;
import io.evitadb.api.query.expression.function.processor.SqrtFunctionProcessor;
import io.evitadb.api.query.expression.operand.ConstantOperand;
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
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for all function processors and the {@link FunctionOperator}
 * in the expression language verifying computation, range determination,
 * and error handling.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Function processors")
class FunctionProcessorTest {

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
	@DisplayName("abs function")
	class AbsFunctionProcessorTests {

		@Test
		@DisplayName("should compute absolute value of negative number")
		void shouldComputeAbsOfNegative() {
			final AbsFunctionProcessor processor =
				new AbsFunctionProcessor();

			final Serializable result = processor.process(
				List.of(new BigDecimal("-4"))
			);

			assertEquals(new BigDecimal("4"), result);
		}

		@Test
		@DisplayName("should compute absolute value of positive number")
		void shouldComputeAbsOfPositive() {
			final AbsFunctionProcessor processor =
				new AbsFunctionProcessor();

			final Serializable result = processor.process(
				List.of(new BigDecimal("4"))
			);

			assertEquals(new BigDecimal("4"), result);
		}

		@Test
		@DisplayName("should throw for wrong argument count")
		void shouldThrowForWrongArgCount() {
			final AbsFunctionProcessor processor =
				new AbsFunctionProcessor();

			assertThrows(
				ExpressionEvaluationException.class,
				() -> processor.process(List.of())
			);
		}

		@Test
		@DisplayName("should report name as 'abs'")
		void shouldReportName() {
			final AbsFunctionProcessor processor =
				new AbsFunctionProcessor();

			assertEquals("abs", processor.getName());
		}
	}

	@Nested
	@DisplayName("ceil function")
	class CeilFunctionProcessorTests {

		@Test
		@DisplayName("should compute ceiling of decimal number")
		void shouldComputeCeiling() {
			final CeilFunctionProcessor processor =
				new CeilFunctionProcessor();

			final Serializable result = processor.process(
				List.of(new BigDecimal("2.3"))
			);

			assertEquals(
				new BigDecimal("3").setScale(0, RoundingMode.CEILING),
				result
			);
		}

		@Test
		@DisplayName("should return integer unchanged")
		void shouldReturnIntegerUnchanged() {
			final CeilFunctionProcessor processor =
				new CeilFunctionProcessor();

			final Serializable result = processor.process(
				List.of(new BigDecimal("3"))
			);

			assertEquals(
				new BigDecimal("3").setScale(0, RoundingMode.CEILING),
				result
			);
		}
	}

	@Nested
	@DisplayName("floor function")
	class FloorFunctionProcessorTests {

		@Test
		@DisplayName("should compute floor of decimal number")
		void shouldComputeFloor() {
			final FloorFunctionProcessor processor =
				new FloorFunctionProcessor();

			final Serializable result = processor.process(
				List.of(new BigDecimal("2.7"))
			);

			assertEquals(
				new BigDecimal("2").setScale(0, RoundingMode.FLOOR),
				result
			);
		}

		@Test
		@DisplayName("should return integer unchanged")
		void shouldReturnIntegerUnchanged() {
			final FloorFunctionProcessor processor =
				new FloorFunctionProcessor();

			final Serializable result = processor.process(
				List.of(new BigDecimal("2"))
			);

			assertEquals(
				new BigDecimal("2").setScale(0, RoundingMode.FLOOR),
				result
			);
		}
	}

	@Nested
	@DisplayName("log function")
	class LogFunctionProcessorTests {

		@Test
		@DisplayName("should compute natural log")
		void shouldComputeLog() {
			final LogFunctionProcessor processor =
				new LogFunctionProcessor();

			final BigDecimal result = (BigDecimal) processor.process(
				List.of(new BigDecimal("20"))
			);

			final BigDecimal expected = new BigDecimal(
				Math.log(20), MathContext.DECIMAL64
			);
			assertEquals(
				0,
				expected.compareTo(result)
			);
		}
	}

	@Nested
	@DisplayName("max function")
	class MaxFunctionProcessorTests {

		@Test
		@DisplayName("should return larger of two arguments")
		void shouldReturnMax() {
			final MaxFunctionProcessor processor =
				new MaxFunctionProcessor();

			final Serializable result = processor.process(
				List.of(new BigDecimal("4"), new BigDecimal("8"))
			);

			assertEquals(new BigDecimal("8"), result);
		}

		@Test
		@DisplayName("should throw for single argument")
		void shouldThrowForSingleArg() {
			final MaxFunctionProcessor processor =
				new MaxFunctionProcessor();

			assertThrows(
				ExpressionEvaluationException.class,
				() -> processor.process(
					List.of(new BigDecimal("4"))
				)
			);
		}
	}

	@Nested
	@DisplayName("min function")
	class MinFunctionProcessorTests {

		@Test
		@DisplayName("should return smaller of two arguments")
		void shouldReturnMin() {
			final MinFunctionProcessor processor =
				new MinFunctionProcessor();

			final Serializable result = processor.process(
				List.of(new BigDecimal("4"), new BigDecimal("8"))
			);

			assertEquals(new BigDecimal("4"), result);
		}

		@Test
		@DisplayName("should throw for single argument")
		void shouldThrowForSingleArg() {
			final MinFunctionProcessor processor =
				new MinFunctionProcessor();

			assertThrows(
				ExpressionEvaluationException.class,
				() -> processor.process(
					List.of(new BigDecimal("4"))
				)
			);
		}
	}

	@Nested
	@DisplayName("pow function")
	class PowFunctionProcessorTests {

		@Test
		@DisplayName("should compute power")
		void shouldComputePower() {
			final PowFunctionProcessor processor =
				new PowFunctionProcessor();

			final Serializable result = processor.process(
				List.of(new BigDecimal("2"), new BigDecimal("6"))
			);

			assertEquals(new BigDecimal("64"), result);
		}

		@Test
		@DisplayName("should throw for non-integer exponent")
		void shouldThrowForNonIntExponent() {
			final PowFunctionProcessor processor =
				new PowFunctionProcessor();

			assertThrows(
				ExpressionEvaluationException.class,
				() -> processor.process(
					List.of(
						new BigDecimal("2"),
						new BigDecimal("2.5")
					)
				)
			);
		}
	}

	@Nested
	@DisplayName("random function")
	class RandomFunctionProcessorTests {

		@Test
		@DisplayName(
			"should generate bounded random when called with 1 arg"
		)
		void shouldGenerateBoundedRandom() {
			final RandomFunctionProcessor processor =
				new RandomFunctionProcessor();

			final Long result = (Long) processor.process(
				List.of(new BigDecimal("5"))
			);

			assertNotNull(result);
			assertTrue(result >= 0 && result < 5);
		}

		@Test
		@DisplayName(
			"should generate unbounded random when called with 0 args"
		)
		void shouldGenerateUnboundedRandom() {
			final RandomFunctionProcessor processor =
				new RandomFunctionProcessor();

			final Long result = (Long) processor.process(
				Collections.emptyList()
			);

			assertNotNull(result);
		}

		@Test
		@DisplayName("should return INFINITE range")
		void shouldReturnInfiniteRange() {
			final RandomFunctionProcessor processor =
				new RandomFunctionProcessor();

			final BigDecimalNumberRange range =
				processor.determinePossibleRange(List.of());

			assertEquals(BigDecimalNumberRange.INFINITE, range);
		}
	}

	@Nested
	@DisplayName("round function")
	class RoundFunctionProcessorTests {

		@Test
		@DisplayName("should round 2.4 down to 2")
		void shouldRoundDown() {
			final RoundFunctionProcessor processor =
				new RoundFunctionProcessor();

			final Serializable result = processor.process(
				List.of(new BigDecimal("2.4"))
			);

			assertEquals(new BigDecimal("2"), result);
		}

		@Test
		@DisplayName("should round 2.5 up to 3")
		void shouldRoundUp() {
			final RoundFunctionProcessor processor =
				new RoundFunctionProcessor();

			final Serializable result = processor.process(
				List.of(new BigDecimal("2.5"))
			);

			assertEquals(new BigDecimal("3"), result);
		}
	}

	@Nested
	@DisplayName("sqrt function")
	class SqrtFunctionProcessorTests {

		@Test
		@DisplayName("should compute square root")
		void shouldComputeSqrt() {
			final SqrtFunctionProcessor processor =
				new SqrtFunctionProcessor();

			final BigDecimal result = (BigDecimal) processor.process(
				List.of(new BigDecimal("16"))
			);

			assertEquals(
				0,
				new BigDecimal("4")
					.compareTo(result)
			);
		}

		@Test
		@DisplayName("should throw for wrong argument count")
		void shouldThrowForWrongArgCount() {
			final SqrtFunctionProcessor processor =
				new SqrtFunctionProcessor();

			assertThrows(
				ExpressionEvaluationException.class,
				() -> processor.process(List.of())
			);
		}
	}

	@Nested
	@DisplayName("FunctionProcessorRegistry")
	class FunctionProcessorRegistryTests {

		@Test
		@DisplayName(
			"should return same singleton instance on repeated calls"
		)
		void shouldReturnSameSingletonInstance() {
			final FunctionProcessorRegistry first =
				FunctionProcessorRegistry.getInstance();
			final FunctionProcessorRegistry second =
				FunctionProcessorRegistry.getInstance();

			assertSame(first, second);
		}

		@Test
		@DisplayName("should find processor by name")
		void shouldFindProcessorByName() {
			final FunctionProcessorRegistry registry =
				FunctionProcessorRegistry.getInstance();

			assertTrue(
				registry.getFunctionProcessor("abs").isPresent()
			);
			assertTrue(
				registry.getFunctionProcessor("sqrt").isPresent()
			);
			assertTrue(
				registry.getFunctionProcessor("round").isPresent()
			);
		}

		@Test
		@DisplayName("should return empty for unknown function name")
		void shouldReturnEmptyForUnknown() {
			final FunctionProcessorRegistry registry =
				FunctionProcessorRegistry.getInstance();

			assertTrue(
				registry.getFunctionProcessor("nonExistent")
					.isEmpty()
			);
		}
	}

	@Nested
	@DisplayName("FunctionOperator")
	class FunctionOperatorTests {

		@Test
		@DisplayName("should delegate computation to processor")
		void shouldDelegateComputation() {
			final FunctionOperator op = new FunctionOperator(
				new AbsFunctionProcessor(),
				List.of(num(-4))
			);

			final BigDecimal result =
				op.compute(CONTEXT, BigDecimal.class);

			assertEquals(new BigDecimal("4"), result);
		}

		@Test
		@DisplayName("should format as 'name(arg1, arg2)'")
		void shouldFormatToString() {
			final FunctionOperator op = new FunctionOperator(
				new MaxFunctionProcessor(),
				List.of(num(4), num(8))
			);

			assertEquals("max(4, 8)", op.toString());
		}

		@Test
		@DisplayName(
			"should delegate range to numeric function processor"
		)
		void shouldDelegateRange() {
			final FunctionOperator op = new FunctionOperator(
				new AbsFunctionProcessor(),
				List.of(num(-4))
			);

			final BigDecimalNumberRange range =
				op.determinePossibleRange();

			assertNotNull(range);
		}

		@Test
		@DisplayName("should have children matching argument operands")
		void shouldHaveChildren() {
			final FunctionOperator op = new FunctionOperator(
				new MaxFunctionProcessor(),
				List.of(num(4), num(8))
			);

			final ExpressionNode[] children = op.getChildren();

			assertNotNull(children);
			assertEquals(2, children.length);
		}
	}
}
