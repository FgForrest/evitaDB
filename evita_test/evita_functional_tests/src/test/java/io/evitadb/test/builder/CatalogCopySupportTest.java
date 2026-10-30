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

package io.evitadb.test.builder;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.exception.GenericEvitaInternalError;
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
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that {@link CatalogCopySupport} copies an entity into a catalog rebuilt from its source's schema - verbatim
 * by default, and with the associated data `evita.warmup.localize` names moved under a locale when asked to.
 *
 * The option is a system property parsed once per JVM, so the tests pass the parsed value to the overloads that take
 * it instead of setting the property.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(TEST_HARNESS)
@Tag(SCHEMA)
@DisplayName("CatalogCopySupport")
class CatalogCopySupportTest implements EvitaTestSupport {

	private static final String SOURCE_CATALOG = "catalogCopySource";
	private static final String TARGET_CATALOG = "catalogCopyTarget";
	private static final String ARTICLE = "article";
	private static final String BRAND = "brand";
	private static final String URL = "url";
	private static final String TITLE = "title";
	private static final String LABEL = "label";
	private static final String BODY = "body";
	private static final String PEREX = "perex";
	private static final int PRIMARY_KEY = 1;
	private static final Locale CZECH = Locale.forLanguageTag("cs");

	@Nested
	@DisplayName("Copying an entity into the rebuilt catalog")
	class CopyingEntity {

		private TestPaths paths;
		private Evita evita;

		/**
		 * Starts an engine whose source catalog holds one article with every kind of localized field in English and a
		 * body without a locale.
		 */
		@BeforeEach
		void setUp() {
			this.paths = createTestPaths("CatalogCopySupportTest");
			this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
			this.evita.defineCatalog(SOURCE_CATALOG).updateViaNewSession(this.evita);
			this.evita.updateCatalog(
				SOURCE_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withAttribute(URL, String.class, whichIs -> whichIs.localized())
						.updateVia(session);
					session.defineEntitySchema(ARTICLE)
						.withLocale(Locale.ENGLISH)
						.withGlobalAttribute(URL)
						.withAttribute(TITLE, String.class, whichIs -> whichIs.localized())
						.withAssociatedData(BODY, String.class)
						.withAssociatedData(PEREX, String.class, whichIs -> whichIs.localized())
						.withReferenceTo(
							BRAND, BRAND, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs.withAttribute(LABEL, String.class, thatIs -> thatIs.localized())
						)
						.updateVia(session);
					session.createNewEntity(ARTICLE, PRIMARY_KEY)
						.setAttribute(URL, Locale.ENGLISH, "/en/article")
						.setAttribute(TITLE, Locale.ENGLISH, "Title")
						.setAssociatedData(BODY, "Body")
						.setAssociatedData(PEREX, Locale.ENGLISH, "Perex")
						.setReference(BRAND, 1, whichIs -> whichIs.setAttribute(LABEL, Locale.ENGLISH, "Label"))
						.upsertVia(session);
				}
			);
		}

		@AfterEach
		void tearDown() {
			this.evita.close();
			cleanupTestPaths(this.paths);
		}

		/**
		 * Without associated data to localize, the copy holds what the source holds, in the source's locales.
		 */
		@Test
		@DisplayName("should copy the entity verbatim when no associated data is localized")
		void shouldCopyEntityVerbatimWhenNoAssociatedDataIsLocalized() {
			final SealedEntity copy = copyArticle(Map.of());

			assertEquals(Set.of(Locale.ENGLISH), copy.getLocales());
			assertEquals("Title", copy.getAttribute(TITLE, Locale.ENGLISH));
			assertEquals("Body", copy.getAssociatedData(BODY));
		}

		/**
		 * Moving the body under Czech gives the entity a locale its other localized fields have no value in. The copy
		 * must still be accepted - every one of those fields holds only the English value the source had.
		 */
		@Test
		@DisplayName("should copy the entity when localized associated data adds a locale the entity lacks")
		void shouldCopyEntityWhenLocalizedAssociatedDataAddsLocaleTheEntityLacks() {
			final SealedEntity copy = copyArticle(Map.of(ARTICLE + ".@" + BODY, CZECH));

			assertEquals(Set.of(Locale.ENGLISH, CZECH), copy.getLocales());
			assertEquals("Title", copy.getAttribute(TITLE, Locale.ENGLISH));
			assertEquals("Body", copy.getAssociatedData(BODY, CZECH));
			assertNull(copy.getAssociatedData(BODY, Locale.ENGLISH));
		}

		/**
		 * Rebuilds the source schema in a new target catalog, copies the article into it and reads the copy back.
		 */
		@Nonnull
		private SealedEntity copyArticle(@Nonnull Map<String, Locale> localizedAssociatedData) {
			final CatalogSchemaContract sourceCatalogSchema;
			final EntitySchemaContract sourceSchema;
			final SealedEntity source;
			try (final EvitaSessionContract session = this.evita.createReadOnlySession(SOURCE_CATALOG)) {
				sourceCatalogSchema = session.getCatalogSchema();
				sourceSchema = session.getEntitySchemaOrThrowException(ARTICLE);
				source = session.queryOneSealedEntity(
					query(
						collection(ARTICLE),
						filterBy(entityPrimaryKeyInSet(PRIMARY_KEY)),
						require(CatalogCopySupport.buildContentRequirement(sourceSchema))
					)
				).orElseThrow();
			}

			this.evita.defineCatalog(TARGET_CATALOG).updateViaNewSession(this.evita);
			this.evita.updateCatalog(
				TARGET_CATALOG,
				session -> {
					CatalogCopySupport.replicateSchema(
						session, sourceCatalogSchema, List.of(sourceSchema), localizedAssociatedData
					);
					session.upsertEntity(
						CatalogCopySupport.createCopyBuilder(
							source, session.getEntitySchemaOrThrowException(ARTICLE), localizedAssociatedData
						)
					);
				}
			);
			try (final EvitaSessionContract session = this.evita.createReadOnlySession(TARGET_CATALOG)) {
				return session.getEntity(ARTICLE, PRIMARY_KEY, entityFetchAllContent()).orElseThrow();
			}
		}

	}

	@Nested
	@DisplayName("Parsing the associated data to localize")
	class ParsingLocalizedAssociatedData {

		/**
		 * `cs_CZ` is the `Locale#toString` form, which a language-tag parser reads as no language at all - the copy
		 * would write the values under the root locale, which no locale-bound query reaches.
		 */
		@Test
		@DisplayName("should refuse a language tag that is not well-formed")
		void shouldThrowWhenLanguageTagIsNotWellFormed() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> CatalogCopySupport.parseLocalizedAssociatedData(ARTICLE + ".@" + BODY + "=cs_CZ")
			);
		}

		/**
		 * A well-formed tag with a region keeps the region.
		 */
		@Test
		@DisplayName("should parse a well-formed language tag with a region")
		void shouldParseLanguageTagWhenItCarriesRegion() {
			assertEquals(
				Map.of(ARTICLE + ".@" + BODY, Locale.forLanguageTag("cs-CZ")),
				CatalogCopySupport.parseLocalizedAssociatedData(ARTICLE + ".@" + BODY + "=cs-CZ")
			);
		}

	}

}
