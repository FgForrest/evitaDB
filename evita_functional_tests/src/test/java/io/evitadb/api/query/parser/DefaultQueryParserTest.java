/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
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
            parser.parseQueryUnsafe("query(collection('a'))")
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b"))
            ),
            parser.parseQuery(
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
            parser.parseQuery(
                "query(collection(?),filterBy(attributeEqualsTrue(?)))",
                List.of("a", "b")
            )
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b"))
            ),
            parser.parseQuery(
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
            parser.parseQuery(
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
            parser.parseQuery(
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
            parser.parseQuery("""
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
    void shouldParseQueryUnsafeString() {
        assertEquals(
            query(
                collection("a")
            ),
            parser.parseQueryUnsafe("query(collection('a'))")
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b")),
                orderBy(random()),
                require(attributeContentAll())
            ),
            parser.parseQueryUnsafe("query(collection('a'),filterBy(attributeEqualsTrue('b')),orderBy(random()),require(attributeContentAll()))")
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("b"))
            ),
            parser.parseQueryUnsafe(
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
            parser.parseQueryUnsafe(
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
            parser.parseQueryUnsafe(
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
            parser.parseQuery("""
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
    void shouldNotParseQueryString() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseQuery("query(filterBy(attributeEquals('a','b')))"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseQuery("query(collection(?))"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseQuery("query(collection(@collection))"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseQuery("query(collection(@collection))", Map.of("attr", "some")));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseQuery(""));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseQuery("'b'"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseQuery("attributeEqualsTrue('a')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseQuery("query(collection('a')) query(collection('b'))"));
    }

    @Test
    void shouldNotParseQueryUnsafeString() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseQueryUnsafe("query(collection(?))"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseQueryUnsafe("query(collection(@collection))"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseQueryUnsafe("query(collection(@collection))", Map.of("attr", "some")));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseQueryUnsafe(""));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseQueryUnsafe("'b'"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseQueryUnsafe("attributeEqualsTrue('a')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseQueryUnsafe("query(collection('a')) query(collection('b'))"));
    }

    @Test
    void shouldParseHeadConstraintListString() {
        final List<HeadConstraint> constraintList2 = parser.parseHeadConstraintList("collection('product'),collection('brand')");
        assertEquals(
            List.of(collection("product"), collection("brand")),
            constraintList2
        );

        final List<HeadConstraint> constraintList3 = parser.parseHeadConstraintList("collection(?)", "product");
        assertEquals(
            List.of(collection("product")),
            constraintList3
        );

        final List<HeadConstraint> constraintList4 = parser.parseHeadConstraintList(
            "collection('product'),collection(@col)",
            Map.of("col", "brand")
        );
        assertEquals(
            List.of(collection("product"), collection("brand")),
            constraintList4
        );

        final List<HeadConstraint> constraintList5 = parser.parseHeadConstraintList(
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
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseHeadConstraintList("attributeEqualsTrue('code')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseHeadConstraintList("collection('product'),attributeEqualsTrue('code')"));
    }

    @Test
    void shouldParseHeadConstraintListUnsafeString() {
        final List<HeadConstraint> constraintList2 = parser.parseHeadConstraintListUnsafe("collection('product'),collection('brand')");
        assertEquals(
            List.of(collection("product"), collection("brand")),
            constraintList2
        );
    }

    @Test
    void shouldNotParseHeadConstraintUnsafeList() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseHeadConstraintListUnsafe("attributeEqualsTrue('code')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseHeadConstraintListUnsafe("collection('product'),attributeEqualsTrue('code')"));
    }

    @Test
    void shouldParseFilterConstraintList() {
        final List<FilterConstraint> constraintList2 = parser.parseFilterConstraintList("attributeEqualsTrue('code'),attributeEqualsTrue('age')");
        assertEquals(
            List.of(attributeEqualsTrue("code"), attributeEqualsTrue("age")),
            constraintList2
        );

        final List<FilterConstraint> constraintList3 = parser.parseFilterConstraintList("attributeEqualsTrue(?)", "code");
        assertEquals(
            List.of(attributeEqualsTrue("code")),
            constraintList3
        );

        final List<FilterConstraint> constraintList4 = parser.parseFilterConstraintList(
            "attributeEqualsTrue('code'),attributeEqualsTrue(@name)",
            Map.of("name", "age")
        );
        assertEquals(
            List.of(attributeEqualsTrue("code"), attributeEqualsTrue("age")),
            constraintList4
        );

        final List<FilterConstraint> constraintList5 = parser.parseFilterConstraintList(
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
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseFilterConstraintList("collection('code')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseFilterConstraintList("attributeEqualsTrue('product'),collection('code')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseFilterConstraintList("attributeEquals('code',2)"));
    }

    @Test
    void shouldParseFilterConstraintUnsafeList() {
        final List<FilterConstraint> constraintList2 = parser.parseFilterConstraintListUnsafe("attributeEquals('code', 1),attributeEquals('age', 2)");
        assertEquals(
            List.of(attributeEquals("code", 1L), attributeEquals("age", 2L)),
            constraintList2
        );
    }

    @Test
    void shouldNotParseFilterConstraintUnsafeList() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseFilterConstraintListUnsafe("collection('code')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseFilterConstraintListUnsafe("attributeEqualsTrue('product'),collection('code')"));
    }

    @Test
    void shouldParseOrderConstraintList() {
        final List<OrderConstraint> constraintList2 = parser.parseOrderConstraintList("attributeNatural('code'),attributeNatural('age')");
        assertEquals(
            List.of(attributeNatural("code"), attributeNatural("age")),
            constraintList2
        );

        final List<OrderConstraint> constraintList3 = parser.parseOrderConstraintList("attributeNatural(?)", "code");
        assertEquals(
            List.of(attributeNatural("code")),
            constraintList3
        );

        final List<OrderConstraint> constraintList4 = parser.parseOrderConstraintList(
            "attributeNatural('code'),attributeNatural(@name)",
            Map.of("name", "age")
        );
        assertEquals(
            List.of(attributeNatural("code"), attributeNatural("age")),
            constraintList4
        );

        final List<OrderConstraint> constraintList5 = parser.parseOrderConstraintList(
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
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseOrderConstraintList("collection('code')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseOrderConstraintList("attributeNatural('product'),collection('code')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseOrderConstraintList("attributeNatural('code',DESC)"));
    }

    @Test
    void shouldParseOrderConstraintListUnsafe() {
        final List<OrderConstraint> constraintList2 = parser.parseOrderConstraintListUnsafe("attributeNatural('code',ASC),attributeNatural('age',DESC)");
        assertEquals(
            List.of(attributeNatural("code", OrderDirection.ASC), attributeNatural("age", OrderDirection.DESC)),
            constraintList2
        );
    }

    @Test
    void shouldNotParseOrderConstraintListUnsafe() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseOrderConstraintListUnsafe("collection('code')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseOrderConstraintListUnsafe("attributeNatural('product'),collection('code')"));
    }

    @Test
    void shouldParseRequireConstraintList() {
        final List<RequireConstraint> constraintList2 = parser.parseRequireConstraintList("attributeContent('code'),attributeContent('age')");
        assertEquals(
            List.of(attributeContent("code"), attributeContent("age")),
            constraintList2
        );

        final List<RequireConstraint> constraintList3 = parser.parseRequireConstraintList("attributeContent(?)", "code");
        assertEquals(
            List.of(attributeContent("code")),
            constraintList3
        );

        final List<RequireConstraint> constraintList4 = parser.parseRequireConstraintList(
            "attributeContent('code'),attributeContent(@name)",
            Map.of("name", "age")
        );
        assertEquals(
            List.of(attributeContent("code"), attributeContent("age")),
            constraintList4
        );

        final List<RequireConstraint> constraintList5 = parser.parseRequireConstraintList(
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
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseRequireConstraintList("collection('product')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseRequireConstraintList("attributeContent('code'),collection('product')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseRequireConstraintList("priceType(WITH_TAX)"));
    }

    @Test
    void shouldParseRequireConstraintListUnsafe() {
        final List<RequireConstraint> constraintList2 = parser.parseRequireConstraintListUnsafe("priceType(WITH_TAX),attributeContent('age')");
        assertEquals(
            List.of(priceType(QueryPriceMode.WITH_TAX), attributeContent("age")),
            constraintList2
        );
    }

    @Test
    void shouldNotParseRequireConstraintListUnsafe() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseRequireConstraintListUnsafe("collection('product')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseRequireConstraintListUnsafe("attributeContent('code'),collection('product')"));
    }

    @Test
    void shouldParseClassifierString() {
        assertEquals("a", parser.parseClassifier("'a'"));
        assertEquals("a", parser.parseClassifier("?", "a"));
        assertEquals("a", parser.parseClassifier("@param", Map.of("param", "a")));
    }

    @Test
    void shouldNotParseClassifierString() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseClassifier("?"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseClassifier("?", 1L));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseClassifier("@name"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseClassifier("@name", Map.of("col", "some")));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseClassifier("@name", Map.of("name", 1L)));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseClassifier("10"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseClassifier(""));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseClassifier("_"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseClassifier("attributeEqualsTrue('a')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseClassifier("12 24"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseClassifier("query(collection('a'))"));
    }

    @Test
    void shouldParseValueString() {
        assertEquals("a", parser.parseValue("'a'"));
        assertEquals(123L, (Long) parser.parseValue("123"));
        assertEquals(EnumWrapper.fromString("SOME_ENUM"), parser.parseValue("SOME_ENUM"));
        assertEquals("a", parser.parseValue("?", "a"));
        assertEquals("a", parser.parseValue("@name", Map.of("name", "a")));
    }

    @Test
    void shouldNotParseValueString() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseValue("?"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseValue("@name"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseValue("@name", Map.of("col", "some")));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseValue(""));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseValue("_"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseValue("attributeEqualsTrue('a')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseValue("12 24"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parser.parseValue("query(collection('a'))"));
    }
}
