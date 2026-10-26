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
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
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
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.REQUIRE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that named localized associated data honor all-locales requests on initial fetch and enrichment.
 * The same shared catalog exercises embedded reads, gRPC conversion and binary storage-part fetching.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Named associated data in all locales")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(REQUIRE)
class AssociatedDataAllLocalesFunctionalTest {
	private static final String DATA_SET = "associatedDataAllLocales";
	private static final String DESCRIPTION = "description";

	/**
	 * Stores two translations and unrelated data so name filtering cannot pass by fetching everything.
	 */
	@DataSet(value = DATA_SET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE})
	@Nonnull
	DataCarrier setUp(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withLocale(Locale.ENGLISH, Locale.GERMAN)
				.withAssociatedData(DESCRIPTION, String.class, AssociatedDataSchemaEditor::localized)
				.withAssociatedData("details", String.class, which -> which.localized().nullable())
				.withAssociatedData("code", String.class)
				.updateVia(session);
			session.createNewEntity(Entities.PRODUCT, 1)
				.setAssociatedData(DESCRIPTION, Locale.ENGLISH, "English description")
				.setAssociatedData(DESCRIPTION, Locale.GERMAN, "Deutsche Beschreibung")
				.setAssociatedData("details", Locale.GERMAN, "Other data")
				.setAssociatedData("code", "product-1")
				.upsertVia(session);
		});
		return new DataCarrier();
	}

	/**
	 * Checks actual stored translations through the unaffected unnamed path before testing a narrower request.
	 */
	private static void assertStoredTranslations(@Nonnull EvitaSessionContract session) {
		final SealedEntity stored = session.getEntity(
			Entities.PRODUCT, 1, associatedDataContentAll(), dataInLocalesAll()
		).orElseThrow();
		assertEquals(Set.of(Locale.ENGLISH, Locale.GERMAN), stored.getLocales());
		assertTranslations(stored);
		assertEquals("Other data", stored.getAssociatedData("details", Locale.GERMAN));
		assertEquals("product-1", stored.getAssociatedData("code"));
	}

	/**
	 * Asserts both non-null translation values rather than trusting only the entity's locale metadata.
	 */
	private static void assertTranslations(@Nonnull SealedEntity entity) {
		assertEquals("English description", entity.getAssociatedData(DESCRIPTION, Locale.ENGLISH),
			"Named associated data must include the stored English value");
		assertEquals("Deutsche Beschreibung", entity.getAssociatedData(DESCRIPTION, Locale.GERMAN),
			"Named associated data must include the stored German value");
	}

	/**
	 * Compares explicit locales and all-data requests with a named all-locales request on the same read route.
	 */
	private static void assertFetchParity(@Nonnull Function<EntityContentRequire[], SealedEntity> fetch) {
		assertTranslations(fetch.apply(new EntityContentRequire[] {associatedDataContentAll(), dataInLocalesAll()}));
		assertTranslations(fetch.apply(new EntityContentRequire[] {
			associatedDataContent(DESCRIPTION), dataInLocales(Locale.ENGLISH, Locale.GERMAN)
		}));
		final SealedEntity named = fetch.apply(new EntityContentRequire[] {
			associatedDataContent(DESCRIPTION), dataInLocalesAll()
		});
		assertTranslations(named);
		assertEquals(Set.of(DESCRIPTION), named.getAssociatedDataNames());
		assertEquals(2, named.getAssociatedDataValues().size());
	}

	/**
	 * Exercises the public object APIs both locally and through the real gRPC client's entity converter.
	 */
	@Nested
	@DisplayName("Object fetching")
	@Tag(GRPC)
	class ObjectFetching {
		/**
		 * Pins named all-locales reads by primary key, including no-locales and global-data semantics.
		 */
		@ParameterizedTest(name = "gRPC client: {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Returns both translations when getting named data in all locales")
		@UseDataSet(DATA_SET)
		void shouldFetchBothTranslationsWhenGettingNamedData(
			boolean remote, @Nonnull Evita evita, @Nonnull EvitaClient evitaClient
		) {
			final EvitaContract api = remote ? evitaClient : evita;
			try (final EvitaSessionContract session = api.createReadOnlySession(TEST_CATALOG)) {
				assertStoredTranslations(session);
				final SealedEntity globalOnly = session.getEntity(
					Entities.PRODUCT, 1, associatedDataContent(DESCRIPTION, "code")
				).orElseThrow();
				assertEquals("product-1", globalOnly.getAssociatedData("code"));
				assertEquals(1, globalOnly.getAssociatedDataValues().size());
				assertFetchParity(requirements -> session.getEntity(Entities.PRODUCT, 1, requirements).orElseThrow());
			}
		}

		/**
		 * Pins query fetching independently of the direct get-entity API.
		 */
		@ParameterizedTest(name = "gRPC client: {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Returns both translations when querying named data in all locales")
		@UseDataSet(DATA_SET)
		void shouldFetchBothTranslationsWhenQueryingNamedData(
			boolean remote, @Nonnull Evita evita, @Nonnull EvitaClient evitaClient
		) {
			final EvitaContract api = remote ? evitaClient : evita;
			try (final EvitaSessionContract session = api.createReadOnlySession(TEST_CATALOG)) {
				assertStoredTranslations(session);
				assertFetchParity(requirements -> session.queryOneSealedEntity(query(
					collection(Entities.PRODUCT), filterBy(entityPrimaryKeyInSet(1)),
					require(entityFetch(requirements))
				)).orElseThrow());
			}
		}
	}

	/**
	 * Covers enrichment, which combines the predicate an entity carries with the enrichment request. The gRPC client
	 * re-fetches the entity on enrichment instead of combining the two, so only the embedded API reaches it.
	 */
	@Nested
	@DisplayName("Embedded enrichment")
	class Enrichment {
		/**
		 * Covers a previous predicate with all locales but no associated data fetched yet.
		 */
		@Test
		@DisplayName("Adds named data when enriching an entity that already requests all locales")
		@UseDataSet(DATA_SET)
		void shouldFetchBothTranslationsWhenAddingNamedData(@Nonnull Evita evita) {
			try (final EvitaSessionContract session = evita.createReadOnlySession(TEST_CATALOG)) {
				assertStoredTranslations(session);
				final SealedEntity initial = session.getEntity(Entities.PRODUCT, 1, dataInLocalesAll()).orElseThrow();
				assertFalse(initial.associatedDataAvailable());
				assertFetchParity(requirements -> session.enrichEntity(initial, requirements));
			}
		}

		/**
		 * Covers the previous-predicate path with English already fetched and German still missing.
		 */
		@Test
		@DisplayName("Adds the missing translation when enriching named data to all locales")
		@UseDataSet(DATA_SET)
		void shouldFetchMissingTranslationWhenWideningLocales(@Nonnull Evita evita) {
			try (final EvitaSessionContract session = evita.createReadOnlySession(TEST_CATALOG)) {
				assertStoredTranslations(session);
				final SealedEntity initial = session.getEntity(
					Entities.PRODUCT, 1, associatedDataContent(DESCRIPTION), dataInLocales(Locale.ENGLISH)
				).orElseThrow();
				assertEquals("English description", initial.getAssociatedData(DESCRIPTION, Locale.ENGLISH));
				assertEquals(1, initial.getAssociatedDataValues().size());
				assertFetchParity(requirements -> session.enrichEntity(initial, requirements));
			}
		}
	}

	/**
	 * Covers storage-part selection before the binary entity reaches a transport converter.
	 */
	@Nested
	@DisplayName("Binary fetching")
	class BinaryFetching {
		/**
		 * Compares binary part counts to explicit locales and all-data controls on the stored two-locale entity.
		 */
		@Test
		@DisplayName("Includes both localized parts when querying named data in all locales")
		@UseDataSet(DATA_SET)
		void shouldFetchBothPartsWhenQueryingNamedBinaryData(@Nonnull Evita evita) {
			try (final EvitaSessionContract objectSession = evita.createReadOnlySession(TEST_CATALOG)) {
				assertStoredTranslations(objectSession);
			}
			try (final EvitaSessionContract session = evita.createSession(
				new SessionTraits(TEST_CATALOG, SessionFlags.BINARY)
			)) {
				final Function<EntityContentRequire[], BinaryEntity> fetch = requirements -> session.queryOne(
					query(collection(Entities.PRODUCT), filterBy(entityPrimaryKeyInSet(1)),
						require(entityFetch(requirements))), BinaryEntity.class
				).orElseThrow();
				assertEquals(4, fetch.apply(new EntityContentRequire[] {
					associatedDataContentAll(), dataInLocalesAll()
				}).getAssociatedDataStorageParts().length);
				assertEquals(2, fetch.apply(new EntityContentRequire[] {
					associatedDataContent(DESCRIPTION), dataInLocales(Locale.ENGLISH, Locale.GERMAN)
				}).getAssociatedDataStorageParts().length);
				assertEquals(2, fetch.apply(new EntityContentRequire[] {
					associatedDataContent(DESCRIPTION), dataInLocalesAll()
				}).getAssociatedDataStorageParts().length,
					"Named binary associated data must include both localized storage parts");
			}
		}
	}
}
