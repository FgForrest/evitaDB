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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.reference;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.ReferenceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.LocalMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Output;

import javax.annotation.Nonnull;

/**
 * Ancestor abstract implementation for {@link ReferenceMutation}s.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
abstract class ReferenceMutationConverter<M extends ReferenceMutation<?>> extends LocalMutationConverter<M> {

	protected ReferenceMutationConverter(@Nonnull MutationObjectMapper objectParser,
	                                     @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	protected ReferenceKey resolveReferenceKey(@Nonnull Input input) {
		return new ReferenceKey(
			input.getProperty(ReferenceMutationDescriptor.NAME),
			input.getProperty(ReferenceMutationDescriptor.PRIMARY_KEY)
		);
	}

	@Override
	protected void convertToOutput(@Nonnull M mutation, @Nonnull Output output) {
		output.setProperty(ReferenceMutationDescriptor.MUTATION_TYPE, mutation.getClass().getSimpleName());
		output.setProperty(ReferenceMutationDescriptor.NAME, mutation.getReferenceKey().referenceName());
		output.setProperty(ReferenceMutationDescriptor.PRIMARY_KEY, mutation.getReferenceKey().primaryKey());
	}
}
