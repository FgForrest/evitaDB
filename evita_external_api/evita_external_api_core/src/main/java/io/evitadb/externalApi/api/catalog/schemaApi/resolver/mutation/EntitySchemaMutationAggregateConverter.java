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

import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationAggregateConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
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
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutationConverter;
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
public class EntitySchemaMutationAggregateConverter extends MutationAggregateConverter<LocalEntitySchemaMutation, SchemaMutationConverter<LocalEntitySchemaMutation>> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final Map<String, SchemaMutationConverter<LocalEntitySchemaMutation>> converters = createHashMap(55);

	public EntitySchemaMutationAggregateConverter(@Nonnull MutationObjectMapper objectParser,
	                                              @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);

		// entity schema mutations
		registerConverter(ALLOW_CURRENCY_IN_ENTITY_SCHEMA_MUTATION.name(), new AllowCurrencyInEntitySchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(ALLOW_EVOLUTION_MODE_IN_ENTITY_SCHEMA_MUTATION.name(), new AllowEvolutionModeInEntitySchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(ALLOW_LOCALE_IN_ENTITY_SCHEMA_MUTATION.name(), new AllowLocaleInEntitySchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(DISALLOW_CURRENCY_IN_ENTITY_SCHEMA_MUTATION.name(), new DisallowCurrencyInEntitySchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(DISALLOW_EVOLUTION_MODE_IN_ENTITY_SCHEMA_MUTATION.name(), new DisallowEvolutionModeInEntitySchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(DISALLOW_LOCALE_IN_ENTITY_SCHEMA_MUTATION.name(), new DisallowLocaleInEntitySchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_ENTITY_SCHEMA_DEPRECATION_NOTICE_MUTATION.name(), new ModifyEntitySchemaDeprecationNoticeMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_ENTITY_SCHEMA_DESCRIPTION_MUTATION.name(), new ModifyEntitySchemaDescriptionMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_ENTITY_SCHEMA_WITH_GENERATED_PRIMARY_KEY_MUTATION.name(), new SetEntitySchemaWithGeneratedPrimaryKeyMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_ENTITY_SCHEMA_WITH_HIERARCHY_MUTATION.name(), new SetEntitySchemaWithHierarchyMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_ENTITY_SCHEMA_WITH_PRICE_MUTATION.name(), new SetEntitySchemaWithPriceMutationConverter(objectParser, exceptionFactory));

		// associated data schema mutations
		registerConverter(CREATE_ASSOCIATED_DATA_SCHEMA_MUTATION.name(), new CreateAssociatedDataSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_ASSOCIATED_DATA_SCHEMA_DEPRECATION_NOTICE_MUTATION.name(), new ModifyAssociatedDataSchemaDeprecationNoticeMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_ASSOCIATED_DATA_SCHEMA_DESCRIPTION_MUTATION.name(), new ModifyAssociatedDataSchemaDescriptionMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_ASSOCIATED_DATA_SCHEMA_NAME_MUTATION.name(), new ModifyAssociatedDataSchemaNameMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_ASSOCIATED_DATA_SCHEMA_TYPE_MUTATION.name(), new ModifyAssociatedDataSchemaTypeMutationConverter(objectParser, exceptionFactory));
		registerConverter(REMOVE_ASSOCIATED_DATA_SCHEMA_MUTATION.name(), new RemoveAssociatedDataSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_ASSOCIATED_DATA_SCHEMA_LOCALIZED_MUTATION.name(), new SetAssociatedDataSchemaLocalizedMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_ASSOCIATED_DATA_SCHEMA_NULLABLE_MUTATION.name(), new SetAssociatedDataSchemaNullableMutationConverter(objectParser, exceptionFactory));

		// attribute schema mutations
		registerConverter(CREATE_ATTRIBUTE_SCHEMA_MUTATION.name(), new CreateAttributeSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_ATTRIBUTE_SCHEMA_DEFAULT_VALUE_MUTATION.name(), new ModifyAttributeSchemaDefaultValueMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_ATTRIBUTE_SCHEMA_DEPRECATION_NOTICE_MUTATION.name(), new ModifyAttributeSchemaDeprecationNoticeMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_ATTRIBUTE_SCHEMA_DESCRIPTION_MUTATION.name(), new ModifyAttributeSchemaDescriptionMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_ATTRIBUTE_SCHEMA_NAME_MUTATION.name(), new ModifyAttributeSchemaNameMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_ATTRIBUTE_SCHEMA_TYPE_MUTATION.name(), new ModifyAttributeSchemaTypeMutationConverter(objectParser, exceptionFactory));
		registerConverter(REMOVE_ATTRIBUTE_SCHEMA_MUTATION.name(), new RemoveAttributeSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_ATTRIBUTE_SCHEMA_FILTERABLE_MUTATION.name(), new SetAttributeSchemaFilterableMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_ATTRIBUTE_SCHEMA_LOCALIZED_MUTATION.name(), new SetAttributeSchemaLocalizedMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_ATTRIBUTE_SCHEMA_NULLABLE_MUTATION.name(), new SetAttributeSchemaNullableMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_ATTRIBUTE_SCHEMA_REPRESENTATIVE_MUTATION.name(), new SetAttributeSchemaRepresentativeMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_ATTRIBUTE_SCHEMA_SORTABLE_MUTATION.name(), new SetAttributeSchemaSortableMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_ATTRIBUTE_SCHEMA_UNIQUE_MUTATION.name(), new SetAttributeSchemaUniqueMutationConverter(objectParser, exceptionFactory));
		registerConverter(USE_GLOBAL_ATTRIBUTE_SCHEMA_MUTATION.name(), new UseGlobalAttributeSchemaMutationConverter(objectParser, exceptionFactory));

		// sortable attribute compounds schema mutations
		registerConverter(CREATE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION.name(), new CreateSortableAttributeCompoundSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DEPRECATION_NOTICE_MUTATION.name(), new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DESCRIPTION_MUTATION.name(), new ModifySortableAttributeCompoundSchemaDescriptionMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_NAME_MUTATION.name(), new ModifySortableAttributeCompoundSchemaNameMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_INDEXED_MUTATION.name(), new SetSortableAttributeCompoundIndexedMutationConverter(objectParser, exceptionFactory));
		registerConverter(REMOVE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION.name(), new RemoveSortableAttributeCompoundSchemaMutationConverter(objectParser, exceptionFactory));

		// reference schema mutations
		registerConverter(CREATE_REFERENCE_SCHEMA_MUTATION.name(), new CreateReferenceSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(CREATE_REFLECTED_REFERENCE_SCHEMA_MUTATION.name(), new CreateReflectedReferenceSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_REFERENCE_ATTRIBUTE_SCHEMA_MUTATION.name(), new ModifyReferenceAttributeSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_REFERENCE_SCHEMA_CARDINALITY_MUTATION.name(), new ModifyReferenceSchemaCardinalityMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_REFERENCE_SCHEMA_DEPRECATION_NOTICE_MUTATION.name(), new ModifyReferenceSchemaDeprecationNoticeMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_REFERENCE_SCHEMA_DESCRIPTION_MUTATION.name(), new ModifyReferenceSchemaDescriptionMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_REFERENCE_SCHEMA_NAME_MUTATION.name(), new ModifyReferenceSchemaNameMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_REFERENCE_SCHEMA_RELATED_ENTITY_GROUP_MUTATION.name(), new ModifyReferenceSchemaRelatedEntityGroupMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_REFERENCE_SCHEMA_RELATED_ENTITY_MUTATION.name(), new ModifyReferenceSchemaRelatedEntityMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_REFERENCE_SCHEMA_ATTRIBUTE_INHERITANCE_MUTATION.name(), new ModifyReflectedReferenceAttributeInheritanceSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(REMOVE_REFERENCE_SCHEMA_MUTATION.name(), new RemoveReferenceSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_REFERENCE_SCHEMA_FACETED_MUTATION.name(), new SetReferenceSchemaFacetedMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_REFERENCE_SCHEMA_INDEXED_MUTATION.name(), new SetReferenceSchemaIndexedMutationConverter(objectParser, exceptionFactory));
	}

	@Nonnull
	@Override
	protected String getMutationAggregateName() {
		return EntitySchemaMutationAggregateDescriptor.THIS.name();
	}
}
