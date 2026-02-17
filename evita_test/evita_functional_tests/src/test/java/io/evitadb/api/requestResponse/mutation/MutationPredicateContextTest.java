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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MutationPredicateContext} state machine behavior.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("MutationPredicateContext")
class MutationPredicateContextTest implements EvitaTestSupport {

	private static final OffsetDateTime TIMESTAMP = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

	@Nested
	@DisplayName("Version management")
	class VersionManagement {

		@Test
		@DisplayName("should initialize with zero version and index")
		void shouldInitializeWithDefaults() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);

			assertEquals(0L, context.getVersion());
			assertEquals(0, context.getIndex());
			assertNull(context.getEntityType());
			assertEquals(OptionalInt.empty(), context.getEntityPrimaryKey());
		}

		@Test
		@DisplayName("should set version and reset entity state")
		void shouldSetVersionAndResetEntityState() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			context.setEntityType("Product");
			context.setEntityPrimaryKey(42);

			context.setVersion(5L, 10, TIMESTAMP);

			assertEquals(5L, context.getVersion());
			assertEquals(0, context.getIndex());
			assertNull(context.getEntityType());
			assertEquals(OptionalInt.empty(), context.getEntityPrimaryKey());
			assertEquals(TIMESTAMP, context.getTimestamp());
		}

		@Test
		@DisplayName("should reset index on new version")
		void shouldResetIndexOnNewVersion() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			context.setVersion(1L, 5, TIMESTAMP);
			context.advance();
			context.advance();
			assertEquals(2, context.getIndex());

			context.setVersion(2L, 3, TIMESTAMP);

			assertEquals(0, context.getIndex());
		}
	}

	@Nested
	@DisplayName("Entity type tracking")
	class EntityTypeTracking {

		@Test
		@DisplayName("should set and match entity type")
		void shouldSetAndMatchEntityType() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);

			context.setEntityType("Product");

			assertEquals("Product", context.getEntityType());
			assertTrue(context.matchEntityType("Product"));
			assertFalse(context.matchEntityType("Category"));
		}

		@Test
		@DisplayName("should reset entity type")
		void shouldResetEntityType() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			context.setEntityType("Product");

			context.resetEntityType();

			assertNull(context.getEntityType());
			assertFalse(context.matchEntityType("Product"));
		}

		@Test
		@DisplayName("should reset primary key when setting entity type")
		void shouldResetPrimaryKeyWhenSettingEntityType() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			context.setEntityPrimaryKey(42);

			context.setEntityType("Product");

			assertEquals(OptionalInt.empty(), context.getEntityPrimaryKey());
		}

		@Test
		@DisplayName("should not match when entity type is null")
		void shouldNotMatchWhenEntityTypeIsNull() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);

			assertFalse(context.matchEntityType("Product"));
		}
	}

	@Nested
	@DisplayName("Primary key tracking")
	class PrimaryKeyTracking {

		@Test
		@DisplayName("should set and get entity primary key")
		void shouldSetAndGetEntityPrimaryKey() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);

			context.setEntityPrimaryKey(42);

			assertEquals(OptionalInt.of(42), context.getEntityPrimaryKey());
		}

		@Test
		@DisplayName("should return empty optional when primary key is null")
		void shouldReturnEmptyOptionalWhenNull() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);

			assertEquals(OptionalInt.empty(), context.getEntityPrimaryKey());
		}

		@Test
		@DisplayName("should match primary key")
		void shouldMatchPrimaryKey() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			context.setEntityPrimaryKey(42);

			assertTrue(context.matchPrimaryKey(42));
			assertFalse(context.matchPrimaryKey(99));
		}

		@Test
		@DisplayName("should reset primary key")
		void shouldResetPrimaryKey() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			context.setEntityPrimaryKey(42);

			context.resetPrimaryKey();

			assertEquals(OptionalInt.empty(), context.getEntityPrimaryKey());
		}

		@Test
		@DisplayName("should allow null primary key via setter")
		void shouldAllowNullPrimaryKeyViaSetter() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			context.setEntityPrimaryKey(42);

			context.setEntityPrimaryKey(null);

			assertEquals(OptionalInt.empty(), context.getEntityPrimaryKey());
		}
	}

	@Nested
	@DisplayName("Forward advancement")
	class ForwardAdvancement {

		@Test
		@DisplayName("should increment index on advance")
		void shouldIncrementIndexOnAdvance() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			context.setVersion(1L, 5, TIMESTAMP);

			context.advance();
			assertEquals(1, context.getIndex());

			context.advance();
			assertEquals(2, context.getIndex());
		}

		@Test
		@DisplayName("should throw when advancing past mutation count")
		void shouldThrowWhenAdvancingPastMutationCount() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			context.setVersion(1L, 2, TIMESTAMP);

			context.advance();
			context.advance();

			assertThrows(GenericEvitaInternalError.class, context::advance);
		}

		@Test
		@DisplayName("should allow advancing to exactly mutation count")
		void shouldAllowAdvancingToExactlyMutationCount() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			context.setVersion(1L, 3, TIMESTAMP);

			context.advance();
			context.advance();
			context.advance();

			assertEquals(3, context.getIndex());
		}

		@Test
		@DisplayName("should store direction")
		void shouldStoreDirection() {
			final MutationPredicateContext forward = new MutationPredicateContext(StreamDirection.FORWARD);
			final MutationPredicateContext reverse = new MutationPredicateContext(StreamDirection.REVERSE);

			assertEquals(StreamDirection.FORWARD, forward.getDirection());
			assertEquals(StreamDirection.REVERSE, reverse.getDirection());
		}
	}

	@Nested
	@DisplayName("Reverse advancement")
	class ReverseAdvancement {

		@Test
		@DisplayName("should set index to mutationCount on first reverse advance")
		void shouldSetIndexToMutationCountOnFirstReverseAdvance() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.REVERSE);
			context.setVersion(1L, 5, TIMESTAMP);

			context.advance();

			assertEquals(5, context.getIndex());
		}

		@Test
		@DisplayName("should decrement index on subsequent reverse advances")
		void shouldDecrementOnSubsequentReverseAdvances() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.REVERSE);
			context.setVersion(1L, 5, TIMESTAMP);

			context.advance(); // sets to 5
			context.advance(); // decrements to 4
			assertEquals(4, context.getIndex());

			context.advance(); // decrements to 3
			assertEquals(3, context.getIndex());
		}

		@Test
		@DisplayName("should reset to mutationCount when reaching zero in reverse")
		void shouldResetToMutationCountWhenReachingZeroInReverse() {
			// The reverse advancement resets index to mutationCount when index reaches 0,
			// effectively creating a cycle rather than decrementing below zero
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.REVERSE);
			context.setVersion(1L, 2, TIMESTAMP);

			context.advance(); // index = 2 (initial set from 0)
			context.advance(); // index = 1
			context.advance(); // index = 0

			// at index=0, the next advance resets to mutationCount again
			context.advance(); // index = 2
			assertEquals(2, context.getIndex());
		}
	}

	@Nested
	@DisplayName("DoNotAdvance guard")
	class DoNotAdvanceGuard {

		@Test
		@DisplayName("should suppress advancement during lambda execution")
		void shouldSuppressAdvancementDuringLambda() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			context.setVersion(1L, 5, TIMESTAMP);

			final String result = context.doNotAdvance(() -> {
				context.advance();
				context.advance();
				return "done";
			});

			assertEquals("done", result);
			assertEquals(0, context.getIndex(), "Index should not have changed");
		}

		@Test
		@DisplayName("should restore advancement after lambda execution")
		void shouldRestoreAdvancementAfterLambda() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			context.setVersion(1L, 5, TIMESTAMP);

			context.doNotAdvance(() -> "ignored");
			context.advance();

			assertEquals(1, context.getIndex(), "Advancement should be restored");
		}

		@Test
		@DisplayName("should restore advancement even on exception")
		void shouldRestoreAdvancementOnException() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			context.setVersion(1L, 5, TIMESTAMP);

			assertThrows(RuntimeException.class, () ->
				context.doNotAdvance(() -> {
					throw new RuntimeException("test error");
				})
			);

			// advancement should be restored
			context.advance();
			assertEquals(1, context.getIndex(), "Advancement should be restored after exception");
		}

		@Test
		@DisplayName("should reject nested doNotAdvance calls")
		void shouldRejectNestedDoNotAdvanceCalls() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);

			assertThrows(GenericEvitaInternalError.class, () ->
				context.doNotAdvance(() ->
					context.doNotAdvance(() -> "nested")
				)
			);
		}

		@Test
		@DisplayName("should return lambda result")
		void shouldReturnLambdaResult() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);

			final int result = context.doNotAdvance(() -> 42);

			assertEquals(42, result);
		}
	}
}
