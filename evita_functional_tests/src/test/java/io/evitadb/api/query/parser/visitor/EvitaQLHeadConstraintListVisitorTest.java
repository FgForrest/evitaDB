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

import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParseMode;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.api.query.QueryConstraints.collection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EvitaQLHeadConstraintListVisitor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
class EvitaQLHeadConstraintListVisitorTest {

    @Test
    void shouldParseHeadConstraintList() {
        final List<HeadConstraint> constraintList1 = parseHeadConstraintListUnsafe("collection('product')");
        assertEquals(
            List.of(collection("product")),
            constraintList1
        );

        final List<HeadConstraint> constraintList2 = parseHeadConstraintListUnsafe("collection('product'),collection('brand')");
        assertEquals(
            List.of(collection("product"), collection("brand")),
            constraintList2
        );

        final List<HeadConstraint> constraintList3 = parseHeadConstraintList("collection(?)", "product");
        assertEquals(
            List.of(collection("product")),
            constraintList3
        );

        final List<HeadConstraint> constraintList4 = parseHeadConstraintList("collection(?),collection(?)", "product", "brand");
        assertEquals(
            List.of(collection("product"), collection("brand")),
            constraintList4
        );
    }

    @Test
    void shouldNotParseHeadConstraintList() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseHeadConstraintList("attributeEqualsTrue('code')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseHeadConstraintList("collection('product'),attributeEqualsTrue('code')"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseHeadConstraintList("collection('product')"));
    }


    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "headConstraintListUnit"
     *
     * @param string string to parse
     * @param positionalArguments positional arguments to substitute
     * @return parsed query
     */
    private List<HeadConstraint> parseHeadConstraintList(@Nonnull String string, @Nonnull Object... positionalArguments) {
        return ParserExecutor.execute(
            new ParseContext(positionalArguments),
            () -> ParserFactory.getParser(string).headConstraintListUnit().headConstraintList().accept(new EvitaQLHeadConstraintListVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "headConstraintListUnit"
     *
     * @param string string to parse
     * @return parsed query
     */
    private List<HeadConstraint> parseHeadConstraintListUnsafe(@Nonnull String string) {
        final ParseContext context = new ParseContext();
        context.setMode(ParseMode.UNSAFE);
        return ParserExecutor.execute(
            context,
            () -> ParserFactory.getParser(string).headConstraintListUnit().headConstraintList().accept(new EvitaQLHeadConstraintListVisitor())
        );
    }
}
