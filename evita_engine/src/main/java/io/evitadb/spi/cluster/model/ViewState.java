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

package io.evitadb.spi.cluster.model;

/**
 * Represents the operational state of the evitaDB cluster using Viewstamped Replication protocol.
 *
 * The cluster transitions between states based on the health and availability of nodes. The state
 * determines how the cluster processes client requests and manages replication.
 *
 * **Viewstamped Replication Overview:**
 *
 * The protocol operates with a set of replicas, where one replica acts as the **primary** (leader)
 * and others act as **backups** (followers). The primary is responsible for ordering client requests
 * and coordinating with backups to ensure consistency.
 *
 * **State Transitions:**
 *
 * - `NORMAL` → `VIEW_CHANGE`: Triggered when the primary fails or becomes unresponsive, requiring
 *   election of a new primary
 * - `VIEW_CHANGE` → `NORMAL`: Completed when a new primary is elected and the new view is established
 * - `NORMAL` → `RECOVERING`: When a replica rejoins the cluster after being unavailable
 * - `RECOVERING` → `NORMAL`: After the replica synchronizes its state with the current cluster state
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public enum ViewState {
	/**
	 * The cluster is operating in **normal mode** of the Viewstamped Replication protocol.
	 *
	 * In this state:
	 *
	 * - A **primary replica** (leader) receives client requests and assigns sequence numbers (op-numbers)
	 * - The primary sends **PREPARE** messages to backups containing operations to be executed
	 * - **Backup replicas** (followers) execute operations in order and send **PREPAREOK** responses
	 * - Operations are committed once the primary receives acknowledgments from a quorum of backups
	 * - Client requests are processed with normal latency and throughput
	 *
	 * The cluster remains in this state as long as all replicas are healthy and the primary is responsive.
	 */
	NORMAL,
	/**
	 * The cluster is undergoing a **view change** to elect a new primary replica.
	 *
	 * A view change is initiated when:
	 *
	 * - The current primary fails or becomes unresponsive
	 * - A backup detects primary failure through timeout mechanisms
	 * - Network partitions prevent communication with the primary
	 *
	 * During view change:
	 *
	 * - Client request processing is temporarily suspended
	 * - Replicas enter view change protocol by sending **STARTVIEWCHANGE** messages
	 * - A new primary candidate collects state information via **DOVIEWCHANGE** messages
	 * - The new primary sends **STARTVIEW** messages to establish the new view
	 * - The **view-number** is incremented to reflect the new configuration
	 *
	 * Once the new view is established with a quorum, the cluster transitions back to `NORMAL` state.
	 */
	VIEW_CHANGE,
	/**
	 * A replica is in **recovery mode**, synchronizing its state with the current cluster.
	 *
	 * A replica enters recovery when:
	 *
	 * - It rejoins the cluster after being offline or partitioned
	 * - Its state is behind the current cluster state (missing committed operations)
	 * - It needs to catch up with operations committed during its absence
	 *
	 * During recovery:
	 *
	 * - The replica sends **RECOVERY** messages to the primary
	 * - The primary responds with **RECOVERYRESPONSE** containing recent operations
	 * - The replica applies missing operations to synchronize its state
	 * - The replica updates its view-number and op-number to match the cluster
	 *
	 * After synchronization completes, the replica transitions to `NORMAL` state and can participate
	 * as a backup in the replication protocol.
	 */
	RECOVERING

}
