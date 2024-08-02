/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationAggregateConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.EntitySchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.CreateAssociatedDataSchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.ModifyAssociatedDataSchemaDeprecationNoticeMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.ModifyAssociatedDataSchemaDescriptionMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.ModifyAssociatedDataSchemaNameMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.ModifyAssociatedDataSchemaTypeMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.RemoveAssociatedDataSchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.SetAssociatedDataSchemaLocalizedMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.SetAssociatedDataSchemaNullableMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.attribute.*;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.entity.*;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.reference.*;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutationConverter;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.EntitySchemaMutationAggregateDescriptor.*;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link MutationAggregateConverter} for converting aggregates of {@link EntitySchemaMutation}s.
 * into list of individual mutations.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class EntitySchemaMutationAggregateConverter extends MutationAggregateConverter<LocalEntitySchemaMutation, SchemaMutationConverter<? extends LocalEntitySchemaMutation>> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final Map<String, SchemaMutationConverter<? extends LocalEntitySchemaMutation>> resolvers = createHashMap(55);

	public EntitySchemaMutationAggregateConverter(@Nonnull MutationObjectParser objectParser,
	                                              @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);

		// entity schema mutations
		this.resolvers.put(ALLOW_CURRENCY_IN_ENTITY_SCHEMA_MUTATION.name(), new AllowCurrencyInEntitySchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(ALLOW_EVOLUTION_MODE_IN_ENTITY_SCHEMA_MUTATION.name(), new AllowEvolutionModeInEntitySchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(ALLOW_LOCALE_IN_ENTITY_SCHEMA_MUTATION.name(), new AllowLocaleInEntitySchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(DISALLOW_CURRENCY_IN_ENTITY_SCHEMA_MUTATION.name(), new DisallowCurrencyInEntitySchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(DISALLOW_EVOLUTION_MODE_IN_ENTITY_SCHEMA_MUTATION.name(), new DisallowEvolutionModeInEntitySchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(DISALLOW_LOCALE_IN_ENTITY_SCHEMA_MUTATION.name(), new DisallowLocaleInEntitySchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_ENTITY_SCHEMA_DEPRECATION_NOTICE_MUTATION.name(), new ModifyEntitySchemaDeprecationNoticeMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_ENTITY_SCHEMA_DESCRIPTION_MUTATION.name(), new ModifyEntitySchemaDescriptionMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(SET_ENTITY_SCHEMA_WITH_GENERATED_PRIMARY_KEY_MUTATION.name(), new SetEntitySchemaWithGeneratedPrimaryKeyMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(SET_ENTITY_SCHEMA_WITH_HIERARCHY_MUTATION.name(), new SetEntitySchemaWithHierarchyMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(SET_ENTITY_SCHEMA_WITH_PRICE_MUTATION.name(), new SetEntitySchemaWithPriceMutationConverter(objectParser, exceptionFactory));

		// associated data schema mutations
		this.resolvers.put(CREATE_ASSOCIATED_DATA_SCHEMA_MUTATION.name(), new CreateAssociatedDataSchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_ASSOCIATED_DATA_SCHEMA_DEPRECATION_NOTICE_MUTATION.name(), new ModifyAssociatedDataSchemaDeprecationNoticeMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_ASSOCIATED_DATA_SCHEMA_DESCRIPTION_MUTATION.name(), new ModifyAssociatedDataSchemaDescriptionMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_ASSOCIATED_DATA_SCHEMA_NAME_MUTATION.name(), new ModifyAssociatedDataSchemaNameMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_ASSOCIATED_DATA_SCHEMA_TYPE_MUTATION.name(), new ModifyAssociatedDataSchemaTypeMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(REMOVE_ASSOCIATED_DATA_SCHEMA_MUTATION.name(), new RemoveAssociatedDataSchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(SET_ASSOCIATED_DATA_SCHEMA_LOCALIZED_MUTATION.name(), new SetAssociatedDataSchemaLocalizedMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(SET_ASSOCIATED_DATA_SCHEMA_NULLABLE_MUTATION.name(), new SetAssociatedDataSchemaNullableMutationConverter(objectParser, exceptionFactory));

		// attribute schema mutations
		this.resolvers.put(CREATE_ATTRIBUTE_SCHEMA_MUTATION.name(), new CreateAttributeSchemaMutationConverter(objectParser, exceptionFactory));
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
		this.resolvers.put(USE_GLOBAL_ATTRIBUTE_SCHEMA_MUTATION.name(), new UseGlobalAttributeSchemaMutationConverter(objectParser, exceptionFactory));

		// sortable attribute compounds schema mutations
		this.resolvers.put(CREATE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION.name(), new CreateSortableAttributeCompoundSchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DEPRECATION_NOTICE_MUTATION.name(), new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DESCRIPTION_MUTATION.name(), new ModifySortableAttributeCompoundSchemaDescriptionMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_NAME_MUTATION.name(), new ModifySortableAttributeCompoundSchemaNameMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(REMOVE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION.name(), new RemoveSortableAttributeCompoundSchemaMutationConverter(objectParser, exceptionFactory));

		// reference schema mutations
		this.resolvers.put(CREATE_REFERENCE_SCHEMA_MUTATION.name(), new CreateReferenceSchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_REFERENCE_ATTRIBUTE_SCHEMA_MUTATION.name(), new ModifyReferenceAttributeSchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_REFERENCE_SCHEMA_CARDINALITY_MUTATION.name(), new ModifyReferenceSchemaCardinalityMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_REFERENCE_SCHEMA_DEPRECATION_NOTICE_MUTATION.name(), new ModifyReferenceSchemaDeprecationNoticeMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_REFERENCE_SCHEMA_DESCRIPTION_MUTATION.name(), new ModifyReferenceSchemaDescriptionMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_REFERENCE_SCHEMA_NAME_MUTATION.name(), new ModifyReferenceSchemaNameMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_REFERENCE_SCHEMA_RELATED_ENTITY_GROUP_MUTATION.name(), new ModifyReferenceSchemaRelatedEntityGroupMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(MODIFY_REFERENCE_SCHEMA_RELATED_ENTITY_MUTATION.name(), new ModifyReferenceSchemaRelatedEntityMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(REMOVE_REFERENCE_SCHEMA_MUTATION.name(), new RemoveReferenceSchemaMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(SET_REFERENCE_SCHEMA_FACETED_MUTATION.name(), new SetReferenceSchemaFacetedMutationConverter(objectParser, exceptionFactory));
		this.resolvers.put(SET_REFERENCE_SCHEMA_INDEXED_MUTATION.name(), new SetReferenceSchemaFilterableMutationConverter(objectParser, exceptionFactory));
	}

	@Nonnull
	@Override
	protected String getMutationAggregateName() {
		return EntitySchemaMutationAggregateDescriptor.THIS.name();
	}
}
