/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.mutation;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MutationPredicate} AND/OR composition and context access.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("MutationPredicate")
class MutationPredicateTest implements EvitaTestSupport {

	/**
	 * Simple test implementation of {@link MutationPredicate} with a configurable boolean result.
	 */
	private static class TestMutationPredicate extends MutationPredicate {
		private final boolean result;
		private int callCount = 0;

		TestMutationPredicate(@Nonnull MutationPredicateContext context, boolean result) {
			super(context);
			this.result = result;
		}

		@Override
		public boolean test(Mutation mutation) {
			this.callCount++;
			return this.result;
		}

		int getCallCount() {
			return this.callCount;
		}
	}

	@Nested
	@DisplayName("AND composition")
	class AndComposition {

		@Test
		@DisplayName("should return true when both predicates match")
		void shouldReturnTrueWhenBothMatch() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			final TestMutationPredicate first = new TestMutationPredicate(context, true);
			final TestMutationPredicate second = new TestMutationPredicate(context, true);

			final MutationPredicate combined = first.and(second);

			assertTrue(combined.test(null));
		}

		@Test
		@DisplayName("should return false when first predicate does not match")
		void shouldReturnFalseWhenFirstDoesNotMatch() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			final TestMutationPredicate first = new TestMutationPredicate(context, false);
			final TestMutationPredicate second = new TestMutationPredicate(context, true);

			final MutationPredicate combined = first.and(second);

			assertFalse(combined.test(null));
		}

		@Test
		@DisplayName("should return false when second predicate does not match")
		void shouldReturnFalseWhenSecondDoesNotMatch() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			final TestMutationPredicate first = new TestMutationPredicate(context, true);
			final TestMutationPredicate second = new TestMutationPredicate(context, false);

			final MutationPredicate combined = first.and(second);

			assertFalse(combined.test(null));
		}

		@Test
		@DisplayName("should short-circuit when first predicate is false")
		void shouldShortCircuitWhenFirstIsFalse() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			final TestMutationPredicate first = new TestMutationPredicate(context, false);
			final TestMutationPredicate second = new TestMutationPredicate(context, true);

			final MutationPredicate combined = first.and(second);
			combined.test(null);

			assertEquals(1, first.getCallCount());
			assertEquals(0, second.getCallCount(), "Second predicate should not be evaluated");
		}

		@Test
		@DisplayName("should reject different contexts")
		void shouldRejectDifferentContexts() {
			final MutationPredicateContext context1 = new MutationPredicateContext(StreamDirection.FORWARD);
			final MutationPredicateContext context2 = new MutationPredicateContext(StreamDirection.REVERSE);
			final TestMutationPredicate first = new TestMutationPredicate(context1, true);
			final TestMutationPredicate second = new TestMutationPredicate(context2, true);

			assertThrows(GenericEvitaInternalError.class, () -> first.and(second));
		}

		@Test
		@DisplayName("should share context between composed predicates")
		void shouldShareContextBetweenComposedPredicates() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			final TestMutationPredicate first = new TestMutationPredicate(context, true);
			final TestMutationPredicate second = new TestMutationPredicate(context, true);

			final MutationPredicate combined = first.and(second);

			assertSame(context, combined.getContext());
		}

		@Test
		@DisplayName("should reject null predicate")
		void shouldRejectNullPredicate() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			final TestMutationPredicate first = new TestMutationPredicate(context, true);

			assertThrows(NullPointerException.class, () -> first.and(null));
		}
	}

	@Nested
	@DisplayName("OR composition")
	class OrComposition {

		@Test
		@DisplayName("should return true when first predicate matches")
		void shouldReturnTrueWhenFirstMatches() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			final TestMutationPredicate first = new TestMutationPredicate(context, true);
			final TestMutationPredicate second = new TestMutationPredicate(context, false);

			final MutationPredicate combined = MutationPredicate.or(first, second);

			assertTrue(combined.test(null));
		}

		@Test
		@DisplayName("should return true when second predicate matches")
		void shouldReturnTrueWhenSecondMatches() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			final TestMutationPredicate first = new TestMutationPredicate(context, false);
			final TestMutationPredicate second = new TestMutationPredicate(context, true);

			final MutationPredicate combined = MutationPredicate.or(first, second);

			assertTrue(combined.test(null));
		}

		@Test
		@DisplayName("should return false when no predicate matches")
		void shouldReturnFalseWhenNoneMatches() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			final TestMutationPredicate first = new TestMutationPredicate(context, false);
			final TestMutationPredicate second = new TestMutationPredicate(context, false);

			final MutationPredicate combined = MutationPredicate.or(first, second);

			assertFalse(combined.test(null));
		}

		@Test
		@DisplayName("should short-circuit when first predicate is true")
		void shouldShortCircuitWhenFirstIsTrue() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			final TestMutationPredicate first = new TestMutationPredicate(context, true);
			final TestMutationPredicate second = new TestMutationPredicate(context, false);

			final MutationPredicate combined = MutationPredicate.or(first, second);
			combined.test(null);

			assertEquals(1, first.getCallCount());
			assertEquals(0, second.getCallCount(), "Second predicate should not be evaluated");
		}

		@Test
		@DisplayName("should handle three predicates")
		void shouldHandleThreePredicates() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			final TestMutationPredicate first = new TestMutationPredicate(context, false);
			final TestMutationPredicate second = new TestMutationPredicate(context, false);
			final TestMutationPredicate third = new TestMutationPredicate(context, true);

			final MutationPredicate combined = MutationPredicate.or(first, second, third);

			assertTrue(combined.test(null));
			assertEquals(1, first.getCallCount());
			assertEquals(1, second.getCallCount());
			assertEquals(1, third.getCallCount());
		}

		@Test
		@DisplayName("should reject different contexts among predicates")
		void shouldRejectDifferentContexts() {
			final MutationPredicateContext context1 = new MutationPredicateContext(StreamDirection.FORWARD);
			final MutationPredicateContext context2 = new MutationPredicateContext(StreamDirection.REVERSE);
			final TestMutationPredicate first = new TestMutationPredicate(context1, true);
			final TestMutationPredicate second = new TestMutationPredicate(context2, true);

			assertThrows(GenericEvitaInternalError.class, () -> MutationPredicate.or(first, second));
		}

		@Test
		@DisplayName("should throw on empty predicates array")
		void shouldThrowOnEmptyPredicatesArray() {
			assertThrows(
				ArrayIndexOutOfBoundsException.class,
				() -> MutationPredicate.or(new MutationPredicate[0])
			);
		}

		@Test
		@DisplayName("should handle single predicate")
		void shouldHandleSinglePredicate() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			final TestMutationPredicate single = new TestMutationPredicate(context, true);

			final MutationPredicate combined = MutationPredicate.or(new MutationPredicate[]{single});

			assertTrue(combined.test(null));
			assertSame(context, combined.getContext());
		}
	}

	@Nested
	@DisplayName("Context access")
	class ContextAccess {

		@Test
		@DisplayName("should return the shared context from getContext()")
		void shouldReturnSharedContext() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			final TestMutationPredicate predicate = new TestMutationPredicate(context, true);

			assertSame(context, predicate.getContext());
		}

		@Test
		@DisplayName("should use first predicate context in OR composition")
		void shouldUseFirstPredicateContextInOr() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			final TestMutationPredicate first = new TestMutationPredicate(context, true);
			final TestMutationPredicate second = new TestMutationPredicate(context, false);

			final MutationPredicate combined = MutationPredicate.or(first, second);

			assertSame(context, combined.getContext());
		}

		@Test
		@DisplayName("should use first predicate context in AND composition")
		void shouldUseFirstPredicateContextInAnd() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			final TestMutationPredicate first = new TestMutationPredicate(context, true);
			final TestMutationPredicate second = new TestMutationPredicate(context, false);

			final MutationPredicate combined = first.and(second);

			assertSame(context, combined.getContext());
		}
	}
}
