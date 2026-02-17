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
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.defaultAccompanyingPriceLists;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DefaultAccompanyingPriceLists} verifying construction, applicability, getters,
 * clone operations, visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("DefaultAccompanyingPriceLists constraint")
class DefaultAccompanyingPriceListsTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with price list names")
		void shouldCreateWithPriceListNames() {
			final DefaultAccompanyingPriceLists constraint = defaultAccompanyingPriceLists("basic", "reference");

			assertArrayEquals(new String[]{"basic", "reference"}, constraint.getPriceLists());
		}

		@Test
		@DisplayName("should filter null values from array")
		void shouldFilterNullValuesFromArray() {
			final DefaultAccompanyingPriceLists constraint = defaultAccompanyingPriceLists(
				"basic", null, "reference"
			);

			assertArrayEquals(new String[]{"basic", "reference"}, constraint.getPriceLists());
		}

		@Test
		@DisplayName("should return null from factory for null variable")
		void shouldReturnNullFromFactoryForNullVariable() {
			final String nullString = null;

			assertNull(defaultAccompanyingPriceLists(nullString));
		}

		@Test
		@DisplayName("should return null from factory for empty array")
		void shouldReturnNullFromFactoryForEmptyArray() {
			assertNull(defaultAccompanyingPriceLists(ArrayUtils.EMPTY_STRING_ARRAY));
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when created with price lists")
		void shouldBeApplicableWithPriceLists() {
			assertTrue(new DefaultAccompanyingPriceLists(new String[0]).isApplicable());
			assertTrue(defaultAccompanyingPriceLists("A").isApplicable());
			assertTrue(defaultAccompanyingPriceLists("A", "B").isApplicable());
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
				defaultAccompanyingPriceLists("basic").getType()
			);
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final DefaultAccompanyingPriceLists constraint = defaultAccompanyingPriceLists("basic", "reference");
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
			final DefaultAccompanyingPriceLists original = defaultAccompanyingPriceLists("basic", "reference");
			final RequireConstraint cloned = original.cloneWithArguments(
				new Serializable[]{"action", "vip"}
			);

			assertNotSame(original, cloned);
			assertInstanceOf(DefaultAccompanyingPriceLists.class, cloned);
			final DefaultAccompanyingPriceLists clonedConstraint = (DefaultAccompanyingPriceLists) cloned;
			assertArrayEquals(new String[]{"action", "vip"}, clonedConstraint.getPriceLists());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString")
		void shouldProduceExpectedToString() {
			assertEquals(
				"defaultAccompanyingPriceLists('basic','reference')",
				defaultAccompanyingPriceLists("basic", "reference").toString()
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
				defaultAccompanyingPriceLists("basic", "reference"),
				defaultAccompanyingPriceLists("basic", "reference")
			);
			assertEquals(
				defaultAccompanyingPriceLists("basic", "reference"),
				defaultAccompanyingPriceLists("basic", "reference")
			);
			assertNotEquals(
				defaultAccompanyingPriceLists("basic", "reference"),
				defaultAccompanyingPriceLists("basic", "action")
			);
			assertNotEquals(
				defaultAccompanyingPriceLists("basic", "reference"),
				defaultAccompanyingPriceLists("basic")
			);
			assertEquals(
				defaultAccompanyingPriceLists("basic", "reference").hashCode(),
				defaultAccompanyingPriceLists("basic", "reference").hashCode()
			);
			assertNotEquals(
				defaultAccompanyingPriceLists("basic", "reference").hashCode(),
				defaultAccompanyingPriceLists("basic", "action").hashCode()
			);
			assertNotEquals(
				defaultAccompanyingPriceLists("basic", "reference").hashCode(),
				defaultAccompanyingPriceLists("basic").hashCode()
			);
		}
	}
}
