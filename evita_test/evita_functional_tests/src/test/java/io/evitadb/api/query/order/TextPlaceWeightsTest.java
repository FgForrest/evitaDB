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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.api.query.visitor.ConstraintCloneVisitor;
import io.evitadb.api.query.visitor.PrettyPrintingVisitor.StringWithParameters;
import io.evitadb.api.query.visitor.QueryPurifierVisitor;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
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
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/** Contracts for named weights, level boundaries, shared numbers and lossless ordinary EvitaQL forms. */
@Tag(CONTRACT)
@Tag(ORDER)
@Tag(QUERY)
@DisplayName("Fulltext place weights")
class TextPlaceWeightsTest {
	/** Supplies every leaf and container shape, including empty containers retained for planning validation. */
	@Nonnull
	static Stream<OrderConstraint> forms() {
		return Stream.of(attributeWeight("name", 3), associatedDataWeight("body", 0.5f),
			referenceWeight("links"), entityWeight(), entityWeight(attributeWeight("name", 2)),
			entityWeight(associatedDataWeight("body", 4)), referenceWeight("links", attributeWeight("name", 2)),
			referenceWeight("links", entityWeight(attributeWeight("name", 2), associatedDataWeight("body", 4))),
			relevanceText(), relevanceText(attributeWeight("name", 3)),
			relevanceText(attributeWeight("name", 3), associatedDataWeight("body", 0.5f),
				referenceWeight("links", attributeWeight("name", 2), entityWeight(attributeWeight("name", 5)))));
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

	/** Numbers accepted as ordinary literals must retain their float value after shared normalization. */
	@ParameterizedTest
	@ValueSource(strings = {"3", "3.0", "0.5", "0", "0.123456789123456789"})
	@DisplayName("Round trips integer, decimal, zero and many-decimal weight literals")
	void shouldRoundTripOrdinaryNumberForms(@Nonnull String number) {
		final DefaultQueryParser parser = DefaultQueryParser.getInstance();
		final OrderConstraint parsed = parser.parseOrderConstraintUnsafe("attributeWeight('name'," + number + ")")
			.getChildren()[0];
		assertEquals(parsed, parser.parseOrderConstraintUnsafe(parsed.toString()).getChildren()[0]);
	}

	/** Shared formatting uses exponents at both extremes, with nonmonotonic large-number plain exceptions. */
	@Test
	@DisplayName("Pins the known limitation of shared number formatting at exponent boundaries")
	void shouldPinKnownLimitationOfSharedNumberFormatting() {
		final DefaultQueryParser parser = DefaultQueryParser.getInstance();
		for (final float plain : new float[]{0f, 0.000001f, 9_999_999f, 12_345_678f, 134_217_704f}) {
			final AttributeWeight weight = attributeWeight("name", plain);
			assertEquals(orderBy(weight), parser.parseOrderConstraintUnsafe(weight.toString()));
		}
		for (final float exponent : new float[]{Float.MIN_VALUE, Math.nextDown(0.000001f), 10_000_000f,
			Math.nextUp(134_217_704f), Float.MAX_VALUE}) {
			final String formatted = attributeWeight("name", exponent).toString();
			assertTrue(formatted.substring(formatted.lastIndexOf(',') + 1).contains("e"), formatted);
			assertThrows(EvitaSyntaxException.class, () -> parser.parseOrderConstraintUnsafe(formatted));
		}
	}

	/** Shared argument normalization rejects non-finite values before a query can be planned. */
	@Test
	@DisplayName("Rejects non-finite numbers during construction")
	void shouldRejectNonFiniteWeightsDuringConstruction() {
		for (final float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
			assertThrows(UnsupportedDataTypeException.class, () -> new AttributeWeight("name", value));
			assertThrows(UnsupportedDataTypeException.class, () -> associatedDataWeight("body", value));
		}
		assertEquals(Float.floatToIntBits(0f), Float.floatToIntBits(attributeWeight("name", -0.0f).getWeight()));
		assertEquals(attributeWeight("name", 0f), attributeWeight("name", -0.0f));
		assertEquals(associatedDataWeight("body", 0f), associatedDataWeight("body", -0.0f));
		assertEquals(-1f, attributeWeight("name", -1f).getWeight());
	}

	/**
	 * The query language has no binary floating point type: a weight is bound as an integer or a `BigDecimal`,
	 * and a bound `Float` or `Double` is refused whatever its value.
	 */
	@Test
	@DisplayName("Refuses a bound Float or Double weight regardless of its value")
	void shouldRefuseBoundFloatWeight() {
		for (final float value : new float[]{2.5f, Float.NaN, Float.POSITIVE_INFINITY}) {
			assertThrows(EvitaSyntaxException.class, () -> DefaultQueryParser.getInstance()
				.parseOrderConstraint("attributeWeight(?,?)", "name", value));
			assertThrows(EvitaSyntaxException.class, () -> DefaultQueryParser.getInstance()
				.parseOrderConstraint("associatedDataWeight(?,?)", "body", value));
		}
		assertThrows(EvitaSyntaxException.class, () -> DefaultQueryParser.getInstance()
			.parseOrderConstraint("attributeWeight(?,?)", "name", 2.5d));
	}

	/**
	 * A client sends a query as text with extracted parameters. Weights travel as `BigDecimal` parameters there,
	 * so even the values whose printed literal uses an exponent arrive unchanged.
	 */
	@Test
	@DisplayName("Keeps every weight through parameter extraction and parsing")
	void shouldKeepWeightsThroughParameterExtraction() {
		for (final float value : new float[]{0f, 0.5f, 3f, 1e-7f, Float.MIN_VALUE, 1e7f, 2e9f, Float.MAX_VALUE}) {
			final Query original = query(
				collection("product"),
				orderBy(relevanceText(
					attributeWeight("name", value),
					referenceWeight("brand", entityWeight(associatedDataWeight("description", value)))
				))
			);
			final StringWithParameters extracted = original.toStringWithParameterExtraction();
			final Query parsed = DefaultQueryParser.getInstance()
				.parseQuery(extracted.query(), extracted.parameters().toArray());
			assertEquals(original, parsed, "Weight " + value + " must survive the client round trip.");
		}
	}

	/** Mandatory names are never omitted by factories, constructors or argument copies. */
	@Test
	@DisplayName("Validates names and serialized argument shapes")
	void shouldValidateNamesAndArguments() {
		for (final String invalid : new String[]{null, "", "  "}) {
			assertThrows(EvitaInvalidUsageException.class, () -> attributeWeight(invalid, 1));
			assertThrows(EvitaInvalidUsageException.class, () -> new AssociatedDataWeight(invalid, 1));
			assertThrows(EvitaInvalidUsageException.class, () -> referenceWeight(invalid, attributeWeight("a", 1)));
			assertThrows(EvitaInvalidUsageException.class, () -> attributeWeight("a", 1)
				.cloneWithArguments(new Serializable[]{invalid, 1}));
		}
		assertEquals("name", attributeWeight("name", 3).getAttributeName());
		assertEquals("body", associatedDataWeight("body", 3).getAssociatedDataName());
		assertEquals("links", referenceWeight("links").getReferenceName());
		assertEquals(attributeWeight("b", 2), attributeWeight("a", 1).cloneWithArguments(new Serializable[]{"b", 2}));
		assertEquals(associatedDataWeight("b", 2), associatedDataWeight("a", 1)
			.cloneWithArguments(new Serializable[]{"b", 2}));
		assertThrows(EvitaInvalidUsageException.class,
			() -> attributeWeight("a", 1).cloneWithArguments(new Serializable[]{"a"}));
		assertThrows(EvitaInvalidUsageException.class,
			() -> associatedDataWeight("a", 1).cloneWithArguments(new Serializable[]{"a", "1"}));
		assertThrows(EvitaInvalidUsageException.class,
			() -> referenceWeight("links").cloneWithArguments(new Serializable[0]));
		assertThrows(EvitaInvalidUsageException.class,
			() -> entityWeight().cloneWithArguments(new Serializable[]{"a"}));
	}

	/** Duplicate places must remain a list until the planner refuses them, also across nested containers. */
	@Test
	@DisplayName("Retains duplicate places and exact child order through full query normalization")
	void shouldPreserveWeightsAndDuplicatesDuringNormalization() {
		final RelevanceText weights = relevanceText(associatedDataWeight("body", 1), attributeWeight("name", 3),
			attributeWeight("name", 3), referenceWeight("links", entityWeight(associatedDataWeight("body", 2))));
		final Query original = query(collection("Item"), orderBy(weights));
		assertEquals(original, original.normalizeQuery());
		assertEquals(original, DefaultQueryParser.getInstance().parseQueryUnsafe(original.toString()));
		assertEquals(4, weights.getChildrenCount());
		assertNotEquals(relevanceText(attributeWeight("a", 1)), relevanceText(attributeWeight("a", 2)));
	}

	/** Child copies enforce the same level markers as the typed constructors. */
	@Test
	@DisplayName("Refuses wrong-level and additional children")
	void shouldRejectWrongLevels() {
		assertThrows(EvitaInvalidUsageException.class, () -> referenceWeight("links").getCopyWithNewChildren(
			new OrderConstraint[]{associatedDataWeight("body", 1)}, new Constraint<?>[0]));
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

	/** Bound weights accept every numeric query data type and convert it to the same float weight. */
	@Test
	@DisplayName("Accepts numeric bound weights and refuses a non-numeric one")
	void shouldAcceptNumericBoundWeights() {
		final DefaultQueryParser parser = DefaultQueryParser.getInstance();
		for (final Number weight : new Number[]{(byte) 3, (short) 3, 3, 3L, new BigDecimal("3")}) {
			final Map<String, Object> bindings = Map.of("name", "name", "weight", weight);

			assertEquals(orderBy(attributeWeight("name", 3)),
				parser.parseOrderConstraint("attributeWeight(@name,@weight)", bindings), weight.getClass().getName());
			assertEquals(orderBy(associatedDataWeight("name", 3)),
				parser.parseOrderConstraint("associatedDataWeight(?,?)", "name", weight), weight.getClass().getName());
		}
		assertThrows(EvitaSyntaxException.class,
			() -> parser.parseOrderConstraint("attributeWeight(?,?)", "name", "3"));
		assertThrows(EvitaSyntaxException.class,
			() -> parser.parseOrderConstraint("associatedDataWeight(?,?)", "name", "3"));
	}

	/** Bound null/blank names follow the selector constructor contract. */
	@Test
	@DisplayName("Refuses invalid bound names")
	void shouldRejectInvalidBoundNames() {
		for (final String invalid : new String[]{null, "", " "}) {
			final Map<String, Object> bindings = new java.util.HashMap<>();
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
