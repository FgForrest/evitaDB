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

import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaDeprecationNoticeMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaDeprecationNoticeMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link ModifyReferenceSchemaDeprecationNoticeMutation} and {@link GrpcModifyReferenceSchemaDeprecationNoticeMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ModifyReferenceSchemaDeprecationNoticeMutationConverter implements SchemaMutationConverter<ModifyReferenceSchemaDeprecationNoticeMutation, GrpcModifyReferenceSchemaDeprecationNoticeMutation> {
	public static final ModifyReferenceSchemaDeprecationNoticeMutationConverter INSTANCE = new ModifyReferenceSchemaDeprecationNoticeMutationConverter();

	@Nonnull
	public ModifyReferenceSchemaDeprecationNoticeMutation convert(@Nonnull GrpcModifyReferenceSchemaDeprecationNoticeMutation mutation) {
		return new ModifyReferenceSchemaDeprecationNoticeMutation(
			mutation.getName(),
			mutation.hasDeprecationNotice() ? mutation.getDeprecationNotice().getValue() : null
		);
	}

	@Nonnull
	public GrpcModifyReferenceSchemaDeprecationNoticeMutation convert(@Nonnull ModifyReferenceSchemaDeprecationNoticeMutation mutation) {
		final GrpcModifyReferenceSchemaDeprecationNoticeMutation.Builder builder = GrpcModifyReferenceSchemaDeprecationNoticeMutation.newBuilder()
			.setName(mutation.getName());

		if (mutation.getDeprecationNotice() != null) {
			builder.setDeprecationNotice(StringValue.of(mutation.getDeprecationNotice()));
		}

		return builder.build();
	}
}
