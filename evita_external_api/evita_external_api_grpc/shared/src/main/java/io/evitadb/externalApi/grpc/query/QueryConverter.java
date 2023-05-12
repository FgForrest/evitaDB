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

package io.evitadb.externalApi.grpc.query;

import io.evitadb.api.query.QueryParser;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.generated.GrpcAttributeSpecialValueArray;
import io.evitadb.externalApi.grpc.generated.GrpcEmptyHierarchicalEntityBehaviourArray;
import io.evitadb.externalApi.grpc.generated.GrpcFacetStatisticsDepthArray;
import io.evitadb.externalApi.grpc.generated.GrpcOrderDirectionArray;
import io.evitadb.externalApi.grpc.generated.GrpcPriceContentModeArray;
import io.evitadb.externalApi.grpc.generated.GrpcQueryPriceModeArray;
import io.evitadb.externalApi.grpc.generated.GrpcStatisticsBaseArray;
import io.evitadb.externalApi.grpc.generated.GrpcStatisticsTypeArray;
import io.evitadb.externalApi.grpc.generated.QueryParam;
import io.evitadb.externalApi.grpc.generated.QueryParam.Builder;
import io.evitadb.externalApi.grpc.generated.QueryParam.QueryParamCase;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.utils.CollectionUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.*;

/**
 * This class is used for converting parametrised query parameters to a form that is accepted by {@link QueryParser}.
 * The class also contains helper methods for translating parts of the query response output.
 *
 * @author Tomáš Pozler, 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class QueryConverter {

	/**
	 * This method is used to convert a list of positional parameters of type {@link QueryParam} to a list
	 * of {@link Object} accepted by {@link QueryParser}.
	 *
	 * @param queryParams list of gRPC positional parameters
	 * @return list of object corresponding Evita recognised parameters
	 */
	@Nonnull
	public static List<Object> convertQueryParamsList(@Nonnull List<QueryParam> queryParams) {
		final List<Object> queryParamObject = new ArrayList<>(queryParams.size());
		for (QueryParam queryParam : queryParams) {
			queryParamObject.add(convertQueryParam(queryParam));
		}
		return queryParamObject;
	}

	/**
	 * This method is used to convert map of named parameters of type {@link QueryParam} specified by parameter name
	 * to a form that is accepted by {@link QueryParser}.
	 *
	 * @param queryParams map of gRPC named parameters
	 * @return map of object corresponding Evita recognised parameters
	 */
	@Nonnull
	public static Map<String, Object> convertQueryParamsMap(@Nonnull Map<String, QueryParam> queryParams) {
		final Map<String, Object> queryParamObject = CollectionUtils.createHashMap(queryParams.size());
		for (Entry<String, QueryParam> queryEntry : queryParams.entrySet()) {
			queryParamObject.put(queryEntry.getKey(), convertQueryParam(queryEntry.getValue()));
		}
		return queryParamObject;
	}

	/**
	 * This method is used to convert {@link QueryParam} to Evita query data types ({@link EvitaDataTypes}).
	 *
	 * @param queryParam query parameter to be converted
	 * @return object which represents value contained in {@code queryParam}
	 */
	@Nonnull
	public static Object convertQueryParam(@Nonnull QueryParam queryParam) {
		if (queryParam.getQueryParamCase() == QueryParamCase.STRINGVALUE) {
			return queryParam.getStringValue();
		} else if (queryParam.getQueryParamCase() == QueryParamCase.INTEGERVALUE) {
			return queryParam.getIntegerValue();
		} else if (queryParam.getQueryParamCase() == QueryParamCase.LONGVALUE) {
			return queryParam.getLongValue();
		} else if (queryParam.getQueryParamCase() == QueryParamCase.BOOLEANVALUE) {
			return queryParam.getBooleanValue();
		} else if (queryParam.getQueryParamCase() == QueryParamCase.BIGDECIMALVALUE) {
			return toBigDecimal(queryParam.getBigDecimalValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.DATETIMERANGEVALUE) {
			return toDateTimeRange(queryParam.getDateTimeRangeValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.INTEGERNUMBERRANGEVALUE) {
			return toIntegerNumberRange(queryParam.getIntegerNumberRangeValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.LONGNUMBERRANGEVALUE) {
			return toLongNumberRange(queryParam.getLongNumberRangeValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.BIGDECIMALNUMBERRANGEVALUE) {
			return toBigDecimalNumberRange(queryParam.getBigDecimalNumberRangeValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.OFFSETDATETIMEVALUE) {
			return toOffsetDateTime(queryParam.getOffsetDateTimeValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.LOCALEVALUE) {
			return toLocale(queryParam.getLocaleValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.CURRENCYVALUE) {
			return toCurrency(queryParam.getCurrencyValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.FACETSTATISTICSDEPTHVALUE) {
			return EvitaEnumConverter.toFacetStatisticsDepth(queryParam.getFacetStatisticsDepthValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.QUERYPRICEMODELVALUE) {
			return EvitaEnumConverter.toQueryPriceMode(queryParam.getQueryPriceModelValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.PRICECONTENTMODEVALUE) {
			return EvitaEnumConverter.toPriceContentMode(queryParam.getPriceContentModeValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.ATTRIBUTESPECIALVALUE) {
			return EvitaEnumConverter.toAttributeSpecialValue(queryParam.getAttributeSpecialValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.ORDERDIRECTIONVALUE) {
			return EvitaEnumConverter.toOrderDirection(queryParam.getOrderDirectionValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.EMPTYHIERARCHICALENTITYBEHAVIOUR) {
			return EvitaEnumConverter.toEmptyHierarchicalEntityBehaviour(queryParam.getEmptyHierarchicalEntityBehaviour());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.STATISTICSBASE) {
			return EvitaEnumConverter.toStatisticsBase(queryParam.getStatisticsBase());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.STATISTICSTYPE) {
			return EvitaEnumConverter.toStatisticsType(queryParam.getStatisticsType());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.STRINGARRAYVALUE) {
			return toStringArray(queryParam.getStringArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.INTEGERARRAYVALUE) {
			return toIntegerArray(queryParam.getIntegerArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.LONGARRAYVALUE) {
			return toLongArray(queryParam.getLongArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.BOOLEANARRAYVALUE) {
			return toBooleanArray(queryParam.getBooleanArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.BIGDECIMALARRAYVALUE) {
			return toBigDecimalArray(queryParam.getBigDecimalArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.DATETIMERANGEARRAYVALUE) {
			return toDateTimeRangeArray(queryParam.getDateTimeRangeArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.INTEGERNUMBERRANGEARRAYVALUE) {
			return toIntegerNumberRangeArray(queryParam.getIntegerNumberRangeArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.LONGNUMBERRANGEARRAYVALUE) {
			return toLongNumberRangeArray(queryParam.getLongNumberRangeArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.BIGDECIMALNUMBERRANGEARRAYVALUE) {
			return toBigDecimalNumberRangeArray(queryParam.getBigDecimalNumberRangeArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.OFFSETDATETIMEARRAYVALUE) {
			return toOffsetDateTimeArray(queryParam.getOffsetDateTimeArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.LOCALEARRAYVALUE) {
			return toLocaleArray(queryParam.getLocaleArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.CURRENCYARRAYVALUE) {
			return toCurrencyArray(queryParam.getCurrencyArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.FACETSTATISTICSDEPTHARRAYVALUE) {
			return queryParam.getFacetStatisticsDepthArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toFacetStatisticsDepth)
				.toArray(FacetStatisticsDepth[]::new);
		} else if (queryParam.getQueryParamCase() == QueryParamCase.QUERYPRICEMODELARRAYVALUE) {
			return queryParam.getQueryPriceModelArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toQueryPriceMode)
				.toArray(QueryPriceMode[]::new);
		} else if (queryParam.getQueryParamCase() == QueryParamCase.PRICECONTENTMODEARRAYVALUE) {
			return queryParam.getPriceContentModeArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toPriceContentMode)
				.toArray(PriceContentMode[]::new);
		} else if (queryParam.getQueryParamCase() == QueryParamCase.ATTRIBUTESPECIALARRAYVALUE) {
			return queryParam.getAttributeSpecialArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toAttributeSpecialValue)
				.toArray(AttributeSpecialValue[]::new);
		} else if (queryParam.getQueryParamCase() == QueryParamCase.ORDERDIRECTIONARRAYVALUE) {
			return queryParam.getOrderDirectionArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toOrderDirection)
				.toArray(OrderDirection[]::new);
		} else if (queryParam.getQueryParamCase() == QueryParamCase.EMPTYHIERARCHICALENTITYBEHAVIOURARRAYVALUE) {
			return queryParam.getEmptyHierarchicalEntityBehaviourArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toEmptyHierarchicalEntityBehaviour)
				.toArray(EmptyHierarchicalEntityBehaviour[]::new);
		} else if (queryParam.getQueryParamCase() == QueryParamCase.STATISTICSBASEARRAYVALUE) {
			return queryParam.getStatisticsBaseArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toStatisticsBase)
				.toArray(StatisticsBase[]::new);
		} else if (queryParam.getQueryParamCase() == QueryParamCase.STATISTICSTYPEARRAYVALUE) {
			return queryParam.getStatisticsTypeArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toStatisticsType)
				.toArray(StatisticsType[]::new);
		}
		throw new EvitaInvalidUsageException("Unsupported Evita data type `" + queryParam + "` in gRPC API.");
	}

	/**
	 * This method is used to convert Evita query data types ({@link EvitaDataTypes}) to {@link QueryParam}.
	 *
	 * @param parameter query parameter to be converted
	 * @return gRPC variant which represents value contained in {@code queryParam}
	 */
	@Nonnull
	public static <T extends Serializable> QueryParam convertQueryParam(@Nonnull T parameter) {
		final Builder builder = QueryParam.newBuilder();
		if (parameter instanceof String stringValue) {
			builder.setStringValue(stringValue);
		} else if (parameter instanceof final Integer integerValue) {
			builder.setIntegerValue(integerValue);
		} else if (parameter instanceof final Long longValue) {
			builder.setLongValue(longValue);
		} else if (parameter instanceof final Boolean booleanValue) {
			builder.setBooleanValue(booleanValue);
		} else if (parameter instanceof final BigDecimal bigDecimalValue) {
			builder.setBigDecimalValue(toGrpcBigDecimal(bigDecimalValue));
		} else if (parameter instanceof final DateTimeRange dateTimeRangeValue) {
			builder.setDateTimeRangeValue(toGrpcDateTimeRange(dateTimeRangeValue));
		} else if (parameter instanceof final ByteNumberRange byteNumberRangeValue) {
			builder.setIntegerNumberRangeValue(toGrpcIntegerNumberRange(byteNumberRangeValue));
		} else if (parameter instanceof final ShortNumberRange shortNumberRangeValue) {
			builder.setIntegerNumberRangeValue(toGrpcIntegerNumberRange(shortNumberRangeValue));
		} else if (parameter instanceof final IntegerNumberRange integerNumberRangeValue) {
			builder.setIntegerNumberRangeValue(toGrpcIntegerNumberRange(integerNumberRangeValue));
		} else if (parameter instanceof final LongNumberRange longNumberRangeValue) {
			builder.setLongNumberRangeValue(toGrpcLongNumberRange(longNumberRangeValue));
		} else if (parameter instanceof final BigDecimalNumberRange bigDecimalNumberRangeValue) {
			builder.setBigDecimalNumberRangeValue(toGrpcBigDecimalNumberRange(bigDecimalNumberRangeValue));
		} else if (parameter instanceof final OffsetDateTime offsetDateTimeValue) {
			builder.setOffsetDateTimeValue(toGrpcOffsetDateTime(offsetDateTimeValue));
		} else if (parameter instanceof final LocalDateTime localDateTimeValue) {
			builder.setOffsetDateTimeValue(toGrpcLocalDateTime(localDateTimeValue));
		} else if (parameter instanceof final LocalDate localDateValue) {
			builder.setOffsetDateTimeValue(toGrpcLocalDate(localDateValue));
		} else if (parameter instanceof final LocalTime localTimeValue) {
			builder.setOffsetDateTimeValue(toGrpcLocalTime(localTimeValue));
		} else if (parameter instanceof final Locale localeValue) {
			builder.setLocaleValue(toGrpcLocale(localeValue));
		} else if (parameter instanceof final Currency currencyValue) {
			builder.setCurrencyValue(toGrpcCurrency(currencyValue));
		} else if (parameter instanceof final FacetStatisticsDepth facetStatisticsDepth) {
			builder.setFacetStatisticsDepthValue(EvitaEnumConverter.toGrpcFacetStatisticsDepth(facetStatisticsDepth));
		} else if (parameter instanceof final QueryPriceMode queryPriceModeValue) {
			builder.setQueryPriceModelValue(EvitaEnumConverter.toGrpcQueryPriceMode(queryPriceModeValue));
		} else if (parameter instanceof final PriceContentMode priceContentModeValue) {
			builder.setPriceContentModeValue(EvitaEnumConverter.toGrpcPriceContentMode(priceContentModeValue));
		} else if (parameter instanceof final AttributeSpecialValue attributeSpecialValue) {
			builder.setAttributeSpecialValue(EvitaEnumConverter.toGrpcAttributeSpecialValue(attributeSpecialValue));
		} else if (parameter instanceof final OrderDirection orderDirectionValue) {
			builder.setOrderDirectionValue(EvitaEnumConverter.toGrpcOrderDirection(orderDirectionValue));
		} else if (parameter instanceof final EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour) {
			builder.setEmptyHierarchicalEntityBehaviour(EvitaEnumConverter.toGrpcEmptyHierarchicalEntityBehaviour(emptyHierarchicalEntityBehaviour));
		} else if (parameter instanceof final StatisticsBase statisticsBase) {
			builder.setStatisticsBase(EvitaEnumConverter.toGrpcStatisticsBase(statisticsBase));
		} else if (parameter instanceof final StatisticsType statisticsType) {
			builder.setStatisticsType(EvitaEnumConverter.toGrpcStatisticsType(statisticsType));
		} else if (parameter instanceof final String[] stringArrayValue) {
			builder.setStringArrayValue(toGrpcStringArray(stringArrayValue));
		} else if (parameter instanceof final Integer[] integerArrayValue) {
			builder.setIntegerArrayValue(toGrpcIntegerArray(integerArrayValue));
		} else if (parameter instanceof final Long[] longArrayValue) {
			builder.setLongArrayValue(toGrpcLongArray(longArrayValue));
		} else if (parameter instanceof final Boolean[] booleanArrayValue) {
			builder.setBooleanArrayValue(toGrpcBooleanArray(booleanArrayValue));
		} else if (parameter instanceof final BigDecimal[] bigDecimalArrayValue) {
			builder.setBigDecimalArrayValue(toGrpcBigDecimalArray(bigDecimalArrayValue));
		} else if (parameter instanceof final DateTimeRange[] dateTimeRangeArrayValue) {
			builder.setDateTimeRangeArrayValue(toGrpcDateTimeRangeArray(dateTimeRangeArrayValue));
		} else if (parameter instanceof final ByteNumberRange[] byteNumberRangeArrayValue) {
			builder.setIntegerNumberRangeArrayValue(toGrpcByteNumberRangeArray(byteNumberRangeArrayValue));
		} else if (parameter instanceof final ShortNumberRange[] shortNumberRangeArrayValue) {
			builder.setIntegerNumberRangeArrayValue(toGrpcShortNumberRangeArray(shortNumberRangeArrayValue));
		} else if (parameter instanceof final IntegerNumberRange[] integerNumberRangeArrayValue) {
			builder.setIntegerNumberRangeArrayValue(toGrpcIntegerNumberRangeArray(integerNumberRangeArrayValue));
		} else if (parameter instanceof final LongNumberRange[] longNumberRangeArrayValue) {
			builder.setLongNumberRangeArrayValue(toGrpcLongNumberRangeArray(longNumberRangeArrayValue));
		} else if (parameter instanceof final BigDecimalNumberRange[] bigDecimalNumberRangeArrayValue) {
			builder.setBigDecimalNumberRangeArrayValue(toGrpcBigDecimalNumberRangeArray(bigDecimalNumberRangeArrayValue));
		} else if (parameter instanceof final OffsetDateTime[] offsetDateTimeArrayValue) {
			builder.setOffsetDateTimeArrayValue(toGrpcOffsetDateTimeArray(offsetDateTimeArrayValue));
		} else if (parameter instanceof final LocalDateTime[] localDateTimeArrayValue) {
			builder.setOffsetDateTimeArrayValue(toGrpcLocalDateTimeArray(localDateTimeArrayValue));
		} else if (parameter instanceof final LocalDate[] localDateArrayValue) {
			builder.setOffsetDateTimeArrayValue(toGrpcLocalDateArray(localDateArrayValue));
		} else if (parameter instanceof final LocalTime[] localTimeArrayValue) {
			builder.setOffsetDateTimeArrayValue(toGrpcLocalTimeArray(localTimeArrayValue));
		} else if (parameter instanceof final Locale[] localeArrayValue) {
			builder.setLocaleArrayValue(toGrpcLocaleArray(localeArrayValue));
		} else if (parameter instanceof final Currency[] currencyArrayValue) {
			builder.setCurrencyArrayValue(toGrpcCurrencyArray(currencyArrayValue));
		} else if (parameter instanceof final FacetStatisticsDepth[] facetStatisticsArrayDepth) {
			builder.setFacetStatisticsDepthArrayValue(GrpcFacetStatisticsDepthArray.newBuilder().addAllValue(
				Arrays.stream(facetStatisticsArrayDepth).map(EvitaEnumConverter::toGrpcFacetStatisticsDepth).toList()
			));
		} else if (parameter instanceof final QueryPriceMode[] queryPriceModeArrayValue) {
			builder.setQueryPriceModelArrayValue(GrpcQueryPriceModeArray.newBuilder().addAllValue(
				Arrays.stream(queryPriceModeArrayValue).map(EvitaEnumConverter::toGrpcQueryPriceMode).toList()
			));
		} else if (parameter instanceof final PriceContentMode[] priceContentModeArrayValue) {
			builder.setPriceContentModeArrayValue(GrpcPriceContentModeArray.newBuilder().addAllValue(
				Arrays.stream(priceContentModeArrayValue).map(EvitaEnumConverter::toGrpcPriceContentMode).toList()
			));
		} else if (parameter instanceof final AttributeSpecialValue[] attributeSpecialArrayValue) {
			builder.setAttributeSpecialArrayValue(GrpcAttributeSpecialValueArray.newBuilder().addAllValue(
				Arrays.stream(attributeSpecialArrayValue).map(EvitaEnumConverter::toGrpcAttributeSpecialValue).toList()
			));
		} else if (parameter instanceof final OrderDirection[] orderDirectionArrayValue) {
			builder.setOrderDirectionArrayValue(GrpcOrderDirectionArray.newBuilder().addAllValue(
				Arrays.stream(orderDirectionArrayValue).map(EvitaEnumConverter::toGrpcOrderDirection).toList()
			));
		} else if (parameter instanceof final EmptyHierarchicalEntityBehaviour[] emptyHierarchicalEntityBehaviourArrayValue) {
			builder.setEmptyHierarchicalEntityBehaviourArrayValue(GrpcEmptyHierarchicalEntityBehaviourArray.newBuilder().addAllValue(
				Arrays.stream(emptyHierarchicalEntityBehaviourArrayValue).map(EvitaEnumConverter::toGrpcEmptyHierarchicalEntityBehaviour).toList()
			));
		} else if (parameter instanceof final StatisticsBase[] statisticsBaseArrayValue) {
			builder.setStatisticsBaseArrayValue(GrpcStatisticsBaseArray.newBuilder().addAllValue(
				Arrays.stream(statisticsBaseArrayValue).map(EvitaEnumConverter::toGrpcStatisticsBase).toList()
			));
		} else if (parameter instanceof final StatisticsType[] statisticsTypeArrayValue) {
			builder.setStatisticsTypeArrayValue(GrpcStatisticsTypeArray.newBuilder().addAllValue(
				Arrays.stream(statisticsTypeArrayValue).map(EvitaEnumConverter::toGrpcStatisticsType).toList()
			));
		} else {
			throw new EvitaInvalidUsageException("Unsupported Evita data type `" + parameter + "` in gRPC API.");
		}
		return builder.build();
	}

}
