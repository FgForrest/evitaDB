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

import io.evitadb.api.requestResponse.schema.mutation.engine.UpgradeCatalogFormatMutation;
import io.evitadb.externalApi.grpc.generated.GrpcUpgradeCatalogFormatMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between `UpgradeCatalogFormatMutation` and `GrpcUpgradeCatalogFormatMutation` in both directions.
 *
 * The converter is the wire-format bridge for the engine-level mutation that drives the
 * `OUT_OF_DATE → BEING_UPGRADED → <prior operational state>` transition for a per-catalog lazy format upgrade. Both
 * the `from`/`to` protocol versions are captured on the wire so CDC consumers can correlate schema or data-shape
 * changes with the protocol bump.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UpgradeCatalogFormatMutationConverter implements SchemaMutationConverter<UpgradeCatalogFormatMutation, GrpcUpgradeCatalogFormatMutation> {
	public static final UpgradeCatalogFormatMutationConverter INSTANCE = new UpgradeCatalogFormatMutationConverter();

	@Nonnull
	public UpgradeCatalogFormatMutation convert(@Nonnull GrpcUpgradeCatalogFormatMutation mutation) {
		return new UpgradeCatalogFormatMutation(
			mutation.getCatalogName(),
			mutation.getFromProtocolVersion(),
			mutation.getToProtocolVersion()
		);
	}

	@Nonnull
	public GrpcUpgradeCatalogFormatMutation convert(@Nonnull UpgradeCatalogFormatMutation mutation) {
		return GrpcUpgradeCatalogFormatMutation.newBuilder()
			.setCatalogName(mutation.getCatalogName())
			.setFromProtocolVersion(mutation.getFromProtocolVersion())
			.setToProtocolVersion(mutation.getToProtocolVersion())
			.build();
	}
}
