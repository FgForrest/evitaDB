/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.RequireConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.debug;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Debug} verifying construction, applicability, getters,
 * clone operations, visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Debug constraint")
class DebugTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with single debug mode")
		void shouldCreateWithSingleDebugMode() {
			final Debug constraint = debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS);

			final EnumSet<DebugMode> modes = constraint.getDebugMode();

			assertEquals(1, modes.size());
			assertTrue(modes.contains(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS));
		}

		@Test
		@DisplayName("should create with multiple debug modes")
		void shouldCreateWithMultipleDebugModes() {
			final Debug constraint = debug(
				DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS,
				DebugMode.VERIFY_POSSIBLE_CACHING_TREES,
				DebugMode.PREFER_PREFETCHING
			);

			final EnumSet<DebugMode> modes = constraint.getDebugMode();

			assertEquals(3, modes.size());
			assertTrue(modes.contains(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS));
			assertTrue(modes.contains(DebugMode.VERIFY_POSSIBLE_CACHING_TREES));
			assertTrue(modes.contains(DebugMode.PREFER_PREFETCHING));
		}

		@Test
		@DisplayName("should return null from factory for null input")
		void shouldReturnNullFromFactoryForNullInput() {
			assertNull(debug((DebugMode[]) null));
		}

		@Test
		@DisplayName("should return null from factory for empty input")
		void shouldReturnNullFromFactoryForEmptyInput() {
			assertNull(debug());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when at least one mode is provided")
		void shouldBeApplicableWithModes() {
			assertTrue(debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS).isApplicable());
			assertTrue(debug(
				DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS,
				DebugMode.PREFER_PREFETCHING
			).isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when no modes provided via constructor")
		void shouldNotBeApplicableWithNoModes() {
			assertFalse(new Debug(new DebugMode[0]).isApplicable());
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return RequireConstraint class as type")
		void shouldReturnRequireConstraintClassAsType() {
			assertEquals(
				RequireConstraint.class,
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS).getType()
			);
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final Debug constraint = debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			constraint.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("Clone operations")
	class CloneTest {

		@Test
		@DisplayName("should clone with new arguments")
		void shouldCloneWithNewArguments() {
			final Debug original = debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS);
			final RequireConstraint cloned = original.cloneWithArguments(
				new Serializable[]{DebugMode.PREFER_PREFETCHING, DebugMode.VERIFY_POSSIBLE_CACHING_TREES}
			);

			assertNotSame(original, cloned);
			assertInstanceOf(Debug.class, cloned);
			final Debug clonedDebug = (Debug) cloned;
			final EnumSet<DebugMode> modes = clonedDebug.getDebugMode();
			assertEquals(2, modes.size());
			assertTrue(modes.contains(DebugMode.PREFER_PREFETCHING));
			assertTrue(modes.contains(DebugMode.VERIFY_POSSIBLE_CACHING_TREES));
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString with single mode")
		void shouldProduceToStringWithSingleMode() {
			assertEquals(
				"debug(VERIFY_ALTERNATIVE_INDEX_RESULTS)",
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS).toString()
			);
		}

		@Test
		@DisplayName("should produce expected toString with multiple modes")
		void shouldProduceToStringWithMultipleModes() {
			assertEquals(
				"debug(VERIFY_ALTERNATIVE_INDEX_RESULTS,PREFER_PREFETCHING)",
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING).toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS),
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS)
			);
			assertEquals(
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS),
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS)
			);
			assertNotEquals(
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS),
				debug(DebugMode.PREFER_PREFETCHING)
			);
			assertNotEquals(
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS),
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
			);
			assertEquals(
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS).hashCode(),
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS).hashCode()
			);
			assertNotEquals(
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS).hashCode(),
				debug(DebugMode.PREFER_PREFETCHING).hashCode()
			);
		}
	}
}
