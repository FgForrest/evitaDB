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
 * Request for incremental catalog state transfer via Write-Ahead Log (WAL).
 *
 * This message is used during recovery or view change when a replica needs to catch up
 * on a specific catalog's state changes. Each catalog maintains its own independent
 * replication state and WAL.
 *
 * **Catalog Identification:**
 *
 * Catalogs are identified by their `catalogPrimaryKey`, which is a stable identifier
 * assigned when the catalog is created. This allows tracking catalog state even if
 * the catalog is renamed.
 *
 * **Usage Scenarios:**
 *
 * 1. Recovery: Recovering replica requests WAL entries for catalogs it's behind on
 * 2. New catalog: Replica that missed catalog creation requests full state
 * 3. Selective sync: Replica can sync individual catalogs independently
 *
 * **Response:**
 *
 * The responder returns WAL entries for the specified catalog starting from
 * `sinceCatalogVersionInclusive` up to the requested `limit`.
 *
 * @param selfIndex requesting replica's index in the cluster configuration
 * @param targetReplicaIndex target replica's index
 * @param crc32 cumulative hash from preceding messages in the hash chain
 * @param epoch current configuration epoch (VSR Revisited extension)
 * @param viewNumber current view number for consistency verification
 * @param catalogPrimaryKey unique identifier of the catalog to transfer
 * @param sinceCatalogVersionInclusive starting catalog op-number (inclusive) for WAL transfer
 * @param limit maximum number of WAL entries to return in one response
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see GetCatalogStateResponse
 */
public record GetCatalogStateRequest(
	int selfIndex,
	int targetReplicaIndex,
	long crc32,
	long epoch,
	long viewNumber,
	int catalogPrimaryKey,
	long sinceCatalogVersionInclusive,
	int limit
) implements HashChainedClusterRequestMessage {

	@Override
	public long calculateHash(@Nonnull Crc32Calculator crc32Calculator) {
		return crc32Calculator
			.withLong(this.epoch)
			.withLong(this.viewNumber)
			.withInt(this.catalogPrimaryKey)
			.withLong(this.sinceCatalogVersionInclusive)
			.withInt(this.limit)
			.getValue();
	}

}
