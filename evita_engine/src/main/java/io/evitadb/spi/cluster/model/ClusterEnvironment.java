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

package io.evitadb.spi.cluster.model;

import io.evitadb.core.exception.ReplicaNotPartOfClusterException;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.net.InetAddress;

/**
 * Represents the cluster environment configuration as seen by a single replica.
 *
 * This record provides the essential information a replica needs to communicate with other
 * cluster members and identify itself within the VSR protocol.
 *
 * **Cluster Membership:**
 *
 * The `clusterMembers` array defines the ordered set of replicas in the cluster. The order
 * is significant because:
 *
 * - Primary election is deterministic: `viewNumber % clusterMembers.length` gives the primary's index
 * - Quorum calculations depend on the total cluster size
 * - Each replica's identity is its index in this array
 *
 * **Self Identification:**
 *
 * The `selfIndex` field allows a replica to identify its own position in the configuration,
 * which is used when:
 *
 * - Populating the `selfIndex` field in outgoing messages
 * - Determining if this replica should act as primary for a given view
 * - Excluding self when broadcasting to other replicas
 *
 * @param clusterMembers ordered array of all cluster member addresses
 * @param selfIndex this replica's index in the clusterMembers array (0 to n-1)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record ClusterEnvironment(
	@Nonnull InetAddress[] clusterMembers,
	int selfIndex
) implements Serializable {

	/**
	 * Determines the index of a given InetAddress in the clusterMembers array.
	 * This method is used to identify the position of a specific cluster member,
	 * which is crucial for establishing its role and responsibilities in the cluster.
	 *
	 * @param inetAddress the InetAddress of the cluster member whose index is to be determined
	 * @return the index of the specified InetAddress in the clusterMembers array
	 * @throws ReplicaNotPartOfClusterException if the specified InetAddress is not part of the clusterMembers array
	 */
	public int selfIndex(@Nonnull InetAddress inetAddress) {
		for (int i = 0; i < this.clusterMembers.length; i++) {
			if (this.clusterMembers[i].equals(inetAddress)) {
				return i;
			}
		}
		throw new ReplicaNotPartOfClusterException(inetAddress, this.clusterMembers);
	}

}
