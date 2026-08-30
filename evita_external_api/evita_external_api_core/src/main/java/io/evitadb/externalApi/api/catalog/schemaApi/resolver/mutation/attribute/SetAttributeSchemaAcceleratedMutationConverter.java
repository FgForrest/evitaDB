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

import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaAcceleratedMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeFilterAcceleratorsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.SetAttributeSchemaAcceleratedMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.PropertyObjectListMapper;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link SetAttributeSchemaAcceleratedMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class SetAttributeSchemaAcceleratedMutationConverter
	extends AttributeSchemaMutationConverter<SetAttributeSchemaAcceleratedMutation> {

	public SetAttributeSchemaAcceleratedMutationConverter(
		@Nonnull MutationObjectMapper objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<SetAttributeSchemaAcceleratedMutation> getMutationClass() {
		return SetAttributeSchemaAcceleratedMutation.class;
	}

	@Nonnull
	@Override
	protected SetAttributeSchemaAcceleratedMutation convertFromInput(@Nonnull Input input) {
		// the property is optional - omitting it yields `null`, which the mutation reads as "no acceleration anywhere"
		final ScopedAttributeFilterAccelerators[] acceleratorsInScopes = input.getOptionalProperty(
			SetAttributeSchemaAcceleratedMutationDescriptor.ACCELERATORS_IN_SCOPES.name(),
			new PropertyObjectListMapper<>(
				getMutationName(),
				getExceptionFactory(),
				SetAttributeSchemaAcceleratedMutationDescriptor.ACCELERATORS_IN_SCOPES,
				ScopedAttributeFilterAccelerators.class,
				nestedInput -> new ScopedAttributeFilterAccelerators(
					nestedInput.getProperty(ScopedDataDescriptor.SCOPE),
					nestedInput.getProperty(ScopedAttributeFilterAcceleratorsDescriptor.ACCELERATORS)
				)
			)
		);

		return new SetAttributeSchemaAcceleratedMutation(
			input.getProperty(AttributeSchemaMutationDescriptor.NAME),
			acceleratorsInScopes
		);
	}
}
