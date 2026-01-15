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
 * Response containing a page of catalog snapshot binary data.
 *
 * This response provides a portion of the snapshot data as requested in
 * {@link GetCatalogSnapshotPageRequest}. The data should be written to the
 * appropriate offset in the local snapshot file.
 *
 * **Page Data:**
 *
 * - `startIndex`: Confirms the byte offset this page starts at
 * - `length`: Actual bytes in this page (may be less than requested at end of snapshot)
 * - `binaryPage`: Raw binary data for this portion of the snapshot
 *
 * **Assembly:**
 *
 * The requesting replica should:
 *
 * 1. Write `binaryPage` to local storage at offset `startIndex`
 * 2. Track received pages to know when transfer is complete
 * 3. After all pages received, verify total checksum from {@link GetCatalogSnapshotResponse}
 *
 * **Error Handling:**
 *
 * If `length` is 0 or `binaryPage` is empty for a valid range, this may indicate
 * an error condition that should trigger transfer restart.
 *
 * @param selfIndex responding replica's index in the cluster configuration
 * @param crc32 cumulative hash echoed back for chain verification
 * @param epoch current configuration epoch
 * @param viewNumber current view number for consistency verification
 * @param catalogPrimaryKey unique identifier of the catalog being transferred
 * @param startIndex byte offset this page starts at (echoed from request)
 * @param length actual number of bytes in this page
 * @param binaryPage raw binary snapshot data for this page
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see GetCatalogSnapshotPageRequest
 * @see GetCatalogSnapshotResponse
 */
public record GetCatalogSnapshotPageResponse(
	int selfIndex,
	long crc32,
	long epoch,
	long viewNumber,
	int catalogPrimaryKey,
	long startIndex,
	int length,
	@Nonnull byte[] binaryPage
) implements HashChainedClusterResponseMessage {
}
