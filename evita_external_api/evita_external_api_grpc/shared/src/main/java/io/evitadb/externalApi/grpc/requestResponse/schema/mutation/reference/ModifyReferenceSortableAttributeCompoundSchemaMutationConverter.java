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

import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSortableAttributeCompoundSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceAttributeSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSortableAttributeCompoundSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingSortableAttributeCompoundSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link ModifyReferenceAttributeSchemaMutation} and {@link GrpcModifyReferenceAttributeSchemaMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ModifyReferenceSortableAttributeCompoundSchemaMutationConverter
	implements SchemaMutationConverter<ModifyReferenceSortableAttributeCompoundSchemaMutation, GrpcModifyReferenceSortableAttributeCompoundSchemaMutation> {
	public static final ModifyReferenceSortableAttributeCompoundSchemaMutationConverter INSTANCE = new ModifyReferenceSortableAttributeCompoundSchemaMutationConverter();

	@Nonnull
	public ModifyReferenceSortableAttributeCompoundSchemaMutation convert(@Nonnull GrpcModifyReferenceSortableAttributeCompoundSchemaMutation mutation) {
		return new ModifyReferenceSortableAttributeCompoundSchemaMutation(
			mutation.getName(),
			(ReferenceSchemaMutation) DelegatingSortableAttributeCompoundSchemaMutationConverter.INSTANCE.convert(mutation.getSortableAttributeCompoundSchemaMutation())
		);
	}

	@Nonnull
	public GrpcModifyReferenceSortableAttributeCompoundSchemaMutation convert(@Nonnull ModifyReferenceSortableAttributeCompoundSchemaMutation mutation) {
		return GrpcModifyReferenceSortableAttributeCompoundSchemaMutation.newBuilder()
			.setName(mutation.getName())
			.setSortableAttributeCompoundSchemaMutation(DelegatingSortableAttributeCompoundSchemaMutationConverter.INSTANCE.convert((SortableAttributeCompoundSchemaMutation) mutation.getSortableAttributeCompoundSchemaMutation()))
			.build();
	}
}
