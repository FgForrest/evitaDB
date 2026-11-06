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

import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.core.query.filter.translator.fulltext.FulltextExpansionCensusMeasurementTest.Group;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextTermKeys;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.*;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.lowercase;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.removeCharacters;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the translator's actual N/k inputs and census semantics over compact real indexes. */
@Tag(ENGINE)
@Tag(FULLTEXT)
@DisplayName("Fulltext postings budget and census integration")
class FulltextPostingsBudgetIntegrationTest implements EvitaTestSupport {
	/** Isolated test catalog folders. */
	private TestPaths paths;
	/** Real engine that runs every comparison query. */
	private Evita evita;

	/** Creates two heterogeneous schema profiles, including an optional absent physical generation. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FulltextPostingsBudgetIntegrationTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getCatalogSchema().openForWrite()
				.withFulltextLanguage("en", language -> language.wordNumberSplit()).updateVia(session);
			session.defineEntitySchema("product").withoutGeneratedPrimaryKey()
				.withLocale(Locale.ENGLISH).withLocale(Locale.FRENCH)
				.withAttribute("title", String.class, a -> a.localized().nullable().searchable().analyzer("text"))
				.withAttribute("body", String.class, a -> a.localized().nullable().searchable().analyzer("html-text"))
				.updateVia(session);
		});
	}

	/** Closes the unmodified persistent catalog; synthetic postings exist only in its in-memory read snapshot. */
	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Production planning")
	class Planning {
		/**
		 * N=1m, k=2 permits 1.7m postings but rejects 1.9m. The count pins the post-stop k: a k of 1 would also reject
		 * the first fuzzy term (count 0), a k of 3 would keep both fuzzy terms (count 300,000).
		 */
		@Test
		@DisplayName("Cuts real translator expansions using locale/scope N and post-stop mandatory k")
		void shouldApplyBudgetThroughTranslator() throws Exception {
			FulltextPostingsBudgetIntegrationTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.upsertEntity(session.createNewEntity("product", 1)
					.setAttribute("title", Locale.ENGLISH, "lamp bright"));
				session.upsertEntity(session.createNewEntity("product", 2)
					.setAttribute("title", Locale.FRENCH, "elsewhere"));
				session.upsertEntity(session.createNewEntity("product", 3)
					.setAttribute("title", Locale.ENGLISH, "archived").setScope(Scope.ARCHIVED));
			});
			final GlobalEntityIndex global = global(Scope.LIVE);
			widenScope(global, Map.of(Locale.ENGLISH, range(1, 1_000_001), Locale.FRENCH, range(1_000_001, 3_000_001)),
				3_000_001);
			final FulltextIndex index = global.getFulltextIndex(Locale.ENGLISH);
			final int field = index.getFieldId(FulltextFieldKey.attribute("title"));
			loadPostings(index, new int[]{field, field, field, field}, new String[]{"bright", "brighx", "lamp", "lump"},
				range(900_001, 1_000_001), range(1, 200_001), range(1, 700_001), range(100_001, 1_000_001));
			assertEquals(100_000, count("the lamp bright "));
			assertEquals(200_000, count("lamp brighx "), "The dropped term really has distinct matches");
		}

		/**
		 * The unfinished stop word `with` stays only for its completion `within`. At N=1m and k=2 the budget keeps
		 * `lump` (1.7m) and cuts `within` (2.0m), so the word must leave the query as if it had been closed: priced
		 * for k=1, which cuts `lump` too.
		 */
		@Test
		@DisplayName("Drops an unfinished stop word whose completions the budget cuts, as if it had been closed")
		void shouldDropUnfinishedStopWordWhenBudgetCutsItsCompletions() throws Exception {
			FulltextPostingsBudgetIntegrationTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.upsertEntity(session.createNewEntity("product", 1)
					.setAttribute("title", Locale.ENGLISH, "lamp within"));
			});
			final GlobalEntityIndex global = global(Scope.LIVE);
			widenScope(global, Map.of(Locale.ENGLISH, range(1, 1_000_001)), 1_000_001);
			final FulltextIndex index = global.getFulltextIndex(Locale.ENGLISH);
			final int field = index.getFieldId(FulltextFieldKey.attribute("title"));
			loadPostings(index, new int[]{field, field, field}, new String[]{"lamp", "lump", "within"},
				range(1, 700_001), range(1, 1_000_001), range(1, 300_001));
			assertEquals(300_000, count("with"), "The completion admits the unfinished stop word");
			assertEquals(700_000, count("lamp with "), "The closed stop word leaves and k=1 cuts the fuzzy term");
			assertEquals(700_000, count("lamp with"));
		}
	}

	@Nested
	@DisplayName("Census and translator agreement")
	class Census {
		/** A declared but absent profile must still participate in the shared query-text phase decision. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Keeps absent-generation profiles in the shared splitting decision")
		void shouldKeepAbsentProfilesForSplitting(boolean queryTextPhase) throws Exception {
			final String text = queryTextPhase ? "bright-winter" : "<p>bright winter jackets</p>";
			FulltextPostingsBudgetIntegrationTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				if (queryTextPhase) {
					// html-strip is index-only; a query-side text phase makes the splitting difference observable.
					session.getCatalogSchema().openForWrite().withFulltextAnalyzer("joined", a ->
						a.text(removeCharacters("-"))
							.words(lowercase())).updateVia(session);
					session.defineEntitySchema("product").withAttribute("body", String.class,
						a -> a.localized().nullable().searchable().analyzer("joined")).updateVia(session);
				}
				session.upsertEntity(session.createNewEntity("product", 1)
					.setAttribute("body", Locale.ENGLISH, text));
			});
			final FulltextIndex index = global(Scope.LIVE).getFulltextIndex(Locale.ENGLISH);
			index.retireField(FulltextFieldKey.attribute("title"));
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID,
				index.getFieldId(FulltextFieldKey.attribute("title")));
			final List<Group> profiles = groups();
			assertEquals(0, count(text + " "));
			final List<FulltextExpansionCensusSupport.Word> words = census(text + " ");
			assertEquals(queryTextPhase ? 2 : 5,
				words.stream().map(FulltextExpansionCensusSupport.Word::word).distinct().count());
			assertEquals(2, profiles.size());
			assertEquals(0, profiles.getFirst().ids().length);
			assertTrue(words.stream().map(FulltextExpansionCensusSupport.Word::word).distinct().anyMatch(w ->
				words.stream().filter(it -> it.word() == w).allMatch(it -> it.hits().isEmpty())),
				"The census must retain every mandatory raw token with no postings, just like the translator");
		}

		/** Real N=2m gives a binding allowance: the first group's fuzzy part precedes the second's prefix. */
		@Test
		@DisplayName("Matches census selection when the real translator cuts split words across profiles")
		void shouldVisitProfilesBeforeParts() throws Exception {
			FulltextPostingsBudgetIntegrationTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.upsertEntity(session.createNewEntity("product", 1)
					.setAttribute("title", Locale.ENGLISH, "lump"));
				session.upsertEntity(session.createNewEntity("product", 2)
					.setAttribute("body", Locale.ENGLISH, "lamp12345"));
			});
			final GlobalEntityIndex global = global(Scope.LIVE);
			final Map<Locale, TransactionalBitmap> byLocale =
				widenScope(global, Map.of(Locale.ENGLISH, range(1, 2_000_001)), 2_000_001);
			final FulltextIndex index = global.getFulltextIndex(Locale.ENGLISH);
			final int title = index.getFieldId(FulltextFieldKey.attribute("title"));
			final int body = index.getFieldId(FulltextFieldKey.attribute("body"));
			assertTrue(title < body);
			loadPostings(index, new int[]{title, body, body}, new String[]{"lump", "lamp", "lamp12345"},
				range(1, 1_200_001), range(800_001, 2_000_001), range(800_001, 2_000_001));
			final List<FulltextExpansionCensusSupport.Word> words = census("lamp123");
			final int mandatoryWords = (int) words.stream()
				.map(FulltextExpansionCensusSupport.Word::word).distinct().count();
			assertEquals(1, mandatoryWords);
			assertEquals(2, words.stream().map(FulltextExpansionCensusSupport.Word::group).distinct().count());
			final long allowance = FulltextPostingsBudget.limit(mandatoryWords, byLocale.get(Locale.ENGLISH).size());
			assertEquals(3_289_513, allowance);
			final List<FulltextExpansionCensusSupport.Word> kept =
				FulltextExpansionCensusSupport.net(words, allowance, true);
			final Set<String> selected = new TreeSet<>();
			kept.forEach(w -> w.hits().forEach(h -> selected.add(h.hash())));
			assertEquals(Set.of(FulltextExpansionCensusSupport.hash("lamp"),
				FulltextExpansionCensusSupport.hash("lump")), selected);
			final BaseBitmap expectedMatches = new BaseBitmap();
			for (final FulltextExpansionCensusSupport.Word word : kept) {
				for (final FulltextExpansionCensusSupport.Hit hit : word.hits()) {
					for (final int field : hit.fields().keySet()) {
						index.forEachTerm(field, "", (term, postings, impacts) -> {
							if (FulltextExpansionCensusSupport.hash(term).equals(hit.hash())) {
								expectedMatches.addAll(postings);
							}
							return true;
						});
					}
				}
			}
			assertEquals(2_000_000, expectedMatches.size());
			// Observe the complete real query result; no reconstruction of production batches or pruning order.
			final int[] actualMatches = FulltextPostingsBudgetIntegrationTest.this.evita.queryCatalog(
				TEST_CATALOG, session -> {
					return session.query(query(collection("product"),
					filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches("lamp123")),
					require(page(1, 2_000_000))), EntityReference.class)
					.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray();
				});
			assertArrayEquals(expectedMatches.getArray(), actualMatches);
		}
	}

	/** Uses the census runner's real schema grouping, including its generation checks. */
	private List<FulltextExpansionCensusSupport.Word> census(String text) throws Exception {
		return FulltextExpansionCensusMeasurementTest.expand(new FulltextExpansionCensusSupport.Query(
			FulltextExpansionCensusSupport.hash(text), "test", "clean", text, text, 1), groups(),
			global(Scope.LIVE).getFulltextIndex(Locale.ENGLISH), this.evita.getFulltextTypoParameters(), false);
	}

	/** Reads the complete schema profiles and the census runner's actual current-generation selection. */
	private List<Group> groups() throws ReflectiveOperationException {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
		final FulltextIndex index = global(Scope.LIVE).getFulltextIndex(Locale.ENGLISH);
		return FulltextExpansionCensusMeasurementTest.groups(catalog,
			catalog.getCollectionForEntityOrThrowException("product").getSchema(), index, Locale.ENGLISH);
	}

	/** Reads the actual scope global index from the catalog snapshot. */
	private GlobalEntityIndex global(Scope scope) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
		return (GlobalEntityIndex) catalog.getCollectionForEntityOrThrowException("product")
			.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, scope));
	}

	/** Executes only an ordinary query, with reference materialization limited to one record. */
	private int count(String text) {
		return this.evita.queryCatalog(TEST_CATALOG, session -> {
			return session.query(query(collection("product"),
					filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches(text)), require(page(1, 1))),
				EntityReference.class).getTotalRecordCount();
		});
	}

	/**
	 * Replaces the scope global index's locale and entity bitmaps in its in-memory read snapshot, so the translator
	 * reads a large N without creating entities; the persistent catalog stays unmodified.
	 */
	@SuppressWarnings("unchecked")
	private static Map<Locale, TransactionalBitmap> widenScope(
		GlobalEntityIndex global, Map<Locale, TransactionalBitmap> perLocale, int entityEnd
	) throws ReflectiveOperationException {
		final Field languages = EntityIndex.class.getDeclaredField("entityIdsByLanguage");
		languages.setAccessible(true);
		final Map<Locale, TransactionalBitmap> byLocale = (Map<Locale, TransactionalBitmap>) languages.get(global);
		byLocale.putAll(perLocale);
		final Field entities = EntityIndex.class.getDeclaredField("entityIds");
		entities.setAccessible(true);
		entities.set(global, range(1, entityEnd));
		return byLocale;
	}

	/**
	 * Loads range-filled postings with ordinary aligned impact columns into the in-memory dictionary snapshot only.
	 * The terms must be given in ascending dictionary-key order, field first.
	 */
	@SuppressWarnings("unchecked")
	private static void loadPostings(
		FulltextIndex index, int[] fields, String[] terms, TransactionalBitmap... postings
	) throws ReflectiveOperationException {
		final Field dictionary = FulltextIndex.class.getDeclaredField("dictionary");
		dictionary.setAccessible(true);
		final TransactionalBucketBPlusTree<String> tree = (TransactionalBucketBPlusTree<String>) dictionary.get(index);
		final Object[] keys = new Object[terms.length];
		final byte[][] impacts = new byte[terms.length][];
		for (int i = 0; i < terms.length; i++) {
			keys[i] = FulltextTermKeys.encode(fields[i], terms[i]);
			impacts[i] = new byte[postings[i].size()];
		}
		tree.bulkLoadPage(keys, new long[terms.length], Arrays.copyOf(postings, postings.length, Object[].class), null,
			impacts, terms.length);
	}

	/** Builds a compact real posting bitmap without creating entities. */
	private static TransactionalBitmap range(int start, int end) {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		bitmap.add((long) start, (long) end);
		return new TransactionalBitmap(new BaseBitmap(bitmap));
	}
}
