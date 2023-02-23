/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.entity;

import io.evitadb.api.requestResponse.data.mutation.entity.SetHierarchicalPlacementMutation;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity.SetHierarchicalPlacementMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.LocalMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.InputMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link LocalMutationConverter} for resolving {@link SetHierarchicalPlacementMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class SetHierarchicalPlacementMutationConverter extends LocalMutationConverter<SetHierarchicalPlacementMutation> {

	public SetHierarchicalPlacementMutationConverter(@Nonnull MutationObjectParser objectParser,
	                                                 @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected String getMutationName() {
		return SetHierarchicalPlacementMutationDescriptor.THIS.name();
	}

	@Nonnull
	@Override
	protected SetHierarchicalPlacementMutation convert(@Nonnull InputMutation inputMutation) {
		final Integer parentPrimaryKey = inputMutation.getOptionalField(SetHierarchicalPlacementMutationDescriptor.PARENT_PRIMARY_KEY);
		final int orderAmongSiblings = inputMutation.getRequiredField(SetHierarchicalPlacementMutationDescriptor.ORDER_AMONG_SIBLINGS);
		if (parentPrimaryKey == null) {
			return new SetHierarchicalPlacementMutation(orderAmongSiblings);
		} else {
			return new SetHierarchicalPlacementMutation(parentPrimaryKey, orderAmongSiblings);
		}
	}
}
