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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute;

import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSortableMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcSetAttributeSchemaSortableMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Converts between {@link SetAttributeSchemaSortableMutation} and {@link GrpcSetAttributeSchemaSortableMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetAttributeSchemaSortableMutationConverter implements SchemaMutationConverter<SetAttributeSchemaSortableMutation, GrpcSetAttributeSchemaSortableMutation> {
	public static final SetAttributeSchemaSortableMutationConverter INSTANCE = new SetAttributeSchemaSortableMutationConverter();

	@Nonnull
	public SetAttributeSchemaSortableMutation convert(@Nonnull GrpcSetAttributeSchemaSortableMutation mutation) {
		final Scope[] sortableInScopes = mutation.getSortableInScopesList().isEmpty() ?
			(mutation.getSortable() ? Scope.DEFAULT_SCOPES : Scope.NO_SCOPE)
			:
			mutation.getSortableInScopesList()
				.stream()
				.map(EvitaEnumConverter::toScope)
				.toArray(Scope[]::new);

		return new SetAttributeSchemaSortableMutation(
			mutation.getName(),
			sortableInScopes
		);
	}

	@Nonnull
	public GrpcSetAttributeSchemaSortableMutation convert(@Nonnull SetAttributeSchemaSortableMutation mutation) {
		return GrpcSetAttributeSchemaSortableMutation.newBuilder()
			.setName(mutation.getName())
			.setSortable(mutation.isSortable())
			.addAllSortableInScopes(
				Arrays.stream(mutation.getSortableInScopes())
					.map(EvitaEnumConverter::toGrpcScope)
					.toList()
			)
			.build();
	}
}
