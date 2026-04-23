/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.reference;

import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.SetReferenceSchemaFacetedMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.Output;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link SetReferenceSchemaFacetedMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class SetReferenceSchemaFacetedMutationConverter
	extends ReferenceSchemaMutationConverter<SetReferenceSchemaFacetedMutation> {

	public SetReferenceSchemaFacetedMutationConverter(
		@Nonnull MutationObjectMapper objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<SetReferenceSchemaFacetedMutation> getMutationClass() {
		return SetReferenceSchemaFacetedMutation.class;
	}

	@Nonnull
	@Override
	protected SetReferenceSchemaFacetedMutation convertFromInput(@Nonnull Input input) {
		final ScopedFacetedPartially[] facetedPartiallyInScopes = parseFacetedPartially(
			input,
			SetReferenceSchemaFacetedMutationDescriptor.FACETED_PARTIALLY_IN_SCOPES
		);

		return new SetReferenceSchemaFacetedMutation(
			input.getProperty(ReferenceSchemaMutationDescriptor.NAME),
			input.getOptionalProperty(
				SetReferenceSchemaFacetedMutationDescriptor.FACETED_IN_SCOPES.name(),
				Scope[].class
			),
			facetedPartiallyInScopes
		);
	}

	@Override
	protected void convertToOutput(
		@Nonnull SetReferenceSchemaFacetedMutation mutation,
		@Nonnull Output output
	) {
		serializeFacetedPartially(mutation.getFacetedPartiallyInScopes(), output);
		super.convertToOutput(mutation, output);
	}
}
