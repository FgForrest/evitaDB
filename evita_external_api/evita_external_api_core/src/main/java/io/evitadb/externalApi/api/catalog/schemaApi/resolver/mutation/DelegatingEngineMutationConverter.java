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

import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RestoreCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.DelegatingMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.engine.*;
import io.evitadb.externalApi.api.transaction.resolver.mutation.TransactionMutationConverter;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class DelegatingEngineMutationConverter extends DelegatingMutationConverter<EngineMutation<?>, EngineMutationConverter<EngineMutation<?>>> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final Map<Class<? extends EngineMutation<?>>, EngineMutationConverter<EngineMutation<?>>> converters = createHashMap(5);

	public DelegatingEngineMutationConverter(
		@Nonnull MutationObjectMapper objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);

		registerConverter(CreateCatalogSchemaMutation.class, new CreateCatalogSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(RestoreCatalogSchemaMutation.class, new RestoreCatalogSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(MakeCatalogAliveMutation.class, new MakeCatalogAliveMutationConverter(objectParser, exceptionFactory));
		registerConverter(DuplicateCatalogMutation.class, new DuplicateCatalogMutationConverter(objectParser, exceptionFactory));
		registerConverter(SetCatalogMutabilityMutation.class, new SetCatalogMutabilityMutationConverter(objectParser, exceptionFactory));
		registerConverter(SetCatalogStateMutation.class, new SetCatalogStateMutationConverter(objectParser, exceptionFactory));
		registerConverter(ModifyCatalogSchemaMutation.class, new ModifyCatalogSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(ModifyCatalogSchemaNameMutation.class, new ModifyCatalogSchemaNameMutationConverter(objectParser, exceptionFactory));
		registerConverter(RemoveCatalogSchemaMutation.class, new RemoveCatalogSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(TransactionMutation.class, new TransactionMutationConverter(objectParser, exceptionFactory));
	}

	@Nonnull
	@Override
	protected String getAncestorMutationName() {
		return EngineMutation.class.getSimpleName();
	}
}
