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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.associatedData;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.AssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.LocalMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Output;

import javax.annotation.Nonnull;

/**
 * Ancestor abstract implementation for {@link AssociatedDataMutation}s.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class AssociatedDataMutationConverter<M extends AssociatedDataMutation> extends LocalMutationConverter<M> {

	protected AssociatedDataMutationConverter(@Nonnull MutationObjectParser objectParser,
	                                          @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	protected AssociatedDataKey resolveAssociatedDataKey(@Nonnull Input input) {
		return new AssociatedDataKey(
			input.getProperty(AssociatedDataMutationDescriptor.NAME),
			input.getProperty(AssociatedDataMutationDescriptor.LOCALE)
		);
	}

	@Override
	protected void convertToOutput(@Nonnull M mutation, @Nonnull Output output) {
		output.setProperty(AssociatedDataMutationDescriptor.NAME, mutation.getAssociatedDataKey().associatedDataName());
		output.setProperty(AssociatedDataMutationDescriptor.LOCALE, mutation.getAssociatedDataKey().locale());
	}
}
