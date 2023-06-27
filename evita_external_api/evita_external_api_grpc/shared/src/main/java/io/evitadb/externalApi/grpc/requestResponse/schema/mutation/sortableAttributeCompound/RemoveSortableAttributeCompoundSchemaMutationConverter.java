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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound;

import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcRemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;

/**
 * Converts between {@link RemoveSortableAttributeCompoundSchemaMutation} and {@link GrpcRemoveSortableAttributeCompoundSchemaMutation} in both directions.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2023
 */
public class RemoveSortableAttributeCompoundSchemaMutationConverter implements SchemaMutationConverter<RemoveSortableAttributeCompoundSchemaMutation, GrpcRemoveSortableAttributeCompoundSchemaMutation> {

	@Nonnull
	public RemoveSortableAttributeCompoundSchemaMutation convert(@Nonnull GrpcRemoveSortableAttributeCompoundSchemaMutation mutation) {
		return new RemoveSortableAttributeCompoundSchemaMutation(
			mutation.getName()
		);
	}

	@Nonnull
	public GrpcRemoveSortableAttributeCompoundSchemaMutation convert(@Nonnull RemoveSortableAttributeCompoundSchemaMutation mutation) {
		return GrpcRemoveSortableAttributeCompoundSchemaMutation.newBuilder()
			.setName(mutation.getName())
			.build();
	}
}
