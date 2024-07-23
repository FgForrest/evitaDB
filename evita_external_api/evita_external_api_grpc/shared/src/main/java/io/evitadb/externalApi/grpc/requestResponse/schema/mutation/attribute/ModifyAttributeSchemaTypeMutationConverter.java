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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute;

import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaTypeMutation;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcModifyAttributeSchemaTypeMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;

/**
 * Converts between {@link ModifyAttributeSchemaTypeMutation} and {@link GrpcModifyAttributeSchemaTypeMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ModifyAttributeSchemaTypeMutationConverter implements SchemaMutationConverter<ModifyAttributeSchemaTypeMutation, GrpcModifyAttributeSchemaTypeMutation> {

	@Nonnull
	public ModifyAttributeSchemaTypeMutation convert(@Nonnull GrpcModifyAttributeSchemaTypeMutation mutation) {
		return new ModifyAttributeSchemaTypeMutation(
			mutation.getName(),
			EvitaDataTypesConverter.toEvitaDataType(mutation.getType()),
			mutation.getIndexedDecimalPlaces()
		);
	}

	@Nonnull
	public GrpcModifyAttributeSchemaTypeMutation convert(@Nonnull ModifyAttributeSchemaTypeMutation mutation) {
		return GrpcModifyAttributeSchemaTypeMutation.newBuilder()
			.setName(mutation.getName())
			.setType(EvitaDataTypesConverter.toGrpcEvitaDataType(mutation.getType()))
			.setIndexedDecimalPlaces(mutation.getIndexedDecimalPlaces())
			.build();
	}
}
