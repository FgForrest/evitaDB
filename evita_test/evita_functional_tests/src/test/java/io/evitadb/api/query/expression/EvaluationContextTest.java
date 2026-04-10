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
import io.evitadb.api.query.expression.evaluate.PossibleRange;
import io.evitadb.api.query.expression.evaluate.SingleVariableEvaluationContext;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.VariableNotDefinedException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for evaluation context implementations and the
 * {@link PossibleRange} utility class verifying variable resolution,
 * randomness seeding, and range combination/transformation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evaluation contexts")
class EvaluationContextTest {

	@Nested
	@DisplayName("MultiVariableEvaluationContext")
	class MultiVariableEvaluationContextTests {

		@Test
		@DisplayName("should return variable value by name")
		void shouldReturnVariable() {
			final MultiVariableEvaluationContext context =
				new MultiVariableEvaluationContext(
					42,
					Map.of("x", BigDecimal.TEN)
				);

			final Optional<Object> value =
				context.getVariable("x");

			assertTrue(value.isPresent());
			assertEquals(BigDecimal.TEN, value.get());
		}

		@Test
		@DisplayName(
			"should throw VariableNotDefinedException for " +
				"undefined variable"
		)
		void shouldThrowForUndefinedVariable() {
			final MultiVariableEvaluationContext context =
				new MultiVariableEvaluationContext(
					42, Map.of("x", 1)
				);

			assertThrows(
				VariableNotDefinedException.class,
				() -> context.getVariable("y")
			);
		}

		@Test
		@DisplayName("should return variable names")
		void shouldReturnVariableNames() {
			final MultiVariableEvaluationContext context =
				new MultiVariableEvaluationContext(
					42,
					Map.of("a", 1, "b", 2)
				);

			final List<String> names = context.getVariableNames()
				.collect(Collectors.toList());

			assertEquals(2, names.size());
			assertTrue(names.contains("a"));
			assertTrue(names.contains("b"));
		}

		@Test
		@DisplayName(
			"should return deterministic random with seed"
		)
		void shouldReturnRandomWithSeed() {
			final MultiVariableEvaluationContext context1 =
				new MultiVariableEvaluationContext(
					42, Map.of()
				);
			final MultiVariableEvaluationContext context2 =
				new MultiVariableEvaluationContext(
					42, Map.of()
				);

			final Random random1 = context1.getRandom();
			final Random random2 = context2.getRandom();

			assertNotNull(random1);
			assertNotNull(random2);
			// same seed should produce same first value
			assertEquals(
				random1.nextLong(),
				random2.nextLong()
			);
		}

		@Test
		@DisplayName(
			"should create context with 'this' object via withThis"
		)
		void shouldSupportWithThis() {
			final MultiVariableEvaluationContext context =
				new MultiVariableEvaluationContext(
					42, Map.of("x", 1)
				);

			final ExpressionEvaluationContext withThis =
				context.withThis("myThis");

			assertTrue(withThis.getThis().isPresent());
			assertEquals("myThis", withThis.getThis().get());
			// original variables should still be accessible
			assertTrue(
				withThis.getVariable("x").isPresent()
			);
		}

		@Test
		@DisplayName(
			"should return empty 'this' when no this is set"
		)
		void shouldReturnEmptyThisWhenNotSet() {
			final MultiVariableEvaluationContext context =
				new MultiVariableEvaluationContext(
					42, Map.of()
				);

			assertTrue(context.getThis().isEmpty());
		}
	}

	@Nested
	@DisplayName("SingleVariableEvaluationContext")
	class SingleVariableEvaluationContextTests {

		@Test
		@DisplayName("should return single variable value")
		void shouldReturnSingleVariable() {
			final SingleVariableEvaluationContext context =
				new SingleVariableEvaluationContext(
					42, "x", BigDecimal.ONE
				);

			final Optional<Object> value =
				context.getVariable("x");

			assertTrue(value.isPresent());
			assertEquals(BigDecimal.ONE, value.get());
		}

		@Test
		@DisplayName(
			"should throw VariableNotDefinedException for " +
				"other variable name"
		)
		void shouldThrowForOtherVariable() {
			final SingleVariableEvaluationContext context =
				new SingleVariableEvaluationContext(
					42, "x", BigDecimal.ONE
				);

			assertThrows(
				VariableNotDefinedException.class,
				() -> context.getVariable("y")
			);
		}

		@Test
		@DisplayName("should return single variable name")
		void shouldReturnVariableNames() {
			final SingleVariableEvaluationContext context =
				new SingleVariableEvaluationContext(
					42, "myVar", "value"
				);

			final List<String> names = context.getVariableNames()
				.collect(Collectors.toList());

			assertEquals(1, names.size());
			assertEquals("myVar", names.get(0));
		}

		@Test
		@DisplayName(
			"should create context with 'this' via withThis"
		)
		void shouldSupportWithThis() {
			final SingleVariableEvaluationContext context =
				new SingleVariableEvaluationContext(
					42, "x", BigDecimal.ONE
				);

			final ExpressionEvaluationContext withThis =
				context.withThis("myThis");

			assertTrue(withThis.getThis().isPresent());
			assertEquals("myThis", withThis.getThis().get());
			// variable should still be accessible
			assertTrue(
				withThis.getVariable("x").isPresent()
			);
		}
	}

	@Nested
	@DisplayName("PossibleRange utility")
	class PossibleRangeTests {

		@Test
		@DisplayName("should combine two finite ranges")
		void shouldCombineRanges() {
			final BigDecimalNumberRange range1 =
				BigDecimalNumberRange.between(
					new BigDecimal("2"), new BigDecimal("10")
				);
			final BigDecimalNumberRange range2 =
				BigDecimalNumberRange.between(
					new BigDecimal("3"), new BigDecimal("5")
				);

			final BigDecimalNumberRange combined =
				PossibleRange.combine(
					range1, range2, BigDecimal::add
				);

			assertEquals(
				BigDecimalNumberRange.between(
					new BigDecimal("5"), new BigDecimal("15")
				),
				combined
			);
		}

		@Test
		@DisplayName(
			"should return INFINITE when both inputs are INFINITE"
		)
		void shouldReturnInfiniteForInfiniteInputs() {
			final BigDecimalNumberRange combined =
				PossibleRange.combine(
					BigDecimalNumberRange.INFINITE,
					BigDecimalNumberRange.INFINITE,
					BigDecimal::add
				);

			assertEquals(
				BigDecimalNumberRange.INFINITE, combined
			);
		}

		@Test
		@DisplayName("should transform finite range")
		void shouldTransformRange() {
			final BigDecimalNumberRange range =
				BigDecimalNumberRange.between(
					new BigDecimal("4"), new BigDecimal("9")
				);

			// use a monotonic increasing transformation
			// so that from stays <= to
			final BigDecimalNumberRange transformed =
				PossibleRange.transform(
					range,
					bd -> bd.multiply(new BigDecimal("2"))
				);

			assertEquals(
				BigDecimalNumberRange.between(
					new BigDecimal("8"), new BigDecimal("18")
				),
				transformed
			);
		}

		@Test
		@DisplayName(
			"should return INFINITE when transforming INFINITE"
		)
		void shouldReturnInfiniteForInfiniteTransform() {
			final BigDecimalNumberRange transformed =
				PossibleRange.transform(
					BigDecimalNumberRange.INFINITE,
					BigDecimal::negate
				);

			assertEquals(
				BigDecimalNumberRange.INFINITE, transformed
			);
		}

		@Test
		@DisplayName(
			"should return from-only range when to is null"
		)
		void shouldReturnFromOnlyRange() {
			final BigDecimalNumberRange range =
				BigDecimalNumberRange.from(new BigDecimal("5"));

			final BigDecimalNumberRange transformed =
				PossibleRange.transform(
					range,
					bd -> bd.multiply(new BigDecimal("2"))
				);

			assertEquals(
				BigDecimalNumberRange.from(new BigDecimal("10")),
				transformed
			);
		}
	}
}
