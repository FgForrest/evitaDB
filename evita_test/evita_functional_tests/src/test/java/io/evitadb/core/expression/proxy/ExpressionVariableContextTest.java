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

package io.evitadb.core.expression.proxy;

import io.evitadb.dataType.exception.VariableNotDefinedException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExpressionVariableContext} verifying that the evaluation context correctly manages variable bindings,
 * `this` object, and random generator.
 */
@DisplayName("Expression variable context")
class ExpressionVariableContextTest {

	@Test
	@DisplayName("Should return bound value for known variable name")
	void shouldReturnBoundValueForKnownVariable() {
		final Object entityProxy = new Object();
		final ExpressionVariableContext ctx = new ExpressionVariableContext(
			Map.of("entity", entityProxy)
		);

		final Optional<Object> result = ctx.getVariable("entity");

		assertTrue(result.isPresent());
		assertSame(entityProxy, result.get(), "Should return the exact bound object");
	}

	@Test
	@DisplayName("Should throw VariableNotDefinedException when variable is undefined")
	void shouldThrowExceptionWhenVariableUndefined() {
		final ExpressionVariableContext ctx = new ExpressionVariableContext(Map.of("entity", "value"));

		assertThrows(
			VariableNotDefinedException.class,
			() -> ctx.getVariable("unknown"),
			"Should throw for undefined variable"
		);
	}

	@Test
	@DisplayName("Should return all bound variable names")
	void shouldReturnAllBoundVariableNames() {
		final ExpressionVariableContext ctx = new ExpressionVariableContext(
			Map.of("entity", "e", "pageNumber", 42)
		);

		final Set<String> names = ctx.getVariableNames().collect(Collectors.toSet());

		assertEquals(Set.of("entity", "pageNumber"), names);
	}

	@Test
	@DisplayName("Should create new context with different this but same variables when withThis called")
	void shouldCreateNewContextWithDifferentThisWhenWithThisCalled() {
		final Object entityProxy = new Object();
		final ExpressionVariableContext original = new ExpressionVariableContext(
			Map.of("entity", entityProxy)
		);

		assertTrue(original.getThis().isEmpty(), "Original should have no this");

		final Object thisObj = "thisValue";
		final ExpressionEvaluationContext withThis = original.withThis(thisObj);

		assertTrue(withThis.getThis().isPresent());
		assertSame(thisObj, withThis.getThis().get(), "New context should have the this object");

		// original variables still accessible
		assertTrue(withThis.getVariable("entity").isPresent());
		assertSame(entityProxy, withThis.getVariable("entity").get());
	}

	@Test
	@DisplayName("Should return empty this by default")
	void shouldReturnEmptyThisByDefault() {
		final ExpressionVariableContext ctx = new ExpressionVariableContext(Map.of());

		assertTrue(ctx.getThis().isEmpty(), "Default this should be empty");
	}

	@Test
	@DisplayName("Should return consistent non-null random instance")
	void shouldReturnConsistentRandomInstance() {
		final ExpressionVariableContext ctx = new ExpressionVariableContext(Map.of());

		final Random random1 = ctx.getRandom();
		final Random random2 = ctx.getRandom();

		assertNotNull(random1, "Random should not be null");
		assertSame(random1, random2, "Should return the same Random instance");
	}

	@Test
	@DisplayName("Should return empty Optional for null variable value")
	void shouldReturnEmptyOptionalForNullVariableValue() {
		// HashMap allows null values — create a map with a null-valued entry
		final java.util.HashMap<String, Object> variables = new java.util.HashMap<>(4);
		variables.put("entity", null);
		final ExpressionVariableContext ctx = new ExpressionVariableContext(variables);

		final Optional<Object> result = ctx.getVariable("entity");

		assertTrue(result.isEmpty(), "Should return empty Optional when variable value is null");
	}

	@Test
	@DisplayName("Should preserve Random instance across withThis transition")
	void shouldPreserveRandomAcrossWithThisTransition() {
		final ExpressionVariableContext original = new ExpressionVariableContext(Map.of("entity", "value"));
		final Random originalRandom = original.getRandom();

		final ExpressionEvaluationContext withThis = original.withThis("thisObj");
		final Random derivedRandom = withThis.getRandom();

		assertSame(originalRandom, derivedRandom,
			"Random instance should be preserved across withThis transition");
	}
}
