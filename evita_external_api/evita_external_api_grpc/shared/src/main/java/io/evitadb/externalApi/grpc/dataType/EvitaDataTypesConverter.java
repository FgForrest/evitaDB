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

package io.evitadb.externalApi.grpc.dataType;

import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import io.evitadb.api.CatalogStatistics;
import io.evitadb.api.CatalogStatistics.EntityCollectionStatistics;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.dataType.*;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaAssociatedDataValue.ValueCase;
import io.evitadb.externalApi.grpc.generated.GrpcTaskStatus.Builder;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.NumberUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Currency;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * This class is used to convert any of the {@link EvitaDataTypes#getSupportedDataTypes()} types to {@link GrpcEvitaDataTypes}
 * and vice versa. Also, value of these types can be convert between Java's {@link Serializable} and gRPC's {@link GrpcEvitaValue}.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class EvitaDataTypesConverter {

	/**
	 * Default zone used to convert data types non-zoned date/time types to gRPC message types.
	 */
	private static final ZoneOffset DEFAULT_ZONE_OFFSET = ZoneOffset.UTC;

	/**
	 * Minimal supported year by gRPC API. More info at <a href="https://github.com/protocolbuffers/protobuf/blob/main/src/google/protobuf/timestamp.proto">official google docs</a>.
	 * Values exceeding this limit will be converted to new {@link #GRPC_MIN_INSTANT}. For precise values, all gRPC clients
	 * should convert timestamps to match the supported range of the language used.
	 */
	private static final int GRPC_YEAR_MIN = 1;
	/**
	 * Maximal supported year by gRPC API. More info at <a href="https://github.com/protocolbuffers/protobuf/blob/main/src/google/protobuf/timestamp.proto">official google docs</a>.
	 * Values exceeding this limit will be converted to new {@link #GRPC_MAX_INSTANT}. For precise values, all gRPC clients
	 * should convert timestamps to match the supported range of the language used.
	 */
	private static final int GRPC_YEAR_MAX = 9999;
	/**
	 * Representation of minimal supported timestamp by gRPC. More info at <a href="https://github.com/protocolbuffers/protobuf/blob/main/src/google/protobuf/timestamp.proto">official google docs</a>.
	 */
	public static final Instant GRPC_MIN_INSTANT = Instant.ofEpochSecond(LocalDate.of(GRPC_YEAR_MIN, 1, 1).toEpochSecond(LocalTime.of(0, 0, 0), ZoneOffset.UTC));
	/**
	 * Representation of maximal supported timestamp by gRPC. More info at <a href="https://github.com/protocolbuffers/protobuf/blob/main/src/google/protobuf/timestamp.proto">official google docs</a>.
	 */
	public static final Instant GRPC_MAX_INSTANT = Instant.ofEpochSecond(LocalDate.of(GRPC_YEAR_MAX, 12, 31).toEpochSecond(LocalTime.of(23, 59, 59), ZoneOffset.UTC));

	/**
	 * Converts the given {@link GrpcEvitaValue} to a {@link Serializable} value.
	 *
	 * @param value the supported data type value which is to be converted to one the {@link EvitaDataTypes#getSupportedDataTypes()} in a {@link Serializable} form.
	 * @param <T>   type of the value
	 * @return converted value
	 */
	@SuppressWarnings("unchecked")
	@Nonnull
	public static <T extends Serializable> T toEvitaValue(@Nonnull GrpcEvitaValue value) {
		return switch (value.getType()) {
			case STRING -> (T) value.getStringValue();
			case BYTE -> (T) toByte(value.getIntegerValue());
			case SHORT -> (T) toShort(value.getIntegerValue());
			case INTEGER -> (T) (Integer) value.getIntegerValue();
			case LONG -> (T) (Long) value.getLongValue();
			case BOOLEAN -> (T) (Boolean) value.getBooleanValue();
			case CHARACTER -> (T) toCharacter(value.getStringValue());
			case BIG_DECIMAL -> (T) toBigDecimal(value.getBigDecimalValue());
			case OFFSET_DATE_TIME -> (T) toOffsetDateTime(value.getOffsetDateTimeValue());
			case LOCAL_DATE_TIME -> (T) toLocalDateTime(value.getOffsetDateTimeValue());
			case LOCAL_DATE -> (T) toLocalDate(value.getOffsetDateTimeValue());
			case LOCAL_TIME -> (T) toLocalTime(value.getOffsetDateTimeValue());
			case DATE_TIME_RANGE -> (T) toDateTimeRange(value.getDateTimeRangeValue());
			case BIG_DECIMAL_NUMBER_RANGE -> (T) toBigDecimalNumberRange(value.getBigDecimalNumberRangeValue());
			case LONG_NUMBER_RANGE -> (T) toLongNumberRange(value.getLongNumberRangeValue());
			case INTEGER_NUMBER_RANGE -> (T) toIntegerNumberRange(value.getIntegerNumberRangeValue());
			case SHORT_NUMBER_RANGE -> (T) toShortNumberRange(value.getIntegerNumberRangeValue());
			case BYTE_NUMBER_RANGE -> (T) toByteNumberRange(value.getIntegerNumberRangeValue());
			case LOCALE -> (T) toLocale(value.getLocaleValue());
			case CURRENCY -> (T) toCurrency(value.getCurrencyValue());
			case PREDECESSOR -> (T) toPredecessor(value.getPredecessorValue());
			case REFERENCED_ENTITY_PREDECESSOR -> (T) toReferencedEntityPredecessor(value.getPredecessorValue());
			case UUID -> (T) toUuid(value.getUuidValue());

			case STRING_ARRAY -> (T) toStringArray(value.getStringArrayValue());
			case BYTE_ARRAY -> (T) toByteArray(value.getIntegerArrayValue());
			case SHORT_ARRAY -> (T) toShortArray(value.getIntegerArrayValue());
			case INTEGER_ARRAY -> (T) toIntegerArray(value.getIntegerArrayValue());
			case LONG_ARRAY -> (T) toLongArray(value.getLongArrayValue());
			case BOOLEAN_ARRAY -> (T) toBooleanArray(value.getBooleanArrayValue());
			case CHARACTER_ARRAY -> (T) toCharacterArray(value.getStringArrayValue());
			case BIG_DECIMAL_ARRAY -> (T) toBigDecimalArray(value.getBigDecimalArrayValue());
			case OFFSET_DATE_TIME_ARRAY -> (T) toOffsetDateTimeArray(value.getOffsetDateTimeArrayValue());
			case LOCAL_DATE_TIME_ARRAY -> (T) toLocalDateTimeArray(value.getOffsetDateTimeArrayValue());
			case LOCAL_DATE_ARRAY -> (T) toLocalDateArray(value.getOffsetDateTimeArrayValue());
			case LOCAL_TIME_ARRAY -> (T) toLocalTimeArray(value.getOffsetDateTimeArrayValue());
			case DATE_TIME_RANGE_ARRAY -> (T) toDateTimeRangeArray(value.getDateTimeRangeArrayValue());
			case BIG_DECIMAL_NUMBER_RANGE_ARRAY ->
				(T) toBigDecimalNumberRangeArray(value.getBigDecimalNumberRangeArrayValue());
			case LONG_NUMBER_RANGE_ARRAY -> (T) toLongNumberRangeArray(value.getLongNumberRangeArrayValue());
			case INTEGER_NUMBER_RANGE_ARRAY -> (T) toIntegerNumberRangeArray(value.getIntegerNumberRangeArrayValue());
			case SHORT_NUMBER_RANGE_ARRAY -> (T) toShortNumberRangeArray(value.getIntegerNumberRangeArrayValue());
			case BYTE_NUMBER_RANGE_ARRAY -> (T) toByteNumberRangeArray(value.getIntegerNumberRangeArrayValue());
			case LOCALE_ARRAY -> (T) toLocaleArray(value.getLocaleArrayValue());
			case CURRENCY_ARRAY -> (T) toCurrencyArray(value.getCurrencyArrayValue());
			case UUID_ARRAY -> (T) toUuidArray(value.getUuidArrayValue());

			default ->
				throw new GenericEvitaInternalError("Unsupported Evita data type in gRPC API `" + value.getValueCase() + "`.");
		};
	}

	/**
	 * This method converts {@link GrpcEvitaAssociatedDataValue} to {@link Serializable}. For each supported associated data
	 * value data type by Evita's gRPC API, this method converts the value to the corresponding {@link Serializable} Evita data type.
	 *
	 * @param value {@link GrpcEvitaAssociatedDataValue} to be converted
	 * @return converted value in the {@link Serializable} form
	 */
	@Nonnull
	public static Serializable toEvitaValue(@Nonnull GrpcEvitaAssociatedDataValue value) {
		if (value.getValueCase() == ValueCase.PRIMITIVEVALUE) {
			return toEvitaValue(value.getPrimitiveValue());
		} else if (value.getValueCase() == ValueCase.ROOT) {
			return toComplexObject(value.getRoot());
		} else if (value.getValueCase() == ValueCase.JSONVALUE) {
			return ComplexDataObjectConverter.convertJsonToComplexDataObject(value.getJsonValue());
		} else {
			throw new GenericEvitaInternalError("Unknown value type.");
		}
	}

	/**
	 * This method creates {@link GrpcEvitaValue} from {@link Serializable} without version. For each supported attribute
	 * value data type by evita, this method converts the value to the corresponding {@link GrpcEvitaValue}
	 * by calling the corresponding method.
	 *
	 * @param value supported by evita without {@link ComplexDataObject} returned by evita response
	 * @return converted {@link GrpcEvitaValue} value
	 */
	@Nonnull
	public static GrpcEvitaValue toGrpcEvitaValue(@Nullable Serializable value) {
		return toGrpcEvitaValue(value, null);
	}

	/**
	 * This method creates {@link GrpcEvitaValue} from {@link Serializable} with specific version. For each supported attribute
	 * value data type by evita, this method converts the value to the corresponding {@link GrpcEvitaValue}
	 * by calling the corresponding method.
	 *
	 * @param value   supported by evita without {@link ComplexDataObject} returned by evita response
	 * @param version optional version of value
	 * @return converted {@link GrpcEvitaValue} value
	 */
	@Nonnull
	public static GrpcEvitaValue toGrpcEvitaValue(@Nullable Serializable value, @Nullable Integer version) {
		final GrpcEvitaValue.Builder builder = GrpcEvitaValue.newBuilder();
		if (value == null) {
			return builder.build();
		}

		builder.setType(toGrpcEvitaDataType(value.getClass()));

		if (value instanceof String stringValue) {
			builder.setStringValue(stringValue);
		} else if (value instanceof Character characterValue) {
			builder.setStringValue(toGrpcCharacter(characterValue));
		} else if (value instanceof Integer integerValue) {
			builder.setIntegerValue(integerValue);
		} else if (value instanceof Short shortValue) {
			builder.setIntegerValue(shortValue);
		} else if (value instanceof Byte byteValue) {
			builder.setIntegerValue(byteValue);
		} else if (value instanceof Long longValue) {
			builder.setLongValue(longValue);
		} else if (value instanceof Boolean booleanValue) {
			builder.setBooleanValue(booleanValue);
		} else if (value instanceof BigDecimal bigDecimalValue) {
			builder.setBigDecimalValue(toGrpcBigDecimal(bigDecimalValue));
		} else if (value instanceof OffsetDateTime offsetDateTimeValue) {
			builder.setOffsetDateTimeValue(toGrpcOffsetDateTime(offsetDateTimeValue));
		} else if (value instanceof LocalDateTime localDateTimeValue) {
			builder.setOffsetDateTimeValue(toGrpcLocalDateTime(localDateTimeValue));
		} else if (value instanceof LocalDate localDateValue) {
			builder.setOffsetDateTimeValue(toGrpcLocalDate(localDateValue));
		} else if (value instanceof LocalTime localeTimeValue) {
			builder.setOffsetDateTimeValue(toGrpcLocalTime(localeTimeValue));
		} else if (value instanceof DateTimeRange dateTimeRangeValue) {
			builder.setDateTimeRangeValue(toGrpcDateTimeRange(dateTimeRangeValue));
		} else if (value instanceof BigDecimalNumberRange bigDecimalNumberRangeValue) {
			builder.setBigDecimalNumberRangeValue(toGrpcBigDecimalNumberRange(bigDecimalNumberRangeValue));
		} else if (value instanceof LongNumberRange longNumberRangeValue) {
			builder.setLongNumberRangeValue(toGrpcLongNumberRange(longNumberRangeValue));
		} else if (value instanceof IntegerNumberRange integerNumberRangeValue) {
			builder.setIntegerNumberRangeValue(toGrpcIntegerNumberRange(integerNumberRangeValue));
		} else if (value instanceof ShortNumberRange shortNumberRangeValue) {
			builder.setIntegerNumberRangeValue(toGrpcIntegerNumberRange(shortNumberRangeValue));
		} else if (value instanceof ByteNumberRange byteNumberRangeValue) {
			builder.setIntegerNumberRangeValue(toGrpcIntegerNumberRange(byteNumberRangeValue));
		} else if (value instanceof Locale localeValue) {
			builder.setLocaleValue(toGrpcLocale(localeValue));
		} else if (value instanceof Currency currencyValue) {
			builder.setCurrencyValue(toGrpcCurrency(currencyValue));
		} else if (value instanceof UUID uuidValue) {
			builder.setUuidValue(toGrpcUuid(uuidValue));
		} else if (value instanceof Predecessor predecessorValue) {
			builder.setPredecessorValue(toGrpcPredecessor(predecessorValue));
		} else if (value instanceof ReferencedEntityPredecessor predecessorValue) {
			builder.setPredecessorValue(toGrpcPredecessor(predecessorValue));
		} else if (value instanceof byte[] byteArrayValues) {
			builder.setIntegerArrayValue(toGrpcByteArray(byteArrayValues));
		} else if (value instanceof short[] shortArrayValues) {
			builder.setIntegerArrayValue(toGrpcShortArray(shortArrayValues));
		} else if (value instanceof int[] integerArrayValues) {
			builder.setIntegerArrayValue(toGrpcIntegerArray(integerArrayValues));
		} else if (value instanceof long[] longArrayValues) {
			builder.setLongArrayValue(toGrpcLongArray(longArrayValues));
		} else if (value instanceof boolean[] boolArrayValues) {
			builder.setBooleanArrayValue(toGrpcBooleanArray(boolArrayValues));
		} else if (value instanceof char[] charArrayValues) {
			builder.setStringArrayValue(toGrpcCharacterArray(charArrayValues));
		} else if (value instanceof String[] stringArrayValues) {
			builder.setStringArrayValue(toGrpcStringArray(stringArrayValues));
		} else if (value instanceof Character[] characterArrayValues) {
			builder.setStringArrayValue(toGrpcCharacterArray(characterArrayValues));
		} else if (value instanceof Integer[] integerArrayValues) {
			builder.setIntegerArrayValue(toGrpcIntegerArray(integerArrayValues));
		} else if (value instanceof Short[] shortArrayValues) {
			builder.setIntegerArrayValue(toGrpcShortArray(shortArrayValues));
		} else if (value instanceof Byte[] byteArrayValues) {
			builder.setIntegerArrayValue(toGrpcByteArray(byteArrayValues));
		} else if (value instanceof Long[] longArrayValues) {
			builder.setLongArrayValue(toGrpcLongArray(longArrayValues));
		} else if (value instanceof Boolean[] booleanArrayValues) {
			builder.setBooleanArrayValue(toGrpcBooleanArray(booleanArrayValues));
		} else if (value instanceof BigDecimal[] bigDecimalArrayValues) {
			builder.setBigDecimalArrayValue(toGrpcBigDecimalArray(bigDecimalArrayValues));
		} else if (value instanceof OffsetDateTime[] offsetDateTimeArrayValues) {
			builder.setOffsetDateTimeArrayValue(toGrpcOffsetDateTimeArray(offsetDateTimeArrayValues));
		} else if (value instanceof LocalDateTime[] localDateTimeArrayValues) {
			builder.setOffsetDateTimeArrayValue(toGrpcLocalDateTimeArray(localDateTimeArrayValues));
		} else if (value instanceof LocalDate[] localDateArrayValues) {
			builder.setOffsetDateTimeArrayValue(toGrpcLocalDateArray(localDateArrayValues));
		} else if (value instanceof LocalTime[] localeTimeArrayValues) {
			builder.setOffsetDateTimeArrayValue(toGrpcLocalTimeArray(localeTimeArrayValues));
		} else if (value instanceof DateTimeRange[] dateTimeRangeArrayValues) {
			builder.setDateTimeRangeArrayValue(toGrpcDateTimeRangeArray(dateTimeRangeArrayValues));
		} else if (value instanceof BigDecimalNumberRange[] bigDecimalNumberRangeArrayValues) {
			builder.setBigDecimalNumberRangeArrayValue(toGrpcBigDecimalNumberRangeArray(bigDecimalNumberRangeArrayValues));
		} else if (value instanceof LongNumberRange[] longNumberRangeArrayValues) {
			builder.setLongNumberRangeArrayValue(toGrpcLongNumberRangeArray(longNumberRangeArrayValues));
		} else if (value instanceof IntegerNumberRange[] integerNumberRangeArrayValues) {
			builder.setIntegerNumberRangeArrayValue(toGrpcIntegerNumberRangeArray(integerNumberRangeArrayValues));
		} else if (value instanceof ShortNumberRange[] shortNumberRangeArrayValues) {
			builder.setIntegerNumberRangeArrayValue(toGrpcShortNumberRangeArray(shortNumberRangeArrayValues));
		} else if (value instanceof ByteNumberRange[] byteNumberRangeArrayValues) {
			builder.setIntegerNumberRangeArrayValue(toGrpcByteNumberRangeArray(byteNumberRangeArrayValues));
		} else if (value instanceof Locale[] localeArrayValues) {
			builder.setLocaleArrayValue(toGrpcLocaleArray(localeArrayValues));
		} else if (value instanceof Currency[] currencyArrayValues) {
			builder.setCurrencyArrayValue(toGrpcCurrencyArray(currencyArrayValues));
		} else if (value instanceof UUID[] uuidArrayValues) {
			builder.setUuidArrayValue(toGrpcUuidArray(uuidArrayValues));
		} else {
			throw new EvitaInvalidUsageException("Unsupported Evita data type in gRPC API '" + value.getClass().getName() + "'.");
		}

		if (version != null) {
			builder.setVersion(Int32Value.of(version));
		}

		return builder.build();
	}

	/**
	 * Converts serializable {@link AssociatedDataValue#value()} to {@link GrpcEvitaAssociatedDataValue} without version.
	 *
	 * @param value in {@link Serializable} data type supported by Evita.
	 * @return converted {@link GrpcEvitaAssociatedDataValue}
	 */
	@Nonnull
	public static GrpcEvitaAssociatedDataValue toGrpcEvitaAssociatedDataValue(@Nonnull Serializable value, @Nonnull AssociatedDataForm form) {
		return toGrpcEvitaAssociatedDataValue(value, null, form);
	}

	/**
	 * Converts serializable {@link AssociatedDataValue#value()} to {@link GrpcEvitaAssociatedDataValue}.
	 *
	 * @param value   in {@link Serializable} data type supported by Evita.
	 * @param version optional version of the built {@link GrpcEvitaValue}
	 * @return converted {@link GrpcEvitaAssociatedDataValue}
	 */
	@Nonnull
	public static GrpcEvitaAssociatedDataValue toGrpcEvitaAssociatedDataValue(@Nonnull Serializable value, @Nullable Integer version, @Nonnull AssociatedDataForm form) {
		final GrpcEvitaAssociatedDataValue.Builder builder = GrpcEvitaAssociatedDataValue.newBuilder()
			.setType(toGrpcEvitaAssociatedDataDataType(value.getClass()));

		if (value instanceof ComplexDataObject complexDataObject) {
			// TOBEDONE #538 - remove when all clients are `2025.4` or higher
			switch (form) {
				case JSON -> builder.setJsonValue(ComplexDataObjectConverter.convertComplexDataObjectToJson(complexDataObject).toString());
				case STRUCTURED_VALUE -> builder.setRoot(toGrpcDataItem(complexDataObject.root()));
			}
		} else {
			builder.setPrimitiveValue(toGrpcEvitaValue(value, version));
		}

		if (version != null) {
			builder.setVersion(Int32Value.of(version));
		}

		return builder.build();
	}

	/**
	 * Converts the provided DataItem object to a GrpcDataItem object.
	 *
	 * @param dataItem the DataItem to be converted; must not be null
	 * @return the converted GrpcDataItem instance
	 * @throws EvitaInvalidUsageException if the dataItem is of an unsupported type
	 */
	@Nonnull
	public static GrpcDataItem toGrpcDataItem(@Nonnull DataItem dataItem) {
		final GrpcDataItem.Builder builder = GrpcDataItem.newBuilder();
		if (dataItem instanceof DataItemValue div) {
			builder.setPrimitiveValue(toGrpcEvitaValue(div.value()));
		} else if (dataItem instanceof DataItemArray dia) {
			final GrpcDataItemArray.Builder arrayBuilder = GrpcDataItemArray.newBuilder();
			for (DataItem child : dia.children()) {
				arrayBuilder.addChildren(toGrpcDataItem(child));
			}
			builder.setArrayValue(arrayBuilder.build());
		} else if (dataItem instanceof DataItemMap dim) {
			final io.evitadb.externalApi.grpc.generated.DataItemMap.Builder mapBuilder = io.evitadb.externalApi.grpc.generated.DataItemMap.newBuilder();
			for (String propertyName : dim.getPropertyNames()) {
				final DataItem property = dim.getProperty(propertyName);
				if (property != null) {
					mapBuilder.putData(propertyName, toGrpcDataItem(property));
				}
			}
			builder.setMapValue(mapBuilder.build());
		} else {
			throw new EvitaInvalidUsageException("Unsupported Evita data type in gRPC API '" + dataItem.getClass().getName() + "'.");
		}
		return builder.build();
	}

	/**
	 * Converts a given GrpcDataItem into a ComplexDataObject.
	 *
	 * @param root the GrpcDataItem to be converted, must not be null
	 * @return a new ComplexDataObject created from the given GrpcDataItem, never null
	 */
	@Nonnull
	public static ComplexDataObject toComplexObject(@Nonnull GrpcDataItem root) {
		return new ComplexDataObject(
			toDataItem(root)
		);
	}

	/**
	 * Converts a given {@code GrpcDataItem} into the corresponding {@code DataItem}.
	 *
	 * @param dataItem the gRPC data item to be converted, must not be null
	 * @return the converted {@code DataItem} representation of the provided {@code GrpcDataItem}
	 * @throws EvitaInvalidUsageException if the provided {@code GrpcDataItem} contains an unsupported or unrecognized type
	 */
	@Nonnull
	private static DataItem toDataItem(@Nonnull GrpcDataItem dataItem) {
		if (dataItem.hasPrimitiveValue()) {
			return new DataItemValue(
				toEvitaValue(dataItem.getPrimitiveValue())
			);
		} else if (dataItem.hasArrayValue()) {
			return new DataItemArray(
				dataItem.getArrayValue().getChildrenList()
					.stream()
					.map(EvitaDataTypesConverter::toDataItem)
					.toArray(DataItem[]::new)
			);
		} else if (dataItem.hasMapValue()) {
			return new DataItemMap(
				dataItem.getMapValue().getDataMap()
					.entrySet()
					.stream()
					.collect(
						Collectors.toMap(
							Entry::getKey,
							it -> toDataItem(it.getValue())
						)
					)
			);
		} else {
			throw new EvitaInvalidUsageException("Unsupported Evita data type in gRPC API '" + dataItem.getClass().getName() + "'.");
		}
	}


	/**
	 * For the passed {@link GrpcEvitaDataTypes} this method returns the corresponding {@link Class} Evita data type.
	 *
	 * @param dataType type to be converted
	 * @return matching {@link EvitaDataTypes} to the passed enum
	 */
	@Nonnull
	public static Class<? extends Serializable> toEvitaDataType(@Nonnull GrpcEvitaDataType dataType) {
		return switch (dataType) {
			case STRING -> String.class;
			case BYTE -> Byte.class;
			case SHORT -> Short.class;
			case INTEGER -> Integer.class;
			case LONG -> Long.class;
			case BOOLEAN -> Boolean.class;
			case CHARACTER -> Character.class;
			case BIG_DECIMAL -> BigDecimal.class;
			case OFFSET_DATE_TIME -> OffsetDateTime.class;
			case LOCAL_DATE_TIME -> LocalDateTime.class;
			case LOCAL_DATE -> LocalDate.class;
			case LOCAL_TIME -> LocalTime.class;
			case DATE_TIME_RANGE -> DateTimeRange.class;
			case BIG_DECIMAL_NUMBER_RANGE -> BigDecimalNumberRange.class;
			case LONG_NUMBER_RANGE -> LongNumberRange.class;
			case INTEGER_NUMBER_RANGE -> IntegerNumberRange.class;
			case SHORT_NUMBER_RANGE -> ShortNumberRange.class;
			case BYTE_NUMBER_RANGE -> ByteNumberRange.class;
			case LOCALE -> Locale.class;
			case CURRENCY -> Currency.class;
			case UUID -> UUID.class;
			case PREDECESSOR -> Predecessor.class;
			case REFERENCED_ENTITY_PREDECESSOR -> ReferencedEntityPredecessor.class;
			case STRING_ARRAY -> String[].class;
			case BYTE_ARRAY -> Byte[].class;
			case SHORT_ARRAY -> Short[].class;
			case INTEGER_ARRAY -> Integer[].class;
			case LONG_ARRAY -> Long[].class;
			case BOOLEAN_ARRAY -> Boolean[].class;
			case CHARACTER_ARRAY -> Character.class;
			case BIG_DECIMAL_ARRAY -> BigDecimal[].class;
			case OFFSET_DATE_TIME_ARRAY -> OffsetDateTime[].class;
			case LOCAL_DATE_TIME_ARRAY -> LocalDateTime[].class;
			case LOCAL_DATE_ARRAY -> LocalDate[].class;
			case LOCAL_TIME_ARRAY -> LocalTime[].class;
			case DATE_TIME_RANGE_ARRAY -> DateTimeRange[].class;
			case BIG_DECIMAL_NUMBER_RANGE_ARRAY -> BigDecimalNumberRange[].class;
			case LONG_NUMBER_RANGE_ARRAY -> LongNumberRange[].class;
			case INTEGER_NUMBER_RANGE_ARRAY -> IntegerNumberRange[].class;
			case SHORT_NUMBER_RANGE_ARRAY -> ShortNumberRange[].class;
			case BYTE_NUMBER_RANGE_ARRAY -> ByteNumberRange[].class;
			case LOCALE_ARRAY -> Locale[].class;
			case CURRENCY_ARRAY -> Currency[].class;
			case UUID_ARRAY -> UUID[].class;
			default ->
				throw new GenericEvitaInternalError("Unsupported Evita data type in gRPC API `" + dataType.getValueDescriptor() + "`.");
		};
	}

	/**
	 * Converts class of {@link AssociatedDataValue#getClass()} from passed {@link GrpcEvitaAssociatedDataDataType.GrpcEvitaDataType}. The method {@link #toEvitaDataType(GrpcEvitaDataType)}
	 * does here most of the work because it handles all the supported types except of {@link ComplexDataObject}, which is handled here.
	 */
	@Nonnull
	public static Class<? extends Serializable> toEvitaDataType(@Nonnull GrpcEvitaAssociatedDataDataType.GrpcEvitaDataType dataType) {
		if (dataType == GrpcEvitaAssociatedDataDataType.GrpcEvitaDataType.COMPLEX_DATA_OBJECT) {
			return ComplexDataObject.class;
		}
		return toEvitaDataType(GrpcEvitaDataType.valueOf(dataType.name()));
	}

	/**
	 * Based on passed class returns matching enum value represented by {@link GrpcEvitaDataTypes}.
	 *
	 * @param dataType class of the {@link GrpcEvitaValue}
	 * @param <T>      type of the value
	 * @return {@link GrpcEvitaDataTypes} enum value
	 */
	@Nonnull
	public static <T extends Serializable> GrpcEvitaDataType toGrpcEvitaDataType(@Nonnull Class<T> dataType) {
		if (dataType.equals(String.class)) {
			return GrpcEvitaDataType.STRING;
		} else if (dataType.equals(Character.class)) {
			return GrpcEvitaDataType.CHARACTER;
		} else if (dataType.equals(char.class)) {
			return GrpcEvitaDataType.CHARACTER;
		} else if (dataType.equals(Integer.class)) {
			return GrpcEvitaDataType.INTEGER;
		} else if (dataType.equals(int.class)) {
			return GrpcEvitaDataType.INTEGER;
		} else if (dataType.equals(Short.class)) {
			return GrpcEvitaDataType.SHORT;
		} else if (dataType.equals(short.class)) {
			return GrpcEvitaDataType.SHORT;
		} else if (dataType.equals(Byte.class)) {
			return GrpcEvitaDataType.BYTE;
		} else if (dataType.equals(byte.class)) {
			return GrpcEvitaDataType.BYTE;
		} else if (dataType.equals(Long.class)) {
			return GrpcEvitaDataType.LONG;
		} else if (dataType.equals(long.class)) {
			return GrpcEvitaDataType.LONG;
		} else if (dataType.equals(Boolean.class)) {
			return GrpcEvitaDataType.BOOLEAN;
		} else if (dataType.equals(boolean.class)) {
			return GrpcEvitaDataType.BOOLEAN;
		} else if (dataType.equals(BigDecimal.class)) {
			return GrpcEvitaDataType.BIG_DECIMAL;
		} else if (dataType.equals(OffsetDateTime.class)) {
			return GrpcEvitaDataType.OFFSET_DATE_TIME;
		} else if (dataType.equals(LocalDateTime.class)) {
			return GrpcEvitaDataType.LOCAL_DATE_TIME;
		} else if (dataType.equals(LocalDate.class)) {
			return GrpcEvitaDataType.LOCAL_DATE;
		} else if (dataType.equals(LocalTime.class)) {
			return GrpcEvitaDataType.LOCAL_TIME;
		} else if (dataType.equals(DateTimeRange.class)) {
			return GrpcEvitaDataType.DATE_TIME_RANGE;
		} else if (dataType.equals(BigDecimalNumberRange.class)) {
			return GrpcEvitaDataType.BIG_DECIMAL_NUMBER_RANGE;
		} else if (dataType.equals(LongNumberRange.class)) {
			return GrpcEvitaDataType.LONG_NUMBER_RANGE;
		} else if (dataType.equals(IntegerNumberRange.class)) {
			return GrpcEvitaDataType.INTEGER_NUMBER_RANGE;
		} else if (dataType.equals(ShortNumberRange.class)) {
			return GrpcEvitaDataType.SHORT_NUMBER_RANGE;
		} else if (dataType.equals(ByteNumberRange.class)) {
			return GrpcEvitaDataType.BYTE_NUMBER_RANGE;
		} else if (dataType.equals(Locale.class)) {
			return GrpcEvitaDataType.LOCALE;
		} else if (dataType.equals(Currency.class)) {
			return GrpcEvitaDataType.CURRENCY;
		} else if (dataType.equals(UUID.class)) {
			return GrpcEvitaDataType.UUID;
		} else if (dataType.equals(Predecessor.class)) {
			return GrpcEvitaDataType.PREDECESSOR;
		} else if (dataType.equals(ReferencedEntityPredecessor.class)) {
			return GrpcEvitaDataType.REFERENCED_ENTITY_PREDECESSOR;
		} else if (dataType.equals(String[].class)) {
			return GrpcEvitaDataType.STRING_ARRAY;
		} else if (dataType.equals(Character[].class)) {
			return GrpcEvitaDataType.CHARACTER_ARRAY;
		} else if (dataType.equals(char[].class)) {
			return GrpcEvitaDataType.CHARACTER_ARRAY;
		} else if (dataType.equals(Integer[].class)) {
			return GrpcEvitaDataType.INTEGER_ARRAY;
		} else if (dataType.equals(int[].class)) {
			return GrpcEvitaDataType.INTEGER_ARRAY;
		} else if (dataType.equals(Short[].class)) {
			return GrpcEvitaDataType.SHORT_ARRAY;
		} else if (dataType.equals(short[].class)) {
			return GrpcEvitaDataType.SHORT_ARRAY;
		} else if (dataType.equals(Byte[].class)) {
			return GrpcEvitaDataType.BYTE_ARRAY;
		} else if (dataType.equals(byte[].class)) {
			return GrpcEvitaDataType.BYTE_ARRAY;
		} else if (dataType.equals(Long[].class)) {
			return GrpcEvitaDataType.LONG_ARRAY;
		} else if (dataType.equals(long[].class)) {
			return GrpcEvitaDataType.LONG_ARRAY;
		} else if (dataType.equals(Boolean[].class)) {
			return GrpcEvitaDataType.BOOLEAN_ARRAY;
		} else if (dataType.equals(boolean[].class)) {
			return GrpcEvitaDataType.BOOLEAN_ARRAY;
		} else if (dataType.equals(BigDecimal[].class)) {
			return GrpcEvitaDataType.BIG_DECIMAL_ARRAY;
		} else if (dataType.equals(OffsetDateTime[].class)) {
			return GrpcEvitaDataType.OFFSET_DATE_TIME_ARRAY;
		} else if (dataType.equals(LocalDateTime[].class)) {
			return GrpcEvitaDataType.LOCAL_DATE_TIME_ARRAY;
		} else if (dataType.equals(LocalDate[].class)) {
			return GrpcEvitaDataType.LOCAL_DATE_ARRAY;
		} else if (dataType.equals(LocalTime[].class)) {
			return GrpcEvitaDataType.LOCAL_TIME_ARRAY;
		} else if (dataType.equals(DateTimeRange[].class)) {
			return GrpcEvitaDataType.DATE_TIME_RANGE_ARRAY;
		} else if (dataType.equals(BigDecimalNumberRange[].class)) {
			return GrpcEvitaDataType.BIG_DECIMAL_NUMBER_RANGE_ARRAY;
		} else if (dataType.equals(LongNumberRange[].class)) {
			return GrpcEvitaDataType.LONG_NUMBER_RANGE_ARRAY;
		} else if (dataType.equals(IntegerNumberRange[].class)) {
			return GrpcEvitaDataType.INTEGER_NUMBER_RANGE_ARRAY;
		} else if (dataType.equals(ShortNumberRange[].class)) {
			return GrpcEvitaDataType.SHORT_NUMBER_RANGE_ARRAY;
		} else if (dataType.equals(ByteNumberRange[].class)) {
			return GrpcEvitaDataType.BYTE_NUMBER_RANGE_ARRAY;
		} else if (dataType.equals(Locale[].class)) {
			return GrpcEvitaDataType.LOCALE_ARRAY;
		} else if (dataType.equals(Currency[].class)) {
			return GrpcEvitaDataType.CURRENCY_ARRAY;
		} else if (dataType.equals(UUID[].class)) {
			return GrpcEvitaDataType.UUID_ARRAY;
		} else {
			throw new EvitaInvalidUsageException("Unsupported Evita data type in gRPC API `" + dataType.getName() + "`.");
		}
	}

	/**
	 * Converts {@link AssociatedDataValue#value()#getClass()} to {@link GrpcEvitaAssociatedDataDataType.GrpcEvitaDataType}. The method {@link #toGrpcEvitaDataType(Class)} does here most of the work
	 * because it handles all the supported types except of {@link ComplexDataObject}, which is handled here.
	 *
	 * @param dataType of which the {@link GrpcEvitaAssociatedDataDataType.GrpcEvitaDataType} is to be returned
	 * @return {@link GrpcEvitaAssociatedDataDataType.GrpcEvitaDataType} for the given {@link AssociatedDataValue#value()#getClass()}
	 */
	@Nonnull
	public static <T extends Serializable> GrpcEvitaAssociatedDataDataType.GrpcEvitaDataType toGrpcEvitaAssociatedDataDataType(@Nonnull Class<T> dataType) {
		if (dataType.equals(ComplexDataObject.class)) {
			return GrpcEvitaAssociatedDataDataType.GrpcEvitaDataType.COMPLEX_DATA_OBJECT;
		}
		return GrpcEvitaAssociatedDataDataType.GrpcEvitaDataType.valueOf(toGrpcEvitaDataType(dataType).name());
	}

	@Nonnull
	public static String[] toStringArray(@Nonnull GrpcStringArray arrayValue) {
		return arrayValue.getValueList().toArray(String[]::new);
	}

	@Nonnull
	public static Character toCharacter(@Nonnull String stringValue) {
		return stringValue.charAt(0);
	}

	@Nonnull
	public static Character[] toCharacterArray(@Nonnull GrpcStringArray arrayValue) {
		return arrayValue.getValueList().stream().map(it -> it.charAt(0)).toArray(Character[]::new);
	}

	@Nonnull
	public static Byte toByte(int intValue) {
		return (byte) intValue;
	}

	@Nonnull
	public static Byte[] toByteArray(@Nonnull GrpcIntegerArray arrayValue) {
		return arrayValue.getValueList().stream().map(it -> (byte) (int) it).toArray(Byte[]::new);
	}

	@Nonnull
	public static Short toShort(int intValue) {
		return (short) intValue;
	}

	@Nonnull
	public static Short[] toShortArray(@Nonnull GrpcIntegerArray arrayValue) {
		return arrayValue.getValueList().stream().map(it -> (short) (int) it).toArray(Short[]::new);
	}

	@Nonnull
	public static Integer[] toIntegerArray(@Nonnull GrpcIntegerArray arrayValue) {
		return arrayValue.getValueList().toArray(Integer[]::new);
	}

	@Nonnull
	public static Long[] toLongArray(@Nonnull GrpcLongArray arrayValue) {
		return arrayValue.getValueList().toArray(Long[]::new);
	}

	@Nonnull
	public static Boolean[] toBooleanArray(@Nonnull GrpcBooleanArray arrayValue) {
		return arrayValue.getValueList().toArray(Boolean[]::new);
	}

	/**
	 * This method is used to convert a {@link GrpcLocale} to {@link Locale}.
	 *
	 * @param locale value to be converted
	 * @return {@link Locale} instance
	 */
	@Nonnull
	public static Locale toLocale(@Nonnull GrpcLocale locale) {
		return Objects.requireNonNull(EvitaDataTypes.toTargetType(locale.getLanguageTag(), Locale.class));
	}

	@Nonnull
	public static Locale[] toLocaleArray(@Nonnull GrpcLocaleArray arrayValue) {
		return arrayValue.getValueList().stream().map(EvitaDataTypesConverter::toLocale).toArray(Locale[]::new);
	}

	/**
	 * This method is used to convert a {@link GrpcCurrency} to {@link Currency}.
	 *
	 * @param currency value to be converted
	 * @return {@link Currency} instance
	 */
	@Nonnull
	public static Currency toCurrency(@Nonnull GrpcCurrency currency) {
		return Currency.getInstance(currency.getCode());
	}

	@Nonnull
	public static Currency[] toCurrencyArray(@Nonnull GrpcCurrencyArray arrayValue) {
		return arrayValue.getValueList().stream().map(EvitaDataTypesConverter::toCurrency).toArray(Currency[]::new);
	}

	/**
	 * This method is used to convert a {@link GrpcUuid} to {@link UUID}.
	 *
	 * @param uuid value to be converted
	 * @return {@link UUID} instance
	 */
	@Nonnull
	public static UUID toUuid(@Nonnull GrpcUuid uuid) {
		return new UUID(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
	}

	@Nonnull
	public static UUID[] toUuidArray(@Nonnull GrpcUuidArray arrayValue) {
		return arrayValue.getValueList().stream().map(EvitaDataTypesConverter::toUuid).toArray(UUID[]::new);
	}

	/**
	 * This method is used to convert a {@link GrpcPredecessor} to {@link Predecessor}.
	 *
	 * @param predecessor value to be converted
	 * @return {@link Predecessor} instance
	 */
	@Nonnull
	public static Predecessor toPredecessor(@Nonnull GrpcPredecessor predecessor) {
		if (predecessor.getHead()) {
			return Predecessor.HEAD;
		} else if (predecessor.hasPredecessorId()) {
			return new Predecessor(predecessor.getPredecessorId().getValue());
		} else {
			throw new EvitaInvalidUsageException("Predecessor must be either HEAD or have a predecessor ID.");
		}
	}

	/**
	 * This method is used to convert a {@link GrpcPredecessor} to {@link ReferencedEntityPredecessor}.
	 *
	 * @param predecessor value to be converted
	 * @return {@link ReferencedEntityPredecessor} instance
	 */
	@Nonnull
	public static ReferencedEntityPredecessor toReferencedEntityPredecessor(@Nonnull GrpcPredecessor predecessor) {
		if (predecessor.getHead()) {
			return ReferencedEntityPredecessor.HEAD;
		} else if (predecessor.hasPredecessorId()) {
			return new ReferencedEntityPredecessor(predecessor.getPredecessorId().getValue());
		} else {
			throw new EvitaInvalidUsageException("ReferencedEntityPredecessor must be either HEAD or have a predecessor ID.");
		}
	}

	/**
	 * This method is used to convert {@link GrpcOffsetDateTime} to {@link OffsetDateTime}.
	 *
	 * @param dateTime value to be converted
	 * @return {@link OffsetDateTime} value
	 */
	@Nonnull
	public static OffsetDateTime toOffsetDateTime(@Nonnull GrpcOffsetDateTime dateTime) {
		final OffsetDateTime grpcOffsetDateTime = OffsetDateTime.ofInstant(
			Instant.ofEpochSecond(dateTime.getTimestamp().getSeconds(), dateTime.getTimestamp().getNanos()),
			ZoneOffset.of(dateTime.getOffset())
		);
		if (grpcOffsetDateTime.getYear() == GRPC_YEAR_MIN) {
			return OffsetDateTime.MIN;
		}
		if (grpcOffsetDateTime.getYear() == GRPC_YEAR_MAX) {
			return OffsetDateTime.MAX;
		}
		return grpcOffsetDateTime;
	}

	@Nonnull
	public static OffsetDateTime[] toOffsetDateTimeArray(@Nonnull GrpcOffsetDateTimeArray arrayValue) {
		return arrayValue.getValueList().stream().map(EvitaDataTypesConverter::toOffsetDateTime).toArray(OffsetDateTime[]::new);
	}

	/**
	 * This method is used to convert {@link GrpcOffsetDateTime} to {@link LocalDateTime}.
	 *
	 * @param dateTime value to be converted
	 * @return {@link LocalDateTime} value
	 */
	@Nonnull
	public static LocalDateTime toLocalDateTime(@Nonnull GrpcOffsetDateTime dateTime) {
		final LocalDateTime grpcLocalDateTime = LocalDateTime.ofInstant(
			Instant.ofEpochSecond(dateTime.getTimestamp().getSeconds(), dateTime.getTimestamp().getNanos()),
			ZoneOffset.of(dateTime.getOffset())
		);
		if (grpcLocalDateTime.getYear() == GRPC_YEAR_MIN) {
			return LocalDateTime.MIN;
		}
		if (grpcLocalDateTime.getYear() == GRPC_YEAR_MAX) {
			return LocalDateTime.MAX;
		}
		return grpcLocalDateTime;
	}

	@Nonnull
	public static LocalDateTime[] toLocalDateTimeArray(@Nonnull GrpcOffsetDateTimeArray arrayValue) {
		return arrayValue.getValueList().stream().map(EvitaDataTypesConverter::toLocalDateTime).toArray(LocalDateTime[]::new);
	}

	/**
	 * This method is used to convert {@link GrpcOffsetDateTime} to {@link LocalDate}.
	 *
	 * @param dateTime value to be converted
	 * @return {@link LocalDate} value
	 */
	@Nonnull
	public static LocalDate toLocalDate(@Nonnull GrpcOffsetDateTime dateTime) {
		final LocalDate grpcLocalDate = LocalDate.ofInstant(
			Instant.ofEpochSecond(dateTime.getTimestamp().getSeconds(), dateTime.getTimestamp().getNanos()),
			ZoneOffset.of(dateTime.getOffset())
		);
		if (grpcLocalDate.getYear() == GRPC_YEAR_MIN) {
			return LocalDate.MIN;
		}
		if (grpcLocalDate.getYear() == GRPC_YEAR_MAX) {
			return LocalDate.MAX;
		}
		return grpcLocalDate;
	}

	@Nonnull
	public static LocalDate[] toLocalDateArray(@Nonnull GrpcOffsetDateTimeArray arrayValue) {
		return arrayValue.getValueList().stream().map(EvitaDataTypesConverter::toLocalDate).toArray(LocalDate[]::new);
	}

	/**
	 * This method is used to convert {@link GrpcOffsetDateTime} to {@link LocalTime}.
	 *
	 * @param dateTime value to be converted
	 * @return {@link LocalTime} value
	 */
	@Nonnull
	public static LocalTime toLocalTime(@Nonnull GrpcOffsetDateTime dateTime) {
		return LocalTime.ofInstant(
			Instant.ofEpochSecond(dateTime.getTimestamp().getSeconds(), dateTime.getTimestamp().getNanos()),
			ZoneOffset.of(dateTime.getOffset())
		);
	}

	@Nonnull
	public static LocalTime[] toLocalTimeArray(@Nonnull GrpcOffsetDateTimeArray arrayValue) {
		return arrayValue.getValueList().stream().map(EvitaDataTypesConverter::toLocalTime).toArray(LocalTime[]::new);
	}

	/**
	 * This method is used to convert {@link GrpcBigDecimal} to {@link BigDecimal}.
	 *
	 * @param grpcBigDecimal value to be converted
	 * @return {@link BigDecimal} value
	 */
	@Nonnull
	public static BigDecimal toBigDecimal(@Nonnull GrpcBigDecimal grpcBigDecimal) {
		return Objects.requireNonNull(EvitaDataTypes.toTargetType(grpcBigDecimal.getValueString(), BigDecimal.class));
	}

	@Nonnull
	public static BigDecimal[] toBigDecimalArray(@Nonnull GrpcBigDecimalArray arrayValue) {
		return arrayValue.getValueList().stream().map(EvitaDataTypesConverter::toBigDecimal).toArray(BigDecimal[]::new);
	}

	/**
	 * This method is used to convert {@link GrpcDateTimeRange} to {@link DateTimeRange}.
	 *
	 * @param grpcDateTimeRange value to be converted
	 * @return {@link DateTimeRange} value
	 */
	@Nonnull
	public static DateTimeRange toDateTimeRange(@Nonnull GrpcDateTimeRange grpcDateTimeRange) {
		final boolean fromSet = grpcDateTimeRange.hasFrom();
		final boolean toSet = grpcDateTimeRange.hasTo();
		final Instant from = Instant.ofEpochSecond(grpcDateTimeRange.getFrom().getTimestamp().getSeconds());
		final Instant to = Instant.ofEpochSecond(grpcDateTimeRange.getTo().getTimestamp().getSeconds());
		final OffsetDateTime fromRange = OffsetDateTime.ofInstant(from, ZoneId.of(fromSet ? grpcDateTimeRange.getFrom().getOffset() : DEFAULT_ZONE_OFFSET.getId()));
		final OffsetDateTime toRange = OffsetDateTime.ofInstant(to, ZoneId.of(toSet ? grpcDateTimeRange.getTo().getOffset() : DEFAULT_ZONE_OFFSET.getId()));
		if (!fromSet && toSet) {
			return DateTimeRange.until(toRange);
		} else if (fromSet && !toSet) {
			return DateTimeRange.since(fromRange);
		}
		return DateTimeRange.between(fromRange, toRange);
	}

	@Nonnull
	public static DateTimeRange[] toDateTimeRangeArray(@Nonnull GrpcDateTimeRangeArray arrayValue) {
		return arrayValue.getValueList().stream().map(EvitaDataTypesConverter::toDateTimeRange).toArray(DateTimeRange[]::new);
	}

	/**
	 * This method is used to convert {@link GrpcIntegerNumberRange} to {@link ShortNumberRange}.
	 *
	 * @param integerNumberRange value to be converted
	 * @return {@link ShortNumberRange} value
	 */
	@Nonnull
	public static ShortNumberRange toShortNumberRange(@Nonnull GrpcIntegerNumberRange integerNumberRange) {
		final boolean fromSet = integerNumberRange.hasFrom();
		final boolean toSet = integerNumberRange.hasTo();
		if (!fromSet && toSet) {
			return ShortNumberRange.to(NumberUtils.convertToShort(integerNumberRange.getTo().getValue()));
		} else if (fromSet && !toSet) {
			return ShortNumberRange.from(NumberUtils.convertToShort(integerNumberRange.getFrom().getValue()));
		}
		return ShortNumberRange.between(
			NumberUtils.convertToShort(integerNumberRange.getFrom().getValue()),
			NumberUtils.convertToShort(integerNumberRange.getTo().getValue())
		);
	}

	@Nonnull
	public static ShortNumberRange[] toShortNumberRangeArray(@Nonnull GrpcIntegerNumberRangeArray arrayValue) {
		return arrayValue.getValueList().stream().map(EvitaDataTypesConverter::toShortNumberRange).toArray(ShortNumberRange[]::new);
	}

	/**
	 * This method is used to convert {@link GrpcIntegerNumberRange} to {@link ByteNumberRange}.
	 *
	 * @param integerNumberRange value to be converted
	 * @return {@link ByteNumberRange} value
	 */
	@Nonnull
	public static ByteNumberRange toByteNumberRange(@Nonnull GrpcIntegerNumberRange integerNumberRange) {
		final boolean fromSet = integerNumberRange.hasFrom();
		final boolean toSet = integerNumberRange.hasTo();
		if (!fromSet && toSet) {
			return ByteNumberRange.to(NumberUtils.convertToByte(integerNumberRange.getTo().getValue()));
		} else if (fromSet && !toSet) {
			return ByteNumberRange.from(NumberUtils.convertToByte(integerNumberRange.getFrom().getValue()));
		}
		return ByteNumberRange.between(
			NumberUtils.convertToByte(integerNumberRange.getFrom().getValue()),
			NumberUtils.convertToByte(integerNumberRange.getTo().getValue())
		);
	}

	@Nonnull
	public static ByteNumberRange[] toByteNumberRangeArray(@Nonnull GrpcIntegerNumberRangeArray arrayValue) {
		return arrayValue.getValueList().stream().map(EvitaDataTypesConverter::toByteNumberRange).toArray(ByteNumberRange[]::new);
	}

	/**
	 * This method is used to convert {@link GrpcIntegerNumberRange} to {@link IntegerNumberRange}.
	 *
	 * @param integerNumberRange value to be converted
	 * @return {@link IntegerNumberRange} value
	 */
	@Nonnull
	public static IntegerNumberRange toIntegerNumberRange(@Nonnull GrpcIntegerNumberRange integerNumberRange) {
		final boolean fromSet = integerNumberRange.hasFrom();
		final boolean toSet = integerNumberRange.hasTo();
		if (!fromSet && toSet) {
			return IntegerNumberRange.to(integerNumberRange.getTo().getValue());
		} else if (fromSet && !toSet) {
			return IntegerNumberRange.from(integerNumberRange.getFrom().getValue());
		}
		return IntegerNumberRange.between(integerNumberRange.getFrom().getValue(), integerNumberRange.getTo().getValue());
	}

	@Nonnull
	public static IntegerNumberRange[] toIntegerNumberRangeArray(@Nonnull GrpcIntegerNumberRangeArray arrayValue) {
		return arrayValue.getValueList().stream().map(EvitaDataTypesConverter::toIntegerNumberRange).toArray(IntegerNumberRange[]::new);
	}

	/**
	 * This method is used to convert {@link GrpcLongNumberRange} to {@link LongNumberRange}.
	 *
	 * @param longNumberRange value to be converted
	 * @return {@link LongNumberRange} value
	 */
	@Nonnull
	public static LongNumberRange toLongNumberRange(@Nonnull GrpcLongNumberRange longNumberRange) {
		final boolean fromSet = longNumberRange.hasFrom();
		final boolean toSet = longNumberRange.hasTo();
		if (!fromSet && toSet) {
			return LongNumberRange.to(longNumberRange.getTo().getValue());
		} else if (fromSet && !toSet) {
			return LongNumberRange.from(longNumberRange.getFrom().getValue());
		}
		return LongNumberRange.between(longNumberRange.getFrom().getValue(), longNumberRange.getTo().getValue());
	}

	@Nonnull
	public static LongNumberRange[] toLongNumberRangeArray(@Nonnull GrpcLongNumberRangeArray arrayValue) {
		return arrayValue.getValueList().stream().map(EvitaDataTypesConverter::toLongNumberRange).toArray(LongNumberRange[]::new);
	}

	/**
	 * This method is used to convert {@link GrpcBigDecimalNumberRange} to {@link BigDecimalNumberRange}.
	 *
	 * @param bigDecimalNumberRange value to be converted
	 * @return {@link BigDecimalNumberRange} value
	 */
	@Nonnull
	public static BigDecimalNumberRange toBigDecimalNumberRange(@Nonnull GrpcBigDecimalNumberRange bigDecimalNumberRange) {
		final boolean fromSet = bigDecimalNumberRange.hasFrom();
		final boolean toSet = bigDecimalNumberRange.hasTo();
		final BigDecimal from = fromSet ? toBigDecimal(bigDecimalNumberRange.getFrom()) : BigDecimal.ZERO;
		final BigDecimal to = toSet ? toBigDecimal(bigDecimalNumberRange.getTo()) : BigDecimal.ZERO;
		if (!fromSet && toSet) {
			return BigDecimalNumberRange.to(to, bigDecimalNumberRange.getDecimalPlacesToCompare());
		} else if (fromSet && !toSet) {
			return BigDecimalNumberRange.from(from, bigDecimalNumberRange.getDecimalPlacesToCompare());
		}
		return BigDecimalNumberRange.between(from, to, bigDecimalNumberRange.getDecimalPlacesToCompare());
	}

	@Nonnull
	public static BigDecimalNumberRange[] toBigDecimalNumberRangeArray(@Nonnull GrpcBigDecimalNumberRangeArray arrayValue) {
		return arrayValue.getValueList().stream().map(EvitaDataTypesConverter::toBigDecimalNumberRange).toArray(BigDecimalNumberRange[]::new);
	}

	@Nonnull
	public static GrpcStringArray toGrpcStringArray(@Nonnull String[] stringArrayValues) {
		final GrpcStringArray.Builder valueBuilder = GrpcStringArray.newBuilder();
		Arrays.stream(stringArrayValues)
			.filter(Objects::nonNull)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	@Nonnull
	public static String toGrpcCharacter(Character characterValue) {
		return characterValue.toString();
	}

	@Nonnull
	public static GrpcStringArray toGrpcCharacterArray(@Nonnull char[] charArrayValues) {
		final GrpcStringArray.Builder valueBuilder = GrpcStringArray.newBuilder();
		for (char charValue : charArrayValues) {
			valueBuilder.addValue(String.valueOf(charValue));
		}
		return valueBuilder.build();
	}

	@Nonnull
	public static GrpcBooleanArray toGrpcBooleanArray(@Nonnull Boolean[] booleanArrayValues) {
		final GrpcBooleanArray.Builder valueBuilder = GrpcBooleanArray.newBuilder();
		Arrays.stream(booleanArrayValues)
			.filter(Objects::nonNull)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	@Nonnull
	public static GrpcBooleanArray toGrpcBooleanArray(@Nonnull boolean[] boolArrayValues) {
		final GrpcBooleanArray.Builder valueBuilder = GrpcBooleanArray.newBuilder();
		for (boolean boolValue : boolArrayValues) {
			valueBuilder.addValue(boolValue);
		}
		return valueBuilder.build();
	}

	@Nonnull
	public static GrpcLongArray toGrpcLongArray(@Nonnull Long[] longArrayValues) {
		final GrpcLongArray.Builder valueBuilder = GrpcLongArray.newBuilder();
		Arrays.stream(longArrayValues)
			.filter(Objects::nonNull)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	@Nonnull
	public static GrpcLongArray toGrpcLongArray(@Nonnull long[] longArrayValues) {
		final GrpcLongArray.Builder valueBuilder = GrpcLongArray.newBuilder();
		for (long longValue : longArrayValues) {
			valueBuilder.addValue(longValue);
		}
		return valueBuilder.build();
	}

	@Nonnull
	public static GrpcIntegerArray toGrpcByteArray(@Nonnull Byte[] byteArrayValues) {
		final GrpcIntegerArray.Builder valueBuilder = GrpcIntegerArray.newBuilder();
		Arrays.stream(byteArrayValues)
			.filter(Objects::nonNull)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	@Nonnull
	public static GrpcIntegerArray toGrpcByteArray(@Nonnull byte[] byteArrayValues) {
		final GrpcIntegerArray.Builder valueBuilder = GrpcIntegerArray.newBuilder();
		for (byte intValue : byteArrayValues) {
			valueBuilder.addValue(intValue);
		}
		return valueBuilder.build();
	}

	@Nonnull
	public static GrpcIntegerArray toGrpcShortArray(@Nonnull Short[] shortArrayValues) {
		final GrpcIntegerArray.Builder valueBuilder = GrpcIntegerArray.newBuilder();
		Arrays.stream(shortArrayValues)
			.filter(Objects::nonNull)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	@Nonnull
	public static GrpcIntegerArray toGrpcShortArray(@Nonnull short[] shortArrayValues) {
		final GrpcIntegerArray.Builder valueBuilder = GrpcIntegerArray.newBuilder();
		for (short intValue : shortArrayValues) {
			valueBuilder.addValue(intValue);
		}
		return valueBuilder.build();
	}

	@Nonnull
	public static GrpcIntegerArray toGrpcIntegerArray(@Nonnull Integer[] integerArrayValues) {
		final GrpcIntegerArray.Builder valueBuilder = GrpcIntegerArray.newBuilder();
		Arrays.stream(integerArrayValues)
			.filter(Objects::nonNull)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	@Nonnull
	public static GrpcIntegerArray toGrpcIntegerArray(@Nonnull int[] intArrayValues) {
		final GrpcIntegerArray.Builder valueBuilder = GrpcIntegerArray.newBuilder();
		for (int intValue : intArrayValues) {
			valueBuilder.addValue(intValue);
		}
		return valueBuilder.build();
	}

	@Nonnull
	public static GrpcStringArray toGrpcCharacterArray(@Nonnull Character[] characterArrayValues) {
		final GrpcStringArray.Builder valueBuilder = GrpcStringArray.newBuilder();
		Arrays.stream(characterArrayValues)
			.filter(Objects::nonNull)
			.map(Object::toString)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	/**
	 * This method is used to convert {@link BigDecimal} to {@link GrpcBigDecimal}.
	 *
	 * @param bigDecimal value to be converted
	 * @return {@link GrpcBigDecimal} value
	 */
	@Nonnull
	public static GrpcBigDecimal toGrpcBigDecimal(@Nonnull BigDecimal bigDecimal) {
		return GrpcBigDecimal.newBuilder()
			.setValueString(EvitaDataTypes.formatValue(bigDecimal))
			.build();
	}

	@Nonnull
	public static GrpcBigDecimalArray toGrpcBigDecimalArray(@Nonnull BigDecimal[] bigDecimalArrayValues) {
		final GrpcBigDecimalArray.Builder valueBuilder = GrpcBigDecimalArray.newBuilder();
		Arrays.stream(bigDecimalArrayValues)
			.filter(Objects::nonNull)
			.map(EvitaDataTypesConverter::toGrpcBigDecimal)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	/**
	 * This method is used to convert {@link OffsetDateTime} to {@link GrpcOffsetDateTime}.
	 *
	 * @param offsetDateTime value to be converted
	 * @return {@link GrpcOffsetDateTime} value
	 */
	@Nonnull
	public static GrpcOffsetDateTime toGrpcOffsetDateTime(@Nonnull OffsetDateTime offsetDateTime) {
		final String offset = offsetDateTime.getOffset().getId();
		final Instant dateTime;
		if (LocalDate.MIN.equals(offsetDateTime.toLocalDate())) {
			dateTime = GRPC_MIN_INSTANT;
		} else if (LocalDate.MAX.equals(offsetDateTime.toLocalDate())) {
			dateTime = GRPC_MAX_INSTANT;
		} else {
			dateTime = offsetDateTime.toInstant();
		}
		return GrpcOffsetDateTime.newBuilder()
			.setTimestamp(Timestamp.newBuilder()
				.setSeconds(dateTime.getEpochSecond())
				.setNanos(dateTime.getNano())
			)
			.setOffset(offset).build();
	}

	@Nonnull
	public static GrpcOffsetDateTimeArray toGrpcOffsetDateTimeArray(@Nonnull OffsetDateTime[] offsetDateTimeArrayValues) {
		final GrpcOffsetDateTimeArray.Builder valueBuilder = GrpcOffsetDateTimeArray.newBuilder();
		Arrays.stream(offsetDateTimeArrayValues)
			.filter(Objects::nonNull)
			.map(EvitaDataTypesConverter::toGrpcOffsetDateTime)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	/**
	 * This method is used to convert {@link LocalDateTime} to {@link GrpcOffsetDateTime}.
	 *
	 * @param localDateTime value to be converted
	 * @return {@link GrpcOffsetDateTime} value
	 */
	@Nonnull
	public static GrpcOffsetDateTime toGrpcLocalDateTime(@Nonnull LocalDateTime localDateTime) {
		final String offset = DEFAULT_ZONE_OFFSET.getId();
		final Instant dateTime;
		if (LocalDate.MIN.equals(localDateTime.toLocalDate())) {
			dateTime = GRPC_MIN_INSTANT;
		} else if (LocalDate.MAX.equals(localDateTime.toLocalDate())) {
			dateTime = GRPC_MAX_INSTANT;
		} else {
			dateTime = localDateTime.toInstant(DEFAULT_ZONE_OFFSET);
		}
		return GrpcOffsetDateTime.newBuilder()
			.setTimestamp(Timestamp.newBuilder()
				.setSeconds(dateTime.getEpochSecond())
				.setNanos(dateTime.getNano())
			)
			.setOffset(offset).build();
	}

	@Nonnull
	public static GrpcOffsetDateTimeArray toGrpcLocalDateTimeArray(@Nonnull LocalDateTime[] localDateTimeArrayValues) {
		final GrpcOffsetDateTimeArray.Builder valueBuilder = GrpcOffsetDateTimeArray.newBuilder();
		Arrays.stream(localDateTimeArrayValues)
			.filter(Objects::nonNull)
			.map(EvitaDataTypesConverter::toGrpcLocalDateTime)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	/**
	 * This method is used to convert {@link LocalDate} to {@link GrpcOffsetDateTime}.
	 *
	 * @param localDate value to be converted
	 * @return {@link GrpcOffsetDateTime} value
	 */
	@Nonnull
	public static GrpcOffsetDateTime toGrpcLocalDate(@Nonnull LocalDate localDate) {
		final Instant dateTime;
		if (LocalDate.MIN.equals(localDate)) {
			dateTime = GRPC_MIN_INSTANT;
		} else if (LocalDate.MAX.equals(localDate)) {
			dateTime = GRPC_MAX_INSTANT;
		} else {
			dateTime = LocalDateTime.of(localDate, LocalTime.MIDNIGHT).toInstant(DEFAULT_ZONE_OFFSET);
		}
		final String offset = DEFAULT_ZONE_OFFSET.getId();
		return GrpcOffsetDateTime.newBuilder()
			.setTimestamp(Timestamp.newBuilder()
				.setSeconds(dateTime.getEpochSecond())
				.setNanos(dateTime.getNano())
			)
			.setOffset(offset).build();
	}

	@Nonnull
	public static GrpcOffsetDateTimeArray toGrpcLocalDateArray(@Nonnull LocalDate[] localDateArrayValues) {
		final GrpcOffsetDateTimeArray.Builder valueBuilder = GrpcOffsetDateTimeArray.newBuilder();
		Arrays.stream(localDateArrayValues)
			.filter(Objects::nonNull)
			.map(EvitaDataTypesConverter::toGrpcLocalDate)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	/**
	 * This method is used to convert {@link LocalTime} to {@link GrpcOffsetDateTime}.
	 *
	 * @param localTime value to be converted
	 * @return {@link GrpcOffsetDateTime} value
	 */
	@Nonnull
	public static GrpcOffsetDateTime toGrpcLocalTime(@Nonnull LocalTime localTime) {
		final Instant time = LocalDateTime.of(LocalDate.of(0, 1, 1), localTime).toInstant(DEFAULT_ZONE_OFFSET);
		final String offset = DEFAULT_ZONE_OFFSET.getId();
		return GrpcOffsetDateTime.newBuilder()
			.setTimestamp(Timestamp.newBuilder()
				.setSeconds(time.getEpochSecond())
				.setNanos(time.getNano())
			)
			.setOffset(offset).build();
	}

	@Nonnull
	public static GrpcOffsetDateTimeArray toGrpcLocalTimeArray(@Nonnull LocalTime[] localeTimeArrayValues) {
		final GrpcOffsetDateTimeArray.Builder valueBuilder = GrpcOffsetDateTimeArray.newBuilder();
		Arrays.stream(localeTimeArrayValues)
			.filter(Objects::nonNull)
			.map(EvitaDataTypesConverter::toGrpcLocalTime)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	/**
	 * This method is used to convert {@link DateTimeRange} to {@link GrpcDateTimeRange}.
	 *
	 * @param dateTimeRange value to be converted
	 * @return {@link GrpcDateTimeRange} value
	 */
	@Nonnull
	public static GrpcDateTimeRange toGrpcDateTimeRange(@Nonnull DateTimeRange dateTimeRange) {
		final GrpcDateTimeRange.Builder grpcDateTimeRangeBuilder = GrpcDateTimeRange.newBuilder();
		if (dateTimeRange.getPreciseFrom() != null) {
			grpcDateTimeRangeBuilder.setFrom(toGrpcOffsetDateTime(dateTimeRange.getPreciseFrom()));
		}
		if (dateTimeRange.getPreciseTo() != null) {
			grpcDateTimeRangeBuilder.setTo(toGrpcOffsetDateTime(dateTimeRange.getPreciseTo()));
		}
		return grpcDateTimeRangeBuilder.build();
	}

	@Nonnull
	public static GrpcDateTimeRangeArray toGrpcDateTimeRangeArray(@Nonnull DateTimeRange[] dateTimeRangeArrayValues) {
		final GrpcDateTimeRangeArray.Builder valueBuilder = GrpcDateTimeRangeArray.newBuilder();
		Arrays.stream(dateTimeRangeArrayValues)
			.filter(Objects::nonNull)
			.map(EvitaDataTypesConverter::toGrpcDateTimeRange)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	/**
	 * This method is used to convert {@link BigDecimalNumberRange} to {@link GrpcBigDecimalNumberRange}.
	 *
	 * @param bigDecimalNumberRange value to be converted
	 * @return {@link GrpcBigDecimalNumberRange} value
	 */
	@Nonnull
	public static GrpcBigDecimalNumberRange toGrpcBigDecimalNumberRange(@Nonnull BigDecimalNumberRange bigDecimalNumberRange) {
		final GrpcBigDecimalNumberRange.Builder grpcBigDecimalRangeBuilder = GrpcBigDecimalNumberRange.newBuilder();
		if (bigDecimalNumberRange.getPreciseFrom() != null) {
			grpcBigDecimalRangeBuilder.setFrom(toGrpcBigDecimal(bigDecimalNumberRange.getPreciseFrom()));
		}
		if (bigDecimalNumberRange.getPreciseTo() != null) {
			grpcBigDecimalRangeBuilder.setTo(toGrpcBigDecimal(bigDecimalNumberRange.getPreciseTo()));
		}
		final Integer retainedDecimalPlaces = bigDecimalNumberRange.getRetainedDecimalPlaces();
		grpcBigDecimalRangeBuilder.setDecimalPlacesToCompare(retainedDecimalPlaces == null ? 0 : retainedDecimalPlaces);
		return grpcBigDecimalRangeBuilder.build();
	}

	@Nonnull
	public static GrpcBigDecimalNumberRangeArray toGrpcBigDecimalNumberRangeArray(@Nonnull BigDecimalNumberRange[] bigDecimalNumberRangeArrayValues) {
		final GrpcBigDecimalNumberRangeArray.Builder valueBuilder = GrpcBigDecimalNumberRangeArray.newBuilder();
		Arrays.stream(bigDecimalNumberRangeArrayValues)
			.filter(Objects::nonNull)
			.map(EvitaDataTypesConverter::toGrpcBigDecimalNumberRange)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	/**
	 * This method is used to convert {@link LongNumberRange} to {@link GrpcLongNumberRange}.
	 *
	 * @param longNumberRange value to be converted
	 * @return {@link GrpcLongNumberRange} value
	 */
	@Nonnull
	public static GrpcLongNumberRange toGrpcLongNumberRange(@Nonnull LongNumberRange longNumberRange) {
		final GrpcLongNumberRange.Builder grpcLongRangeBuilder = GrpcLongNumberRange.newBuilder();
		if (longNumberRange.getPreciseFrom() != null) {
			grpcLongRangeBuilder.setFrom(Int64Value.newBuilder().setValue(longNumberRange.getPreciseFrom()).build());
		}
		if (longNumberRange.getPreciseTo() != null) {
			grpcLongRangeBuilder.setTo(Int64Value.newBuilder().setValue(longNumberRange.getPreciseTo()).build());
		}
		return grpcLongRangeBuilder.build();
	}

	@Nonnull
	public static GrpcLongNumberRangeArray toGrpcLongNumberRangeArray(@Nonnull LongNumberRange[] longNumberRangeArrayValues) {
		final GrpcLongNumberRangeArray.Builder valueBuilder = GrpcLongNumberRangeArray.newBuilder();
		Arrays.stream(longNumberRangeArrayValues)
			.filter(Objects::nonNull)
			.map(EvitaDataTypesConverter::toGrpcLongNumberRange)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	/**
	 * This method is used to convert {@link NumberRange <T>} to {@link GrpcIntegerNumberRange}. As {@code <T>} should be passed either {@link Integer}, {@link Short} or {@link Byte}.
	 *
	 * @param numberRange value to be converted
	 * @param <T>         type of the number
	 * @return {@link GrpcIntegerNumberRange} value
	 */
	@Nonnull
	public static <T extends Number> GrpcIntegerNumberRange toGrpcIntegerNumberRange(@Nonnull NumberRange<T> numberRange) {
		final GrpcIntegerNumberRange.Builder grpcIntegerRangeBuilder = GrpcIntegerNumberRange.newBuilder();
		if (numberRange.getPreciseFrom() != null) {
			grpcIntegerRangeBuilder.setFrom(Int32Value.newBuilder().setValue(NumberUtils.convertToInt(numberRange.getPreciseFrom())).build());
		}
		if (numberRange.getPreciseTo() != null) {
			grpcIntegerRangeBuilder.setTo(Int32Value.newBuilder().setValue(NumberUtils.convertToInt(numberRange.getPreciseTo())).build());
		}
		return grpcIntegerRangeBuilder.build();
	}

	@Nonnull
	public static GrpcIntegerNumberRangeArray toGrpcIntegerNumberRangeArray(@Nonnull IntegerNumberRange[] integerNumberRangeArrayValues) {
		final GrpcIntegerNumberRangeArray.Builder valueBuilder = GrpcIntegerNumberRangeArray.newBuilder();
		Arrays.stream(integerNumberRangeArrayValues)
			.filter(Objects::nonNull)
			.map(EvitaDataTypesConverter::toGrpcIntegerNumberRange)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	@Nonnull
	public static GrpcIntegerNumberRangeArray toGrpcByteNumberRangeArray(@Nonnull ByteNumberRange[] byteNumberRangeArrayValues) {
		final GrpcIntegerNumberRangeArray.Builder valueBuilder = GrpcIntegerNumberRangeArray.newBuilder();
		Arrays.stream(byteNumberRangeArrayValues)
			.filter(Objects::nonNull)
			.map(EvitaDataTypesConverter::toGrpcIntegerNumberRange)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	@Nonnull
	public static GrpcIntegerNumberRangeArray toGrpcShortNumberRangeArray(@Nonnull ShortNumberRange[] shortNumberRangeArrayValues) {
		final GrpcIntegerNumberRangeArray.Builder valueBuilder = GrpcIntegerNumberRangeArray.newBuilder();
		Arrays.stream(shortNumberRangeArrayValues)
			.filter(Objects::nonNull)
			.map(EvitaDataTypesConverter::toGrpcIntegerNumberRange)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}


	/**
	 * This method is used to convert a {@link Locale} to {@link GrpcLocale}.
	 *
	 * @param locale value to be converted
	 * @return {@link GrpcLocale} value
	 */
	@Nonnull
	public static GrpcLocale toGrpcLocale(@Nonnull Locale locale) {
		return GrpcLocale.newBuilder()
			.setLanguageTag(locale.toLanguageTag())
			.build();
	}

	@Nonnull
	public static GrpcLocaleArray toGrpcLocaleArray(@Nonnull Locale[] localeArrayValues) {
		final GrpcLocaleArray.Builder valueBuilder = GrpcLocaleArray.newBuilder();
		Arrays.stream(localeArrayValues)
			.filter(Objects::nonNull)
			.map(EvitaDataTypesConverter::toGrpcLocale)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	/**
	 * This method is used to convert a {@link Currency} to {@link GrpcCurrency}.
	 *
	 * @param currency value to be converted
	 * @return {@link GrpcCurrency} value
	 */
	@Nonnull
	public static GrpcCurrency toGrpcCurrency(@Nonnull Currency currency) {
		return GrpcCurrency.newBuilder().setCode(currency.getCurrencyCode()).build();
	}

	@Nonnull
	public static GrpcCurrencyArray toGrpcCurrencyArray(@Nonnull Currency[] currencyArrayValues) {
		final GrpcCurrencyArray.Builder valueBuilder = GrpcCurrencyArray.newBuilder();
		Arrays.stream(currencyArrayValues)
			.filter(Objects::nonNull)
			.map(EvitaDataTypesConverter::toGrpcCurrency)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	/**
	 * This method is used to convert a {@link UUID} to {@link GrpcUuid}.
	 *
	 * @param uuid value to be converted
	 * @return {@link GrpcUuid} value
	 */
	@Nonnull
	public static GrpcUuid toGrpcUuid(@Nonnull UUID uuid) {
		return GrpcUuid.newBuilder()
			.setMostSignificantBits(uuid.getMostSignificantBits())
			.setLeastSignificantBits(uuid.getLeastSignificantBits())
			.build();
	}

	@Nonnull
	public static GrpcUuidArray toGrpcUuidArray(@Nonnull UUID[] uuidArrayValues) {
		final GrpcUuidArray.Builder valueBuilder = GrpcUuidArray.newBuilder();
		Arrays.stream(uuidArrayValues)
			.filter(Objects::nonNull)
			.map(EvitaDataTypesConverter::toGrpcUuid)
			.forEach(valueBuilder::addValue);
		return valueBuilder.build();
	}

	/**
	 * This method is used to convert a {@link Predecessor} to {@link GrpcPredecessor}.
	 *
	 * @param predecessor value to be converted
	 * @return {@link GrpcPredecessor} value
	 */
	@Nonnull
	public static GrpcPredecessor toGrpcPredecessor(@Nonnull Predecessor predecessor) {
		if (predecessor.isHead()) {
			return GrpcPredecessor.newBuilder()
				.setHead(true)
				.build();
		} else {
			return GrpcPredecessor.newBuilder()
				.setPredecessorId(Int32Value.of(predecessor.predecessorPk()))
				.build();
		}
	}

	/**
	 * This method is used to convert a {@link ReferencedEntityPredecessor} to {@link GrpcPredecessor}.
	 *
	 * @param predecessor value to be converted
	 * @return {@link GrpcPredecessor} value
	 */
	@Nonnull
	public static GrpcPredecessor toGrpcPredecessor(@Nonnull ReferencedEntityPredecessor predecessor) {
		if (predecessor.isHead()) {
			return GrpcPredecessor.newBuilder()
				.setHead(true)
				.build();
		} else {
			return GrpcPredecessor.newBuilder()
				.setPredecessorId(Int32Value.of(predecessor.predecessorPk()))
				.build();
		}
	}

	/**
	 * This method is used to convert a {@link TaskStatus} to {@link GrpcTaskStatus}.
	 *
	 * @param taskStatus task status to be converted
	 * @return {@link GrpcTaskStatus} instance
	 */
	@Nonnull
	public static GrpcTaskStatus toGrpcTaskStatus(@Nonnull TaskStatus<?, ?> taskStatus) {
		final Builder builder = GrpcTaskStatus.newBuilder()
			.setTaskType(taskStatus.taskType())
			.setTaskName(taskStatus.taskName())
			.setTaskId(toGrpcUuid(taskStatus.taskId()))
			.setCreated(toGrpcOffsetDateTime(taskStatus.created()))
			.setSimplifiedState(EvitaEnumConverter.toGrpcSimplifiedStatus(taskStatus.simplifiedState()))
			.setProgress(taskStatus.progress());
		ofNullable(taskStatus.catalogName())
			.ifPresent(
				catalogName -> builder.setCatalogName(
					StringValue.newBuilder()
						.setValue(catalogName)
						.build()
				)
			);
		ofNullable(taskStatus.issued())
			.ifPresent(issued -> builder.setIssued(toGrpcOffsetDateTime(issued)));
		ofNullable(taskStatus.started())
			.ifPresent(started -> builder.setStarted(toGrpcOffsetDateTime(started)));
		ofNullable(taskStatus.finished())
			.ifPresent(finished -> builder.setFinished(toGrpcOffsetDateTime(finished)));
		ofNullable(taskStatus.settings())
			.ifPresent(settings -> builder.setSettings(StringValue.newBuilder().setValue(settings.toString()).build()));
		ofNullable(taskStatus.result())
			.ifPresent(
				result -> {
					if (result instanceof FileForFetch fileForFetch) {
						builder.setFile(toGrpcFile(fileForFetch));
					} else {
						builder.setText(StringValue.newBuilder().setValue(result.toString()).build());
					}
				}
			);
		ofNullable(taskStatus.publicExceptionMessage())
			.ifPresent(
				publicExceptionMessage -> builder.setException(
					StringValue.newBuilder()
						.setValue(publicExceptionMessage)
						.build()
				)
			);
		taskStatus.traits()
			.stream()
			.map(EvitaEnumConverter::toGrpcTaskTrait)
			.forEach(builder::addTrait);
		return builder.build();
	}

	/**
	 * This method is used to convert a {@link GrpcTaskStatus} to {@link TaskStatus}.
	 *
	 * @param taskStatus task status to be converted
	 * @return {@link TaskStatus} instance
	 */
	@Nonnull
	public static TaskStatus<?, ?> toTaskStatus(@Nonnull GrpcTaskStatus taskStatus) {
		return new TaskStatus<>(
			taskStatus.getTaskType(),
			taskStatus.getTaskName(),
			toUuid(taskStatus.getTaskId()),
			taskStatus.hasCatalogName() ? taskStatus.getCatalogName().getValue() : null,
			toOffsetDateTime(taskStatus.getCreated()),
			taskStatus.hasIssued() ? EvitaDataTypesConverter.toOffsetDateTime(taskStatus.getIssued()) : null,
			taskStatus.hasStarted() ? EvitaDataTypesConverter.toOffsetDateTime(taskStatus.getStarted()) : null,
			taskStatus.hasFinished() ? EvitaDataTypesConverter.toOffsetDateTime(taskStatus.getFinished()) : null,
			taskStatus.getProgress(),
			taskStatus.hasSettings() ? taskStatus.getSettings().getValue() : "",
			taskStatus.hasFile() ?
				EvitaDataTypesConverter.toFileForFetch(taskStatus.getFile()) :
				taskStatus.hasText() ? taskStatus.getText().getValue() : null,
			taskStatus.hasException() ? taskStatus.getException().getValue() : null,
			null,
			EnumSet.copyOf(
				taskStatus.getTraitList()
					.stream()
					.map(EvitaEnumConverter::toTaskTrait)
					.toList()
			)
		);
	}

	/**
	 * This method is used to convert a {@link FileForFetch} to {@link GrpcFile}.
	 *
	 * @param fileForFetch file to be converted
	 * @return {@link GrpcFile} instance
	 */
	@Nonnull
	public static GrpcFile toGrpcFile(@Nonnull FileForFetch fileForFetch) {
		final GrpcFile.Builder builder = GrpcFile.newBuilder()
			.setFileId(toGrpcUuid(fileForFetch.fileId()))
			.setName(fileForFetch.name())
			.setContentType(fileForFetch.contentType())
			.setTotalSizeInBytes(fileForFetch.totalSizeInBytes())
			.setCreated(toGrpcOffsetDateTime(fileForFetch.created()));
		ofNullable(fileForFetch.description())
			.ifPresent(description -> builder.setDescription(StringValue.newBuilder().setValue(description).build()));
		ofNullable(fileForFetch.origin())
			.ifPresent(origin -> builder.setOrigin(StringValue.newBuilder().setValue(String.join(",", origin)).build()));
		return builder.build();
	}

	/**
	 * This method is used to convert a {@link GrpcFile} to {@link FileForFetch}.
	 * @param grpcFile file to be converted
	 * @return {@link FileForFetch} instance
	 */
	@Nonnull
	public static FileForFetch toFileForFetch(@Nonnull GrpcFile grpcFile) {
		return new FileForFetch(
			toUuid(grpcFile.getFileId()),
			grpcFile.getName(),
			grpcFile.hasDescription() ? grpcFile.getDescription().getValue() : null,
			grpcFile.getContentType(),
			grpcFile.getTotalSizeInBytes(),
			toOffsetDateTime(grpcFile.getCreated()),
			grpcFile.hasOrigin() ? grpcFile.getOrigin().getValue().split(",") : null
		);
	}

	/**
	 * This method is used to convert a {@link CatalogStatistics} to {@link GrpcCatalogStatistics}.
	 * @param catalogStatistics catalog statistics to be converted
	 * @return {@link GrpcCatalogStatistics} instance
	 */
	@Nonnull
	public static GrpcCatalogStatistics toGrpcCatalogStatistics(@Nonnull CatalogStatistics catalogStatistics) {
		final GrpcCatalogStatistics.Builder builder = GrpcCatalogStatistics.newBuilder()
			.setCatalogName(catalogStatistics.catalogName())
			.setCorrupted(catalogStatistics.unusable())
			.setUnusable(catalogStatistics.unusable())
			.setReadOnly(catalogStatistics.readOnly())
			.setCatalogVersion(catalogStatistics.catalogVersion())
			.setTotalRecords(catalogStatistics.totalRecords())
			.setIndexCount(catalogStatistics.indexCount())
			.setSizeOnDiskInBytes(catalogStatistics.sizeOnDiskInBytes())
			.addAllEntityCollectionStatistics(
				Arrays.stream(catalogStatistics.entityCollectionStatistics())
					.filter(Objects::nonNull)
					.map(EvitaDataTypesConverter::toGrpcEntityCollectionStatistics)
					.collect(Collectors.toList())
			);
		if (catalogStatistics.catalogState() != null) {
			builder.setCatalogState(EvitaEnumConverter.toGrpcCatalogState(catalogStatistics.catalogState()));
		} else {
			builder.setCatalogState(GrpcCatalogState.UNKNOWN_CATALOG_STATE);
		}
		if (catalogStatistics.catalogId() != null) {
			builder.setCatalogId(toGrpcUuid(catalogStatistics.catalogId()));
		}
		return builder
			.build();
	}

	/**
	 * This method is used to convert a {@link EntityCollectionStatistics} to {@link GrpcEntityCollectionStatistics}.
	 * @param entityCollectionStatistics entity collection statistics to be converted
	 * @return {@link GrpcEntityCollectionStatistics} instance
	 */
	@Nonnull
	public static GrpcEntityCollectionStatistics toGrpcEntityCollectionStatistics(@Nonnull EntityCollectionStatistics entityCollectionStatistics) {
		return GrpcEntityCollectionStatistics.newBuilder()
			.setEntityType(entityCollectionStatistics.entityType())
			.setTotalRecords(entityCollectionStatistics.totalRecords())
			.setIndexCount(entityCollectionStatistics.indexCount())
			.setSizeOnDiskInBytes(entityCollectionStatistics.sizeOnDiskInBytes())
			.build();
	}

	/**
	 * This method is used to convert a {@link GrpcCatalogStatistics} to {@link CatalogStatistics}.
	 * @param grpcCatalogStatistics catalog statistics to be converted
	 * @return {@link CatalogStatistics} instance
	 */
	@Nonnull
	public static CatalogStatistics toCatalogStatistics(@Nonnull GrpcCatalogStatistics grpcCatalogStatistics) {
		return new CatalogStatistics(
			grpcCatalogStatistics.hasCatalogId() ? toUuid(grpcCatalogStatistics.getCatalogId()) : null,
			grpcCatalogStatistics.getCatalogName(),
			grpcCatalogStatistics.getUnusable(),
			grpcCatalogStatistics.getReadOnly(),
			EvitaEnumConverter.toCatalogState(grpcCatalogStatistics.getCatalogState()),
			grpcCatalogStatistics.getCatalogVersion(),
			grpcCatalogStatistics.getTotalRecords(),
			grpcCatalogStatistics.getIndexCount(),
			grpcCatalogStatistics.getSizeOnDiskInBytes(),
			grpcCatalogStatistics.getEntityCollectionStatisticsList().stream()
				.map(EvitaDataTypesConverter::toEntityCollectionStatistics)
				.toArray(EntityCollectionStatistics[]::new)

		);
	}

	/**
	 * This method is used to convert a {@link GrpcEntityCollectionStatistics} to {@link EntityCollectionStatistics}.
	 * @param grpcEntityCollectionStatistics entity collection statistics to be converted
	 * @return {@link EntityCollectionStatistics} instance
	 */
	@Nonnull
	public static EntityCollectionStatistics toEntityCollectionStatistics(@Nonnull GrpcEntityCollectionStatistics grpcEntityCollectionStatistics) {
		return new EntityCollectionStatistics(
			grpcEntityCollectionStatistics.getEntityType(),
			grpcEntityCollectionStatistics.getTotalRecords(),
			grpcEntityCollectionStatistics.getIndexCount(),
			grpcEntityCollectionStatistics.getSizeOnDiskInBytes()
		);
	}

	/**
	 * Converts {@link NamingConvention} to {@link GrpcNamingConvention}.
	 * @param namingConvention naming convention to be converted
	 * @param nameVariant name variant to be converted
	 * @return built instance of {@link GrpcNameVariant}
	 */
	@Nonnull
	public static GrpcNameVariant toGrpcNameVariant(@Nonnull NamingConvention namingConvention, @Nonnull String nameVariant) {
		return GrpcNameVariant.newBuilder()
			.setNamingConvention(EvitaEnumConverter.toGrpcNamingConvention(namingConvention))
			.setName(nameVariant)
			.build();
	}

	/**
	 * TOBEDONE #538 - remove this enum when all clients are `2025.4` or newer
	 */
	public enum AssociatedDataForm {
		/**
		 * Deprecated form of passing associated data.
		 */
		JSON,
		/**
		 * New form of passing associated data.
		 */
		STRUCTURED_VALUE

	}

}
