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

import io.evitadb.utils.Crc32Calculator;

import javax.annotation.Nonnull;

/**
 * Interface for request messages that participate in the cumulative hash chain verification.
 *
 * This interface combines the request message semantics ({@link ClusterRequestMessage}) with
 * hash chain verification ({@link HashChained}). Messages implementing this interface:
 *
 * - Are sent from one replica to another (have sender and target)
 * - Carry a cumulative CRC32C hash for integrity verification
 * - Can compute their contribution to the hash chain
 *
 * **Hash Chain Protocol:**
 *
 * 1. Sender maintains a running CRC32C hash of all sent messages
 * 2. Before sending, sender computes new hash including current message payload
 * 3. Message is sent with the cumulative hash value
 * 4. Receiver verifies the hash matches its own computation
 * 5. On mismatch, receiver raises {@link io.evitadb.spi.cluster.exception.CorruptedHashChainException}
 *
 * Most VSR protocol messages implement this interface except for recovery requests (which start
 * a new sequence) and some reconfiguration messages.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see HashChainedClusterResponseMessage
 * @see io.evitadb.utils.Crc32Calculator
 */
public interface HashChainedClusterRequestMessage extends ClusterRequestMessage, HashChained {

	/**
	 * Calculates the cumulative hash value including this message's payload.
	 *
	 * The calculator should already contain the hash of all previous messages in the chain.
	 * This method adds the current message's fields to the hash computation and returns
	 * the resulting cumulative hash value.
	 *
	 * **Implementation Note:**
	 *
	 * Implementations should hash all message fields except {@link #crc32()} itself, as that
	 * field carries the hash from the sender's perspective. The order of field hashing must
	 * be consistent across all replicas.
	 *
	 * @param crc32Calculator the CRC32C calculator pre-initialized with the previous cumulative hash
	 * @return the updated CRC32C hash value including this message's payload
	 */
	long calculateHash(@Nonnull Crc32Calculator crc32Calculator);

}