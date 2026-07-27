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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.associatedData;

import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaConflictResolutionOverrideMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetAssociatedDataSchemaConflictResolutionOverrideMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toConflictResolutionOverride;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcConflictResolutionOverride;

/**
 * Converts between {@link SetAssociatedDataSchemaConflictResolutionOverrideMutation} and
 * {@link GrpcSetAssociatedDataSchemaConflictResolutionOverrideMutation} in both directions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetAssociatedDataSchemaConflictResolutionOverrideMutationConverter implements SchemaMutationConverter<SetAssociatedDataSchemaConflictResolutionOverrideMutation, GrpcSetAssociatedDataSchemaConflictResolutionOverrideMutation> {
	public static final SetAssociatedDataSchemaConflictResolutionOverrideMutationConverter INSTANCE = new SetAssociatedDataSchemaConflictResolutionOverrideMutationConverter();

	@Nonnull
	public SetAssociatedDataSchemaConflictResolutionOverrideMutation convert(@Nonnull GrpcSetAssociatedDataSchemaConflictResolutionOverrideMutation mutation) {
		return new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
			mutation.getName(),
			toConflictResolutionOverride(mutation.getConflictResolutionOverride())
		);
	}

	@Nonnull
	public GrpcSetAssociatedDataSchemaConflictResolutionOverrideMutation convert(@Nonnull SetAssociatedDataSchemaConflictResolutionOverrideMutation mutation) {
		return GrpcSetAssociatedDataSchemaConflictResolutionOverrideMutation.newBuilder()
			.setName(mutation.getName())
			.setConflictResolutionOverride(toGrpcConflictResolutionOverride(mutation.getConflictResolutionOverride()))
			.build();
	}
}
