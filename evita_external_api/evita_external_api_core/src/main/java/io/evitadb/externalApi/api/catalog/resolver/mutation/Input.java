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

package io.evitadb.externalApi.api.catalog.resolver.mutation;

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Range;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.dataType.Any;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Wrapper for raw input mutation maps to provide helper methods.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class Input {

	@Nonnull private final String mutationName;
	@Nullable private final Object inputMutationObject;
	@Nonnull private final MutationResolvingExceptionFactory exceptionFactory;

	@Nonnull
	public <T> Optional<T> getOptionalValue() {
		//noinspection unchecked
		return Optional.ofNullable(inputMutationObject)
			.map(it -> (T) it);
	}

	@Nonnull
	public <T extends Serializable> Optional<T> getOptionalValue(@Nonnull Class<T> targetType) {
		return Optional.ofNullable(inputMutationObject)
			.map(it -> toTargetType(it, targetType));
	}

	@Nonnull
	public <T> T getRequiredValue() {
		assertMutationObjectNonNull();
		//noinspection unchecked
		return (T) inputMutationObject;
	}

	@Nonnull
	public <T extends Serializable> T getRequiredValue(@Nonnull Class<T> targetType) {
		assertMutationObjectNonNull();
		return toTargetType(inputMutationObject, targetType);
	}

	/**
	 * Tries to get property from raw input local mutation.
	 */
	<T> T getProperty(@Nonnull String name, boolean required) {
		return getProperty(name, required, (T) null);
	}

	/**
	 * Tries to get property from raw input local mutation.
	 */
	@SuppressWarnings("unchecked")
	<T> T getProperty(@Nonnull String name, boolean required, @Nullable T defaultValue) {
		return getProperty(name, required, it -> (T) it, defaultValue);
	}

	/**
	 * Tries to get property from raw input local mutation.
	 */
	<T extends Serializable> T getProperty(@Nonnull String name,
	                                       boolean required,
	                                       @Nonnull Class<T> targetType) {
		return getProperty(name, required, targetType, null);
	}

	/**
	 * Tries to get property from raw input local mutation.
	 */
	<T extends Serializable> T getProperty(@Nonnull String name,
	                                       boolean required,
	                                       @Nonnull Class<T> targetType,
	                                       @Nullable T defaultValue) {
		return getProperty(name, required, rawProperty -> toTargetType(name, rawProperty, targetType), defaultValue);
	}

	/**
	 * Tries to get property from raw input local mutation.
	 */
	<T> T getProperty(@Nonnull String name,
	                  boolean required,
	                  @Nonnull Function<Object, T> propertyMapper) {
		return getProperty(name, required, propertyMapper, null);
	}

	/**
	 * Tries to get property from raw input local mutation.
	 */
	<T> T getProperty(@Nonnull String name,
	                  boolean required,
	                  @Nonnull Function<Object, T> propertyMapper,
	                  @Nullable T defaultValue) {
		assertMutationObjectNonNull();
		assertMutationObjectIsObject();
		//noinspection unchecked
		final T propertyValue = Optional.ofNullable(((Map<String, Object>) inputMutationObject).get(name))
			.map(propertyMapper)
			.orElse(defaultValue);
		if (required) {
			assertRequiredPropertyNonNull(name, propertyValue);
		}
		return propertyValue;
	}


	/**
	 * Tries to get property from raw input local mutation based on descriptor.
	 */
	public <T extends Serializable> T getProperty(@Nonnull PropertyDescriptor propertyDescriptor) {
		return getProperty(propertyDescriptor, null);
	}

	/**
	 * Tries to get property from raw input local mutation based on descriptor.
	 */
	public <T extends Serializable> T getProperty(@Nonnull PropertyDescriptor propertyDescriptor, @Nullable T defaultValue) {
		Assert.isPremiseValid(
			propertyDescriptor.primitiveType() != null,
			() -> exceptionFactory.createInternalError("Property descriptor of property `" + propertyDescriptor.name() + "` doesn't specify type. You must specify type explicitly.")
		);
		//noinspection unchecked
		return getProperty(propertyDescriptor.name(), propertyDescriptor.primitiveType().nonNull(), (Class<T>) propertyDescriptor.primitiveType().javaType(), defaultValue);
	}


	/**
	 * Tries to get property from raw input local mutation.
	 */
	@Nullable
	public <T> T getOptionalProperty(@Nonnull String name) {
		return getOptionalProperty(name, (T) null);
	}

	/**
	 * Tries to get property from raw input local mutation.
	 */
	@Nullable
	public <T> T getOptionalProperty(@Nonnull String name, @Nullable T defaultValue) {
		return getProperty(name, false, defaultValue);
	}

	/**
	 * Tries to get property from raw input local mutation.
	 */
	@Nullable
	public <T> T getOptionalProperty(@Nonnull String name,
	                                 @Nonnull Function<Object, T> propertyMapper) {
		return getOptionalProperty(name, propertyMapper, null);
	}

	/**
	 * Tries to get property from raw input local mutation.
	 */
	@Nullable
	public <T> T getOptionalProperty(@Nonnull String name,
	                                 @Nonnull Function<Object, T> propertyMapper,
	                                 @Nullable T defaultValue) {
		return getProperty(name, false, propertyMapper, defaultValue);
	}

	/**
	 * Tries to get property from raw input local mutation.
	 */
	@Nullable
	public <T extends Serializable> T getOptionalProperty(@Nonnull String name,
	                                                      @Nonnull Class<T> targetType) {
		return getOptionalProperty(name, targetType, null);
	}

	/**
	 * Tries to get property from raw input local mutation.
	 */
	@Nullable
	public <T extends Serializable> T getOptionalProperty(@Nonnull String name,
	                                                      @Nonnull Class<T> targetType,
	                                                      @Nullable T defaultValue) {
		return getOptionalProperty(name, rawProperty -> toTargetType(name, rawProperty, targetType), defaultValue);
	}

	/**
	 * Tries to get property from raw input local mutation.
	 */
	@Nonnull
	public <T> T getRequiredProperty(@Nonnull String name) {
		return getProperty(name, true, (T) null);
	}

	/**
	 * Tries to get property from raw input local mutation.
	 */
	@Nonnull
	public <T> T getRequiredProperty(@Nonnull String name, @Nonnull Function<Object, T> propertyMapper) {
		return getProperty(name, true, propertyMapper, null);
	}

	/**
	 * Tries to get property from raw input local mutation.
	 */
	@Nonnull
	public <T extends Serializable> T getRequiredProperty(@Nonnull String name, @Nonnull Class<T> targetType) {
		return getRequiredProperty(name, rawProperty -> toTargetType(name, rawProperty, targetType));
	}


	@Nonnull
	private <T extends Serializable> T toTargetType(@Nonnull Object rawProperty, @Nonnull Class<T> targetType) {
		return toTargetType(null, rawProperty, targetType);
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	private <T extends Serializable> T toTargetType(@Nullable String propertyName, @Nonnull Object rawPropertyValue, @Nonnull Class<T> targetType) {
		Assert.isPremiseValid(
			!Any.class.isAssignableFrom(targetType),
			() -> exceptionFactory.createInternalError("Java type `Any` cannot be converted directly, explicit mapper must be specified.")
		);

		if (targetType.isInstance(rawPropertyValue)) {
			//noinspection unchecked
			return (T) rawPropertyValue;
		}

		Assert.isPremiseValid(
			rawPropertyValue instanceof Serializable,
			() -> {
				if (propertyName == null) {
					return exceptionFactory.createInternalError("Mutation `" + mutationName + "` has unsupported data type.");
				} else {
					return exceptionFactory.createInternalError("Property `" + propertyName + "` of mutation `" + mutationName + "` has unsupported data type.");
				}
			}
		);

		if (targetType.isEnum()) {
			return (T) toEnumType(propertyName, rawPropertyValue, targetType);
		} else if (Range.class.isAssignableFrom(targetType)) {
			return toRangeType(propertyName, rawPropertyValue, targetType);
		} else if (targetType.isArray() && !rawPropertyValue.getClass().isArray()) {
			final Class<? extends Serializable> componentType = (Class<? extends Serializable>) targetType.getComponentType();
			return (T) toArrayOfSpecificType(propertyName, rawPropertyValue, componentType);
		} else {
			return EvitaDataTypes.toTargetType((Serializable) rawPropertyValue, targetType);
		}
	}

	@Nonnull
	private <E extends Enum<E>, T extends Serializable> E toEnumType(@Nullable String propertyName, @Nonnull Object rawProperty, @Nonnull Class<T> targetType) {
		Assert.isPremiseValid(
			targetType.isEnum(),
			() -> exceptionFactory.createInternalError("Expected enum class, found `" + targetType.getName() + "`.")
		);

		if (targetType.isAssignableFrom(rawProperty.getClass())) {
			//noinspection unchecked
			return (E) rawProperty;
		}
		if (rawProperty instanceof String s) {
			//noinspection unchecked
			return Enum.valueOf((Class<E>) targetType, s);
		}
		throw exceptionFactory.createInvalidArgumentException("Unsupported data type for property `" + propertyName + "`.");
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	private <T extends Serializable> T toRangeType(@Nullable String propertyName, @Nonnull Object rawProperty, @Nonnull Class<T> targetType) {
		Assert.isTrue(
			rawProperty instanceof List<?> && ((List<?>) rawProperty).size() == 2,
			() -> {
				if (propertyName == null) {
					return exceptionFactory.createInvalidArgumentException("Mutation `" + mutationName + "` is expected to be a tuple of 2 items.");
				} else {
					return exceptionFactory.createInvalidArgumentException("Property `" + propertyName + "` of mutation `" + mutationName + "` is expected to be a tuple of 2 items.");
				}
			}
		);

		final List<Object> tuple = (List<Object>) rawProperty;
		if (targetType.equals(DateTimeRange.class)) {
			final OffsetDateTime from = EvitaDataTypes.toTargetType((Serializable) tuple.get(0), OffsetDateTime.class);
			final OffsetDateTime to = EvitaDataTypes.toTargetType((Serializable) tuple.get(1), OffsetDateTime.class);
			if (from != null && to != null) {
				return (T) DateTimeRange.between(from, to);
			} else if (from != null) {
				return (T) DateTimeRange.since(from);
			} else if (to != null) {
				return (T) DateTimeRange.until(to);
			} else {
				throw exceptionFactory.createInvalidArgumentException("Datetime range can never be created with both bounds null!");
			}
		} else if (targetType.equals(BigDecimalNumberRange.class)) {
			final BigDecimal from = EvitaDataTypes.toTargetType((Serializable) tuple.get(0), BigDecimal.class);
			final BigDecimal to = EvitaDataTypes.toTargetType((Serializable) tuple.get(1), BigDecimal.class);
			if (from != null && to != null) {
				return (T) BigDecimalNumberRange.between(from, to);
			} else if (from != null) {
				return (T) BigDecimalNumberRange.from(from);
			} else if (to != null) {
				return (T) BigDecimalNumberRange.to(to);
			} else {
				throw exceptionFactory.createInvalidArgumentException("BigDecimal range can never be created with both bounds null!");
			}
		} else if (targetType.equals(LongNumberRange.class)) {
			final Long from = EvitaDataTypes.toTargetType((Serializable) tuple.get(0), Long.class);
			final Long to = EvitaDataTypes.toTargetType((Serializable) tuple.get(1), Long.class);
			if (from != null && to != null) {
				return (T) LongNumberRange.between(from, to);
			} else if (from != null) {
				return (T) LongNumberRange.from(from);
			} else if (to != null) {
				return (T) LongNumberRange.to(to);
			} else {
				throw exceptionFactory.createInvalidArgumentException("Long range can never be created with both bounds null!");
			}
		} else if (targetType.equals(IntegerNumberRange.class)) {
			final Integer from = EvitaDataTypes.toTargetType((Serializable) tuple.get(0), Integer.class);
			final Integer to = EvitaDataTypes.toTargetType((Serializable) tuple.get(1), Integer.class);
			if (from != null && to != null) {
				return (T) IntegerNumberRange.between(from, to);
			} else if (from != null) {
				return (T) IntegerNumberRange.from(from);
			} else if (to != null) {
				return (T) IntegerNumberRange.to(to);
			} else {
				throw exceptionFactory.createInvalidArgumentException("Integer range can never be created with both bounds null!");
			}
		} else if (targetType.equals(ShortNumberRange.class)) {
			final Short from = EvitaDataTypes.toTargetType((Serializable) tuple.get(0), Short.class);
			final Short to = EvitaDataTypes.toTargetType((Serializable) tuple.get(1), Short.class);
			if (from != null && to != null) {
				return (T) ShortNumberRange.between(from, to);
			} else if (from != null) {
				return (T) ShortNumberRange.from(from);
			} else if (to != null) {
				return (T) ShortNumberRange.to(to);
			} else {
				throw exceptionFactory.createInvalidArgumentException("Short range can never be created with both bounds null!");
			}
		} else if (targetType.equals(ByteNumberRange.class)) {
			final Byte from = EvitaDataTypes.toTargetType((Serializable) tuple.get(0), Byte.class);
			final Byte to = EvitaDataTypes.toTargetType((Serializable) tuple.get(1), Byte.class);
			if (from != null && to != null) {
				return (T) ByteNumberRange.between(from, to);
			} else if (from != null) {
				return (T) ByteNumberRange.from(from);
			} else if (to != null) {
				return (T) ByteNumberRange.to(to);
			} else {
				throw exceptionFactory.createInvalidArgumentException("Byte range can never be created with both bounds null!");
			}
		} else {
			throw exceptionFactory.createInternalError("Unsupported range type `" + targetType + "`.");
		}
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	private <T extends Serializable> T[] toArrayOfSpecificType(@Nullable String propertyName, @Nonnull Object rawProperty, @Nonnull Class<T> componentType) {
		Assert.isTrue(
			rawProperty instanceof Collection<?>,
			() -> {
				if (propertyName == null) {
					return exceptionFactory.createInvalidArgumentException("Mutation `" + mutationName + "` is expected to be an array.");
				} else {
					return exceptionFactory.createInvalidArgumentException("Property `" + propertyName + "` of mutation `" + mutationName + "` is expected to be an array.");
				}
			}
		);
		final Collection<Object> array = (Collection<Object>) rawProperty;
		return array.stream()
			.map(it -> toTargetType(propertyName, it, componentType))
			.toArray(size -> (T[]) Array.newInstance(componentType, size));
	}

	private void assertMutationObjectNonNull() {
		Assert.isTrue(
			inputMutationObject != null,
			() -> exceptionFactory.createInvalidArgumentException("Expected non-null mutation object.")
		);
	}

	private void assertMutationObjectIsObject() {
		Assert.isPremiseValid(
			inputMutationObject instanceof Map<?, ?>,
			() -> exceptionFactory.createInternalError("Expected map for mutation but found `" + inputMutationObject.getClass().getName() + "`.")
		);
	}

	private void assertRequiredPropertyNonNull(@Nonnull String name, @Nullable Object rawProperty) {
		Assert.isTrue(
			rawProperty != null,
			() -> exceptionFactory.createInvalidArgumentException("Cannot find required mutation property `" + name + "`.")
		);
	}
}
