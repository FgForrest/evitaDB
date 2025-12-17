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
 * COMMIT message for catalog state changes in the VSR two-phase commit protocol.
 *
 * This message instructs backups to execute prepared catalog operations and advance their
 * commit-number for the specified catalog.
 *
 * @param selfIndex sender (primary) replica's index
 * @param targetReplicaIndex target backup replica's index
 * @param crc32 cumulative hash for message chain verification
 * @param epoch current configuration epoch
 * @param viewNumber current view number
 * @param catalogVersion the catalog operation number to commit up to (inclusive)
 * @param catalogPrimaryKey identifier of the catalog to commit
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see CommitCatalogVersionResponse
 * @see PrepareCatalogVersionRequest
 */
public record CommitCatalogVersionRequest(
	int selfIndex,
	int targetReplicaIndex,
	long crc32,
	int epoch,
	long viewNumber,
	long catalogVersion,
	int catalogPrimaryKey
) implements HashChainedClusterRequestMessage {

	@Override
	public long calculateHash(@Nonnull Crc32Calculator crc32Calculator) {
		return crc32Calculator
			.withInt(this.epoch)
			.withLong(this.viewNumber)
			.withLong(this.catalogVersion)
			.withInt(this.catalogPrimaryKey)
			.getValue();
	}

}
