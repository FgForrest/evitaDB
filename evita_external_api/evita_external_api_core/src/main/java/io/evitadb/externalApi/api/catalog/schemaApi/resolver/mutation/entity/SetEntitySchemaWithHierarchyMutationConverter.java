/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.entity;

import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithHierarchyMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.SetEntitySchemaWithHierarchyMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link SetEntitySchemaWithHierarchyMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class SetEntitySchemaWithHierarchyMutationConverter extends EntitySchemaMutationConverter<SetEntitySchemaWithHierarchyMutation> {

	public SetEntitySchemaWithHierarchyMutationConverter(@Nonnull MutationObjectParser objectParser,
	                                                     @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected String getMutationName() {
		return SetEntitySchemaWithHierarchyMutationDescriptor.THIS.name();
	}

	@Nonnull
	@Override
	protected SetEntitySchemaWithHierarchyMutation convert(@Nonnull Input input) {
		return new SetEntitySchemaWithHierarchyMutation(
			input.getRequiredField(SetEntitySchemaWithHierarchyMutationDescriptor.WITH_HIERARCHY),
			input.getOptionalField(SetEntitySchemaWithHierarchyMutationDescriptor.INDEXED_IN_SCOPES)
		);
	}
}
