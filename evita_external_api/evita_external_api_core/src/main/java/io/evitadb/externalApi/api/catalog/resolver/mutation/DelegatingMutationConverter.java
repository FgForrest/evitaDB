/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.externalApi.api.model.mutation.MutationConverterContext;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class DelegatingMutationConverter<M extends Mutation, C extends MutationConverter<M>> {

	/**
	 * Parses input object into Java primitive or generic {@link Map} to resolve into {@link Mutation} or other way around.
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

	/**
	 * Returns name of mutation this resolver supports
	 */
	@Nonnull
	protected abstract String getAncestorMutationName();

	/**
	 * Returns mappings of mutation converters to mutation type. These resolvers are used for resolving inner individual
	 * mutations
	 */
	@Nonnull
	protected abstract Map<Class<? extends M>, C> getConverters();

	protected void registerConverter(@Nonnull Class<?> mutationType, @Nonnull MutationConverter<? extends M> converter) {
		//noinspection unchecked
		getConverters().put((Class<? extends M>) mutationType, (C) converter);
	}

	@Nullable
	public Object convertToOutput(@Nonnull M mutation) {
		return this.convertToOutput(mutation, MutationConverterContext.EMPTY);
	}

	@Nullable
	public Object convertToOutput(@Nonnull M mutation, @Nonnull Map<String, Object> context) {
		final C mutationConverter = getMutationConverter(mutation);
		return mutationConverter.convertToOutput(mutation, context);
	}

	@Nullable
	public Object convertToOutput(@Nonnull M[] mutations) {
		return this.convertToOutput(mutations, MutationConverterContext.EMPTY);
	}

	@Nullable
	public Object convertToOutput(@Nonnull M[] mutations, @Nonnull Map<String, Object> context) {
		return this.convertToOutput(Arrays.asList(mutations), context);
	}

	@Nullable
	public Object convertToOutput(@Nonnull Collection<M> mutations) {
		return this.convertToOutput(mutations, MutationConverterContext.EMPTY);
	}

	@Nullable
	public Object convertToOutput(@Nonnull Collection<M> mutations, @Nonnull Map<String, Object> context) {
		final Output output = new Output(getAncestorMutationName(), getExceptionFactory(), context);
		mutations.forEach(mutation -> {
			final C mutationConverter = getMutationConverter(mutation);
			final Output innerMutationAggregateOutput = Output.from(output, mutationConverter.getMutationName(), getExceptionFactory());
			mutationConverter.convertToOutput(mutation, innerMutationAggregateOutput);
			output.addValue(innerMutationAggregateOutput);
		});
		return this.objectMapper.serialize(output.getOutputMutationObject());
	}

	@Nonnull
	private C getMutationConverter(@Nonnull M mutation) {
		final C mutationConverter = this.getConverters().get(mutation.getClass());
		Assert.isPremiseValid(
			mutationConverter != null,
			() -> this.exceptionFactory.createInternalError("No converter registered for mutation `" + mutation.getClass().getName() + "`.")
		);
		return mutationConverter;
	}
}
