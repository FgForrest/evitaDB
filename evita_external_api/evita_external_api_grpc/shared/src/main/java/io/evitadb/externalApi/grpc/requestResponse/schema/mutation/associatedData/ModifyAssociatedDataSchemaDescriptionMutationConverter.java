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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.associatedData;

import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaDescriptionMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifyAssociatedDataSchemaDescriptionMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link ModifyAssociatedDataSchemaDescriptionMutation} and {@link GrpcModifyAssociatedDataSchemaDescriptionMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ModifyAssociatedDataSchemaDescriptionMutationConverter implements SchemaMutationConverter<ModifyAssociatedDataSchemaDescriptionMutation, GrpcModifyAssociatedDataSchemaDescriptionMutation> {
	public static final ModifyAssociatedDataSchemaDescriptionMutationConverter INSTANCE = new ModifyAssociatedDataSchemaDescriptionMutationConverter();

	@Nonnull
	public ModifyAssociatedDataSchemaDescriptionMutation convert(@Nonnull GrpcModifyAssociatedDataSchemaDescriptionMutation mutation) {
		return new ModifyAssociatedDataSchemaDescriptionMutation(
			mutation.getName(),
			mutation.hasDescription() ? mutation.getDescription().getValue() : null
		);
	}

	@Nonnull
	public GrpcModifyAssociatedDataSchemaDescriptionMutation convert(@Nonnull ModifyAssociatedDataSchemaDescriptionMutation mutation) {
		final GrpcModifyAssociatedDataSchemaDescriptionMutation.Builder builder = GrpcModifyAssociatedDataSchemaDescriptionMutation.newBuilder()
			.setName(mutation.getName());

		if (mutation.getDescription() != null) {
			builder.setDescription(StringValue.of(mutation.getDescription()));
		}

		return builder.build();
	}
}
