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

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.NotMonitored;
import io.evitadb.externalApi.observability.ObservabilityManager;
import io.evitadb.externalApi.observability.configuration.ErrorOriginLogging;
import io.evitadb.externalApi.observability.metric.MetricHandler;
import io.evitadb.store.traffic.OffHeapTrafficRecorder.MemoryNotAvailableException;
import io.evitadb.utils.Assert;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the error-monitoring agent counts each constructed evitaDB error **exactly once**.
 *
 * Advice on a constructor fires once per constructor *entered*, which used to make the error counters wrong in two
 * independent ways: a concrete error class extending another concrete one was counted once per level, and a
 * constructor delegating to a sibling via `this(...)` was counted twice. `EvitaInvalidUsageException` did both, so
 * `io_evitadb_client_errors_total` over-counted by two to six times for essentially every client error.
 *
 * Nothing fails when this regresses - the numbers are simply wrong - so it needs a test rather than a comment.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("evitaDB error monitoring counts each error once")
@Tag(OBSERVABILITY_API)
@Tag(OBSERVABILITY)
// This class retransforms the two evitaDB error roots process-wide. With
// junit.jupiter.execution.parallel.mode.classes.default=concurrent and a single reused surefire fork, that
// weaving would otherwise apply to sibling test classes running at the same time, and their error
// constructions would land in the counter below. @Isolated forces the class to run alone - the same remedy
// ConsoleWriterTest uses for the process-global System.out it swaps.
@Isolated
public class EvitaErrorMonitoringTest {
	/**
	 * Counts advice firings. The advice body is inlined into the instrumented constructors, which live in
	 * `io.evitadb.exception`, so both this field and its declaring class must be public - the inlined code is
	 * subject to ordinary access control from the package it lands in, exactly as `ErrorMonitor` is in production.
	 */
	public static final AtomicInteger FIRINGS = new AtomicInteger();

	/**
	 * Thread currently measuring a construction. The advice counts only firings on this thread, so a construction
	 * on any other thread - a background pool inside an embedded instance, or anything @Isolated does not cover -
	 * cannot inflate the measurement. Public for the same reason {@link #FIRINGS} is.
	 */
	public static volatile Thread probingThread;

	/**
	 * The only two classes this test ever retransforms.
	 *
	 * Shared deliberately between {@link #instrumentErrorRoots()} and {@link #restoreErrorRoots()}: `reset` runs a
	 * retransformation pass of its own, and if it were left on the default discovery strategy it would re-run the
	 * very enumerate-every-loaded-class pass the install avoids - moving the hang from `@BeforeAll` to `@AfterAll`
	 * rather than removing it.
	 */
	private static final AgentBuilder.RedefinitionStrategy.DiscoveryStrategy ERROR_ROOTS_ONLY =
		new AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Explicit(
			EvitaInternalError.class, EvitaInvalidUsageException.class
		);

	/**
	 * Instrumentation handle, kept so {@link #restoreErrorRoots()} can put the classes back as they were.
	 */
	private static Instrumentation instrumentation;

	/**
	 * The installed transformer, kept for the same reason.
	 */
	private static ResettableClassFileTransformer transformer;

	/**
	 * Instruments the two evitaDB error roots with a counting advice, using the **production** type matchers so
	 * this test cannot drift away from what the agent actually installs.
	 *
	 * Unlike the production agent - which runs at `premain`, before anything is loaded - this attaches to a JVM that
	 * already has tens of thousands of classes in it, and surefire shares that JVM with every other test in the
	 * fork. Left on Byte Buddy's defaults that combination wedges the whole fork, through two *independent*
	 * mechanisms that each have to be shut off:
	 *
	 * - the default discovery strategy enumerates every loaded class and loads it to decide whether it matches.
	 *   {@link #ERROR_ROOTS_ONLY} replaces that with the two classes actually being woven.
	 * - the default description strategy (`HYBRID`) resolves a type by calling `ClassLoader#loadClass` - *from
	 *   inside a class-file transformer*, which is itself invoked during class loading. `POOL_ONLY` reads the
	 *   class file from the type pool instead and never loads anything.
	 *
	 * Scoping the retransformation alone is not enough: `installOn` leaves the transformer on the class-load path
	 * for every subsequent load regardless of how few classes were retransformed, so the second mechanism keeps
	 * firing on its own. Observed as a fork that made no progress for eight hours with 105 threads blocked on jar
	 * and classloader monitors, 56 of them inside this transformer - no deadlock the JVM could report, just a
	 * convoy that never drains.
	 *
	 * {@link #restoreErrorRoots()} then undoes the weaving; leaving it in place made a sibling traffic-recorder
	 * test fail while passing in isolation.
	 */
	@BeforeAll
	static void instrumentErrorRoots() {
		instrumentation = ByteBuddyAgent.install();
		transformer = new AgentBuilder.Default()
			.disableClassFormatChanges()
			.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
			.with(ERROR_ROOTS_ONLY)
			.with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
			.ignore(
				nameStartsWith("net.bytebuddy.")
					.or(nameStartsWith("java."))
					.or(nameStartsWith("jdk."))
					.or(nameStartsWith("sun."))
			)
			.type(ErrorMonitoringAgent.evitaInternalErrorRoot())
			.transform((builder, type, loader, module, domain) -> builder
				.visit(Advice.to(CountingAdvice.class).on(isConstructor())))
			.type(ErrorMonitoringAgent.clientErrorRoot())
			.transform((builder, type, loader, module, domain) -> builder
				.visit(Advice.to(CountingAdvice.class).on(isConstructor())))
			.installOn(instrumentation);
	}

	/**
	 * Removes the weaving again, so the rest of the surefire fork runs against untouched classes.
	 *
	 * Passes {@link #ERROR_ROOTS_ONLY} rather than taking the two-argument overload's default discovery strategy -
	 * see the note on that constant for why the default would reintroduce here exactly what the install avoids.
	 */
	@AfterAll
	static void restoreErrorRoots() {
		if (transformer != null) {
			transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION, ERROR_ROOTS_ONLY);
			transformer = null;
		}
	}

	/**
	 * Constructs one exception and returns how many times the advice fired for it.
	 *
	 * @param constructor creates the exception under test
	 * @return number of advice firings observed
	 */
	private static int firingsWhenConstructing(@Nonnull Supplier<Throwable> constructor) {
		FIRINGS.set(0);
		probingThread = Thread.currentThread();
		try {
			final Throwable constructed = constructor.get();
			assertNotNull(constructed);
			return FIRINGS.get();
		} finally {
			probingThread = null;
		}
	}

	/**
	 * Advice mirroring the shape of the production ones - it binds to the same constructors, and merely counts.
	 */
	public static class CountingAdvice {

		@Advice.OnMethodExit
		public static void after() {
			if (Thread.currentThread() == EvitaErrorMonitoringTest.probingThread) {
				FIRINGS.incrementAndGet();
			}
		}

	}

	@Nested
	@DisplayName("only the hierarchy roots are instrumented")
	class MatcherTests {

		@Test
		@DisplayName("Should match the internal-error root but not its subtypes")
		void shouldMatchInternalErrorRootOnly() {
			assertTrue(
				ErrorMonitoringAgent.evitaInternalErrorRoot()
					.matches(TypeDescription.ForLoadedType.of(EvitaInternalError.class))
			);
			assertFalse(
				ErrorMonitoringAgent.evitaInternalErrorRoot()
					.matches(TypeDescription.ForLoadedType.of(GenericEvitaInternalError.class))
			);
		}

		@Test
		@DisplayName("Should match the client-error root but not its subtypes")
		void shouldMatchClientErrorRootOnly() {
			assertTrue(
				ErrorMonitoringAgent.clientErrorRoot()
					.matches(TypeDescription.ForLoadedType.of(EvitaInvalidUsageException.class))
			);
			assertFalse(
				ErrorMonitoringAgent.clientErrorRoot()
					.matches(TypeDescription.ForLoadedType.of(TestUsageException.class))
			);
		}

		@Test
		@DisplayName("Should match concrete JVM errors but not the abstract base")
		void shouldMatchConcreteJvmErrorsOnly() {
			assertTrue(
				ErrorMonitoringAgent.javaErrorTypes()
					.matches(TypeDescription.ForLoadedType.of(OutOfMemoryError.class))
			);
			assertFalse(
				ErrorMonitoringAgent.javaErrorTypes()
					.matches(TypeDescription.ForLoadedType.of(VirtualMachineError.class))
			);
		}
	}

	@Nested
	@DisplayName("one increment per constructed instance")
	class CountingTests {

		@Test
		@DisplayName("Should count a directly constructed internal error once")
		void shouldCountInternalErrorOnce() {
			assertEquals(1, firingsWhenConstructing(() -> new GenericEvitaInternalError("Whatever")));
		}

		@Test
		@DisplayName("Should count an internal error carrying a cause once")
		void shouldCountInternalErrorWithCauseOnce() {
			assertEquals(
				1,
				firingsWhenConstructing(() -> new GenericEvitaInternalError("Whatever", new IllegalStateException()))
			);
		}

		@Test
		@DisplayName("Should count an internal error raised through Assert once")
		void shouldCountAssertRaisedInternalErrorOnce() {
			assertEquals(1, firingsWhenConstructing(() -> {
				try {
					Assert.isPremiseValid(false, "Whatever");
					return new IllegalStateException("Assertion unexpectedly passed.");
				} catch (GenericEvitaInternalError ex) {
					return ex;
				}
			}));
		}

		@Test
		@DisplayName("Should count a two-level internal error subclass once")
		void shouldCountNestedInternalErrorSubclassOnce() {
			assertEquals(1, firingsWhenConstructing(() -> new NestedInternalError("Whatever")));
		}

		@Test
		@DisplayName("Should count a directly constructed client error once")
		void shouldCountClientErrorOnce() {
			assertEquals(1, firingsWhenConstructing(() -> new EvitaInvalidUsageException("Whatever")));
		}

		@Test
		@DisplayName("Should count a client error carrying a cause once")
		void shouldCountClientErrorWithCauseOnce() {
			assertEquals(
				1,
				firingsWhenConstructing(() -> new EvitaInvalidUsageException("Whatever", new IllegalStateException()))
			);
		}

		@Test
		@DisplayName("Should count a client error subclass once")
		void shouldCountClientErrorSubclassOnce() {
			assertEquals(1, firingsWhenConstructing(() -> new TestUsageException("Whatever")));
		}
	}

	@Nested
	@DisplayName("@NotMonitored opt-out survives root-only instrumentation")
	class NotMonitoredTests {

		@BeforeEach
		void warmUpDataPoints() {
			// touching the label values registers the data points, so the reads below start from a known value
			MetricHandler.EVITA_ERRORS_TOTAL.labelValues(MemoryNotAvailableException.class.getSimpleName());
			MetricHandler.EVITA_ERRORS_TOTAL.labelValues(GenericEvitaInternalError.class.getSimpleName());
		}

		@Test
		@DisplayName("Should not count an error type carrying @NotMonitored")
		void shouldNotCountNotMonitoredErrorType() {
			final String label = MemoryNotAvailableException.class.getSimpleName();
			final double before = MetricHandler.EVITA_ERRORS_TOTAL.labelValues(label).get();
			ObservabilityManager.evitaErrorEvent(new MemoryNotAvailableException());
			assertEquals(before, MetricHandler.EVITA_ERRORS_TOTAL.labelValues(label).get());
		}

		@Test
		@DisplayName("Should count an ordinary error type")
		void shouldCountOrdinaryErrorType() {
			final String label = GenericEvitaInternalError.class.getSimpleName();
			final double before = MetricHandler.EVITA_ERRORS_TOTAL.labelValues(label).get();
			ObservabilityManager.evitaErrorEvent(new GenericEvitaInternalError("Whatever"));
			assertEquals(before + 1.0d, MetricHandler.EVITA_ERRORS_TOTAL.labelValues(label).get());
		}
	}

	@Nested
	@DisplayName("client errors reach their own counter")
	class ClientErrorEventTests {

		@Test
		@DisplayName("Should count an ordinary client error type")
		void shouldCountOrdinaryClientErrorType() {
			final String label = EvitaInvalidUsageException.class.getSimpleName();
			final double before = MetricHandler.CLIENT_ERRORS_TOTAL.labelValues(label).get();
			ObservabilityManager.clientErrorEvent(new EvitaInvalidUsageException("Whatever"));
			assertEquals(before + 1.0d, MetricHandler.CLIENT_ERRORS_TOTAL.labelValues(label).get());
		}

		@Test
		@DisplayName("Should not count a client error type carrying @NotMonitored")
		void shouldNotCountNotMonitoredClientErrorType() {
			final String label = NotMonitoredUsageException.class.getSimpleName();
			final double before = MetricHandler.CLIENT_ERRORS_TOTAL.labelValues(label).get();
			ObservabilityManager.clientErrorEvent(new NotMonitoredUsageException("Whatever"));
			assertEquals(before, MetricHandler.CLIENT_ERRORS_TOTAL.labelValues(label).get());
		}
	}

	@Nested
	@DisplayName("the agent-to-manager hand-off is wired")
	class MediatorWiringTests {

		@BeforeEach
		void wireConsumersAndResetOrigins() {
			// touching the manager runs its static initializer, which is what reflectively installs the three
			// consumers onto ErrorMonitor - the hand-off under test here
			ObservabilityManager.evitaErrorEvent(new GenericEvitaInternalError("wiring warm-up"));
			ErrorOriginLogger.reset();
			ErrorOriginLogger.configure(ErrorOriginLogging.ALL);
		}

		@AfterEach
		void restoreOriginLogging() {
			ErrorOriginLogger.reset();
			ErrorOriginLogger.configure(ErrorOriginLogging.INTERNAL);
		}

		@Test
		@DisplayName("Should route an internal error from ErrorMonitor to the metric")
		void shouldRouteInternalErrorFromErrorMonitor() {
			final String label = GenericEvitaInternalError.class.getSimpleName();
			final double before = MetricHandler.EVITA_ERRORS_TOTAL.labelValues(label).get();
			ErrorMonitor.registerEvitaError(new GenericEvitaInternalError("Whatever"));
			assertEquals(before + 1.0d, MetricHandler.EVITA_ERRORS_TOTAL.labelValues(label).get());
		}

		@Test
		@DisplayName("Should route a client error from ErrorMonitor to the metric")
		void shouldRouteClientErrorFromErrorMonitor() {
			final String label = EvitaInvalidUsageException.class.getSimpleName();
			final double before = MetricHandler.CLIENT_ERRORS_TOTAL.labelValues(label).get();
			ErrorMonitor.registerClientError(new EvitaInvalidUsageException("Whatever"));
			assertEquals(before + 1.0d, MetricHandler.CLIENT_ERRORS_TOTAL.labelValues(label).get());
		}

		@Test
		@DisplayName("Should route a JVM error from ErrorMonitor to the metric")
		void shouldRouteJavaErrorFromErrorMonitor() {
			final String label = OutOfMemoryError.class.getSimpleName();
			final double before = MetricHandler.JAVA_ERRORS_TOTAL.labelValues(label).get();
			ErrorMonitor.registerJavaError(new OutOfMemoryError("Whatever"));
			assertEquals(before + 1.0d, MetricHandler.JAVA_ERRORS_TOTAL.labelValues(label).get());
		}

		@Test
		@DisplayName("Should record the origin as well as the metric")
		void shouldRecordOriginAsWellAsMetric() {
			final GenericEvitaInternalError error = (GenericEvitaInternalError) GenericEvitaInternalError
				.createExceptionWithErrorCode("Whatever", "wiring:origin:1");
			ErrorMonitor.registerEvitaError(error);
			assertEquals(1L, ErrorOriginLogger.occurrencesOf("wiring:origin:1"));
		}

		@Test
		@DisplayName("Should contain a failure raised inside a consumer")
		void shouldContainFailureRaisedInsideConsumer() {
			// the consumer reads the error's class to honour @NotMonitored and to label the metric; a type whose
			// accessors misbehave must not turn into a throwable escaping an exception constructor
			assertDoesNotThrow(() -> ErrorMonitor.registerEvitaError(new HostileError()));
		}
	}

	/**
	 * Client-error counterpart of the traffic recorder's opt-out signal - no production client error carries
	 * {@link NotMonitored}, so the runtime check needs one of its own to be exercised on that side.
	 */
	@NotMonitored
	private static class NotMonitoredUsageException extends EvitaInvalidUsageException {
		@Serial private static final long serialVersionUID = 5522177398295163851L;

		NotMonitoredUsageException(@Nonnull String publicMessage) {
			super(publicMessage);
		}
	}

	/**
	 * Reports a class whose accessor throws, standing in for anything a consumer might call that misbehaves.
	 */
	private static class HostileError extends GenericEvitaInternalError {
		@Serial private static final long serialVersionUID = 8560517319131983842L;

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
	 * Two levels below the instrumented root - the shape that used to be counted three times.
	 */
	private static class NestedInternalError extends GenericEvitaInternalError {
		@Serial private static final long serialVersionUID = 3062265618834072176L;

		NestedInternalError(@Nonnull String publicMessage) {
			super(publicMessage);
		}
	}

	/**
	 * One level below the instrumented client-error root.
	 */
	private static class TestUsageException extends EvitaInvalidUsageException {
		@Serial private static final long serialVersionUID = 8175471925548133962L;

		TestUsageException(@Nonnull String publicMessage) {
			super(publicMessage);
		}
	}
}
