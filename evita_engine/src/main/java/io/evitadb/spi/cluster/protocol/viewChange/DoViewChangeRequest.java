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

package io.evitadb.spi.cluster.protocol.viewChange;

import io.evitadb.spi.cluster.protocol.CatalogVersions;
import io.evitadb.spi.cluster.protocol.HashChainedClusterRequestMessage;
import io.evitadb.spi.cluster.protocol.Versions;
import io.evitadb.utils.Crc32Calculator;

import javax.annotation.Nonnull;

/**
 * DOVIEWCHANGE message sent to the new primary candidate during view change.
 *
 * This message is sent by replicas to the new primary candidate (determined by
 * `viewNumber % clusterSize`) after receiving sufficient STARTVIEWCHANGE messages.
 * It carries the replica's state information to help the new primary determine the
 * most up-to-date state.
 *
 * **State Information:**
 *
 * The message includes both `lastObserved` (op-number) and `lastCommitted` (commit-number)
 * for engine state and each catalog. The new primary uses `lastNormalViewNumber` to
 * select which replica's state to use - preferring replicas that were in normal state
 * more recently.
 *
 * **Primary Selection Logic:**
 *
 * The new primary candidate selects the log from the replica with:
 *
 * 1. Highest `lastNormalViewNumber` (was in normal state most recently)
 * 2. Among ties, highest `lastObserved` (has most operations)
 *
 * @param selfIndex sender replica's index
 * @param targetReplicaIndex new primary candidate's index
 * @param crc32 cumulative hash for verification
 * @param epoch current configuration epoch
 * @param viewNumber the new view number
 * @param lastNormalViewNumber the view where this replica was last in NORMAL state
 * @param engineVersion engine state versions (op-number and commit-number)
 * @param catalogVersions array of catalog versions (catalog id and version pairs)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see DoViewChangeResponse
 * @see StartViewRequest
 */
public record DoViewChangeRequest(
	int selfIndex,
	int targetReplicaIndex,
	long crc32,
	int epoch,
	long viewNumber,
	long lastNormalViewNumber,
	@Nonnull Versions engineVersion,
	@Nonnull CatalogVersions[] catalogVersions
) implements HashChainedClusterRequestMessage {

	@Override
	public long calculateHash(@Nonnull Crc32Calculator crc32Calculator) {
		crc32Calculator
			.withInt(this.epoch)
			.withLong(this.viewNumber)
			.withLong(this.lastNormalViewNumber)
			.withLong(this.engineVersion.lastObserved())
			.withLong(this.engineVersion.lastCommitted());
		for (final CatalogVersions entry : this.catalogVersions) {
			crc32Calculator
				.withInt(entry.catalogId())
				.withLong(entry.versions().lastObserved())
				.withLong(entry.versions().lastCommitted());
		}
		return crc32Calculator.getValue();
	}

}
