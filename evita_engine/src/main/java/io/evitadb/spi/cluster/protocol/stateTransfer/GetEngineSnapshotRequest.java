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

package io.evitadb.spi.cluster.protocol.stateTransfer;

import io.evitadb.spi.cluster.protocol.HashChainedClusterRequestMessage;
import io.evitadb.utils.Crc32Calculator;

import javax.annotation.Nonnull;

/**
 * Request to retrieve a complete engine state snapshot.
 *
 * This message is used to obtain the current state of the entire evitaDB engine, including all
 * active and inactive catalogs, rather than transferring data for individual catalogs.
 *
 * **Engine State Information:**
 *
 * The engine state snapshot contains:
 *
 * - List of all catalogs (both active and inactive)
 * - Catalog metadata and version information
 * - Engine-level configuration and state
 * - No actual catalog data (entities, schemas, etc.)
 *
 * **When to Use:**
 *
 * Use engine snapshot request when:
 * - A new replica needs to discover available catalogs
 * - Synchronizing engine-level state across cluster nodes
 * - Verifying consistency of catalog inventory
 * - Initial cluster join or recovery scenarios
 *
 * @param selfIndex requesting replica's index in the cluster configuration
 * @param targetReplicaIndex target replica's index (typically the primary)
 * @param crc32 cumulative hash from preceding messages in the hash chain
 * @param epoch current configuration epoch (VSR Revisited extension)
 * @param viewNumber current view number for consistency verification
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see GetEngineSnapshotResponse
 * @see GetCatalogStateRequest
 */
public record GetEngineSnapshotRequest(
	int selfIndex,
	int targetReplicaIndex,
	long crc32,
	long epoch,
	long viewNumber
) implements HashChainedClusterRequestMessage {

	public GetEngineSnapshotRequest(int selfIndex, int targetReplicaIndex, long epoch, long viewNumber) {
		// there is no previous history - hence crc32 is zero
		this(selfIndex, targetReplicaIndex, 0L, epoch, viewNumber);
	}

	@Override
	public long calculateHash(@Nonnull Crc32Calculator crc32Calculator) {
		return crc32Calculator
			.withLong(this.epoch)
			.withLong(this.viewNumber)
			.getValue();
	}

}
