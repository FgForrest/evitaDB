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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound;

import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation} and {@link GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation} in both directions.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter implements SchemaMutationConverter<ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation, GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation> {
	public static final ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter INSTANCE = new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter();

	@Nonnull
	public ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation convert(@Nonnull GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation mutation) {
		return new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(
			mutation.getName(),
			mutation.hasDeprecationNotice() ? mutation.getDeprecationNotice().getValue() : null
		);
	}

	@Nonnull
	public GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation convert(@Nonnull ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation mutation) {
		final GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation.Builder builder = GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation.newBuilder()
			.setName(mutation.getName());

		if (mutation.getDeprecationNotice() != null) {
			builder.setDeprecationNotice(StringValue.of(mutation.getDeprecationNotice()));
		}

		return builder.build();
	}
}
