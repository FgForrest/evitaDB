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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation;

import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationAggregateConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationAggregateDescriptor;
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

import static io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationAggregateDescriptor.*;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link MutationAggregateConverter} for converting aggregates of {@link LocalCatalogSchemaMutation}s.
 * into list of individual mutations.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class LocalCatalogSchemaMutationAggregateConverter extends MutationAggregateConverter<LocalCatalogSchemaMutation, SchemaMutationConverter<? extends LocalCatalogSchemaMutation>> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final Map<String, SchemaMutationConverter<? extends LocalCatalogSchemaMutation>> resolvers = createHashMap(20);

	public LocalCatalogSchemaMutationAggregateConverter(@Nonnull MutationObjectParser objectParser,
	                                                    @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);

		// catalog schema mutations
		this.resolvers.put(MODIFY_CATALOG_SCHEMA_DESCRIPTION_MUTATION.name(), new ModifyCatalogSchemaDescriptionMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(ALLOW_EVOLUTION_MODE_IN_CATALOG_SCHEMA_MUTATION.name(), new AllowEvolutionModeInCatalogSchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(DISALLOW_EVOLUTION_MODE_IN_CATALOG_SCHEMA_MUTATION.name(), new DisallowEvolutionModeInCatalogSchemaMutationConverter(objectParser, exceptionFactory));

		// global attribute schema mutations
		this.resolvers.put(CREATE_GLOBAL_ATTRIBUTE_SCHEMA_MUTATION.name(), new CreateGlobalAttributeSchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_ATTRIBUTE_SCHEMA_DEFAULT_VALUE_MUTATION.name(), new ModifyAttributeSchemaDefaultValueMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_ATTRIBUTE_SCHEMA_DEPRECATION_NOTICE_MUTATION.name(), new ModifyAttributeSchemaDeprecationNoticeMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_ATTRIBUTE_SCHEMA_DESCRIPTION_MUTATION.name(), new ModifyAttributeSchemaDescriptionMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_ATTRIBUTE_SCHEMA_NAME_MUTATION.name(), new ModifyAttributeSchemaNameMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_ATTRIBUTE_SCHEMA_TYPE_MUTATION.name(), new ModifyAttributeSchemaTypeMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(REMOVE_ATTRIBUTE_SCHEMA_MUTATION.name(), new RemoveAttributeSchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(SET_ATTRIBUTE_SCHEMA_FILTERABLE_MUTATION.name(), new SetAttributeSchemaFilterableMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(SET_ATTRIBUTE_SCHEMA_LOCALIZED_MUTATION.name(), new SetAttributeSchemaLocalizedMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(SET_ATTRIBUTE_SCHEMA_NULLABLE_MUTATION.name(), new SetAttributeSchemaNullableMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(SET_ATTRIBUTE_SCHEMA_REPRESENTATIVE_MUTATION.name(), new SetAttributeSchemaRepresentativeMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(SET_ATTRIBUTE_SCHEMA_SORTABLE_MUTATION.name(), new SetAttributeSchemaSortableMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(SET_ATTRIBUTE_SCHEMA_UNIQUE_MUTATION.name(), new SetAttributeSchemaUniqueMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(SET_ATTRIBUTE_SCHEMA_GLOBALLY_UNIQUE_MUTATION.name(), new SetAttributeSchemaGloballyUniqueMutationConverter(objectParser, exceptionFactory));

		// entity schema mutations
		this.resolvers.put(CREATE_ENTITY_SCHEMA_MUTATION.name(), new CreateEntitySchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_ENTITY_SCHEMA_MUTATION.name(), new ModifyEntitySchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_ENTITY_SCHEMA_NAME_MUTATION.name(), new ModifyEntitySchemaNameMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(REMOVE_ENTITY_SCHEMA_MUTATION.name(), new RemoveEntitySchemaMutationConverter(objectParser, exceptionFactory));
	}

	@Nonnull
	@Override
	protected String getMutationAggregateName() {
		return LocalCatalogSchemaMutationAggregateDescriptor.THIS.name();
	}
}
