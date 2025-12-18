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
 * PREPARE message for engine state changes in the VSR two-phase commit protocol.
 *
 * This message is sent by the primary replica to all backup replicas during the first phase
 * of committing an engine state change. In evitaDB, engine state includes global database
 * operations that affect the entire database instance (not specific to any catalog).
 *
 * **Protocol Flow:**
 *
 * 1. Primary receives client request to modify engine state
 * 2. Primary assigns new `engineVersion` (op-number) and logs the operation
 * 3. Primary sends this PREPARE message to all backups
 * 4. Backups log the operation and respond with {@link PrepareEngineStateResponse}
 * 5. Once quorum responds, primary sends {@link CommitEngineStateRequest}
 *
 * **Message Fields:**
 *
 * @param selfIndex sender replica's index in cluster configuration
 * @param targetReplicaIndex target backup replica's index
 * @param crc32 cumulative hash for message chain verification
 * @param epoch current configuration epoch
 * @param viewNumber current view number identifying the primary
 * @param engineVersion the new operation number being prepared (op-number)
 * @param committedEngineVersion the highest committed operation (commit-number)
 * @param writeAheadLog serialized operation data to be applied
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see PrepareEngineStateResponse
 * @see CommitEngineStateRequest
 */
public record PrepareEngineStateRequest(
	int selfIndex,
	int targetReplicaIndex,
	long crc32,
	int epoch,
	long viewNumber,
	long engineVersion,
	long committedEngineVersion,
	@Nonnull byte[] writeAheadLog
) implements HashChainedClusterRequestMessage {

	@Override
	public long calculateHash(@Nonnull Crc32Calculator crc32Calculator) {
		return crc32Calculator
			.withInt(this.epoch)
			.withLong(this.viewNumber)
			.withLong(this.engineVersion)
			.withLong(this.committedEngineVersion)
			.withByteArray(this.writeAheadLog)
			.getValue();
	}
}
