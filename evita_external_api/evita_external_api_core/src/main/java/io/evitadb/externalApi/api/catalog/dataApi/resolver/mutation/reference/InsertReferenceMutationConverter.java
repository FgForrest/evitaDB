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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.reference;

import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.InsertReferenceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.LocalMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Output;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link LocalMutationConverter} for resolving {@link InsertReferenceMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class InsertReferenceMutationConverter extends ReferenceMutationConverter<InsertReferenceMutation> {

	public InsertReferenceMutationConverter(@Nonnull MutationObjectParser objectParser,
	                                        @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<InsertReferenceMutation> getMutationClass() {
		return InsertReferenceMutation.class;
	}

	@Nonnull
	@Override
	protected InsertReferenceMutation convertFromInput(@Nonnull Input input) {
		return new InsertReferenceMutation(
			resolveReferenceKey(input),
			input.getProperty(InsertReferenceMutationDescriptor.CARDINALITY),
			input.getProperty(InsertReferenceMutationDescriptor.REFERENCED_ENTITY_TYPE)
		);
	}

	@Override
	protected void convertToOutput(@Nonnull InsertReferenceMutation mutation, @Nonnull Output output) {
		output.setProperty(InsertReferenceMutationDescriptor.CARDINALITY, mutation.getReferenceCardinality());
		output.setProperty(InsertReferenceMutationDescriptor.REFERENCED_ENTITY_TYPE, mutation.getReferencedEntityType());
		super.convertToOutput(mutation, output);
	}
}
