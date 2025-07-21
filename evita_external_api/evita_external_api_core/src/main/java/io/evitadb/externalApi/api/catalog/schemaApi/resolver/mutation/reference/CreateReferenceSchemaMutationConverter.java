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

import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.externalApi.api.catalog.resolver.mutation.FieldObjectListMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.CreateReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link CreateReferenceSchemaMutation}.
 *
 * TODO JNO - backward compatibility - this converter is not backward compatible with previous versions of EvitaDB.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CreateReferenceSchemaMutationConverter extends ReferenceSchemaMutationConverter<CreateReferenceSchemaMutation> {

	public CreateReferenceSchemaMutationConverter(@Nonnull MutationObjectParser objectParser,
	                                              @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected String getMutationName() {
		return CreateReferenceSchemaMutationDescriptor.THIS.name();
	}

	@Nonnull
	@Override
	protected CreateReferenceSchemaMutation convert(@Nonnull Input input) {
		final ScopedReferenceIndexType[] indexedInScopes = input.getOptionalField(
			CreateReferenceSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(),
			new FieldObjectListMapper<>(
				getMutationName(),
				getExceptionFactory(),
				CreateReferenceSchemaMutationDescriptor.INDEXED_IN_SCOPES,
				ScopedReferenceIndexType.class,
				nestedInput -> new ScopedReferenceIndexType(
					nestedInput.getRequiredField(ScopedReferenceIndexTypeDescriptor.SCOPE),
					nestedInput.getRequiredField(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE)
				)
			)
		);

		return new CreateReferenceSchemaMutation(
			input.getRequiredField(ReferenceSchemaMutationDescriptor.NAME),
			input.getOptionalField(CreateReferenceSchemaMutationDescriptor.DESCRIPTION),
			input.getOptionalField(CreateReferenceSchemaMutationDescriptor.DEPRECATION_NOTICE),
			input.getOptionalField(CreateReferenceSchemaMutationDescriptor.CARDINALITY),
			input.getRequiredField(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE),
			input.getRequiredField(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED),
			input.getOptionalField(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE),
			input.getOptionalField(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED, false),
			indexedInScopes,
			input.getOptionalField(CreateReferenceSchemaMutationDescriptor.FACETED_IN_SCOPES)
		);
	}
}
