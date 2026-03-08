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

import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexedComponentsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.CreateReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.Output;
import io.evitadb.externalApi.api.resolver.mutation.PropertyObjectListMapper;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link CreateReferenceSchemaMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CreateReferenceSchemaMutationConverter
	extends ReferenceSchemaMutationConverter<CreateReferenceSchemaMutation> {

	public CreateReferenceSchemaMutationConverter(
		@Nonnull MutationObjectMapper objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<CreateReferenceSchemaMutation> getMutationClass() {
		return CreateReferenceSchemaMutation.class;
	}

	@Nonnull
	@Override
	protected CreateReferenceSchemaMutation convertFromInput(@Nonnull Input input) {
		final ScopedReferenceIndexType[] indexedInScopes = input.getOptionalProperty(
			CreateReferenceSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(),
			new PropertyObjectListMapper<>(
				getMutationName(),
				getExceptionFactory(),
				CreateReferenceSchemaMutationDescriptor.INDEXED_IN_SCOPES,
				ScopedReferenceIndexType.class,
				nestedInput -> new ScopedReferenceIndexType(
					nestedInput.getProperty(ScopedDataDescriptor.SCOPE),
					nestedInput.getProperty(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE)
				)
			)
		);

		final ScopedReferenceIndexedComponents[] indexedComponentsInScopes = input.getOptionalProperty(
			CreateReferenceSchemaMutationDescriptor.INDEXED_COMPONENTS_IN_SCOPES.name(),
			new PropertyObjectListMapper<>(
				getMutationName(),
				getExceptionFactory(),
				CreateReferenceSchemaMutationDescriptor.INDEXED_COMPONENTS_IN_SCOPES,
				ScopedReferenceIndexedComponents.class,
				nestedInput -> new ScopedReferenceIndexedComponents(
					nestedInput.getProperty(ScopedDataDescriptor.SCOPE),
					nestedInput.getProperty(ScopedReferenceIndexedComponentsDescriptor.INDEXED_COMPONENTS)
				)
			)
		);

		final ScopedFacetedPartially[] facetedPartiallyInScopes = parseFacetedPartially(
			input,
			CreateReferenceSchemaMutationDescriptor.FACETED_PARTIALLY_IN_SCOPES
		);

		return new CreateReferenceSchemaMutation(
			input.getProperty(ReferenceSchemaMutationDescriptor.NAME),
			input.getProperty(CreateReferenceSchemaMutationDescriptor.DESCRIPTION),
			input.getProperty(CreateReferenceSchemaMutationDescriptor.DEPRECATION_NOTICE),
			input.getProperty(CreateReferenceSchemaMutationDescriptor.CARDINALITY),
			input.getProperty(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE),
			input.getProperty(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED),
			input.getProperty(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE),
			input.getProperty(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED, false),
			indexedInScopes,
			indexedComponentsInScopes,
			input.getProperty(CreateReferenceSchemaMutationDescriptor.FACETED_IN_SCOPES),
			facetedPartiallyInScopes
		);
	}

	@Override
	protected void convertToOutput(
		@Nonnull CreateReferenceSchemaMutation mutation,
		@Nonnull Output output
	) {
		serializeFacetedPartially(mutation.getFacetedPartiallyInScopes(), output);
		super.convertToOutput(mutation, output);
	}
}
