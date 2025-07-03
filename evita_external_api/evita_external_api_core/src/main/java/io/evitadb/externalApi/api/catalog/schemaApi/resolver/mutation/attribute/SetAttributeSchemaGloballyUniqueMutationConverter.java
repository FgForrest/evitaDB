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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.attribute;

import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaGloballyUniqueMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PropertyObjectListMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedGlobalAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.SetAttributeSchemaGloballyUniqueMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link SetAttributeSchemaGloballyUniqueMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class SetAttributeSchemaGloballyUniqueMutationConverter
	extends AttributeSchemaMutationConverter<SetAttributeSchemaGloballyUniqueMutation> {

	public SetAttributeSchemaGloballyUniqueMutationConverter(
		@Nonnull MutationObjectParser objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<SetAttributeSchemaGloballyUniqueMutation> getMutationClass() {
		return SetAttributeSchemaGloballyUniqueMutation.class;
	}

	@Nonnull
	@Override
	protected SetAttributeSchemaGloballyUniqueMutation convertFromInput(@Nonnull Input input) {
		final ScopedGlobalAttributeUniquenessType[] uniqueGloballyInScopes = input.getOptionalProperty(
			SetAttributeSchemaGloballyUniqueMutationDescriptor.UNIQUE_GLOBALLY_IN_SCOPES.name(),
			new PropertyObjectListMapper<>(
				getMutationName(),
				getExceptionFactory(),
				SetAttributeSchemaGloballyUniqueMutationDescriptor.UNIQUE_GLOBALLY_IN_SCOPES,
				ScopedGlobalAttributeUniquenessType.class,
				nestedInput -> new ScopedGlobalAttributeUniquenessType(
					nestedInput.getProperty(ScopedDataDescriptor.SCOPE),
					nestedInput.getProperty(ScopedGlobalAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE)
				)
			)
		);

		return new SetAttributeSchemaGloballyUniqueMutation(
			input.getProperty(AttributeSchemaMutationDescriptor.NAME),
			uniqueGloballyInScopes
		);
	}
}
