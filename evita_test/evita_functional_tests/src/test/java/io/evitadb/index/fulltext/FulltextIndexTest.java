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

package io.evitadb.index.fulltext;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Expansion;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;
import io.evitadb.index.fulltext.analysis.AnalyzedTerm;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static io.evitadb.utils.AssertionUtils.assertWarmUpSavepointRollbackRestores;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link FulltextIndex}: field registration, the posting and impact maintenance of the dictionary, the
 * isolation of fields sharing one dictionary, the analyzing write path, and the transactional contract — the last two
 * against an index rebuilt from scratch, the strongest oracle available.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(FULLTEXT)
@DisplayName("Fulltext index")
class FulltextIndexTest {

	/**
	 * Words the randomized tests build values from: Czech declension variants the analyzer folds together, stop words,
	 * and diacritics.
	 */
	private static final String[] VOCABULARY = {
		"Praha", "Brno", "Ostrava", "kolo", "kola", "kolem", "horské", "horský", "elektrické", "město",
		"městem", "hrad", "hradu", "řeka", "řeky", "most", "mostu", "a", "na", "pro", "žlutý", "kůň"
	};

	/**
	 * Exclusive upper bound of the primary keys the randomized tests use.
	 */
	private static final int PK_BOUND = 300;

	/**
	 * Separates the elements of an array value in the value model of the randomized tests - a character the analyzer
	 * drops, so a model value with the separators replaced by spaces is the same text indexed as one value.
	 */
	private static final char ELEMENT_SEPARATOR = '|';

	/**
	 * Registry providing the real Czech index-slot analyzer.
	 */
	private static FulltextAnalyzerRegistry registry;

	/**
	 * The Czech index-slot analyzer every index in this test uses.
	 */
	private static FulltextAnalyzer analyzer;

	@BeforeAll
	static void setUpAnalyzer() {
		registry = new FulltextAnalyzerRegistry();
		analyzer = registry.getIndexAnalyzer("product", Locale.forLanguageTag("cs"));
	}

	@AfterAll
	static void closeRegistry() {
		registry.close();
	}

	/**
	 * Creates an empty index over the Czech analyzer.
	 *
	 * @return the index
	 */
	@Nonnull
	private static FulltextIndex newIndex() {
		return new FulltextIndex(analyzer);
	}

	@Nested
	@DisplayName("Fields")
	class Fields {

		@Test
		@DisplayName("Field ids are assigned in first-use order and never change")
		void shouldAssignStableFieldIds() {
			final FulltextIndex index = newIndex();
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, index.getFieldId("name"));
			assertEquals(0, index.getOrAssignFieldId("name"));
			assertEquals(1, index.getOrAssignFieldId("description", 120.0));
			assertEquals(0, index.getOrAssignFieldId("name"));
			assertEquals(1, index.getFieldId("description"));
			assertEquals("description", index.getFieldName(1));
			assertNull(index.getFieldName(2));
			assertNull(index.getFieldName(-1));
			assertEquals(2, index.getFieldCount());
			assertEquals(FulltextIndex.DEFAULT_LENGTH_PIVOT, index.getLengthPivot(0));
			assertEquals(120.0, index.getLengthPivot(1));
		}

		@Test
		@DisplayName("A field's pivot cannot change, and a pivot must be positive")
		void shouldRefusePivotChangeAndInvalidPivot() {
			final FulltextIndex index = newIndex();
			index.getOrAssignFieldId("body", 200.0);
			assertEquals(0, index.getOrAssignFieldId("body", 200.0));
			assertThrows(GenericEvitaInternalError.class, () -> index.getOrAssignFieldId("body", 100.0));
			assertThrows(GenericEvitaInternalError.class, () -> index.getOrAssignFieldId("title", 0.0));
			assertThrows(GenericEvitaInternalError.class, () -> index.getOrAssignFieldId("title", Double.NaN));
			assertThrows(GenericEvitaInternalError.class, () -> new FulltextIndex(analyzer, -1.0));
		}

		@Test
		@DisplayName("A field id the index never assigned is refused")
		void shouldRefuseUnassignedFieldId() {
			final FulltextIndex index = newIndex();
			index.getOrAssignFieldId("name");
			assertThrows(GenericEvitaInternalError.class, () -> index.addPosting(1, "term", 1, 10));
			assertThrows(GenericEvitaInternalError.class, () -> index.removePosting(-1, "term", 1));
			assertThrows(GenericEvitaInternalError.class, () -> index.getPostings(1, "term"));
			assertThrows(GenericEvitaInternalError.class, () -> index.getImpacts(1, "term"));
			assertThrows(GenericEvitaInternalError.class, () -> index.getFieldLengths(1));
			assertThrows(
				GenericEvitaInternalError.class, () -> index.forEachTerm(1, "", (term, postings, impacts) -> true)
			);
		}

	}

	@Nested
	@DisplayName("Postings")
	class Postings {

		@Test
		@DisplayName("Postings and their impacts are added, replaced and removed; a term leaves with its last posting")
		void shouldMaintainPostingsAndImpacts() {
			final FulltextIndex index = newIndex();
			final int name = index.getOrAssignFieldId("name");
			index.addPosting(name, "praha", 7, 70);
			index.addPosting(name, "praha", 3, 30);
			assertArrayEquals(new int[]{3, 7}, index.getPostings(name, "praha").getArray());
			assertArrayEquals(new byte[]{30, 70}, index.getImpacts(name, "praha"));
			// re-adding a present posting replaces its impact
			index.addPosting(name, "praha", 7, 77);
			assertArrayEquals(new byte[]{30, 77}, index.getImpacts(name, "praha"));
			assertEquals(1, index.getTermCount());

			index.removePosting(name, "praha", 3);
			assertArrayEquals(new int[]{7}, index.getPostings(name, "praha").getArray());
			assertArrayEquals(new byte[]{77}, index.getImpacts(name, "praha"));
			// removing a posting that is not there changes nothing
			index.removePosting(name, "praha", 99);
			index.removePosting(name, "brno", 7);
			assertArrayEquals(new int[]{7}, index.getPostings(name, "praha").getArray());

			index.removePosting(name, "praha", 7);
			assertTrue(index.getPostings(name, "praha").isEmpty());
			assertEquals(0, index.getImpacts(name, "praha").length);
			assertEquals(0, index.getTermCount());
		}

		@Test
		@DisplayName("An impact outside 1..255 is refused")
		void shouldRefuseImpactOutOfRange() {
			final FulltextIndex index = newIndex();
			final int name = index.getOrAssignFieldId("name");
			assertThrows(GenericEvitaInternalError.class, () -> index.addPosting(name, "term", 1, 0));
			assertThrows(GenericEvitaInternalError.class, () -> index.addPosting(name, "term", 1, 256));
		}

		@Test
		@DisplayName("The same term in two fields is two independent posting lists")
		void shouldIsolateFieldsSharingATerm() {
			final FulltextIndex index = newIndex();
			final int name = index.getOrAssignFieldId("name");
			final int description = index.getOrAssignFieldId("description");
			index.addPosting(name, "kolo", 1, 11);
			index.addPosting(description, "kolo", 2, 22);
			index.addPosting(description, "kolo", 3, 33);
			assertArrayEquals(new int[]{1}, index.getPostings(name, "kolo").getArray());
			assertArrayEquals(new byte[]{22, 33}, index.getImpacts(description, "kolo"));
			assertEquals(2, index.getTermCount());

			index.removePosting(name, "kolo", 1);
			assertTrue(index.getPostings(name, "kolo").isEmpty());
			assertArrayEquals(new int[]{2, 3}, index.getPostings(description, "kolo").getArray());
		}

		@Test
		@DisplayName("A term walk visits exactly the field's terms with the prefix, in order, across leaves")
		void shouldWalkOnlyTheFieldsTermsWithThePrefix() {
			final FulltextIndex index = newIndex();
			// eleven fields, so ids 9 and 10 straddle the digit/letter boundary of the hexadecimal prefix
			final int fieldCount = 11;
			final List<Map<String, TreeSet<Integer>>> expected = new ArrayList<>(fieldCount);
			final Random random = new Random(7);
			for (int i = 0; i < fieldCount; i++) {
				assertEquals(i, index.getOrAssignFieldId("field" + i));
				expected.add(new TreeMap<>());
			}
			// enough terms that one field spans several 256-bucket leaves
			for (int i = 0; i < 6_000; i++) {
				final int fieldId = random.nextInt(fieldCount);
				final String term = randomTerm(random);
				final int primaryKey = random.nextInt(50);
				index.addPosting(fieldId, term, primaryKey, 1 + random.nextInt(255));
				expected.get(fieldId).computeIfAbsent(term, t -> new TreeSet<>()).add(primaryKey);
			}

			for (int fieldId = 0; fieldId < fieldCount; fieldId++) {
				assertWalkEquals(expected.get(fieldId), index, fieldId, "");
				for (String prefix : new String[]{"a", "ab", "č", "zz", "nonexistent"}) {
					final Map<String, TreeSet<Integer>> filtered = new TreeMap<>();
					for (Map.Entry<String, TreeSet<Integer>> entry : expected.get(fieldId).entrySet()) {
						if (entry.getKey().startsWith(prefix)) {
							filtered.put(entry.getKey(), entry.getValue());
						}
					}
					assertWalkEquals(filtered, index, fieldId, prefix);
				}
			}
		}

		@Test
		@DisplayName("A term walk stops when the visitor asks it to")
		void shouldStopWalkWhenVisitorDeclines() {
			final FulltextIndex index = newIndex();
			final int name = index.getOrAssignFieldId("name");
			for (String term : new String[]{"a", "b", "c", "d"}) {
				index.addPosting(name, term, 1, 1);
			}
			final List<String> visited = new ArrayList<>(4);
			index.forEachTerm(name, "", (term, postings, impacts) -> {
				visited.add(term);
				return visited.size() < 2;
			});
			assertEquals(List.of("a", "b"), visited);
		}

		@Test
		@DisplayName("Postings and impacts stay aligned through every bucket representation")
		void shouldKeepPostingsAndImpactsThroughBucketPromotion() {
			final FulltextIndex index = newIndex();
			final int body = index.getOrAssignFieldId("body");
			final int title = index.getOrAssignFieldId("title");
			// past the sorted-array tier, into a bitmap spanning three roaring containers
			final TreeMap<Integer, Integer> expected = new TreeMap<>();
			for (int primaryKey = 140_000; primaryKey >= 0; primaryKey -= 97) {
				final int impact = 1 + primaryKey % 255;
				index.addPosting(body, "the", primaryKey, impact);
				expected.put(primaryKey, impact);
			}
			index.addPosting(title, "the", 5, 9);
			assertPostingsEqual(expected, index, body, "the");
			assertArrayEquals(new byte[]{9}, index.getImpacts(title, "the"));

			for (int primaryKey = 0; primaryKey <= 140_000; primaryKey += 2 * 97) {
				index.removePosting(body, "the", primaryKey);
				expected.remove(primaryKey);
			}
			assertPostingsEqual(expected, index, body, "the");
			assertArrayEquals(new int[]{5}, index.getPostings(title, "the").getArray());
		}

		@Test
		@DisplayName("The scorer ranks on the impacts a walk hands out exactly as on the impacts a descent copies")
		void shouldScoreOnWalkImpactsAsOnCopies() {
			final FulltextIndex index = newIndex();
			final int body = index.getOrAssignFieldId("body");
			final Random random = new Random(99);
			// buckets in every tier: chunked bitmaps over many roaring containers, sorted arrays, singles
			for (int primaryKey = 0; primaryKey < 400_000; primaryKey += 1 + random.nextInt(200)) {
				index.addPosting(body, "the", primaryKey, 1 + random.nextInt(255));
				if (random.nextInt(50) == 0) {
					index.addPosting(body, "thermal", primaryKey, 1 + random.nextInt(255));
				}
			}
			// a single-record bucket, on a primary key both candidate sets below contain
			index.addPosting(body, "theory", 2_991, 77);
			final List<Expansion> fromWalk = new ArrayList<>(3);
			final List<Expansion> fromCopies = new ArrayList<>(3);
			index.forEachTerm(body, "the", (term, postings, impacts) -> {
				final int[] postingArray = postings.getArray();
				fromWalk.add(new Expansion(postingArray, impacts, 0));
				fromCopies.add(new Expansion(postingArray, index.getImpacts(body, term), 0));
				return true;
			});
			assertEquals(3, fromWalk.size());
			// candidates both sparse and dense against the postings, so both merge strategies run
			for (final int stride : new int[]{3, 997}) {
				final int[] candidates = new int[400_000 / stride];
				for (int i = 0; i < candidates.length; i++) {
					candidates[i] = i * stride;
				}
				final Result walked = FulltextPhaseOneScorer.score(
					candidates, new Expansion[][]{fromWalk.toArray(Expansion[]::new)}, 50
				);
				final Result copied = FulltextPhaseOneScorer.score(
					candidates, new Expansion[][]{fromCopies.toArray(Expansion[]::new)}, 50
				);
				assertTrue(walked.matchedDocuments() > 0);
				assertEquals(copied.matchedDocuments(), walked.matchedDocuments());
				assertArrayEquals(copied.primaryKeys(), walked.primaryKeys());
				assertArrayEquals(copied.composites(), walked.composites());
			}
		}

	}

	@Nested
	@DisplayName("Write path")
	class WritePath {

		@Test
		@DisplayName("A value is analyzed into postings carrying the computed impacts, and its length is recorded")
		void shouldIndexValueWithImpactsAndLength() {
			final FulltextIndex index = newIndex();
			final String value = "Praha a Praha a Brno";
			index.addValue("name", 10, value);
			final int name = index.getFieldId("name");

			final Map<String, Integer> frequencies = termFrequencies(value);
			final int length = positions(value);
			assertTrue(length > 0);
			assertEquals(length, index.getFieldLengths(name).getLength(10));
			for (Map.Entry<String, Integer> entry : frequencies.entrySet()) {
				assertArrayEquals(new int[]{10}, index.getPostings(name, entry.getKey()).getArray(), entry.getKey());
				final int expectedImpact = FulltextIndex.computeImpact(
					entry.getValue(), length, FulltextIndex.DEFAULT_LENGTH_PIVOT
				);
				assertEquals(expectedImpact, Byte.toUnsignedInt(index.getImpacts(name, entry.getKey())[0]));
			}
			assertEquals(frequencies.size(), index.getTermCount());
		}

		@Test
		@DisplayName("A value is indexed into a field registered with its own pivot, and its impacts use that pivot")
		void shouldAddValueToFieldRegisteredWithCustomPivot() {
			final FulltextIndex index = newIndex();
			final double pivot = 7.0;
			final int body = index.getOrAssignFieldId("body", pivot);
			index.addValue("body", 1, "Praha a Praha a Brno");
			index.addValue("body", 2, new String[]{"Praha", "Brno"});
			assertEquals(body, index.getOrAssignFieldId("body"), "a known field is found, whatever its pivot");
			assertEquals(pivot, index.getLengthPivot(body));
			final String praha = termFrequencies("Praha").keySet().iterator().next();
			assertArrayEquals(new int[]{1, 2}, index.getPostings(body, praha).getArray());
			final int expectedImpact = FulltextIndex.computeImpact(
				termFrequencies("Praha a Praha a Brno").get(praha), positions("Praha a Praha a Brno"), pivot
			);
			assertEquals(expectedImpact, Byte.toUnsignedInt(index.getImpacts(body, praha)[0]));

			index.removeValue("body", 1, "Praha a Praha a Brno");
			index.removeValue("body", 2, new String[]{"Praha", "Brno"});
			assertEquals(0, index.getTermCount());
			assertEquals(0, index.getFieldLengths(body).size());
			// a removal never registers a field
			index.removeValue("unknown", 1, "Praha");
			index.removeValue("unknown", 1, new String[]{"Praha"});
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, index.getFieldId("unknown"));
			assertEquals(1, index.getFieldCount());
		}

		@Test
		@DisplayName("Impacts grow with term frequency and shrink with field length, and never reach zero")
		void shouldComputeImpactsMonotonically() {
			final double pivot = 20.0;
			for (int length = 1; length < 2_000; length += 7) {
				int previous = 0;
				for (int frequency = 1; frequency <= Math.min(length, 50); frequency++) {
					final int impact = FulltextIndex.computeImpact(frequency, length, pivot);
					assertTrue(impact >= previous && impact >= 1 && impact <= 255);
					previous = impact;
				}
			}
			for (int frequency = 1; frequency < 10; frequency++) {
				int previous = 256;
				for (int length = frequency; length < 5_000; length += 13) {
					final int impact = FulltextIndex.computeImpact(frequency, length, pivot);
					assertTrue(impact <= previous, "impact rose with length at " + length);
					previous = impact;
				}
			}
			assertEquals(1, FulltextIndex.computeImpact(1, 10_000_000, pivot));
		}

		@Test
		@DisplayName("Removing a value removes its postings and length and leaves other entities untouched")
		void shouldRemoveValue() {
			final FulltextIndex index = newIndex();
			index.addValue("name", 1, "Praha hlavní město");
			index.addValue("name", 2, "Praha a Brno");
			final int name = index.getFieldId("name");
			final String praha = termFrequencies("Praha").keySet().iterator().next();
			assertArrayEquals(new int[]{1, 2}, index.getPostings(name, praha).getArray());

			index.removeValue("name", 1, "Praha hlavní město");
			assertArrayEquals(new int[]{2}, index.getPostings(name, praha).getArray());
			assertEquals(0, index.getFieldLengths(name).getLength(1));
			assertTrue(index.getFieldLengths(name).getLength(2) > 0);
			for (String term : termFrequencies("hlavní město").keySet()) {
				assertTrue(index.getPostings(name, term).isEmpty(), term);
			}
			// removing from an unknown field, or a value without tokens, changes nothing
			index.removeValue("unknown", 2, "Praha");
			index.removeValue("name", 2, "!!!");
			assertArrayEquals(new int[]{2}, index.getPostings(name, praha).getArray());
			assertEquals(positions("Praha a Brno"), index.getFieldLengths(name).getLength(2));
		}

		@Test
		@DisplayName("A second value for the same entity and field is refused until the first is removed")
		void shouldRefuseSecondValueUntilRemoved() {
			final FulltextIndex index = newIndex();
			index.addValue("name", 1, "Praha");
			assertThrows(GenericEvitaInternalError.class, () -> index.addValue("name", 1, "Brno"));
			index.removeValue("name", 1, "Praha");
			index.addValue("name", 1, "Brno");
			assertEquals(termFrequencies("Brno").size(), index.getTermCount());
		}

		@Test
		@DisplayName("A value without tokens indexes nothing")
		void shouldIndexNothingForValueWithoutTokens() {
			final FulltextIndex index = newIndex();
			index.addValue("name", 1, "!!! ...");
			assertEquals(0, index.getTermCount());
			assertEquals(0, index.getFieldLengths(index.getFieldId("name")).size());
		}

		@Test
		@DisplayName("An array value is indexed as the text its elements make one after another")
		void shouldIndexArrayAsOneText() {
			final FulltextIndex index = newIndex();
			index.addValue("keywords", 1, new String[]{"horské kolo", "kolo Praha"});
			index.addValue("keywords", 2, "Brno");
			final FulltextIndex expected = newIndex();
			expected.addValue("keywords", 1, "horské kolo kolo Praha");
			expected.addValue("keywords", 2, "Brno");
			// summed frequencies (`kolo` twice), summed length, and therefore the very same impacts
			assertEquals(contentOf(expected), contentOf(index));
			final int keywords = index.getFieldId("keywords");
			assertEquals(
				positions("horské kolo") + positions("kolo Praha"), index.getFieldLengths(keywords).getLength(1)
			);

			// one entry per entity: a second value is refused in either form
			assertThrows(GenericEvitaInternalError.class, () -> index.addValue("keywords", 1, "Ostrava"));
			assertThrows(
				GenericEvitaInternalError.class, () -> index.addValue("keywords", 1, new String[]{"Ostrava"})
			);

			// the whole array goes, in any element order
			index.removeValue("keywords", 1, new String[]{"kolo Praha", "horské kolo"});
			final FulltextIndex onlyBrno = newIndex();
			onlyBrno.addValue("keywords", 2, "Brno");
			assertEquals(contentOf(onlyBrno), contentOf(index));
		}

		@Test
		@DisplayName("An array without tokens indexes nothing, and an array with a null element is refused")
		void shouldIndexNothingForArrayWithoutTokens() {
			final FulltextIndex index = newIndex();
			index.addValue("keywords", 1, new String[0]);
			index.addValue("keywords", 2, new String[]{"!!!", "..."});
			assertEquals(0, index.getTermCount());
			assertEquals(0, index.getFieldLengths(index.getFieldId("keywords")).size());
			assertThrows(
				GenericEvitaInternalError.class, () -> index.addValue("keywords", 3, new String[]{"Praha", null})
			);
			assertEquals(0, index.getTermCount());

			// an array whose elements but one produce no token is that one element
			index.addValue("keywords", 4, new String[]{"!!!", "Praha", ""});
			final FulltextIndex expected = newIndex();
			expected.addValue("keywords", 4, "Praha");
			assertEquals(contentOf(expected), contentOf(index));
		}

		@Test
		@DisplayName("Random adds and removes leave the index identical to one rebuilt from the surviving values")
		void shouldMatchRebuildFromScratch() {
			final Random random = new Random(1234);
			final FulltextIndex index = newIndex();
			// field -> primary key -> value currently indexed
			final Map<String, Map<Integer, String>> live = new TreeMap<>();
			final String[] fields = {"name", "description", "keywords"};
			for (String field : fields) {
				// registered up front, so the ids do not depend on which field the steps happen to touch first
				index.getOrAssignFieldId(field);
			}
			applyRandomValueOperations(random, index, live, fields, 4_000);

			final FulltextIndex rebuilt = rebuild(index, live);
			assertEquals(rebuilt.getTermCount(), index.getTermCount());
			for (String field : fields) {
				final int fieldId = index.getFieldId(field);
				assertEquals(rebuilt.getFieldId(field), fieldId);
				final List<String> rebuiltTerms = new ArrayList<>(64);
				rebuilt.forEachTerm(fieldId, "", (term, postings, impacts) -> rebuiltTerms.add(term));
				final List<String> terms = new ArrayList<>(64);
				index.forEachTerm(fieldId, "", (term, postings, impacts) -> terms.add(term));
				assertEquals(rebuiltTerms, terms, field);
				for (String term : terms) {
					assertArrayEquals(
						rebuilt.getPostings(fieldId, term).getArray(), index.getPostings(fieldId, term).getArray(),
						field + "/" + term
					);
					assertArrayEquals(
						rebuilt.getImpacts(fieldId, term), index.getImpacts(fieldId, term), field + "/" + term
					);
				}
				for (int primaryKey = 0; primaryKey < PK_BOUND; primaryKey++) {
					assertEquals(
						rebuilt.getFieldLengths(fieldId).getEncoded(primaryKey),
						index.getFieldLengths(fieldId).getEncoded(primaryKey)
					);
				}
			}
			assertEquals(contentOf(rebuilt), contentOf(index));
		}

	}

	@Nested
	@DisplayName("Transactions")
	@Tag(TRANSACTION)
	class Transactions {

		@Test
		@DisplayName("Writes, a field registration included, are visible in the transaction and published at commit")
		void shouldPublishWritesAtCommit() {
			final FulltextIndex index = newIndex();
			index.addValue("name", 1, "horské kolo");
			final String before = contentOf(index);
			final String bike = termFrequencies("kolo").keySet().iterator().next();
			assertStateAfterCommit(
				index,
				t -> {
					t.addValue("name", 2, "elektrické kolo");
					t.addValue("brand", 1, "Praha");
					t.removeValue("name", 1, "horské kolo");
					assertEquals(1, t.getFieldId("brand"));
					assertEquals("brand", t.getFieldName(1));
					assertEquals(2, t.getFieldCount());
					assertArrayEquals(new int[]{2}, t.getPostings(0, bike).getArray());
					assertEquals(1, t.getFieldLengths(1).size());
				},
				(original, committed) -> {
					assertEquals(before, contentOf(original));
					assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, original.getFieldId("brand"));
					assertEquals(1, original.getFieldCount());
					final FulltextIndex expected = newIndex();
					expected.addValue("name", 2, "elektrické kolo");
					expected.addValue("brand", 1, "Praha");
					assertEquals(contentOf(expected), contentOf(committed));
				}
			);
		}

		@Test
		@DisplayName("A rolled-back transaction leaves the index and its field registry untouched")
		void shouldDiscardWritesOnRollback() {
			final FulltextIndex index = newIndex();
			index.addValue("name", 1, "horské kolo");
			final String before = contentOf(index);
			assertStateAfterRollback(
				index,
				t -> {
					t.addValue("brand", 1, "Praha");
					t.removeValue("name", 1, "horské kolo");
				},
				(original, committed) -> {
					assertNull(committed);
					assertEquals(before, contentOf(original));
					assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, original.getFieldId("brand"));
				}
			);
		}

		@Test
		@DisplayName("An index the transaction only read is carried forward as the same instance")
		void shouldCarryUntouchedIndexForward() {
			final FulltextIndex index = newIndex();
			index.addValue("name", 1, "horské kolo");
			assertStateAfterCommit(
				index,
				t -> {
					assertEquals(0, t.getOrAssignFieldId("name"));
					t.forEachTerm(0, "", (term, postings, impacts) -> true);
				},
				(original, committed) -> assertSame(original, committed)
			);
		}

		@Test
		@DisplayName("Random adds and removes across commits match a rebuild after every commit")
		void shouldMatchRebuildAcrossCommits() {
			final Random random = new Random(4321);
			final Map<String, Map<Integer, String>> live = new TreeMap<>();
			// the later fields are first touched inside a transaction, so their registration is transactional
			final String[] fields = {"name", "description", "keywords", "brand"};
			FulltextIndex index = newIndex();
			applyRandomValueOperations(random, index, live, new String[]{"name"}, 600);
			for (int round = 0; round < 8; round++) {
				final String before = contentOf(index);
				final FulltextIndex[] next = new FulltextIndex[1];
				final String[] seenInTransaction = new String[1];
				assertStateAfterCommit(
					index,
					t -> {
						applyRandomValueOperations(random, t, live, fields, 300);
						// no rebuild in here: a fresh index written inside the transaction would leave layers the
						// commit never sweeps - what the transaction reads is compared with the committed state instead
						seenInTransaction[0] = contentOf(t);
					},
					(original, committed) -> {
						assertEquals(before, contentOf(original));
						final String committedContent = contentOf(committed);
						assertEquals(contentOf(rebuild(committed, live)), committedContent);
						assertEquals(committedContent, seenInTransaction[0], "the transaction read its own writes");
						next[0] = committed;
					}
				);
				index = next[0];
			}
			assertEquals(fields.length, index.getFieldCount());
		}

		@Test
		@DisplayName("A savepoint rollback restores the index, field registry included; a savepoint commit keeps it")
		void shouldRestoreOnSavepointRollback() {
			final Random random = new Random(77);
			final FulltextIndex index = newIndex();
			final Map<String, Map<Integer, String>> live = new TreeMap<>();
			applyRandomValueOperations(random, index, live, new String[]{"name", "description"}, 400);
			// each helper runs its own transaction against the untouched original, so each starts from its own copy
			final Map<String, Map<Integer, String>> rolledBack = copyOf(live);
			assertSavepointRollbackRestores(
				index,
				t -> applyRandomValueOperations(random, t, rolledBack, new String[]{"name", "keywords"}, 100),
				FulltextIndexTest::contentOf,
				// a field first used inside the savepoint must be unregistered by its rollback
				t -> applyRandomValueOperations(random, t, copyOf(rolledBack), new String[]{"name", "brand"}, 100)
			);
			final Map<String, Map<Integer, String>> scratch = copyOf(live);
			assertSavepointCommitKeeps(
				index,
				t -> applyRandomValueOperations(random, t, scratch, new String[]{"name"}, 100),
				FulltextIndexTest::contentOf,
				t -> applyRandomValueOperations(random, t, scratch, new String[]{"description", "brand"}, 100)
			);
		}

		@Test
		@DisplayName("A warm-up savepoint rollback restores the index, field registry included")
		void shouldRestoreOnWarmUpSavepointRollback() {
			final Random random = new Random(78);
			final FulltextIndex index = newIndex();
			final Map<String, Map<Integer, String>> live = new TreeMap<>();
			assertWarmUpSavepointRollbackRestores(
				index,
				t -> applyRandomValueOperations(random, t, live, new String[]{"name", "description"}, 400),
				FulltextIndexTest::contentOf,
				t -> applyRandomValueOperations(random, t, copyOf(live), new String[]{"name", "brand"}, 200)
			);
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, index.getFieldId("brand"));
			// the restored index keeps working: the next registration takes the id the rolled-back one had
			assertEquals(2, index.getOrAssignFieldId("brand"));
		}

	}

	/**
	 * Applies random value replacements, additions and removals to an index and to the model of what it holds.
	 *
	 * @param random the source of randomness
	 * @param index  the index under test
	 * @param live   field to primary key to the value currently indexed; updated alongside the index
	 * @param fields the fields to draw from
	 * @param steps  how many steps to take
	 */
	private static void applyRandomValueOperations(
		@Nonnull Random random,
		@Nonnull FulltextIndex index,
		@Nonnull Map<String, Map<Integer, String>> live,
		@Nonnull String[] fields,
		int steps
	) {
		for (int step = 0; step < steps; step++) {
			final String field = fields[random.nextInt(fields.length)];
			final int primaryKey = random.nextInt(PK_BOUND);
			final Map<Integer, String> values = live.computeIfAbsent(field, f -> new TreeMap<>());
			final String current = values.get(primaryKey);
			if (current != null) {
				if (current.indexOf(ELEMENT_SEPARATOR) >= 0) {
					// an array is removed whole, and in any element order
					final List<String> elements = new ArrayList<>(List.of(splitElements(current)));
					Collections.shuffle(elements, random);
					index.removeValue(field, primaryKey, elements.toArray(String[]::new));
				} else {
					index.removeValue(field, primaryKey, current);
				}
				values.remove(primaryKey);
			}
			if (current == null || random.nextBoolean()) {
				// one value in four is an array of two or three elements
				final int elements = random.nextInt(4) == 0 ? 2 + random.nextInt(2) : 1;
				final StringBuilder value = new StringBuilder(64);
				for (int e = 0; e < elements; e++) {
					if (e > 0) {
						value.append(ELEMENT_SEPARATOR);
					}
					final int words = 1 + random.nextInt(elements == 1 ? 12 : 5);
					for (int w = 0; w < words; w++) {
						value.append(VOCABULARY[random.nextInt(VOCABULARY.length)]).append(' ');
					}
				}
				final String modelled = value.toString();
				if (elements == 1) {
					index.addValue(field, primaryKey, modelled);
				} else {
					index.addValue(field, primaryKey, splitElements(modelled));
				}
				values.put(primaryKey, modelled);
			}
		}
	}

	/**
	 * Builds a fresh index holding exactly the modelled values, with the fields registered in the order - and with the
	 * pivots - the reference index registered them, so the two assign the same ids. An array value is indexed as the
	 * single text its elements make when written one after another, which is what the array semantics promise - so
	 * the rebuild is an independent oracle of them, not a replay of the array API.
	 *
	 * @param reference the index whose field registry to copy
	 * @param live      field to primary key to value
	 * @return the rebuilt index
	 */
	@Nonnull
	private static FulltextIndex rebuild(
		@Nonnull FulltextIndex reference,
		@Nonnull Map<String, Map<Integer, String>> live
	) {
		final FulltextIndex rebuilt = newIndex();
		for (int fieldId = 0; fieldId < reference.getFieldCount(); fieldId++) {
			rebuilt.getOrAssignFieldId(reference.getFieldName(fieldId), reference.getLengthPivot(fieldId));
		}
		for (Map.Entry<String, Map<Integer, String>> entry : live.entrySet()) {
			for (Map.Entry<Integer, String> value : entry.getValue().entrySet()) {
				rebuilt.addValue(
					entry.getKey(), value.getKey(), value.getValue().replace(ELEMENT_SEPARATOR, ' ')
				);
			}
		}
		return rebuilt;
	}

	/**
	 * Splits a modelled array value into its elements.
	 *
	 * @param modelled the value as the model holds it, elements separated by {@link #ELEMENT_SEPARATOR}
	 * @return the elements
	 */
	@Nonnull
	private static String[] splitElements(@Nonnull String modelled) {
		return modelled.split("\\" + ELEMENT_SEPARATOR);
	}

	/**
	 * Deep-copies the value model, so a step sequence that is going to be rolled back cannot corrupt the original.
	 *
	 * @param live field to primary key to value
	 * @return an independent copy
	 */
	@Nonnull
	private static Map<String, Map<Integer, String>> copyOf(@Nonnull Map<String, Map<Integer, String>> live) {
		final Map<String, Map<Integer, String>> copy = new TreeMap<>();
		for (Map.Entry<String, Map<Integer, String>> entry : live.entrySet()) {
			copy.put(entry.getKey(), new TreeMap<>(entry.getValue()));
		}
		return copy;
	}

	/**
	 * Dumps everything an index holds, as the caller's transaction sees it: every field with its pivot, every term with
	 * its postings and their impacts in posting order, every length, and the dictionary size.
	 *
	 * @param index the index to dump
	 * @return the dump, comparable with `equals`
	 */
	@Nonnull
	private static String contentOf(@Nonnull FulltextIndex index) {
		final StringBuilder content = new StringBuilder(8_192);
		for (int fieldId = 0; fieldId < index.getFieldCount(); fieldId++) {
			content.append(index.getFieldName(fieldId)).append('@').append(index.getLengthPivot(fieldId)).append('\n');
			final int theFieldId = fieldId;
			index.forEachTerm(fieldId, "", (term, postings, view) -> {
				content.append(term).append(':');
				final byte[] impacts = index.getImpacts(theFieldId, term);
				// the walk reads the impacts off the leaf it stands on; they must be the ones a descent finds
				assertArrayEquals(impacts, view.toArray(), "walk impacts of " + term);
				final OfInt it = postings.iterator();
				int ordinal = 0;
				while (it.hasNext()) {
					content.append(' ').append(it.nextInt()).append('/').append(impacts[ordinal++] & 0xFF);
				}
				assertEquals(ordinal, impacts.length, "impacts misaligned with the postings of " + term);
				content.append('\n');
				return true;
			});
			final FieldLengthTable lengths = index.getFieldLengths(fieldId);
			content.append("lengths ").append(lengths.size()).append(':');
			for (int primaryKey = 0; primaryKey < PK_BOUND; primaryKey++) {
				final int encoded = lengths.getEncoded(primaryKey);
				if (encoded != 0) {
					content.append(' ').append(primaryKey).append('/').append(encoded);
				}
			}
			content.append('\n');
		}
		return content.append("terms ").append(index.getTermCount()).toString();
	}

	/**
	 * Analyzes a value with the test's analyzer and counts its distinct terms.
	 *
	 * @param value the value
	 * @return each distinct term with its frequency
	 */
	@Nonnull
	private static Map<String, Integer> termFrequencies(@Nonnull String value) {
		final Map<String, Integer> frequencies = new HashMap<>();
		for (AnalyzedTerm term : analyzer.getTerms(value)) {
			frequencies.merge(term.term(), 1, Integer::sum);
		}
		return frequencies;
	}

	/**
	 * Counts the token positions of a value with the test's analyzer.
	 *
	 * @param value the value
	 * @return the number of terms arriving at a positive position increment
	 */
	private static int positions(@Nonnull String value) {
		int positions = 0;
		for (AnalyzedTerm term : analyzer.getTerms(value)) {
			if (term.positionIncrement() > 0) {
				positions++;
			}
		}
		return positions;
	}

	/**
	 * Asserts a term's postings and impacts equal the expected map.
	 *
	 * @param expected expected impact by primary key
	 * @param index    the index
	 * @param fieldId  the field
	 * @param term     the term
	 */
	private static void assertPostingsEqual(
		@Nonnull TreeMap<Integer, Integer> expected,
		@Nonnull FulltextIndex index,
		int fieldId,
		@Nonnull String term
	) {
		final int[] postings = index.getPostings(fieldId, term).getArray();
		final byte[] impacts = index.getImpacts(fieldId, term);
		assertEquals(expected.size(), postings.length);
		assertEquals(expected.size(), impacts.length);
		int i = 0;
		for (Map.Entry<Integer, Integer> entry : expected.entrySet()) {
			assertEquals(entry.getKey(), postings[i]);
			assertEquals(entry.getValue(), Byte.toUnsignedInt(impacts[i]), "impact of " + entry.getKey());
			i++;
		}
	}

	/**
	 * Asserts a walk over the field with the prefix yields exactly the expected terms and postings, in order.
	 *
	 * @param expected expected terms with their postings, in term order
	 * @param index    the index to walk
	 * @param fieldId  the field to walk
	 * @param prefix   the term prefix
	 */
	private static void assertWalkEquals(
		@Nonnull Map<String, TreeSet<Integer>> expected,
		@Nonnull FulltextIndex index,
		int fieldId,
		@Nonnull String prefix
	) {
		final List<String> terms = new ArrayList<>(expected.size());
		final List<int[]> postings = new ArrayList<>(expected.size());
		index.forEachTerm(fieldId, prefix, (term, termPostings, termImpacts) -> {
			terms.add(term);
			postings.add(termPostings.getArray());
			return true;
		});
		assertEquals(new ArrayList<>(expected.keySet()), terms, "field " + fieldId + ", prefix `" + prefix + "`");
		int i = 0;
		for (TreeSet<Integer> expectedPostings : expected.values()) {
			final int[] expectedArray = new int[expectedPostings.size()];
			int j = 0;
			for (Integer primaryKey : expectedPostings) {
				expectedArray[j++] = primaryKey;
			}
			assertArrayEquals(expectedArray, postings.get(i), terms.get(i));
			i++;
		}
	}

	/**
	 * Generates a short term over an alphabet that includes a diacritic, so the walk crosses non-ASCII keys.
	 *
	 * @param random the source of randomness
	 * @return a term of one to four characters
	 */
	@Nonnull
	private static String randomTerm(@Nonnull Random random) {
		final String alphabet = "abcčz";
		final int length = 1 + random.nextInt(4);
		final StringBuilder term = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			term.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return term.toString();
	}

}
