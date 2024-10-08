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

import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation.Builder;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

import static java.util.Optional.ofNullable;

/**
 * Converts between {@link SetReferenceSchemaFacetedMutation} and {@link GrpcSetReferenceSchemaFacetedMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetReferenceSchemaFacetedMutationConverter implements SchemaMutationConverter<SetReferenceSchemaFacetedMutation, GrpcSetReferenceSchemaFacetedMutation> {
	public static final SetReferenceSchemaFacetedMutationConverter INSTANCE = new SetReferenceSchemaFacetedMutationConverter();

	@Nonnull
	public SetReferenceSchemaFacetedMutation convert(@Nonnull GrpcSetReferenceSchemaFacetedMutation mutation) {
		return new SetReferenceSchemaFacetedMutation(
			mutation.getName(),
			mutation.getInherited() ? null : mutation.getFaceted()
		);
	}

	@Nonnull
	public GrpcSetReferenceSchemaFacetedMutation convert(@Nonnull SetReferenceSchemaFacetedMutation mutation) {
		final Builder builder = GrpcSetReferenceSchemaFacetedMutation.newBuilder()
			.setName(mutation.getName());
		ofNullable(mutation.getFaceted())
			.ifPresentOrElse(
				builder::setFaceted,
				() -> builder.setInherited(true)
			);
		return builder.build();
	}
}
