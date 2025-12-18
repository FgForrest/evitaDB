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

package io.evitadb.spi.cluster.protocol.reconfiguration;

import io.evitadb.spi.cluster.protocol.HashChainedClusterResponseMessage;

/**
 * Response acknowledging a reconfiguration request.
 *
 * This response confirms that the replica has received and processed the
 * reconfiguration request. The response includes the replica's current state
 * information to help coordinate the configuration change.
 *
 * **Response Semantics:**
 *
 * The responding replica provides:
 *
 * - Its current engine version (op-number) to verify state synchronization
 * - The timestamp echoed back for request correlation
 * - The new epoch to confirm the configuration change
 *
 * **Quorum Requirement:**
 *
 * The reconfiguration initiator must receive responses from a quorum of the
 * **old** configuration before proceeding with the configuration change. This
 * ensures that committed operations are not lost.
 *
 * @param selfIndex responding replica's index in the cluster configuration
 * @param crc32 cumulative hash for chain verification
 * @param epoch the epoch being acknowledged (from the request)
 * @param engineVersion replica's current engine op-number
 * @param timestamp echoed timestamp from the request
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see ReconfigurationRequest
 */
public record ReconfigurationResponse(
	int selfIndex,
	long crc32,
	int epoch,
	long engineVersion,
	long timestamp
) implements HashChainedClusterResponseMessage {

}
