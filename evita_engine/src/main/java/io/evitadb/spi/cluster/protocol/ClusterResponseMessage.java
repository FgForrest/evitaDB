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

package io.evitadb.spi.cluster.protocol;

/**
 * Interface for response messages sent in reply to {@link ClusterRequestMessage} in the VSR protocol.
 *
 * Response messages are sent back to the original requester and carry:
 *
 * - The responder's identity via {@link #selfIndex()}
 * - Acknowledgment or result data specific to the request type
 * - For hash-chained messages, the cumulative CRC32 hash for verification
 *
 * Unlike request messages, responses do not specify a target replica index because they are
 * always sent back to the replica that initiated the request.
 *
 * **Common Response Patterns:**
 *
 * - **PrepareOK responses**: Backups acknowledge receipt and logging of operations
 * - **View change responses**: Replicas confirm participation in view change protocol
 * - **State transfer responses**: Return requested WAL entries or snapshot data
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see ClusterRequestMessage
 * @see HashChainedClusterResponseMessage
 */
public interface ClusterResponseMessage extends ClusterMessage {
}
