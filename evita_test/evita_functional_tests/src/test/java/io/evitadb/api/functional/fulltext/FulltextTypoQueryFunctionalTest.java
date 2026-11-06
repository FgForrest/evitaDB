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

package io.evitadb.api.functional.fulltext;

import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.core.query.filter.translator.fulltext.FulltextExpansionTestSupport.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/** Submitted-search expansion observed through real evitaQL constraints and catalog writes. */
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FULLTEXT)
@DisplayName("Submitted fulltext typo queries")
class FulltextTypoQueryFunctionalTest implements EvitaTestSupport {
	/** Isolated folders of this test's catalog. */
	private TestPaths paths;
	/** Engine with the two-profile catalog, created for every test. */
	private Evita evita;

	/** Defines a folding profile and an independent case-sensitive profile. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FulltextTypoQueryFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getCatalogSchema().openForWrite()
				.withFulltextResource("stops", wordList("jako"))
				.withFulltextAnalyzer("folded", a -> a.words(lowercase(), asciiFolding(), stopWords(resource("stops"))))
				.withFulltextAnalyzer("sensitive", a -> a.words())
				.updateVia(session);
			session.defineEntitySchema("product").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("title", String.class, a -> a.localized().searchable().analyzer("folded"))
				.withAttribute("brand", String.class, a -> a.localized().searchable().analyzer("sensitive"))
				.updateVia(session);
		});
	}

	/** Closes the catalog and removes its isolated folders. */
	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/** Writes values through the catalog mutation path. */
	private void put(int pk, String title, String brand) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.upsertEntity(session.createNewEntity("product", pk)
				.setAttribute("title", Locale.ENGLISH, title).setAttribute("brand", Locale.ENGLISH, brand));
		});
	}

	/** Compares the strict result set with explicitly expected primary keys. */
	private void matches(String text, String field, int... expected) {
		final int[] actual = this.evita.queryCatalog(TEST_CATALOG, session -> {
			return session.query(
			query(collection("product"), filterBy(entityLocaleEquals(Locale.ENGLISH),
				field == null ? textMatches(text) : textMatches(text, inField(field))),
				require(page(1, expected.length + 1))),
			EntityReference.class).getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray();
		});
		assertArrayEquals(expected, actual, text + " / " + field);
	}

	/** Thresholds measured after the field profile folds Czech diacritics. */
	@Nested
	@DisplayName("Typo policy in queries")
	class Policy {
		/** Folded code-point length controls each band, and a swap costs one edit. */
		@ParameterizedTest
		@CsvSource({"žlu,žxu,false", "žlut,žxut,true", "žluťouč,žxuťouč,true", "žluťouč,žxuťoux,false",
			"žluťoučký,žxuťoučxy,true", "abcd,acbd,true", "abcd,xbcd,false", "ab1234,ab1235,false"})
		@DisplayName("Applies folded length bands, digit exactness, frozen initials and transpositions")
		void shouldApplyTypoPolicy(String word, String indexed, boolean accepted) {
			put(1, indexed, "unrelated");
			matches(word + " ", "title", accepted ? new int[]{1} : new int[0]);
			matches(Normalizer.normalize(word, Normalizer.Form.NFD) + " ", "title",
				accepted ? new int[]{1} : new int[0]);
		}

		/** Every earlier word remains mandatory and uses the finished-word leg. */
		@Test
		@DisplayName("ANDs finished query words after typo expansion")
		void shouldKeepMustMatch() {
			put(1, "blackberry jacket", "unused");
			put(2, "blueberry jacket", "unused");
			matches("blakcberry jacxet ", "title", 1);
			matches("blakc jacxet ", "title");
			matches("blakc jacxet", "title");
		}
	}

	/** Raw input and field/profile isolation. */
	@Nested
	@DisplayName("Last query word")
	class LastWord {
		/** Whitespace and punctuation close the word, while the unfinished typo walk accepts suffixes. */
		@Test
		@DisplayName("Closes prefix and open-ended expansion on a trailing separator")
		void shouldCloseLastWord() {
			put(1, "blackberry", "unused");
			matches("blac", "title", 1);
			matches("blakc", "title", 1);
			matches("blac ", "title");
			matches("blakc.", "title");
			matches("bla", "title");
		}

		/** A trailing digit or combining mark leaves the last word open; a separator after it closes the word. */
		@Test
		@DisplayName("Keeps the last word unfinished when the text ends in a digit or a combining mark")
		void shouldKeepWordEndingInDigitOrMarkUnfinished() {
			put(1, "abcd1234", "unused");
			put(2, "cafeteria", "unused");
			matches("abcd12", "title", 1);
			matches("abcd12 ", "title");
			matches("abcd12.", "title");
			final String decomposed = Normalizer.normalize("café", Normalizer.Form.NFD);
			matches(decomposed, "title", 2);
			matches(decomposed + " ", "title");
		}

		/** A shared text phase erasing the final raw word must not reopen the preceding closed word. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Keeps corrected raw end offsets when a shared text phase erases or shortens the last word")
		void shouldKeepActualFinalWordIdentity(boolean severalGroups) {
			FulltextTypoQueryFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("erase", a -> a.text(removeCharacters("x")).words(lowercase()))
					.withFulltextAnalyzer("eraseOther",
						a -> a.text(removeCharacters("x")).words(lowercase(), asciiFolding()))
					.updateVia(session);
				session.getEntitySchemaOrThrowException("product").openForWrite()
					.withAttribute("title", String.class, a -> a.localized().searchable().analyzer("erase"))
					.withAttribute("brand", String.class,
						a -> a.localized().searchable().analyzer(severalGroups ? "eraseOther" : "erase"))
					.updateVia(session);
			});
			put(1, "blackberry", "blackberry");
			matches("blac x", null);
			matches("blac ", null);
			matches("blac", null, 1);
			matches("blacx", null, 1);
		}

		/** Raw splitting leaves the final word to each group's own text phase, whose corrected offsets keep it open. */
		@Test
		@DisplayName("Keeps a group's corrected raw end offsets when the profiles' text phases differ")
		void shouldKeepCorrectedOffsetsOfHeterogeneousTextPhase() {
			FulltextTypoQueryFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("erase", a -> a.text(removeCharacters("x")).words(lowercase()))
					.withFulltextAnalyzer("plain", a -> a.words(lowercase()))
					.updateVia(session);
				session.getEntitySchemaOrThrowException("product").openForWrite()
					.withAttribute("title", String.class, a -> a.localized().searchable().analyzer("erase"))
					.withAttribute("brand", String.class, a -> a.localized().searchable().analyzer("plain"))
					.updateVia(session);
			});
			put(1, "blackberry", "unrelated");
			matches("blacx", null, 1);
			matches("blacx ", null);
		}

		/** A length-changing provider without CharFilter offsets degrades to finished-word matching. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Treats uncorrected reader offsets as finished in homogeneous and heterogeneous groups")
		void shouldDegradeUncorrectedTextReader(boolean heterogeneous) {
			FulltextTypoQueryFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("uncorrected",
						a -> a.text(textStep("test-uncorrected-removal")).words(lowercase()))
					.withFulltextAnalyzer("plain", a -> a.words(lowercase())).updateVia(session);
				session.getEntitySchemaOrThrowException("product").openForWrite()
					.withAttribute("title", String.class, a -> a.localized().searchable().analyzer("uncorrected"))
					.withAttribute("brand", String.class,
						a -> a.localized().searchable().analyzer(heterogeneous ? "plain" : "uncorrected"))
					.updateVia(session);
			});
			put(1, "blackberry", "unrelated");
			put(2, "blac", "unrelated");
			matches("xblac", null, 2);
			matches("blacx", null, 2);
			// The plain profile retains x as a required word; the shortening profile erases it.
			matches("blac x", null, heterogeneous ? new int[0] : new int[]{2});
			matches("blac ", null, 2);
			matches("blac", null, 1, 2);
		}

		/** A stopped last word can be a fragment of a longer dictionary term. */
		@Test
		@DisplayName("Keeps a raw stopped fragment and drops finished stop words around it")
		void shouldExpandStoppedFragment() {
			put(1, "jakost", "unused");
			matches("JAKO", "title", 1);
			matches("jako jako", "title", 1);
			matches("jako ", "title");
			matches("jako blac", "title");
		}

		/** A stopped last word that completes to nothing leaves the query as if it had been closed. */
		@Test
		@DisplayName("Drops an unfinished stop word that completes to no dictionary term")
		void shouldDropUnfinishedStopWordWithoutCompletion() {
			put(1, "blackberry", "unused");
			matches("blackberry jako", "title", 1);
			matches("blackberry jako ", "title", 1);
		}

		/** A stop word typed without diacritics stays out of the query when only typo neighbours reach it. */
		@Test
		@DisplayName("Drops an unfinished folded stop word that only typo neighbours reach")
		void shouldDropUnfinishedFoldedStopWordReachingOnlyTypos() {
			FulltextTypoQueryFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextResource("accentedStops", wordList("když"))
					.withFulltextAnalyzer("accentedStop",
						a -> a.words(lowercase(), stopWords(resource("accentedStops"))))
					.updateVia(session);
				session.getEntitySchemaOrThrowException("product").openForWrite()
					.withAttribute("title", String.class, a -> a.localized().searchable().analyzer("accentedStop"))
					.updateVia(session);
			});
			put(1, "blackberry", "unused");
			put(2, "blackberry kdys", "unused");
			matches("blackberry kdyz", "title", 1, 2);
			matches("blackberry kdyz ", "title", 1, 2);
		}

		/** A large profile cannot consume the smaller profile's prefix budget. */
		@Test
		@DisplayName("Caps prefix expansions independently in several profile groups")
		void shouldCapEachGroup() {
			final int cap = effectiveTermsPerLeg();
			for (int i = 0; i <= cap; i++) {
				put(i + 1, "stem" + alphabeticSuffix(i), "unused");
			}
			final int otherGroup = cap + 2;
			put(otherGroup, "unused", "stemzzzz");
			final int[] expected = new int[cap + 1];
			for (int i = 0; i < cap; i++) {
				expected[i] = i + 1;
			}
			expected[cap] = otherGroup;
			matches("stem", null, expected);
		}

		/** Field restrictions and independently normalized profile groups cannot leak expansions. */
		@Test
		@DisplayName("Isolates fields and profile groups including prefix normalization")
		void shouldIsolateGroups() {
			put(1, "blackberry", "CaféStore");
			put(2, "unused", "blaxkberry");
			matches("blakcberry ", "title", 1);
			matches("blakcberry ", null, 1, 2);
			matches("blakcberry ", "brand", 2);
			matches("cafe", "brand");
			matches("Café", "brand", 1);
		}
	}
	/** Lane readers must retain the field group's profile and typed normalization. */
	@Nested
	@DisplayName("Surface lane query integration")
	class SurfaceLane {
		/** A stem from the Czech lane is never looked up in the independent brand group. */
		@Test
		@DisplayName("Keeps Czech surface stems out of an unstemmed brand field")
		void shouldIsolateSurfaceProfiles() {
			FulltextTypoQueryFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("czech-lane", a -> a.words(lowercase(), czechStemming()))
					.withFulltextAnalyzer("brand-lane", a -> a.words(lowercase())).updateVia(session);
				session.getEntitySchemaOrThrowException("product").openForWrite()
					.withAttribute("title", String.class, a -> a.analyzer("czech-lane"))
					.withAttribute("brand", String.class, a -> a.analyzer("brand-lane")).updateVia(session);
			});
			put(1, "černé", "unrelated");
			put(2, "unrelated", "Cern");
			matches("černé ", null, 1);
			matches("černé ", "brand");
		}

		/** Real query preparation must carry typed surface forms even for finished words. */
		@Test
		@DisplayName("Recovers a stemmer-distorted typo and a mid-ending fragment through the translator")
		void shouldRecoverThroughTranslator() {
			FulltextTypoQueryFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getEntitySchemaOrThrowException("product").openForWrite()
					.withAttribute("title", String.class, a -> a.analyzer("text")).updateVia(session);
			});
			put(1, "running", "unrelated");
			matches("runnign ", "title", 1);
			matches("runni", "title", 1);
			matches("runni ", "title");
		}

		/** German surface normalization lengthens sharp s before the four-code-point fragment guard. */
		@Test
		@DisplayName("Starts German completion when süß normalizes across the four-character boundary")
		void shouldNormalizeGermanFragmentBeforeGuard() {
			FulltextTypoQueryFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("german-lane", a -> a.words(lowercase(), germanStemming())).updateVia(session);
				session.getEntitySchemaOrThrowException("product").openForWrite()
					.withAttribute("title", String.class, a -> a.analyzer("german-lane")).updateVia(session);
			});
			put(1, "Süßigkeiten", "unrelated");
			matches("süß", "title", 1);
			matches("süß ", "title");
		}
	}

}
