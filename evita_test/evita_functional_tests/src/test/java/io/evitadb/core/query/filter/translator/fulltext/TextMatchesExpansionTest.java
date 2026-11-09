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

import io.evitadb.core.query.algebra.fulltext.FulltextFormula.ExpandedTerm;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.typo.FulltextTypoParameters;
import io.evitadb.test.fulltext.EditDistances;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static io.evitadb.core.query.filter.translator.fulltext.FulltextSurfaceTestSupport.*;

import java.util.*;

import static io.evitadb.index.fulltext.FulltextIndexTestSupport.*;
import static io.evitadb.index.fulltext.FulltextTermExactness.FUZZY;
import static io.evitadb.core.query.filter.translator.fulltext.FulltextExpansionTestSupport.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.*;

/** Real dictionary and translator expansion, independent of stemming and the catalog query planner. */
@Tag(ENGINE)
@Tag(FULLTEXT)
@DisplayName("Text matches expansion legs")
class TextMatchesExpansionTest {
	/** Effective production cap, read without widening production visibility. */
	private static final int CAP = effectiveTermsPerLeg();
	/** Built-in options mapped to the engine policy. */
	private static final FulltextTypoParameters DEFAULT_PARAMETERS =
		new FulltextTypoParameters(new FulltextOptions());

	/** Real dictionary under test, rebuilt for every test. */
	private final FulltextIndex index = new FulltextIndex();
	/** The title field, registered as field 0. */
	private final FulltextFieldKey key = FulltextFieldKey.attribute("title");

	/** Builds a real field generation. */
	@BeforeEach
	void register() {
		registerExactField(this.index, this.key, 25.0);
	}

	/** Expands one word in the title field under the default policy, its fragment passed as the translator does. */
	private ExpandedTerm[] expand(String fragment, boolean unfinished, String... variants) {
		return new FulltextTermExpansion(DEFAULT_PARAMETERS)
			.expand(this.index, new int[]{0}, variants, new String[0], new String[]{fragment}, unfinished)
			.toArray(ExpandedTerm[]::new);
	}

	/** Indexes the words as the entity's value of the title field. */
	private void add(int primaryKey, String... words) {
		addWords(this.index, this.key, primaryKey, words);
	}

	/** Terms remain unique within the field and carry independently observable distance and exactness. */
	private static Map<String, String> describe(ExpandedTerm[] terms) {
		final Map<String, String> result = new TreeMap<>();
		for (final ExpandedTerm term : terms) {
			final String exactness = term.exactness().name();
			assertNull(result.put(term.term(), exactness + ":" + term.distance()), "Duplicate " + term.term());
		}
		return result;
	}

	/** Finished-word mechanics. */
	@Nested
	@DisplayName("Finished words")
	class Finished {
		/** Every variant has its own threshold, not the raw word's length. */
		@ParameterizedTest
		@CsvSource({"abc,axc,false", "abcd,abxd,true", "abcdefg,abxdefg,true", "abcdefg,abxdefy,false",
			"abcdefgh,abxdefgy,true",
			"ab123456,ab123457,false", "abcd,xbcd,false", "abcd,acbd,true"})
		@DisplayName("Uses variant thresholds, exact digits, frozen initials and one-edit transpositions")
		void shouldRespectBudget(String query, String candidate, boolean accepted) {
			add(1, candidate);
			assertEquals(accepted, expand(query, false, query).length == 1);
		}

		/** A long surface does not give a short stem two edits. */
		@Test
		@DisplayName("Budgets each stem separately and merges overlapping cascades")
		void shouldMergeVariants() {
			add(1, "abcd", "abxd", "abxy", "abzz", "abcdefgh", "abxdefgy");
			assertEquals(Map.of("abcd", "EXACT:0", "abxd", "EXACT:0", "abxy", "FUZZY:1",
				"abcdefgh", "EXACT:0", "abxdefgy", "FUZZY:2"),
				describe(expand("abcdefghijk", false, "abcd", "abxd", "abcdefgh")));
			assertEquals(Map.of("abcdefgh", "EXACT:0", "abxdefgy", "FUZZY:2"),
				describe(expand("abcd", false, "abcdefgh")));
		}
		/** A term reached at one edit by a variant is not worsened by another variant's two-edit cascade. */
		@Test
		@DisplayName("Keeps the minimum distance when long variants overlap between edit bands")
		void shouldKeepMinimumDistanceAcrossVariants() {
			add(1, "abcdefgh", "abxdefgh", "abxdxfgh", "abxdefgy");
			assertEquals(Map.of("abcdefgh", "EXACT:0", "abxdefgh", "EXACT:0",
				"abxdxfgh", "FUZZY:1", "abxdefgy", "FUZZY:1"),
				describe(expand("abc", false, "abcdefgh", "abxdefgh")));
		}
	}

	/** Last-word mechanics. */
	@Nested
	@DisplayName("Unfinished words")
	class Unfinished {
		/** The raw fragment and every variant contribute prefixes; exactness wins overlapping legs. */
		@Test
		@DisplayName("Unions raw and stem prefixes with open-ended typo hits")
		void shouldUnionLastWordLegs() {
			add(1, "black", "blackberry", "blacks", "blakcberry", "bla");
			assertEquals(Map.of("black", "EXACT:0", "blackberry", "PREFIX:0", "blacks", "PREFIX:0",
				"blakcberry", "PREFIX:0"), describe(expand("blakc", true, "black")));
		}

		/** From four characters a mistyped unfinished variant reaches longer terms. */
		@Test
		@DisplayName("Opens the typo walk only for unfinished words from four letters")
		void shouldOpenWalk() {
			add(1, "blackberry");
			assertEquals(Map.of("blackberry", "FUZZY:1"), describe(expand("blakc", true, "blakc")));
			assertEquals(Map.of("blackberry", "FUZZY:1"), describe(expand("blax", true, "blax")));
			assertEquals(0, expand("blakc", false, "blakc").length);
			assertEquals(0, expand("bla", true, "bla").length);
		}

		/** Spec §6.2 opens every variant; the finite-variant prototype oracle differs here. */
		@Test
		@DisplayName("Uses an open-ended walk for each differently sized variant")
		void shouldOpenEveryVariant() {
			add(1, "cerxston");
			assertEquals(Map.of("cerxston", "FUZZY:1"), describe(expand("cerne", true, "cern", "cerne")));
			assertTrue(describe(expand("cerne", false, "cern", "cerne")).isEmpty());
		}

		/** A stop filter may leave no variant but the raw fragment still gets its prefix leg. */
		@Test
		@DisplayName("Expands a raw fragment even when its variants were stopped")
		void shouldKeepStoppedFragment() {
			add(1, "jakost");
			assertEquals(Map.of("jakost", "PREFIX:0"), describe(expand("jako", true)));
		}
	}

	/** A per-group cap applies after variants merge, by distance, frequency and term. */
	@Nested
	@DisplayName("Leg caps")
	class Caps {
		/** Exact variant lookups survive even when their count exceeds the effective cap. */
		@Test
		@DisplayName("Keeps every exact variant outside the cap")
		void shouldKeepAllExactVariants() {
			final String[] variants = new String[CAP + 1];
			for (int i = 0; i < variants.length; i++) {
				variants[i] = "term" + alphabeticSuffix(i);
			}
			add(1, variants);
			assertEquals(CAP + 1, expand("term", false, variants).length);
		}

		/** A late lexicographical high-frequency candidate must survive, ties cut by term. */
		@Test
		@DisplayName("Caps prefix terms at the effective cap by frequency then term")
		void shouldCapPrefix() {
			for (int i = 0; i <= CAP; i++) {
				add(i + 1, "stem" + alphabeticSuffix(i));
			}
			final String popular = "stem" + alphabeticSuffix(CAP);
			add(CAP + 2, popular);
			final Map<String, String> actual = describe(expand("stem", true));
			assertEquals(CAP, actual.size());
			assertTrue(actual.containsKey(popular));
			assertFalse(actual.containsKey("stem" + alphabeticSuffix(CAP - 1)));
		}

		/** A stem appearing in several fields consumes one slot, while unsearched fields contribute nothing. */
		@Test
		@DisplayName("Caps stems across fields and retains their isolated posting views")
		void shouldCapAcrossFields() {
			final FulltextFieldKey second = FulltextFieldKey.attribute("body");
			final FulltextFieldKey excluded = FulltextFieldKey.attribute("excluded");
			final int secondId = registerExactField(TextMatchesExpansionTest.this.index, second, 25.0);
			registerExactField(TextMatchesExpansionTest.this.index, excluded, 25.0);
			for (int i = 0; i <= CAP; i++) {
				final String word = "stem" + alphabeticSuffix(i);
				add(i + 1, word);
				addWords(TextMatchesExpansionTest.this.index, second, i + CAP + 2, word);
			}
			addWords(TextMatchesExpansionTest.this.index, excluded, 1, "stemexcluded");
			final List<ExpandedTerm> actual = new FulltextTermExpansion(DEFAULT_PARAMETERS)
				.expand(TextMatchesExpansionTest.this.index, new int[]{0, secondId}, new String[0],
					new String[0], new String[]{"stem"}, true);
			assertEquals(2 * CAP, actual.size());
			final Set<String> terms = new TreeSet<>();
			for (final ExpandedTerm term : actual) {
				assertTrue(term.fieldId() == 0 || term.fieldId() == secondId);
				assertNotEquals("stemexcluded", term.term());
				terms.add(term.term());
			}
			assertEquals(CAP, terms.size());
		}

		/** Prefix hits reached fuzzily must not spend both caps after their best class has been merged. */
		@Test
		@DisplayName("Does not let high-frequency prefix hits consume the typo cap")
		void shouldCapAfterMergingExactness() {
			for (int i = 0; i <= CAP; i++) {
				final String suffix = alphabeticSuffix(i);
				add(i + 1, "stem" + suffix);
				add(i + CAP + 2, "stem" + suffix, "sbam" + suffix);
			}
			final Map<String, String> actual = describe(expand("stem", true, "stam"));
			assertEquals(2 * CAP, actual.size());
			assertEquals(CAP, actual.values().stream().filter("PREFIX:0"::equals).count());
			assertEquals(CAP, actual.values().stream().filter("FUZZY:1"::equals).count());
		}

		/** Equal-distance and frequency ties must choose the same ordered stems at the actual cap boundary. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Cuts equal-distance ties at the cap in stable term order across field and variant orders")
		void shouldCutEqualDistanceTiesDeterministically(boolean reverse) {
			final FulltextFieldKey second = FulltextFieldKey.attribute("body");
			final int secondId = registerExactField(TextMatchesExpansionTest.this.index, second, 25.0);
			final List<String> expected = new ArrayList<>();
			for (int i = 0; i <= CAP; i++) {
				final String suffix = alphabeticSuffix(i);
				add(i + 1, "sbam" + suffix);
				addWords(TextMatchesExpansionTest.this.index, second, i + CAP + 2, "sbam" + suffix);
				if (i < CAP) {
					expected.add("sbam" + suffix);
				}
			}
			final List<ExpandedTerm> actual = new FulltextTermExpansion(DEFAULT_PARAMETERS).expand(
				TextMatchesExpansionTest.this.index, reverse ? new int[]{secondId, 0} : new int[]{0, secondId},
				reverse ? new String[]{"sbem", "stam"} : new String[]{"stam", "sbem"},
				new String[0], new String[]{"stam"}, true);
			assertEquals(2 * CAP, actual.size());
			assertEquals(expected, actual.stream().map(ExpandedTerm::term).distinct().toList());
			assertTrue(actual.stream().allMatch(term -> term.distance() == 1 &&
				term.exactness() == FUZZY));
		}

		/** Thousands of hits in both legs must retain only a bounded working set. */
		@Test
		@DisplayName("Selects exact top terms from thousands of hits with bounded candidate retention")
		void shouldBoundCandidateCollection() {
			addWideDictionary();
			final FulltextTermExpansion expansion = new FulltextTermExpansion(DEFAULT_PARAMETERS);
			final List<ExpandedTerm> actual = expansion.expand(TextMatchesExpansionTest.this.index, new int[]{0},
				new String[]{"zzzz", "aaaa"}, new String[0], new String[]{"aaaa"}, true);
			final Map<String, String> expected = new TreeMap<>();
			for (int i = 0; i < CAP - 1; i++) {
				expected.put("zzzz" + String.format("%05d", i), "PREFIX:0");
				expected.put("zzzx" + String.format("%05d", i), "FUZZY:1");
			}
			expected.put("aaaa03999", "PREFIX:0");
			expected.put("aaab03999", "FUZZY:1");
			assertEquals(expected, describe(actual.toArray(ExpandedTerm[]::new)));
		}

		/** Late winners must carry their own borrowed views through prefix and fuzzy slot reuse. */
		@ParameterizedTest
		@CsvSource({"aaaa03999,2,3", "aaab03999,4,5"})
		@DisplayName("Keeps the late winner's primary keys and aligned impacts on both expansion legs")
		void shouldKeepReusedSlotViews(String winner, int first, int second) {
			addWideDictionary();
			final byte[] expectedImpacts = impactsOf(TextMatchesExpansionTest.this.index, 0, winner);
			assertEquals(2, expectedImpacts.length);
			assertNotEquals(expectedImpacts[0], expectedImpacts[1]);
			assertFalse(Arrays.equals(impactsOf(TextMatchesExpansionTest.this.index, 0, "aaaa03999"),
				impactsOf(TextMatchesExpansionTest.this.index, 0, "aaab03999")));
			for (final ExpandedTerm term : expand("aaaa", true, "zzzz", "aaaa")) {
				if (term.term().equals(winner)) {
					assertArrayEquals(new int[]{first, second}, term.postings().getArray());
					assertArrayEquals(expectedImpacts, term.impacts().toArray());
					return;
				}
			}
			fail("Missing late winner " + winner);
		}

		/** Both legs replace equal-cardinality victims using disjoint primary keys and distinct aligned impacts. */
		private void addWideDictionary() {
			final List<String> words = new ArrayList<>(7998);
			for (int i = 0; i < 3999; i++) {
				words.add("zzzz" + String.format(Locale.ROOT, "%05d", i));
				words.add("zzzx" + String.format(Locale.ROOT, "%05d", i));
			}
			// The z variant fills each heap first; the a variant wins the frequency tie lexically afterwards.
			for (final int pk : new int[]{10, 11}) {
				add(pk, words.toArray(String[]::new));
			}
			add(2, ("aaaa03999 " + "other ".repeat(100)).trim());
			add(3, "aaaa03999 aaaa03999 aaaa03999 aaaa03999 aaaa03999 other");
			add(4, ("aaab03999 " + "other ".repeat(20)).trim());
			add(5, ("aaab03999 aaab03999 " + "other ".repeat(200)).trim());
		}

		/** The maximum per-field frequency selects the survivors, not the frequency summed over fields. */
		@Test
		@DisplayName("Uses maximum field frequency when sum and maximum select different survivors")
		void shouldUseMaximumFieldFrequency() {
			final FulltextFieldKey second = FulltextFieldKey.attribute("body");
			final int secondId = registerExactField(TextMatchesExpansionTest.this.index, second, 25.0);
			final String[] frequent = new String[CAP - 1];
			final Set<String> expected = new TreeSet<>();
			for (int i = 0; i < frequent.length; i++) {
				frequent[i] = "stemfrequent" + alphabeticSuffix(i);
				expected.add(frequent[i]);
			}
			for (int pk = 1; pk <= 20; pk++) {
				add(pk, frequent);
			}
			for (int pk = 100; pk < 106; pk++) {
				add(pk, "stemshared");
				addWords(TextMatchesExpansionTest.this.index, second, pk, "stemshared");
			}
			for (int pk = 200; pk < 210; pk++) {
				add(pk, "stemsingle");
			}
			expected.add("stemsingle");
			final List<ExpandedTerm> actual = new FulltextTermExpansion(DEFAULT_PARAMETERS).expand(
				TextMatchesExpansionTest.this.index, new int[]{0, secondId}, new String[0], new String[0],
					new String[]{"stem"}, true);
			final Set<String> actualTerms = new TreeSet<>();
			for (final ExpandedTerm term : actual) {
				actualTerms.add(term.term());
			}
			assertEquals(expected, actualTerms);
		}

		/** The globally selected term needs a posting view even in a field whose local heap evicted it. */
		@Test
		@DisplayName("Reacquires the missing field view of a global survivor")
		void shouldReacquireEvictedFieldView() {
			final FulltextFieldKey second = FulltextFieldKey.attribute("body");
			final int secondId = registerExactField(TextMatchesExpansionTest.this.index, second, 25.0);
			final String[] competitors = new String[CAP];
			for (int i = 0; i < competitors.length; i++) {
				competitors[i] = "stemrank" + alphabeticSuffix(i);
			}
			for (int pk = 1; pk <= 3; pk++) {
				add(pk, competitors);
			}
			add(4, "stemwinner");
			for (int pk = 10; pk < 20; pk++) {
				addWords(TextMatchesExpansionTest.this.index, second, pk, "stemwinner");
			}
			final List<ExpandedTerm> actual = new FulltextTermExpansion(DEFAULT_PARAMETERS).expand(
				TextMatchesExpansionTest.this.index, new int[]{0, secondId}, new String[0], new String[0],
					new String[]{"stem"}, true);
			assertEquals(CAP + 1, actual.size());
			int fields = 0;
			for (final ExpandedTerm term : actual) {
				assertNotEquals("stemrank" + alphabeticSuffix(CAP - 1), term.term());
				if (term.term().equals("stemwinner")) {
					fields++;
					assertEquals(term.fieldId() == 0 ? 1 : 10, term.postings().size());
				}
			}
			assertEquals(2, fields);
		}

		/** Only retained prefixes suppress a term's eligible positive-distance path. */
		@Test
		@DisplayName("Lets a discarded prefix compete as a typo while retaining prefix survivor exactness")
		void shouldAdmitDiscardedPrefixAsTypo() {
			final String[] frequent = new String[CAP];
			for (int i = 0; i < frequent.length; i++) {
				frequent[i] = "happi" + alphabeticSuffix(i);
			}
			add(1, frequent);
			add(2, frequent);
			add(3, "happyzz");
			final Map<String, String> actual = describe(expand("happy", true, "happi"));
			assertEquals(CAP + 1, actual.size());
			assertEquals("FUZZY:1", actual.get("happyzz"));
			for (final String term : frequent) {
				assertEquals("PREFIX:0", actual.get(term));
			}
			for (int pk = 4; pk <= 6; pk++) {
				add(pk, "happykept");
			}
			final Map<String, String> inverse = describe(expand("happy", true, "happi"));
			assertEquals(CAP + 1, inverse.size());
			assertEquals("PREFIX:0", inverse.get("happykept"));
			assertEquals("FUZZY:1", inverse.get("happyzz"));
		}

		/** One-edit terms take priority over more popular two-edit terms, exact hits are uncapped. */
		@Test
		@DisplayName("Caps the merged typo leg by distance before frequency and keeps exact terms")
		void shouldCapMergedTypoLeg() {
			final String query = "a".repeat(Math.max(8, 2 + CAP / 25));
			final List<String> expected = new ArrayList<>();
			for (int position = 1; position < query.length() && expected.size() <= CAP; position++) {
				for (char suffix = 'b'; suffix <= 'z' && expected.size() <= CAP; suffix++) {
					final char[] word = query.toCharArray();
					word[position] = suffix;
					final String term = new String(word);
					expected.add(term);
					add(expected.size(), term);
				}
			}
			final String twoEdits = "abb" + query.substring(3);
			for (int pk = CAP + 2; pk < CAP + 12; pk++) {
				add(pk, twoEdits);
			}
			add(CAP + 12, query);
			expected.sort(String::compareTo);
			final String popular = expected.getLast();
			add(CAP + 13, popular);
			final Map<String, String> actual = describe(expand(query, false, query, query));
			assertEquals(CAP + 1, actual.size());
			assertEquals("EXACT:0", actual.remove(query));
			final Set<String> kept = new TreeSet<>(expected.subList(0, CAP - 1));
			kept.add(popular);
			assertEquals(kept, actual.keySet());
		}
	}

	/** The query input-mode policy is separate from typo thresholds. */
	@Nested
	@DisplayName("Policy overrides")
	class Overrides {
		/** Disabling prefix uses a finite walk; delaying open-end still leaves the prefix leg active. */
		@Test
		@DisplayName("Honors prefix, minimum fragment, open-ended start and transposition overrides")
		void shouldApplyInputMode() {
			add(1, "blackberry", "abdc");
			assertEquals(1, configured("blac", true, 4, 4, true).size());
			assertEquals(0, configured("blac", false, 4, 4, true).size());
			assertEquals(0, configured("blac", true, 5, 4, true).size());
			assertEquals(0, configured("blakc", true, 4, 6, true).size());
			assertEquals(1, configured("blakc", true, 4, 4, true).size());
			assertEquals(1, configured("abcd", false, 4, 4, true).size());
			assertEquals(0, configured("abcd", false, 4, 4, false).size());
		}

		/** Invokes the real expansion helper with a nondefault immutable policy. */
		private List<?> configured(String word, boolean prefix, int minimum, int openEnd, boolean swaps) {
			return new FulltextTermExpansion(new FulltextTypoParameters(4, 8, 1, swaps, true,
				prefix, minimum, openEnd)).expand(TextMatchesExpansionTest.this.index, new int[]{0},
					new String[]{word}, new String[0], new String[]{word}, true);
		}

		/** A supplementary character is one letter of the fragment, and the longest fragment decides. */
		@Test
		@DisplayName("Measures the unfinished fragment in code points, by its longest form and only when enabled")
		void shouldMeasureFragmentInCodePoints() {
			final FulltextTermExpansion expansion = new FulltextTermExpansion(DEFAULT_PARAMETERS);
			final String letter = "𝒜";
			assertFalse(expansion.expandsUnfinishedWord(new String[]{letter.repeat(3)}, true));
			assertTrue(expansion.expandsUnfinishedWord(new String[]{letter.repeat(4)}, true));
			assertFalse(expansion.expandsUnfinishedWord(new String[]{letter.repeat(4)}, false));
			assertTrue(expansion.expandsUnfinishedWord(new String[]{"ab", "abcd"}, true));
			assertFalse(new FulltextTermExpansion(new FulltextTypoParameters(4, 8, 1, true, true, false, 4, 4))
				.expandsUnfinishedWord(new String[]{"abcd"}, true));
		}
	}

	/**
	 * Independent matrix-distance oracle for single-hypothesis words. Multi-variant unfinished words have
	 * deterministic expectations above. Every sampled dictionary word is queried as itself, as its shortest
	 * expandable prefix and with an inner letter from outside the dictionary alphabet, so the scan checks exact,
	 * prefix and fuzzy precedence below every leg's cap.
	 */
	@Nested
	@DisplayName("Seeded oracle")
	class Oracle {
		/**
		 * Random dictionaries stay below the cap. Identity hypotheses isolate the single-hypothesis contract while
		 * a linear scan and a full distance matrix remain independent of the engine's dictionary walker.
		 */
		@Test
		@DisplayName("Matches an independent matrix-distance scan on a seeded random dictionary")
		void shouldMatchOracle() {
			final Random random = new Random(9341);
			final List<String> words = new ArrayList<>();
			for (int i = 0; i < 180; i++) {
				final StringBuilder word = new StringBuilder(9);
				for (int j = 0, length = 4 + random.nextInt(5); j < length; j++) {
					word.append((char) ('a' + random.nextInt(8)));
				}
				final String term = word.toString();
				words.add(term);
				add(i + 1, term);
			}
			final int prefixLength = DEFAULT_PARAMETERS.minFragmentLength();
			for (int i = 0; i < 35; i++) {
				final String word = words.get(random.nextInt(words.size()));
				final List<String> queries = new ArrayList<>(3);
				queries.add(word.substring(0, 2) + "x" + word.substring(3));
				queries.add(word);
				if (word.length() > prefixLength) {
					queries.add(word.substring(0, prefixLength));
				}
				for (final String query : queries) {
					for (final boolean unfinished : new boolean[]{false, true}) {
						final Map<String, String> expected = oracle(words, query, unfinished);
						assertTrue(expected.size() < CAP, "Oracle hits must stay below every expansion cap.");
						assertEquals(expected, describe(expand(query, unfinished, query)), query + "/" + unfinished);
					}
				}
			}
		}

		/**
		 * Classifies the dictionary against one query by a linear scan: an equal word is exact, a longer word
		 * extending an expandable unfinished query is a prefix hit, and any other word sharing the frozen initials is
		 * fuzzy when its matrix distance stays within the query's edit budget.
		 */
		private Map<String, String> oracle(List<String> words, String query, boolean unfinished) {
			final Map<String, String> expected = new TreeMap<>();
			final int length = query.codePointCount(0, query.length());
			final boolean prefix = unfinished && DEFAULT_PARAMETERS.prefixLeg()
				&& length >= DEFAULT_PARAMETERS.minFragmentLength();
			final boolean openEnd = prefix && length >= DEFAULT_PARAMETERS.openEndedStartLength();
			final int frozenEnd = query.offsetByCodePoints(0, Math.min(length, DEFAULT_PARAMETERS.nonFuzzyPrefix()));
			final String frozen = query.substring(0, frozenEnd);
			for (final String candidate : words) {
				if (candidate.equals(query)) {
					expected.put(candidate, "EXACT:0");
				} else if (prefix && candidate.startsWith(query)) {
					expected.put(candidate, "PREFIX:0");
				} else if (candidate.startsWith(frozen)) {
					// Matrix distance on the unfrozen suffix cannot edit across the frozen boundary.
					final String queryTail = query.substring(frozenEnd);
					final String candidateTail = candidate.substring(frozenEnd);
					int distance = EditDistances.distance(queryTail, candidateTail,
						DEFAULT_PARAMETERS.transpositions());
					if (openEnd) {
						for (int offset = 0; offset < candidateTail.length();
							offset += Character.charCount(candidateTail.codePointAt(offset))) {
							distance = Math.min(distance, EditDistances.distance(queryTail,
								candidateTail.substring(0, offset), DEFAULT_PARAMETERS.transpositions()));
						}
					}
					if (distance > 0 && distance <= DEFAULT_PARAMETERS.maxEdits(query)) {
						expected.put(candidate, "FUZZY:" + distance);
					}
				}
			}
			return expected;
		}
	}
	/** Extension declarations determine whether an unfinished word may open the completion guard. */
	@Nested
	@DisplayName("Extension completion policy")
	class ExtensionCompletion {
		/** A stopped query word survives as an unfinished fragment when its provider declares omission. */
		@ParameterizedTest
		@ValueSource(booleans = {true, false})
		@DisplayName("Completes a custom stop word only when the fragment bypasses its filter")
		void shouldCompleteDeclaredStopWord(boolean omitted) {
			final TransformingWords provider = omitted
				? new OmittedFragmentWords("stop") : new TransformingWords("stop");
			withAnalyzer(provider, analyzer -> assertEquals(omitted ? 1 : 0,
				FulltextSurfaceTestSupport.expand(analyzer, "that", "thatch", fragments(analyzer, "that")).size()));
		}

		/** A query synonym must not lengthen a short fragment past the completion guard. */
		@ParameterizedTest
		@ValueSource(booleans = {true, false})
		@DisplayName("Keeps a three-letter synonym source below the completion guard")
		void shouldKeepSynonymSourceBelowGuard(boolean omitted) {
			final TransformingWords provider = omitted
				? new OmittedFragmentWords("synonym") : new TransformingWords("synonym");
			withAnalyzer(provider, analyzer -> assertEquals(omitted ? 0 : 1,
				FulltextSurfaceTestSupport.expand(analyzer, "car", "automobileshop", fragments(analyzer, "car")).size()));
		}
	}

}
