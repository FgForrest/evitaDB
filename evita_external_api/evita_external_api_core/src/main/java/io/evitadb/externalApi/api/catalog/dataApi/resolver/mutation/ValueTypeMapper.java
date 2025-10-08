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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation;

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.dataType.DataTypeSerializer;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Converts string representing supported Evita data type to actual class.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ValueTypeMapper implements Function<Object, Class<? extends Serializable>> {

	private static final Map<String, Class<? extends Serializable>> VALUE_TYPE_MAPPINGS;
	static {
		final Map<String, Class<? extends Serializable>> mappings = createHashMap(50);
		registerTypeMapping(mappings, String.class);
		registerTypeMapping(mappings, String[].class);
		registerTypeMapping(mappings, Byte.class);
		registerTypeMapping(mappings, Byte[].class);
		registerTypeMapping(mappings, Short.class);
		registerTypeMapping(mappings, Short[].class);
		registerTypeMapping(mappings, Integer.class);
		registerTypeMapping(mappings, Integer[].class);
		registerTypeMapping(mappings, Long.class);
		registerTypeMapping(mappings, Long[].class);
		registerTypeMapping(mappings, Boolean.class);
		registerTypeMapping(mappings, Boolean[].class);
		registerTypeMapping(mappings, Character.class);
		registerTypeMapping(mappings, Character[].class);
		registerTypeMapping(mappings, BigDecimal.class);
		registerTypeMapping(mappings, BigDecimal[].class);
		registerTypeMapping(mappings, OffsetDateTime.class);
		registerTypeMapping(mappings, OffsetDateTime[].class);
		registerTypeMapping(mappings, LocalDateTime.class);
		registerTypeMapping(mappings, LocalDateTime[].class);
		registerTypeMapping(mappings, LocalDate.class);
		registerTypeMapping(mappings, LocalDate[].class);
		registerTypeMapping(mappings, LocalTime.class);
		registerTypeMapping(mappings, LocalTime[].class);
		registerTypeMapping(mappings, DateTimeRange.class);
		registerTypeMapping(mappings, DateTimeRange[].class);
		registerTypeMapping(mappings, BigDecimalNumberRange.class);
		registerTypeMapping(mappings, BigDecimalNumberRange[].class);
		registerTypeMapping(mappings, ByteNumberRange.class);
		registerTypeMapping(mappings, ByteNumberRange[].class);
		registerTypeMapping(mappings, ShortNumberRange.class);
		registerTypeMapping(mappings, ShortNumberRange[].class);
		registerTypeMapping(mappings, IntegerNumberRange.class);
		registerTypeMapping(mappings, IntegerNumberRange[].class);
		registerTypeMapping(mappings, LongNumberRange.class);
		registerTypeMapping(mappings, LongNumberRange[].class);
		registerTypeMapping(mappings, Locale.class);
		registerTypeMapping(mappings, Locale[].class);
		registerTypeMapping(mappings, Currency.class);
		registerTypeMapping(mappings, Currency[].class);
		registerTypeMapping(mappings, UUID.class);
		registerTypeMapping(mappings, UUID[].class);
		registerTypeMapping(mappings, Predecessor.class);
		registerTypeMapping(mappings, ReferencedEntityPredecessor.class);
		registerTypeMapping(mappings, ComplexDataObject.class);
		VALUE_TYPE_MAPPINGS = Collections.unmodifiableMap(mappings);
	}

	@Nonnull
	private final MutationResolvingExceptionFactory exceptionFactory;

	@Nonnull
	private final String fieldName;

	public ValueTypeMapper(@Nonnull MutationResolvingExceptionFactory exceptionFactory, @Nonnull String fieldName) {
		this.exceptionFactory = exceptionFactory;
		this.fieldName = fieldName;
	}

	public ValueTypeMapper(@Nonnull MutationResolvingExceptionFactory exceptionFactory, @Nonnull PropertyDescriptor field) {
		this.exceptionFactory = exceptionFactory;
		this.fieldName = field.name();
	}

	@Override
	public Class<? extends Serializable> apply(Object rawField) {
		if (rawField instanceof Class<?> valueType) {
			//noinspection unchecked
			return (Class<? extends Serializable>) valueType;
		}
		if (rawField instanceof String valueTypeName) {
			final Class<? extends Serializable> valueType = VALUE_TYPE_MAPPINGS.get(valueTypeName);
			if (valueType == null) {
				throw this.exceptionFactory.createInvalidArgumentException("Unknown value type in `" + this.fieldName + "`.");
			}
			return valueType;
		}
		throw this.exceptionFactory.createInvalidArgumentException("Unsupported value type in `" + this.fieldName + "`.");
	}

	private static void registerTypeMapping(@Nonnull Map<String, Class<? extends Serializable>> mappings,
	                                        @Nonnull Class<? extends Serializable> javaType) {
		final String apiName = DataTypeSerializer.serialize(javaType);
		mappings.put(apiName, javaType);
	}
}
