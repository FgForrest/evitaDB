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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.reference;

import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaIndexedMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaIndexedMutation.Builder;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static java.util.Optional.ofNullable;

/**
 * Converts between {@link SetReferenceSchemaIndexedMutation} and {@link GrpcSetReferenceSchemaIndexedMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetReferenceSchemaIndexedMutationConverter implements SchemaMutationConverter<SetReferenceSchemaIndexedMutation, GrpcSetReferenceSchemaIndexedMutation> {
	public static final SetReferenceSchemaIndexedMutationConverter INSTANCE = new SetReferenceSchemaIndexedMutationConverter();

	@Nonnull
	public SetReferenceSchemaIndexedMutation convert(@Nonnull GrpcSetReferenceSchemaIndexedMutation mutation) {
		return new SetReferenceSchemaIndexedMutation(
			mutation.getName(),
			mutation.getInherited() ?
				null :
				mutation.getIndexedInScopesList()
					.stream()
					.map(EvitaEnumConverter::toScope)
					.toArray(Scope[]::new)
		);
	}

	@Nonnull
	public GrpcSetReferenceSchemaIndexedMutation convert(@Nonnull SetReferenceSchemaIndexedMutation mutation) {
		final Builder builder = GrpcSetReferenceSchemaIndexedMutation.newBuilder()
			.setName(mutation.getName());
		final boolean indexedInherited = mutation.getIndexedInScopes() == null;
		builder.setInherited(indexedInherited);
		if (!indexedInherited) {
			ofNullable(mutation.getIndexedInScopes())
				.ifPresentOrElse(
					it -> builder.addAllIndexedInScopes(
						Arrays.stream(it)
							.map(EvitaEnumConverter::toGrpcScope)
							.toList()
					),
					() -> builder.setInherited(true)
				);
		}
		return builder.build();
	}
}
