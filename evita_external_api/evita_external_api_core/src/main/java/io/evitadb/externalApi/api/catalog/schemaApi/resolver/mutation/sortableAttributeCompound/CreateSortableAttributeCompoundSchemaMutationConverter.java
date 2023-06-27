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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound;

import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.InputMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.FieldObjectListMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeElementDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.SortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link CreateSortableAttributeCompoundSchemaMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CreateSortableAttributeCompoundSchemaMutationConverter
	extends SortableAttributeCompoundSchemaMutationConverter<CreateSortableAttributeCompoundSchemaMutation> {

	public CreateSortableAttributeCompoundSchemaMutationConverter(@Nonnull MutationObjectParser objectParser,
	                                                              @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected String getMutationName() {
		return CreateSortableAttributeCompoundSchemaMutationDescriptor.THIS.name();
	}

	@Nonnull
	@Override
	protected CreateSortableAttributeCompoundSchemaMutation convert(@Nonnull InputMutation inputMutation) {
		final AttributeElement[] attributeElements = inputMutation.getRequiredField(
			CreateSortableAttributeCompoundSchemaMutationDescriptor.ATTRIBUTE_ELEMENTS.name(),
			new FieldObjectListMapper<>(
				getMutationName(),
				getExceptionFactory(),
				CreateSortableAttributeCompoundSchemaMutationDescriptor.ATTRIBUTE_ELEMENTS,
				AttributeElement.class,
				input -> new AttributeElement(
					input.getRequiredField(AttributeElementDescriptor.ATTRIBUTE_NAME),
					input.getRequiredField(AttributeElementDescriptor.DIRECTION),
					input.getRequiredField(AttributeElementDescriptor.BEHAVIOUR)
				)
			)
		);

		return new CreateSortableAttributeCompoundSchemaMutation(
			inputMutation.getRequiredField(SortableAttributeCompoundSchemaMutationDescriptor.NAME),
			inputMutation.getOptionalField(CreateSortableAttributeCompoundSchemaMutationDescriptor.DESCRIPTION),
			inputMutation.getOptionalField(CreateSortableAttributeCompoundSchemaMutationDescriptor.DEPRECATION_NOTICE),
			attributeElements
		);
	}

}
