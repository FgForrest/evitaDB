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

import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetCatalogMutabilityMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link SetCatalogMutabilityMutation} and {@link GrpcSetCatalogMutabilityMutation} in both directions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetCatalogMutabilityMutationConverter implements SchemaMutationConverter<SetCatalogMutabilityMutation, GrpcSetCatalogMutabilityMutation> {
	public static final SetCatalogMutabilityMutationConverter INSTANCE = new SetCatalogMutabilityMutationConverter();

	@Nonnull
	public SetCatalogMutabilityMutation convert(@Nonnull GrpcSetCatalogMutabilityMutation mutation) {
		return new SetCatalogMutabilityMutation(
			mutation.getCatalogName(),
			mutation.getMutable()
		);
	}

	@Nonnull
	public GrpcSetCatalogMutabilityMutation convert(@Nonnull SetCatalogMutabilityMutation mutation) {
		return GrpcSetCatalogMutabilityMutation.newBuilder()
			.setCatalogName(mutation.getCatalogName())
			.setMutable(mutation.isMutable())
			.build();
	}
}