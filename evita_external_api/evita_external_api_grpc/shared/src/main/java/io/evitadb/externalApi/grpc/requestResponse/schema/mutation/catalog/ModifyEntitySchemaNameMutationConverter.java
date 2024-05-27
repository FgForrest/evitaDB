/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifyEntitySchemaNameMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;

/**
 * Converts between {@link ModifyEntitySchemaNameMutation} and {@link GrpcModifyEntitySchemaNameMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ModifyEntitySchemaNameMutationConverter implements SchemaMutationConverter<ModifyEntitySchemaNameMutation, GrpcModifyEntitySchemaNameMutation> {

	@Nonnull
	public ModifyEntitySchemaNameMutation convert(@Nonnull GrpcModifyEntitySchemaNameMutation mutation) {
		return new ModifyEntitySchemaNameMutation(
			mutation.getName(),
			mutation.getNewName(),
			mutation.getOverwriteTarget()
		);
	}

	@Nonnull
	public GrpcModifyEntitySchemaNameMutation convert(@Nonnull ModifyEntitySchemaNameMutation mutation) {
		return GrpcModifyEntitySchemaNameMutation.newBuilder()
			.setName(mutation.getName())
			.setNewName(mutation.getNewName())
			.setOverwriteTarget(mutation.isOverwriteTarget())
			.build();
	}
}
