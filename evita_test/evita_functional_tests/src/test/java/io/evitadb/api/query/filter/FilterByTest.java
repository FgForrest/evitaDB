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
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FilterBy} verifying construction, applicability, necessity, copy/clone operations,
 * visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("FilterBy constraint")
class FilterByTest {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with children")
		void shouldCreateViaFactoryMethodWithChildren() {
			final ConstraintContainer<FilterConstraint> filterBy = filterBy(
				attributeEquals("abc", "def"),
				attributeEquals("abc", "xyz")
			);

			assertNotNull(filterBy);
			assertEquals(2, filterBy.getChildrenCount());
			assertEquals("abc", ((AttributeEquals) filterBy.getChildren()[0]).getAttributeName());
			assertEquals("def", ((AttributeEquals) filterBy.getChildren()[0]).getAttributeValue());
			assertEquals("abc", ((AttributeEquals) filterBy.getChildren()[1]).getAttributeName());
			assertEquals("xyz", ((AttributeEquals) filterBy.getChildren()[1]).getAttributeValue());
		}

		@Test
		@DisplayName("should return null when factory method receives null")
		void shouldReturnNullWhenFactoryMethodReceivesNull() {
			final FilterBy result = filterBy((FilterConstraint[]) null);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has children")
		void shouldBeApplicableWhenItHasChildren() {
			final FilterBy filterBy = new FilterBy(attributeEquals("abc", "def"));

			assertTrue(filterBy.isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when it has no children")
		void shouldNotBeApplicableWhenItHasNoChildren() {
			final FilterBy emptyFilterBy =
				(FilterBy) new FilterBy(attributeEquals("abc", "def"))
					.getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);

			assertFalse(emptyFilterBy.isApplicable());
		}

		@Test
		@DisplayName("should be necessary when applicable (single child is necessary)")
		void shouldBeNecessaryWhenApplicable() {
			final FilterBy singleChild = new FilterBy(attributeEquals("abc", "def"));

			assertTrue(singleChild.isNecessary());
		}

		@Test
		@DisplayName("should not be necessary when not applicable")
		void shouldNotBeNecessaryWhenNotApplicable() {
			final FilterBy emptyFilterBy =
				(FilterBy) new FilterBy(attributeEquals("abc", "def"))
					.getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);

			assertFalse(emptyFilterBy.isNecessary());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new children")
		void shouldCreateCopyWithNewChildren() {
			final FilterBy original = new FilterBy(attributeEquals("abc", "def"));
			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[]{attributeEquals("xyz", "123")},
				new Constraint<?>[0]
			);

			assertInstanceOf(FilterBy.class, copy);
			assertEquals(1, ((FilterBy) copy).getChildrenCount());
			assertEquals("xyz", ((AttributeEquals) ((FilterBy) copy).getChildren()[0]).getAttributeName());
		}

		@Test
		@DisplayName("should create empty copy when no children provided")
		void shouldCreateEmptyCopyWhenNoChildrenProvided() {
			final FilterBy original = new FilterBy(attributeEquals("abc", "def"));
			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[0],
				new Constraint<?>[0]
			);

			assertInstanceOf(FilterBy.class, copy);
			assertFalse(((FilterBy) copy).isApplicable());
		}

		@Test
		@DisplayName("should reject non-empty additional children")
		void shouldRejectNonEmptyAdditionalChildren() {
			final FilterBy original = new FilterBy(attributeEquals("abc", "def"));

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
			final FilterBy filterBy = new FilterBy(attributeEquals("abc", "def"));

			assertThrows(
				UnsupportedOperationException.class,
				() -> filterBy.cloneWithArguments(new Serializable[]{"arg"})
			);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint class as type")
		void shouldReturnFilterConstraintClassAsType() {
			final FilterBy filterBy = new FilterBy(attributeEquals("abc", "def"));

			assertEquals(FilterConstraint.class, filterBy.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final FilterBy filterBy = new FilterBy(
				attributeEquals("abc", "def"),
				attributeEquals("xyz", "123")
			);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			filterBy.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(filterBy, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldProduceExpectedToStringFormat() {
			final ConstraintContainer<FilterConstraint> filterBy = filterBy(
				attributeEquals("abc", '\''),
				attributeEquals("abc", 'x')
			);

			assertNotNull(filterBy);
			assertEquals(
				"filterBy(attributeEquals('abc','\\''),attributeEquals('abc','x'))",
				filterBy.toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(createFilterBy("abc", "def"), createFilterBy("abc", "def"));
			assertEquals(createFilterBy("abc", "def"), createFilterBy("abc", "def"));
			assertNotEquals(createFilterBy("abc", "def"), createFilterBy("abc", "defe"));
			assertNotEquals(createFilterBy("abc", "def"), createFilterBy("abc", null));
			assertNotEquals(createFilterBy("abc", "def"), createFilterBy(null, "abc"));
			assertEquals(
				createFilterBy("abc", "def").hashCode(),
				createFilterBy("abc", "def").hashCode()
			);
			assertNotEquals(
				createFilterBy("abc", "def").hashCode(),
				createFilterBy("abc", "defe").hashCode()
			);
		}
	}

	/**
	 * Creates a {@link FilterBy} constraint containing {@link AttributeEquals} children built from the given values.
	 */
	@Nullable
	private static FilterBy createFilterBy(@Nullable String... values) {
		return filterBy(
			Arrays.stream(values)
				.map(it -> new AttributeEquals("abc", it))
				.toArray(FilterConstraint[]::new)
		);
	}
}
