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

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.observability.configuration.ErrorOriginLogging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ErrorOriginLogger}: which modes report, that repeated occurrences of one origin collapse onto a
 * single tracked entry, that the number of tracked origins is bounded, and - most importantly - that nothing it does
 * can propagate a failure.
 *
 * That last one is not defensive tidiness. The logger is called from Byte Buddy advice inlined into exception
 * constructors, so a throwable escaping it would surface from `new SomeException(...)` at an arbitrary call site
 * that cannot possibly handle it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ErrorOriginLogger contract")
@Tag(OBSERVABILITY_API)
@Tag(OBSERVABILITY)
class ErrorOriginLoggerTest {

	@Nonnull
	private static GenericEvitaInternalError errorWithOrigin(@Nonnull String origin) {
		// a wire-supplied code lets each test pick its own origin without depending on line numbers
		return (GenericEvitaInternalError) GenericEvitaInternalError.createExceptionWithErrorCode("Whatever", origin);
	}

	@BeforeEach
	void resetLogger() {
		ErrorOriginLogger.reset();
		ErrorOriginLogger.configure(ErrorOriginLogging.INTERNAL);
	}

	@AfterEach
	void restoreDefaults() {
		ErrorOriginLogger.reset();
		ErrorOriginLogger.configure(ErrorOriginLogging.INTERNAL);
	}

	@Nested
	@DisplayName("mode gating")
	class ModeTests {

		@Test
		@DisplayName("Should record nothing at all in NONE")
		void shouldRecordNothingInNoneMode() {
			ErrorOriginLogger.configure(ErrorOriginLogging.NONE);
			ErrorOriginLogger.reportInternalError(errorWithOrigin("a:b:1"));
			ErrorOriginLogger.reportClientError(new EvitaInvalidUsageException("Whatever"));
			assertEquals(0L, ErrorOriginLogger.occurrencesOf("a:b:1"));
		}

		@Test
		@DisplayName("Should record internal errors but not client errors in INTERNAL")
		void shouldRecordOnlyInternalErrorsInInternalMode() {
			ErrorOriginLogger.configure(ErrorOriginLogging.INTERNAL);
			ErrorOriginLogger.reportInternalError(errorWithOrigin("a:b:1"));
			assertEquals(1L, ErrorOriginLogger.occurrencesOf("a:b:1"));

			final EvitaInvalidUsageException clientError = EvitaInvalidUsageException
				.createExceptionWithErrorCode("Whatever", "c:d:2");
			ErrorOriginLogger.reportClientError(clientError);
			assertEquals(0L, ErrorOriginLogger.occurrencesOf("c:d:2"));
		}

		@Test
		@DisplayName("Should record both internal and client errors in ALL")
		void shouldRecordBothInAllMode() {
			ErrorOriginLogger.configure(ErrorOriginLogging.ALL);
			ErrorOriginLogger.reportInternalError(errorWithOrigin("a:b:1"));
			ErrorOriginLogger.reportClientError(
				EvitaInvalidUsageException.createExceptionWithErrorCode("Whatever", "c:d:2")
			);
			assertEquals(1L, ErrorOriginLogger.occurrencesOf("a:b:1"));
			assertEquals(1L, ErrorOriginLogger.occurrencesOf("c:d:2"));
		}
	}

	@Nested
	@DisplayName("deduplication and bounding")
	class DeduplicationTests {

		@Test
		@DisplayName("Should collapse repeated occurrences of one origin onto a single counter")
		void shouldCollapseRepeatedOccurrences() {
			for (int i = 0; i < 25; i++) {
				ErrorOriginLogger.reportInternalError(errorWithOrigin("a:b:1"));
			}
			assertEquals(25L, ErrorOriginLogger.occurrencesOf("a:b:1"));
		}

		@Test
		@DisplayName("Should track distinct origins separately")
		void shouldTrackDistinctOriginsSeparately() {
			ErrorOriginLogger.reportInternalError(errorWithOrigin("a:b:1"));
			ErrorOriginLogger.reportInternalError(errorWithOrigin("a:b:2"));
			ErrorOriginLogger.reportInternalError(errorWithOrigin("a:b:2"));
			assertEquals(1L, ErrorOriginLogger.occurrencesOf("a:b:1"));
			assertEquals(2L, ErrorOriginLogger.occurrencesOf("a:b:2"));
		}

		@Test
		@DisplayName("Should stop admitting new origins once the cap is reached")
		void shouldStopAdmittingNewOriginsAtCap() {
			for (int i = 0; i < ErrorOriginLogger.MAX_TRACKED_ORIGINS; i++) {
				ErrorOriginLogger.reportInternalError(errorWithOrigin("cap:origin:" + i));
			}
			assertEquals(1L, ErrorOriginLogger.occurrencesOf("cap:origin:0"));

			// one past the cap is dropped rather than tracked - an error code can arrive from the wire, so the map
			// must not grow on client-supplied data
			ErrorOriginLogger.reportInternalError(errorWithOrigin("cap:origin:overflow"));
			assertEquals(0L, ErrorOriginLogger.occurrencesOf("cap:origin:overflow"));

			// origins admitted before the cap keep counting
			ErrorOriginLogger.reportInternalError(errorWithOrigin("cap:origin:0"));
			assertEquals(2L, ErrorOriginLogger.occurrencesOf("cap:origin:0"));
		}

		@Test
		@DisplayName("Should hold the cap when distinct new origins arrive concurrently")
		void shouldHoldCapUnderConcurrentNovelOrigins() throws InterruptedException {
			// the cap guards a map keyed partly on data that can arrive from the wire, so it has to hold under
			// concurrent arrival - checking the size and then inserting would let every racing thread through
			final int threads = 8;
			final int originsPerThread = 400;
			final CountDownLatch startGate = new CountDownLatch(1);
			final CountDownLatch finished = new CountDownLatch(threads);
			final ExecutorService executor = Executors.newFixedThreadPool(threads, runnable -> {
				final Thread thread = new Thread(runnable, "origin-cap-probe");
				thread.setDaemon(true);
				return thread;
			});
			try {
				for (int t = 0; t < threads; t++) {
					final int threadIndex = t;
					executor.submit(() -> {
						try {
							startGate.await();
							for (int i = 0; i < originsPerThread; i++) {
								ErrorOriginLogger.reportInternalError(
									errorWithOrigin("race:" + threadIndex + ":" + i)
								);
							}
						} catch (InterruptedException ex) {
							Thread.currentThread().interrupt();
						} finally {
							finished.countDown();
						}
					});
				}
				startGate.countDown();
				assertTrue(finished.await(30, TimeUnit.SECONDS), "reporting threads must finish");
			} finally {
				executor.shutdownNow();
			}

			assertEquals(ErrorOriginLogger.MAX_TRACKED_ORIGINS, ErrorOriginLogger.trackedOriginCount());
		}

		@Test
		@DisplayName("Should forget everything on reset")
		void shouldForgetEverythingOnReset() {
			ErrorOriginLogger.reportInternalError(errorWithOrigin("a:b:1"));
			assertNotEquals(0L, ErrorOriginLogger.occurrencesOf("a:b:1"));
			ErrorOriginLogger.reset();
			assertEquals(0L, ErrorOriginLogger.occurrencesOf("a:b:1"));
		}
	}

	@Nested
	@DisplayName("never propagates a failure")
	class FailureContainmentTests {

		@Test
		@DisplayName("Should swallow an exception thrown while resolving the origin")
		void shouldSwallowFailureWhileResolvingOrigin() {
			assertDoesNotThrow(() -> ErrorOriginLogger.reportInternalError(new HostileError()));
		}

		@Test
		@DisplayName("Should ignore a throwable that is not an evitaDB error")
		void shouldIgnoreNonEvitaThrowable() {
			assertDoesNotThrow(() -> ErrorOriginLogger.reportInternalError(new IllegalStateException("Whatever")));
		}

		@Test
		@DisplayName("Should not recurse when reporting itself constructs an evitaDB error")
		void shouldNotRecurseWhenReportingConstructsAnError() {
			// without the re-entrancy guard this recurses until the stack is exhausted - and a StackOverflowError
			// raised from inside a constructor is exactly the failure this class exists not to cause
			assertDoesNotThrow(() -> ErrorOriginLogger.reportInternalError(new ReentrantError()));
		}
	}

	/**
	 * Stands in for any subclass whose overridden accessor misbehaves - the logger runs on a partially constructed
	 * object, so it must survive one.
	 */
	private static class HostileError extends GenericEvitaInternalError {
		@Serial private static final long serialVersionUID = 4004886118570449831L;

		HostileError() {
			super("Whatever");
		}

		@Nonnull
		@Override
		public String getErrorCode() {
			throw new UnsupportedOperationException("Deliberately hostile.");
		}
	}

	/**
	 * Stands in for anything the reporting path might come to call that itself constructs an evitaDB error - a
	 * logging appender, a future helper. The re-entrant call must be dropped rather than followed.
	 */
	private static class ReentrantError extends GenericEvitaInternalError {
		@Serial private static final long serialVersionUID = 2377395168353447457L;

		ReentrantError() {
			super("Whatever");
		}

		@Nonnull
		@Override
		public String getErrorCode() {
			ErrorOriginLogger.reportInternalError(new ReentrantError());
			return "reentrant:origin:1";
		}
	}
}
