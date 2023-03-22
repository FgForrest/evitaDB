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

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EvitaQLRequireConstraintListVisitor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
class EvitaQLRequireConstraintListVisitorTest {

    @Test
    void shouldParseRequireConstraintList() {
        final List<RequireConstraint> constraintList1 = parseRequireConstraintList("attributeContent('code')");
        assertEquals(
            List.of(attributeContent("code")),
            constraintList1
        );

        final List<RequireConstraint> constraintList2 = parseRequireConstraintList("attributeContent('code'),attributeContent('age')");
        assertEquals(
            List.of(attributeContent("code"), attributeContent("age")),
            constraintList2
        );

        final List<RequireConstraint> constraintList3 = parseRequireConstraintList("attributeContent(?)", "code");
        assertEquals(
            List.of(attributeContent("code")),
            constraintList3
        );

        final List<RequireConstraint> constraintList4 = parseRequireConstraintList("attributeContent('code'),attributeContent(?)", "age");
        assertEquals(
            List.of(attributeContent("code"), attributeContent("age")),
            constraintList4
        );
    }

    @Test
    void shouldNotParseRequireConstraintList() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintList("collection('product')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseRequireConstraintList("attributeContent('code'),collection('product')"));
    }


    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "requireConstraintListUnit"
     *
     * @param string string to parse
     * @param positionalArguments positional arguments to substitute
     * @return parsed query
     */
    private List<RequireConstraint> parseRequireConstraintList(@Nonnull String string, @Nonnull Object... positionalArguments) {
        return ParserExecutor.execute(
            new ParseContext(positionalArguments),
            () -> ParserFactory.getParser(string).requireConstraintListUnit().requireConstraintList().accept(new EvitaQLRequireConstraintListVisitor())
        );
    }
}
