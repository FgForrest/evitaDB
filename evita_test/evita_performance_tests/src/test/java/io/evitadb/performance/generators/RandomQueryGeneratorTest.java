/*
 *
 *                         _     _        ____  ____
 *               _____   _(_)___| |_ __ _|  _ \| __ )
 *              / _ \ \ / / / __| __/ _` | | | |  _ \
 *             |  __/\ V /| | |_ | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__|\__\__,_|____/|____/
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

package io.evitadb.performance.generators;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.require.AttributeHistogram;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeIsNotNull;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the candidate selection every generated query rests on. `pickRandom` chooses by walking a collection's
 * iterator to a random index, so the arithmetic that produces the index decides which candidates a benchmark can
 * ever exercise — and an index drawn from the wrong range silently narrows the field instead of failing.
 *
 * The fixtures use a `LinkedHashSet` so that "the first candidate" is a fixed, named element rather than whichever
 * one a hash order happens to yield. That is what lets the first assertion below be specific: it names the element
 * that an index starting at one would skip, instead of asserting a coverage count that a skewed distribution could
 * still satisfy.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Random query generator can select every candidate it is given")
@Tag(TEST_HARNESS)
class RandomQueryGeneratorTest implements RandomQueryGenerator {

	private static final String ENTITY_PRODUCT = "product";

	/**
	 * Enough draws that a reachable candidate is missed only with vanishing probability, while an unreachable one
	 * is missed every time.
	 */
	private static final int DRAWS = 500;

	/**
	 * A query the histogram generator can hang its requirement on. The require block is not optional: the
	 * generator merges its histogram into `existingQuery.getRequire().getChildren()`, so a query without one
	 * fails with a null dereference rather than a message.
	 */
	private static Query baseQuery() {
		return query(
			collection(ENTITY_PRODUCT),
			filterBy(attributeIsNotNull("code")),
			require(page(1, 20))
		);
	}

	/**
	 * Collects every attribute name the generator selected across repeated draws from the given candidates.
	 *
	 * @param candidates the candidate attribute names, in a deterministic iteration order
	 * @return the distinct names observed
	 */
	private Set<String> namesSelectedOver(final Set<String> candidates) {
		final Random random = new Random(42);
		final Set<String> seen = new HashSet<>();
		for (int i = 0; i < DRAWS; i++) {
			final Query generated = generateRandomAttributeHistogramQuery(baseQuery(), random, candidates);
			final List<AttributeHistogram> histograms = QueryUtils.findRequires(generated, AttributeHistogram.class);
			assertEquals(1, histograms.size(), "the generator must add exactly one attribute histogram");
			for (final String name : histograms.get(0).getAttributeNames()) {
				seen.add(name);
			}
		}
		return seen;
	}

	@Test
	@DisplayName("every candidate is reachable, including the one the collection iterates first")
	void shouldReachEveryCandidateIncludingTheFirst() {
		final Set<String> candidates = new LinkedHashSet<>(List.of("alpha", "beta", "gamma", "delta", "epsilon"));
		final Set<String> seen = namesSelectedOver(candidates);

		assertTrue(
			seen.contains("alpha"),
			"`alpha` is iterated first and was never selected across " + DRAWS + " draws, so the index the "
				+ "selection walks to cannot reach position zero"
		);
		assertEquals(
			candidates, seen,
			"every candidate must be reachable; missing " + differenceOf(candidates, seen)
		);
	}

	/**
	 * Pins behaviour that is already correct rather than behaviour that was repaired: the set-based selection
	 * has always special-cased a lone candidate. The map-based selection did not, and an index bound of zero is
	 * rejected outright — but reaching it needs an entity schema and populated attribute statistics, so that
	 * path is covered by reading and not by this test. Kept because a future simplification of the arithmetic
	 * would most likely drop the special case along with the off-by-one it compensated for.
	 */
	@Test
	@DisplayName("a lone candidate is selected rather than rejected")
	void shouldSelectTheOnlyCandidate() {
		final Set<String> candidates = new LinkedHashSet<>(List.of("solo"));
		final Set<String> seen = assertDoesNotThrow(
			() -> namesSelectedOver(candidates),
			"a single candidate must be selectable - an index bound of zero is rejected outright"
		);
		assertEquals(candidates, seen, "the only candidate must be the one selected");
	}

	@Test
	@DisplayName("a histogram requirement can span every candidate")
	void shouldBeAbleToSpanEveryCandidate() {
		final Set<String> candidates = new LinkedHashSet<>(List.of("alpha", "beta", "gamma"));
		final Random random = new Random(42);
		int widest = 0;
		for (int i = 0; i < DRAWS; i++) {
			final Query generated = generateRandomAttributeHistogramQuery(baseQuery(), random, candidates);
			final AttributeHistogram histogram = QueryUtils.findRequires(generated, AttributeHistogram.class).get(0);
			widest = Math.max(widest, histogram.getAttributeNames().length);
		}
		assertEquals(
			candidates.size(), widest,
			"the widest histogram requested " + widest + " of " + candidates.size() + " candidates, so a query "
				+ "spanning all of them is unreachable"
		);
	}

	/**
	 * Names the candidates that were never selected, so a failure says which ones rather than only that some are
	 * missing.
	 *
	 * @param candidates every candidate offered
	 * @param seen       the candidates actually selected
	 * @return the candidates that were never selected
	 */
	private static Set<String> differenceOf(final Set<String> candidates, final Set<String> seen) {
		final Set<String> missing = new LinkedHashSet<>(candidates);
		missing.removeAll(seen);
		return missing;
	}
}
