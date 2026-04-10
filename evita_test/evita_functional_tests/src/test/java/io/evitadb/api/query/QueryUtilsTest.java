/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.query;

import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.require.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link QueryUtils} - utility methods for accessing and manipulating
 * {@link Query} constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("QueryUtils")
class QueryUtilsTest {

	@Nested
	@DisplayName("Find constraint")
	class FindConstraintTest {

		@Test
		@DisplayName("should find constraint by type")
		void shouldFindConstraintByType() {
			final FilterBy filterBy = filterBy(
				and(attributeEquals("code", "abc"))
			);
			final AttributeEquals found = QueryUtils.findConstraint(filterBy, AttributeEquals.class);
			assertNotNull(found);
			assertEquals("code", found.getArguments()[0]);
		}

		@Test
		@DisplayName("should find constraint by predicate")
		void shouldFindConstraintByPredicate() {
			final FilterBy filterBy = filterBy(
				and(attributeEquals("code", "abc"), attributeEquals("name", "xyz"))
			);
			final Constraint<?> found = QueryUtils.findConstraint(
				filterBy,
				c -> c instanceof AttributeEquals && "name".equals(((AttributeEquals) c).getArguments()[0])
			);
			assertNotNull(found);
		}

		@Test
		@DisplayName("should return null when constraint not found")
		void shouldReturnNullWhenNotFound() {
			final FilterBy filterBy = filterBy(
				and(attributeEquals("code", "abc"))
			);
			final Page found = QueryUtils.findConstraint(filterBy, Page.class);
			assertNull(found);
		}

		@Test
		@DisplayName("should respect stop container type")
		void shouldRespectStopContainerType() {
			final FilterBy filterBy = filterBy(
				and(
					attributeEquals("code", "abc"),
					or(
						attributeEquals("name", "xyz")
					)
				)
			);
			// Looking for AttributeEquals but stopping at Or - should find "code" not "name"
			final AttributeEquals found = QueryUtils.findConstraint(
				filterBy, AttributeEquals.class, Or.class
			);
			assertNotNull(found);
			assertEquals("code", found.getArguments()[0]);
		}
	}

	@Nested
	@DisplayName("Find constraints (list)")
	class FindConstraintsTest {

		@Test
		@DisplayName("should find all constraints of type")
		void shouldFindAllOfType() {
			final FilterBy filterBy = filterBy(
				and(
					attributeEquals("code", "abc"),
					attributeEquals("name", "xyz")
				)
			);
			final List<AttributeEquals> found = QueryUtils.findConstraints(filterBy, AttributeEquals.class);
			assertEquals(2, found.size());
		}

		@Test
		@DisplayName("should return empty list when none found")
		void shouldReturnEmptyListWhenNoneFound() {
			final FilterBy filterBy = filterBy(
				and(attributeEquals("code", "abc"))
			);
			final List<Page> found = QueryUtils.findConstraints(filterBy, Page.class);
			assertTrue(found.isEmpty());
		}

		@Test
		@DisplayName("should find constraints with stop container")
		void shouldFindConstraintsWithStopContainer() {
			final FilterBy filterBy = filterBy(
				and(
					attributeEquals("code", "abc"),
					or(
						attributeEquals("name", "xyz")
					)
				)
			);
			// Stop at Or - should find only "code"
			final List<AttributeEquals> found = QueryUtils.findConstraints(
				filterBy, AttributeEquals.class, Or.class
			);
			assertEquals(1, found.size());
			assertEquals("code", found.get(0).getArguments()[0]);
		}

		@Test
		@DisplayName("should find constraints by predicate")
		void shouldFindConstraintsByPredicate() {
			final FilterBy filterBy = filterBy(
				and(
					attributeEquals("code", "abc"),
					attributeEquals("name", "xyz")
				)
			);
			final List<Constraint<?>> found = QueryUtils.findConstraints(
				filterBy,
				c -> c instanceof AttributeEquals
			);
			assertEquals(2, found.size());
		}
	}

	@Nested
	@DisplayName("Find filter")
	class FindFilterTest {

		@Test
		@DisplayName("should find filter constraint from query")
		void shouldFindFilterFromQuery() {
			final Query q = query(
				collection("product"),
				filterBy(attributeEquals("code", "abc"))
			);
			final AttributeEquals found = QueryUtils.findFilter(q, AttributeEquals.class);
			assertNotNull(found);
			assertEquals("code", found.getArguments()[0]);
		}

		@Test
		@DisplayName("should return null for query without filter")
		void shouldReturnNullForQueryWithoutFilter() {
			final Query q = query(collection("product"));
			final AttributeEquals found = QueryUtils.findFilter(q, AttributeEquals.class);
			assertNull(found);
		}
	}

	@Nested
	@DisplayName("Find order")
	class FindOrderTest {

		@Test
		@DisplayName("should find order constraint from query")
		void shouldFindOrderFromQuery() {
			final Query q = query(
				collection("product"),
				orderBy(attributeNatural("name"))
			);
			final AttributeNatural found = QueryUtils.findOrder(q, AttributeNatural.class);
			assertNotNull(found);
		}

		@Test
		@DisplayName("should return null for query without order")
		void shouldReturnNullForQueryWithoutOrder() {
			final Query q = query(collection("product"));
			final AttributeNatural found = QueryUtils.findOrder(q, AttributeNatural.class);
			assertNull(found);
		}
	}

	@Nested
	@DisplayName("Find require")
	class FindRequireTest {

		@Test
		@DisplayName("should find require constraint from query")
		void shouldFindRequireFromQuery() {
			final Query q = query(
				collection("product"),
				require(page(1, 5))
			);
			final Page found = QueryUtils.findRequire(q, Page.class);
			assertNotNull(found);
		}

		@Test
		@DisplayName("should return null for query without require")
		void shouldReturnNullForQueryWithoutRequire() {
			final Query q = query(collection("product"));
			final Page found = QueryUtils.findRequire(q, Page.class);
			assertNull(found);
		}

		@Test
		@DisplayName("should find all requires of type")
		void shouldFindAllRequiresOfType() {
			final Query q = query(
				collection("product"),
				require(page(1, 5))
			);
			final List<Page> found = QueryUtils.findRequires(q, Page.class);
			assertEquals(1, found.size());
		}

		@Test
		@DisplayName("should return empty list for missing require")
		void shouldReturnEmptyListForMissingRequire() {
			final Query q = query(collection("product"));
			final List<Page> found = QueryUtils.findRequires(q, Page.class);
			assertTrue(found.isEmpty());
		}
	}

	@Nested
	@DisplayName("Value differs")
	class ValueDiffersTest {

		@Test
		@DisplayName("should return false for null vs null")
		void shouldReturnFalseForNullVsNull() {
			assertFalse(QueryUtils.valueDiffers(null, null));
		}

		@Test
		@DisplayName("should return true for null vs value")
		void shouldReturnTrueForNullVsValue() {
			assertTrue(QueryUtils.valueDiffers(null, "abc"));
		}

		@Test
		@DisplayName("should return true for value vs null")
		void shouldReturnTrueForValueVsNull() {
			assertTrue(QueryUtils.valueDiffers("abc", null));
		}

		@Test
		@DisplayName("should use compareTo for BigDecimal")
		void shouldUseCompareToForBigDecimal() {
			// BigDecimal: 10.0 equals 10.00 via compareTo, but not via equals
			assertFalse(QueryUtils.valueDiffers(new BigDecimal("10.0"), new BigDecimal("10.00")));
		}

		@Test
		@DisplayName("should return true for different BigDecimals")
		void shouldReturnTrueForDifferentBigDecimals() {
			assertTrue(QueryUtils.valueDiffers(new BigDecimal("10.0"), new BigDecimal("20.0")));
		}

		@Test
		@DisplayName("should use equals for non-comparable types")
		void shouldUseEqualsForNonComparableTypes() {
			assertFalse(QueryUtils.valueDiffers("abc", "abc"));
			assertTrue(QueryUtils.valueDiffers("abc", "xyz"));
		}

		@Test
		@DisplayName("should compare arrays element by element")
		void shouldCompareArraysElementByElement() {
			assertFalse(QueryUtils.valueDiffers(
				new String[]{"a", "b"},
				new String[]{"a", "b"}
			));
		}

		@Test
		@DisplayName("should detect different array lengths")
		void shouldDetectDifferentArrayLengths() {
			assertTrue(QueryUtils.valueDiffers(
				new String[]{"a"},
				new String[]{"a", "b"}
			));
		}

		@Test
		@DisplayName("should detect different array elements")
		void shouldDetectDifferentArrayElements() {
			assertTrue(QueryUtils.valueDiffers(
				new String[]{"a", "b"},
				new String[]{"a", "c"}
			));
		}

		@Test
		@DisplayName("should return true for array vs non-array")
		void shouldReturnTrueForArrayVsNonArray() {
			assertTrue(QueryUtils.valueDiffers(new String[]{"a"}, "a"));
		}
	}
}
