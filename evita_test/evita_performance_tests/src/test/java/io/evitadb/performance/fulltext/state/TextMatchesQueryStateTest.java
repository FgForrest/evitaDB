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

package io.evitadb.performance.fulltext.state;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.test.EvitaTestSupport;
import org.apache.lucene.analysis.Analyzer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static io.evitadb.test.TestTags.TEST_HARNESS;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Trial lifecycle privacy, observed through the real embedded session logging proxy. */
@Tag(TEST_HARNESS)
@Tag(QUERY)
@DisplayName("Fulltext query-file trial privacy")
class TextMatchesQueryStateTest implements EvitaTestSupport {
	@Nested
	@DisplayName("Internal failures")
	class Failures {
		/** Setup, execution and close failures must keep decoded text out of every outward surface. */
		@ParameterizedTest
		@CsvSource({"false, false", "true, false", "false, true", "true, true"})
		@DisplayName("Suppresses query-bearing events and cleanup failures, then restores logging for the next trial")
		void shouldSuppressQueryLogsForWholeTrial(
			boolean failInSetup, boolean failOnClose, @TempDir Path directory
		) throws Exception {
			final TestPaths paths = createTestPaths("TextMatchesQueryStateTest");
			try (final Evita engine = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				engine.defineCatalog(TEST_CATALOG);
				engine.updateCatalog(TEST_CATALOG, session -> {
					session.defineEntitySchema("product").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
						.withAttribute("title", String.class, a -> a.localized().searchable()).updateVia(session);
					session.upsertEntity(session.createNewEntity("product", 1)
						.setAttribute("title", Locale.ENGLISH, "ordinary text"));
				});
				final String secret = "privatephrase";
				final String encoded =
					Base64.getEncoder().encodeToString((secret + " ").getBytes(StandardCharsets.UTF_8));
				final Path queries = directory.resolve("queries.tsv");
				Files.writeString(queries, "queryHash\tcohort\tkind\tweight\ttextBase64\tcleanBase64\n" +
					"a".repeat(64) + "\tby-token\tclean\t1\t" + encoded + "\t" + encoded + "\n");
				final FulltextCatalogFixture.FixtureKey key =
					new FulltextCatalogFixture.FixtureKey("", TEST_CATALOG, "product", "en", 8, true);
				final Constructor<?> constructor = FulltextCatalogFixture.class.getDeclaredConstructors()[0];
				constructor.setAccessible(true);
				final FulltextCatalogFixture fixture = (FulltextCatalogFixture) constructor.newInstance(
					key, directory, engine, TEST_CATALOG, "product", Locale.ENGLISH, Map.of());
				final Field fixturesField = FulltextCatalogFixture.class.getDeclaredField("FIXTURES");
				fixturesField.setAccessible(true);
				@SuppressWarnings("unchecked")
				final Map<FulltextCatalogFixture.FixtureKey, FulltextCatalogFixture> fixtures =
					(Map<FulltextCatalogFixture.FixtureKey, FulltextCatalogFixture>) fixturesField.get(null);
				fixtures.put(key, fixture);
				final TextMatchesQueryState state = new TextMatchesQueryState();
				set(state, "storageDirectory", "");
				set(state, "catalogName", TEST_CATALOG);
				set(state, "entityType", "product");
				set(state, "locale", "en");
				set(state, "tokenCount", 1);
				set(state, "frequencyClass", FulltextFrequencyClass.RARE);
				set(state, "queryFile", queries.toString());
				set(state, "cohort", "by-token");
				set(state, "poolSize", 64);
				set(state, "minLengthTwoTypos", 8);
				final BenchmarkParams params = mock(BenchmarkParams.class);
				when(params.getBenchmark()).thenReturn("benchmark.textMatches");
				final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
				final List<TurboFilter> initialFilters = List.copyOf(context.getTurboFilterList());
				final TurboFilter neutral = filter(FilterReply.NEUTRAL);
				final TurboFilter accepting = filter(FilterReply.ACCEPT);
				context.addTurboFilter(neutral);
				context.addTurboFilter(accepting);
				final List<TurboFilter> originalFilters = List.copyOf(context.getTurboFilterList());
				final String lateLoggerName = "trial-" + UUID.randomUUID();
				assertNull(context.exists(lateLoggerName));
				final Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
				final ListAppender<ILoggingEvent> appender = new ListAppender<>();
				appender.start();
				root.addAppender(appender);
				final PrintStream originalOut = System.out;
				final PrintStream originalErr = System.err;
				final ByteArrayOutputStream output = new ByteArrayOutputStream();
				final ByteArrayOutputStream errors = new ByteArrayOutputStream();
				final Catalog catalog = (Catalog) engine.getCatalogInstanceOrThrowException(TEST_CATALOG);
				final FulltextAnalyzer chain = catalog.getFulltextAnalyzerRegistry()
					.getCompiledAnalyzer(catalog.getSchema(), "text", Locale.ENGLISH).textSplitting();
				final Field analyzerField = FulltextAnalyzer.class.getDeclaredField("analyzer");
				analyzerField.setAccessible(true);
				final Analyzer originalAnalyzer = (Analyzer) analyzerField.get(chain);
				try (final Analyzer failing = new Analyzer() {
					/** Injects an unexpected internal failure during query analysis. */
					@Override
					protected TokenStreamComponents createComponents(String fieldName) {
						context.getLogger(lateLoggerName).error(secret);
						if (failOnClose && failInSetup) {
							try {
								failOnClose(state, secret, lateLoggerName);
							} catch (ReflectiveOperationException exception) {
								throw new AssertionError(exception);
							}
						}
						throw new IllegalStateException(secret);
					}
				}) {
					System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
					System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
					if (!failInSetup) {
						state.setUp(params);
					}
					final IllegalStateException failure;
					if (failOnClose && !failInSetup) {
						context.getLogger(lateLoggerName).error(secret);
						failOnClose(state, secret, lateLoggerName);
						failure = assertThrows(IllegalStateException.class, state::tearDown);
						assertEquals("Query-file cleanup failed: " + IllegalStateException.class.getName(),
							failure.getMessage());
					} else {
						analyzerField.set(chain, failing);
						failure = assertThrows(IllegalStateException.class,
							() -> { if (failInSetup) { state.setUp(params); } else { state.executeQuery(0, false); } });
						assertTrue(failure.getMessage().contains("queryHash=" + "a".repeat(64)));
						if (!failInSetup) {
							state.tearDown();
						}
					}
					assertPrivate(failure, secret);
					assertNull(failure.getCause());
					assertEquals(0, failure.getSuppressed().length);
					assertFilterOrder(originalFilters, context);
					assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains(secret)
						|| e.getThrowableProxy() != null
						&& ThrowableProxyUtil.asString(e.getThrowableProxy()).contains(secret)));
					assertFalse(output.toString(StandardCharsets.UTF_8).contains(secret));
					assertFalse(errors.toString(StandardCharsets.UTF_8).contains(secret));
					// A complete subsequent default trial must inherit the original logging configuration.
					analyzerField.set(chain, originalAnalyzer);
					final FulltextCatalogFixture.FixtureKey defaultKey =
						new FulltextCatalogFixture.FixtureKey("", TEST_CATALOG, "product", "en", 8, false);
					final Class<?> poolKeyType = Class.forName(FulltextCatalogFixture.class.getName() + "$PoolKey");
					final Constructor<?> poolKeyConstructor = poolKeyType.getDeclaredConstructors()[0];
					poolKeyConstructor.setAccessible(true);
					final Object poolKey = poolKeyConstructor.newInstance(1, FulltextFrequencyClass.RARE);
					fixtures.put(defaultKey, (FulltextCatalogFixture) constructor.newInstance(defaultKey, directory,
						engine, TEST_CATALOG, "product", Locale.ENGLISH, Map.of(poolKey, List.of(
							new FulltextQueryProfile("ordinary ", FulltextFrequencyClass.RARE, 1, 1, 1)))));
					try {
						set(state, "queryFile", "");
						state.setUp(params);
						root.error("logging-restored");
						assertEquals(1, state.executeQuery(0, false).getTotalRecordCount());
						state.tearDown();
						assertFilterOrder(originalFilters, context);
						assertTrue(appender.list.stream()
							.anyMatch(e -> e.getFormattedMessage().equals("logging-restored")));
					} finally {
						fixtures.remove(defaultKey);
					}
				} finally {
					final Method restore = TextMatchesQueryState.class.getDeclaredMethod("restoreQueryFileLogging");
					restore.setAccessible(true);
					restore.invoke(state);
					analyzerField.set(chain, originalAnalyzer);
					System.setOut(originalOut);
					System.setErr(originalErr);
					root.detachAppender(appender);
					appender.stop();
					fixtures.remove(key);
					context.getTurboFilterList().removeIf(filter -> filter == neutral || filter == accepting);
					neutral.stop();
					accepting.stop();
					assertFilterOrder(initialFilters, context);
				}
			} finally {
				cleanupTestPaths(paths);
			}
		}
	}

	/** Creates a pre-existing filter whose position must survive the trial unchanged. */
	private static TurboFilter filter(FilterReply reply) {
		final TurboFilter filter = new TurboFilter() {
			@Override
			public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] arguments,
				Throwable throwable) {
				return reply == FilterReply.ACCEPT && level != Level.ERROR
					? FilterReply.NEUTRAL : reply;
			}
		};
		filter.start();
		return filter;
	}

	/** Compares each filter by identity, including the original relative order. */
	private static void assertFilterOrder(List<TurboFilter> expected, LoggerContext context) {
		assertEquals(expected.size(), context.getTurboFilterList().size());
		for (int i = 0; i < expected.size(); i++) {
			assertSame(expected.get(i), context.getTurboFilterList().get(i));
		}
	}

	/** Checks every outward cause and suppressed branch, including their rendered stack traces. */
	private static void assertPrivate(Throwable failure, String secret) {
		final ByteArrayOutputStream trace = new ByteArrayOutputStream();
		failure.printStackTrace(new PrintStream(trace, true, StandardCharsets.UTF_8));
		assertFalse(trace.toString(StandardCharsets.UTF_8).contains(secret));
		if (failure.getCause() != null) {
			assertPrivate(failure.getCause(), secret);
		}
		for (final Throwable suppressed : failure.getSuppressed()) {
			assertPrivate(suppressed, secret);
		}
	}

	/** Delegates to the real session, injecting only a text-bearing failure and log event on close. */
	private static void failOnClose(TextMatchesQueryState state, String secret, String loggerName)
		throws ReflectiveOperationException {
		final Field sessionField = TextMatchesQueryState.class.getDeclaredField("session");
		sessionField.setAccessible(true);
		final EvitaSessionContract session = (EvitaSessionContract) sessionField.get(state);
		sessionField.set(state, Proxy.newProxyInstance(EvitaSessionContract.class.getClassLoader(),
			new Class<?>[]{EvitaSessionContract.class}, (proxy, method, arguments) -> {
				if (method.getName().equals("close")) {
					final IllegalStateException failure = new IllegalStateException(secret,
						new IllegalArgumentException("cause-" + secret));
					failure.addSuppressed(new IllegalArgumentException("suppressed-" + secret));
					LoggerFactory.getLogger(loggerName).error(secret, failure);
					session.close();
					throw failure;
				}
				try {
					return method.invoke(session, arguments);
				} catch (InvocationTargetException exception) {
					throw exception.getCause();
				}
			}));
	}

	/** Supplies JMH parameters without starting JMH or running a measurement. */
	private static void set(TextMatchesQueryState state, String name, Object value) throws Exception {
		final Field field = TextMatchesQueryState.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(state, value);
	}
}
