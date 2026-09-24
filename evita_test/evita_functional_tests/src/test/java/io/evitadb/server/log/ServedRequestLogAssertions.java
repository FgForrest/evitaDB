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

package io.evitadb.server.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.evitadb.api.observability.trace.TracingContext;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that a line logged while a **real** HTTP request is being served carries {@code duration_ms} — the whole
 * chain, from the request arriving on Armeria's event loop to a line rendered by {@link AppLogJsonLayout}.
 *
 * Every hop of that chain is unit-tested on its own; this is what proves they are joined up. It is worth doing end
 * to end because of *where* the JSON APIs log: both write their "Generated evitaDB query" line from inside the task
 * submitted to evitaDB's request thread pool, and that task is constructed from Armeria's body-aggregation callback
 * — after `serve(...)` has returned, on a thread carrying neither a request context nor the entry point's MDC
 * scope. That is the exact hand-off at which the request start is lost if
 * {@code EndpointExecutionContext} stops scoping its submissions, and no mock of it is as convincing as the real
 * thing.
 *
 * **What this covers, and what it deliberately leaves to the unit tests.** Functional datasets collapse evitaDB's
 * request pool into an immediate executor, so that parallel runs on a starved CPU cannot go flaky on an asynchronous
 * hand-off ({@code DataSet#useRealThreadPools}). The submitted work therefore runs inline on the thread that
 * submitted it — which is still the body-aggregation callback, with the entry point's MDC scope already unwound, so
 * removing the scope from {@code EndpointExecutionContext} does make these tests fail. What is *not* covered here is
 * the second half of the hand-off, restoring the captured context on a pooled worker; that is covered against a real
 * {@code ObservableThreadExecutor} by {@code TracingContextRequestStartTest}. Opting these datasets into real pools
 * would cover both halves, but their catalogs are filled *after* the web API has started and the API's schema rebuild
 * is asynchronous under real pools, so the probe request would race it — the flakiness that annotation warns about.
 *
 * Lives here, beside {@link AppLogJsonLayoutTest}, because the claim is about what the layout can report; the GraphQL
 * and REST suites each own the request that provokes the line, since each has to be built on its own API's dataset.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ServedRequestLogAssertions {

	/**
	 * Primary key no dataset contains and no other test filters on, so the line a probe request provoked can be told
	 * apart from the lines of any test running beside it — both APIs log the evitaDB query they generated, and the
	 * key appears in it verbatim.
	 */
	public static final int PROBE_PRIMARY_KEY = 987_654_321;
	/**
	 * The probe as it is written in the logged query, which is what the captured lines are filtered by.
	 */
	private static final String PROBE_TOKEN = Integer.toString(PROBE_PRIMARY_KEY);
	/**
	 * Upper bound on how long the probe request may plausibly have been in flight when the line was written. Wide
	 * enough that a loaded CI machine cannot fail it, narrow enough that a request start left over from an earlier
	 * request — or a value that is not a request start at all — cannot pass it.
	 */
	private static final long PLAUSIBLE_REQUEST_LIFETIME_MS = 300_000L;

	private ServedRequestLogAssertions() {
	}

	/**
	 * Runs {@code request} with a recording appender attached to the logger of the class that logs while serving it,
	 * and asserts that what it logged carries the start of the request being served.
	 *
	 * The layout is applied afterwards, on the calling thread, which is faithful rather than convenient: a worker
	 * thread has no Armeria request context either, so production renders these very events through the same branch
	 * of {@link AppLogJsonLayout} — the one that reads the MDC.
	 *
	 * @param loggingClass the class that writes a line while the request is being served
	 * @param request      issues the HTTP request, which must filter on {@link #PROBE_PRIMARY_KEY}
	 */
	public static void assertServedRequestLogsItsDuration(
		@Nonnull Class<?> loggingClass,
		@Nonnull Runnable request
	) {
		final Logger logger = (Logger) LoggerFactory.getLogger(loggingClass);
		final RecordingAppender appender = new RecordingAppender();
		final Level originalLevel = logger.getLevel();
		appender.start();
		logger.addAppender(appender);
		// the line this assertion reads is written at DEBUG, which the server does not enable by default
		logger.setLevel(Level.DEBUG);
		final long issuedAt = System.currentTimeMillis();
		try {
			request.run();
		} finally {
			logger.setLevel(originalLevel);
			logger.detachAppender(appender);
			appender.stop();
		}

		final List<ILoggingEvent> provoked = appender.events.stream()
			.filter(event -> event.getFormattedMessage().contains(PROBE_TOKEN))
			.toList();
		assertFalse(
			provoked.isEmpty(),
			"The request provoked no line from " + loggingClass.getSimpleName() + " — it logs the evitaDB query it " +
				"generated, so either the request never reached it or that line moved elsewhere. Captured " +
				appender.events.size() + " line(s) in total."
		);

		final AppLogJsonLayout layout = new AppLogJsonLayout();
		layout.setLogTimestamp(false);
		layout.start();

		for (final ILoggingEvent event : provoked) {
			assertNotEquals(
				Thread.currentThread().getName(), event.getThreadName(),
				"The line came from the thread that issued the request, so it was not written while serving it"
			);

			final String requestStart = event.getMDCPropertyMap().get(TracingContext.MDC_REQUEST_START_PROPERTY);
			assertNotNull(
				requestStart,
				"The request start did not survive the hand-off to `" + event.getThreadName() + "`, so every line " +
					"written while serving this request is logged without a duration"
			);

			final long start = Long.parseLong(requestStart);
			assertTrue(
				start > 0 && start <= event.getTimeStamp()
					&& event.getTimeStamp() - start < PLAUSIBLE_REQUEST_LIFETIME_MS,
				"The recorded request start `" + start + "` is not when this request began — the line was written " +
					"at " + event.getTimeStamp() + ", and the request was issued at " + issuedAt
			);

			// the layout turns that value into the field log aggregators consume, and does the arithmetic itself
			assertEquals(Math.max(0L, event.getTimeStamp() - start), durationOf(layout.doLayout(event)));
		}
	}

	/**
	 * Appender that keeps what it captured for the asserting thread to read.
	 *
	 * Two things make it a type of its own rather than Logback's {@code ListAppender}. Its list is thread-safe,
	 * because the logger it attaches to is global and every test running beside this one logs through it; and it
	 * materialises each event **on the thread that logged it**, because Logback fills in the thread name and the MDC
	 * map on first read. Read later, from the thread running the assertions, an event would answer with that
	 * thread's name and that thread's (empty) MDC - and the assertions would quietly be about the wrong thread.
	 */
	private static final class RecordingAppender extends AppenderBase<ILoggingEvent> {

		/**
		 * What was logged while the appender was attached, in the order it arrived.
		 */
		private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

		@Override
		protected void append(@Nonnull ILoggingEvent event) {
			event.prepareForDeferredProcessing();
			this.events.add(event);
		}
	}

	/**
	 * Extracts the numeric {@code duration_ms} value from a rendered line.
	 *
	 * @param renderedLine the line produced by the layout
	 * @return the parsed value
	 */
	private static long durationOf(@Nonnull String renderedLine) {
		final String prefix = "\"duration_ms\":";
		final int fieldIndex = renderedLine.indexOf(prefix);
		assertTrue(fieldIndex >= 0, "no duration_ms field in the rendered line: " + renderedLine);
		final int start = fieldIndex + prefix.length();
		int end = start;
		if (end < renderedLine.length() && renderedLine.charAt(end) == '-') {
			end++;
		}
		while (end < renderedLine.length() && Character.isDigit(renderedLine.charAt(end))) {
			end++;
		}
		return Long.parseLong(renderedLine.substring(start, end));
	}
}
