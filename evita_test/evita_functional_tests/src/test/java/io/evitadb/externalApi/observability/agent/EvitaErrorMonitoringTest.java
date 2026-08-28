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
import io.evitadb.externalApi.observability.ObservabilityManager;
import io.evitadb.externalApi.observability.metric.MetricHandler;
import io.evitadb.store.traffic.OffHeapTrafficRecorder.MemoryNotAvailableException;
import io.evitadb.utils.Assert;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
public class EvitaErrorMonitoringTest {
	/**
	 * Counts advice firings. The advice body is inlined into the instrumented constructors, which live in
	 * `io.evitadb.exception`, so both this field and its declaring class must be public - the inlined code is
	 * subject to ordinary access control from the package it lands in, exactly as `ErrorMonitor` is in production.
	 */
	public static final AtomicInteger FIRINGS = new AtomicInteger();

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
	 * Unlike the production agent - which runs at `premain`, before anything is loaded - this attaches to a JVM
	 * that already has thousands of classes in it, and surefire shares that JVM with every other test in the fork.
	 * Two consequences are handled deliberately: the ignore matcher excludes the JDK and the instrumentation
	 * libraries so retransformation touches only what is actually matched, and {@link #restoreErrorRoots()} undoes
	 * the weaving afterwards. Leaving it in place made a sibling traffic-recorder test fail while passing in
	 * isolation, which is the kind of cross-test coupling that is very expensive to diagnose later.
	 */
	@BeforeAll
	static void instrumentErrorRoots() {
		instrumentation = ByteBuddyAgent.install();
		transformer = new AgentBuilder.Default()
			.disableClassFormatChanges()
			.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
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
	 */
	@AfterAll
	static void restoreErrorRoots() {
		if (transformer != null) {
			transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
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
		final Throwable constructed = constructor.get();
		assertTrue(constructed != null);
		return FIRINGS.get();
	}

	/**
	 * Advice mirroring the shape of the production ones - it binds to the same constructors, and merely counts.
	 */
	public static class CountingAdvice {

		@Advice.OnMethodExit
		public static void after() {
			FIRINGS.incrementAndGet();
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
