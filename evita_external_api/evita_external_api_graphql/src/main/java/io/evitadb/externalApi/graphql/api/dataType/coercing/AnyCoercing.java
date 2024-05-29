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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.api.dataType.coercing;

import graphql.schema.Coercing;
import graphql.schema.CoercingSerializeException;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.externalApi.dataType.Any;
import io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars;

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
	}

	@Override
	public Object serialize(@Nonnull Object dataFetcherResult) throws CoercingSerializeException {
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
		return coercing.serialize(dataFetcherResult);
	}
}
