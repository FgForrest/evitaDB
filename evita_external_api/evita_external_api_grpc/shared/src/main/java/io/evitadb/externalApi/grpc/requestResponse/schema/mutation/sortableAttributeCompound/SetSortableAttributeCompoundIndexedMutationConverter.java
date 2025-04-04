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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound;

import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Converts between {@link SetSortableAttributeCompoundIndexedMutation} and {@link GrpcSetSortableAttributeCompoundIndexedMutation} in both directions.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetSortableAttributeCompoundIndexedMutationConverter implements SchemaMutationConverter<SetSortableAttributeCompoundIndexedMutation, GrpcSetSortableAttributeCompoundIndexedMutation> {
	public static final SetSortableAttributeCompoundIndexedMutationConverter INSTANCE = new SetSortableAttributeCompoundIndexedMutationConverter();

	@Nonnull
	public SetSortableAttributeCompoundIndexedMutation convert(@Nonnull GrpcSetSortableAttributeCompoundIndexedMutation mutation) {
		return new SetSortableAttributeCompoundIndexedMutation(
			mutation.getName(),
			mutation.getIndexedInScopesList()
				.stream()
				.map(EvitaEnumConverter::toScope)
				.toArray(Scope[]::new)
		);
	}

	@Nonnull
	public GrpcSetSortableAttributeCompoundIndexedMutation convert(@Nonnull SetSortableAttributeCompoundIndexedMutation mutation) {
		final GrpcSetSortableAttributeCompoundIndexedMutation.Builder builder = GrpcSetSortableAttributeCompoundIndexedMutation.newBuilder()
			.setName(mutation.getName())
			.addAllIndexedInScopes(
				Arrays.stream(mutation.getIndexedInScopes())
					.map(EvitaEnumConverter::toGrpcScope)
					.toList()
			);

		return builder.build();
	}
}
