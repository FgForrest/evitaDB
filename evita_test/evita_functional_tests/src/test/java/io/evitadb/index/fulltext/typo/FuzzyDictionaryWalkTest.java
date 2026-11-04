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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.test.fulltext.EditDistances;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static io.evitadb.index.fulltext.FulltextIndexTestSupport.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.*;

/** Engine walk checked against independent linear scans over real analyzed fields. */
@Tag(ENGINE)
@Tag(FULLTEXT)
@DisplayName("Fuzzy dictionary walks over fulltext fields")
class FuzzyDictionaryWalkTest {
	private static FulltextIndex index;
	private static final int[] FIELDS = new int[3];

	/** Builds several leaves through the real write path, with overlapping and field-specific terms. */
	@BeforeAll
	static void createIndex() {
		index = new FulltextIndex();
		final Random random = new Random(42);
		final int[] alphabet = "abcdeěšč𐐨ａ".codePoints().toArray();
		for (int field = 0; field < FIELDS.length; field++) {
			final FulltextFieldKey key = FulltextFieldKey.attribute("field" + field);
			FIELDS[field] = registerExactField(index, key, 25.0);
			addWords(index, key, 1, "cerna", "cerne", "cern", "cernaa", "cenra", "cxrnx", "xerna",
				"bunda", "bnuda", "kalhoty", "kalhot", "kalht", "blackberry", "black", "blakc",
				"mikina", "pansky", "stul", "zidle", "dum", "kabat", "a", "aa", "ab", "ba",
				"𐐨", "ａ", "𐐨b", "𐐨a", "𐐩a", "a𐐨", "aａ");
			addWords(index, key, 2, "cerna", "cerna", "bunda");
			addWords(index, key, 100 + field, "isolated");
			for (int i = 0; i < 600; i++) {
				addWords(index, key, 1000 + i, randomWord(random, alphabet, 8));
			}
		}
	}

	/** Produces a seeded word of one to `maxLength` code points drawn from the alphabet. */
	private static String randomWord(Random random, int[] alphabet, int maxLength) {
		final StringBuilder word = new StringBuilder(2 * maxLength);
		for (int i = random.nextInt(maxLength) + 1; i > 0; i--) {
			word.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
		}
		return word.toString();
	}

	/** Returns the independent minimum distance over code-point prefixes, with or without one-edit swaps. */
	private static int distance(String query, String term, boolean transpositions, boolean openEnd) {
		int best = EditDistances.distance(query, term, transpositions);
		if (openEnd) {
			for (int end = 0; end < term.length();) {
				best = Math.min(best, EditDistances.distance(query, term.substring(0, end), transpositions));
				end += Character.charCount(term.codePointAt(end));
			}
		}
		return best;
	}

	/** Collects every term of the field the linear scan accepts, in dictionary order, with its oracle distance. */
	private static List<String> linearScan(
		FulltextIndex fulltextIndex, int field, String query, int maxEdits, int frozen,
		boolean transpositions, boolean openEnd
	) {
		final String prefix = query.substring(0, query.offsetByCodePoints(0,
			Math.min(frozen, query.codePointCount(0, query.length()))));
		final List<String> accepted = new ArrayList<>();
		fulltextIndex.forEachTerm(field, "", (term, postings, impacts) -> {
			final int edits = distance(query, term, transpositions, openEnd);
			if (term.startsWith(prefix) && edits <= maxEdits) {
				accepted.add(term + "@" + edits);
			}
			return true;
		});
		return accepted;
	}

	/** Collects hits of the shared fixture without exposing the index's private dictionary. */
	private static List<String> walk(FulltextDictionaryWalker walker, int field) {
		return walk(index, walker, field);
	}

	/** Collects hits without exposing the index's private dictionary. */
	private static List<String> walk(FulltextIndex fulltextIndex, FulltextDictionaryWalker walker, int field) {
		final List<String> hits = new ArrayList<>();
		fulltextIndex.forEachTerm(field, walker, (term, postings, impacts, edits, frequency) -> {
			assertEquals(postings.size(), frequency);
			assertEquals(frequency, impacts.size());
			hits.add(term + "@" + edits);
			return true;
		});
		return hits;
	}

	/** Finite and open-ended equivalence. */
	@Nested
	@DisplayName("Finite and open-ended equivalence")
	class Equivalence {
		/** Reused automata return the linear scan's terms, distances and dictionary order in every field. */
		@ParameterizedTest
		@CsvSource({"cerny,2,1", "cerny,2,0", "bnuda,1,1", "kalht,2,1", "blakc,1,1",
			"mikina,1,0", "pansky,2,1", "stul,1,1", "zidle,2,1", "dum,1,1", "kabat,2,0",
			"xqzv,2,0", "a,2,0", "a,0,9", "𐐨a,1,1", "𐐨a,2,9", "ａ,1,0", "'',2,0"})
		@DisplayName("Reused automata return the linear scan's terms, distances and order with either swap policy")
		void shouldEqualLinearScan(String query, int maxEdits, int frozen) {
			for (final boolean transpositions : new boolean[]{true, false}) {
				for (final boolean openEnd : new boolean[]{false, true}) {
					final FulltextDictionaryWalker walker =
						new FulltextDictionaryWalker(query, maxEdits, frozen, transpositions, openEnd);
					for (final int field : FIELDS) {
						assertEquals(
							linearScan(index, field, query, maxEdits, frozen, transpositions, openEnd),
							walk(walker, field),
							query + " transpositions=" + transpositions + " open=" + openEnd + " field=" + field
						);
					}
				}
			}
		}

		/** Open-ended distance can improve after the first accepting prefix. */
		@Test
		@DisplayName("Open-ended distance can improve after the first accepting prefix")
		void shouldKeepSmallestPrefixDistance() {
			assertTrue(walk(new FulltextDictionaryWalker("black", 2, 1, true, true), FIELDS[0])
				.contains("blackberry@0"));
		}

		/** The empty word at distance zero accepts every continuation only when open-ended. */
		@Test
		@DisplayName("The empty word at distance zero accepts every continuation only when open-ended")
		void shouldHandleEmptyLanguageRoot() {
			assertTrue(walk(new FulltextDictionaryWalker("", 0, 1, true, false), FIELDS[0]).isEmpty());
			final List<String> all = new ArrayList<>();
			index.forEachTerm(FIELDS[0], "", (term, postings, impacts) -> { all.add(term + "@0"); return true; });
			assertEquals(all, walk(new FulltextDictionaryWalker("", 0, 1, true, true), FIELDS[0]));
		}
	}

	/** Field boundaries and visitor contract. */
	@Nested
	@DisplayName("Field boundaries and visitor contract")
	class Boundaries {
		/** The same term in adjacent fields never leaks its other postings. */
		@Test
		@DisplayName("The same term in adjacent fields never leaks its other postings")
		void shouldStayInOneField() {
			final FulltextDictionaryWalker walker = new FulltextDictionaryWalker("isolated", 2, 0, true, false);
			for (int i = 0; i < FIELDS.length; i++) {
				final int primaryKey = 100 + i;
				final List<String> hits = new ArrayList<>();
				index.forEachTerm(FIELDS[i], walker, (term, postings, impacts, edits, frequency) -> {
					assertArrayEquals(new int[]{primaryKey}, postings.getArray());
					assertEquals(1, frequency);
					hits.add(term);
					return true;
				});
				assertEquals(List.of("isolated"), hits);
			}
		}

		/** Every hit carries the postings and impacts an exact read of the same term returns. */
		@Test
		@DisplayName("Visitors receive the postings and impacts an exact read of the term returns")
		void shouldSupplyTheSamePostingsAndImpactsAsAnExactRead() {
			final List<String> hits = new ArrayList<>();

			index.forEachTerm(FIELDS[0], new FulltextDictionaryWalker("cerna", 2, 1, true, false),
				(term, postings, impacts, edits, frequency) -> {
					assertArrayEquals(postingsOf(index, FIELDS[0], term).getArray(), postings.getArray());
					assertArrayEquals(impactsOf(index, FIELDS[0], term), impacts.toArray());
					hits.add(term);
					return true;
				});

			assertTrue(hits.size() > 1, "The query must reach several terms.");
		}

		/** A visitor returning false ends the walk at its first hit. */
		@Test
		@DisplayName("A visitor returning false ends the walk at the first hit")
		void shouldStopWhenVisitorReturnsFalse() {
			final List<String> hits = new ArrayList<>();

			index.forEachTerm(FIELDS[0], new FulltextDictionaryWalker("cerna", 2, 1, true, false),
				(term, postings, impacts, edits, frequency) -> {
					hits.add(term + "@" + edits);
					return false;
				});

			final List<String> all = walk(new FulltextDictionaryWalker("cerna", 2, 1, true, false), FIELDS[0]);
			assertTrue(all.size() > 1, "The query must reach several terms.");
			assertEquals(all.subList(0, 1), hits);
		}

		/** A walker stopped by its visitor carries no seek state into its next walk. */
		@Test
		@DisplayName("A walker stopped by its visitor walks the whole field on the next call")
		void shouldBeReusableAfterVisitorStops() {
			final FulltextDictionaryWalker walker = new FulltextDictionaryWalker("cerna", 2, 1, true, false);
			index.forEachTerm(FIELDS[0], walker, (term, postings, impacts, edits, frequency) -> false);

			final List<String> reused = walk(walker, FIELDS[0]);

			assertEquals(walk(new FulltextDictionaryWalker("cerna", 2, 1, true, false), FIELDS[0]), reused);
		}

		/** A field id the index never assigned is a caller error, reported before any term is visited. */
		@Test
		@DisplayName("An unassigned field id is rejected")
		void shouldRejectUnknownFieldId() {
			final FulltextDictionaryWalker walker = new FulltextDictionaryWalker("a", 1, 0, true, false);

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class, () -> index.forEachTerm(999, walker, (t, p, i, d, f) -> true)
			);

			assertTrue(error.getMessage().contains("999"), error.getMessage());
		}
	}

	/** Constructor validation. */
	@Nested
	@DisplayName("Construction")
	class Construction {
		/** Rejects an edit budget outside zero to two and a negative frozen prefix, naming the broken limit. */
		@ParameterizedTest
		@CsvSource({"-1,0,between zero and two", "3,0,between zero and two", "1,-1,must not be negative"})
		@DisplayName("Rejects an edit budget outside zero to two and a negative frozen prefix")
		void shouldRejectInvalidEditBudgetAndNegativePrefix(int maxEdits, int frozen, String reason) {
			final IllegalArgumentException error = assertThrows(
				IllegalArgumentException.class, () -> new FulltextDictionaryWalker("a", maxEdits, frozen, true, false)
			);

			assertTrue(error.getMessage().contains(reason), error.getMessage());
		}
	}

	/** Automaton properties. */
	@Nested
	@DisplayName("Automaton properties")
	class Properties {
		/** Accepts all single edit kinds, freezes whole code points and supports disabling swaps. */
		@Test
		@DisplayName("Accepts all single edit kinds, freezes whole code points and supports disabling swaps")
		void shouldAcceptSingleEdits() {
			final List<String> hits = walk(new FulltextDictionaryWalker("cerna", 1, 1, true, false), FIELDS[0]);
			assertTrue(hits.containsAll(List.of("cerna@0", "cerne@1", "cern@1", "cernaa@1", "cenra@1")));
			assertFalse(hits.contains("xerna@1"));
			assertFalse(hits.contains("cxrnx@2"));
			assertFalse(walk(new FulltextDictionaryWalker("cerna", 1, 1, false, false), FIELDS[0])
				.contains("cenra@1"));
			final List<String> supplementary = walk(new FulltextDictionaryWalker("𐐨a", 1, 1, true, false), FIELDS[0]);
			assertTrue(supplementary.contains("𐐨b@1"));
			assertFalse(supplementary.contains("𐐩a@1"));
		}
	}

	/** Preserved malformed UTF-16 and the scalar-successor boundary. */
	@Nested
	@DisplayName("Dictionary terms containing unpaired surrogates")
	class SurrogateTerms {
		/** Finds a well-formed exact term after a rejected lone high surrogate in both walk modes. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("A rejected lone high surrogate never hides the later exact supplementary term")
		void shouldFindExactTermAfterLoneHighSurrogate(boolean openEnd) {
			final String target = "a\uD801\uDC28";
			assertKeywordWalk(target, 0, openEnd, List.of(target), "a\uD800", target);
		}

		/** The bounded visitor catches the old backward seek even if a timeout would never fire. */
		@Test
		@DisplayName("A lone low surrogate after the exact supplementary term never repeats the hit")
		void shouldTerminateAfterLoneLowSurrogate() {
			final String target = "a\uDBFF\uDC01";
			assertKeywordWalk(target, 0, false, List.of(target), target, "a\uDC00");
		}

		/** A malformed term remains eligible when linear advancement reaches its bucket. */
		@Test
		@DisplayName("An accepted lone-surrogate term is visited when reached linearly")
		void shouldVisitAcceptedLoneSurrogate() {
			assertKeywordWalk("a", 1, false, List.of("a\uD800"), "a\uD800");
		}

		/** A malformed query must not send a scalar successor into a forced surrogate transition. */
		@Test
		@DisplayName("A query containing a lone surrogate still reaches well-formed accepted terms")
		void shouldScanWhenQueryContainsLoneSurrogate() {
			assertKeywordWalk("abc\uD800d", 1, false, List.of("abcad"), "a\u0000x", "abcad");
		}

		/** Indexes whole Java strings through keyword analysis and bounds every visitor independently of time. */
		private void assertKeywordWalk(
			String query, int maxEdits, boolean openEnd, List<String> expected, String... terms
		) {
			final FulltextIndex localIndex = new FulltextIndex();
			final FulltextFieldKey key = FulltextFieldKey.attribute("keywordTerms");
			final CompiledFulltextAnalyzer analyzer = keywordAnalyzer();
			try (final FulltextAnalyzer chain = analyzer.index()) {
				for (int i = 0; i < terms.length; i++) {
					localIndex.addValue(key, i + 1, analyzer, 25.0, null, terms[i]);
				}
				final int field = localIndex.getFieldId(key);
				for (final String term : terms) {
					assertEquals(
						1, postingsOf(localIndex, field, term).size(), "Keyword analysis must preserve the term."
					);
				}
				final List<String> actual = new ArrayList<>();
				final FulltextDictionaryWalker walker = new FulltextDictionaryWalker(query, maxEdits, 1, true, openEnd);
				assertDoesNotThrow(() -> localIndex.forEachTerm(field, walker, (term, postings, impacts, edits, df) -> {
					actual.add(term);
					return actual.size() <= terms.length;
				}));
				assertTrue(actual.size() <= terms.length, "A dictionary bucket must never be visited twice.");
				assertEquals(expected, actual);
			}
		}
	}

	/** Successor seeks across the seams between the scalar intervals of UTF-16 order. */
	@Nested
	@DisplayName("Successor seeks at the seams of UTF-16 order")
	class Utf16OrderSeams {
		/**
		 * Words over the code points on both sides of every seam - `U+D7FF` and `U+E000` around the surrogates,
		 * `U+FFFF` before `U+10000`, and `U+10FFFF` before `U+E000` - make successors cross each seam, and `U+FFFF`,
		 * the last scalar in UTF-16 order, forces the backtrack past a position that cannot be incremented.
		 */
		@Test
		@DisplayName("Words over the interval edges return the linear scan's terms and distances in every walk mode")
		void shouldEqualLinearScanAtIntervalEdges() {
			final int[] alphabet = {'a', 'b', 0xD7FF, 0xE000, 0xFFFF, 0x10000, 0x10FFFF};
			final Random random = new Random(7);
			final FulltextIndex localIndex = new FulltextIndex();
			final FulltextFieldKey key = FulltextFieldKey.attribute("edgeTerms");
			final CompiledFulltextAnalyzer analyzer = keywordAnalyzer();
			try (final FulltextAnalyzer chain = analyzer.index()) {
				final List<String> words = new ArrayList<>(400);
				for (int i = 0; i < 400; i++) {
					words.add(randomWord(random, alphabet, 6));
					localIndex.addValue(key, i + 1, analyzer, 25.0, null, words.get(i));
				}
				final int field = localIndex.getFieldId(key);
				for (final String word : words) {
					assertTrue(
						postingsOf(localIndex, field, word).size() > 0, "Keyword analysis must preserve the word."
					);
				}
				int walksWithHits = 0;

				for (int round = 0; round < 60; round++) {
					final String query = randomWord(random, alphabet, 4);
					final int maxEdits = random.nextInt(3);
					final int frozen = random.nextInt(2);
					final boolean transpositions = random.nextBoolean();
					for (final boolean openEnd : new boolean[]{false, true}) {
						final List<String> expected =
							linearScan(localIndex, field, query, maxEdits, frozen, transpositions, openEnd);
						final FulltextDictionaryWalker walker =
							new FulltextDictionaryWalker(query, maxEdits, frozen, transpositions, openEnd);
						assertEquals(
							expected, walk(localIndex, walker, field),
							query.codePoints().mapToObj(Integer::toHexString).toList() + " edits=" + maxEdits
								+ " frozen=" + frozen + " transpositions=" + transpositions + " open=" + openEnd
						);
						walksWithHits += expected.isEmpty() ? 0 : 1;
					}
				}

				assertTrue(walksWithHits > 0, "The queries must reach accepted terms.");
			}
		}
	}
}
