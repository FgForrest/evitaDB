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

package io.evitadb.externalApi.api.catalog.resolver.mutation;

import io.evitadb.dataType.Range;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.dataType.DataTypeSerializer;
import io.evitadb.utils.Assert;
import io.evitadb.utils.MapBuilder;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static io.evitadb.utils.MapBuilder.map;

/**
 * Represents the output result of a mutation operation in the external API context.
 * This record encapsulates the result data that is returned after executing a mutation,
 * providing a standardized way to handle mutation responses across different API implementations.
 *
 * @author Lukáš Hornych, 2023
 */
@RequiredArgsConstructor
public class Output {

	@Nonnull private final String mutationName;
	@Nonnull private final MutationResolvingExceptionFactory exceptionFactory;

	@Nullable private Object outputMutationObject = null;

	@Nullable
	public Object getOutputMutationObject() {
		if (this.outputMutationObject instanceof MapBuilder builder) {
			return builder.build();
		}
		return this.outputMutationObject;
	}

	public void setValue(@Nullable Object value) {
		Assert.isPremiseValid(
			this.outputMutationObject == null,
			() -> this.exceptionFactory.createInternalError("Output mutation `" + this.mutationName + "` of is already set to specific value.")
		);
		if (value != null) {
			this.outputMutationObject = toSerializableValue(value);
		}
	}

	public void addValue(@Nullable Object value) {
		getOutputValuesBuilder().add(toSerializableValue(value));
	}

	public void setProperty(@Nonnull String propertyName, @Nullable Object propertyValue) {
		final MapBuilder outputBuilder = getOutputPropertiesBuilder();
		if (!outputBuilder.containsKey(propertyName)) {
			if (propertyValue != null) {
				outputBuilder.e(propertyName, toSerializableValue(propertyValue));
			}
		}
	}

	public void setProperty(@Nonnull PropertyDescriptor propertyDescriptor, @Nullable Object propertyValue) {
		setProperty(propertyDescriptor.name(), propertyValue);
	}

	public boolean hasProperty(@Nonnull String propertyName) {
		return getOutputPropertiesBuilder().containsKey(propertyName);
	}

	public boolean hasProperty(@Nonnull PropertyDescriptor propertyDescriptor) {
		return hasProperty(propertyDescriptor.name());
	}

	@Nonnull
	private List<Object> getOutputValuesBuilder() {
		if (this.outputMutationObject == null) {
			this.outputMutationObject = new LinkedList<>();
		}
		Assert.isPremiseValid(
			this.outputMutationObject instanceof List<?>,
			() -> this.exceptionFactory.createInternalError("Output mutation `" + this.mutationName + "` is already set to a `" + this.outputMutationObject.getClass().getName() + "`.")
		);
		//noinspection unchecked
		return (List<Object>) this.outputMutationObject;
	}

	@Nonnull
	private MapBuilder getOutputPropertiesBuilder() {
		if (this.outputMutationObject == null) {
			this.outputMutationObject = map();
		}
		Assert.isPremiseValid(
			this.outputMutationObject instanceof MapBuilder,
			() -> this.exceptionFactory.createInternalError("Output mutation `" + this.mutationName + "` is already set to a `" + this.outputMutationObject.getClass().getName() + "`.")
		);
		return (MapBuilder) this.outputMutationObject;
	}

	/**
	 * Serializes Evita-supported value into universal serializable type to be used e.g. in JSON serialization.
	 */
	@Nullable
	private Object toSerializableValue(@Nullable Object value) {
		if (value == null) return null;
		if (value instanceof Output nestedOutput) return nestedOutput.getOutputMutationObject();
		if (value instanceof Collection<?> values) return toSerializableCollection(values);
		// we optimistically assume that the Map is already serialized output passed from another converter
		if (value instanceof Map<?, ?> nestedObject) return nestedObject;
		if(value instanceof Object[] values) return toSerializableArray(values);
		if (value.getClass().isArray()) return toSerializableArray(value);
		if (value instanceof String string) return string;
		if (value instanceof Character character) return toSerializableValue(character);
		if (value instanceof Integer integer) return integer;
		if (value instanceof Short shortNumber) return shortNumber;
		if (value instanceof Long longNumber) return toSerializableValue(longNumber);
		if (value instanceof Boolean bool) return bool;
		if (value instanceof Byte byteVal) return byteVal;
		if (value instanceof BigDecimal bigDecimal) return toSerializableValue(bigDecimal);
		if (value instanceof Locale locale) return toSerializableValue(locale);
		if (value instanceof Currency currency) return toSerializableValue(currency);
		if (value instanceof OffsetDateTime offsetDateTime) return toSerializableValue(offsetDateTime);
		if (value instanceof LocalDateTime localDateTime) return toSerializableValue(localDateTime);
		if (value instanceof LocalDate dateTime) return toSerializableValue(dateTime);
		if (value instanceof LocalTime localTime) return toSerializableValue(localTime);
		if (value instanceof Range<?> range) return toSerializableValue(range);
		if (value.getClass().isEnum()) return toSerializableValue((Enum<?>) value);
		if (value instanceof Class<?> type) return toSerializableValue(type);

		throw this.exceptionFactory.createInternalError("Serialization of value of class: " + value.getClass().getName() + " is not implemented yet.");
	}

	/**
	 * Serialize {@link Collection} of values into generic list
	 *
	 * @param values list of values
	 * @return values in form of JsonNode
	 */
	private Serializable toSerializableCollection(@Nonnull Collection<?> values) {
		return values.stream()
			.map(this::toSerializableValue)
			.collect(Collectors.toCollection(ArrayList::new));
	}

	/**
	 * Serialize {@link Array} of values into generic list
	 *
	 * @param values array of values
	 * @return values in form of JsonNode
	 */
	private Serializable toSerializableArray(@Nonnull Object[] values) {
		return Arrays.stream(values)
			.map(this::toSerializableValue)
			.toArray();
	}

	/**
	 * Serialize {@link Array} of unknown primitive type into generic list
	 */
	private Serializable toSerializableArray(@Nonnull Object values) {
		final int arraySize = Array.getLength(values);
		final Object[] array = new Object[arraySize];
		for (int i = 0; i < arraySize; i++) {
			final Object item = Array.get(values, i);
			array[i] = toSerializableValue(item);
		}

		return array;
	}

	@Nonnull
	private static String toSerializableValue(@Nonnull Long longNumber) {
		return String.valueOf(longNumber);
	}

	@Nonnull
	private static String toSerializableValue(@Nonnull Character character) {
		return character.toString();
	}

	@Nonnull
	private static String toSerializableValue(@Nonnull BigDecimal bigDecimal) {
		return bigDecimal.toPlainString();
	}

	@Nonnull
	private static String toSerializableValue(@Nonnull Locale locale) {
		return locale.toLanguageTag();
	}

	@Nonnull
	private static String toSerializableValue(@Nonnull Currency currency) {
		return currency.getCurrencyCode();
	}

	@Nonnull
	private static String toSerializableValue(@Nonnull OffsetDateTime offsetDateTime) {
		return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(offsetDateTime);
	}

	@Nonnull
	private static String toSerializableValue(@Nonnull LocalDateTime localDateTime) {
		return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(localDateTime);
	}

	@Nonnull
	private static String toSerializableValue(@Nonnull LocalDate localDate) {
		return DateTimeFormatter.ISO_LOCAL_DATE.format(localDate);
	}

	@Nonnull
	private static String toSerializableValue(@Nonnull LocalTime localTime) {
		return DateTimeFormatter.ISO_LOCAL_TIME.format(localTime);
	}

	@Nonnull
	private static String toSerializableValue(@Nonnull Enum<?> e) {
		return e.name();
	}

	@Nonnull
	private Serializable toSerializableValue(@Nonnull Range<?> range) {
		final Object[] serializedRange = new Object[2];
		serializedRange[0] = range.getPreciseFrom() != null ? toSerializableValue(range.getPreciseFrom()) : null;
		serializedRange[1] = range.getPreciseTo() != null ? toSerializableValue(range.getPreciseTo()) : null;
		return serializedRange;
	}

	private static Serializable toSerializableValue(@Nonnull Class<?> type) {
		return DataTypeSerializer.serialize(type);
	}
}
