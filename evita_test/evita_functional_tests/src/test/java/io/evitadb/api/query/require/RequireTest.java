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
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.RequireConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Require} verifying construction, applicability, necessity, copy/clone operations,
 * visitor acceptance, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Require constraint")
class RequireTest {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with children")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final ConstraintContainer<RequireConstraint> require =
				require(
					entityFetch(),
					attributeHistogram(20, "abc")
				);

			assertNotNull(require);
			assertEquals(2, require.getChildrenCount());
			assertArrayEquals(new String[]{"abc"}, ((AttributeHistogram) require.getChildren()[1]).getAttributeNames());
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has children")
		void shouldRecognizeApplicability() {
			assertTrue(new Require(facetSummary()).isApplicable());
			assertFalse(new Require().isApplicable());
		}

		@Test
		@DisplayName("should be necessary when applicable (isNecessary equals isApplicable)")
		void shouldRecognizeNecessity() {
			assertTrue(new Require(facetSummary(), attributeContentAll()).isNecessary());
			assertTrue(new Require(facetSummary()).isNecessary());
			assertFalse(new Require().isNecessary());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new children")
		void shouldCreateCopyWithNewChildren() {
			final Require original = new Require(entityFetch(), facetSummary());
			final RequireConstraint copy = original.getCopyWithNewChildren(
				new RequireConstraint[]{attributeContentAll()},
				new Constraint<?>[0]
			);

			assertInstanceOf(Require.class, copy);
			assertEquals(1, ((Require) copy).getChildrenCount());
			assertInstanceOf(AttributeContent.class, ((Require) copy).getChildren()[0]);
		}

		@Test
		@DisplayName("should create empty copy when no children provided")
		void shouldCreateEmptyCopyWhenNoChildrenProvided() {
			final Require original = new Require(entityFetch());
			final RequireConstraint copy = original.getCopyWithNewChildren(
				new RequireConstraint[0],
				new Constraint<?>[0]
			);

			assertInstanceOf(Require.class, copy);
			assertFalse(((Require) copy).isApplicable());
		}

		@Test
		@DisplayName("should clone with empty arguments preserving children")
		void shouldCloneWithEmptyArgumentsPreservingChildren() {
			final Require original = new Require(entityFetch(), facetSummary());
			final RequireConstraint cloned = original.cloneWithArguments(new Serializable[0]);

			assertNotSame(original, cloned);
			assertInstanceOf(Require.class, cloned);
			final Require clonedRequire = (Require) cloned;
			assertEquals(2, clonedRequire.getChildrenCount());
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return RequireConstraint class as type")
		void shouldReturnRequireConstraintClassAsType() {
			final Require require = new Require(entityFetch());

			assertEquals(RequireConstraint.class, require.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final Require require = new Require(entityFetch(), facetSummary());
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			require.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(require, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldToStringReturnExpectedFormat() {
			final ConstraintContainer<RequireConstraint> require =
				require(
					entityFetch(),
					attributeHistogram(20, "abc")
				);

			assertNotNull(require);
			assertEquals("require(entityFetch(),attributeHistogram(20,'abc'))", require.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(createRequireConstraint("abc", "def"), createRequireConstraint("abc", "def"));
			assertEquals(createRequireConstraint("abc", "def"), createRequireConstraint("abc", "def"));
			assertNotEquals(createRequireConstraint("abc", "def"), createRequireConstraint("abc", "defe"));
			assertNotEquals(createRequireConstraint("abc", "def"), createRequireConstraint("abc", null));
			assertNotEquals(createRequireConstraint("abc", "def"), createRequireConstraint(null, "abc"));
			assertEquals(
				createRequireConstraint("abc", "def").hashCode(),
				createRequireConstraint("abc", "def").hashCode()
			);
			assertNotEquals(
				createRequireConstraint("abc", "def").hashCode(),
				createRequireConstraint("abc", "defe").hashCode()
			);
			assertNotEquals(
				createRequireConstraint("abc", "def").hashCode(),
				createRequireConstraint("abc", null).hashCode()
			);
			assertNotEquals(
				createRequireConstraint("abc", "def").hashCode(),
				createRequireConstraint(null, "abc").hashCode()
			);
		}
	}

	/**
	 * Creates a {@link Require} constraint containing {@link AssociatedDataContent} children built from the given values.
	 */
	@Nonnull
	private static Require createRequireConstraint(@Nullable String... values) {
		return require(
			Arrays.stream(values)
				.map(QueryConstraints::associatedDataContent)
				.toArray(RequireConstraint[]::new)
		);
	}

}
