/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.externalApi.grpc.query;

import com.google.protobuf.Int32Value;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.filter.EntityScope;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.HierarchyParentsBehaviour;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.query.require.QueryTelemetryContent;
import io.evitadb.api.query.visitor.PrettyPrintingVisitor.StringWithParameters;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.Evita;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.generated.GrpcHierarchyParentsBehaviour;
import io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange;
import io.evitadb.externalApi.grpc.generated.GrpcQueryParam;
import io.evitadb.externalApi.grpc.generated.GrpcQueryParam.QueryParamCase;
import io.evitadb.externalApi.grpc.utils.QueryUtil;
import io.evitadb.externalApi.grpc.utils.QueryWithParameters;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeInRange;
import static io.evitadb.api.query.QueryConstraints.attributeInSet;
import static io.evitadb.api.query.QueryConstraints.attributeLessThan;
import static io.evitadb.api.query.QueryConstraints.attributeSetExact;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.histogramHaving;
import static io.evitadb.api.query.QueryConstraints.histogramStatistics;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.queryTelemetry;
import static io.evitadb.api.query.QueryConstraints.referenceContent;
import static io.evitadb.api.query.QueryConstraints.referenceContentWithAttributes;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryWithHistograms;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test verifies functionalities of methods in {@link QueryConverter} class.
 *
 * @author Tomáš Pozler, 2022
 */
@SuppressWarnings("FieldCanBeLocal")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@ExtendWith(EvitaParameterResolver.class)
class QueryConverterTest {
	private static final boolean enabled = true;
	private static final String name = "test";
	private final IntegerNumberRange range = IntegerNumberRange.from(1);

	@Test
	void shouldConvertListAndMapsOfQueryParam() {
		final Field[] fields = QueryConverterTest.class.getDeclaredFields();
		final String enabledName = fields[0].getName();
		final String nameName = fields[1].getName();
		final String rangeName = fields[2].getName();

		final GrpcQueryParam enabledQueryParam = GrpcQueryParam.newBuilder().setBooleanValue(enabled).build();
		final GrpcQueryParam nameQueryParam = GrpcQueryParam.newBuilder().setStringValue(name).build();
		//noinspection ConstantConditions
		final GrpcQueryParam rangeQueryParam = GrpcQueryParam.newBuilder().setIntegerNumberRangeValue(GrpcIntegerNumberRange.newBuilder().setFrom(Int32Value.newBuilder().setValue(this.range.getPreciseFrom()).build()).build()).build();

		final List<GrpcQueryParam> positionalQueryParams = List.of(
			enabledQueryParam,
			nameQueryParam,
			rangeQueryParam
		);

		final List<Object> positionalParams = QueryConverter.convertQueryParamsList(positionalQueryParams);

		assertEquals(enabled, positionalParams.get(0));
		assertEquals(name, positionalParams.get(1));
		assertEquals(this.range, positionalParams.get(2));

		final Map<String, GrpcQueryParam> namedQueryParams = Map.of(
			enabledName, enabledQueryParam,
			nameName, nameQueryParam,
			rangeName, rangeQueryParam
		);

		final Map<String, Object> namedParams = QueryConverter.convertQueryParamsMap(namedQueryParams);
		assertEquals(enabled, namedParams.get(enabledName));
		assertEquals(name, namedParams.get(nameName));
		assertEquals(this.range, namedParams.get(rangeName));

		final String stringValue = "a";
		assertEquals(stringValue, convertQueryParam(stringValue));
		final int intValue = 1;
		assertEquals(intValue, convertQueryParam(intValue));
		final long longValue = 1L;
		assertEquals(longValue, convertQueryParam(longValue));
		final boolean booleanValue = true;
		assertEquals(booleanValue, convertQueryParam(booleanValue));
		final BigDecimal bigDecimalValue = new BigDecimal("123.456");
		assertEquals(bigDecimalValue, convertQueryParam(bigDecimalValue));
		final DateTimeRange dateTimeRangeValue = DateTimeRange.until(OffsetDateTime.now());
		assertEquals(dateTimeRangeValue, convertQueryParam(dateTimeRangeValue));
		final IntegerNumberRange integerNumberRangeValue = IntegerNumberRange.from(1);
		assertEquals(integerNumberRangeValue, convertQueryParam(integerNumberRangeValue));
		final LongNumberRange longNumberRangeValue = LongNumberRange.from(1L);
		assertEquals(longNumberRangeValue, convertQueryParam(longNumberRangeValue));
		final BigDecimalNumberRange bigDecimalNumberRangeValue = BigDecimalNumberRange.to(new BigDecimal("123.456"), 3);
		assertEquals(bigDecimalNumberRangeValue, convertQueryParam(bigDecimalNumberRangeValue));
		final OffsetDateTime offsetDateTimeValue = OffsetDateTime.of(2000, 1, 1, 1, 1, 1, 1, ZoneOffset.UTC);
		assertEquals(offsetDateTimeValue, convertQueryParam(offsetDateTimeValue));
		final Locale localeValue = Locale.GERMANY;
		assertEquals(localeValue, convertQueryParam(localeValue));
		final Currency currencyValue = Currency.getInstance(Locale.GERMANY);
		assertSame(currencyValue, convertQueryParam(currencyValue));
		final FacetStatisticsDepth facetStatisticsDepthValue = FacetStatisticsDepth.IMPACT;
		assertEquals(FacetStatisticsDepth.IMPACT, convertQueryParam(facetStatisticsDepthValue));
		final QueryPriceMode queryPriceModeValue = QueryPriceMode.WITHOUT_TAX;
		assertEquals(QueryPriceMode.WITHOUT_TAX, convertQueryParam(queryPriceModeValue));
		final AttributeSpecialValue attributeSpecialValueValue = AttributeSpecialValue.NOT_NULL;
		assertEquals(AttributeSpecialValue.NOT_NULL, convertQueryParam(attributeSpecialValueValue));
		final OrderDirection orderDirectionValue = OrderDirection.DESC;
		assertEquals(OrderDirection.DESC, convertQueryParam(orderDirectionValue));
		final HierarchyParentsBehaviour hierarchyParentsBehaviourValue = HierarchyParentsBehaviour.COMPLETE;
		assertEquals(HierarchyParentsBehaviour.COMPLETE, convertQueryParam(hierarchyParentsBehaviourValue));
	}

	@Test
	void shouldSendExpressionAsTheTextItParsesFrom() {
		final Expression expression = ExpressionFactory.parse("$pageNumber % 2 == 0 && $pageNumber <= 10");

		final Object received = QueryConverter.convertQueryParam(QueryConverter.convertQueryParam(expression));

		// the server parses the received text, which must give the same expression, not a string constant
		assertEquals(expression.toExpressionString(), ExpressionFactory.parse((String) received).toExpressionString());
	}

	@Test
	void shouldSendStringConstantsOfExpressionAsWritten() {
		// constants `C:\new`, `end\` and a tab - the text of each must parse back to the same constant on the server
		final Expression expression = ExpressionFactory.parse(
			"$name == 'C:\\\\new' || $name == 'end\\\\' || $name == 'tab\\tstop'"
		);

		final Object received = QueryConverter.convertQueryParam(QueryConverter.convertQueryParam(expression));

		assertEquals(expression, ExpressionFactory.parse((String) received));
	}

	/**
	 * The `hierarchyContent` parents behaviour is the only enum parameter whose default value is also the zero value
	 * of its gRPC counterpart, so a round-trip on its own would not tell a correctly bound arm from an unset message -
	 * `MATCHING` survives both. The arm actually carrying the value is therefore asserted alongside the round-trip,
	 * for both values.
	 */
	@Test
	void shouldConvertHierarchyParentsBehaviourInBothDirections() {
		for (final HierarchyParentsBehaviour behaviour : HierarchyParentsBehaviour.values()) {
			final GrpcQueryParam queryParam = QueryConverter.convertQueryParam(behaviour);
			assertEquals(
				QueryParamCase.HIERARCHYPARENTSBEHAVIOUR, queryParam.getQueryParamCase(),
				"The behaviour must travel in its own arm, otherwise the server cannot tell it from an unset value."
			);
			assertEquals(behaviour, QueryConverter.convertQueryParam(queryParam));
		}
	}

	/**
	 * The whole backward-compatibility argument of the new `GrpcQueryParam` arm rests on `MATCHING` being the zero
	 * value of its gRPC enum: a client built before the arm existed simply omits the field, and protobuf then reads
	 * it back as the numbered-zero constant. Reordering the constants would silently flip every such client to
	 * `COMPLETE` without breaking the round trip, which is all
	 * {@link #shouldConvertHierarchyParentsBehaviourInBothDirections()} checks.
	 */
	@Test
	void shouldKeepMatchingAsTheZeroValueOnTheWire() {
		assertEquals(
			0, GrpcHierarchyParentsBehaviour.MATCHING.getNumber(),
			"An absent field must read back as `MATCHING`, which requires it to carry number zero."
		);
		assertEquals(1, GrpcHierarchyParentsBehaviour.COMPLETE.getNumber());
	}

	/**
	 * The companion half of the same guarantee: a `GrpcQueryParam` with no arm set must be refused outright rather
	 * than silently read as the zero-valued arm of whichever branch happens to be tested first.
	 */
	@Test
	void shouldRefuseAQueryParamWithNoArmSet() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> QueryConverter.convertQueryParam(GrpcQueryParam.getDefaultInstance())
		);
	}

	/**
	 * Every telemetry level has to survive the trip through `GrpcQueryParam` in both directions. A real query only
	 * ever extracts `PLAN` - `TIMINGS` is an implicit argument the client leaves out - but the conversion maps the
	 * type, so it must not depend on which constant a query happens to carry.
	 */
	@Test
	@DisplayName("should convert every query telemetry content level in both directions")
	void shouldConvertQueryTelemetryContentInBothDirections() {
		for (final QueryTelemetryContent content : QueryTelemetryContent.values()) {
			assertEquals(content, convertQueryParam(content));
		}
	}

	/**
	 * The client sends `queryTelemetry(PLAN)` as `queryTelemetry(?)` with the level as a positional parameter, so the
	 * level reaches the server only if that parameter encodes and the server parses it back into the same query.
	 */
	@Test
	@DisplayName("should round-trip a query asking for the formula plan through the gRPC query converter")
	void shouldRoundTripQueryTelemetryPlanThroughTheWire() {
		final Query original = query(
			collection("product"),
			require(queryTelemetry(QueryTelemetryContent.PLAN))
		).normalizeQuery();
		final StringWithParameters extracted = original.toStringWithParameterExtraction();
		assertTrue(
			extracted.parameters().contains(QueryTelemetryContent.PLAN),
			"The level must travel as a parameter, or this test does not exercise the gRPC parameter at all."
		);
		final List<GrpcQueryParam> encoded = extracted.parameters().stream()
			.map(QueryConverter::convertQueryParam)
			.toList();

		final QueryWithParameters parsed = QueryUtil.parseQuery(
			extracted.query(), encoded, Map.of(), null,
			(queryString, positionalArguments, namedArguments, error) -> fail(error)
		);
		assertEquals(original, parsed.parsedQuery());
	}

	@Test
	void shouldConvertArrayValues() {
		final String[] stringValue = {"a", "b"};
		assertArrayEquals(stringValue, convertQueryParam(stringValue));
		final Integer[] intValue = {1, 2};
		assertArrayEquals(intValue, convertQueryParam(intValue));
		final Long[] longValue = {1L, 2L};
		assertArrayEquals(longValue, convertQueryParam(longValue));
		final Boolean[] booleanValue = {true, false};
		assertArrayEquals(booleanValue, convertQueryParam(booleanValue));
		final BigDecimal[] bigDecimalValue = {new BigDecimal("123.456"), BigDecimal.ZERO};
		assertArrayEquals(bigDecimalValue, convertQueryParam(bigDecimalValue));
		final DateTimeRange[] dateTimeRangeValue = {DateTimeRange.until(OffsetDateTime.now()), DateTimeRange.since(OffsetDateTime.now())};
		assertArrayEquals(dateTimeRangeValue, convertQueryParam(dateTimeRangeValue));
		final IntegerNumberRange[] integerNumberRangeValue = {IntegerNumberRange.from(1), IntegerNumberRange.to(1)};
		assertArrayEquals(integerNumberRangeValue, convertQueryParam(integerNumberRangeValue));
		final LongNumberRange[] longNumberRangeValue = {LongNumberRange.from(1L), LongNumberRange.to(1L)};
		assertArrayEquals(longNumberRangeValue, convertQueryParam(longNumberRangeValue));
		final BigDecimalNumberRange[] bigDecimalNumberRangeValue = {BigDecimalNumberRange.to(new BigDecimal("123.456"), 3), BigDecimalNumberRange.from(new BigDecimal("123.456"), 3)};
		assertArrayEquals(bigDecimalNumberRangeValue, convertQueryParam(bigDecimalNumberRangeValue));
		final OffsetDateTime[] offsetDateTimeValue = {
			OffsetDateTime.of(2000, 1, 1, 1, 1, 1, 1, ZoneOffset.UTC),
			OffsetDateTime.of(2022, 1, 1, 1, 1, 1, 1, ZoneOffset.UTC)
		};
		assertArrayEquals(offsetDateTimeValue, convertQueryParam(offsetDateTimeValue));
		final Locale[] localeValue = {Locale.GERMANY, Locale.ENGLISH};
		assertArrayEquals(localeValue, convertQueryParam(localeValue));
		final Currency[] currencyValue = {Currency.getInstance("CZK"), Currency.getInstance("USD")};
		assertArrayEquals(currencyValue, convertQueryParam(currencyValue));
		final FacetStatisticsDepth[] facetStatisticsDepthValue = {FacetStatisticsDepth.IMPACT, FacetStatisticsDepth.COUNTS};
		assertArrayEquals(facetStatisticsDepthValue, convertQueryParam(facetStatisticsDepthValue));
		final QueryPriceMode[] queryPriceModeValue = {QueryPriceMode.WITHOUT_TAX, QueryPriceMode.WITH_TAX};
		assertArrayEquals(queryPriceModeValue, convertQueryParam(queryPriceModeValue));
		final AttributeSpecialValue[] attributeSpecialValueValue = {AttributeSpecialValue.NOT_NULL, AttributeSpecialValue.NULL};
		assertArrayEquals(attributeSpecialValueValue, convertQueryParam(attributeSpecialValueValue));
		final OrderDirection[] orderDirectionValue = {OrderDirection.DESC, OrderDirection.ASC};
		assertArrayEquals(orderDirectionValue, convertQueryParam(orderDirectionValue));
		final Scope[] scopeValue = {Scope.ARCHIVED, Scope.LIVE};
		assertArrayEquals(scopeValue, convertQueryParam(scopeValue));
	}

	/**
	 * Checks the client extraction and encoding path and the server's decoding and parsing path.
	 */
	@Test
	@DisplayName("Round-trip currency and locale attribute values through the gRPC query converter")
	void shouldRoundTripCurrencyAndLocaleAttributeValues() {
		final Query original = query(
			collection("product"),
			filterBy(attributeEquals("currency", Currency.getInstance("USD")), attributeEquals("lang", Locale.GERMAN))
		).normalizeQuery();
		final StringWithParameters extracted = original.toStringWithParameterExtraction();
		final List<GrpcQueryParam> encoded = extracted.parameters().stream()
			.map(QueryConverter::convertQueryParam)
			.toList();
		assertTrue(encoded.stream().anyMatch(GrpcQueryParam::hasCurrencyValue));
		assertTrue(encoded.stream().anyMatch(GrpcQueryParam::hasLocaleValue));

		final QueryWithParameters parsed = QueryUtil.parseQuery(
			extracted.query(), encoded, Map.of(), null,
			(queryString, positionalArguments, namedArguments, error) -> fail(error)
		);
		assertEquals(original, parsed.parsedQuery());
	}


	/**
	 * Creates products that distinguish membership and exact ordering from a query with no usable values.
	 *
	 * @param evita the embedded database
	 * @return the dataset carrier
	 */
	@DataSet("queryConverterMembershipProducts")
	@Nonnull
	DataCarrier setUpMembershipProducts(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("Product").withoutGeneratedPrimaryKey()
				.withAttribute("number", Integer.class, attribute -> attribute.filterable().sortable())
				.updateVia(session);
			for (int id = 1; id <= 3; id++) {
				session.createNewEntity("Product", id).setAttribute("number", id).upsertVia(session);
			}
		});
		return new DataCarrier();
	}

	/**
	 * Runs the original query and the query parsed by the gRPC server on the same data.
	 *
	 * @param session the populated catalog session
	 */
	@Test
	@UseDataSet("queryConverterMembershipProducts")
	@DisplayName("Round-trip wrapped membership and exact order arrays with identical primary keys")
	void shouldRoundTripWrappedAttributeValueArrays(@Nonnull EvitaSessionContract session) {
		final Serializable membership = new Integer[]{1, 2};
		final Serializable order = new int[]{2, 1};
		final Query original = query(collection("Product"),
			filterBy(attributeInSet("number", membership)), orderBy(attributeSetExact("number", order)));
		final StringWithParameters extracted = original.toStringWithParameterExtraction();
		final List<GrpcQueryParam> encoded = extracted.parameters().stream().map(QueryConverter::convertQueryParam)
			.toList();
		final QueryWithParameters parsed = QueryUtil.parseQuery(
			extracted.query(), encoded, Map.of(), null, (q, positional, named, error) -> fail(error)
		);
		assertEquals(original, parsed.parsedQuery());
		final List<Integer> embeddedKeys = session.queryEntityReference(original).getRecordData().stream()
			.map(EntityReferenceContract::getPrimaryKey).toList();
		final List<Integer> serverKeys = session.queryEntityReference(parsed.parsedQuery()).getRecordData().stream()
			.map(EntityReferenceContract::getPrimaryKey).toList();
		assertEquals(List.of(2, 1), embeddedKeys);
		assertEquals(embeddedKeys, serverKeys);
	}

	/**
	 * Checks that both kinds of enum and normalized floating-point numbers retain the Java query over gRPC.
	 */
	@Test
	@DisplayName("Round-trip enum and floating-point attribute values through the gRPC query converter")
	void shouldRoundTripEnumAndFloatingPointAttributeValues() {
		final Query original = query(
			collection("product"),
			filterBy(
				attributeEquals("text", DayOfWeek.MONDAY),
				attributeLessThan("amount", 2.5f),
				attributeInSet("state", HistogramBehavior.OPTIMIZED, HistogramBehavior.EQUALIZED)
			),
			orderBy(attributeSetExact("decimalValue", 2.0d, 1.5d))
		).normalizeQuery();
		final StringWithParameters extracted = original.toStringWithParameterExtraction();
		final List<GrpcQueryParam> encoded = extracted.parameters().stream()
			.map(QueryConverter::convertQueryParam)
			.toList();
		assertTrue(encoded.stream().anyMatch(GrpcQueryParam::hasHistogramBehavior));
		assertTrue(encoded.stream().anyMatch(GrpcQueryParam::hasBigDecimalValue));
		assertTrue(encoded.stream().anyMatch(it -> it.getStringValue().equals("MONDAY")));

		final QueryWithParameters parsed = QueryUtil.parseQuery(
			extracted.query(), encoded, Map.of(), null,
			(queryString, positionalArguments, namedArguments, error) -> fail(error)
		);
		assertEquals(original, parsed.parsedQuery());
	}

	/**
	 * Checks that range points and histogram bounds created from floating-point values use the decimal wire type.
	 *
	 * @param from the range point and lower histogram bound
	 * @param to the upper histogram bound
	 */
	@ParameterizedTest
	@MethodSource("floatingPointRangeAndHistogramBounds")
	@DisplayName("Round-trip floating-point range points and histogram bounds through gRPC")
	void shouldRoundTripFloatingPointRangeAndHistogramBounds(@Nonnull Number from, @Nonnull Number to) {
		final Query original = query(
			collection("product"),
			filterBy(
				attributeInRange("validity", from),
				userFilter(histogramHaving("parameterValues", "amountBucket", from, to))
			)
		).normalizeQuery();
		final StringWithParameters extracted = original.toStringWithParameterExtraction();
		final List<GrpcQueryParam> encoded = extracted.parameters().stream()
			.map(QueryConverter::convertQueryParam)
			.toList();
		assertEquals(3, encoded.stream().filter(GrpcQueryParam::hasBigDecimalValue).count());

		final QueryWithParameters parsed = QueryUtil.parseQuery(
			extracted.query(), encoded, Map.of(), null,
			(queryString, positionalArguments, namedArguments, error) -> fail(error)
		);
		assertNotNull(parsed);
		assertEquals(original, parsed.parsedQuery());
	}

	/**
	 * Supplies a pair of decimal bounds for each floating-point type to exercise the gRPC round trip.
	 *
	 * @return the finite bound pairs
	 */
	@Nonnull
	static Stream<Arguments> floatingPointRangeAndHistogramBounds() {
		return Stream.of(Arguments.of(2.1d, 2.9d), Arguments.of(7.1f, 7.9f));
	}

	/**
	 * Checks that the server parses a reference histogram and its fetch from the Java client's query and parameters.
	 */
	@Test
	@DisplayName("Round-trip histogram statistics with a nested fetch through the gRPC query converter")
	void shouldRoundTripHistogramStatisticsWhenFetchHasParameters() {
		final Query original = query(
			collection("product"),
			require(referenceSummaryWithHistograms(
				FacetStatisticsDepth.COUNTS, null, null,
				histogramStatistics(
					20, HistogramBehavior.OPTIMIZED, entityFetch(attributeContent("code")), "priceIndex", "ratingIndex"
				)
			))
		).normalizeQuery();
		final StringWithParameters extracted = original.toStringWithParameterExtraction();
		final List<GrpcQueryParam> encoded = extracted.parameters().stream()
			.map(QueryConverter::convertQueryParam)
			.toList();

		final QueryWithParameters parsed = QueryUtil.parseQuery(
			extracted.query(), encoded, Map.of(), null,
			(queryString, positionalArguments, namedArguments, error) -> fail(error)
		);
		assertEquals(original, parsed.parsedQuery());
	}

	/**
	 * Checks that the server binds every parameter of the reference content and histogram shapes the Java client
	 * prints. Each positional parameter takes the next value in the order the parser visits it, so a token visited
	 * out of order or skipped would hand its value to another placeholder or leave it unused, and the server would
	 * refuse the query or build a different one.
	 *
	 * @param shape description of the requirement shape
	 * @param requirement the requirement the Java client sends
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("referenceContentAndHistogramShapes")
	@DisplayName("Round-trip reference content and histogram shapes through the gRPC query converter")
	void shouldRoundTripReferenceContentAndHistogramShapes(
		@Nonnull String shape, @Nonnull RequireConstraint requirement
	) {
		final Query original = query(collection("product"), require(requirement)).normalizeQuery();
		final StringWithParameters extracted = original.toStringWithParameterExtraction();
		final List<GrpcQueryParam> encoded = extracted.parameters().stream()
			.map(QueryConverter::convertQueryParam)
			.toList();
		assertFalse(encoded.isEmpty());

		final QueryWithParameters parsed = QueryUtil.parseQuery(
			extracted.query(), encoded, Map.of(), null,
			(queryString, positionalArguments, namedArguments, error) -> fail(error)
		);
		assertEquals(original, parsed.parsedQuery());
	}

	/**
	 * Supplies reference content and histogram requirements whose arguments the parser must visit in query order,
	 * most of them with parameters inside a nested constraint.
	 *
	 * @return the shape description and the requirement for each case
	 */
	@Nonnull
	static Stream<Arguments> referenceContentAndHistogramShapes() {
		return Stream.of(
			Arguments.of(
				"attribute names after the reference name",
				entityFetch(referenceContentWithAttributes("brand", "code", "name"))
			),
			Arguments.of(
				"behaviour before the reference name and its fetch",
				entityFetch(
					referenceContent(
						ManagedReferencesBehaviour.EXISTING, "brand", entityFetch(attributeContent("code"))
					)
				)
			),
			Arguments.of(
				"filter and fetch after the reference name",
				entityFetch(
					referenceContent(
						"brand", filterBy(entityPrimaryKeyInSet(1, 2)), entityFetch(attributeContent("code"))
					)
				)
			),
			Arguments.of(
				"paging after the fetch",
				entityFetch(referenceContent("brand", entityFetch(attributeContent("code")), page(1, 20)))
			),
			Arguments.of(
				"several reference names before the fetch",
				entityFetch(referenceContent(new String[]{"brand", "parameter"}, entityFetch(attributeContent("code"))))
			),
			Arguments.of(
				"histogram with its fetch after the index names",
				referenceSummaryWithHistograms(
					FacetStatisticsDepth.COUNTS, null, null,
					histogramStatistics(20, entityFetch(attributeContent("code")), "priceIndex", "ratingIndex")
				)
			),
			Arguments.of(
				"histogram with a behaviour and no fetch",
				referenceSummaryWithHistograms(
					FacetStatisticsDepth.COUNTS, null, null,
					histogramStatistics(20, HistogramBehavior.OPTIMIZED, "priceIndex")
				)
			)
		);
	}

	/**
	 * The client sends a query as a string with extracted parameters and the server parses it back. The order of
	 * `scope(...)` decides which scope a unique lookup prefers, so it has to survive the whole trip.
	 */
	@Test
	void shouldKeepTheRequestedScopeOrderThroughTheWire() {
		final Query original = query(
			collection("product"),
			filterBy(scope(Scope.ARCHIVED, Scope.LIVE), attributeEquals("code", "a"))
		);
		final StringWithParameters stringWithParameters = original.normalizeQuery().toStringWithParameterExtraction();
		final List<Object> parameters = QueryConverter.convertQueryParamsList(
			stringWithParameters.parameters()
				.stream()
				.map((Serializable parameter) -> QueryConverter.convertQueryParam(parameter))
				.toList()
		);

		final Query parsed = DefaultQueryParser.getInstance().parseQuery(stringWithParameters.query(), parameters);

		final EntityScope parsedScope = QueryUtils.findFilter(parsed, EntityScope.class);
		assertNotNull(parsedScope);
		assertArrayEquals(new Scope[]{Scope.ARCHIVED, Scope.LIVE}, parsedScope.getScopesInRequestedOrder());
	}

	@Test
	@DisplayName("should keep the precision a decimal range parameter takes from the scale of its bounds")
	void shouldKeepImplicitPrecisionOfDecimalRangeParameter() {
		final BigDecimalNumberRange sent = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"));
		final BigDecimalNumberRange readBack = convertQueryParam(sent);
		// `2.4` lies above `10..20`, yet rounds into `1..2`
		final BigDecimal probe = new BigDecimal("2.4");
		assertFalse(sent.isWithin(probe), "the probe must lie outside the sent range");
		assertAll(
			() -> assertEquals(
				sent.getEffectiveRetainedDecimalPlaces(), readBack.getEffectiveRetainedDecimalPlaces(),
				"effective retained decimal places of " + sent
			),
			() -> assertEquals(sent.getFrom(), readBack.getFrom(), "encoded lower bound of " + sent),
			() -> assertEquals(sent.getTo(), readBack.getTo(), "encoded upper bound of " + sent),
			() -> assertFalse(readBack.isWithin(probe), sent + " isWithin " + probe),
			() -> assertEquals(sent, readBack, "equality of " + sent)
		);
	}

	@SuppressWarnings("unchecked")
	private static <T extends Serializable> T convertQueryParam(T originalValue) {
		return (T) QueryConverter.convertQueryParam(QueryConverter.convertQueryParam(originalValue));
	}
}
