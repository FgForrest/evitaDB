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

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.Arrays;
import java.util.Locale;
import static io.evitadb.api.query.parser.DefaultQueryParser.getInstance;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/** Split-word recall and quality ordering through real catalog writes and evitaQL. */
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FULLTEXT)
@DisplayName("Fulltext word coverage and quality ranking")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FulltextWordRankingFunctionalTest implements EvitaTestSupport {
	private TestPaths paths;
	private Evita evita;

	/** Shares products and independently normalized field groups across read-only tests. */
	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("FulltextWordRankingFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getCatalogSchema().openForWrite()
				.withFulltextLanguage("en", l -> l.wordNumberSplit())
				.withFulltextAnalyzer("folded", a -> a.words(lowercase(), asciiFolding()))
				.withFulltextAnalyzer("lower", a -> a.words(lowercase())).updateVia(session);
			session.defineEntitySchema("product").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("title", String.class, a -> a.localized().searchable().analyzer("folded"))
				.withAttribute("brand", String.class, a -> a.localized().searchable().analyzer("lower"))
				.updateVia(session);
			for (final String type : new String[]{
				"quality", "coverage", "scoreSources", "splitScoreSources", "delimiterSplit"
			}) {
				session.defineEntitySchema(type).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("title", String.class, a -> a.localized().searchable().analyzer("lower"))
					.updateVia(session);
			}
			session.upsertEntity(session.createNewEntity("quality", 1).setAttribute("title", Locale.ENGLISH,
				"abcxefxh kabat " + "zzzz ".repeat(200)));
			session.upsertEntity(session.createNewEntity("quality", 2)
				.setAttribute("title", Locale.ENGLISH, "abcxefxh kabatka"));
			session.upsertEntity(session.createNewEntity("delimiterSplit", 1)
				.setAttribute("title", Locale.ENGLISH, "abcdefgh"));
			session.upsertEntity(session.createNewEntity("delimiterSplit", 2).setAttribute("title", Locale.ENGLISH,
				"abcdefgh_x " + "zzzz ".repeat(200)));
			for (int pk = 1; pk <= 10000; pk++) {
				session.upsertEntity(session.createNewEntity("coverage", pk)
					.setAttribute("title", Locale.ENGLISH, "7800"));
			}
			for (final String type : new String[]{"scoreSources", "splitScoreSources"}) {
				final boolean split = type.equals("splitScoreSources");
				final String a = split ? "amber1234" : "amber";
				final String b = split ? "violet5678" : "violet";
				session.upsertEntity(session.createNewEntity(type, 1).setAttribute("title", Locale.ENGLISH,
					a + " " + (b + " ").repeat(8)));
				session.upsertEntity(session.createNewEntity(type, 2).setAttribute("title", Locale.ENGLISH,
					(a + " ").repeat(8) + b));
			}
			final String[] coverageValues = {
				"needle UHD7800", "needle UHD 7800", "needle UHD", "needle 7800", "needle neither"
			};
			for (int i = 0; i < coverageValues.length; i++) {
				session.upsertEntity(session.createNewEntity("coverage", 10001 + i)
					.setAttribute("title", Locale.ENGLISH, coverageValues[i]));
			}
			final String[][] values = {
				{"TV UHD7800", "unused"}, {"TV UHD 7800", "unused"}, {"7800", "unused"},
				{"UHD", "unused"}, {"TV unrelated", "unused"}, {"TV 7800", "unused"},
				{"TV UHD", "unused"}, {"UHD7800", "unused"},
				{"kabat many filler words making the impact lower", "unused"}, {"kabatka", "unused"},
				{"Kabel USB-C", "unused"}, {"Kabelka", "unused"},
				{"alxha beta", "alpha bexa"}, {"alxha bexa", "unused"},
				{"abcdefgh", "unused"}, {"abcxefgh", "unused"}, {"1235", "unused"}
			};
			for (int i = 0; i < values.length; i++) {
				session.upsertEntity(session.createNewEntity("product", i + 1)
					.setAttribute("title", Locale.ENGLISH, values[i][0])
					.setAttribute("brand", Locale.ENGLISH, values[i][1]));
			}
		});
	}

	/** Releases the shared catalog. */
	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/** Runs bound text through evitaQL and observes relevance order. */
	private int[] search(String text) {
		return search("product", text);
	}

	/** Ranks a query in the requested shared collection. */
	private int[] search(String type, String text) {
		return queryPrimaryKeys(getInstance().parseQuery(
			"query(collection(?), filterBy(entityLocaleEquals(?), textMatches(?)), " +
				"orderBy(relevanceText()), require(page(?, ?)))",
			type, Locale.ENGLISH, text, 1, 100
		));
	}

	/** Returns the primary keys of the page a query selects, in result order. */
	private int[] queryPrimaryKeys(Query query) {
		return this.evita.queryCatalog(TEST_CATALOG, session -> {
			return session.query(query, EntityReference.class).getRecordData().stream()
				.mapToInt(EntityReference::getPrimaryKey).toArray();
		});
	}

	/** Retrieves the strict count without requesting relevance. */
	private int count(Query query) {
		return this.evita.queryCatalog(TEST_CATALOG, session -> {
			return session.query(query, EntityReference.class).getTotalRecordCount();
		});
	}

	/**
	 * Asserts a ranking whose order inside each tier is left to impact: the top tier fills the first positions in any
	 * order, and the rest follows in any order.
	 *
	 * @param actual the ranked primary keys
	 * @param top    the keys of the top tier, ascending
	 * @param rest   the keys ranked below it, ascending
	 */
	private static void assertTiers(int[] actual, int[] top, int[] rest) {
		assertEquals(top.length + rest.length, actual.length, () -> Arrays.toString(actual));
		final int[] first = Arrays.copyOf(actual, top.length);
		Arrays.sort(first);
		assertArrayEquals(top, first, () -> "top tier of " + Arrays.toString(actual));
		final int[] second = Arrays.copyOfRange(actual, top.length, actual.length);
		Arrays.sort(second);
		assertArrayEquals(rest, second, () -> "lower tier of " + Arrays.toString(actual));
	}

	@Nested
	@DisplayName("Coverage queries")
	class CoverageQueries {
		/** A common number part is narrowed by the independent mandatory word before ranking coverage. */
		@Test
		@DisplayName("Ranks complete and partial coverage correctly beside ten thousand common-part postings")
		void shouldRankNarrowedCommonPart() {
			assertTiers(search("coverage", "needle UHD7800 "), new int[]{10001, 10002}, new int[]{10003, 10004});
		}

		/** Count-only queries retain their strict result with many repeated split groups and common postings. */
		@Test
		@DisplayName("Keeps the narrowed count for an unranked query with repeated split groups")
		void shouldKeepNarrowedCountWithRepeatedSplitGroups() {
			final Query query = getInstance().parseQuery(
				"query(collection(?), filterBy(entityLocaleEquals(?), textMatches(?)), require(page(?, ?)))",
				"coverage", Locale.ENGLISH, "needle " + "UHD7800 ".repeat(100), 1, 1
			);
			assertEquals(4, count(query));
		}
	}

	@Nested
	@DisplayName("Independent relevance sources")
	class ScoreSources {
		/** OR must retain both score accessors when terms share their posting ids but have opposite impacts. */
		@ParameterizedTest
		@CsvSource({"false,false", "false,true", "true,false", "true,true"})
		@DisplayName("Keeps both impact sources through OR deduplication, including split queries")
		void shouldKeepBothScoreSourcesInOr(boolean split, boolean reversed) {
			final String type = split ? "splitScoreSources" : "scoreSources";
			final String a = split ? "amber1234 " : "amber ";
			final String b = split ? "violet5678 " : "violet ";
			assertArrayEquals(new int[]{2, 1}, search(type, a));
			assertArrayEquals(new int[]{1, 2}, search(type, b));
			final int[] actual = queryPrimaryKeys(getInstance().parseQuery(
				"query(collection(?), filterBy(entityLocaleEquals(?), or(textMatches(?), textMatches(?))), " +
					"orderBy(relevanceText()), require(page(?, ?)))",
				type, Locale.ENGLISH, reversed ? b : a, reversed ? a : b, 1, 100
			));
			assertArrayEquals(new int[]{1, 2}, actual);
		}
	}

	@Nested
	@DisplayName("Offset-contained alternatives")
	class SplitWords {
		/** Every partial match is recalled, but complete coverage beats either single part. */
		@ParameterizedTest
		@ValueSource(strings = {"UHD7800", "UHD7800 "})
		@DisplayName("ORs original and parts, ranking original and all parts above partial coverage")
		void shouldRankCompleteCoverageFirst(String text) {
			assertTiers(search(text), new int[]{1, 2, 8}, new int[]{3, 4, 6, 7});
		}

		/** A word part has the same finite typo policy as a standalone word; numeric parts stay exact. */
		@Test
		@DisplayName("Expands letter parts with typos while retaining exact digits")
		void shouldExpandPartsIndependently() {
			assertArrayEquals(new int[]{15, 16}, search("abcdefgh1234 "));
		}

		/**
		 * The original's typo leg reaches its own part `abcdefgh` two deletions away; the document holding only that
		 * part must still rank below the one holding the whole word, whose filler lowers its impact so a tie would sink
		 * it.
		 */
		@Test
		@DisplayName("Ranks the whole word above a document holding only a part its typo neighbours reach")
		void shouldRankOriginalAboveFuzzyReachOfItsOwnPart() {
			assertArrayEquals(new int[]{2, 1}, search("delimiterSplit", "abcdefgh_x "));
		}

		/** The surrounding word is a separate mandatory unit. */
		@Test
		@DisplayName("Keeps TV mandatory beside the split group")
		void shouldKeepUnrelatedWordMandatory() {
			assertTiers(search("TV UHD7800 "), new int[]{1, 2}, new int[]{6, 7});
		}
	}

	@Nested
	@DisplayName("Quality above impact")
	class Quality {
		/** A long real query has equal 170-edit cost and higher impact on the prefix document. */
		@Test
		@DisplayName("Ranks exact kabat above prefix in the 86-word query")
		void shouldKeepExactBeforePrefixInLongQuery() {
			assertArrayEquals(new int[]{1, 2}, search("quality", "abcdefgh ".repeat(85) + "kabat"));
		}

		/** The shorter prefix value has higher impact than the much longer exact value. */
		@Test
		@DisplayName("Ranks typed kabat exactly above its higher-impact prefix")
		void shouldRankExactAboveHighImpactPrefix() {
			assertArrayEquals(new int[]{9, 10}, search("kabat"));
		}

		/** The submitted kabel example also retains the exact-before-prefix promise. */
		@Test
		@DisplayName("Ranks Kabel USB-C above Kabelka")
		void shouldRankKabelAboveKabelka() {
			assertArrayEquals(new int[]{11, 12}, search("kabel"));
		}

		/** Independent groups supply the best match of each query word, counted once. */
		@Test
		@DisplayName("Unions profile groups before choosing per-word quality")
		void shouldTakeBestWordAcrossGroups() {
			assertArrayEquals(new int[]{13, 14}, search("alpha beta "));
		}
	}
}
