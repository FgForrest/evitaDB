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

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import io.evitadb.spi.cluster.model.ReplicaState;
import io.evitadb.spi.cluster.model.ViewState;
import io.evitadb.spi.cluster.protocol.recovery.RecoveryResponse;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Iterator;

/**
 * Aggregates responses collected during cluster reconciliation.
 *
 * <p>
 * In Viewstamped Replication (VR) terminology this helper is used while the node is trying to
 * {@code reconcile} (i.e. learn) the latest <em>view</em> and the corresponding leader state from a
 * quorum of replicas.
 * </p>
 *
 * <h2>VR mapping</h2>
 * <ul>
 *   <li><b>View number</b> ({@link #getViewNumber()}): the monotonically increasing identifier of the
 *   current leader configuration (VR "view").</li>
 *   <li><b>Epoch</b> ({@link #getEpoch()}): an additional monotonic component used by evitaDB to
 *   partition histories (e.g. after reconfiguration). Together with {@code viewNumber} it forms an
 *   ordering equivalent to VR's "newer view wins" rule. Conceptually this is a <em>term/view epoch</em>
 *   used to avoid confusion between independent histories.</li>
 *   <li><b>Quorum</b> ({@code quorumSize}): the minimum number of replicas that must respond before we
 *   act on the result. This mirrors VR requirements for view-change / commit decisions where progress
 *   requires a majority.</li>
 *   <li><b>Primary state</b> ({@link PrimaryState}): the authoritative state for the highest
 *   {@code (epoch, viewNumber)} observed in the quorum. In VR this plays the role of the leader's
 *   view-change payload (e.g. information needed to safely continue in the new view).</li>
 * </ul>
 *
 * <h2>What this class guarantees</h2>
 * <ul>
 *   <li>Tracks how many responses have been observed ({@link #getResponseCount(ViewState)}).</li>
 *   <li>Keeps the highest {@code (epoch, viewNumber)} seen so far using lexicographic ordering.</li>
 *   <li>Returns a non-null {@link PrimaryState} only once a quorum has responded <em>and</em> the leader
 *   state for the highest view has been captured.</li>
 * </ul>
 *
 * <p>
 * The method {@link #updateIfHigher(int, int, int, RecoveryResponse)} is synchronized because this
 * aggregator is typically updated from multiple response-handling threads.
 * </p>
 *
 * <p><b>Important:</b> This class does not validate that responses come from distinct replicas.
 * That is expected to be enforced by the caller as part of the reconciliation/view-change flow.
 * </p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
class ReconciliationResult implements Serializable {
	@Serial private static final long serialVersionUID = 7040041644057121711L;
	/**
	 * Map of replica indexes to their reported view states.
	 */
	private final IntObjectMap<ViewState> viewStateInResponses;
	/**
	 * Map of replica indexes to their reported primary states.
	 */
	private final IntObjectMap<RecoveryResponse> recoveryResponses;
	/**
	 * Set of replica indexes that have responded.
	 */
	private final IntSet responses;
	/**
	 * Highest epoch observed so far (part of the total order of "newness").
	 */
	@Getter private long epoch = -1L;
	/**
	 * Highest view number observed so far for the highest {@link #epoch}.
	 */
	@Getter private long viewNumber = -1L;
	/**
	 * Leader state for the highest observed (epoch, viewNumber), if any responder provided it.
	 */
	@Getter @Nullable private PrimaryState primaryState;

	public ReconciliationResult(int expectedResponses) {
		this.responses = new IntHashSet(expectedResponses);
		this.viewStateInResponses = new IntObjectHashMap<>(expectedResponses);
		this.recoveryResponses = new IntObjectHashMap<>(expectedResponses);
	}

	/**
	 * Incorporates a single replica response.
	 *
	 * <p>
	 * The response is represented by its {@code candidateEpoch} and {@code candidateViewNumber}.
	 * If this pair is higher than what we have seen so far, the method updates the stored
	 * "highest view". If the caller provides a {@link ReplicaState}, it must correspond to the same
	 * {@code (epoch, viewNumber)}.
	 * </p>
	 *
	 * <p>
	 * The method returns a {@link ReplicaState} only when:
	 * </p>
	 * <ol>
	 *   <li>At least {@code quorumSize} responses have been processed (VR quorum condition), and</li>
	 *   <li>A non-null leader state has been recorded for the highest view.</li>
	 * </ol>
	 *
	 * <p>
	 * This corresponds to the point in a VR-style reconciliation / view-change where a node has
	 * collected enough information from a majority to safely adopt the most recent view and proceed.
	 * </p>
	 *
	 * @param currentReplicaIndex index of the current replica
	 * @param quorumSize          number of responses required to make progress
	 * @param clusterSize         number of nodes in the cluster
	 * @param response            the recovery response from a replica
	 * @return leader state for the highest observed view once quorum is met; otherwise {@code null}
	 */
	@Nullable
	public synchronized PrimaryState updateIfHigher(
		int currentReplicaIndex,
		int quorumSize,
		int clusterSize,
		@Nonnull RecoveryResponse response
	) {
		final long candidateEpoch = response.epoch();
		final long candidateViewNumber = response.viewNumber();
		final int replicaIndex = response.selfIndex();
		final ViewState viewState = response.viewState();

		// VR rule: prefer the most recent view. evitaDB extends the ordering with an epoch component.
		if (candidateEpoch > this.epoch ||
			(candidateEpoch == this.epoch && candidateViewNumber > this.viewNumber)
		) {
			log.info(
				"View advanced: viewNumber {} -> {} (epoch: {} -> {}). " +
					"Clearing {} previous responses from older view.",
				this.viewNumber, candidateViewNumber,
				this.epoch, candidateEpoch,
				this.responses.size()
			);
			// Clear previous responses as they belong to an older view.
			this.responses.clear();
			this.primaryState = null;
			// Update to the new highest view.
			this.epoch = candidateEpoch;
			this.viewNumber = candidateViewNumber;
		}

		// Keep the recovery response for potential later use.
		this.recoveryResponses.put(replicaIndex, response);
		// If it matches the highest view, and is primary state, store it
		if (
			this.epoch == candidateEpoch &&
				this.viewNumber == candidateViewNumber &&
				response.isPrimary() &&
				response.viewState() == ViewState.NORMAL
		) {
			Assert.isPremiseValid(
				this.primaryState == null ||
					candidateEpoch == this.primaryState.epoch() &&
						candidateViewNumber == this.primaryState.viewNumber(),
				"ClusterMemberState must match the ViewKey!"
			);
			// Defensive: for the highest view we expect at most one authoritative leader state payload.
			Assert.isPremiseValid(
				this.primaryState == null,
				"ClusterMemberState for the highest view is already set!"
			);
			// revalidate primary claim
			Assert.isPremiseValid(
				(int) (candidateViewNumber % clusterSize) == replicaIndex,
				"Replica claiming to be primary is not the primary for the view!"
			);

			this.primaryState = new PrimaryState(currentReplicaIndex, ViewState.RECOVERING, response);
			log.info(
				"Primary replica identified at index {}. " +
					"Epoch={}, viewNumber={}, engineVersion={}, committedVersion={}",
				replicaIndex,
				candidateEpoch,
				candidateViewNumber,
				response.engineVersion(),
				response.committedEngineVersion()
			);
		}

		// One more replica responded in this reconciliation round.
		if (candidateEpoch == this.epoch && candidateViewNumber == this.viewNumber) {
			this.responses.add(replicaIndex);
			log.debug(
				"Response from replica {} added to quorum count. " +
					"Current responses: {}/{} required for quorum. Primary found: {}",
				replicaIndex,
				this.responses.size(),
				quorumSize,
				this.primaryState != null
			);
		}
		this.viewStateInResponses.put(replicaIndex, viewState);

		// Check if we have enough responses to proceed.
		if (this.responses.size() >= quorumSize && this.primaryState != null) {
			// VR-style quorum gate: we only "finish" reconciliation once a quorum responded AND we
			// actually have the payload needed to adopt the highest view.
			log.info(
				"Quorum reached with primary! Responses: {}/{}, epoch={}, viewNumber={}. " +
					"Primary at replica index {}.",
				this.responses.size(),
				quorumSize,
				this.epoch,
				this.viewNumber,
				this.primaryState.replicaState().replicaNumber()
			);
			return this.primaryState;
		} else if (getResponseCount(ViewState.RECOVERING) == clusterSize) {
			// Special case: if all replicas are recovering (power-loss scenario), we require
			// ALL nodes to respond before proceeding. This is a deliberate safety mechanism to:
			// 1. Prevent split-brain when network is partitioned
			// 2. Ensure cluster-wide visibility of all replica states
			// 3. Select the replica with highest (epoch, viewNumber) as authoritative
			// This is a stricter requirement than standard quorum for this exceptional case.
			log.warn(
				"All {} replicas are in RECOVERING state (power-loss scenario). " +
					"Requiring all nodes to respond for safety. " +
					"Selecting highest view response as authoritative.",
				clusterSize
			);
			return pickHighestViewResponse(currentReplicaIndex, clusterSize);
		} else {
			log.debug(
				"Quorum not yet reached. Responses: {}/{}, " +
					"recovering count: {}/{}, primary found: {}",
				this.responses.size(),
				quorumSize,
				getResponseCount(ViewState.RECOVERING),
				clusterSize,
				this.primaryState != null
			);
			return null;
		}
	}

	/**
	 * Picks the highest recovery response based on the epoch and view number among the available recovery responses.
	 * If multiple responses share the same epoch and view number, the method selects the response with the highest
	 * priority (preferring primary).
	 * Returns a {@link PrimaryState} object that corresponds to the determined highest recovery response.
	 *
	 * @param currentReplicaIndex the index of the current replica within the cluster
	 * @param clusterSize         the total number of replicas in the cluster
	 * @return a {@link PrimaryState} object representing the highest observed view response;
	 * or {@code null} if no responses are available
	 */
	@Nullable
	private PrimaryState pickHighestViewResponse(int currentReplicaIndex, int clusterSize) {
		final Iterator<IntObjectCursor<RecoveryResponse>> it = this.recoveryResponses.iterator();
		RecoveryResponse highestRecoveryResponse = null;
		while (it.hasNext()) {
			final RecoveryResponse theResponse = it.next().value;
			if (highestRecoveryResponse == null) {
				// select the first response as the initial highest
				highestRecoveryResponse = theResponse;
			} else if (
				theResponse.epoch() == highestRecoveryResponse.epoch() &&
					theResponse.viewNumber() == highestRecoveryResponse.viewNumber() &&
					theResponse.isPrimary()
			) {
				// revalidate primary claim
				Assert.isPremiseValid(
					(int) (theResponse.viewNumber() % clusterSize) == theResponse.selfIndex(),
					"Replica claiming to be primary is not the primary for the view!"
				);
				// prefer primary if epochs and view numbers are equal
				log.debug(
					"Preferring primary response from replica {} over non-primary. " +
						"Epoch={}, viewNumber={}",
					theResponse.selfIndex(),
					theResponse.epoch(),
					theResponse.viewNumber()
				);
				highestRecoveryResponse = theResponse;
			} else if (
				theResponse.epoch() > highestRecoveryResponse.epoch() ||
					(theResponse.epoch() == highestRecoveryResponse.epoch() &&
						theResponse.viewNumber() > highestRecoveryResponse.viewNumber())
			) {
				// select the response with the higher epoch or view number
				log.debug(
					"Selecting higher view response from replica {}. " +
						"Previous: epoch={}/view={}, new: epoch={}/view={}",
					theResponse.selfIndex(),
					highestRecoveryResponse.epoch(),
					highestRecoveryResponse.viewNumber(),
					theResponse.epoch(),
					theResponse.viewNumber()
				);
				highestRecoveryResponse = theResponse;
			}
		}
		// return the PrimaryState based on the highest recovery response found
		if (highestRecoveryResponse != null) {
			log.info(
				"Selected highest view response from replica {} as authoritative. " +
					"Final epoch={}, viewNumber={}, isPrimary={}",
				highestRecoveryResponse.selfIndex(),
				highestRecoveryResponse.epoch(),
				highestRecoveryResponse.viewNumber(),
				highestRecoveryResponse.isPrimary()
			);
			return new PrimaryState(currentReplicaIndex, ViewState.RECOVERING, highestRecoveryResponse);
		} else {
			log.warn("No recovery responses available to pick highest view from!");
			return null;
		}
	}

	/**
	 * Retrieves the total number of responses currently stored.
	 *
	 * @return the number of responses contained in the internal collection
	 */
	private int getResponseCount(@Nonnull ViewState viewState) {
		int count = 0;
		for (IntObjectCursor<ViewState> entry : this.viewStateInResponses) {
			if (entry.value == viewState) {
				count++;
			}
		}
		return count;
	}

}
