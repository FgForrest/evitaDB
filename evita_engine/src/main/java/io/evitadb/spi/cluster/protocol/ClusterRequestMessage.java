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

package io.evitadb.spi.cluster.protocol;

/**
 * Interface for request messages sent from one replica to another in the VSR protocol.
 *
 * Request messages are typically sent in the following scenarios:
 *
 * - **Normal operation**: Primary sends PREPARE and COMMIT messages to backup replicas
 * - **View change**: Replicas exchange StartViewChange, DoViewChange, and StartView messages
 * - **Recovery**: Recovering replica requests state from other replicas
 * - **State transfer**: Replica requests WAL entries or snapshots for synchronization
 * - **Reconfiguration**: Cluster membership changes are coordinated via epoch messages
 *
 * Each request message specifies both the sender ({@link #selfIndex()}) and the intended
 * recipient ({@link #targetReplicaIndex()}), enabling point-to-point communication between
 * specific replicas in the cluster.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see ClusterResponseMessage
 * @see HashChainedClusterRequestMessage
 */
public interface ClusterRequestMessage extends ClusterMessage {

	/**
	 * Returns the index of the target replica in the current cluster configuration array.
	 *
	 * This identifies which replica should process this request message. The target replica
	 * will typically respond with a corresponding {@link ClusterResponseMessage}.
	 *
	 * @return the zero-based index of the destination replica in the cluster configuration
	 */
	int targetReplicaIndex();

}
