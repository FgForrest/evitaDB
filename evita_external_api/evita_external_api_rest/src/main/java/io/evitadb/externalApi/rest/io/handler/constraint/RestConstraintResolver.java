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

package io.evitadb.externalApi.rest.io.handler.constraint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ConstraintValueStructure;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint.ConstraintResolver;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.exception.ExternalApiInvalidUsageException;
import io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator;
import io.evitadb.externalApi.rest.api.catalog.resolver.DataDeserializer;
import io.evitadb.externalApi.rest.exception.RESTApiInvalidArgumentException;
import io.evitadb.externalApi.rest.exception.RESTApiQueryResolvingInternalError;
import io.evitadb.externalApi.rest.io.SchemaUtils;
import io.evitadb.externalApi.rest.io.handler.RESTApiContext;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Ancestor for all REST query resolvers. Implements basic resolving logic of {@link ConstraintResolver} specific
 * to REST
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public abstract class RestConstraintResolver<C extends Constraint<?>> extends ConstraintResolver<C, Object> {
	protected final RESTApiContext restApiContext;
	protected final Operation operation;

	protected RestConstraintResolver(@Nonnull RESTApiContext restApiContext, @Nonnull Operation operation) {
		super(restApiContext.getCatalog().getSchema());
		this.restApiContext = restApiContext;
		this.operation = operation;
	}

	@Nullable
	@Override
	protected Object extractValueArgumentFromWrapperObject(@Nonnull ParsedKey parsedKey, @Nullable Object value, @Nonnull ValueParameterDescriptor parameterDescriptor) {
		if(value == null) {
			return null;
		}

		final String argumentName = parameterDescriptor.name();
		return DataDeserializer.deserializeObject(parameterDescriptor.type(), ((JsonNode) value).get(argumentName));
	}

	@Nullable
	@Override
	protected Object extractFromArgumentFromWrapperRange(@Nonnull ParsedKey parsedKey, @Nullable Object value, @Nonnull ValueParameterDescriptor parameterDescriptor) {
		final Schema<?> argumentSchema = getSchemaFromOperationProperty(parsedKey.originalKey());
		final Object[] deserialized = DataDeserializer.deserializeArray(restApiContext.getOpenApi().get(), argumentSchema, (JsonNode) value);
		return deserialized[0];
	}

	@Nullable
	@Override
	protected Object extractToArgumentFromWrapperRange(@Nonnull ParsedKey parsedKey, @Nullable Object value, @Nonnull ValueParameterDescriptor parameterDescriptor) {
		final Schema<?> argumentSchema = getSchemaFromOperationProperty(parsedKey.originalKey());
		final Object[] deserialized = DataDeserializer.deserializeArray(restApiContext.getOpenApi().get(), argumentSchema, (JsonNode) value);
		return deserialized[1];
	}

	@Nullable
	@Override
	protected Object convertValueParameterArgumentToInstantiationArg(@Nonnull ResolveContext resolveContext, @Nullable String originalClassifier, @Nullable Object argument, @Nonnull ParsedKey parsedKey, @Nonnull ValueParameterDescriptor valueParameterDescriptor) {
		if (isNullValue(argument)) {
			// we can return null if parameter isn't required
			return null;
		} else if(argument instanceof JsonNode jsonNode) {
			final Class<? extends Serializable> type = valueParameterDescriptor.type();
			if(type.isArray()) {
				final Schema<?> argumentSchema = getSchemaFromOperationProperty(parsedKey.originalKey());
				return convertArrayArgument(valueParameterDescriptor.type(), argumentSchema, jsonNode);
			} else if(type.equals(Serializable.class)) {
				final Schema<?> argumentSchema = getSchemaFromOperationProperty(parsedKey.originalKey());
				return DataDeserializer.deserialize(restApiContext.getOpenApi().get(), argumentSchema, (jsonNode));
			} else {
				return DataDeserializer.deserializeObject(type, jsonNode);
			}
		} else {
			return argument;
		}
	}

	@Override
	protected boolean resolveNoneParameter(@Nonnull ResolveContext resolveContext, @Nonnull ConstraintValueStructure constraintValueStructure, @Nonnull ParsedKey parsedKey, @Nullable Object value) {
		Assert.notNull(
			value,
			() -> createInvalidArgumentException("Constraint `" + parsedKey.originalKey() + "` requires non-null value.")
		);

		return ((BooleanNode) value).asBoolean();
	}

	private Object convertArrayArgument(@Nonnull Class<?> classForArray, @Nonnull Schema<?> argumentSchema, @Nonnull JsonNode argument) {
		final Schema<?> childrenSchema = SchemaUtils.getTargetSchemaFromRefOrOneOf(argumentSchema.getItems(), restApiContext.getOpenApi().get());
		if(childrenSchema.getType().equals(SchemaCreator.TYPE_ARRAY)) {
			if(argument instanceof ArrayNode arrayNode) {
				final Object objects = Array.newInstance(classForArray.isArray()?classForArray.getComponentType():classForArray, arrayNode.size());
				for (int i = 0; i < arrayNode.size(); i++) {
					Array.set(objects, i, convertArrayArgument(classForArray.isArray()?classForArray.getComponentType():classForArray, childrenSchema, arrayNode.get(i)));
				}
				return objects;
			}
			throw new RESTApiQueryResolvingInternalError("Error when getting values from query",
				"Can't get array  if JsonNode is not instance of ArrayNode. Class: " + argument.getClass().getSimpleName());

		} else {
			return DataDeserializer.deserialize(restApiContext.getOpenApi().get(), argumentSchema, argument);
		}
	}

	@Nullable
	@Override
	protected Object extractChildArgumentFromWrapperObject(@Nonnull ParsedKey parsedKey, @Nullable Object value, @Nonnull ChildParameterDescriptor parameterDescriptor) {
		if(value == null) {
			return null;
		}
		try {
			return ((ObjectNode) value).get(parameterDescriptor.name());
		} catch (ClassCastException e) {
			throw createQueryResolvingInternalError(
				"Constraint `" + parsedKey + "` expected to be ObjectNode but found `" + value + "`."
			);
		}
	}

	@Nonnull
	@Override
	protected Stream<C> resolveContainerInnerConstraints(@Nonnull ResolveContext resolveContext, @Nonnull ParsedKey parsedKey, @Nonnull Object value) {
		if (!(value instanceof final JsonNode innerConstraints)) {
			throw createQueryResolvingInternalError(
				"Constraint `" + parsedKey.originalKey() + "` expected to has container with nested constraints."
			);
		}

		final LinkedList<C> resolvedContainers = new LinkedList<>();
		for (Iterator<String> iterator = innerConstraints.fieldNames(); iterator.hasNext(); ) {
			final String fieldName = iterator.next();
			final C resolved = resolve(resolveContext, fieldName, innerConstraints.get(fieldName));
			if (resolved != null) {
				resolvedContainers.add(resolved);
			}

		}
		return resolvedContainers.stream();
	}

	@Nonnull
	@Override
	protected List<Object> convertInputListToJavaList(@Nonnull Object argument, @Nonnull ParsedKey parsedKey) {
		if (argument instanceof ArrayNode arrayNode) {
			if(arrayNode.isEmpty()) {
				return Collections.emptyList();
			} else {
				final ArrayList<Object> objects = new ArrayList<>(arrayNode.size());
				for (JsonNode jsonNode : arrayNode) {
					objects.add(jsonNode);
				}
				return objects;
			}
		} else {
			throw createQueryResolvingInternalError(
				"Constraint `" + parsedKey.originalKey() + "` expected array value but found `" + argument.getClass() + "`."
			);
		}
	}

	@Nonnull
	@Override
	protected <T extends ExternalApiInternalError> T createQueryResolvingInternalError(@Nonnull String message) {
		//noinspection unchecked
		return (T) new RESTApiQueryResolvingInternalError(message);
	}

	@Nonnull
	@Override
	protected <T extends ExternalApiInvalidUsageException> T createInvalidArgumentException(@Nonnull String message) {
		//noinspection unchecked
		return (T) new RESTApiInvalidArgumentException(message);
	}

	@Override
	protected boolean isBooleanValue(@Nonnull Object argument) {
		return argument instanceof BooleanNode;
	}

	@Override
	protected boolean isNullValue(@Nullable Object argument) {
		return argument == null || argument instanceof NullNode;
	}

	@Nonnull
	@Override
	protected Object createEmptyWrapperObject() {
		return restApiContext.getObjectMapper().getNodeFactory().objectNode();
	}

	@Nonnull
	@Override
	protected Object createEmptyListObject() {
		return restApiContext.getObjectMapper().getNodeFactory().arrayNode();
	}

	@SuppressWarnings("rawtypes")
	protected abstract Schema getSchemaFromOperationProperty(@Nonnull String propertyName);
}
