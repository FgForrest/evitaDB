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
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;

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

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FulltextAnalyzerValidationSchema");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

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
