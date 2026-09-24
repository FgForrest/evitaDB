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

package io.evitadb.performance.substring.state;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * The fixture of `SubstringCacheRepeatBenchmark`: one `TRIGRAM` instance run with and without evitaDB's formula cache,
 * plus the {@link CacheAdmissionProbe} that says which of the two possible readings of the result is the true one.
 *
 * # The priming phase, and why it is in the setup rather than left to the warmup
 *
 * Admission is a three-stage, asynchronous process. The first execution of a formula registers a *cache adept*; the
 * `reevaluateEachSeconds` timer promotes qualifying adepts into the eden; and only the request *after* the promotion
 * computes and stores the payload that later requests can be served from. A trial whose warmup happens to be shorter
 * than two of those timer periods measures an unadmitted formula and reports it as "the cache does not help".
 *
 * The setup therefore repeats the measured query for {@link #PRIMING_MILLIS} - several times the shortened
 * re-evaluation period of {@link SubstringCacheMode} - and then reads the probe, so admission is a **recorded
 * observation made before measurement starts** rather than something inferred afterwards from a latency ratio. Both
 * arms are primed identically; on the `DISABLED` arm the priming is simply a few thousand more executions.
 *
 * # Reporting
 *
 * The eden **zeroes** its hit, miss and initialised counters at every re-evaluation cycle, so a single reading of them
 * is a sample of the last second and a later reading can legitimately be smaller than an earlier one. The probe is
 * therefore sampled after priming and again at the end of every JMH iteration, and the trial reports two maxima: the
 * largest record count ever seen - a gauge, and the verdict on whether anything was admitted - and the largest
 * interval hit count ever seen, which is what says the cache was actually *serving* invocations rather than merely
 * holding a record.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@State(Scope.Benchmark)
public class SubstringCacheRepeatState {

	/**
	 * Page size of the measured query - the same as the A/B matrix uses, so the two are comparable.
	 */
	public static final int MEASURED_PAGE_SIZE = SubstringQueryState.MEASURED_PAGE_SIZE;

	/**
	 * How long the setup repeats the query before reading the probe. Generously more than two re-evaluation periods,
	 * which is the minimum a formula needs to travel adept -> promoted -> initialised.
	 */
	private static final long PRIMING_MILLIS = 5_000L;

	/**
	 * Whether the instance runs the formula cache.
	 */
	@Param({"ENABLED", "DISABLED"})
	private SubstringCacheMode cache;

	/**
	 * How many entities, and therefore how many distinct values, the catalog holds.
	 */
	@Param({"1000", "10000", "100000"})
	private int entityCount;

	/**
	 * Which posting width the searched pattern has. It matters here for a reason that is not about selectivity: the
	 * eagerly folded result only becomes a cache adept when its estimated cost clears the shipped complexity floor, so
	 * the narrow classes are expected to be refused admission on cost grounds alone.
	 *
	 * The default list is the original five rather than the full enum, for the reason given on the same field of
	 * `SubstringQueryState`; the width-bisect classes are opt-in through `-p`.
	 */
	@Param({"COMMON", "THRESHOLD", "MEDIUM", "RARE", "NONEXISTENT"})
	private SubstringPatternClass patternClass;

	/**
	 * The verified fixture this cell is measured on.
	 */
	@Getter private SubstringCatalogFixture fixture;

	/**
	 * The read-only session every invocation queries through. Read-only matters: the cache is consulted for read-only
	 * sessions only, since a write session may hold modifications a cached result cannot know about.
	 */
	@Getter private EvitaSessionContract session;

	/**
	 * The prepared query, identical on every invocation - which is the whole point, since a cache can only help a
	 * repeated expression.
	 */
	@Getter private Query query;

	/**
	 * The probe over this instance's formula cache.
	 */
	@Getter private CacheAdmissionProbe probe;

	/**
	 * The largest record count the probe ever saw. A gauge rather than a counter, so this survives the eden's
	 * per-interval reset and is the trial's verdict on whether anything was admitted at all.
	 */
	private int maximumRecordCount;

	/**
	 * The largest interval hit count the probe ever saw. Non-zero means the cache was genuinely *serving* invocations
	 * at some sampled moment, not merely holding a record.
	 */
	private long maximumIntervalHits;

	/**
	 * How many times the probe was sampled - one per JMH iteration plus the priming read.
	 */
	private int samples;

	/**
	 * Obtains the fixture, opens the session, primes the cache and reports what was admitted.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		this.fixture = SubstringCatalogFixture.obtain(SubstringIndexArm.TRIGRAM, this.entityCount, this.cache);
		this.session = this.fixture.getEvita().createReadOnlySession(SubstringCatalogFixture.CATALOG_NAME);
		this.query = SubstringCatalogFixture.buildQuery(this.patternClass, MEASURED_PAGE_SIZE);
		this.probe = CacheAdmissionProbe.of(this.fixture.getEvita());

		final long deadline = System.currentTimeMillis() + PRIMING_MILLIS;
		long executions = 0L;
		// a bounded workload loop, not a poll: every turn is a real query, which is exactly what drives admission
		while (System.currentTimeMillis() < deadline) {
			this.session.query(this.query, EntityReference.class);
			executions++;
		}
		sample();
		System.out.println(
			"[substring-cache] cache=" + this.cache.describeSettings() + " entityCount=" + this.entityCount + " "
				+ this.fixture.getProfile(this.patternClass)
				+ " primingExecutions=" + executions + " " + this.probe.describe()
		);
		if (this.cache == SubstringCacheMode.ENABLED && this.probe.getCacheRecordCount() == 0) {
			System.out.println(
				"[substring-cache] WARNING: the eden holds no record after priming - a latency ratio near 1.0 for "
					+ "this cell means `the formula was never cached`, NOT `the cache does not help`."
			);
		}
	}

	/**
	 * Samples the probe once per JMH iteration.
	 *
	 * Sampling repeatedly is what makes the hit counter usable at all: the eden zeroes it at every re-evaluation
	 * cycle, so a single reading taken at an unlucky moment reports zero hits on a cache that is serving every
	 * invocation.
	 */
	@TearDown(Level.Iteration)
	public void sampleIteration() {
		sample();
	}

	/**
	 * Reports the trial's verdict on admission and closes the session.
	 */
	@TearDown(Level.Trial)
	public void tearDown() {
		if (this.probe != null) {
			System.out.println(
				"[substring-cache] cache=" + this.cache + " entityCount=" + this.entityCount + " "
					+ this.patternClass + " verdict: admitted=" + (this.maximumRecordCount > 0)
					+ " maxRecords=" + this.maximumRecordCount
					+ " maxIntervalHits=" + this.maximumIntervalHits
					+ " samples=" + this.samples
					+ " final: " + this.probe.describe()
			);
		}
		if (this.session != null) {
			this.session.close();
			this.session = null;
		}
	}

	/**
	 * Folds one probe reading into the trial's running maxima.
	 */
	private void sample() {
		this.maximumRecordCount = Math.max(this.maximumRecordCount, this.probe.getCacheRecordCount());
		this.maximumIntervalHits = Math.max(this.maximumIntervalHits, this.probe.getIntervalHits());
		this.samples++;
	}

}
