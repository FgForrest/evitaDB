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

import io.evitadb.spi.cluster.protocol.HashChainedClusterResponseMessage;

import javax.annotation.Nonnull;

/**
 * Response containing incremental catalog state via Write-Ahead Log (WAL) entries.
 *
 * This response provides WAL entries for a specific catalog, allowing the requesting
 * replica to replay operations and catch up with the catalog's state.
 *
 * **WAL Content:**
 *
 * The `writeAheadLog` byte array contains serialized WAL entries specific to the
 * requested catalog. The entries cover versions from `sinceCatalogVersionInclusive`
 * to `sinceCatalogVersionInclusive + versionCount - 1`.
 *
 * **Catalog Independence:**
 *
 * Each catalog maintains independent versioning. A replica may be caught up on some
 * catalogs while being behind on others, allowing efficient selective synchronization.
 *
 * @param selfIndex responding replica's index in the cluster configuration
 * @param crc32 cumulative hash echoed back for chain verification
 * @param epoch current configuration epoch
 * @param viewNumber current view number for consistency verification
 * @param catalogPrimaryKey unique identifier of the transferred catalog
 * @param sinceCatalogVersionInclusive starting catalog op-number of the WAL entries
 * @param versionCount number of versions included in this response
 * @param writeAheadLog serialized WAL entries for the catalog state
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see GetCatalogStateRequest
 */
public record GetCatalogStateResponse(
	int selfIndex,
	long crc32,
	int epoch,
	long viewNumber,
	int catalogPrimaryKey,
	long sinceCatalogVersionInclusive,
	int versionCount,
	@Nonnull byte[] writeAheadLog
) implements HashChainedClusterResponseMessage {
}
