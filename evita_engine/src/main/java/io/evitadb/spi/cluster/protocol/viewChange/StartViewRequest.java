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

package io.evitadb.spi.cluster.protocol.viewChange;

import io.evitadb.spi.cluster.protocol.HashChainedClusterRequestMessage;
import io.evitadb.utils.Crc32Calculator;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * STARTVIEW message announcing the new view to all replicas.
 *
 * This message is sent by the new primary after collecting sufficient DOVIEWCHANGE messages
 * and selecting the authoritative state. It announces the new view and provides the commit
 * positions that all replicas should synchronize to.
 *
 * **New Primary Actions:**
 *
 * Before sending this message, the new primary:
 *
 * 1. Selects the most up-to-date state from received DOVIEWCHANGE messages
 * 2. Adopts that state as its own (may need state transfer if behind)
 * 3. Prepares to re-propose any uncommitted operations from the selected log
 *
 * **Backup Actions:**
 *
 * Upon receiving this message, backups:
 *
 * 1. Accept the new primary for this view
 * 2. Synchronize their state if behind (via state transfer)
 * 3. Transition to NORMAL state
 *
 * @param selfIndex new primary replica's index
 * @param targetReplicaIndex target backup replica's index
 * @param crc32 cumulative hash for verification
 * @param epoch current configuration epoch
 * @param viewNumber the new view number now in effect
 * @param engineVersion the authoritative engine commit position
 * @param catalogVersions map of catalog primary key to authoritative commit positions
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see StartViewResponse
 */
public record StartViewRequest(
	int selfIndex,
	int targetReplicaIndex,
	long crc32,
	long epoch,
	long viewNumber,
	long engineVersion,
	@Nonnull Map<Integer, Long> catalogVersions
) implements HashChainedClusterRequestMessage {

	@Override
	public long calculateHash(@Nonnull Crc32Calculator crc32Calculator) {
		crc32Calculator
			.withInt(this.selfIndex)
			.withInt(this.targetReplicaIndex)
			.withLong(this.epoch)
			.withLong(this.viewNumber)
			.withLong(this.engineVersion);
		for (final Map.Entry<Integer, Long> entry : this.catalogVersions.entrySet()) {
			crc32Calculator
				.withInt(entry.getKey())
				.withLong(entry.getValue());
		}
		return crc32Calculator.getValue();
	}

}
