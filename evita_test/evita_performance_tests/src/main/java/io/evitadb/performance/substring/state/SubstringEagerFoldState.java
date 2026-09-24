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

import io.evitadb.core.query.filter.translator.attribute.AttributeContainsTranslator;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.InvertedIndex.MatchedBuckets;
import io.evitadb.index.trigram.TrigramIndex;
import io.evitadb.index.trigram.TrigramSubstringSearch;
import io.evitadb.performance.substring.state.SubstringCatalogFixture.PatternProfile;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import javax.annotation.Nonnull;
import java.util.function.BiPredicate;

/**
 * The fixture of `SubstringEagerFoldBenchmark`: the trigram index and the shared value tree of a real, booted
 * instance, reached below the query API so the two halves of the translator's substring path can be timed apart.
 *
 * # Why this reaches into the engine rather than through the query API
 *
 * `AbstractAttributeStringSearchTranslator` folds the matched buckets into a formula **eagerly**, on the line its own
 * comment marks as the one place presupposing eager evaluation. The alternative that was rejected - deferring the
 * fold behind a `BitmapSupplier` - can only save the fold itself, so the maximum possible saving of that rejected
 * option is the difference between "match" and "match and fold". No query-API measurement can separate those two,
 * because the query API only ever runs both.
 *
 * The structures are nevertheless the **real ones**: the same `TrigramIndex` and the same `InvertedIndex` the engine
 * would use, taken from a catalog that was written through the public API and switched to its transactional state.
 * The route is the one the functional suite uses - catalog, collection, global index - and requires no visibility
 * change anywhere.
 *
 * # Why a declining cell aborts
 *
 * `TrigramSubstringSearch#match` refuses - returns `null` - when the corpus is below the distinct-value floor. On such
 * a cell there is no fold to price and both benchmark methods would degenerate into measuring the same refusal,
 * yielding a delta of zero that reads exactly like "deferring the fold would save nothing". The setup therefore
 * fails the trial instead, naming the constant that declined it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@State(Scope.Benchmark)
public class SubstringEagerFoldState {

	/**
	 * The exact predicate the `contains` path applies to every candidate - taken from the translator itself rather
	 * than restated, so the verification pass measured here is the one production runs.
	 */
	private static final BiPredicate<String, String> CONTAINS = AttributeContainsTranslator.createPredicate();

	/**
	 * How many entities, and therefore how many distinct values, the catalog holds.
	 *
	 * `100` is below `TrigramSubstringSearch#MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT` and is included for symmetry
	 * with the A/B matrix; every cell at that size other than `NONEXISTENT` will abort by design, because a refused
	 * pattern has no fold to price.
	 */
	@Param({"100", "256", "1000", "10000", "100000"})
	private int entityCount;

	/**
	 * Which posting width the searched pattern has - the fold's cost is driven by how many buckets matched, so this
	 * is the axis that actually moves it.
	 *
	 * The default list is the original five rather than the full enum, for the reason given on the same field of
	 * `SubstringQueryState`; the width-bisect classes are opt-in through `-p`.
	 */
	@Param({"COMMON", "THRESHOLD", "MEDIUM", "RARE", "NONEXISTENT"})
	private SubstringPatternClass patternClass;

	/**
	 * The attribute's substring accelerator.
	 */
	@Getter private TrigramIndex trigramIndex;

	/**
	 * The shared value tree the accelerator's postings name value ids in, and the object that owns the fold.
	 */
	@Getter private InvertedIndex sharedValueTree;

	/**
	 * The raw search term, unnormalized, exactly as a query would supply it.
	 */
	@Getter private String pattern;

	/**
	 * The staleness tokens the eager fold folds in beside the leaf tokens, resolved once because they are a property
	 * of the index rather than of the query.
	 */
	@Getter private long[] versionIds;

	/**
	 * Obtains the fixture, binds the structures and refuses a cell whose accelerated path declines.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		final SubstringCatalogFixture fixture = SubstringCatalogFixture.obtain(
			SubstringIndexArm.TRIGRAM, this.entityCount, SubstringCacheMode.DISABLED
		);
		this.trigramIndex = fixture.getTrigramIndexOrThrow();
		this.sharedValueTree = fixture.getSharedValueTree();
		this.pattern = this.patternClass.getPattern();
		this.versionIds = TrigramSubstringSearch.versionIdsOf(this.trigramIndex);

		final MatchedBuckets matched = TrigramSubstringSearch.match(
			this.trigramIndex, this.sharedValueTree, this.pattern, CONTAINS
		);
		final PatternProfile profile = fixture.getProfile(this.patternClass);
		if (matched == null) {
			throw new GenericEvitaInternalError(
				"The accelerated path declines `" + this.patternClass + "` on " + this.entityCount + " distinct "
					+ "values (" + profile + "), so there is no eager fold to price here - a run of this cell would "
					+ "measure the refusal twice and report a saving of zero. The floor is "
					+ TrigramSubstringSearch.MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT + " distinct values and the "
					+ "selectivity gate is one candidate in "
					+ TrigramSubstringSearch.REQUIRED_NARROWING_FACTOR + ".",
				"The accelerated path declines this cell, so its fold cannot be priced!"
			);
		}
		System.out.println(
			"[substring-fold] entityCount=" + this.entityCount + " " + profile
				+ " foldedBuckets=" + matched.recordSets().length
				+ " leafTokens=" + matched.leafVersionIds().length
		);
	}

	/**
	 * @return the exact predicate the candidate verification applies
	 */
	@Nonnull
	public BiPredicate<String, String> getExactPredicate() {
		return CONTAINS;
	}

}
