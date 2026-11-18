/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.query.order;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.api.query.visitor.ConstraintCloneVisitor;
import io.evitadb.api.query.visitor.PrettyPrintingVisitor;
import io.evitadb.api.query.visitor.PrettyPrintingVisitor.StringWithParameters;
import io.evitadb.api.query.visitor.QueryPurifierVisitor;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.QUERY;
import static java.math.BigDecimal.valueOf;
import static org.junit.jupiter.api.Assertions.*;

/** Contracts for named weights, level boundaries, shared numbers and lossless decimal EvitaQL forms. */
@Tag(CONTRACT)
@Tag(ORDER)
@Tag(QUERY)
@DisplayName("Fulltext place weights")
class TextPlaceWeightsTest {
	/** Supplies every leaf and container shape, including empty containers retained for planning validation. */
	@Nonnull
	static Stream<OrderConstraint> forms() {
		return Stream.of(attributeWeight("name", valueOf(3)), associatedDataWeight("body", new BigDecimal("0.5")),
			referenceWeight("links"), entityWeight(), entityWeight(attributeWeight("name", valueOf(2))),
			entityWeight(associatedDataWeight("body", valueOf(4))),
			referenceWeight("links", attributeWeight("name", valueOf(2))),
			referenceWeight("links", entityWeight(attributeWeight("name", valueOf(2)),
				associatedDataWeight("body", valueOf(4)))),
			relevanceText(), relevanceText(attributeWeight("name", valueOf(3))),
			relevanceText(attributeWeight("name", valueOf(3)), associatedDataWeight("body", new BigDecimal("0.5")),
				referenceWeight("links", attributeWeight("name", valueOf(2)),
					entityWeight(attributeWeight("name", valueOf(5))))));
	}

	/** Each level retains its boundary and supplied order through parser and clone visitors. */
	@ParameterizedTest
	@MethodSource("forms")
	@DisplayName("Preserves every weight shape through parsing, purification and cloning")
	void shouldRoundTripEveryForm(@Nonnull OrderConstraint constraint) {
		final DefaultQueryParser parser = DefaultQueryParser.getInstance();
		final OrderConstraint parsed = parser.parseOrderConstraintUnsafe(constraint.toString()).getChildren()[0];
		assertEquals(constraint, parsed);
		assertEquals(parsed, parser.parseOrderConstraintUnsafe(parsed.toString()).getChildren()[0]);
		assertTrue(constraint.isApplicable());
		assertEquals(constraint, QueryPurifierVisitor.purify(constraint));
		assertEquals(constraint, ConstraintCloneVisitor.clone(constraint, (visitor, child) -> child));
		if (!(constraint instanceof RelevanceText)) {
			assertEquals(constraint, constraint.cloneWithArguments(constraint.getArguments()));
		}
		if (constraint instanceof AbstractOrderConstraintContainer container) {
			assertTrue(container.isNecessary());
			assertEquals(constraint, container.getCopyWithNewChildren(
				container.getChildren(), new Constraint<?>[0]));
		}
	}

	/** Integer and decimal literals retain their full decimal precision through both printing paths. */
	@ParameterizedTest
	@ValueSource(strings = {"0.0000001", "12345678.5", "0", "3", "3.0", "0.123456789123456789",
		"0.0000000000000000000000000000000000000000000014", "340282350000000000000000000000000000000"})
	@DisplayName("Round trips plain decimal weights losslessly through text and extracted parameters")
	void shouldRoundTripNumberForms(@Nonnull String number) {
		final DefaultQueryParser parser = DefaultQueryParser.getInstance();
		for (final String name : new String[]{"attributeWeight", "associatedDataWeight"}) {
			final OrderConstraint parsed = parser.parseOrderConstraintUnsafe(name + "('name'," + number + ")")
				.getChildren()[0];
			final BigDecimal expected = new BigDecimal(number).stripTrailingZeros();
			assertEquals(0, expected.compareTo((BigDecimal) parsed.getArguments()[1]));
			final String printed = parsed.toString();
			assertFalse(printed.substring(printed.lastIndexOf(',')).matches(".*[eE].*"), printed);
			assertEquals(parsed, parser.parseOrderConstraintUnsafe(printed).getChildren()[0]);
			final StringWithParameters extracted = PrettyPrintingVisitor.toStringWithParameterExtraction(parsed);
			assertFalse(extracted.query().matches(".*[eE][+-]?[0-9]+.*"), extracted.query());
			assertEquals(orderBy(parsed), parser.parseOrderConstraint(
				extracted.query(), extracted.parameters().toArray()));
		}
	}

	/** Canonical arguments make equality, hashes and text independent of decimal scale, including zero. */
	@Test
	@DisplayName("Normalizes scale once and retains BigDecimal arguments")
	void shouldNormalizeWeightScale() {
		for (final String number : new String[]{"1", "3", "0", "10000000"}) {
			final BigDecimal value = new BigDecimal(number);
			final AttributeWeight attribute = attributeWeight("name", value);
			final AssociatedDataWeight data = associatedDataWeight("body", value);
			for (final int scale : new int[]{0, 1, 2}) {
				final AttributeWeight scaledAttribute = attributeWeight("name", value.setScale(scale));
				final AssociatedDataWeight scaledData = associatedDataWeight("body", value.setScale(scale));
				assertEquals(attribute, scaledAttribute);
				assertEquals(data, scaledData);
				assertEquals(attribute.hashCode(), scaledAttribute.hashCode());
				assertEquals(data.hashCode(), scaledData.hashCode());
				assertEquals(attribute.toString(), scaledAttribute.toString());
				assertEquals(data.toString(), scaledData.toString());
				assertEquals(value, scaledAttribute.getWeight());
				assertEquals(value, scaledData.getWeight());
				assertSame(scaledAttribute.getWeight(), scaledAttribute.getArguments()[1]);
				assertSame(scaledData.getWeight(), scaledData.getArguments()[1]);
			}
		}
		for (final BigDecimal negative : new BigDecimal[]{valueOf(-1), new BigDecimal("-1E-100")}) {
			assertThrows(EvitaInvalidUsageException.class, () -> attributeWeight("name", negative));
			assertThrows(EvitaInvalidUsageException.class, () -> associatedDataWeight("body", negative));
		}
	}

	/**
	 * The query language has no binary floating point type: a weight is bound as an integer or a `BigDecimal`,
	 * and a bound `Float` or `Double` is refused whatever its value.
	 */
	@Test
	@DisplayName("Refuses binary floating query parameters even though weights are decimal")
	void shouldRefuseBoundBinaryFloatingWeight() {
		for (final float value : new float[]{2.5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
			assertThrows(EvitaSyntaxException.class, () -> DefaultQueryParser.getInstance()
				.parseOrderConstraint("attributeWeight(?,?)", "name", value));
			assertThrows(EvitaSyntaxException.class, () -> DefaultQueryParser.getInstance()
				.parseOrderConstraint("associatedDataWeight(?,?)", "body", value));
		}
		for (final double value : new double[]{2.5d, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
			assertThrows(EvitaSyntaxException.class, () -> DefaultQueryParser.getInstance()
				.parseOrderConstraint("attributeWeight(?,?)", "name", value));
			assertThrows(EvitaSyntaxException.class, () -> DefaultQueryParser.getInstance()
				.parseOrderConstraint("associatedDataWeight(?,?)", "body", value));
		}
	}

	/** Complete client queries retain exact decimal weights in literals and extracted BigDecimal parameters. */
	@Test
	@DisplayName("Keeps every weight through full-query text and parameter extraction")
	void shouldKeepWeightsThroughParameterExtraction() {
		for (final String number : new String[]{"0", "0.5", "3", "1E-7", "1.4E-45", "1E+7", "3.4028235E+38"}) {
			final BigDecimal value = new BigDecimal(number);
			final Query original = query(
				collection("product"),
				orderBy(relevanceText(
					attributeWeight("name", value),
					referenceWeight("brand", entityWeight(associatedDataWeight("description", value)))
				))
			);
			final StringWithParameters extracted = original.toStringWithParameterExtraction();
			final DefaultQueryParser parser = DefaultQueryParser.getInstance();
			assertEquals(original, parser.parseQueryUnsafe(original.toString()));
			assertEquals(original, parser.parseQuery(extracted.query(), extracted.parameters().toArray()));
		}
	}

	/** Mandatory names are never omitted by factories, constructors or argument copies. */
	@Test
	@DisplayName("Validates names and serialized argument shapes")
	void shouldValidateNamesAndArguments() {
		for (final String invalid : new String[]{null, "", "  "}) {
			assertThrows(EvitaInvalidUsageException.class, () -> attributeWeight(invalid, BigDecimal.ONE));
			assertThrows(EvitaInvalidUsageException.class, () -> new AssociatedDataWeight(invalid, BigDecimal.ONE));
			assertThrows(EvitaInvalidUsageException.class,
				() -> referenceWeight(invalid, attributeWeight("a", BigDecimal.ONE)));
			assertThrows(EvitaInvalidUsageException.class, () -> attributeWeight("a", BigDecimal.ONE)
				.cloneWithArguments(new Serializable[]{invalid, BigDecimal.ONE}));
			assertThrows(EvitaInvalidUsageException.class, () -> associatedDataWeight("a", BigDecimal.ONE)
				.cloneWithArguments(new Serializable[]{invalid, BigDecimal.ONE}));
			assertThrows(EvitaInvalidUsageException.class, () -> referenceWeight("links")
				.cloneWithArguments(new Serializable[]{invalid}));
		}
		assertThrows(EvitaInvalidUsageException.class, () -> attributeWeight("name", null));
		assertThrows(EvitaInvalidUsageException.class, () -> associatedDataWeight("body", null));
		for (final Serializable invalid : new Serializable[]{null, 1, 1L, 1f, 1d, "1"}) {
			assertThrows(EvitaInvalidUsageException.class, () -> attributeWeight("a", BigDecimal.ONE)
				.cloneWithArguments(new Serializable[]{"a", invalid}));
			assertThrows(EvitaInvalidUsageException.class, () -> associatedDataWeight("a", BigDecimal.ONE)
				.cloneWithArguments(new Serializable[]{"a", invalid}));
		}
		assertEquals("name", attributeWeight("name", valueOf(3)).getAttributeName());
		assertEquals("body", associatedDataWeight("body", valueOf(3)).getAssociatedDataName());
		assertEquals("links", referenceWeight("links").getReferenceName());
		assertEquals(attributeWeight("b", valueOf(2)), attributeWeight("a", BigDecimal.ONE)
			.cloneWithArguments(new Serializable[]{"b", valueOf(2)}));
		assertEquals(associatedDataWeight("b", valueOf(2)), associatedDataWeight("a", BigDecimal.ONE)
			.cloneWithArguments(new Serializable[]{"b", valueOf(2)}));
		assertThrows(EvitaInvalidUsageException.class,
			() -> attributeWeight("a", BigDecimal.ONE).cloneWithArguments(new Serializable[]{"a"}));
		assertThrows(EvitaInvalidUsageException.class,
			() -> associatedDataWeight("a", BigDecimal.ONE).cloneWithArguments(new Serializable[]{"a", "1"}));
		assertThrows(EvitaInvalidUsageException.class,
			() -> referenceWeight("links").cloneWithArguments(new Serializable[0]));
		assertThrows(EvitaInvalidUsageException.class,
			() -> entityWeight().cloneWithArguments(new Serializable[]{"a"}));
	}

	/** Compact exponents must be refused before normalization expands their mantissa. */
	@Test
	@DisplayName("Refuses million-digit weight expansion at construction")
	void shouldBoundDecimalWeightExpansion() {
		final BigDecimal value = new BigDecimal("1E+1000000");
		assertThrows(EvitaInvalidUsageException.class, () -> attributeWeight("a", value));
		assertThrows(EvitaInvalidUsageException.class, () -> associatedDataWeight("a", value));
	}

	/**
	 * Zero padding must be bounded before trailing zeros are stripped, because the strip removes one zero per division:
	 * stripping these million-digit inputs unbounded runs for minutes, the bounded path finishes well under a second.
	 * The generous timeout only catches a return of the digit-by-digit strip; it is no performance assertion.
	 */
	@Test
	@DisplayName("Refuses or accepts zero-padded weights without stripping them digit by digit")
	void shouldBoundZeroPaddedWeightsWithoutDigitByDigitStripping() {
		final BigInteger padded = BigInteger.TEN.pow(1_000_000);
		final BigDecimal oversized = new BigDecimal(padded, 0);
		final BigDecimal one = new BigDecimal(padded, 1_000_000);

		assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
			assertThrows(EvitaInvalidUsageException.class, () -> attributeWeight("a", oversized));
			assertThrows(InvalidSchemaMutationException.class,
				() -> AttributeSchemaContract.normalizeSearchWeight(oversized, "Attribute `a`"));
			assertEquals(BigDecimal.ONE, attributeWeight("a", one).getWeight());
			assertEquals(BigDecimal.ONE, AttributeSchemaContract.normalizeSearchWeight(one, "Attribute `a`"));
		});
	}

	/** Extreme scale representations must produce ordinary usage errors without arithmetic or string allocation. */
	@Test
	@DisplayName("Refuses extreme scales for both weight leaves with bounded diagnostics")
	void shouldBoundExtremeWeightScales() {
		for (final int scale : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE}) {
			for (final BigInteger mantissa : new BigInteger[]{BigInteger.ONE, BigInteger.TEN}) {
				final BigDecimal value = new BigDecimal(mantissa, scale);
				final EvitaInvalidUsageException attribute = assertThrows(EvitaInvalidUsageException.class,
					() -> attributeWeight("a", value));
				final EvitaInvalidUsageException data = assertThrows(EvitaInvalidUsageException.class,
					() -> associatedDataWeight("a", value));
				assertTrue(attribute.getMessage().length() < 500);
				assertTrue(data.getMessage().length() < 500);
			}
		}
	}

	/** Bounds apply to the stripped value, while every accepted normalized weight still prints in plain notation. */
	@Test
	@DisplayName("Accepts the inclusive precision and scale limits after stripping zeros")
	void shouldAcceptDecimalRepresentationBoundaries() {
		final BigInteger maximumPrecision = new BigInteger("1".repeat(100));
		for (final BigDecimal value : new BigDecimal[]{
			new BigDecimal(maximumPrecision, -100), new BigDecimal(maximumPrecision, 100),
			new BigDecimal(BigInteger.TEN, 101), new BigDecimal(maximumPrecision.multiply(BigInteger.TEN), 1),
			new BigDecimal(BigInteger.ZERO, Integer.MIN_VALUE),
			new BigDecimal(BigInteger.ZERO, Integer.MAX_VALUE)
		}) {
			final AttributeWeight attribute = attributeWeight("a", value);
			final AssociatedDataWeight data = associatedDataWeight("a", value);
			assertEquals(0, value.compareTo(attribute.getWeight()));
			assertEquals(attribute.getWeight(), data.getWeight());
			assertEquals("attributeWeight('a'," + attribute.getWeight().toPlainString() + ")", attribute.toString());
			assertEquals("associatedDataWeight('a'," + data.getWeight().toPlainString() + ")", data.toString());
		}
		for (final BigDecimal value : new BigDecimal[]{
			new BigDecimal("1".repeat(101)), new BigDecimal(BigInteger.ONE, -101),
			new BigDecimal(BigInteger.ONE, 101)
		}) {
			assertThrows(EvitaInvalidUsageException.class, () -> attributeWeight("a", value));
			assertThrows(EvitaInvalidUsageException.class, () -> associatedDataWeight("a", value));
		}
	}

	/** Literal and bound parser inputs follow the same bounded constructor policy. */
	@Test
	@DisplayName("Refuses excessive literal precision and compact extreme-scale parameters cleanly")
	void shouldBoundParsedWeights() {
		final DefaultQueryParser parser = DefaultQueryParser.getInstance();
		for (final String kind : new String[]{"attributeWeight", "associatedDataWeight"}) {
			assertThrows(EvitaSyntaxException.class,
				() -> parser.parseOrderConstraintUnsafe(kind + "('a'," + "1".repeat(101) + ")"));
			assertThrows(EvitaSyntaxException.class,
				() -> parser.parseOrderConstraint(kind + "(?,?)", "a",
					new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE)));
			assertThrows(EvitaSyntaxException.class,
				() -> parser.parseOrderConstraint(kind + "(?,?)", "a",
					new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE)));
		}
	}

	/** Duplicate places must remain a list until the planner refuses them, also across nested containers. */
	@Test
	@DisplayName("Retains duplicate places and exact child order through full query normalization")
	void shouldPreserveWeightsAndDuplicatesDuringNormalization() {
		final RelevanceText weights = relevanceText(associatedDataWeight("body", BigDecimal.ONE),
			attributeWeight("name", valueOf(3)),
			attributeWeight("name", valueOf(3)),
			referenceWeight("links", entityWeight(associatedDataWeight("body", valueOf(2)))));
		final Query original = query(collection("Item"), orderBy(weights));
		assertEquals(original, original.normalizeQuery());
		assertEquals(original, DefaultQueryParser.getInstance().parseQueryUnsafe(original.toString()));
		assertEquals(4, weights.getChildrenCount());
		assertNotEquals(relevanceText(attributeWeight("a", BigDecimal.ONE)),
			relevanceText(attributeWeight("a", valueOf(2))));
	}

	/** Child copies enforce the same level markers as the typed constructors. */
	@Test
	@DisplayName("Refuses wrong-level and additional children")
	void shouldRejectWrongLevels() {
		assertThrows(EvitaInvalidUsageException.class, () -> referenceWeight("links").getCopyWithNewChildren(
			new OrderConstraint[]{associatedDataWeight("body", BigDecimal.ONE)}, new Constraint<?>[0]));
		assertThrows(EvitaInvalidUsageException.class, () -> entityWeight().getCopyWithNewChildren(
			new OrderConstraint[]{referenceWeight("links")}, new Constraint<?>[0]));
		assertThrows(EvitaInvalidUsageException.class, () -> entityWeight().getCopyWithNewChildren(
			new OrderConstraint[0], new Constraint<?>[]{page(1, 10)}));
		assertThrows(EvitaInvalidUsageException.class, () -> referenceWeight("links").getCopyWithNewChildren(
			new OrderConstraint[0], new Constraint<?>[]{page(1, 10)}));
	}

	/** Invalid syntax is rejected before it can alter a policy or silently drop a name. */
	@ParameterizedTest
	@ValueSource(strings = {"attributeWeight()", "attributeWeight('a')", "attributeWeight('a','b',1)",
		"attributeWeight('',1)", "attributeWeight(' ',1)", "associatedDataWeight('',1)",
		"referenceWeight('',attributeWeight('a',1))", "referenceWeight()", "entityWeight('a')",
		"relevanceText(entityWeight())", "referenceWeight('r',associatedDataWeight('a',1))",
		"entityWeight(referenceWeight('r'))", "entityWeight(entityWeight())", "attributeWeightAll()"})
	@DisplayName("Refuses invalid weight syntax and wrong-level nesting")
	void shouldRejectInvalidSyntax(@Nonnull String input) {
		assertThrows(EvitaSyntaxException.class,
			() -> DefaultQueryParser.getInstance().parseOrderConstraintUnsafe(input));
	}

	/** Bound weights accept every numeric query data type and normalize it to the same BigDecimal weight. */
	@Test
	@DisplayName("Accepts numeric bound weights and refuses a non-numeric one")
	void shouldAcceptNumericBoundWeights() {
		final DefaultQueryParser parser = DefaultQueryParser.getInstance();
		for (final Number weight : new Number[]{(byte) 3, (short) 3, 3, 3L, valueOf(3), new BigDecimal("3.00")}) {
			final Map<String, Object> bindings = Map.of("name", "name", "weight", weight);

			assertEquals(orderBy(attributeWeight("name", valueOf(3))),
				parser.parseOrderConstraint("attributeWeight(@name,@weight)", bindings), weight.getClass().getName());
			assertEquals(orderBy(associatedDataWeight("name", valueOf(3))),
				parser.parseOrderConstraint("associatedDataWeight(?,?)", "name", weight), weight.getClass().getName());
		}
		assertThrows(EvitaSyntaxException.class,
			() -> parser.parseOrderConstraint("attributeWeight(?,?)", "name", "3"));
		assertThrows(EvitaSyntaxException.class,
			() -> parser.parseOrderConstraint("associatedDataWeight(?,?)", "name", "3"));
	}

	/** Null bound weights follow mandatory-name validation; decimal literal parsing still requires unsafe mode. */
	@Test
	@DisplayName("Refuses null bound weights and literals in safe mode")
	void shouldRefuseNullWeightsAndSafeLiterals() {
		final DefaultQueryParser parser = DefaultQueryParser.getInstance();
		for (final String name : new String[]{"attributeWeight", "associatedDataWeight"}) {
			assertThrows(EvitaSyntaxException.class,
				() -> parser.parseOrderConstraint(name + "(?,?)", "name", null));
			assertThrows(EvitaSyntaxException.class,
				() -> parser.parseOrderConstraint(name + "(?,3)", "name"));
			assertThrows(EvitaSyntaxException.class,
				() -> parser.parseOrderConstraint(name + "(?,3.0)", "name"));
		}
	}

	/** Bound null/blank names follow the selector constructor contract. */
	@Test
	@DisplayName("Refuses invalid bound names")
	void shouldRejectInvalidBoundNames() {
		for (final String invalid : new String[]{null, "", " "}) {
			final Map<String, Object> bindings = new HashMap<>();
			bindings.put("name", invalid);
			bindings.put("weight", 1);
			assertThrows(EvitaSyntaxException.class, () -> DefaultQueryParser.getInstance()
				.parseOrderConstraint("attributeWeight(@name,@weight)", bindings));
			assertThrows(EvitaSyntaxException.class, () -> DefaultQueryParser.getInstance()
				.parseOrderConstraint("associatedDataWeight(@name,@weight)", bindings));
			assertThrows(EvitaSyntaxException.class, () -> DefaultQueryParser.getInstance()
				.parseOrderConstraint("referenceWeight(?)", invalid));
		}
	}
}
