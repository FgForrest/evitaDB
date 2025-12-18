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

package io.evitadb.spi.cluster.protocol.recovery;

import io.evitadb.spi.cluster.protocol.ClusterRequestMessage;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * RECOVERY request message sent by a replica rejoining the cluster.
 *
 * This message is sent when a replica starts up after being offline or partitioned.
 * The recovering replica uses this to learn the current cluster state and determine
 * how far behind it is.
 *
 * **Recovery Protocol:**
 *
 * 1. Recovering replica sends RECOVERY to the primary (or any replica if primary unknown)
 * 2. Responder returns current view, epoch, and version information
 * 3. Recovering replica compares with its local state
 * 4. If behind, initiates state transfer to catch up
 * 5. Once synchronized, transitions to NORMAL state
 *
 * **Nonce Usage:**
 *
 * The `nonce` is a unique identifier for this recovery request. The response echoes
 * back the nonce to ensure the response corresponds to this specific request, preventing
 * confusion from stale or duplicate responses.
 *
 * @param selfIndex recovering replica's index
 * @param targetReplicaIndex target replica to request recovery from
 * @param nonce unique identifier for request-response correlation
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see RecoveryResponse
 */
public record RecoveryRequest(
	int selfIndex,
	int targetReplicaIndex,
	@Nonnull UUID nonce
) implements ClusterRequestMessage {
}
