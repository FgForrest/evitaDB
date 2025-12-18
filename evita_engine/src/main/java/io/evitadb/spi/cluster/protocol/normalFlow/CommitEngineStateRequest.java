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
 * COMMIT message for engine state changes in the VSR two-phase commit protocol.
 *
 * This message is sent by the primary after receiving PREPAREOK responses from a quorum
 * of backups. It instructs backups to execute the prepared operation and advance their
 * commit-number.
 *
 * **Protocol Flow:**
 *
 * 1. Primary receives quorum of {@link PrepareEngineStateResponse} messages
 * 2. Primary executes the operation locally and advances its commit-number
 * 3. Primary sends this COMMIT message to all backups
 * 4. Backups execute the operation and respond with {@link CommitEngineStateResponse}
 * 5. Primary can respond to client once local commit is complete
 *
 * **Commit Semantics:**
 *
 * Upon receiving this message, backups should execute all prepared operations up to and
 * including `engineVersion`. The backup may have multiple prepared-but-uncommitted operations
 * that should all be committed in order.
 *
 * @param selfIndex sender (primary) replica's index
 * @param targetReplicaIndex target backup replica's index
 * @param crc32 cumulative hash for message chain verification
 * @param epoch current configuration epoch
 * @param viewNumber current view number
 * @param engineVersion the operation number to commit up to (inclusive)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see CommitEngineStateResponse
 * @see PrepareEngineStateRequest
 */
public record CommitEngineStateRequest(
	int selfIndex,
	int targetReplicaIndex,
	long crc32,
	int epoch,
	long viewNumber,
	long engineVersion
) implements HashChainedClusterRequestMessage {

	@Override
	public long calculateHash(@Nonnull Crc32Calculator crc32Calculator) {
		return crc32Calculator
			.withInt(this.epoch)
			.withLong(this.viewNumber)
			.withLong(this.engineVersion)
			.getValue();
	}

}
