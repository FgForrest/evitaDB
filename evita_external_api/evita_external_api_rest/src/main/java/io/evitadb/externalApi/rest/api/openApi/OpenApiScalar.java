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

package io.evitadb.externalApi.rest.api.openApi;

import io.evitadb.dataType.*;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.externalApi.dataType.Any;
import io.evitadb.externalApi.dataType.GenericObject;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiConstants.*;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Represents basic primitive type of OpenAPI (string, integer, ...). It include support for all {@link EvitaDataTypes}-supported
 * Java types.
 *
 * It translates to corresponding {@link Schema} with appropriate type and format and because these data are system-wide,
 * they are not registered globally like objects.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@EqualsAndHashCode
@ToString
public class OpenApiScalar implements OpenApiSimpleType {

	@Nonnull
	private static final Map<Class<?>, Supplier<Schema<?>>> SCALAR_SCHEMA_MAPPINGS;
	static {
		SCALAR_SCHEMA_MAPPINGS = createHashMap(32);
		SCALAR_SCHEMA_MAPPINGS.put(String.class, StringSchema::new);
		SCALAR_SCHEMA_MAPPINGS.put(Byte.class, OpenApiScalar::createByteSchema);
		SCALAR_SCHEMA_MAPPINGS.put(Short.class, OpenApiScalar::createShortSchema);
		SCALAR_SCHEMA_MAPPINGS.put(Integer.class, IntegerSchema::new);
		SCALAR_SCHEMA_MAPPINGS.put(Long.class, OpenApiScalar::createLongSchema);
		SCALAR_SCHEMA_MAPPINGS.put(Boolean.class, BooleanSchema::new);
		SCALAR_SCHEMA_MAPPINGS.put(Character.class, OpenApiScalar::createCharacterSchema);
		SCALAR_SCHEMA_MAPPINGS.put(BigDecimal.class, OpenApiScalar::createBigDecimalSchema);
		SCALAR_SCHEMA_MAPPINGS.put(OffsetDateTime.class, OpenApiScalar::createOffsetDateTimeSchema);
		SCALAR_SCHEMA_MAPPINGS.put(LocalDateTime.class, OpenApiScalar::createLocalDateTimeSchema);
		SCALAR_SCHEMA_MAPPINGS.put(LocalDate.class, OpenApiScalar::createLocalDateSchema);
		SCALAR_SCHEMA_MAPPINGS.put(LocalTime.class, OpenApiScalar::createLocalTimeSchema);
		SCALAR_SCHEMA_MAPPINGS.put(DateTimeRange.class, () -> createRangeOf(createOffsetDateTimeSchema()));
		SCALAR_SCHEMA_MAPPINGS.put(BigDecimalNumberRange.class, () -> createRangeOf(createBigDecimalSchema()));
		SCALAR_SCHEMA_MAPPINGS.put(ByteNumberRange.class, () -> createRangeOf(createByteSchema()));
		SCALAR_SCHEMA_MAPPINGS.put(ShortNumberRange.class, () -> createRangeOf(createShortSchema()));
		SCALAR_SCHEMA_MAPPINGS.put(IntegerNumberRange.class, () -> createRangeOf(new IntegerSchema()));
		SCALAR_SCHEMA_MAPPINGS.put(LongNumberRange.class, () -> createRangeOf(createLongSchema()));
		SCALAR_SCHEMA_MAPPINGS.put(ComplexDataObject.class, OpenApiScalar::createGenericObjectSchema);
		SCALAR_SCHEMA_MAPPINGS.put(Locale.class, OpenApiScalar::createLocaleSchema);
		SCALAR_SCHEMA_MAPPINGS.put(Currency.class, OpenApiScalar::createCurrencySchema);
		SCALAR_SCHEMA_MAPPINGS.put(UUID.class, OpenApiScalar::createUuidSchema);
		SCALAR_SCHEMA_MAPPINGS.put(Predecessor.class, OpenApiScalar::createPredecessorSchema);
		SCALAR_SCHEMA_MAPPINGS.put(ReferencedEntityPredecessor.class, OpenApiScalar::createReferencedEntityPredecessorSchema);
		SCALAR_SCHEMA_MAPPINGS.put(Any.class, OpenApiScalar::createAnySchema);
		SCALAR_SCHEMA_MAPPINGS.put(GenericObject.class, OpenApiScalar::createGenericObjectSchema);
		SCALAR_SCHEMA_MAPPINGS.put(Expression.class, OpenApiScalar::createExpressionSchema);
	}

	@Nonnull
	private final Class<?> javaType;

	private OpenApiScalar(@Nonnull Class<?> javaType) {
		Assert.isPremiseValid(
			!javaType.isArray(),
			() -> new OpenApiBuildingError("OpenAPI scalar cannot be created from Java array `" + javaType.getName() + "`.")
		);
		Assert.isPremiseValid(
			!javaType.isEnum(),
			() -> new OpenApiBuildingError("OpenAPI scalar cannot be created from Java enum `" + javaType.getName() + "`.")
		);
		Assert.isPremiseValid(
			EvitaDataTypes.isSupportedType(javaType) ||
				ComplexDataObject.class.isAssignableFrom(javaType) ||
				Any.class.isAssignableFrom(javaType) ||
				GenericObject.class.isAssignableFrom(javaType),
			() -> new OpenApiBuildingError("OpenAPI scalar doesn't support type `" + javaType.getName() + "`.")
		);
		if (javaType.isPrimitive()) {
			this.javaType = EvitaDataTypes.getWrappingPrimitiveClass(javaType);
		} else {
			this.javaType = javaType;
		}
	}

	/**
	 * Creates OpenAPI scalar representing {@link EvitaDataTypes} Java type.
	 */
	@Nonnull
	public static OpenApiScalar scalarFrom(@Nonnull Class<?> javaType) {
		return new OpenApiScalar(javaType);
	}

	/**
	 * Whether this scalar is range type.
	 */
	public boolean isRange() {
		return Range.class.isAssignableFrom(this.javaType);
	}

	@Nonnull
	@Override
	public Schema<?> toSchema() {
		return SCALAR_SCHEMA_MAPPINGS.get(this.javaType).get();
	}

	/**
	 * Creates schema for {@link io.evitadb.dataType.Range} and its descendants.<br/>
	 * Any range is represented by array of two items.
	 * @param schema will be used as type of range items.
	 */
	@Nonnull
	private static Schema<?> createRangeOf(@Nonnull Schema<?> schema) {
		final var rangeObject = new ArraySchema();
		rangeObject.format(FORMAT_RANGE);
		rangeObject.items(schema);
		rangeObject.minItems(2);
		rangeObject.maxItems(2);
		return rangeObject;
	}

	/**
	 * Creates schema for {@link Short}
	 */
	@Nonnull
	private static Schema<?> createShortSchema() {
		final Schema<?> shortSchema = new IntegerSchema();
		shortSchema
			.format(FORMAT_INT_16)
			.example(845);
		return shortSchema;
	}

	/**
	 * Creates schema for {@link Long}
	 */
	@Nonnull
	private static Schema<?> createLongSchema() {
		final Schema<?> longSchema = new StringSchema();
		longSchema
			.format(FORMAT_INT_64)
			.example("685487");
		return longSchema;
	}

	/**
	 * Creates schema for {@link BigDecimal}.<br/>
	 * <strong>string</strong> type is used for {@link BigDecimal} in order to keep precision of number.
	 */
	@Nonnull
	private static Schema<?> createBigDecimalSchema() {
		final Schema<?> bigDecimalSchema = new StringSchema();
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
	private static Schema<?> createLocaleSchema() {
		final var localeSchema = new StringSchema();
		localeSchema
			.format(FORMAT_LOCALE)
			.example("cs-CZ");
		return localeSchema;
	}

	@Nonnull
	private static Schema<?> createGenericObjectSchema() {
		final Schema<Object> objectSchema = new ObjectSchema();
		objectSchema.setAdditionalProperties(true);
		return objectSchema;
	}

	/**
	 * Creates schema for {@link OffsetDateTime}
	 */
	@Nonnull
	private static Schema<?> createOffsetDateTimeSchema() {
		final Schema<?> dateTimeSchema = new StringSchema();
		dateTimeSchema
			.format(FORMAT_DATE_TIME)
			.example("2022-09-27T13:28:27.357+02:00");
		return dateTimeSchema;
	}

	/**
	 * Creates schema for {@link java.time.LocalDateTime}
	 */
	@Nonnull
	private static Schema<?> createLocalDateTimeSchema() {
		final Schema<?> dateTimeSchema = new StringSchema();
		dateTimeSchema
			.format(FORMAT_LOCAL_DATE_TIME)
			.example("2022-09-27T13:28:27.357");
		return dateTimeSchema;
	}

	/**
	 * Creates schema for {@link java.time.LocalDate}
	 */
	@Nonnull
	private static Schema<?> createLocalDateSchema() {
		final Schema<?> dateTimeSchema = new StringSchema();
		dateTimeSchema
			.format(FORMAT_DATE)
			.example("2022-09-27");
		return dateTimeSchema;
	}

	/**
	 * Creates schema for {@link java.time.LocalTime}
	 */
	@Nonnull
	private static Schema<?> createLocalTimeSchema() {
		final Schema<?> dateTimeSchema = new StringSchema();
		dateTimeSchema
			.format(FORMAT_LOCAL_TIME)
			.example("13:28:27.357");
		return dateTimeSchema;
	}

	/**
	 * Creates schema for {@link Character}
	 */
	@Nonnull
	private static Schema<?> createCharacterSchema() {
		final Schema<?> characterSchema = new StringSchema();
		characterSchema
			.minLength(1)
			.maxLength(1)
			.format(FORMAT_CHAR);
		return characterSchema;
	}


	/**
	 * Creates schema for {@link java.util.Currency}
	 */
	@Nonnull
	private static Schema<?> createCurrencySchema() {
		final Schema<?> currencySchema = new StringSchema();
		currencySchema
			.format(FORMAT_CURRENCY)
			.example("CZK");
		return currencySchema;
	}

	/**
	 * Creates schema for {@link UUID}
	 */
	@Nonnull
	private static Schema<?> createUuidSchema() {
		final Schema<?> uuidSchema = new StringSchema();
		uuidSchema
			.format(FORMAT_UUID)
			.example("01081e6f-851f-46b1-9f8f-075b582b5d2e");
		return uuidSchema;
	}

	/**
	 * Creates schema for {@link ExpressionNode}
	 */
	@Nonnull
	private static Schema<?> createExpressionSchema() {
		final Schema<?> expessionSchema = new StringSchema();
		expessionSchema
			.format(FORMAT_EXPRESSION)
			.example("($pageNumber - 1) % 2 == 0 && $pageNumber <= 6");
		return expessionSchema;
	}

	/**
	 * Creates schema for {@link Predecessor}
	 */
	@Nonnull
	private static Schema<?> createPredecessorSchema() {
		final Schema<?> predecessorSchema = new IntegerSchema();
		predecessorSchema
			.format(FORMAT_INT_32)
			.example("44872");
		return predecessorSchema;
	}

	/**
	 * Creates schema for {@link ReferencedEntityPredecessor}
	 */
	@Nonnull
	private static Schema<?> createReferencedEntityPredecessorSchema() {
		final Schema<?> predecessorSchema = new IntegerSchema();
		predecessorSchema
			.format(FORMAT_INT_32)
			.example("44872");
		return predecessorSchema;
	}

	/**
	 * Creates schema for {@link Byte}. OpenAPI specifies byte as BASE-64 encoded character
	 */
	@Nonnull
	private static Schema<?> createByteSchema() {
		final Schema<?> byteSchema = new IntegerSchema();
		byteSchema
			.format(FORMAT_BYTE)
			.example(6);
		return byteSchema;
	}

	@Nonnull
	private static Schema<?> createAnySchema() {
		// inspired by https://swagger.io/docs/specification/data-models/data-types/#any
		final Schema<?> anySchema = new Schema<>();
		anySchema.addAnyOfItem(new StringSchema());
		anySchema.addAnyOfItem(new NumberSchema());
		anySchema.addAnyOfItem(new IntegerSchema());
		anySchema.addAnyOfItem(new BooleanSchema());
		final ArraySchema arraySchema = new ArraySchema();
		arraySchema.setItems(new Schema<>());
		anySchema.addAnyOfItem(arraySchema);
		anySchema.addAnyOfItem(new ObjectSchema());
		return anySchema;
	}
}
