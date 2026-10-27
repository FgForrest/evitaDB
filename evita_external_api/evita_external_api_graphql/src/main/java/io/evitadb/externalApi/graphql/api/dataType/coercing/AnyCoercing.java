/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.graphql.api.dataType.coercing;

import graphql.GraphQLContext;
import graphql.schema.Coercing;
import graphql.schema.CoercingSerializeException;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.externalApi.dataType.Any;
import io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Extension of {@link ObjectCoercing} that extends serialization logic to serialize Java values to correct custom
 * Evita scalars. Beware that parsing of values support only basic GraphQL types
 * and does not support any custom Evita types because lots of those types are serialized as string which is ambiguous
 * without supporting schema. Such values need to be post-processed after parsing somewhere else.
 *
 * <b>Note: should be used only in edge cases. Please consider other
 * ways that allow specifying specific types in schema first.</b>
 *
 * @see Any
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class AnyCoercing extends ObjectCoercing {

	private static final Map<Class<?>, Coercing<?, ?>> SERIALIZATION_COERCING_MAPPINGS;
	static {
		SERIALIZATION_COERCING_MAPPINGS = createHashMap(32);
		SERIALIZATION_COERCING_MAPPINGS.put(String.class, GraphQLScalars.STRING_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(Byte.class, GraphQLScalars.BYTE_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(Short.class, GraphQLScalars.SHORT_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(Integer.class, GraphQLScalars.INT_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(Long.class, GraphQLScalars.LONG_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(Boolean.class, GraphQLScalars.BOOLEAN_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(Character.class, GraphQLScalars.STRING_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(BigDecimal.class, GraphQLScalars.BIG_DECIMAL_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(OffsetDateTime.class, GraphQLScalars.OFFSET_DATE_TIME_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(LocalDateTime.class, GraphQLScalars.LOCAL_DATE_TIME_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(LocalDate.class, GraphQLScalars.LOCAL_DATE_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(LocalTime.class, GraphQLScalars.LOCAL_TIME_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(DateTimeRange.class, GraphQLScalars.DATE_TIME_RANGE_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(BigDecimalNumberRange.class, GraphQLScalars.BIG_DECIMAL_NUMBER_RANGE_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(ByteNumberRange.class, GraphQLScalars.BYTE_NUMBER_RANGE_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(ShortNumberRange.class, GraphQLScalars.SHORT_NUMBER_RANGE_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(IntegerNumberRange.class, GraphQLScalars.INTEGER_NUMBER_RANGE_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(LongNumberRange.class, GraphQLScalars.LONG_NUMBER_RANGE_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(Locale.class, GraphQLScalars.LOCALE_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(Currency.class, GraphQLScalars.CURRENCY_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(UUID.class, GraphQLScalars.UUID_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(Predecessor.class, GraphQLScalars.PREDECESSOR_COERCING);
		SERIALIZATION_COERCING_MAPPINGS.put(ReferencedEntityPredecessor.class, GraphQLScalars.REFERENCED_ENTITY_PREDECESSOR_COERCING);
	}

	/**
	 * Serializes scalar values and free-form parameter objects, applying the existing evitaDB scalar encodings
	 * recursively to their contents.
	 *
	 * @param dataFetcherResult scalar value, object, collection or array returned by a fetcher
	 * @param graphQLContext context of the executing request, handed on to the scalar coercings
	 * @param locale locale of the executing request, handed on to the scalar coercings
	 * @return JSON-compatible value
	 */
	@Override
	public Object serialize(
		@Nonnull Object dataFetcherResult,
		@Nonnull GraphQLContext graphQLContext,
		@Nonnull Locale locale
	) throws CoercingSerializeException {
		if (dataFetcherResult instanceof Map<?, ?> object) {
			final Map<String, Object> serialized = createHashMap(object.size());
			for (final Map.Entry<?, ?> entry : object.entrySet()) {
				if (!(entry.getKey() instanceof String name)) {
					throw new CoercingSerializeException("Object property names must be strings.");
				}
				serialized.put(name, serializeMember(entry.getValue(), graphQLContext, locale));
			}
			return serialized;
		}
		if (dataFetcherResult instanceof Iterable<?> values) {
			final List<Object> serialized = new ArrayList<>();
			for (final Object value : values) {
				serialized.add(serializeMember(value, graphQLContext, locale));
			}
			return serialized;
		}
		if (dataFetcherResult.getClass().isArray()) {
			final int length = Array.getLength(dataFetcherResult);
			final List<Object> serialized = new ArrayList<>(length);
			for (int i = 0; i < length; i++) {
				serialized.add(serializeMember(Array.get(dataFetcherResult, i), graphQLContext, locale));
			}
			return serialized;
		}
		final Coercing<?, ?> coercing;
		final Class<?> dataFetcherResultClass = dataFetcherResult.getClass();
		if (dataFetcherResultClass.isPrimitive()) {
			coercing = SERIALIZATION_COERCING_MAPPINGS.get(EvitaDataTypes.getWrappingPrimitiveClass(dataFetcherResultClass));
		} else {
			coercing = SERIALIZATION_COERCING_MAPPINGS.get(dataFetcherResultClass);
		}

		if (coercing == null) {
			throw new CoercingSerializeException("Could not find coercing for value of type `" + dataFetcherResult.getClass().getName() + "`.");
		}
		return coercing.serialize(dataFetcherResult, graphQLContext, locale);
	}

	/**
	 * Serializes one member of an object, collection or array. Unlike a fetcher's result, which GraphQL never hands
	 * to a coercing as `null`, a member may legitimately be `null` and stays so.
	 *
	 * @param member property value or element to serialize
	 * @param graphQLContext context of the executing request
	 * @param locale locale of the executing request
	 * @return JSON-compatible value, `null` for a `null` member
	 */
	@Nullable
	private Object serializeMember(
		@Nullable Object member,
		@Nonnull GraphQLContext graphQLContext,
		@Nonnull Locale locale
	) {
		return member == null ? null : serialize(member, graphQLContext, locale);
	}
}
