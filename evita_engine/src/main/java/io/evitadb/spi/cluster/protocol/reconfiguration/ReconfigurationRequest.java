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

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.net.InetAddress;

/**
 * Request to reconfigure the cluster membership (VSR Revisited extension).
 *
 * This message initiates the reconfiguration protocol, which allows the cluster
 * to change its membership (add or remove replicas) while maintaining consistency.
 * This is an extension to the original VSR protocol defined in VSR Revisited.
 *
 * **Reconfiguration Protocol:**
 *
 * Cluster membership changes follow a two-phase protocol:
 *
 * 1. **Prepare Phase**: Leader proposes new configuration to old configuration members
 * 2. **Commit Phase**: Once quorum agrees, new epoch starts with new membership
 *
 * **Epoch Semantics:**
 *
 * Each configuration is identified by an epoch number. The epoch is incremented
 * each time the cluster membership changes. Replicas use epoch to detect stale
 * messages from old configurations.
 *
 * **Safety Guarantees:**
 *
 * The reconfiguration protocol ensures:
 *
 * - No operations are lost during membership change
 * - Quorum intersection between old and new configurations
 * - At most one configuration is active at any time
 *
 * **Note:** This is a special external message that may come from an administrator
 * or orchestration system, hence it implements {@link Serializable} rather than
 * {@link io.evitadb.spi.cluster.protocol.ClusterRequestMessage}.
 *
 * @param epoch the new epoch number for the proposed configuration
 * @param timestamp timestamp of the reconfiguration request for ordering
 * @param clusterMembers array of network addresses for all members in new configuration
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see ReconfigurationResponse
 * @see StartEpochRequest
 */
public record ReconfigurationRequest(
	long epoch,
	long timestamp,
	@Nonnull InetAddress[] clusterMembers
) implements Serializable {

}
