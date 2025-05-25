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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link DefaultQueryParser}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
class DefaultQueryParserTest {

    private final DefaultQueryParser parser = new DefaultQueryParser();

    @Test
    void shouldGetInstance() {
        final DefaultQueryParser queryParser1 = DefaultQueryParser.getInstance();
        final DefaultQueryParser queryParser2 = DefaultQueryParser.getInstance();
        assertSame(queryParser1, queryParser2);
    }

    @Test
    void shouldParseQueryString() {
        assertEquals(
            query(
                collection("a")
            ),
	        this.parser.parseQueryUnsafe("query(collection('a'))")
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b"))
            ),
	        this.parser.parseQuery(
                "query(collection(?),filterBy(attributeEqualsTrue(?)))",
                "a",
                "b"
            )
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b"))
            ),
	        this.parser.parseQuery(
                "query(collection(?),filterBy(attributeEqualsTrue(?)))",
                List.of("a", "b")
            )
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b"))
            ),
	        this.parser.parseQuery(
                "query(collection(@collection),filterBy(attributeEqualsTrue(@attr)))",
                Map.of(
                    "collection", "a",
                    "attr", "b"
                )
            )
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b"))
            ),
	        this.parser.parseQuery(
                "query(collection(@collection),filterBy(attributeEqualsTrue(?)))",
                Map.of("collection", "a"),
                "b"
            )
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b"))
            ),
	        this.parser.parseQuery(
                "query(collection(@collection),filterBy(attributeEqualsTrue(?)))",
                Map.of("collection", "a"),
                List.of("b")
            )
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b"))
            ),
	        this.parser.parseQuery("""
                query(
                    collection(?),
                    filterBy(
                        attributeEqualsTrue(?)
                    )
                )
                """,
                "a",
                "b"
            )
        );

        assertEquals(
            query(
                collection("PRODUCT"),
                filterBy(
                    entityPrimaryKeyInSet(1)
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
            ),
	        this.parser.parseQuery("""
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

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b")),
                orderBy(attributeNatural("c"))
            ),
	        this.parser.parseQuery(
                """
                    query(
                        collection(?),
                        filterBy(
                            attributeEqualsTrue(?),
                        ),
                        orderBy(attributeNatural(?),)
                    )
                    """,
                "a",
                "b",
                "c"
            )
        );
    }

    @Test
    void shouldParseQueryUnsafeString() {
        assertEquals(
            query(
                collection("a")
            ),
	        this.parser.parseQueryUnsafe("query(collection('a'))")
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b")),
                orderBy(random()),
                require(attributeContentAll())
            ),
	        this.parser.parseQueryUnsafe("query(collection('a'),filterBy(attributeEqualsTrue('b')),orderBy(random()),require(attributeContentAll()))")
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b"))
            ),
	        this.parser.parseQueryUnsafe(
                "query(collection(?),filterBy(attributeEqualsTrue(?)))",
                "a",
                "b"
            )
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b"))
            ),
	        this.parser.parseQueryUnsafe(
                "query(collection(@collection),filterBy(attributeEqualsTrue(@attr)))",
                Map.of(
                    "collection", "a",
                    "attr", "b"
                )
            )
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b"))
            ),
	        this.parser.parseQueryUnsafe(
                "query(collection(@collection),filterBy(attributeEqualsTrue(?)))",
                Map.of("collection", "a"),
                "b"
            )
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b"))
            ),
	        this.parser.parseQueryUnsafe("""
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
    void shouldParseQueryStringWithComments() {
        assertEquals(
            query(
                collection("PRODUCT"),
                filterBy(
                    entityPrimaryKeyInSet(1)
                ),
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
	        this.parser.parseQuery("""
                        // this is a comment
                        query(
                         	collection(?),
                         	// this is a inner comment
                         	filterBy(
                         		entityPrimaryKeyInSet(?) // this is a inline comment
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
    void shouldParseQueryStringWithDifferentQuotationMarks() {
        assertEquals(
            query(
                collection("Product"),
                filterBy(
                    attributeEquals("a", "b")
                )
            ),
	        this.parser.parseQueryUnsafe("""
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
                filterBy(
                    attributeEquals("a", "b")
                )
            ),
	        this.parser.parseQueryUnsafe("""
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
                filterBy(
                    attributeEquals("a", "b")
                )
            ),
	        this.parser.parseQueryUnsafe("""
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
    void shouldNotParseQueryString() {
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQuery("query(filterBy(attributeEquals('a','b')))"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQuery("query(collection(?))"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQuery("query(collection(@collection))"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQuery("query(collection(@collection))", Map.of("attr", "some")));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQuery(""));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQuery("'b'"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQuery("attributeEqualsTrue('a')"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQuery("query(collection('a')) query(collection('b'))"));
    }

    @Test
    void shouldNotParseQueryUnsafeString() {
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQueryUnsafe("query(collection(?))"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQueryUnsafe("query(collection(@collection))"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQueryUnsafe("query(collection(@collection))", Map.of("attr", "some")));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQueryUnsafe(""));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQueryUnsafe("'b'"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQueryUnsafe("attributeEqualsTrue('a')"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQueryUnsafe("query(collection('a')) query(collection('b'))"));
    }

    @Test
    void shouldParseQueryWithIncorrectQuotationMarks() {
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseQueryUnsafe("query(collection('a\"))"));
    }

    @Test
    void shouldParseHeadConstraintListString() {
        final List<HeadConstraint> constraintList2 = this.parser.parseHeadConstraintListUnsafe("collection('product'),collection('brand')");
        assertEquals(
            List.of(collection("product"), collection("brand")),
            constraintList2
        );

        final List<HeadConstraint> constraintList3 = this.parser.parseHeadConstraintList("collection(?)", "product");
        assertEquals(
            List.of(collection("product")),
            constraintList3
        );

        final List<HeadConstraint> constraintList4 = this.parser.parseHeadConstraintList(
            "collection(@product),collection(@col)",
            Map.of("product", "product", "col", "brand")
        );
        assertEquals(
            List.of(collection("product"), collection("brand")),
            constraintList4
        );

        final List<HeadConstraint> constraintList5 = this.parser.parseHeadConstraintList(
            "collection(?),collection(@col)",
            Map.of("col", "brand"),
            "product"
        );
        assertEquals(
            List.of(collection("product"), collection("brand")),
            constraintList5
        );
    }

    @Test
    void shouldNotParseHeadConstraintList() {
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseHeadConstraintList("attributeEqualsTrue('code')"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseHeadConstraintList("collection('product'),attributeEqualsTrue('code')"));
    }

    @Test
    void shouldParseHeadConstraintListUnsafeString() {
        final List<HeadConstraint> constraintList2 = this.parser.parseHeadConstraintListUnsafe("collection('product'),collection('brand')");
        assertEquals(
            List.of(collection("product"), collection("brand")),
            constraintList2
        );
    }

    @Test
    void shouldNotParseHeadConstraintUnsafeList() {
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseHeadConstraintListUnsafe("attributeEqualsTrue('code')"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseHeadConstraintListUnsafe("collection('product'),attributeEqualsTrue('code')"));
    }

    @Test
    void shouldParseFilterConstraintList() {
        final List<FilterConstraint> constraintList2 = this.parser.parseFilterConstraintListUnsafe("attributeEqualsTrue('code'),attributeEqualsTrue('age')");
        assertEquals(
            List.of(attributeEqualsTrue("code"), attributeEqualsTrue("age")),
            constraintList2
        );

        final List<FilterConstraint> constraintList3 = this.parser.parseFilterConstraintList("attributeEqualsTrue(?)", "code");
        assertEquals(
            List.of(attributeEqualsTrue("code")),
            constraintList3
        );

        final List<FilterConstraint> constraintList4 = this.parser.parseFilterConstraintList(
            "attributeEqualsTrue(@code),attributeEqualsTrue(@name)",
            Map.of("code", "code", "name", "age")
        );
        assertEquals(
            List.of(attributeEqualsTrue("code"), attributeEqualsTrue("age")),
            constraintList4
        );

        final List<FilterConstraint> constraintList5 = this.parser.parseFilterConstraintList(
            "attributeEqualsTrue(?),attributeEqualsTrue(@name)",
            Map.of("name", "age"),
            "code"
        );
        assertEquals(
            List.of(attributeEqualsTrue("code"), attributeEqualsTrue("age")),
            constraintList5
        );
    }

    @Test
    void shouldNotParseFilterConstraintList() {
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseFilterConstraintList("collection('code')"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseFilterConstraintList("attributeEqualsTrue('product'),collection('code')"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseFilterConstraintList("attributeEquals('code',2)"));
    }

    @Test
    void shouldParseFilterConstraintUnsafeList() {
        final List<FilterConstraint> constraintList2 = this.parser.parseFilterConstraintListUnsafe("attributeEquals('code', 1),attributeEquals('age', 2)");
        assertEquals(
            List.of(attributeEquals("code", 1L), attributeEquals("age", 2L)),
            constraintList2
        );
    }

    @Test
    void shouldNotParseFilterConstraintUnsafeList() {
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseFilterConstraintListUnsafe("collection('code')"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseFilterConstraintListUnsafe("attributeEqualsTrue('product'),collection('code')"));
    }

    @Test
    void shouldParseOrderConstraintList() {
        final List<OrderConstraint> constraintList2 = this.parser.parseOrderConstraintListUnsafe("attributeNatural('code'),attributeNatural('age')");
        assertEquals(
            List.of(attributeNatural("code"), attributeNatural("age")),
            constraintList2
        );

        final List<OrderConstraint> constraintList3 = this.parser.parseOrderConstraintList("attributeNatural(?)", "code");
        assertEquals(
            List.of(attributeNatural("code")),
            constraintList3
        );

        final List<OrderConstraint> constraintList4 = this.parser.parseOrderConstraintList(
            "attributeNatural(@code),attributeNatural(@name)",
            Map.of("code", "code", "name", "age")
        );
        assertEquals(
            List.of(attributeNatural("code"), attributeNatural("age")),
            constraintList4
        );

        final List<OrderConstraint> constraintList5 = this.parser.parseOrderConstraintList(
            "attributeNatural(?),attributeNatural(@name)",
            Map.of("name", "age"),
            "code"
        );
        assertEquals(
            List.of(attributeNatural("code"), attributeNatural("age")),
            constraintList5
        );
    }

    @Test
    void shouldNotParseOrderConstraintList() {
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseOrderConstraintList("collection('code')"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseOrderConstraintList("attributeNatural('product'),collection('code')"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseOrderConstraintList("attributeNatural('code',DESC)"));
    }

    @Test
    void shouldParseOrderConstraintListUnsafe() {
        final List<OrderConstraint> constraintList2 = this.parser.parseOrderConstraintListUnsafe("attributeNatural('code',ASC),attributeNatural('age',DESC)");
        assertEquals(
            List.of(attributeNatural("code", OrderDirection.ASC), attributeNatural("age", OrderDirection.DESC)),
            constraintList2
        );
    }

    @Test
    void shouldNotParseOrderConstraintListUnsafe() {
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseOrderConstraintListUnsafe("collection('code')"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseOrderConstraintListUnsafe("attributeNatural('product'),collection('code')"));
    }

    @Test
    void shouldParseRequireConstraintList() {
        final List<RequireConstraint> constraintList2 = this.parser.parseRequireConstraintListUnsafe("attributeContent('code'),attributeContent('age')");
        assertEquals(
            List.of(attributeContent("code"), attributeContent("age")),
            constraintList2
        );

        final List<RequireConstraint> constraintList3 = this.parser.parseRequireConstraintList("attributeContent(?)", "code");
        assertEquals(
            List.of(attributeContent("code")),
            constraintList3
        );

        final List<RequireConstraint> constraintList4 = this.parser.parseRequireConstraintList(
            "attributeContent(@code),attributeContent(@name)",
            Map.of("code", "code", "name", "age")
        );
        assertEquals(
            List.of(attributeContent("code"), attributeContent("age")),
            constraintList4
        );

        final List<RequireConstraint> constraintList5 = this.parser.parseRequireConstraintList(
            "attributeContent(?),attributeContent(@name)",
            Map.of("name", "age"),
            "code"
        );
        assertEquals(
            List.of(attributeContent("code"), attributeContent("age")),
            constraintList5
        );
    }

    @Test
    void shouldNotParseRequireConstraintList() {
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseRequireConstraintList("collection('product')"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseRequireConstraintList("attributeContent('code'),collection('product')"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseRequireConstraintList("priceType(WITH_TAX)"));
    }

    @Test
    void shouldParseRequireConstraintListUnsafe() {
        final List<RequireConstraint> constraintList2 = this.parser.parseRequireConstraintListUnsafe("priceType(WITH_TAX),attributeContent('age')");
        assertEquals(
            List.of(priceType(QueryPriceMode.WITH_TAX), attributeContent("age")),
            constraintList2
        );
    }

    @Test
    void shouldNotParseRequireConstraintListUnsafe() {
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseRequireConstraintListUnsafe("collection('product')"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseRequireConstraintListUnsafe("attributeContent('code'),collection('product')"));
    }

    @Test
    void shouldParseValueString() {
        assertEquals("a", this.parser.parseValue("'a'"));
        assertEquals(123L, (Long) this.parser.parseValue("123"));
        assertEquals(EnumWrapper.fromString("SOME_ENUM"), this.parser.parseValue("SOME_ENUM"));
        assertEquals("a", this.parser.parseValue("?", "a"));
        assertEquals("a", this.parser.parseValue("@name", Map.of("name", "a")));
    }

    @Test
    void shouldNotParseValueString() {
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseValue("?"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseValue("@name"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseValue("@name", Map.of("col", "some")));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseValue(""));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseValue("_"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseValue("attributeEqualsTrue('a')"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseValue("12 24"));
        assertThrows(EvitaSyntaxException.class, () -> this.parser.parseValue("query(collection('a'))"));
    }
}
