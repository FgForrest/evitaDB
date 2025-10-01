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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.reference;

import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PropertyObjectListMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.SetReferenceSchemaIndexedMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link SetReferenceSchemaIndexedMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class SetReferenceSchemaIndexedMutationConverter
	extends ReferenceSchemaMutationConverter<SetReferenceSchemaIndexedMutation> {

	public SetReferenceSchemaIndexedMutationConverter(
		@Nonnull MutationObjectMapper objectParser, @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<SetReferenceSchemaIndexedMutation> getMutationClass() {
		return SetReferenceSchemaIndexedMutation.class;
	}

	@Nonnull
	@Override
	protected SetReferenceSchemaIndexedMutation convertFromInput(@Nonnull Input input) {
		final ScopedReferenceIndexType[] indexedInScopes = input.getOptionalProperty(
			SetReferenceSchemaIndexedMutationDescriptor.INDEXED_IN_SCOPES.name(),
			new PropertyObjectListMapper<>(
				getMutationName(),
				getExceptionFactory(),
				SetReferenceSchemaIndexedMutationDescriptor.INDEXED_IN_SCOPES,
				ScopedReferenceIndexType.class,
				nestedInput -> new ScopedReferenceIndexType(
					nestedInput.getProperty(ScopedDataDescriptor.SCOPE),
					nestedInput.getProperty(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE)
				)
			)
		);

		return new SetReferenceSchemaIndexedMutation(
			input.getProperty(ReferenceSchemaMutationDescriptor.NAME),
			indexedInScopes
		);
	}

}
