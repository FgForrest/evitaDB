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

import io.evitadb.spi.cluster.protocol.HashChainedClusterResponseMessage;

/**
 * Response acknowledging the new view and transitioning to NORMAL state.
 *
 * This response confirms that the backup has accepted the new primary and new view.
 * After sending this response, the backup transitions to NORMAL state and is ready
 * to participate in normal operation with the new primary.
 *
 * @param selfIndex responding backup replica's index
 * @param crc32 cumulative hash echoed back
 * @param epoch current configuration epoch
 * @param viewNumber the accepted view number
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see StartViewRequest
 */
public record StartViewResponse(
	int selfIndex,
	long crc32,
	int epoch,
	long viewNumber
) implements HashChainedClusterResponseMessage {
}
