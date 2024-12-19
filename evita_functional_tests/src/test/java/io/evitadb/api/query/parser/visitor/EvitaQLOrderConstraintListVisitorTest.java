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

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParseMode;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EvitaQLOrderConstraintListVisitor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
class EvitaQLOrderConstraintListVisitorTest {

    @Test
    void shouldParseOrderConstraintList() {
        final List<OrderConstraint> constraintList1 = parseOrderConstraintListUnsafe("attributeNatural('code')");
        assertEquals(
            List.of(attributeNatural("code")),
            constraintList1
        );

        final List<OrderConstraint> constraintList2 = parseOrderConstraintListUnsafe("attributeNatural('code'),attributeNatural('age')");
        assertEquals(
            List.of(attributeNatural("code"), attributeNatural("age")),
            constraintList2
        );

        final List<OrderConstraint> constraintList3 = parseOrderConstraintList("attributeNatural(?)", "code");
        assertEquals(
            List.of(attributeNatural("code")),
            constraintList3
        );

        final List<OrderConstraint> constraintList4 = parseOrderConstraintList("attributeNatural(?),attributeNatural(?)", "code", "age");
        assertEquals(
            List.of(attributeNatural("code"), attributeNatural("age")),
            constraintList4
        );
    }

    @Test
    void shouldNotParseOrderConstraintList() {
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintList("collection('code')"));
        assertThrows(EvitaSyntaxException.class, () -> parseOrderConstraintList("attributeNatural('product'),collection('code')"));
    }


    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "orderConstraintListUnit"
     *
     * @param string string to parse
     * @param positionalArguments positional arguments to substitute
     * @return parsed query
     */
    private List<OrderConstraint> parseOrderConstraintList(@Nonnull String string, @Nonnull Object... positionalArguments) {
        return ParserExecutor.execute(
            new ParseContext(positionalArguments),
            () -> ParserFactory.getParser(string).orderConstraintListUnit().orderConstraintList().accept(new EvitaQLOrderConstraintListVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "orderConstraintListUnit"
     *
     * @param string string to parse
     * @return parsed query
     */
    private List<OrderConstraint> parseOrderConstraintListUnsafe(@Nonnull String string) {
        final ParseContext context = new ParseContext();
        context.setMode(ParseMode.UNSAFE);
        return ParserExecutor.execute(
            context,
            () -> ParserFactory.getParser(string).orderConstraintListUnit().orderConstraintList().accept(new EvitaQLOrderConstraintListVisitor())
        );
    }
}
