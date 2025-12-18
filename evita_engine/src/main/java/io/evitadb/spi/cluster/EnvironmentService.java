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

package io.evitadb.spi.cluster;

import io.evitadb.spi.cluster.model.ClusterEnvironment;

import javax.annotation.Nonnull;

/**
 * Service interface for managing cluster environment and leadership operations.
 *
 * This interface provides methods for a replica to query the current cluster configuration
 * and manage its leadership status within the VSR protocol.
 *
 * **Leadership Management:**
 *
 * In VSR, one replica acts as the primary (leader) and coordinates all state changes.
 * This interface provides methods for leadership operations that may involve external
 * coordination (e.g., with a distributed lock service or consensus system).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface EnvironmentService {

	/**
	 * Returns the current cluster environment configuration.
	 *
	 * The returned environment contains the current view of cluster membership,
	 * including all replica addresses, the current epoch, and this replica's
	 * index within the configuration.
	 *
	 * @return current cluster environment, never null
	 */
	@Nonnull
	ClusterEnvironment getEnvironment();

	/**
	 * Attempts to claim leadership (primary role) for this replica.
	 *
	 * In VSR, primary election is deterministic based on `viewNumber % clusterSize`.
	 * However, this method handles the external coordination required to ensure
	 * only one replica acts as primary at a time (e.g., acquiring a distributed lock).
	 *
	 * This method should be called when:
	 * - This replica's index equals `viewNumber % clusterSize`
	 * - After winning a view change election
	 * - During initial cluster startup
	 *
	 * @return true if leadership was successfully claimed, false if another replica
	 *         already holds leadership or the claim failed
	 */
	boolean claimLeadership();

	/**
	 * Renews the leadership lease for this replica.
	 *
	 * The primary must periodically call this method to maintain its leadership
	 * status. This typically involves renewing a distributed lock or heartbeat.
	 * If this method returns false, the replica should initiate a view change
	 * as it has lost leadership.
	 *
	 * @return true if leadership was successfully maintained, false if leadership
	 *         was lost (e.g., lease expired, lock stolen)
	 */
	boolean maintainLeadership();

	/**
	 * Voluntarily resigns leadership.
	 *
	 * This method should be called when:
	 * - The primary is shutting down gracefully
	 * - A view change is initiated and this replica should step down
	 * - The replica detects it should no longer be primary
	 *
	 * After calling this method, the replica transitions to backup status
	 * and a view change may be triggered to elect a new primary.
	 */
	void resignLeadership();

}
