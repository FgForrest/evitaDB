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

import com.sun.management.ThreadMXBean;
import io.evitadb.api.configuration.FulltextOptions;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula.ExpandedTerm;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextTermExactness;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.index.fulltext.typo.FulltextDictionaryWalker;
import io.evitadb.index.fulltext.typo.FulltextTypoParameters;
import io.evitadb.spi.analysis.*;
import io.evitadb.test.fulltext.EditDistances;
import io.evitadb.test.fulltext.FulltextAnalysisTestSupport;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.util.*;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.core.query.filter.translator.fulltext.FulltextSurfaceTestSupport.*;
import static io.evitadb.core.query.filter.translator.fulltext.FulltextExpansionTestSupport.*;
import static io.evitadb.test.TestTags.*;
import static io.evitadb.test.fulltext.FulltextAnalysisTestSupport.collectTerms;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real English morphology exposes recall that stem-only expansion cannot supply. */
@Tag(ENGINE)
@Tag(FULLTEXT)
@DisplayName("Surface lane expansion")
class FulltextSurfaceExpansionTest {
	/** Effective production cap shared by both dictionaries. */
	private static final int CAP = effectiveTermsPerLeg();
	/** Lane recall and matching class through real index analysis. */
	@Nested
	@DisplayName("English stemming recall")
	class Recall {
		/** Derives the target and typo stems with the shipped text analyzer before asserting lane recall. */
		@Test
		@DisplayName("Recovers a transposition whose Porter stems exceed the typed edit budget")
		void shouldRecoverStemmerDistortedTypo() {
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(
					FulltextAnalysisTestSupport.SCHEMA, "text", Locale.ENGLISH);
				assertEquals(List.of("run"), collectTerms(analyzer.indexSurface(), "running")
					.stream().map(t -> t.term()).toList());
				final String[] variants = collectTerms(analyzer.word(), "runnign")
					.stream().map(t -> t.term()).toArray(String[]::new);
				assertArrayEquals(new String[]{"runnign"}, variants);
				assertTrue(EditDistances.distance("run", variants[0], true) > 1);
				final List<ExpandedTerm> actual = expand(analyzer, "runnign", false, variants);
				assertEquals(1, actual.size());
				assertEquals("run", actual.getFirst().term());
				assertEquals(1, actual.getFirst().distance());
				assertEquals(FulltextTermExactness.FUZZY, actual.getFirst().exactness());
			}
		}

		/** The intermediate ending is longer than its indexed stem and has no stem-prefix path. */
		@Test
		@DisplayName("Completes a mid-ending keystroke through the folded surface prefix")
		void shouldCompleteMidEnding() {
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(
					FulltextAnalysisTestSupport.SCHEMA, "text", Locale.ENGLISH);
				final String[] variants = collectTerms(analyzer.word(), "runni")
					.stream().map(t -> t.term()).toArray(String[]::new);
				for (final String variant : variants) {
					assertFalse("run".startsWith(variant));
				}
				final List<ExpandedTerm> actual = expand(analyzer, "runni", true, variants);
				assertEquals(1, actual.size());
				assertEquals("run", actual.getFirst().term());
				assertEquals(FulltextTermExactness.PREFIX, actual.getFirst().exactness());
			}
		}

		/** An exact folded key supplies its index stem even when query alternatives differ. */
		@Test
		@DisplayName("Credits an exact surface hit as EXACT at distance zero")
		void shouldCreditExactSurface() {
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(
					FulltextAnalysisTestSupport.SCHEMA, "text", Locale.ENGLISH);
				final List<ExpandedTerm> actual = expand(analyzer, "running", false, "different");
				assertEquals(1, actual.size());
				assertEquals("run", actual.getFirst().term());
				assertEquals(0, actual.getFirst().distance());
				assertEquals(FulltextTermExactness.EXACT, actual.getFirst().exactness());
			}
		}
	}

	/** Profile-owned folded spaces and the common cap of each merged leg. */
	@Nested
	@DisplayName("Lane policy and merged caps")
	class Policy {
		/** With typos disabled, only the folding profile may reach an accentless exact surface. */
		@Test
		@DisplayName("Uses the profile fold instead of a universal ASCII fold")
		void shouldRespectProfileFold() {
			final CatalogSchemaContract schema = new InternalCatalogSchemaBuilder(FulltextAnalysisTestSupport.SCHEMA)
				.withFulltextAnalyzer("plain-lane", a -> a.words(lowercase()))
				.withFulltextAnalyzer("folded-lane", a -> a.words(lowercase(), asciiFolding())).toInstance();
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				for (final String profile : List.of("plain-lane", "folded-lane")) {
					final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(schema, profile, Locale.ENGLISH);
					final FulltextIndex index = new FulltextIndex();
					final FulltextFieldKey field = FulltextFieldKey.attribute("title");
					index.addValue(field, 1, analyzer, 25, null, "café");
					final String[] typed = collectTerms(analyzer.surface(), "cafe").stream()
						.map(t -> t.term()).toArray(String[]::new);
					final List<ExpandedTerm> actual = new FulltextTermExpansion(
						new FulltextTypoParameters(100, 200, 1, true, true, false, 4, 4))
						.expand(index, new int[]{index.getFieldId(field)}, new String[0], typed, new String[0], false);
					assertEquals(profile.equals("folded-lane") ? 1 : 0, actual.size());
					if (!actual.isEmpty()) {
						assertEquals(FulltextTermExactness.EXACT, actual.getFirst().exactness());
					}
				}
			}
		}

		/** A real custom stemmer provides many surface-only stems competing against ordinary stem hits. */
		@Test
		@DisplayName("Cuts lane and stem prefix candidates together by frequency then term")
		void shouldCapMergedPrefixLeg() {
			withCapFixture((index, id, analyzer) -> {
				final List<String> expected = new ArrayList<>();
				for (int i = 0; i <= CAP; i++) {
					final String suffix = alphabeticSuffix(i);
					index.addValue(FulltextFieldKey.attribute("title"), i + 1, analyzer, 25,
						null, "stem" + suffix, "surf" + suffix);
					expected.add("stem" + suffix);
				}
				index.addValue(FulltextFieldKey.attribute("title"), 1000, analyzer, 25, null, "surfzz");
				index.addValue(FulltextFieldKey.attribute("title"), 1001, analyzer, 25, null, "surfzz");
				final List<ExpandedTerm> actual = new FulltextTermExpansion(new FulltextTypoParameters(new FulltextOptions()))
					.expand(index, new int[]{id}, new String[]{"stem"}, new String[]{"surf"},
						new String[]{"surf"}, true);
				final List<String> prefixes = actual.stream().filter(t -> t.exactness() == FulltextTermExactness.PREFIX)
					.map(ExpandedTerm::term).toList();
				final List<String> winners = new ArrayList<>();
				winners.add("wzz");
				winners.addAll(expected.subList(0, CAP - 1));
				assertEquals(winners, prefixes);
			});
		}

		/** Both sources compete at their minimum distance before the common cap is applied. */
		@Test
		@DisplayName("Cuts lane and stem typo candidates together after merging distances")
		void shouldCapMergedTypoLeg() {
			withCapFixture((index, id, analyzer) -> {
				final SortedSet<String> stems = new TreeSet<>();
				final int suffixLength = Math.max(2, 1 + CAP / 25);
				final String querySuffix = "a".repeat(suffixLength);
				final String popularSuffix = "a".repeat(suffixLength - 1) + "z";
				int pk = 1;
				for (char letter = 'b'; letter <= 'z'; letter++) {
					for (int position = 0; position < suffixLength; position++) {
						final char[] letters = querySuffix.toCharArray();
						letters[position] = letter;
						final String suffix = new String(letters);
						index.addValue(FulltextFieldKey.attribute("title"), pk++, analyzer, 25, null,
							"stem" + suffix, "surf" + suffix);
						stems.add("stem" + suffix);
					}
				}
				index.addValue(FulltextFieldKey.attribute("title"), 1000, analyzer, 25, null, "surf" + popularSuffix);
				final List<ExpandedTerm> actual = new FulltextTermExpansion(new FulltextTypoParameters(new FulltextOptions()))
					.expand(index, new int[]{id}, new String[]{"stem" + querySuffix},
						new String[]{"surf" + querySuffix}, new String[0], false);
				final List<String> winners = new ArrayList<>();
				winners.add("w" + popularSuffix);
				winners.addAll(new ArrayList<>(stems).subList(0, CAP - 1));
				assertEquals(winners, actual.stream().map(ExpandedTerm::term).toList());
				assertTrue(actual.stream().allMatch(t -> t.distance() == 1 &&
					t.exactness() == FulltextTermExactness.FUZZY));
			});
		}
	}

	/** Allocation growth, not latency or throughput, checks discarded hits after warm-up. */
	@Nested
	@DisplayName("Discarded surface hits")
	class Allocation {
		/** A growing vocabulary either shares an exact stem or supplies distinct, heap-rejected stems. */
		@ParameterizedTest
		@CsvSource({"true,false", "false,false", "true,true", "false,true"})
		@DisplayName("Bounds allocation growth for discarded prefix and accepted typo-lane hits")
		void shouldNotAllocatePerDiscardedHit(boolean exactExcluded, boolean guided) {
			final java.lang.management.ThreadMXBean platform = ManagementFactory.getThreadMXBean();
			assumeTrue(platform instanceof ThreadMXBean);
			final ThreadMXBean threads = (ThreadMXBean) platform;
			assumeTrue(threads.isThreadAllocatedMemorySupported());
			assumeTrue(threads.isThreadAllocatedMemoryEnabled());
			withAnalyzer(new TransformingWords(exactExcluded ? "collapse" : "namespace"), analyzer -> {
				final FulltextIndex small = fanout(analyzer, 1000 + (exactExcluded ? 0 : CAP));
				final FulltextIndex large = fanout(analyzer, 10000 + (exactExcluded ? 0 : CAP));
				if (guided) {
					assertAcceptedHits(small, analyzer, 1000 + (exactExcluded ? 0 : CAP));
					assertAcceptedHits(large, analyzer, 10000 + (exactExcluded ? 0 : CAP));
				}
				for (int i = 0; i < 15; i++) {
					expandFanout(small, exactExcluded, guided);
					expandFanout(large, exactExcluded, guided);
				}

				final long smallBytes = allocated(threads, small, exactExcluded, guided);
				final long largeBytes = allocated(threads, large, exactExcluded, guided);
				assertTrue(largeBytes - smallBytes < 32768,
					"Discarded hit allocation: 1000=" + smallBytes + ", 10000=" + largeBytes +
						", growth=" + (largeBytes - smallBytes) + ", guided=" + guided + ", exact=" + exactExcluded);
			});
		}
	}

	/** Keeps the synthetic stemmer's field and real lane alive for a cap assertion. */
	private static void withCapFixture(CapAssertion assertion) {
		final List<AnalysisStepFactory> factories = new ArrayList<>(ServiceLoader.load(AnalysisStepFactory.class)
			.stream().map(ServiceLoader.Provider::get).toList());
		factories.add(new SurfaceLaneStem());
		final CatalogSchemaContract schema = new InternalCatalogSchemaBuilder(FulltextAnalysisTestSupport.SCHEMA)
			.withFulltextAnalyzer("cap-lane", a -> a.words(lowercase(), wordsStep("test-surface-lane-stem")))
			.toInstance();
		try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry(factories))) {
			final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(schema, "cap-lane", Locale.ENGLISH);
			final FulltextIndex index = new FulltextIndex();
			index.addValue(FulltextFieldKey.attribute("title"), 2000, analyzer, 25, null, "unrelated");
			assertion.accept(index, index.getFieldId(FulltextFieldKey.attribute("title")), analyzer);
		}
	}

	/** Test callback with the real registered field. */
	@FunctionalInterface
	private interface CapAssertion {
		void accept(FulltextIndex index, int fieldId, CompiledFulltextAnalyzer analyzer);
	}

	/** Maps a surface namespace into a disjoint posting namespace, with identity surface normalization. */
	private static final class SurfaceLaneStem implements FulltextWordsAnalysisStepFactory {
		@Nonnull
		@Override
		public String getName() { return "test-surface-lane-stem"; }
		@Nonnull
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() { return List.of(); }
		@Override
		public void validate(@Nonnull Map<String, Serializable> parameters) { }
		@Nonnull
		@Override
		public Set<FulltextAnalysisSide> getSides() { return Set.of(FulltextAnalysisSide.values()); }
		@Nonnull
		@Override
		public Set<String> getIndexSideParameters() { return Set.of(); }
		@Override
		public int getBehaviourVersion() { return 1; }
		@Nonnull
		@Override
		public FulltextWordsAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters, @Nonnull FulltextAnalysisContext context
		) {
			return new FulltextWordsAnalysisOperation() {
				@Nonnull
				@Override
				public Set<FulltextAnalysisTrait> getTraits(@Nonnull FulltextAnalysisSide side) {
					return Set.of(FulltextAnalysisTrait.STEMS);
				}
				@Nonnull
				@Override
				public TokenStream wrap(@Nonnull TokenStream words, @Nonnull FulltextAnalysisSide side) {
					return new TokenFilter(words) {
						private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
						@Override
						public boolean incrementToken() throws IOException {
							if (!this.input.incrementToken()) {
								return false;
							}
							final String value = this.term.toString();
							if (value.startsWith("surf")) {
								this.term.setEmpty().append("w").append(value.substring(4));
							}
							return true;
						}
					};
				}
			};
		}
	}

	/** Writes the actual built-in stem and surface pair, then runs translator expansion. */
	private static List<ExpandedTerm> expand(
		CompiledFulltextAnalyzer analyzer, String typed, boolean unfinished, String... variants
	) {
		final FulltextIndex index = new FulltextIndex();
		final FulltextFieldKey field = FulltextFieldKey.attribute("description");
		index.addValue(field, 1, analyzer, 25.0, null, "running");
		final int id = index.getFieldId(field);
		return new FulltextTermExpansion(new FulltextTypoParameters(new FulltextOptions()))
			.expand(index, new int[]{id}, variants, new String[]{typed}, new String[]{typed}, unfinished);
	}

	/** Creates equal-length alphabetical surfaces with controlled posting stems. */
	private static FulltextIndex fanout(CompiledFulltextAnalyzer analyzer, int count) {
		final StringBuilder text = new StringBuilder(count * 9);
		for (int i = 0; i < count; i++) {
			int remainder = i;
			text.append("surf");
			for (int j = 0; j < 4; j++) {
				text.append((char) ('a' + remainder % 26));
				remainder /= 26;
			}
			text.append(' ');
		}
		final FulltextIndex index = new FulltextIndex();
		index.addValue(FulltextFieldKey.attribute("title"), 1, analyzer, 25, null, text.toString());
		return index;
	}

	/** Samples minimum allocation over repeated correctness checks, after both fixture sizes have warmed up. */
	private static long allocated(ThreadMXBean threads, FulltextIndex index, boolean exactExcluded, boolean guided) {
		long minimum = Long.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			final long before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
			expandFanout(index, exactExcluded, guided);
			minimum = Math.min(minimum, threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before);
		}
		return minimum;
	}

	/** Checks the uncapped exact survivor or the capped completion survivors while comparing allocation growth. */
	private static void expandFanout(FulltextIndex index, boolean exactExcluded, boolean guided) {
		final List<ExpandedTerm> result = new FulltextTermExpansion(new FulltextTypoParameters(new FulltextOptions()))
			.expand(index, new int[]{0}, exactExcluded ? new String[]{"root"} : new String[0],
				guided ? new String[]{"sury"} : new String[0], new String[]{guided ? "sury" : "surf"}, true);
		assertEquals(exactExcluded ? 1 : CAP, result.size());
		if (exactExcluded) {
			assertEquals("root", result.getFirst().term());
		} else {
			assertTrue(result.stream().allMatch(term -> term.exactness() ==
				(guided ? FulltextTermExactness.FUZZY : FulltextTermExactness.PREFIX)));
		}
	}

	/** Every guided fixture key is accepted at distance one; rejection seeks cannot dominate the observation. */
	private static void assertAcceptedHits(FulltextIndex index, CompiledFulltextAnalyzer analyzer, int expected) {
		final int[] hits = {0};
		new FulltextDictionaryWalker("sury", 1, 1, true, true)
			.walkSurface(index, analyzer.fingerprint().wordPhase(), (cursor, distance) -> {
				assertEquals(1, distance);
				hits[0]++;
				return true;
			});
		assertEquals(expected, hits[0]);
	}
}
