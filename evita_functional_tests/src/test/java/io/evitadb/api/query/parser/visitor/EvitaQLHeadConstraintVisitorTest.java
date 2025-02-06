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
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.head;
import static io.evitadb.api.query.QueryConstraints.label;
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
        final HeadConstraint constraint1 = parseHeadConstraintUnsafe("collection('product')");
        assertEquals(collection("product"), constraint1);

        final HeadConstraint constraint2 = parseHeadConstraint("collection(?)", "product");
        assertEquals(collection("product"), constraint2);

        final HeadConstraint constraint3 = parseHeadConstraint("collection(@col)", Map.of("col", "product"));
        assertEquals(collection("product"), constraint3);
    }

    @Test
    void shouldNotParseCollectionConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("collection"));
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("collection()"));
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("collection('product')"));
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("collection(?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("collection(@col)"));
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("collection('product','variant')"));
    }

    @Test
    void shouldParseHeadConstraint() {
        final HeadConstraint constraint1 = parseHeadConstraintUnsafe("head(collection('product'))");
        assertEquals(head(collection("product")), constraint1);
    }

    @Test
    void shouldNotParseHeadConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("head"));
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("head()"));
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("head('product')"));
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("head(?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("head(@col)"));
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("head('product','variant')"));
    }

    @Test
    void shouldParseLabelConstraint() {
        final HeadConstraint constraint1 = parseHeadConstraintUnsafe("label('url', '/cs/product')");
        assertEquals(label("url", "/cs/product"), constraint1);

        final HeadConstraint constraint2 = parseHeadConstraintUnsafe("label('id', 1)");
        assertEquals(label("id", 1L), constraint2);

        final HeadConstraint constraint3 = parseHeadConstraint("label(?,?)", "url", "/cs/product");
        assertEquals(label("url", "/cs/product"), constraint3);

        final HeadConstraint constraint4 = parseHeadConstraint("label(@key, @val)", Map.of("key", "url", "val", "/cs/product"));
        assertEquals(label("url", "/cs/product"), constraint4);
    }

    @Test
    void shouldNotParseLabelConstraint() {
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("label"));
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("label()"));
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("label('product')"));
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("label(?)"));
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("label(@col)"));
        assertThrows(EvitaSyntaxException.class, () -> parseHeadConstraint("label('product','variant')"));
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "headingConstraint"
     *
     * @param string string to parse
     * @param positionalArguments positional arguments to substitute
     * @return parsed constraint
     */
    private static HeadConstraint parseHeadConstraint(@Nonnull String string, @Nonnull Object... positionalArguments) {
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
    private static HeadConstraint parseHeadConstraint(@Nonnull String string, @Nonnull Map<String, Object> namedArguments) {
        return ParserExecutor.execute(
            new ParseContext(namedArguments),
            () -> ParserFactory.getParser(string).headConstraint().accept(new EvitaQLHeadConstraintVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "headingConstraint"
     *
     * @param string string to parse
     * @return parsed constraint
     */
    private static HeadConstraint parseHeadConstraintUnsafe(@Nonnull String string) {
        final ParseContext context = new ParseContext();
        context.setMode(ParseMode.UNSAFE);
        return ParserExecutor.execute(
            context,
            () -> ParserFactory.getParser(string).headConstraint().accept(new EvitaQLHeadConstraintVisitor())
        );
    }
}
