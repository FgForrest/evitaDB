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
 * COMMITOK response acknowledging catalog state commit completion.
 *
 * This response is sent by backup replicas after executing committed catalog operations.
 *
 * @param selfIndex responding backup replica's index
 * @param crc32 cumulative hash echoed back for verification
 * @param epoch current configuration epoch
 * @param viewNumber current view number
 * @param catalogVersion the committed catalog operation number
 * @param catalogPrimaryKey identifier of the catalog that was committed
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see CommitCatalogVersionRequest
 */
public record CommitCatalogVersionResponse(
	int selfIndex,
	long crc32,
	long epoch,
	long viewNumber,
	long catalogVersion,
	int catalogPrimaryKey
) implements HashChainedClusterResponseMessage {

}
