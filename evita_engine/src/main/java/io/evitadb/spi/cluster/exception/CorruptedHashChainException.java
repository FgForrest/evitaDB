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

package io.evitadb.spi.cluster.exception;

import io.evitadb.spi.cluster.protocol.HashChainedClusterRequestMessage;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when hash chain verification fails during message processing.
 *
 * This exception indicates that a received message's cumulative CRC32C hash does not match
 * the expected value computed by the receiver. This is a critical error that suggests one of:
 *
 * - **Message corruption**: The message was altered during transmission
 * - **Missing messages**: One or more messages in the sequence were lost
 * - **Ordering violation**: Messages arrived out of order
 * - **Byzantine behavior**: A replica is sending inconsistent data
 *
 * **Recovery Actions:**
 *
 * When this exception occurs, the receiving replica should:
 *
 * 1. Reject the message and not apply it to local state
 * 2. Potentially initiate view change if the sender was the primary
 * 3. Request state transfer to resynchronize with other replicas
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see io.evitadb.spi.cluster.protocol.HashChained
 */
public class CorruptedHashChainException extends ClusterException {
	@Serial private static final long serialVersionUID = 2413080518545491589L;

	public CorruptedHashChainException(@Nonnull HashChainedClusterRequestMessage message, long expectedCrc32) {
		super(
			"Corrupted hash chain detected! The message with CRC32 " + message.crc32() + " is corrupted - expected CRC32 " + expectedCrc32 + "!",
			"Corrupted hash chain detected!"
		);
	}

}
