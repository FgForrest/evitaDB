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

import java.io.Serializable;

/**
 * Base interface for all messages exchanged between replicas in the Viewstamped Replication (VSR) protocol.
 *
 * This interface serves as the root of the cluster message hierarchy, ensuring all messages are serializable
 * and carry the sender's identity. The VSR protocol uses these messages to maintain consistency across
 * distributed replicas through a combination of primary-backup replication and view change mechanisms.
 *
 * **Message Categories:**
 *
 * - **Request messages** ({@link ClusterRequestMessage}): Sent from one replica to another, typically
 *   from the primary to backups during normal operation, or between any replicas during view changes
 * - **Response messages** ({@link ClusterResponseMessage}): Sent in reply to request messages
 *
 * **Hash Chain Verification:**
 *
 * Messages that participate in ordered state replication implement {@link HashChained} to enable
 * cumulative hash verification, ensuring message integrity and ordering consistency across replicas.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see ClusterRequestMessage
 * @see ClusterResponseMessage
 * @see HashChained
 */
public interface ClusterMessage extends Serializable {

	/**
	 * Returns the index of the sender replica in the current cluster configuration array.
	 *
	 * In VSR, each replica is identified by its position (0 to n-1) in the ordered configuration array.
	 * This index is used to:
	 *
	 * - Identify which replica sent the message
	 * - Calculate the primary replica using the formula: `viewNumber % clusterSize`
	 * - Track acknowledgments from specific replicas during quorum calculations
	 *
	 * @return the zero-based index of the sender replica in the cluster configuration
	 */
	int selfIndex();

}
