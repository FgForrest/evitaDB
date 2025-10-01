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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.EntityUpsertMutationDescriptor;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Output;

import javax.annotation.Nonnull;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class EntityUpsertMutationConverter extends MutationConverter<EntityUpsertMutation> {

	@Nonnull
	private final DelegatingLocalMutationConverter delegatingLocalMutationConverter;

	public EntityUpsertMutationConverter(
		@Nonnull ObjectMapper objectMapper,
		@Nonnull MutationObjectMapper mutationObjectMapper,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(mutationObjectMapper, exceptionFactory);
		this.delegatingLocalMutationConverter = new DelegatingLocalMutationConverter(
			objectMapper,
			mutationObjectMapper,
			exceptionFactory
		);
	}

	@Nonnull
	@Override
	protected EntityUpsertMutation convertFromInput(@Nonnull Input input) {
		// All APIs currently use EntityUpsertMutationFactory implementations because
		// the part of the mutation is specification in the endpoints itself. No need to implement it here too.
		throw new UnsupportedOperationException("Use EntityUpsertMutationFactory instead.");
	}

	@Override
	protected void convertToOutput(@Nonnull EntityUpsertMutation mutation, @Nonnull Output output) {
		output.setProperty(EntityUpsertMutationDescriptor.ENTITY_PRIMARY_KEY, mutation.getEntityPrimaryKey());
		output.setProperty(EntityUpsertMutationDescriptor.ENTITY_TYPE, mutation.getEntityType());
		output.setProperty(EntityUpsertMutationDescriptor.ENTITY_EXISTENCE, mutation.expects());
		output.setProperty(
			EntityUpsertMutationDescriptor.LOCAL_MUTATIONS,
			this.delegatingLocalMutationConverter.convertToOutput((LocalMutation<?, ?>) mutation.getLocalMutations())
		);
	}

	@Nonnull
	@Override
	protected Class<EntityUpsertMutation> getMutationClass() {
		return EntityUpsertMutation.class;
	}
}
