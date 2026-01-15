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

import javax.annotation.Nonnull;

/**
 * Response containing incremental engine state via Write-Ahead Log (WAL) entries.
 *
 * This response provides WAL entries starting from the requested version, allowing
 * the requesting replica to replay operations and catch up with the cluster state.
 *
 * **WAL Content:**
 *
 * The `writeAheadLog` byte array contains serialized WAL entries that can be replayed
 * to update the engine state. The entries cover versions from `sinceEngineVersionInclusive`
 * to `sinceEngineVersionInclusive + versionCount - 1`.
 *
 * **Incomplete Transfer:**
 *
 * If `versionCount` is less than requested in {@link GetEngineStateRequest#limit()},
 * either:
 * - All available entries have been transferred (replica is caught up)
 * - More requests are needed to continue transfer
 *
 * @param selfIndex responding replica's index in the cluster configuration
 * @param crc32 cumulative hash echoed back for chain verification
 * @param epoch current configuration epoch
 * @param viewNumber current view number for consistency verification
 * @param sinceEngineVersionInclusive starting engine op-number of the WAL entries
 * @param versionCount number of versions included in this response
 * @param writeAheadLog serialized WAL entries for the engine state
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see GetEngineStateRequest
 */
public record GetEngineStateResponse(
	int selfIndex,
	long crc32,
	long epoch,
	long viewNumber,
	long sinceEngineVersionInclusive,
	int versionCount,
	@Nonnull byte[] writeAheadLog
) implements HashChainedClusterResponseMessage {
}
