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

import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.collection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EvitaQLHeadConstraintVisitor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
class EvitaQLHeadConstraintVisitorTest {

    @Test
    void shouldParseCollectionConstraint() {
        final HeadConstraint constraint1 = parseHeadConstraint("collection('product')");
        assertEquals(collection("product"), constraint1);

        final HeadConstraint constraint2 = parseHeadConstraint("collection(?)", "product");
        assertEquals(collection("product"), constraint2);

        final HeadConstraint constraint3 = parseHeadConstraint("collection(@col)", Map.of("col", "product"));
        assertEquals(collection("product"), constraint3);
    }

    @Test
    void shouldNotParseCollectionConstraint() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseHeadConstraint("collection"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseHeadConstraint("collection()"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseHeadConstraint("collection(?)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseHeadConstraint("collection(@col)"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseHeadConstraint("collection('product','variant')"));
    }


    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "headingConstraint"
     *
     * @param string string to parse
     * @param positionalArguments positional arguments to substitute
     * @return parsed constraint
     */
    private HeadConstraint parseHeadConstraint(@Nonnull String string, @Nonnull Object... positionalArguments) {
        return ParserExecutor.execute(
            new ParseContext(positionalArguments),
            () -> ParserFactory.getParser(string).headConstraint().accept(new EvitaQLHeadConstraintVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "headingConstraint"
     *
     * @param string string to parse
     * @param namedArguments named arguments to substitute
     * @return parsed constraint
     */
    private HeadConstraint parseHeadConstraint(@Nonnull String string, @Nonnull Map<String, Object> namedArguments) {
        return ParserExecutor.execute(
            new ParseContext(namedArguments),
            () -> ParserFactory.getParser(string).headConstraint().accept(new EvitaQLHeadConstraintVisitor())
        );
    }
}
