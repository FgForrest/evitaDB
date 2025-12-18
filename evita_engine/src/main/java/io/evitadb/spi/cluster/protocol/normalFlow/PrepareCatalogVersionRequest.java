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

package io.evitadb.spi.cluster.protocol.normalFlow;

import io.evitadb.spi.cluster.protocol.HashChainedClusterRequestMessage;
import io.evitadb.utils.Crc32Calculator;

import javax.annotation.Nonnull;

/**
 * PREPARE message for catalog state changes in the VSR two-phase commit protocol.
 *
 * This message is sent by the primary replica to all backup replicas during the first phase
 * of committing a catalog state change. Each catalog in evitaDB has its own independent version
 * sequence, identified by `catalogPrimaryKey`.
 *
 * **Catalog vs Engine State:**
 *
 * Unlike engine state which is global, catalog state is per-catalog. Each catalog maintains
 * its own `catalogVersion` sequence, allowing independent replication of different catalogs.
 *
 * **Protocol Flow:**
 *
 * 1. Primary receives client request to modify a catalog
 * 2. Primary assigns new `catalogVersion` for that catalog and logs the operation
 * 3. Primary sends this PREPARE message to all backups
 * 4. Backups log the operation and respond with {@link PrepareCatalogVersionResponse}
 * 5. Once quorum responds, primary sends {@link CommitCatalogVersionRequest}
 *
 * @param selfIndex sender replica's index in cluster configuration
 * @param targetReplicaIndex target backup replica's index
 * @param crc32 cumulative hash for message chain verification
 * @param epoch current configuration epoch
 * @param viewNumber current view number
 * @param catalogVersion the new operation number for this catalog (op-number)
 * @param committedCatalogVersion the highest committed catalog version (commit-number)
 * @param catalogPrimaryKey unique identifier for the catalog being modified
 * @param writeAheadLog serialized catalog operation data to be applied
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see PrepareCatalogVersionResponse
 * @see CommitCatalogVersionRequest
 */
public record PrepareCatalogVersionRequest(
	int selfIndex,
	int targetReplicaIndex,
	long crc32,
	int epoch,
	long viewNumber,
	long catalogVersion,
	long committedCatalogVersion,
	int catalogPrimaryKey,
	@Nonnull byte[] writeAheadLog
) implements HashChainedClusterRequestMessage {

	@Override
	public long calculateHash(@Nonnull Crc32Calculator crc32Calculator) {
		return crc32Calculator
			.withInt(this.epoch)
			.withLong(this.viewNumber)
			.withLong(this.catalogVersion)
			.withLong(this.committedCatalogVersion)
			.withInt(this.catalogPrimaryKey)
			.withByteArray(this.writeAheadLog)
			.getValue();
	}

}
