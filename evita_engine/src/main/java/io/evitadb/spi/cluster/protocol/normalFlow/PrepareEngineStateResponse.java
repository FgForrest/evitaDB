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

package io.evitadb.spi.cluster.protocol.normalFlow;

import io.evitadb.spi.cluster.protocol.HashChainedClusterResponseMessage;

/**
 * PREPAREOK response for engine state changes in the VSR two-phase commit protocol.
 *
 * This response is sent by backup replicas to acknowledge receipt and logging of an engine
 * state operation. The primary waits for a quorum of these responses before proceeding to
 * the commit phase.
 *
 * **Acknowledgment Semantics:**
 *
 * By sending this response, the backup confirms:
 *
 * 1. The operation has been durably logged to local storage
 * 2. The hash chain verification passed (message integrity confirmed)
 * 3. The backup is ready to commit this operation when instructed
 *
 * @param selfIndex responding backup replica's index
 * @param crc32 cumulative hash echoed back for verification
 * @param epoch current configuration epoch
 * @param viewNumber current view number
 * @param engineVersion the prepared operation number being acknowledged
 * @param committedEngineVersion the backup's current commit-number
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see PrepareEngineStateRequest
 */
public record PrepareEngineStateResponse(
	int selfIndex,
	long crc32,
	long epoch,
	long viewNumber,
	long engineVersion,
	long committedEngineVersion
) implements HashChainedClusterResponseMessage {

}
