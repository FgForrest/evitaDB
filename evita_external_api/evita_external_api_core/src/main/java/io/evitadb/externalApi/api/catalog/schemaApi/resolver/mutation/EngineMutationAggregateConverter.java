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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation;

import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationAggregateConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.EngineMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.engine.*;
import io.evitadb.externalApi.api.transaction.resolver.mutation.TransactionMutationConverter;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.EngineMutationAggregateDescriptor.*;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Aggregate converter that handles the resolution of top-level catalog schema mutations from external API requests.
 * This converter serves as a central point for processing various types of top-level catalog schema mutations,
 * delegating to specific converters based on the mutation type and providing a unified interface for
 * catalog schema mutation resolution.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class EngineMutationAggregateConverter extends MutationAggregateConverter<EngineMutation<?>, EngineMutationConverter<EngineMutation<?>>> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final Map<String, EngineMutationConverter<EngineMutation<?>>> converters = createHashMap(5);

	public EngineMutationAggregateConverter(
		@Nonnull MutationObjectMapper objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);

		registerConverter(CREATE_CATALOG_SCHEMA_MUTATION.name(), new CreateCatalogSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(RESTORE_CATALOG_SCHEMA_MUTATION.name(), new RestoreCatalogSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(MAKE_CATALOG_ALIVE_MUTATION.name(), new MakeCatalogAliveMutationConverter(objectParser, exceptionFactory));
		registerConverter(DUPLICATE_CATALOG_MUTATION.name(), new DuplicateCatalogMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_CATALOG_MUTABILITY_MUTATION.name(), new SetCatalogMutabilityMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_CATALOG_STATE_MUTATION.name(), new SetCatalogStateMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_CATALOG_SCHEMA_MUTATION.name(), new ModifyCatalogSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_CATALOG_SCHEMA_NAME_MUTATION.name(), new ModifyCatalogSchemaNameMutationConverter(objectParser, exceptionFactory));
		registerConverter(REMOVE_CATALOG_SCHEMA_MUTATION.name(), new RemoveCatalogSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(TRANSACTION_MUTATION.name(), new TransactionMutationConverter(objectParser, exceptionFactory));
	}

	@Nonnull
	@Override
	protected String getMutationAggregateName() {
		return EngineMutationAggregateDescriptor.THIS.name();
	}
}
