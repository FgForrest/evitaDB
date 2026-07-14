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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.entity;

import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaConflictResolutionMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifyEntitySchemaConflictResolutionMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.ConflictResolutionConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link ModifyEntitySchemaConflictResolutionMutation} and
 * {@link GrpcModifyEntitySchemaConflictResolutionMutation} in both directions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ModifyEntitySchemaConflictResolutionMutationConverter implements SchemaMutationConverter<ModifyEntitySchemaConflictResolutionMutation, GrpcModifyEntitySchemaConflictResolutionMutation> {
	public static final ModifyEntitySchemaConflictResolutionMutationConverter INSTANCE = new ModifyEntitySchemaConflictResolutionMutationConverter();

	@Nonnull
	public ModifyEntitySchemaConflictResolutionMutation convert(@Nonnull GrpcModifyEntitySchemaConflictResolutionMutation mutation) {
		return new ModifyEntitySchemaConflictResolutionMutation(
			mutation.hasConflictResolution()
				? ConflictResolutionConverter.toConflictResolution(mutation.getConflictResolution())
				: null
		);
	}

	@Nonnull
	public GrpcModifyEntitySchemaConflictResolutionMutation convert(@Nonnull ModifyEntitySchemaConflictResolutionMutation mutation) {
		final GrpcModifyEntitySchemaConflictResolutionMutation.Builder builder = GrpcModifyEntitySchemaConflictResolutionMutation.newBuilder();

		if (mutation.getConflictResolution() != null) {
			builder.setConflictResolution(ConflictResolutionConverter.toGrpcConflictResolution(mutation.getConflictResolution()));
		}

		return builder.build();
	}
}
