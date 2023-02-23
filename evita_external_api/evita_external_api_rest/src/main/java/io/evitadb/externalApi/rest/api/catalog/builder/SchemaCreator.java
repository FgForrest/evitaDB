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

	@Nonnull
	private static final Map<Class<?>, Supplier<Schema<Object>>> PRIMITIVE_SCHEMA_MAPPINGS;
	static {
		PRIMITIVE_SCHEMA_MAPPINGS = createHashMap(32);
		PRIMITIVE_SCHEMA_MAPPINGS.put(String.class, SchemaCreator::createStringSchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(Byte.class, SchemaCreator::createByteSchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(Short.class, SchemaCreator::createShortSchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(Integer.class, SchemaCreator::createIntegerSchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(Long.class, SchemaCreator::createLongSchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(Boolean.class, SchemaCreator::createBooleanSchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(Character.class, SchemaCreator::createCharacterSchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(BigDecimal.class, SchemaCreator::createBigDecimalSchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(OffsetDateTime.class, SchemaCreator::createOffsetDateTimeSchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(LocalDateTime.class, SchemaCreator::createLocalDateTimeSchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(LocalDate.class, SchemaCreator::createLocalDateSchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(LocalTime.class, SchemaCreator::createLocalTimeSchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(DateTimeRange.class, () -> createRangeSchemaOf(OffsetDateTime.class));
		PRIMITIVE_SCHEMA_MAPPINGS.put(BigDecimalNumberRange.class, () -> createRangeSchemaOf(BigDecimal.class));
		PRIMITIVE_SCHEMA_MAPPINGS.put(ByteNumberRange.class, () -> createRangeSchemaOf(Byte.class));
		PRIMITIVE_SCHEMA_MAPPINGS.put(ShortNumberRange.class, () -> createRangeSchemaOf(Short.class));
		PRIMITIVE_SCHEMA_MAPPINGS.put(IntegerNumberRange.class, () -> createRangeSchemaOf(Integer.class));
		PRIMITIVE_SCHEMA_MAPPINGS.put(LongNumberRange.class, () -> createRangeSchemaOf(Long.class));
		PRIMITIVE_SCHEMA_MAPPINGS.put(ComplexDataObject.class, SchemaCreator::createComplexDataObjectSchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(Locale.class, SchemaCreator::createLocaleSchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(Currency.class, SchemaCreator::createCurrencySchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(Any.class, SchemaCreator::createAnySchema);
		PRIMITIVE_SCHEMA_MAPPINGS.put(GenericObject.class, SchemaCreator::createGenericObjectSchema);
	}

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
	 * Creates OpenAPI schema object by Java type. If class is an {@link java.lang.reflect.Array} then schema corresponding
	 * to that array is returned.
	 */
	@Nonnull
	public static Schema<Object> createSchemaByJavaType(@Nonnull Class<?> javaDataType) {
		final Class<?> componentType = javaDataType.isArray() ? javaDataType.getComponentType() : javaDataType;

		Schema<Object> componentTypeSchema;
		if (componentType.isEnum()) {
			componentTypeSchema = createEnumSchema(componentType);
		} else {
			final Class<?> searchableComponentType = componentType.isPrimitive() ?
				EvitaDataTypes.getWrappingPrimitiveClass(componentType) :
				componentType;

			final Supplier<Schema<Object>> schemaFactory = PRIMITIVE_SCHEMA_MAPPINGS.get(searchableComponentType);
			Assert.isPremiseValid(
				schemaFactory != null,
				() -> new OpenApiInternalError("Unsupported Evita data type in REST API `" + javaDataType.getName() + "`.")
			);
			componentTypeSchema = schemaFactory.get();
		}

		if(javaDataType.isArray()) {
			return createArraySchemaOf(componentTypeSchema);
		}
		return componentTypeSchema;
	}

	@Nonnull
	public static List<Schema<Object>> createAllDataTypes() {
		final List<Schema<Object>> types = new ArrayList<>(45);
		types.add(createSchema(STRING));
		types.add(createSchema(BYTE));
		types.add(createSchema(SHORT));
		types.add(createSchema(INT));
		types.add(createSchema(LONG));
		types.add(createSchema(BOOLEAN));
		types.add(createSchema(CHAR));
		types.add(createSchema(BIG_DECIMAL));
		types.add(createSchema(OFFSET_DATE_TIME));
		types.add(createSchema(LOCAL_DATE_TIME));
		types.add(createSchema(LOCAL_DATE));
		types.add(createSchema(LOCAL_TIME));
		types.add(createSchema(DATE_TIME_RANGE));
		types.add(createSchema(BIG_DECIMAL_NUMBER_RANGE));
		types.add(createSchema(BYTE_NUMBER_RANGE));
		types.add(createSchema(SHORT_NUMBER_RANGE));
		types.add(createSchema(INTEGER_NUMBER_RANGE));
		types.add(createSchema(LONG_NUMBER_RANGE));
		types.add(createSchema(LOCALE));
		types.add(createSchema(CURRENCY));
		types.add(createSchema(COMPLEX_DATA_OBJECT));
		types.add(createSchema(STRING_ARRAY));
		types.add(createSchema(BYTE_ARRAY));
		types.add(createSchema(SHORT_ARRAY));
		types.add(createSchema(INT_ARRAY));
		types.add(createSchema(LONG_ARRAY));
		types.add(createSchema(BOOLEAN_ARRAY));
		types.add(createSchema(CHAR_ARRAY));
		types.add(createSchema(BIG_DECIMAL_ARRAY));
		types.add(createSchema(OFFSET_DATE_TIME_ARRAY));
		types.add(createSchema(LOCAL_DATE_TIME_ARRAY));
		types.add(createSchema(LOCAL_DATE_ARRAY));
		types.add(createSchema(LOCAL_TIME_ARRAY));
		types.add(createSchema(DATE_TIME_RANGE_ARRAY));
		types.add(createSchema(BIG_DECIMAL_NUMBER_RANGE_ARRAY));
		types.add(createSchema(BYTE_NUMBER_RANGE_ARRAY));
		types.add(createSchema(SHORT_NUMBER_RANGE_ARRAY));
		types.add(createSchema(INTEGER_NUMBER_RANGE_ARRAY));
		types.add(createSchema(LONG_NUMBER_RANGE_ARRAY));
		types.add(createSchema(LOCALE_ARRAY));
		types.add(createSchema(CURRENCY_ARRAY));

		return types;
	}

	@Nonnull
	private static Schema<Object> createSchema(@Nonnull DataTypeDescriptor dataTypeDescriptor) {
		final Schema<Object> schema = createSchemaByJavaType(dataTypeDescriptor.type());

		schema
			.name(dataTypeDescriptor.name())
			.description(dataTypeDescriptor.description());

		return schema;
	}

	@Nonnull
	private static Schema<Object> createAnySchema() {
		// todo lho cache
		final List<DataTypeDescriptor> types = new ArrayList<>(45);
		types.add(STRING);
		types.add(BYTE);
		types.add(SHORT);
		types.add(INT);
		types.add(LONG);
		types.add(BOOLEAN);
		types.add(CHAR);
		types.add(BIG_DECIMAL);
		types.add(OFFSET_DATE_TIME);
		types.add(LOCAL_DATE_TIME);
		types.add(LOCAL_DATE);
		types.add(LOCAL_TIME);
		types.add(DATE_TIME_RANGE);
		types.add(BIG_DECIMAL_NUMBER_RANGE);
		types.add(BYTE_NUMBER_RANGE);
		types.add(SHORT_NUMBER_RANGE);
		types.add(INTEGER_NUMBER_RANGE);
		types.add(LONG_NUMBER_RANGE);
		types.add(LOCALE);
		types.add(CURRENCY);
		types.add(COMPLEX_DATA_OBJECT);
		types.add(STRING_ARRAY);
		types.add(BYTE_ARRAY);
		types.add(SHORT_ARRAY);
		types.add(INT_ARRAY);
		types.add(LONG_ARRAY);
		types.add(BOOLEAN_ARRAY);
		types.add(CHAR_ARRAY);
		types.add(BIG_DECIMAL_ARRAY);
		types.add(OFFSET_DATE_TIME_ARRAY);
		types.add(LOCAL_DATE_TIME_ARRAY);
		types.add(LOCAL_DATE_ARRAY);
		types.add(LOCAL_TIME_ARRAY);
		types.add(DATE_TIME_RANGE_ARRAY);
		types.add(BIG_DECIMAL_NUMBER_RANGE_ARRAY);
		types.add(BYTE_NUMBER_RANGE_ARRAY);
		types.add(SHORT_NUMBER_RANGE_ARRAY);
		types.add(INTEGER_NUMBER_RANGE_ARRAY);
		types.add(LONG_NUMBER_RANGE_ARRAY);
		types.add(LOCALE_ARRAY);
		types.add(CURRENCY_ARRAY);

		final Schema<Object> anySchema = createSchemaWithoutType();
		//noinspection rawtypes
		final List<Schema> schemas = types.stream()
			.map(it -> (Schema) createReferenceSchema(it.name()))
			.toList();
		anySchema.oneOf(schemas);

		return anySchema;
	}

	@Nonnull
	public static Schema<Object> createGenericObjectSchema() {
		final Schema<Object> objectSchema = createObjectSchema();
		objectSchema.setAdditionalProperties(true);
		return objectSchema;
	}

	public static Schema<Object> createComplexDataObjectSchema() {
		return createObjectSchema();
	}

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
	 * Creates schema for {@link OrderDirection}
	 */
//	@Nonnull
//	public static Schema<Object> createOrderDirectionSchema() {
////		final var orderDirectionSchema = createSchema(TYPE_STRING);
////		orderDirectionSchema.addEnumItemObject(OrderDirection.ASC);
////		orderDirectionSchema.addEnumItemObject(OrderDirection.DESC);
////		orderDirectionSchema.example(OrderDirection.ASC);
////		return orderDirectionSchema;
//		return createEnumSchema(OrderDirection.class);
//	}

	/**
	 * Creates schema for {@link QueryPriceMode}
	 */
//	@Nonnull
//	public static Schema<Object> createQueryPriceModeSchema() {
////		final var queryPriceModeSchema = createSchema(TYPE_STRING);
////		queryPriceModeSchema.addEnumItemObject(QueryPriceMode.WITH_TAX);
////		queryPriceModeSchema.addEnumItemObject(QueryPriceMode.WITHOUT_TAX);
////		queryPriceModeSchema.example(QueryPriceMode.WITH_TAX);
////		return queryPriceModeSchema;
//		return createEnumSchema(QueryPriceMode.class);
//	}

	/**
	 * Creates schema for {@link PriceContentMode}
	 * @return
	 */
//	@Nonnull
//	public static Schema<Object> createPriceContentModeSchema() {
//		final var priceContentModeSchema = createSchema(TYPE_STRING);
//		priceContentModeSchema.addEnumItemObject(PriceContentMode.NONE);
//		priceContentModeSchema.addEnumItemObject(PriceContentMode.RESPECTING_FILTER);
//		priceContentModeSchema.addEnumItemObject(PriceContentMode.ALL);
//		priceContentModeSchema.example(PriceContentMode.NONE);
//		return priceContentModeSchema;
//	}

	/**
	 * Creates schema for {@link FacetStatisticsDepth}
	 * @return
	 */
//	@Nonnull
//	public static Schema<Object> createFacetStatisticsDepthSchema() {
//		final var facetStatisticsDepthSchema = createSchema(TYPE_STRING);
//		facetStatisticsDepthSchema.addEnumItemObject(FacetStatisticsDepth.COUNTS);
//		facetStatisticsDepthSchema.addEnumItemObject(FacetStatisticsDepth.IMPACT);
//		facetStatisticsDepthSchema.example(FacetStatisticsDepth.COUNTS);
//		return facetStatisticsDepthSchema;
//	}

	/**
	 * Creates schema for {@link PriceInnerRecordHandling}
	 * @return
	 */
//	@Nonnull
//	public static Schema<Object> createPriceInnerRecordHandlingSchema() {
//		final var priceInnerRecordHandlingSchema = createSchema(TYPE_STRING);
//		priceInnerRecordHandlingSchema.addEnumItemObject(PriceInnerRecordHandling.SUM);
//		priceInnerRecordHandlingSchema.addEnumItemObject(PriceInnerRecordHandling.FIRST_OCCURRENCE);
//		priceInnerRecordHandlingSchema.addEnumItemObject(PriceInnerRecordHandling.NONE);
//		priceInnerRecordHandlingSchema.addEnumItemObject(PriceInnerRecordHandling.UNKNOWN);
//		priceInnerRecordHandlingSchema.example(PriceInnerRecordHandling.SUM);
//		return priceInnerRecordHandlingSchema;
//	}

	/**
	 * Creates schema for {@link io.evitadb.api.requestResponse.schema.Cardinality}
	 * @return
	 */
//	@Nonnull
//	public static Schema<Object> createCardinalitySchema() {
//		final var cardinalitySchema = createSchema(TYPE_STRING);
//		cardinalitySchema.addEnumItemObject(Cardinality.ZERO_OR_ONE);
//		cardinalitySchema.addEnumItemObject(Cardinality.ZERO_OR_MORE);
//		cardinalitySchema.addEnumItemObject(Cardinality.EXACTLY_ONE);
//		cardinalitySchema.addEnumItemObject(Cardinality.ONE_OR_MORE);
//		cardinalitySchema.example(Cardinality.ZERO_OR_ONE);
//		return cardinalitySchema;
//	}

	/**
	 * Creates schema for {@link EntityExistence}
	 * @return
	 */
//	@Nonnull
//	public static Schema<Object> createEntityExistence() {
//		final var enitityExistenceSchema = createSchema(TYPE_STRING);
//		enitityExistenceSchema.addEnumItemObject(EntityExistence.MAY_EXIST);
//		enitityExistenceSchema.addEnumItemObject(EntityExistence.MUST_EXIST);
//		enitityExistenceSchema.addEnumItemObject(EntityExistence.MUST_NOT_EXIST);
//		enitityExistenceSchema.example(EntityExistence.MAY_EXIST);
//		return enitityExistenceSchema;
//	}

	/**
	 * Creates schema for {@link AttributeSpecialValue}
	 * @return
	 */
//	@Nonnull
//	public static Schema<Object> createAttributeSpecialValueSchema() {
//		final var attributeSpecialValueSchema = createSchema(TYPE_STRING);
//		attributeSpecialValueSchema.addEnumItemObject(AttributeSpecialValue.NULL);
//		attributeSpecialValueSchema.addEnumItemObject(AttributeSpecialValue.NOT_NULL);
//		attributeSpecialValueSchema.example(AttributeSpecialValue.NULL);
//		return attributeSpecialValueSchema;
//	}

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
	 */
	@Nonnull
	public static Schema<Object> createRangeSchemaOf(@Nonnull Class<?> javaType) {
		return createRangeSchemaOf(SchemaCreator.createSchemaByJavaType(javaType));
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
	 * Creates new schema object without any specified type.
	 */
	@Nonnull
	public static Schema<Object> createSchemaWithoutType() {
		return new Schema<>();
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
