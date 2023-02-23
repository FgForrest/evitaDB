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
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.dataType.*;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;
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
import java.util.function.Function;

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
			return EvitaDataTypesConverter.toBigDecimal(queryParam.getBigDecimalValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.DATETIMERANGEVALUE) {
			return EvitaDataTypesConverter.toDateTimeRange(queryParam.getDateTimeRangeValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.INTEGERNUMBERRANGEVALUE) {
			return EvitaDataTypesConverter.toIntegerNumberRange(queryParam.getIntegerNumberRangeValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.LONGNUMBERRANGEVALUE) {
			return EvitaDataTypesConverter.toLongNumberRange(queryParam.getLongNumberRangeValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.BIGDECIMALNUMBERRANGEVALUE) {
			return EvitaDataTypesConverter.toBigDecimalNumberRange(queryParam.getBigDecimalNumberRangeValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.OFFSETDATETIMEVALUE) {
			return EvitaDataTypesConverter.toOffsetDateTime(queryParam.getOffsetDateTimeValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.LOCALEVALUE) {
			return EvitaDataTypesConverter.toLocale(queryParam.getLocaleValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.CURRENCYVALUE) {
			return EvitaDataTypesConverter.toCurrency(queryParam.getCurrencyValue());
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
		} else if (queryParam.getQueryParamCase() == QueryParamCase.STRINGARRAYVALUE) {
			return EvitaDataTypesConverter.toStringArray(queryParam.getStringArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.INTEGERARRAYVALUE) {
			return EvitaDataTypesConverter.toIntegerArray(queryParam.getIntegerArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.LONGARRAYVALUE) {
			return EvitaDataTypesConverter.toLongArray(queryParam.getLongArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.BOOLEANARRAYVALUE) {
			return EvitaDataTypesConverter.toBooleanArray(queryParam.getBooleanArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.BIGDECIMALARRAYVALUE) {
			return EvitaDataTypesConverter.toBigDecimalArray(queryParam.getBigDecimalArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.DATETIMERANGEARRAYVALUE) {
			return EvitaDataTypesConverter.toDateTimeRangeArray(queryParam.getDateTimeRangeArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.INTEGERNUMBERRANGEARRAYVALUE) {
			return EvitaDataTypesConverter.toIntegerNumberRangeArray(queryParam.getIntegerNumberRangeArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.LONGNUMBERRANGEARRAYVALUE) {
			return EvitaDataTypesConverter.toLongNumberRangeArray(queryParam.getLongNumberRangeArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.BIGDECIMALNUMBERRANGEARRAYVALUE) {
			return EvitaDataTypesConverter.toBigDecimalNumberRangeArray(queryParam.getBigDecimalNumberRangeArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.OFFSETDATETIMEARRAYVALUE) {
			return EvitaDataTypesConverter.toOffsetDateTimeArray(queryParam.getOffsetDateTimeArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.LOCALEARRAYVALUE) {
			return EvitaDataTypesConverter.toLocaleArray(queryParam.getLocaleArrayValue());
		} else if (queryParam.getQueryParamCase() == QueryParamCase.CURRENCYARRAYVALUE) {
			return EvitaDataTypesConverter.toCurrencyArray(queryParam.getCurrencyArrayValue());
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
		} else if (parameter instanceof Integer integerValue) {
			builder.setIntegerValue(integerValue);
		} else if (parameter instanceof Long longValue) {
			builder.setLongValue(longValue);
		} else if (parameter instanceof Boolean booleanValue) {
			builder.setBooleanValue(booleanValue);
		} else if (parameter instanceof BigDecimal bigDecimalValue) {
			builder.setBigDecimalValue(EvitaDataTypesConverter.toGrpcBigDecimal(bigDecimalValue));
		} else if (parameter instanceof DateTimeRange dateTimeRangeValue) {
			builder.setDateTimeRangeValue(EvitaDataTypesConverter.toGrpcDateTimeRange(dateTimeRangeValue));
		} else if (parameter instanceof ByteNumberRange byteNumberRangeValue) {
			builder.setIntegerNumberRangeValue(EvitaDataTypesConverter.toGrpcIntegerNumberRange(byteNumberRangeValue));
		} else if (parameter instanceof ShortNumberRange shortNumberRangeValue) {
			builder.setIntegerNumberRangeValue(EvitaDataTypesConverter.toGrpcIntegerNumberRange(shortNumberRangeValue));
		} else if (parameter instanceof IntegerNumberRange integerNumberRangeValue) {
			builder.setIntegerNumberRangeValue(EvitaDataTypesConverter.toGrpcIntegerNumberRange(integerNumberRangeValue));
		} else if (parameter instanceof LongNumberRange longNumberRangeValue) {
			builder.setLongNumberRangeValue(EvitaDataTypesConverter.toGrpcLongNumberRange(longNumberRangeValue));
		} else if (parameter instanceof BigDecimalNumberRange bigDecimalNumberRangeValue) {
			builder.setBigDecimalNumberRangeValue(EvitaDataTypesConverter.toGrpcBigDecimalNumberRange(bigDecimalNumberRangeValue));
		} else if (parameter instanceof OffsetDateTime offsetDateTimeValue) {
			builder.setOffsetDateTimeValue(EvitaDataTypesConverter.toGrpcOffsetDateTime(offsetDateTimeValue));
		} else if (parameter instanceof LocalDateTime localDateTimeValue) {
			builder.setOffsetDateTimeValue(EvitaDataTypesConverter.toGrpcLocalDateTime(localDateTimeValue));
		} else if (parameter instanceof LocalDate localDateValue) {
			builder.setOffsetDateTimeValue(EvitaDataTypesConverter.toGrpcLocalDate(localDateValue));
		} else if (parameter instanceof LocalTime localTimeValue) {
			builder.setOffsetDateTimeValue(EvitaDataTypesConverter.toGrpcLocalTime(localTimeValue));
		} else if (parameter instanceof Locale localeValue) {
			builder.setLocaleValue(EvitaDataTypesConverter.toGrpcLocale(localeValue));
		} else if (parameter instanceof Currency currencyValue) {
			builder.setCurrencyValue(EvitaDataTypesConverter.toGrpcCurrency(currencyValue));
		} else if (parameter instanceof FacetStatisticsDepth facetStatisticsDepth) {
			builder.setFacetStatisticsDepthValue(EvitaEnumConverter.toGrpcFacetStatisticsDepth(facetStatisticsDepth));
		} else if (parameter instanceof QueryPriceMode queryPriceModeValue) {
			builder.setQueryPriceModelValue(EvitaEnumConverter.toGrpcQueryPriceMode(queryPriceModeValue));
		} else if (parameter instanceof PriceContentMode priceContentModeValue) {
			builder.setPriceContentModeValue(EvitaEnumConverter.toGrpcPriceContentMode(priceContentModeValue));
		} else if (parameter instanceof AttributeSpecialValue attributeSpecialValue) {
			builder.setAttributeSpecialValue(EvitaEnumConverter.toGrpcAttributeSpecialValue(attributeSpecialValue));
		} else if (parameter instanceof OrderDirection orderDirectionValue) {
			builder.setOrderDirectionValue(EvitaEnumConverter.toGrpcOrderDirection(orderDirectionValue));
		} else if (parameter instanceof String[] stringArrayValue) {
			builder.setStringArrayValue(EvitaDataTypesConverter.toGrpcStringArray(stringArrayValue));
		} else if (parameter instanceof Integer[] integerArrayValue) {
			builder.setIntegerArrayValue(EvitaDataTypesConverter.toGrpcIntegerArray(integerArrayValue));
		} else if (parameter instanceof Long[] longArrayValue) {
			builder.setLongArrayValue(EvitaDataTypesConverter.toGrpcLongArray(longArrayValue));
		} else if (parameter instanceof Boolean[] booleanArrayValue) {
			builder.setBooleanArrayValue(EvitaDataTypesConverter.toGrpcBooleanArray(booleanArrayValue));
		} else if (parameter instanceof BigDecimal[] bigDecimalArrayValue) {
			builder.setBigDecimalArrayValue(EvitaDataTypesConverter.toGrpcBigDecimalArray(bigDecimalArrayValue));
		} else if (parameter instanceof DateTimeRange[] dateTimeRangeArrayValue) {
			builder.setDateTimeRangeArrayValue(EvitaDataTypesConverter.toGrpcDateTimeRangeArray(dateTimeRangeArrayValue));
		} else if (parameter instanceof ByteNumberRange[] byteNumberRangeArrayValue) {
			builder.setIntegerNumberRangeArrayValue(EvitaDataTypesConverter.toGrpcByteNumberRangeArray(byteNumberRangeArrayValue));
		} else if (parameter instanceof ShortNumberRange[] shortNumberRangeArrayValue) {
			builder.setIntegerNumberRangeArrayValue(EvitaDataTypesConverter.toGrpcShortNumberRangeArray(shortNumberRangeArrayValue));
		} else if (parameter instanceof IntegerNumberRange[] integerNumberRangeArrayValue) {
			builder.setIntegerNumberRangeArrayValue(EvitaDataTypesConverter.toGrpcIntegerNumberRangeArray(integerNumberRangeArrayValue));
		} else if (parameter instanceof LongNumberRange[] longNumberRangeArrayValue) {
			builder.setLongNumberRangeArrayValue(EvitaDataTypesConverter.toGrpcLongNumberRangeArray(longNumberRangeArrayValue));
		} else if (parameter instanceof BigDecimalNumberRange[] bigDecimalNumberRangeArrayValue) {
			builder.setBigDecimalNumberRangeArrayValue(EvitaDataTypesConverter.toGrpcBigDecimalNumberRangeArray(bigDecimalNumberRangeArrayValue));
		} else if (parameter instanceof OffsetDateTime[] offsetDateTimeArrayValue) {
			builder.setOffsetDateTimeArrayValue(EvitaDataTypesConverter.toGrpcOffsetDateTimeArray(offsetDateTimeArrayValue));
		} else if (parameter instanceof LocalDateTime[] localDateTimeArrayValue) {
			builder.setOffsetDateTimeArrayValue(EvitaDataTypesConverter.toGrpcLocalDateTimeArray(localDateTimeArrayValue));
		} else if (parameter instanceof LocalDate[] localDateArrayValue) {
			builder.setOffsetDateTimeArrayValue(EvitaDataTypesConverter.toGrpcLocalDateArray(localDateArrayValue));
		} else if (parameter instanceof LocalTime[] localTimeArrayValue) {
			builder.setOffsetDateTimeArrayValue(EvitaDataTypesConverter.toGrpcLocalTimeArray(localTimeArrayValue));
		} else if (parameter instanceof Locale[] localeArrayValue) {
			builder.setLocaleArrayValue(EvitaDataTypesConverter.toGrpcLocaleArray(localeArrayValue));
		} else if (parameter instanceof Currency[] currencyArrayValue) {
			builder.setCurrencyArrayValue(EvitaDataTypesConverter.toGrpcCurrencyArray(currencyArrayValue));
		} else if (parameter instanceof FacetStatisticsDepth[] facetStatisticsArrayDepth) {
			builder.setFacetStatisticsDepthArrayValue(GrpcFacetStatisticsDepthArray.newBuilder().addAllValue(
				Arrays.stream(facetStatisticsArrayDepth).map(EvitaEnumConverter::toGrpcFacetStatisticsDepth).toList()
			));
		} else if (parameter instanceof QueryPriceMode[] queryPriceModeArrayValue) {
			builder.setQueryPriceModelArrayValue(GrpcQueryPriceModeArray.newBuilder().addAllValue(
				Arrays.stream(queryPriceModeArrayValue).map(EvitaEnumConverter::toGrpcQueryPriceMode).toList()
			));
		} else if (parameter instanceof PriceContentMode[] priceContentModeArrayValue) {
			builder.setPriceContentModeArrayValue(GrpcPriceContentModeArray.newBuilder().addAllValue(
				Arrays.stream(priceContentModeArrayValue).map(EvitaEnumConverter::toGrpcPriceContentMode).toList()
			));
		} else if (parameter instanceof AttributeSpecialValue[] attributeSpecialArrayValue) {
			builder.setAttributeSpecialArrayValue(GrpcAttributeSpecialValueArray.newBuilder().addAllValue(
				Arrays.stream(attributeSpecialArrayValue).map(EvitaEnumConverter::toGrpcAttributeSpecialValue).toList()
			));
		} else if (parameter instanceof OrderDirection[] orderDirectionArrayValue) {
			builder.setOrderDirectionArrayValue(GrpcOrderDirectionArray.newBuilder().addAllValue(
				Arrays.stream(orderDirectionArrayValue).map(EvitaEnumConverter::toGrpcOrderDirection).toList()
			));
		} else {
			throw new EvitaInvalidUsageException("Unsupported Evita data type `" + parameter + "` in gRPC API.");
		}
		return builder.build();
	}

	/**
	 * Converts {@link GrpcQueryResponse} to {@link DataChunk} using proper implementation - either {@link PaginatedList}
	 * or {@link StripList} depending on the information in the response.
	 */
	@Nonnull
	public static <T extends Serializable> DataChunk<T> convertToDataChunk(
		@Nonnull GrpcQueryResponse grpcResponse,
		@Nonnull Function<GrpcDataChunk, List<T>> converter
	) {
		final GrpcDataChunk grpcRecordPage = grpcResponse.getRecordPage();
		if (grpcRecordPage.hasPaginatedList()) {
			final GrpcPaginatedList grpcPaginatedList = grpcRecordPage.getPaginatedList();
			return new PaginatedList<>(
				grpcPaginatedList.getPageNumber(),
				grpcPaginatedList.getPageSize(),
				grpcRecordPage.getTotalRecordCount(),
				converter.apply(grpcRecordPage)
			);
		} else if (grpcRecordPage.hasStripList()) {
			final GrpcStripList grpcStripList = grpcRecordPage.getStripList();
			return new StripList<>(
				grpcStripList.getOffset(),
				grpcStripList.getLimit(),
				grpcRecordPage.getTotalRecordCount(),
				converter.apply(grpcRecordPage)
			);
		} else {
			throw new EvitaInternalError(
				"Only PaginatedList or StripList expected, but got none!"
			);
		}
	}

	/**
	 * TODO JNO - implement a document me
	 */
	@Nonnull
	public static EvitaResponseExtraResult[] toExtraResults(@Nonnull GrpcExtraResults extraResults) {
		return new EvitaResponseExtraResult[0];
	}
}
