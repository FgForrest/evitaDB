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

import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ReferenceSortableAttributeCompoundSchemaMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationAggregateConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.SortableAttributeCompoundSchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.SortableAttributeCompoundSchemaMutationConverter;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.SortableAttributeCompoundSchemaMutationAggregateDescriptor.*;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link MutationAggregateConverter} for converting aggregates of {@link ReferenceSortableAttributeCompoundSchemaMutation}s.
 * into list of individual mutations.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class SortableAttributeCompoundSchemaMutationAggregateConverter extends MutationAggregateConverter<ReferenceSortableAttributeCompoundSchemaMutation, SortableAttributeCompoundSchemaMutationConverter<ReferenceSortableAttributeCompoundSchemaMutation>> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final Map<String, SortableAttributeCompoundSchemaMutationConverter<ReferenceSortableAttributeCompoundSchemaMutation>> converters = createHashMap(15);

	public SortableAttributeCompoundSchemaMutationAggregateConverter(
		@Nonnull MutationObjectParser objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);

		registerConverter(CREATE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION.name(), new CreateSortableAttributeCompoundSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DEPRECATION_NOTICE_MUTATION.name(), new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DESCRIPTION_MUTATION.name(), new ModifySortableAttributeCompoundSchemaDescriptionMutationConverter(objectParser, exceptionFactory));
		registerConverter(MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_NAME_MUTATION.name(), new ModifySortableAttributeCompoundSchemaNameMutationConverter(objectParser, exceptionFactory));
		registerConverter(SET_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_INDEXED_MUTATION.name(), new SetSortableAttributeCompoundIndexedMutationConverter(objectParser, exceptionFactory));
		registerConverter(REMOVE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION.name(), new RemoveSortableAttributeCompoundSchemaMutationConverter(objectParser, exceptionFactory));
	}

	@Nonnull
	@Override
	protected String getMutationAggregateName() {
		return SortableAttributeCompoundSchemaMutationAggregateDescriptor.THIS.name();
	}
}
