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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.performance.fulltext.state.FulltextCatalogFixture.FixtureKey;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

/**
 * The fixture of `TextMatchesQueryBenchmark`: one cell of the matrix - a word count and a frequency class - with its
 * pool of verified queries, a long-lived read-only session and a record of how long each query took.
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

	/**
	 * The read-only session every invocation queries through, opened once because opening one per invocation would
	 * measure session bookkeeping rather than the search.
	 */
	@Getter private EvitaSessionContract session;
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

	/**
	 * Obtains the fixture, opens the session and prepares the queries of the cell.
	 *
	 * @param benchmarkParams what JMH runs in this trial
	 */
	@Setup(Level.Trial)
	public void setUp(@Nonnull BenchmarkParams benchmarkParams) {
		final FulltextCatalogFixture fixture = FulltextCatalogFixture.obtain(
			new FixtureKey(this.storageDirectory, this.catalogName, this.entityType, this.locale)
		);
		final List<FulltextQueryProfile> pool = fixture.getPool(this.tokenCount, this.frequencyClass);
		this.profiles = pool.toArray(FulltextQueryProfile[]::new);
		this.plainQueries = new Query[this.profiles.length];
		this.rankedQueries = new Query[this.profiles.length];
		this.samples = new long[this.profiles.length][];
		this.sampleCounts = new int[this.profiles.length];
		for (int i = 0; i < this.profiles.length; i++) {
			this.plainQueries[i] = fixture.buildQuery(this.profiles[i].text(), false, MEASURED_PAGE_SIZE);
			this.rankedQueries[i] = fixture.buildQuery(this.profiles[i].text(), true, MEASURED_PAGE_SIZE);
			this.samples[i] = new long[INITIAL_SAMPLE_CAPACITY];
		}
		this.session = fixture.getEvita().createReadOnlySession(fixture.getCatalogName());
		final String benchmark = benchmarkParams.getBenchmark();
		this.benchmarkName = benchmark.substring(benchmark.lastIndexOf('.') + 1);
		System.out.println(
			"[fulltext-query] " + this.benchmarkName + " " + fixture.getKey() + " cell " + this.tokenCount + " x "
				+ this.frequencyClass + ": " + this.profiles.length + " queries"
		);
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
		this.cursor = index + 1 == this.profiles.length ? 0 : index + 1;
		return index;
	}

	/**
	 * Returns a prepared query.
	 *
	 * @param index  the pool position
	 * @param ranked whether the query orders by `relevance()`
	 * @return the query
	 */
	@Nonnull
	public Query getQuery(int index, boolean ranked) {
		return ranked ? this.rankedQueries[index] : this.plainQueries[index];
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
	 * Prints every query's profile with its measured median and 90th percentile, then closes the session. The
	 * instance itself outlives the trial and is closed by the fixture's shutdown hook.
	 */
	@TearDown(Level.Trial)
	public void tearDown() {
		double measuredSum = 0.0;
		double modelSum = 0.0;
		int measuredQueries = 0;
		for (int i = 0; i < this.profiles.length; i++) {
			final FulltextQueryProfile profile = this.profiles[i];
			final int count = this.sampleCounts[i];
			final long[] sorted = Arrays.copyOf(this.samples[i], count);
			Arrays.sort(sorted);
			final long median = count == 0 ? -1L : sorted[count / 2];
			final long p90 = count == 0 ? -1L : sorted[Math.min(count - 1, (int) (count * 0.9))];
			if (count > 0) {
				measuredSum += median;
				modelSum += profile.getModelNanos();
				measuredQueries++;
			}
			System.out.println(
				"[fulltext-query] " + this.benchmarkName + " #" + i + " " + profile.frequencyClass()
					+ " tokens=" + profile.tokenCount() + " postings=" + profile.summedPostings()
					+ " matched=" + profile.matchedDocuments()
					+ " modelUs=" + Math.round(profile.getModelNanos() / 1_000.0)
					+ " medianUs=" + Math.round(median / 1_000.0) + " p90Us=" + Math.round(p90 / 1_000.0)
					+ " samples=" + count + " text=\"" + profile.text() + "\""
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
		if (this.session != null) {
			this.session.close();
			this.session = null;
		}
	}

}
