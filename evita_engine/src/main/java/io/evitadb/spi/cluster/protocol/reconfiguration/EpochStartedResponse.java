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
 * Response acknowledging receipt of EPOCHSTARTED message.
 *
 * This response confirms that the replica has also transitioned to the new epoch
 * and is ready to operate in the new configuration. Both sender and receiver
 * are now synchronized at the new epoch.
 *
 * **Coordination Completion:**
 *
 * When a replica has:
 *
 * 1. Sent EPOCHSTARTED to all other new configuration members
 * 2. Received EPOCHSTARTED (or this response) from a quorum
 *
 * The replica knows the new configuration is fully active and can begin
 * normal VSR operation (PREPARE/COMMIT) in the new epoch.
 *
 * **Epoch Verification:**
 *
 * The response includes the epoch to verify both replicas agree on which
 * epoch is being started. A mismatch would indicate a serious protocol error.
 *
 * @param selfIndex responding replica's index in the new configuration
 * @param crc32 cumulative hash echoed back for chain verification
 * @param epoch the epoch being acknowledged (should match the request)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see EpochStartedRequest
 */
public record EpochStartedResponse(
	int selfIndex,
	long crc32,
	int epoch
) implements HashChainedClusterResponseMessage {

}
