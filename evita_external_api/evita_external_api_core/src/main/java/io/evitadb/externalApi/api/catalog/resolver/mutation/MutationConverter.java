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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * Resolves individual JSON objects into actual {@link Mutation} implementations.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public abstract class MutationConverter<M extends Mutation> {

	/**
	 * Returns name of mutation this resolver supports
	 */
	@Nonnull
	protected abstract String getMutationName();

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
	 * Resolve raw input local mutation parsed from JSON into actual implementation of {@link Mutation} based on implementation of
	 * resolver.
	 */
	@Nonnull
	protected abstract M convert(@Nonnull Input input);

}
