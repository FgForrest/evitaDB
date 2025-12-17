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

package io.evitadb.spi.cluster.protocol.viewChange;

import io.evitadb.spi.cluster.protocol.HashChainedClusterRequestMessage;
import io.evitadb.utils.Crc32Calculator;

import javax.annotation.Nonnull;

/**
 * STARTVIEWCHANGE message initiating the view change protocol.
 *
 * This message is sent by a replica when it detects that the current primary has failed
 * (typically via timeout). The view change protocol ensures a new primary is elected
 * without losing committed operations.
 *
 * **View Change Initiation:**
 *
 * A replica initiates view change when:
 *
 * 1. It hasn't heard from the primary within the heartbeat timeout
 * 2. It receives a STARTVIEWCHANGE for a higher view from another replica
 * 3. It needs to advance to a higher view to process a message
 *
 * **Protocol Flow:**
 *
 * 1. Replica increments its view number and broadcasts STARTVIEWCHANGE
 * 2. Once a replica receives STARTVIEWCHANGE from f+1 replicas, it sends DOVIEWCHANGE
 * 3. New primary candidate collects DOVIEWCHANGE messages
 * 4. New primary sends STARTVIEW to all replicas
 *
 * @param selfIndex sender replica's index
 * @param targetReplicaIndex target replica's index
 * @param crc32 cumulative hash for verification
 * @param epoch current configuration epoch
 * @param viewNumber the new view number being proposed (current + 1)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see StartViewChangeResponse
 * @see DoViewChangeRequest
 */
public record StartViewChangeRequest(
	int selfIndex,
	int targetReplicaIndex,
	long crc32,
	int epoch,
	long viewNumber
) implements HashChainedClusterRequestMessage {

	@Override
	public long calculateHash(@Nonnull Crc32Calculator crc32Calculator) {
		return crc32Calculator
			.withInt(this.selfIndex)
			.withInt(this.targetReplicaIndex)
			.withInt(this.epoch)
			.withLong(this.viewNumber)
			.getValue();
	}

}
