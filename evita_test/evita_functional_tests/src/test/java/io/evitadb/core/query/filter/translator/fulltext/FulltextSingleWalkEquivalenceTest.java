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
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula.ExpandedTerm;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextIndex.SurfaceFormCursor;
import io.evitadb.index.fulltext.FulltextTermExactness;
import io.evitadb.index.fulltext.analysis.*;
import io.evitadb.index.fulltext.typo.FulltextDictionaryWalker;
import io.evitadb.index.fulltext.typo.FulltextTypoParameters;
import io.evitadb.spi.analysis.*;
import io.evitadb.test.fulltext.EditDistances;
import io.evitadb.test.fulltext.FulltextAnalysisTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.apache.lucene.analysis.TokenStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.*;

import static io.evitadb.core.query.filter.translator.fulltext.FulltextSurfaceTestSupport.unitWeights;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.index.fulltext.FulltextIndexTestSupport.*;
import static io.evitadb.core.query.filter.translator.fulltext.FulltextExpansionTestSupport.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.fulltext.FulltextAnalysisTestSupport.collectTerms;
import static org.junit.jupiter.api.Assertions.*;

/** Real-index equivalence to the frozen cascade, with the accepted malformed-key reachability exception. */
@Tag(ENGINE)
@Tag(FULLTEXT)
@DisplayName("Single typo walk equivalence")
class FulltextSingleWalkEquivalenceTest {
	/** Fixed, reported seed; every fixture is synthetic and failures identify its index. */
	private static final long SEED = 0xF1B0C2026L;
	/** Dictionary cardinalities exercise the effective production cap, including JVM tuning overrides. */
	private static final int CAP = effectiveTermsPerLeg();
	/** All source words have four to twelve letters and include Czech diacritics. */
	private static final String[] WORDS = {
		"růže", "černá", "běhání", "žlutavý", "čokoláda", "přátelský", "přírodověda", "nejkrásnější"
	};
	/** Default submitted-search policy. */
	private static final FulltextTypoParameters PARAMETERS = new FulltextTypoParameters(new FulltextOptions());
	/** Immutable profile definitions shared across all synthetic fixtures. */
	private static final CatalogSchemaContract FIXTURE_SCHEMA = schema();

	/** Mixed source paths must merge distances before applying either cap. */
	@Nested
	@DisplayName("Overlapping variants and lane words")
	class Overlap {
		/** Covers both distance improvement and a later worse offer through the stem path. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Keeps the smallest distance across two long variants in either order")
		void shouldMergeVariantDistances(boolean reverse) {
			final FulltextIndex index = new FulltextIndex();
			final FulltextFieldKey key = FulltextFieldKey.attribute("title");
			addWords(index, key, 1, "abxdxfgh");
			final String[] variants = reverse
				? new String[]{"abxdefgh", "abcdefgh"} : new String[]{"abcdefgh", "abxdefgh"};
			final List<ExpandedTerm> actual = compare(index, new int[]{0}, variants, new String[0], false);
			assertEquals(1, actual.size());
			assertEquals(1, actual.getFirst().distance());
		}

		/** Lane probes must allow a retained stem to improve without reacquiring unchanged views unnecessarily. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Keeps the smallest distance when stem and surface walks overlap")
		void shouldMergeStemAndSurfaceDistances(boolean surfaceBetter) {
			try (final FulltextAnalyzerRegistry registry = registry()) {
				final CompiledFulltextAnalyzer analyzer =
					registry.getCompiledAnalyzer(FIXTURE_SCHEMA, "first", Locale.ROOT);
				final FulltextIndex index = new FulltextIndex();
				index.addValue(FulltextFieldKey.attribute("title"), 1, analyzer, 25, null, "abxdxfgh");
				final String[] variants = {surfaceBetter ? "abcdefgh" : "abxdefgh"};
				final String[] surfaces = {surfaceBetter ? "abxdefgh" : "abcdefgh"};
				final List<ExpandedTerm> actual = compare(index, new int[]{0}, variants, surfaces, false);
				assertEquals(1, actual.size());
				assertEquals(1, actual.getFirst().distance());
			}
		}

		/** A real index counts actual field walks and phase cursors, without mocking a dictionary or a walker. */
		@Test
		@DisplayName("Walks each field and phase once per eligible word using only its widest automaton")
		void shouldWalkOnlyOnce() {
			try (final FulltextAnalyzerRegistry registry = registry()) {
				final CountingIndex index = new CountingIndex();
				final int[] fields = fields(index, registry, 3, false);
				final String[] words = {"abcdefgh", "ijklmnop", "abcd", "ab12", "abc"};
				new FulltextTermExpansion(PARAMETERS).expand(index, fields, unitWeights(index), words, words,
					new String[0], false);
				assertEquals(9, index.stemWalks);
				assertEquals(6, index.laneWalks);
				assertEquals(3, index.walkers.size());
			}
		}
	}

	/** Seeded random dictionaries cover both end modes, real morphology, and all band-one cap boundaries. */
	@Nested
	@DisplayName("Seeded well-formed dictionaries")
	class Property {
		/**
		 * The effective cap is exercised through cardinalities; no class initialization or system property is altered.
		 */
		@Test
		@DisplayName("Matches all ordered expansion views in 800 seeded queries over two or three fields")
		void shouldMatchCascadeOnSeededDictionaries() {
			System.out.println(
				"Fulltext single-walk property seed=" + SEED + ", fixtures=400, queries=800, cap=" + CAP);
			final Random random = new Random(SEED);
			final int[] boundaries = new int[3];
			final int[] twoEditBoundaries = new int[3];
			int accentedPairs = 0;
			try (final FulltextAnalyzerRegistry registry = registry()) {
				for (int caseIndex = 0; caseIndex < 400; caseIndex++) {
					try {
						final boolean morphology = caseIndex >= 320;
						final FulltextIndex index = new FulltextIndex();
						final int[] fieldIds = fields(index, registry, 2 + random.nextInt(2), morphology);
						final CompiledFulltextAnalyzer[] analyzers = new CompiledFulltextAnalyzer[fieldIds.length];
						for (int f = 0; f < fieldIds.length; f++) { analyzers[f] = analyzer(registry, f, morphology); }
						final String source = WORDS[caseIndex % WORDS.length];
						final String query = fold(source);
						final int bandOneCount = new int[]{CAP, CAP + 20, CAP / 2}[(caseIndex / WORDS.length) % 3];
						final List<String> dictionary = new ArrayList<>();
						dictionary.add(query);
						dictionary.addAll(mutations(query, 1, bandOneCount, random));
						dictionary.addAll(mutations(query, 2, CAP + 10, random));
						dictionary.addAll(mutations(query, 3, 8, random));
						for (int i = 0, count = random.nextInt(CAP + 25) + 1; i < count; i++) {
							dictionary.add(query + "a" + (char) ('a' + i / 26) + (char) ('a' + i % 26));
						}
						Collections.shuffle(dictionary, random);
						int primaryKey = 1;
						for (int i = 0; i < dictionary.size(); i++) {
							final String term = accent(dictionary.get(i), random);
							for (int f = 0; f < fieldIds.length; f++) {
								if (f == i % fieldIds.length || random.nextInt(5) == 0) {
									final CompiledFulltextAnalyzer analyzer = analyzers[f];
									for (int n = 0, frequency = 1 + random.nextInt(3); n < frequency; n++) {
										index.addValue(FulltextFieldKey.attribute("field" + f), primaryKey++, analyzer,
											25 + f * 5, null,
											(term + " ").repeat(1 + random.nextInt(3)) + "xxxxxxxxxxxxxxxx");
									}
								}
							}
						}
						for (int f = fieldIds.length - 1; f > 0; f--) {
							final int other = random.nextInt(f + 1);
							final int field = fieldIds[f];
							fieldIds[f] = fieldIds[other];
							fieldIds[other] = field;
						}
						final Set<Long> phases = CollectionUtils.createHashSet(fieldIds.length);
						for (final int field : fieldIds) {
							phases.add(index.getWordPhaseId(field));
						}
						assertTrue(phases.size() >= 2, "Multiple word phases must be searched.");
						final List<FulltextIndex.SurfaceFormPage> surfacePages =
							index.collectChangedSurfaceFormPages().changedPages();
						for (final long phase : phases) {
							for (final String entry : persistedSurfaceEntries(surfacePages,
								index.getWordPhaseIds().get(phase))) {
								final String[] components = entry.split("/", -1);
								if (!components[0].equals(components[2])) {
									accentedPairs++;
								}
							}
						}
						final String[] variants = morphology
							? collectTerms(analyzers[0].word(), source).stream()
								.map(t -> t.term()).toArray(String[]::new)
							: caseIndex % 4 == 0 ? new String[]{query, mutations(query, 1, 1, random).getFirst()}
								: new String[]{query};
						final String[] surfaces = {query};
						for (final boolean unfinished : new boolean[]{false, true}) {
							final CascadeExpansion cascade = new CascadeExpansion(PARAMETERS);
							final List<ExpandedTerm> expected = cascade.expand(index, fieldIds, variants, surfaces,
								new String[]{query}, unfinished);
							final List<ExpandedTerm> actual = new FulltextTermExpansion(PARAMETERS).expand(
								index, fieldIds, unitWeights(index), variants, surfaces, new String[]{query},
									unfinished);
							assertViews(expected, actual);
							if (cascade.bandOneUnion == CAP && cascade.bandOneMaximumField < CAP) {
								boundaries[0]++;
								if (PARAMETERS.maxEdits(query) == 2) { twoEditBoundaries[0]++; }
							} else if (cascade.bandOneUnion > CAP) {
								boundaries[1]++;
								if (PARAMETERS.maxEdits(query) == 2) { twoEditBoundaries[1]++; }
							} else if (cascade.bandOneUnion < CAP) {
								boundaries[2]++;
								if (PARAMETERS.maxEdits(query) == 2) { twoEditBoundaries[2]++; }
							}
						}
					} catch (final RuntimeException | AssertionError failure) {
						throw new AssertionError("Single-walk property seed=" + SEED + ", index=" + caseIndex, failure);
					}
				}
			}
			for (final int count : twoEditBoundaries) {
				assertTrue(count >= 20, "seed=" + SEED + ", index=400, a cap boundary lacks coverage");
			}
			assertTrue(accentedPairs > 400, "seed=" + SEED + ", index=400, accented lane pairs must exist");
			System.out.println("Fulltext single-walk property seed=" + SEED + ", boundaries=" +
				Arrays.toString(boundaries) + ", twoEditBoundaries=" + Arrays.toString(twoEditBoundaries) +
				", accentedPairs=" + accentedPairs);
		}
	}

	/** The accepted difference adds a correctly classified hit below the cap, retaining every cascade view. */
	@Nested
	@DisplayName("Malformed UTF-16 reachability")
	class Malformed {
		/** A full cap cannot hide a raw reachability regression; all baseline candidates must still be visited. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Visits every cascade winner before capping when the recovered hit adds a candidate")
		void shouldRetainCascadeCandidatesAtFullCap(boolean unfinished) {
			final FulltextIndex index = new FulltextIndex();
			final String query = "abcdefgh";
			final CompiledFulltextAnalyzer analyzer = keywordAnalyzer();
			try (final FulltextAnalyzer chain = analyzer.indexSurface()) {
				final List<String> terms = mutations(query, 2, CAP - 1, new Random(SEED));
				terms.add("abcde\uD7FFzh");
				terms.add("abcde\uD800gh");
				for (int i = 0; i < terms.size(); i++) {
					index.addValue(FulltextFieldKey.attribute("keywordTerms"), i + 1, analyzer, 25, null, terms.get(i));
				}
				final List<ExpandedTerm> cascade = new CascadeExpansion(PARAMETERS).expand(index, new int[]{0},
					new String[]{query}, new String[0], new String[]{query}, unfinished);
				final Set<String> visited = CollectionUtils.createHashSet(CAP + 1);
				index.forEachTerm(0, new FulltextDictionaryWalker(query, 2, 1, true, unfinished),
					(term, postings, impacts, distance, frequency) -> {
						assertEquals(referenceDistance(query, term, unfinished), distance);
						visited.add(term);
						return true;
					});
				assertEquals(CAP, cascade.size());
				assertEquals(CAP + 1, visited.size());
				final List<ExpandedTerm> actual = new FulltextTermExpansion(PARAMETERS).expand(index, new int[]{0},
					unitWeights(index),
					new String[]{query}, new String[0], new String[]{query}, unfinished);
				assertEquals(CAP, actual.size());
				assertTrue(actual.stream().anyMatch(term -> term.term().equals("abcde\uD800gh")));
				for (final ExpandedTerm term : cascade) { assertTrue(visited.contains(term.term())); }
			}
		}

		/** A malformed query uses linear advancement, so a well-formed dictionary still has strict equivalence. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Keeps identical views for a malformed query over well-formed indexed terms")
		void shouldMatchWellFormedDictionaryForMalformedQuery(boolean unfinished) {
			final FulltextIndex index = new FulltextIndex();
			final CompiledFulltextAnalyzer analyzer = keywordAnalyzer();
			try (final FulltextAnalyzer chain = analyzer.indexSurface()) {
				final FulltextFieldKey key = FulltextFieldKey.attribute("keywordTerms");
				index.addValue(key, 1, analyzer, 25, null, "abcde\uD7FFzh");
				index.addValue(key, 2, analyzer, 25, null, "abcdefgh");
				final List<ExpandedTerm> actual = compare(index, new int[]{0}, new String[]{"abcde\uD800gh"},
					new String[0], unfinished);
				assertEquals(2, actual.size());
				assertEquals(1, actual.getFirst().distance());
			}
		}

		/** The adjacent supplementary scalar is well formed and both walks must reach it identically. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Keeps identical views for a supplementary scalar adjacent to the surrogate gap")
		void shouldMatchSupplementaryControl(boolean unfinished) {
			final FulltextIndex index = new FulltextIndex();
			final CompiledFulltextAnalyzer analyzer = keywordAnalyzer();
			try (final FulltextAnalyzer chain = analyzer.indexSurface()) {
				final FulltextFieldKey key = FulltextFieldKey.attribute("keywordTerms");
				index.addValue(key, 1, analyzer, 25, null, "abcde\uD7FFzh");
				index.addValue(key, 2, analyzer, 25, null, "abcde\uD800\uDC00gh");
				final List<ExpandedTerm> actual = compare(index, new int[]{0}, new String[]{"abcdefgh"},
					new String[0], unfinished);
				assertEquals(2, actual.size());
				assertEquals(1, actual.getFirst().distance());
			}
		}

		/** Both finite and open-ended walks preserve the old result and recover the skipped one-edit term. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Adds the skipped lone-surrogate hit without losing any cascade hit")
		void shouldContainCascadeAndClassifyExtraHit(boolean unfinished) {
			final FulltextIndex index = new FulltextIndex();
			final String query = "abcdefgh";
			final String predecessor = "abcde\uD7FFzh";
			final String extra = "abcde\uD800gh";
			final CompiledFulltextAnalyzer analyzer = keywordAnalyzer();
			try (final FulltextAnalyzer chain = analyzer.indexSurface()) {
				final FulltextFieldKey key = FulltextFieldKey.attribute("keywordTerms");
				index.addValue(key, 1, analyzer, 25, null, predecessor);
				index.addValue(key, 2, analyzer, 25, null, extra);
				final List<ExpandedTerm> expected = new CascadeExpansion(PARAMETERS).expand(index, new int[]{0},
					new String[]{query}, new String[0], new String[]{query}, unfinished);
				final List<ExpandedTerm> actual = new FulltextTermExpansion(PARAMETERS).expand(index, new int[]{0},
					unitWeights(index),
					new String[]{query}, new String[0], new String[]{query}, unfinished);
				assertEquals(1, expected.size());
				assertEquals(2, actual.size());
				assertViews(expected, actual.subList(1, 2));
				assertEquals(extra, actual.getFirst().term());
				assertEquals(referenceDistance(query, extra, unfinished), actual.getFirst().distance());
				final int[] classified = {-1};
				index.forEachTerm(0, new FulltextDictionaryWalker(query, 2, 1, true, unfinished),
					(term, postings, impacts, distance, frequency) -> {
						if (term.equals(extra)) { classified[0] = distance; }
						return true;
					});
				assertEquals(classified[0], actual.getFirst().distance());
				assertEquals(FulltextTermExactness.FUZZY, actual.getFirst().exactness());
				assertArrayEquals(new int[]{2}, actual.getFirst().postings().getArray());
				assertArrayEquals(impactsOf(index, 0, extra), actual.getFirst().impacts().toArray());
			}
		}
	}

	/** Independent DP minimizes over code-point prefixes in the same way as FulltextEditDistance. */
	private static int referenceDistance(String query, String term, boolean openEnd) {
		int distance = EditDistances.distance(query, term, true);
		if (openEnd) {
			for (int offset = 0; offset < term.length(); offset += Character.charCount(term.codePointAt(offset))) {
				distance = Math.min(distance, EditDistances.distance(query, term.substring(0, offset), true));
			}
		}
		return distance;
	}

	/** Compares every result position, including the order of a stem's retained and reacquired field views. */
	private static void assertViews(List<ExpandedTerm> expected, List<ExpandedTerm> actual) {
		assertEquals(expected.size(), actual.size(), "Expansion view count");
		for (int i = 0; i < expected.size(); i++) {
			final ExpandedTerm left = expected.get(i);
			final ExpandedTerm right = actual.get(i);
			assertTrue(left.term().equals(right.term()), "Stem order at view " + i);
			assertEquals(left.fieldId(), right.fieldId(), "Field order at view " + i);
			assertEquals(left.distance(), right.distance(), "Distance at view " + i);
			assertEquals(left.exactness(), right.exactness(), "Exactness at view " + i);
			assertArrayEquals(left.postings().getArray(), right.postings().getArray(), "Postings at view " + i);
			assertArrayEquals(left.impacts().toArray(), right.impacts().toArray(), "Impacts at view " + i);
		}
	}

	/** Executes the current production path and the frozen baseline with identical inputs. */
	private static List<ExpandedTerm> compare(
		FulltextIndex index, int[] fields, String[] variants, String[] surfaces, boolean unfinished
	) {
		final List<ExpandedTerm> expected = new CascadeExpansion(PARAMETERS).expand(
			index, fields, variants, surfaces, variants, unfinished);
		final List<ExpandedTerm> actual = new FulltextTermExpansion(PARAMETERS).expand(
			index, fields, unitWeights(index), variants, surfaces, variants, unfinished);
		assertViews(expected, actual);
		return actual;
	}

	/** Supplies all discovered production analysis steps plus a real identity stemmer for controlled cap fixtures. */
	private static FulltextAnalyzerRegistry registry() {
		final List<AnalysisStepFactory> factories = new ArrayList<>(ServiceLoader.load(AnalysisStepFactory.class)
			.stream().map(ServiceLoader.Provider::get).toList());
		factories.add(new IdentityLaneStem());
		return new FulltextAnalyzerRegistry(new AnalysisStepRegistry(factories));
	}

	/** Different step order gives two phases with equal folding; real Czech stemming adds two morphology phases. */
	private static CatalogSchemaContract schema() {
		return new InternalCatalogSchemaBuilder(FulltextAnalysisTestSupport.SCHEMA)
			.withFulltextAnalyzer("first", a -> a.words(lowercase(), asciiFolding(), wordsStep("single-walk-identity")))
			.withFulltextAnalyzer("second",
				a -> a.words(asciiFolding(), lowercase(), wordsStep("single-walk-identity")))
			.withFulltextAnalyzer("czech-first", a -> a.words(lowercase(), czechStemming()))
			.withFulltextAnalyzer("czech-second",
				a -> a.words(lowercase(), stopWords(czechStopWords()), czechStemming()))
			.toInstance();
	}

	/** Selects a current real analysis chain for the field; every fixture has at least two phases. */
	private static CompiledFulltextAnalyzer analyzer(FulltextAnalyzerRegistry registry, int field, boolean morphology) {
		return registry.getCompiledAnalyzer(FIXTURE_SCHEMA, (morphology ? "czech-" : "") +
			(field % 2 == 0 ? "first" : "second"), Locale.forLanguageTag("cs"));
	}

	/** Registers two or three searched fields before writing any synthetic values. */
	private static int[] fields(
		FulltextIndex index, FulltextAnalyzerRegistry registry, int count, boolean morphology
	) {
		final int[] fields = new int[count];
		for (int f = 0; f < count; f++) {
			final CompiledFulltextAnalyzer analyzer = analyzer(registry, f, morphology);
			fields[f] = index.getOrAssignFieldId(FulltextFieldKey.attribute("field" + f),
				analyzer.indexSurface().getAnalyzerName(),
				analyzer.fingerprint().index(), analyzer.fingerprint().wordPhase(), 25 + f * 5, null);
		}
		return fields;
	}

	/** Generates substitutions or insertions at the requested DP distance, preserving the frozen first letter. */
	private static List<String> mutations(String word, int distance, int count, Random random) {
		final Set<String> terms = CollectionUtils.createLinkedHashSet(count);
		for (int attempts = 0; terms.size() < count && attempts < 100000; attempts++) {
			final int insertion = 1 + random.nextInt(word.length());
			final String mutated = distance == 1 && random.nextBoolean()
				? word.substring(0, insertion) + (char) ('a' + random.nextInt(26)) + word.substring(insertion) : word;
			final char[] term = mutated.toCharArray();
			final Set<Integer> positions = CollectionUtils.createHashSet(distance);
			while (positions.size() < (mutated.length() == word.length() ? distance : 0)) {
				positions.add(1 + random.nextInt(word.length() - 1));
			}
			for (final int position : positions) {
				term[position] = (char) ('a' + random.nextInt(26));
			}
			final String candidate = new String(term);
			if (EditDistances.distance(word, candidate, true) == distance) { terms.add(candidate); }
		}
		assertEquals(count, terms.size(), "Synthetic mutation cardinality");
		return new ArrayList<>(terms);
	}

	/** Folds the limited Czech fixture alphabet without relying on a different normalization library. */
	private static String fold(String word) {
		final String accents = "áčďéěíňóřšťúůýž";
		final String plain =   "acdeeinorstuuyz";
		final StringBuilder folded = new StringBuilder(word.length());
		for (int i = 0; i < word.length(); i++) {
			final int position = accents.indexOf(word.charAt(i));
			folded.append(position < 0 ? word.charAt(i) : plain.charAt(position));
		}
		return folded.toString();
	}

	/** Adds random Czech diacritics while preserving the folded spelling and all edit distances. */
	private static String accent(String word, Random random) {
		final String plain = "acdeinorstuyz";
		final String accents = "áčďěíňóřšťůýž";
		final char[] result = word.toCharArray();
		for (int i = 0; i < result.length; i++) {
			final int position = plain.indexOf(result[i]);
			if (position >= 0 && random.nextBoolean()) { result[i] = accents.charAt(position); }
		}
		return new String(result);
	}

	/** Captures the pre-stem lane using real analysis wrappers while preserving controlled mutation stems. */
	private static final class IdentityLaneStem implements FulltextWordsAnalysisStepFactory {
		@Nonnull
		@Override
		public String getName() { return "single-walk-identity"; }
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
					return words;
				}
			};
		}
	}

	/** Read-only counters on a real index demonstrate the removed work without production observation hooks. */
	private static final class CountingIndex extends FulltextIndex {
		/** Number of stem walks requested. */
		private int stemWalks;
		/** Number of lane walks requested, excluding exact lane lookups. */
		private int laneWalks;
		/** Automaton identities reused across fields. */
		private final Set<FulltextDictionaryWalker> walkers = Collections.newSetFromMap(new IdentityHashMap<>());
		@Override
		public void forEachTerm(int fieldId, @Nonnull FulltextDictionaryWalker walker,
			@Nonnull FulltextDictionaryWalker.MatchVisitor visitor) {
			this.stemWalks++;
			this.walkers.add(walker);
			super.forEachTerm(fieldId, walker, visitor);
		}
		@Nonnull
		@Override
		public SurfaceFormCursor surfaceFormCursor(long phase, @Nonnull String prefix) {
			this.laneWalks++;
			return super.surfaceFormCursor(phase, prefix);
		}
	}

	/**
	 * Frozen implementation from 9cf5c68ea4. Only band-one coverage counters were added. Keeping the full reference
	 * here also preserves baseline tie-breaking and the order of retained versus reacquired field views.
	 */
	private static final class CascadeExpansion {
		/** Interim per-word, per-group term limit; exact lookups never consume it. */
		private static final int TERMS_PER_LEG = CAP;
		/** Actual band-one union before the reference truncates its exclusion set. */
		private int bandOneUnion;
		/** Largest local heap after band one. */
		private int bandOneMaximumField;
		/** Distance ascending, maximum field frequency descending, then term ascending. */
		private static final Comparator<Candidate> LEG_ORDER = (left, right) -> FulltextTermHeap.compare(
			left.distance, left.frequency, left.term, right.distance, right.frequency, right.term
		);
		/** Submitted-search policy fixed for this query. */
		@Nonnull private final FulltextTypoParameters parameters;
		/** Automata built once per variant, edit band and end mode. */
		@Nonnull private final Map<WalkKey, FulltextDictionaryWalker> walkers = CollectionUtils.createHashMap(8);

		/**
		 * Creates query-local expansion state.
		 * @param parameters submitted-search policy
		 */
		CascadeExpansion(@Nonnull FulltextTypoParameters parameters) {
			this.parameters = parameters;
		}

		/**
		 * Expands a word into its profile's current field generations.
		 * @param index the scope's locale dictionary
		 * @param fieldIds current fields belonging to this profile
		 * @param variants the word's analyzed alternatives
		 * @param surfaces query-prepared typed words in the lane normalization space
		 * @param fragments normalized raw fragments prepared around stop removal
		 * @param unfinished whether the actual final raw word remains unclosed
		 * @return field-specific expansions with their best distance and exactness
		 */
		@Nonnull
		List<ExpandedTerm> expand(
			@Nonnull FulltextIndex index, @Nonnull int[] fieldIds, @Nonnull String[] variants,
			@Nonnull String[] surfaces, @Nonnull String[] fragments, boolean unfinished
		) {
			final Map<String, Candidate> exact = CollectionUtils.createLinkedHashMap(variants.length);
			for (final String variant : variants) {
				for (final int fieldId : fieldIds) {
					final ExpandedTerm term = lookUpTerm(index, fieldId, variant);
					if (term != null) {
						offer(exact, fieldId, term.term(), term.postings(), term.impacts(), 0,
							FulltextTermExactness.EXACT, term.postings().size());
					}
				}
			}
			final long[] phases = wordPhases(index, fieldIds);
			final SurfaceTermCollector lane = new SurfaceTermCollector(index, fieldIds, exact);
			for (final long phase : phases) {
				for (final String surface : surfaces) {
					final SurfaceFormCursor cursor = index.exactSurfaceFormCursor(phase, surface);
					while (cursor.next()) {
						lane.collectUnlessExact(cursor.stemCharacters(), null);
					}
				}
			}
			final boolean prefixAllowed = expandsUnfinishedWord(fragments, unfinished);
			final boolean openEnd = prefixAllowed &&
				fragmentLength(fragments) >= this.parameters.openEndedStartLength();
			final Map<String, Candidate> prefix = prefixAllowed
				? collectPrefixes(index, fieldIds, phases, variants, fragments, exact, lane)
				: CollectionUtils.createHashMap(0);
			final Map<String, Candidate> typo = collectTypos(
				index, fieldIds, phases, variants, surfaces, exact, prefix, openEnd, lane);
			final List<ExpandedTerm> result = new ArrayList<>(viewCount(exact) + viewCount(prefix) + viewCount(typo));
			append(result, exact);
			append(result, prefix);
			append(result, typo);
			return result;
		}

		/**
		 * Collects prefix hits with bounded per-field heaps before selecting global survivors.
		 * @param index locale dictionary
		 * @param fieldIds profile fields
		 * @param phases distinct index word phases of the group
		 * @param variants analyzed alternatives whose prefixes also participate
		 * @param fragments normalized raw fragments
		 * @param exact uncapped exact matches excluded from this leg
		 * @param lane reusable exact field lookup shared by lane hits
		 * @return at most `TERMS_PER_LEG` stems, including their views in every searched field holding them
		 */
		@Nonnull
		private Map<String, Candidate> collectPrefixes(
			@Nonnull FulltextIndex index, @Nonnull int[] fieldIds, @Nonnull long[] phases,
			@Nonnull String[] variants, @Nonnull String[] fragments, @Nonnull Map<String, Candidate> exact,
			@Nonnull SurfaceTermCollector lane
		) {
			final Set<String> prefixes = CollectionUtils.createHashSet(fragments.length + variants.length);
			for (final String fragment : fragments) {
				if (!fragment.isEmpty()) {
					prefixes.add(fragment);
				}
			}
			for (final String variant : variants) {
				if (!variant.isEmpty()) {
					prefixes.add(variant);
				}
			}
			final FulltextTermHeap[] fields = newHeaps(fieldIds.length);
			for (int f = 0; f < fieldIds.length; f++) {
				final FulltextTermHeap heap = fields[f];
				for (final String fragment : prefixes) {
					index.forEachTerm(fieldIds[f], fragment, (term, postings, impacts) -> {
						if (!exact.containsKey(term)) {
							heap.offer(term, postings, impacts, 0, postings.size());
						}
						return true;
					});
				}
			}
			for (final long phase : phases) {
				for (final String fragment : fragments) {
					if (!fragment.isEmpty()) {
						final SurfaceFormCursor cursor = index.surfaceFormCursor(phase, fragment);
						while (cursor.next()) {
							lane.collectUnlessExact(cursor.stemCharacters(), fields);
						}
					}
				}
			}
			return select(index, fieldIds, fields, FulltextTermExactness.PREFIX);
		}

		/**
		 * Walks complete distance bands across all variants and fields. A term is skipped when the exact leg or the
		 * selected prefix leg already holds it, or when an earlier band collected it.
		 * @param index locale dictionary
		 * @param fieldIds profile fields
		 * @param phases distinct index word phases of the group
		 * @param variants analyzed alternatives, each with its own edit budget
		 * @param surfaces typed words, each budgeted by its folded length
		 * @param exact uncapped exact matches
		 * @param prefix selected prefix matches; discarded prefixes may compete through a positive-distance path
		 * @param openEnd whether a matching prefix accepts the remaining suffix
		 * @param lane reusable exact field lookup shared by lane hits
		 * @return at most `TERMS_PER_LEG` fuzzy stems with all their field views
		 */
		@Nonnull
		private Map<String, Candidate> collectTypos(
			@Nonnull FulltextIndex index, @Nonnull int[] fieldIds, @Nonnull long[] phases,
			@Nonnull String[] variants, @Nonnull String[] surfaces, @Nonnull Map<String, Candidate> exact,
			@Nonnull Map<String, Candidate> prefix, boolean openEnd,
			@Nonnull SurfaceTermCollector lane
		) {
			if (!anyAllowsEdits(variants) && !anyAllowsEdits(surfaces)) {
				return CollectionUtils.createHashMap(0);
			}
			final FulltextTermHeap[] fields = newHeaps(fieldIds.length);
			Set<String> earlierTerms = CollectionUtils.createHashSet(0);
			for (int edits = 1; edits <= 2; edits++) {
				final int band = edits;
				final Set<String> previousBands = earlierTerms;
				for (final String variant : variants) {
					if (this.parameters.maxEdits(variant) < edits) {
						continue;
					}
					final FulltextDictionaryWalker walker = walker(variant, edits, openEnd);
					for (int f = 0; f < fieldIds.length; f++) {
						final FulltextTermHeap heap = fields[f];
						index.forEachTerm(fieldIds[f], walker, (term, postings, impacts, distance, frequency) -> {
							if (distance == band && !previousBands.contains(term) &&
								!exact.containsKey(term) && !prefix.containsKey(term)) {
								heap.offer(term, postings, impacts, distance, frequency);
							}
							return true;
						});
					}
				}
				for (final String surface : surfaces) {
					if (this.parameters.maxEdits(surface) < edits) {
						continue;
					}
					final boolean surfaceOpenEnd = openEnd && surface.codePointCount(0, surface.length()) >=
						this.parameters.openEndedStartLength();
					final FulltextDictionaryWalker walker = walker(surface, edits, surfaceOpenEnd);
					for (final long phase : phases) {
						walker.walkSurface(index, phase, (cursor, distance) -> {
							if (distance == band) {
								lane.prepare(cursor.stemCharacters());
								if (!previousBands.contains(lane.probe) && !exact.containsKey(lane.probe) &&
									!prefix.containsKey(lane.probe)) {
									lane.collect(distance, fields);
								}
							}
							return true;
						});
					}
				}
				if (edits == 1) {
					final Set<String> all = CollectionUtils.createHashSet(TERMS_PER_LEG * fields.length);
					for (final FulltextTermHeap field : fields) {
						this.bandOneMaximumField = Math.max(this.bandOneMaximumField, field.size());
						for (int i = 0; i < field.size(); i++) {
							all.add(field.get(i).term());
						}
					}
					this.bandOneUnion = all.size();
				}
				earlierTerms = collectedTerms(fields);
				if (earlierTerms.size() == TERMS_PER_LEG) {
					break;
				}
			}
			return select(index, fieldIds, fields, FulltextTermExactness.FUZZY);
		}

		/**
		 * Tells whether the submitted policy grants an edit to at least one of the words.
		 * @param words analyzed variants or typed surface words of one query word
		 * @return true when any word is long enough for a typo walk
		 */
		private boolean anyAllowsEdits(@Nonnull String[] words) {
			for (final String word : words) {
				if (this.parameters.maxEdits(word) > 0) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Collects phase identities without boxing, once per group expansion rather than once per field walk.
		 * @param index locale index owning the field metadata
		 * @param fieldIds current fields of this profile
		 * @return distinct index word-phase fingerprints in field order
		 */
		@Nonnull
		private static long[] wordPhases(@Nonnull FulltextIndex index, @Nonnull int[] fieldIds) {
			final long[] phases = new long[fieldIds.length];
			int count = 0;
			for (final int fieldId : fieldIds) {
				final long phase = index.getWordPhaseId(fieldId);
				boolean found = false;
				for (int i = 0; i < count; i++) {
					found |= phases[i] == phase;
				}
				if (!found) {
					phases[count++] = phase;
				}
			}
			return Arrays.copyOf(phases, count);
		}

		/**
		 * Retrieves or builds the variant's automaton once for this query.
		 * @param variant analyzed alternative
		 * @param edits current edit band
		 * @param openEnd acceptance of arbitrary suffixes
		 * @return reusable, non-reentrant walker
		 */
		@Nonnull
		private FulltextDictionaryWalker walker(@Nonnull String variant, int edits, boolean openEnd) {
			final WalkKey key = new WalkKey(variant, edits, openEnd);
			final FulltextDictionaryWalker existing = this.walkers.get(key);
			if (existing != null) {
				return existing;
			}
			final FulltextDictionaryWalker walker = new FulltextDictionaryWalker(variant, edits,
				this.parameters.nonFuzzyPrefix(), this.parameters.transpositions(), openEnd);
			this.walkers.put(key, walker);
			return walker;
		}

		/**
		 * Starts one leg's bounded field collectors.
		 * @param count number of searched fields
		 * @return one empty heap per field
		 */
		@Nonnull
		private static FulltextTermHeap[] newHeaps(int count) {
			final FulltextTermHeap[] fields = new FulltextTermHeap[count];
			for (int i = 0; i < count; i++) {
				fields[i] = new FulltextTermHeap(TERMS_PER_LEG);
			}
			return fields;
		}

		/**
		 * Collects earlier-band terms to skip them in wider walks; reaching the cap ends the cascade.
		 * @param fields bounded field collectors
		 * @return distinct earlier-band stems, stopping once the global cap is reached
		 */
		@Nonnull
		private static Set<String> collectedTerms(@Nonnull FulltextTermHeap[] fields) {
			final Set<String> terms = CollectionUtils.createHashSet(TERMS_PER_LEG);
			for (final FulltextTermHeap field : fields) {
				for (int i = 0; i < field.size(); i++) {
					terms.add(field.get(i).term());
					if (terms.size() == TERMS_PER_LEG) {
						return terms;
					}
				}
			}
			return terms;
		}

		/**
		 * Unions at most fields-times-cap hits, selects the global top terms and reacquires only missing winner views.
		 * @param index locale dictionary
		 * @param fieldIds searched field generations
		 * @param fields bounded field survivors
		 * @param exactness this leg's matching class
		 * @return selected terms in deterministic leg order, with every available field view
		 */
		@Nonnull
		private static Map<String, Candidate> select(
			@Nonnull FulltextIndex index, @Nonnull int[] fieldIds, @Nonnull FulltextTermHeap[] fields,
			@Nonnull FulltextTermExactness exactness
		) {
			final Map<String, Candidate> union = CollectionUtils.createHashMap(fieldIds.length * TERMS_PER_LEG);
			for (int f = 0; f < fields.length; f++) {
				final FulltextTermHeap heap = fields[f];
				for (int i = 0; i < heap.size(); i++) {
					final FulltextTermHeap.Entry hit = heap.get(i);
					offer(union, fieldIds[f], hit.term(), hit.postings(), hit.impacts(), hit.distance(), exactness,
						hit.frequency());
				}
			}
			final Candidate[] ordered = union.values().toArray(Candidate[]::new);
			Arrays.sort(ordered, LEG_ORDER);
			final int count = Math.min(TERMS_PER_LEG, ordered.length);
			final Map<String, Candidate> selected = CollectionUtils.createLinkedHashMap(count);
			for (int i = 0; i < count; i++) {
				final Candidate candidate = ordered[i];
				for (final int fieldId : fieldIds) {
					if (!candidate.hasField(fieldId)) {
						final ExpandedTerm found = lookUpTerm(index, fieldId, candidate.term);
						if (found != null) {
							offer(union, fieldId, candidate.term, found.postings(), found.impacts(), candidate.distance,
								candidate.exactness, found.postings().size());
						}
					}
				}
				selected.put(candidate.term, candidate);
			}
			return selected;
		}

		/**
		 * Tests the submitted input-mode guards before expanding the final word.
		 * @param fragments normalized raw fragments
		 * @param unfinished whether the actual final word is unclosed
		 * @return whether unfinished-word expansion is enabled at this length
		 */
		boolean expandsUnfinishedWord(@Nonnull String[] fragments, boolean unfinished) {
			return unfinished && this.parameters.prefixLeg() &&
				fragmentLength(fragments) >= this.parameters.minFragmentLength();
		}

		/**
		 * Counts normalized fragment code points before stemming.
		 * @param fragments raw fragments emitted by normalization
		 * @return greatest fragment length, or zero
		 */
		private static int fragmentLength(@Nonnull String[] fragments) {
			int length = 0;
			for (final String fragment : fragments) {
				length = Math.max(length, fragment.codePointCount(0, fragment.length()));
			}
			return length;
		}

		/**
		 * Reads exact field views without copying postings or impacts.
		 * @param index locale dictionary
		 * @param fieldId current field generation
		 * @param term exact analyzed term
		 * @return borrowed dictionary views, or null when the term is absent or has no postings
		 */
		@Nullable
		static ExpandedTerm lookUpTerm(@Nonnull FulltextIndex index, int fieldId, @Nonnull String term) {
			final ExpandedTerm[] found = new ExpandedTerm[1];
			index.forEachTerm(fieldId, term, (visited, postings, impacts) -> {
				if (visited.equals(term) && !postings.isEmpty()) {
					found[0] = new ExpandedTerm(fieldId, visited, postings, impacts, 0, FulltextTermExactness.EXACT,
						fieldId, 1.0f);
				}
				return false;
			});
			return found[0];
		}

		/**
		 * Merges a retained field view into a bounded union or the uncapped exact leg.
		 * @param candidates terms already collected
		 * @param fieldId hit field
		 * @param term bare stem
		 * @param postings borrowed posting view
		 * @param impacts impacts aligned with postings
		 * @param distance hit distance
		 * @param exactness matching class of the collecting leg
		 * @param frequency document frequency in this field
		 * @throws GenericEvitaInternalError when the term was already collected under another matching class
		 */
		private static void offer(
			@Nonnull Map<String, Candidate> candidates, int fieldId, @Nonnull String term,
			@Nonnull Bitmap postings, @Nonnull ImpactView impacts, int distance,
			@Nonnull FulltextTermExactness exactness, int frequency
		) {
			final Candidate candidate;
			final Candidate existing = candidates.get(term);
			if (existing == null) {
				candidate = new Candidate(term, distance, exactness);
				candidates.put(term, candidate);
			} else {
				candidate = existing;
			}
			if (exactness != candidate.exactness) {
				throw new GenericEvitaInternalError("Fulltext term `" + term + "` was offered as " +
					candidate.exactness + " and " + exactness + " in one leg.");
			}
			candidate.distance = Math.min(candidate.distance, distance);
			candidate.frequency = Math.max(candidate.frequency, frequency);
			if (!candidate.hasField(fieldId)) {
				candidate.fields.add(new FieldTerm(fieldId, postings, impacts));
			}
		}

		/**
		 * Counts selected field views before allocating the result array list.
		 * @param candidates selected terms
		 * @return exact number of result entries
		 */
		private static int viewCount(@Nonnull Map<String, Candidate> candidates) {
			int count = 0;
			for (final Candidate candidate : candidates.values()) {
				count += candidate.fields.size();
			}
			return count;
		}

		/**
		 * Emits selected borrowed field views with the term's merged match metadata.
		 * @param result destination in leg order
		 * @param candidates selected stems
		 */
		private static void append(@Nonnull List<ExpandedTerm> result, @Nonnull Map<String, Candidate> candidates) {
			for (final Candidate candidate : candidates.values()) {
				for (final FieldTerm field : candidate.fields) {
					result.add(new ExpandedTerm(field.fieldId(), candidate.term, field.postings(), field.impacts(),
						candidate.distance, candidate.exactness, field.fieldId(), 1.0f));
				}
			}
		}

		/** Reuses exact field probes and a map search key across every lane hit, freezing only retained terms. */
		private static final class SurfaceTermCollector {
			/** Fields of this profile group. */
			@Nonnull private final int[] fieldIds;
			/** One reusable dictionary lookup per searched field. */
			@Nonnull private final FulltextIndex.TermLookup[] lookups;
			/** Uncapped exact entries. */
			@Nonnull private final Map<String, Candidate> exact;
			/** Query-only borrowed-character search object, never inserted into a map or set. */
			@Nonnull private final BorrowedTerm probe = new BorrowedTerm();

			/**
			 * Prepares all field framing once for this expansion.
			 * @param index locale dictionary
			 * @param fieldIds current searched field generations
			 * @param exact uncapped exact matches
			 */
			private SurfaceTermCollector(
				@Nonnull FulltextIndex index, @Nonnull int[] fieldIds, @Nonnull Map<String, Candidate> exact
			) {
				this.fieldIds = fieldIds;
				this.exact = exact;
				this.lookups = new FulltextIndex.TermLookup[fieldIds.length];
				for (int i = 0; i < fieldIds.length; i++) {
					this.lookups[i] = index.termLookup(fieldIds[i]);
				}
			}

			/**
			 * Rebinds the query-only exclusion probe to the current cursor's decoded stem.
			 * @param stem borrowed cursor characters, unchanged until collection finishes
			 */
			private void prepare(@Nonnull CharSequence stem) {
				this.probe.characters = stem;
				int hash = 0;
				for (int i = 0; i < stem.length(); i++) {
					hash = 31 * hash + stem.charAt(i);
				}
				this.probe.hash = hash;
			}

			/**
			 * Collects a distance-zero lane hit unless the exact entries already hold its stem, including a stem the
			 * exact lane leg itself collected through an earlier surface or phase.
			 * @param stem borrowed cursor characters, unchanged until collection finishes
			 * @param heaps common prefix collectors, or null for the uncapped exact leg
			 */
			private void collectUnlessExact(@Nonnull CharSequence stem, @Nullable FulltextTermHeap[] heaps) {
				prepare(stem);
				if (!this.exact.containsKey(this.probe)) {
					collect(0, heaps);
				}
			}

			/**
			 * Finds field frequencies, tests competitiveness, then freezes and acquires views for retained hits only.
			 * @param distance accepted folded distance
			 * @param heaps common prefix/typo collectors, or null for the uncapped exact leg
			 */
			private void collect(int distance, @Nullable FulltextTermHeap[] heaps) {
				String retained = null;
				for (int i = 0; i < this.lookups.length; i++) {
					final FulltextIndex.TermLookup lookup = this.lookups[i];
					if (!lookup.find(this.probe.characters)) {
						continue;
					}
					final int frequency = lookup.size();
					if (heaps == null || heaps[i].canOffer(this.probe.characters, distance, frequency)) {
						if (retained == null) {
							retained = this.probe.characters.toString();
						}
						if (heaps == null) {
							offer(this.exact, this.fieldIds[i], retained, lookup.postings(), lookup.impacts(), 0,
								FulltextTermExactness.EXACT, frequency);
						} else {
							heaps[i].offer(retained, lookup.postings(), lookup.impacts(), distance, frequency);
						}
					}
				}
			}
		}

		/**
		 * HashMap/HashSet query probe over borrowed characters. Its hash and comparison match stored String keys. This
		 * deliberately asymmetric search object is never stored: hash collections compare the query to the stored key,
		 * so the stored immutable String's equality and hash semantics remain unchanged.
		 */
		private static final class BorrowedTerm {
			/** Cursor-owned characters of the current hit. */
			@Nonnull private CharSequence characters = "";
			/** String-compatible hash recomputed when the probe is rebound. */
			private int hash;

			/** Creates the one mutable map search probe owned by a lane collector. */
			private BorrowedTerm() { }

			@Override
			public int hashCode() {
				return this.hash;
			}

			@Override
			public boolean equals(@Nullable Object other) {
				return this == other ||
					other instanceof String term && CharSequence.compare(this.characters, term) == 0;
			}
		}

		/**
		 * Query-local automaton identity; prefix and transposition policy are fixed on the enclosing instance.
		 * @param term analyzed alternative
		 * @param edits accepted distance band
		 * @param openEnd whether a matching prefix accepts its remaining suffix
		 */
		private record WalkKey(@Nonnull String term, int edits, boolean openEnd) { }

		/**
		 * Borrowed views in one selected field.
		 * @param fieldId current field generation
		 * @param postings primary keys containing the term
		 * @param impacts aligned impact bytes
		 */
		private record FieldTerm(int fieldId, @Nonnull Bitmap postings, @Nonnull ImpactView impacts) { }

		/** A union candidate, bounded by the total number of retained field hits. */
		private static final class Candidate {
			/** Bare dictionary stem. */
			@Nonnull private final String term;
			/** Borrowed field views; total views across a union are bounded by field-count times cap. */
			@Nonnull private final List<FieldTerm> fields = new ArrayList<>(2);
			/** Smallest distance across variants. */
			private int distance;
			/** Matching class of the leg that collected it. */
			@Nonnull private final FulltextTermExactness exactness;
			/** Maximum document frequency over fields. */
			private int frequency;

			/**
			 * Starts a union candidate.
			 * @param term bare dictionary stem
			 * @param distance first hit distance
			 * @param exactness first matching class
			 */
			private Candidate(@Nonnull String term, int distance, @Nonnull FulltextTermExactness exactness) {
				this.term = term;
				this.distance = distance;
				this.exactness = exactness;
			}

			/**
			 * Checks whether a retained view already covers a field.
			 * @param fieldId searched field generation
			 * @return true when this candidate holds that field's dictionary view
			 */
			private boolean hasField(int fieldId) {
				for (final FieldTerm field : this.fields) {
					if (field.fieldId() == fieldId) {
						return true;
					}
				}
				return false;
			}
		}
	}
}
