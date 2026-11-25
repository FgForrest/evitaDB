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

package io.evitadb.externalApi.observability.metric;

import io.evitadb.api.CatalogState;
import io.evitadb.core.Evita;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.externalApi.api.system.ProbesProvider.CatalogRoutingSettlement;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Evaluates initial catalog routing and latches the result once every catalog has settled.
 * Only pending publications of active catalogs are subject to the timeout.
 *
 * {@link ObservabilityProbesDetector} creates one instance lazily, and both the HTTP readiness endpoint and the gRPC
 * server status reach it through the detector. A lock serializes scans. The private settlement latch completes
 * outside that lock so settlement actions run with the lock released.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@ThreadSafe
public final class CatalogRoutingReadiness {
	/** Monotonic time source used to measure pending publication. */
	private final LongSupplier nanoTime;
	/** Publication budget for a catalog after it first appears active and unpublished. */
	private final long timeoutNanos;
	/** Serializes scans and guards the bookkeeping for pending publications. */
	private final ReentrantLock scanLock = new ReentrantLock();
	/** Private latch that triggers settlement actions once a scan finds no pending catalogs. */
	private final CompletableFuture<Void> settled = new CompletableFuture<>();
	/** First observation time, in nanoseconds, for each active catalog awaiting publication. Guarded by `scanLock`. */
	private final Map<String, Long> firstPendingAt = CollectionUtils.createHashMap(20);
	/** API codes already logged as overdue for each catalog. Guarded by `scanLock`. */
	private final Map<String, Set<String>> overdueApis = CollectionUtils.createHashMap(20);
	/**
	 * Records that a scan found no pending catalogs, before settlement actions run outside the scan lock. Guarded by
	 * `scanLock`.
	 */
	private boolean allCatalogsSettled;

	/**
	 * Creates a startup scan using a monotonic clock.
	 *
	 * @param nanoTime source of monotonic time in nanoseconds
	 * @param timeoutInMillis maximum time to wait for an active catalog's route publication
	 */
	public CatalogRoutingReadiness(@Nonnull LongSupplier nanoTime, long timeoutInMillis) {
		this.nanoTime = nanoTime;
		this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutInMillis);
	}

	/**
	 * Scans catalog states and the routing obligations of all registered API providers, and latches settlement when
	 * nothing is pending. A transitional catalog is pending without a time limit. An active catalog is pending while
	 * some provider owes its publication and less than the timeout has passed since a scan first found it owed; after
	 * that, each overdue API is logged once as an error and stops blocking. A catalog that is neither transitional nor
	 * active never blocks. Once a scan finds nothing pending, it completes the private latch outside the scan lock,
	 * and every later call returns the settled outcome without scanning.
	 *
	 * @param evita engine supplying catalog state
	 * @param providers registered API providers
	 * @return whether startup catalog routing has settled and the number of catalogs still pending
	 */
	@Nonnull
	public CatalogRoutingSettlement evaluateCatalogRouting(
		@Nonnull Evita evita,
		@Nonnull Collection<ExternalApiProvider<?>> providers
	) {
		this.scanLock.lock();
		try {
			if (!this.allCatalogsSettled) {
				final long now = this.nanoTime.getAsLong();
				int pending = 0;
				final ExpandedEngineState engineState = evita.getEngineState();
				final Set<String> catalogNames = engineState.catalogs().keySet();
				for (final String name : catalogNames) {
					final CatalogState state = engineState.getCatalog(name).orElseThrow().getCatalogState();
					if (state.isTransitional()) {
						this.firstPendingAt.remove(name);
						pending++;
					} else if (state.isActive() && this.isPublicationPending(name, providers, now)) {
						pending++;
					} else if (!state.isActive()) {
						this.firstPendingAt.remove(name);
					}
				}
				if (pending > 0) {
					this.firstPendingAt.keySet().retainAll(catalogNames);
					return new CatalogRoutingSettlement(false, pending);
				}
				this.allCatalogsSettled = true;
				this.firstPendingAt.clear();
				this.overdueApis.clear();
			}
		} finally {
			this.scanLock.unlock();
		}
		// Completing the latch can run settlement actions inline.
		this.settled.complete(null);
		return new CatalogRoutingSettlement(true, 0);
	}

	/**
	 * Runs an action when the internal startup latch completes normally.
	 *
	 * @param action action to run after catalog routing settles
	 */
	void whenSettled(@Nonnull Runnable action) {
		this.settled.thenRun(action);
	}

	/**
	 * Checks whether an active catalog is still within its publication budget.
	 *
	 * @param catalogName name of the active catalog
	 * @param providers registered API providers
	 * @param now time of the scan in nanoseconds
	 * @return true if at least one API still owes publication and the budget has not expired
	 */
	private boolean isPublicationPending(
		@Nonnull String catalogName, @Nonnull Collection<ExternalApiProvider<?>> providers, long now
	) {
		boolean unpublished = false;
		boolean pending = false;
		for (final ExternalApiProvider<?> provider : providers) {
			if (!provider.isCatalogRoutingSettled(catalogName)) {
				unpublished = true;
				final long firstSeen = this.firstPendingAt.computeIfAbsent(catalogName, ignored -> now);
				if (now - firstSeen < this.timeoutNanos) {
					pending = true;
				} else if (this.overdueApis.computeIfAbsent(
					catalogName, ignored -> CollectionUtils.createHashSet(providers.size())
				).add(provider.getCode())) {
					log.error(
						"API `{}` has not finished activating catalog `{}` within " +
							"readinessCatalogActivationTimeoutInMillis; readiness no longer waits for it.",
						provider.getCode(), catalogName
					);
				}
			}
		}
		if (!unpublished) {
			this.firstPendingAt.remove(catalogName);
		}
		return pending;
	}
}
