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

import io.evitadb.core.exception.InsufficientClusterSizeException;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.net.InetAddress;

/**
 * Represents the current state of a replica in the VSR protocol.
 *
 * This record captures all the essential state information that a replica maintains to participate
 * in the Viewstamped Replication protocol. The state includes both the current cluster configuration
 * and the replica's position within the protocol's state machine.
 *
 * **Configuration Management:**
 *
 * VSR Revisited introduces epochs for safe reconfiguration. During a configuration change:
 *
 * 1. `oldConfiguration` holds the previous cluster membership
 * 2. `configuration` holds the new cluster membership
 * 3. Both configurations may be active during the transition period
 * 4. Once the epoch completes, `oldConfiguration` and `configuration` become identical
 *
 * **View and Epoch Numbers:**
 *
 * - `viewNumber` is incremented each time a new primary is elected within an epoch
 * - `epoch` is incremented when the cluster configuration changes
 * - The current primary is determined by: `viewNumber % configuration.length`
 *
 * @param oldConfiguration the previous cluster configuration (may equal current during stable state)
 * @param configuration the current cluster configuration as an array of member addresses
 * @param replicaNumber this replica's index in the current configuration (0 to n-1)
 * @param viewNumber the current view number, incremented on each primary change
 * @param epoch the current configuration epoch, incremented on cluster membership changes
 * @param status the current operational state of this replica
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see ViewState
 */
public record ReplicaState(
	@Nonnull InetAddress[] oldConfiguration,
	@Nonnull InetAddress[] configuration,
	int replicaNumber,
	long epoch,
	long viewNumber,
	@Nonnull ViewState status
) implements Serializable {

	public ReplicaState {
		if (configuration.length < 2) {
			throw new InsufficientClusterSizeException(configuration.length);
		}
	}

	/**
	 * Calculates the quorum size (majority) required for consensus in the current configuration.
	 *
	 * A quorum is the minimum number of replicas that must acknowledge an operation for it to be
	 * committed. In VSR, a quorum consists of a majority of replicas to ensure that at least one
	 * replica in any subsequent quorum will have witnessed all committed operations.
	 *
	 * @return the minimum number of replicas needed for a quorum
	 */
	public int getQuorum() {
		return (this.configuration.length / 2) + 1;
	}


}
