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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.dataInLocalesAll;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the indexes of `Locale` attributes survive a restart: a filterable attribute and a sort-only one keep
 * their values in the comparable `Locale` wrapper the index stores, so the catalog written to disk must read those
 * values back, and filtering and sorting by them must answer the same after the catalog is loaded again.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Locale attribute indexes survive a catalog reload")
@Tag(ATTRIBUTE)
@Tag(STORAGE)
class LocaleAttributeReloadTest implements EvitaTestSupport {
	private static final String LANGUAGE = "language";
	private static final String SPOKEN_LANGUAGE = "spokenLanguage";
	private static final String WRITING_SYSTEM = "writingSystem";
	private static final String NAME = "name";
	private static final Locale CHINESE = Locale.forLanguageTag("zh");
	private static final Locale SIMPLIFIED_CHINESE = Locale.forLanguageTag("zh-Hans");
	private static final Locale TRADITIONAL_CHINESE = Locale.forLanguageTag("zh-Hant");

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("LocaleAttributeReload");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("filtering and sorting by Locale attributes answer the same after a restart")
	void shouldFilterAndSortByLocaleAttributesAfterRestart() {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(LANGUAGE, Locale.class, whichIs -> whichIs.filterable())
					.withAttribute(SPOKEN_LANGUAGE, Locale.class, whichIs -> whichIs.sortable())
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(LANGUAGE, Locale.ENGLISH)
						.setAttribute(SPOKEN_LANGUAGE, Locale.GERMAN)
				);
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 2)
						.setAttribute(LANGUAGE, Locale.GERMAN)
						.setAttribute(SPOKEN_LANGUAGE, Locale.FRENCH)
				);
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 3)
						.setAttribute(LANGUAGE, Locale.ENGLISH)
						.setAttribute(SPOKEN_LANGUAGE, Locale.forLanguageTag("cs"))
				);
			}
		);
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);

		final List<Integer> englishBefore = queryPrimaryKeys(this::englishProducts);
		final List<Integer> sortedBefore = queryPrimaryKeys(this::productsBySpokenLanguage);
		assertEquals(List.of(1, 3), englishBefore);
		// language tags order `cs`, `de`, `fr`
		assertEquals(List.of(3, 1, 2), sortedBefore);

		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		// the catalog is loaded in the background - its load is what reads the Locale values back
		this.evita.waitUntilFullyInitialized();

		assertEquals(englishBefore, queryPrimaryKeys(this::englishProducts));
		assertEquals(sortedBefore, queryPrimaryKeys(this::productsBySpokenLanguage));
	}

	@Test
	@DisplayName("locales differing only in their script stay distinct after a restart")
	void shouldKeepLocaleScriptAfterRestart() {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withLocale(SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE)
					.withAttribute(WRITING_SYSTEM, Locale.class, whichIs -> whichIs.filterable().sortable())
					.withAttribute(NAME, String.class, whichIs -> whichIs.localized().nullable())
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(WRITING_SYSTEM, SIMPLIFIED_CHINESE)
						.setAttribute(NAME, SIMPLIFIED_CHINESE, "simplified")
						.setAttribute(NAME, TRADITIONAL_CHINESE, "traditional")
				);
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 2)
						.setAttribute(WRITING_SYSTEM, TRADITIONAL_CHINESE)
				);
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 3)
						.setAttribute(WRITING_SYSTEM, CHINESE)
				);
			}
		);
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);

		assertAll(indexesKeepLocaleScripts());

		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		// the catalog is loaded in the background - its load is what reads the Locale values back
		this.evita.waitUntilFullyInitialized();

		// the indexes are read back from disk on load, while the entity is fetched from disk in both runs
		assertAll(
			Stream.concat(Arrays.stream(indexesKeepLocaleScripts()), Arrays.stream(entityKeepsLocaleScripts()))
				.toArray(Executable[]::new)
		);
	}

	/**
	 * Returns checks that the filter and sort indexes tell `zh`, `zh-Hans` and `zh-Hant` apart.
	 *
	 * @return the checks
	 */
	@Nonnull
	private Executable[] indexesKeepLocaleScripts() {
		return new Executable[]{
			() -> assertEquals(List.of(1), queryPrimaryKeys(session -> productsWritten(session, SIMPLIFIED_CHINESE))),
			() -> assertEquals(List.of(2), queryPrimaryKeys(session -> productsWritten(session, TRADITIONAL_CHINESE))),
			() -> assertEquals(List.of(3), queryPrimaryKeys(session -> productsWritten(session, CHINESE))),
			// language tags order `zh`, `zh-Hans`, `zh-Hant`
			() -> assertEquals(List.of(3, 1, 2), queryPrimaryKeys(this::productsByWritingSystem))
		};
	}

	/**
	 * Returns checks that the stored entity keeps its `zh-Hans` attribute value and its attributes localized to
	 * `zh-Hans` and `zh-Hant` apart.
	 *
	 * @return the checks
	 */
	@Nonnull
	private Executable[] entityKeepsLocaleScripts() {
		final Function<EvitaSessionContract, SealedEntity> fetchFirstProduct =
			session -> session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocalesAll()).orElseThrow();
		final SealedEntity product = this.evita.queryCatalog(TEST_CATALOG, fetchFirstProduct);
		return new Executable[]{
			() -> assertEquals(SIMPLIFIED_CHINESE, product.getAttribute(WRITING_SYSTEM)),
			() -> assertEquals("simplified", product.getAttribute(NAME, SIMPLIFIED_CHINESE)),
			() -> assertEquals("traditional", product.getAttribute(NAME, TRADITIONAL_CHINESE))
		};
	}

	/**
	 * Runs a query in a read-only session and returns the primary keys it found, in order.
	 *
	 * @param querying runs the query in the session
	 * @return the primary keys
	 */
	@Nonnull
	private List<Integer> queryPrimaryKeys(
		@Nonnull Function<EvitaSessionContract, List<EntityReferenceContract>> querying
	) {
		final Function<EvitaSessionContract, List<Integer>> primaryKeys =
			session -> querying.apply(session).stream().map(EntityReferenceContract::getPrimaryKey).toList();
		return this.evita.queryCatalog(TEST_CATALOG, primaryKeys);
	}

	/**
	 * Finds the products whose language is English.
	 *
	 * @param session the session
	 * @return the found references
	 */
	@Nonnull
	private List<EntityReferenceContract> englishProducts(@Nonnull EvitaSessionContract session) {
		return session.queryListOfEntityReferences(
			query(collection(Entities.PRODUCT), filterBy(attributeEquals(LANGUAGE, Locale.ENGLISH)))
		);
	}

	/**
	 * Finds the products written in the passed writing system.
	 *
	 * @param session       the session
	 * @param writingSystem the locale to look for
	 * @return the found references
	 */
	@Nonnull
	private List<EntityReferenceContract> productsWritten(
		@Nonnull EvitaSessionContract session,
		@Nonnull Locale writingSystem
	) {
		return session.queryListOfEntityReferences(
			query(collection(Entities.PRODUCT), filterBy(attributeEquals(WRITING_SYSTEM, writingSystem)))
		);
	}

	/**
	 * Lists all products ordered by their writing system.
	 *
	 * @param session the session
	 * @return the found references
	 */
	@Nonnull
	private List<EntityReferenceContract> productsByWritingSystem(@Nonnull EvitaSessionContract session) {
		return session.queryListOfEntityReferences(
			query(collection(Entities.PRODUCT), orderBy(attributeNatural(WRITING_SYSTEM)))
		);
	}

	/**
	 * Lists all products ordered by their spoken language.
	 *
	 * @param session the session
	 * @return the found references
	 */
	@Nonnull
	private List<EntityReferenceContract> productsBySpokenLanguage(@Nonnull EvitaSessionContract session) {
		return session.queryListOfEntityReferences(
			query(collection(Entities.PRODUCT), orderBy(attributeNatural(SPOKEN_LANGUAGE)))
		);
	}
}
