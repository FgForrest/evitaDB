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
import io.evitadb.api.query.filter.AttributeBetween;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link QueryPurifierVisitor} verifying constraint purification, flattening and transformation logic.
 *
 * @author evitaDB
 */
@DisplayName("QueryPurifierVisitor functionality")
class QueryPurifierVisitorTest {

	@Nested
	@DisplayName("Basic purification")
	class BasicPurificationTest {

		@Test
		@DisplayName("Should return null when purifying non-applicable leaf")
		void shouldReturnNullWhenPurifyingNonApplicableLeaf() {
			// Create a non-applicable constraint using AttributeBetween with both null values
			final FilterConstraint nonApplicableConstraint = new io.evitadb.api.query.filter.AttributeBetween("name", null, null);
			assertFalse(nonApplicableConstraint.isApplicable(), "Constraint should not be applicable");

			final FilterConstraint purified = QueryPurifierVisitor.purify(nonApplicableConstraint);
			assertNull(purified, "Purifying non-applicable constraint should return null");
		}

		@Test
		@DisplayName("Should return same instance when constraint is already valid")
		void shouldReturnSameInstanceWhenAlreadyValid() {
			final FilterConstraint validConstraint = attributeEquals("name", "value");
			assertTrue(validConstraint.isApplicable());

			final FilterConstraint purified = QueryPurifierVisitor.purify(validConstraint);
			assertSame(validConstraint, purified);
		}

		@Test
		@DisplayName("Should remove non-applicable leaf from container and flatten")
		void shouldRemoveNonApplicableLeafFromContainer() {
			final FilterConstraint valid = attributeEquals("name", "value");
			final FilterConstraint invalid = new AttributeBetween("other", null, null);
			final FilterConstraint container = and(valid, invalid);

			final FilterConstraint purified = QueryPurifierVisitor.purify(container);

			// Container should be flattened to just the valid constraint
			assertNotNull(purified);
			assertSame(valid, purified);
		}

		@Test
		@DisplayName("Should remove multiple non-applicable leaves from container")
		void shouldRemoveMultipleNonApplicableLeaves() {
			final FilterConstraint valid = attributeEquals("name", "value");
			final FilterConstraint invalid1 = new AttributeBetween("x", null, null);
			final FilterConstraint invalid2 = new AttributeBetween("y", null, null);
			final FilterConstraint container = or(invalid1, invalid2, valid);

			final FilterConstraint purified = QueryPurifierVisitor.purify(container);

			// Container should be flattened to just the valid constraint
			assertNotNull(purified);
			assertSame(valid, purified);
		}

		@Test
		@DisplayName("Should return null when all children are non-applicable")
		void shouldReturnNullWhenAllChildrenNonApplicable() {
			final FilterConstraint invalid1 = new AttributeBetween("x", null, null);
			final FilterConstraint invalid2 = new AttributeBetween("y", null, null);
			final FilterConstraint container = and(invalid1, invalid2);

			final FilterConstraint purified = QueryPurifierVisitor.purify(container);

			assertNull(purified);
		}

		@Test
		@DisplayName("Should purify nested containers recursively")
		void shouldPurifyNestedContainersRecursively() {
			final FilterConstraint valid1 = attributeEquals("a", "b");
			final FilterConstraint valid2 = attributeEquals("c", "d");
			final FilterConstraint invalid = new AttributeBetween("e", null, null);
			final FilterConstraint nested = and(
				valid1,
				or(
					valid2,
					invalid
				)
			);

			final FilterConstraint purified = QueryPurifierVisitor.purify(nested);

			assertNotNull(purified);
			assertEquals(
				"""
					and(
						attributeEquals('a', 'b'),
						attributeEquals('c', 'd')
					)""",
				PrettyPrintingVisitor.toString(purified, "\t")
			);
		}
	}

	@Nested
	@DisplayName("Custom translation")
	class CustomTranslationTest {

		@Test
		@DisplayName("Should apply translator to leaf constraints")
		void shouldApplyTranslatorToLeafConstraints() {
			final FilterConstraint original = and(
				attributeEquals("a", "old"),
				attributeEquals("b", "old")
			);

			final FilterConstraint purified = QueryPurifierVisitor.purify(
				original,
				constraint -> {
					if (constraint instanceof final io.evitadb.api.query.filter.AttributeEquals ae &&
						"old".equals(ae.getAttributeValue())) {
						return attributeEquals(ae.getAttributeName(), "new");
					}
					return constraint;
				}
			);

			assertNotNull(purified);
			assertEquals(
				"""
					and(
						attributeEquals('a', 'new'),
						attributeEquals('b', 'new')
					)""",
				PrettyPrintingVisitor.toString(purified, "\t")
			);
		}

		@Test
		@DisplayName("Should remove constraint when translator returns null")
		void shouldRemoveConstraintWhenTranslatorReturnsNull() {
			final FilterConstraint original = and(
				attributeEquals("keep", "value"),
				attributeEquals("remove", "value")
			);

			final FilterConstraint purified = QueryPurifierVisitor.purify(
				original,
				constraint -> {
					if (constraint instanceof final io.evitadb.api.query.filter.AttributeEquals ae &&
						"remove".equals(ae.getAttributeName())) {
						return null;
					}
					return constraint;
				}
			);

			assertNotNull(purified);
			// Should be flattened to single constraint
			assertEquals("attributeEquals('keep', 'value')", PrettyPrintingVisitor.toString(purified, ""));
		}

		@Test
		@DisplayName("Should return same instance when using identity translator")
		void shouldReturnSameInstanceWithIdentityTranslator() {
			final FilterConstraint original = and(
				attributeEquals("a", "b"),
				attributeEquals("c", "d")
			);

			final FilterConstraint purified = QueryPurifierVisitor.purify(
				original,
				constraint -> constraint
			);

			assertSame(original, purified);
		}

		@Test
		@DisplayName("Should apply translator only to leaves, not containers")
		void shouldApplyTranslatorOnlyToLeaves() {
			final FilterConstraint original = and(
				attributeEquals("a", "value"),
				or(
					attributeEquals("b", "value")
				)
			);

			final int[] containerCount = {0};
			final int[] leafCount = {0};

			QueryPurifierVisitor.purify(
				original,
				constraint -> {
					if (constraint instanceof io.evitadb.api.query.ConstraintContainer) {
						containerCount[0]++;
					} else if (constraint instanceof io.evitadb.api.query.ConstraintLeaf) {
						leafCount[0]++;
					}
					return constraint;
				}
			);

			assertEquals(0, containerCount[0], "Translator should not be called on containers");
			assertEquals(2, leafCount[0], "Translator should be called on leaves");
		}
	}

	@Nested
	@DisplayName("Container flattening")
	class ContainerFlatteningTest {

		@Test
		@DisplayName("Should flatten single-child container when not necessary")
		void shouldFlattenSingleChildContainer() {
			final FilterConstraint leaf = attributeEquals("name", "value");
			final FilterConstraint container = and(leaf);

			final FilterConstraint purified = QueryPurifierVisitor.purify(container);

			// Container with single child should be flattened
			assertNotNull(purified);
			assertSame(leaf, purified);
		}

		@Test
		@DisplayName("Should not flatten multi-child container")
		void shouldNotFlattenMultiChildContainer() {
			final FilterConstraint leaf1 = attributeEquals("a", "b");
			final FilterConstraint leaf2 = attributeEquals("c", "d");
			final FilterConstraint container = and(leaf1, leaf2);

			final FilterConstraint purified = QueryPurifierVisitor.purify(container);

			// Container with multiple children should not be flattened
			assertNotNull(purified);
			assertSame(container, purified);
		}

		@Test
		@DisplayName("Should flatten nested single-child containers")
		void shouldFlattenNestedSingleChildContainers() {
			final FilterConstraint leaf = attributeEquals("name", "value");
			final FilterConstraint nested = and(or(and(leaf)));

			final FilterConstraint purified = QueryPurifierVisitor.purify(nested);

			// All unnecessary containers should be flattened
			assertNotNull(purified);
			assertSame(leaf, purified);
		}

		@Test
		@DisplayName("Should flatten container after removing non-applicable children")
		void shouldFlattenContainerAfterChildRemoval() {
			final FilterConstraint valid = attributeEquals("name", "value");
			final FilterConstraint invalid = new AttributeBetween("other", null, null);
			final FilterConstraint container = and(valid, invalid);

			final FilterConstraint purified = QueryPurifierVisitor.purify(container);

			// After removing invalid, container should be flattened
			assertNotNull(purified);
			assertSame(valid, purified);
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTest {

		@Test
		@DisplayName("Should handle deeply nested constraint tree")
		void shouldHandleDeeplyNestedTree() {
			FilterConstraint nested = attributeEquals("level0", "value");
			for (int i = 0; i < 10; i++) {
				nested = and(nested, attributeEquals("level" + (i + 1), "value"));
			}

			final FilterConstraint purified = QueryPurifierVisitor.purify(nested);

			assertNotNull(purified);
			assertSame(nested, purified);
		}

		@Test
		@DisplayName("Should preserve constraint order after purification")
		void shouldPreserveConstraintOrder() {
			final FilterConstraint first = attributeEquals("first", "1");
			final FilterConstraint second = attributeEquals("second", "2");
			final FilterConstraint third = attributeEquals("third", "3");
			final FilterConstraint invalid = new AttributeBetween("invalid", null, null);
			final FilterConstraint container = and(first, invalid, second, third);

			final FilterConstraint purified = QueryPurifierVisitor.purify(container);

			assertNotNull(purified);
			final String result = PrettyPrintingVisitor.toString(purified, "\t");
			assertTrue(result.contains("'first'"));
			assertTrue(result.contains("'second'"));
			assertTrue(result.contains("'third'"));
			assertFalse(result.contains("'invalid'"));

			// Verify order is preserved: first < second < third
			final int firstPos = result.indexOf("'first'");
			final int secondPos = result.indexOf("'second'");
			final int thirdPos = result.indexOf("'third'");
			assertTrue(firstPos < secondPos);
			assertTrue(secondPos < thirdPos);
		}

		@Test
		@DisplayName("Should return null for completely invalid constraint tree")
		void shouldReturnNullForCompletelyInvalidTree() {
			final FilterConstraint invalid1 = new AttributeBetween("a", null, null);
			final FilterConstraint invalid2 = new AttributeBetween("b", null, null);
			final FilterConstraint invalid3 = new AttributeBetween("c", null, null);
			final FilterConstraint tree = and(
				or(invalid1, invalid2),
				and(invalid3)
			);

			final FilterConstraint purified = QueryPurifierVisitor.purify(tree);

			assertNull(purified);
		}
	}

}
