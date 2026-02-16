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
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.AttributeBetween;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.Not;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

/**
 * Tests for {@link ConstraintCloneVisitor} verifying constraint cloning and transformation logic.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("ConstraintCloneVisitor functionality")
class ConstraintCloneVisitorTest {
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
					attributeEqualsTrue("def")
				),
				entityFetch(attributeContent("code"))
			)
		);
	}

	@Nested
	@DisplayName("Translator cloning")
	class TranslatorCloningTest {

		@Test
		@DisplayName("Should clone filtering constraint replacing true values with false")
		void shouldCloneFilteringConstraintReplacingIsTrue() {
			final FilterConstraint clone = ConstraintCloneVisitor.clone(ConstraintCloneVisitorTest.this.filterConstraint, (visitor, examined) -> {
			if (examined instanceof final AttributeEquals attributeEquals && attributeEquals.getAttributeValue().equals(true)) {
				return new AttributeEquals(attributeEquals.getAttributeName(), false);
			} else {
				return examined;
			}
		});

			assertEquals(
				"""
					and(
					\tattributeEquals('a', 'b'),
					\tor(
					\t\tattributeIs('def', NOT_NULL),
					\t\tattributeEquals('xev', false),
					\t\tattributeBetween('c', 1, 78),
					\t\tnot(
					\t\t\tattributeEquals('utr', false)
					\t\t)
					\t)
					)""",
				PrettyPrintingVisitor.toString(clone, "\t")
			);
		}

		@Test
		@DisplayName("Should clone filtering constraint removing true values via null translator")
		void shouldCloneFilteringConstraintReplacingIsTrueWithNull() {
			final FilterConstraint clone = ConstraintCloneVisitor.clone(ConstraintCloneVisitorTest.this.filterConstraint, (visitor, examined) -> {
			if (examined instanceof final AttributeEquals attributeEquals && attributeEquals.getAttributeValue().equals(true)) {
				return null;
			} else {
				return examined;
			}
		});

			assertEquals(
				"""
					and(
					\tattributeEquals('a', 'b'),
					\tor(
					\t\tattributeIs('def', NOT_NULL),
					\t\tattributeBetween('c', 1, 78)
					\t)
					)""",
				PrettyPrintingVisitor.toString(clone, "\t")
			);
		}

		@Test
		@DisplayName("Should clone filtering constraint removing between constraints via null translator")
		void shouldCloneFilteringConstraintReplacingBetweenWithNull() {
			final FilterConstraint clone = ConstraintCloneVisitor.clone(ConstraintCloneVisitorTest.this.filterConstraint, (visitor, examined) -> {
			if (examined instanceof AttributeBetween) {
				return null;
			} else {
				return examined;
			}
		});

			assertEquals(
				"""
					and(
					\tattributeEquals('a', 'b'),
					\tor(
					\t\tattributeIs('def', NOT_NULL),
					\t\tattributeEquals('xev', true),
					\t\tnot(
					\t\t\tattributeEquals('utr', true)
					\t\t)
					\t)
					)""",
				PrettyPrintingVisitor.toString(clone, "\t")
			);
		}

		@Test
		@DisplayName("Should clone filtering constraint replacing container with different container type")
		void shouldCloneFilteringConstraintReplacingNotWithAnd() {
			final FilterConstraint clone = ConstraintCloneVisitor.clone(ConstraintCloneVisitorTest.this.filterConstraint, (visitor, examined) -> {
			if (examined instanceof Not) {
				return new And(
					Stream.concat(
						visitor.analyseChildren((Not) examined).stream(),
						Stream.of(new AttributeEquals("added", true))
					).toArray(FilterConstraint[]::new)
				);
			} else {
				return examined;
			}
		});

			assertEquals(
				"""
					and(
					\tattributeEquals('a', 'b'),
					\tor(
					\t\tattributeIs('def', NOT_NULL),
					\t\tattributeEquals('xev', true),
					\t\tattributeBetween('c', 1, 78),
					\t\tand(
					\t\t\tattributeEquals('utr', true),
					\t\t\tattributeEquals('added', true)
					\t\t)
					\t)
					)""",
				PrettyPrintingVisitor.toString(clone, "\t")
			);
		}

		@Test
		@DisplayName("Should clone require constraint modifying constraints in additional children")
		void shouldCloneRequireConstraintReplacingIsTrueInAdditionalChildren() {
			final RequireConstraint clone = ConstraintCloneVisitor.clone(
				ConstraintCloneVisitorTest.this.requireConstraint,
			(visitor, examined) -> {
				if (examined instanceof final AttributeEquals attributeEquals && attributeEquals.getAttributeValue().equals(true)) {
					return new AttributeEquals(attributeEquals.getAttributeName(), false);
				} else {
					return examined;
				}
			});

			assertEquals(
				"""
					require(
					\tpage(1, 20),
					\treferenceContent(
					\t\t'a',
					\t\tfilterBy(
					\t\t\tattributeEquals('def', false)
					\t\t),
					\t\tentityFetch(
					\t\t\tattributeContent('code')
					\t\t)
					\t)
					)""",
				PrettyPrintingVisitor.toString(clone, "\t")
			);
		}
	}

	@Nested
	@DisplayName("Identity behavior")
	class IdentityBehaviorTest {

		@Test
		@DisplayName("Should return same instance when no changes are made with identity translator")
		void shouldReturnSameInstanceWhenNoChangesAreMade() {
			final FilterConstraint original = and(
				attributeEquals("a", "b"),
				or(
					attributeIsNotNull("def"),
					attributeBetween("c", 1, 78)
				)
			);

			final FilterConstraint clone = ConstraintCloneVisitor.clone(
				original,
				(visitor, examined) -> examined
			);

			assertSame(original, clone, "Clone with identity translator should return same instance");
		}

		@Test
		@DisplayName("Should return null when translator removes all constraints")
		void shouldReturnNullWhenTranslatorRemovesAllConstraints() {
			final FilterConstraint original = and(
				attributeEquals("a", "b"),
				attributeEquals("c", "d")
			);

			final FilterConstraint clone = ConstraintCloneVisitor.clone(
				original,
				(visitor, examined) -> null
			);

			assertNull(clone, "Clone with all-null translator should return null");
		}

		@Test
		@DisplayName("Should detect parent context with isWithin method")
		void shouldDetectParentContextWithIsWithin() {
			final FilterConstraint original = and(
				attributeEquals("outside", "value"),
				not(
					attributeEquals("inside", "value")
				)
			);

			final int[] insideNotCount = {0};
			final int[] outsideNotCount = {0};

			ConstraintCloneVisitor.clone(
				original,
				(visitor, examined) -> {
					if (examined instanceof final AttributeEquals ae) {
						if (visitor.isWithin(Not.class)) {
							insideNotCount[0]++;
						} else {
							outsideNotCount[0]++;
						}
					}
					return examined;
				}
			);

			assertEquals(1, insideNotCount[0], "One constraint should be detected inside Not");
			assertEquals(1, outsideNotCount[0], "One constraint should be detected outside Not");
		}
	}
}
