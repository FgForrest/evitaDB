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

package io.evitadb.spi.cluster.protocol.reconfiguration;

import io.evitadb.spi.cluster.protocol.HashChainedClusterRequestMessage;
import io.evitadb.utils.Crc32Calculator;

import javax.annotation.Nonnull;
import java.net.InetAddress;

/**
 * Request to start a new configuration epoch (VSR Revisited reconfiguration).
 *
 * This message is sent by the reconfiguration leader to transition replicas
 * from the old configuration to the new configuration. It is sent after
 * receiving acknowledgments from a quorum of the old configuration.
 *
 * **Transition Protocol:**
 *
 * The STARTEPOCH message performs the following:
 *
 * 1. Informs replicas of both old and new cluster membership
 * 2. Provides the engine version at which the transition occurs
 * 3. Triggers replicas to update their configuration
 *
 * **Membership Information:**
 *
 * Both old and new cluster members are included to:
 *
 * - Allow new members to discover their index in the new configuration
 * - Allow old members being removed to gracefully shut down
 * - Enable overlap verification for safety
 *
 * **State Requirements:**
 *
 * Before accepting this message, replicas should verify:
 *
 * - Their engine version is at least `engineVersion`
 * - If behind, they must catch up via state transfer before accepting
 *
 * @param selfIndex requesting replica's index (reconfiguration leader)
 * @param targetReplicaIndex target replica's index
 * @param crc32 cumulative hash from preceding messages in the hash chain
 * @param epoch the new epoch number being started
 * @param engineVersion engine op-number at which the epoch transition occurs
 * @param oldClusterMembers network addresses of all members in the old configuration
 * @param newClusterMembers network addresses of all members in the new configuration
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see StartEpochResponse
 * @see EpochStartedRequest
 */
public record StartEpochRequest(
	int selfIndex,
	int targetReplicaIndex,
	long crc32,
	long epoch,
	long engineVersion,
	@Nonnull InetAddress[] oldClusterMembers,
	@Nonnull InetAddress[] newClusterMembers
) implements HashChainedClusterRequestMessage {

	@Override
	public long calculateHash(@Nonnull Crc32Calculator crc32Calculator) {
		crc32Calculator
			.withLong(this.epoch)
			.withLong(this.engineVersion);
		for (final InetAddress address : this.oldClusterMembers) {
			crc32Calculator.withString(address.getHostAddress());
		}
		for (final InetAddress address : this.newClusterMembers) {
			crc32Calculator.withString(address.getHostAddress());
		}
		return crc32Calculator.getValue();
	}

}
