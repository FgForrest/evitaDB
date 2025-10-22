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

import io.evitadb.api.requestResponse.schema.mutation.SortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.SetSortableAttributeCompoundSchemaIndexedMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.DelegatingMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
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

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link DelegatingMutationConverter} for converting implementations of {@link SortableAttributeCompoundSchemaMutation}s.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class DelegatingSortableAttributeCompoundSchemaMutationConverter
	extends DelegatingMutationConverter<SortableAttributeCompoundSchemaMutation, SortableAttributeCompoundSchemaMutationConverter<SortableAttributeCompoundSchemaMutation>> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final Map<Class<? extends SortableAttributeCompoundSchemaMutation>, SortableAttributeCompoundSchemaMutationConverter<SortableAttributeCompoundSchemaMutation>> converters = createHashMap(15);

	public DelegatingSortableAttributeCompoundSchemaMutationConverter(
		@Nonnull MutationObjectMapper objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);

		registerConverter(CreateSortableAttributeCompoundSchemaMutation.class, new CreateSortableAttributeCompoundSchemaMutationConverter(objectParser, exceptionFactory));
		registerConverter(ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation.class, new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter(objectParser, exceptionFactory));
		registerConverter(ModifySortableAttributeCompoundSchemaDescriptionMutation.class, new ModifySortableAttributeCompoundSchemaDescriptionMutationConverter(objectParser, exceptionFactory));
		registerConverter(ModifySortableAttributeCompoundSchemaNameMutation.class, new ModifySortableAttributeCompoundSchemaNameMutationConverter(objectParser, exceptionFactory));
		registerConverter(SetSortableAttributeCompoundSchemaIndexedMutation.class, new SetSortableAttributeCompoundIndexedMutationConverter(objectParser, exceptionFactory));
		registerConverter(RemoveSortableAttributeCompoundSchemaMutation.class, new RemoveSortableAttributeCompoundSchemaMutationConverter(objectParser, exceptionFactory));
	}

	@Nonnull
	@Override
	protected String getAncestorMutationName() {
		return SortableAttributeCompoundSchemaMutation.class.getSimpleName();
	}
}
