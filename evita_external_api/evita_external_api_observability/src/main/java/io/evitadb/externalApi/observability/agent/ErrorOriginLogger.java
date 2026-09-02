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

package io.evitadb.externalApi.observability.agent;

import io.evitadb.exception.EvitaError;
import io.evitadb.externalApi.observability.configuration.ErrorOriginLogging;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes the *place an evitaDB error was created* to the log, once per distinct place.
 *
 * `io_evitadb_errors_total` counts an exception being constructed and is labelled with nothing but the class name,
 * so an exception that is built and discarded - or thrown at a caller that has already gone away - moves the counter
 * and leaves no other trace at all. This class is what turns such a movement into a location somebody can open.
 *
 * ## What gets logged, and how often
 *
 * The first time an origin is seen it is logged at WARN together with the full stack trace. After that the origin is
 * only counted, and re-logged when its count reaches a power of ten (10, 100, 1000, …) - enough to show that
 * something is escalating, without a per-occurrence cost or a flooded log. Origins are identified by
 * {@link EvitaError#getErrorCode()}, which is derived from the creating frame and already cached on the exception.
 *
 * ## Why nothing here may throw, or recurse
 *
 * Every method runs inside an exception constructor, from Byte Buddy advice, on an object that is not yet fully
 * constructed. A throwable escaping from here would surface from `new SomeException(...)` at an arbitrary call site
 * that has no way to handle it, so the whole body is wrapped in a `catch (Throwable)` and only
 * {@link EvitaError#getErrorCode()} and {@link Throwable#getStackTrace()} - neither of which reads subclass state -
 * are called on the exception.
 *
 * The same position makes re-entrancy fatal rather than merely wasteful: an evitaDB error constructed anywhere below
 * this method would be reported, re-entering it, until the stack runs out. A thread-local guard drops the nested
 * report, so the safety of this class does not rest on a promise about what the logging framework - or a future
 * version of this method - happens to construct.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public class ErrorOriginLogger {
	/**
	 * Upper bound on the number of distinct origins tracked. Origins are bounded in practice by the number of
	 * `throw` sites in the codebase, but an error code may also arrive from the wire on an exception rebuilt by the
	 * client driver, and that is client-supplied data - so the map is capped rather than trusted. Once the cap is
	 * reached no new origin is admitted; those already known keep counting.
	 */
	static final int MAX_TRACKED_ORIGINS = 1024;

	/**
	 * Occurrence count per origin (see {@link EvitaError#getErrorCode()}).
	 */
	private static final Map<String, AtomicLong> OCCURRENCES_BY_ORIGIN = new ConcurrentHashMap<>(256);

	/**
	 * Number of slots handed out in {@link #OCCURRENCES_BY_ORIGIN}. Kept separately from the map's own `size()`
	 * because a slot has to be *reserved before* the insert: checking `size()` and then inserting is two operations,
	 * and enough threads reporting distinct new origins at once would all pass the check and all insert, pushing the
	 * map past its cap. The map guards against error codes arriving from the wire, so it has to hold under exactly
	 * that kind of concurrent arrival.
	 */
	private static final AtomicInteger RESERVED_ORIGIN_SLOTS = new AtomicInteger();

	/**
	 * Currently effective mode. Written once from {@link #configure(ErrorOriginLogging)} when the observability
	 * manager starts, read on every error construction - hence `volatile`.
	 *
	 * Defaults to {@link ErrorOriginLogging#INTERNAL} rather than to "off", because the consumers this class serves
	 * are registered from a static initializer while the configuration only arrives with the manager's constructor:
	 * anything constructed in between must fall on the useful side, not into a blind spot.
	 */
	private static volatile ErrorOriginLogging mode = ErrorOriginLogging.INTERNAL;

	/**
	 * Marks the thread as being inside {@link #report}, so an evitaDB error constructed further down - by the
	 * logging framework, an appender, or anything this method comes to call in future - is dropped rather than
	 * recursing back in. Without it the safety of this class would rest on the claim that nothing below ever
	 * constructs an evitaDB error, which is a claim about code that has not been written yet.
	 *
	 * `ErrorMonitor` carries an equivalent guard one level further out, which is what covers the *whole* consumer
	 * rather than only the part of it that runs after this point. This one is not therefore redundant: these
	 * methods are public and called directly, and a class in this position should not depend on its caller having
	 * guarded for it.
	 */
	private static final ThreadLocal<Boolean> REPORTING = ThreadLocal.withInitial(() -> Boolean.FALSE);

	/**
	 * Applies the configured mode. Called by the observability manager on start-up.
	 *
	 * @param errorOriginLogging mode to apply
	 */
	public static void configure(@Nonnull ErrorOriginLogging errorOriginLogging) {
		mode = errorOriginLogging;
	}

	/**
	 * Reports the origin of an internal error - a fault inside evitaDB itself.
	 *
	 * @param error the exception being constructed; may be partially initialised
	 */
	public static void reportInternalError(@Nonnull Throwable error) {
		if (mode != ErrorOriginLogging.NONE) {
			report(error, "internal");
		}
	}

	/**
	 * Reports the origin of a client error - a request evitaDB rejected. Only active in
	 * {@link ErrorOriginLogging#ALL}, because these are raised on ordinary rejection paths.
	 *
	 * @param error the exception being constructed; may be partially initialised
	 */
	public static void reportClientError(@Nonnull Throwable error) {
		if (mode == ErrorOriginLogging.ALL) {
			report(error, "client");
		}
	}

	/**
	 * Resets all tracked origins. Exists for tests, which would otherwise see each other's first-occurrence state.
	 */
	static void reset() {
		OCCURRENCES_BY_ORIGIN.clear();
		RESERVED_ORIGIN_SLOTS.set(0);
	}

	/**
	 * Returns how many times the given origin has been reported since the last {@link #reset()}. For tests.
	 *
	 * @param origin error code identifying the origin
	 * @return number of occurrences, zero when the origin was never seen
	 */
	static long occurrencesOf(@Nonnull String origin) {
		final AtomicLong counter = OCCURRENCES_BY_ORIGIN.get(origin);
		return counter == null ? 0L : counter.get();
	}

	/**
	 * Returns how many distinct origins are currently tracked. For tests asserting the cap holds.
	 *
	 * @return number of tracked origins, never above {@link #MAX_TRACKED_ORIGINS}
	 */
	static int trackedOriginCount() {
		return OCCURRENCES_BY_ORIGIN.size();
	}

	/**
	 * Counts one occurrence of the error's origin and logs it when it is either new or has just reached a power of
	 * ten. Never propagates a failure - see the class comment for why that is not merely defensive.
	 *
	 * @param error    the exception being constructed
	 * @param category human-readable error category used in the log message
	 */
	private static void report(@Nonnull Throwable error, @Nonnull String category) {
		if (Boolean.TRUE.equals(REPORTING.get())) {
			// an evitaDB error was constructed while this method was reporting one, on this very thread. Reporting
			// it too would re-enter here again, and the recursion would end in a StackOverflowError raised from
			// inside a constructor. Correctness must not rest on "nothing below ever constructs an error".
			return;
		}
		REPORTING.set(Boolean.TRUE);
		try {
			if (!(error instanceof EvitaError evitaError)) {
				return;
			}
			final String origin = evitaError.getErrorCode();
			final AtomicLong counter = OCCURRENCES_BY_ORIGIN.get(origin);
			if (counter == null) {
				admitNewOrigin(error, category, origin);
			} else {
				countKnownOrigin(error, category, origin, counter);
			}
		} catch (Throwable ignored) {
			// this runs inside an exception constructor: letting anything escape would replace an ordinary,
			// handleable failure with one thrown from `new SomeException(...)`, which no call site can recover
			// from. Losing a diagnostic line is the only acceptable outcome here.
		} finally {
			REPORTING.set(Boolean.FALSE);
		}
	}

	/**
	 * Records the first occurrence of an origin, provided the tracking cap has not been reached.
	 *
	 * The slot is reserved before the insert so the map can never exceed {@link #MAX_TRACKED_ORIGINS}, and given
	 * back if another thread won the race for the same origin.
	 *
	 * @param error    the exception being constructed
	 * @param category human-readable error category
	 * @param origin   error code identifying the origin
	 */
	private static void admitNewOrigin(@Nonnull Throwable error, @Nonnull String category, @Nonnull String origin) {
		int reserved;
		do {
			reserved = RESERVED_ORIGIN_SLOTS.get();
			if (reserved >= MAX_TRACKED_ORIGINS) {
				return;
			}
		} while (!RESERVED_ORIGIN_SLOTS.compareAndSet(reserved, reserved + 1));

		final AtomicLong existing = OCCURRENCES_BY_ORIGIN.putIfAbsent(origin, new AtomicLong(1L));
		if (existing == null) {
			logOrigin(error, category, origin, 1L);
		} else {
			// another thread inserted this same origin first - hand the reserved slot back rather than burning it,
			// and count through the same path a known origin takes, so a lost race cannot swallow a milestone line
			RESERVED_ORIGIN_SLOTS.decrementAndGet();
			countKnownOrigin(error, category, origin, existing);
		}
	}

	/**
	 * Counts one more occurrence of an origin that is already tracked, logging it again at every power of ten.
	 *
	 * @param error    the exception being constructed
	 * @param category human-readable error category
	 * @param origin   error code identifying the origin
	 * @param counter  the origin's occurrence counter
	 */
	private static void countKnownOrigin(
		@Nonnull Throwable error,
		@Nonnull String category,
		@Nonnull String origin,
		@Nonnull AtomicLong counter
	) {
		final long occurrences = counter.incrementAndGet();
		if (isPowerOfTen(occurrences)) {
			logOrigin(error, category, origin, occurrences);
		}
	}

	/**
	 * Emits the log line describing where an error was created.
	 *
	 * @param error       the exception being constructed, passed so the logger renders its stack trace
	 * @param category    human-readable error category
	 * @param origin      error code identifying the origin
	 * @param occurrences number of times this origin has been seen so far
	 */
	private static void logOrigin(
		@Nonnull Throwable error,
		@Nonnull String category,
		@Nonnull String origin,
		long occurrences
	) {
		if (log.isWarnEnabled()) {
			log.warn(
				"evitaDB {} error `{}` was created at a newly observed place (error code `{}`, seen {}x). This is " +
					"reported because the error metric counts construction and cannot say where it happened - the " +
					"error may well have been handled, so treat the stack trace as a location, not as a failure.",
				category, error.getClass().getSimpleName(), origin, occurrences, error
			);
		}
	}

	/**
	 * Tells whether the passed count is a power of ten, which is when an already-known origin is logged again.
	 *
	 * @param value count to test, always positive
	 * @return true when the value is 1, 10, 100, …
	 */
	private static boolean isPowerOfTen(long value) {
		long remaining = value;
		while (remaining >= 10L && remaining % 10L == 0L) {
			remaining /= 10L;
		}
		return remaining == 1L;
	}

}
