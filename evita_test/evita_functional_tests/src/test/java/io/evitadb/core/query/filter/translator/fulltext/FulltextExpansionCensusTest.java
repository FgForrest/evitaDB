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

import io.evitadb.api.configuration.FulltextOptions;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.index.fulltext.typo.FulltextTypoParameters;
import io.evitadb.test.fulltext.FulltextAnalysisTestSupport;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import io.evitadb.index.fulltext.FulltextTermExactness;
import io.evitadb.test.fulltext.EditDistances;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Fast correctness checks for the census and its production cap. */
@Tag(ENGINE)
@Tag(FULLTEXT)
@DisplayName("Fulltext expansion census correctness")
class FulltextExpansionCensusTest {
	@Nested
	@DisplayName("Cap configuration")
	class CapConfiguration {
		/** Unset and out-of-range values have bounded, predictable meanings. */
		@Test
		@DisplayName("Defaults to one hundred and clamps configured limits")
		void shouldResolveBoundedCap() {
			assertEquals(100, FulltextTermExpansion.resolveTermsPerLeg(null));
			assertEquals(1, FulltextTermExpansion.resolveTermsPerLeg(-10));
			assertEquals(1, FulltextTermExpansion.resolveTermsPerLeg(0));
			assertEquals(1, FulltextTermExpansion.resolveTermsPerLeg(1));
			assertEquals(200, FulltextTermExpansion.resolveTermsPerLeg(200));
			assertEquals(10_000, FulltextTermExpansion.resolveTermsPerLeg(10_000));
			assertEquals(10_000, FulltextTermExpansion.resolveTermsPerLeg(Integer.MAX_VALUE));
		}
	}
	@Nested
	@DisplayName("Offline replay")
	class OfflineReplay {
		/** The leg cap counts stems once across fields and never consumes exact terms. */
		@Test
		@DisplayName("Caps each word, profile and leg independently")
		void shouldReplayCapsAcrossFieldViews() {
			final List<FulltextExpansionCensusSupport.Word> words = List.of(word(
				hit("exact", FulltextTermExactness.EXACT, 100, true),
				hit("prefix1", FulltextTermExactness.PREFIX, 90, false),
				hit("prefix2", FulltextTermExactness.PREFIX, 80, true),
				hit("typo1", FulltextTermExactness.FUZZY, 70, false),
				hit("typo2", FulltextTermExactness.FUZZY, 60, true)));
			final List<FulltextExpansionCensusSupport.Word> capped = FulltextExpansionCensusSupport.cap(words, 1);
			assertEquals(List.of("exact", "prefix1", "typo1"), capped.getFirst().hits().stream()
				.map(FulltextExpansionCensusSupport.Hit::hash).toList());
			assertEquals(260, FulltextExpansionCensusSupport.postings(capped));
			assertEquals(1, FulltextExpansionCensusSupport.recalled(capped));
		}

		/** All exact views reserve budget before either inexact drop rule runs. */
		@Test
		@DisplayName("Skips giants or stops globally while retaining every exact expansion")
		void shouldSimulateBothDropRules() {
			final List<FulltextExpansionCensusSupport.Word> words = List.of(
				word(hit("giant", FulltextTermExactness.PREFIX, 100, false),
					hit("small", FulltextTermExactness.FUZZY, 5, true)),
				word(hit("later-exact", FulltextTermExactness.EXACT, 10, true),
					hit("later-small", FulltextTermExactness.FUZZY, 2, true)));
			final List<FulltextExpansionCensusSupport.Word> skip =
				FulltextExpansionCensusSupport.net(words, 17, false);
			final List<FulltextExpansionCensusSupport.Word> stop =
				FulltextExpansionCensusSupport.net(words, 17, true);
			assertEquals(17, FulltextExpansionCensusSupport.postings(skip));
			assertEquals(10, FulltextExpansionCensusSupport.postings(stop));
			assertEquals(2, FulltextExpansionCensusSupport.recalled(skip));
			assertEquals(1, FulltextExpansionCensusSupport.recalled(stop));
			assertEquals(10, FulltextExpansionCensusSupport.postings(
				FulltextExpansionCensusSupport.net(words, 1, false)));
		}

		/** Empty result lists agree; order matters independently of set intersection. */
		@Test
		@DisplayName("Compares top twenty membership and ordering")
		void shouldCompareTopK() {
			assertEquals(1.0, FulltextExpansionCensusSupport.overlap(List.of(), List.of()));
			assertEquals(0.5, FulltextExpansionCensusSupport.overlap(List.of(1, 2), List.of(2, 3)));
			assertEquals(1.0, FulltextExpansionCensusSupport.overlap(List.of(1, 2), List.of(2, 1)));
			assertEquals(false, FulltextExpansionCensusSupport.identical(List.of(1, 2), List.of(2, 1)));
			assertEquals(true, FulltextExpansionCensusSupport.identical(List.of(1, 2), List.of(1, 2)));
		}
	}

	@Nested
	@DisplayName("Synthetic input")
	class SyntheticInput {
		/** Fixed seeds give repeatable substitutions, insertions, deletions and swaps in each position class. */
		@Test
		@DisplayName("Generates the four edits in the stem, suffix and first-letter control")
		void shouldGenerateDeterministicTypos() {
			final List<FulltextExpansionCensusSupport.Variant> first =
				FulltextExpansionCensusSupport.variants("abcdefghij", "abcdef", new Random(42));
			assertEquals(first, FulltextExpansionCensusSupport.variants("abcdefghij", "abcdef", new Random(42)));
			for (final String position : List.of("first", "stem", "suffix")) {
				for (final String operation : List.of("substitution", "insertion", "deletion", "transposition")) {
					assertTrue(first.stream().anyMatch(v -> v.kind().equals(position + "-" + operation)));
					first.stream().filter(v -> v.kind().equals(position + "-" + operation)).forEach(v ->
						assertEquals(1, EditDistances.osaDistance("abcdefghij", v.text())));
				}
			}
			assertTrue(first.stream().anyMatch(v -> v.kind().startsWith("two-")));
			first.stream().filter(v -> v.kind().startsWith("two-")).forEach(v ->
				assertEquals(2, EditDistances.osaDistance("abcdefghij", v.text())));
			assertTrue(FulltextExpansionCensusSupport.variants("abcd", "abc", new Random(42)).stream()
				.noneMatch(v -> v.kind().startsWith("two-")));
			assertTrue(FulltextExpansionCensusSupport.variants("abc", "abc", new Random(42)).stream()
				.noneMatch(v -> v.kind().contains("substitution")));
			assertTrue(FulltextExpansionCensusSupport.variants("žluťoučký", "zlutouck", new Random(42)).stream()
				.anyMatch(v -> v.kind().equals("missing-diacritics") && v.text().equals("zlutoucky")));
		}
	}

	/** Makes a synthetic ordered leg candidate with two field frequencies. */
	private static FulltextExpansionCensusSupport.Hit hit(
		String hash, FulltextTermExactness leg, long postings, boolean target
	) {
		return new FulltextExpansionCensusSupport.Hit(hash, leg == FulltextTermExactness.FUZZY ? 1 : 0,
			leg, Map.of(0, postings - 1, 1, 1L), target, true);
	}

	/** Makes one synthetic query word and analyzer group with a target. */
	private static FulltextExpansionCensusSupport.Word word(FulltextExpansionCensusSupport.Hit... hits) {
		return new FulltextExpansionCensusSupport.Word(0, 0, 0, Set.of("target"), List.of(hits), 10);
	}
	@Nested
	@DisplayName("Saved query and result files")
	class SavedFiles {
		/**
		 * Arbitrary text lives only in the explicit output file, with duplicate samples retaining unique identities.
		 */
		@Test
		@DisplayName("Round trips Unicode and delimiters and rejects mismatched top-k files")
		void shouldRoundTripAndValidateFiles(@TempDir Path output)
			throws IOException {
			final String id = FulltextExpansionCensusSupport.hash("sample");
			final FulltextExpansionCensusSupport.Query item = new FulltextExpansionCensusSupport.Query(
				id, "head", "clean", "čaj\twith\nspace ", "čaj\twith\nspace ", 20);
			final Path queries = output.resolve("queries.tsv");
			FulltextExpansionCensusReport.writeQueries(queries, List.of(item));
			assertEquals(List.of(item), FulltextExpansionCensusReport.readQueries(queries));
			assertThrows(FileAlreadyExistsException.class,
				() -> FulltextExpansionCensusReport.writeQueries(queries, List.of(item)));
			final Path reference = output.resolve("reference.tsv");
			final Path candidate = output.resolve("candidate.tsv");
			Files.writeString(reference, "queryHash\tprimaryKeys\n" + id + "\t1,2\n");
			Files.writeString(candidate, "queryHash\tprimaryKeys\n" + id + "\t2,1\n");
			FulltextExpansionCensusReport.compare(output, reference, candidate);
			final String comparison = Files.readString(output.resolve("topk-comparison.md"));
			assertTrue(comparison.contains("| head | 1 | 20 | 1.000000 | 0.000000 |"));
			Files.writeString(candidate, "queryHash\tprimaryKeys\n");
			assertThrows(IllegalArgumentException.class,
				() -> FulltextExpansionCensusReport.compare(output, reference, candidate));
		}

		/** Exact contributions are included in leg S and net recall is conditioned on whole-query overflow. */
		@Test
		@DisplayName("Reports cap recall, summed postings, and both query-wide drop rules")
		void shouldReportReplayAggregates(@TempDir Path output)
			throws IOException {
			final FulltextExpansionCensusSupport.Query query = new FulltextExpansionCensusSupport.Query(
				FulltextExpansionCensusSupport.hash("query"), "by-token", "clean", "secret-text", "secret-target", 1);
			try (final FulltextExpansionCensusReport report = new FulltextExpansionCensusReport(
				output, new int[]{1}, new long[]{15})) {
				report.accept(query, List.of(word(hit("exact", FulltextTermExactness.EXACT, 10, false),
					hit("giant", FulltextTermExactness.PREFIX, 100, false),
					hit("small", FulltextTermExactness.FUZZY, 5, true))));
				report.accept(new FulltextExpansionCensusSupport.Query(FulltextExpansionCensusSupport.hash("wide"),
					"wide", "clean", "secret", "target", 1), List.of(
					word(hit("exact", FulltextTermExactness.EXACT, 10, false),
						hit("giant", FulltextTermExactness.PREFIX, 100, false),
						hit("small", FulltextTermExactness.FUZZY, 5, true)),
					word(hit("other-exact", FulltextTermExactness.EXACT, 7, true))));
			}
			final String summary = Files.readString(output.resolve("census-summary.md"));
			assertTrue(summary.contains("| by-token | clean | ALL | 1 | 1 | 1 | 1.000000"));
			assertTrue(summary.contains("| wide | clean | ALL | 1 | 122 | 122 | 122 | 122 |"));
			assertTrue(summary.contains("| wide | clean | EXACT | 1 | 17 | 17 | 17 | 17 |"));
			assertTrue(summary.contains(
				"| by-token | clean | 1 | 15 | 1 | 1.000000 | 1 | 1.000000 | 1.000000 | 0.000000 |"));
			assertTrue(summary.contains("word, profile, part and prefix/typo leg order"));
			assertFalse(summary.contains("secret-text"));
			assertFalse(Files.readString(output.resolve("census-rows.tsv")).contains("secret-target"));
		}
	}

	@Nested
	@DisplayName("Head phrase input")
	class HeadPhraseInput {
		/** Real-head counts weight both the original phrase and its typo, with clean targets retained verbatim. */
		@Test
		@DisplayName("Preserves CSV phrases and counts and generates one deterministic typo")
		void shouldReadHeadPhrases(@TempDir Path output) throws Exception {
			final Path head = output.resolve("head.csv");
			Files.writeString(head, "phrase;count\n\"bright; winter jackets\";12\nbright bright;7\n");
			final String property = "fulltext.census.headFile";
			final String previous = System.getProperty(property);
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				System.setProperty(property, head.toString());
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(
					FulltextAnalysisTestSupport.SCHEMA, "text", Locale.ENGLISH);
				final FulltextIndex index = new FulltextIndex();
				index.addValue(FulltextFieldKey.attribute("name"), 1, analyzer, 25, null, "bright winter jackets");
				final FulltextExpansionCensusMeasurementTest.Corpus corpus =
					new FulltextExpansionCensusMeasurementTest.Corpus(
						Map.of("bright", 10L, "winter", 10L, "jackets", 10L), List.of("bright winter jackets"), 1);
				final List<FulltextExpansionCensusMeasurementTest.Group> groups = List.of(
					new FulltextExpansionCensusMeasurementTest.Group(analyzer, List.of(), new int[]{0}));
				final FulltextTypoParameters policy = new FulltextTypoParameters(new FulltextOptions());
				final List<FulltextExpansionCensusSupport.Query> first = FulltextExpansionCensusMeasurementTest.queries(
					corpus, groups, index, policy, new Random(42), true);
				assertEquals(first, FulltextExpansionCensusMeasurementTest.queries(
					corpus, groups, index, policy, new Random(42), true));
				final List<FulltextExpansionCensusSupport.Query> rows = first.stream()
					.filter(q -> q.cohort().equals("head")).toList();
				assertEquals(4, rows.size());
				assertEquals("bright; winter jackets", rows.get(0).text());
				assertEquals(12, rows.get(0).weight());
				assertEquals(12, rows.get(1).weight());
				assertEquals(rows.get(0).text(), rows.get(1).clean());
				assertFalse(rows.get(0).text().equals(rows.get(1).text()));
				assertEquals("one-typo", rows.get(1).kind());
				assertEquals(7, rows.get(2).weight());
			} finally {
				if (previous == null) {
					System.clearProperty(property);
				} else {
					System.setProperty(property, previous);
				}
			}
		}
	}

	@Nested
	@DisplayName("Production analysis")
	class ProductionAnalysis {
		/** The intended target is the clean stem, not the original surface spelling. */
		@Test
		@DisplayName("Keeps the clean stem target after a typo and folds missing diacritics at zero edits")
		void shouldAnalyzeTargetsWithTheProductionProfile() throws ReflectiveOperationException {
			try (final FulltextAnalyzerRegistry registry =
				new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				for (final Locale locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("cs"))) {
					final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(
						FulltextAnalysisTestSupport.SCHEMA, "text", locale);
					final FulltextIndex index = new FulltextIndex();
					final String clean = locale.equals(Locale.ENGLISH) ? "jackets" : "kabáty";
					final String typed = locale.equals(Locale.ENGLISH) ? "jacktes" : "kabaty";
					index.addValue(FulltextFieldKey.attribute("name"), 1, analyzer, 25, null, clean);
					final FulltextExpansionCensusSupport.Query query = new FulltextExpansionCensusSupport.Query(
						"", "by-token", "control", typed + " ", clean + " ", 1);
					final List<FulltextExpansionCensusMeasurementTest.Group> groups = List.of(
						new FulltextExpansionCensusMeasurementTest.Group(analyzer, List.of(), new int[]{0}));
					final List<FulltextExpansionCensusSupport.Word> words = FulltextExpansionCensusMeasurementTest
						.expand(query, groups, index, new FulltextTypoParameters(new FulltextOptions()), true);
					assertEquals(1, FulltextExpansionCensusSupport.recalled(words));
					assertTrue(words.getFirst().hits().stream().anyMatch(h -> h.target() &&
						h.distance() == (locale.equals(Locale.ENGLISH) ? 1 : 0)));
				}
			}
		}
	}
}
