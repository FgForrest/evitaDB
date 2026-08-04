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

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Enumerates the independently selectable parts of {@link CatalogStatistics} and
 * {@link EntityCollectionStatistics}. A client names the components it wants, and the engine computes only those.
 *
 * **Why components and not detail levels**
 *
 * A nested `BASIC < STORAGE < FULL` ladder would force a once-and-for-all decision about which statistic belongs at
 * which level, and a client asking for one expensive number would have to pull everything cheaper along with it.
 * Component selection has neither problem, and adding a component later is purely additive.
 *
 * **Two levels, two calls**
 *
 * A component may exist at the catalog level, at the entity collection level, or at both - see
 * {@link #isCatalogLevel()} and {@link #isCollectionLevel()}. The catalog level reports **aggregates only** and never
 * carries a per-collection breakdown; anything about one collection is fetched by naming that collection. This keeps
 * the catalog response a fixed size no matter how many collections the catalog holds, which matters because the
 * catalog response is the one that gets polled.
 *
 * The line between the levels is drawn by cost, not by tidiness: a catalog-level aggregate may only be assembled from
 * per-collection reads that are *cheap enough to do for every collection on every request*. Where that does not hold,
 * the component has no catalog-level form at all - {@link #INDEX_CARDINALITY} and {@link #MEMORY_FOOTPRINT} are
 * collection-level only for exactly this reason, and {@link #INDEX_SUMMARY} reports only a total index count at the
 * catalog level (an `O(1)` map size per collection) while the kind and scope breakdown, which walks index keys, is
 * collection-level.
 *
 * **Components are independently *selectable*, not independently *computed***
 *
 * Several components draw on the same underlying reads - {@link #STORAGE_SIZE} and {@link #FRAGMENTATION} both need
 * file lengths, and {@link #STORAGE_SIZE} cannot attribute its unaccounted remainder without knowing the WAL set that
 * {@link #HISTORY} enumerates. The engine therefore takes **one** file-system snapshot and **one** index-key snapshot
 * per request and projects it into whichever components were asked for. The component list selects the *output* shape;
 * it must not multiply the *input* work.
 *
 * **Cost classes**
 *
 * - free - in-memory counter or map read: {@link #IDENTITY}, {@link #RECORD_COUNTS}, {@link #COLLECTIONS},
 *   {@link #SESSIONS}, {@link #COMMIT_PIPELINE}, {@link #ACTIVITY}, {@link #STORAGE_COMPOSITION},
 *   {@link #VOLATILE_STATE}
 * - bounded IO - one flat directory listing plus targeted `stat` calls: {@link #STORAGE_SIZE},
 *   {@link #FRAGMENTATION}, {@link #HISTORY}, {@link #DURABILITY}
 * - proportional to index count: {@link #INDEX_SUMMARY} at the collection level
 * - expensive, never poll: {@link #INDEX_CARDINALITY}, {@link #MEMORY_FOOTPRINT}. Both are rejected outright on the
 *   instance-wide variant, where their cost would be multiplied by the number of catalogs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see CatalogStatistics
 * @see EntityCollectionStatistics
 */
public enum CatalogStatisticsComponent {

	/**
	 * Catalog id, name, state, version, read-only / unusable flags and transactional capabilities. Always delivered
	 * regardless of whether it was requested - nothing else in the response can be interpreted without it, and that
	 * holds for a collection-level response just as much as for a catalog-level one.
	 */
	IDENTITY(true, true),

	/**
	 * Total / live / archived record counts. Catalog level sums the per-collection counters; collection level reports
	 * the counters of the named collection.
	 */
	RECORD_COUNTS(true, true),

	/**
	 * Catalog level: the inventory of entity collections - which collections exist and their entity type primary keys.
	 * Collection level: the header counters of the named collection.
	 */
	COLLECTIONS(true, true),

	/**
	 * Active session count, split into read-only and read-write. Sessions are opened against a catalog, not against
	 * a collection.
	 */
	SESSIONS(true, false),

	/**
	 * The four commit pipeline version watermarks and the deltas between them - how far behind durability and
	 * visibility currently are. The pipeline is catalog-wide.
	 */
	COMMIT_PIPELINE(true, false),

	/**
	 * Transaction, mutation and WAL counters together with their short-window rates. Transactions span the catalog.
	 */
	ACTIVITY(true, false),

	/**
	 * The disk footprint decomposition - live bytes, waste, WAL, files awaiting deletion, bootstrap and the
	 * unaccounted remainder. Both levels are served from the same single directory listing.
	 */
	STORAGE_SIZE(true, true),

	/**
	 * Storage-part histogram - where the bytes actually go. Catalog level covers the catalog's own data store
	 * (schemas, catalog indexes); collection level covers the named collection's data store.
	 */
	STORAGE_COMPOSITION(true, true),

	/**
	 * Active record share, waste accumulation rate and the compaction forecast.
	 */
	FRAGMENTATION(true, true),

	/**
	 * Time-travel window, retained WAL files and the awaiting-deletion breakdown. The write-ahead log is catalog-wide.
	 */
	HISTORY(true, false),

	/**
	 * Checkpoint cadence, fence depth and files forced - how much replay a crash would cost right now. Durability is
	 * a property of the catalog's write-ahead log.
	 */
	DURABILITY(true, false),

	/**
	 * Index counts. Catalog level reports the total only, which each collection answers from an `O(1)` map size;
	 * the breakdown by index kind and scope requires walking the index keys and is therefore collection-level.
	 */
	INDEX_SUMMARY(true, true),

	/**
	 * Distinct values and records covered per index. Expensive - never part of a polled refresh, and available at the
	 * collection level only, because a catalog-wide form would mean paying that cost for every collection at once.
	 */
	INDEX_CARDINALITY(false, true),

	/**
	 * Best-effort heap estimates per collection and index. Expensive - never part of a polled refresh, and available
	 * at the collection level only, for the same reason as {@link #INDEX_CARDINALITY}.
	 */
	MEMORY_FOOTPRINT(false, true),

	/**
	 * Pending (not yet flushed) state and the in-memory history retained for old open sessions.
	 */
	VOLATILE_STATE(true, true);

	/**
	 * Whether this component can be requested from a catalog-level call.
	 */
	private final boolean catalogLevel;
	/**
	 * Whether this component can be requested from an entity-collection-level call.
	 */
	private final boolean collectionLevel;

	CatalogStatisticsComponent(boolean catalogLevel, boolean collectionLevel) {
		this.catalogLevel = catalogLevel;
		this.collectionLevel = collectionLevel;
	}

	/**
	 * Tells whether this component has a catalog-level form and may therefore appear in a request for
	 * {@link CatalogStatistics}.
	 *
	 * @return true when the component may be requested from a catalog-level call
	 */
	public boolean isCatalogLevel() {
		return this.catalogLevel;
	}

	/**
	 * Tells whether this component has an entity-collection-level form and may therefore appear in a request for
	 * {@link EntityCollectionStatistics}.
	 *
	 * @return true when the component may be requested from a collection-level call
	 */
	public boolean isCollectionLevel() {
		return this.collectionLevel;
	}

	/**
	 * Rejects a selection a catalog-level call cannot answer, so that the dispatch that follows may treat every
	 * remaining component as valid and throw on anything else as a programming error.
	 *
	 * @param components the requested components
	 * @throws EvitaInvalidUsageException when the selection is empty or names a component with no catalog-level form
	 */
	public static void assertCatalogLevel(@Nonnull Set<CatalogStatisticsComponent> components)
		throws EvitaInvalidUsageException {
		assertNotEmpty(components);
		for (final CatalogStatisticsComponent component : components) {
			if (!component.isCatalogLevel()) {
				throw new EvitaInvalidUsageException(
					"Statistics component `" + component + "` has no catalog-level form - ask the entity collection " +
						"it belongs to for it."
				);
			}
		}
	}

	/**
	 * Rejects a selection an entity-collection-level call cannot answer.
	 *
	 * @param components the requested components
	 * @throws EvitaInvalidUsageException when the selection is empty or names a component with no collection-level
	 *                                    form
	 */
	public static void assertCollectionLevel(@Nonnull Set<CatalogStatisticsComponent> components)
		throws EvitaInvalidUsageException {
		assertNotEmpty(components);
		for (final CatalogStatisticsComponent component : components) {
			if (!component.isCollectionLevel()) {
				throw new EvitaInvalidUsageException(
					"Statistics component `" + component + "` has no entity collection form - ask the catalog for it."
				);
			}
		}
	}

	/**
	 * Rejects a request that names no component at all.
	 *
	 * Answering it with the always-delivered identity alone would look like a successful, empty catalog rather than
	 * like the malformed request it is - a caller that wants only the identity asks for
	 * {@link #IDENTITY} explicitly.
	 *
	 * @param components the requested components
	 * @throws EvitaInvalidUsageException when nothing was requested
	 */
	private static void assertNotEmpty(@Nonnull Set<CatalogStatisticsComponent> components)
		throws EvitaInvalidUsageException {
		if (components.isEmpty()) {
			throw new EvitaInvalidUsageException(
				"No statistics component was requested - name at least one component to compute."
			);
		}
	}

}
