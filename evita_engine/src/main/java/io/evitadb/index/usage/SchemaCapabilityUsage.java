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

package io.evitadb.index.usage;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.LongAdder;

/**
 * How often one schema capability is **requested by queries** against how often **mutations touch it** - the reading
 * that answers *"you never filter by EAN, so why are you paying to keep its filter index up to date?"*.
 *
 * # `requested` is not `chosen`, and must never be read as it
 *
 * This is the one thing to understand before using either number, because the neighbouring
 * {@link io.evitadb.index.IndexActivity} counts something that sounds identical and is not.
 *
 * - {@link io.evitadb.index.IndexActivity#getQueryCount()} counts the times **one physical index** was in the winning
 *   target index set of an executed plan. It answers *"is this index earning the heap it occupies?"*, so a candidate
 *   index the planner probed and then discarded is deliberately excluded - counting it would inflate the losers.
 * - {@link #getRequestedCount()} counts the times **a logical query asked for this capability**, whichever plan won.
 *   It answers *"would dropping this flag from the schema break somebody's query?"*, and for that question a losing
 *   candidate plan is not a false positive at all: the query named the attribute, so removing `filterable()` would
 *   have made it invalid regardless of which index ended up serving it.
 *
 * The two therefore disagree by design, and the difference is not an error to be reconciled. **Never present this
 * count as physical index earning**, and never name it `queryCount` on any surface that carries it.
 *
 * It counts **once per logical query**, not once per candidate plan - the planner translates a filter afresh for every
 * candidate, so a count taken where the translation happens would measure how many alternatives the planner
 * considered rather than what the workload does.
 *
 * # What the update side counts
 *
 * {@link #getUpdatedCount()} counts **entity mutations that touched the element**, deduplicated per entity mutation
 * rather than per affected index: one upsert writing an attribute that lives in the global index and five reduced
 * indexes is one, not six. The fan-out width is a legitimately different metric - *"physical maintenance
 * operations"* - and it is not what this measures; the per-index counters are where fan-out is visible.
 *
 * Like the per-index counters this measures work **performed**, including work a later rollback undoes, because the
 * maintenance was paid either way.
 *
 * # Lifetime
 *
 * **Since catalog load, never persisted** - the same contract {@link io.evitadb.api.statistics.ActivityStatistics}
 * carries, for the same reason: the operational use is a rate over an observation window, and
 * {@link #getObservedSinceMillis()} is the denominator that makes a zero count reportable rather than merely unknown.
 * An element dropped from the schema and re-added starts over, because the registry prunes its entry on the way out.
 *
 * # Concurrency, and why these are `LongAdder`s
 *
 * Deliberately **non-transactional**: shared mutable telemetry, visible across catalog versions, never part of the
 * transactional diff layer.
 *
 * The adversarial case is one popular attribute requested by every query thread at once - a single shared counter,
 * which is exactly where a CAS-based `AtomicLong` degenerates into one contended cache line ping-ponging across the
 * machine. {@link LongAdder} stripes only once CASes actually start failing, costs one uncontended `long` until then,
 * and is bounded by the CPU count. The registry holds dozens of entries rather than hundreds of thousands, so the
 * cell array's variable footprint - the reason {@link io.evitadb.index.IndexActivity} rejected `LongAdder` - does not
 * apply here.
 *
 * Timestamps are plain volatile stores (last writer wins), which is sufficient for a figure read by an operator.
 * Two properties follow, and neither may be asserted against: **a stamp is not monotonic**, and **a count can be seen
 * without its stamp**, because the count advances first.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see SchemaCapabilityKey
 * @see io.evitadb.index.IndexActivity
 */
public final class SchemaCapabilityUsage {

	/**
	 * Milliseconds in the second the two stamps are coarsened to.
	 */
	private static final long COARSENING_GRANULARITY_MILLIS = 1_000L;

	/**
	 * How many logical queries have requested this capability since the catalog was loaded.
	 */
	private final LongAdder requested = new LongAdder();
	/**
	 * How many entity mutations have touched the element this capability belongs to since the catalog was loaded.
	 */
	private final LongAdder updated = new LongAdder();
	/**
	 * Epoch millis of the last recorded request, or `0` when this capability has never been requested since the
	 * catalog was loaded. Zero is the "never" sentinel rather than a real instant - a query planned at the epoch is
	 * not a state this database can be in, which is also why the coarsening below may safely treat the sentinel as an
	 * ordinary value.
	 */
	private volatile long lastRequestedAtMillis;
	/**
	 * Epoch millis of the last recorded mutation, or `0` when nothing has touched the element since the catalog was
	 * loaded - see {@link #lastRequestedAtMillis} for the sentinel.
	 */
	private volatile long lastUpdatedAtMillis;
	/**
	 * Epoch millis of the moment observation of this capability began - when this holder was constructed. Unlike the
	 * two stamps above there is no "never" sentinel, because the value is always set.
	 *
	 * Plain `final` rather than volatile: the holder is reached through the registry's map, whose own publication
	 * already establishes the happens-before every reader needs.
	 */
	private final long observedSinceMillis;

	/**
	 * Opens the observation window at the moment the holder is constructed - catalog load for a capability the schema
	 * already declared, the schema mutation itself for one declared later.
	 *
	 * That reading is true only because {@link SchemaCapabilityUsageRegistry} mints holders **eagerly**, at the moment
	 * a schema version is adopted, rather than on first use. Were they created on first use, this stamp would say
	 * *"first queried"* while the public contract on
	 * {@link io.evitadb.api.statistics.SchemaCapabilityUsageSnapshot#observedSince()} promises *"declared"* - and a
	 * capability first touched a month after load would report a millisecond-wide window, turning one request into an
	 * enormous rate.
	 */
	public SchemaCapabilityUsage() {
		this.observedSinceMillis = System.currentTimeMillis();
	}

	/**
	 * Records that one logical query asked for this capability - see the class documentation for why that is a
	 * different claim from "an index maintaining it was chosen".
	 *
	 * @param nowMillis the instant the query plan was built, shared by every capability one query requested so that a
	 *                  single query cannot stamp them with two different moments
	 */
	public void recordRequested(long nowMillis) {
		this.requested.increment();
		if (differentSecond(this.lastRequestedAtMillis, nowMillis)) {
			this.lastRequestedAtMillis = nowMillis;
		}
	}

	/**
	 * Records that one entity mutation touched the element this capability belongs to.
	 *
	 * @param nowMillis the instant the mutation finished applying, shared by every capability it touched
	 */
	public void recordUpdated(long nowMillis) {
		this.updated.increment();
		if (differentSecond(this.lastUpdatedAtMillis, nowMillis)) {
			this.lastUpdatedAtMillis = nowMillis;
		}
	}

	/**
	 * @return how many logical queries have requested this capability since the catalog was loaded
	 */
	public long getRequestedCount() {
		return this.requested.sum();
	}

	/**
	 * @return how many entity mutations have touched the element this capability belongs to since the catalog was
	 * loaded
	 */
	public long getUpdatedCount() {
		return this.updated.sum();
	}

	/**
	 * Raw epoch millis rather than a timestamp, and **accurate only to the second**: the store is skipped whenever the
	 * resident value already falls in the same second, which is what keeps a capability requested thousands of times a
	 * second down to one store. A reading of *"last requested three weeks ago"* is not made worse by it. Rendering is
	 * the management surface's job, including turning the `0` sentinel into an explicit absence rather than a date in
	 * 1970.
	 *
	 * @return when this capability was last requested, or `0` when it has not been since the catalog was loaded
	 */
	public long getLastRequestedAtMillis() {
		return this.lastRequestedAtMillis;
	}

	/**
	 * @return when a mutation last touched the element, or `0` when none has since the catalog was loaded; coarsened
	 * to the second exactly like {@link #getLastRequestedAtMillis()}
	 */
	public long getLastUpdatedAtMillis() {
		return this.lastUpdatedAtMillis;
	}

	/**
	 * The window the two counts were accumulated over - the denominator a client divides them by to state a lifetime
	 * average rate, and what lets it qualify a zero honestly: *"not requested in the twenty minutes since this
	 * capability was first observed"* is actionable where a bare zero is not.
	 *
	 * @return when observation of this capability began, never the "never" sentinel
	 */
	public long getObservedSinceMillis() {
		return this.observedSinceMillis;
	}

	/**
	 * The coarsening test - a plain read of the volatile stamp and two divisions, no allocation and no CAS, so the
	 * common case (a stamp already written this second) costs nothing but the read.
	 *
	 * @param storedMillis the stamp currently resident
	 * @param nowMillis    the instant being recorded
	 * @return true when the new instant falls in a different second and is therefore worth storing
	 */
	private static boolean differentSecond(long storedMillis, long nowMillis) {
		return nowMillis / COARSENING_GRANULARITY_MILLIS != storedMillis / COARSENING_GRANULARITY_MILLIS;
	}

	@Nonnull
	@Override
	public String toString() {
		return "SchemaCapabilityUsage{requested=" + getRequestedCount() + " (last at " + this.lastRequestedAtMillis +
			"), updated=" + getUpdatedCount() + " (last at " + this.lastUpdatedAtMillis +
			"), observed since " + this.observedSinceMillis + "}";
	}

}
