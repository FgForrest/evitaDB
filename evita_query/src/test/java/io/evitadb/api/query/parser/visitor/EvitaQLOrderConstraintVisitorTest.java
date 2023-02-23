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

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.ReferenceProperty;
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParseMode;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EvitaQLOrderConstraintVisitor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
class EvitaQLOrderConstraintVisitorTest {

    @Test
    void shouldParseOrderByConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("orderBy(attributeNatural('a'))");
        assertEquals(orderBy(attributeNatural("a")), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraintUnsafe("orderBy(attributeNatural('a'),attributeNatural('b',DESC))");
        assertEquals(orderBy(attributeNatural("a"), attributeNatural("b", DESC)), constraint2);

        final OrderConstraint constraint3 = parseOrderConstraintUnsafe("orderBy( attributeNatural('a') ,  attributeNatural('b',  DESC ) )");
        assertEquals(orderBy(attributeNatural("a"), attributeNatural("b", DESC)), constraint3);

        final OrderConstraint constraint4 = parseOrderConstraint("orderBy()");
        assertEquals(orderBy(), constraint4);
    }

    @Test
    void shouldNotParseOrderByConstraint() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("orderBy"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("orderBy(attributeEquals('a',1))"));
    }

    @Test
    void shouldParseAttributeConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("attributeNatural('a')");
        assertEquals(attributeNatural("a"), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraint("attributeNatural( 'a'  )");
        assertEquals(attributeNatural("a"), constraint2);

        final OrderConstraint constraint3 = parseOrderConstraintUnsafe("attributeNatural('a',DESC)");
        assertEquals(attributeNatural("a", DESC), constraint3);

        final OrderConstraint constraint4 = parseOrderConstraintUnsafe("attributeNatural( 'a' ,  DESC )");
        assertEquals(attributeNatural("a", DESC), constraint4);

        final OrderConstraint constraint5 = parseOrderConstraint("attributeNatural(?,?)", "a", DESC);
        assertEquals(attributeNatural("a", DESC), constraint5);

        final OrderConstraint constraint6 = parseOrderConstraint("attributeNatural(@name,@dir)", Map.of("name", "a", "dir", DESC));
        assertEquals(attributeNatural("a", DESC), constraint6);
    }

    @Test
    void shouldNotParseAttributeConstraint() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraint("attributeNatural('a',DESC)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraint("attributeNatural('a',?)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraint("attributeNatural('a',@b)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("attributeNatural"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("attributeNatural()"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("attributeNatural(10)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("attributeNatural('a', 'b')"));
    }

    @Test
    void shouldParsePriceConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("priceNatural()");
        assertEquals(priceNatural(), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraint("priceNatural(  )");
        assertEquals(priceNatural(), constraint2);

        final OrderConstraint constraint3 = parseOrderConstraintUnsafe("priceNatural(DESC)");
        assertEquals(priceNatural(DESC), constraint3);

        final OrderConstraint constraint4 = parseOrderConstraintUnsafe("priceNatural(  DESC )");
        assertEquals(priceNatural(DESC), constraint4);

        final OrderConstraint constraint5 = parseOrderConstraint("priceNatural(?)", DESC);
        assertEquals(priceNatural(DESC), constraint5);

        final OrderConstraint constraint6 = parseOrderConstraint("priceNatural(@dir)", Map.of("dir", DESC));
        assertEquals(priceNatural(DESC), constraint6);
    }

    @Test
    void shouldNotParsePriceConstraint() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraint("priceNatural(DESC)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraint("priceNatural(?)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraint("priceNatural(@a)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("priceNatural"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("priceNatural('a')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("priceNatural('a', 'b')"));
    }

    @Test
    void shouldParseRandomConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("random()");
        assertEquals(random(), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraint("random (  )");
        assertEquals(random(), constraint2);
    }

    @Test
    void shouldNotParseRandomConstraint() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("random"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("random('a')"));
    }

    @Test
    void shouldParseReferencePropertyConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("referenceProperty('a',attributeNatural('b'))");
        assertEquals(
                referenceProperty(
                    "a",
                    attributeNatural("b")
                ),
                constraint1
        );

        final OrderConstraint constraint2 = parseOrderConstraintUnsafe("referenceProperty('a',attributeNatural('b'),attributeNatural('c',DESC))");
        assertEquals(
                referenceProperty(
                    "a",
                    attributeNatural("b"),
                    attributeNatural("c", DESC)
                ),
                constraint2
        );

        final OrderConstraint constraint3 = parseOrderConstraintUnsafe("referenceProperty ( 'a' , attributeNatural('b')  , attributeNatural('c',  DESC) )");
        assertEquals(
                referenceProperty(
                    "a",
                    attributeNatural("b"),
                    attributeNatural("c",DESC)
                ),
                constraint3
        );

        final OrderConstraint constraint4 = parseOrderConstraintUnsafe("referenceProperty('a',attributeNatural('b'))");
        assertEquals(
                new ReferenceProperty(
                    "a",
                    attributeNatural("b")
                ),
                constraint4
        );

        final OrderConstraint constraint5 = parseOrderConstraint("referenceProperty(?,attributeNatural('b'))", "a");
        assertEquals(
            referenceProperty(
                "a",
                attributeNatural("b")
            ),
            constraint5
        );

        final OrderConstraint constraint6 = parseOrderConstraint("referenceProperty(@a,attributeNatural('b'))", Map.of("a", "a"));
        assertEquals(
            referenceProperty(
                "a",
                attributeNatural("b")
            ),
            constraint6
        );
    }

    @Test
    void shouldNotParseReferencePropertyConstraint() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("referenceProperty(?,attributeNatural('b'))"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("referenceProperty(@a,attributeNatural('b'))"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("referenceProperty"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("referenceProperty()"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("referenceProperty('a')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("referenceProperty('a',1)"));
    }

    @Test
    void shouldParseEntityPropertyConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("entityProperty(attributeNatural('b'))");
        assertEquals(entityProperty(attributeNatural("b")), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraintUnsafe("entityProperty(attributeNatural('b'),attributeNatural('c',DESC))");
        assertEquals(
            entityProperty(
                attributeNatural("b"),
                attributeNatural("c", DESC)
            ),
            constraint2
        );

        final OrderConstraint constraint3 = parseOrderConstraintUnsafe("entityProperty (  attributeNatural('b')  , attributeNatural('c',  DESC) )");
        assertEquals(
            entityProperty(
                attributeNatural("b"),
                attributeNatural("c",DESC)
            ),
            constraint3
        );

        final OrderConstraint constraint4 = parseOrderConstraint("entityProperty(attributeNatural(?))", "a");
        assertEquals(
            entityProperty(
                attributeNatural("a")
            ),
            constraint4
        );
    }

    @Test
    void shouldNotParseEntityPropertyConstraint() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("entityProperty"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("entityProperty()"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("entityProperty('a')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("entityProperty('a',priceNatural())"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("entityProperty(?)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("entityProperty(and())"));
    }


    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "orderConstraint"
     *
     * @param string string to parse
     * @return parsed constraint
     */
    private OrderConstraint parseOrderConstraintUnsafe(@Nonnull String string) {
        final ParseContext context = new ParseContext();
        context.setMode(ParseMode.UNSAFE);
        return ParserExecutor.execute(
            context,
            () -> ParserFactory.getParser(string).orderConstraint().accept(new EvitaQLOrderConstraintVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "orderConstraint"
     *
     * @param string string to parse
     * @param positionalArguments positional arguments to substitute
     * @return parsed constraint
     */
    private OrderConstraint parseOrderConstraint(@Nonnull String string, @Nonnull Object... positionalArguments) {
        return ParserExecutor.execute(
            new ParseContext(positionalArguments),
            () -> ParserFactory.getParser(string).orderConstraint().accept(new EvitaQLOrderConstraintVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "orderConstraint"
     *
     * @param string string to parse
     * @param namedArguments named arguments to substitute
     * @return parsed constraint
     */
    private OrderConstraint parseOrderConstraint(@Nonnull String string, @Nonnull Map<String, Object> namedArguments) {
        return ParserExecutor.execute(
            new ParseContext(namedArguments),
            () -> ParserFactory.getParser(string).orderConstraint().accept(new EvitaQLOrderConstraintVisitor())
        );
    }
}
