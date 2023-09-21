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

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.ValueTypeMapper;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;

/**
 * Resolves individual JSON objects into actual {@link Mutation} implementations.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public abstract class MutationConverter<M extends Mutation> {

	/**
	 * Class representing the original mutation.
	 */
	@Nonnull
	protected abstract Class<M> getMutationClass();

	/**
	 * Returns name of mutation this converter supports for better logging.
	 */
	@Nonnull
	protected String getMutationName() {
		return getMutationClass().getSimpleName();
	}

	/**
	 * Parses input object into Java primitive or generic {@link Map} to resolve into {@link Mutation}.
	 */
	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final MutationObjectParser objectParser;
	/**
	 * Handles exception creation that can occur during resolving.
	 */
	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final MutationResolvingExceptionFactory exceptionFactory;

	/**
	 * Resolve raw input local mutation parsed from JSON into actual {@link Mutation} based on implementation of
	 * resolver.
	 */
	@Nonnull
	public M convert(@Nullable Object rawInputMutationObject) {
		final Object inputMutationObject = objectParser.parse(rawInputMutationObject);
		return convert(new Input(getMutationName(), inputMutationObject, exceptionFactory));
	}

	/**
	 * Converts raw input local mutation parsed from JSON into actual implementation of {@link Mutation} based on implementation of
	 * resolver.
	 */
	@Nonnull
	protected M convert(@Nonnull Input input) {
		final Class<M> mutationClass = getMutationClass();
		//noinspection unchecked
		return (M) convertObject(input, mutationClass);
	}

	@Nonnull
	private Object convertObject(@Nonnull Input input, @Nonnull Class<?> outputType) {
		final Constructor<?>[] constructors = outputType.getConstructors();
		Assert.isPremiseValid(
			constructors.length == 1,
			() -> new ExternalApiInternalError("Mutation class must have exactly one public constructor for automatic conversion. The `" + getMutationName() + "` doesn't have any or more than one.")
		);
		final Constructor<?> constructor = constructors[0];
		final Object[] instantiationArgs = new Object[constructor.getParameterCount()];
		final Parameter[] parameters = constructor.getParameters();

		if (constructor.getParameterCount() == 0) {
			final Boolean isObjectEnabled = input.getRequiredValue(Boolean.class);
			Assert.isTrue(
				isObjectEnabled,
				() -> getExceptionFactory().createInvalidArgumentException("Mutation `" + getMutationName() + "` supports only `true` value.")
			);
		} else {
			for (int i = 0; i < constructor.getParameterCount(); i++) {
				final Parameter parameter = parameters[i];

				final String name = parameter.getName();
				final Class<?> type = parameter.getType();
				final boolean required = type.isPrimitive() || parameter.getAnnotation(Nonnull.class) != null;

				if (Class.class.isAssignableFrom(type)) {
					instantiationArgs[i] = input.getField(name, required, new ValueTypeMapper(getExceptionFactory(), name));
				} else if (
					EvitaDataTypes.isSupportedTypeOrItsArray(type) ||
					type.isEnum() ||
					(type.isArray() && type.getComponentType().isEnum())
				) {
					//noinspection unchecked
					instantiationArgs[i] = input.getField(name, required, (Class<? extends Serializable>) type);
				} else {
					if (type.isArray()) {
						instantiationArgs[i] = convertInnerObjectList(input, name, required, type.getComponentType());
					} else {
						instantiationArgs[i] = convertInnerObject(input, name, required, type);
					}
				}
			}
		}

		try {
			return constructor.newInstance(instantiationArgs);
		} catch (Exception e) {
			throw new ExternalApiInternalError("Could not instantiate mutation `" + getMutationName() +"` with automatic conversion.", e);
		}
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	private Object convertInnerObject(@Nonnull Input input, @Nonnull String fieldName, boolean required, @Nonnull Class<?> type) {
		return input.getField(
			fieldName,
			required,
			rawField -> {
				Assert.isTrue(
					rawField instanceof Map<?, ?>,
					() -> exceptionFactory.createInvalidArgumentException("Item in field `" + fieldName + "` of mutation `" + getMutationName() + "` is expected to be an object.")
				);

				final Map<String, Object> element = (Map<String, Object>) rawField;
				return convertObject(new Input(getMutationName(), element, exceptionFactory), type);
			}
		);
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	private <T> Object convertInnerObjectList(@Nonnull Input input, @Nonnull String fieldName, boolean required, @Nonnull Class<T> componentType) {
		return input.getField(
			fieldName,
			required,
			rawField -> {
				Assert.isTrue(
					rawField instanceof List<?>,
					() -> exceptionFactory.createInvalidArgumentException("Field `" + fieldName + "` of mutation `" + getMutationName() + "` is expected to be an array.")
				);

				final List<Object> rawElements = (List<Object>) rawField;
				return rawElements.stream()
					.map(rawElement -> {
						Assert.isTrue(
							rawElement instanceof Map<?, ?>,
							() -> exceptionFactory.createInvalidArgumentException("Item in field `" + fieldName + "` of mutation `" + getMutationName() + "` is expected to be an object.")
						);

						final Map<String, Object> element = (Map<String, Object>) rawElement;
						return convertObject(new Input(getMutationName(), element, exceptionFactory), componentType);
					})
					.toArray(size -> (T[]) Array.newInstance(componentType, size));
			}
		);
	}

	// todo lho implement
//	@Nonnull
//	protected abstract Object convert(@Nonnull M mutation);

}
