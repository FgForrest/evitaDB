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

package io.evitadb.spi.cluster.protocol.stateTransfer;

import io.evitadb.spi.cluster.protocol.HashChainedClusterResponseMessage;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.engine.model.EngineState;

import javax.annotation.Nonnull;

/**
 * Response containing the complete engine state snapshot.
 *
 * This response provides a serialized representation of the entire evitaDB engine state,
 * including information about all catalogs (both active and inactive) managed by the engine.
 *
 * **Engine State Contents:**
 *
 * The {@link EngineState} includes:
 *
 * - Complete catalog inventory (active and inactive catalogs)
 * - Catalog version numbers and state information
 * - Engine-level configuration and metadata
 * - No actual catalog data (entities, schemas are transferred separately)
 *
 * **Usage Pattern:**
 *
 * After receiving this response, the requesting replica should:
 *
 * 1. Deserialize the {@link EngineState} object
 * 2. Compare with local engine state to identify differences
 * 3. Request individual catalog state transfers using {@link GetCatalogStateRequest}
 * 4. Synchronize missing or outdated catalogs as needed
 *
 * @param selfIndex responding replica's index in the cluster configuration
 * @param crc32 cumulative hash echoed back for chain verification
 * @param epoch current configuration epoch
 * @param viewNumber current view number for consistency verification
 * @param catalogPrimaryKey unique identifier used for response correlation (not related to engine state)
 * @param engineState serialized engine state containing all catalog metadata
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see GetEngineSnapshotRequest
 * @see GetCatalogStateRequest
 */
public record GetEngineSnapshotResponse(
	int selfIndex,
	long crc32,
	long epoch,
	long viewNumber,
	int catalogPrimaryKey,
	@Nonnull EngineState<LogRecordReference> engineState
) implements HashChainedClusterResponseMessage {
}
