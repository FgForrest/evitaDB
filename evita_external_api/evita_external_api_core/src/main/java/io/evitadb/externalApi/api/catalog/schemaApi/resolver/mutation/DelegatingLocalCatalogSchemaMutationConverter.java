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

import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.*;
import io.evitadb.api.requestResponse.schema.mutation.catalog.*;
import io.evitadb.externalApi.api.catalog.resolver.mutation.DelegatingMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.attribute.*;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog.CreateEntitySchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog.ModifyCatalogSchemaDescriptionMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog.ModifyEntitySchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog.ModifyEntitySchemaNameMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog.RemoveEntitySchemaMutationConverter;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link DelegatingMutationConverter} for converting implementations of {@link LocalCatalogSchemaMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class DelegatingLocalCatalogSchemaMutationConverter extends
	DelegatingMutationConverter<LocalCatalogSchemaMutation, SchemaMutationConverter<LocalCatalogSchemaMutation>> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final Map<Class<? extends LocalCatalogSchemaMutation>, SchemaMutationConverter<LocalCatalogSchemaMutation>> converters = createHashMap(20);

	public DelegatingLocalCatalogSchemaMutationConverter(@Nonnull MutationObjectMapper objectParser,
	                                                    @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);

		// catalog schema mutations
		registerConverter(ModifyCatalogSchemaDescriptionMutation.class, new ModifyCatalogSchemaDescriptionMutationConverter(objectParser, exceptionFactory));
		registerConverter(AllowEvolutionModeInCatalogSchemaMutation.class, new AllowEvolutionModeInCatalogSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(DisallowEvolutionModeInCatalogSchemaMutation.class, new DisallowEvolutionModeInCatalogSchemaMutationConverter(objectParser, exceptionFactory));

		// global attribute schema mutations
		registerConverter(CreateGlobalAttributeSchemaMutation.class, new CreateGlobalAttributeSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(ModifyAttributeSchemaDefaultValueMutation.class, new ModifyAttributeSchemaDefaultValueMutationConverter(objectParser, exceptionFactory));
		registerConverter(ModifyAttributeSchemaDeprecationNoticeMutation.class, new ModifyAttributeSchemaDeprecationNoticeMutationConverter(objectParser, exceptionFactory));
		registerConverter(ModifyAttributeSchemaDescriptionMutation.class, new ModifyAttributeSchemaDescriptionMutationConverter(objectParser, exceptionFactory));
		registerConverter(ModifyAttributeSchemaNameMutation.class, new ModifyAttributeSchemaNameMutationConverter(objectParser, exceptionFactory));
		registerConverter(ModifyAttributeSchemaTypeMutation.class, new ModifyAttributeSchemaTypeMutationConverter(objectParser, exceptionFactory));
		registerConverter(RemoveAttributeSchemaMutation.class, new RemoveAttributeSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(SetAttributeSchemaFilterableMutation.class, new SetAttributeSchemaFilterableMutationConverter(objectParser, exceptionFactory));
		registerConverter(SetAttributeSchemaLocalizedMutation.class, new SetAttributeSchemaLocalizedMutationConverter(objectParser, exceptionFactory));
		registerConverter(SetAttributeSchemaNullableMutation.class, new SetAttributeSchemaNullableMutationConverter(objectParser, exceptionFactory));
		registerConverter(SetAttributeSchemaRepresentativeMutation.class, new SetAttributeSchemaRepresentativeMutationConverter(objectParser, exceptionFactory));
		registerConverter(SetAttributeSchemaSortableMutation.class, new SetAttributeSchemaSortableMutationConverter(objectParser, exceptionFactory));
		registerConverter(SetAttributeSchemaUniqueMutation.class, new SetAttributeSchemaUniqueMutationConverter(objectParser, exceptionFactory));
		registerConverter(SetAttributeSchemaGloballyUniqueMutation.class, new SetAttributeSchemaGloballyUniqueMutationConverter(objectParser, exceptionFactory));

		// entity schema mutations
		registerConverter(CreateEntitySchemaMutation.class, new CreateEntitySchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(ModifyEntitySchemaMutation.class, new ModifyEntitySchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(ModifyEntitySchemaNameMutation.class, new ModifyEntitySchemaNameMutationConverter(objectParser, exceptionFactory));
		registerConverter(RemoveEntitySchemaMutation.class, new RemoveEntitySchemaMutationConverter(objectParser, exceptionFactory));
	}

	@Nonnull
	@Override
	protected String getAncestorMutationName() {
		return LocalEntitySchemaMutation.class.getSimpleName();
	}
}
