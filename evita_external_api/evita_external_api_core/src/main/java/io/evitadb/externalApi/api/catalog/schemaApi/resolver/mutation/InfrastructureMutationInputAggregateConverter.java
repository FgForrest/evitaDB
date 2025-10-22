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
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationInputAggregateConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.InfrastructureMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.transaction.resolver.mutation.TransactionMutationConverter;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.InfrastructureMutationInputAggregateDescriptor.TRANSACTION_MUTATION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link MutationInputAggregateConverter} for converting aggregates of infrastructure mutations into
 * a list of individual mutations.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class InfrastructureMutationInputAggregateConverter
	extends MutationInputAggregateConverter<Mutation, MutationConverter<Mutation>> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final Map<String, MutationConverter<Mutation>> converters = createHashMap(5);

	public InfrastructureMutationInputAggregateConverter(
		@Nonnull MutationObjectMapper objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);

		registerConverter(TRANSACTION_MUTATION.name(), new TransactionMutationConverter(objectParser, exceptionFactory));
	}

	@Nonnull
	@Override
	protected String getMutationAggregateName() {
		return InfrastructureMutationInputAggregateDescriptor.THIS_INPUT.name();
	}
}
