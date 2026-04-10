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

package io.evitadb.api.query.parser;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.api.query.require.QueryPriceMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DefaultQueryParser} verifying parsing of
 * queries, constraint lists, and values with various argument
 * passing strategies.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("DefaultQueryParser")
class DefaultQueryParserTest {

	private final DefaultQueryParser parser = new DefaultQueryParser();

	@Test
	@DisplayName("should return singleton instance")
	void shouldGetInstance() {
		final DefaultQueryParser queryParser1 = DefaultQueryParser.getInstance();
		final DefaultQueryParser queryParser2 = DefaultQueryParser.getInstance();

		assertSame(queryParser1, queryParser2);
	}

	@Nested
	@DisplayName("Query parsing")
	class QueryParsing {

		@Test
		@DisplayName("should parse query with positional varargs")
		void shouldParseQueryWithPositionalVarargs() {
			assertEquals(
				query(
					collection("a"),
					filterBy(attributeEqualsTrue("b"))
				),
				parser.parseQuery(
					"query(collection(?),filterBy(attributeEqualsTrue(?)))",
					"a", "b"
				)
			);
		}

		@Test
		@DisplayName("should parse query with positional List")
		void shouldParseQueryWithPositionalList() {
			assertEquals(
				query(
					collection("a"),
					filterBy(attributeEqualsTrue("b"))
				),
				parser.parseQuery(
					"query(collection(?),filterBy(attributeEqualsTrue(?)))",
					List.of("a", "b")
				)
			);
		}

		@Test
		@DisplayName("should parse query with named arguments")
		void shouldParseQueryWithNamedArguments() {
			assertEquals(
				query(
					collection("a"),
					filterBy(attributeEqualsTrue("b"))
				),
				parser.parseQuery(
					"query(collection(@collection),filterBy(attributeEqualsTrue(@attr)))",
					Map.of("collection", "a", "attr", "b")
				)
			);
		}

		@Test
		@DisplayName("should parse query with named and positional varargs")
		void shouldParseQueryWithNamedAndPositionalVarargs() {
			assertEquals(
				query(
					collection("a"),
					filterBy(attributeEqualsTrue("b"))
				),
				parser.parseQuery(
					"query(collection(@collection),filterBy(attributeEqualsTrue(?)))",
					Map.of("collection", "a"),
					"b"
				)
			);
		}

		@Test
		@DisplayName("should parse query with named and positional List")
		void shouldParseQueryWithNamedAndPositionalList() {
			assertEquals(
				query(
					collection("a"),
					filterBy(attributeEqualsTrue("b"))
				),
				parser.parseQuery(
					"query(collection(@collection),filterBy(attributeEqualsTrue(?)))",
					Map.of("collection", "a"),
					List.of("b")
				)
			);
		}

		@Test
		@DisplayName("should parse multi-line query")
		void shouldParseMultiLineQuery() {
			assertEquals(
				query(
					collection("a"),
					filterBy(attributeEqualsTrue("b"))
				),
				parser.parseQuery("""
						query(
							collection(?),
							filterBy(
								attributeEqualsTrue(?)
							)
						)
						""",
					"a", "b"
				)
			);
		}

		@Test
		@DisplayName("should parse complex query with entity fetch")
		void shouldParseComplexQueryWithEntityFetch() {
			assertEquals(
				query(
					collection("PRODUCT"),
					filterBy(entityPrimaryKeyInSet(1)),
					require(
						entityFetch(
							attributeContentAll(),
							associatedDataContentAll(),
							priceContentAll(),
							referenceContentAll(),
							dataInLocalesAll()
						)
					)
				),
				parser.parseQuery("""
						query(
							collection(?),
							filterBy(
								entityPrimaryKeyInSet(?)
							),
							require(
								entityFetch(
									attributeContentAll(),
									associatedDataContentAll(),
									priceContentAll(),
									referenceContentAll(),
									dataInLocalesAll()
								)
							)
						)
						""",
					"PRODUCT", 1
				)
			);
		}

		@Test
		@DisplayName("should parse query with trailing commas")
		void shouldParseQueryWithTrailingCommas() {
			assertEquals(
				query(
					collection("a"),
					filterBy(attributeEqualsTrue("b")),
					orderBy(attributeNatural("c"))
				),
				parser.parseQuery(
					"""
						query(
							collection(?),
							filterBy(
								attributeEqualsTrue(?),
							),
							orderBy(attributeNatural(?),)
						)
						""",
					"a", "b", "c"
				)
			);
		}

		@Test
		@DisplayName("should parse query with comments")
		void shouldParseQueryWithComments() {
			assertEquals(
				query(
					collection("PRODUCT"),
					filterBy(entityPrimaryKeyInSet(1)),
					require(
						entityFetch(
							attributeContent(),
							associatedDataContent(),
							priceContentAll(),
							referenceContentAll(),
							dataInLocales()
						)
					)
				),
				parser.parseQuery("""
						// this is a comment
						query(
							collection(?),
							// this is a inner comment
							filterBy(
								entityPrimaryKeyInSet(?) // inline
							),
							require(
								entityFetch(
									attributeContentAll(),
									associatedDataContentAll(),
									priceContentAll(),
									referenceContentAll(),
									dataInLocalesAll()
								)
							)
						)
						""",
					"PRODUCT", 1
				)
			);
		}

		@Test
		@DisplayName("should parse query with different quotation marks")
		void shouldParseQueryWithDifferentQuotationMarks() {
			assertEquals(
				query(
					collection("Product"),
					filterBy(attributeEquals("a", "b"))
				),
				parser.parseQueryUnsafe("""
					query(
						collection('Product'),
						filterBy(
							attributeEquals('a', 'b')
						)
					)
					""")
			);

			assertEquals(
				query(
					collection("Product"),
					filterBy(attributeEquals("a", "b"))
				),
				parser.parseQueryUnsafe("""
					query(
						collection("Product"),
						filterBy(
							attributeEquals("a", "b")
						)
					)
					""")
			);

			assertEquals(
				query(
					collection("Product"),
					filterBy(attributeEquals("a", "b"))
				),
				parser.parseQueryUnsafe("""
					query(
						collection('Product'),
						filterBy(
							attributeEquals('a', "b")
						)
					)
					""")
			);
		}

		@Test
		@DisplayName("should not parse invalid query strings")
		void shouldNotParseInvalidQueryStrings() {
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseQuery("query(filterBy(attributeEquals('a','b')))")
			);
			assertThrows(EvitaSyntaxException.class, () -> parser.parseQuery("query(collection(?))"));
			assertThrows(EvitaSyntaxException.class, () -> parser.parseQuery("query(collection(@collection))"));
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseQuery("query(collection(@collection))", Map.of("attr", "some"))
			);
			assertThrows(EvitaSyntaxException.class, () -> parser.parseQuery(""));
			assertThrows(EvitaSyntaxException.class, () -> parser.parseQuery("'b'"));
			assertThrows(EvitaSyntaxException.class, () -> parser.parseQuery("attributeEqualsTrue('a')"));
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseQuery("query(collection('a')) query(collection('b'))")
			);
		}

		@Test
		@DisplayName("should reject mismatched quotation marks")
		void shouldRejectMismatchedQuotationMarks() {
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseQueryUnsafe("query(collection('a\"))")
			);
		}

		@Test
		@DisplayName("should parse query unsafe with literal values")
		void shouldParseQueryUnsafeWithLiteralValues() {
			assertEquals(
				query(collection("a")),
				parser.parseQueryUnsafe("query(collection('a'))")
			);

			assertEquals(
				query(
					collection("a"),
					filterBy(attributeEqualsTrue("b")),
					orderBy(random()),
					require(attributeContentAll())
				),
				parser.parseQueryUnsafe(
					"query(collection('a'),filterBy(attributeEqualsTrue('b')),orderBy(random()),require(attributeContentAll()))"
				)
			);
		}

		@Test
		@DisplayName("should parse query unsafe with positional varargs")
		void shouldParseQueryUnsafeWithPositionalVarargs() {
			assertEquals(
				query(
					collection("a"),
					filterBy(attributeEqualsTrue("b"))
				),
				parser.parseQueryUnsafe(
					"query(collection(?),filterBy(attributeEqualsTrue(?)))",
					"a", "b"
				)
			);
		}

		@Test
		@DisplayName("should parse query unsafe with positional List")
		void shouldParseQueryUnsafeWithPositionalList() {
			assertEquals(
				query(
					collection("a"),
					filterBy(attributeEqualsTrue("b"))
				),
				parser.parseQueryUnsafe(
					"query(collection(?),filterBy(attributeEqualsTrue(?)))",
					List.of("a", "b")
				)
			);
		}

		@Test
		@DisplayName("should parse query unsafe with named arguments")
		void shouldParseQueryUnsafeWithNamedArguments() {
			assertEquals(
				query(
					collection("a"),
					filterBy(attributeEqualsTrue("b"))
				),
				parser.parseQueryUnsafe(
					"query(collection(@collection),filterBy(attributeEqualsTrue(@attr)))",
					Map.of("collection", "a", "attr", "b")
				)
			);
		}

		@Test
		@DisplayName("should parse query unsafe with named and positional varargs")
		void shouldParseQueryUnsafeWithNamedAndPosVarargs() {
			assertEquals(
				query(
					collection("a"),
					filterBy(attributeEqualsTrue("b"))
				),
				parser.parseQueryUnsafe(
					"query(collection(@collection),filterBy(attributeEqualsTrue(?)))",
					Map.of("collection", "a"),
					"b"
				)
			);
		}

		@Test
		@DisplayName("should parse query unsafe with named and positional List")
		void shouldParseQueryUnsafeWithNamedAndPosList() {
			assertEquals(
				query(
					collection("a"),
					filterBy(attributeEqualsTrue("b"))
				),
				parser.parseQueryUnsafe(
					"query(collection(@collection),filterBy(attributeEqualsTrue(?)))",
					Map.of("collection", "a"),
					List.of("b")
				)
			);
		}

		@Test
		@DisplayName("should parse query unsafe with mixed literals and positional args")
		void shouldParseQueryUnsafeWithMixedLiteralsAndArgs() {
			assertEquals(
				query(
					collection("a"),
					filterBy(attributeEqualsTrue("b"))
				),
				parser.parseQueryUnsafe("""
					query(
						collection('a'),
						filterBy(
							attributeEqualsTrue(?)
						)
					)
					""",
					"b"
				)
			);
		}

		@Test
		@DisplayName("should not parse invalid unsafe query strings")
		void shouldNotParseInvalidUnsafeQueryStrings() {
			assertThrows(EvitaSyntaxException.class, () -> parser.parseQueryUnsafe("query(collection(?))"));
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseQueryUnsafe("query(collection(@collection))")
			);
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseQueryUnsafe("query(collection(@collection))", Map.of("attr", "some"))
			);
			assertThrows(EvitaSyntaxException.class, () -> parser.parseQueryUnsafe(""));
			assertThrows(EvitaSyntaxException.class, () -> parser.parseQueryUnsafe("'b'"));
			assertThrows(EvitaSyntaxException.class, () -> parser.parseQueryUnsafe("attributeEqualsTrue('a')"));
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseQueryUnsafe("query(collection('a')) query(collection('b'))")
			);
		}
	}

	@Nested
	@DisplayName("Head constraint parsing")
	class HeadConstraintParsing {

		@Test
		@DisplayName("should parse head constraint list unsafe with literals")
		void shouldParseHeadConstraintListUnsafe() {
			final List<HeadConstraint> result =
				parser.parseHeadConstraintListUnsafe("collection('product'),collection('brand')");

			assertEquals(List.of(collection("product"), collection("brand")), result);
		}

		@Test
		@DisplayName("should parse head constraint list with positional varargs")
		void shouldParseHeadConstraintListWithPosVarargs() {
			final List<HeadConstraint> result = parser.parseHeadConstraintList("collection(?)", "product");

			assertEquals(List.of(collection("product")), result);
		}

		@Test
		@DisplayName("should parse head constraint list with positional List")
		void shouldParseHeadConstraintListWithPosList() {
			final List<HeadConstraint> result =
				parser.parseHeadConstraintList("collection(?)", List.of("product"));

			assertEquals(List.of(collection("product")), result);
		}

		@Test
		@DisplayName("should parse head constraint list with named arguments")
		void shouldParseHeadConstraintListWithNamedArgs() {
			final List<HeadConstraint> result = parser.parseHeadConstraintList(
				"collection(@product),collection(@col)",
				Map.of("product", "product", "col", "brand")
			);

			assertEquals(List.of(collection("product"), collection("brand")), result);
		}

		@Test
		@DisplayName("should parse head constraint list with named and positional varargs")
		void shouldParseHeadConstraintListWithMixedArgs() {
			final List<HeadConstraint> result = parser.parseHeadConstraintList(
				"collection(?),collection(@col)",
				Map.of("col", "brand"),
				"product"
			);

			assertEquals(List.of(collection("product"), collection("brand")), result);
		}

		@Test
		@DisplayName("should parse head constraint list with named and positional List")
		void shouldParseHeadConstraintListWithNamedAndList() {
			final List<HeadConstraint> result = parser.parseHeadConstraintList(
				"collection(?),collection(@col)",
				Map.of("col", "brand"),
				List.of("product")
			);

			assertEquals(List.of(collection("product"), collection("brand")), result);
		}

		@Test
		@DisplayName("should not parse invalid head constraint list")
		void shouldNotParseInvalidHeadConstraintList() {
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseHeadConstraintList("attributeEqualsTrue('code')")
			);
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseHeadConstraintList("collection('product'),attributeEqualsTrue('code')")
			);
		}

		@Test
		@DisplayName("should parse head constraint list unsafe with positional varargs")
		void shouldParseHeadUnsafeWithPosVarargs() {
			final List<HeadConstraint> result =
				parser.parseHeadConstraintListUnsafe("collection(?)", "product");

			assertEquals(List.of(collection("product")), result);
		}

		@Test
		@DisplayName("should parse head constraint list unsafe with positional List")
		void shouldParseHeadUnsafeWithPosList() {
			final List<HeadConstraint> result =
				parser.parseHeadConstraintListUnsafe("collection(?)", List.of("product"));

			assertEquals(List.of(collection("product")), result);
		}

		@Test
		@DisplayName("should parse head constraint list unsafe with named arguments")
		void shouldParseHeadUnsafeWithNamedArgs() {
			final List<HeadConstraint> result =
				parser.parseHeadConstraintListUnsafe("collection(@name)", Map.of("name", "product"));

			assertEquals(List.of(collection("product")), result);
		}

		@Test
		@DisplayName("should parse head constraint list unsafe with named and positional varargs")
		void shouldParseHeadUnsafeWithNamedAndPosVarargs() {
			final List<HeadConstraint> result = parser.parseHeadConstraintListUnsafe(
				"collection(?),collection(@col)",
				Map.of("col", "brand"),
				"product"
			);

			assertEquals(List.of(collection("product"), collection("brand")), result);
		}

		@Test
		@DisplayName("should parse head constraint list unsafe with named and positional List")
		void shouldParseHeadUnsafeWithNamedAndPosList() {
			final List<HeadConstraint> result = parser.parseHeadConstraintListUnsafe(
				"collection(?),collection(@col)",
				Map.of("col", "brand"),
				List.of("product")
			);

			assertEquals(List.of(collection("product"), collection("brand")), result);
		}

		@Test
		@DisplayName("should not parse invalid head constraint list unsafe")
		void shouldNotParseInvalidHeadUnsafeList() {
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseHeadConstraintListUnsafe("attributeEqualsTrue('code')")
			);
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseHeadConstraintListUnsafe("collection('product'),attributeEqualsTrue('code')")
			);
		}
	}

	@Nested
	@DisplayName("Filter constraint parsing")
	class FilterConstraintParsing {

		@Test
		@DisplayName("should parse filter constraint list unsafe with literals")
		void shouldParseFilterConstraintListUnsafe() {
			final List<FilterConstraint> result = parser.parseFilterConstraintListUnsafe(
				"attributeEqualsTrue('code'),attributeEqualsTrue('age')"
			);

			assertEquals(List.of(attributeEqualsTrue("code"), attributeEqualsTrue("age")), result);
		}

		@Test
		@DisplayName("should parse filter constraint list with positional varargs")
		void shouldParseFilterListWithPosVarargs() {
			final List<FilterConstraint> result =
				parser.parseFilterConstraintList("attributeEqualsTrue(?)", "code");

			assertEquals(List.of(attributeEqualsTrue("code")), result);
		}

		@Test
		@DisplayName("should parse filter constraint list with positional List")
		void shouldParseFilterListWithPosList() {
			final List<FilterConstraint> result =
				parser.parseFilterConstraintList("attributeEqualsTrue(?)", List.of("code"));

			assertEquals(List.of(attributeEqualsTrue("code")), result);
		}

		@Test
		@DisplayName("should parse filter constraint list with named arguments")
		void shouldParseFilterListWithNamedArgs() {
			final List<FilterConstraint> result = parser.parseFilterConstraintList(
				"attributeEqualsTrue(@code),attributeEqualsTrue(@name)",
				Map.of("code", "code", "name", "age")
			);

			assertEquals(List.of(attributeEqualsTrue("code"), attributeEqualsTrue("age")), result);
		}

		@Test
		@DisplayName("should parse filter constraint list with named and positional varargs")
		void shouldParseFilterListWithMixedArgs() {
			final List<FilterConstraint> result = parser.parseFilterConstraintList(
				"attributeEqualsTrue(?),attributeEqualsTrue(@name)",
				Map.of("name", "age"),
				"code"
			);

			assertEquals(List.of(attributeEqualsTrue("code"), attributeEqualsTrue("age")), result);
		}

		@Test
		@DisplayName("should parse filter constraint list with named and positional List")
		void shouldParseFilterListWithNamedAndPosList() {
			final List<FilterConstraint> result = parser.parseFilterConstraintList(
				"attributeEqualsTrue(?),attributeEqualsTrue(@name)",
				Map.of("name", "age"),
				List.of("code")
			);

			assertEquals(List.of(attributeEqualsTrue("code"), attributeEqualsTrue("age")), result);
		}

		@Test
		@DisplayName("should not parse invalid filter constraint list")
		void shouldNotParseInvalidFilterList() {
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseFilterConstraintList("collection('code')")
			);
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseFilterConstraintList("attributeEqualsTrue('product'),collection('code')")
			);
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseFilterConstraintList("attributeEquals('code',2)")
			);
		}

		@Test
		@DisplayName("should parse filter constraint list unsafe with literal values")
		void shouldParseFilterUnsafeWithLiterals() {
			final List<FilterConstraint> result = parser.parseFilterConstraintListUnsafe(
				"attributeEquals('code', 1),attributeEquals('age', 2)"
			);

			assertEquals(List.of(attributeEquals("code", 1L), attributeEquals("age", 2L)), result);
		}

		@Test
		@DisplayName("should parse filter constraint list unsafe with positional varargs")
		void shouldParseFilterUnsafeWithPosVarargs() {
			final List<FilterConstraint> result =
				parser.parseFilterConstraintListUnsafe("attributeEqualsTrue(?)", "code");

			assertEquals(List.of(attributeEqualsTrue("code")), result);
		}

		@Test
		@DisplayName("should parse filter constraint list unsafe with positional List")
		void shouldParseFilterUnsafeWithPosList() {
			final List<FilterConstraint> result =
				parser.parseFilterConstraintListUnsafe("attributeEqualsTrue(?)", List.of("code"));

			assertEquals(List.of(attributeEqualsTrue("code")), result);
		}

		@Test
		@DisplayName("should parse filter constraint list unsafe with named arguments")
		void shouldParseFilterUnsafeWithNamedArgs() {
			final List<FilterConstraint> result = parser.parseFilterConstraintListUnsafe(
				"attributeEqualsTrue(@code)",
				Map.of("code", "code")
			);

			assertEquals(List.of(attributeEqualsTrue("code")), result);
		}

		@Test
		@DisplayName("should parse filter constraint list unsafe with named and positional varargs")
		void shouldParseFilterUnsafeWithMixedVarargs() {
			final List<FilterConstraint> result = parser.parseFilterConstraintListUnsafe(
				"attributeEqualsTrue(?),attributeEqualsTrue(@name)",
				Map.of("name", "age"),
				"code"
			);

			assertEquals(List.of(attributeEqualsTrue("code"), attributeEqualsTrue("age")), result);
		}

		@Test
		@DisplayName("should parse filter constraint list unsafe with named and positional List")
		void shouldParseFilterUnsafeWithNamedAndPosList() {
			final List<FilterConstraint> result = parser.parseFilterConstraintListUnsafe(
				"attributeEqualsTrue(?),attributeEqualsTrue(@name)",
				Map.of("name", "age"),
				List.of("code")
			);

			assertEquals(List.of(attributeEqualsTrue("code"), attributeEqualsTrue("age")), result);
		}

		@Test
		@DisplayName("should not parse invalid filter constraint list unsafe")
		void shouldNotParseInvalidFilterUnsafeList() {
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseFilterConstraintListUnsafe("collection('code')")
			);
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseFilterConstraintListUnsafe("attributeEqualsTrue('product'),collection('code')")
			);
		}
	}

	@Nested
	@DisplayName("Order constraint parsing")
	class OrderConstraintParsing {

		@Test
		@DisplayName("should parse order constraint list unsafe with literals")
		void shouldParseOrderConstraintListUnsafe() {
			final List<OrderConstraint> result = parser.parseOrderConstraintListUnsafe(
				"attributeNatural('code'),attributeNatural('age')"
			);

			assertEquals(List.of(attributeNatural("code"), attributeNatural("age")), result);
		}

		@Test
		@DisplayName("should parse order constraint list with positional varargs")
		void shouldParseOrderListWithPosVarargs() {
			final List<OrderConstraint> result =
				parser.parseOrderConstraintList("attributeNatural(?)", "code");

			assertEquals(List.of(attributeNatural("code")), result);
		}

		@Test
		@DisplayName("should parse order constraint list with positional List")
		void shouldParseOrderListWithPosList() {
			final List<OrderConstraint> result =
				parser.parseOrderConstraintList("attributeNatural(?)", List.of("code"));

			assertEquals(List.of(attributeNatural("code")), result);
		}

		@Test
		@DisplayName("should parse order constraint list with named arguments")
		void shouldParseOrderListWithNamedArgs() {
			final List<OrderConstraint> result = parser.parseOrderConstraintList(
				"attributeNatural(@code),attributeNatural(@name)",
				Map.of("code", "code", "name", "age")
			);

			assertEquals(List.of(attributeNatural("code"), attributeNatural("age")), result);
		}

		@Test
		@DisplayName("should parse order constraint list with named and positional varargs")
		void shouldParseOrderListWithMixedArgs() {
			final List<OrderConstraint> result = parser.parseOrderConstraintList(
				"attributeNatural(?),attributeNatural(@name)",
				Map.of("name", "age"),
				"code"
			);

			assertEquals(List.of(attributeNatural("code"), attributeNatural("age")), result);
		}

		@Test
		@DisplayName("should parse order constraint list with named and positional List")
		void shouldParseOrderListWithNamedAndPosList() {
			final List<OrderConstraint> result = parser.parseOrderConstraintList(
				"attributeNatural(?),attributeNatural(@name)",
				Map.of("name", "age"),
				List.of("code")
			);

			assertEquals(List.of(attributeNatural("code"), attributeNatural("age")), result);
		}

		@Test
		@DisplayName("should not parse invalid order constraint list")
		void shouldNotParseInvalidOrderList() {
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseOrderConstraintList("collection('code')")
			);
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseOrderConstraintList("attributeNatural('product'),collection('code')")
			);
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseOrderConstraintList("attributeNatural('code',DESC)")
			);
		}

		@Test
		@DisplayName("should parse order constraint list unsafe with enum direction")
		void shouldParseOrderUnsafeWithEnumDirection() {
			final List<OrderConstraint> result = parser.parseOrderConstraintListUnsafe(
				"attributeNatural('code',ASC),attributeNatural('age',DESC)"
			);

			assertEquals(
				List.of(
					attributeNatural("code", OrderDirection.ASC),
					attributeNatural("age", OrderDirection.DESC)
				),
				result
			);
		}

		@Test
		@DisplayName("should parse order constraint list unsafe with positional varargs")
		void shouldParseOrderUnsafeWithPosVarargs() {
			final List<OrderConstraint> result =
				parser.parseOrderConstraintListUnsafe("attributeNatural(?)", "code");

			assertEquals(List.of(attributeNatural("code")), result);
		}

		@Test
		@DisplayName("should parse order constraint list unsafe with positional List")
		void shouldParseOrderUnsafeWithPosList() {
			final List<OrderConstraint> result =
				parser.parseOrderConstraintListUnsafe("attributeNatural(?)", List.of("code"));

			assertEquals(List.of(attributeNatural("code")), result);
		}

		@Test
		@DisplayName("should parse order constraint list unsafe with named arguments")
		void shouldParseOrderUnsafeWithNamedArgs() {
			final List<OrderConstraint> result = parser.parseOrderConstraintListUnsafe(
				"attributeNatural(@code)",
				Map.of("code", "code")
			);

			assertEquals(List.of(attributeNatural("code")), result);
		}

		@Test
		@DisplayName("should parse order constraint list unsafe with named and positional varargs")
		void shouldParseOrderUnsafeWithMixedVarargs() {
			final List<OrderConstraint> result = parser.parseOrderConstraintListUnsafe(
				"attributeNatural(?),attributeNatural(@name)",
				Map.of("name", "age"),
				"code"
			);

			assertEquals(List.of(attributeNatural("code"), attributeNatural("age")), result);
		}

		@Test
		@DisplayName("should parse order constraint list unsafe with named and positional List")
		void shouldParseOrderUnsafeWithNamedAndPosList() {
			final List<OrderConstraint> result = parser.parseOrderConstraintListUnsafe(
				"attributeNatural(?),attributeNatural(@name)",
				Map.of("name", "age"),
				List.of("code")
			);

			assertEquals(List.of(attributeNatural("code"), attributeNatural("age")), result);
		}

		@Test
		@DisplayName("should not parse invalid order constraint list unsafe")
		void shouldNotParseInvalidOrderUnsafeList() {
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseOrderConstraintListUnsafe("collection('code')")
			);
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseOrderConstraintListUnsafe("attributeNatural('product'),collection('code')")
			);
		}
	}

	@Nested
	@DisplayName("Require constraint parsing")
	class RequireConstraintParsing {

		@Test
		@DisplayName("should parse require constraint list unsafe with literals")
		void shouldParseRequireConstraintListUnsafe() {
			final List<RequireConstraint> result = parser.parseRequireConstraintListUnsafe(
				"attributeContent('code'),attributeContent('age')"
			);

			assertEquals(List.of(attributeContent("code"), attributeContent("age")), result);
		}

		@Test
		@DisplayName("should parse require constraint list with positional varargs")
		void shouldParseRequireListWithPosVarargs() {
			final List<RequireConstraint> result =
				parser.parseRequireConstraintList("attributeContent(?)", "code");

			assertEquals(List.of(attributeContent("code")), result);
		}

		@Test
		@DisplayName("should parse require constraint list with positional List")
		void shouldParseRequireListWithPosList() {
			final List<RequireConstraint> result =
				parser.parseRequireConstraintList("attributeContent(?)", List.of("code"));

			assertEquals(List.of(attributeContent("code")), result);
		}

		@Test
		@DisplayName("should parse require constraint list with named arguments")
		void shouldParseRequireListWithNamedArgs() {
			final List<RequireConstraint> result = parser.parseRequireConstraintList(
				"attributeContent(@code),attributeContent(@name)",
				Map.of("code", "code", "name", "age")
			);

			assertEquals(List.of(attributeContent("code"), attributeContent("age")), result);
		}

		@Test
		@DisplayName("should parse require constraint list with named and positional varargs")
		void shouldParseRequireListWithMixedArgs() {
			final List<RequireConstraint> result = parser.parseRequireConstraintList(
				"attributeContent(?),attributeContent(@name)",
				Map.of("name", "age"),
				"code"
			);

			assertEquals(List.of(attributeContent("code"), attributeContent("age")), result);
		}

		@Test
		@DisplayName("should parse require constraint list with named and positional List")
		void shouldParseRequireListWithNamedAndPosList() {
			final List<RequireConstraint> result = parser.parseRequireConstraintList(
				"attributeContent(?),attributeContent(@name)",
				Map.of("name", "age"),
				List.of("code")
			);

			assertEquals(List.of(attributeContent("code"), attributeContent("age")), result);
		}

		@Test
		@DisplayName("should not parse invalid require constraint list")
		void shouldNotParseInvalidRequireList() {
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseRequireConstraintList("collection('product')")
			);
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseRequireConstraintList("attributeContent('code'),collection('product')")
			);
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseRequireConstraintList("priceType(WITH_TAX)")
			);
		}

		@Test
		@DisplayName("should parse require constraint list unsafe with enum")
		void shouldParseRequireUnsafeWithEnum() {
			final List<RequireConstraint> result = parser.parseRequireConstraintListUnsafe(
				"priceType(WITH_TAX),attributeContent('age')"
			);

			assertEquals(List.of(priceType(QueryPriceMode.WITH_TAX), attributeContent("age")), result);
		}

		@Test
		@DisplayName("should parse require constraint list unsafe with positional varargs")
		void shouldParseRequireUnsafeWithPosVarargs() {
			final List<RequireConstraint> result =
				parser.parseRequireConstraintListUnsafe("attributeContent(?)", "code");

			assertEquals(List.of(attributeContent("code")), result);
		}

		@Test
		@DisplayName("should parse require constraint list unsafe with positional List")
		void shouldParseRequireUnsafeWithPosList() {
			final List<RequireConstraint> result =
				parser.parseRequireConstraintListUnsafe("attributeContent(?)", List.of("code"));

			assertEquals(List.of(attributeContent("code")), result);
		}

		@Test
		@DisplayName("should parse require constraint list unsafe with named arguments")
		void shouldParseRequireUnsafeWithNamedArgs() {
			final List<RequireConstraint> result = parser.parseRequireConstraintListUnsafe(
				"attributeContent(@code)",
				Map.of("code", "code")
			);

			assertEquals(List.of(attributeContent("code")), result);
		}

		@Test
		@DisplayName("should parse require constraint list unsafe with named and positional varargs")
		void shouldParseRequireUnsafeWithMixedVarargs() {
			final List<RequireConstraint> result = parser.parseRequireConstraintListUnsafe(
				"attributeContent(?),attributeContent(@name)",
				Map.of("name", "age"),
				"code"
			);

			assertEquals(List.of(attributeContent("code"), attributeContent("age")), result);
		}

		@Test
		@DisplayName("should parse require constraint list unsafe with named and positional List")
		void shouldParseRequireUnsafeWithNamedAndPosList() {
			final List<RequireConstraint> result = parser.parseRequireConstraintListUnsafe(
				"attributeContent(?),attributeContent(@name)",
				Map.of("name", "age"),
				List.of("code")
			);

			assertEquals(List.of(attributeContent("code"), attributeContent("age")), result);
		}

		@Test
		@DisplayName("should not parse invalid require constraint list unsafe")
		void shouldNotParseInvalidRequireUnsafeList() {
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseRequireConstraintListUnsafe("collection('product')")
			);
			assertThrows(
				EvitaSyntaxException.class,
				() -> parser.parseRequireConstraintListUnsafe("attributeContent('code'),collection('product')")
			);
		}
	}

	@Nested
	@DisplayName("Value parsing")
	class ValueParsing {

		@Test
		@DisplayName("should parse string literal")
		void shouldParseStringLiteral() {
			assertEquals("a", parser.parseValue("'a'"));
		}

		@Test
		@DisplayName("should parse numeric literal")
		void shouldParseNumericLiteral() {
			assertEquals(123L, (Long) parser.parseValue("123"));
		}

		@Test
		@DisplayName("should parse enum literal")
		void shouldParseEnumLiteral() {
			assertEquals(EnumWrapper.fromString("SOME_ENUM"), parser.parseValue("SOME_ENUM"));
		}

		@Test
		@DisplayName("should parse value with positional argument")
		void shouldParseValueWithPositionalArgument() {
			assertEquals("a", parser.parseValue("?", "a"));
		}

		@Test
		@DisplayName("should parse value with named argument")
		void shouldParseValueWithNamedArgument() {
			assertEquals("a", parser.parseValue("@name", Map.of("name", "a")));
		}

		@Test
		@DisplayName("should parse value with named and positional arguments")
		void shouldParseValueWithNamedAndPositionalArgs() {
			assertEquals("a", parser.parseValue("@name", Map.of("name", "a"), "unused"));
		}

		@Test
		@DisplayName("should not parse invalid value strings")
		void shouldNotParseInvalidValueStrings() {
			assertThrows(EvitaSyntaxException.class, () -> parser.parseValue("?"));
			assertThrows(EvitaSyntaxException.class, () -> parser.parseValue("@name"));
			assertThrows(EvitaSyntaxException.class, () -> parser.parseValue("@name", Map.of("col", "some")));
			assertThrows(EvitaSyntaxException.class, () -> parser.parseValue(""));
			assertThrows(EvitaSyntaxException.class, () -> parser.parseValue("_"));
			assertThrows(EvitaSyntaxException.class, () -> parser.parseValue("attributeEqualsTrue('a')"));
			assertThrows(EvitaSyntaxException.class, () -> parser.parseValue("12 24"));
			assertThrows(EvitaSyntaxException.class, () -> parser.parseValue("query(collection('a'))"));
		}
	}
}
