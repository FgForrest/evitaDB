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

import java.io.Serializable;

/**
 * Interface for messages that participate in the cumulative hash chain verification mechanism.
 *
 * In the VSR protocol implementation for evitaDB, hash chaining provides an additional layer of
 * consistency verification. Each message in the ordered sequence carries a CRC32C hash that is
 * computed from:
 *
 * 1. The cumulative hash of all previous messages in the chain
 * 2. The payload of the current message
 *
 * This creates a cryptographic chain where any tampering, reordering, or message loss can be
 * detected by both the sender and receiver. If a replica receives a message with an unexpected
 * hash, it indicates either:
 *
 * - Message corruption during transmission
 * - Missing messages in the sequence
 * - Divergent state between replicas (Byzantine behavior)
 *
 * **Hash Chain Usage:**
 *
 * - Request messages compute the hash via {@link HashChainedClusterRequestMessage#calculateHash}
 * - Response messages echo back the hash to confirm receipt of the correct message
 * - Hash mismatches trigger {@link io.evitadb.spi.cluster.exception.CorruptedHashChainException}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see HashChainedClusterRequestMessage
 * @see HashChainedClusterResponseMessage
 */
public interface HashChained extends Serializable {

	/**
	 * Returns the cumulative CRC32C hash value for this message in the hash chain.
	 *
	 * This value represents the hash computed from all previous messages plus the current
	 * message payload. Recipients use this value to verify message integrity and ordering.
	 *
	 * @return the cumulative CRC32C hash value
	 */
	long crc32();

}
