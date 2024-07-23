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

import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaCardinalityMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;

/**
 * Converts between {@link ModifyReferenceSchemaCardinalityMutation} and {@link GrpcModifyReferenceSchemaCardinalityMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ModifyReferenceSchemaCardinalityMutationConverter implements SchemaMutationConverter<ModifyReferenceSchemaCardinalityMutation, GrpcModifyReferenceSchemaCardinalityMutation> {

	@Nonnull
	public ModifyReferenceSchemaCardinalityMutation convert(@Nonnull GrpcModifyReferenceSchemaCardinalityMutation mutation) {
		return new ModifyReferenceSchemaCardinalityMutation(
			mutation.getName(),
			EvitaEnumConverter.toCardinality(mutation.getCardinality())
		);
	}

	@Nonnull
	public GrpcModifyReferenceSchemaCardinalityMutation convert(@Nonnull ModifyReferenceSchemaCardinalityMutation mutation) {
		return GrpcModifyReferenceSchemaCardinalityMutation.newBuilder()
			.setName(mutation.getName())
			.setCardinality(EvitaEnumConverter.toGrpcCardinality(mutation.getCardinality()))
			.build();
	}
}
