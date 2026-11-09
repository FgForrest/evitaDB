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

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
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
import io.evitadb.dataType.Scope;
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
import static io.evitadb.index.fulltext.FulltextIndexTestSupport.retainedAnalyzerCount;
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

	/** How a query reaches the catalog's analyzer registry. */
	@Nested
	@DisplayName("Analyzer lookups")
	class AnalyzerLookups {
		/** Undeclared locales cannot own postings and must not compile or retain chains. */
		@Test
		@DisplayName("should answer an undeclared query locale without compiling an analyzer")
		void shouldIgnoreUndeclaredLocalesWithoutCompiling() {
			defineFields("text", "text");
			put(1, Locale.ENGLISH, "red", "blue");
			final Catalog catalog = (Catalog) FulltextFieldAnalysisFunctionalTest.this.evita
				.getCatalogInstanceOrThrowException(TEST_CATALOG);
			final FulltextAnalyzerRegistry registry = catalog.getFulltextAnalyzerRegistry();
			final int before = retainedAnalyzerCount(registry);
			for (int i = 0; i < 100; i++) {
				assertMatches(
					FulltextFieldAnalysisFunctionalTest.this.evita, Locale.forLanguageTag("en-x-" + i), "red", null
				);
			}
			assertEquals(before, retainedAnalyzerCount(registry),
				"Undeclared query locales must not populate the analyzer registry");
			assertThrows(AttributeNotFoundException.class,
				() -> assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.FRENCH, "red", "missing"),
				"Field validation must precede the undeclared-locale shortcut");
		}

		/**
		 * Eviction after resolution must not force a second identity lookup during grouping. The catalog's registry is
		 * replaced by reflection because the counting registry is the only observer of how often a translation looks
		 * an analyzer up, and a catalog offers no way to install another registry.
		 */
		@Test
		@DisplayName("should keep a resolved analyzer usable across its eviction and look it up once")
		void shouldHoldResolvedAnalyzerAcrossEviction() throws Exception {
			defineFields("text", "text");
			put(1, Locale.ENGLISH, "red", "blue");
			final Catalog catalog = (Catalog) FulltextFieldAnalysisFunctionalTest.this.evita
				.getCatalogInstanceOrThrowException(TEST_CATALOG);
			final FulltextAnalyzerRegistry original = catalog.getFulltextAnalyzerRegistry();
			final EvictingRegistry registry = new EvictingRegistry();
			final java.lang.reflect.Field field = Catalog.class.getDeclaredField("fulltextAnalyzerRegistry");
			field.setAccessible(true);
			field.set(catalog, registry);
			try {
				// the evicted analyzer handle must remain usable throughout the translation
				assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "red", "title", 1);
				assertEquals(1, registry.lookups, "Resolve each searched field only once per translation");
			} finally {
				field.set(catalog, original);
				registry.close();
			}
		}
	}

	/** Which split the query words come from, and how each group prepares them. */
	@Nested
	@DisplayName("Query text phases")
	class QueryTextPhases {
		/** Equal resolved text phases from different definitions remove boundaries before the shared split. */
		@Test
		@DisplayName("should remove boundaries before the shared split when every group has the same text phase")
		void shouldRemoveBoundariesBeforeSharedSplit() {
			FulltextFieldAnalysisFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
				.withFulltextAnalyzer("joined", a -> a.text(removeCharacters("-")).words(lowercase()))
				.withFulltextAnalyzer("joinedOther",
					a -> a.text(removeCharacters("-")).words(lowercase(), asciiFolding()))
				.updateVia(session);
			});
			defineFields("joined", "joinedOther");
			put(1, Locale.ENGLISH, "USB-C", "USB-C");
			for (final String text : new String[]{"USB-C", "usb-c", "USBC"}) {
				assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, text, null, 1);
			}
		}

		/** Different text phases keep the raw boundaries, regardless of which field is visited first. */
		@Test
		@DisplayName("should keep the raw boundaries when the groups' text phases differ")
		void shouldKeepRawBoundariesForHeterogeneousTextPhases() {
			FulltextFieldAnalysisFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
				.withFulltextAnalyzer("joined", a -> a.text(removeCharacters("-")).words(lowercase()))
				.withFulltextAnalyzer("plain", a -> a.words(lowercase())).updateVia(session);
			});
			defineFields("joined", "plain");
			// Keep each split part more than two edits from the joined form, so typos cannot hide a boundary.
			put(1, Locale.ENGLISH, "USB-TYPEC", "red");
			put(2, Locale.ENGLISH, "red", "USB-TYPEC");
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "USB-TYPEC", null, 2);
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "USBTYPEC", null, 1);
			// Reverse analyzer encounter order with two inField constraints.
			FulltextFieldAnalysisFunctionalTest.this.evita.queryCatalog(TEST_CATALOG, session -> {
				for (final boolean reversed : new boolean[]{false, true}) {
					assertEquals(1, session.query(query(collection(PRODUCT),
						filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches("USB-TYPEC",
							inField(reversed ? "body" : "title"), inField(reversed ? "title" : "body")))),
						EntityReference.class).getTotalRecordCount());
				}
				return null;
			});
		}

		/** A heterogeneous phase in another processed scope also forces the shared raw split. */
		@Test
		@DisplayName("should take the raw split when a group of another processed scope has another text phase")
		void shouldChooseSharedSplitAcrossAllProcessedScopes() {
			FulltextFieldAnalysisFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("joined", a -> a.text(removeCharacters("-")).words(lowercase()))
					.withFulltextAnalyzer("plain", a -> a.words(lowercase())).updateVia(session);
				session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("title", String.class,
						a -> a.localized().nullable().searchableInScope(Scope.LIVE).analyzer("joined"))
					.withAttribute("body", String.class,
						a -> a.localized().nullable().searchableInScope(Scope.ARCHIVED).analyzer("plain"))
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(PRODUCT, 1)
					.setAttribute("title", Locale.ENGLISH, "USB-C"));
				session.upsertEntity(session.createNewEntity(PRODUCT, 2).setScope(Scope.ARCHIVED)
					.setAttribute("body", Locale.ENGLISH, "USB-C"));
			});
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "USB-C", null, 1);
			FulltextFieldAnalysisFunctionalTest.this.evita.queryCatalog(TEST_CATALOG, session -> {
				final int[] actual = session.query(query(collection(PRODUCT),
					filterBy(entityLocaleEquals(Locale.ENGLISH), scope(Scope.LIVE, Scope.ARCHIVED),
						textMatches("USB-C"))),
					EntityReference.class).getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).toArray();
				assertArrayEquals(new int[]{2}, actual,
					"All processed scopes must agree before applying a common text phase");
				return null;
			});
		}

		/** An erased word is dropped even when another text phase forces the raw shared split. */
		@Test
		@DisplayName("should drop a word a group's text phase erases under the raw split")
		void shouldDropEmptyWordAlternatives() {
			FulltextFieldAnalysisFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
				.withFulltextResource("stop", wordList("x"))
				.withFulltextAnalyzer("erase", a -> a.text(removeCharacters("x")).words(lowercase()))
				.withFulltextAnalyzer("stopX", a -> a.words(lowercase(), stopWords(resource("stop"))))
				.updateVia(session);
			});
			defineFields("erase", "stopX");
			put(1, Locale.ENGLISH, "x red", "red");
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "x red", null, 1);
		}

		/** Folded stop recognition prepares the surface through English possessive removal. */
		@Test
		@DisplayName("should prepare a possessive surface before recognizing a folded stop word")
		void shouldPreparePossessiveSurfaceForFoldedStops() {
			FulltextFieldAnalysisFunctionalTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
				.withFulltextResource("stop", wordList("café"))
				.withFulltextAnalyzer("possessive", a -> a.words(englishPossessive(), lowercase(),
					stopWords(resource("stop")))).updateVia(session);
			});
			defineFields("possessive", "possessive");
			put(1, Locale.ENGLISH, "café's red", "red");
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "cafe's red", null, 1);
		}
	}

	/** A real registry that deterministically evicts the just-returned handle before translation continues. */
	private static final class EvictingRegistry extends FulltextAnalyzerRegistry {
		/** Number of production resolutions, excluding the eviction workload. */
		private int lookups;

		/** Uses the installed production analysis providers. */
		private EvictingRegistry() {
			super(new AnalysisStepRegistry());
		}

		/** Resolves with the real cache, then evicts that memo before returning the held handle. */
		@Nonnull
		@Override
		public CompiledFulltextAnalyzer getCompiledAnalyzer(
			@Nonnull CatalogSchemaContract schema, @Nonnull String analyzer, @Nonnull Locale locale
		) {
			this.lookups++;
			final CompiledFulltextAnalyzer result = super.getCompiledAnalyzer(schema, analyzer, locale);
			for (int i = 0; i < 8; i++) {
				final InternalCatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(schema);
				builder.withFulltextResource("eviction", wordList("word" + this.lookups + "x" + i));
				super.getCompiledAnalyzer(builder.toInstance(), analyzer, locale);
			}
			return result;
		}
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
					"UHD7800 ".repeat(256), null));
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "the ".repeat(255), null);
			assertMatches(FulltextFieldAnalysisFunctionalTest.this.evita, Locale.ENGLISH, "UHD7800 ".repeat(255), null);
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
		assertArrayEquals(expected, actual, "Query " + text + " in " + locale + " field " + field);
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

	/** Ranking configuration cannot replace field identities or discard postings from earlier entity writes. */
	@Test
	@DisplayName("should keep attribute and associated data generations after weight-only commits")
	void shouldKeepFieldGenerationsWhenOnlySearchWeightsChange() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("title", String.class, a -> a.localized().searchable())
				.withAssociatedData("article", String.class, a -> a.localized().searchable())
				.updateVia(session);
			for (int id = 1; id <= 2; id++) {
				session.upsertEntity(session.createNewEntity(PRODUCT, id)
					.setAttribute("title", Locale.ENGLISH, "green")
					.setAssociatedData("article", Locale.ENGLISH, "amber"));
			}
			session.goLiveAndClose();
		});
		final FulltextFieldKey title = FulltextFieldKey.attribute("title");
		final FulltextFieldKey article = FulltextFieldKey.associatedData("article");
		final FulltextIndex before = index(this.evita, Locale.ENGLISH);
		final int titleId = before.getFieldId(title);
		final int articleId = before.getFieldId(article);
		final int fieldCount = before.getFieldCount();
		final long titleFingerprint = before.getIndexFingerprint(titleId);
		final long articleFingerprint = before.getIndexFingerprint(articleId);
		for (final float weight : new float[]{2.5f, 0.0f}) {
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getEntitySchemaOrThrow(PRODUCT).openForWrite()
					.withAttribute("title", String.class, a -> a.searchWeight(weight))
					.withAssociatedData("article", String.class, a -> a.searchWeight(weight))
					.updateVia(session);
			});
			this.evita.queryCatalog(TEST_CATALOG, session -> {
				final EntitySchemaContract committed = session.getEntitySchemaOrThrow(PRODUCT);
				assertEquals(weight, committed.getAttribute("title").orElseThrow().getSearchWeight());
				assertEquals(weight, committed.getAssociatedData("article").orElseThrow().getSearchWeight());
				return null;
			});
			assertMatches(this.evita, Locale.ENGLISH, "green", "title", 1, 2);
			assertMatches(this.evita, Locale.ENGLISH, "amber", null, 1, 2);
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.upsertEntity(session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow()
					.openForWrite().setAttribute("title", Locale.ENGLISH, "green tea")
					.setAssociatedData("article", Locale.ENGLISH, "amber light"));
			});
			final FulltextIndex after = index(this.evita, Locale.ENGLISH);
			assertEquals(titleId, after.getFieldId(title));
			assertEquals(articleId, after.getFieldId(article));
			assertEquals(fieldCount, after.getFieldCount());
			assertEquals(titleFingerprint, after.getIndexFingerprint(titleId));
			assertEquals(articleFingerprint, after.getIndexFingerprint(articleId));
			assertFalse(after.isFieldRetired(titleId));
			assertFalse(after.isFieldRetired(articleId));
			this.evita.queryCatalog(TEST_CATALOG, session -> {
				final EntitySchemaContract committed = session.getEntitySchemaOrThrow(PRODUCT);
				assertEquals(weight, committed.getAttribute("title").orElseThrow().getSearchWeight());
				assertEquals(weight, committed.getAssociatedData("article").orElseThrow().getSearchWeight());
				return null;
			});
			assertMatches(this.evita, Locale.ENGLISH, "green", "title", 1, 2);
			assertMatches(this.evita, Locale.ENGLISH, "amber", null, 1, 2);
		}
	}

}
