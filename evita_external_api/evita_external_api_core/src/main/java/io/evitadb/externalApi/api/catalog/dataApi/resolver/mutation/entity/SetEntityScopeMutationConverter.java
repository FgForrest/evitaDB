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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.entity;

import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity.SetEntityScopeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.LocalMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link LocalMutationConverter} for resolving {@link SetEntityScopeMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class SetEntityScopeMutationConverter extends LocalMutationConverter<SetEntityScopeMutation> {

	public SetEntityScopeMutationConverter(@Nonnull MutationObjectParser objectParser,
	                                       @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected String getMutationName() {
		return SetEntityScopeMutationDescriptor.THIS.name();
	}

	@Nonnull
	@Override
	protected SetEntityScopeMutation convert(@Nonnull Input input) {
		final Scope scope = input.getRequiredField(SetEntityScopeMutationDescriptor.SCOPE);
		return new SetEntityScopeMutation(scope);
	}
}
