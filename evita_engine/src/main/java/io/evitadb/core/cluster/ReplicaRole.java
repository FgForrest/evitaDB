/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.cluster;

import io.evitadb.spi.cluster.protocol.recovery.RecoveryResponse;

/**
 * Represents the role of a replica in the Viewstamped Replication protocol.
 *
 * In Viewstamped Replication, replicas can assume one of two roles:
 * - **Primary**: The replica responsible for ordering client operations and coordinating replication
 * - **Backup**: Replicas that maintain copies of the state and participate in the replication protocol
 *
 * The primary role rotates among replicas through view changes. In each view, exactly one replica
 * acts as the primary while all others act as backups. The primary receives client requests,
 * assigns operation numbers (op-numbers), and coordinates the replication protocol to ensure
 * all backups maintain consistent state.
 *
 * Role transitions occur during view changes, which can be triggered by primary failure detection
 * or configuration changes. After a view change completes, the new primary is determined by
 * the formula: `primaryIndex = viewNumber % replicaCount`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see io.evitadb.spi.cluster.model.ViewState
 * @see RecoveryResponse
 */
public enum ReplicaRole {

	/**
	 * The replica is currently acting as the primary in the cluster.
	 *
	 * In Viewstamped Replication, the primary replica is responsible for:
	 * - Receiving and ordering client operations by assigning sequential op-numbers
	 * - Coordinating the PREPARE phase by sending PREPARE messages to backups
	 * - Tracking commit points and advancing the commit-number
	 * - Broadcasting COMMIT messages to inform backups of committed operations
	 * - Processing client queries and returning results
	 *
	 * The primary is determined by the current view number: `primaryIndex = viewNumber % replicaCount`.
	 * During normal operation, the primary coordinates all state changes. If the primary fails,
	 * backups initiate a view change to elect a new primary.
	 */
	PRIMARY,
	/**
	 * The replica is currently acting as a backup (secondary) in the cluster.
	 *
	 * In Viewstamped Replication, backup replicas are responsible for:
	 * - Receiving PREPARE messages from the primary and executing operations in order
	 * - Sending PREPARE_OK responses to acknowledge successful operation execution
	 * - Receiving COMMIT messages to advance their local commit-number
	 * - Monitoring the primary's health and initiating view changes if necessary
	 * - Participating in the view change protocol to elect a new primary
	 * - Maintaining consistent state with the primary through the replication protocol
	 *
	 * Backups execute operations speculatively (advancing op-number) but only consider them
	 * committed once the commit-number advances. This allows the system to make progress
	 * while ensuring durability through the replication protocol.
	 */
	BACKUP

}
