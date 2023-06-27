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

	@Nonnull
	private final String mutationName;

	@Nullable
	private final Object inputMutationObject;

	@Nonnull
	private final MutationResolvingExceptionFactory exceptionFactory;

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
	 * Tries to get field from raw input local mutation.
	 */
	@Nullable
	public <T> T getOptionalField(@Nonnull String name) {
		return getOptionalField(name, (T) null);
	}

	/**
	 * Tries to get field from raw input local mutation.
	 */
	@Nullable
	public <T> T getOptionalField(@Nonnull String name, @Nullable T defaultValue) {
		assertMutationObjectNonNull();
		assertMutationObjectIsObject();
		//noinspection unchecked
		return Optional.ofNullable(((Map<String, Object>) inputMutationObject).get(name))
			.map(it -> (T) it)
			.orElse(defaultValue);
	}

	/**
	 * Tries to get field from raw input local mutation.
	 */
	@Nullable
	public <T extends Serializable> T getOptionalField(@Nonnull PropertyDescriptor fieldDescriptor) {
		return getOptionalField(fieldDescriptor, null);
	}

	/**
	 * Tries to get field from raw input local mutation.
	 */
	@Nullable
	public <T extends Serializable> T getOptionalField(@Nonnull PropertyDescriptor fieldDescriptor, @Nullable T defaultValue) {
		Assert.isPremiseValid(
			fieldDescriptor.primitiveType() != null && !fieldDescriptor.primitiveType().nonNull(),
			() -> exceptionFactory.createInternalError("Field descriptor of field `" + fieldDescriptor.name() + "` doesn't specify type or is required. You must specify type explicitly.")
		);
		//noinspection unchecked
		return getOptionalField(fieldDescriptor.name(), (Class<T>) fieldDescriptor.primitiveType().javaType(), defaultValue);
	}

	/**
	 * Tries to get field from raw input local mutation.
	 */
	@Nullable
	public <T extends Serializable> T getOptionalField(@Nonnull String name,
	                                                   @Nonnull Function<Object, T> fieldMapper) {
		return getOptionalField(name, fieldMapper, null);
	}

	/**
	 * Tries to get field from raw input local mutation.
	 */
	@Nullable
	public <T extends Serializable> T getOptionalField(@Nonnull String name,
	                                                   @Nonnull Function<Object, T> fieldMapper,
	                                                   @Nullable T defaultValue) {
		assertMutationObjectNonNull();
		assertMutationObjectIsObject();
		//noinspection unchecked
		return Optional.ofNullable(((Map<String, Object>) inputMutationObject).get(name))
			.map(fieldMapper)
			.orElse(defaultValue);
	}

	/**
	 * Tries to get field from raw input local mutation.
	 */
	@Nullable
	public <T extends Serializable> T getOptionalField(@Nonnull String name,
	                                                   @Nonnull Class<T> targetType) {
		return getOptionalField(name, targetType, null);
	}

	/**
	 * Tries to get field from raw input local mutation.
	 */
	@Nullable
	public <T extends Serializable> T getOptionalField(@Nonnull String name,
	                                                   @Nonnull Class<T> targetType,
	                                                   @Nullable T defaultValue) {
		return getOptionalField(name, rawField -> toTargetType(name, rawField, targetType), defaultValue);
	}

	/**
	 * Tries to get field from raw input local mutation.
	 */
	@Nonnull
	public <T> T getRequiredField(@Nonnull String name) {
		assertMutationObjectNonNull();
		assertMutationObjectIsObject();
		//noinspection unchecked
		final Object rawField = ((Map<String, Object>) inputMutationObject).get(name);
		assertRequiredFieldNonNull(name, rawField);
		//noinspection unchecked
		return (T) rawField;
	}

	/**
	 * Tries to get field from raw input local mutation.
	 */
	@Nonnull
	public <T extends Serializable> T getRequiredField(@Nonnull String name, @Nonnull Function<Object, T> fieldMapper) {
		assertMutationObjectNonNull();
		assertMutationObjectIsObject();
		//noinspection unchecked
		final Object rawField = ((Map<String, Object>) inputMutationObject).get(name);
		assertRequiredFieldNonNull(name, rawField);
		return fieldMapper.apply(rawField);
	}

	/**
	 * Tries to get field from raw input local mutation.
	 */
	@Nonnull
	public <T extends Serializable> T getRequiredField(@Nonnull String name, @Nonnull Class<T> targetType) {
		return getRequiredField(name, rawField -> toTargetType(name, rawField, targetType));
	}

	/**
	 * Tries to get field from raw input local mutation.
	 */
	@Nonnull
	public <T extends Serializable> T getRequiredField(@Nonnull PropertyDescriptor fieldDescriptor) {
		Assert.isPremiseValid(
			fieldDescriptor.primitiveType() != null && fieldDescriptor.primitiveType().nonNull(),
			() -> exceptionFactory.createInternalError("Field descriptor of field `" + fieldDescriptor.name() + "` doesn't specify type or is not required. You must specify type explicitly.")
		);
		//noinspection unchecked
		return (T) getRequiredField(fieldDescriptor.name(), fieldDescriptor.primitiveType().javaType());
	}

	@Nonnull
	private <T extends Serializable> T toTargetType(@Nonnull Object rawField, @Nonnull Class<T> targetType) {
		return toTargetType(null, rawField, targetType);
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	private <T extends Serializable> T toTargetType(@Nullable String fieldName, @Nonnull Object rawField, @Nonnull Class<T> targetType) {
		Assert.isPremiseValid(
			!Any.class.isAssignableFrom(targetType),
			() -> exceptionFactory.createInternalError("Java type `Any` cannot be converted directly, explicit mapper must be specified.")
		);

		if (targetType.isInstance(rawField)) {
			//noinspection unchecked
			return (T) rawField;
		}

		Assert.isPremiseValid(
			rawField instanceof Serializable,
			() -> {
				if (fieldName == null) {
					return exceptionFactory.createInternalError("Mutation `" + mutationName + "` has unsupported data type.");
				} else {
					return exceptionFactory.createInternalError("Field `" + fieldName + "` of mutation `" + mutationName + "` has unsupported data type.");
				}
			}
		);

		if (targetType.isEnum()) {
			return (T) toEnumType(fieldName, rawField, targetType);
		} else if (Range.class.isAssignableFrom(targetType)) {
			return toRangeType(fieldName, rawField, targetType);
		} else if (targetType.isArray() && !rawField.getClass().isArray()) {
			final Class<? extends Serializable> componentType = (Class<? extends Serializable>) targetType.getComponentType();
			return (T) toArrayOfSpecificType(fieldName, rawField, componentType);
		} else {
			return EvitaDataTypes.toTargetType((Serializable) rawField, targetType);
		}
	}

	@Nonnull
	private <E extends Enum<E>, T extends Serializable> E toEnumType(@Nullable String fieldName, @Nonnull Object rawField, @Nonnull Class<T> targetType) {
		Assert.isPremiseValid(
			targetType.isEnum(),
			() -> exceptionFactory.createInternalError("Expected enum class, found `" + targetType.getName() + "`.")
		);

		if (targetType.isAssignableFrom(rawField.getClass())) {
			//noinspection unchecked
			return (E) rawField;
		}
		if (rawField instanceof String s) {
			//noinspection unchecked
			return Enum.valueOf((Class<E>) targetType, s);
		}
		throw exceptionFactory.createInvalidArgumentException("Unsupported data type for field `" + fieldName + "`.");
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	private <T extends Serializable> T toRangeType(@Nullable String fieldName, @Nonnull Object rawField, @Nonnull Class<T> targetType) {
		Assert.isTrue(
			rawField instanceof List<?> && ((List<?>) rawField).size() == 2,
			() -> {
				if (fieldName == null) {
					return exceptionFactory.createInvalidArgumentException("Mutation `" + mutationName + "` is expected to be a tuple of 2 items.");
				} else {
					return exceptionFactory.createInvalidArgumentException("Field `" + fieldName + "` of mutation `" + mutationName + "` is expected to be a tuple of 2 items.");
				}
			}
		);

		final List<Object> tuple = (List<Object>) rawField;
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
	private <T extends Serializable> T[] toArrayOfSpecificType(@Nullable String fieldName, @Nonnull Object rawField, @Nonnull Class<T> componentType) {
		Assert.isTrue(
			rawField instanceof Collection<?>,
			() -> {
				if (fieldName == null) {
					return exceptionFactory.createInvalidArgumentException("Mutation `" + mutationName + "` is expected to be an array.");
				} else {
					return exceptionFactory.createInvalidArgumentException("Field `" + fieldName + "` of mutation `" + mutationName + "` is expected to be an array.");
				}
			}
		);
		final Collection<Object> array = (Collection<Object>) rawField;
		return array.stream()
			.map(it -> toTargetType(fieldName, it, componentType))
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

	private void assertRequiredFieldNonNull(@Nonnull String name, @Nullable Object rawField) {
		Assert.isTrue(
			rawField != null,
			() -> exceptionFactory.createInvalidArgumentException("Cannot find required mutation field `" + name + "`.")
		);
	}
}
