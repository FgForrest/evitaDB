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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation;

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.DelegatingMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationAggregateConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.InfrastructureMutationAggregateDescriptor;
import io.evitadb.externalApi.api.transaction.resolver.mutation.TransactionMutationConverter;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.InfrastructureMutationAggregateDescriptor.TRANSACTION_MUTATION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link DelegatingMutationConverter} for converting implementations of infrastructure mutations.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class DelegatingInfrastructureMutationConverter extends
	DelegatingMutationConverter<Mutation, MutationConverter<Mutation>> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final Map<Class<? extends Mutation>, MutationConverter<Mutation>> converters = createHashMap(5);

	public DelegatingInfrastructureMutationConverter(
		@Nonnull MutationObjectMapper objectMapper,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectMapper, exceptionFactory);

		registerConverter(TransactionMutation.class, new TransactionMutationConverter(objectMapper, exceptionFactory));
	}

	@Nonnull
	@Override
	protected String getAncestorMutationName() {
		return Mutation.class.getSimpleName();
	}
}
