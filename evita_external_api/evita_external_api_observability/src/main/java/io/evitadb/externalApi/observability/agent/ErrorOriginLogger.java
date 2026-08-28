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
 * ## Why nothing here may throw
 *
 * Every method runs inside an exception constructor, from Byte Buddy advice, on an object that is not yet fully
 * constructed. A throwable escaping from here would surface from `new SomeException(...)` at an arbitrary call site
 * that has no way to handle it, so the whole body is wrapped in a `catch (Throwable)` and only
 * {@link EvitaError#getErrorCode()} and {@link Throwable#getStackTrace()} - neither of which reads subclass state -
 * are called on the exception.
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
	 * Currently effective mode. Written once from {@link #configure(ErrorOriginLogging)} when the observability
	 * manager starts, read on every error construction - hence `volatile`.
	 *
	 * Defaults to {@link ErrorOriginLogging#INTERNAL} rather than to "off", because the consumers this class serves
	 * are registered from a static initializer while the configuration only arrives with the manager's constructor:
	 * anything constructed in between must fall on the useful side, not into a blind spot.
	 */
	private static volatile ErrorOriginLogging mode = ErrorOriginLogging.INTERNAL;

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
	 * Counts one occurrence of the error's origin and logs it when it is either new or has just reached a power of
	 * ten. Never propagates a failure - see the class comment for why that is not merely defensive.
	 *
	 * @param error    the exception being constructed
	 * @param category human-readable error category used in the log message
	 */
	private static void report(@Nonnull Throwable error, @Nonnull String category) {
		try {
			if (!(error instanceof EvitaError evitaError)) {
				return;
			}
			final String origin = evitaError.getErrorCode();
			final AtomicLong counter = OCCURRENCES_BY_ORIGIN.get(origin);
			if (counter == null) {
				// a new origin - admit it unless the cap has been reached, and log it with its stack trace
				if (OCCURRENCES_BY_ORIGIN.size() >= MAX_TRACKED_ORIGINS) {
					return;
				}
				final AtomicLong existing = OCCURRENCES_BY_ORIGIN.putIfAbsent(origin, new AtomicLong(1L));
				if (existing == null) {
					logOrigin(error, category, origin, 1L);
				} else {
					existing.incrementAndGet();
				}
			} else {
				final long occurrences = counter.incrementAndGet();
				if (isPowerOfTen(occurrences)) {
					logOrigin(error, category, origin, occurrences);
				}
			}
		} catch (Throwable ignored) {
			// this runs inside an exception constructor: letting anything escape would replace an ordinary,
			// handleable failure with one thrown from `new SomeException(...)`, which no call site can recover
			// from. Losing a diagnostic line is the only acceptable outcome here.
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
