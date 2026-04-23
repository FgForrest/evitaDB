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

package io.evitadb.api.query.order;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.QueryConstraints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.random;
import static io.evitadb.api.query.QueryConstraints.randomWithSeed;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Random} verifying construction, applicability, property accessors,
 * suffix behavior, cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Random constraint")
class RandomTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create seedless instance via factory method")
		void shouldCreateSeedlessInstance() {
			final Random constraint = random();

			assertNotNull(constraint);
			assertNull(constraint.getSeed());
		}

		@Test
		@DisplayName("should create seeded instance via factory method")
		void shouldCreateSeededInstance() {
			final Random constraint = randomWithSeed(42);

			assertNotNull(constraint);
			assertEquals(42L, constraint.getSeed());
		}

		@Test
		@DisplayName("should return singleton INSTANCE for seedless random")
		void shouldReturnSingletonForSeedless() {
			assertSame(random(), random());
			assertSame(Random.INSTANCE, random());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should always be applicable for seedless variant")
		void shouldBeApplicableForSeedless() {
			assertTrue(random().isApplicable());
		}

		@Test
		@DisplayName("should always be applicable for seeded variant")
		void shouldBeApplicableForSeeded() {
			assertTrue(randomWithSeed(42).isApplicable());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorsTest {

		@Test
		@DisplayName("should return null seed for seedless variant")
		void shouldReturnNullSeedForSeedless() {
			assertNull(random().getSeed());
		}

		@Test
		@DisplayName("should return correct seed for seeded variant")
		void shouldReturnCorrectSeed() {
			assertEquals(42L, randomWithSeed(42).getSeed());
		}

		@Test
		@DisplayName("should return different seeds for different instances")
		void shouldReturnDifferentSeeds() {
			assertNotEquals(randomWithSeed(42).getSeed(), randomWithSeed(99).getSeed());
		}
	}

	@Nested
	@DisplayName("Suffix behavior")
	class SuffixTest {

		@Test
		@DisplayName("should return empty suffix for seedless variant")
		void shouldReturnEmptySuffixForSeedless() {
			final Optional<String> suffix = random().getSuffixIfApplied();

			assertTrue(suffix.isEmpty());
		}

		@Test
		@DisplayName("should return 'withSeed' suffix for seeded variant")
		void shouldReturnWithSeedSuffix() {
			final Optional<String> suffix = randomWithSeed(42).getSuffixIfApplied();

			assertTrue(suffix.isPresent());
			assertEquals("withSeed", suffix.get());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldCloneSeededInstance() {
			final Random original = randomWithSeed(42);
			final OrderConstraint clone = original.cloneWithArguments(new Serializable[]{42L});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(Random.class, clone);
		}

		@Test
		@DisplayName("should clone seedless instance")
		void shouldCloneSeedlessInstance() {
			final Random original = random();
			final OrderConstraint clone = original.cloneWithArguments(new Serializable[]{});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(Random.class, clone);
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final Random constraint = randomWithSeed(42);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			constraint.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> c) {
					visited.set(c);
				}
			});

			assertSame(constraint, visited.get());
		}

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnCorrectType() {
			assertEquals(OrderConstraint.class, random().getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce 'random()' for seedless variant")
		void shouldToStringForSeedless() {
			assertEquals("random()", random().toString());
		}

		@Test
		@DisplayName("should produce 'randomWithSeed(42)' for seeded variant")
		void shouldToStringForSeeded() {
			assertEquals("randomWithSeed(42)", randomWithSeed(42).toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should be equal for same seedless instances")
		void shouldBeEqualForSeedless() {
			assertSame(random(), random());
			assertEquals(random(), random());
			assertEquals(random().hashCode(), random().hashCode());
		}

		@Test
		@DisplayName("should be equal for same seed values")
		void shouldBeEqualForSameSeeds() {
			assertNotSame(randomWithSeed(42), randomWithSeed(42));
			assertEquals(randomWithSeed(42), randomWithSeed(42));
			assertEquals(randomWithSeed(42).hashCode(), randomWithSeed(42).hashCode());
		}

		@Test
		@DisplayName("should not be equal for different seed values")
		void shouldNotBeEqualForDifferentSeeds() {
			assertNotEquals(randomWithSeed(42), randomWithSeed(32));
			assertNotEquals(randomWithSeed(42).hashCode(), randomWithSeed(32).hashCode());
		}

		@Test
		@DisplayName("should not be equal for seedless and seeded variants")
		void shouldNotBeEqualForSeedlessAndSeeded() {
			assertNotEquals(random(), randomWithSeed(42));
		}
	}
}
