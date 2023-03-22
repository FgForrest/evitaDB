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

package io.evitadb.api.query.parser.visitor;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParseMode;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EvitaQLQueryVisitor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
class EvitaQLQueryVisitorTest {

    @Test
    void shouldParseQuery() {
        assertEquals(
            query(
                collection("a")
            ),
            parseQuery("query(collection('a'))")
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEqualsTrue("a"))
            ),
            parseQuery("query(collection('a'),filterBy(attributeEqualsTrue('a')))")
        );

        assertEquals(
            query(
                collection("a"),
                orderBy(attributeNatural("c"))
            ),
            parseQuery("query(collection('a'),orderBy(attributeNatural('c')))")
        );

        assertEquals(
            query(
                collection("a"),
                require(attributeContent())
            ),
            parseQuery("query(collection('a'),require(attributeContent()))")
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEquals("a", 1L)),
                orderBy(attributeNatural("c")),
                require(attributeContent())
            ),
            parseQuery(
                "query(require(attributeContent()),collection('a'),orderBy(attributeNatural('c')),filterBy(attributeEquals('a',?)))",
                1L
            )
        );

        assertEquals(
            query(
                collection("a"),
                filterBy(attributeEquals("a", 1L)),
                orderBy(attributeNatural("c")),
                require(attributeContent())
            ),
            parseQuery(
                "query(require(attributeContent()),collection('a'),orderBy(attributeNatural('c')),filterBy(attributeEquals('a',@value)))",
                Map.of(
                    "value", 1L
                )
            )
        );
    }

    @Test
    void shouldParseQueryUnsafe() {
        assertEquals(
                query(
                        collection("a")
                ),
                parseQueryUnsafe("query(collection('a'))")
        );

        assertEquals(
                query(
                        collection("a"),
                        filterBy(attributeEqualsTrue("a"))
                ),
                parseQueryUnsafe("query(collection('a'),filterBy(attributeEqualsTrue('a')))")
        );

        assertEquals(
                query(
                        collection("a"),
                        orderBy(attributeNatural("c"))
                ),
                parseQueryUnsafe("query(collection('a'),orderBy(attributeNatural('c')))")
        );

        assertEquals(
                query(
                        collection("a"),
                        require(attributeContent())
                ),
                parseQueryUnsafe("query(collection('a'),require(attributeContent()))")
        );

        assertEquals(
                query(
                        collection("a"),
                        filterBy(attributeEquals("a", 1L)),
                        orderBy(attributeNatural("c")),
                        require(attributeContent())
                ),
                parseQueryUnsafe("query(require(attributeContent()),collection('a'),orderBy(attributeNatural('c')),filterBy(attributeEquals('a',1)))")
        );

        assertEquals(
                query(
                        collection("a"),
                        filterBy(
                                and(
                                        attributeEqualsTrue("b"),
                                        attributeEquals("c", 5L)
                                )
                        ),
                        orderBy(
                                attributeNatural("c"),
                                priceNatural()
                        ),
                        require(attributeContent())
                ),
                parseQueryUnsafe(
                    """
                        query(
                            collection('a'),
                            filterBy(
                                and(
                                    attributeEqualsTrue('b'),
                                    attributeEquals('c', 5)
                                )
                            ),
                            orderBy(
                                attributeNatural('c'),
                                priceNatural()
                            ),
                            require(
                                attributeContent()
                            )
                        )
                        """
                )
        );
    }

    @Test
    void shouldNotParseQuery() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseQuery("query(require(attributeContent()),collection('a'),orderBy(attributeNatural('c')),filterBy(attributeEquals('a',1)))"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseQueryUnsafe("query"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseQueryUnsafe("query()"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseQueryUnsafe("query(filterBy(attributeEquals('a',1)))"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseQueryUnsafe("query(collection('a'),attributeEquals('b',1))"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseQueryUnsafe("query(collection('a'),attributeContent('b'))"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseQueryUnsafe("query(collection('a'),attributeContent())"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseQueryUnsafe("query(collection('a'),filterBy(attributeEquals('b',1)),attributeEquals('c',1))"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseQueryUnsafe("query(collection('a'),orderBy(attributeContent('c')),attributeContent('b'))"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseQueryUnsafe("query(collection('a'),require(entityFetch()),attributeContent())"));
    }


    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "query"
     *
     * @param string string to parse
     * @return query
     */
    private Query parseQueryUnsafe(@Nonnull String string) {
        final ParseContext context = new ParseContext();
        context.setMode(ParseMode.UNSAFE);
        return ParserExecutor.execute(
            context,
            () -> ParserFactory.getParser(string).query().accept(new EvitaQLQueryVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "query"
     *
     * @param string string to parse
     * @param positionalArguments positional arguments to substitute
     * @return query
     */
    private Query parseQuery(@Nonnull String string, @Nonnull Object... positionalArguments) {
        return ParserExecutor.execute(
            new ParseContext(positionalArguments),
            () -> ParserFactory.getParser(string).query().accept(new EvitaQLQueryVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "query"
     *
     * @param string string to parse
     * @param namedArguments named arguments to substitute
     * @return query
     */
    private Query parseQuery(@Nonnull String string, @Nonnull Map<String, Object> namedArguments) {
        return ParserExecutor.execute(
            new ParseContext(namedArguments),
            () -> ParserFactory.getParser(string).query().accept(new EvitaQLQueryVisitor())
        );
    }
}
