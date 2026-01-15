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

package io.evitadb.spi.cluster.protocol.reconfiguration;

import io.evitadb.spi.cluster.protocol.HashChainedClusterRequestMessage;
import io.evitadb.utils.Crc32Calculator;

import javax.annotation.Nonnull;

/**
 * Request announcing that a replica has transitioned to the new epoch.
 *
 * This message is broadcast by replicas after they have successfully transitioned
 * to the new configuration epoch. It serves as a coordination mechanism to ensure
 * all replicas in the new configuration are aware of successful transitions.
 *
 * **Protocol Flow:**
 *
 * After receiving STARTEPOCH and updating configuration:
 *
 * 1. Replica sends EPOCHSTARTED to all other members of new configuration
 * 2. Replicas collect EPOCHSTARTED messages from quorum of new configuration
 * 3. Once quorum achieved, normal operation can begin in new epoch
 *
 * **Synchronization Purpose:**
 *
 * This message ensures that:
 *
 * - All replicas agree on the new configuration
 * - No replica starts normal operation before others are ready
 * - Network partitions during reconfiguration are detected
 *
 * **Minimal Content:**
 *
 * This message is intentionally minimal, containing only the epoch number,
 * as its purpose is purely coordination - the actual configuration was
 * already communicated via {@link StartEpochRequest}.
 *
 * @param selfIndex requesting replica's index in the new configuration
 * @param targetReplicaIndex target replica's index in the new configuration
 * @param crc32 cumulative hash from preceding messages in the hash chain
 * @param epoch the new epoch that has been started
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see EpochStartedResponse
 * @see StartEpochRequest
 */
public record EpochStartedRequest(
	int selfIndex,
	int targetReplicaIndex,
	long crc32,
	long epoch
) implements HashChainedClusterRequestMessage {

	@Override
	public long calculateHash(@Nonnull Crc32Calculator crc32Calculator) {
		return crc32Calculator
			.withLong(this.epoch)
			.getValue();
	}

}
