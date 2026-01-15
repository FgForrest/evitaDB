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

import io.evitadb.spi.cluster.protocol.HashChainedClusterRequestMessage;
import io.evitadb.utils.Crc32Calculator;

import javax.annotation.Nonnull;

/**
 * Request to initiate a full catalog snapshot transfer.
 *
 * This message is used when incremental WAL transfer is not possible or practical:
 *
 * - The replica is too far behind and WAL entries have been compacted
 * - A new replica is joining the cluster for the first time
 * - The replica's state is corrupted and needs full resynchronization
 *
 * **Snapshot Transfer Protocol:**
 *
 * Full snapshot transfer is a multi-step process:
 *
 * 1. Send `GetCatalogSnapshotRequest` to initiate transfer
 * 2. Receive `GetCatalogSnapshotResponse` with total size and checksum
 * 3. Send multiple `GetCatalogSnapshotPageRequest` to retrieve data pages
 * 4. Reassemble the snapshot and verify using the total checksum
 *
 * **When to Use:**
 *
 * Use snapshot transfer when:
 * - Catalog doesn't exist locally
 * - Local version is below the oldest available WAL entry
 * - Data integrity issues require full resync
 *
 * @param selfIndex requesting replica's index in the cluster configuration
 * @param targetReplicaIndex target replica's index (typically the primary)
 * @param crc32 cumulative hash from preceding messages in the hash chain
 * @param epoch current configuration epoch (VSR Revisited extension)
 * @param viewNumber current view number for consistency verification
 * @param catalogPrimaryKey unique identifier of the catalog to snapshot
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see GetCatalogSnapshotResponse
 * @see GetCatalogSnapshotPageRequest
 */
public record GetCatalogSnapshotRequest(
	int selfIndex,
	int targetReplicaIndex,
	long crc32,
	long epoch,
	long viewNumber,
	int catalogPrimaryKey
) implements HashChainedClusterRequestMessage {

	@Override
	public long calculateHash(@Nonnull Crc32Calculator crc32Calculator) {
		return crc32Calculator
			.withLong(this.epoch)
			.withLong(this.viewNumber)
			.withInt(this.catalogPrimaryKey)
			.getValue();
	}

}
