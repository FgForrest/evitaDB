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
 * Request for incremental engine state transfer via Write-Ahead Log (WAL).
 *
 * This message is used during recovery or view change when a replica needs to catch up
 * on engine state changes. Instead of transferring a full snapshot, incremental WAL
 * transfer is more efficient when the replica is only slightly behind.
 *
 * **Usage Scenarios:**
 *
 * 1. Recovery: Recovering replica requests WAL entries to catch up
 * 2. View change: New primary may need to catch up before accepting leadership
 * 3. Lagging replica: Backup that fell behind requests incremental updates
 *
 * **Response:**
 *
 * The responder returns WAL entries starting from `sinceEngineVersionInclusive` up to
 * the requested `limit`. If the requested version is no longer available (compacted),
 * the responder indicates this and full state transfer should be used instead.
 *
 * @param selfIndex requesting replica's index in the cluster configuration
 * @param targetReplicaIndex target replica's index (typically the primary or most up-to-date replica)
 * @param crc32 cumulative hash from preceding messages in the hash chain
 * @param epoch current configuration epoch (VSR Revisited extension)
 * @param viewNumber current view number for consistency verification
 * @param sinceEngineVersionInclusive starting engine op-number (inclusive) for WAL transfer
 * @param limit maximum number of WAL entries to return in one response
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see GetEngineStateResponse
 */
public record GetEngineStateRequest(
	int selfIndex,
	int targetReplicaIndex,
	long crc32,
	long epoch,
	long viewNumber,
	long sinceEngineVersionInclusive,
	int limit
) implements HashChainedClusterRequestMessage {

	@Override
	public long calculateHash(@Nonnull Crc32Calculator crc32Calculator) {
		return crc32Calculator
			.withLong(this.epoch)
			.withLong(this.viewNumber)
			.withLong(this.sinceEngineVersionInclusive)
			.withInt(this.limit)
			.getValue();
	}

}
