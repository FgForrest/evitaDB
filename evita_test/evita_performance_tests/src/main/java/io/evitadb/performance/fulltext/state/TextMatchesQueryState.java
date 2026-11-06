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

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.performance.fulltext.state.FulltextCatalogFixture.FixtureKey;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The fixture of `TextMatchesQueryBenchmark`: one cell of the matrix - a word count and a frequency class - with its
 * pool of verified queries, a long-lived read-only session and a record of how long each query took. With `queryFile`
 * set, the pool instead contains one census cohort in file order, with no word-count/frequency classification.
 * Select `-p tokenCount=1 -p frequencyClass=RARE` to collapse those unused axes; other cells are refused.
 *
 * # Which catalog
 *
 * With `storageDirectory` empty (the default) the cell runs on the synthetic catalog, which checks the harness and
 * measures nothing worth quoting. For a real measurement pass all four of `storageDirectory`, `catalogName`,
 * `entityType` and `locale` with `-p`; `FulltextCatalogFixture` explains what the directory has to hold.
 *
 * # Per-query times
 *
 * An invocation runs one query of the pool, the next one each time, so a cell's JMH score is the mean over its pool.
 * The phase-1 cost model is a per-query statement, though, so the state also keeps the duration of every invocation
 * of the measurement iterations, by query, and prints each query's median and 90th percentile next to its posting
 * count, match count and model cost when the trial ends. A table from the plain method and one from the ranked method
 * pair query by query, because both sample the same pool.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@State(Scope.Benchmark)
public class TextMatchesQueryState {

	/**
	 * Page size of the measured queries - what a front store asks for. The engine computes the whole match set and,
	 * when ranking, scores all of it before it pages, so this decides only how many references are materialized.
	 */
	public static final int MEASURED_PAGE_SIZE = 20;
	/**
	 * Initial capacity of each query's duration record.
	 */
	private static final int INITIAL_SAMPLE_CAPACITY = 256;

	/**
	 * The directory holding the stored catalog, or empty for the synthetic one.
	 */
	@Param({""})
	private String storageDirectory;
	/**
	 * Name of the stored catalog.
	 */
	@Param({""})
	private String catalogName;
	/**
	 * Name of the queried collection of the stored catalog.
	 */
	@Param({""})
	private String entityType;
	/**
	 * Language tag of the searched locale of the stored catalog.
	 */
	@Param({"cs"})
	private String locale;
	/**
	 * How many words each query of the cell has.
	 */
	@Param({"1", "2", "3"})
	private int tokenCount;
	/**
	 * Which frequency class the words of the cell's queries come from.
	 */
	@Param({"RARE", "MEDIUM", "HIGH"})
	private FulltextFrequencyClass frequencyClass;

	/** Census artifact, or empty for the corpus-derived exact-core pools. */
	@Param({""})
	private String queryFile;
	/** Census cohort, including every one of its kinds; ignored without a query file. */
	@Param({"by-token"})
	private String cohort;
	/** Maximum census occurrences per cell; corpus-derived pools retain their existing size of 32. */
	@Param({"64"})
	private int poolSize;
	/** Shortest analyzed query term allowed two typos (`minLengthTwoTypos`). */
	@Param({"8"})
	private int minLengthTwoTypos;
	/** Saved census occurrences, empty in corpus-derived mode. */
	private FulltextQueryFile.Entry[] fileQueries;
	/** Setup result identities for the method being measured, empty in corpus-derived mode. */
	private FulltextQueryResultIdentity[] resultIdentities;

	/**
	 * The read-only session every invocation queries through, opened once because opening one per invocation would
	 * measure session bookkeeping rather than the search.
	 */
	private EvitaSessionContract session;
	/**
	 * The cell's queries.
	 */
	private FulltextQueryProfile[] profiles;
	/**
	 * The prepared plain queries, by pool position.
	 */
	private Query[] plainQueries;
	/**
	 * The prepared ranked queries, by pool position.
	 */
	private Query[] rankedQueries;
	/**
	 * Durations of the measurement invocations in nanoseconds, by pool position.
	 */
	private long[][] samples;
	/**
	 * How many entries of {@link #samples} are filled, by pool position.
	 */
	private int[] sampleCounts;
	/**
	 * Pool position of the query the next invocation runs.
	 */
	private int cursor;
	/**
	 * Whether the running iteration is a measurement one, whose durations are kept.
	 */
	private boolean recording;
	/**
	 * Short name of the benchmark method the trial runs, for the printed table.
	 */
	private String benchmarkName;

	/** Trial-wide suppression prevents engine errors from logging decoded census text before sanitization. */
	private TurboFilter queryFileLogFilter;

	/**
	 * Obtains the fixture, opens the session and prepares the queries of the cell.
	 *
	 * @param benchmarkParams what JMH runs in this trial
	 */
	@Setup(Level.Trial)
	public void setUp(@Nonnull BenchmarkParams benchmarkParams) {
		if (!this.queryFile.isEmpty()) {
			final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
			this.queryFileLogFilter = new TurboFilter() {
				@Override
				public FilterReply decide(
					Marker marker, ch.qos.logback.classic.Logger logger, ch.qos.logback.classic.Level level,
					String format, Object[] arguments, Throwable throwable
				) {
					return FilterReply.DENY;
				}
			};
			this.queryFileLogFilter.start();
			context.getTurboFilterList().add(0, this.queryFileLogFilter);
		}
		try {
			setUpTrial(benchmarkParams);
		} catch (RuntimeException | Error failure) {
			try {
				closeSession();
			} catch (RuntimeException | Error cleanupFailure) {
				if (this.queryFile.isEmpty()) {
					throw cleanupFailure;
				}
				// Keep the sanitized primary failure; raw cleanup failures must never become suppressed exceptions.
			} finally {
				restoreQueryFileLogging();
			}
			throw failure;
		}
	}

	/**
	 * Builds and verifies the query pool while trial-wide logging suppression is already active.
	 *
	 * @param benchmarkParams what JMH runs in this trial
	 */
	private void setUpTrial(@Nonnull BenchmarkParams benchmarkParams) {
		final boolean fileMode = !this.queryFile.isEmpty();
		if (fileMode && (this.tokenCount != 1 || this.frequencyClass != FulltextFrequencyClass.RARE)) {
			throw new IllegalArgumentException(
				"Query-file mode requires -p tokenCount=1 -p frequencyClass=RARE; these axes do not apply."
			);
		}
		this.fileQueries = fileMode ?
			FulltextQueryFile.read(Path.of(this.queryFile), this.cohort, this.poolSize)
				.toArray(FulltextQueryFile.Entry[]::new) : new FulltextQueryFile.Entry[0];
		final FulltextCatalogFixture fixture = FulltextCatalogFixture.obtain(
			new FixtureKey(this.storageDirectory, this.catalogName, this.entityType, this.locale,
				this.minLengthTwoTypos, fileMode)
		);
		this.profiles = fileMode ? new FulltextQueryProfile[0] :
			fixture.getPool(this.tokenCount, this.frequencyClass).toArray(FulltextQueryProfile[]::new);
		final int queryCount = fileMode ? this.fileQueries.length : this.profiles.length;
		this.plainQueries = new Query[queryCount];
		this.rankedQueries = new Query[queryCount];
		this.samples = new long[queryCount][];
		this.sampleCounts = new int[queryCount];
		this.resultIdentities = new FulltextQueryResultIdentity[fileMode ? queryCount : 0];
		for (int i = 0; i < queryCount; i++) {
			final String text = fileMode ? this.fileQueries[i].text() : this.profiles[i].text();
			this.plainQueries[i] = fixture.buildQuery(text, false, MEASURED_PAGE_SIZE);
			this.rankedQueries[i] = fixture.buildQuery(text, true, MEASURED_PAGE_SIZE);
			this.samples[i] = new long[INITIAL_SAMPLE_CAPACITY];
		}
		this.session = fixture.getEvita().createReadOnlySession(fixture.getCatalogName());
		final String benchmark = benchmarkParams.getBenchmark();
		this.benchmarkName = benchmark.substring(benchmark.lastIndexOf('.') + 1);
		this.cursor = 0;
		this.recording = false;
		System.out.println(
			"[fulltext-query] " + this.benchmarkName + " " + fixture.getKey() + " cell " + this.tokenCount + " x "
				+ this.frequencyClass + ": " + queryCount + " queries"
				+ (fileMode ? " cohort=" + this.cohort : "")
		);
		if (fileMode) {
			final boolean ranked = this.benchmarkName.equals("textMatchesByRelevance");
			for (int i = 0; i < queryCount; i++) {
				final EvitaResponse<EntityReference> response = executeQuery(i, ranked);
				final int[] primaryKeys = response.getRecordData().stream()
					.mapToInt(EntityReference::getPrimaryKey).toArray();
				this.resultIdentities[i] =
					FulltextQueryResultIdentity.from(response.getTotalRecordCount(), primaryKeys);
				System.out.println("[fulltext-query-result] " + this.benchmarkName + " #" + i + " " + fileIdentity(i));
			}
		}
	}

	/**
	 * Starts keeping durations when a measurement iteration begins, and stops for a warm-up one.
	 *
	 * @param iterationParams what JMH runs in this iteration
	 */
	@Setup(Level.Iteration)
	public void startIteration(@Nonnull IterationParams iterationParams) {
		this.recording = iterationParams.getType() == IterationType.MEASUREMENT;
	}

	/**
	 * Returns the pool position of the query the invocation runs, and moves on to the next one.
	 *
	 * @return the pool position
	 */
	public int nextQuery() {
		final int index = this.cursor;
		this.cursor = index + 1 == this.plainQueries.length ? 0 : index + 1;
		return index;
	}

	/**
	 * Executes a prepared query. In census mode an engine exception may include raw text, so only the saved query
	 * identity is allowed into the exception JMH prints; no original cause or suppressed exception is retained.
	 *
	 * @param index the pool position
	 * @param ranked whether the query orders by `relevance()`
	 * @return the response materialized by the measured call
	 */
	@Nonnull
	public EvitaResponse<EntityReference> executeQuery(int index, boolean ranked) {
		try {
			return this.session.query(
				ranked ? this.rankedQueries[index] : this.plainQueries[index], EntityReference.class);
		} catch (RuntimeException exception) {
			if (this.fileQueries.length == 0) {
				throw exception;
			}
			throw new IllegalStateException(
				"Query-file execution failed: queryHash=" + this.fileQueries[index].identity());
		}
	}

	/**
	 * Formats the census occurrence and the setup result without ever rendering its text.
	 *
	 * @param index pool position
	 * @return query identity, cohort/kind and the total count plus ordered-page identity
	 */
	@Nonnull
	private String fileIdentity(int index) {
		return this.fileQueries[index].toString() + " cohort=" + this.cohort
			+ " kind=" + this.fileQueries[index].kind()
			+ " totalRecordCount=" + this.resultIdentities[index].totalRecordCount()
			+ " pageHash=" + this.resultIdentities[index].pageHash();
	}

	/**
	 * Keeps the duration of one invocation, when a measurement iteration is running.
	 *
	 * @param index the pool position of the query the invocation ran
	 * @param nanos how long it took, in nanoseconds
	 */
	public void record(int index, long nanos) {
		if (this.recording) {
			long[] querySamples = this.samples[index];
			final int count = this.sampleCounts[index];
			if (count == querySamples.length) {
				querySamples = Arrays.copyOf(querySamples, count * 2);
				this.samples[index] = querySamples;
			}
			querySamples[count] = nanos;
			this.sampleCounts[index] = count + 1;
		}
	}

	/**
	 * Prints every query's profile with its measured median and 90th percentile, then closes the session. The engine
	 * the session queried (`FulltextCatalogFixture#getEvita`) outlives the trial, because the other cells share it, and
	 * the fixture's shutdown hook closes it.
	 */
	@TearDown(Level.Trial)
	public void tearDown() {
		try {
			reportTrial();
		} finally {
			try {
				closeSession();
			} finally {
				restoreQueryFileLogging();
			}
		}
	}

	/**
	 * Releases the trial session once. Query-file cleanup failures expose only the exception class, with no message,
	 * cause or suppressed exceptions from the original failure.
	 */
	private void closeSession() {
		final EvitaSessionContract trialSession = this.session;
		this.session = null;
		if (trialSession != null) {
			try {
				trialSession.close();
			} catch (RuntimeException | Error failure) {
				if (this.queryFile.isEmpty()) {
					throw failure;
				}
				throw new IllegalStateException("Query-file cleanup failed: " + failure.getClass().getName());
			}
		}
	}

	/** Removes only this trial's filter, preserving all preexisting logging configuration. */
	private void restoreQueryFileLogging() {
		if (this.queryFileLogFilter != null) {
			final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
			context.getTurboFilterList().removeIf(filter -> filter == this.queryFileLogFilter);
			this.queryFileLogFilter.stop();
			this.queryFileLogFilter = null;
		}
	}

	/** Prints the verified query identities and accumulated invocation durations. */
	private void reportTrial() {
		double measuredSum = 0.0;
		double modelSum = 0.0;
		int measuredQueries = 0;
		final boolean fileMode = this.fileQueries.length > 0;
		for (int i = 0; i < this.plainQueries.length; i++) {
			final FulltextQueryProfile profile = fileMode ? null : this.profiles[i];
			final int count = this.sampleCounts[i];
			final long[] sorted = Arrays.copyOf(this.samples[i], count);
			Arrays.sort(sorted);
			final long median = count == 0 ? -1L : sorted[count / 2];
			final long p90 = count == 0 ? -1L : sorted[Math.min(count - 1, (int) (count * 0.9))];
			if (count > 0) {
				measuredSum += median;
				modelSum += fileMode ? 0 : profile.getModelNanos();
				measuredQueries++;
			}
			System.out.println(
				"[fulltext-query] " + this.benchmarkName + " #" + i + " "
					+ (fileMode ? this.frequencyClass : profile.frequencyClass())
					+ " tokens=" + (fileMode ? this.tokenCount : profile.tokenCount())
					+ " postings=" + (fileMode ? 0 : profile.summedPostings())
					+ " matched="
					+ (fileMode ? this.resultIdentities[i].totalRecordCount() : profile.matchedDocuments())
					+ " modelUs=" + (fileMode ? 0 : Math.round(profile.getModelNanos() / 1_000.0))
					+ " medianUs=" + Math.round(median / 1_000.0) + " p90Us=" + Math.round(p90 / 1_000.0)
					+ " samples=" + count
					+ " text=\"" + (fileMode ? this.fileQueries[i].identity() : profile.text()) + "\""
					+ (fileMode ? " " + fileIdentity(i) : "")
			);
		}
		if (measuredQueries > 0) {
			System.out.println(
				"[fulltext-query] " + this.benchmarkName + " cell " + this.tokenCount + " x " + this.frequencyClass
					+ " summary: queries=" + measuredQueries
					+ " meanMedianUs=" + Math.round(measuredSum / measuredQueries / 1_000.0)
					+ " meanModelUs=" + Math.round(modelSum / measuredQueries / 1_000.0)
			);
		}
	}

}
