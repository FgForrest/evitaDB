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


package io.evitadb.api.functional.schema;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins end to end on an embedded evitaDB where a fulltext analyzer that does not compile is refused: when the session
 * changing the schema closes - with the refusal naming the analyzer and the language - and not at the single schema
 * change, so that a schema built in several steps may pass through such a state. The language decides: an analyzer
 * is checked in the languages of the entities whose searchable fields use it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Fulltext analyzers that do not compile")
@Tag(ENGINE)
@Tag(SCHEMA)
@Tag(FULLTEXT)
class FulltextAnalyzerValidationSchemaTest implements EvitaTestSupport {

	private static final String ANALYZER_NAMES = "names";
	private static final String ATTRIBUTE_NAME = "name";
	private static final Locale CZECH = Locale.forLanguageTag("cs");

	private TestPaths paths;
	private Evita evita;

	/** Starts an embedded evitaDB over fresh test folders with an empty catalog in warm-up. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FulltextAnalyzerValidationSchema");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	/** Closes the embedded evitaDB, when a test left it running, and removes its folders. */
	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * A fold before the Czech stemmer does not compile in any language, so the closing session rolls back and the
	 * refusal names the analyzer and its all-languages definition.
	 */
	@Test
	@DisplayName("should refuse an analyzer whose words phase does not compile when the session closes")
	void shouldRefuseAnalyzerThatDoesNotCompile() {
		goLive();

		// a fold before the Czech stemmer is refused in every language, the all-languages definition included
		final RollbackException exception = assertThrows(
			RollbackException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withFulltextAnalyzer(ANALYZER_NAMES, a -> a.words(asciiFolding(), czechStemming()))
						.updateVia(session);
				}
			)
		);

		final InvalidSchemaMutationException refusal =
			assertInstanceOf(InvalidSchemaMutationException.class, exception.getCause());
		assertTrue(refusal.getMessage().contains("`" + ANALYZER_NAMES + "`"), refusal.getMessage());
		assertTrue(refusal.getMessage().contains("all-languages definition"), refusal.getMessage());
		assertTrue(catalogSchema().getFulltextAnalyzer(ANALYZER_NAMES).isEmpty());
	}

	/** An analyzer that does not compile in Czech alone is refused once a Czech field uses it, naming the language. */
	@Test
	@DisplayName("should refuse an analyzer that does not compile in the language of a field using it")
	void shouldRefuseAnalyzerInLanguageOfField() {
		goLive();

		// `stemming()` folds by itself in Czech, so a fold before it is refused there - and only there
		final RollbackException exception = assertThrows(
			RollbackException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withFulltextAnalyzer(ANALYZER_NAMES, a -> a.words(asciiFolding(), stemming()))
						.updateVia(session);
					defineProduct(session, CZECH);
				}
			)
		);

		final InvalidSchemaMutationException refusal =
			assertInstanceOf(InvalidSchemaMutationException.class, exception.getCause());
		assertTrue(refusal.getMessage().contains("`" + ANALYZER_NAMES + "`"), refusal.getMessage());
		assertTrue(refusal.getMessage().contains("language `cs`"), refusal.getMessage());
		assertTrue(catalogSchema().getFulltextAnalyzer(ANALYZER_NAMES).isEmpty());
	}

	/** The same analyzer is accepted while the only field using it is in a language where it compiles. */
	@Test
	@DisplayName("should accept the same analyzer when no field uses it in that language")
	void shouldAcceptAnalyzerOutsideLanguageOfField() {
		goLive();

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withFulltextAnalyzer(ANALYZER_NAMES, a -> a.words(asciiFolding(), stemming()))
					.updateVia(session);
				defineProduct(session, Locale.ENGLISH);
			}
		);

		assertTrue(catalogSchema().getFulltextAnalyzer(ANALYZER_NAMES).isPresent());
	}

	/**
	 * A warm-up session may define an analyzer that does not compile, define a collection using it and fix it before
	 * it closes: only the final state is checked, and it survives a reopen.
	 */
	@Test
	@DisplayName("should let a warm-up session pass through an analyzer that does not compile and fix it")
	void shouldToleratePassingStateWithinSession() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withFulltextAnalyzer(ANALYZER_NAMES, a -> a.words(asciiFolding(), czechStemming()))
					.updateVia(session);
				// a collection-level change in warm-up asks whether the schema is valid before it flushes
				defineProduct(session, CZECH);
				session.getCatalogSchema()
					.openForWrite()
					.withFulltextAnalyzer(ANALYZER_NAMES, a -> a.words(lowercase(), czechStemming()))
					.updateVia(session);
			}
		);

		reopenOverTheSameStorage();

		final FulltextAnalyzerSchemaContract analyzer =
			catalogSchema().getFulltextAnalyzer(ANALYZER_NAMES).orElseThrow();
		assertEquals(
			new FulltextAnalysisPhases(null, List.of(lowercase(), czechStemming())), analyzer.getAllLanguages()
		);
	}

	/** Removal is validated through actual catalog-schema mutations and transaction completion. */
	@Nested
	@DisplayName("Analysis resource removal")
	class ResourceRemoval {
		/** The analyzer a searchable field uses cannot disappear; an analyzer nothing uses can. */
		@Test
		@DisplayName("Refuses removing the analyzer a field uses and removes an unused one")
		void shouldRefuseRemovingAnalyzerInUseAndAcceptUnusedOne() {
			defineResourcesAndGoLive();

			final InvalidSchemaMutationException refusal = assertRefused(
				builder -> builder.withoutFulltextAnalyzer(ANALYZER_NAMES)
			);
			removeFromCatalogSchema(builder -> builder.withoutFulltextAnalyzer("unusedAnalyzer"));

			assertTrue(refusal.getMessage().contains("`" + ANALYZER_NAMES + "`"), refusal.getMessage());
			assertTrue(catalogSchema().getFulltextAnalyzer("unusedAnalyzer").isEmpty());
		}

		/** The word list an analyzer reads cannot disappear; a word list nothing reads can. */
		@Test
		@DisplayName("Refuses removing the word list an analyzer reads and removes an unused one")
		void shouldRefuseRemovingResourceInUseAndAcceptUnusedOne() {
			defineResourcesAndGoLive();

			final InvalidSchemaMutationException refusal = assertRefused(
				builder -> builder.withoutFulltextResource("usedStops")
			);
			removeFromCatalogSchema(builder -> builder.withoutFulltextResource("unusedStops"));

			assertTrue(refusal.getMessage().contains("`usedStops`"), refusal.getMessage());
			assertTrue(catalogSchema().getFulltextResource("unusedStops").isEmpty());
		}

		/**
		 * Declares a used and an unused word list and analyzer, defines the product collection whose field uses the
		 * `names` analyzer reading the used word list, and switches the catalog to the transactional state.
		 */
		private void defineResourcesAndGoLive() {
			FulltextAnalyzerValidationSchemaTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextResource("usedStops", wordList("the"))
					.withFulltextResource("unusedStops", wordList("unused"))
					.withFulltextAnalyzer(ANALYZER_NAMES, a -> a.words(stopWords(resource("usedStops"))))
					.withFulltextAnalyzer("unusedAnalyzer", a -> a.words())
					.updateVia(session);
				defineProduct(session, Locale.ENGLISH);
			});
			goLive();
		}

		/**
		 * Asserts that a removal rolls back when its session closes and that both used definitions survive it.
		 *
		 * @param removal the removal applied to the catalog schema builder
		 * @return the refusal that caused the rollback
		 */
		@Nonnull
		private InvalidSchemaMutationException assertRefused(@Nonnull Consumer<CatalogSchemaBuilder> removal) {
			final RollbackException exception = assertThrows(
				RollbackException.class, () -> removeFromCatalogSchema(removal)
			);
			final InvalidSchemaMutationException refusal =
				assertInstanceOf(InvalidSchemaMutationException.class, exception.getCause());
			assertTrue(catalogSchema().getFulltextAnalyzer(ANALYZER_NAMES).isPresent());
			assertTrue(catalogSchema().getFulltextResource("usedStops").isPresent());
			return refusal;
		}

		/**
		 * Applies a removal to the catalog schema in its own session.
		 *
		 * @param removal the removal applied to the catalog schema builder
		 */
		private void removeFromCatalogSchema(@Nonnull Consumer<CatalogSchemaBuilder> removal) {
			FulltextAnalyzerValidationSchemaTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				final CatalogSchemaBuilder builder = session.getCatalogSchema().openForWrite();
				removal.accept(builder);
				builder.updateVia(session);
			});
		}
	}

	/**
	 * Defines the product collection with one locale and a searchable attribute using the `names` analyzer.
	 *
	 * @param session the session to define it in
	 * @param locale  the locale of the collection
	 */
	private static void defineProduct(@Nonnull EvitaSessionContract session, @Nonnull Locale locale) {
		session.defineEntitySchema(Entities.PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withLocale(locale)
			.withAttribute(
				ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().searchable().analyzer(ANALYZER_NAMES)
			)
			.updateVia(session);
	}

	/**
	 * Switches the catalog to the transactional (alive) state, where a refused update rolls back - in warm-up the
	 * same refusal raises the unpublishable barrier and deactivates the catalog.
	 */
	private void goLive() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.goLiveAndClose();
		});
	}

	/**
	 * Reads the current catalog schema in a fresh read-only session.
	 *
	 * @return the catalog schema
	 */
	@Nonnull
	private CatalogSchemaContract catalogSchema() {
		return this.evita.queryCatalog(TEST_CATALOG, session -> {
			return session.getCatalogSchema();
		});
	}

	/**
	 * Closes the embedded evitaDB and opens a new one over the very same storage, so that everything asserted
	 * afterwards was read back from disk - through the load-time check of the analyzers.
	 */
	private void reopenOverTheSameStorage() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		// the constructor schedules the initial catalog load and this joins the futures it created
		this.evita.waitUntilFullyInitialized();
		if (this.evita.getCatalogState(TEST_CATALOG).orElse(null) == CatalogState.INACTIVE) {
			CatalogUnpublishableBarrierAssertions.activateWithConflictRetry(this.evita, TEST_CATALOG);
		}
	}

}
