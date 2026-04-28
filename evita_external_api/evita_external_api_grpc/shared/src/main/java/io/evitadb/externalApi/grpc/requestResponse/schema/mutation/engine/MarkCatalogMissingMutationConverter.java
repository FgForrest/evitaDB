/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

import io.evitadb.api.requestResponse.schema.mutation.engine.MarkCatalogMissingMutation;
import io.evitadb.externalApi.grpc.generated.GrpcMarkCatalogMissingMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between `MarkCatalogMissingMutation` and `GrpcMarkCatalogMissingMutation` in both directions.
 *
 * The converter is the wire-format bridge for the engine-level mutation that records a catalog whose on-disk folder
 * disappeared after registration. Only the catalog name is carried on the wire — the state transition to
 * `CatalogState.MISSING` is performed by the engine-side operator.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MarkCatalogMissingMutationConverter implements SchemaMutationConverter<MarkCatalogMissingMutation, GrpcMarkCatalogMissingMutation> {
	public static final MarkCatalogMissingMutationConverter INSTANCE = new MarkCatalogMissingMutationConverter();

	@Nonnull
	public MarkCatalogMissingMutation convert(@Nonnull GrpcMarkCatalogMissingMutation mutation) {
		return new MarkCatalogMissingMutation(
			mutation.getCatalogName()
		);
	}

	@Nonnull
	public GrpcMarkCatalogMissingMutation convert(@Nonnull MarkCatalogMissingMutation mutation) {
		return GrpcMarkCatalogMissingMutation.newBuilder()
			.setCatalogName(mutation.getCatalogName())
			.build();
	}
}
