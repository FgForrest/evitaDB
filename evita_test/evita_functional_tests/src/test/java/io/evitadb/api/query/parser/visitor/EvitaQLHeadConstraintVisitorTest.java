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

import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParseMode;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.api.query.require.HistogramBehavior;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.time.DayOfWeek;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.head;
import static io.evitadb.api.query.QueryConstraints.label;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;

/**
 * Tests for {@link EvitaQLHeadConstraintVisitor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
@Tag(CONTRACT)
@Tag(QUERY)
class EvitaQLHeadConstraintVisitorTest {

	/**
	 * Checks that the comparable label value rejects a currency and a locale in either binding mode, because the Java
	 * factory requires a comparable value and neither type is one.
	 */
	@Test
	@DisplayName("Reject currency and locale parameters in labels")
	void shouldRejectBoundCurrencyAndLocaleInLabel() {
		final Currency usd = Currency.getInstance("USD");
		assertAll(
			() -> assertRefusedType(usd, () -> parseHeadConstraint("label(?, ?)", "currency", usd)),
			() -> assertRefusedType(usd, () -> parseHeadConstraint(
				"label(@key, @value)", Map.of("key", "currency", "value", usd)
			)),
			() -> assertRefusedType(
				Locale.ENGLISH, () -> parseHeadConstraint("label(?, ?)", "lang", Locale.ENGLISH)
			),
			() -> assertRefusedType(Locale.ENGLISH, () -> parseHeadConstraint(
				"label(@key, @value)", Map.of("key", "lang", "value", Locale.ENGLISH)
			))
		);
	}

	/**
	 * Checks the diagnostics for types the Java label constructor refuses.
	 *
	 * @param value the floating-point or enum parameter
	 */
	@ParameterizedTest
	@MethodSource("unsupportedLabelValues")
	@DisplayName("Name unsupported floating-point and enum label types in both binding modes")
	void shouldRejectUnsupportedLabelValues(@Nonnull Object value) {
		assertAll(
			() -> assertRefusedType(value, () -> parseHeadConstraint("label(?, ?)", "value", value)),
			() -> assertRefusedType(value, () -> parseHeadConstraint(
				"label(@name, @value)", Map.of("name", "value", "value", value)
			))
		);
	}

	/**
	 * Supplies floating-point values and both kinds of enum refused by the label factory.
	 *
	 * @return the refused label values
	 */
	@Nonnull
	static Stream<Object> unsupportedLabelValues() {
		return Stream.of(
			2.1d, 2.1f, DayOfWeek.MONDAY, HistogramBehavior.OPTIMIZED,
			Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
			Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY
		);
	}

	/** Checks that an enum literal still requires a type permitted by the label position. */
	@Test
	@DisplayName("Name the enum type when refusing an unquoted enum label value")
	void shouldRejectUntypedLabelEnumLiteral() {
		final EvitaSyntaxException error = assertThrows(
			EvitaSyntaxException.class, () -> parseHeadConstraintUnsafe("label('day', MONDAY)")
		);
		assertTrue(error.getMessage().contains(Enum.class.getTypeName()), error::getMessage);
	}

	/**
	 * Checks that parsing fails with a syntax error naming the runtime type of the value its position refuses,
	 * which a grammar or arity error would not name.
	 *
	 * @param refusedValue the bound value whose type the position does not allow
	 * @param parsing the parser call that binds the value
	 */
	private static void assertRefusedType(@Nonnull Object refusedValue, @Nonnull Executable parsing) {
		final EvitaSyntaxException error = assertThrows(EvitaSyntaxException.class, parsing);
		assertTrue(error.getMessage().contains(refusedValue.getClass().getTypeName()), error::getMessage);
	}

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
