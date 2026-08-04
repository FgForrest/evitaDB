/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.statistics;

/**
 * The {@link CatalogStatisticsComponent#COMMIT_PIPELINE} component - the four version watermarks the commit pipeline
 * maintains, and the deltas between them.
 *
 * A transaction moves through the pipeline in stages, each stamping its own watermark: a version is *assigned* at
 * conflict resolution, *written* once it is in the WAL, *durable* once it has been forced to disk, and *finalized*
 * once it is visible to readers. The watermarks are always read in that order, so each delta answers a specific
 * operational question:
 *
 * - `writeLag` - work accepted but not yet in the WAL
 * - `durabilityLag` - how much replay a crash would cost right now
 * - `visibilityLag` - how far behind readers are from what has been committed
 *
 * All four are plain counter reads; this component costs nothing.
 *
 * **Reading for a degraded catalog**
 *
 * Not delivered for an unusable catalog. A catalog in `WARMING_UP` has no transactional pipeline either, and reports
 * {@link ComponentAvailability#FEATURE_DISABLED}.
 *
 * @param lastAssignedCatalogVersion  newest version handed out by conflict resolution
 * @param lastWrittenCatalogVersion   newest version appended to the write-ahead log
 * @param lastDurableCatalogVersion   newest version forced to durable storage
 * @param lastFinalizedCatalogVersion newest version visible to readers
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CommitPipelineStatistics(
	long lastAssignedCatalogVersion,
	long lastWrittenCatalogVersion,
	long lastDurableCatalogVersion,
	long lastFinalizedCatalogVersion
) {

	/**
	 * Versions accepted by conflict resolution that have not reached the write-ahead log yet.
	 *
	 * @return number of versions between the assigned and the written watermark
	 */
	public long writeLag() {
		return this.lastAssignedCatalogVersion - this.lastWrittenCatalogVersion;
	}

	/**
	 * Versions in the write-ahead log that have not been forced to durable storage yet - the amount of replay a crash
	 * would cost at this moment.
	 *
	 * @return number of versions between the written and the durable watermark
	 */
	public long durabilityLag() {
		return this.lastWrittenCatalogVersion - this.lastDurableCatalogVersion;
	}

	/**
	 * Versions committed but not yet visible to readers.
	 *
	 * @return number of versions between the written and the finalized watermark
	 */
	public long visibilityLag() {
		return this.lastWrittenCatalogVersion - this.lastFinalizedCatalogVersion;
	}

}
