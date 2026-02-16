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

package io.evitadb.api.query.visitor;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.AttributeBetween;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.AttributeStartsWith;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.query.visitor.FinderVisitor.MoreThanSingleResultException;
import io.evitadb.api.query.visitor.FinderVisitor.PredicateWithDescription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FinderVisitor} verifying constraint search functionality with matchers and stoppers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("FinderVisitor functionality")
class FinderVisitorTest {
	private FilterConstraint filterConstraint;
	private RequireConstraint requireConstraint;

	@BeforeEach
	void setUp() {
		this.filterConstraint = and(
			attributeEquals("a", "b"),
			or(
				attributeIsNotNull("def"),
				attributeEqualsTrue("xev"),
				attributeBetween("c", 1, 78),
				not(
					attributeEqualsTrue("utr")
				)
			)
		);
		this.requireConstraint = require(
			page(1, 20),
			referenceContent(
				"a",
				filterBy(
					attributeEqualsTrue("xev")
				),
				entityFetch(attributeContentAll())
			)
		);
	}

	@Nested
	@DisplayName("Single constraint search")
	class SingleConstraintSearchTest {

		@Test
		@DisplayName("Should return null when constraint is not found")
		void shouldNotFindMissingConstraint() {
			assertNull(FinderVisitor.findConstraint(FinderVisitorTest.this.filterConstraint, fc -> fc instanceof AttributeStartsWith));
		}

		@Test
		@DisplayName("Should find existing constraint by type")
		void shouldFindExistingConstraint() {
			assertEquals(
				attributeBetween("c", 1, 78),
				FinderVisitor.findConstraint(FinderVisitorTest.this.filterConstraint, fc -> fc instanceof AttributeBetween)
			);
		}

		@Test
		@DisplayName("Should find existing constraint by attribute name")
		void shouldFindExistingConstraintByName() {
			assertEquals(
				attributeBetween("c", 1, 78),
				FinderVisitor.findConstraint(FinderVisitorTest.this.filterConstraint, fc -> {
					final Serializable[] args = fc.getArguments();
					return args.length >= 1 && "c".equals(args[0]);
				})
			);
		}

		@Test
		@DisplayName("Should find constraint in additional children")
		void shouldFindExistingConstraintInAdditionalChildrenByName() {
			assertEquals(
				attributeEqualsTrue("xev"),
				FinderVisitor.findConstraint(FinderVisitorTest.this.requireConstraint, fc -> {
					final Serializable[] args = fc.getArguments();
					return args.length >= 1 && "xev".equals(args[0]);
				})
			);
		}
	}

	@Nested
	@DisplayName("Multiple constraints search")
	class MultipleConstraintsSearchTest {

		@Test
		@DisplayName("Should find multiple constraints matching predicate")
		void shouldFindMultipleConstraints() {
			final List<FilterConstraint> found = FinderVisitor.findConstraints(
				FinderVisitorTest.this.filterConstraint,
				fc -> fc instanceof final AttributeEquals attributeEquals && attributeEquals.getAttributeValue().equals(true)
			);

			assertEquals(2, found.size());
		}
	}

	@Nested
	@DisplayName("Stopper predicate")
	class StopperPredicateTest {

		@Test
		@DisplayName("Should stop searching when stopper matches container")
		void shouldStopSearchingWhenStopperMatches() {
			final FilterConstraint constraint = and(
				attributeEquals("outside", "value"),
				or(
					attributeEquals("inside1", "value"),
					attributeEquals("inside2", "value")
				)
			);

			// Stopper should prevent searching inside Or container
			final List<FilterConstraint> found = FinderVisitor.findConstraints(
				constraint,
				fc -> fc instanceof AttributeEquals,
				fc -> fc instanceof Or
			);

			// Should only find the "outside" constraint, not the ones inside Or
			assertEquals(1, found.size());
			final AttributeEquals foundConstraint = (AttributeEquals) found.get(0);
			assertEquals("outside", foundConstraint.getAttributeName());
		}

		@Test
		@DisplayName("Should return empty list when stopper blocks all matches")
		void shouldReturnEmptyListWhenStopperBlocksAllMatches() {
			final FilterConstraint constraint = or(
				attributeEquals("inside1", "value"),
				attributeEquals("inside2", "value")
			);

			// Stopper blocks the Or container itself, so nothing inside is searched
			final List<FilterConstraint> found = FinderVisitor.findConstraints(
				constraint,
				fc -> fc instanceof AttributeEquals,
				fc -> fc instanceof Or
			);

			assertTrue(found.isEmpty());
		}
	}

	@Nested
	@DisplayName("Error handling")
	class ErrorHandlingTest {

		@Test
		@DisplayName("Should throw exception when expecting single result but multiple found")
		void shouldReportExceptionWhenExpectingSingleResultButMultipleFound() {
			assertThrows(
				MoreThanSingleResultException.class,
				() -> FinderVisitor.findConstraint(
					FinderVisitorTest.this.filterConstraint,
					fc -> fc instanceof final AttributeEquals attributeEquals && attributeEquals.getAttributeValue().equals(true)
				)
			);
		}

		@Test
		@DisplayName("Should include predicate description in exception when using PredicateWithDescription")
		void shouldIncludePredicateDescriptionInException() {
			final PredicateWithDescription<io.evitadb.api.query.Constraint<?>> predicate = new PredicateWithDescription<>() {
				@Override
				public boolean test(@Nonnull io.evitadb.api.query.Constraint<?> constraint) {
					return constraint instanceof final AttributeEquals attributeEquals &&
						attributeEquals.getAttributeValue().equals(true);
				}

				@Override
				@Nonnull
				public String toString() {
					return "constraints with attribute value equals to true";
				}
			};

			final MoreThanSingleResultException exception = assertThrows(
				MoreThanSingleResultException.class,
				() -> FinderVisitor.findConstraint(FinderVisitorTest.this.filterConstraint, predicate)
			);

			assertTrue(
				exception.getMessage().contains("constraints with attribute value equals to true"),
				"Exception message should contain predicate description"
			);
		}
	}
}
