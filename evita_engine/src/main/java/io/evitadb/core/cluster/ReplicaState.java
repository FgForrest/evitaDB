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

import io.evitadb.spi.cluster.model.ReplicaClusterState;
import io.evitadb.spi.cluster.model.ViewState;
import io.evitadb.spi.cluster.protocol.CatalogVersions;
import io.evitadb.spi.cluster.protocol.recovery.RecoveryResponse;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Represents the state of the Primary replica in the Viewstamped Replication (VSR) protocol.
 *
 * This record captures the critical state that a Primary replica maintains and communicates to other replicas
 * during view changes or state synchronization. It includes the current view configuration, the highest
 * op-number assigned, and the current commit-number.
 *
 * **State Components:**
 *
 * - `replicaClusterState`: general replica information including current `viewNumber` and `epoch`
 * - `engineVersion`: the highest op-number (version) that has been assigned to a request by this Primary
 * - `committedEngineVersion`: the highest commit-number (version) that is known to be committed by a majority
 * - `catalogVersions`: versions of individual catalogs, each tracking its own op-number and commit-number
 *
 * @param replicaClusterState           general information about the replica's position in the cluster and protocol
 * @param engineVersion          the current op-number (version) of the engine state
 * @param committedEngineVersion the current commit-number (version) of the engine state
 * @param catalogVersions        the current versions of individual catalogs managed by the engine
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ReplicaState(
	@Nonnull ReplicaClusterState replicaClusterState,
	long engineVersion,
	long committedEngineVersion,
	@Nonnull CatalogVersions[] catalogVersions
) implements Serializable {

	/**
	 * Secondary constructor that creates a new `ReplicaState` from a `RecoveryResponse`.
	 *
	 * This constructor is typically used when a replica is assuming the Primary role or when synchronization
	 * is performed based on a response from another replica.
	 *
	 * @param currentReplicaIndex the index of the replica for which the state is being created
	 * @param viewState           the current operational state of the replica
	 * @param response            the recovery response containing the latest cluster and version information
	 */
	public ReplicaState(
		int currentReplicaIndex,
		@Nonnull ViewState viewState,
		@Nonnull RecoveryResponse response
	) {
		this(
			new ReplicaClusterState(
				response.environment().clusterMembers(),
				response.environment().clusterMembers(),
				currentReplicaIndex,
				response.epoch(),
				response.viewNumber(),
				viewState
			),
			response.engineVersion(),
			response.committedEngineVersion(),
			response.catalogVersions()
		);
	}

	/**
	 * Returns the number of replicas in the cluster as reported by the current replica cluster state.
	 *
	 * This information is typically used to determine the size of the cluster
	 * and may be utilized in operations such as quorum calculations or replica selection.
	 *
	 * @return the total number of replicas in the cluster
	 */
	public int replicaNumber() {
		return this.replicaClusterState.replicaNumber();
	}

	/**
	 * Returns the current configuration epoch.
	 *
	 * The epoch is incremented whenever the cluster configuration changes (reconfiguration).
	 *
	 * @return the current epoch number
	 */
	public long epoch() {
		return this.replicaClusterState.epoch();
	}

	/**
	 * Returns the current view number.
	 *
	 * The view number is incremented each time a new Primary is elected within the same epoch.
	 *
	 * @return the current view number
	 */
	public long viewNumber() {
		return this.replicaClusterState.viewNumber();
	}

	/**
	 * Determines the role of this replica (PRIMARY or BACKUP) based on the current view number
	 * and the replica's index in the configuration.
	 *
	 * @return the role of this replica
	 */
	@Nonnull
	public ReplicaRole getRole() {
		return this.replicaClusterState.viewNumber() % this.replicaClusterState.configuration().length == this.replicaClusterState.replicaNumber()
			? ReplicaRole.PRIMARY
			: ReplicaRole.BACKUP;
	}
}
