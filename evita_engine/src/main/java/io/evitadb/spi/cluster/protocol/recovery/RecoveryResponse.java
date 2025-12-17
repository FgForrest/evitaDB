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

import io.evitadb.spi.cluster.protocol.CatalogVersions;
import io.evitadb.spi.cluster.protocol.ClusterResponseMessage;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * RECOVERY response providing current cluster state information.
 *
 * This response provides the recovering replica with the current cluster state,
 * allowing it to determine how to synchronize. The response includes version information
 * for both engine state and all catalogs.
 *
 * **State Information:**
 *
 * The response provides both observed (op-number) and committed (commit-number) versions:
 *
 * - `engineVersion` / `committedEngineVersion`: Engine state version pair
 * - `catalogVersions`: Array of {@link io.evitadb.spi.cluster.protocol.CatalogVersions}
 *   containing both observed and committed versions for each catalog
 *
 * **Recovery Decision:**
 *
 * Based on this response, the recovering replica can:
 *
 * 1. Determine if it needs state transfer (local version < cluster version)
 * 2. Request incremental WAL transfer if only slightly behind
 * 3. Request full snapshot transfer if significantly behind
 * 4. Join immediately if already synchronized
 *
 * @param selfIndex responding replica's index
 * @param epoch current configuration epoch
 * @param viewNumber current view number
 * @param nonce echoed nonce from the request for correlation
 * @param engineVersion current engine op-number (observed version)
 * @param committedEngineVersion current engine commit-number
 * @param catalogVersions array of catalog versions (catalog id and version pairs)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see RecoveryRequest
 */
public record RecoveryResponse(
	int selfIndex,
	int epoch,
	long viewNumber,
	@Nonnull UUID nonce,
	long engineVersion,
	long committedEngineVersion,
	@Nonnull CatalogVersions[] catalogVersions
) implements ClusterResponseMessage {
}
