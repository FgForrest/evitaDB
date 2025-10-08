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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.reference;

import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.LocalMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link LocalMutationConverter} for resolving {@link RemoveReferenceGroupMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class RemoveReferenceGroupMutationConverter extends ReferenceMutationConverter<RemoveReferenceGroupMutation> {

	public RemoveReferenceGroupMutationConverter(@Nonnull MutationObjectMapper objectParser,
	                                             @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<RemoveReferenceGroupMutation> getMutationClass() {
		return RemoveReferenceGroupMutation.class;
	}

	@Nonnull
	@Override
	protected RemoveReferenceGroupMutation convertFromInput(@Nonnull Input input) {
		return new RemoveReferenceGroupMutation(resolveReferenceKey(input));
	}
}
