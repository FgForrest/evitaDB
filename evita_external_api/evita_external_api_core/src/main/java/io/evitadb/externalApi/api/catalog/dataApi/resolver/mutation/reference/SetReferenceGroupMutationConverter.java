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

import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.SetReferenceGroupMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.LocalMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Output;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link LocalMutationConverter} for resolving {@link SetReferenceGroupMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class SetReferenceGroupMutationConverter extends ReferenceMutationConverter<SetReferenceGroupMutation> {

	public SetReferenceGroupMutationConverter(@Nonnull MutationObjectParser objectParser,
	                                          @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<SetReferenceGroupMutation> getMutationClass() {
		return SetReferenceGroupMutation.class;
	}

	@Nonnull
	@Override
	protected SetReferenceGroupMutation convertFromInput(@Nonnull Input input) {
		return new SetReferenceGroupMutation(
			resolveReferenceKey(input),
			input.getProperty(SetReferenceGroupMutationDescriptor.GROUP_TYPE),
			input.getProperty(SetReferenceGroupMutationDescriptor.GROUP_PRIMARY_KEY)
		);
	}

	@Override
	protected void convertToOutput(@Nonnull SetReferenceGroupMutation mutation, @Nonnull Output output) {
		output.setProperty(SetReferenceGroupMutationDescriptor.GROUP_TYPE, mutation.getGroupType());
		output.setProperty(SetReferenceGroupMutationDescriptor.GROUP_PRIMARY_KEY, mutation.getGroupPrimaryKey());
		super.convertToOutput(mutation, output);
	}
}
