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

/**
 * Response providing metadata about a catalog snapshot for transfer.
 *
 * This response provides the information needed to coordinate the snapshot transfer:
 * the total size of the snapshot and a checksum for verification after all pages
 * have been received.
 *
 * **Transfer Coordination:**
 *
 * The response does not contain the actual snapshot data. Instead, it provides:
 *
 * - `totalLength`: Total bytes of the snapshot, used to calculate number of page requests
 * - `totalCrc32`: Checksum of the entire snapshot for end-to-end verification
 *
 * **Next Steps:**
 *
 * After receiving this response, the requesting replica should:
 *
 * 1. Prepare storage for `totalLength` bytes
 * 2. Calculate required number of page requests based on page size
 * 3. Send {@link GetCatalogSnapshotPageRequest} messages to retrieve data
 * 4. Verify assembled snapshot against `totalCrc32`
 *
 * @param selfIndex responding replica's index in the cluster configuration
 * @param crc32 cumulative hash echoed back for chain verification
 * @param epoch current configuration epoch
 * @param viewNumber current view number for consistency verification
 * @param catalogPrimaryKey unique identifier of the snapshot catalog
 * @param totalLength total size of the snapshot in bytes
 * @param totalCrc32 CRC32 checksum of the complete snapshot for integrity verification
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see GetCatalogSnapshotRequest
 * @see GetCatalogSnapshotPageRequest
 */
public record GetCatalogSnapshotResponse(
	int selfIndex,
	long crc32,
	long epoch,
	long viewNumber,
	int catalogPrimaryKey,
	long totalLength,
	long totalCrc32
) implements HashChainedClusterResponseMessage {
}
