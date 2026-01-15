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

package io.evitadb.spi.cluster.protocol.recovery;

import io.evitadb.spi.cluster.model.ClusterEnvironment;
import io.evitadb.spi.cluster.model.ReplicaState;
import io.evitadb.spi.cluster.model.ViewState;
import io.evitadb.spi.cluster.protocol.CatalogVersions;
import io.evitadb.spi.cluster.protocol.ClusterResponseMessage;
import io.evitadb.utils.UUIDUtil;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * RECOVERY response providing current cluster state information.
 *
 * This response provides the recovering replica with the current cluster state,
 * allowing it to determine how to synchronize. The response includes version information
 * for both engine state and all catalogs.
 *
 * **State Information:**
 *
 * The response provides both observed (op-number) and committed (commit-number) versions:
 *
 * - `engineVersion` / `committedEngineVersion`: Engine state version pair
 * - `catalogVersions`: Array of {@link io.evitadb.spi.cluster.protocol.CatalogVersions}
 * containing both observed and committed versions for each catalog
 *
 * **Recovery Decision:**
 *
 * Based on this response, the recovering replica can:
 *
 * 1. Determine if it needs state transfer (local version < cluster version)
 * 2. Request incremental WAL transfer if only slightly behind
 * 3. Request full snapshot transfer if significantly behind
 * 4. Join immediately if already synchronized
 *
 * @param selfIndex              responding replica's index
 * @param epoch                  current configuration epoch
 * @param viewNumber             current view number
 * @param viewState              current view state of the responding replica
 * @param nonce                  echoed nonce from the request for correlation
 * @param primaryIndex           index of the leader replica in the cluster
 * @param engineVersion          current engine op-number (observed version)
 * @param committedEngineVersion current engine commit-number
 * @param catalogVersions        array of catalog versions (catalog id and version pairs)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see RecoveryRequest
 */
public record RecoveryResponse(
	int selfIndex,
	long epoch,
	long viewNumber,
	@Nonnull ViewState viewState,
	@Nonnull UUID nonce,
	int primaryIndex,
	long engineVersion,
	long committedEngineVersion,
	@Nonnull CatalogVersions[] catalogVersions,
	@Nonnull ClusterEnvironment environment
) implements ClusterResponseMessage {

	/**
	 * Creates a new instance of {@link RecoveryResponse} using the provided replica state and cluster environment.
	 *
	 * This method initializes a recovery response object based on the current state of the replica
	 * and its knowledge of the cluster environment. The response encapsulates key information
	 * about the replica's identity and configuration, which can be used by other replicas
	 * for synchronization and recovery.
	 *
	 * @param replicaState       the current state of the replica, providing details such as the replica's number,
	 *                           epoch, and view number
	 * @param clusterEnvironment the environment configuration of the cluster, containing details about
	 *                           cluster members and this replica's index within the cluster
	 * @return a {@link RecoveryResponse} object that contains the synthesized state and recovery details
	 */
	@Nonnull
	public static RecoveryResponse fromSelf(
		@Nonnull ReplicaState replicaState,
		@Nonnull ClusterEnvironment clusterEnvironment
	) {
		return new RecoveryResponse(
			replicaState.replicaNumber(),
			replicaState.epoch(),
			replicaState.viewNumber(),
			replicaState.status(),
			UUIDUtil.randomUUID(),
			(int) (replicaState.viewNumber() % replicaState.configuration().length),
			0L,
			0L,
			new CatalogVersions[0],
			clusterEnvironment
		);
	}

	/**
	 * Determines if the current replica is the primary replica.
	 *
	 * This method compares the replica's own index with the primary index to identify
	 * whether it is designated as the primary replica within the cluster.
	 *
	 * @return {@code true} if the current replica's index matches the primary index, otherwise {@code false}
	 */
	public boolean isPrimary() {
		return this.selfIndex == this.primaryIndex;
	}
}
