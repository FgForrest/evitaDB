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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.reference;

import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaConflictResolutionOverrideMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaConflictResolutionOverrideMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toConflictResolutionOverride;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcConflictResolutionOverride;

/**
 * Converts between {@link SetReferenceSchemaConflictResolutionOverrideMutation} and
 * {@link GrpcSetReferenceSchemaConflictResolutionOverrideMutation} in both directions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetReferenceSchemaConflictResolutionOverrideMutationConverter implements SchemaMutationConverter<SetReferenceSchemaConflictResolutionOverrideMutation, GrpcSetReferenceSchemaConflictResolutionOverrideMutation> {
	public static final SetReferenceSchemaConflictResolutionOverrideMutationConverter INSTANCE = new SetReferenceSchemaConflictResolutionOverrideMutationConverter();

	@Nonnull
	public SetReferenceSchemaConflictResolutionOverrideMutation convert(@Nonnull GrpcSetReferenceSchemaConflictResolutionOverrideMutation mutation) {
		return new SetReferenceSchemaConflictResolutionOverrideMutation(
			mutation.getName(),
			toConflictResolutionOverride(mutation.getConflictResolutionOverride())
		);
	}

	@Nonnull
	public GrpcSetReferenceSchemaConflictResolutionOverrideMutation convert(@Nonnull SetReferenceSchemaConflictResolutionOverrideMutation mutation) {
		return GrpcSetReferenceSchemaConflictResolutionOverrideMutation.newBuilder()
			.setName(mutation.getName())
			.setConflictResolutionOverride(toGrpcConflictResolutionOverride(mutation.getConflictResolutionOverride()))
			.build();
	}
}
