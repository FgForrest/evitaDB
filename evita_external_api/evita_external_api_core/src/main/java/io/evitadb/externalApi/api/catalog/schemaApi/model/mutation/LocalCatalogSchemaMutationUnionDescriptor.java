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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation;

import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.CreateEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyCatalogSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyEntitySchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.RemoveEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.model.UnionDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;

/**
 * Union descriptor of all possible types of schema mutations for catalog schema modification.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface LocalCatalogSchemaMutationUnionDescriptor {

	UnionDescriptor THIS = UnionDescriptor.builder()
		.name("LocalCatalogSchemaMutationUnion")
		.description("Lists all possible types of schema mutations for catalog schema modification.")
		.discriminator(MutationDescriptor.MUTATION_TYPE)

		.type(ModifyCatalogSchemaDescriptionMutationDescriptor.THIS)
		.type(AllowEvolutionModeInCatalogSchemaMutationDescriptor.THIS)
		.type(DisallowEvolutionModeInCatalogSchemaMutationDescriptor.THIS)
		.type(CreateEntitySchemaMutationDescriptor.THIS)
		.type(ModifyEntitySchemaMutationDescriptor.THIS)
		.type(ModifyEntitySchemaNameMutationDescriptor.THIS)
		.type(RemoveEntitySchemaMutationDescriptor.THIS)

		.type(CreateGlobalAttributeSchemaMutationDescriptor.THIS)
		.type(ModifyAttributeSchemaDefaultValueMutationDescriptor.THIS)
		.type(ModifyAttributeSchemaDeprecationNoticeMutationDescriptor.THIS)
		.type(ModifyAttributeSchemaDescriptionMutationDescriptor.THIS)
		.type(ModifyAttributeSchemaNameMutationDescriptor.THIS)
		.type(ModifyAttributeSchemaTypeMutationDescriptor.THIS)
		.type(RemoveAttributeSchemaMutationDescriptor.THIS)
		.type(SetAttributeSchemaFilterableMutationDescriptor.THIS)
		.type(SetAttributeSchemaLocalizedMutationDescriptor.THIS)
		.type(SetAttributeSchemaNullableMutationDescriptor.THIS)
		.type(SetAttributeSchemaRepresentativeMutationDescriptor.THIS)
		.type(SetAttributeSchemaSortableMutationDescriptor.THIS)
		.type(SetAttributeSchemaUniqueMutationDescriptor.THIS)
		.type(SetAttributeSchemaGloballyUniqueMutationDescriptor.THIS)

		.build();
}
