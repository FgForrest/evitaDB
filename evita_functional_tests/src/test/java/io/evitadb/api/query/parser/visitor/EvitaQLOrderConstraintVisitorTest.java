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

package io.evitadb.api.query.parser.visitor;

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.PickFirstByEntityProperty;
import io.evitadb.api.query.order.ReferenceProperty;
import io.evitadb.api.query.order.TraversalMode;
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParseMode;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
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
        final OrderConstraint constraint1 = parseOrderConstraint("orderBy(attributeNatural(?))", "a");
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
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("orderBy"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("orderBy(attributeNatural('a'))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("orderBy(attributeEquals('a',1))"));
    }

    @Test
    void shouldParseOrderGroupByConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("orderGroupBy(attributeNatural(?))", "a");
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
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("orderGroupBy"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("orderGroupBy(attributeEquals('a',1))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("orderGroupBy(attributeNatural('a'))"));
    }

    @Test
    void shouldParseAttributeNaturalConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("attributeNatural(?)", "a");
        assertEquals(attributeNatural("a"), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraint("attributeNatural( ?  )", "a");
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
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("attributeNatural('a',DESC)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("attributeNatural('a',?)", DESC));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("attributeNatural('a',?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("attributeNatural('a',@b)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("attributeNatural"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("attributeNatural()"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("attributeNatural(10)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("attributeNatural('a', 'b')"));
    }

    @Test
    void shouldParseAttributeSetExactConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraintUnsafe("attributeSetExact('a', 1)");
        assertEquals(attributeSetExact("a", 1L), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraintUnsafe("attributeSetExact('a', 1, 2, 3)");
        assertEquals(attributeSetExact("a", 1L, 2L, 3L), constraint2);

        final OrderConstraint constraint3 = parseOrderConstraint("attributeSetExact(?, ?)", "a", 1);
        assertEquals(attributeSetExact("a", 1), constraint3);

        final OrderConstraint constraint5 = parseOrderConstraint("attributeSetExact(?, ?)", "a", List.of(1, 2, 3));
        assertEquals(attributeSetExact("a", 1, 2, 3), constraint5);

        final OrderConstraint constraint6 = parseOrderConstraint("attributeSetExact(@name, @val)", Map.of("name", "a", "val", 1));
        assertEquals(attributeSetExact("a", 1), constraint6);

        final OrderConstraint constraint7 = parseOrderConstraint("attributeSetExact(@name, @val)", Map.of("name", "a", "val", List.of(1, 2, 3)));
        assertEquals(attributeSetExact("a", 1, 2, 3), constraint7);

        final OrderConstraint constraint8 = parseOrderConstraint(
            "attributeSetExact(@na, @val)",
            Map.of("na", "a", "val", List.of(1, 2, 3))
        );
        assertEquals(attributeSetExact("a", 1, 2, 3), constraint8);
    }

    @Test
    void shouldNotParseAttributeSetExactConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("attributeSetExact"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("attributeSetExact()"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("attributeSetExact('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("attributeSetExact('a',1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("attributeSetExact('a',?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("attributeSetExact('a',?)", 1));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("attributeSetExact('a',@b)"));
    }

    @Test
    void shouldParseAttributeSetInFilterConstraint() {
        final OrderConstraint constraint2 = parseOrderConstraint("attributeSetInFilter(?)", "a");
        assertEquals(attributeSetInFilter("a"), constraint2);

        final OrderConstraint constraint3 = parseOrderConstraint("attributeSetInFilter(@na)", Map.of("na", "a"));
        assertEquals(attributeSetInFilter("a"), constraint3);
    }

    @Test
    void shouldNotParseAttributeSetInFilterConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("attributeSetInFilter"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("attributeSetInFilter()"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("attributeSetInFilter(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("attributeSetInFilter('a')"));
    }

    @Test
    void shouldParsePriceNaturalConstraint() {
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
    void shouldNotParsePriceNaturalConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("priceNatural(DESC)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("priceNatural(?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("priceNatural(@a)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("priceNatural"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("priceNatural('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("priceNatural('a', 'b')"));
    }

    @Test
    void shouldParsePriceDiscountConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraintUnsafe("priceDiscount('reference', 'basic')");
        assertEquals(priceDiscount("reference", "basic"), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraintUnsafe("priceDiscount( 'basic' )");
        assertEquals(priceDiscount("basic"), constraint2);

        final OrderConstraint constraint3 = parseOrderConstraintUnsafe("priceDiscount(ASC, 'reference', 'basic')");
        assertEquals(priceDiscount(ASC, "reference", "basic"), constraint3);

        final OrderConstraint constraint4 = parseOrderConstraintUnsafe("priceDiscount(  ASC, 'basic' )");
        assertEquals(priceDiscount(ASC, "basic"), constraint4);

        final OrderConstraint constraint5 = parseOrderConstraint("priceDiscount(?, ?)", ASC, "basic");
        assertEquals(priceDiscount(ASC, "basic"), constraint5);

        final OrderConstraint constraint6 = parseOrderConstraint("priceDiscount(@dir, @ref1, @ref2)", Map.of("dir", ASC, "ref1", "reference", "ref2", "basic"));
        assertEquals(priceDiscount(ASC, "reference", "basic"), constraint6);
    }

    @Test
    void shouldNotParsePriceDiscountConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("priceDiscount(DESC)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("priceDiscount(?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("priceDiscount(@a)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("priceDiscount"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("priceDiscount()"));
    }

    @Test
    void shouldParseRandomConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("random()");
        assertEquals(random(), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraint("random (  )");
        assertEquals(random(), constraint2);

        final OrderConstraint constraint3 = parseOrderConstraintUnsafe("randomWithSeed(42)");
        assertEquals(randomWithSeed(42), constraint3);

        final OrderConstraint constraint4 = parseOrderConstraintUnsafe("randomWithSeed(  42 )");
        assertEquals(randomWithSeed(42), constraint4);
    }

    @Test
    void shouldNotParseRandomConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("random"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("random('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("randomWithSeed()"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("randomWithSeed(42, 4)"));
    }

    @Test
    void shouldParseReferencePropertyConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("referenceProperty(?,attributeNatural(?))", "a", "b");
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

        final OrderConstraint constraint5 = parseOrderConstraint("referenceProperty(?,attributeNatural(?))", "a", "b");
        assertEquals(
            referenceProperty(
                "a",
                attributeNatural("b")
            ),
            constraint5
        );

        final OrderConstraint constraint6 = parseOrderConstraint("referenceProperty(@a,attributeNatural(@b))", Map.of("a", "a", "b", "b"));
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
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("referenceProperty(?,attributeNatural('b'))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("referenceProperty(@a,attributeNatural('b'))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("referenceProperty"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("referenceProperty()"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("referenceProperty('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("referenceProperty('a',1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("referenceProperty('a',attributeNatural('b'))"));
    }

    @Test
    void shouldParseTraverseByEntityPropertyConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("traverseByEntityProperty(attributeNatural(?))", "a");
        assertEquals(
            traverseByEntityProperty(
                attributeNatural("a")
            ),
            constraint1
        );

        final OrderConstraint constraint2 = parseOrderConstraintUnsafe("traverseByEntityProperty(attributeNatural('b'),attributeNatural('c',DESC))");
        assertEquals(
            traverseByEntityProperty(
                attributeNatural("b"),
                attributeNatural("c", DESC)
            ),
            constraint2
        );

        final OrderConstraint constraint3 = parseOrderConstraintUnsafe("traverseByEntityProperty ( attributeNatural('b')  , attributeNatural('c',  DESC) )");
        assertEquals(
            traverseByEntityProperty(
                attributeNatural("b"),
                attributeNatural("c", DESC)
            ),
            constraint3
        );

        final OrderConstraint constraint4 = parseOrderConstraintUnsafe("traverseByEntityProperty(attributeNatural('b'))");
        assertEquals(
            traverseByEntityProperty(
                attributeNatural("b")
            ),
            constraint4
        );

        final OrderConstraint constraint5 = parseOrderConstraint("traverseByEntityProperty(attributeNatural(?))", "b");
        assertEquals(
            traverseByEntityProperty(
                attributeNatural("b")
            ),
            constraint5
        );

        final OrderConstraint constraint6 = parseOrderConstraint("traverseByEntityProperty(attributeNatural(@b))", Map.of("b", "b"));
        assertEquals(
            traverseByEntityProperty(
                attributeNatural("b")
            ),
            constraint6
        );

        final OrderConstraint constraint11 = parseOrderConstraint("traverseByEntityProperty(?, attributeNatural(?))", TraversalMode.BREADTH_FIRST, "a");
        assertEquals(
            traverseByEntityProperty(
                TraversalMode.BREADTH_FIRST,
                attributeNatural("a")
            ),
            constraint11
        );

        final OrderConstraint constraint12 = parseOrderConstraintUnsafe("traverseByEntityProperty(BREADTH_FIRST, attributeNatural('b'),attributeNatural('c',DESC))");
        assertEquals(
            traverseByEntityProperty(
                TraversalMode.BREADTH_FIRST,
                attributeNatural("b"),
                attributeNatural("c", DESC)
            ),
            constraint12
        );

        final OrderConstraint constraint13 = parseOrderConstraintUnsafe("traverseByEntityProperty ( BREADTH_FIRST,  attributeNatural('b')  , attributeNatural('c',  DESC) )");
        assertEquals(
            traverseByEntityProperty(
                TraversalMode.BREADTH_FIRST,
                attributeNatural("b"),
                attributeNatural("c", DESC)
            ),
            constraint13
        );

        final OrderConstraint constraint14 = parseOrderConstraintUnsafe("traverseByEntityProperty(BREADTH_FIRST, attributeNatural('b'))");
        assertEquals(
            traverseByEntityProperty(
                TraversalMode.BREADTH_FIRST,
                attributeNatural("b")
            ),
            constraint14
        );

        final OrderConstraint constraint15 = parseOrderConstraint("traverseByEntityProperty(?, attributeNatural(?))", TraversalMode.DEPTH_FIRST, "b");
        assertEquals(
            traverseByEntityProperty(
                TraversalMode.DEPTH_FIRST,
                attributeNatural("b")
            ),
            constraint15
        );

        final OrderConstraint constraint16 = parseOrderConstraint("traverseByEntityProperty(@a, attributeNatural(@b))", Map.of("a", TraversalMode.BREADTH_FIRST, "b", "b"));
        assertEquals(
            traverseByEntityProperty(
                TraversalMode.BREADTH_FIRST,
                attributeNatural("b")
            ),
            constraint16
        );

        final OrderConstraint constraint17 = parseOrderConstraintUnsafe("traverseByEntityProperty(BREADTH_FIRST)");
        assertEquals(
            traverseByEntityProperty(
                TraversalMode.BREADTH_FIRST,
                entityPrimaryKeyNatural(ASC)
            ),
            constraint17
        );

        final OrderConstraint constraint18 = parseOrderConstraint("traverseByEntityProperty(?)", TraversalMode.BREADTH_FIRST);
        assertEquals(
            traverseByEntityProperty(
                TraversalMode.BREADTH_FIRST,
                entityPrimaryKeyNatural(ASC)
            ),
            constraint18
        );

        final OrderConstraint constraint19 = parseOrderConstraint("traverseByEntityProperty(@a)", Map.of("a", TraversalMode.BREADTH_FIRST));
        assertEquals(
            traverseByEntityProperty(
                TraversalMode.BREADTH_FIRST,
                entityPrimaryKeyNatural(ASC)
            ),
            constraint19
        );
    }

    @Test
    void shouldNotParseTraverseByEntityPropertyConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("traverseByEntityProperty(attributeNatural(?))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("traverseByEntityProperty('A', attributeNatural('A'))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("traverseByEntityProperty(A, attributeNatural('A'))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("traverseByEntityProperty(attributeNatural(@a))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("traverseByEntityProperty"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("traverseByEntityProperty()"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("traverseByEntityProperty('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("traverseByEntityProperty('a',1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("traverseByEntityProperty(attributeNatural('b'))"));
    }

    @Test
    void shouldParsePickFirstByEntityPropertyConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("pickFirstByEntityProperty(attributeNatural(?))", "a");
        assertEquals(
            pickFirstByEntityProperty(
                attributeNatural("a")
            ),
            constraint1
        );

        final OrderConstraint constraint2 = parseOrderConstraintUnsafe("pickFirstByEntityProperty(attributeNatural('b'),attributeNatural('c',DESC))");
        assertEquals(
            pickFirstByEntityProperty(
                attributeNatural("b"),
                attributeNatural("c", DESC)
            ),
            constraint2
        );

        final OrderConstraint constraint3 = parseOrderConstraintUnsafe("pickFirstByEntityProperty ( attributeNatural('b')  , attributeNatural('c',  DESC) )");
        assertEquals(
            pickFirstByEntityProperty(
                attributeNatural("b"),
                attributeNatural("c", DESC)
            ),
            constraint3
        );

        final OrderConstraint constraint4 = parseOrderConstraintUnsafe("pickFirstByEntityProperty(attributeNatural('b'))");
        assertEquals(
            new PickFirstByEntityProperty(
                attributeNatural("b")
            ),
            constraint4
        );

        final OrderConstraint constraint5 = parseOrderConstraint("pickFirstByEntityProperty(attributeNatural(?))", "b");
        assertEquals(
            pickFirstByEntityProperty(
                attributeNatural("b")
            ),
            constraint5
        );

        final OrderConstraint constraint6 = parseOrderConstraint("pickFirstByEntityProperty(attributeNatural(@b))", Map.of("b", "b"));
        assertEquals(
            pickFirstByEntityProperty(
                attributeNatural("b")
            ),
            constraint6
        );
    }

    @Test
    void shouldNotParsePickFirstByEntityPropertyConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("pickFirstByEntityProperty(attributeNatural(?))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("pickFirstByEntityProperty(attributeNatural(@a))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("pickFirstByEntityProperty"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("pickFirstByEntityProperty()"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("pickFirstByEntityProperty('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("pickFirstByEntityProperty('a',1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("pickFirstByEntityProperty(attributeNatural('b'))"));
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
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("entityPrimaryKeyExact"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("entityPrimaryKeyExact()"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("entityPrimaryKeyExact('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("entityPrimaryKeyExact(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("entityPrimaryKeyExact(?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("entityPrimaryKeyExact(@b)"));
    }

    @Test
    void shouldParseEntityPrimaryKeyInFilterConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("entityPrimaryKeyInFilter()");
        assertEquals(entityPrimaryKeyInFilter(), constraint1);
    }

    @Test
    void shouldNotParseEntityPrimaryKeyInFilterConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("entityPrimaryKeyInFilter"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("entityPrimaryKeyInFilter(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("entityPrimaryKeyInFilter(DESC)"));
    }

    @Test
    void shouldParseEntityPrimaryKeyNaturalConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("entityPrimaryKeyNatural()");
        assertEquals(entityPrimaryKeyNatural(ASC), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraintUnsafe("entityPrimaryKeyNatural(DESC)");
        assertEquals(entityPrimaryKeyNatural(DESC), constraint2);

        final OrderConstraint constraint3 = parseOrderConstraint("entityPrimaryKeyNatural(?)", DESC);
        assertEquals(entityPrimaryKeyNatural(DESC), constraint3);

        final OrderConstraint constraint4 = parseOrderConstraint("entityPrimaryKeyNatural(@order)", Map.of("order", DESC));
        assertEquals(entityPrimaryKeyNatural(DESC), constraint4);
    }

    @Test
    void shouldNotParseEntityPrimaryKeyNaturalConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("entityPrimaryKeyNatural"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("entityPrimaryKeyNatural(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("entityPrimaryKeyNatural(DESC)"));
    }

    @Test
    void shouldParseEntityPropertyConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraint("entityProperty(attributeNatural(?))", "b");
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
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("entityProperty"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("entityProperty()"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("entityProperty('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("entityProperty('a',priceNatural())"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("entityProperty(?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("entityProperty(and())"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("entityProperty(attributeNatural('b'))"));
    }

    @Test
    void shouldParseSegmentConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraintUnsafe("orderBy(segments(segment(orderBy(random()))))");
        assertEquals(orderBy(segments(segment(orderBy(random())))), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraintUnsafe("orderBy(segments(segment(orderBy(random())),segment(orderBy(random()))))");
        assertEquals(orderBy(segments(segment(orderBy(random())), segment(orderBy(random())))), constraint2);

        final OrderConstraint constraint3 = parseOrderConstraintUnsafe("orderBy(segments(segment(entityHaving(entityPrimaryKeyInSet(1)), orderBy(random()))))");
        assertEquals(orderBy(segments(segment(entityHaving(entityPrimaryKeyInSet(1)), orderBy(random())))), constraint3);

        final OrderConstraint constraint4 = parseOrderConstraintUnsafe("orderBy(segments(segment(orderBy(random()), limit(5))))");
        assertEquals(orderBy(segments(segment(orderBy(random()), limit(5)))), constraint4);

        final OrderConstraint constraint5 = parseOrderConstraintUnsafe("orderBy(segments(segment(entityHaving(entityPrimaryKeyInSet(1)), orderBy(random()), limit(5))))");
        assertEquals(orderBy(segments(segment(entityHaving(entityPrimaryKeyInSet(1)), orderBy(random()), limit(5)))), constraint5);

        final OrderConstraint constraint6 = parseOrderConstraintUnsafe("orderBy( segments ( segment(   entityHaving( entityPrimaryKeyInSet( 1)), orderBy (random()), limit(5)), segment ( orderBy ( randomWithSeed(8 )) ) ) ))");
        assertEquals(orderBy(segments(segment(entityHaving(entityPrimaryKeyInSet(1)), orderBy(random()), limit(5)), segment(orderBy(randomWithSeed(8L))))), constraint6);

        final OrderConstraint constraint7 = parseOrderConstraint(
            "orderBy(segments(segment(entityHaving(entityPrimaryKeyInSet(?)),orderBy(random()),limit(?)),segment(orderBy(randomWithSeed(?))))))",
            1, 5, 8L
        );
        assertEquals(orderBy(segments(segment(entityHaving(entityPrimaryKeyInSet(1)), orderBy(random()), limit(5)), segment(orderBy(randomWithSeed(8L))))), constraint7);

        final OrderConstraint constraint8 = parseOrderConstraint(
            "orderBy(segments(segment(entityHaving(entityPrimaryKeyInSet(@pk)),orderBy(random()),limit(@limit)),segment(orderBy(randomWithSeed(@seed))))))",
            Map.of("pk", 10, "limit", 20, "seed", 8L)
        );
        assertEquals(orderBy(segments(segment(entityHaving(entityPrimaryKeyInSet(10)), orderBy(random()), limit(20)), segment(orderBy(randomWithSeed(8L))))), constraint8);
    }

    @Test
    void shouldNotParseSegmentConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("orderBy(segments())"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("orderBy(segments(segment()))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("orderBy(segments(segment(attributeEquals('a', 1)))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("orderBy(segments(segment(entityHaving(entityPrimaryKeyInSet(1))))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("orderBy(segments(segment(limit(5)))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("orderBy(segments(segment(random()))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("orderBy(segments(segment(orderBy(random()), random()))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("orderBy(segments(segment(orderBy(random()), limit(?))))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("orderBy(segments(segment(orderBy(random()), limit(5))))"));
    }

    @Test
    void shouldParseInScopeConstraint() {
        final OrderConstraint constraint1 = parseOrderConstraintUnsafe("inScope(LIVE, attributeNatural('code', ASC))");
        assertEquals(inScope(Scope.LIVE, attributeNatural("code", ASC)), constraint1);

        final OrderConstraint constraint2 = parseOrderConstraintUnsafe("inScope ( LIVE , attributeNatural('code', ASC))");
        assertEquals(inScope(Scope.LIVE, attributeNatural("code", ASC)), constraint2);

        final OrderConstraint constraint3 = parseOrderConstraintUnsafe("inScope ( LIVE , attributeNatural('code', ASC), attributeNatural('name', DESC))");
        assertEquals(inScope(Scope.LIVE, attributeNatural("code", ASC), attributeNatural("name", DESC)), constraint3);

        final OrderConstraint constraint4 = parseOrderConstraint("inScope ( ?,    attributeNatural( ?  , ?) )", Scope.ARCHIVED, "code", ASC);
        assertEquals(inScope(Scope.ARCHIVED, attributeNatural("code", ASC)), constraint4);

        final OrderConstraint constraint5 = parseOrderConstraint("inScope ( ?,    attributeNatural( ? , ?), attributeNatural(?, ?) )", Scope.ARCHIVED, "code", ASC, "name", DESC);
        assertEquals(inScope(Scope.ARCHIVED, attributeNatural("code", ASC), attributeNatural("name", DESC)), constraint5);

        final OrderConstraint constraint6 = parseOrderConstraint("inScope ( @a,  attributeNatural(  @b,@c) )", Map.of("a", Scope.ARCHIVED, "b", "code", "c", ASC));
        assertEquals(inScope(Scope.ARCHIVED, attributeNatural("code", ASC)), constraint6);

        final OrderConstraint constraint7 = parseOrderConstraint("inScope ( @a,   attributeNatural( @b , @c), attributeNatural(@d, @e) )", Map.of("a", Scope.ARCHIVED, "b", "code", "c", ASC, "d", "name", "e", DESC));
        assertEquals(inScope(Scope.ARCHIVED, attributeNatural("code", ASC), attributeNatural("name", DESC)), constraint7);
    }

    @Test
    void shouldNotParseInScopeConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("inScope"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraint("inScope(LIVE)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("inScope(LIVE)"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("inScope('LIVE', attributeNatural('code', ASC))"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintUnsafe("inScope('a','b')"));
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
