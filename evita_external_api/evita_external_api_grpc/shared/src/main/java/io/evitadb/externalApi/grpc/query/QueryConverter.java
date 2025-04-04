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

import io.evitadb.api.query.QueryParser;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.TraversalMode;
import io.evitadb.api.query.require.*;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcQueryParam.Builder;
import io.evitadb.externalApi.grpc.generated.GrpcQueryParam.QueryParamCase;
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
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.*;

/**
 * This class is used for converting parametrised query parameters to a form that is accepted by {@link QueryParser}.
 * The class also contains helper methods for translating parts of the query response output.
 *
 * @author Tomáš Pozler, 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class QueryConverter {

	/**
	 * This method is used to convert a list of positional parameters of type {@link GrpcQueryParam} to a list
	 * of {@link Object} accepted by {@link QueryParser}.
	 *
	 * @param GrpcQueryParams list of gRPC positional parameters
	 * @return list of object corresponding Evita recognised parameters
	 */
	@Nonnull
	public static List<Object> convertQueryParamsList(@Nonnull List<GrpcQueryParam> GrpcQueryParams) {
		final List<Object> parameterList = new ArrayList<>(GrpcQueryParams.size());
		for (GrpcQueryParam GrpcQueryParam : GrpcQueryParams) {
			parameterList.add(convertQueryParam(GrpcQueryParam));
		}
		return parameterList;
	}

	/**
	 * This method is used to convert map of named parameters of type {@link GrpcQueryParam} specified by parameter name
	 * to a form that is accepted by {@link QueryParser}.
	 *
	 * @param GrpcQueryParams map of gRPC named parameters
	 * @return map of object corresponding Evita recognised parameters
	 */
	@Nonnull
	public static Map<String, Object> convertQueryParamsMap(@Nonnull Map<String, GrpcQueryParam> GrpcQueryParams) {
		final Map<String, Object> parameterIndex = CollectionUtils.createHashMap(GrpcQueryParams.size());
		for (Entry<String, GrpcQueryParam> queryEntry : GrpcQueryParams.entrySet()) {
			parameterIndex.put(queryEntry.getKey(), convertQueryParam(queryEntry.getValue()));
		}
		return parameterIndex;
	}

	/**
	 * This method is used to convert {@link GrpcQueryParam} to Evita query data types ({@link EvitaDataTypes}).
	 *
	 * @param GrpcQueryParam query parameter to be converted
	 * @return object which represents value contained in {@code GrpcQueryParam}
	 */
	@Nonnull
	public static Object convertQueryParam(@Nonnull GrpcQueryParam GrpcQueryParam) {
		if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.STRINGVALUE) {
			return GrpcQueryParam.getStringValue();
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.INTEGERVALUE) {
			return GrpcQueryParam.getIntegerValue();
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.LONGVALUE) {
			return GrpcQueryParam.getLongValue();
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.BOOLEANVALUE) {
			return GrpcQueryParam.getBooleanValue();
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.BIGDECIMALVALUE) {
			return toBigDecimal(GrpcQueryParam.getBigDecimalValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.DATETIMERANGEVALUE) {
			return toDateTimeRange(GrpcQueryParam.getDateTimeRangeValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.INTEGERNUMBERRANGEVALUE) {
			return toIntegerNumberRange(GrpcQueryParam.getIntegerNumberRangeValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.LONGNUMBERRANGEVALUE) {
			return toLongNumberRange(GrpcQueryParam.getLongNumberRangeValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.BIGDECIMALNUMBERRANGEVALUE) {
			return toBigDecimalNumberRange(GrpcQueryParam.getBigDecimalNumberRangeValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.OFFSETDATETIMEVALUE) {
			return toOffsetDateTime(GrpcQueryParam.getOffsetDateTimeValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.LOCALEVALUE) {
			return toLocale(GrpcQueryParam.getLocaleValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.CURRENCYVALUE) {
			return toCurrency(GrpcQueryParam.getCurrencyValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.FACETSTATISTICSDEPTHVALUE) {
			return toFacetStatisticsDepth(GrpcQueryParam.getFacetStatisticsDepthValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.QUERYPRICEMODELVALUE) {
			return toQueryPriceMode(GrpcQueryParam.getQueryPriceModelValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.PRICECONTENTMODEVALUE) {
			return toPriceContentMode(GrpcQueryParam.getPriceContentModeValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.ATTRIBUTESPECIALVALUE) {
			return toAttributeSpecialValue(GrpcQueryParam.getAttributeSpecialValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.ORDERDIRECTIONVALUE) {
			return toOrderDirection(GrpcQueryParam.getOrderDirectionValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.EMPTYHIERARCHICALENTITYBEHAVIOUR) {
			return toEmptyHierarchicalEntityBehaviour(GrpcQueryParam.getEmptyHierarchicalEntityBehaviour());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.STATISTICSBASE) {
			return toStatisticsBase(GrpcQueryParam.getStatisticsBase());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.STATISTICSTYPE) {
			return toStatisticsType(GrpcQueryParam.getStatisticsType());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.HISTOGRAMBEHAVIOR) {
			return toHistogramBehavior(GrpcQueryParam.getHistogramBehavior());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.SCOPE) {
			return toScope(GrpcQueryParam.getScope());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.MANAGEDREFERENCESBEHAVIOUR) {
			return toManagedReferencesBehaviour(GrpcQueryParam.getManagedReferencesBehaviour());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.FACETRELATIONTYPE) {
			return toFacetRelationType(GrpcQueryParam.getFacetRelationType());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.FACETGROUPRELATIONLEVEL) {
			return toFacetGroupRelationLevel(GrpcQueryParam.getFacetGroupRelationLevel());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.TRAVERSALMODE) {
			return toTraversalMode(GrpcQueryParam.getTraversalMode());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.EXPRESSIONVALUE) {
			return GrpcQueryParam.getExpressionValue();
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.STRINGARRAYVALUE) {
			return toStringArray(GrpcQueryParam.getStringArrayValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.INTEGERARRAYVALUE) {
			return toIntegerArray(GrpcQueryParam.getIntegerArrayValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.LONGARRAYVALUE) {
			return toLongArray(GrpcQueryParam.getLongArrayValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.BOOLEANARRAYVALUE) {
			return toBooleanArray(GrpcQueryParam.getBooleanArrayValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.BIGDECIMALARRAYVALUE) {
			return toBigDecimalArray(GrpcQueryParam.getBigDecimalArrayValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.DATETIMERANGEARRAYVALUE) {
			return toDateTimeRangeArray(GrpcQueryParam.getDateTimeRangeArrayValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.INTEGERNUMBERRANGEARRAYVALUE) {
			return toIntegerNumberRangeArray(GrpcQueryParam.getIntegerNumberRangeArrayValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.LONGNUMBERRANGEARRAYVALUE) {
			return toLongNumberRangeArray(GrpcQueryParam.getLongNumberRangeArrayValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.BIGDECIMALNUMBERRANGEARRAYVALUE) {
			return toBigDecimalNumberRangeArray(GrpcQueryParam.getBigDecimalNumberRangeArrayValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.OFFSETDATETIMEARRAYVALUE) {
			return toOffsetDateTimeArray(GrpcQueryParam.getOffsetDateTimeArrayValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.LOCALEARRAYVALUE) {
			return toLocaleArray(GrpcQueryParam.getLocaleArrayValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.CURRENCYARRAYVALUE) {
			return toCurrencyArray(GrpcQueryParam.getCurrencyArrayValue());
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.FACETSTATISTICSDEPTHARRAYVALUE) {
			return GrpcQueryParam.getFacetStatisticsDepthArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toFacetStatisticsDepth)
				.toArray(FacetStatisticsDepth[]::new);
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.QUERYPRICEMODELARRAYVALUE) {
			return GrpcQueryParam.getQueryPriceModelArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toQueryPriceMode)
				.toArray(QueryPriceMode[]::new);
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.PRICECONTENTMODEARRAYVALUE) {
			return GrpcQueryParam.getPriceContentModeArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toPriceContentMode)
				.toArray(PriceContentMode[]::new);
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.ATTRIBUTESPECIALARRAYVALUE) {
			return GrpcQueryParam.getAttributeSpecialArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toAttributeSpecialValue)
				.toArray(AttributeSpecialValue[]::new);
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.ORDERDIRECTIONARRAYVALUE) {
			return GrpcQueryParam.getOrderDirectionArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toOrderDirection)
				.toArray(OrderDirection[]::new);
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.EMPTYHIERARCHICALENTITYBEHAVIOURARRAYVALUE) {
			return GrpcQueryParam.getEmptyHierarchicalEntityBehaviourArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toEmptyHierarchicalEntityBehaviour)
				.toArray(EmptyHierarchicalEntityBehaviour[]::new);
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.STATISTICSBASEARRAYVALUE) {
			return GrpcQueryParam.getStatisticsBaseArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toStatisticsBase)
				.toArray(StatisticsBase[]::new);
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.STATISTICSTYPEARRAYVALUE) {
			return GrpcQueryParam.getStatisticsTypeArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toStatisticsType)
				.toArray(StatisticsType[]::new);
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.HISTOGRAMBEHAVIORTYPEARRAYVALUE) {
			return GrpcQueryParam.getHistogramBehaviorTypeArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toHistogramBehavior)
				.toArray(HistogramBehavior[]::new);
		} else if (GrpcQueryParam.getQueryParamCase() == QueryParamCase.SCOPEARRAYVALUE) {
			return GrpcQueryParam.getScopeArrayValue()
				.getValueList()
				.stream()
				.map(EvitaEnumConverter::toScope)
				.toArray(Scope[]::new);
		}
		throw new EvitaInvalidUsageException("Unsupported Evita data type `" + GrpcQueryParam + "` in gRPC API.");
	}

	/**
	 * This method is used to convert Evita query data types ({@link EvitaDataTypes}) to {@link GrpcQueryParam}.
	 *
	 * @param parameter query parameter to be converted
	 * @return gRPC variant which represents value contained in {@code GrpcQueryParam}
	 */
	@Nonnull
	public static <T extends Serializable> GrpcQueryParam convertQueryParam(@Nonnull T parameter) {
		final Builder builder = GrpcQueryParam.newBuilder();
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
			builder.setFacetStatisticsDepthValue(toGrpcFacetStatisticsDepth(facetStatisticsDepth));
		} else if (parameter instanceof final QueryPriceMode queryPriceModeValue) {
			builder.setQueryPriceModelValue(toGrpcQueryPriceMode(queryPriceModeValue));
		} else if (parameter instanceof final PriceContentMode priceContentModeValue) {
			builder.setPriceContentModeValue(toGrpcPriceContentMode(priceContentModeValue));
		} else if (parameter instanceof final AttributeSpecialValue attributeSpecialValue) {
			builder.setAttributeSpecialValue(toGrpcAttributeSpecialValue(attributeSpecialValue));
		} else if (parameter instanceof final OrderDirection orderDirectionValue) {
			builder.setOrderDirectionValue(toGrpcOrderDirection(orderDirectionValue));
		} else if (parameter instanceof final EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour) {
			builder.setEmptyHierarchicalEntityBehaviour(toGrpcEmptyHierarchicalEntityBehaviour(emptyHierarchicalEntityBehaviour));
		} else if (parameter instanceof final StatisticsBase statisticsBase) {
			builder.setStatisticsBase(toGrpcStatisticsBase(statisticsBase));
		} else if (parameter instanceof final StatisticsType statisticsType) {
			builder.setStatisticsType(toGrpcStatisticsType(statisticsType));
		} else if (parameter instanceof final HistogramBehavior histogramBehavior) {
			builder.setHistogramBehavior(toGrpcHistogramBehavior(histogramBehavior));
		} else if (parameter instanceof final Scope scope) {
			builder.setScope(toGrpcScope(scope));
		} else if (parameter instanceof final ManagedReferencesBehaviour managedReferencesBehaviour) {
			builder.setManagedReferencesBehaviour(toGrpcManagedReferencesBehaviour(managedReferencesBehaviour));
		} else if (parameter instanceof final FacetRelationType facetRelationType) {
			builder.setFacetRelationType(EvitaEnumConverter.toGrpcFacetRelationType(facetRelationType));
		} else if (parameter instanceof final FacetGroupRelationLevel facetGroupRelationLevel) {
			builder.setFacetGroupRelationLevel(EvitaEnumConverter.toGrpcFacetGroupRelationLevel(facetGroupRelationLevel));
		} else if (parameter instanceof final TraversalMode traversalMode) {
			builder.setTraversalMode(EvitaEnumConverter.toGrpcTraversalMode(traversalMode));
		} else if (parameter instanceof Expression expression) {
			builder.setExpressionValue(expression.toString());
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
		} else if (parameter instanceof final HistogramBehavior[] histogramBehaviorArrayValue) {
			builder.setHistogramBehaviorTypeArrayValue(GrpcHistogramBehaviorTypeArray.newBuilder().addAllValue(
				Arrays.stream(histogramBehaviorArrayValue).map(EvitaEnumConverter::toGrpcHistogramBehavior).toList()
			));
		} else if (parameter instanceof final Scope[] scopeArrayValue) {
			builder.setScopeArrayValue(GrpcEntityScopeArray.newBuilder().addAllValue(
				Arrays.stream(scopeArrayValue).map(EvitaEnumConverter::toGrpcScope).toList()
			));
		} else {
			throw new EvitaInvalidUsageException("Unsupported Evita data type `" + parameter + "` in gRPC API.");
		}
		return builder.build();
	}

}
