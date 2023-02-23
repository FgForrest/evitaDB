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

package io.evitadb.externalApi.rest.api.catalog.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator;
import io.evitadb.externalApi.rest.exception.RESTApiInternalError;
import io.evitadb.externalApi.rest.exception.RESTApiInvalidArgumentException;
import io.evitadb.externalApi.rest.exception.RESTApiQueryResolvingInternalError;
import io.evitadb.externalApi.rest.exception.RESTApiTooManyValuesPresentException;
import io.evitadb.externalApi.rest.io.SchemaUtils;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Currency;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Deserializes data using OpenAPI schema
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DataDeserializer {

	/**
	 * Deserializes data from string array. It can return single object, array or range object.
	 *
	 * @throws RESTApiTooManyValuesPresentException is thrown when single object is required by schema but data array
	 * contains more than one value.
	 */
	@SuppressWarnings({"rawtypes"})
	@Nullable
	public static Object deserialize(@Nonnull OpenAPI openAPI,  @Nonnull Schema schema, @Nonnull String[] data) {
		if(data.length == 0) {
			final Class schemaClass = resolveDataClass(SchemaUtils.getTargetSchemaFromRefOrOneOf(schema.getItems(), openAPI));
			return Array.newInstance(schemaClass, 0);
		}
		if(SchemaCreator.TYPE_ARRAY.equals(schema.getType())) {
			if(SchemaCreator.FORMAT_RANGE.equals(schema.getFormat())) {
				return deserializeRange(resolveDataClass(SchemaUtils.getTargetSchemaFromRefOrOneOf(schema.getItems(), openAPI)), data, schema.getName());
			}
			return deserializeArray(openAPI, schema, data);
		} else {
			if(data.length > 1) {
				throw new RESTApiTooManyValuesPresentException("Expected one value of parameter " + schema.getName() + " but found: " + data.length);
			}
			return deserialize(schema, data[0]);
		}
	}

	/**
	 * Deserializes data from {@link JsonNode}. It can return single object, array or range object.
	 */
	public static Object deserialize(@Nonnull OpenAPI openAPI, @Nonnull Schema<?> schema, @Nonnull JsonNode jsonNode) {
		if((jsonNode.isArray() && jsonNode.isEmpty()) || jsonNode.asText() == null) {
			final Class<?> schemaClass = resolveDataClass(SchemaUtils.getTargetSchemaFromRefOrOneOf(schema.getItems(), openAPI));
			return Array.newInstance(schemaClass, 0);
		}
		if(SchemaCreator.TYPE_ARRAY.equals(schema.getType())) {
			if(SchemaCreator.FORMAT_RANGE.equals(schema.getFormat())) {
				return deserializeRange(resolveDataClass(SchemaUtils.getTargetSchemaFromRefOrOneOf(schema.getItems(), openAPI)), jsonNode, schema.getName());
			}
			return deserializeArray(openAPI, schema, getNodeValuesAsStringArray(jsonNode, schema.getName()));
		} else {
			return deserialize(schema, jsonNode.asText());
		}
	}

	/**
	 * Deserializes {@link JsonNode} and its content according provided OpenAPI {@link Schema}.
	 *
	 * @return single object or map of objects when as keys are used property names
	 */
	@Nullable
	public static Object deserializeJsonNodeTree(@Nonnull OpenAPI openAPI, @Nonnull Schema<?> schema, @Nonnull JsonNode jsonNode) {
		if(jsonNode instanceof NullNode) {
			return null;
		}
		if(jsonNode instanceof ArrayNode arrayNode) {
			if(schema instanceof ArraySchema arraySchema) {
				final ArrayList<Object> objects = new ArrayList<>(arrayNode.size());
				for (JsonNode node : arrayNode) {
					objects.add(deserializeJsonNodeTree(openAPI, SchemaUtils.getTargetSchemaFromRefOrOneOf(arraySchema.getItems(), openAPI), node));
				}
				return objects;
			}
			else {
				throw new RESTApiInvalidArgumentException("Can't parse data form an ArrayNode when schema is not an ArraySchema. " +
					"Schema: " + schema.getName(), "Error when parsing data.");
			}
		} else {
			if(schema instanceof ArraySchema) {
				throw new RESTApiInvalidArgumentException("Can't parse data form an JsonNode when schema is an ArraySchema but JsonNode " +
					"is not an ArrayNode. Schema: " + schema.getName(), "Error when parsing data.");
			}

			if(schema.getType() == null || SchemaCreator.TYPE_OBJECT.equals(schema.getType())) {
				final Map<String, Object> dataMap = createHashMap(20);
				final Iterator<String> namesIterator = jsonNode.fieldNames();
				while (namesIterator.hasNext()) {
					final String fieldName = namesIterator.next();
					final Schema<?> propertySchema = schema.getProperties().get(fieldName);
					if (propertySchema != null) {
						final Schema<?> targetPropertySchema = SchemaUtils.getTargetSchemaFromRefOrOneOf(propertySchema, openAPI);
						dataMap.put(fieldName, deserializeJsonNodeTree(openAPI, targetPropertySchema, jsonNode.get(fieldName)));
					} else {
						throw new RESTApiInvalidArgumentException("Invalid property name: " + fieldName);
					}
				}
				return dataMap;
			} else {
				return DataDeserializer.deserialize(openAPI, schema, jsonNode);
			}
		}
	}

	/**
	 * Deserializes objects in array represented by {@link ArrayNode}
	 * @throws RESTApiInvalidArgumentException is thrown when JsonNode is not instance of ArrayNode or when schema type
	 * is not {@link SchemaCreator#TYPE_ARRAY}.
	 */
	public static Object[] deserializeArray(@Nonnull OpenAPI openAPI, @Nonnull Schema<?> schema, @Nonnull JsonNode jsonNode) {
		if(!SchemaCreator.TYPE_ARRAY.equals(schema.getType())) {
			throw new RESTApiInvalidArgumentException("Can't deserialize value, schema type is not array. Name: " + schema.getName());
		}

		return deserializeArray(openAPI, schema, getNodeValuesAsStringArray(jsonNode, schema.getName()));
	}

	private static Object[] deserializeArray(@Nonnull OpenAPI openAPI, @Nonnull Schema<?> schema, @Nonnull String[] data) {
		final Class<?> arrayClass = resolveDataClass(SchemaUtils.getTargetSchemaFromRefOrOneOf(schema.getItems(), openAPI));
		final Object[] dataArray = (Object[]) Array.newInstance(arrayClass, data.length);
		for (int i = 0; i < data.length; i++) {
			dataArray[i] = deserialize(SchemaUtils.getTargetSchemaFromRefOrOneOf(schema.getItems(), openAPI), data[i]);
		}
		return dataArray;
	}

	@SuppressWarnings({"rawtypes"})
	private static Object deserialize(@Nonnull Schema schema, @Nonnull String data) {
		return deserialize(resolveDataClass(schema), data);
	}

	private static <T extends Serializable> T deserialize(@Nonnull Class<T> requestedType, @Nonnull String data) {
		return EvitaDataTypes.toTargetType(data, requestedType);
	}

	private static String[] getNodeValuesAsStringArray(@Nonnull JsonNode jsonNode, @Nonnull String attributeName) {
		if(jsonNode instanceof ArrayNode arrayNode) {
			final String[] strings = new String[arrayNode.size()];
			for (int i = 0; i < arrayNode.size(); i++) {
				strings[i] = arrayNode.get(i).asText();
			}
			return strings;
		}
		throw new RESTApiInvalidArgumentException("Can't get array of string if JsonNode is not instance of ArrayNode. Class: " + jsonNode.getClass().getSimpleName(),
			"Expecting array but getting single value. Attribute name: " + attributeName);
	}

	@SuppressWarnings("rawtypes")
	private static Class<? extends Serializable> resolveDataClass(@Nonnull Schema schema) {
		if(SchemaCreator.TYPE_STRING.equals(schema.getType())) {
			if(schema.getFormat() == null) {
				return String.class;
			}
			return switch (schema.getFormat()) {
				case SchemaCreator.FORMAT_DATE -> LocalDate.class;
				case SchemaCreator.FORMAT_DATE_TIME -> OffsetDateTime.class;
				case SchemaCreator.FORMAT_LOCAL_TIME -> LocalTime.class;
				case SchemaCreator.FORMAT_LOCAL_DATE_TIME -> LocalDateTime.class;
				case SchemaCreator.FORMAT_CURRENCY -> Currency.class;
				case SchemaCreator.FORMAT_LOCALE -> Locale.class;
				case SchemaCreator.FORMAT_CHAR -> Character.class;
				case SchemaCreator.FORMAT_DECIMAL -> BigDecimal.class;
				case SchemaCreator.FORMAT_INT_64 -> Long.class;
				default -> throw new RESTApiInternalError("Unknown schema format " + schema.getFormat() + " for String type.");
			};
		}
		if(SchemaCreator.TYPE_INTEGER.equals(schema.getType())) {
			if(schema.getFormat() == null) {
				return Integer.class;
			} else if(schema.getFormat().equals(SchemaCreator.FORMAT_INT_16)) {
				return Short.class;
			} else if(schema.getFormat().equals(SchemaCreator.FORMAT_BYTE)) {
				return Byte.class;
			} else {
				throw new RESTApiInternalError("Unknown schema format " + schema.getFormat() + " for Integer type.");
			}
		}
		if(SchemaCreator.TYPE_BOOLEAN.equals(schema.getType())) {
			return Boolean.class;
		}
		throw new RESTApiInternalError("Unknown schema type " + schema.getType());
	}

	/**
	 * Deserialize value from JsonNode.
	 *
	 * @throws RESTApiInternalError when Class ob object is not among supported classes for deserialization
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public static <T extends Serializable> T deserializeObject(@Nonnull Class<T> targetClass, @Nullable JsonNode value) {
		if(value == null || value.isNull()) {
			return null;
		}
		if(targetClass.isArray()) {
			if(value instanceof ArrayNode arrayNode) {
				return (T) deserializeArray((Class<? extends Serializable>) targetClass.getComponentType(), arrayNode);
			} else {
				throw new RESTApiInternalError("Target class is array but json node is not instance of ArrayNode. " + targetClass.getName());
			}
		}
		return switch (targetClass.getSimpleName()) {
			case "String" -> (T) value.asText();
			case "Character", "char" -> (T) Character.valueOf(value.asText().charAt(0));
			case "Integer", "int" -> (T) Integer.valueOf(value.intValue());
			case "Short", "short" -> (T) Short.valueOf(value.shortValue());
			case "Long", "long" -> (T) Long.valueOf(value.asText());
			case "Boolean", "boolean" -> (T) Boolean.valueOf(value.booleanValue());
			case "Byte", "byte" -> (T) deserializeByteNumber(value);
			case "BigDecimal" -> (T) new BigDecimal(value.asText());
			case "OffsetDateTime", "LocalDateTime", "LocalDate", "LocalTime", "Currency", "Locale" ->
				EvitaDataTypes.toTargetType(value.asText(), targetClass);
			case "AttributeSpecialValue" -> (T) deserializeAttributeSpecialValue(value);
			case "OrderDirection" -> (T) deserializeOrderDirection(value);
			case "FacetStatisticsDepth" -> (T) deserializeFacetStatisticsDepth(value);
			case "PriceContentMode" -> (T) deserializePriceContentMode(value);

			default ->
				throw new RESTApiInternalError("Deserialization of field of JavaType: " + targetClass.getSimpleName() + " is not implemented yet.");
		};
	}

	@SuppressWarnings("unchecked")
	private static <T extends Serializable> T[] deserializeArray(@Nonnull Class<T> targetClass, @Nonnull ArrayNode arrayNode) {
		final Object deserialized = Array.newInstance(targetClass, arrayNode.size());
		for (int i = 0; i < arrayNode.size(); i++) {
			 Array.set(deserialized, i, deserializeObject(targetClass, arrayNode.get(i)));
		}
		return (T[]) deserialized;
	}

	@Nonnull
	private static AttributeSpecialValue deserializeAttributeSpecialValue(@Nonnull JsonNode value) {
		return AttributeSpecialValue.valueOf(value.textValue());
	}

	@Nonnull
	private static OrderDirection deserializeOrderDirection(@Nonnull JsonNode value) {
		return Enum.valueOf(OrderDirection.class, value.asText());
	}

	@Nonnull
	private static FacetStatisticsDepth deserializeFacetStatisticsDepth(@Nonnull JsonNode value) {
		return Enum.valueOf(FacetStatisticsDepth.class, value.asText());
	}

	@Nonnull
	private static PriceContentMode deserializePriceContentMode(@Nonnull JsonNode value) {
		return Enum.valueOf(PriceContentMode.class, value.asText());
	}

	@Nullable
	private static Byte deserializeByteNumber(@Nonnull JsonNode value) {
		return deserializeByteNumber(value.asText());
	}

	@Nullable
	private static Byte deserializeByteNumber(@Nonnull String value) {
		final byte[] decoded = Base64.getDecoder().decode(value);
		if(decoded.length == 1) {
			return decoded[0];
		} else if(decoded.length == 0) {
			return null;
		} else {
			throw new RESTApiQueryResolvingInternalError("Byte value must be always single byte not array of bytes.");
		}
	}

	@Nonnull
	private static <T extends Serializable> T deserializeRange(@Nonnull Class<T> targetClass, @Nonnull JsonNode value, @Nonnull String attributeName) {
		if (value instanceof ArrayNode values && values.size() == 2) {
			return switch (targetClass.getSimpleName()) {
				case "OffsetDateTime" -> deserializeRange(targetClass, deserializeObject(OffsetDateTime.class, values.get(0)), deserializeObject(OffsetDateTime.class, values.get(1)), attributeName);
				case "BigDecimal" -> deserializeRange(targetClass, deserializeObject(BigDecimal.class, values.get(0)), deserializeObject(BigDecimal.class, values.get(1)), attributeName);
				case "Byte" -> deserializeRange(targetClass, deserializeObject(Byte.class, values.get(0)), deserializeObject(Byte.class, values.get(1)), attributeName);
				case "Short" -> deserializeRange(targetClass, deserializeObject(Short.class, values.get(0)), deserializeObject(Short.class, values.get(1)), attributeName);
				case "Integer" -> deserializeRange(targetClass, deserializeObject(Integer.class, values.get(0)), deserializeObject(Integer.class, values.get(1)), attributeName);
				case "Long" -> deserializeRange(targetClass, deserializeObject(Long.class, values.get(0)), deserializeObject(Long.class, values.get(1)), attributeName);

				default ->
					throw new RESTApiInternalError("Deserialization of range JavaType: " + targetClass.getSimpleName() +
						" is not implemented yet. Attribute: " + attributeName);
			};
		}
		throw new RESTApiInternalError("Array of two values is required for range data type. Attribute: " + attributeName);
	}

	@Nonnull
	private static <T extends Serializable> T deserializeRange(@Nonnull Class<T> targetClass, @Nonnull String[] values, @Nonnull String attributeName) {
		if (values.length == 2) {
			return switch (targetClass.getSimpleName()) {
				case "OffsetDateTime" -> deserializeRange(targetClass, deserialize(OffsetDateTime.class, values[0]), deserialize(OffsetDateTime.class, values[1]), attributeName);
				case "BigDecimal" -> deserializeRange(targetClass, deserialize(BigDecimal.class, values[0]), deserialize(BigDecimal.class, values[1]), attributeName);
				case "Byte" -> deserializeRange(targetClass, deserialize(Byte.class, values[0]), deserialize(Byte.class, values[1]), attributeName);
				case "Short" -> deserializeRange(targetClass, deserialize(Short.class, values[0]), deserialize(Short.class, values[1]), attributeName);
				case "Integer" -> deserializeRange(targetClass, deserialize(Integer.class, values[0]), deserialize(Integer.class, values[1]), attributeName);
				case "Long" -> deserializeRange(targetClass, deserialize(Long.class, values[0]), deserialize(Long.class, values[1]), attributeName);

				default ->
					throw new RESTApiInternalError("Deserialization of range JavaType: " + targetClass.getSimpleName() +
						" is not implemented yet. Attribute: " + attributeName);
			};
		}
		throw new RESTApiInternalError("Array of two values is required for range data type. Attribute: " + attributeName);
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	private static <T extends Serializable> T deserializeRange(@Nonnull Class<T> targetClass, @Nullable Object from, @Nullable Object to, @Nonnull String attributeName) {
		if (from != null && to != null) {
			return switch (targetClass.getSimpleName()) {
				case "OffsetDateTime" -> (T) DateTimeRange.between((OffsetDateTime) from, (OffsetDateTime) to);
				case "BigDecimal" -> (T) BigDecimalNumberRange.between((BigDecimal) from, (BigDecimal) to);
				case "Byte" -> (T) ByteNumberRange.between((Byte) from, (Byte) to);
				case "Short" -> (T) ShortNumberRange.between((Short) from, (Short) to);
				case "Integer" -> (T) IntegerNumberRange.between((Integer) from, (Integer) to);
				case "Long" -> (T) LongNumberRange.between((Long) from, (Long) to);

				default ->
					throw new RESTApiInternalError("Deserialization of range JavaType: " + targetClass.getSimpleName() +
						" is not implemented yet. Attribute: " + attributeName);
			};
		} else if(from != null) {
			return switch (targetClass.getSimpleName()) {
				case "OffsetDateTime" -> (T) DateTimeRange.since((OffsetDateTime) from);
				case "BigDecimal" -> (T) BigDecimalNumberRange.from((BigDecimal) from);
				case "Byte" -> (T) ByteNumberRange.from((Byte) from);
				case "Short" -> (T) ShortNumberRange.from((Short) from);
				case "Integer" -> (T) IntegerNumberRange.from((Integer) from);
				case "Long" -> (T) LongNumberRange.from((Long) from);

				default ->
					throw new RESTApiInternalError("Deserialization of range JavaType: " + targetClass.getSimpleName() +
						" is not implemented yet. Attribute: " + attributeName);
			};
		} else if(to != null) {
			return switch (targetClass.getSimpleName()) {
				case "OffsetDateTime" -> (T) DateTimeRange.until((OffsetDateTime) to);
				case "BigDecimal" -> (T) BigDecimalNumberRange.to((BigDecimal) to);
				case "Byte" -> (T) ByteNumberRange.to((Byte) to);
				case "Short" -> (T) ShortNumberRange.to((Short) to);
				case "Integer" -> (T) IntegerNumberRange.to((Integer) to);
				case "Long" -> (T) LongNumberRange.to((Long) to);

				default ->
					throw new RESTApiInternalError("Deserialization of range JavaType: " + targetClass.getSimpleName() +
						" is not implemented yet. Attribute: " + attributeName);
			};
		}
		throw new RESTApiInternalError("Both values for range data type are null which is not allowed. Attribute: " + attributeName);
	}

}
