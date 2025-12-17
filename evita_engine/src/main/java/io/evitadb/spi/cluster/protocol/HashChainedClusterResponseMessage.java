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
 * Interface for response messages that participate in the cumulative hash chain verification.
 *
 * This interface combines response message semantics ({@link ClusterResponseMessage}) with
 * hash chain verification ({@link HashChained}). Response messages implementing this interface:
 *
 * - Are sent in reply to {@link HashChainedClusterRequestMessage}
 * - Echo back the cumulative CRC32C hash to confirm receipt of the correct request
 * - Allow the original sender to verify the responder processed the expected message
 *
 * **Verification Flow:**
 *
 * 1. Sender sends request with cumulative hash H(n)
 * 2. Receiver verifies H(n) matches expected value
 * 3. Receiver sends response with same or updated hash
 * 4. Sender verifies response hash matches expectations
 *
 * Unlike request messages, response messages do not need a `calculateHash` method because
 * they typically echo back the hash from the request rather than extending the chain.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see HashChainedClusterRequestMessage
 */
public interface HashChainedClusterResponseMessage extends ClusterResponseMessage, HashChained {
}
