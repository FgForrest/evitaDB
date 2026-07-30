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

package io.evitadb.core.cache;

import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.comparator.CollationKeyCache;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.utils.IOUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.util.concurrent.TimeUnit;

/**
 * Periodically decays the shared collation-key caches, so that the memory a bulk import needed is not retained for the
 * rest of the process lifetime.
 *
 * Those caches trade memory for the cost of the collation machinery: comparing two pre-computed collation keys is about
 * two orders of magnitude cheaper than `Collator.compare`, which is why a workload that compares nearly every distinct
 * value in a corpus - a bulk import, or a large transaction touching a sortable localized attribute - benefits from
 * a cache large enough to hold them. Measurement on a ~943k-value localized catalog: the cache is worth 1.37x on import
 * throughput and retains ~254 MB to earn it. Steady-state query serving compares a far smaller hot subset and has no
 * reason to keep paying for the import's footprint, so the configured cache size is treated as an upper bound rather
 * than a commitment: each sweep releases every key that has not been read since the previous one and grants the rest
 * a second chance, which makes the retained footprint follow the live working set.
 *
 * Two properties of the sweep are load-bearing:
 *
 * - **the period is time-based, not lookup-count-based**. A hand advanced by lookups would decay fastest exactly when
 *   the cache is most valuable (an import performs tens of millions of lookups) and stop advancing when the cache has
 *   gone cold - precisely backwards. A wall-clock period cannot invert like that;
 * - **an in-flight import is not a special case**. The CLOCK second chance already distinguishes keys the workload is
 *   still comparing from keys it has moved past, so a sweep landing in the middle of an import keeps what the import
 *   is using (most visibly the B+ tree separators, which are re-compared constantly) and drops only what it has
 *   finished with. No "skip while a catalog is warming up" guard is therefore needed.
 *
 * The caches are process-wide (keyed by locale) whereas an instance of this class belongs to a single
 * {@link io.evitadb.core.Evita} instance, so several embedded instances in one JVM multiply the sweep rate. That is
 * deliberately accepted: a sweep is idempotent and the entries are pure derived data, so the only consequence is that
 * keys decay sooner, and the alternative - a process-wide singleton sweeper - would need a process-wide lifecycle the
 * engine does not otherwise have.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see ServerOptions#dropCollationKeysAfterSecondsOfInactivity()
 * @see CollationKeyCache#sweepAll()
 */
@Slf4j
public class CollationKeyCacheSweeper implements Closeable {
	/**
	 * The task that periodically performs the sweep.
	 */
	private final DelayedAsyncTask sweepTask;

	/**
	 * Creates the sweeper and arms its periodic task immediately.
	 *
	 * @param inactivitySeconds how long a key may go uncompared before it is released, which is also the interval
	 *                          between two sweeps; must be positive (the caller is expected to skip creating the
	 *                          sweeper altogether when retention is unbounded)
	 * @param scheduler         scheduler used to plan the periodic execution
	 */
	public CollationKeyCacheSweeper(int inactivitySeconds, @Nonnull Scheduler scheduler) {
		this.sweepTask = new DelayedAsyncTask(
			null,
			"Collation key cache decay",
			scheduler,
			() -> {
				sweep();
				// return 0 to reschedule with the full default delay
				return 0L;
			},
			inactivitySeconds,
			TimeUnit.SECONDS
		);
		this.sweepTask.schedule();
	}

	/**
	 * Performs a single sweep of every locale's collation-key cache. Exposed separately from the periodic task so that
	 * the decay can be triggered on demand and asserted in tests.
	 *
	 * @return number of cached collation keys released
	 */
	public int sweep() {
		final int released = CollationKeyCache.sweepAll();
		if (released > 0) {
			log.debug("Released {} cached collation keys that were not used since the last sweep.", released);
		}
		return released;
	}

	/**
	 * Stops the periodic scheduling. Direct calls to {@link #sweep()} remain functional after close.
	 */
	@Override
	public void close() {
		IOUtils.closeQuietly(this.sweepTask::close);
	}
}
