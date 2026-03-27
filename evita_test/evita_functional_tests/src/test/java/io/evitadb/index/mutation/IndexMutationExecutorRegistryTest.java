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

package io.evitadb.index.mutation;

import io.evitadb.core.expression.trigger.DependencyType;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link IndexMutationExecutorRegistry} dispatch correctness — routing, identity preservation,
 * error handling, and multi-executor independence.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("IndexMutationExecutorRegistry")
class IndexMutationExecutorRegistryTest {

	/**
	 * Test mutation type used exclusively for dispatch verification in tests.
	 */
	private record TestMutationA(int id) implements IndexMutation {
	}

	/**
	 * Second test mutation type for multi-executor dispatch verification.
	 */
	private record TestMutationB(int id) implements IndexMutation {
	}

	/**
	 * Counting executor that records all received mutations and targets for assertion.
	 *
	 * @param <M> the concrete mutation type handled
	 */
	private static final class CountingExecutor<M extends IndexMutation> implements IndexMutationExecutor<M> {

		/** List of mutations received by this executor. */
		@Nonnull
		private final List<M> receivedMutations = new ArrayList<>(4);
		/** List of targets received by this executor. */
		@Nonnull
		private final List<IndexMutationTarget> receivedTargets = new ArrayList<>(4);

		@Override
		public void execute(@Nonnull M mutation, @Nonnull IndexMutationTarget target) {
			this.receivedMutations.add(mutation);
			this.receivedTargets.add(target);
		}

	}

	@Test
	@DisplayName("Should route to correct executor for registered mutation")
	void shouldRouteToCorrectExecutorForRegisteredMutation() {
		final CountingExecutor<TestMutationA> executor = new CountingExecutor<>();
		final IndexMutationExecutorRegistry registry = new IndexMutationExecutorRegistry(
			Map.of(TestMutationA.class, executor)
		);
		final IndexMutationTarget target = Mockito.mock(IndexMutationTarget.class);

		registry.dispatch(new TestMutationA(1), target);

		assertEquals(1, executor.receivedMutations.size());
	}

	@Test
	@DisplayName("Should pass exact mutation instance to executor (identity)")
	void shouldPassExactMutationInstanceToExecutor() {
		final CountingExecutor<TestMutationA> executor = new CountingExecutor<>();
		final IndexMutationExecutorRegistry registry = new IndexMutationExecutorRegistry(
			Map.of(TestMutationA.class, executor)
		);
		final TestMutationA mutation = new TestMutationA(42);
		final IndexMutationTarget target = Mockito.mock(IndexMutationTarget.class);

		registry.dispatch(mutation, target);

		assertSame(mutation, executor.receivedMutations.get(0));
	}

	@Test
	@DisplayName("Should pass exact target instance to executor (identity)")
	void shouldPassExactTargetInstanceToExecutor() {
		final CountingExecutor<TestMutationA> executor = new CountingExecutor<>();
		final IndexMutationExecutorRegistry registry = new IndexMutationExecutorRegistry(
			Map.of(TestMutationA.class, executor)
		);
		final IndexMutationTarget target = Mockito.mock(IndexMutationTarget.class);

		registry.dispatch(new TestMutationA(1), target);

		assertSame(target, executor.receivedTargets.get(0));
	}

	@Test
	@DisplayName("Should throw for unregistered mutation type")
	void shouldThrowForUnregisteredMutationType() {
		final IndexMutationExecutorRegistry registry = new IndexMutationExecutorRegistry(Map.of());
		final IndexMutationTarget target = Mockito.mock(IndexMutationTarget.class);
		final TestMutationA unregistered = new TestMutationA(1);

		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class,
			() -> registry.dispatch(unregistered, target)
		);
		assertTrue(
			exception.getMessage().contains(TestMutationA.class.getName()),
			"Exception message should contain the unregistered mutation class name, "
				+ "but was: " + exception.getMessage()
		);
	}

	@Test
	@DisplayName("Should handle multiple registered mutation types independently")
	void shouldHandleMultipleRegisteredMutationTypes() {
		final CountingExecutor<TestMutationA> executorA = new CountingExecutor<>();
		final CountingExecutor<TestMutationB> executorB = new CountingExecutor<>();
		final IndexMutationExecutorRegistry registry = new IndexMutationExecutorRegistry(
			Map.of(
				TestMutationA.class, executorA,
				TestMutationB.class, executorB
			)
		);
		final IndexMutationTarget target = Mockito.mock(IndexMutationTarget.class);

		registry.dispatch(new TestMutationA(1), target);
		registry.dispatch(new TestMutationB(2), target);

		assertEquals(1, executorA.receivedMutations.size());
		assertEquals(1, executorB.receivedMutations.size());
		assertEquals(1, executorA.receivedMutations.get(0).id());
		assertEquals(2, executorB.receivedMutations.get(0).id());
	}

	/**
	 * Verifies that the `INSTANCE` singleton contains an executor mapping for
	 * {@link ReevaluateExpressionMutation}. Dispatching a minimal mutation with a mock target that
	 * returns `null` indexes causes the executor to short-circuit (no affected entities), proving the
	 * mapping is present without requiring a fully wired collection. This guards against accidental
	 * removal of the registration entry.
	 *
	 * Uses {@link DependencyType#GROUP_ENTITY_ATTRIBUTE} because its resolution path calls
	 * {@code target.getIndexIfExists(...)} first (which returns {@code null} from the mock),
	 * causing an immediate short-circuit without requiring a wired entity schema.
	 */
	@Test
	@DisplayName("INSTANCE singleton should contain ReevaluateExpressionMutation executor")
	void shouldContainReevaluateExpressionMutationEntry() {
		final ReevaluateExpressionMutation mutation = new ReevaluateExpressionMutation(
			"testRef", 1, DependencyType.GROUP_ENTITY_ATTRIBUTE, Scope.DEFAULT_SCOPE
		);
		final IndexMutationTarget target = Mockito.mock(IndexMutationTarget.class);

		// dispatch must not throw — the executor is registered and the null-returning mock target
		// causes it to short-circuit on empty affected-entity resolution
		assertDoesNotThrow(
			() -> IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, target)
		);
	}

}
