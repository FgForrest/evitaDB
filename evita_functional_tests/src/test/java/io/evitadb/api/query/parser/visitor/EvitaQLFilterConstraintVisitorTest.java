/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParseMode;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.filter.AttributeSpecialValue.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EvitaQLFilterConstraintVisitor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
class EvitaQLFilterConstraintVisitorTest {

    public static final Currency CZK = Currency.getInstance("CZK");

    @Test
    void shouldParseFilterByConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("filterBy(attributeEquals('a',1))");
        assertEquals(filterBy(attributeEquals("a", 1L)), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("filterBy ( attributeEquals('a',1)  )");
        assertEquals(filterBy(attributeEquals("a", 1L)), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("filterBy(attributeEquals('a',1),entityPrimaryKeyInSet(1))");
        assertEquals(filterBy(attributeEquals("a", 1L), entityPrimaryKeyInSet(1)), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint(
            "filterBy(attributeEquals(?,?))",
            "a", 1L
        );
        assertEquals(filterBy(attributeEquals("a", 1L)), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint(
            "filterBy(attributeEquals(?,?),entityPrimaryKeyInSet(?))",
            "a", 1L, 1L
        );
        assertEquals(filterBy(attributeEquals("a", 1L), entityPrimaryKeyInSet(1)), constraint5);

        final FilterConstraint constraint6 = parseFilterConstraint(
            "filterBy(attributeEquals(@name,@val))",
            Map.of("name", "a", "val", 1L)
        );
        assertEquals(filterBy(attributeEquals("a", 1L)), constraint6);

        final FilterConstraint constraint7 = parseFilterConstraint(
            "filterBy(attributeEquals(@name,@val),entityPrimaryKeyInSet(@pk))",
            Map.of("name", "a", "val", 1L, "pk", 1L)
        );
        assertEquals(filterBy(attributeEquals("a", 1L), entityPrimaryKeyInSet(1)), constraint7);
    }

    @Test
    void shouldNotParseFilterByConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("filterBy"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("filterBy()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("filterBy(collection('a'))"));
    }

    @Test
    void shouldParseFilterGroupByConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("filterGroupBy(attributeEquals('a',1))");
        assertEquals(filterGroupBy(attributeEquals("a", 1L)), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("filterGroupBy ( attributeEquals('a',1)  )");
        assertEquals(filterGroupBy(attributeEquals("a", 1L)), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("filterGroupBy(attributeEquals('a',1),entityPrimaryKeyInSet(1))");
        assertEquals(filterGroupBy(attributeEquals("a", 1L), entityPrimaryKeyInSet(1)), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint(
            "filterGroupBy(attributeEquals(?,?))",
            "a", 1L
        );
        assertEquals(filterGroupBy(attributeEquals("a", 1L)), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint(
            "filterGroupBy(attributeEquals(?,?),entityPrimaryKeyInSet(?))",
            "a", 1L, 1L
        );
        assertEquals(filterGroupBy(attributeEquals("a", 1L), entityPrimaryKeyInSet(1)), constraint5);

        final FilterConstraint constraint6 = parseFilterConstraint(
            "filterGroupBy(attributeEquals(@name,@val))",
            Map.of("name", "a", "val", 1L)
        );
        assertEquals(filterGroupBy(attributeEquals("a", 1L)), constraint6);

        final FilterConstraint constraint7 = parseFilterConstraint(
            "filterGroupBy(attributeEquals(@name,@val),entityPrimaryKeyInSet(@pk))",
            Map.of("name", "a", "val", 1L, "pk", 1L)
        );
        assertEquals(filterGroupBy(attributeEquals("a", 1L), entityPrimaryKeyInSet(1)), constraint7);
    }

    @Test
    void shouldNotParseFilterGroupByConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("filterGroupBy"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("filterGroupBy()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("filterGroupBy(collection('a'))"));
    }

    @Test
    void shouldParseAndConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("and(attributeEquals('a',1))");
        assertEquals(and(attributeEquals("a", 1L)), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("and(attributeEquals('a',1),attributeEquals('b','c'))");
        assertEquals(and(attributeEquals("a", 1L), attributeEquals("b", "c")), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("and ( attributeEquals('a',1), attributeEquals('b','c') )");
        assertEquals(and(attributeEquals("a", 1L), attributeEquals("b", "c")), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraintUnsafe("and()");
        assertEquals(and(), constraint4);
    }

    @Test
    void shouldNotParseAndConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("and"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("and(collection('a'))"));
    }

    @Test
    void shouldParseOrConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("or(attributeEquals('a',1))");
        assertEquals(or(attributeEquals("a", 1L)), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("or(attributeEquals('a',1),attributeEquals('b','c'))");
        assertEquals(or(attributeEquals("a", 1L), attributeEquals("b", "c")), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("or ( attributeEquals('a',1) , attributeEquals('b','c') )");
        assertEquals(or(attributeEquals("a", 1L), attributeEquals("b", "c")), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraintUnsafe("or()");
        assertEquals(or(), constraint4);
    }

    @Test
    void shouldNotParseOrConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("or"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("or(collection('a'))"));
    }

    @Test
    void shouldParseNotConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("not(attributeEquals('a',1))");
        assertEquals(not(attributeEquals("a", 1L)), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("not ( attributeEquals('a',1)  )");
        assertEquals(not(attributeEquals("a", 1L)), constraint2);
    }

    @Test
    void shouldNotParseNotConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("not"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("not()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("not(collection('a'))"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("not(attributeEquals('a',1),attributeEquals('b','c'))"));
    }

     @Test
     void shouldParseUserFilterConstraint() {
         final FilterConstraint constraint1 = parseFilterConstraintUnsafe("userFilter(attributeEquals('a',1))");
         assertEquals(userFilter(attributeEquals("a", 1L)), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("userFilter(attributeEquals('a',1),attributeEquals('b','c'))");
         assertEquals(userFilter(attributeEquals("a", 1L), attributeEquals("b", "c")), constraint2);

         final FilterConstraint constraint3 = parseFilterConstraintUnsafe("userFilter ( attributeEquals('a',1)  , attributeEquals('b','c') )");
         assertEquals(userFilter(attributeEquals("a", 1L), attributeEquals("b", "c")), constraint3);

         final FilterConstraint constraint4 = parseFilterConstraintUnsafe("userFilter()");
         assertEquals(userFilter(), constraint4);
     }

    @Test
    void shouldNotParseUserFilterConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("userFilter"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("userFilter(collection('a'))"));
    }

    @Test
    void shouldParseAttributeEqualsConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeEquals('a',100)");
        assertEquals(attributeEquals("a", 100L), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeEquals( 'a'  , 'c'   )");
        assertEquals(attributeEquals("a", "c"), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraint("attributeEquals(?,?)", "a", "c");
        assertEquals(attributeEquals("a", "c"), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("attributeEquals(@name,@value)", Map.of("name", "a", "value", "c"));
        assertEquals(attributeEquals("a", "c"), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraintUnsafe("attributeEquals('a', 2fbbfcf2-d4bb-4db9-9658-acf1d287cbe9)");
        assertEquals(attributeEquals("a", UUID.fromString("2fbbfcf2-d4bb-4db9-9658-acf1d287cbe9")), constraint5);

        final FilterConstraint constraint6 = parseFilterConstraint("attributeEquals(@name,@value)", Map.of("name", "a", "value", UUID.fromString("2fbbfcf2-d4bb-4db9-9658-acf1d287cbe9")));
        assertEquals(attributeEquals("a", UUID.fromString("2fbbfcf2-d4bb-4db9-9658-acf1d287cbe9")), constraint6);
    }

    @Test
    void shouldNotParseAttributeEqualsConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeEquals('a','b')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeEquals('a',?)", "c"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeEquals(?,?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeEquals(@a,@b)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEquals"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEquals()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEquals('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEquals(1,2)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEquals('a',2,3)"));
    }

    @Test
    void shouldParseAttributeGreaterThanConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeGreaterThan('a',100)");
        assertEquals(attributeGreaterThan("a", 100L), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeGreaterThan('a','c')");
        assertEquals(attributeGreaterThan("a", "c"), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("attributeGreaterThan (  'a' ,  'c' )");
        assertEquals(attributeGreaterThan("a", "c"), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("attributeGreaterThan(?,?)", "a", "c");
        assertEquals(attributeGreaterThan("a", "c"), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("attributeGreaterThan(@name,@value)", Map.of("name", "a", "value", "c"));
        assertEquals(attributeGreaterThan("a", "c"), constraint5);
    }

    @Test
    void shouldNotParseAttributeGreaterThanConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeGreaterThan('a','c')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeGreaterThan('a',?)", "c"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeGreaterThan(?,?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeGreaterThan(@a,@b)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeGreaterThan"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeGreaterThan()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeGreaterThan('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeGreaterThan(1,2)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeGreaterThan('a',2,3)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeGreaterThan('a',SOME_ENUM)"));
    }

    @Test
    void shouldParseAttributeGreaterThanEqualsConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeGreaterThanEquals('a',100)");
        assertEquals(attributeGreaterThanEquals("a", 100L), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeGreaterThanEquals('a','c')");
        assertEquals(attributeGreaterThanEquals("a", "c"), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("attributeGreaterThanEquals  ( 'a'  , 'c'   )");
        assertEquals(attributeGreaterThanEquals("a", "c"), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("attributeGreaterThanEquals(?,?)", "a", "c");
        assertEquals(attributeGreaterThanEquals("a", "c"), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("attributeGreaterThanEquals(@name,@value)", Map.of("name", "a", "value", "c"));
        assertEquals(attributeGreaterThanEquals("a", "c"), constraint5);
    }

    @Test
    void shouldNotParseAttributeGreaterThanEqualsConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeGreaterThanEquals('a','c')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeGreaterThanEquals('a',?)", "c"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeGreaterThanEquals(?,?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeGreaterThanEquals(@a,@b)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeGreaterThanEquals"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeGreaterThanEquals()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeGreaterThanEquals('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeGreaterThanEquals(1,2)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeGreaterThanEquals('a',2,3)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeGreaterThanEquals('a',SOME_ENUM)"));
    }

    @Test
    void shouldParseAttributeLessThanConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeLessThan('a',100)");
        assertEquals(attributeLessThan("a", 100L), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeLessThan('a','c')");
        assertEquals(attributeLessThan("a", "c"), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("attributeLessThan (  'a' , 'c'  )");
        assertEquals(attributeLessThan("a", "c"), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("attributeLessThan(?,?)", "a", "c");
        assertEquals(attributeLessThan("a", "c"), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("attributeLessThan(@name,@value)", Map.of("name", "a", "value", "c"));
        assertEquals(attributeLessThan("a", "c"), constraint5);
    }

    @Test
    void shouldNotParseAttributeLessThanConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeGreaterThan('a','c')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeGreaterThan('a',?)", "c"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeGreaterThan(?,?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeGreaterThan(@a,@b)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeLessThan"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeLessThan()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeLessThan('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeLessThan(1,2)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeLessThan('a',2,3)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeLessThan('a',SOME_ENUM)"));
    }

    @Test
    void shouldParseAttributeLessThanEqualsConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeLessThanEquals('a',100)");
        assertEquals(attributeLessThanEquals("a", 100L), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeLessThanEquals('a','c')");
        assertEquals(attributeLessThanEquals("a", "c"), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("attributeLessThanEquals ( 'a'  , 'c' )");
        assertEquals(attributeLessThanEquals("a", "c"), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("attributeLessThanEquals(?,?)", "a", "c");
        assertEquals(attributeLessThanEquals("a", "c"), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("attributeLessThanEquals(@name,@value)", Map.of("name", "a", "value", "c"));
        assertEquals(attributeLessThanEquals("a", "c"), constraint5);
    }

    @Test
    void shouldNotParseAttributeLessThanEqualsConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeLessThanEquals('a','c')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeLessThanEquals('a',?)", "c"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeLessThanEquals(?,?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeLessThanEquals(@a,@b)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeLessThanEquals"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeLessThanEquals()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeLessThanEquals('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeLessThanEquals(1,2)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeLessThanEquals('a',2,3)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeLessThanEquals('a',SOME_ENUM)"));
    }

    @Test
    void shouldParseAttributeBetweenConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeBetween('a',100,150)");
        assertEquals(attributeBetween("a", 100L, 150L), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeBetween('a',2021-02-15,2021-03-15)");
        assertEquals(attributeBetween("a", LocalDate.of(2021, 2, 15), LocalDate.of(2021, 3, 15)), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("attributeBetween ( 'a' ,  2021-02-15,2021-03-15 )");
        assertEquals(attributeBetween("a", LocalDate.of(2021, 2, 15), LocalDate.of(2021, 3, 15)), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("attributeBetween(?,?,?)", "a", 100L, 150L);
        assertEquals(attributeBetween("a", 100L, 150L), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint(
            "attributeBetween(@name,@from,@to)",
            Map.of(
                "name", "a",
                "from", 100L,
                "to", 150L
            )
        );
        assertEquals(attributeBetween("a", 100L, 150L), constraint5);
    }

    @Test
    void shouldNotParseAttributeBetweenConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeBetween('a',100,150)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeBetween('a',?,?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeBetween('a',?,?)", 100, 150));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeBetween('a',@a,@b)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeBetween"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeBetween()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeBetween('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeBetween(1,2)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeBetween('a',2,3,3)"));
    }

    @Test
    void shouldParseAttributeInSetConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeInSet('a',100,150,200)");
        assertEquals(attributeInSet("a", 100L, 150L, 200L), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeInSet('a','aa','bb','cc')");
        assertEquals(attributeInSet("a", "aa", "bb", "cc"), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("attributeInSet (  'a' ,   'aa' ,'bb'  , 'cc'  )");
        assertEquals(attributeInSet("a", "aa", "bb", "cc"), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("attributeInSet(?,?,?)", "a", 100L, 150L);
        assertEquals(attributeInSet("a", 100L, 150L), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint(
            "attributeInSet(@name,@v1,@v2)",
            Map.of(
                "name", "a",
                "v1", 100L,
                "v2", 150L
            )
        );
        assertEquals(attributeInSet("a", 100L, 150L), constraint5);

        final FilterConstraint constraint6 = parseFilterConstraint("attributeInSet(?)", "a");
        assertEquals(attributeInSet("a"), constraint6);

        final FilterConstraint constraint7 = parseFilterConstraint("attributeInSet(?, ?)", "a", new int[0]);
        assertEquals(attributeInSet("a"), constraint7);

        final FilterConstraint constraint8 = parseFilterConstraint("attributeInSet(@name, @pk1)", Map.of("name", "a", "pk1", new int[0]));
        assertEquals(attributeInSet("a"), constraint8);
    }

    @Test
    void shouldNotParseAttributeInSetConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeInSet('a',1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeInSet('a',?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeInSet('a',?)", "b"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeInSet('a',@a)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeInSet"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeInSet()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeInSet(1,2)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeInSet('a',SOME_ENUM)"));
    }

    @Test
    void shouldParseAttributeContainsConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeContains('a','text')");
        assertEquals(attributeContains("a", "text"), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeContains('a','')");
        assertEquals(attributeContains("a", ""), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("attributeContains ( 'a'  , 'text' )");
        assertEquals(attributeContains("a", "text"), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("attributeContains(?,?)", "a", "text");
        assertEquals(attributeContains("a", "text"), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("attributeContains(@name,@value)", Map.of("name", "a", "value", "text"));
        assertEquals(attributeContains("a", "text"), constraint5);
    }

    @Test
    void shouldNotParseAttributeContainsConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeContains('a','b')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeContains('a',?)", "b"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeContains(?,?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeContains(@a,@b)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeContains"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeContains()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeContains('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeContains(1,2)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeContains('a',2,'b',3)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeContains('a',2)"));
    }

    @Test
    void shouldParseAttributeStartsWithConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeStartsWith('a','text')");
        assertEquals(attributeStartsWith("a", "text"), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeStartsWith('a','')");
        assertEquals(attributeStartsWith("a", ""), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("attributeStartsWith ( 'a'  , 'text' )");
        assertEquals(attributeStartsWith("a", "text"), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("attributeStartsWith(?,?)", "a", "text");
        assertEquals(attributeStartsWith("a", "text"), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("attributeStartsWith(@name,@value)", Map.of("name", "a", "value", "text"));
        assertEquals(attributeStartsWith("a", "text"), constraint5);
    }

    @Test
    void shouldNotParseAttributeStartsWithConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeStartsWith('a','b')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeStartsWith(?,?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeStartsWith('a',?)", "b"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeStartsWith(@a,@b)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeStartsWith"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeStartsWith()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeStartsWith('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeStartsWith(1,2)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeStartsWith('a',2,'b',3)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeStartsWith('a',2)"));
    }

    @Test
    void shouldParseAttributeEndsWithConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeEndsWith('a','text')");
        assertEquals(attributeEndsWith("a", "text"), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeEndsWith('a','')");
        assertEquals(attributeEndsWith("a", ""), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("attributeEndsWith ( 'a'  , 'text' )");
        assertEquals(attributeEndsWith("a", "text"), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("attributeEndsWith(?,?)", "a", "text");
        assertEquals(attributeEndsWith("a", "text"), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("attributeEndsWith(@name,@value)", Map.of("name", "a", "value", "text"));
        assertEquals(attributeEndsWith("a", "text"), constraint5);
    }

    @Test
    void shouldNotParseAttributeEndsWithConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeEndsWith('a','b')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeEndsWith('a',?)", "b"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeEndsWith(?,?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeEndsWith(@a,@b)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEndsWith"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEndsWith()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEndsWith('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEndsWith(1,2)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEndsWith('a',2,'b',3)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEndsWith('a',2)"));
    }

    @Test
    void shouldParseAttributeEqualsTrueConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeEqualsTrue('a')");
        assertEquals(attributeEqualsTrue("a"), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeEqualsTrue (  'a' )");
        assertEquals(attributeEqualsTrue("a"), constraint2);

        final FilterConstraint constraint4 = parseFilterConstraint("attributeEqualsTrue(?)", "a");
        assertEquals(attributeEqualsTrue("a"), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("attributeEqualsTrue(@name)", Map.of("name", "a"));
        assertEquals(attributeEqualsTrue("a"), constraint5);
    }

    @Test
    void shouldNotParseAttributeEqualsTrueConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEqualsTrue"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEqualsTrue()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEqualsTrue(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEqualsTrue('a',2)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeEqualsTrue('a')"));
    }

    @Test
    void shouldParseAttributeEqualsFalseConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeEqualsFalse('a')");
        assertEquals(attributeEqualsFalse("a"), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeEqualsFalse (  'a' )");
        assertEquals(attributeEqualsFalse("a"), constraint2);

        final FilterConstraint constraint4 = parseFilterConstraint("attributeEqualsFalse(?)", "a");
        assertEquals(attributeEqualsFalse("a"), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("attributeEqualsFalse(@name)", Map.of("name", "a"));
        assertEquals(attributeEqualsFalse("a"), constraint5);
    }

    @Test
    void shouldNotParseAttributeEqualsFalseConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEqualsFalse"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEqualsFalse()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEqualsFalse(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeEqualsFalse('a',2)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeEqualsFalse('a')"));
    }

    @Test
    void shouldParseAttributeIsConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeIs('a',NULL)");
        assertEquals(attributeIsNull("a"), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeIs (  'a'   , NULL )");
        assertEquals(attributeIsNull("a"), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraint("attributeIs(?,?)", "a", NULL);
        assertEquals(attributeIsNull("a"), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("attributeIs(@name,@value)", Map.of("name", "a", "value", NULL));
        assertEquals(attributeIsNull("a"), constraint4);
    }

    @Test
    void shouldNotParseAttributeIsConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeIs('a',NULL)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeIs('a',?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeIs('a',?)", NULL));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeIs('a',@b)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeIs"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeIs()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeIs('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeIs('a',2)"));
    }

    @Test
    void shouldParseAttributeIsNullConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeIsNull('a')");
        assertEquals(attributeIsNull("a"), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeIsNull (  'a' )");
        assertEquals(attributeIsNull("a"), constraint2);

        final FilterConstraint constraint4 = parseFilterConstraint("attributeIsNull(?)", "a");
        assertEquals(attributeIsNull("a"), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("attributeIsNull(@name)", Map.of("name", "a"));
        assertEquals(attributeIsNull("a"), constraint5);
    }

    @Test
    void shouldNotParseAttributeIsNullConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeIsNull"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeIsNull()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeIsNull(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeIsNull('a',2)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeIsNull('a')"));
    }

    @Test
    void shouldParseAttributeIsNotNullConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeIsNotNull('a')");
        assertEquals(attributeIsNotNull("a"), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeIsNotNull (  'a' )");
        assertEquals(attributeIsNotNull("a"), constraint2);

        final FilterConstraint constraint4 = parseFilterConstraint("attributeIsNotNull(?)", "a");
        assertEquals(attributeIsNotNull("a"), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("attributeIsNotNull(@name)", Map.of("name", "a"));
        assertEquals(attributeIsNotNull("a"), constraint5);
    }

    @Test
    void shouldNotParseAttributeIsNotNullConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeIsNotNull"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeIsNotNull()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeIsNotNull(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeIsNotNull('a',2)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeIsNotNull('a')"));
    }

    @Test
    void shouldParseAttributeInRangeConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("attributeInRange('a',500)");
        assertEquals(attributeInRange("a", 500L), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("attributeInRange('a',2021-02-15T11:00:00+01:00)");
        assertEquals(
                attributeInRange("a", OffsetDateTime.of(2021, 2, 15, 11, 0, 0, 0, ZoneId.of("Europe/Prague").getRules().getOffset(LocalDateTime.of(2022, 12, 1, 0, 0)))),
                constraint2
        );

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("attributeInRange (  'a' ,  500)");
        assertEquals(attributeInRange("a", 500L), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraintUnsafe("attributeInRange ( 'a'  , 2021-02-15T11:00:00+01:00 )");
        assertEquals(
                attributeInRange("a", OffsetDateTime.of(2021, 2, 15, 11, 0, 0, 0, ZoneId.of("Europe/Prague").getRules().getOffset(LocalDateTime.of(2022, 12, 1, 0, 0)))),
                constraint4
        );

        final FilterConstraint constraint5 = parseFilterConstraintUnsafe("attributeInRange('a',500.5)");
        assertEquals(attributeInRange("a", BigDecimal.valueOf(500.5)), constraint5);

        final FilterConstraint constraint6 = parseFilterConstraint("attributeInRange(?,?)", "a", 500L);
        assertEquals(attributeInRange("a", 500L), constraint6);

        final FilterConstraint constraint7 = parseFilterConstraint("attributeInRange(@name,@value)", Map.of("name", "a", "value", 500L));
        assertEquals(attributeInRange("a", 500L), constraint7);

        final FilterConstraint constraint8 = parseFilterConstraintUnsafe("attributeInRangeNow('a')");
        assertEquals(attributeInRangeNow("a"), constraint8);
    }

    @Test
    void shouldNotParseAttributeInRangeConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeInRange('a',500)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeInRange('a',?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeInRange('a',?)", 500));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("attributeInRange('a',@b)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeInRange"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeInRange()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeInRange(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeInRange('a','b')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeInRange('a',2021-02-15)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("attributeInRange('a',1,2)"));
    }

    @Test
    void shouldParseEntityPrimaryKeyInSetConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("entityPrimaryKeyInSet(10)");
        assertEquals(entityPrimaryKeyInSet(10), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("entityPrimaryKeyInSet(10,20,50,60)");
        assertEquals(entityPrimaryKeyInSet(10, 20, 50, 60), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("entityPrimaryKeyInSet ( 10 ,  20 , 50, 60 )");
        assertEquals(entityPrimaryKeyInSet(10, 20, 50, 60), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("entityPrimaryKeyInSet(?)", 10);
        assertEquals(entityPrimaryKeyInSet(10), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("entityPrimaryKeyInSet(@pk)", Map.of("pk", 10));
        assertEquals(entityPrimaryKeyInSet(10), constraint5);

        final FilterConstraint constraint6 = parseFilterConstraint("entityPrimaryKeyInSet(?)", List.of(10, 11));
        assertEquals(entityPrimaryKeyInSet(10, 11), constraint6);

        final FilterConstraint constraint7 = parseFilterConstraint("entityPrimaryKeyInSet(@pk)", Map.of("pk", List.of(10, 11)));
        assertEquals(entityPrimaryKeyInSet(10, 11), constraint7);

        final FilterConstraint constraint8 = parseFilterConstraint("entityPrimaryKeyInSet(?, ?)", 10, 11);
        assertEquals(entityPrimaryKeyInSet(10, 11), constraint8);

        final FilterConstraint constraint9 = parseFilterConstraint("entityPrimaryKeyInSet(@pk1,@pk2)", Map.of("pk1", 10, "pk2", 11));
        assertEquals(entityPrimaryKeyInSet(10, 11), constraint9);

        final FilterConstraint constraint10 = parseFilterConstraint("entityPrimaryKeyInSet()");
        assertEquals(entityPrimaryKeyInSet(), constraint10);

        final FilterConstraint constraint11 = parseFilterConstraint("entityPrimaryKeyInSet(?)", new int[0]);
        assertEquals(entityPrimaryKeyInSet(), constraint11);

        final FilterConstraint constraint12 = parseFilterConstraint("entityPrimaryKeyInSet(@pk1)", Map.of("pk1", new int[0]));
        assertEquals(entityPrimaryKeyInSet(), constraint12);
    }

    @Test
    void shouldNotParseEntityPrimaryKeyInSetConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("entityPrimaryKeyInSet(?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("entityPrimaryKeyInSet(@a)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("entityPrimaryKeyInSet"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("entityPrimaryKeyInSet('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("entityPrimaryKeyInSet('a','b')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("entityPrimaryKeyInSet(1,'a',2)"));
    }

    @Test
    void shouldParseEntityLocaleEqualsConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("entityLocaleEquals('cs-CZ')");
        assertEquals(entityLocaleEquals(new Locale("cs", "CZ")), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("entityLocaleEquals(  'cs-CZ' )");
        assertEquals(entityLocaleEquals(new Locale("cs", "CZ")), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraint("entityLocaleEquals(?)", new Locale("cs", "CZ"));
        assertEquals(entityLocaleEquals(new Locale("cs", "CZ")), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("entityLocaleEquals(@loc)", Map.of("loc", new Locale("cs", "CZ")));
        assertEquals(entityLocaleEquals(new Locale("cs", "CZ")), constraint4);
    }

    @Test
    void shouldNotParseEntityLocaleEqualsConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("entityLocaleEquals('cs')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("entityLocaleEquals(?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("entityLocaleEquals(@a)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("entityLocaleEquals"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("entityLocaleEquals()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("entityLocaleEquals('a','b')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("entityLocaleEquals(1,'a',2)"));
    }

    @Test
    void shouldParsePriceInCurrencyConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("priceInCurrency('CZK')");
        assertEquals(priceInCurrency(CZK), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("priceInCurrency (  'CZK' )");
        assertEquals(priceInCurrency(CZK), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraint("priceInCurrency(?)", "CZK");
        assertEquals(priceInCurrency(CZK), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("priceInCurrency(@cr)", Map.of("cr", "CZK"));
        assertEquals(priceInCurrency(CZK), constraint4);
    }

    @Test
    void shouldNotParsePriceInCurrencyConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceInCurrency(?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceInCurrency(@a)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceInCurrency"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceInCurrency()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceInCurrency('a','b')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceInCurrency(1,'a',2)"));
    }

    @Test
    void shouldParsePriceInPriceListsConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("priceInPriceLists('A')");
        assertEquals(priceInPriceLists("A"), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("priceInPriceLists('A','B','C','E')");
        assertEquals(priceInPriceLists("A", "B", "C", "E"), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("priceInPriceLists ( 'A' ,  'B' , 'C', 'D' )");
        assertEquals(priceInPriceLists("A", "B", "C", "D"), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraintUnsafe("priceInPriceLists('basic')");
        assertEquals(priceInPriceLists("basic"), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraintUnsafe("priceInPriceLists('basic','reference')");
        assertEquals(priceInPriceLists("basic", "reference"), constraint5);

        final FilterConstraint constraint6 = parseFilterConstraintUnsafe("priceInPriceLists ( 'basic' ,  'reference' , 'action' )");
        assertEquals(priceInPriceLists("basic", "reference", "action"), constraint6);

        final FilterConstraint constraint8 = parseFilterConstraint("priceInPriceLists(?)", "basic");
        assertEquals(priceInPriceLists("basic"), constraint8);

        final FilterConstraint constraint9 = parseFilterConstraint("priceInPriceLists(@pl)", Map.of("pl", "basic"));
        assertEquals(priceInPriceLists("basic"), constraint9);

        final FilterConstraint constraint10 = parseFilterConstraint("priceInPriceLists(?)", List.of("basic", "vip"));
        assertEquals(priceInPriceLists("basic", "vip"), constraint10);

        final FilterConstraint constraint11 = parseFilterConstraint("priceInPriceLists(@pl)", Map.of("pl", List.of("basic", "vip")));
        assertEquals(priceInPriceLists("basic", "vip"), constraint11);

        final FilterConstraint constraint12 = parseFilterConstraint("priceInPriceLists(?,?)", "basic", "vip");
        assertEquals(priceInPriceLists("basic", "vip"), constraint12);

        final FilterConstraint constraint13 = parseFilterConstraint("priceInPriceLists(@pl1,@pl2)", Map.of("pl1", "basic", "pl2", "vip"));
        assertEquals(priceInPriceLists("basic", "vip"), constraint13);

        final FilterConstraint constraint14 = parseFilterConstraint("priceInPriceLists()");
        assertEquals(priceInPriceLists(), constraint14);

        final FilterConstraint constraint15 = parseFilterConstraint("priceInPriceLists()");
        assertEquals(priceInPriceLists(), constraint15);

        final FilterConstraint constraint16 = parseFilterConstraint("priceInPriceLists(?)", (Object) new String[0]);
        assertEquals(priceInPriceLists(), constraint16);

        final FilterConstraint constraint17 = parseFilterConstraint("priceInPriceLists(@pk1)", Map.of("pk1", new String[0]));
        assertEquals(priceInPriceLists(), constraint17);
    }

    @Test
    void shouldNotParsePriceInPriceListsConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("priceInPriceLists('basic')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("priceInPriceLists(?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("priceInPriceLists(@a)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceInPriceLists"));
    }

    @Test
    void shouldParsepriceValidInNowConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("priceValidInNow()");
        assertEquals(priceValidInNow(), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("priceValidInNow  (  )");
        assertEquals(priceValidInNow(), constraint2);
    }

    @Test
    void shouldNotParsepriceValidInNowConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceValidInNow"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceValidInNow(2021-02-15T11:00:00+01:00)"));
    }

    @Test
    void shouldParsePriceValidInConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("priceValidIn(2021-02-15T11:00:00+01:00)");
        assertEquals(
                priceValidIn(OffsetDateTime.of(2021, 2, 15, 11, 0, 0, 0, ZoneId.of("Europe/Prague").getRules().getOffset(LocalDateTime.of(2022, 12, 1, 0, 0)))),
                constraint1
        );

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("priceValidIn (  2021-02-15T11:00:00+01:00 )");
        assertEquals(
                priceValidIn(OffsetDateTime.of(2021, 2, 15, 11, 0, 0, 0, ZoneId.of("Europe/Prague").getRules().getOffset(LocalDateTime.of(2022, 12, 1, 0, 0)))),
                constraint2
        );

        final FilterConstraint constraint3 = parseFilterConstraint(
            "priceValidIn(?)",
            OffsetDateTime.of(2021, 2, 15, 11, 0, 0, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now()))
        );
        assertEquals(
            priceValidIn(OffsetDateTime.of(2021, 2, 15, 11, 0, 0, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now()))),
            constraint3
        );

        final FilterConstraint constraint4 = parseFilterConstraint(
            "priceValidIn(@ts)",
            Map.of("ts", OffsetDateTime.of(2021, 2, 15, 11, 0, 0, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now())))
        );
        assertEquals(
            priceValidIn(OffsetDateTime.of(2021, 2, 15, 11, 0, 0, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now()))),
            constraint4
        );

        final FilterConstraint constraint5 = parseFilterConstraintUnsafe("priceValidInNow()");
        assertEquals(priceValidInNow(), constraint5);
    }

    @Test
    void shouldNotParsePriceValidInConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("priceValidIn(2021-02-15T11:00:00+01:00)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("priceValidIn(?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("priceValidIn(@a)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceValidIn"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceValidIn('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceValidIn(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceValidIn(2021-02-15T11:00:00+01:00,2021-02-15T11:00:00+01:00)"));
    }

    @Test
    void shouldParsePriceBetweenConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("priceBetween(10.0,50.5)");
        assertEquals(priceBetween(BigDecimal.valueOf(10.0), BigDecimal.valueOf(50.5)), constraint1);

        final FilterConstraint constraint5 = parseFilterConstraintUnsafe("priceBetween(10,50)");
        assertEquals(priceBetween(BigDecimal.valueOf(10), BigDecimal.valueOf(50)), constraint5);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("priceBetween( 10.0  , 50.5  )");
        assertEquals(priceBetween(BigDecimal.valueOf(10.0), BigDecimal.valueOf(50.5)), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraint("priceBetween(?,?)", BigDecimal.valueOf(10.0), BigDecimal.valueOf(50.5));
        assertEquals(priceBetween(BigDecimal.valueOf(10.0), BigDecimal.valueOf(50.5)), constraint3);

        final FilterConstraint constraint6 = parseFilterConstraint("priceBetween(?,?)", 10, 50);
        assertEquals(priceBetween(BigDecimal.valueOf(10), BigDecimal.valueOf(50)), constraint6);

        final FilterConstraint constraint4 = parseFilterConstraint(
            "priceBetween(@from,@to)",
            Map.of(
                "from",  BigDecimal.valueOf(10.0),
                "to", BigDecimal.valueOf(50.5)
            )
        );
        assertEquals(priceBetween(BigDecimal.valueOf(10.0), BigDecimal.valueOf(50.5)), constraint4);

        final FilterConstraint constraint7 = parseFilterConstraint(
            "priceBetween(@from,@to)",
            Map.of("from",  10, "to", 50)
        );
        assertEquals(priceBetween(BigDecimal.valueOf(10), BigDecimal.valueOf(50)), constraint7);
    }

    @Test
    void shouldNotParsePriceBetweenConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("priceBetween(10.0,50.5)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("priceBetween(?,?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("priceBetween(@a,@b)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("priceBetween(10,11)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceBetween"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceBetween()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceBetween('a',1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("priceBetween(10.0,50.0,78)"));
    }

    @Test
    void shouldParseFacetHavingConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("facetHaving('a',entityPrimaryKeyInSet(10))");
        assertEquals(facetHaving("a", entityPrimaryKeyInSet(10)), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("facetHaving('a',entityPrimaryKeyInSet(10,20,50))");
        assertEquals(new FacetHaving("a", entityPrimaryKeyInSet(10, 20, 50)), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("facetHaving ( 'a'  , entityPrimaryKeyInSet(10,  20,50) )");
        assertEquals(new FacetHaving("a", entityPrimaryKeyInSet(10, 20, 50)), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("facetHaving(?,entityPrimaryKeyInSet(?))", "a", 10);
        assertEquals(facetHaving("a", entityPrimaryKeyInSet(10)), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("facetHaving(@name, entityPrimaryKeyInSet(@pk))", Map.of("name", "a", "pk", 10));
        assertEquals(facetHaving("a", entityPrimaryKeyInSet(10)), constraint5);

        final FilterConstraint constraint6 = parseFilterConstraint("facetHaving(?,entityPrimaryKeyInSet(?))", "a", List.of(10, 11));
        assertEquals(facetHaving("a", entityPrimaryKeyInSet(10, 11)), constraint6);

        final FilterConstraint constraint7 = parseFilterConstraint("facetHaving(@name, entityPrimaryKeyInSet(@pk))", Map.of("name", "a", "pk", List.of(10, 11)));
        assertEquals(facetHaving("a", entityPrimaryKeyInSet(10, 11)), constraint7);

        final FilterConstraint constraint8 = parseFilterConstraint("facetHaving(?,entityPrimaryKeyInSet(?,?))", "a", 10, 11);
        assertEquals(facetHaving("a", entityPrimaryKeyInSet(10, 11)), constraint8);

        final FilterConstraint constraint9 = parseFilterConstraint("facetHaving(@name, entityPrimaryKeyInSet(@pk1, @pk2))", Map.of("name", "a", "pk1", 10, "pk2", 11));
        assertEquals(facetHaving("a", entityPrimaryKeyInSet(10, 11)), constraint9);
    }

    @Test
    void shouldNotParseFacetHavingConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("facetHaving('a',entityPrimaryKeyInSet(10))"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("facetHaving('a',entityPrimaryKeyInSet(?))"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("facetHaving('a',entityPrimaryKeyInSet(?))", "c"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("facetHaving('a',entityPrimaryKeyInSet(@b))"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("facetHaving"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("facetHaving()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("facetHaving('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("facetHaving('a',5)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("facetHaving('a','b',entityPrimaryKeyInSet(5))"));
    }

    @Test
    void shouldParseReferenceHavingConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("referenceHaving('a',attributeEquals('b',1))");
        assertEquals(
                referenceHaving(
                    "a",
                    attributeEquals("b", 1L)
                ),
                constraint1
        );

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("referenceHaving('a',and(attributeEquals('b',1)))");
        assertEquals(
                referenceHaving(
                    "a",
                    and(attributeEquals("b", 1L))
                ),
                constraint2
        );

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("referenceHaving ( 'a' , and( attributeEquals ('b', 1 )) )");
        assertEquals(
                referenceHaving(
                    "a",
                    and(attributeEquals("b", 1L))
                ),
                constraint3
        );

        final FilterConstraint constraint4 = parseFilterConstraintUnsafe("referenceHaving ( 'a' )");
        assertEquals(
            referenceHaving(
                "a"
            ),
            constraint4
        );
    }

    @Test
    void shouldNotParseReferenceHavingConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("referenceHaving(?,attributeEqualsTrue('a'))"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("referenceHaving(@a,attributeEqualsTrue('a'))"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("referenceHaving"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("referenceHaving()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("referenceHaving(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("referenceHaving('a','b')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("referenceHaving('a',attributeEquals('b',1),attributeEqualsTrue('c'))"));
    }

    @Test
    void shouldParseHierarchyWithinConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("hierarchyWithin('a',entityPrimaryKeyInSet(10))");
        assertEquals(hierarchyWithin("a", entityPrimaryKeyInSet(10)), constraint1);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("hierarchyWithin('a',entityPrimaryKeyInSet(10),directRelation())");
        assertEquals(hierarchyWithin("a", entityPrimaryKeyInSet(10), directRelation()), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraintUnsafe("hierarchyWithin('a',entityPrimaryKeyInSet(10),directRelation(),excluding(entityPrimaryKeyInSet(1,3)),excludingRoot())");
        assertEquals(
                hierarchyWithin(
                    "a",
                    entityPrimaryKeyInSet(10),
                    directRelation(),
                    excluding(entityPrimaryKeyInSet(1, 3)),
                    excludingRoot()
                ),
                constraint4
        );

        final FilterConstraint constraint5 = parseFilterConstraintUnsafe("hierarchyWithin (  'a' ,entityPrimaryKeyInSet(10) ,  directRelation() )");
        assertEquals(hierarchyWithin("a", entityPrimaryKeyInSet(10), directRelation()), constraint5);

        final FilterConstraint constraint6 = parseFilterConstraint("hierarchyWithin(?,entityPrimaryKeyInSet(?))", "a", 10);
        assertEquals(hierarchyWithin("a", entityPrimaryKeyInSet(10)), constraint6);

        final FilterConstraint constraint7 = parseFilterConstraint("hierarchyWithin(@name,entityPrimaryKeyInSet(@par))", Map.of("name", "a", "par", 10));
        assertEquals(hierarchyWithin("a", entityPrimaryKeyInSet(10)), constraint7);

        final FilterConstraint constraint8 = parseFilterConstraint("hierarchyWithin(?,entityPrimaryKeyInSet(?))","a", 10);
        assertEquals(hierarchyWithin("a", entityPrimaryKeyInSet(10)), constraint8);

        final FilterConstraint constraint9 = parseFilterConstraint("hierarchyWithin(?,entityPrimaryKeyInSet(?),excluding(entityPrimaryKeyInSet(?)))", "a", 10, 2);
        assertEquals(hierarchyWithin("a", entityPrimaryKeyInSet(10), excluding(entityPrimaryKeyInSet(2))), constraint9);
    }

    @Test
    void shouldNotParseHierarchyWithinConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("hierarchyWithin('a',10)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("hierarchyWithin('a',?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("hierarchyWithin('a',?)", "c"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("hierarchyWithin('a',@b)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithin"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithin()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithin('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithin(10)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithin(directRelation())"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithin(1,'a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithin(directRelation(),'a')"));
    }

    @Test
    void shouldParseHierarchyWithinSelfConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("hierarchyWithinSelf(entityPrimaryKeyInSet(10))");
        assertEquals(hierarchyWithinSelf(entityPrimaryKeyInSet(10)), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraint("hierarchyWithinSelf(entityPrimaryKeyInSet(?))", 10);
        assertEquals(hierarchyWithinSelf(entityPrimaryKeyInSet(10)), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraint("hierarchyWithinSelf(entityPrimaryKeyInSet(?), directRelation(), excluding(entityPrimaryKeyInSet(?)))", 10, 1);
        assertEquals(hierarchyWithinSelf(entityPrimaryKeyInSet(10), directRelation(), excluding(entityPrimaryKeyInSet(1))), constraint3);
    }

    @Test
    void shouldNotParseHierarchyWithinSelfConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("hierarchyWithinSelf(10)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithinSelf"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithinSelf()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithinSelf('a')"));
    }

    @Test
    void shouldParseHierarchyWithinRootConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("hierarchyWithinRoot('a')");
        assertEquals(hierarchyWithinRoot("a"), constraint1);

        final FilterConstraint constraint4 = parseFilterConstraintUnsafe("hierarchyWithinRoot('a')");
        assertEquals(hierarchyWithinRoot("a"), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraintUnsafe("hierarchyWithinRoot('a',excluding(entityPrimaryKeyInSet(1,3)))");
        assertEquals(hierarchyWithinRoot("a", excluding(entityPrimaryKeyInSet(1, 3))), constraint5);

        final FilterConstraint constraint6 = parseFilterConstraintUnsafe("hierarchyWithinRoot (  'a' )");
        assertEquals(hierarchyWithinRoot("a"), constraint6);

        final FilterConstraint constraint8 = parseFilterConstraint("hierarchyWithinRoot(?,excluding(entityPrimaryKeyInSet(?)))","a", 10);
        assertEquals(hierarchyWithinRoot("a", excluding(entityPrimaryKeyInSet(10))), constraint8);
    }

    @Test
    void shouldNotParseHierarchyWithinRootConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithinRoot(?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithinRoot(@a)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithinRoot"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithinRoot(1,'a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithinRoot(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithinRoot(directRelation(),'a')"));
    }

    @Test
    void shouldParseHierarchyWithinRootSelfConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("hierarchyWithinRootSelf()");
        assertEquals(hierarchyWithinRootSelf(), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("hierarchyWithinRootSelf(directRelation())");
        assertEquals(hierarchyWithinRootSelf(directRelation()), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("hierarchyWithinRootSelf(directRelation(),excluding(entityPrimaryKeyInSet(1)))");
        assertEquals(hierarchyWithinRootSelf(directRelation(), excluding(entityPrimaryKeyInSet(1))), constraint3);

        final FilterConstraint constraint7 = parseFilterConstraint("hierarchyWithinRootSelf (   directRelation()   ,excluding( entityPrimaryKeyInSet( ?) ) )", 1);
        assertEquals(hierarchyWithinRootSelf(directRelation(), excluding(entityPrimaryKeyInSet(1))), constraint7);
    }

    @Test
    void shouldNotParseHierarchyWithinRootSelfConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithinRootSelf(excluding(entityPrimaryKeyInSet(?)))"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithinRootSelf"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithinRootSelf('a')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("hierarchyWithinRootSelf(directRelation(),'a')"));
    }

    @Test
    void shouldParseDirectRelationConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("directRelation()");
        assertEquals(directRelation(), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("directRelation (  )");
        assertEquals(directRelation(), constraint2);
    }

    @Test
    void shouldNotParseDirectRelationConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("directRelation"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("directRelation('a')"));
    }

    @Test
    void shouldParseHierarchyHavingConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("having(entityPrimaryKeyInSet(1))");
        assertEquals(having(entityPrimaryKeyInSet(1)), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("having(entityPrimaryKeyInSet(1,5,6))");
        assertEquals(having(entityPrimaryKeyInSet(1, 5, 6)), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("having ( entityPrimaryKeyInSet (  1 , 6, 2 ) )");
        assertEquals(having(entityPrimaryKeyInSet(1, 6, 2)), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("having(entityPrimaryKeyInSet(?))", 1);
        assertEquals(having(entityPrimaryKeyInSet(1)), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("having(entityPrimaryKeyInSet(@pk))", Map.of("pk", 1));
        assertEquals(having(entityPrimaryKeyInSet(1)), constraint5);

        final FilterConstraint constraint6 = parseFilterConstraint("having(entityPrimaryKeyInSet(?))", List.of(1,2));
        assertEquals(having(entityPrimaryKeyInSet(1, 2)), constraint6);

        final FilterConstraint constraint7 = parseFilterConstraint("having(entityPrimaryKeyInSet(@pk))", Map.of("pk", List.of(1, 2)));
        assertEquals(having(entityPrimaryKeyInSet(1, 2)), constraint7);

        final FilterConstraint constraint8 = parseFilterConstraint("having(entityPrimaryKeyInSet(?,?))", 1, 2);
        assertEquals(having(entityPrimaryKeyInSet(1, 2)), constraint8);

        final FilterConstraint constraint9 = parseFilterConstraint("having(entityPrimaryKeyInSet(@pk1,@pk2))", Map.of("pk1", 1, "pk2", 2));
        assertEquals(having(entityPrimaryKeyInSet(1, 2)), constraint9);
    }

    @Test
    void shouldNotParseHierarchyHavingConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("having(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("having(?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("having(@a)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("having(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("having"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("having()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("having('a','b')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("having(1,'a')"));
    }

    @Test
    void shouldParseExcludingRootConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("excludingRoot()");
        assertEquals(excludingRoot(), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("excludingRoot (  )");
        assertEquals(excludingRoot(), constraint2);
    }

    @Test
    void shouldNotParseExcludingRootConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("excludingRoot"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("excludingRoot('a')"));
    }

    @Test
    void shouldParseExcludingConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("excluding(entityPrimaryKeyInSet(1))");
        assertEquals(excluding(entityPrimaryKeyInSet(1)), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("excluding(entityPrimaryKeyInSet(1,5,6))");
        assertEquals(excluding(entityPrimaryKeyInSet(1, 5, 6)), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("excluding ( entityPrimaryKeyInSet (  1 , 6, 2 ) )");
        assertEquals(excluding(entityPrimaryKeyInSet(1, 6, 2)), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraint("excluding(entityPrimaryKeyInSet(?))", 1);
        assertEquals(excluding(entityPrimaryKeyInSet(1)), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("excluding(entityPrimaryKeyInSet(@pk))", Map.of("pk", 1));
        assertEquals(excluding(entityPrimaryKeyInSet(1)), constraint5);

        final FilterConstraint constraint6 = parseFilterConstraint("excluding(entityPrimaryKeyInSet(?))", List.of(1,2));
        assertEquals(excluding(entityPrimaryKeyInSet(1, 2)), constraint6);

        final FilterConstraint constraint7 = parseFilterConstraint("excluding(entityPrimaryKeyInSet(@pk))", Map.of("pk", List.of(1, 2)));
        assertEquals(excluding(entityPrimaryKeyInSet(1, 2)), constraint7);

        final FilterConstraint constraint8 = parseFilterConstraint("excluding(entityPrimaryKeyInSet(?,?))", 1, 2);
        assertEquals(excluding(entityPrimaryKeyInSet(1, 2)), constraint8);

        final FilterConstraint constraint9 = parseFilterConstraint("excluding(entityPrimaryKeyInSet(@pk1,@pk2))", Map.of("pk1", 1, "pk2", 2));
        assertEquals(excluding(entityPrimaryKeyInSet(1, 2)), constraint9);
    }

    @Test
    void shouldNotParseExcludingConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("excluding(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("excluding(?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("excluding(@a)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("excluding(1)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("excluding"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("excluding()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("excluding('a','b')"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("excluding(1,'a')"));
    }

    @Test
    void shouldParseEntityHavingConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("entityHaving(attributeEquals('a',1))");
        assertEquals(entityHaving(attributeEquals("a", 1L)), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("entityHaving ( attributeEquals('a',1)  )");
        assertEquals(entityHaving(attributeEquals("a", 1L)), constraint2);
    }

    @Test
    void shouldNotParseEntityHavingConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("entityHaving"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("entityHaving()"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("entityHaving(collection('a'))"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("entityHaving(attributeEquals('a',1),attributeEquals('b','c'))"));
    }

    @Test
    void shouldParseScopeConstraint() {
        final FilterConstraint constraint1 = parseFilterConstraintUnsafe("scope(LIVE)");
        assertEquals(scope(Scope.LIVE), constraint1);

        final FilterConstraint constraint2 = parseFilterConstraintUnsafe("scope ( LIVE )");
        assertEquals(scope(Scope.LIVE), constraint2);

        final FilterConstraint constraint3 = parseFilterConstraintUnsafe("scope ( ARCHIVED )");
        assertEquals(scope(Scope.ARCHIVED), constraint3);

        final FilterConstraint constraint4 = parseFilterConstraintUnsafe("scope ( ARCHIVED,    LIVE )");
        assertEquals(scope(Scope.ARCHIVED, Scope.LIVE), constraint4);

        final FilterConstraint constraint5 = parseFilterConstraint("scope ( ?,    ? )", Scope.ARCHIVED, Scope.LIVE);
        assertEquals(scope(Scope.ARCHIVED, Scope.LIVE), constraint5);

        final FilterConstraint constraint = parseFilterConstraint("scope ( @a,    @b )", Map.of("a", Scope.ARCHIVED, "b", Scope.LIVE));
        assertEquals(scope(Scope.ARCHIVED, Scope.LIVE), constraint);
    }

    @Test
    void shouldNotParseScopeConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("scope"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraint("scope(LIVE)"));
        assertThrows(EvitaSyntaxException.class, () -> parseFilterConstraintUnsafe("scope('a','b')"));
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "filterConstraint"
     *
     * @param string string to parse
     * @param positionalArguments positional arguments to substitute
     * @return parsed constraint
     */
    private FilterConstraint parseFilterConstraint(@Nonnull String string, @Nonnull Object... positionalArguments) {
        return ParserExecutor.execute(
            new ParseContext(positionalArguments),
            () -> ParserFactory.getParser(string).filterConstraint().accept(new EvitaQLFilterConstraintVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "filterConstraint"
     *
     * @param string string to parse
     * @param namedArguments named arguments to substitute
     * @return parsed constraint
     */
    private FilterConstraint parseFilterConstraint(@Nonnull String string, @Nonnull Map<String, Object> namedArguments) {
        return ParserExecutor.execute(
            new ParseContext(namedArguments),
            () -> ParserFactory.getParser(string).filterConstraint().accept(new EvitaQLFilterConstraintVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "filterConstraint"
     *
     * @param string string to parse
     * @param namedArguments named arguments to substitute
     * @param positionalArguments positional arguments to substitute
     * @return parsed constraint
     */
    private FilterConstraint parseFilterConstraint(@Nonnull String string, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments) {
        return ParserExecutor.execute(
            new ParseContext(namedArguments, positionalArguments),
            () -> ParserFactory.getParser(string).filterConstraint().accept(new EvitaQLFilterConstraintVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "filterConstraint" in unsafe mode
     *
     * @param string string to parse
     * @return parsed constraint
     */
    private FilterConstraint parseFilterConstraintUnsafe(@Nonnull String string) {
        final ParseContext context = new ParseContext();
        context.setMode(ParseMode.UNSAFE);
        return ParserExecutor.execute(
            context,
            () -> ParserFactory.getParser(string).filterConstraint().accept(new EvitaQLFilterConstraintVisitor())
        );
    }
}

