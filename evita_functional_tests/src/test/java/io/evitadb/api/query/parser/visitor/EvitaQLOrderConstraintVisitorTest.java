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
import java.util.List;
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
        assertEquals(orderBy(attributeNatural("a"), attributeNatural("b",DESC )), constraint2);

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
    void shouldParseOrderGroupByConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("orderGroupBy(attributeNatural('a'))");
        assertEquals(orderGroupBy(attributeNatural("a")), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraintUnsafe("orderGroupBy(attributeNatural('a'),attributeNatural('b',DESC))");
        assertEquals(orderGroupBy(attributeNatural("a"), attributeNatural("b", DESC)), constraint2);

        final OrderConstraint constraint3 = parseOrderConstraintUnsafe("orderGroupBy( attributeNatural('a') ,  attributeNatural('b',  DESC ) )");
        assertEquals(orderGroupBy(attributeNatural("a"), attributeNatural("b", DESC)), constraint3);

        final OrderConstraint constraint4 = parseOrderConstraint("orderGroupBy()");
        assertEquals(orderGroupBy(), constraint4);
    }

    @Test
    void shouldNotParseOrderGroupByConstraint() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("orderGroupBy"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("orderGroupBy(attributeEquals('a',1))"));
    }

    @Test
    void shouldParseAttributeNaturalConstraint() {
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
    void shouldNotParseAttributeNaturalConstraint() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraint("attributeNatural('a',DESC)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraint("attributeNatural('a',?)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraint("attributeNatural('a',@b)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("attributeNatural"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("attributeNatural()"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("attributeNatural(10)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("attributeNatural('a', 'b')"));
    }

    @Test
    void shouldParseAttributeSetExactConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraintUnsafe("attributeSetExact('a', 1)");
        assertEquals(attributeSetExact("a", 1L), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraintUnsafe("attributeSetExact('a', 1, 2, 3)");
        assertEquals(attributeSetExact("a", 1L, 2L, 3L), constraint2);

        final OrderConstraint constraint3 = parseOrderConstraint("attributeSetExact('a', ?)", 1);
        assertEquals(attributeSetExact("a", 1), constraint3);

        final OrderConstraint constraint4 = parseOrderConstraint("attributeSetExact('a', ?)", List.of(1, 2, 3));
        assertEquals(attributeSetExact("a", 1, 2, 3), constraint4);

        final OrderConstraint constraint5 = parseOrderConstraint("attributeSetExact(?, ?)", "a", List.of(1, 2, 3));
        assertEquals(attributeSetExact("a", 1, 2, 3), constraint5);

        final OrderConstraint constraint6 = parseOrderConstraint("attributeSetExact('a', @val)", Map.of("val", 1));
        assertEquals(attributeSetExact("a", 1), constraint6);

        final OrderConstraint constraint7 = parseOrderConstraint("attributeSetExact('a', @val)", Map.of("val", List.of(1, 2, 3)));
        assertEquals(attributeSetExact("a", 1, 2, 3), constraint7);

        final OrderConstraint constraint8 = parseOrderConstraint(
            "attributeSetExact(@na, @val)",
            Map.of("na", "a", "val", List.of(1, 2, 3))
        );
        assertEquals(attributeSetExact("a", 1, 2, 3), constraint8);
    }

    @Test
    void shouldNotParseAttributeSetExactConstraint() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("attributeSetExact"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("attributeSetExact()"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("attributeSetExact('a')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraint("attributeSetExact('a',1)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraint("attributeSetExact('a',?)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraint("attributeSetExact('a',@b)"));
    }

    @Test
    void shouldParseAttributeSetInFilterConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("attributeSetInFilter('a')");
        assertEquals(attributeSetInFilter("a"), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraint("attributeSetInFilter(?)", "a");
        assertEquals(attributeSetInFilter("a"), constraint2);

        final OrderConstraint constraint3 = parseOrderConstraint("attributeSetInFilter(@na)", Map.of("na", "a"));
        assertEquals(attributeSetInFilter("a"), constraint3);
    }

    @Test
    void shouldNotParseAttributeSetInFilterConstraint() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("attributeSetInFilter"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("attributeSetInFilter()"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("attributeSetInFilter(1)"));
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
                    attributeNatural("c", DESC)
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
    void shouldParseEntityPrimaryKeyExactConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraintUnsafe("entityPrimaryKeyExact(1)");
        assertEquals(entityPrimaryKeyExact(1), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraintUnsafe("entityPrimaryKeyExact(1, 2, 3)");
        assertEquals(entityPrimaryKeyExact(1, 2, 3), constraint2);

        final OrderConstraint constraint3 = parseOrderConstraint("entityPrimaryKeyExact(?)", 1);
        assertEquals(entityPrimaryKeyExact(1), constraint3);

        final OrderConstraint constraint4 = parseOrderConstraint("entityPrimaryKeyExact(?)", List.of(1, 2, 3));
        assertEquals(entityPrimaryKeyExact(1, 2, 3), constraint4);

        final OrderConstraint constraint6 = parseOrderConstraint("entityPrimaryKeyExact(@val)", Map.of("val", 1));
        assertEquals(entityPrimaryKeyExact(1), constraint6);

        final OrderConstraint constraint7 = parseOrderConstraint("entityPrimaryKeyExact(@val)", Map.of("val", List.of(1, 2, 3)));
        assertEquals(entityPrimaryKeyExact(1, 2, 3), constraint7);
    }

    @Test
    void shouldNotParseEntityPrimaryKeyExactConstraint() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("entityPrimaryKeyExact"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("entityPrimaryKeyExact()"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("entityPrimaryKeyExact('a')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraint("entityPrimaryKeyExact(1)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraint("entityPrimaryKeyExact(?)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraint("entityPrimaryKeyExact(@b)"));
    }

    @Test
    void shouldParseEntityPrimaryKeyInFilterConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("entityPrimaryKeyInFilter()");
        assertEquals(entityPrimaryKeyInFilter(), constraint1);
    }

    @Test
    void shouldNotParseEntityPrimaryKeyInFilterConstraint() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("entityPrimaryKeyInFilter"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseOrderConstraintUnsafe("entityPrimaryKeyInFilter(1)"));
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
                attributeNatural("c", DESC)
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
