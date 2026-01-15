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

package io.evitadb.spi.cluster.protocol.viewChange;

import io.evitadb.spi.cluster.protocol.HashChainedClusterResponseMessage;

/**
 * Response acknowledging receipt of STARTVIEWCHANGE message.
 *
 * This response confirms that the replica has received and processed the view change initiation.
 * Once a replica receives STARTVIEWCHANGE from f+1 distinct replicas (including itself),
 * it proceeds to send DOVIEWCHANGE to the new primary candidate.
 *
 * @param selfIndex responding replica's index
 * @param crc32 cumulative hash echoed back
 * @param epoch current configuration epoch
 * @param viewNumber the view number being acknowledged
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see StartViewChangeRequest
 */
public record StartViewChangeResponse(
	int selfIndex,
	long crc32,
	long epoch,
	long viewNumber
) implements HashChainedClusterResponseMessage {
}
