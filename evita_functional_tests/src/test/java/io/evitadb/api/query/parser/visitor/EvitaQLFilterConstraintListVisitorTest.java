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
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParseMode;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.api.query.QueryConstraints.attributeEqualsTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EvitaQLFilterConstraintListVisitor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
class EvitaQLFilterConstraintListVisitorTest {

    @Test
    void shouldParseFilterConstraintList() {
        final List<FilterConstraint> constraintList1 = parseFilterConstraintListUnsafe("attributeEqualsTrue('code')");
        assertEquals(
            List.of(attributeEqualsTrue("code")),
            constraintList1
        );

        final List<FilterConstraint> constraintList2 = parseFilterConstraintListUnsafe("attributeEqualsTrue('code'),attributeEqualsTrue('age')");
        assertEquals(
            List.of(attributeEqualsTrue("code"), attributeEqualsTrue("age")),
            constraintList2
        );

        final List<FilterConstraint> constraintList3 = parseFilterConstraintList("attributeEqualsTrue(?)", "code");
        assertEquals(
            List.of(attributeEqualsTrue("code")),
            constraintList3
        );

        final List<FilterConstraint> constraintList4 = parseFilterConstraintList("attributeEqualsTrue(?),attributeEqualsTrue(?)", "code", "age");
        assertEquals(
            List.of(attributeEqualsTrue("code"), attributeEqualsTrue("age")),
            constraintList4
        );
    }

    @Test
    void shouldNotParseFilterConstraintList() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseFilterConstraintList("collection('code')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseFilterConstraintList("attributeEqualsTrue('product'),collection('code')"));
    }


    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "filterConstraintListUnit"
     *
     * @param string string to parse
     * @param positionalArguments positional arguments to substitute
     * @return parsed query
     */
    private List<FilterConstraint> parseFilterConstraintList(@Nonnull String string, @Nonnull Object... positionalArguments) {
        return ParserExecutor.execute(
            new ParseContext(positionalArguments),
            () -> ParserFactory.getParser(string).filterConstraintListUnit().filterConstraintList().accept(new EvitaQLFilterConstraintListVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "filterConstraintListUnit"
     *
     * @param string string to parse
     * @return parsed query
     */
    private List<FilterConstraint> parseFilterConstraintListUnsafe(@Nonnull String string) {
        final ParseContext context = new ParseContext();
        context.setMode(ParseMode.UNSAFE);
        return ParserExecutor.execute(
            context,
            () -> ParserFactory.getParser(string).filterConstraintListUnit().filterConstraintList().accept(new EvitaQLFilterConstraintListVisitor())
        );
    }
}
