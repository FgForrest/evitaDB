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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog;

import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaConflictResolutionMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifyCatalogSchemaConflictResolutionMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.ConflictResolutionConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link ModifyCatalogSchemaConflictResolutionMutation} and
 * {@link GrpcModifyCatalogSchemaConflictResolutionMutation} in both directions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ModifyCatalogSchemaConflictResolutionMutationConverter implements SchemaMutationConverter<ModifyCatalogSchemaConflictResolutionMutation, GrpcModifyCatalogSchemaConflictResolutionMutation> {
	public static final ModifyCatalogSchemaConflictResolutionMutationConverter INSTANCE = new ModifyCatalogSchemaConflictResolutionMutationConverter();

	@Nonnull
	public ModifyCatalogSchemaConflictResolutionMutation convert(@Nonnull GrpcModifyCatalogSchemaConflictResolutionMutation mutation) {
		return new ModifyCatalogSchemaConflictResolutionMutation(
			mutation.hasConflictResolution()
				? ConflictResolutionConverter.toConflictResolution(mutation.getConflictResolution())
				: null
		);
	}

	@Nonnull
	public GrpcModifyCatalogSchemaConflictResolutionMutation convert(@Nonnull ModifyCatalogSchemaConflictResolutionMutation mutation) {
		final GrpcModifyCatalogSchemaConflictResolutionMutation.Builder builder = GrpcModifyCatalogSchemaConflictResolutionMutation.newBuilder();

		if (mutation.getConflictResolution() != null) {
			builder.setConflictResolution(ConflictResolutionConverter.toGrpcConflictResolution(mutation.getConflictResolution()));
		}

		return builder.build();
	}
}
