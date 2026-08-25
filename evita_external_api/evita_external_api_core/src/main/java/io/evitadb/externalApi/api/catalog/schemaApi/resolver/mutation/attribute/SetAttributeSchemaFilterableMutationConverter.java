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

import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedFilterCapabilities;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaFilterableMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedFilterCapabilitiesDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.*;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.PropertyObjectListMapper;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link SetAttributeSchemaFilterableMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class SetAttributeSchemaFilterableMutationConverter
	extends AttributeSchemaMutationConverter<SetAttributeSchemaFilterableMutation> {

	public SetAttributeSchemaFilterableMutationConverter(
		@Nonnull MutationObjectMapper objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<SetAttributeSchemaFilterableMutation> getMutationClass() {
		return SetAttributeSchemaFilterableMutation.class;
	}

	@Nonnull
	@Override
	protected SetAttributeSchemaFilterableMutation convertFromInput(@Nonnull Input input) {
		// read the mandatory name first, so that an input missing it is refused before any optional property is parsed
		final String name = input.getProperty(AttributeSchemaMutationDescriptor.NAME);
		// the typed overload is mandatory here - the raw one hands back the underlying List and the constructor
		// expects a `Scope[]`, which would only blow up at runtime
		final Scope[] filterableInScopes = input.getOptionalProperty(
			SetAttributeSchemaFilterableMutationDescriptor.FILTERABLE_IN_SCOPES.name(),
			Scope[].class
		);
		// absent property means an older client that knows nothing about capabilities - `null` is turned into
		// "no acceleration anywhere" by the mutation itself
		final ScopedFilterCapabilities[] filterCapabilitiesInScopes = input.getOptionalProperty(
			SetAttributeSchemaFilterableMutationDescriptor.FILTER_CAPABILITIES_IN_SCOPES.name(),
			new PropertyObjectListMapper<>(
				getMutationName(),
				getExceptionFactory(),
				SetAttributeSchemaFilterableMutationDescriptor.FILTER_CAPABILITIES_IN_SCOPES,
				ScopedFilterCapabilities.class,
				nestedInput -> new ScopedFilterCapabilities(
					nestedInput.getProperty(ScopedDataDescriptor.SCOPE),
					nestedInput.getProperty(ScopedFilterCapabilitiesDescriptor.CAPABILITIES)
				)
			)
		);

		return new SetAttributeSchemaFilterableMutation(
			name,
			filterableInScopes,
			filterCapabilitiesInScopes
		);
	}
}
