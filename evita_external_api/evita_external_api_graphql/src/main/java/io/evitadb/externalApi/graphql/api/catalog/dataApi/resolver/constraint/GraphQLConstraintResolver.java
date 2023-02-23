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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ConstraintProcessingUtils;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ConstraintValueStructure;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint.ConstraintResolver;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.exception.ExternalApiInvalidUsageException;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Ancestor for all GraphQL query resolvers. Implements basic resolving logic of {@link ConstraintResolver} specific
 * to GraphQL
 *
 * @param <C> query type
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class GraphQLConstraintResolver<C extends Constraint<?>> extends ConstraintResolver<C, Object> {

	@Nonnull
	protected final String rootEntityType;

	protected GraphQLConstraintResolver(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull String rootEntityType) {
		super(catalogSchema);
		this.rootEntityType = rootEntityType;
	}

	@Nullable
	@Override
	protected Object extractValueArgumentFromWrapperObject(@Nonnull ParsedKey parsedKey,
	                                                       @Nullable Object value,
	                                                       @Nonnull ValueParameterDescriptor parameterDescriptor) {
		return extractArgumentFromWrapperObject(parsedKey, value, parameterDescriptor.name());
	}

	@Nullable
	@Override
	protected Object extractFromArgumentFromWrapperRange(@Nonnull ParsedKey parsedKey,
	                                                     @Nullable Object value,
	                                                     @Nonnull ValueParameterDescriptor parameterDescriptor) {
		return extractRangeFromWrapperRange(parsedKey, value).get(0);
	}

	@Nullable
	@Override
	protected Object extractToArgumentFromWrapperRange(@Nonnull ParsedKey parsedKey,
	                                                   @Nullable Object value,
	                                                   @Nonnull ValueParameterDescriptor parameterDescriptor) {
		return extractRangeFromWrapperRange(parsedKey, value).get(1);
	}

	@Nullable
	@Override
	protected Object convertValueParameterArgumentToInstantiationArg(@Nonnull ResolveContext resolveContext,
	                                                                 @Nullable String originalClassifier,
	                                                                 @Nullable Object argument,
	                                                                 @Nonnull ParsedKey parsedKey,
	                                                                 @Nonnull ValueParameterDescriptor valueParameterDescriptor) {
		if (isNullValue(argument)) {
			// we can return null if parameter isn't required
			return null;
		} else if (valueParameterDescriptor.type().isArray()) {
			final List<Object> listArgument;
			try {
				//noinspection unchecked
				listArgument = (List<Object>) argument;
			} catch (ClassCastException e) {
				throw createQueryResolvingInternalError("Constraint `" + parsedKey.originalKey() + "` expected list value but found `" + argument + "`.");
			}
			//noinspection unchecked
			return convertGraphQLListToSpecificArray(
				(Class<? extends Serializable>) valueParameterDescriptor.type().getComponentType(),
				listArgument
			);
		} else {
			return argument;
		}
	}

	@Override
	protected boolean resolveNoneParameter(@Nonnull ResolveContext resolveContext,
	                                       @Nonnull ConstraintValueStructure constraintValueStructure,
	                                       @Nonnull ParsedKey parsedKey,
	                                       @Nullable Object value) {
		Assert.notNull(
			value,
			() -> createInvalidArgumentException("Constraint `" + parsedKey.originalKey() + "` requires non-null value.")
		);

		return (boolean) value;
	}

	@Nullable
	@Override
	protected Object extractChildArgumentFromWrapperObject(@Nonnull ParsedKey parsedKey,
	                                                       @Nullable Object value,
	                                                       @Nonnull ChildParameterDescriptor parameterDescriptor) {
		return extractArgumentFromWrapperObject(parsedKey, value, parameterDescriptor.name());
	}

	@Nonnull
	@Override
	protected Stream<C> resolveContainerInnerConstraints(@Nonnull ResolveContext resolveContext,
	                                                     @Nonnull ParsedKey parsedKey,
	                                                     @Nonnull Object value) {
		if (!(value instanceof Map<?, ?>)) {
			throw createQueryResolvingInternalError(
				"Constraint `" + parsedKey.originalKey() + "` expected to has container with nested constraints."
			);
		}

		//noinspection unchecked
		final Map<String, Object> innerConstraints = (Map<String, Object>) value;
		return innerConstraints.entrySet()
			.stream()
			.map(c -> resolve(resolveContext, c.getKey(), c.getValue()))
			.filter(Objects::nonNull);
	}

	@Override
	protected boolean isBooleanValue(@Nonnull Object argument) {
		return argument instanceof Boolean;
	}

	@Override
	protected boolean isNullValue(@Nullable Object argument) {
		return argument == null;
	}

	@Nonnull
	@Override
	protected Object createEmptyWrapperObject() {
		return Map.of();
	}

	@Nonnull
	@Override
	protected Object createEmptyListObject() {
		return List.of();
	}

	@Nonnull
	@Override
	protected List<Object> convertInputListToJavaList(@Nonnull Object argument, @Nonnull ParsedKey parsedKey) {
		try {
			//noinspection unchecked
			return (List<Object>) argument;
		} catch (ClassCastException e) {
			throw createQueryResolvingInternalError(
				"Constraint `" + parsedKey.originalKey() + "` expected list value but found `" + argument + "`."
			);
		}
	}

	@Nonnull
	@Override
	protected <T extends ExternalApiInternalError> T createQueryResolvingInternalError(@Nonnull String message) {
		//noinspection unchecked
		return (T) new GraphQLQueryResolvingInternalError(message);
	}

	@Nonnull
	@Override
	protected <T extends ExternalApiInvalidUsageException> T createInvalidArgumentException(@Nonnull String message) {
		//noinspection unchecked
		return (T) new GraphQLInvalidArgumentException(message);
	}

	@Nonnull
	private List<Object> extractRangeFromWrapperRange(@Nonnull ParsedKey parsedKey,
	                                                  @Nullable Object value) {
		final List<Object> range;
		try {
			//noinspection unchecked
			range = (List<Object>) value;
		} catch (ClassCastException e) {
			throw new GraphQLQueryResolvingInternalError(
				"Constraint `" + parsedKey + "` expected to be wrapper range but found `" + value + "`."
			);
		}
		Assert.notNull(
			range,
			() -> new GraphQLInvalidArgumentException("Constraint `" + parsedKey.originalKey() + "` requires range value.")
		);
		Assert.isTrue(
			range.size() == ConstraintProcessingUtils.WRAPPER_RANGE_PARAMETERS_COUNT,
			() -> new GraphQLInvalidArgumentException("Constraint `" + parsedKey.originalKey() + "` has invalid range format.")
		);

		return range;
	}

	@Nullable
	private Object extractArgumentFromWrapperObject(@Nonnull ParsedKey parsedKey,
	                                                @Nullable Object value,
	                                                @Nonnull String parameterName) {
		final Map<String, Object> wrapperObject;
		try {
			//noinspection unchecked
			wrapperObject = (Map<String, Object>) value;
		} catch (ClassCastException e) {
			throw createQueryResolvingInternalError(
				"Constraint `" + parsedKey + "` expected to be wrapper object but found `" + value + "`."
			);
		}
		if (wrapperObject == null) {
			return null;
		} else {
			return wrapperObject.get(parameterName);
		}
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	private <V extends Serializable> V[] convertGraphQLListToSpecificArray(@Nonnull Class<V> targetComponentType,
	                                                                       @Nonnull List<Object> graphQLList) {
		try {
			//noinspection SuspiciousToArrayCall
			return graphQLList.toArray(size -> (V[]) Array.newInstance(targetComponentType, size));
		} catch (ClassCastException e) {
			throw new GraphQLInternalError("Could not cast GraphQL list to array of type `" + targetComponentType.getName() + "`");
		}
	}
}
