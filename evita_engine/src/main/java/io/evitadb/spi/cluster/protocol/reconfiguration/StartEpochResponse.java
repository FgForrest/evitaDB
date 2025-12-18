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
 * Response acknowledging the start of a new configuration epoch.
 *
 * This response confirms that the replica has accepted the new epoch and updated
 * its configuration. The replica is now ready to operate in the new configuration.
 *
 * **Acceptance Criteria:**
 *
 * A replica accepts the STARTEPOCH when:
 *
 * - The new epoch is greater than its current epoch
 * - Its engine version is at least the transition version
 * - The old configuration in the request matches its current configuration
 *
 * **Post-Acceptance State:**
 *
 * After accepting and responding:
 *
 * - Replica uses new cluster membership for quorum calculations
 * - Replica determines new primary using: `viewNumber % newClusterSize`
 * - Replica may need to participate in view change if primary changed
 *
 * @param selfIndex responding replica's index in the new configuration
 * @param crc32 cumulative hash echoed back for chain verification
 * @param epoch the new epoch being acknowledged
 * @param engineVersion replica's current engine op-number
 * @param timestamp timestamp of the response for coordination
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see StartEpochRequest
 */
public record StartEpochResponse(
	int selfIndex,
	long crc32,
	int epoch,
	long engineVersion,
	long timestamp
) implements HashChainedClusterResponseMessage {

}
