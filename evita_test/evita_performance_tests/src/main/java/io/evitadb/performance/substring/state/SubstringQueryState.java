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
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import javax.annotation.Nonnull;

/**
 * The fixture of `SubstringQueryBenchmark`: one cell of the A/B matrix, i.e. one arm, one corpus size and one pattern
 * class, with a long-lived read-only session and a prepared query.
 *
 * The instance itself comes from {@link SubstringCatalogFixture}, which has already refused to hand it over unless
 * the arm took effect, the corpus is all-distinct, the pattern class lands on its intended posting width and the query
 * returns the corpus's own answer. Everything left here is the per-trial bookkeeping: a session, a query, and a line
 * on stdout naming the cell so a result table can be read back to the corpus it was measured on.
 *
 * The formula cache is off on this fixture - measuring an execution and measuring a cache hit rate are different
 * questions, and `SubstringCacheRepeatBenchmark` asks the second one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@State(Scope.Benchmark)
public class SubstringQueryState {

	/**
	 * Page size of the measured query. The engine computes the whole matching bitmap before it pages, so this decides
	 * how many `EntityReference` instances are materialised per invocation and nothing about the filtering work - a
	 * page of twenty is what a front store asks for.
	 */
	public static final int MEASURED_PAGE_SIZE = 20;

	/**
	 * Which schema the benchmarked attribute is declared with - the whole A/B.
	 */
	@Param({"TRIGRAM", "SCAN"})
	private SubstringIndexArm arm;

	/**
	 * How many entities, and therefore how many distinct values, the catalog holds.
	 *
	 * `100` and `256` sit either side of `TrigramSubstringSearch#MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT` on purpose:
	 * the first is refused by the floor however selective the pattern, the second is the smallest corpus the floor
	 * admits, and the pair is what prices that constant end-to-end.
	 */
	@Param({"100", "256", "1000", "10000", "100000"})
	private int entityCount;

	/**
	 * Which posting width the searched pattern has - see {@link SubstringPatternClass}.
	 *
	 * The default list is the original five, deliberately **not** the full enum: it is the matrix already measured,
	 * and a default that silently grew would stop a re-run being comparable with the results on file. The width-bisect
	 * classes are opt-in, through `-p patternClass=WIDTH_02_PCT,…`.
	 */
	@Param({"COMMON", "THRESHOLD", "MEDIUM", "RARE", "NONEXISTENT"})
	private SubstringPatternClass patternClass;

	/**
	 * The verified fixture this cell is measured on.
	 */
	@Getter private SubstringCatalogFixture fixture;

	/**
	 * The read-only session every invocation queries through, opened once because opening one per invocation would
	 * measure session bookkeeping rather than filtering.
	 */
	@Getter private EvitaSessionContract session;

	/**
	 * The prepared query, assembled once so an invocation pays for planning and execution rather than for rebuilding
	 * an identical constraint tree.
	 */
	@Getter private Query query;

	/**
	 * Obtains the fixture, opens the session and prepares the query.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		this.fixture = SubstringCatalogFixture.obtain(this.arm, this.entityCount, SubstringCacheMode.DISABLED);
		this.session = this.fixture.getEvita().createReadOnlySession(SubstringCatalogFixture.CATALOG_NAME);
		this.query = SubstringCatalogFixture.buildQuery(this.patternClass, MEASURED_PAGE_SIZE);
		System.out.println(
			"[substring-query] arm=" + this.arm + " entityCount=" + this.entityCount + " "
				+ this.fixture.getProfile(this.patternClass)
		);
	}

	/**
	 * Closes the session. The instance itself outlives the trial and is closed by the fixture's shutdown hook.
	 */
	@TearDown(Level.Trial)
	public void tearDown() {
		if (this.session != null) {
			this.session.close();
			this.session = null;
		}
	}

	/**
	 * @return the pattern class this cell searches for
	 */
	@Nonnull
	public SubstringPatternClass getPatternClass() {
		return this.patternClass;
	}

}
