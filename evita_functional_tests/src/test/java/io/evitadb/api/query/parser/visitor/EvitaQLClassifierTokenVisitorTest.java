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

import io.evitadb.api.query.parser.Classifier;
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import io.evitadb.exception.EvitaInternalError;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EvitaQLClassifierTokenVisitor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
class EvitaQLClassifierTokenVisitorTest {

    @Test
    void shouldParseSingleClassifier() {
        final Classifier classifier1 = parseClassifier("?", "code");
        assertEquals("code", classifier1.asSingleClassifier());

        final Classifier classifier2 = parseClassifier("@name", Map.of("name", "code"));
        assertEquals("code", classifier2.asSingleClassifier());

        final Classifier classifier3 = parseClassifier("'name'");
        assertEquals("name", classifier3.asSingleClassifier());
    }

    @Test
    void shouldNotParseSingleClassifier() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseClassifier("100"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseClassifier("100.588"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseClassifier("2020-20-11"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseClassifier("2020-20-11T15:16:30"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseClassifier("`cs_CZ`"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseClassifier("?"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseClassifier("?", 1));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseClassifier("@name"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseClassifier("@name", Map.of("other", "something")));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseClassifier("@name", Map.of("name", 1)));
        assertThrows(EvitaInternalError.class, () -> parseClassifier("'name'").asClassifierArray());
    }

    @Test
    void shouldParseVariadicClassifier() {
        final Classifier classifier1 = parseVariadicClassifier("?", List.of("a", "b"));
        assertArrayEquals(new String[] { "a", "b" }, classifier1.asClassifierArray());

        final Classifier classifier2 = parseVariadicClassifier("?", new LinkedHashSet<>(List.of("a", "b")));
        assertArrayEquals(new String[] { "a", "b" }, classifier2.asClassifierArray());

        final Classifier classifier3 = parseVariadicClassifier("?", (Object) new String[] { "a", "b" });
        assertArrayEquals(new String[] { "a", "b" }, classifier3.asClassifierArray());

        final Classifier classifier4 = parseVariadicClassifier("@names", Map.of("names", List.of("a", "b")));
        assertArrayEquals(new String[] { "a", "b" }, classifier4.asClassifierArray());

        final Classifier classifier5 = parseVariadicClassifier("@names", Map.of("names", new LinkedHashSet<>(List.of("a", "b"))));
        assertArrayEquals(new String[] { "a", "b" }, classifier5.asClassifierArray());

        final Classifier classifier6 = parseVariadicClassifier("@names", Map.of("names", new String[] { "a", "b" }));
        assertArrayEquals(new String[] { "a", "b" }, classifier6.asClassifierArray());

        final Classifier classifier7 = parseVariadicClassifier("'a', 'b'");
        assertArrayEquals(new String[] { "a", "b" }, classifier7.asClassifierArray());

        final Classifier classifier8 = parseVariadicClassifier("?", "a");
        assertArrayEquals(new String[] { "a" }, classifier8.asClassifierArray());

        final Classifier classifier9 = parseVariadicClassifier("@names", Map.of("names", "a"));
        assertArrayEquals(new String[] { "a" }, classifier9.asClassifierArray());

        final Classifier classifier10 = parseVariadicClassifier("'a'");
        assertArrayEquals(new String[] { "a" }, classifier10.asClassifierArray());

        final Classifier classifier11 = parseVariadicClassifier("'a','b'");
        assertArrayEquals(new String[] { "a", "b" }, classifier11.asClassifierArray());
    }

    @Test
    void shouldNotParseVariadicClassifier() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseVariadicClassifier("100.588"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseVariadicClassifier("2020-20-11"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseVariadicClassifier("`cs_CZ`"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseVariadicClassifier("?"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseVariadicClassifier("?", (Object) new int[] { 1 }));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseVariadicClassifier("@name"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseVariadicClassifier("@name", Map.of("other", "something")));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseVariadicClassifier("@name", Map.of("name", new int[] { 1 })));
        assertThrows(EvitaInternalError.class, () -> parseVariadicClassifier("'name'").asSingleClassifier());
    }


    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "classifierToken"
     *
     * @param string string to parse
     * @return parsed classifier
     */
    private Classifier parseClassifier(@Nonnull String string) {
        return ParserExecutor.execute(
            new ParseContext(),
            () -> ParserFactory.getParser(string).classifierToken().accept(new EvitaQLClassifierTokenVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "classifierToken"
     *
     * @param string string to parse
     * @param positionalArguments positional arguments to substitute
     * @return parsed classifier
     */
    private Classifier parseClassifier(@Nonnull String string, @Nonnull Object... positionalArguments) {
        return ParserExecutor.execute(
            new ParseContext(positionalArguments),
            () -> ParserFactory.getParser(string).classifierToken().accept(new EvitaQLClassifierTokenVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "classifierToken"
     *
     * @param string string to parse
     * @param namedArguments named arguments to substitute
     * @return parsed classifier
     */
    private Classifier parseClassifier(@Nonnull String string, @Nonnull Map<String, Object> namedArguments) {
        return ParserExecutor.execute(
            new ParseContext(namedArguments),
            () -> ParserFactory.getParser(string).classifierToken().accept(new EvitaQLClassifierTokenVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "variadicClassifierTokens"
     *
     * @param string string to parse
     * @return parsed classifier
     */
    private Classifier parseVariadicClassifier(@Nonnull String string) {
        return ParserExecutor.execute(
            new ParseContext(),
            () -> ParserFactory.getParser(string).variadicClassifierTokens().accept(new EvitaQLClassifierTokenVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "variadicClassifierTokens"
     *
     * @param string string to parse
     * @param positionalArguments positional arguments to substitute
     * @return parsed classifier
     */
    private Classifier parseVariadicClassifier(@Nonnull String string, @Nonnull Object... positionalArguments) {
        return ParserExecutor.execute(
            new ParseContext(positionalArguments),
            () -> ParserFactory.getParser(string).variadicClassifierTokens().accept(new EvitaQLClassifierTokenVisitor())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "variadicClassifierTokens"
     *
     * @param string string to parse
     * @param namedArguments named arguments to substitute
     * @return parsed classifier
     */
    private Classifier parseVariadicClassifier(@Nonnull String string, @Nonnull Map<String, Object> namedArguments) {
        return ParserExecutor.execute(
            new ParseContext(namedArguments),
            () -> ParserFactory.getParser(string).variadicClassifierTokens().accept(new EvitaQLClassifierTokenVisitor())
        );
    }
}
