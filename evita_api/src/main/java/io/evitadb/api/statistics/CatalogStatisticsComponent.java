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
 * the component either has no catalog-level form at all - {@link #MEMORY_FOOTPRINT} is collection-level only for
 * exactly this reason - or its catalog-level form reports something different and cheaper than its collection-level
 * one. {@link #INDEX_SUMMARY} reports only a total index count at the catalog level (an `O(1)` map size per
 * collection) while the kind and scope breakdown is collection-level; {@link #INDEX_CARDINALITY} reports the catalog
 * index's global unique indexes at the catalog level - a count that does not grow with entity count - while the
 * collection's own data-bounded indexes stay collection-level. In neither case does the catalog-level form aggregate
 * the collection-level one.
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
 * - expensive, never poll: {@link #INDEX_CARDINALITY} *at the collection level*, and {@link #MEMORY_FOOTPRINT}.
 *   `MEMORY_FOOTPRINT` has no catalog-level form at all, so the instance-wide call can never multiply its cost by the
 *   number of catalogs; `INDEX_CARDINALITY` is admitted there because what the catalog level reports is a handful of
 *   `O(1)` readings rather than the per-collection walk of the same name.
 *
 * **What the instance-wide call multiplies**
 *
 * Answering for every catalog at once multiplies whatever comes back by the number of catalogs, so a component is
 * judged there on **payload as much as on time** - a cheap computation can still return a large listing. That call
 * nevertheless applies the same gate as the single-catalog one ({@link #assertCatalogLevel(Set)}), because every
 * component reaching the catalog level answers either with scalars or with a listing no larger than
 * {@link #COLLECTIONS}, which carries one entry per entity collection.
 *
 * {@link #INDEX_CARDINALITY} was weighed against exactly that bar and admitted. Its catalog-level listing runs to
 * (globally-unique attributes × locales in use × scopes) - the same size class as `COLLECTIONS` rather than a
 * measured ratio, since globally-unique attributes are rare in practice while collections are not.
 *
 * The load-bearing argument needs no such estimate, though: selection is opt-in, so a client that cannot afford a
 * component simply does not name it - and barring this one would not remove its cost, only force that client into one
 * call per catalog for the same bytes. That is what separates it from {@link #MEMORY_FOOTPRINT}, where the
 * collection-level walk is genuinely expensive and no cheap catalog-level form exists to fall back on: there a bar
 * prevents work, here it would only relocate it.
 *
 * A future component whose catalog-level answer is materially larger than `COLLECTIONS` would need a gate narrower than
 * {@link #isCatalogLevel()}. None exists: introduce it together with that component rather than in advance.
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
	 * Index counts. Catalog level reports the total only; the breakdown by index kind and scope is collection-level.
	 *
	 * Both are `O(1)` reads - each collection maintains its counts per index kind and scope incrementally rather than
	 * counting on demand. The split is therefore about *shape*, not cost: a catalog-level breakdown would carry one
	 * entry per collection per kind per scope, making the response grow with the number of collections, which is
	 * exactly what the catalog level exists not to do.
	 */
	INDEX_SUMMARY(true, true),

	/**
	 * Distinct values and records covered per index.
	 *
	 * The two levels report different indexes and have different cost classes, which is deliberate rather than an
	 * oversight. Catalog level reports the *global unique indexes* of the catalog index
	 * ({@link CatalogIndexCardinality}): there is one per globally-unique attribute per locale in use and every reading
	 * is an `O(1)` counter, so the cost grows with neither the entity count nor the collection count. Note the locale
	 * dimension is data-influenced rather than declared, so this is a small constant rather than a schema-derived one.
	 * Collection level reports the collection's own entity indexes
	 * ({@link CollectionIndexCardinality}), which is **expensive and must never be part of a polled refresh** - a
	 * collection reaches hundreds of thousands of data-bounded indexes, and a covered-record count on a unique
	 * attribute walks one step per record.
	 *
	 * There is deliberately no catalog-wide form of the *collection* half: it would mean paying that cost for every
	 * collection at once.
	 *
	 * The catalog-level half **is** answerable for every catalog at once. It was weighed on payload rather than on
	 * compute - see the class javadoc - and the listing it returns stays in the same size class as
	 * {@link #COLLECTIONS}.
	 */
	INDEX_CARDINALITY(true, true),

	/**
	 * Best-effort heap estimates per collection and index. Expensive - never part of a polled refresh, and available
	 * at the collection level only, for the same reason as {@link #INDEX_CARDINALITY}.
	 *
	 * **Not delivered by any build, and the obstacle is structural rather than unfinished work.** An index's storage
	 * is not one record: the entity-id bitmaps live in its own `EntityIndexStoragePart`, while its attribute, price,
	 * facet and histogram sub-indexes are separate storage parts under independent keys. Nothing maps an index back
	 * to the parts it owns without running the flush machinery that assembles that list as a side effect of writing.
	 * So a measured size can be read cheaply for the main part alone - which would report the bitmaps and silently
	 * omit everything an operator opens this component to find - and a computed estimate would have to model a dozen
	 * structures whose byte cost varies by orders of magnitude with compression and layout, not by a constant factor.
	 *
	 * Reporting a number that is wrong in an unknown direction is worse here than reporting none: the whole point is
	 * to decide which index to act on. The component therefore reports {@link ComponentAvailability#NOT_SUPPORTED}
	 * with that reason rather than a partial figure, and the decision of which way to resolve it is deliberately
	 * left open.
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
	 * {@link CatalogStatistics}, including the instance-wide variant that answers for every catalog at once.
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
