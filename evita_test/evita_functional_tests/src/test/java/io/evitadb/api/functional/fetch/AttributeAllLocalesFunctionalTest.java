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

package io.evitadb.api.functional.fetch;

import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.driver.EvitaClient;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.REQUIRE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that localized attributes honor an all-locales request next to another locale source: the implicit locale
 * a globally unique localized attribute derives on the first fetch, and the locale scope an entity already carries
 * when it is enriched.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Localized attributes in all locales")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(ATTRIBUTE)
@Tag(REQUIRE)
class AttributeAllLocalesFunctionalTest {
	private static final String DATA_SET = "attributeAllLocales";
	private static final String URL = "url";
	private static final String NAME = "name";
	private static final String ENGLISH_URL = "/en/product-1";
	private static final Map<Locale, Serializable> BOTH_TRANSLATIONS = Map.of(
		Locale.ENGLISH, "English name",
		Locale.GERMAN, "Deutscher Name"
	);

	/**
	 * Stores two translations of `name` next to a globally unique localized `url`, whose lookup derives an implicit
	 * locale, and a global `code`, so a named request cannot pass by fetching everything.
	 */
	@DataSet(value = DATA_SET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE})
	@Nonnull
	DataCarrier setUp(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.getCatalogSchema()
				.openForWrite()
				.withAttribute(URL, String.class, whichIs -> whichIs.localized().uniqueGlobally())
				.updateVia(session);
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withLocale(Locale.ENGLISH, Locale.GERMAN)
				.withGlobalAttribute(URL)
				.withAttribute(NAME, String.class, AttributeSchemaEditor::localized)
				.withAttribute("code", String.class)
				.updateVia(session);
			session.createNewEntity(Entities.PRODUCT, 1)
				.setAttribute(URL, Locale.ENGLISH, ENGLISH_URL)
				.setAttribute(URL, Locale.GERMAN, "/de/produkt-1")
				.setAttribute(NAME, Locale.ENGLISH, "English name")
				.setAttribute(NAME, Locale.GERMAN, "Deutscher Name")
				.setAttribute("code", "product-1")
				.upsertVia(session);
		});
		return new DataCarrier();
	}

	/**
	 * Collects the visible `name` values by locale; unlike a per-locale getter it never throws for a locale outside
	 * the fetched scope, so a narrowed scope fails as a missing value rather than as an exception.
	 */
	@Nonnull
	private static Map<Locale, Serializable> translationsOf(@Nonnull SealedEntity entity) {
		return entity.getAttributeValues(NAME)
			.stream()
			.collect(Collectors.toMap(it -> it.key().locale(), AttributeValue::value));
	}

	/**
	 * Fetches the product by its English `url`, which makes English the implicit locale of the fetch.
	 */
	@Nonnull
	private static SealedEntity queryByEnglishUrl(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntityContentRequire... requirements
	) {
		return session.queryOneSealedEntity(query(
			collection(Entities.PRODUCT),
			filterBy(attributeEquals(URL, ENGLISH_URL)),
			require(entityFetch(requirements))
		)).orElseThrow();
	}

	/**
	 * Exercises the first fetch both locally and through the gRPC server, which fetches with the same request.
	 */
	@Nested
	@DisplayName("First fetch")
	@Tag(GRPC)
	class FirstFetch {
		/**
		 * Pins all-locales reads for the whole attribute set and for a single named attribute. The entity a globally
		 * unique attribute matches is prefetched without the implicit locale and only then enriched with it, so this
		 * pins the combined result of both steps rather than the locale merge of either one.
		 */
		@ParameterizedTest(name = "gRPC client: {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Returns both translations when an implicit locale meets a request for all locales")
		@UseDataSet(DATA_SET)
		void shouldFetchBothTranslationsWhenImplicitLocaleMeetsAllLocales(
			boolean remote, @Nonnull Evita evita, @Nonnull EvitaClient evitaClient
		) {
			final EvitaContract api = remote ? evitaClient : evita;
			try (final EvitaSessionContract session = api.createReadOnlySession(TEST_CATALOG)) {
				final SealedEntity all = queryByEnglishUrl(session, attributeContentAll(), dataInLocalesAll());
				// the gRPC client cannot see the implicit locale, so only the embedded entity proves it was derived
				if (!remote) {
					assertEquals(Locale.ENGLISH, ((EntityDecorator) all).getImplicitLocale());
				}
				assertEquals(BOTH_TRANSLATIONS, translationsOf(all), "All attributes must include both translations");
				assertEquals(Set.of(Locale.ENGLISH, Locale.GERMAN), all.getLocales());

				final SealedEntity named = queryByEnglishUrl(session, attributeContent(NAME), dataInLocalesAll());
				assertEquals(
					BOTH_TRANSLATIONS, translationsOf(named), "A named attribute must include both translations"
				);
				assertEquals(Set.of(NAME), named.getAttributeNames());
			}
		}

		/**
		 * Guards against the fix widening a fetch that names one locale and asks for no others.
		 */
		@ParameterizedTest(name = "gRPC client: {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Returns only the filtered locale when no locales are requested")
		@UseDataSet(DATA_SET)
		void shouldFetchOnlyFilteredLocaleWhenNoLocalesAreRequested(
			boolean remote, @Nonnull Evita evita, @Nonnull EvitaClient evitaClient
		) {
			final EvitaContract api = remote ? evitaClient : evita;
			try (final EvitaSessionContract session = api.createReadOnlySession(TEST_CATALOG)) {
				final SealedEntity entity = session.queryOneSealedEntity(query(
					collection(Entities.PRODUCT),
					filterBy(entityPrimaryKeyInSet(1), entityLocaleEquals(Locale.ENGLISH)),
					require(entityFetch(attributeContentAll()))
				)).orElseThrow();

				assertEquals(Map.of(Locale.ENGLISH, "English name"), translationsOf(entity));
				assertEquals(Set.of(Locale.ENGLISH), entity.getLocales());
			}
		}
	}

	/**
	 * Covers the combination of the locale scope an entity carries with the scope of the enrichment request. The gRPC
	 * client re-fetches the entity on enrichment instead of combining the two, so only the embedded API reaches it.
	 */
	@Nested
	@DisplayName("Embedded enrichment")
	class Enrichment {
		/**
		 * Covers an entity fetched in English that is then enriched to all locales.
		 */
		@Test
		@DisplayName("Adds the missing translation when enriching to all locales")
		@UseDataSet(DATA_SET)
		void shouldAddMissingTranslationWhenEnrichingToAllLocales(@Nonnull Evita evita) {
			try (final EvitaSessionContract session = evita.createReadOnlySession(TEST_CATALOG)) {
				final SealedEntity initial = session.getEntity(
					Entities.PRODUCT, 1, attributeContent(NAME), dataInLocales(Locale.ENGLISH)
				).orElseThrow();
				assertEquals(Map.of(Locale.ENGLISH, "English name"), translationsOf(initial));

				final SealedEntity enriched = session.enrichEntity(initial, attributeContentAll(), dataInLocalesAll());

				assertEquals(BOTH_TRANSLATIONS, translationsOf(enriched), "Enrichment must add the German translation");
			}
		}

		/**
		 * Covers an entity fetched in all locales that is then enriched with a request naming a single locale.
		 */
		@Test
		@DisplayName("Keeps both translations when enriching with a single locale")
		@UseDataSet(DATA_SET)
		void shouldKeepBothTranslationsWhenEnrichingWithSingleLocale(@Nonnull Evita evita) {
			try (final EvitaSessionContract session = evita.createReadOnlySession(TEST_CATALOG)) {
				final SealedEntity initial = session.getEntity(
					Entities.PRODUCT, 1, attributeContentAll(), dataInLocalesAll()
				).orElseThrow();
				assertEquals(BOTH_TRANSLATIONS, translationsOf(initial));

				final SealedEntity enriched = session.enrichEntity(
					initial, attributeContent(NAME), dataInLocales(Locale.ENGLISH)
				);

				assertEquals(
					BOTH_TRANSLATIONS, translationsOf(enriched), "Enrichment must not hide the German translation"
				);
			}
		}

		/**
		 * Covers an entity matched by a globally unique attribute: it carries the implicit locale into the
		 * enrichment, where that locale must not narrow the request for all locales back to itself.
		 */
		@Test
		@DisplayName("Adds the other translations when enriching an implicit-locale entity to all locales")
		@UseDataSet(DATA_SET)
		void shouldAddOtherTranslationsWhenEnrichingImplicitLocaleEntityToAllLocales(@Nonnull Evita evita) {
			try (final EvitaSessionContract session = evita.createReadOnlySession(TEST_CATALOG)) {
				final SealedEntity initial = queryByEnglishUrl(session, attributeContentAll());
				assertEquals(Locale.ENGLISH, ((EntityDecorator) initial).getImplicitLocale());
				assertEquals(Map.of(Locale.ENGLISH, "English name"), translationsOf(initial));

				final SealedEntity enriched = session.enrichEntity(initial, attributeContentAll(), dataInLocalesAll());

				assertEquals(BOTH_TRANSLATIONS, translationsOf(enriched), "Enrichment must add the German translation");
			}
		}
	}
}
