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

package io.evitadb.index;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * How often one logical index is **queried** against how often it is **updated** - the reading that says whether an
 * index earns the maintenance it costs.
 *
 * An index browse already reports what an index *costs* ({@link io.evitadb.api.statistics.IndexDetail#heapSizeInBytes},
 * entity count, cardinality). These five readings say what it *earns*: an index with heavy write traffic and no query
 * hits is a direct candidate for dropping a `filterable()` / `sortable()` / faceted flag, or for rethinking a reference
 * setup. They surface on {@link io.evitadb.api.statistics.BrowsedIndex} and
 * {@link io.evitadb.api.statistics.IndexDetail}.
 *
 * # What each reading counts
 *
 * - **Query count** counts the times this index was in the **winning target index set of an executed query plan**.
 *   Planning also probes candidate indexes that lose, reaches the super price index from reduced-index plans, and pulls
 *   referenced-entity indexes for fetch enrichment - none of that counts. The metric means *"this index was the
 *   filtering backbone of an executed query"*, because counting every consultation would inflate the losers and make
 *   the number unactionable.
 * - **Update count** counts **entity mutations that acquired this index for modification**, deduplicated per entity
 *   mutation rather than per attribute write. It counts work *performed*, including work a later rollback undoes: a
 *   rolled-back transaction still paid the index-maintenance cost, and this measures maintenance cost rather than
 *   surviving state. By the same reading, a transactional write counts twice - once as the writing session applies it
 *   to its isolated layer, once as {@link io.evitadb.core.transaction.TransactionManager} replays it from the
 *   write-ahead log onto the trunk - because the index does the work both times. Compare indexes against each other,
 *   never against an expected mutation count.
 * - **Observed since** is the window the two counts were accumulated over - the moment observation of **this** index
 *   began, which is the denominator a client divides them by to state a lifetime average rate. It is deliberately per
 *   index and not per catalog load: an index created hours after the catalog opened was not observable before it
 *   existed, and billing it the catalog's window would make "never queried in the six hours observed" a false
 *   statement about it.
 *
 * # Lifetime, and why this is a separate object
 *
 * **The counters are since catalog load and are never persisted** - the same "since this instance was created" contract
 * {@link io.evitadb.api.statistics.ActivityStatistics} carries. Their operational use is a rate over an observation
 * window, which persisting would not improve, while putting a hot mutable value into an
 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart} would cost a manifest
 * rewrite per commit plus its share of the WAL.
 *
 * **The holder exists because hot indexes are replaced rather than mutated.** Every commit that dirties an index
 * rebuilds it through `createCopyWithMergedTransactionalMemory`, so a counter held as a plain index field would reset
 * exactly on the indexes most worth measuring. This object is therefore passed **by reference** through every
 * merge-copy constructor - like the index's `primaryKey`, unlike its recomputed `version` - so one instance spans every
 * catalog version of one logical index. Reload from disk and fresh creation allocate a new one, which is what makes the
 * counters "since catalog load" and what restamps {@link #getObservedSince()} along with them.
 *
 * It also holds no back-reference to its index, so retaining a holder (as an index browse does while it pages) cannot
 * keep index contents alive.
 *
 * # Concurrency
 *
 * Deliberately **non-transactional**: shared mutable telemetry, visible across catalog versions, never part of the
 * transactional diff layer. Counts advance by CAS and timestamps are plain volatile writes (last writer wins), which is
 * sufficient for a figure read by an operator - a reader can observe a count that has advanced past the timestamp
 * beside it, and the pair is not claimed to be atomic.
 *
 * Two properties follow, and neither may be asserted against: **a stamp is not monotonic** - two concurrent recordings
 * can be written in the opposite order to the instants they carry, leaving the older one resident - and **a count can
 * be seen without its stamp**, because the count is advanced first. Only the counts themselves never go backwards, and
 * a stamp once set is never cleared.
 *
 * `LongAdder` is deliberately **not** used: its cell array grows under contention, which would make the byte-exact JOL
 * heap assertions non-deterministic. Contention here is one increment per query and one per entity mutation per index,
 * where a CAS costs nothing worth reclaiming.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see Index#getActivity()
 */
public final class IndexActivity {

	/**
	 * CAS handle advancing {@link #queryCount} - a static field of the class, so an instance carries no extra state and
	 * the heap arithmetic stays five longs.
	 */
	private static final AtomicLongFieldUpdater<IndexActivity> QUERY_COUNT_UPDATER =
		AtomicLongFieldUpdater.newUpdater(IndexActivity.class, "queryCount");
	/**
	 * CAS handle advancing {@link #updateCount} - see {@link #QUERY_COUNT_UPDATER}.
	 */
	private static final AtomicLongFieldUpdater<IndexActivity> UPDATE_COUNT_UPDATER =
		AtomicLongFieldUpdater.newUpdater(IndexActivity.class, "updateCount");

	/**
	 * How many executed query plans have chosen this index as part of their winning target index set, since the
	 * catalog was loaded.
	 */
	private volatile long queryCount;
	/**
	 * How many entity mutations have acquired this index for modification, since the catalog was loaded.
	 */
	private volatile long updateCount;
	/**
	 * Epoch millis of the last {@link #queryCount} increment, or `0` when this index has never been queried since the
	 * catalog was loaded. Zero is the "never" sentinel rather than a real instant - an index queried at the epoch is
	 * not a state this database can be in.
	 */
	private volatile long lastQueriedAtMillis;
	/**
	 * Epoch millis of the last {@link #updateCount} increment, or `0` when this index has never been updated since the
	 * catalog was loaded - see {@link #lastQueriedAtMillis} for the sentinel.
	 */
	private volatile long lastUpdatedAtMillis;
	/**
	 * Epoch millis of the moment observation of this index began - when this holder was constructed. Unlike the two
	 * stamps above there is no "never" sentinel, because the value is always set.
	 *
	 * Plain `final` rather than volatile: the holder is reached through the index's own final `activity` field, and
	 * final-field safe publication already guarantees that every thread which can see the index sees this value.
	 */
	private final long observedSinceMillis;

	/**
	 * Opens the observation window at the moment the holder is constructed - catalog load for an index restored from
	 * disk, first creation for an index born later.
	 */
	public IndexActivity() {
		this.observedSinceMillis = System.currentTimeMillis();
	}

	/**
	 * Records that an executed query plan chose this index.
	 *
	 * @param nowMillis the instant the plan was built, shared by every index of one winning set so that a single query
	 *                  cannot stamp its indexes with two different moments
	 */
	public void recordQuery(long nowMillis) {
		QUERY_COUNT_UPDATER.incrementAndGet(this);
		this.lastQueriedAtMillis = nowMillis;
	}

	/**
	 * Records that an entity mutation acquired this index for modification.
	 *
	 * @param nowMillis the instant the mutation finished applying, shared by every index it touched
	 */
	public void recordUpdate(long nowMillis) {
		UPDATE_COUNT_UPDATER.incrementAndGet(this);
		this.lastUpdatedAtMillis = nowMillis;
	}

	/**
	 * @return how many executed query plans chose this index since the catalog was loaded
	 */
	public long getQueryCount() {
		return this.queryCount;
	}

	/**
	 * @return how many entity mutations acquired this index for modification since the catalog was loaded
	 */
	public long getUpdateCount() {
		return this.updateCount;
	}

	/**
	 * The recording methods take epoch millis while these hand back a timestamp: the write side runs on the query and
	 * mutation paths and must allocate nothing, whereas the read side is an operator-facing surface reached a handful
	 * of times per management call.
	 *
	 * @return when the last query that chose this index was planned, or null when none has since the catalog was loaded
	 */
	@Nullable
	public OffsetDateTime getLastQueriedAt() {
		return toTimestamp(this.lastQueriedAtMillis);
	}

	/**
	 * @return when the last entity mutation that acquired this index finished applying, or null when none has since the
	 * catalog was loaded
	 */
	@Nullable
	public OffsetDateTime getLastUpdatedAt() {
		return toTimestamp(this.lastUpdatedAtMillis);
	}

	/**
	 * The window the two counts were accumulated over, as raw epoch millis - the arithmetic form, for a caller dividing
	 * a count by an elapsed duration rather than rendering a date.
	 *
	 * @return when observation of this index began
	 */
	public long getObservedSinceMillis() {
		return this.observedSinceMillis;
	}

	/**
	 * Unlike the two "last at" readings this one is never null: an index has been observed since the moment it came
	 * into existence, so there is nothing for an absence to mean. That is what makes a zero count reportable rather
	 * than merely unknown - "not queried in the twenty minutes since this index was created" is a statement an
	 * operator can act on, where a bare zero is not.
	 *
	 * @return when observation of this index began, never null
	 */
	@Nonnull
	public OffsetDateTime getObservedSince() {
		return atSystemZone(this.observedSinceMillis);
	}

	/**
	 * Decodes one stamp, turning the "never" sentinel into an explicit absence rather than an epoch-zero instant a
	 * client would render as a date in 1970.
	 *
	 * @param millis the recorded stamp, `0` when nothing was ever recorded
	 * @return the timestamp, or null when nothing was ever recorded
	 */
	@Nullable
	private static OffsetDateTime toTimestamp(long millis) {
		return millis == 0L ? null : atSystemZone(millis);
	}

	/**
	 * Renders epoch millis in the JVM's own zone - the conversion the two stamps share with
	 * {@link #getObservedSince()}, which reaches it directly because it has no sentinel to decode first.
	 *
	 * @param millis the instant to render
	 * @return the timestamp
	 */
	@Nonnull
	private static OffsetDateTime atSystemZone(long millis) {
		return OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
	}

	@Nonnull
	@Override
	public String toString() {
		return "IndexActivity{queries=" + this.queryCount + " (last at " + this.lastQueriedAtMillis +
			"), updates=" + this.updateCount + " (last at " + this.lastUpdatedAtMillis +
			"), observed since " + this.observedSinceMillis + "}";
	}

}
