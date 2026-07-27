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
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Currency;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;

/**
 * Tests for {@link UserFilter} verifying construction, forbidden children enforcement, applicability,
 * necessity, copy/clone operations, visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("UserFilter constraint")
@Tag(CONTRACT)
@Tag(FILTER)
class UserFilterTest {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with children")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final ConstraintContainer<FilterConstraint> userFilter = userFilter(
				attributeEquals("abc", "def"),
				attributeEquals("abc", "xyz")
			);

			assertNotNull(userFilter);
			assertEquals(2, userFilter.getChildrenCount());
			assertEquals("abc", ((AttributeEquals) userFilter.getChildren()[0]).getAttributeName());
			assertEquals("def", ((AttributeEquals) userFilter.getChildren()[0]).getAttributeValue());
			assertEquals("abc", ((AttributeEquals) userFilter.getChildren()[1]).getAttributeName());
			assertEquals("xyz", ((AttributeEquals) userFilter.getChildren()[1]).getAttributeValue());
		}
	}

	@Nested
	@DisplayName("Forbidden children enforcement")
	class ForbiddenChildrenTest {

		@Test
		@DisplayName("should reject EntityLocaleEquals as child")
		void shouldRejectEntityLocaleEquals() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(entityLocaleEquals(Locale.ENGLISH))
			);
		}

		@Test
		@DisplayName("should reject PriceInCurrency as child")
		void shouldRejectPriceInCurrency() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(priceInCurrency(Currency.getInstance("USD")))
			);
		}

		@Test
		@DisplayName("should reject PriceInPriceLists as child")
		void shouldRejectPriceInPriceLists() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(priceInPriceLists("basic"))
			);
		}

		@Test
		@DisplayName("should reject PriceValidIn as child")
		void shouldRejectPriceValidIn() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(priceValidInNow())
			);
		}

		@Test
		@DisplayName("should reject HierarchyWithin as child")
		void shouldRejectHierarchyWithin() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(
					hierarchyWithin("category", entityPrimaryKeyInSet(1))
				)
			);
		}

		@Test
		@DisplayName("should reject HierarchyWithinRoot as child")
		void shouldRejectHierarchyWithinRoot() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(hierarchyWithinRoot("category"))
			);
		}

		@Test
		@DisplayName("should reject nested UserFilter as child")
		void shouldRejectNestedUserFilter() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(
					new UserFilter(attributeEquals("abc", "def"))
				)
			);
		}

		@Test
		@DisplayName("should reject every baseline forbidden child in one regression guard")
		void shouldRejectEveryBaselineForbiddenChildInOneRegressionGuard() {
			// Baseline regression guard: the forbidden-children list was churned while wiring
			// `histogramHaving` in as the range carrier, so this test re-asserts the full
			// existing blacklist (locale, all three price-binding constraints, both hierarchy
			// constraints, and self-nesting) as a single failure-loud safety net. If any entry
			// is accidentally dropped from `FORBIDDEN_CHILDREN`, this test fails even when
			// every sibling micro-test above still passes. `PriceBetween` is intentionally
			// absent — it is the textbook user-controlled constraint and remains allowed.
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(entityLocaleEquals(Locale.ENGLISH))
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(priceInCurrency(Currency.getInstance("USD")))
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(priceInPriceLists("basic"))
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(priceValidInNow())
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(
					hierarchyWithin("category", entityPrimaryKeyInSet(1))
				)
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(hierarchyWithinRoot("category"))
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(
					new UserFilter(attributeEquals("abc", "def"))
				)
			);
		}

		@Test
		@DisplayName("should reject ReferenceHaving inside userFilter")
		void shouldRejectReferenceHavingInsideUserFilter() {
			// `ReferenceHaving` is back in `FORBIDDEN_CHILDREN` — the only legitimate range
			// carrier inside `userFilter` is `histogramHaving`. Passing a `referenceHaving`
			// directly must fail at constructor time.
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(
					referenceHaving("r", attributeBetween("a", 1, 10))
				)
			);
		}

		@Test
		@DisplayName("should accept HistogramHaving inside userFilter")
		void shouldAcceptHistogramHavingInsideUserFilter() {
			// `histogramHaving` is the first-class range carrier for slider-driven
			// per-histogram range selection and is allowed as a direct child of `userFilter`.
			final UserFilter userFilter = new UserFilter(
				histogramHaving("parameterValues", "basic", 50, 120)
			);
			assertNotNull(userFilter);
			assertEquals(1, userFilter.getChildrenCount());
		}

		@Test
		@DisplayName("should reject multiple forbidden children with descriptive message")
		void shouldRejectMultipleForbiddenChildren() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> new UserFilter(
					entityLocaleEquals(Locale.ENGLISH),
					priceInCurrency(Currency.getInstance("USD"))
				)
			);

			final String message = exception.getMessage();
			assertTrue(
				message.contains("entityLocaleEquals") && message.contains("priceInCurrency"),
				"Exception message should mention both forbidden constraint names, was: " + message
			);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has children")
		void shouldRecognizeApplicability() {
			assertTrue(new UserFilter(attributeEquals("abc", "def")).isApplicable());
			assertFalse(new UserFilter().isApplicable());
		}

		@Test
		@DisplayName("should be necessary when it has at least one child")
		void shouldRecognizeNecessity() {
			assertTrue(
				new UserFilter(attributeEquals("abc", "def"), attributeEquals("xyz", "def")).isNecessary()
			);
			assertTrue(new UserFilter(attributeEquals("abc", "def")).isNecessary());
			assertFalse(new UserFilter().isNecessary());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new children")
		void shouldCreateCopyWithNewChildren() {
			final UserFilter original = new UserFilter(
				attributeEquals("abc", "def"), attributeEquals("xyz", "123")
			);
			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[]{attributeEquals("new", "val")},
				new Constraint<?>[0]
			);

			assertInstanceOf(UserFilter.class, copy);
			assertEquals(1, ((UserFilter) copy).getChildrenCount());
			assertEquals(
				"new",
				((AttributeEquals) ((UserFilter) copy).getChildren()[0]).getAttributeName()
			);
		}

		@Test
		@DisplayName("should reject non-empty additional children in getCopyWithNewChildren")
		void shouldRejectNonEmptyAdditionalChildren() {
			final UserFilter original = new UserFilter(attributeEquals("abc", "def"));

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.getCopyWithNewChildren(
					new FilterConstraint[]{attributeEquals("xyz", "123")},
					new Constraint<?>[]{new OrderBy()}
				)
			);
		}

		@Test
		@DisplayName("should throw UnsupportedOperationException when cloning with arguments")
		void shouldThrowWhenCloningWithArguments() {
			final UserFilter userFilter = new UserFilter(attributeEquals("abc", "def"));

			assertThrows(
				UnsupportedOperationException.class,
				() -> userFilter.cloneWithArguments(new Serializable[]{"arg"})
			);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint class as type")
		void shouldReturnFilterConstraintClassAsType() {
			final UserFilter userFilter = new UserFilter(attributeEquals("abc", "def"));

			assertSame(FilterConstraint.class, userFilter.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final UserFilter userFilter = new UserFilter(attributeEquals("abc", "def"));
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			userFilter.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(userFilter, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldToStringReturnExpectedFormat() {
			final ConstraintContainer<FilterConstraint> userFilter = userFilter(
				attributeEquals("abc", '\''),
				attributeEquals("abc", 'x')
			);

			assertNotNull(userFilter);
			assertEquals(
				"userFilter(attributeEquals('abc','\\''),attributeEquals('abc','x'))",
				userFilter.toString()
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
				createUserFilterConstraint("abc", "def"),
				createUserFilterConstraint("abc", "def")
			);
			assertEquals(
				createUserFilterConstraint("abc", "def"),
				createUserFilterConstraint("abc", "def")
			);
			assertNotEquals(
				createUserFilterConstraint("abc", "def"),
				createUserFilterConstraint("abc", "defe")
			);
			assertNotEquals(
				createUserFilterConstraint("abc", "def"),
				createUserFilterConstraint("abc", null)
			);
			assertNotEquals(
				createUserFilterConstraint("abc", "def"),
				createUserFilterConstraint(null, "abc")
			);
			assertEquals(
				createUserFilterConstraint("abc", "def").hashCode(),
				createUserFilterConstraint("abc", "def").hashCode()
			);
			assertNotEquals(
				createUserFilterConstraint("abc", "def").hashCode(),
				createUserFilterConstraint("abc", "defe").hashCode()
			);
			assertNotEquals(
				createUserFilterConstraint("abc", "def").hashCode(),
				createUserFilterConstraint("abc", null).hashCode()
			);
			assertNotEquals(
				createUserFilterConstraint("abc", "def").hashCode(),
				createUserFilterConstraint(null, "abc").hashCode()
			);
		}
	}

	/**
	 * Creates a {@link UserFilter} constraint containing {@link AttributeEquals} children
	 * built from the given values.
	 */
	@Nullable
	private static UserFilter createUserFilterConstraint(@Nullable String... values) {
		return userFilter(
			Arrays.stream(values)
				.map(it -> new AttributeEquals("abc", it))
				.toArray(FilterConstraint[]::new)
		);
	}
}
