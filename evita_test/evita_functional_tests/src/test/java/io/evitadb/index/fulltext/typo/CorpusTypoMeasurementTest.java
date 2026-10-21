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

package io.evitadb.index.fulltext.typo;

import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.test.fulltext.CzechLexicon;
import io.evitadb.test.fulltext.LevenshteinDictionaryWalker;
import io.evitadb.test.fulltext.LevenshteinDictionaryWalker.Hit;
import io.evitadb.test.fulltext.SyntheticTypos;
import io.evitadb.test.fulltext.SyntheticTypos.TypoKind;
import io.evitadb.test.fulltext.TypoExpansion;
import io.evitadb.test.fulltext.TypoExpansion.ExpandedTerm;
import io.evitadb.test.fulltext.TypoExpansion.LengthBase;
import io.evitadb.test.fulltext.TypoExpansion.QueryWord;
import io.evitadb.test.fulltext.TypoExpansion.UnfinishedWordMode;
import io.evitadb.test.fulltext.TypoExpansion.UnfinishedWordRules;
import io.evitadb.test.fulltext.TypoThresholds;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.JolHeapSize;
import org.apache.lucene.analysis.cz.CzechStemmer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Re-runs the typo measurements of `typo-tolerance-fulltext-dictionary.md` §7.3, §7.4, §10 and §11 on a **real**
 * corpus instead of the hunspell fixture: the dictionary holds the index terms the production Czech chain derives
 * from real text, with their real document frequencies and postings. The corpus is the flat file
 * {@link CorpusTextExtractionTest} writes; its location is the system property `fulltext.corpus.file`, and the test is
 * skipped without it, because the corpus is supplied externally and never committed.
 *
 * The query words are drawn from the corpus itself, in two cohorts: **by token** (a word is drawn as often as it
 * occurs, so frequent words dominate — the conservative cohort for cost) and **by vocabulary** (every distinct word
 * occurring at least {@link #VOCABULARY_MIN_COUNT} times equally likely — closer to the content words people search
 * for). Typos are synthetic, as in §10.3: one substitution, insertion, deletion or transposition, placed in the first
 * letter, in the stem or in the suffix of the folded word. There is no query log, so neither cohort is a query
 * distribution; both are stated as what they are.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
@DisplayName("Typo tolerance measured on a real corpus")
@Tag(ENGINE)
@Tag(FULLTEXT)
@Tag(SLOW)
class CorpusTypoMeasurementTest {
	private static final String ENTITY_TYPE = "ARTICLE";
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	private static final int LEAF_BLOCK_SIZE = 63;
	/**
	 * Number of words per cohort in the threshold and fan-out measurements.
	 */
	private static final int WORD_COUNT = 3_000;
	/**
	 * Number of queries in the latency replay.
	 */
	private static final int QUERY_COUNT = 1_000;
	/**
	 * A distinct word joins the vocabulary cohort only when it occurs at least this many times; rarer words are
	 * dominated by the corpus's own typos, names and markup leftovers.
	 */
	private static final int VOCABULARY_MIN_COUNT = 5;
	/**
	 * The expansion cap under test (§7.1, O3) — Lucene's `FuzzyQuery.defaultMaxExpansions`.
	 */
	private static final int CAP = 50;
	/**
	 * The proposal's thresholds with the two-typo band removed — every word at most one typo.
	 */
	private static final TypoThresholds ONE_TYPO = new TypoThresholds(4, Integer.MAX_VALUE, 1, true);
	/**
	 * The rules of the submitted search's last word under pattern 1 (§11.6): run like the as-you-type query, prefix
	 * from three characters, but keeping the full thresholds of a submitted search.
	 */
	private static final UnfinishedWordRules SUBMITTED_LAST_WORD = new UnfinishedWordRules(3, 2, true);
	private static final String[] CLASSES = {"no typo (control)", "first letter", "stem", "suffix"};

	private static FulltextAnalyzerRegistry registry;
	private static FulltextAnalyzer searchAnalyzer;
	private static Corpus corpus;

	@BeforeAll
	static void loadCorpus() throws IOException {
		final String file = System.getProperty("fulltext.corpus.file");
		assumeTrue(file != null, "set fulltext.corpus.file to the output of CorpusTextExtractionTest to run");
		registry = new FulltextAnalyzerRegistry();
		searchAnalyzer = registry.getSearchAnalyzer(ENTITY_TYPE, CZECH);
		corpus = Corpus.load(Path.of(file), registry.getIndexAnalyzer(ENTITY_TYPE, CZECH));
	}

	@AfterAll
	static void closeAnalyzers() {
		if (registry != null) {
			registry.close();
		}
	}

	@Test
	@DisplayName("§7.3 and §10.3: length bases, typos out of reach on stems, and what a surface-form lane recovers")
	void shouldMeasureThresholdsAndReachOnRealVocabulary() {
		for (final Cohort cohort : Cohort.values()) {
			measureThresholds(cohort, corpus.sample(cohort, WORD_COUNT, new Random(7)));
		}
	}

	@Test
	@DisplayName("§7.4 and the cap: which stem variant finds the target, what the others cost, where the cap binds")
	void shouldMeasureHypothesisFanOutAndCap() {
		for (final Cohort cohort : Cohort.values()) {
			measureFanOut(cohort, corpus.sample(cohort, WORD_COUNT, new Random(13)));
		}
	}

	@Test
	@DisplayName("§7.1: where the one-edit and the two-edit bands should start, on stems plus the surface-form lane")
	void shouldSweepThresholdBreakpoints() {
		final int[] oneFrom = {3, 4, 5, 6};
		final int[] twoFrom = {6, 7, 8, 9, 10, Integer.MAX_VALUE};
		final String[] classes = {"no typo", "one typo", "two typos"};
		final List<Word> words = corpus.sample(Cohort.BY_VOCABULARY, WORD_COUNT, new Random(23));
		final Random random = new Random(29);
		final TypoKind[] kinds = TypoKind.values();
		final TypoThresholds widest = new TypoThresholds(1, 1, 1, true);
		// [class][one][two] → found, noise sum, words, words with two-edit budget anywhere
		final long[][][][] cells = new long[classes.length][oneFrom.length][twoFrom.length][4];
		int n = 0;
		for (final Word sample : words) {
			n++;
			for (int typoClass = 0; typoClass < classes.length; typoClass++) {
				String typed = sample.folded();
				for (int t = 0; t < typoClass && typed != null; t++) {
					typed = SyntheticTypos.apply(
						typed, kinds[(n + t) % kinds.length], 1 + random.nextInt(typed.length() - 1), random
					);
				}
				if (typed == null) {
					continue;
				}
				final List<QueryWord> analyzed = TypoExpansion.analyze(searchAnalyzer, typed);
				if (analyzed.isEmpty()) {
					continue;
				}
				final QueryWord word = analyzed.get(0);
				final Map<String, List<Hit>> stemHits = walkAll(word, 2, widest);
				final List<Hit> laneHits = new LevenshteinDictionaryWalker(
					word.typed(), 2, widest.frozenPrefix(word.typed())
				).walk(corpus.surfaces());
				for (int o = 0; o < oneFrom.length; o++) {
					for (int t = 0; t < twoFrom.length; t++) {
						final Set<String> admitted = CollectionUtils.createHashSet(64);
						boolean twoBand = false;
						for (final Map.Entry<String, List<Hit>> entry : stemHits.entrySet()) {
							final int budget = band(entry.getKey(), oneFrom[o], twoFrom[t]);
							twoBand |= budget == 2;
							for (final Hit hit : entry.getValue()) {
								if (hit.distance() <= budget) {
									admitted.add(hit.term());
								}
							}
						}
						final int laneBudget = band(word.typed(), oneFrom[o], twoFrom[t]);
						twoBand |= laneBudget == 2;
						for (final Hit hit : laneHits) {
							if (hit.distance() <= laneBudget) {
								admitted.add(corpus.stemOfSurface(hit.term()));
							}
						}
						final boolean found = admitted.contains(sample.stem());
						final long[] cell = cells[typoClass][o][t];
						cell[0] += found ? 1 : 0;
						cell[1] += admitted.size() - (found ? 1 : 0);
						cell[2]++;
						cell[3] += twoBand ? 1 : 0;
					}
				}
			}
		}
		System.out.println("[breakpoints] stems read the stem length, the lane the typed length; digits and the"
			+ " frozen first letter as in the proposal; by-vocabulary cohort, all word lengths");
		System.out.println("| one edit from | two edits from | no typo: noise mean | one typo: found | noise mean"
			+ " | two typos: found | noise mean | words walked with two edits |");
		System.out.println("|---|---|---|---|---|---|---|---|");
		for (int o = 0; o < oneFrom.length; o++) {
			for (int t = 0; t < twoFrom.length; t++) {
				if (twoFrom[t] <= oneFrom[o]) {
					continue;
				}
				final long[] none = cells[0][o][t];
				final long[] one = cells[1][o][t];
				final long[] two = cells[2][o][t];
				System.out.printf(
					Locale.ROOT, "| %d | %s | %.1f | %.1f %% | %.1f | %.1f %% | %.1f | %.1f %% |%n",
					oneFrom[o], twoFrom[t] == Integer.MAX_VALUE ? "never" : Integer.toString(twoFrom[t]),
					none[1] / (double) none[2], pct(one[0], one[2]), one[1] / (double) one[2],
					pct(two[0], two[2]), two[1] / (double) two[2], pct(none[3], none[2])
				);
			}
		}
		assertTrue(cells[0][1][2][2] > 2_000, "expected thousands of words");
	}

	/**
	 * The edit budget of a word under breakpoints: no edit below `oneFrom` letters, one below `twoFrom`, two above;
	 * none for a word with a digit.
	 */
	private static int band(@Nonnull String word, int oneFrom, int twoFrom) {
		if (TypoThresholds.hasDigit(word)) {
			return 0;
		}
		final int length = word.codePointCount(0, word.length());
		return length >= twoFrom ? 2 : length >= oneFrom ? 1 : 0;
	}

	@Test
	@DisplayName("Memory of the stem dictionary, its postings and the surface-form lane")
	void shouldMeasureSurfaceLaneMemory() {
		final long stemTree = JolHeapSize.ownedSize(corpus.stems());
		final long surfaceTree = JolHeapSize.ownedSize(corpus.surfaces());
		final long postings = JolHeapSize.ownedSize(corpus.postingsMap());
		System.out.printf(
			Locale.ROOT,
			"[memory] stem dictionary, keys and one int per key: %,d B (%d stems); postings of the stems: %,d B;"
				+ " surface-form lane, keys and one int per key (the stem id): %,d B (%d keys); lane over stems"
				+ " plus postings: +%.1f %%%n",
			stemTree, corpus.stemCount(), postings, surfaceTree, corpus.surfaceCount(),
			surfaceTree * 100.0 / (stemTree + postings)
		);
		final LaneVariants lane = corpus.laneVariants();
		final long pairTree = JolHeapSize.ownedSize(lane.pairs());
		final long displayTree = JolHeapSize.ownedSize(lane.pairsWithDisplay());
		System.out.printf(
			Locale.ROOT,
			"[memory] folded keys %d, of them with more than one stem %d (%.2f %%); distinct folded → stem pairs %d;"
				+ " pairs whose most frequent accented form differs from the folded one %d%n",
			lane.foldedKeys(), lane.multiStemKeys(), pct(lane.multiStemKeys(), lane.foldedKeys()), lane.pairCount(),
			lane.accentedPairs()
		);
		System.out.printf(
			Locale.ROOT,
			"[memory] lane as pair keys `folded␀stem`, one int (counter) per key: %,d B, +%.1f %%;"
				+ " with the accented display form in the key: %,d B, +%.1f %%%n",
			pairTree, pairTree * 100.0 / (stemTree + postings),
			displayTree, displayTree * 100.0 / (stemTree + postings)
		);
		assertTrue(surfaceTree > 0 && stemTree > 0, "both trees must be measured");
	}

	@Test
	@DisplayName("§9 item 5: what two edits buy on words of eight or more letters, with one and with two typos")
	void shouldMeasureTwoEditsOnLongWords() {
		final List<Word> pool = corpus.sample(Cohort.BY_VOCABULARY, 40_000, new Random(17));
		final List<Word> words = new ArrayList<>(WORD_COUNT);
		for (final Word word : pool) {
			if (word.folded().length() >= 8 && words.size() < WORD_COUNT) {
				words.add(word);
			}
		}
		final Random random = new Random(19);
		final TypoKind[] kinds = TypoKind.values();
		final String[] arms = {
			"two edits from 8 letters, typed length", "two edits from 8 letters, stem length",
			"two edits from 8 letters, stem length + lane", "one edit, stem length + lane"
		};
		for (int typos = 1; typos <= 2; typos++) {
			final int[] found = new int[arms.length];
			final Series[] time = new Series[arms.length];
			final Series[] terms = new Series[arms.length];
			for (int a = 0; a < arms.length; a++) {
				time[a] = new Series();
				terms[a] = new Series();
			}
			int count = 0;
			int n = 0;
			for (final Word sample : words) {
				n++;
				String typed = sample.folded();
				for (int t = 0; t < typos && typed != null; t++) {
					typed = SyntheticTypos.apply(
						typed, kinds[(n + t) % kinds.length], 1 + random.nextInt(typed.length() - 1), random
					);
				}
				if (typed == null) {
					continue;
				}
				final List<QueryWord> analyzed = TypoExpansion.analyze(searchAnalyzer, typed);
				if (analyzed.isEmpty()) {
					continue;
				}
				count++;
				final QueryWord word = analyzed.get(0);
				for (int a = 0; a < arms.length; a++) {
					final TypoThresholds thresholds = a == 3 ? ONE_TYPO : TypoThresholds.PROPOSAL;
					final long start = System.nanoTime();
					final List<ExpandedTerm> expanded = TypoExpansion.expand(
						corpus.stems(), word, thresholds, a == 0 ? LengthBase.TYPED : LengthBase.HYPOTHESIS,
						UnfinishedWordMode.FINISHED
					);
					final Set<String> admitted = CollectionUtils.createHashSet(expanded.size() + 16);
					for (final ExpandedTerm term : expanded) {
						admitted.add(term.term());
					}
					if (a >= 2) {
						final int budget = thresholds.maxEdits(word.typed());
						if (budget > 0) {
							for (final Hit hit : new LevenshteinDictionaryWalker(
								word.typed(), budget, thresholds.frozenPrefix(word.typed())
							).walk(corpus.surfaces())) {
								admitted.add(corpus.stemOfSurface(hit.term()));
							}
						}
					}
					time[a].add(System.nanoTime() - start);
					terms[a].add(admitted.size());
					found[a] += admitted.contains(sample.stem()) ? 1 : 0;
				}
			}
			for (int a = 0; a < arms.length; a++) {
				System.out.printf(
					Locale.ROOT,
					"[two edits, %d typo(s), %d words of 8+ letters] %s: found %.1f %%, terms p50/p95/p99 %s,"
						+ " expansion ms p50/p95/p99 %s%n",
					typos, count, arms[a], pct(found[a], count), terms[a].counts(), time[a].millis()
				);
			}
		}
		assertTrue(words.size() == WORD_COUNT, "expected " + WORD_COUNT + " long words, got " + words.size());
	}

	@Test
	@DisplayName("§9 items 1, 5 and 7: keystroke and submitted-query cost with real postings, levers and refinements")
	void shouldMeasureLatencyWithRealPostings() {
		final List<Query> queries = queries(corpus.sample(Cohort.BY_TOKEN, QUERY_COUNT * 3, new Random(11)));
		// warm-up over a slice of every configuration
		for (int i = 0; i < 100; i++) {
			for (final TypingConfig config : TypingConfig.values()) {
				asYouType(queries.get(i), config, new TypingStats());
			}
			for (final SubmittedConfig config : SubmittedConfig.values()) {
				submitted(queries.get(i), config, new SubmittedStats());
			}
		}
		System.out.printf(
			Locale.ROOT, "latency: %d queries, %d stems, %d folded surface forms%n",
			queries.size(), corpus.stemCount(), corpus.surfaceCount()
		);
		for (final TypingConfig config : TypingConfig.values()) {
			final TypingStats stats = new TypingStats();
			for (final Query query : queries) {
				asYouType(query, config, stats);
			}
			System.out.println(stats.report(config));
		}
		for (final SubmittedConfig config : SubmittedConfig.values()) {
			final SubmittedStats stats = new SubmittedStats();
			for (final Query query : queries) {
				submitted(query, config, stats);
			}
			System.out.println(stats.report(config));
		}
		final Refinements refinements = new Refinements();
		for (final Query query : queries) {
			refinements.measure(query);
		}
		System.out.println(refinements.report());
		assertTrue(queries.size() == QUERY_COUNT, "expected the full query set");
	}

	/**
	 * The §10.3 / §10.6 tables over one cohort, plus the surface-form lane.
	 *
	 * @param cohort the cohort
	 * @param words  the sampled words
	 */
	private static void measureThresholds(@Nonnull Cohort cohort, @Nonnull List<Word> words) {
		final TypoThresholds thresholds = TypoThresholds.PROPOSAL;
		final LengthBase[] bases = LengthBase.values();
		final Random random = new Random(7);
		final TypoKind[] kinds = TypoKind.values();
		final Stat[][] stats = new Stat[CLASSES.length][bases.length + 1];
		for (final Stat[] row : stats) {
			for (int i = 0; i < row.length; i++) {
				row[i] = new Stat();
			}
		}
		final int[][] bestDistance = new int[CLASSES.length][4];
		final int[][] laneRecovers = new int[CLASSES.length][2];
		final int[] classCount = new int[CLASSES.length];
		final long[] hypothesisCount = new long[CLASSES.length];
		final int[] lengthDifference = new int[12];
		final int[][] editsTypedVersusStem = new int[3][3];
		final Series ed1 = new Series();
		final Series ed2 = new Series();
		final List<String> unreachable = new ArrayList<>(16);
		int n = 0;
		for (final Word sample : words) {
			n++;
			final String folded = sample.folded();
			final String target = sample.stem();
			lengthDifference[Math.min(lengthDifference.length - 1, Math.max(0, folded.length() - target.length()))]++;
			editsTypedVersusStem[thresholds.maxEdits(folded)][thresholds.maxEdits(target)]++;
			final int shared = commonPrefix(folded, target);
			for (int typoClass = 0; typoClass < CLASSES.length; typoClass++) {
				final String typed = typo(folded, shared, typoClass, kinds[n % kinds.length], random);
				if (typed == null) {
					continue;
				}
				final List<QueryWord> analyzed = TypoExpansion.analyze(searchAnalyzer, typed);
				if (analyzed.isEmpty()) {
					continue;
				}
				classCount[typoClass]++;
				final QueryWord word = analyzed.get(0);
				hypothesisCount[typoClass] += word.hypotheses().size();
				final Map<String, List<Hit>> hitsByHypothesis = walkAll(word, 2, thresholds);
				int best = Integer.MAX_VALUE;
				for (final List<Hit> hits : hitsByHypothesis.values()) {
					for (final Hit hit : hits) {
						if (hit.term().equals(target)) {
							best = Math.min(best, hit.distance());
						}
					}
				}
				bestDistance[typoClass][best == Integer.MAX_VALUE ? 3 : best]++;
				if (best == Integer.MAX_VALUE && typoClass > 1 && unreachable.size() < 12) {
					unreachable.add(
						sample.surface() + " typed `" + typed + "` → " + word.hypotheses()
							+ ", index `" + target + "`"
					);
				}
				Set<String> stemBaseAdmitted = null;
				for (int p = 0; p < bases.length; p++) {
					final Set<String> admitted = admitted(hitsByHypothesis, thresholds, bases[p], word.typed());
					final boolean found = admitted.contains(target);
					stats[typoClass][p].add(found, admitted.size() - (found ? 1 : 0));
					if (bases[p] == LengthBase.HYPOTHESIS) {
						stemBaseAdmitted = admitted;
					}
				}
				// the surface-form lane: walk the folded surface forms with the typed word, map hits to their stems
				final int laneBudget = thresholds.maxEdits(word.typed());
				final Set<String> withLane = CollectionUtils.createHashSet(stemBaseAdmitted.size() + 16);
				withLane.addAll(stemBaseAdmitted);
				boolean laneFound = false;
				if (laneBudget > 0) {
					final List<Hit> laneHits = new LevenshteinDictionaryWalker(
						word.typed(), laneBudget, thresholds.frozenPrefix(word.typed())
					).walk(corpus.surfaces());
					for (final Hit hit : laneHits) {
						laneFound |= hit.term().equals(folded);
						withLane.add(corpus.stemOfSurface(hit.term()));
					}
				}
				final boolean foundWithLane = stemBaseAdmitted.contains(target) || laneFound;
				stats[typoClass][bases.length].add(foundWithLane, withLane.size() - (foundWithLane ? 1 : 0));
				if (best == Integer.MAX_VALUE) {
					laneRecovers[typoClass][laneFound ? 0 : 1]++;
				}
				final Set<String> within1 = CollectionUtils.createHashSet(32);
				final Set<String> within2 = CollectionUtils.createHashSet(64);
				for (final List<Hit> hits : hitsByHypothesis.values()) {
					for (final Hit hit : hits) {
						within2.add(hit.term());
						if (hit.distance() <= 1) {
							within1.add(hit.term());
						}
					}
				}
				ed1.add(within1.size());
				ed2.add(within2.size());
			}
		}

		final String prefix = "[thresholds, " + cohort.label + "] ";
		System.out.println(prefix + corpus.summary());
		System.out.println(prefix + "typed length minus stem length: " + Arrays.toString(lengthDifference));
		int disagreement = 0;
		for (int row = 0; row < editsTypedVersusStem.length; row++) {
			System.out.println(prefix + "edits by typed " + row + " × by stem 0,1,2: "
				+ Arrays.toString(editsTypedVersusStem[row]));
			for (int column = 0; column < editsTypedVersusStem[row].length; column++) {
				disagreement += row == column ? 0 : editsTypedVersusStem[row][column];
			}
		}
		System.out.printf(
			Locale.ROOT, "%sbudget disagreement typed vs stem: %d of %d (%.1f %%)%n",
			prefix, disagreement, classCount[0], pct(disagreement, classCount[0])
		);
		final long mistyped = (long) classCount[1] + classCount[2] + classCount[3];
		System.out.printf(
			Locale.ROOT, "%sstem variants per word: correct %.2f, mistyped %.2f%n", prefix,
			hypothesisCount[0] / (double) classCount[0],
			(hypothesisCount[1] + hypothesisCount[2] + hypothesisCount[3]) / (double) mistyped
		);
		System.out.println(
			prefix + "| typo position | words | best distance 0 | 1 | 2 | more than 2 | lane recovers |"
		);
		System.out.println(prefix + "|---|---|---|---|---|---|---|");
		for (int c = 0; c < CLASSES.length; c++) {
			final int lost = laneRecovers[c][0] + laneRecovers[c][1];
			System.out.printf(
				Locale.ROOT, "%s| %s | %d | %.1f %% | %.1f %% | %.1f %% | %.1f %% | %d of %d |%n",
				prefix, CLASSES[c], classCount[c],
				pct(bestDistance[c][0], classCount[c]), pct(bestDistance[c][1], classCount[c]),
				pct(bestDistance[c][2], classCount[c]), pct(bestDistance[c][3], classCount[c]),
				laneRecovers[c][0], lost
			);
		}
		final int stemAndSuffix = classCount[2] + classCount[3];
		final int outOfReach = bestDistance[2][3] + bestDistance[3][3];
		System.out.printf(
			Locale.ROOT, "%sout of reach over stem and suffix typos: %d of %d (%.1f %%)%n",
			prefix, outOfReach, stemAndSuffix, pct(outOfReach, stemAndSuffix)
		);
		System.out.println(prefix + "| typo position | length base | found | noise mean | noise p50 | noise p95 |");
		System.out.println(prefix + "|---|---|---|---|---|---|");
		for (int c = 0; c < CLASSES.length; c++) {
			for (int p = 0; p < bases.length; p++) {
				System.out.println(prefix + stats[c][p].row(CLASSES[c], bases[p].name()));
			}
			System.out.println(prefix + stats[c][bases.length].row(CLASSES[c], "HYPOTHESIS + surface lane"));
		}
		System.out.println(prefix + "terms within distance 1 per word: " + ed1.countSummary());
		System.out.println(prefix + "terms within distance 2 per word: " + ed2.countSummary());
		System.out.println(prefix + "examples out of reach: " + unreachable);
		assertTrue(
			bestDistance[0][0] * 100L >= classCount[0] * 99L,
			"a correctly typed word must find its own index stem, measured " + bestDistance[0][0] + " of "
				+ classCount[0]
		);
	}

	/**
	 * §7.4 over one cohort: per mistyped word, which stem variant reaches the target, what the variants cost in
	 * noise under three budget policies, and how a cap of {@link #CAP} binds per word and per variant under two
	 * orderings.
	 *
	 * @param cohort the cohort
	 * @param words  the sampled words
	 */
	private static void measureFanOut(@Nonnull Cohort cohort, @Nonnull List<Word> words) {
		final TypoThresholds thresholds = TypoThresholds.PROPOSAL;
		final CzechStemmer stemmer = new CzechStemmer();
		final Random random = new Random(13);
		final TypoKind[] kinds = TypoKind.values();
		final int[] variantCounts = new int[8];
		int words2plus = 0;
		int straightPresent = 0;
		// who reaches the target within the stem-base budget: straight only, others only, both, nobody
		final int[] reach = new int[4];
		final String[] policies = {
			"every variant, full budget", "other variants one edit less", "straight variant only"
		};
		final Stat[] policyStats = new Stat[policies.length];
		final Stat[] controlNoise = new Stat[policies.length];
		for (int i = 0; i < policies.length; i++) {
			policyStats[i] = new Stat();
			controlNoise[i] = new Stat();
		}
		final int[] capBinds = new int[2];
		final int[] capBindsPerVariant = new int[2];
		final int[][] targetCut = new int[3][2];
		final int[] budgetBand = new int[3];
		final int[][] bindsByBand = new int[3][2];
		int mistypedCount = 0;
		int n = 0;
		for (final Word sample : words) {
			n++;
			final String folded = sample.folded();
			final String target = sample.stem();
			final int shared = commonPrefix(folded, target);
			for (int typoClass = 0; typoClass < CLASSES.length; typoClass++) {
				if (typoClass == 1) {
					// a first-letter typo is decided out of scope (U5)
					continue;
				}
				final String typed = typo(folded, shared, typoClass, kinds[n % kinds.length], random);
				if (typed == null) {
					continue;
				}
				final List<QueryWord> analyzed = TypoExpansion.analyze(searchAnalyzer, typed);
				if (analyzed.isEmpty()) {
					continue;
				}
				final QueryWord word = analyzed.get(0);
				final String straight = straight(word.typed(), stemmer);
				final Map<String, List<Hit>> hits = walkAll(word, 2, thresholds);
				final List<Set<String>> byPolicy = new ArrayList<>(policies.length);
				for (int p = 0; p < policies.length; p++) {
					byPolicy.add(admittedByPolicy(hits, thresholds, straight, p));
				}
				if (typoClass == 0) {
					for (int p = 0; p < policies.length; p++) {
						final boolean found = byPolicy.get(p).contains(target);
						controlNoise[p].add(found, byPolicy.get(p).size() - (found ? 1 : 0));
					}
					continue;
				}
				mistypedCount++;
				variantCounts[Math.min(variantCounts.length - 1, word.hypotheses().size())]++;
				words2plus += word.hypotheses().size() > 1 ? 1 : 0;
				straightPresent += word.hypotheses().contains(straight) ? 1 : 0;
				boolean byStraight = false;
				boolean byOthers = false;
				for (final Map.Entry<String, List<Hit>> entry : hits.entrySet()) {
					final int budget = thresholds.maxEdits(entry.getKey());
					for (final Hit hit : entry.getValue()) {
						if (hit.distance() <= budget && hit.term().equals(target)) {
							if (entry.getKey().equals(straight)) {
								byStraight = true;
							} else {
								byOthers = true;
							}
						}
					}
				}
				reach[byStraight ? (byOthers ? 2 : 0) : (byOthers ? 1 : 3)]++;
				for (int p = 0; p < policies.length; p++) {
					final boolean found = byPolicy.get(p).contains(target);
					policyStats[p].add(found, byPolicy.get(p).size() - (found ? 1 : 0));
				}
				// the cap, on the full policy: per word over the union, per variant over each variant's own hits
				final Map<String, Integer> distances = distances(hits, thresholds, straight, 0);
				final int band = thresholds.maxEdits(word.typed());
				budgetBand[band]++;
				final boolean binds = distances.size() > CAP;
				capBinds[binds ? 0 : 1]++;
				bindsByBand[band][binds ? 0 : 1]++;
				boolean variantBinds = false;
				for (final Map.Entry<String, List<Hit>> entry : hits.entrySet()) {
					int admittedHere = 0;
					final int budget = thresholds.maxEdits(entry.getKey());
					for (final Hit hit : entry.getValue()) {
						admittedHere += hit.distance() <= budget ? 1 : 0;
					}
					variantBinds |= admittedHere > CAP;
				}
				capBindsPerVariant[variantBinds ? 0 : 1]++;
				if (distances.containsKey(target)) {
					final List<String> alphabetical = new ArrayList<>(distances.keySet());
					alphabetical.sort(Comparator.<String>comparingInt(distances::get).thenComparing(t -> t));
					final List<String> byFrequency = new ArrayList<>(distances.keySet());
					byFrequency.sort(
						Comparator.<String>comparingInt(distances::get)
							.thenComparing(Comparator.comparingInt(corpus::documentFrequency).reversed())
							.thenComparing(t -> t)
					);
					final List<String> frequencyOnly = new ArrayList<>(distances.keySet());
					frequencyOnly.sort(
						Comparator.comparingInt(corpus::documentFrequency).reversed().thenComparing(t -> t)
					);
					final List<List<String>> orders = List.of(alphabetical, byFrequency, frequencyOnly);
					for (int o = 0; o < orders.size(); o++) {
						final int rank = orders.get(o).indexOf(target);
						targetCut[o][rank >= CAP ? 0 : 1]++;
					}
				}
			}
		}
		final String prefix = "[fan-out, " + cohort.label + "] ";
		System.out.printf(
			Locale.ROOT, "%smistyped words (stem and suffix typos): %d; variants per word %s; two or more: %.1f %%;"
				+ " straight variant among them: %.1f %%%n",
			prefix, mistypedCount, Arrays.toString(variantCounts), pct(words2plus, mistypedCount),
			pct(straightPresent, mistypedCount)
		);
		System.out.printf(
			Locale.ROOT, "%starget reached by the straight variant only %.1f %%, by other variants only %.1f %%,"
				+ " by both %.1f %%, by none %.1f %%%n",
			prefix, pct(reach[0], mistypedCount), pct(reach[1], mistypedCount), pct(reach[2], mistypedCount),
			pct(reach[3], mistypedCount)
		);
		System.out.println(prefix + "| policy | found (mistyped) | noise mean | p50 | p95 | noise on correct words mean"
			+ " | p95 |");
		System.out.println(prefix + "|---|---|---|---|---|---|---|");
		for (int p = 0; p < policies.length; p++) {
			System.out.println(prefix + policyStats[p].policyRow(policies[p], controlNoise[p]));
		}
		System.out.printf(
			Locale.ROOT, "%scap %d binds per word on %.1f %% of mistyped words, per variant on %.1f %%;"
				+ " by budget band 0/1/2 edits: %s%n",
			prefix, CAP, pct(capBinds[0], capBinds[0] + capBinds[1]),
			pct(capBindsPerVariant[0], capBindsPerVariant[0] + capBindsPerVariant[1]),
			bandReport(bindsByBand, budgetBand)
		);
		final String[] orderNames = {
			"distance, then alphabetical", "distance, then document frequency", "document frequency only"
		};
		for (int o = 0; o < orderNames.length; o++) {
			System.out.printf(
				Locale.ROOT, "%scap %d per word, ordered by %s: found target cut off in %d of %d (%.2f %%)%n",
				prefix, CAP, orderNames[o], targetCut[o][0], targetCut[o][0] + targetCut[o][1],
				pct(targetCut[o][0], targetCut[o][0] + targetCut[o][1])
			);
		}
	}

	/**
	 * Formats the share of words a cap binds on, per budget band.
	 */
	@Nonnull
	private static String bandReport(@Nonnull int[][] bindsByBand, @Nonnull int[] budgetBand) {
		final StringBuilder result = new StringBuilder(64);
		for (int band = 0; band < budgetBand.length; band++) {
			result.append(band == 0 ? "" : ", ")
				.append(String.format(Locale.ROOT, "%.1f %% of %d", pct(bindsByBand[band][0], budgetBand[band]),
					budgetBand[band]));
		}
		return result.toString();
	}

	/**
	 * The terms admitted under one of the three §7.4 policies, with the stem base of the thresholds.
	 *
	 * @param policy 0 every variant full budget, 1 other variants one edit less, 2 the straight variant only
	 */
	@Nonnull
	private static Set<String> admittedByPolicy(
		@Nonnull Map<String, List<Hit>> hits,
		@Nonnull TypoThresholds thresholds,
		@Nonnull String straight,
		int policy
	) {
		return distances(hits, thresholds, straight, policy).keySet();
	}

	/**
	 * The admitted terms with their smallest distance under a policy (see {@link #admittedByPolicy}). A variant that
	 * the policy denies typo expansion still contributes its exact hit.
	 */
	@Nonnull
	private static Map<String, Integer> distances(
		@Nonnull Map<String, List<Hit>> hits,
		@Nonnull TypoThresholds thresholds,
		@Nonnull String straight,
		int policy
	) {
		final Map<String, Integer> result = CollectionUtils.createHashMap(64);
		for (final Map.Entry<String, List<Hit>> entry : hits.entrySet()) {
			final boolean isStraight = entry.getKey().equals(straight);
			final int full = thresholds.maxEdits(entry.getKey());
			final int budget = switch (policy) {
				case 0 -> full;
				case 1 -> isStraight ? full : Math.max(0, full - 1);
				case 2 -> isStraight ? full : 0;
				default -> throw new IllegalStateException("Unexpected policy " + policy);
			};
			for (final Hit hit : entry.getValue()) {
				if (hit.distance() <= budget) {
					result.merge(hit.term(), hit.distance(), Math::min);
				}
			}
		}
		return result;
	}

	/**
	 * The straight stem of a folded word: Lucene's `CzechStemmer` applied to the folded text, which is the one path
	 * through the rules that does not fork on an ambiguous ending.
	 */
	@Nonnull
	private static String straight(@Nonnull String folded, @Nonnull CzechStemmer stemmer) {
		final char[] buffer = folded.toCharArray();
		final int length = stemmer.stem(buffer, buffer.length);
		return new String(buffer, 0, length);
	}

	/**
	 * Walks the stem dictionary once per hypothesis at the widest budget.
	 */
	@Nonnull
	private static Map<String, List<Hit>> walkAll(
		@Nonnull QueryWord word, int maxEdits, @Nonnull TypoThresholds thresholds
	) {
		final Map<String, List<Hit>> result = CollectionUtils.createHashMap(word.hypotheses().size());
		for (final String hypothesis : word.hypotheses()) {
			result.put(
				hypothesis,
				new LevenshteinDictionaryWalker(hypothesis, maxEdits, thresholds.frozenPrefix(hypothesis))
					.walk(corpus.stems())
			);
		}
		return result;
	}

	/**
	 * The terms a length base admits from the widest walk.
	 */
	@Nonnull
	private static Set<String> admitted(
		@Nonnull Map<String, List<Hit>> hitsByHypothesis,
		@Nonnull TypoThresholds thresholds,
		@Nonnull LengthBase base,
		@Nonnull String typed
	) {
		final Set<String> admitted = CollectionUtils.createHashSet(32);
		for (final Map.Entry<String, List<Hit>> entry : hitsByHypothesis.entrySet()) {
			final int budget = TypoExpansion.maxEdits(thresholds, base, typed, entry.getKey());
			for (final Hit hit : entry.getValue()) {
				if (hit.distance() <= budget) {
					admitted.add(hit.term());
				}
			}
		}
		return admitted;
	}

	/**
	 * Applies a synthetic typo of the given position class, or returns the word unchanged for the control class.
	 *
	 * @return the typed word, or null when the class has no position in this word
	 */
	@Nullable
	private static String typo(
		@Nonnull String folded, int shared, int typoClass, @Nonnull TypoKind kind, @Nonnull Random random
	) {
		if (typoClass == 0) {
			return folded;
		}
		final int position = switch (typoClass) {
			case 1 -> 0;
			case 2 -> shared < 2 ? -1 : 1 + random.nextInt(shared - 1);
			case 3 -> shared >= folded.length() ? -1 : shared + random.nextInt(folded.length() - shared);
			default -> throw new IllegalStateException("Unexpected typo class " + typoClass);
		};
		return position < 0 ? null : SyntheticTypos.apply(folded, kind, position, random);
	}

	/**
	 * Builds the query set: one to three words, half of the queries with one typo after the first letter.
	 */
	@Nonnull
	private static List<Query> queries(@Nonnull List<Word> pool) {
		final Random random = new Random(11);
		final TypoKind[] kinds = TypoKind.values();
		final List<Query> queries = new ArrayList<>(QUERY_COUNT);
		int cursor = 0;
		while (queries.size() < QUERY_COUNT) {
			final int wordCount = 1 + random.nextInt(3);
			final String[] typed = new String[wordCount];
			final String[] targets = new String[wordCount];
			for (int w = 0; w < wordCount; w++) {
				final Word word = pool.get(cursor++ % pool.size());
				typed[w] = word.folded();
				targets[w] = word.stem();
			}
			int typoWord = -1;
			if (random.nextBoolean()) {
				typoWord = random.nextInt(wordCount);
				final String mistyped = SyntheticTypos.apply(
					typed[typoWord], kinds[queries.size() % kinds.length],
					1 + random.nextInt(typed[typoWord].length() - 1), random
				);
				if (mistyped != null) {
					typed[typoWord] = mistyped;
				} else {
					typoWord = -1;
				}
			}
			queries.add(new Query(String.join(" ", typed), typed, targets, typoWord));
		}
		return queries;
	}

	/**
	 * Replays a query keystroke by keystroke: finished words expanded once and their postings OR-ed once, the
	 * unfinished last word expanded and OR-ed on every keystroke, the words AND-ed.
	 */
	private static void asYouType(@Nonnull Query query, @Nonnull TypingConfig config, @Nonnull TypingStats stats) {
		final Map<String, Bitmap> finished = CollectionUtils.createHashMap(4);
		final String text = query.text();
		for (int keystroke = 1; keystroke <= text.length(); keystroke++) {
			final String typed = text.substring(0, keystroke);
			final int lastSpace = typed.lastIndexOf(' ');
			if (lastSpace == typed.length() - 1) {
				// the space itself: the finished word is expanded on this keystroke, timed as such
				final long start = System.nanoTime();
				for (final QueryWord word : TypoExpansion.analyze(searchAnalyzer, typed.substring(0, lastSpace))) {
					if (!finished.containsKey(word.typed())) {
						final List<ExpandedTerm> terms = TypoExpansion.expand(
							corpus.stems(), word, ONE_TYPO, LengthBase.TYPED, UnfinishedWordMode.FINISHED,
							UnfinishedWordRules.PROPOSAL
						);
						finished.put(word.typed(), union(config.cap ? cap(terms, config.perLeg) : terms));
					}
				}
				stats.finishing.add(System.nanoTime() - start);
				continue;
			}
			final String fragment = typed.substring(lastSpace + 1);
			final long start = System.nanoTime();
			final List<QueryWord> analyzed = TypoExpansion.analyze(searchAnalyzer, fragment);
			final QueryWord word = analyzed.isEmpty() ? new QueryWord(fragment, List.of()) : analyzed.get(0);
			final UnfinishedWordMode mode = fragment.length() >= config.openEndFrom
				? UnfinishedWordMode.PREFIX_AND_OPEN_END_TYPO : UnfinishedWordMode.PREFIX_AND_TYPO;
			final List<ExpandedTerm> expanded = TypoExpansion.expand(
				corpus.stems(), word, TypoThresholds.PROPOSAL, LengthBase.TYPED, mode, UnfinishedWordRules.PROPOSAL
			);
			final List<ExpandedTerm> terms = config.cap ? cap(expanded, config.perLeg) : expanded;
			final long expandedAt = System.nanoTime();
			Bitmap result = union(terms);
			if (!finished.isEmpty() && !result.isEmpty()) {
				final List<Bitmap> operands = new ArrayList<>(finished.size() + 1);
				operands.addAll(finished.values());
				operands.add(result);
				result = intersect(operands);
			}
			final long end = System.nanoTime();
			final int wordIndex = typed.split(" ", -1).length - 1;
			boolean found = false;
			for (final ExpandedTerm term : terms) {
				if (term.term().equals(query.targets()[wordIndex])) {
					found = true;
					break;
				}
			}
			stats.add(
				fragment.length(), expandedAt - start, end - expandedAt, terms.size(), result.size(), found,
				query.typoWord() == wordIndex, query.words()[wordIndex].length() - fragment.length()
			);
		}
	}

	/**
	 * Runs a submitted query: every word but the last finished, the last run like the as-you-type query (pattern 1).
	 */
	private static void submitted(
		@Nonnull Query query, @Nonnull SubmittedConfig config, @Nonnull SubmittedStats stats
	) {
		final long start = System.nanoTime();
		final List<QueryWord> words = TypoExpansion.analyze(searchAnalyzer, query.text());
		final List<Bitmap> operands = new ArrayList<>(words.size());
		final List<List<ExpandedTerm>> expansions = new ArrayList<>(words.size());
		for (int i = 0; i < words.size(); i++) {
			final boolean last = i == words.size() - 1;
			final List<ExpandedTerm> expanded = TypoExpansion.expand(
				corpus.stems(), words.get(i), config.thresholds, LengthBase.TYPED,
				last ? UnfinishedWordMode.PREFIX_AND_OPEN_END_TYPO : UnfinishedWordMode.FINISHED,
				last ? new UnfinishedWordRules(3, config.thresholds == ONE_TYPO ? 1 : 2, true) : SUBMITTED_LAST_WORD
			);
			expansions.add(config.cap ? cap(expanded, config.perLeg) : expanded);
		}
		final long expandedAt = System.nanoTime();
		for (final List<ExpandedTerm> expansion : expansions) {
			operands.add(union(expansion));
		}
		final Bitmap result = operands.isEmpty() ? EmptyBitmap.INSTANCE : intersect(operands);
		final long end = System.nanoTime();
		int termCount = 0;
		for (final List<ExpandedTerm> expansion : expansions) {
			termCount += expansion.size();
		}
		boolean typoFound = false;
		if (query.typoWord() >= 0 && query.typoWord() < expansions.size() && words.size() == query.words().length) {
			for (final ExpandedTerm term : expansions.get(query.typoWord())) {
				typoFound |= term.term().equals(query.targets()[query.typoWord()]);
			}
		}
		stats.add(expandedAt - start, end - expandedAt, termCount, result.size(), query, typoFound);
	}

	/**
	 * Keeps the best {@link #CAP} terms of an expansion: exactness, then distance, then document frequency. Per leg,
	 * the exact terms are all kept and the prefix and the typo leg keep {@link #CAP} terms each, as Meilisearch caps
	 * its derivations per kind.
	 */
	@Nonnull
	private static List<ExpandedTerm> cap(@Nonnull List<ExpandedTerm> terms, boolean perLeg) {
		if (perLeg) {
			final List<ExpandedTerm> result = new ArrayList<>(2 * CAP + 4);
			final List<ExpandedTerm> prefix = new ArrayList<>(terms.size());
			final List<ExpandedTerm> fuzzy = new ArrayList<>(terms.size());
			for (final ExpandedTerm term : terms) {
				switch (term.exactness()) {
					case EXACT -> result.add(term);
					case PREFIX -> prefix.add(term);
					case FUZZY -> fuzzy.add(term);
					default -> throw new IllegalStateException("Unexpected exactness " + term.exactness());
				}
			}
			result.addAll(cap(prefix, false));
			result.addAll(cap(fuzzy, false));
			return result;
		}
		if (terms.size() <= CAP) {
			return terms;
		}
		final List<ExpandedTerm> sorted = new ArrayList<>(terms);
		sorted.sort(
			Comparator.comparing(ExpandedTerm::exactness)
				.thenComparingInt(ExpandedTerm::distance)
				.thenComparing(
					Comparator.comparingInt((ExpandedTerm t) -> corpus.documentFrequency(t.term())).reversed()
				)
		);
		return sorted.subList(0, CAP);
	}

	/**
	 * ORs the postings of the expanded terms with the production formula.
	 */
	@Nonnull
	private static Bitmap union(@Nonnull List<ExpandedTerm> terms) {
		final List<Bitmap> postings = new ArrayList<>(terms.size());
		for (final ExpandedTerm term : terms) {
			final Bitmap bitmap = corpus.postings(term.term());
			if (bitmap != null) {
				postings.add(bitmap);
			}
		}
		if (postings.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		} else if (postings.size() == 1) {
			return postings.get(0);
		} else {
			return new OrFormula(new long[]{1L}, postings.toArray(new Bitmap[0])).compute();
		}
	}

	/**
	 * ANDs the per-word unions with the production formula.
	 */
	@Nonnull
	private static Bitmap intersect(@Nonnull List<Bitmap> operands) {
		for (final Bitmap operand : operands) {
			if (operand.isEmpty()) {
				return EmptyBitmap.INSTANCE;
			}
		}
		return operands.size() == 1
			? operands.get(0) : new AndFormula(new long[]{1L}, operands.toArray(new Bitmap[0])).compute();
	}

	private static double pct(long part, long whole) {
		return whole == 0 ? 0.0 : part * 100.0 / whole;
	}

	private static int commonPrefix(@Nonnull String a, @Nonnull String b) {
		final int limit = Math.min(a.length(), b.length());
		int i = 0;
		while (i < limit && a.charAt(i) == b.charAt(i)) {
			i++;
		}
		return i;
	}

	/**
	 * The two ways query words are drawn from the corpus.
	 */
	private enum Cohort {
		BY_TOKEN("by token"),
		BY_VOCABULARY("by vocabulary");

		private final String label;

		Cohort(@Nonnull String label) {
			this.label = label;
		}
	}

	/**
	 * The as-you-type configurations: the §7.8 proposal, the open-ended walk started later (lever 1 of §9 item 1) and
	 * the best-first cap (lever 2).
	 */
	private enum TypingConfig {
		PROPOSAL(4, false),
		OPEN_END_FROM_5(5, false),
		OPEN_END_FROM_6(6, false),
		PROPOSAL_CAPPED(4, true, false),
		OPEN_END_FROM_5_CAPPED(5, true, false),
		PROPOSAL_CAPPED_PER_LEG(4, true, true),
		OPEN_END_FROM_5_CAPPED_PER_LEG(5, true, true);

		private final int openEndFrom;
		private final boolean cap;
		private final boolean perLeg;

		TypingConfig(int openEndFrom, boolean cap) {
			this(openEndFrom, cap, false);
		}

		TypingConfig(int openEndFrom, boolean cap, boolean perLeg) {
			this.openEndFrom = openEndFrom;
			this.cap = cap;
			this.perLeg = perLeg;
		}
	}

	/**
	 * The submitted-search configurations of §9 item 5.
	 */
	private enum SubmittedConfig {
		TWO_EDITS(TypoThresholds.PROPOSAL, false),
		ONE_EDIT(ONE_TYPO, false),
		TWO_EDITS_CAPPED(TypoThresholds.PROPOSAL, true, false),
		ONE_EDIT_CAPPED(ONE_TYPO, true, false),
		TWO_EDITS_CAPPED_PER_LEG(TypoThresholds.PROPOSAL, true, true),
		ONE_EDIT_CAPPED_PER_LEG(ONE_TYPO, true, true);

		private final TypoThresholds thresholds;
		private final boolean cap;
		private final boolean perLeg;

		SubmittedConfig(@Nonnull TypoThresholds thresholds, boolean cap) {
			this(thresholds, cap, false);
		}

		SubmittedConfig(@Nonnull TypoThresholds thresholds, boolean cap, boolean perLeg) {
			this.thresholds = thresholds;
			this.cap = cap;
			this.perLeg = perLeg;
		}
	}

	/**
	 * One sampled corpus word.
	 *
	 * @param surface the word as written, lowercased
	 * @param folded  the word lowercased and diacritics-folded, as a user typing without accents enters it
	 * @param stem    the index term the production chain derived from it
	 */
	private record Word(@Nonnull String surface, @Nonnull String folded, @Nonnull String stem) {
	}

	/**
	 * One query of the latency replay.
	 */
	private record Query(@Nonnull String text, @Nonnull String[] words, @Nonnull String[] targets, int typoWord) {
	}

	/**
	 * The real dictionary: index terms with postings, folded surface forms with their stem, and word counts.
	 */
	private static final class Corpus {
		private final TransactionalBucketBPlusTree<String> stems;
		private final TransactionalBucketBPlusTree<String> surfaces;
		private final Map<String, Bitmap> postings;
		private final Map<String, String> surfaceStems;
		private final String[] words;
		private final String[] wordStems;
		private final long[] wordCounts;
		private final String summary;
		private final LaneVariants laneVariants;

		private Corpus(
			@Nonnull TransactionalBucketBPlusTree<String> stems,
			@Nonnull TransactionalBucketBPlusTree<String> surfaces,
			@Nonnull Map<String, Bitmap> postings,
			@Nonnull Map<String, String> surfaceStems,
			@Nonnull String[] words,
			@Nonnull String[] wordStems,
			@Nonnull long[] wordCounts,
			@Nonnull String summary,
			@Nonnull LaneVariants laneVariants
		) {
			this.stems = stems;
			this.surfaces = surfaces;
			this.postings = postings;
			this.surfaceStems = surfaceStems;
			this.words = words;
			this.wordStems = wordStems;
			this.wordCounts = wordCounts;
			this.summary = summary;
			this.laneVariants = laneVariants;
		}

		/**
		 * Reads the flat file and analyses every field with the production index chain.
		 */
		@Nonnull
		static Corpus load(@Nonnull Path file, @Nonnull FulltextAnalyzer indexAnalyzer) throws IOException {
			final long start = System.nanoTime();
			final Map<String, PersistentRoaringBitmap> postings = CollectionUtils.createHashMap(2_000_000);
			// accented lowercased surface form → [count], and its index term
			final Map<String, long[]> counts = CollectionUtils.createHashMap(4_000_000);
			final Map<String, String> stemOfWord = CollectionUtils.createHashMap(4_000_000);
			final long[] tokens = new long[1];
			final int[] fields = new int[1];
			final int[] documents = new int[1];
			int lastPk = -1;
			try (final BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				for (String line = reader.readLine(); line != null; line = reader.readLine()) {
					final int firstTab = line.indexOf('\t');
					final int secondTab = line.indexOf('\t', firstTab + 1);
					final int pk = Integer.parseInt(line, 0, firstTab, 10);
					documents[0] += pk == lastPk ? 0 : 1;
					lastPk = pk;
					fields[0]++;
					indexAnalyzer.analyze(
						line.substring(secondTab + 1),
						(term, surfaceForm, startOffset, endOffset, positionIncrement) -> {
							tokens[0]++;
							postings.computeIfAbsent(term, t -> new PersistentRoaringBitmap()).add(pk);
							final String surface = surfaceForm.get().toLowerCase(Locale.ROOT);
							final long[] count = counts.get(surface);
							if (count == null) {
								counts.put(surface, new long[]{1L});
								stemOfWord.put(surface, term);
							} else {
								count[0]++;
							}
						}
					);
					if (fields[0] % 500_000 == 0) {
						System.out.printf(
							Locale.ROOT, "corpus: %,d fields, %,d tokens, %,d stems, %,d surface forms, %d s%n",
							fields[0], tokens[0], postings.size(), counts.size(),
							(System.nanoTime() - start) / 1_000_000_000L
						);
					}
				}
			}
			final long analysedAt = System.nanoTime();
			final String[] sortedStems = postings.keySet().toArray(new String[0]);
			Arrays.sort(sortedStems);
			final TransactionalBucketBPlusTree<String> stemTree =
				new TransactionalBucketBPlusTree<>(LEAF_BLOCK_SIZE, String.class);
			final Map<String, Bitmap> bitmaps = CollectionUtils.createHashMap(sortedStems.length);
			long postingCount = 0;
			for (int i = 0; i < sortedStems.length; i++) {
				stemTree.addRecord(sortedStems[i], i + 1);
				final PersistentRoaringBitmap bitmap = postings.get(sortedStems[i]);
				bitmap.runOptimize();
				postingCount += bitmap.getCardinality();
				bitmaps.put(sortedStems[i], new BaseBitmap(bitmap));
			}
			postings.clear();
			// folded surface forms with the stem of their most frequent accented form
			final Map<String, String> surfaceStems = CollectionUtils.createHashMap(counts.size());
			final Map<String, Long> surfaceBest = new HashMap<>(counts.size() * 2);
			final List<String> eligible = new ArrayList<>(counts.size());
			long surfaceCharacters = 0;
			for (final Map.Entry<String, long[]> entry : counts.entrySet()) {
				final String surface = entry.getKey();
				final String folded = CzechLexicon.fold(surface);
				final long count = entry.getValue()[0];
				final Long best = surfaceBest.get(folded);
				if (best == null || best < count) {
					surfaceBest.put(folded, count);
					surfaceStems.put(folded, stemOfWord.get(surface));
				}
				if (surface.length() >= 3 && isLetters(surface)) {
					eligible.add(surface);
				}
			}
			surfaceBest.clear();
			final LaneVariants laneVariants = LaneVariants.build(counts, stemOfWord, surfaceStems.size());
			final String[] sortedSurfaces = surfaceStems.keySet().toArray(new String[0]);
			Arrays.sort(sortedSurfaces);
			final TransactionalBucketBPlusTree<String> surfaceTree =
				new TransactionalBucketBPlusTree<>(LEAF_BLOCK_SIZE, String.class);
			for (int i = 0; i < sortedSurfaces.length; i++) {
				surfaceTree.addRecord(sortedSurfaces[i], i + 1);
				surfaceCharacters += sortedSurfaces[i].length();
			}
			eligible.sort(null);
			final String[] words = eligible.toArray(new String[0]);
			final String[] wordStems = new String[words.length];
			final long[] wordCounts = new long[words.length];
			long stemCharacters = 0;
			for (final String stem : sortedStems) {
				stemCharacters += stem.length();
			}
			for (int i = 0; i < words.length; i++) {
				wordStems[i] = stemOfWord.get(words[i]);
				wordCounts[i] = counts.get(words[i])[0];
			}
			final String summary = String.format(
				Locale.ROOT,
				"corpus: %,d documents, %,d fields, %,d tokens, %,d postings; %,d stems (%,d chars), %,d accented"
					+ " surface forms, %,d folded surface forms (%,d chars), %,d eligible words; analysed in %d s,"
					+ " built in %d s",
				documents[0], fields[0], tokens[0], postingCount, sortedStems.length, stemCharacters, counts.size(),
				sortedSurfaces.length, surfaceCharacters, words.length,
				(analysedAt - start) / 1_000_000_000L, (System.nanoTime() - analysedAt) / 1_000_000_000L
			);
			System.out.println(summary);
			return new Corpus(
				stemTree, surfaceTree, bitmaps, surfaceStems, words, wordStems, wordCounts, summary, laneVariants
			);
		}

		/**
		 * Draws words of a cohort. The token cohort draws with replacement in proportion to the occurrence count; the
		 * vocabulary cohort draws distinct words occurring at least {@link #VOCABULARY_MIN_COUNT} times, uniformly.
		 */
		@Nonnull
		List<Word> sample(@Nonnull Cohort cohort, int count, @Nonnull Random random) {
			final List<Word> result = new ArrayList<>(count);
			if (cohort == Cohort.BY_TOKEN) {
				final long[] cumulative = new long[this.words.length];
				long total = 0;
				for (int i = 0; i < this.words.length; i++) {
					total += this.wordCounts[i];
					cumulative[i] = total;
				}
				while (result.size() < count) {
					final long draw = (long) (random.nextDouble() * total);
					int index = Arrays.binarySearch(cumulative, draw + 1);
					index = index < 0 ? -index - 1 : index;
					result.add(word(index));
				}
			} else {
				final List<Integer> frequent = new ArrayList<>(this.words.length / 4);
				for (int i = 0; i < this.words.length; i++) {
					if (this.wordCounts[i] >= VOCABULARY_MIN_COUNT) {
						frequent.add(i);
					}
				}
				final Set<Integer> chosen = CollectionUtils.createHashSet(count);
				while (chosen.size() < Math.min(count, frequent.size())) {
					final int index = frequent.get(random.nextInt(frequent.size()));
					if (chosen.add(index)) {
						result.add(word(index));
					}
				}
			}
			return result;
		}

		@Nonnull
		private Word word(int index) {
			return new Word(this.words[index], CzechLexicon.fold(this.words[index]), this.wordStems[index]);
		}

		@Nonnull
		TransactionalBucketBPlusTree<String> stems() {
			return this.stems;
		}

		@Nonnull
		TransactionalBucketBPlusTree<String> surfaces() {
			return this.surfaces;
		}

		@Nonnull
		LaneVariants laneVariants() {
			return this.laneVariants;
		}

		@Nonnull
		Map<String, Bitmap> postingsMap() {
			return this.postings;
		}

		@Nullable
		Bitmap postings(@Nonnull String stem) {
			return this.postings.get(stem);
		}

		int documentFrequency(@Nonnull String stem) {
			final Bitmap bitmap = this.postings.get(stem);
			return bitmap == null ? 0 : bitmap.size();
		}

		@Nonnull
		String stemOfSurface(@Nonnull String folded) {
			return this.surfaceStems.get(folded);
		}

		int stemCount() {
			return this.postings.size();
		}

		int surfaceCount() {
			return this.surfaceStems.size();
		}

		@Nonnull
		String summary() {
			return this.summary;
		}

		private static boolean isLetters(@Nonnull String word) {
			for (int i = 0; i < word.length(); i++) {
				if (!Character.isLetter(word.charAt(i))) {
					return false;
				}
			}
			return true;
		}
	}

	/**
	 * The surface-form lane in the shape §3.4 of the document proposes: one key per distinct `folded␀stem` pair, so
	 * a folded form with several stems keeps all of them, with one int per key standing for the pair's reference
	 * counter. The second tree also appends the most frequent accented form when it differs from the folded one.
	 *
	 * @param pairs            keys `folded␀stem`, one int per key
	 * @param pairsWithDisplay keys `folded␀stem` or `folded␀stem␀accented`, one int per key
	 * @param foldedKeys       distinct folded surface forms
	 * @param multiStemKeys    folded forms that map to more than one stem
	 * @param pairCount        distinct pairs
	 * @param accentedPairs    pairs whose display form differs from the folded form
	 */
	private record LaneVariants(
		@Nonnull TransactionalBucketBPlusTree<String> pairs,
		@Nonnull TransactionalBucketBPlusTree<String> pairsWithDisplay,
		int foldedKeys,
		int multiStemKeys,
		int pairCount,
		int accentedPairs
	) {

		/**
		 * Builds both trees from the accented surface forms with their counts and stems.
		 */
		@Nonnull
		static LaneVariants build(
			@Nonnull Map<String, long[]> counts, @Nonnull Map<String, String> stemOfWord, int foldedKeys
		) {
			final Map<String, String> display = CollectionUtils.createHashMap(counts.size());
			final Map<String, Long> best = CollectionUtils.createHashMap(counts.size());
			final Map<String, String> firstStem = CollectionUtils.createHashMap(foldedKeys);
			final Set<String> multiStem = CollectionUtils.createHashSet(1_024);
			for (final Map.Entry<String, long[]> entry : counts.entrySet()) {
				final String folded = CzechLexicon.fold(entry.getKey());
				final String stem = stemOfWord.get(entry.getKey());
				final String pair = folded + '\u0000' + stem;
				final Long current = best.get(pair);
				if (current == null || current < entry.getValue()[0]) {
					best.put(pair, entry.getValue()[0]);
					display.put(pair, entry.getKey());
				}
				final String previous = firstStem.putIfAbsent(folded, stem);
				if (previous != null && !previous.equals(stem)) {
					multiStem.add(folded);
				}
			}
			final String[] sorted = display.keySet().toArray(new String[0]);
			Arrays.sort(sorted);
			final TransactionalBucketBPlusTree<String> pairs =
				new TransactionalBucketBPlusTree<>(LEAF_BLOCK_SIZE, String.class);
			final String[] withDisplay = new String[sorted.length];
			int accented = 0;
			for (int i = 0; i < sorted.length; i++) {
				pairs.addRecord(sorted[i], 1);
				final String accentedForm = display.get(sorted[i]);
				final String folded = sorted[i].substring(0, sorted[i].indexOf('\u0000'));
				if (accentedForm.equals(folded)) {
					withDisplay[i] = sorted[i];
				} else {
					withDisplay[i] = sorted[i] + '\u0000' + accentedForm;
					accented++;
				}
			}
			Arrays.sort(withDisplay);
			final TransactionalBucketBPlusTree<String> pairsWithDisplay =
				new TransactionalBucketBPlusTree<>(LEAF_BLOCK_SIZE, String.class);
			for (final String key : withDisplay) {
				pairsWithDisplay.addRecord(key, 1);
			}
			return new LaneVariants(pairs, pairsWithDisplay, foldedKeys, multiStem.size(), sorted.length, accented);
		}
	}

	/**
	 * A series of values with percentiles.
	 */
	private static final class Series {
		private int count;
		private long[] values = new long[1_024];

		void add(long value) {
			if (this.count == this.values.length) {
				this.values = Arrays.copyOf(this.values, this.count * 2);
			}
			this.values[this.count++] = value;
		}

		@Nonnull
		long[] sorted() {
			final long[] sorted = Arrays.copyOf(this.values, this.count);
			Arrays.sort(sorted);
			return sorted;
		}

		static long at(@Nonnull long[] sorted, double quantile) {
			return sorted.length == 0 ? 0 : sorted[Math.max(0, (int) Math.ceil(quantile * sorted.length) - 1)];
		}

		@Nonnull
		String millis() {
			final long[] sorted = sorted();
			return String.format(
				Locale.ROOT, "%.2f / %.2f / %.2f", at(sorted, 0.5) / 1e6, at(sorted, 0.95) / 1e6, at(sorted, 0.99) / 1e6
			);
		}

		@Nonnull
		String counts() {
			final long[] sorted = sorted();
			return at(sorted, 0.5) + " / " + at(sorted, 0.95) + " / " + at(sorted, 0.99);
		}

		@Nonnull
		String countSummary() {
			final long[] sorted = sorted();
			int over10 = 0;
			int over50 = 0;
			for (final long size : sorted) {
				over10 += size > 10 ? 1 : 0;
				over50 += size > 50 ? 1 : 0;
			}
			return String.format(
				Locale.ROOT, "p50 %d, p90 %d, p99 %d, max %d, over 10: %.1f %%, over 50: %.1f %%",
				at(sorted, 0.5), at(sorted, 0.9), at(sorted, 0.99), sorted.length == 0 ? 0 : sorted[sorted.length - 1],
				pct(over10, this.count), pct(over50, this.count)
			);
		}

		double overShare(long limit) {
			int over = 0;
			for (int i = 0; i < this.count; i++) {
				over += this.values[i] > limit ? 1 : 0;
			}
			return pct(over, this.count);
		}
	}

	/**
	 * Found and noise for one row of a recall table.
	 */
	private static final class Stat {
		private final Series noise = new Series();
		private int found;

		void add(boolean wasFound, int noiseCount) {
			this.noise.add(noiseCount);
			this.found += wasFound ? 1 : 0;
		}

		@Nonnull
		String row(@Nonnull String typoClass, @Nonnull String policy) {
			if (this.noise.count == 0) {
				return "| " + typoClass + " | " + policy + " | - | - | - | - |";
			}
			final long[] sorted = this.noise.sorted();
			long sum = 0;
			for (final long value : sorted) {
				sum += value;
			}
			return String.format(
				Locale.ROOT, "| %s | %s | %.1f %% | %.1f | %d | %d |",
				typoClass, policy, pct(this.found, this.noise.count), sum / (double) this.noise.count,
				Series.at(sorted, 0.5), Series.at(sorted, 0.95)
			);
		}

		@Nonnull
		String policyRow(@Nonnull String policy, @Nonnull Stat control) {
			final long[] sorted = this.noise.sorted();
			final long[] controlSorted = control.noise.sorted();
			return String.format(
				Locale.ROOT, "| %s | %.1f %% | %.1f | %d | %d | %.1f | %d |",
				policy, pct(this.found, this.noise.count), mean(sorted), Series.at(sorted, 0.5),
				Series.at(sorted, 0.95), mean(controlSorted), Series.at(controlSorted, 0.95)
			);
		}

		private static double mean(@Nonnull long[] values) {
			long sum = 0;
			for (final long value : values) {
				sum += value;
			}
			return values.length == 0 ? 0.0 : sum / (double) values.length;
		}
	}

	/**
	 * Keystroke statistics by fragment length.
	 */
	private static final class TypingStats {
		private static final String[] LABELS = {"1–2", "3", "4", "5", "6–7", "8+"};
		private final Series finishing = new Series();
		private final Series[] expansion = series();
		private final Series[] postings = series();
		private final Series[] total = series();
		private final Series[] terms = series();
		private final Series[] records = series();
		private final int[][] recall = new int[LABELS.length][4];

		@Nonnull
		private static Series[] series() {
			final Series[] result = new Series[LABELS.length];
			for (int i = 0; i < result.length; i++) {
				result[i] = new Series();
			}
			return result;
		}

		void add(
			int fragmentLength, long expansionNanos, long postingsNanos, int termCount, int recordCount, boolean found,
			boolean mistyped, int missing
		) {
			final int bucket = fragmentLength <= 2 ? 0 : fragmentLength <= 5 ? fragmentLength - 2
				: fragmentLength <= 7 ? 4 : 5;
			this.expansion[bucket].add(expansionNanos);
			this.postings[bucket].add(postingsNanos);
			this.total[bucket].add(expansionNanos + postingsNanos);
			this.terms[bucket].add(termCount);
			this.records[bucket].add(recordCount);
			if (missing > 0) {
				this.recall[bucket][mistyped ? 2 : 0] += found ? 1 : 0;
				this.recall[bucket][mistyped ? 3 : 1]++;
			}
		}

		@Nonnull
		String report(@Nonnull TypingConfig config) {
			final StringBuilder result = new StringBuilder(2_048);
			result.append("[as-you-type, ").append(config.name())
				.append("] keystroke finishing a word, ms p50/p95/p99: ")
				.append(this.finishing.millis()).append('\n');
			result.append("| ").append(config.name()).append(" fragment | keystrokes | term expansion ms p50/p95/p99")
				.append(" | postings ms p50/p95/p99 | total ms p50/p95/p99 | over 5 ms | terms p50/p95/p99")
				.append(" | matching records p50/p95/p99 | target found, unfinished, no typo | with typo |\n");
			result.append("|---|---|---|---|---|---|---|---|---|---|\n");
			for (int b = 0; b < LABELS.length; b++) {
				result.append(String.format(
					Locale.ROOT, "| %s | %d | %s | %s | %s | %.1f %% | %s | %s | %.1f %% | %.1f %% |%n",
					LABELS[b], this.total[b].count, this.expansion[b].millis(), this.postings[b].millis(),
					this.total[b].millis(), this.total[b].overShare(5_000_000L), this.terms[b].counts(),
					this.records[b].counts(), pct(this.recall[b][0], this.recall[b][1]),
					pct(this.recall[b][2], this.recall[b][3])
				));
			}
			return result.toString();
		}
	}

	/**
	 * Submitted-query statistics.
	 */
	private static final class SubmittedStats {
		private final Series expansion = new Series();
		private final Series postings = new Series();
		private final Series total = new Series();
		private final Series terms = new Series();
		private int typoQueries;
		private int typoFound;
		private int longTypoQueries;
		private int longTypoFound;

		void add(
			long expansionNanos, long postingsNanos, int termCount, int recordCount, @Nonnull Query query,
			boolean found
		) {
			this.expansion.add(expansionNanos);
			this.postings.add(postingsNanos);
			this.total.add(expansionNanos + postingsNanos);
			this.terms.add(termCount);
			if (query.typoWord() >= 0) {
				this.typoQueries++;
				this.typoFound += found ? 1 : 0;
				if (query.words()[query.typoWord()].length() >= 8) {
					this.longTypoQueries++;
					this.longTypoFound += found ? 1 : 0;
				}
			}
		}

		@Nonnull
		String report(@Nonnull SubmittedConfig config) {
			return String.format(
				Locale.ROOT,
				"[submitted, %s] term expansion ms p50/p95/p99 %s; postings %s; total %s; over 25 ms %.1f %%;"
					+ " terms p50/p95/p99 %s; mistyped word's target found %.1f %% (%d queries), in words of 8+"
					+ " letters %.1f %% (%d)",
				config.name(), this.expansion.millis(), this.postings.millis(), this.total.millis(),
				this.total.overShare(25_000_000L), this.terms.counts(), pct(this.typoFound, this.typoQueries),
				this.typoQueries, pct(this.longTypoFound, this.longTypoQueries), this.longTypoQueries
			);
		}
	}

	/**
	 * §9 item 7: the two noise refinements, measured over every keystroke of every unfinished, correctly typed word
	 * of three or more letters.
	 */
	private static final class Refinements {
		private int keystrokes;
		private int reachedByStemInputs;
		private int reachedByLaneOnly;
		private int unreached;
		private final Series hypothesisPrefixNoise = new Series();
		private final Series shortHypothesisNoise = new Series();
		private int guardLosesTarget;

		void measure(@Nonnull Query query) {
			for (int w = 0; w < query.words().length; w++) {
				if (w == query.typoWord()) {
					continue;
				}
				final String word = query.words()[w];
				final String target = query.targets()[w];
				for (int length = 3; length < word.length(); length++) {
					final String fragment = word.substring(0, length);
					final List<QueryWord> analyzed = TypoExpansion.analyze(searchAnalyzer, fragment);
					final List<String> hypotheses = analyzed.isEmpty() ? List.of() : analyzed.get(0).hypotheses();
					this.keystrokes++;
					final boolean byFragment = target.startsWith(fragment);
					boolean byHypothesis = false;
					boolean byLongHypothesis = false;
					final Set<String> fromHypotheses = CollectionUtils.createHashSet(64);
					final Set<String> fromShort = CollectionUtils.createHashSet(64);
					for (final String hypothesis : hypotheses) {
						final boolean isShort = hypothesis.length() < fragment.length() - 1;
						if (target.startsWith(hypothesis)) {
							byHypothesis = true;
							byLongHypothesis |= !isShort;
						}
						for (final String term : TypoExpansion.prefix(corpus.stems(), hypothesis)) {
							fromHypotheses.add(term);
							if (isShort) {
								fromShort.add(term);
							}
						}
					}
					// noise the short hypotheses alone contribute: terms no longer hypothesis or the fragment reaches
					int shortOnly = 0;
					for (final String term : fromShort) {
						boolean elsewhere = term.startsWith(fragment);
						for (final String hypothesis : hypotheses) {
							elsewhere |= hypothesis.length() >= fragment.length() - 1 && term.startsWith(hypothesis);
						}
						shortOnly += elsewhere ? 0 : 1;
					}
					this.hypothesisPrefixNoise.add(fromHypotheses.size());
					this.shortHypothesisNoise.add(shortOnly);
					if (byHypothesis && !byLongHypothesis && !byFragment) {
						this.guardLosesTarget++;
					}
					if (byFragment || byHypothesis) {
						this.reachedByStemInputs++;
					} else if (laneReaches(fragment, target)) {
						this.reachedByLaneOnly++;
					} else {
						this.unreached++;
					}
				}
			}
		}

		private static boolean laneReaches(@Nonnull String fragment, @Nonnull String target) {
			for (final String surface : TypoExpansion.prefix(corpus.surfaces(), fragment)) {
				if (target.equals(corpus.stemOfSurface(surface))) {
					return true;
				}
			}
			return false;
		}

		@Nonnull
		String report() {
			return String.format(
				Locale.ROOT,
				"[refinements] unfinished keystrokes of 3+ letters: %d; target reached by prefix of fragment or"
					+ " variants %.1f %%, only by a prefix scan of the surface-form lane %.1f %%, by neither %.1f %%%n"
					+ "[refinements] terms from prefix(variants) p50/p95/p99 %s; of them reached only through variants"
					+ " more than one letter shorter than the fragment p50/p95/p99 %s, share of keystrokes with any"
					+ " %.1f %%; target lost by dropping those variants %d (%.2f %%)",
				this.keystrokes, pct(this.reachedByStemInputs, this.keystrokes),
				pct(this.reachedByLaneOnly, this.keystrokes), pct(this.unreached, this.keystrokes),
				this.hypothesisPrefixNoise.counts(), this.shortHypothesisNoise.counts(),
				this.shortHypothesisNoise.overShare(0), this.guardLosesTarget,
				pct(this.guardLosesTarget, this.keystrokes)
			);
		}
	}
}
