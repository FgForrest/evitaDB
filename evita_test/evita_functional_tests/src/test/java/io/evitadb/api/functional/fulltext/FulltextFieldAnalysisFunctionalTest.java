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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/** Per-field analysis, shared query words and persistent generation identities through real catalog writes. */
@DisplayName("Fulltext analysis per field group")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FULLTEXT)
class FulltextFieldAnalysisFunctionalTest implements EvitaTestSupport {
	/** The collection under test. */
	private static final String PRODUCT = "product";
	/** A locale whose stop words distinguish accented and ASCII input. */
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	/** Fresh storage for each scenario. */
	private TestPaths paths;
	/** The real engine under test. */
	private Evita evita;

	/** Opens an empty catalog with periodic checkpoints disabled so crash copies exercise a WAL gap. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FulltextFieldAnalysisFunctionalTest");
		this.evita = new Evita(configuration(this.paths));
		this.evita.defineCatalog(TEST_CATALOG);
	}

	/** Releases the engine and its test folders. */
	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/** Field-specific query analysis and counting. */
	@Nested
	@DisplayName("Field groups")
	class FieldGroups {
		/** Only html-text strips markup, and shared words can be found across analyzer groups. */
		@Test
		@DisplayName("should strip markup per field and find words across groups")
		void shouldAnalyzeEachFieldWithItsSchema() {
			defineFields("text", "html-text");
			put(1, Locale.ENGLISH, "<marker>red</marker>", "<marker>blue</marker>");
			put(2, Locale.ENGLISH, "green", "<marker>blue</marker>");
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "marker", "title", 1);
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "marker", "body");
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "green blue", null, 2);
		}

		/** All registration paths read both analysis settings from their own schema. */
		@Test
		@DisplayName("should record schema analysis and pivots for attributes references and associated data")
		void shouldRegisterEveryFieldKindWithSchemaSettings() {
			defineFields("text", "html-text");
			FulltextFieldAnalysisFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.defineEntitySchema("category").withoutGeneratedPrimaryKey().updateVia(session);
				session.upsertEntity(session.createNewEntity("category", 1));
				session.defineEntitySchema(PRODUCT)
					.withAttribute("title", String.class, a -> a.lengthPivot(30))
					.withAssociatedData("article", String.class,
						a -> a.localized().nullable().searchable().analyzer("html-text").lengthPivot(125))
					.withReferenceToEntity("categories", "category", Cardinality.ZERO_OR_MORE,
						r -> r.withAttribute("label", String.class,
							a -> a.localized().nullable().searchable().analyzer("html-text").lengthPivot(75)))
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(PRODUCT, 1)
					.setAttribute("title", Locale.ENGLISH, "green")
					.setAssociatedData("article", Locale.ENGLISH, "<marker>blue</marker>")
					.setReference("categories", 1,
						r -> r.setAttribute("label", Locale.ENGLISH, "<marker>red</marker>")));
			});
			final FulltextIndex index = index(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH);
			final int title = index.getFieldId(FulltextFieldKey.attribute("title"));
			final int data = index.getFieldId(FulltextFieldKey.associatedData("article"));
			final int reference = index.getFieldId(FulltextFieldKey.referenceAttribute("categories", "label"));
			assertEquals(30.0, index.getLengthPivot(title));
			assertEquals(125.0, index.getLengthPivot(data));
			assertEquals(75.0, index.getLengthPivot(reference));
			assertEquals("html-text", index.getAnalyzerName(data));
			assertEquals("html-text", index.getAnalyzerName(reference));
			assertEquals(index.getIndexFingerprint(data), index.getIndexFingerprint(reference));
			assertNotEquals(index.getIndexFingerprint(title), index.getIndexFingerprint(reference));
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "green blue red", null, 1);
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "marker", null);
		}

		/** A stop word retained by a different group is still required, including ASCII input of accented words. */
		@Test
		@DisplayName("should retain a stop word served by a group without stop words")
		void shouldKeepWordsRetainedByAnotherGroup() {
			FulltextFieldAnalysisFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("plain", a -> a.words(lowercase(), asciiFolding())).updateVia(session);
			});
			defineFields("text", "plain");
			put(1, CZECH, "čaj", "není skladem");
			put(2, CZECH, "čaj", "skladem");
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, CZECH, "čaj není", null, 1);
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, CZECH, "čaj neni", null, 1);
		}

		/** Two distinct groups with stop words both drop accented and unaccented forms when absent from the corpus. */
		@Test
		@DisplayName("should drop a word only when all groups drop it")
		void shouldDropWordsAcrossEveryGroup() {
			defineFields("text", "html-text");
			put(1, CZECH, "čaj", "<b>skladem</b>");
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, CZECH, "není čaj", null, 1);
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, CZECH, "neni čaj", null, 1);
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, CZECH, "neni", null);
		}

		/** A stale group without stop words must not keep words dropped by the remaining current group. */
		@Test
		@DisplayName("should ignore a stale group's keep vote for accented and folded stop words")
		void shouldIgnoreStopWordVotesOfStaleGroups() {
			FulltextFieldAnalysisFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("plain", a -> a.words(lowercase(), asciiFolding())).updateVia(session);
			});
			defineFields("text", "plain");
			put(1, CZECH, "čaj", "skladem");
			FulltextFieldAnalysisFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("plain", a -> a.words(lowercase())).updateVia(session);
			});
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, CZECH, "skladem", "body");
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, CZECH, "není čaj", null, 1);
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, CZECH, "neni čaj", null, 1);
		}

		/** With no current field in the scope, even otherwise searchable terms yield an empty formula. */
		@Test
		@DisplayName("should return nothing when every field in the scope is stale")
		void shouldReturnNothingWhenEveryFieldIsStale() {
			FulltextFieldAnalysisFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("plain", a -> a.words(lowercase())).updateVia(session);
			});
			defineFields("plain", "plain");
			put(1, CZECH, "čaj", "skladem");
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, CZECH, "čaj", null, 1);
			FulltextFieldAnalysisFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("plain", a -> a.words(lowercase(), asciiFolding())).updateVia(session);
			});
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, CZECH, "čaj", null);
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, CZECH, "skladem", null);
		}

		/** The limit applies before stop removal, even with no data, and the language's splitting defines words. */
		@Test
		@DisplayName("should count shared split words before running any group")
		void shouldCountSharedSplitWords() {
			FulltextFieldAnalysisFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextLanguage("en", language -> language.wordNumberSplit()).updateVia(session);
			});
			defineFields("text", "html-text");
			assertThrows(EvitaInvalidUsageException.class,
				() -> assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH,
					"the ".repeat(256), null));
			assertThrows(EvitaInvalidUsageException.class,
				() -> assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH,
					"UHD7800 ".repeat(128), null));
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "the ".repeat(255), null);
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "UHD7800 ".repeat(127), null);
		}
	}

	/** Schema edits affect queries immediately, and writes establish fresh persistent generations. */
	@Nested
	@DisplayName("Schema edits and persistence")
	class SchemaEdits {
		/** Query-only edits preserve postings; index edits skip stale fields and replace them only on writes. */
		@Test
		@DisplayName("should apply edits immediately and retain behavior through WAL replay and reload")
		void shouldApplyEditsAndPersistGenerations() throws IOException {
			editAnalyzer("bicycle", false);
			defineFields("names", "html-text");
			put(1, Locale.ENGLISH, "bike", "blue");
			put(2, Locale.ENGLISH, "bike", "red");
			FulltextFieldAnalysisFunctionalTest.this.evita.updateCatalog(TEST_CATALOG,
				EvitaSessionContract::goLiveAndClose);
			final int originalId = index(FulltextFieldAnalysisFunctionalTest.this.evita,
				Locale.ENGLISH).getFieldId(FulltextFieldKey.attribute("title"));
			final long originalFingerprint = index(FulltextFieldAnalysisFunctionalTest.this.evita,
				Locale.ENGLISH).getIndexFingerprint(originalId);
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "bicycle", "title", 1, 2);
			editAnalyzer("cycle", false);
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "cycle", "title", 1, 2);
			assertEquals(originalId, index(FulltextFieldAnalysisFunctionalTest.this.evita,
				Locale.ENGLISH).getFieldId(FulltextFieldKey.attribute("title")));
			assertEquals(originalFingerprint, index(FulltextFieldAnalysisFunctionalTest.this.evita,
				Locale.ENGLISH).getIndexFingerprint(originalId));
			editAnalyzer("cycle", true);
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "cycle", "title");
			put(1, Locale.ENGLISH, "bike blue", "blue");
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "cycle", "title", 1);
			final FulltextIndex updated = index(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH);
			final int freshId = updated.getFieldId(FulltextFieldKey.attribute("title"));
			assertNotEquals(originalId, freshId);
			assertTrue(updated.isFieldRetired(originalId));
			assertNotEquals(originalFingerprint, updated.getIndexFingerprint(freshId));
			assertReplay(freshId, updated.getIndexFingerprint(freshId));
			FulltextFieldAnalysisFunctionalTest.this.evita.close();
			FulltextFieldAnalysisFunctionalTest.this.evita =
				new Evita(configuration(FulltextFieldAnalysisFunctionalTest.this.paths));
			FulltextFieldAnalysisFunctionalTest.this.evita.waitUntilFullyInitialized();
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "cycle", "title", 1);
			assertEquals(freshId, index(FulltextFieldAnalysisFunctionalTest.this.evita,
				Locale.ENGLISH).getFieldId(FulltextFieldKey.attribute("title")));
		}

		/** A pivot change skips old impacts at once and the next write starts one fresh field. */
		@Test
		@DisplayName("should skip a changed pivot immediately and retire on the next write")
		void shouldRetirePivotChange() {
			defineFields("text", "html-text");
			put(1, Locale.ENGLISH, "green", "blue");
			put(2, Locale.ENGLISH, "green", "red");
			FulltextFieldAnalysisFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.defineEntitySchema(PRODUCT)
					.withAttribute("title", String.class, a -> a.lengthPivot(100)).updateVia(session);
			});
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "green", "title");
			put(1, Locale.ENGLISH, "green tea", "blue");
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "green", "title", 1);
		}
	}

	/** Defines two localized nullable fields with separately chosen analyzers. */
	private void defineFields(@Nonnull String titleAnalyzer, @Nonnull String bodyAnalyzer) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(PRODUCT)
				.withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH, CZECH)
				.withAttribute("title", String.class,
					a -> a.localized().nullable().searchable().analyzer(titleAnalyzer))
				.withAttribute("body", String.class, a -> a.localized().nullable().searchable().analyzer(bodyAnalyzer))
				.updateVia(session);
		});
	}

	/** Writes both localized texts through the real mutation executors. */
	private void put(int id, @Nonnull Locale locale, @Nonnull String title, @Nonnull String body) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			final EntityBuilder entity = session.getEntity(PRODUCT, id, entityFetchAllContent())
				.<EntityBuilder>map(stored -> stored.openForWrite())
				.orElseGet(() -> session.createNewEntity(PRODUCT, id));
			session.upsertEntity(entity.setAttribute("title", locale, title).setAttribute("body", locale, body));
		});
	}

	/** Replaces query synonym content and optionally adds an index stop filter. */
	private void editAnalyzer(@Nonnull String alternative, boolean stop) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getCatalogSchema().openForWrite()
				.withFulltextResource("syn", synonymList(synonymGroup("bike", alternative)))
				.withFulltextResource("stop", wordList("unused"))
				.withFulltextAnalyzer("names", a -> {
					if (stop) {
						a.words(lowercase(), stopWords(resource("stop")), synonyms(resource("syn")));
					} else {
						a.words(lowercase(), synonyms(resource("syn")));
					}
				}).updateVia(session);
		});
	}

	/** Asserts the exact primary keys for an optional field selection. */
	private static void assertMatches(
		@Nonnull Evita engine, @Nonnull Locale locale, @Nonnull String text,
		@Nullable String field, @Nonnull int... expected
	) {
		final int[] actual = engine.queryCatalog(TEST_CATALOG, session -> {
			return session.query(
				query(collection(PRODUCT), filterBy(entityLocaleEquals(locale),
					field == null ? textMatches(text) : textMatches(text, inField(field))), require(page(1, 100))),
				EntityReference.class).getRecordData().stream()
				.mapToInt(EntityReference::getPrimaryKey).sorted().toArray();
		});
		assertArrayEquals(expected, actual);
	}

	/** Reads the live locale index to verify its persisted identities. */
	@Nonnull
	private static FulltextIndex index(@Nonnull Evita engine, @Nonnull Locale locale) {
		final Catalog catalog = (Catalog) engine.getCatalogInstanceOrThrowException(TEST_CATALOG);
		return ((GlobalEntityIndex) catalog.getCollectionForEntityOrThrowException(PRODUCT)
			.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL))).getFulltextIndex(locale);
	}

	/** Copies a catalog with a proven WAL gap and verifies replay through the production startup path. */
	private void assertReplay(int fieldId, long fingerprint) throws IOException {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
		assertTrue(catalog.getVersion() > catalog.getPersistenceService().getLastCatalogVersion());
		final TestPaths replayPaths = createTestPaths("FulltextFieldAnalysisReplay");
		try {
			try (final Stream<Path> files = Files.walk(this.paths.storage())) {
				for (final Path path : files.toList()) {
					final Path destination = replayPaths.storage().resolve(this.paths.storage().relativize(path));
					if (Files.isDirectory(path)) {
						Files.createDirectories(destination);
					} else {
						Files.createDirectories(destination.getParent());
						Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
			try (final Evita recovered = new Evita(configuration(replayPaths))) {
				recovered.waitUntilFullyInitialized();
				assertMatches(recovered, Locale.ENGLISH, "cycle", "title", 1);
				assertEquals(fingerprint, index(recovered, Locale.ENGLISH).getIndexFingerprint(fieldId));
			}
		} finally {
			cleanupTestPaths(replayPaths);
		}
	}

	/** Disables periodic checkpoints for deterministic replay coverage. */
	@Nonnull
	private EvitaConfiguration configuration(@Nonnull TestPaths testPaths) {
		final EvitaConfiguration base = newTestEvitaConfigurationBuilder(testPaths).build();
		return EvitaConfiguration.builder(base).transaction(TransactionOptions.builder(base.transaction())
			.checkpointIntervalInMillis(3_600_000L).build()).build();
	}
}
