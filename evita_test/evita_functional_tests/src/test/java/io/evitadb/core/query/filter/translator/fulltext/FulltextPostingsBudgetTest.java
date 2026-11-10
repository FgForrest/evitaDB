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

package io.evitadb.core.query.filter.translator.fulltext;

import io.evitadb.core.query.algebra.fulltext.FulltextFormula;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula.ExpandedTerm;
import io.evitadb.core.query.filter.translator.fulltext.FulltextPostingsBudget.PreparedTerm;
import io.evitadb.core.query.filter.translator.fulltext.FulltextPostingsBudget.TermPreparation;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;
import io.evitadb.index.fulltext.FulltextTermExactness;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.index.fulltext.FulltextIndexTestSupport.defaultScoringPolicy;
import static io.evitadb.index.fulltext.FulltextIndexTestSupport.rankedScores;
import static io.evitadb.index.fulltext.FulltextTermExactness.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.*;

/** Predicted-time arithmetic and query-wide pruning over real posting views. */
@Tag(ENGINE)
@Tag(FULLTEXT)
@DisplayName("Fulltext postings budget")
class FulltextPostingsBudgetTest {

	@Nested
	@DisplayName("Closed worst-case cost model")
	class CostModel {
		/** Independently calculated integer floors cover both union regimes and the budget floor. */
		@ParameterizedTest
		@CsvSource({
			"1,0,3585065", "1,133879,3325183", "2,133879,3324543", "3,133879,3323903",
			"1,1000000,1643889", "2,1000000,1818962", "3,1000000,2175870",
			"1,2000000,3289513", "3,2000000,4353570", "10000,1,0"
		})
		@DisplayName("Floors the closed form across flat and proportional budgets and clamps at zero")
		void shouldComputePostingsLimit(int words, int documents, long expected) {
			assertEquals(expected, FulltextPostingsBudget.limit(words, documents));
		}
	}

	@Nested
	@DisplayName("Stop at the limit")
	class DropRule {
		/** A later exact reserves space before an earlier prefix, and stopping spans all later words/groups. */
		@Test
		@DisplayName("Reserves every exact and stops globally in production collection order")
		void shouldReserveExactsAndStopGlobally() {
			final ExpandedTerm exact = term(0, "exact", EXACT, 1, 2);
			final ExpandedTerm first = term(0, "prefix", PREFIX, 1, 2, 3);
			final ExpandedTerm overflow = term(1, "giant", FUZZY, 1, 2, 3, 4);
			final ExpandedTerm later = term(0, "small", FUZZY, 1);
			final ExpandedTerm[][][] words = {{{first}, {overflow}}, {{exact, later}}};
			final ExpandedTerm[][][] kept = FulltextPostingsBudget.retain(words, 8);
			assertArrayEquals(new ExpandedTerm[]{first}, kept[0][0]);
			assertEquals(0, kept[0][1].length);
			assertArrayEquals(new ExpandedTerm[]{exact}, kept[1][0]);
			assertArrayEquals(new ExpandedTerm[]{exact, later}, words[1][0], "The input stays intact");
			assertSame(first, kept[0][0][0]);
			assertTrue(Arrays.deepEquals(kept, FulltextPostingsBudget.retain(words, 8)));
		}

		/** Exact postings can exceed even a zero allowance without losing exact matches. */
		@Test
		@DisplayName("Keeps all exacts above the limit and removes every inexact")
		void shouldKeepExactsOverBudget() {
			final ExpandedTerm a = term(0, "a", EXACT, 1, 2);
			final ExpandedTerm b = term(0, "b", EXACT, 3);
			final ExpandedTerm[][][] kept = FulltextPostingsBudget.retain(new ExpandedTerm[][][]{
				{{term(0, "prefix", PREFIX, 1), a}}, {{b, term(0, "typo", FUZZY, 1)}}}, 0);
			assertArrayEquals(new ExpandedTerm[]{a}, kept[0][0]);
			assertArrayEquals(new ExpandedTerm[]{b}, kept[1][0]);
		}

		/** The first view fits on its own, but all views must fit before any may survive. */
		@Test
		@DisplayName("Drops a stem's field views atomically and counts overlapping postings twice")
		void shouldKeepFieldViewsTogether() {
			final ExpandedTerm[][][] words = {{{term(0, "same", PREFIX, 1, 2),
				term(1, "same", PREFIX, 1, 2), term(1, "later", FUZZY, 1)}}};
			assertEquals(0, FulltextPostingsBudget.retain(words, 3)[0][0].length);
			final ExpandedTerm[][][] kept = FulltextPostingsBudget.retain(words, 4);
			assertArrayEquals(Arrays.copyOf(words[0][0], 2), kept[0][0]);
		}

		/** Batch boundaries distinguish adjacent identical stems of different groups or split parts. */
		@Test
		@DisplayName("Keeps distinct group and part batches separate even with identical stems")
		void shouldRespectBatchBoundaries() {
			final ExpandedTerm same = term(0, "same", FUZZY, 1, 2);
			final ExpandedTerm[][][] words = {{{same}, {same}}};
			final ExpandedTerm[][][] kept = FulltextPostingsBudget.retain(words, 2);
			assertSame(words[0][0], kept[0][0]);
			assertEquals(0, kept[0][1].length);
		}

		/** Equality fits; the common case shares every array and posting/impact view. */
		@Test
		@DisplayName("Returns the original arrays when every expansion fits")
		void shouldShareUnchangedArrays() {
			final ExpandedTerm[][][] words = {{{term(0, "exact", EXACT, 1),
				term(0, "prefix", PREFIX, 1, 2)}, {term(1, "typo", FUZZY, 3)}}};
			assertSame(words, FulltextPostingsBudget.retain(words, 4));
			assertSame(words, FulltextPostingsBudget.retain(words, Long.MAX_VALUE));
		}

		/** The offline stop simulation and production keep exactly the same stems on a multi-field fixture. */
		@Test
		@DisplayName("Agrees with the census stop simulation")
		void shouldAgreeWithCensus() {
			final ExpandedTerm a = term(0, "a", EXACT, 1);
			final ExpandedTerm b = term(0, "b", PREFIX, 1, 2);
			final ExpandedTerm c = term(1, "b", PREFIX, 1, 2);
			final ExpandedTerm d = term(0, "d", FUZZY, 1);
			final ExpandedTerm[][][] words = {{{b, c, d}}, {{a, d}}};
			final List<FulltextExpansionCensusSupport.Word> census = List.of(
				censusWord(censusHit("b", PREFIX, Map.of(0, 2L, 1, 2L)),
					censusHit("d", FUZZY, Map.of(0, 1L))),
				censusWord(censusHit("a", EXACT, Map.of(0, 1L)), censusHit("d", FUZZY, Map.of(0, 1L))));
			for (final long limit : new long[]{0, 1, 3, 5, 6, 7}) {
				final ExpandedTerm[][][] kept = FulltextPostingsBudget.retain(words, limit);
				final List<FulltextExpansionCensusSupport.Word> simulated =
					FulltextExpansionCensusSupport.net(census, limit, true);
				for (int w = 0; w < words.length; w++) {
					assertEquals(simulated.get(w).hits().stream()
						.map(FulltextExpansionCensusSupport.Hit::hash).toList(),
						Arrays.stream(kept[w][0]).map(ExpandedTerm::term).distinct().toList());
				}
			}
		}
	}

	@Nested
	@DisplayName("Lifted retention")
	class LiftedRetention {
		/** Own groups keep their terms as they are and pay posting cardinality; composed groups pay their lift. */
		@Test
		@DisplayName("Keeps own-group terms unlifted and prices them by their postings")
		void shouldKeepOwnGroupTermsUnlifted() {
			final ExpandedTerm own = term(0, "own", PREFIX, 1, 2);
			final ExpandedTerm target = term(1, "target", PREFIX, 3);
			final ExpandedTerm lifted = term(-1, "target", PREFIX, 7, 8, 9);
			final TermPreparation[] preparations = {
				null, (term, remaining) -> remaining >= 4 ? new PreparedTerm(lifted, 4) : null
			};
			final ExpandedTerm[][][] words = {{{own}, {target}}};

			final ExpandedTerm[][][] fits = FulltextPostingsBudget.retainLifted(words, 6, preparations, new int[]{1});
			final ExpandedTerm[][][] overflows = FulltextPostingsBudget.retainLifted(
				words, 5, preparations, new int[]{1});

			assertSame(own, fits[0][0][0]);
			assertSame(lifted, fits[0][1][0]);
			assertArrayEquals(new ExpandedTerm[]{own}, overflows[0][0]);
			assertEquals(0, overflows[0][1].length);
			assertArrayEquals(new ExpandedTerm[]{own}, words[0][0], "The input stays intact");
			assertArrayEquals(new ExpandedTerm[]{target}, words[0][1], "The input stays intact");
		}

		/** A word's batches are laid out group by group, with each group's split parts together. */
		@Test
		@DisplayName("Prepares every part batch with the preparation of its own group, exacts first")
		void shouldMapPartBatchesToTheirGroup() {
			final List<String> first = new ArrayList<>(4);
			final List<String> second = new ArrayList<>(4);
			final TermPreparation[] preparations = {
				(term, remaining) -> {
					first.add(term.term());
					return new PreparedTerm(term, 1);
				},
				(term, remaining) -> {
					second.add(term.term());
					return new PreparedTerm(term, 1);
				}
			};
			final ExpandedTerm[][][] words = {
				{{term(0, "a0", EXACT, 1)}, {term(0, "a1", PREFIX, 1)}, {term(1, "b0", PREFIX, 1)},
					{term(1, "b1", EXACT, 1)}},
				{{term(0, "c", PREFIX, 1)}, {term(1, "d", EXACT, 1)}}
			};

			FulltextPostingsBudget.retainLifted(words, Long.MAX_VALUE, preparations, new int[]{2, 1});

			assertEquals(List.of("a0", "a1", "c"), first);
			assertEquals(List.of("b1", "d", "b0"), second);
		}

		/** One stem's field views stand or fall together, and the first overflow ends all later inexact work. */
		@Test
		@DisplayName("Drops an overflowing stem's views together and stops every later inexact term")
		void shouldStopAtFirstOverflowAcrossWords() {
			final AtomicInteger calls = new AtomicInteger();
			final TermPreparation[] preparations = {
				(term, remaining) -> {
					calls.incrementAndGet();
					final long cost = term.postings().size();
					return cost <= remaining ? new PreparedTerm(term, cost) : null;
				}
			};
			final ExpandedTerm fits = term(0, "fits", PREFIX, 1);
			final ExpandedTerm firstView = term(0, "wide", PREFIX, 1, 2);
			final ExpandedTerm secondView = term(1, "wide", PREFIX, 1, 2, 3);
			final ExpandedTerm laterExact = term(0, "exact", EXACT, 4);
			final ExpandedTerm laterSmall = term(0, "small", FUZZY, 5);
			final ExpandedTerm[][][] words = {{{fits, firstView, secondView}}, {{laterExact, laterSmall}}};

			final ExpandedTerm[][][] kept = FulltextPostingsBudget.retainLifted(
				words, 4, preparations, new int[]{1, 1});

			assertArrayEquals(new ExpandedTerm[]{fits}, kept[0][0], "The first view alone fits, its stem does not");
			assertArrayEquals(new ExpandedTerm[]{laterExact}, kept[1][0]);
			assertEquals(4, calls.get(), "The later inexact term is never prepared");
		}

		/** Exact terms are never budgeted away, so a preparation refusing one is a programming error. */
		@Test
		@DisplayName("Refuses a preparation that does not retain an exact term")
		void shouldRefuseUnretainedExact() {
			final TermPreparation[] preparations = {(term, remaining) -> null};
			final ExpandedTerm[][][] words = {{{term(0, "exact", EXACT, 1)}}};

			assertThrows(GenericEvitaInternalError.class,
				() -> FulltextPostingsBudget.retainLifted(words, Long.MAX_VALUE, preparations, new int[]{1}));
		}
	}

	@Nested
	@DisplayName("Formula construction")
	class FormulaConstruction {
		/** A group-first cut must be reflected in the part-first layout, including empty ranges. */
		@Test
		@DisplayName("Rebuilds split coverage after a group-first cut")
		void shouldRebuildCoverageAfterDropping() {
			final ExpandedTerm original = term(0, "original", EXACT, 1);
			final ExpandedTerm firstPart = term(0, "part", PREFIX, 2);
			final ExpandedTerm lastPart = term(0, "last", EXACT, 2, 3);
			final ExpandedTerm secondGroupOriginal = term(1, "other", FUZZY, 4);
			final ExpandedTerm[][][] words = {{{original}, {firstPart}, {}, {secondGroupOriginal}, {}, {lastPart}}};
			final ExpandedTerm[][][] kept = FulltextPostingsBudget.retain(words, 4);
			final FulltextFormula formula = FulltextPostingsBudget.toFormula(kept, new int[]{3},
				defaultScoringPolicy());
			assertArrayEquals(new ExpandedTerm[]{original, firstPart, lastPart}, formula.getTokens()[0]);
			assertArrayEquals(new int[]{1, 2, 3}, formula.compute().getArray());
			final Result scores = rankedScores(formula, new int[]{1, 2, 3}, 3);
			assertArrayEquals(new int[]{1, 2, 3}, scores.primaryKeys());
			assertArrayEquals(new long[]{2, 2, 1}, Arrays.stream(scores.composites()).map(it -> it >>> 56).toArray());
		}

		/** A mandatory word emptied by the net stays mandatory. */
		@Test
		@DisplayName("Matches nothing when pruning empties a word")
		void shouldKeepEmptyWordsMandatory() {
			final ExpandedTerm[][][] words = {{{term(0, "a", EXACT, 1)}}, {{term(0, "b", PREFIX, 1)}}};
			final FulltextFormula formula = FulltextPostingsBudget.toFormula(
				FulltextPostingsBudget.retain(words, 1), new int[]{1, 1}, defaultScoringPolicy());
			assertTrue(formula.compute().isEmpty());
			assertEquals(2, formula.getTokens().length);
		}

		/** Ordinary under-budget formulas preserve membership, ranking and cache identity. */
		@Test
		@DisplayName("Leaves an ordinary query's formula unchanged")
		void shouldLeaveOrdinaryFormulaUnchanged() {
			final ExpandedTerm a = term(0, "a", EXACT, 1, 2);
			final ExpandedTerm b = term(0, "b", FUZZY, 2, 3);
			final ExpandedTerm[][][] words = {{{a, b}}, {{a}}};
			final FulltextFormula expected = new FulltextFormula(new ExpandedTerm[][]{{a, b}, {a}}, null,
				defaultScoringPolicy());
			final FulltextFormula actual = FulltextPostingsBudget.toFormula(
				FulltextPostingsBudget.retain(words, FulltextPostingsBudget.limit(2, 3)), new int[]{1, 1},
					defaultScoringPolicy());
			assertEquals(expected.getHash(), actual.getHash());
			assertArrayEquals(expected.compute().getArray(), actual.compute().getArray());
			assertArrayEquals(rankedScores(expected, new int[]{1, 2}, 2).composites(),
				rankedScores(actual, new int[]{1, 2}, 2).composites());
		}
	}

	/** Creates a real immutable posting and impact view with observable membership. */
	private static ExpandedTerm term(int field, String stem, FulltextTermExactness leg, int... postings) {
		return new ExpandedTerm(field, stem, new ArrayBitmap(postings), ImpactView.of(new byte[postings.length]),
			leg == FUZZY ? 1 : 0, leg, field, 1.0f);
	}

	/** Creates the same ordered stem batch for the offline simulator. */
	private static FulltextExpansionCensusSupport.Word censusWord(FulltextExpansionCensusSupport.Hit... hits) {
		return new FulltextExpansionCensusSupport.Word(0, 0, 0, Set.of(), List.of(hits), 0);
	}

	/** Census field cardinalities mirror the real expanded views above. */
	private static FulltextExpansionCensusSupport.Hit censusHit(
		String stem, FulltextTermExactness leg, Map<Integer, Long> fields
	) {
		return new FulltextExpansionCensusSupport.Hit(stem, leg == FUZZY ? 1 : 0, leg, fields, false, false);
	}
}
