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

package io.evitadb.performance.substring.state;

import io.evitadb.core.Evita;
import io.evitadb.core.cache.CacheAnteroom;
import io.evitadb.core.cache.CacheEden;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.cache.HeapMemoryCacheSupervisor;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads the formula cache's admission counters out of a booted {@link Evita}, so a latency ratio can be told apart
 * from a cache that was never populated.
 *
 * # Why this exists
 *
 * `SubstringCacheRepeatBenchmark` exists to answer whether the eager fold's one advantage - that its result is a
 * `CacheableFormula` - is actually realised. A measured ratio of 1.0 between the cache-enabled and cache-disabled arms
 * has two completely different readings: *the cache does not help here*, or *the formula never entered the cache*.
 * Without an admission count the benchmark cannot distinguish them, and reporting the ratio alone would be reporting
 * a null result as evidence.
 *
 * # Why reflection
 *
 * {@link CacheEden} publishes {@link CacheEden#getCacheRecordCount()} and {@link CacheEden#getByteSizeUsedByCache()},
 * and its hit/miss counters are private; what has no accessor at all is the *path to the eden* -
 * {@link Evita} keeps its {@link CacheSupervisor} private and {@link HeapMemoryCacheSupervisor} keeps its
 * {@link CacheAnteroom} private. The alternative to walking those three fields is a visibility change in the engine,
 * which this benchmark may not make: the suite exists to measure the shipped code, and widening a field for a
 * benchmark would change the thing being measured into a thing that only exists for benchmarks.
 *
 * The walk is therefore deliberately brittle and **fails loudly**: a renamed field throws rather than degrading into
 * "no cache observed", which is the exact false negative this class was written to prevent.
 *
 * # Gauges and interval counters are not the same thing here
 *
 * `CacheEden#evaluateAdepts` **zeroes** its hit, miss and initialised counters at the end of every re-evaluation
 * cycle, right after reporting them to the observability subsystem. They are therefore *interval* counters covering
 * roughly the last `reevaluateEachSeconds`, not totals - a reading of `initialized=0` says nothing about whether a
 * record was ever initialised, only that none was during the last cycle, and a later reading can be *smaller* than an
 * earlier one.
 *
 * Only {@link #getCacheRecordCount()} and {@link #getCacheSizeInBytes()} are live gauges, so
 * **{@link #getCacheRecordCount()} is the admission signal**; the counters say whether the cache was being *used* at
 * the moment they were read, which is why the benchmark samples them once per JMH iteration rather than once.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class CacheAdmissionProbe {

	/**
	 * The eden of the observed instance, or `null` when the instance runs without a cache at all.
	 */
	@Nullable private final CacheEden cacheEden;

	/**
	 * The eden's private hit counter, or `null` when there is no eden. Zeroed at every re-evaluation cycle, so it
	 * counts hits within roughly the last `reevaluateEachSeconds`.
	 */
	@Nullable private final AtomicLong hits;

	/**
	 * The eden's private miss counter, or `null` when there is no eden. Zeroed at every re-evaluation cycle.
	 */
	@Nullable private final AtomicLong misses;

	/**
	 * The eden's private counter of records whose payload was computed and stored, or `null` when there is no eden.
	 * Zeroed at every re-evaluation cycle, so a zero reading is **not** evidence that nothing was ever cached - see
	 * {@link #getCacheRecordCount()} for that.
	 */
	@Nullable private final AtomicLong initialized;

	/**
	 * Adopts an already-resolved eden.
	 *
	 * @param cacheEden   the eden, or `null` when the instance has no cache
	 * @param hits        the eden's hit counter, or `null`
	 * @param misses      the eden's miss counter, or `null`
	 * @param initialized the eden's initialized-record counter, or `null`
	 */
	private CacheAdmissionProbe(
		@Nullable CacheEden cacheEden,
		@Nullable AtomicLong hits,
		@Nullable AtomicLong misses,
		@Nullable AtomicLong initialized
	) {
		this.cacheEden = cacheEden;
		this.hits = hits;
		this.misses = misses;
		this.initialized = initialized;
	}

	/**
	 * Walks `Evita -> CacheSupervisor -> CacheAnteroom -> CacheEden` and binds the eden's counters.
	 *
	 * @param evita the booted instance to observe
	 * @return a probe over that instance's cache, or a probe reporting no cache when it runs uncached
	 * @throws GenericEvitaInternalError when the field chain no longer matches the engine
	 */
	@Nonnull
	public static CacheAdmissionProbe of(@Nonnull Evita evita) {
		final Object supervisor = readField(evita, Evita.class, "cacheSupervisor");
		if (supervisor instanceof NoCacheSupervisor) {
			return new CacheAdmissionProbe(null, null, null, null);
		}
		if (!(supervisor instanceof final HeapMemoryCacheSupervisor heapSupervisor)) {
			throw new GenericEvitaInternalError(
				"The instance's cache supervisor is a `" + supervisor.getClass().getName() + "`, which this probe "
					+ "cannot read - a new supervisor implementation was introduced and the probe was not updated!",
				"Unknown cache supervisor implementation!"
			);
		}
		final Object anteroom = readField(heapSupervisor, HeapMemoryCacheSupervisor.class, "cacheAnteroom");
		final Object eden = readField(anteroom, CacheAnteroom.class, "cacheEden");
		if (!(eden instanceof final CacheEden cacheEden)) {
			throw new GenericEvitaInternalError(
				"The anteroom's eden is a `" + eden.getClass().getName() + "` rather than a `CacheEden`!",
				"Unexpected cache eden implementation!"
			);
		}
		return new CacheAdmissionProbe(
			cacheEden,
			(AtomicLong) readField(cacheEden, CacheEden.class, "hits"),
			(AtomicLong) readField(cacheEden, CacheEden.class, "misses"),
			(AtomicLong) readField(cacheEden, CacheEden.class, "initialized")
		);
	}

	/**
	 * @return whether the observed instance runs a formula cache at all
	 */
	public boolean isCachePresent() {
		return this.cacheEden != null;
	}

	/**
	 * The live count of records the eden holds - the one reading that is a gauge rather than an interval counter, and
	 * therefore the admission signal: a non-zero value means a formula was promoted into the cache and is still there.
	 *
	 * @return how many records the eden currently holds, or `0` when there is no cache
	 */
	public int getCacheRecordCount() {
		return this.cacheEden == null ? 0 : this.cacheEden.getCacheRecordCount();
	}

	/**
	 * @return how many bytes the eden estimates it occupies, or `0` when there is no cache
	 */
	public long getCacheSizeInBytes() {
		return this.cacheEden == null ? 0L : this.cacheEden.getByteSizeUsedByCache();
	}

	/**
	 * @return how many lookups found a usable cached result during the current re-evaluation interval, or `0` when
	 * there is no cache
	 */
	public long getIntervalHits() {
		return this.hits == null ? 0L : this.hits.get();
	}

	/**
	 * @return how many lookups found nothing usable during the current re-evaluation interval, or `0` when there is
	 * no cache
	 */
	public long getIntervalMisses() {
		return this.misses == null ? 0L : this.misses.get();
	}

	/**
	 * How many promoted records had their payload computed and stored during the current re-evaluation interval. A
	 * promoted record is initialised exactly once, so this is nearly always zero on a steady-state read even when the
	 * cache is fully warm - {@link #getCacheRecordCount()} is the admission signal, not this.
	 *
	 * @return how many cached records were initialised during the current interval, or `0` when there is no cache
	 */
	public long getIntervalInitializedRecords() {
		return this.initialized == null ? 0L : this.initialized.get();
	}

	/**
	 * @return a one-line, log-friendly rendering of every reading this probe can take, gauges and interval counters
	 * labelled apart
	 */
	@Nonnull
	public String describe() {
		if (this.cacheEden == null) {
			return "cache=absent (NoCacheSupervisor - nothing is admitted, nothing is reused)";
		}
		return "cache=present records=" + getCacheRecordCount()
			+ " bytes=" + getCacheSizeInBytes()
			+ " [interval] hits=" + getIntervalHits()
			+ " misses=" + getIntervalMisses()
			+ " initialized=" + getIntervalInitializedRecords();
	}

	/**
	 * Reads one private field, refusing to guess when it is not there.
	 *
	 * @param instance      the object to read from
	 * @param declaringType the class declaring the field
	 * @param fieldName     the field's name
	 * @return the field's value
	 * @throws GenericEvitaInternalError when the field is absent or unreadable
	 */
	@Nonnull
	private static Object readField(
		@Nonnull Object instance,
		@Nonnull Class<?> declaringType,
		@Nonnull String fieldName
	) {
		try {
			final Field field = declaringType.getDeclaredField(fieldName);
			field.setAccessible(true);
			final Object value = field.get(instance);
			if (value == null) {
				throw new GenericEvitaInternalError(
					"`" + declaringType.getName() + "#" + fieldName + "` is null - the cache is not wired the way "
						+ "this probe expects!",
					"A cache field the admission probe reads is null!"
				);
			}
			return value;
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new GenericEvitaInternalError(
				"`" + declaringType.getName() + "#" + fieldName + "` cannot be read (" + e.getMessage() + ") - the "
					+ "engine's cache wiring changed and the admission probe must be updated, because without it a "
					+ "cache benchmark cannot tell `the cache does not help` from `nothing was ever cached`!",
				"The cache admission probe cannot reach the cache!",
				e
			);
		}
	}

}
