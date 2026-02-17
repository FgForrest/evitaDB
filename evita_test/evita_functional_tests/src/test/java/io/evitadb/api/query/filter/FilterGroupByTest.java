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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.filterGroupBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FilterGroupBy} verifying construction, applicability, necessity, copy/clone operations,
 * visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("FilterGroupBy constraint")
class FilterGroupByTest {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with children")
		void shouldCreateViaFactoryMethodWithChildren() {
			final ConstraintContainer<FilterConstraint> filterGroupBy = filterGroupBy(
				attributeEquals("abc", "def"),
				attributeEquals("abc", "xyz")
			);

			assertNotNull(filterGroupBy);
			assertEquals(2, filterGroupBy.getChildrenCount());
			assertEquals("abc", ((AttributeEquals) filterGroupBy.getChildren()[0]).getAttributeName());
			assertEquals("def", ((AttributeEquals) filterGroupBy.getChildren()[0]).getAttributeValue());
			assertEquals("abc", ((AttributeEquals) filterGroupBy.getChildren()[1]).getAttributeName());
			assertEquals("xyz", ((AttributeEquals) filterGroupBy.getChildren()[1]).getAttributeValue());
		}

		@Test
		@DisplayName("should return null when factory method receives null")
		void shouldReturnNullWhenFactoryMethodReceivesNull() {
			final FilterGroupBy result = filterGroupBy((FilterConstraint[]) null);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has children")
		void shouldBeApplicableWhenItHasChildren() {
			final FilterGroupBy filterGroupBy = new FilterGroupBy(attributeEquals("abc", "def"));

			assertTrue(filterGroupBy.isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when it has no children")
		void shouldNotBeApplicableWhenItHasNoChildren() {
			final FilterGroupBy emptyFilterGroupBy =
				(FilterGroupBy) new FilterGroupBy(attributeEquals("abc", "def"))
					.getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);

			assertFalse(emptyFilterGroupBy.isApplicable());
		}

		@Test
		@DisplayName("should be necessary when applicable (single child is necessary)")
		void shouldBeNecessaryWhenApplicable() {
			final FilterGroupBy singleChild = new FilterGroupBy(attributeEquals("abc", "def"));

			assertTrue(singleChild.isNecessary());
		}

		@Test
		@DisplayName("should not be necessary when not applicable")
		void shouldNotBeNecessaryWhenNotApplicable() {
			final FilterGroupBy emptyFilterGroupBy =
				(FilterGroupBy) new FilterGroupBy(attributeEquals("abc", "def"))
					.getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);

			assertFalse(emptyFilterGroupBy.isNecessary());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new children")
		void shouldCreateCopyWithNewChildren() {
			final FilterGroupBy original = new FilterGroupBy(attributeEquals("abc", "def"));
			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[]{attributeEquals("xyz", "123")},
				new Constraint<?>[0]
			);

			assertInstanceOf(FilterGroupBy.class, copy);
			assertEquals(1, ((FilterGroupBy) copy).getChildrenCount());
			assertEquals("xyz", ((AttributeEquals) ((FilterGroupBy) copy).getChildren()[0]).getAttributeName());
		}

		@Test
		@DisplayName("should create empty copy when no children provided")
		void shouldCreateEmptyCopyWhenNoChildrenProvided() {
			final FilterGroupBy original = new FilterGroupBy(attributeEquals("abc", "def"));
			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[0],
				new Constraint<?>[0]
			);

			assertInstanceOf(FilterGroupBy.class, copy);
			assertFalse(((FilterGroupBy) copy).isApplicable());
		}

		@Test
		@DisplayName("should reject non-empty additional children")
		void shouldRejectNonEmptyAdditionalChildren() {
			final FilterGroupBy original = new FilterGroupBy(attributeEquals("abc", "def"));

			assertThrows(
				IllegalArgumentException.class,
				() -> original.getCopyWithNewChildren(
					new FilterConstraint[]{attributeEquals("xyz", "123")},
					new Constraint<?>[]{attributeEquals("extra", "child")}
				)
			);
		}

		@Test
		@DisplayName("should throw UnsupportedOperationException when cloning with arguments")
		void shouldThrowWhenCloningWithArguments() {
			final FilterGroupBy filterGroupBy = new FilterGroupBy(attributeEquals("abc", "def"));

			assertThrows(
				UnsupportedOperationException.class,
				() -> filterGroupBy.cloneWithArguments(new Serializable[]{"arg"})
			);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint class as type")
		void shouldReturnFilterConstraintClassAsType() {
			final FilterGroupBy filterGroupBy = new FilterGroupBy(attributeEquals("abc", "def"));

			assertEquals(FilterConstraint.class, filterGroupBy.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final FilterGroupBy filterGroupBy = new FilterGroupBy(
				attributeEquals("abc", "def"),
				attributeEquals("xyz", "123")
			);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			filterGroupBy.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(filterGroupBy, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldProduceExpectedToStringFormat() {
			final ConstraintContainer<FilterConstraint> filterGroupBy = filterGroupBy(
				attributeEquals("abc", '\''),
				attributeEquals("abc", 'x')
			);

			assertNotNull(filterGroupBy);
			assertEquals(
				"filterGroupBy(attributeEquals('abc','\\''),attributeEquals('abc','x'))",
				filterGroupBy.toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(createFilterGroupBy("abc", "def"), createFilterGroupBy("abc", "def"));
			assertEquals(createFilterGroupBy("abc", "def"), createFilterGroupBy("abc", "def"));
			assertNotEquals(createFilterGroupBy("abc", "def"), createFilterGroupBy("abc", "defe"));
			assertNotEquals(createFilterGroupBy("abc", "def"), createFilterGroupBy("abc", null));
			assertNotEquals(createFilterGroupBy("abc", "def"), createFilterGroupBy(null, "abc"));
			assertEquals(
				createFilterGroupBy("abc", "def").hashCode(),
				createFilterGroupBy("abc", "def").hashCode()
			);
			assertNotEquals(
				createFilterGroupBy("abc", "def").hashCode(),
				createFilterGroupBy("abc", "defe").hashCode()
			);
		}
	}

	/**
	 * Creates a {@link FilterGroupBy} constraint containing {@link AttributeEquals} children
	 * built from the given values.
	 */
	@Nullable
	private static FilterGroupBy createFilterGroupBy(@Nullable String... values) {
		return filterGroupBy(
			Arrays.stream(values)
				.map(it -> new AttributeEquals("abc", it))
				.toArray(FilterConstraint[]::new)
		);
	}
}
