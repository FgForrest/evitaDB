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

package io.evitadb.externalApi.grpc.query;

import com.google.protobuf.Int32Value;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange;
import io.evitadb.externalApi.grpc.generated.GrpcQueryParam;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies functionalities of methods in {@link QueryConverter} class.
 *
 * @author Tomáš Pozler, 2022
 */
@SuppressWarnings("FieldCanBeLocal")
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
		assertEquals(currencyValue, convertQueryParam(currencyValue));
		final FacetStatisticsDepth facetStatisticsDepthValue = FacetStatisticsDepth.IMPACT;
		assertEquals(FacetStatisticsDepth.IMPACT, convertQueryParam(facetStatisticsDepthValue));
		final QueryPriceMode queryPriceModeValue = QueryPriceMode.WITHOUT_TAX;
		assertEquals(QueryPriceMode.WITHOUT_TAX, convertQueryParam(queryPriceModeValue));
		final AttributeSpecialValue attributeSpecialValueValue = AttributeSpecialValue.NOT_NULL;
		assertEquals(AttributeSpecialValue.NOT_NULL, convertQueryParam(attributeSpecialValueValue));
		final OrderDirection orderDirectionValue = OrderDirection.DESC;
		assertEquals(OrderDirection.DESC, convertQueryParam(orderDirectionValue));
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
	}

	@SuppressWarnings("unchecked")
	private static <T extends Serializable> T convertQueryParam(T originalValue) {
		return (T) QueryConverter.convertQueryParam(QueryConverter.convertQueryParam(originalValue));
	}
}
