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
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Locale;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.htmlStrip;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the fulltext analyzer and the length pivot of searchable fields end to end on an embedded evitaDB: that every
 * kind of field keeps them across a restart through the catalog storage, that a field naming an analyzer the catalog
 * does not have is refused, and that an analyzer a field still uses cannot be removed - unless the same update points
 * the field elsewhere, because the reference is checked on the schema an update produces as a whole.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Fulltext analyzer and length pivot of searchable fields")
@Tag(ENGINE)
@Tag(SCHEMA)
@Tag(FULLTEXT)
class FulltextFieldSettingsSchemaTest implements EvitaTestSupport {

	private static final String ANALYZER_TITLES = "titles";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_DESCRIPTION = "description";
	private static final String ATTRIBUTE_TITLE = "title";
	private static final String ATTRIBUTE_BRAND_NAME = "brandName";
	private static final String ASSOCIATED_DATA_BODY = "body";
	private static final String REFERENCE_BRAND = "brand";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FulltextFieldSettingsSchema");
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
	@DisplayName("should keep the analyzer and the length pivot of every kind of field across a restart")
	void shouldKeepFieldSettingsAcrossRestart() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withFulltextAnalyzer(
						ANALYZER_TITLES, a -> a.basedOn(FulltextAnalyzerSchemaContract.TEXT).text(htmlStrip())
					)
					.withAttribute(
						ATTRIBUTE_TITLE, String.class,
						whichIs -> whichIs.localized().searchable().analyzer(ANALYZER_TITLES).lengthPivot(8)
					)
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH)
					.withGlobalAttribute(ATTRIBUTE_TITLE)
					// declared before `searchable()` - neither of them resets the other
					.withAttribute(
						ATTRIBUTE_NAME, String.class,
						whichIs -> whichIs.localized().analyzer(ANALYZER_TITLES).lengthPivot(12).searchable()
					)
					.withAttribute(
						ATTRIBUTE_DESCRIPTION, String.class, whichIs -> whichIs.localized().nullable().searchable()
					)
					.withAssociatedData(
						ASSOCIATED_DATA_BODY, String.class,
						whichIs -> whichIs.localized().nullable().searchable()
							.analyzer(FulltextAnalyzerSchemaContract.HTML_TEXT).lengthPivot(400)
					)
					.withReferenceTo(
						REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.withAttribute(
							ATTRIBUTE_BRAND_NAME, String.class,
							thatIs -> thatIs.localized().nullable().searchable()
								.analyzer(FulltextAnalyzerSchemaContract.HTML_TEXT)
						)
					)
					.updateVia(session);
			}
		);

		reopenOverTheSameStorage();

		final AttributeSchemaContract title = catalogSchema().getAttribute(ATTRIBUTE_TITLE).orElseThrow();
		assertEquals(ANALYZER_TITLES, title.getAnalyzer());
		assertEquals(8, title.getLengthPivot());
		final EntitySchemaContract schema = productSchema();
		final AttributeSchemaContract name = schema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertTrue(name.isSearchable());
		assertEquals(ANALYZER_TITLES, name.getAnalyzer());
		assertEquals(12, name.getLengthPivot());
		final AttributeSchemaContract description = schema.getAttribute(ATTRIBUTE_DESCRIPTION).orElseThrow();
		assertEquals(FulltextAnalyzerSchemaContract.TEXT, description.getAnalyzer());
		assertEquals(AttributeSchemaContract.DEFAULT_LENGTH_PIVOT, description.getLengthPivot());
		final AssociatedDataSchemaContract body = schema.getAssociatedData(ASSOCIATED_DATA_BODY).orElseThrow();
		assertEquals(FulltextAnalyzerSchemaContract.HTML_TEXT, body.getAnalyzer());
		assertEquals(400, body.getLengthPivot());
		final AttributeSchemaContract brandName = schema.getReference(REFERENCE_BRAND).orElseThrow()
			.getAttribute(ATTRIBUTE_BRAND_NAME).orElseThrow();
		assertEquals(FulltextAnalyzerSchemaContract.HTML_TEXT, brandName.getAnalyzer());
		assertEquals(AttributeSchemaContract.DEFAULT_LENGTH_PIVOT, brandName.getLengthPivot());
	}

	@Test
	@DisplayName("should refuse a field naming an analyzer the catalog does not have and leave the schema untouched")
	void shouldRefuseFieldNamingUnknownAnalyzer() {
		defineProductWithoutAttributes();

		// the reference is checked when the session closes, so the transaction rolls back with the refusal as its cause
		final RollbackException exception = assertThrows(
			RollbackException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs.localized().searchable().analyzer("missing")
						)
						.updateVia(session);
				}
			)
		);

		final InvalidSchemaMutationException refusal =
			assertInstanceOf(InvalidSchemaMutationException.class, exception.getCause());
		assertTrue(refusal.getMessage().contains("`missing`"), refusal.getMessage());
		assertTrue(productSchema().getAttribute(ATTRIBUTE_NAME).isEmpty());
	}

	@Test
	@DisplayName("should refuse removing an analyzer a field still uses")
	void shouldRefuseRemovingAnalyzerInUse() {
		defineProductUsingTitlesAnalyzer();

		final RollbackException exception = assertThrows(
			RollbackException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withoutFulltextAnalyzer(ANALYZER_TITLES)
						.updateVia(session);
				}
			)
		);

		final InvalidSchemaMutationException refusal =
			assertInstanceOf(InvalidSchemaMutationException.class, exception.getCause());
		assertTrue(refusal.getMessage().contains("`" + ANALYZER_TITLES + "`"), refusal.getMessage());
		assertTrue(catalogSchema().getFulltextAnalyzer(ANALYZER_TITLES).isPresent());
	}

	@Test
	@DisplayName("should remove an analyzer in the update that points its last field elsewhere")
	void shouldRemoveAnalyzerTogetherWithRepointingItsField() {
		defineProductUsingTitlesAnalyzer();

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrow(Entities.PRODUCT)
					.openForWrite()
					.withAttribute(
						ATTRIBUTE_NAME, String.class,
						whichIs -> whichIs.analyzer(FulltextAnalyzerSchemaContract.TEXT)
					)
					.updateVia(session);
				session.getCatalogSchema()
					.openForWrite()
					.withoutFulltextAnalyzer(ANALYZER_TITLES)
					.updateVia(session);
			}
		);

		assertTrue(catalogSchema().getFulltextAnalyzer(ANALYZER_TITLES).isEmpty());
		final AttributeSchemaContract name = productSchema().getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertEquals(FulltextAnalyzerSchemaContract.TEXT, name.getAnalyzer());
		assertTrue(name.isSearchable());
	}

	/**
	 * Declares an empty product collection and switches the catalog to the transactional (alive) state, where a refused
	 * update rolls back - in warm-up the same refusal raises the unpublishable barrier and deactivates the catalog.
	 */
	private void defineProductWithoutAttributes() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH)
					.updateVia(session);
				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Declares the `titles` analyzer and a product collection whose searchable attribute uses it, and switches the
	 * catalog to the transactional (alive) state - see {@link #defineProductWithoutAttributes()} for why.
	 */
	private void defineProductUsingTitlesAnalyzer() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withFulltextAnalyzer(
						ANALYZER_TITLES, a -> a.basedOn(FulltextAnalyzerSchemaContract.TEXT).text(htmlStrip())
					)
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH)
					.withAttribute(
						ATTRIBUTE_NAME, String.class,
						whichIs -> whichIs.localized().searchable().analyzer(ANALYZER_TITLES)
					)
					.updateVia(session);
				session.goLiveAndClose();
			}
		);
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
	 * Reads the current product entity schema in a fresh read-only session.
	 *
	 * @return the product entity schema
	 */
	@Nonnull
	private EntitySchemaContract productSchema() {
		return this.evita.queryCatalog(TEST_CATALOG, session -> {
			return session.getEntitySchemaOrThrow(Entities.PRODUCT);
		});
	}

	/**
	 * Closes the embedded evitaDB and opens a new one over the very same storage, so that everything asserted
	 * afterwards was read back from disk.
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
