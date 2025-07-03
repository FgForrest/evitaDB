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

package io.evitadb.externalApi.rest.api.resolver.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ValueNode;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.externalApi.rest.api.openApi.OpenApiConstants;
import io.evitadb.externalApi.rest.api.openApi.SchemaUtils;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.exception.RestQueryResolvingInternalError;
import io.evitadb.externalApi.rest.exception.RestTooManyValuesPresentException;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import lombok.RequiredArgsConstructor;

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
import java.util.UUID;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Deserializes data using OpenAPI schema
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class DataDeserializer {
	private final OpenAPI openApi;
	private final Map<String, Class<? extends Enum<?>>> enumMapping;

	/**
	 * Deserializes value from string array. It can return single object, array or range object.
	 *
	 * @throws RestTooManyValuesPresentException is thrown when single object is required by schema but data array
	 * contains more than one value.
	 */
	@SuppressWarnings({"rawtypes"})
	@Nullable
	public Object deserializeValue(@Nonnull Schema schema, @Nonnull String[] data) {
		if (data.length == 0) {
			final Class schemaClass = resolveDataClass(SchemaUtils.getTargetSchemaFromRefOrOneOf(schema.getItems(), this.openApi));
			return Array.newInstance(schemaClass, 0);
		}
		if (OpenApiConstants.TYPE_ARRAY.equals(schema.getType())) {
			if(OpenApiConstants.FORMAT_RANGE.equals(schema.getFormat())) {
				return deserializeRange(resolveDataClass(SchemaUtils.getTargetSchemaFromRefOrOneOf(schema.getItems(), this.openApi)), data, schema.getName());
			}
			return deserializeArray(schema, data);
		} else {
			if (data.length > 1) {
				throw new RestTooManyValuesPresentException("Expected one value of parameter " + schema.getName() + " but found: " + data.length);
			}
			return deserializeValue(schema, data[0]);
		}
	}

	/**
	 * Deserializes value from {@link JsonNode}. It can return single value, array or range object.
	 */
	public Object deserializeValue(@Nonnull Schema<?> schema, @Nonnull JsonNode jsonNode) {
		if((jsonNode.isArray() && jsonNode.isEmpty()) || jsonNode.asText() == null) {
			final Class<?> schemaClass = resolveDataClass(SchemaUtils.getTargetSchemaFromRefOrOneOf(schema.getItems(), this.openApi));
			return Array.newInstance(schemaClass, 0);
		}
		if(OpenApiConstants.TYPE_ARRAY.equals(schema.getType())) {
			if(OpenApiConstants.FORMAT_RANGE.equals(schema.getFormat())) {
				return deserializeRange(resolveDataClass(SchemaUtils.getTargetSchemaFromRefOrOneOf(schema.getItems(), this.openApi)), jsonNode, schema.getName());
			}
			return deserializeArray(schema, getNodeValuesAsStringArray(jsonNode, schema.getName()));
		} else {
			return deserializeValue(schema, jsonNode.asText());
		}
	}

	/**
	 * Deserialize value from JsonNode.
	 *
	 * @throws RestInternalError when Class ob object is not among supported classes for deserialization
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public static <T extends Serializable> T deserializeValue(@Nonnull Class<T> targetClass, @Nullable JsonNode value) {
		if (value == null || value.isNull()) {
			return null;
		}

		if (targetClass.isArray()) {
			if(value instanceof ArrayNode arrayNode) {
				return (T) deserializeArray((Class<? extends Serializable>) targetClass.getComponentType(), arrayNode);
			} else {
				throw new RestInternalError("Target class is array but json node is not instance of ArrayNode. " + targetClass.getName());
			}
		}

		if (Character.class.isAssignableFrom(targetClass) || char.class.isAssignableFrom(targetClass)) {
			return (T) Character.valueOf(value.asText().charAt(0));
		} else if (Integer.class.isAssignableFrom(targetClass) || int.class.isAssignableFrom(targetClass)) {
			return (T) Integer.valueOf(value.intValue());
		} else if (Short.class.isAssignableFrom(targetClass) || short.class.isAssignableFrom(targetClass)) {
			return (T) Short.valueOf(value.shortValue());
		} else if (Boolean.class.isAssignableFrom(targetClass) || boolean.class.isAssignableFrom(targetClass)) {
			return (T) Boolean.valueOf(value.booleanValue());
		} else if (Byte.class.isAssignableFrom(targetClass) || byte.class.isAssignableFrom(targetClass)) {
			return (T) deserializeByteNumber(value);
		} else if (String.class.isAssignableFrom(targetClass) ||
			OffsetDateTime.class.isAssignableFrom(targetClass) ||
			LocalDateTime.class.isAssignableFrom(targetClass) ||
			LocalDate.class.isAssignableFrom(targetClass) ||
			LocalTime.class.isAssignableFrom(targetClass) ||
			Currency.class.isAssignableFrom(targetClass) ||
			UUID.class.isAssignableFrom(targetClass) ||
			BigDecimal.class.isAssignableFrom(targetClass) ||
			Long.class.isAssignableFrom(targetClass) ||
			long.class.isAssignableFrom(targetClass) ||
			Locale.class.isAssignableFrom(targetClass)) {
			return EvitaDataTypes.toTargetType(value.asText(), targetClass);
		} else if (Predecessor.class.isAssignableFrom(targetClass)) {
			return (T) new Predecessor(value.intValue());
		} else if (ReferencedEntityPredecessor.class.isAssignableFrom(targetClass)) {
			return (T) new ReferencedEntityPredecessor(value.intValue());
		} else if (Expression.class.isAssignableFrom(targetClass)) {
			return (T) ExpressionFactory.parse(value.asText());
		} else if (targetClass.isEnum()) {
			return deserializeEnum(targetClass, value);
		}
		throw new RestInternalError("Deserialization of field of JavaType: " + targetClass.getSimpleName() + " is not implemented yet.");
	}

	/**
	 * Deserializes objects in array represented by {@link ArrayNode}
	 * @throws RestInvalidArgumentException is thrown when JsonNode is not instance of ArrayNode or when schema type
	 * is not {@link OpenApiConstants#TYPE_ARRAY}.
	 */
	@Nonnull
	public Object[] deserializeArray(@Nonnull Schema<?> schema, @Nonnull JsonNode jsonNode) {
		if(!OpenApiConstants.TYPE_ARRAY.equals(schema.getType())) {
			throw new RestInvalidArgumentException("Can't deserialize value, schema type is not array. Name: " + schema.getName());
		}

		return deserializeArray(schema, getNodeValuesAsStringArray(jsonNode, schema.getName()));
	}

	/**
	 * Deserializes {@link JsonNode} and its content according provided OpenAPI {@link Schema}.
	 *
	 * @return single object or map of objects when as keys are used property names
	 */
	@Nullable
	public Object deserializeTree(@Nonnull Schema<?> schema, @Nonnull JsonNode jsonNode) {
		if (jsonNode instanceof NullNode) {
			return null;
		} else if (schema.getAnyOf() != null) {
			for (final Schema<?> possibleSchema : schema.getAnyOf()) {
				try {
					return deserializeTree(possibleSchema, jsonNode);
				} catch (RestInvalidArgumentException e) {
					// not the schema the input data used, lets try another one
				}
			}

			// no match for any of the possible schemas
			throw new RestInvalidArgumentException(
				"Expected `Any` for schema `" + schema.getName() + "` but got `" + jsonNode.getClass().getSimpleName() + "`.",
				"Expected Any object."
			);
		} else if (schema instanceof ArraySchema || OpenApiConstants.TYPE_ARRAY.equals(schema.getType())) {
			Assert.isTrue(
				jsonNode instanceof ArrayNode,
				() -> new RestInvalidArgumentException(
					"Expected `ArrayNode` for schema `" + schema.getName() + "` but got `" + jsonNode.getClass().getSimpleName() + "`.",
					"Expected JSON array."
				)
			);
			final ArrayNode arrayNode = (ArrayNode) jsonNode;

			final ArrayList<Object> objects = new ArrayList<>(arrayNode.size());
			for (JsonNode node : arrayNode) {
				objects.add(deserializeTree(SchemaUtils.getTargetSchemaFromRefOrOneOf(schema.getItems(), this.openApi), node));
			}
			return objects;
		} else if (schema.getType() == null || OpenApiConstants.TYPE_OBJECT.equals(schema.getType())) {
			Assert.isTrue(
				jsonNode instanceof ObjectNode,
				() -> new RestInvalidArgumentException(
					"Expected `ObjectNode` for schema `" + schema.getName() + "` but got `" + jsonNode.getClass().getSimpleName() + "`.",
					"Expected JSON object."
				)
			);
			final ObjectNode objectNode = (ObjectNode) jsonNode;

			final Map<String, Object> dataMap = createHashMap(objectNode.size());
			final Iterator<String> namesIterator = objectNode.fieldNames();
			while (namesIterator.hasNext()) {
				final String fieldName = namesIterator.next();
				final Schema<?> propertySchema = schema.getProperties().get(fieldName);
				if (propertySchema != null) {
					final Schema<?> targetPropertySchema = SchemaUtils.getTargetSchemaFromRefOrOneOf(propertySchema, this.openApi);
					dataMap.put(fieldName, deserializeTree(targetPropertySchema, objectNode.get(fieldName)));
				} else {
					throw new RestInvalidArgumentException("Invalid property name: " + fieldName);
				}
			}
			return dataMap;
		} else {
			Assert.isTrue(
				jsonNode instanceof ValueNode,
				() -> new RestInvalidArgumentException(
					"Expected `ValueNode` for schema `" + schema.getName() + "` but got `" + jsonNode.getClass().getSimpleName() + "`.",
					"Expected JSON value."
				)
			);
			return deserializeValue(schema, jsonNode);
		}
	}

	/**
	 * Deserializes the given data string into an object of the type specified by the provided schema.
	 *
	 * @param schema the schema that defines the type to which the data should be deserialized
	 * @param data the string representation of the data to be deserialized
	 * @return the deserialized object based on the provided schema and data
	 */
	@Nullable
	@SuppressWarnings({"rawtypes"})
	private Object deserializeValue(@Nonnull Schema schema, @Nonnull String data) {
		return deserializeValue(resolveDataClass(schema), data);
	}

	/**
	 * Deserializes a string representation of data into an object of the specified type.
	 *
	 * @param <T> the type of the object to be deserialized, must extend Serializable
	 * @param requestedType the class of the type to which the data should be deserialized, must not be null
	 * @param data the string representation of the data to be deserialized, must not be null
	 * @return an instance of the specified type containing the deserialized data
	 * @throws NullPointerException if the deserialization result is null (when applicable)
	 */
	@Nullable
	private static <T extends Serializable> T deserializeValue(@Nonnull Class<T> requestedType, @Nonnull String data) {
		if (requestedType.isEnum()) {
			return deserializeEnum(requestedType, data);
		} else if (Expression.class.isAssignableFrom(requestedType)) {
			//noinspection unchecked
			return (T) ExpressionFactory.parse(data);
		} else {
			return EvitaDataTypes.toTargetType(data, requestedType);
		}
	}

	/**
	 * Deserializes an array of string data into an array of objects of the specified type defined in the schema.
	 *
	 * @param schema the schema defining the type of the array's elements
	 * @param data the array of string data to deserialize
	 * @return an array of objects deserialized from the input string data based on the provided schema
	 */
	@Nonnull
	private Object[] deserializeArray(@Nonnull Schema<?> schema, @Nonnull String[] data) {
		final Class<?> arrayClass = resolveDataClass(SchemaUtils.getTargetSchemaFromRefOrOneOf(schema.getItems(), this.openApi));
		final Object[] dataArray = (Object[]) Array.newInstance(arrayClass, data.length);
		for (int i = 0; i < data.length; i++) {
			dataArray[i] = deserializeValue(SchemaUtils.getTargetSchemaFromRefOrOneOf(schema.getItems(), this.openApi), data[i]);
		}
		return dataArray;
	}

	/**
	 * Deserializes an array of objects from an {@link ArrayNode} into an array of the specified target class type.
	 *
	 * @param <T> the type of elements in the deserialized array, which extends {@link Serializable}
	 * @param targetClass the class type of the elements in the resulting array, must not be null
	 * @param arrayNode the {@link ArrayNode} containing the data to deserialize, must not be null
	 * @return an array of type {@code T}, containing the deserialized elements
	 * @throws RestInternalError if deserialization of elements into the target class is not supported
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static <T extends Serializable> T[] deserializeArray(@Nonnull Class<T> targetClass, @Nonnull ArrayNode arrayNode) {
		final Object deserialized = Array.newInstance(targetClass, arrayNode.size());
		for (int i = 0; i < arrayNode.size(); i++) {
			 Array.set(deserialized, i, deserializeValue(targetClass, arrayNode.get(i)));
		}
		return (T[]) deserialized;
	}

	/**
	 * Deserializes a JSON node representing a range into the specified target class type.
	 * The range value must be represented as an array of two elements.
	 *
	 * @param targetClass the class type of the target object to deserialize into. It must be a type that extends {@link Serializable}.
	 * @param value the JSON node representing the range, which must be an array with exactly two elements.
	 * @param attributeName the name of the attribute being deserialized, used for error messages.
	 * @param <T> the type of the object to be returned, which extends {@link Serializable}.
	 *
	 * @return the deserialized object of the specified type representing the range.
	 *
	 * @throws RestInternalError if the input value is not a valid array of two elements or
	 *                           if the target class type deserialization is not implemented.
	 */
	@Nonnull
	private static <T extends Serializable> T deserializeRange(
		@Nonnull Class<T> targetClass,
		@Nonnull JsonNode value,
		@Nonnull String attributeName
	) {
		if (value instanceof ArrayNode values && values.size() == 2) {
			if (OffsetDateTime.class.isAssignableFrom(targetClass)) {
				return deserializeRange(targetClass, deserializeValue(OffsetDateTime.class, values.get(0)), deserializeValue(OffsetDateTime.class, values.get(1)), attributeName);
			} else if (BigDecimal.class.isAssignableFrom(targetClass)) {
				return deserializeRange(targetClass, deserializeValue(BigDecimal.class, values.get(0)), deserializeValue(BigDecimal.class, values.get(1)), attributeName);
			} else if (Byte.class.isAssignableFrom(targetClass)) {
				return deserializeRange(targetClass, deserializeValue(Byte.class, values.get(0)), deserializeValue(Byte.class, values.get(1)), attributeName);
			} else if (Short.class.isAssignableFrom(targetClass)) {
				return deserializeRange(targetClass, deserializeValue(Short.class, values.get(0)), deserializeValue(Short.class, values.get(1)), attributeName);
			} else if (Integer.class.isAssignableFrom(targetClass)) {
				return deserializeRange(targetClass, deserializeValue(Integer.class, values.get(0)), deserializeValue(Integer.class, values.get(1)), attributeName);
			} else if (Long.class.isAssignableFrom(targetClass)) {
				return deserializeRange(targetClass, deserializeValue(Long.class, values.get(0)), deserializeValue(Long.class, values.get(1)), attributeName);
			}
			throw new RestInternalError("Deserialization of range JavaType: " + targetClass.getSimpleName() + " is not implemented yet. Attribute: " + attributeName);
		}
		throw new RestInternalError("Array of two values is required for range data type. Attribute: " + attributeName);
	}

	/**
	 * Deserializes a range object from an array of two string values based on the target class type.
	 *
	 * @param targetClass the class of the target type to which the range is to be deserialized; must not be null
	 * @param values an array of two string values representing the range; must not be null and must contain exactly two elements
	 * @param attributeName the name of the attribute to provide context for error handling; must not be null
	 * @param <T> the type parameter that extends Serializable and represents the target type
	 * @return an instance of the target type representing the deserialized range
	 * @throws RestInternalError if the target class is unsupported or if the input array does not contain exactly two elements
	 */
	@Nonnull
	private static <T extends Serializable> T deserializeRange(
		@Nonnull Class<T> targetClass,
		@Nonnull String[] values,
		@Nonnull String attributeName
	) {
		if (values.length == 2) {
			if (OffsetDateTime.class.isAssignableFrom(targetClass)) {
				return deserializeRange(targetClass, deserializeValue(OffsetDateTime.class, values[0]), deserializeValue(OffsetDateTime.class, values[1]), attributeName);
			} else if (BigDecimal.class.isAssignableFrom(targetClass)) {
				return deserializeRange(targetClass, deserializeValue(BigDecimal.class, values[0]), deserializeValue(BigDecimal.class, values[1]), attributeName);
			} else if (Byte.class.isAssignableFrom(targetClass)) {
				return deserializeRange(targetClass, deserializeValue(Byte.class, values[0]), deserializeValue(Byte.class, values[1]), attributeName);
			} else if (Short.class.isAssignableFrom(targetClass)) {
				return deserializeRange(targetClass, deserializeValue(Short.class, values[0]), deserializeValue(Short.class, values[1]), attributeName);
			} else if (Integer.class.isAssignableFrom(targetClass)) {
				return deserializeRange(targetClass, deserializeValue(Integer.class, values[0]), deserializeValue(Integer.class, values[1]), attributeName);
			} else if (Long.class.isAssignableFrom(targetClass)) {
				return deserializeRange(targetClass, deserializeValue(Long.class, values[0]), deserializeValue(Long.class, values[1]), attributeName);
			}
			throw new RestInternalError("Deserialization of range JavaType: " + targetClass.getSimpleName() +
				" is not implemented yet. Attribute: " + attributeName);
		}
		throw new RestInternalError("Array of two values is required for range data type. Attribute: " + attributeName);
	}

	/**
	 * Deserializes a range object from "from" and "to" values based on the target class type.
	 *
	 * @param targetClass the class of the target type to which the range is to be deserialized
	 * @param from the starting value of the range (nullable)
	 * @param to the ending value of the range (nullable)
	 * @param attributeName the name of the attribute to provide context for error handling
	 * @return an instance of the target class representing the deserialized range
	 * @throws RestInternalError if the target class is unsupported or both "from" and "to" are null
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static <T extends Serializable> T deserializeRange(
		@Nonnull Class<T> targetClass,
		@Nullable Object from,
		@Nullable Object to,
		@Nonnull String attributeName
	) {
		if (from != null && to != null) {
			if (OffsetDateTime.class.isAssignableFrom(targetClass)) {
				return (T) DateTimeRange.between((OffsetDateTime) from, (OffsetDateTime) to);
			} else if (BigDecimal.class.isAssignableFrom(targetClass)) {
				return (T) BigDecimalNumberRange.between((BigDecimal) from, (BigDecimal) to);
			} else if (Byte.class.isAssignableFrom(targetClass)) {
				return (T) ByteNumberRange.between((Byte) from, (Byte) to);
			} else if (Short.class.isAssignableFrom(targetClass)) {
				return (T) ShortNumberRange.between((Short) from, (Short) to);
			} else if (Integer.class.isAssignableFrom(targetClass)) {
				return (T) IntegerNumberRange.between((Integer) from, (Integer) to);
			} else if (Long.class.isAssignableFrom(targetClass)) {
				return (T) LongNumberRange.between((Long) from, (Long) to);
			}
			throw new RestInternalError("Deserialization of range JavaType: " + targetClass.getSimpleName() +
				" is not implemented yet. Attribute: " + attributeName);
		} else if(from != null) {
			if (OffsetDateTime.class.isAssignableFrom(targetClass)) {
				return (T) DateTimeRange.since((OffsetDateTime) from);
			} else if (BigDecimal.class.isAssignableFrom(targetClass)) {
				return (T) BigDecimalNumberRange.from((BigDecimal) from);
			} else if (Byte.class.isAssignableFrom(targetClass)) {
				return (T) ByteNumberRange.from((Byte) from);
			} else if (Short.class.isAssignableFrom(targetClass)) {
				return (T) ShortNumberRange.from((Short) from);
			} else if (Integer.class.isAssignableFrom(targetClass)) {
				return (T) IntegerNumberRange.from((Integer) from);
			} else if (Long.class.isAssignableFrom(targetClass)) {
				return (T) LongNumberRange.from((Long) from);
			}
			throw new RestInternalError("Deserialization of range JavaType: " + targetClass.getSimpleName() +
				" is not implemented yet. Attribute: " + attributeName);
		} else if(to != null) {
			if (OffsetDateTime.class.isAssignableFrom(targetClass)) {
				return (T) DateTimeRange.until((OffsetDateTime) to);
			} else if (BigDecimal.class.isAssignableFrom(targetClass)) {
				return (T) BigDecimalNumberRange.to((BigDecimal) to);
			} else if (Byte.class.isAssignableFrom(targetClass)) {
				return (T) ByteNumberRange.to((Byte) to);
			} else if (Short.class.isAssignableFrom(targetClass)) {
				return (T) ShortNumberRange.to((Short) to);
			} else if (Integer.class.isAssignableFrom(targetClass)) {
				return (T) IntegerNumberRange.to((Integer) to);
			} else if (Long.class.isAssignableFrom(targetClass)) {
				return (T) LongNumberRange.to((Long) to);
			}
			throw new RestInternalError("Deserialization of range JavaType: " + targetClass.getSimpleName() +
				" is not implemented yet. Attribute: " + attributeName);
		}
		throw new RestInternalError("Both values for range data type are null which is not allowed. Attribute: " + attributeName);
	}

	/**
	 * Deserializes a JSON node into a byte value. The method extracts the text content
	 * from the given JSON node and attempts to deserialize it as a single byte.
	 *
	 * @param value the JSON node containing the value to be deserialized, must not be null
	 * @return the deserialized byte value, or null if the input cannot be deserialized
	 */
	@Nullable
	private static Byte deserializeByteNumber(@Nonnull JsonNode value) {
		return deserializeByteNumber(value.asText());
	}

	/**
	 * Deserializes a Base64-encoded string into a single byte value.
	 * This method decodes the provided string and ensures that the resulting byte array
	 * contains exactly one byte. If the byte array is empty, the method returns null.
	 * If the byte array has more than one byte, an exception is thrown.
	 *
	 * @param value the Base64-encoded string to be deserialized, must not be null
	 * @return the deserialized byte value, or null if the input is encoded as an empty byte array
	 * @throws RestQueryResolvingInternalError if the decoded byte array contains more than one byte
	 */
	@Nullable
	private static Byte deserializeByteNumber(@Nonnull String value) {
		final byte[] decoded = Base64.getDecoder().decode(value);
		if(decoded.length == 1) {
			return decoded[0];
		} else if(decoded.length == 0) {
			return null;
		} else {
			throw new RestQueryResolvingInternalError("Byte value must be always single byte not array of bytes.");
		}
	}

	/**
	 * Deserializes a JSON node into an enum constant of the specified target class.
	 *
	 * @param targetClass the class of the enum to which the value should be deserialized, must not be null
	 * @param value the JSON node containing the string representation of the enum constant, must not be null
	 * @param <T> the target type that extends Serializable
	 * @param <E> the enum type of the target class
	 * @return the deserialized enum constant of the specified type
	 * @throws IllegalArgumentException if the provided value does not match any of the constants of the target enum
	 */
	@Nonnull
	private static <T extends Serializable, E extends Enum<E>> T deserializeEnum(
		@Nonnull Class<T> targetClass,
		@Nonnull JsonNode value
	) {
		//noinspection unchecked
		return (T) Enum.valueOf((Class<E>) targetClass, value.asText());
	}

	/**
	 * Deserializes a string value into an enum constant of the specified target class.
	 *
	 * @param targetClass the class of the enum to which the value should be deserialized, must not be null
	 * @param value the string representation of the enum constant to be deserialized, must not be null
	 * @return the deserialized enum constant of the specified type
	 * @param <T> the target type that extends Serializable
	 * @param <E> the enum type of the target class
	 * @throws IllegalArgumentException if the specified value does not match any of the constants of the target enum
	 */
	@Nonnull
	private static <T extends Serializable, E extends Enum<E>> T deserializeEnum(
		@Nonnull Class<T> targetClass,
		@Nonnull String value
	) {
		//noinspection unchecked
		return (T) Enum.valueOf((Class<E>) targetClass, value);
	}

	/**
	 * Resolves the Java class corresponding to the given schema's type and format.
	 * This method maps OpenAPI schema definitions for primitive and standard types
	 * to equivalent Java classes that implement {@link Serializable}.
	 *
	 * @param schema the OpenAPI schema for which the corresponding data class needs to be resolved; must not be null
	 * @return the resolved Java class that corresponds to the specified schema; never null
	 * @throws RestInternalError if the schema's type or format is unknown or unsupported
	 */
	@Nonnull
	@SuppressWarnings("rawtypes")
	private Class<? extends Serializable> resolveDataClass(@Nonnull Schema schema) {
		if(OpenApiConstants.TYPE_STRING.equals(schema.getType())) {
			if(schema.getFormat() == null) {
				if (schema.getEnum() != null) {
					final Class<? extends Enum<?>> enumTemplate = this.enumMapping.get(schema.getName());
					Assert.isPremiseValid(
						enumTemplate != null,
						() -> new RestInternalError("No Java enum for enum `" + schema.getName() + "` found.")
					);
					return enumTemplate;
				}
				return String.class;
			}
			return switch (schema.getFormat()) {
				case OpenApiConstants.FORMAT_DATE -> LocalDate.class;
				case OpenApiConstants.FORMAT_DATE_TIME -> OffsetDateTime.class;
				case OpenApiConstants.FORMAT_LOCAL_TIME -> LocalTime.class;
				case OpenApiConstants.FORMAT_LOCAL_DATE_TIME -> LocalDateTime.class;
				case OpenApiConstants.FORMAT_CURRENCY -> Currency.class;
				case OpenApiConstants.FORMAT_UUID -> UUID.class;
				case OpenApiConstants.FORMAT_LOCALE -> Locale.class;
				case OpenApiConstants.FORMAT_CHAR -> Character.class;
				case OpenApiConstants.FORMAT_DECIMAL -> BigDecimal.class;
				case OpenApiConstants.FORMAT_INT_64 -> Long.class;
				case OpenApiConstants.FORMAT_EXPRESSION -> Expression.class;
				default -> throw new RestInternalError("Unknown schema format " + schema.getFormat() + " for String type.");
			};
		}
		if(OpenApiConstants.TYPE_INTEGER.equals(schema.getType())) {
			return switch (schema.getFormat()) {
				case OpenApiConstants.FORMAT_INT_32 -> Integer.class;
				case OpenApiConstants.FORMAT_INT_16 -> Short.class;
				case OpenApiConstants.FORMAT_BYTE -> Byte.class;
				default ->
					throw new RestInternalError("Unknown schema format " + schema.getFormat() + " for Integer type.");
			};
		}
		if(OpenApiConstants.TYPE_BOOLEAN.equals(schema.getType())) {
			return Boolean.class;
		}
		throw new RestInternalError("Unknown schema type " + schema.getType());
	}

	/**
	 * Extracts the string values from the specified JSON node, assuming the node is of type {@link ArrayNode}.
	 * Each element in the array is converted to its string representation.
	 * If the provided JSON node is not an {@link ArrayNode}, a {@link RestInvalidArgumentException} is thrown.
	 *
	 * @param jsonNode the JSON node from which string values need to be extracted, must not be null
	 * @param attributeName the name of the attribute (used for error handling in case of invalid JSON node type), must not be null
	 * @return an array of strings containing text values of the elements in the provided {@link ArrayNode}
	 * @throws RestInvalidArgumentException if the provided JSON node is not an instance of {@link ArrayNode}
	 */
	@Nonnull
	private static String[] getNodeValuesAsStringArray(@Nonnull JsonNode jsonNode, @Nonnull String attributeName) {
		if(jsonNode instanceof ArrayNode arrayNode) {
			final String[] strings = new String[arrayNode.size()];
			for (int i = 0; i < arrayNode.size(); i++) {
				strings[i] = arrayNode.get(i).asText();
			}
			return strings;
		}
		throw new RestInvalidArgumentException("Can't get array of string if JsonNode is not instance of ArrayNode. Class: " + jsonNode.getClass().getSimpleName(),
			"Expecting array but getting single value. Attribute name: " + attributeName);
	}

}
