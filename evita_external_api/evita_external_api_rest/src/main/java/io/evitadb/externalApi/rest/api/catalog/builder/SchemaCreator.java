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

package io.evitadb.externalApi.rest.api.catalog.builder;

import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.dataType.Any;
import io.evitadb.externalApi.dataType.GenericObject;
import io.evitadb.externalApi.rest.api.catalog.DataTypeDescriptor;
import io.evitadb.externalApi.rest.exception.OpenApiInternalError;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
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
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import static io.evitadb.externalApi.rest.api.catalog.RestDataTypeDescriptor.*;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Contains utility methods to create instance of {@link Schema} class according to required data type. Only Classes
 * used by Evita are supported.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SchemaCreator {


	// todo lho private?
	public static final String TYPE_ARRAY = "array";
	public static final String TYPE_OBJECT = "object";
	public static final String TYPE_STRING = "string";
	public static final String TYPE_INTEGER = "integer";
	public static final String TYPE_BOOLEAN = "boolean";

	public static final String FORMAT_DATE_TIME = "date-time";
	public static final String FORMAT_LOCAL_DATE_TIME = "local-date-time";
	public static final String FORMAT_DATE = "date";
	public static final String FORMAT_LOCAL_TIME = "local-time";
	public static final String FORMAT_INT_16 = "int16";
	public static final String FORMAT_INT_64 = "int64";
	public static final String FORMAT_CURRENCY = "iso-4217";
	public static final String FORMAT_BYTE = "int8";
	public static final String FORMAT_CHAR = "char";
	public static final String FORMAT_DECIMAL = "decimal";
	public static final String FORMAT_LOCALE = "locale";
	public static final String FORMAT_RANGE = "range";



	/**
	 * Creates schema for {@link String}.
	 */
	@Nonnull
	public static Schema<Object> createStringSchema() {
		return createSchema(TYPE_STRING);
	}

	/**
	 * Creates schema for {@link OffsetDateTime}
	 */
	@Nonnull
	public static Schema<Object> createOffsetDateTimeSchema() {
		final var dateTimeSchema = createSchema(TYPE_STRING);
		dateTimeSchema
			.format(FORMAT_DATE_TIME)
			.example("2022-09-27T13:28:27.357442951+02:00");
		return dateTimeSchema;
	}

	/**
	 * Creates schema for {@link java.time.LocalDateTime}
	 */
	@Nonnull
	public static Schema<Object> createLocalDateTimeSchema() {
		final var dateTimeSchema = createSchema(TYPE_STRING);
		dateTimeSchema
			.format(FORMAT_LOCAL_DATE_TIME)
			.example("2022-09-27T13:28:27.357442951");
		return dateTimeSchema;
	}

	/**
	 * Creates schema for {@link java.time.LocalDate}
	 */
	@Nonnull
	public static Schema<Object> createLocalDateSchema() {
		final var dateTimeSchema = createSchema(TYPE_STRING);
		dateTimeSchema
			.format(FORMAT_DATE)
			.example("2022-09-27");
		return dateTimeSchema;
	}

	/**
	 * Creates schema for {@link java.time.LocalTime}
	 */
	@Nonnull
	public static Schema<Object> createLocalTimeSchema() {
		final var dateTimeSchema = createSchema(TYPE_STRING);
		dateTimeSchema
			.format(FORMAT_LOCAL_TIME)
			.example("13:28:27.357442951");
		return dateTimeSchema;
	}

	/**
	 * Creates schema for {@link Character}
	 */
	@Nonnull
	public static Schema<Object> createCharacterSchema() {
		final var characterSchema = createSchema(TYPE_STRING);
		characterSchema
			.minLength(1)
			.maxLength(1)
			.format(FORMAT_CHAR);
		return characterSchema;
	}

	/**
	 * Creates array schema.
	 */
	public static ArraySchema createArraySchemaOf(@Nonnull Schema<Object> schema) {
		final var array = new ArraySchema();
		array.items(schema);
		return array;
	}

	/**
	 * Creates schema for object. This can be used to create schema of any object with attributes.
	 */
	@Nonnull
	public static Schema<Object> createObjectSchema() {
		final Schema<Object> objectSchema = createSchema(TYPE_OBJECT);
		objectSchema.setProperties(new LinkedHashMap<>());
		return objectSchema;
	}

	/**
	 * Creates schema for {@link Integer}
	 */
	@Nonnull
	public static Schema<Object> createIntegerSchema() {
		return createSchema(TYPE_INTEGER);
	}

	/**
	 * Creates schema for {@link Short}
	 */
	@Nonnull
	public static Schema<Object> createShortSchema() {
		final var shortSchema = createSchema(TYPE_INTEGER);
		shortSchema
			.format(FORMAT_INT_16)
			.example(845);
		return shortSchema;
	}

	/**
	 * Creates schema for {@link Long}
	 */
	@Nonnull
	public static Schema<Object> createLongSchema() {
		final var longSchema = createSchema(TYPE_STRING);
		longSchema
			.format(FORMAT_INT_64)
			.example("685487");
		return longSchema;
	}

	/**
	 * Creates schema for {@link Boolean}
	 */
	@Nonnull
	public static Schema<Object> createBooleanSchema() {
		return createSchema(TYPE_BOOLEAN);
	}

	/**
	 * Creates schema for {@link BigDecimal}.<br/>
	 * <strong>string</strong> type is used for {@link BigDecimal} in order to keep precision of number.
	 */
	@Nonnull
	public static Schema<Object> createBigDecimalSchema() {
		final var bigDecimalSchema = createSchema(TYPE_STRING);
		bigDecimalSchema
			.pattern("d+([.]d+)?")
			.format(FORMAT_DECIMAL)
			.example("6584.25");
		return bigDecimalSchema;
	}

	/**
	 * Creates schema for {@link java.util.Locale}. Value must be string formatted as language tag.
	 */
	@Nonnull
	public static Schema<Object> createLocaleSchema() {
		final var localeSchema = createSchema(TYPE_STRING);
		localeSchema
			.format(FORMAT_LOCALE)
			.example("cs-CZ");
		return localeSchema;
	}

	@Nonnull
	public static Schema<Object> createEnumSchema(@Nonnull Class<?> javaEnumType) {
		//noinspection unchecked
		final Class<? extends Enum<?>> componentType = (Class<? extends Enum<?>>) (javaEnumType.isArray() ? javaEnumType.getComponentType() : javaEnumType);

		Schema<Object> enumSchema = createSchema(TYPE_STRING);
		for (Enum<?> enumItem : componentType.getEnumConstants()) {
			enumSchema.addEnumItemObject(enumItem.name());
		}
		enumSchema.example(componentType.getEnumConstants()[0].name());

		if (javaEnumType.isArray()) {
			enumSchema = createArraySchemaOf(enumSchema);
		}

		return enumSchema;
	}

	/**
	 * Creates schema for {@link java.util.Currency}
	 */
	@Nonnull
	public static Schema<Object> createCurrencySchema() {
		final var currencySchema = createSchema(TYPE_STRING);
		currencySchema.format(FORMAT_CURRENCY);
		currencySchema.example("CZK");
		return currencySchema;
	}

	/**
	 * Creates schema for {@link Byte}. OpenAPI specifies byte as BASE-64 encoded character
	 */
	@Nonnull
	public static Schema<Object> createByteSchema() {
		final var byteSchema = createSchema(TYPE_INTEGER);
		byteSchema.format(FORMAT_BYTE);
		byteSchema.example(6);
		return byteSchema;
	}
	/**
	 * Creates schema for {@link io.evitadb.dataType.Range} and its descendants.<br/>
	 * Any range is represented by array of two items.
	 * @param schema will be used as type of range items.
	 */
	@Nonnull
	public static Schema<Object> createRangeSchemaOf(@Nonnull Schema<Object> schema) {
		final var rangeObject = new ArraySchema();
		rangeObject.format(FORMAT_RANGE);
		rangeObject.items(schema);
		rangeObject.minItems(2);
		rangeObject.maxItems(2);
		return rangeObject;
	}

	/**
	 * Creates schema which contains reference to provided schema name
	 */
	@Nonnull
	public static Schema<Object> createReferenceSchema(@Nonnull String schemaName) {
		final var reference = new Schema<>();
		reference.set$ref(schemaName);
		return reference;
	}

	/**
	 * Creates schema which contains reference to provided schema name
	 */
	@Nonnull
	public static Schema<Object> createReferenceSchema(@Nonnull ObjectDescriptor objectDescriptor) {
		return createReferenceSchema(objectDescriptor.name());
	}

	/**
	 * Creates schema which contains reference to provided schema
	 */
	@Nonnull
	public static Schema<Object> createReferenceSchema(@Nonnull Schema<Object> schema) {
		final var reference = new Schema<>();
		reference.set$ref(schema.getName());
		return reference;
	}

	/**
	 * Adds schema as reference in <strong>oneOf</strong>. This allows to add description to reference as <strong>description</strong>
	 * is not allowed on the same level as <strong>ref</strong> by OpenAPI specification.
	 */
	public static void addReferenceSchemaAsOneOf(@Nonnull Schema<Object> objectSchema, @Nonnull Schema<Object> referenceSchema) {
		objectSchema.oneOf(Collections.singletonList(createReferenceSchema(referenceSchema)));
	}

	/**
	 * Creates new schema object of provided schema type
	 */
	@Nonnull
	public static Schema<Object> createSchema(@Nonnull String schemaType) {
		final var localeSchema = new Schema<>();
		//adding type twice is correct for some reason see protected constructors of Schema class
		localeSchema.type(schemaType);
		localeSchema.addType(schemaType);
		return localeSchema;
	}

	/**
	 * Checks if Evita's data type is pseudo generic i.e. it is a {@link java.io.Serializable} interface or is array
	 * of pseudo generic types. {@link Serializable} is common ancestor for all Evita data types.
	 */
	public static boolean isGeneric(@Nonnull Class<?> javaType) {
		final Class<?> componentType = javaType.isArray() ? javaType.getComponentType() : javaType;
		return componentType.equals(Serializable.class);
	}
}
