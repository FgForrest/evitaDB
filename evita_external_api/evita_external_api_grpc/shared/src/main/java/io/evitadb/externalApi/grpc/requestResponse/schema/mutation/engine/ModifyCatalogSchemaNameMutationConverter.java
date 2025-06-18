/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine;

import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifyCatalogSchemaNameMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link ModifyCatalogSchemaNameMutation} and {@link GrpcModifyCatalogSchemaNameMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ModifyCatalogSchemaNameMutationConverter implements SchemaMutationConverter<ModifyCatalogSchemaNameMutation, GrpcModifyCatalogSchemaNameMutation> {
	public static final ModifyCatalogSchemaNameMutationConverter INSTANCE = new ModifyCatalogSchemaNameMutationConverter();

	@Nonnull
	public ModifyCatalogSchemaNameMutation convert(@Nonnull GrpcModifyCatalogSchemaNameMutation mutation) {
		return new ModifyCatalogSchemaNameMutation(
			mutation.getCatalogName(),
			mutation.getNewCatalogName(),
			mutation.getOverwriteTarget()
		);
	}

	@Nonnull
	public GrpcModifyCatalogSchemaNameMutation convert(@Nonnull ModifyCatalogSchemaNameMutation mutation) {
		return GrpcModifyCatalogSchemaNameMutation.newBuilder()
			.setCatalogName(mutation.getCatalogName())
			.setNewCatalogName(mutation.getNewCatalogName())
			.setOverwriteTarget(mutation.isOverwriteTarget())
			.build();
	}
}
