/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
 * Request for a specific page of a catalog snapshot during full state transfer.
 *
 * This message retrieves a portion of the snapshot data identified in
 * {@link GetCatalogSnapshotResponse}. Large snapshots are transferred in pages
 * to manage memory usage and allow progress tracking.
 *
 * **Pagination:**
 *
 * - `startIndex`: Byte offset into the snapshot (0-based)
 * - `length`: Number of bytes to retrieve in this page
 *
 * **Transfer Strategy:**
 *
 * The requesting replica can use different strategies:
 *
 * - Sequential: Request pages in order (startIndex = 0, pageSize, 2*pageSize, ...)
 * - Parallel: Request multiple pages concurrently for faster transfer
 * - Resumable: Track last received page to resume interrupted transfers
 *
 * **Consistency:**
 *
 * All page requests should use the same `viewNumber` and `epoch` to ensure
 * consistency. If the view changes during transfer, the snapshot may become
 * invalid and transfer should restart.
 *
 * @param selfIndex requesting replica's index in the cluster configuration
 * @param targetReplicaIndex target replica's index (same as snapshot request)
 * @param crc32 cumulative hash from preceding messages in the hash chain
 * @param epoch current configuration epoch (VSR Revisited extension)
 * @param viewNumber current view number for consistency verification
 * @param catalogPrimaryKey unique identifier of the catalog being transferred
 * @param startIndex byte offset into the snapshot to start reading from
 * @param length number of bytes to include in this page
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see GetCatalogSnapshotRequest
 * @see GetCatalogSnapshotPageResponse
 */
public record GetCatalogSnapshotPageRequest(
	int selfIndex,
	int targetReplicaIndex,
	long crc32,
	int epoch,
	long viewNumber,
	int catalogPrimaryKey,
	long startIndex,
	int length
) implements HashChainedClusterRequestMessage {

	@Override
	public long calculateHash(@Nonnull Crc32Calculator crc32Calculator) {
		return crc32Calculator
			.withInt(this.epoch)
			.withLong(this.viewNumber)
			.withInt(this.catalogPrimaryKey)
			.withLong(this.startIndex)
			.withInt(this.length)
			.getValue();
	}

}
