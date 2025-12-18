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

import io.evitadb.spi.cluster.protocol.normalFlow.CommitCatalogVersionRequest;
import io.evitadb.spi.cluster.protocol.normalFlow.CommitCatalogVersionResponse;
import io.evitadb.spi.cluster.protocol.normalFlow.CommitEngineStateRequest;
import io.evitadb.spi.cluster.protocol.normalFlow.CommitEngineStateResponse;
import io.evitadb.spi.cluster.protocol.normalFlow.PrepareCatalogVersionRequest;
import io.evitadb.spi.cluster.protocol.normalFlow.PrepareCatalogVersionResponse;
import io.evitadb.spi.cluster.protocol.normalFlow.PrepareEngineStateRequest;
import io.evitadb.spi.cluster.protocol.normalFlow.PrepareEngineStateResponse;
import io.evitadb.spi.cluster.protocol.reconfiguration.EpochStartedRequest;
import io.evitadb.spi.cluster.protocol.reconfiguration.EpochStartedResponse;
import io.evitadb.spi.cluster.protocol.reconfiguration.ReconfigurationRequest;
import io.evitadb.spi.cluster.protocol.reconfiguration.ReconfigurationResponse;
import io.evitadb.spi.cluster.protocol.reconfiguration.StartEpochRequest;
import io.evitadb.spi.cluster.protocol.reconfiguration.StartEpochResponse;
import io.evitadb.spi.cluster.protocol.recovery.RecoveryRequest;
import io.evitadb.spi.cluster.protocol.recovery.RecoveryResponse;
import io.evitadb.spi.cluster.protocol.stateTransfer.GetCatalogSnapshotPageRequest;
import io.evitadb.spi.cluster.protocol.stateTransfer.GetCatalogSnapshotPageResponse;
import io.evitadb.spi.cluster.protocol.stateTransfer.GetCatalogSnapshotRequest;
import io.evitadb.spi.cluster.protocol.stateTransfer.GetCatalogSnapshotResponse;
import io.evitadb.spi.cluster.protocol.stateTransfer.GetCatalogStateRequest;
import io.evitadb.spi.cluster.protocol.stateTransfer.GetCatalogStateResponse;
import io.evitadb.spi.cluster.protocol.stateTransfer.GetEngineStateRequest;
import io.evitadb.spi.cluster.protocol.stateTransfer.GetEngineStateResponse;
import io.evitadb.spi.cluster.protocol.viewChange.DoViewChangeRequest;
import io.evitadb.spi.cluster.protocol.viewChange.DoViewChangeResponse;
import io.evitadb.spi.cluster.protocol.viewChange.StartViewChangeRequest;
import io.evitadb.spi.cluster.protocol.viewChange.StartViewChangeResponse;
import io.evitadb.spi.cluster.protocol.viewChange.StartViewRequest;
import io.evitadb.spi.cluster.protocol.viewChange.StartViewResponse;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletionStage;

/**
 * Service interface defining the VSR protocol operations for cluster communication.
 *
 * This interface defines all the remote procedure calls (RPCs) that replicas use to communicate
 * with each other in the Viewstamped Replication protocol. Each method corresponds to a specific
 * protocol message exchange and returns a {@link CompletionStage} for asynchronous operation.
 *
 * **Protocol Operations:**
 *
 * **Recovery Protocol:**
 * - {@link #recovery} - Recovering replica requests current cluster state
 *
 * **State Transfer:**
 * - {@link #getEngineState} - Request engine WAL entries for synchronization
 * - {@link #getCatalogState} - Request catalog WAL entries for synchronization
 * - {@link #getCatalogSnapshot} - Request catalog snapshot metadata
 * - {@link #getCatalogSnapshotPage} - Request catalog snapshot data pages
 *
 * **Normal Operation (Two-Phase Commit):**
 * - {@link #prepareEngineState} - Primary sends engine state operation to backups (PREPARE phase)
 * - {@link #commitEngineState} - Primary tells backups to commit engine state (COMMIT phase)
 * - {@link #prepareCatalogVersion} - Primary sends catalog operation to backups (PREPARE phase)
 * - {@link #commitCatalogVersion} - Primary tells backups to commit catalog (COMMIT phase)
 *
 * **View Change Protocol:**
 * - {@link #startViewChange} - Replica initiates view change on detecting primary failure
 * - {@link #doViewChange} - Replica sends its state to new primary candidate
 * - {@link #startView} - New primary announces the new view to all replicas
 *
 * **Reconfiguration Protocol (VSR Revisited):**
 * - {@link #reconfigure} - Request cluster membership change
 * - {@link #startEpoch} - Primary initiates new epoch with new configuration
 * - {@link #epochStarted} - Confirm epoch transition completed
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ViewStampedReplicationService {

	/**
	 * Handles a RECOVERY request from a replica rejoining the cluster.
	 *
	 * The responding replica provides its current state information including
	 * epoch, view number, and version information for engine and all catalogs.
	 * This allows the recovering replica to determine how far behind it is
	 * and what state transfer is needed.
	 *
	 * @param request the recovery request containing the recovering replica's identity
	 * @return completion stage with response containing current cluster state
	 */
	@Nonnull
	CompletionStage<RecoveryResponse> recovery(@Nonnull RecoveryRequest request);

	/**
	 * Handles a request for incremental engine state via WAL transfer.
	 *
	 * Returns WAL entries starting from the requested version, allowing
	 * the requesting replica to catch up on engine state changes without
	 * requiring a full snapshot transfer.
	 *
	 * @param request the request specifying starting version and entry limit
	 * @return completion stage with response containing WAL entries
	 */
	@Nonnull
	CompletionStage<GetEngineStateResponse> getEngineState(@Nonnull GetEngineStateRequest request);

	/**
	 * Handles a request for incremental catalog state via WAL transfer.
	 *
	 * Returns WAL entries for the specified catalog starting from the requested
	 * version. Each catalog maintains independent versioning and WAL.
	 *
	 * @param request the request specifying catalog, starting version, and limit
	 * @return completion stage with response containing catalog WAL entries
	 */
	@Nonnull
	CompletionStage<GetCatalogStateResponse> getCatalogState(@Nonnull GetCatalogStateRequest request);

	/**
	 * Handles a request to initiate full catalog snapshot transfer.
	 *
	 * Returns metadata about the snapshot including total size and checksum.
	 * The actual snapshot data is retrieved via subsequent
	 * {@link #getCatalogSnapshotPage} calls.
	 *
	 * @param request the request specifying which catalog to snapshot
	 * @return completion stage with response containing snapshot metadata
	 */
	@Nonnull
	CompletionStage<GetCatalogSnapshotResponse> getCatalogSnapshot(@Nonnull GetCatalogSnapshotRequest request);

	/**
	 * Handles a request for a page of catalog snapshot data.
	 *
	 * Returns a portion of the snapshot binary data at the specified offset.
	 * Multiple page requests are typically needed to transfer a complete snapshot.
	 *
	 * @param request the request specifying catalog, offset, and page size
	 * @return completion stage with response containing snapshot page data
	 */
	@Nonnull
	CompletionStage<GetCatalogSnapshotPageResponse> getCatalogSnapshotPage(@Nonnull GetCatalogSnapshotPageRequest request);

	/**
	 * Handles the PREPARE phase for engine state replication.
	 *
	 * The primary sends this to backups with the proposed engine state operation.
	 * Backups tentatively accept the operation (write to WAL) and respond.
	 * The primary waits for quorum before proceeding to COMMIT.
	 *
	 * @param request the prepare request with engine operation and WAL data
	 * @return completion stage with response confirming prepare acceptance
	 */
	@Nonnull
	CompletionStage<PrepareEngineStateResponse> prepareEngineState(@Nonnull PrepareEngineStateRequest request);

	/**
	 * Handles the COMMIT phase for engine state replication.
	 *
	 * The primary sends this after receiving quorum of PREPARE responses.
	 * Backups make the operation durable and apply it to their state machine.
	 *
	 * @param request the commit request with version to commit
	 * @return completion stage with response confirming commit completion
	 */
	@Nonnull
	CompletionStage<CommitEngineStateResponse> commitEngineState(@Nonnull CommitEngineStateRequest request);

	/**
	 * Handles the PREPARE phase for catalog version replication.
	 *
	 * The primary sends this to backups with the proposed catalog operation.
	 * Each catalog is replicated independently with its own version sequence.
	 *
	 * @param request the prepare request with catalog operation and WAL data
	 * @return completion stage with response confirming prepare acceptance
	 */
	@Nonnull
	CompletionStage<PrepareCatalogVersionResponse> prepareCatalogVersion(@Nonnull PrepareCatalogVersionRequest request);

	/**
	 * Handles the COMMIT phase for catalog version replication.
	 *
	 * The primary sends this after receiving quorum of PREPARE responses
	 * for the catalog operation. Backups commit the catalog version.
	 *
	 * @param request the commit request with catalog and version to commit
	 * @return completion stage with response confirming commit completion
	 */
	@Nonnull
	CompletionStage<CommitCatalogVersionResponse> commitCatalogVersion(@Nonnull CommitCatalogVersionRequest request);

	/**
	 * Handles STARTVIEWCHANGE message initiating a view change.
	 *
	 * Sent by a replica that suspects the primary has failed. Receiving replicas
	 * respond to indicate they also want to change views. Once a quorum agrees,
	 * the process continues with DOVIEWCHANGE.
	 *
	 * @param request the view change initiation request
	 * @return completion stage with response acknowledging the view change
	 */
	@Nonnull
	CompletionStage<StartViewChangeResponse> startViewChange(@Nonnull StartViewChangeRequest request);

	/**
	 * Handles DOVIEWCHANGE message containing replica state for new primary.
	 *
	 * Sent to the replica that will become the new primary. Contains the sender's
	 * state information so the new primary can select the most up-to-date state
	 * and reconstruct the authoritative log.
	 *
	 * @param request the request containing replica's state information
	 * @return completion stage with response acknowledging receipt
	 */
	@Nonnull
	CompletionStage<DoViewChangeResponse> doViewChange(@Nonnull DoViewChangeRequest request);

	/**
	 * Handles STARTVIEW message announcing the new view.
	 *
	 * Sent by the new primary after collecting DOVIEWCHANGE from quorum and
	 * selecting authoritative state. Backups accept the new primary and
	 * synchronize their state.
	 *
	 * @param request the request announcing new view and commit positions
	 * @return completion stage with response accepting the new view
	 */
	@Nonnull
	CompletionStage<StartViewResponse> startView(@Nonnull StartViewRequest request);

	/**
	 * Handles a cluster reconfiguration request.
	 *
	 * Initiates the VSR Revisited reconfiguration protocol to change cluster
	 * membership. Requires quorum agreement from the old configuration before
	 * the new configuration can take effect.
	 *
	 * @param request the reconfiguration request with new cluster membership
	 * @return completion stage with response acknowledging the request
	 */
	@Nonnull
	CompletionStage<ReconfigurationResponse> reconfigure(@Nonnull ReconfigurationRequest request);

	/**
	 * Handles STARTEPOCH message transitioning to new configuration.
	 *
	 * Sent by the reconfiguration leader after old configuration quorum agrees.
	 * Replicas update their configuration and prepare for the new epoch.
	 *
	 * @param request the request with old and new cluster membership
	 * @return completion stage with response confirming epoch acceptance
	 */
	@Nonnull
	CompletionStage<StartEpochResponse> startEpoch(@Nonnull StartEpochRequest request);

	/**
	 * Handles EPOCHSTARTED message confirming epoch transition.
	 *
	 * Sent between new configuration members to confirm they have all
	 * transitioned to the new epoch. Once quorum confirms, normal operation
	 * can begin in the new configuration.
	 *
	 * @param request the request confirming epoch transition
	 * @return completion stage with response acknowledging the confirmation
	 */
	@Nonnull
	CompletionStage<EpochStartedResponse> epochStarted(@Nonnull EpochStartedRequest request);

}
