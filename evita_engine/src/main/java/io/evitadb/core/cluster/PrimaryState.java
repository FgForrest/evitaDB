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

import io.evitadb.spi.cluster.model.ReplicaState;
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
 * - `replicaState`: general replica information including current `viewNumber` and `epoch`
 * - `engineVersion`: the highest op-number (version) that has been assigned to a request by this Primary
 * - `committedEngineVersion`: the highest commit-number (version) that is known to be committed by a majority
 * - `catalogVersions`: versions of individual catalogs, each tracking its own op-number and commit-number
 *
 * @param replicaState           general information about the replica's position in the cluster and protocol
 * @param engineVersion          the current op-number (version) of the engine state
 * @param committedEngineVersion the current commit-number (version) of the engine state
 * @param catalogVersions        the current versions of individual catalogs managed by the engine
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record PrimaryState(
	@Nonnull ReplicaState replicaState,
	long engineVersion,
	long committedEngineVersion,
	@Nonnull CatalogVersions[] catalogVersions
) implements Serializable {

	/**
	 * Secondary constructor that creates a new `PrimaryState` from a `RecoveryResponse`.
	 *
	 * This constructor is typically used when a replica is assuming the Primary role or when synchronization
	 * is performed based on a response from another replica.
	 *
	 * @param currentReplicaIndex the index of the replica for which the state is being created
	 * @param viewState           the current operational state of the replica
	 * @param response            the recovery response containing the latest cluster and version information
	 */
	public PrimaryState(
		int currentReplicaIndex,
		@Nonnull ViewState viewState,
		@Nonnull RecoveryResponse response
	) {
		this(
			new ReplicaState(
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
	 * Returns the current configuration epoch.
	 *
	 * The epoch is incremented whenever the cluster configuration changes (reconfiguration).
	 *
	 * @return the current epoch number
	 */
	public long epoch() {
		return this.replicaState.epoch();
	}

	/**
	 * Returns the current view number.
	 *
	 * The view number is incremented each time a new Primary is elected within the same epoch.
	 *
	 * @return the current view number
	 */
	public long viewNumber() {
		return this.replicaState.viewNumber();
	}
}
