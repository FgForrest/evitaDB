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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.parser.visitor;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EvitaQLConstraintVisitor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
class EvitaQLConstraintVisitorTest {

    @Test
    void shouldDelegateToHeadConstraintVisitor() {
        final Constraint<?> constraint = parseConstraint("collection('col')");
        assertTrue(HeadConstraint.class.isAssignableFrom(constraint.getClass()));
    }

    @Test
    void shouldDelegateToFilterConstraintVisitor() {
        final Constraint<?> constraint = parseConstraint("attributeEqualsTrue('name')");
        assertTrue(FilterConstraint.class.isAssignableFrom(constraint.getClass()));
    }

    @Test
    void shouldDelegateToOrderConstraintVisitor() {
        final Constraint<?> constraint = parseConstraint("attributeNatural('name')");
        assertTrue(OrderConstraint.class.isAssignableFrom(constraint.getClass()));
    }

    @Test
    void shouldDelegateToRequireConstraintVisitor() {
        final Constraint<?> constraint = parseConstraint("attributeContentAll()");
        assertTrue(RequireConstraint.class.isAssignableFrom(constraint.getClass()));
    }

    @Test
    void shouldNotDelegateToAnyVisitor() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseConstraint("nonExistingConstraint()"));
    }


    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "query"
     *
     * @param string string to parse
     * @return parsed query
     */
    private Constraint<?> parseConstraint(@Nonnull String string) {
        return ParserExecutor.execute(
            new ParseContext(),
            () -> ParserFactory.getParser(string).constraint().accept(new EvitaQLConstraintVisitor())
        );
    }
}
