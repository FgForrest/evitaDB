/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.http;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Helper used by {@link ExternalApiProvider#isReady()} implementations that probe several candidate URLs before
 * settling on the one that actually works. Individual candidate failures are expected while none of them has been
 * proven reachable yet (e.g. a publicly exposed hostname that doesn't route back to the container that just booted),
 * so they are not worth an alarming log line on their own. This tracker instead recognizes the case where an entire
 * discovery round has failed for longer than a reasonable grace period, and reports it exactly once so that a server
 * that never becomes ready produces a single warning instead of flooding the log on every subsequent probe.
 *
 * Instances are shared by a single provider across concurrent readiness probes (e.g. an overlapping Docker health
 * check and Kubernetes readiness probe), so state is held in atomics rather than plain fields to keep the
 * exactly-once guarantee correct under concurrent calls.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ReadinessDiscoveryStallTracker {

	/**
	 * How long a discovery phase (no candidate URL proven reachable yet) may run before it's considered stalled
	 * rather than merely still starting up.
	 */
	public static final Duration GRACE_PERIOD = Duration.ofSeconds(60);

	private final long gracePeriodMillis;
	private final AtomicLong firstAttemptMillis = new AtomicLong(-1L);
	private final AtomicBoolean stalledWarningLogged = new AtomicBoolean(false);

	/**
	 * Uses the default {@link #GRACE_PERIOD}.
	 */
	public ReadinessDiscoveryStallTracker() {
		this(GRACE_PERIOD);
	}

	/**
	 * Allows callers (and tests) to use a different grace period than the default {@link #GRACE_PERIOD}.
	 */
	public ReadinessDiscoveryStallTracker(@Nonnull Duration gracePeriod) {
		this.gracePeriodMillis = gracePeriod.toMillis();
	}

	/**
	 * Call once after an entire discovery round has exhausted all candidate URLs without success. Returns TRUE
	 * exactly once - the first time the grace period has elapsed since the first recorded exhausted round - so the
	 * caller can log a single consolidated warning instead of repeating it on every subsequent round.
	 *
	 * @return TRUE if the caller should log a stalled-discovery warning now
	 */
	public boolean shouldWarnAboutStall() {
		final long now = System.currentTimeMillis();
		this.firstAttemptMillis.compareAndSet(-1L, now);
		if (now - this.firstAttemptMillis.get() >= this.gracePeriodMillis) {
			return this.stalledWarningLogged.compareAndSet(false, true);
		}
		return false;
	}

}
