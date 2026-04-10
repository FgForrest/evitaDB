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

package io.evitadb.core.cluster;

import io.evitadb.api.configuration.ClusterOptions;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.core.Evita;
import io.evitadb.core.exception.ClusterEnvironmentNotFoundException;
import io.evitadb.core.executor.ScheduledTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.cluster.EnvironmentService;
import io.evitadb.spi.cluster.EnvironmentServiceFactory;
import io.evitadb.spi.cluster.ViewStampedReplicationService;
import io.evitadb.spi.cluster.ViewStampedReplicationServiceFactory;
import io.evitadb.spi.cluster.model.ClusterEnvironment;
import io.evitadb.spi.cluster.model.ReplicaClusterState;
import io.evitadb.spi.cluster.model.ViewState;
import io.evitadb.spi.cluster.protocol.recovery.RecoveryRequest;
import io.evitadb.spi.cluster.protocol.recovery.RecoveryResponse;
import io.evitadb.spi.cluster.protocol.stateTransfer.GetEngineSnapshotRequest;
import io.evitadb.spi.cluster.protocol.stateTransfer.GetEngineSnapshotResponse;
import io.evitadb.spi.cluster.protocol.stateTransfer.GetEngineStateRequest;
import io.evitadb.spi.cluster.protocol.stateTransfer.GetEngineStateResponse;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.UUIDUtil;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.InetAddress;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Central wiring point for cluster-related services.
 *
 * Initializes and exposes the cluster {@link EnvironmentService} and the
 * {@link ViewStampedReplicationService} using their respective SPI factories
 * discovered via {@link ServiceLoader}.
 *
 * Selection logic:
 * - When {@link ClusterOptions} are provided, the factory whose implementation code matches
 * {@link ClusterOptions#getImplementationCode()} is used to create both services.
 * - When no options are provided, the {@link EnvironmentService} remains {@code null} (cluster
 * disabled), while the {@link ViewStampedReplicationService} is selected by the highest
 * {@link ViewStampedReplicationServiceFactory#getPriority()} and initialized with
 * {@link ViewStampedReplicationServiceFactory#createDefaultOptions()} if any implementation
 * is present; otherwise {@code null} is used as well.
 *
 * The created services are immutable references for the lifetime of this instance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class ClusterManagement implements AutoCloseable {
	private static final Duration RECONCILIATION_TIMEOUT = Duration.ofMinutes(1);
	private static final Duration RECONCILIATION_RETRY_DELAY = Duration.ofSeconds(1);
	/**
	 * Maximum number of WAL entries fetched from the primary in a single `getEngineState` RPC call during
	 * catch-up synchronization. Keeping this bounded prevents arbitrarily large RPC payloads and allows the
	 * `onProgress` callback to be invoked between pages, which resets the reconciliation deadline timer.
	 */
	private static final int WAL_SYNC_PAGE_SIZE = 1000;
	@Nonnull
	private final ViewStampedReplicationService viewStampedReplicationService;
	@Nonnull
	private final AtomicReference<ReplicaClusterState> replicaState;
	private final ScheduledTask clusterReconciliationTask;
	/**
	 * Reference to the owning evitaDB engine instance. Used to access the current expanded engine state
	 * (including WAL reference and version), to apply incoming WAL batches during catch-up, and to obtain
	 * the service executor for scheduling asynchronous work.
	 */
	private final Evita evita;

	/**
	 * Initializes the {@link EnvironmentService} based on the provided {@link ClusterOptions}.
	 * This method uses the {@link ServiceLoader} mechanism to dynamically locate the appropriate
	 * {@link EnvironmentServiceFactory} implementation for the given cluster configuration.
	 * If a matching implementation cannot be found, an exception is thrown.
	 *
	 * @param clusterOptions the configuration options for the cluster environment; must not be null
	 * @return an initialized {@link EnvironmentService} instance
	 * @throws ClusterEnvironmentNotFoundException if no {@link EnvironmentServiceFactory} matches the
	 *                                             implementation code specified in {@code clusterOptions}
	 */
	@Nonnull
	private static EnvironmentService initializeEnvironmentService(@Nonnull ClusterOptions clusterOptions) {
		final ServiceLoader<EnvironmentServiceFactory> loader = ServiceLoader.load(EnvironmentServiceFactory.class);
		final String implementationCode = clusterOptions.getImplementationCode();
		clusterOptions.validateWhenEnabled();

		final Optional<EnvironmentServiceFactory> matched = loader
			.stream()
			.map(ServiceLoader.Provider::get)
			.filter(factory -> implementationCode.equals(factory.getImplementationCode()))
			.findFirst();

		return matched
			.map(factory -> factory.create(clusterOptions))
			.orElseThrow(() -> new ClusterEnvironmentNotFoundException("EnvironmentService", implementationCode));
	}

	/**
	 * Initializes the {@link ViewStampedReplicationService} using the specified {@link ClusterOptions}.
	 * This method leverages the {@link ServiceLoader} mechanism to dynamically locate the appropriate
	 * {@link ViewStampedReplicationServiceFactory} implementation matching the given cluster configuration.
	 * If no matching implementation is found, an exception is thrown.
	 *
	 * @param clusterOptions the configuration options for the cluster environment; must not be null
	 * @return an initialized {@link ViewStampedReplicationService} instance
	 * @throws ClusterEnvironmentNotFoundException if no {@link ViewStampedReplicationServiceFactory} matches
	 *                                             the implementation code specified in {@code clusterOptions}
	 */
	@Nonnull
	private static ViewStampedReplicationService initializeVsrService(@Nonnull ClusterOptions clusterOptions) {
		final ServiceLoader<ViewStampedReplicationServiceFactory> loader =
			ServiceLoader.load(ViewStampedReplicationServiceFactory.class);

		final String implementationCode = clusterOptions.getImplementationCode();
		clusterOptions.validateWhenEnabled();
		final Optional<ViewStampedReplicationServiceFactory> matched = loader
			.stream()
			.map(ServiceLoader.Provider::get)
			.filter(factory -> implementationCode.equals(factory.getImplementationCode()))
			.findFirst();
		return matched
			.map(factory -> factory.create(clusterOptions))
			.orElseThrow(
				() -> new ClusterEnvironmentNotFoundException("ViewStampedReplicationService", implementationCode));
	}

	/**
	 * Validates whether the nonce of the received recovery response matches the expected nonce.
	 * If the nonces match, the method returns the given response. Otherwise, it logs an
	 * error and throws a {@link GenericEvitaInternalError}.
	 *
	 * @param response      the recovery response to validate; must not be null
	 * @param nonce         the expected nonce for validation; must not be null
	 * @param targetAddress the network address of the replica that sent the recovery response; must not be null
	 * @return the original {@link RecoveryResponse} if the nonce matches the expected value
	 * @throws GenericEvitaInternalError if the nonce does not match the expected value
	 */
	@Nonnull
	private static RecoveryResponse checkNonceMatch(
		@Nonnull RecoveryResponse response,
		@Nonnull UUID nonce,
		@Nonnull InetAddress targetAddress
	) {
		final boolean nonceMatch = Objects.equals(nonce, response.nonce());
		if (nonceMatch) {
			log.trace(
				"Nonce validation successful for response from {}. Nonce: {}",
				targetAddress,
				nonce
			);
			return response;
		} else {
			final String message = "Received recovery response with invalid nonce from replica " +
				targetAddress + "!. Expected " + nonce + " but got " + response.nonce();
			log.error(message);
			throw new GenericEvitaInternalError(
				message, "Received recovery response with invalid nonce!"
			);
		}
	}

	/**
	 * Creates the cluster management wiring using provided cluster options.
	 *
	 * @param clusterOptions cluster configuration to select concrete implementation; when {@code null}
	 *                       cluster remains effectively disabled and services might be {@code null}
	 */
	public ClusterManagement(
		@Nonnull ClusterOptions clusterOptions,
		@Nonnull Evita evita,
		long initialEpoch,
		long initialViewNumber
	) {
		final EnvironmentService environmentService = initializeEnvironmentService(clusterOptions);
		this.viewStampedReplicationService = initializeVsrService(clusterOptions);

		final ClusterEnvironment initialEnvironment = environmentService.getEnvironment();
		final ReplicaClusterState initialReplicaState = new ReplicaClusterState(
			initialEnvironment.clusterMembers(),
			initialEnvironment.clusterMembers(),
			initialEnvironment.selfIndex(),
			initialEpoch,
			initialViewNumber,
			ViewState.RECOVERING
		);
		this.replicaState = new AtomicReference<>(initialReplicaState);
		this.clusterReconciliationTask = new ScheduledTask(
			null,
			"replicaInitialization",
			evita.getServiceExecutor(),
			() -> initializeReplicaState(initialReplicaState, initialEnvironment),
			RECONCILIATION_RETRY_DELAY.toSeconds(),
			TimeUnit.SECONDS
		);
		this.clusterReconciliationTask.scheduleImmediately();
		this.evita = evita;

		log.info(
			"Cluster management initialized for replica {} with {} cluster members. " +
				"Initial state: epoch={}, viewNumber={}, status={}",
			initialEnvironment.selfIndex(),
			initialEnvironment.clusterMembers().length,
			initialEpoch,
			initialViewNumber,
			ViewState.RECOVERING
		);
	}

	@Override
	public void close() {
		IOUtils.closeQuietly(this.clusterReconciliationTask::close);
	}

	/**
	 * Initializes the replica state based on the provided cluster environment. This method determines
	 * the initial state of the replica by resolving and setting it during the first invocation.
	 * If the replica state has already been initialized, the initialization is skipped.
	 *
	 * - If the replica state is successfully resolved and set, subsequent executions are paused.
	 * - If resolution fails, a retry mechanism is triggered.
	 *
	 * @param initialEnvironment the cluster environment configuration used to resolve the initial replica state; must not be null
	 * @return a long value indicating the result of the initialization:
	 * - `-1L` if initialization is complete or unnecessary
	 * - `0L` if initialization needs to be retried
	 */
	private long initializeReplicaState(
		@Nonnull ReplicaClusterState initialReplicaState,
		@Nonnull ClusterEnvironment initialEnvironment
	) {
		final ReplicaClusterState currentState = this.replicaState.get();
		log.debug(
			"Attempting replica state initialization. Current status: {}, epoch={}, viewNumber={}",
			currentState.status(),
			currentState.epoch(),
			currentState.viewNumber()
		);

		if (currentState.status() == ViewState.RECOVERING) {
			final ReplicaClusterState theState = resolveInitialReplicaState(initialReplicaState, initialEnvironment);
			if (theState != null && theState.status() == ViewState.NORMAL) {
				if (!this.replicaState.compareAndSet(initialReplicaState, theState)) {
					log.warn(
						"Replica state was concurrently modified during initialization! " +
							"Expected: {}, but was: {}",
						initialReplicaState,
						this.replicaState.get()
					);
				}
				log.info(
					"Cluster reconciliation completed successfully. Replica state: {}",
					theState
				);
				// reconciliation completed, stop further executions
				return -1L;
			} else {
				log.warn(
					"Cluster reconciliation failed for replica {}. Resolved state was {} (expected NORMAL). " +
						"Will retry in {} ms.",
					initialReplicaState.replicaNumber(),
					theState == null ? "null" : theState.status(),
					RECONCILIATION_RETRY_DELAY.toMillis()
				);
				// reconciliation failed, retry after delay
				return 0L;
			}
		}
		// reconciliation already completed, pause further executions
		return -1L;
	}

	/**
	 * Resolves the initial replica state for the cluster based on the provided environment configuration.
	 * This method orchestrates the reconciliation process to determine the state of the replica
	 * and initializes its attributes accordingly. If the reconciliation fails, it logs an error
	 * and returns null.
	 *
	 * @param initialEnvironment the initial cluster environment used to determine the replica state; must not be null
	 * @return the resolved {@link ReplicaClusterState}, or null if the reconciliation process fails
	 */
	@Nullable
	private ReplicaClusterState resolveInitialReplicaState(
		@Nonnull ReplicaClusterState initialReplicaState,
		@Nonnull ClusterEnvironment initialEnvironment
	) {
		log.info(
			"Starting cluster state reconciliation for replica {}. " +
				"Cluster size: {}, quorum required: {}, timeout (no progress): {} ms",
			initialReplicaState.replicaNumber(),
			initialEnvironment.clusterMembers().length,
			getQuorumSize(),
			RECONCILIATION_TIMEOUT.toMillis()
		);

		final AtomicReference<OffsetDateTime> lastProgressTimestamp = new AtomicReference<>(OffsetDateTime.now());
		final ReplicaClusterState reconciliationResult;
		try {
			// first we need to resolve replica status
			final CompletableFuture<ReplicaClusterState> replicaStateCompletableFuture = reconcileClusterState(
				initialEnvironment, initialReplicaState, getQuorumSize(),
				// onProgress
				() -> lastProgressTimestamp.set(OffsetDateTime.now())
			);
			final ReconciliationDeadlineTask cancellationTask = new ReconciliationDeadlineTask(
				this.evita.getServiceExecutor(),
				lastProgressTimestamp,
				replicaStateCompletableFuture,
				initialEnvironment.selfIndex()
			);
			// cleanup cancellation task
			try (cancellationTask) {
				cancellationTask.schedule();
				// we're setting up a separate task, that will cancel the reconciliation if no progress is made
				reconciliationResult = replicaStateCompletableFuture.join();
			}
		} catch (Exception e) {
			log.error(
				"Failed to reconcile cluster state during startup! " +
					"Cluster cannot start properly without successful reconciliation.",
				e
			);
			return null;
		}

		return reconciliationResult;
	}

	/**
	 * Reconciles the cluster state by coordinating with peers to gather recovery responses,
	 * identifying the leader, and ensuring that a quorum condition is met.
	 *
	 * This method sends recovery requests to other peers in the cluster, processes their responses,
	 * and determines if a consistent view of the cluster can be established. Once a valid quorum
	 * including a leader is achieved, a reconciliation result is returned. If no such condition
	 * is met, the method fails with an error.
	 *
	 * @param initialEnvironment  the initial environment configuration of the cluster
	 * @param initialReplicaState the initial state of the replica used for reconciliation
	 * @param quorumSize          the minimum number of responses required to achieve quorum
	 * @param onProgress          a callback invoked whenever progress is made during reconciliation
	 * @return a {@link CompletableFuture} that completes with the reconciliation result
	 * or fails if no valid quorum including a leader can be established
	 */
	@Nonnull
	private CompletableFuture<ReplicaClusterState> reconcileClusterState(
		@Nonnull ClusterEnvironment initialEnvironment,
		@Nonnull ReplicaClusterState initialReplicaState,
		int quorumSize,
		@Nonnull Runnable onProgress
	) {
		final CompletableFuture<ReplicaState> decision = new CompletableFuture<>();
		final InetAddress[] inetAddresses = initialEnvironment.clusterMembers();
		final InetAddress selfInetAddress = initialEnvironment.clusterMembers()[initialReplicaState.replicaNumber()];

		final int clusterSize = inetAddresses.length;
		final int expectedResponses = clusterSize - 1; // excluding self
		final ReconciliationResult reconciliationResult = new ReconciliationResult(expectedResponses);
		final List<CompletableFuture<RecoveryResponse>> futures = new ArrayList<>(expectedResponses);

		log.info(
			"Initiating reconciliation: replica {} sending recovery requests to {} peers. " +
				"Self address: {}",
			initialReplicaState.replicaNumber(),
			expectedResponses,
			selfInetAddress
		);

		for (int peer = 0; peer < clusterSize; peer++) {
			// send recovery request to each peer
			final UUID nonce = UUIDUtil.randomUUID();
			final int peerIndex = peer;
			final InetAddress targetAddress = inetAddresses[peerIndex];
			final CompletableFuture<RecoveryResponse> futureRecoveryResponse;
			if (peerIndex == initialReplicaState.replicaNumber()) {
				log.debug(
					"Using self-response for replica {}: epoch={}, viewNumber={}, status={}",
					initialReplicaState.replicaNumber(),
					initialReplicaState.epoch(),
					initialReplicaState.viewNumber(),
					initialReplicaState.status()
				);
				futureRecoveryResponse = CompletableFuture.completedFuture(
					RecoveryResponse.fromSelf(initialReplicaState, initialEnvironment)
				);
			} else {
				log.debug(
					"Sending recovery request to peer {} at {} with nonce {}",
					peerIndex,
					targetAddress,
					nonce
				);
				futureRecoveryResponse = this.viewStampedReplicationService
					.recovery(new RecoveryRequest(initialReplicaState.replicaNumber(), peerIndex, nonce))
					.thenApply(response -> checkNonceMatch(response, nonce, targetAddress))
					.toCompletableFuture();
			}

			futures.add(futureRecoveryResponse);

			// after each response arrives
			futureRecoveryResponse.whenComplete(
				(resp, err) -> {
					onProgress.run();
					// accept only successful responses until we have a decision
					if (err != null) {
						log.error("Recovery request failed for peer {} with error: {}", peerIndex, err.getMessage());
						return;
					} else if (resp == null) {
						log.error("Recovery request returned null response for peer {}!", peerIndex);
						return;
					} else if (decision.isDone()) {
						return;
					}

					log.debug(
						"Received recovery response from peer {}: epoch={}, viewNumber={}, " +
							"status={}, isPrimary={}, engineVersion={}, committedVersion={}",
						peerIndex,
						resp.epoch(),
						resp.viewNumber(),
						resp.viewState(),
						resp.isPrimary(),
						resp.engineVersion(),
						resp.committedEngineVersion()
					);

					// update reconciliation result
					final ReplicaState primaryState = reconciliationResult.updateIfHigher(
						resp.environment().selfIndex(selfInetAddress), quorumSize, clusterSize, resp
					);

					// if the decision is made (we have quorum including leader) -> complete the decision future
					if (primaryState != null) {
						log.info(
							"Reconciliation decision reached! Leader found at replica index {}. " +
								"Epoch={}, viewNumber={}",
							primaryState.replicaClusterState().replicaNumber(),
							primaryState.epoch(),
							primaryState.viewNumber()
						);
						decision.complete(primaryState);
					}
				}
			);
		}

		onProgress.run();

		// wait for either decision or allOf completion
		return decision
			.applyToEither(
				// allOf completed future that never meets the condition
				CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
					// If everybody finishes and condition never happened -> fail
					.thenCompose(
						v -> CompletableFuture.failedFuture(
							new GenericEvitaInternalError(
								"No view reached quorum including leader, this should not be possible!"))
					),
				vk -> {
					// we have a decision, proceed with updating local state
					onProgress.run();
					// update local state to match the primary's state
					return updateLocalState(
						// the state to update from
						initialReplicaState,
						// the target state to update to (primary's state)
						vk,
						// provide access to all completed future results in real-time - i.e. states of individual replicas
						() -> futures
							.stream()
							.filter(CompletableFuture::isDone)
							.map(CompletableFuture::join)
							// except primary and self
							.filter(
								it -> it.selfIndex() != initialReplicaState.replicaNumber() &&
									it.selfIndex() != vk.replicaNumber()
							)
							.toList(),
						// track progress
						onProgress
					);
				}
			)
			.thenCompose(Function.identity());
	}

	/**
	 * Reconciles the local replica's engine and catalog state against the primary, completing the startup
	 * recovery handshake.
	 *
	 * This method is called after a quorum decision has been reached during cluster reconciliation. It is
	 * expected to apply any missing WAL records or transfer snapshots as needed so that the local state
	 * matches the primary, and then return the resulting {@link ReplicaClusterState} in `NORMAL` view
	 * state. The recovery responses from other peers (excluding the primary and self) are available via
	 * `recoveryResponseSupplier` to assist with state selection when the primary alone cannot satisfy the
	 * required data.
	 *
	 * @param initialReplicaState the local replica's initial state at the start of reconciliation
	 * @param primaryReplicaState the quorum-selected primary replica's current state
	 * @param recoveryResponseSupplier provides the recovery responses already collected from other peers
	 *                                 (excluding primary and self) at the time of invocation
	 * @param onProgress              callback invoked whenever meaningful progress is made; resets the
	 *                                reconciliation deadline timer
	 * @return a `CompletableFuture` that completes with the updated local {@link ReplicaClusterState}
	 *         after the replica has caught up with the primary
	 */
	@Nonnull
	private CompletableFuture<ReplicaClusterState> updateLocalState(
		@Nonnull ReplicaClusterState initialReplicaState,
		@Nonnull ReplicaState primaryReplicaState,
		@Nonnull Supplier<List<RecoveryResponse>> recoveryResponseSupplier,
		@Nonnull Runnable onProgress
	) {
		return CompletableFuture.failedFuture(new UnsupportedOperationException("Not implemented yet!"));
	}

	/**
	 * Updates the local engine state to match the primary replica, using the most efficient strategy available.
	 *
	 * Two strategies are attempted in order:
	 *
	 * 1. **WAL catch-up (incremental):** when the local replica already has an engine WAL reference, missing WAL
	 *    entries are fetched from the primary in pages of up to {@link #WAL_SYNC_PAGE_SIZE} entries per RPC call.
	 *    Each page is applied sequentially via `applyWAL` and `onProgress` is invoked between pages. The returned
	 *    `CompletionStage` completes only after all pages have been fetched and applied in sequence.
	 *
	 * 2. **Snapshot fallback:** triggered in two situations — when the local replica has no WAL reference at all
	 *    (cold bootstrap), or when a {@link io.evitadb.api.exception.TemporalDataNotAvailableException} is thrown
	 *    during WAL catch-up because the requested entries have already been rotated out of the primary's WAL.
	 *    In this case the full engine state is retrieved as a single snapshot via `getEngineSnapshot`.
	 *
	 * @param initialReplicaState identity and configuration of the local replica
	 * @param primaryReplicaState current state of the primary, used as the synchronization target
	 * @param onProgress          callback invoked after each WAL page is applied; resets the reconciliation
	 *                            deadline timer so long catch-ups are not prematurely cancelled
	 * @return a `CompletionStage` that completes with the fully synchronized local {@link EngineState}
	 */
	@Nonnull
	private CompletionStage<EngineState<LogRecordReference>> updateEngineState(
		@Nonnull ReplicaClusterState initialReplicaState,
		@Nonnull ReplicaState primaryReplicaState,
		@Nonnull Runnable onProgress
	) {
		onProgress.run();
		if (this.evita.getExpandedEngineState().engineState().walReference() == null) {
			// bootstrap from primary, we have no local engine state history
			return this.viewStampedReplicationService.getEngineSnapshot(
				new GetEngineSnapshotRequest(
					initialReplicaState.replicaNumber(),
					primaryReplicaState.replicaNumber(),
					primaryReplicaState.epoch(),
					primaryReplicaState.viewNumber()
				)
			).thenApply(GetEngineSnapshotResponse::engineState);
		} else {
			// try to fetch missing WAL records from primary
			try {
				CompletionStage<EngineState<LogRecordReference>> localEngineState = CompletableFuture.completedFuture(
					this.evita.getExpandedEngineState().engineState()
				);
				long replicaEngineVersion = this.evita.getExpandedEngineState().engineState().version();
				do {
					final GetEngineStateResponse engineStateResponse = this.viewStampedReplicationService.getEngineState(
						new GetEngineStateRequest(
							initialReplicaState.replicaNumber(),
							primaryReplicaState.replicaNumber(),
							0L, // TODO JNO - determine from local WAL
							primaryReplicaState.epoch(),
							primaryReplicaState.viewNumber(),
							replicaEngineVersion + 1,
							Math.min(
								WAL_SYNC_PAGE_SIZE,
								Math.toIntExact(primaryReplicaState.engineVersion() - replicaEngineVersion)
							)
						)
					).toCompletableFuture().join();

					// apply received WAL records
					onProgress.run();
					replicaEngineVersion += engineStateResponse.versionCount();
					// chain the application of WAL records sequentially
					localEngineState = localEngineState
						.thenApply(
							__ -> this.evita.applyWAL(
								engineStateResponse.writeAheadLog(),
								engineStateResponse.crc32()
							)
					).thenCompose(Function.identity());
				} while (replicaEngineVersion < primaryReplicaState.engineVersion());

				// return the fully updated local engine state
				return localEngineState;

			} catch (TemporalDataNotAvailableException ex) {
				// fall back to getting snapshot without WAL if WAL has already rotated away
				onProgress.run();
				return this.viewStampedReplicationService.getEngineSnapshot(
					new GetEngineSnapshotRequest(
						initialReplicaState.replicaNumber(),
						primaryReplicaState.replicaNumber(),
						primaryReplicaState.epoch(),
						primaryReplicaState.viewNumber()
					)
				).thenApply(GetEngineSnapshotResponse::engineState);
			}
		}
	}

	/**
	 * Retrieves the size of the quorum required for achieving consensus in the cluster.
	 *
	 * The quorum size is calculated based on the current replica configuration and represents the
	 * minimum number of replicas that must acknowledge an operation for it to be considered
	 * committed. This ensures that a majority of replicas are involved in consensus.
	 *
	 * @return the minimum number of replicas needed for a quorum
	 */
	private int getQuorumSize() {
		return this.replicaState.get().getQuorum();
	}

	/**
	 * Task that monitors the progress of the cluster reconciliation and cancels it if the deadline is reached.
	 */
	private static class ReconciliationDeadlineTask extends ScheduledTask {

		/**
		 * Creates a new instance of the task.
		 *
		 * @param scheduler                     the scheduler to use for task execution
		 * @param lastProgressTimestamp         the timestamp of the last progress made
		 * @param replicaStateCompletableFuture the future to cancel if the deadline is reached
		 * @param selfIndex                     the index of the current replica
		 */
		private ReconciliationDeadlineTask(
			@Nonnull Scheduler scheduler,
			@Nonnull AtomicReference<OffsetDateTime> lastProgressTimestamp,
			@Nonnull CompletableFuture<ReplicaClusterState> replicaStateCompletableFuture,
			int selfIndex
		) {
			super(
				null,
				"replicaInitializationCancellation",
				scheduler,
				() -> {
					final OffsetDateTime lastProgress = lastProgressTimestamp.get();
					final OffsetDateTime now = OffsetDateTime.now();
					final OffsetDateTime deadline = lastProgress.plus(RECONCILIATION_TIMEOUT);
					if (deadline.isBefore(now)) {
						log.error(
							"Cluster reconciliation timed out after {} ms for replica {}! " +
								"Cluster cannot start properly without successful reconciliation.",
							RECONCILIATION_TIMEOUT.toMillis(),
							selfIndex
						);
						replicaStateCompletableFuture.cancel(true);
					}
					return Math.max(0L, RECONCILIATION_TIMEOUT.minus(Duration.between(now, deadline)).toMillis());
				},
				RECONCILIATION_TIMEOUT.toMillis(),
				TimeUnit.MILLISECONDS
			);
		}
	}

}
