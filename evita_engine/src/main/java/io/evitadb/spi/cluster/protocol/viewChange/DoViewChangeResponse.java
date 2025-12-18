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

import io.evitadb.spi.cluster.protocol.HashChainedClusterResponseMessage;

/**
 * Response acknowledging receipt of DOVIEWCHANGE message.
 *
 * This response confirms that the new primary candidate received the replica's state
 * information. Once the candidate receives DOVIEWCHANGE from f+1 replicas, it becomes
 * the new primary and sends STARTVIEW to all replicas.
 *
 * @param selfIndex responding replica's index (new primary candidate)
 * @param crc32 cumulative hash echoed back
 * @param epoch current configuration epoch
 * @param viewNumber the view number being established
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see DoViewChangeRequest
 */
public record DoViewChangeResponse(
	int selfIndex,
	long crc32,
	int epoch,
	long viewNumber
) implements HashChainedClusterResponseMessage {
}
