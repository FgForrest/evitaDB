/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaAcceleratedMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetAttributeSchemaAcceleratedMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcScopedAttributeFilterAccelerators;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toScopedAttributeFilterAccelerators;

/**
 * Converts between {@link SetAttributeSchemaAcceleratedMutation} and
 * {@link GrpcSetAttributeSchemaAcceleratedMutation} in both directions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetAttributeSchemaAcceleratedMutationConverter
	implements SchemaMutationConverter<SetAttributeSchemaAcceleratedMutation, GrpcSetAttributeSchemaAcceleratedMutation> {
	public static final SetAttributeSchemaAcceleratedMutationConverter INSTANCE =
		new SetAttributeSchemaAcceleratedMutationConverter();

	@Nonnull
	public SetAttributeSchemaAcceleratedMutation convert(
		@Nonnull GrpcSetAttributeSchemaAcceleratedMutation mutation
	) {
		// absent on the wire - proto3 renders that as an empty list, which converts to `null`, i.e. no acceleration
		final ScopedAttributeFilterAccelerators[] acceleratorsInScopes =
			toScopedAttributeFilterAccelerators(mutation.getAcceleratorsInScopesList());
		return new SetAttributeSchemaAcceleratedMutation(mutation.getName(), acceleratorsInScopes);
	}

	@Nonnull
	public GrpcSetAttributeSchemaAcceleratedMutation convert(
		@Nonnull SetAttributeSchemaAcceleratedMutation mutation
	) {
		return GrpcSetAttributeSchemaAcceleratedMutation.newBuilder()
			.setName(mutation.getName())
			.addAllAcceleratorsInScopes(
				toGrpcScopedAttributeFilterAccelerators(mutation.getAcceleratorsInScopes())
			)
			.build();
	}
}
