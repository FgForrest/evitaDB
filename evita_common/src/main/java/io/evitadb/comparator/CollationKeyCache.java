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

package io.evitadb.comparator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.text.CollationKey;
import java.text.Collator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Bounded, process-wide cache of {@link CollationKey} byte forms, shared by all
 * {@link LocalizedStringComparator} instances created for the same {@link Locale}.
 *
 * Rationale: `Collator.compare` re-runs the full collation-element machinery on both operands on
 * every call (hundreds of nanoseconds to tens of microseconds plus kilobytes of allocation per
 * comparison on realistic e-commerce corpora), while comparing two pre-computed collation-key byte
 * arrays as unsigned bytes costs about ten nanoseconds, allocates nothing, and is guaranteed by the
 * {@link CollationKey} contract to produce the identical total order. Index write paths
 * compare the same probe and pivot values over and over (binary-search midpoints, B+ tree
 * separators), so a small cache converts almost all collation work into plain byte comparisons.
 *
 * Design notes:
 *
 * - **per-locale static registry** - comparator instances are numerous (one per localized attribute
 *   index), and the key form is a pure function of (locale rules, string): sharing one cache per
 *   locale maximizes the hit rate, bounds total memory to `locales × slots` entries and makes
 *   invalidation unnecessary (entries never become stale);
 * - **2-way direct-mapped, replace-on-collision** - no LRU bookkeeping, no locks; the secondary
 *   slot (derived from the upper hash bits) prevents two hot values that collide on the primary
 *   slot from evicting each other on every access;
 * - **safely published entries, benign races** - {@link CachedKey}'s `value` and `key` are final, so a
 *   racy read of a concurrently published entry is safe under the JMM final-field guarantee; the
 *   worst outcome of a lost concurrent write is a redundant recomputation;
 * - **decay, not resizing** - {@link #SIZE} is an upper bound on how many keys a locale MAY retain, not a
 *   commitment to retain that many. {@link #sweep()} implements CLOCK second chance: an entry touched since the
 *   previous sweep survives, an untouched one is dropped, so the retained footprint follows the live working set
 *   and falls back towards nothing when the cache stops being exercised. Deliberately *not* implemented by
 *   shrinking the slot array: the array is `slots x reference size` (4 MB at the maximum) while the entries it
 *   points at measure ~282 bytes each (~254 MB for a fully populated million-slot locale), so shedding entries
 *   recovers essentially all of the reclaimable memory and resizing would recover under 2% of it while forcing
 *   a volatile array reference onto the hot path. The reference-tracking flag lives INSIDE the entry rather than
 *   in a parallel bitmap so that marking a hit touches the cache line the reader has already loaded, instead of
 *   contending on a side array shared by 64+ slots per line;
 * - **striped collator pool** - `RuleBasedCollator.getCollationKey` is `synchronized`, therefore
 *   each miss borrows a pool stripe's {@link Collator} exclusively (atomic exchange, falling back
 *   to a fresh clone when the stripe is empty), keeping cache misses monitor-free under concurrent
 *   indexing without paying a per-miss `ThreadLocal` lookup;
 * - **memory bound** - `slots` entries per locale, created lazily only for locales that actually
 *   collate. The default slot count is derived from the maximum heap size (see {@link #defaultSize()}),
 *   because a value large enough to cover a real e-commerce corpus would be disproportionate for a
 *   small embedded deployment; the system property `evita.collationKeyCache.size` overrides it
 *   (values round down to a power of two and are capped at {@link #MAX_SIZE}) and `0` disables the
 *   cache entirely, making comparators delegate straight to {@link Collator#compare(String, String)}.
 *   Only the slot array is allocated eagerly (`slots × reference size`); entries themselves are
 *   filled on demand, so the full footprint is reached only by a corpus large enough to fill the
 *   cache. Both bounds are **per locale**.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class CollationKeyCache implements Serializable {
	@Serial private static final long serialVersionUID = 2359981769309770696L;

	/**
	 * Name of the system property controlling the per-locale slot count.
	 */
	static final String SIZE_PROPERTY = "evita.collationKeyCache.size";
	/**
	 * Hard upper bound on the per-locale slot count; measurement showed the hit rate flattening at
	 * this size on a real ~1M-product localized catalog, so a larger cache buys nothing.
	 */
	private static final int MAX_SIZE = 1 << 20;
	/**
	 * Lower bound of the heap-derived default; also the historical fixed default.
	 */
	private static final int MIN_DEFAULT_SIZE = 8192;
	/**
	 * Fraction of the maximum heap (as a divisor) budgeted for one locale's fully populated cache.
	 */
	private static final int DEFAULT_HEAP_SHARE_DIVISOR = 50;
	/**
	 * Estimated retained size of a single populated entry in bytes - a {@link CachedKey} record plus
	 * its collation-key byte array. Collation keys measure roughly 6.5 bytes per character, so this
	 * corresponds to attribute values of about thirty characters.
	 */
	private static final int ESTIMATED_ENTRY_SIZE = 256;
	/**
	 * Registry of per-locale caches; populated lazily, never evicted (the locale count is bounded
	 * by the catalog schemas).
	 */
	private static final ConcurrentHashMap<Locale, CollationKeyCache> INSTANCES = new ConcurrentHashMap<>(8);
	/**
	 * Number of slots per locale resolved once at class load; zero disables caching.
	 */
	private static final int SIZE = resolveSize();
	/**
	 * Number of stripes in the per-cache collator pool; a power of two so stripe selection can mask
	 * the thread id. Sixteen stripes make same-stripe contention between concurrently indexing
	 * threads unlikely, and a collision merely costs one fresh `Collator.getInstance` clone.
	 */
	private static final int COLLATOR_STRIPES = 16;

	/**
	 * Cached entries; elements are written with plain stores (see the class javadoc on benign
	 * races) and are either null or fully initialized immutable records.
	 */
	@Nonnull private final CachedKey[] slots;
	/**
	 * Bit mask selecting a slot index from a hash (`SIZE - 1`).
	 */
	private final int mask;
	/**
	 * Locale whose default collator order this cache serves; needed to create collators on demand
	 * when a pool stripe is empty.
	 */
	@Nonnull private final Locale locale;
	/**
	 * Striped pool of collators used to compute keys on cache misses. `RuleBasedCollator` is not
	 * thread-safe, so each computation borrows a stripe's collator exclusively (atomic exchange) and
	 * returns it afterwards; a thread hitting an empty stripe simply creates a fresh collator.
	 *
	 * A striped pool is used instead of a `ThreadLocal` deliberately: profiling showed the
	 * per-miss `ThreadLocal.get()` costing more than the collation-key computation itself
	 * (`ThreadLocalMap` probing on the hot indexing threads), while an uncontended atomic exchange
	 * is a few nanoseconds with a hard upper bound.
	 */
	@SuppressWarnings("TransientFieldNotInitialized")
	@Nonnull private final transient AtomicReferenceArray<Collator> collators;

	/**
	 * Returns the shared cache for `locale`, or null when caching is disabled by configuration.
	 *
	 * @param locale locale whose default collator order the cache serves
	 * @return shared cache instance, or null when the cache is disabled
	 */
	@Nullable
	static CollationKeyCache forLocale(@Nonnull Locale locale) {
		return SIZE <= 0 ? null : INSTANCES.computeIfAbsent(locale, CollationKeyCache::new);
	}

	/**
	 * Resolves the configured per-locale slot count, rounding down to a power of two so that slot
	 * selection can use bit masking, and capping it to keep the worst-case footprint sane. When the
	 * property is not set at all, the default is derived from the heap size (see
	 * {@link #defaultSize()}).
	 *
	 * @return slot count per locale; zero when caching is disabled
	 */
	private static int resolveSize() {
		final Integer configured = Integer.getInteger(SIZE_PROPERTY);
		if (configured == null) {
			return defaultSize();
		}
		return configured <= 0 ? 0 : Integer.highestOneBit(Math.min(configured, MAX_SIZE));
	}

	/**
	 * Derives the default per-locale slot count from the maximum heap size.
	 *
	 * A fixed default cannot serve both ends of the deployment range: measurement on a real
	 * ~1M-product localized catalog showed the previous fixed default of 8192 slots covering only a
	 * fraction of the pivot working set (nearly every B+ tree comparison recomputed a full collation
	 * key), and raising it to {@link #MAX_SIZE} made that workload **2x** faster - while the same
	 * value on a small embedded deployment would reserve memory out of all proportion to its corpus.
	 *
	 * Heap size is used as the proxy for corpus size, because the two correlate in practice and the
	 * corpus is not known when this class is loaded. The budget is {@link #DEFAULT_HEAP_SHARE_DIVISOR}
	 * of the maximum heap, divided by the {@link #ESTIMATED_ENTRY_SIZE} average cost of one entry.
	 *
	 * Note that entries are populated **lazily**, so this bound is a ceiling that only a corpus large
	 * enough to fill the cache ever reaches; a small corpus pays just the slot array
	 * (`slots × reference size`) regardless of how large the default is. The bound is **per locale**,
	 * so deployments with many localized attribute values should size the cache explicitly through
	 * the `evita.collationKeyCache.size` system property.
	 *
	 * @return slot count per locale, clamped to [{@link #MIN_DEFAULT_SIZE}, {@link #MAX_SIZE}]
	 */
	private static int defaultSize() {
		final long budgetBytes = Runtime.getRuntime().maxMemory() / DEFAULT_HEAP_SHARE_DIVISOR;
		final long slots = budgetBytes / ESTIMATED_ENTRY_SIZE;
		if (slots <= MIN_DEFAULT_SIZE) {
			return MIN_DEFAULT_SIZE;
		} else if (slots >= MAX_SIZE) {
			return MAX_SIZE;
		} else {
			return Integer.highestOneBit((int) slots);
		}
	}

	/**
	 * Creates the cache for a single locale; invoked lazily through {@link #forLocale(Locale)}.
	 *
	 * @param locale locale whose default collator order this cache serves
	 */
	private CollationKeyCache(@Nonnull Locale locale) {
		this.slots = new CachedKey[SIZE];
		this.mask = SIZE - 1;
		this.locale = locale;
		this.collators = new AtomicReferenceArray<>(COLLATOR_STRIPES);
	}

	/**
	 * Returns the collation-key byte form of `value` under this cache's locale, computing and
	 * caching it when absent. Unsigned lexicographic order of the returned arrays (i.e.
	 * `Arrays.compareUnsigned`) equals {@link Collator#compare(String, String)} of the source
	 * strings for the same locale.
	 *
	 * @param value string to resolve
	 * @return collation-key bytes defining the value's position in the locale's total order
	 */
	@Nonnull
	byte[] keyFor(@Nonnull String value) {
		final int hash = value.hashCode();
		final int primary = hash & this.mask;
		final CachedKey primaryEntry = this.slots[primary];
		// the `value == ...` reference check is a deliberate fast path, not dead code: on the
		// common identity hit (the same probe/pivot String recurs across binary-search and B+ tree
		// steps, see class javadoc) it returns without entering String.equals at all. IntelliJ
		// reports it as an "unnecessary part of condition" because reference identity implies
		// equals(), so the `==` operand cannot change the boolean result - but it is kept because
		// it changes the cost.
		//noinspection StringEquality,ConditionCoveredByFurtherCondition
		if (primaryEntry != null && (primaryEntry.value == value || primaryEntry.value.equals(value))) {
			// mark for CLOCK second chance; the entry's cache line is already loaded by the comparison above
			primaryEntry.referenced = true;
			return primaryEntry.key;
		}
		final int secondary = (hash >>> 16) & this.mask;
		final CachedKey secondaryEntry = this.slots[secondary];
		// intentional identity fast path (see the note on the primary slot above)
		//noinspection StringEquality,ConditionCoveredByFurtherCondition
		if (secondaryEntry != null && (secondaryEntry.value == value || secondaryEntry.value.equals(value))) {
			secondaryEntry.referenced = true;
			return secondaryEntry.key;
		}
		// borrow a collator from the stripe keyed by the current thread (exclusive via exchange);
		// an empty stripe - first use or a concurrent borrower - just creates a fresh instance
		final int stripe = (int) Thread.currentThread().getId() & (COLLATOR_STRIPES - 1);
		Collator collator = this.collators.getAndSet(stripe, null);
		if (collator == null) {
			// Collator.getInstance returns a fresh clone per call, safe to use exclusively
			collator = Collator.getInstance(this.locale);
		}
		final byte[] key = collator.getCollationKey(value).toByteArray();
		this.collators.set(stripe, collator);
		// prefer filling an empty way; when both ways are occupied displace the primary slot - the
		// secondary way then shields the displaced value's competitor from ping-pong eviction
		if (primaryEntry == null || secondaryEntry != null) {
			this.slots[primary] = new CachedKey(value, key);
		} else {
			this.slots[secondary] = new CachedKey(value, key);
		}
		return key;
	}

	/**
	 * Drops every entry that has not been read since the previous sweep, and clears the reference flag of those that
	 * have — CLOCK second chance. The retained footprint therefore follows the live working set: a locale that keeps
	 * being exercised holds on to exactly the keys it keeps asking for, while one that has gone quiet (a catalog that
	 * finished its bulk import, or an attribute nothing sorts by any more) releases its keys over the following
	 * sweeps. See the class javadoc for why this, rather than resizing, is the mechanism.
	 *
	 * Safe by construction: entries are a pure function of `(locale rules, string)`, so discarding one can never
	 * produce a wrong answer — only a recomputation on the next miss. Runs without synchronization; a concurrent
	 * {@link #keyFor(String)} may lose the race and have its just-marked entry dropped, which costs one
	 * recomputation. Cost is `O(slots)` (a linear scan of one reference array), so a caller sweeping the largest
	 * supported cache walks a million references.
	 *
	 * @return number of entries dropped, for the caller to log or expose
	 */
	int sweep() {
		int dropped = 0;
		final CachedKey[] theSlots = this.slots;
		for (int i = 0; i < theSlots.length; i++) {
			final CachedKey entry = theSlots[i];
			if (entry == null) {
				continue;
			}
			if (entry.referenced) {
				// touched since the last sweep - grant it a second chance and reset the flag
				entry.referenced = false;
			} else {
				theSlots[i] = null;
				dropped++;
			}
		}
		return dropped;
	}

	/**
	 * Sweeps every locale's cache — see {@link #sweep()}. This is the only entry point the rest of the engine needs:
	 * the caches themselves are an implementation detail of this package, but the events that should trigger their
	 * maintenance (a catalog leaving its bulk-indexing phase, a periodic housekeeping tick) are known only outside it.
	 *
	 * Safe to call at any time and from any thread: a cached key is a pure function of `(locale rules, string)`, so
	 * discarding one can only ever cost a recomputation, never change an ordering. Cost is one linear scan per locale
	 * — a million reference reads for the largest supported cache — so it belongs on a housekeeping path, never on
	 * a request path.
	 *
	 * **A single sweep reclaims almost nothing, by design.** {@link #keyFor(String)} marks an entry on every hit and
	 * one B+ tree descent re-reads its own probe several times, so practically every entry carries a set reference
	 * flag moments after it was inserted and survives on its second chance. Releasing memory therefore takes TWO
	 * sweeps with no intervening lookup: the first clears the flags, the second drops what stayed untouched between
	 * them. Measured on a 972k-article localized import — a lone sweep at the end of the import released 3 entries
	 * out of ~1M slots, while the same sweep preceded by one earlier sweep released ~215k. Callers wanting a bounded
	 * footprint must therefore sweep periodically; a single well-timed sweep is not a substitute.
	 *
	 * @return total number of entries dropped across all locales
	 */
	public static int sweepAll() {
		int dropped = 0;
		for (final CollationKeyCache cache : INSTANCES.values()) {
			dropped += cache.sweep();
		}
		return dropped;
	}

	/**
	 * A (value, collation key) pair plus the CLOCK reference flag consulted by {@link #sweep()}.
	 *
	 * `value` and `key` are final, which is what guarantees safe publication when the {@link #slots} elements are
	 * written without synchronization (see the class javadoc). {@link #referenced} is deliberately mutable and
	 * deliberately NOT volatile: it is a hint, read only by the sweeper, and a lost write merely costs the entry its
	 * second chance and therefore one recomputation. Writing it on a cache hit touches the cache line the reader has
	 * just loaded to compare `value`, so marking a hit is effectively free.
	 */
	private static final class CachedKey implements Serializable {
		@Serial private static final long serialVersionUID = 8543766319929827553L;
		/**
		 * The source string.
		 */
		@Nonnull private final String value;
		/**
		 * Its collation-key byte form.
		 */
		@Nonnull private final byte[] key;
		/**
		 * CLOCK reference flag: set on every read, cleared by {@link #sweep()}; an entry still clear at the next
		 * sweep is dropped. Racy by design — see the class javadoc on this type.
		 */
		private boolean referenced;

		CachedKey(@Nonnull String value, @Nonnull byte[] key) {
			this.value = value;
			this.key = key;
		}
	}

}
