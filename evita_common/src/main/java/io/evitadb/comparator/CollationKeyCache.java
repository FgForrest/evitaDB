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
 * - **immutable entries, benign races** - {@link CachedKey} is a record (all fields final), so a
 *   racy read of a concurrently published entry is safe under the JMM final-field guarantee; the
 *   worst outcome of a lost concurrent write is a redundant recomputation;
 * - **per-thread collators** - `RuleBasedCollator.getCollationKey` is `synchronized`, therefore
 *   each thread computes keys on its own {@link Collator} instance, keeping cache misses
 *   monitor-free under concurrent indexing;
 * - **memory bound** - `slots` entries per locale (default 8192, roughly 2 MB warm for typical
 *   attribute value lengths), created lazily only for locales that actually collate; the system
 *   property `evita.collationKeyCache.size` resizes the cache (values round down to a power of
 *   two) and `0` disables it entirely, making comparators delegate straight to
 *   {@link Collator#compare(String, String)}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class CollationKeyCache implements Serializable {
	@Serial private static final long serialVersionUID = 2359981769309770696L;

	/**
	 * Name of the system property controlling the per-locale slot count.
	 */
	static final String SIZE_PROPERTY = "evita.collationKeyCache.size";
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
	 * Cached entries; elements are written with plain stores (see the class javadoc on benign
	 * races) and are either null or fully initialized immutable records.
	 */
	@Nonnull private final CachedKey[] slots;
	/**
	 * Bit mask selecting a slot index from a hash (`SIZE - 1`).
	 */
	private final int mask;
	/**
	 * Per-thread collator of this cache's locale used to compute keys on cache misses.
	 */
	@SuppressWarnings("TransientFieldNotInitialized")
	@Nonnull private final transient ThreadLocal<Collator> collator;

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
	 * selection can use bit masking, and capping it to keep the worst-case footprint sane.
	 *
	 * @return slot count per locale; zero when caching is disabled
	 */
	private static int resolveSize() {
		final int configured = Integer.getInteger(SIZE_PROPERTY, 8192);
		return configured <= 0 ? 0 : Integer.highestOneBit(Math.min(configured, 1 << 20));
	}

	/**
	 * Creates the cache for a single locale; invoked lazily through {@link #forLocale(Locale)}.
	 *
	 * @param locale locale whose default collator order this cache serves
	 */
	private CollationKeyCache(@Nonnull Locale locale) {
		this.slots = new CachedKey[SIZE];
		this.mask = SIZE - 1;
		// Collator.getInstance returns a fresh clone per call, so each thread gets its own instance
		this.collator = ThreadLocal.withInitial(() -> Collator.getInstance(locale));
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
			return primaryEntry.key;
		}
		final int secondary = (hash >>> 16) & this.mask;
		final CachedKey secondaryEntry = this.slots[secondary];
		// intentional identity fast path (see the note on the primary slot above)
		//noinspection StringEquality,ConditionCoveredByFurtherCondition
		if (secondaryEntry != null && (secondaryEntry.value == value || secondaryEntry.value.equals(value))) {
			return secondaryEntry.key;
		}
		final byte[] key = this.collator.get().getCollationKey(value).toByteArray();
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
	 * Immutable (value, collation key) pair; record finality guarantees safe publication even when
	 * the {@link #slots} elements are written without synchronization.
	 *
	 * @param value the source string
	 * @param key   its collation-key byte form
	 */
	private record CachedKey(
		@Nonnull String value,
		@Nonnull byte[] key
	) implements Serializable {
	}

}
