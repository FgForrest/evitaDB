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
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcCreateAssociatedDataSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link CreateAssociatedDataSchemaMutation} and {@link GrpcCreateAssociatedDataSchemaMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CreateAssociatedDataSchemaMutationConverter implements SchemaMutationConverter<CreateAssociatedDataSchemaMutation, GrpcCreateAssociatedDataSchemaMutation> {
	public static final CreateAssociatedDataSchemaMutationConverter INSTANCE = new CreateAssociatedDataSchemaMutationConverter();

	@Nonnull
	public CreateAssociatedDataSchemaMutation convert(@Nonnull GrpcCreateAssociatedDataSchemaMutation mutation) {
		return new CreateAssociatedDataSchemaMutation(
			mutation.getName(),
			mutation.hasDescription() ? mutation.getDescription().getValue() : null,
			mutation.hasDeprecationNotice() ? mutation.getDeprecationNotice().getValue() : null,
			EvitaDataTypesConverter.toEvitaDataType(mutation.getType()),
			mutation.getLocalized(),
			mutation.getNullable()
		);
	}

	@Nonnull
	public GrpcCreateAssociatedDataSchemaMutation convert(@Nonnull CreateAssociatedDataSchemaMutation mutation) {
		final GrpcCreateAssociatedDataSchemaMutation.Builder builder = GrpcCreateAssociatedDataSchemaMutation.newBuilder()
			.setName(mutation.getName())
			.setType(EvitaDataTypesConverter.toGrpcEvitaAssociatedDataDataType(mutation.getType()))
			.setLocalized(mutation.isLocalized())
			.setNullable(mutation.isNullable());

		if (mutation.getDescription() != null) {
			builder.setDescription(StringValue.of(mutation.getDescription()));
		}
		if (mutation.getDeprecationNotice() != null) {
			builder.setDeprecationNotice(StringValue.of(mutation.getDeprecationNotice()));
		}

		return builder.build();
	}
}
