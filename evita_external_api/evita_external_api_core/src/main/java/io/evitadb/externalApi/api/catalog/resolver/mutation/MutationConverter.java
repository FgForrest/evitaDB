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

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.ValueTypeMapper;
import io.evitadb.externalApi.api.model.mutation.MutationConverterContext;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves individual JSON objects into actual {@link Mutation} implementations.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
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
	private final MutationObjectMapper objectMapper;
	/**
	 * Handles exception creation that can occur during resolving.
	 */
	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final MutationResolvingExceptionFactory exceptionFactory;

	@Getter(AccessLevel.PROTECTED)
	private final ReflectionLookup reflectionLookup = new ReflectionLookup(ReflectionCachingBehaviour.CACHE);

	/**
	 * Resolve raw input local mutation parsed from JSON into actual {@link Mutation} based on implementation of
	 * resolver.
	 */
	@Nonnull
	public M convertFromInput(@Nullable Object rawInputMutationObject) {
		return this.convertFromInput(rawInputMutationObject, MutationConverterContext.EMPTY);
	}

	/**
	 * Resolve raw input local mutation parsed from JSON into actual {@link Mutation} based on implementation of
	 * resolver.
	 * Context map can be passed down to pass custom data for nested mutation converters.
	 */
	@Nonnull
	public M convertFromInput(@Nullable Object rawInputMutationObject, @Nonnull Map<String, Object> context) {
		final Object inputMutationObject = this.objectMapper.parse(rawInputMutationObject);
		return this.convertFromInput(new Input(getMutationName(), inputMutationObject, this.exceptionFactory, context));
	}

	/**
	 * Converts local mutation into a serialized version based on implementation of
	 * resolver.
	 */
	@Nullable
	public Object convertToOutput(@Nonnull M mutation) {
		return this.convertToOutput(mutation, MutationConverterContext.EMPTY);
	}

	/**
	 * Converts local mutation into a serialized version based on implementation of
	 * resolver.
	 * Context map can be passed down to pass custom data for nested mutation converters.
	 */
	@Nullable
	public Object convertToOutput(@Nonnull M mutation, @Nonnull Map<String, Object> context) {
		final Output output = new Output(getMutationName(), this.exceptionFactory, context);
		convertToOutput(mutation, output);
		return this.objectMapper.serialize(output.getOutputMutationObject());
	}

	/**
	 * Converts raw input local mutation parsed from JSON into actual implementation of {@link Mutation} based on implementation of
	 * resolver.
	 */
	@Nonnull
	protected M convertFromInput(@Nonnull Input input) {
		final Class<M> mutationClass = getMutationClass();
		//noinspection unchecked
		return (M) convertObjectFromInput(input, mutationClass);
	}

	protected void convertToOutput(@Nonnull M mutation, @Nonnull Output output) {
		convertObjectToOutput(mutation, output);
	}

	private void convertObjectToOutput(@Nonnull Object object, @Nonnull Output output) {
		final Class<?> objectType = object.getClass();
		final Constructor<?> constructor = resolveCreatorConstructor(objectType);

		if (constructor.getParameterCount() == 0 && !(object instanceof Mutation)) {
			output.setValue(true);
			return;
		}

		if (object instanceof Mutation) {
			output.setProperty(MutationDescriptor.MUTATION_TYPE.name(), object.getClass().getSimpleName());
		}

		final Parameter[] parameters = constructor.getParameters();
		for (int i = 0; i < constructor.getParameterCount(); i++) {
			final Parameter parameter = parameters[i];

			final String name = parameter.getName();
			if (output.hasProperty(name)) {
				// the property has been set manually, we don't want to override it
				continue;
			}

			final Object originalValue;
			final Method getter = this.reflectionLookup.findGetter(objectType, name);
			if (getter != null) {
				try {
					originalValue = getter.invoke(object);
				} catch (IllegalAccessException | InvocationTargetException e) {
					throw this.exceptionFactory.createInternalError("Could not invoke getter for property `" + name + "` in mutation `" + getMutationName() + "`.", e);
				}
			} else {
				final Field propertyField = this.reflectionLookup.findPropertyField(objectType, name);
				if (propertyField != null) {
					try {
						originalValue = propertyField.get(object);
					} catch (IllegalAccessException e) {
						throw this.exceptionFactory.createInternalError("Could not invoke field for property `" + name + "` in mutation `" + getMutationName() + "`.", e);
					}
				} else {
					throw this.exceptionFactory.createInternalError("Could not find getter nor field for property `" + name + "` in mutation `" + getMutationName() + "`.");
				}
			}


			Class<?> targetType = parameter.getType();
			if (Serializable.class.equals(targetType) && originalValue != null) {
				if (Serializable.class.isAssignableFrom(originalValue.getClass())) {
					targetType = originalValue.getClass();
				} else {
					throw this.exceptionFactory.createInternalError(
						"Could not serialize property `" + name + "` in mutation `" + getMutationName() + "`. " +
							"Value to serialize is not serializable as expected, it is `" + originalValue.getClass().getName() + "`."
					);
				}
			}
			final Object targetValue;

			if (originalValue == null) {
				targetValue = null;
			} else if (
				Class.class.isAssignableFrom(targetType) ||
				EvitaDataTypes.isSupportedTypeOrItsArray(targetType) ||
				targetType.isEnum() ||
				(targetType.isArray() && targetType.getComponentType().isEnum())
			) {
				targetValue = originalValue;
			} else if (targetType.isArray()) {
				final int arraySize = Array.getLength(originalValue);
				final List<Object> targetList = new ArrayList<>(arraySize);
				for (int j = 0; j < arraySize; j++) {
					final Object item = Array.get(originalValue, j);

					final Output innerItemOutput = Output.from(output, this.getMutationName(), this.exceptionFactory);
					convertObjectToOutput(item, innerItemOutput);
					targetList.add(innerItemOutput);
				}
				targetValue = targetList;
			} else {
				final Output innerOutput = Output.from(output, this.getMutationName(), this.exceptionFactory);
				convertObjectToOutput(originalValue, innerOutput);
				targetValue = innerOutput;
			}
			output.setProperty(name, targetValue);
		}
	}

	@Nonnull
	private Object convertObjectFromInput(@Nonnull Input input, @Nonnull Class<?> outputType) {
		final Constructor<?> constructor = resolveCreatorConstructor(outputType);
		final Object[] instantiationArgs = new Object[constructor.getParameterCount()];

		if (constructor.getParameterCount() == 0) {
			final Boolean isObjectEnabled = input.getRequiredValue(Boolean.class);
			Assert.isTrue(
				isObjectEnabled,
				() -> getExceptionFactory().createInvalidArgumentException("Mutation `" + getMutationName() + "` supports only `true` value.")
			);
		} else {
			final Parameter[] parameters = constructor.getParameters();
			for (int i = 0; i < constructor.getParameterCount(); i++) {
				final Parameter parameter = parameters[i];

				final String name = parameter.getName();
				final Class<?> type = parameter.getType();
				final boolean required = type.isPrimitive() || parameter.getAnnotation(Nonnull.class) != null;

				if (Class.class.isAssignableFrom(type)) {
					instantiationArgs[i] = input.getProperty(name, required, new ValueTypeMapper(getExceptionFactory(), name));
				} else if (
					EvitaDataTypes.isSupportedTypeOrItsArray(type) ||
					type.isEnum() ||
					(type.isArray() && type.getComponentType().isEnum())
				) {
					//noinspection unchecked
					instantiationArgs[i] = input.getProperty(name, required, (Class<? extends Serializable>) type);
				} else if (type.isArray()) {
					instantiationArgs[i] = convertInnerObjectListFromInput(input, name, required, type.getComponentType());
				} else {
					instantiationArgs[i] = convertInnerObjectFromInput(input, name, required, type);
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
	private Object convertInnerObjectFromInput(@Nonnull Input input, @Nonnull String propertyName, boolean required, @Nonnull Class<?> type) {
		return input.getProperty(
			propertyName,
			required,
			rawPropertyValue -> {
				Assert.isTrue(
					rawPropertyValue instanceof Map<?, ?>,
					() -> this.exceptionFactory.createInvalidArgumentException("Item in property `" + propertyName + "` of mutation `" + getMutationName() + "` is expected to be an object.")
				);

				final Map<String, Object> element = (Map<String, Object>) rawPropertyValue;
				return convertObjectFromInput(Input.from(input, getMutationName(), element, this.exceptionFactory), type);
			}
		);
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	private <T> Object convertInnerObjectListFromInput(@Nonnull Input input, @Nonnull String propertyName, boolean required, @Nonnull Class<T> componentType) {
		return input.getProperty(
			propertyName,
			required,
			rawPropertyValue -> {
				Assert.isTrue(
					rawPropertyValue instanceof List<?>,
					() -> this.exceptionFactory.createInvalidArgumentException("Property `" + propertyName + "` of mutation `" + getMutationName() + "` is expected to be an array.")
				);

				final List<Object> rawElements = (List<Object>) rawPropertyValue;
				return rawElements.stream()
					.map(rawElement -> {
						Assert.isTrue(
							rawElement instanceof Map<?, ?>,
							() -> this.exceptionFactory.createInvalidArgumentException("Item in property `" + propertyName + "` of mutation `" + getMutationName() + "` is expected to be an object.")
						);

						final Map<String, Object> element = (Map<String, Object>) rawElement;
						return convertObjectFromInput(Input.from(input, getMutationName(), element, this.exceptionFactory), componentType);
					})
					.toArray(size -> (T[]) Array.newInstance(componentType, size));
			}
		);
	}

	@Nonnull
	private static Constructor<?> resolveCreatorConstructor(@Nonnull Class<?> outputType) {
		final Constructor<?>[] constructors = outputType.getConstructors();
		final Optional<Constructor<?>> annotatedConstructor = Arrays.stream(constructors)
			.filter(it -> it.getAnnotation(SerializableCreator.class) != null)
			.findFirst();
		if (annotatedConstructor.isPresent()) {
			return annotatedConstructor.get();
		} else if (constructors.length == 1) {
			return constructors[0];
		} else {
			throw new ExternalApiInternalError(
				"Mutation class `" + outputType.getName() + "` must have exactly one public constructor or a constructor marked with @SerializableCreator" +
					" for automatic conversion. "
			);
		}
	}
}
