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
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves individual JSON objects acting as aggregating object for inner mutations into actual {@link Mutation}s implementations.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public abstract class MutationAggregateConverter<M extends Mutation, R extends MutationConverter<? extends M>> {

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
	 * Returns name of mutation this resolver supports
	 */
	@Nonnull
	protected abstract String getMutationAggregateName();

	/**
	 * Returns mappings of mutation resolvers to names of mutations. These resolver are used for resolving inner individual
	 * mutations
	 */
	@Nonnull
	protected abstract Map<String, R> getResolvers();

	/**
	 * Resolve raw input local mutation parsed from JSON into actual list of {@link Mutation} based on implementation of
	 * resolver.
	 */
	@Nonnull
	public List<M> convert(@Nullable Object rawInputMutationObject) {
		final Object inputMutationObject = objectParser.parse(rawInputMutationObject);
		return convert(new InputMutation(getMutationAggregateName(), inputMutationObject, exceptionFactory));
	}

	/**
	 * Resolve raw input local mutation parsed from JSON into actual list of {@link Mutation}s based on implementation of
	 * resolver.
	 */
	@Nonnull
	protected List<M> convert(@Nonnull InputMutation inputMutation) {
		final List<M> mutations = new LinkedList<>();

		final Map<String, Object> mutationAggregate = Optional.of(inputMutation.getRequiredValue())
			.map(m -> {
				Assert.isTrue(
					m instanceof Map<?, ?>,
					() -> getExceptionFactory().createInvalidArgumentException("Mutation is expected to be an object.")
				);
				//noinspection unchecked
				return (Map<String, Object>) m;
			})
			.get();

		mutationAggregate.forEach((mutationName, mutation) -> {
			final R resolver = Optional.ofNullable(getResolvers().get(mutationName))
				.orElseThrow(() -> getExceptionFactory().createInvalidArgumentException("Unknown mutation `" + mutationName + "`."));
			mutations.add(resolver.convert(mutation));
		});

		return mutations;
	}

}
