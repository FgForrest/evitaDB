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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REQUIRE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that localized associated data are fetched in the implicit locale a globally unique localized attribute
 * derives, the same way localized attributes are. Only the embedded API is exercised: the gRPC client builds its
 * entity view from its own request, which carries no implicit locale.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Localized associated data in the implicit locale")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(REQUIRE)
class AssociatedDataImplicitLocaleFunctionalTest {
	private static final String DATA_SET = "associatedDataImplicitLocale";
	private static final String URL = "url";
	private static final String DESCRIPTION = "description";
	private static final String CODE = "code";
	private static final String ENGLISH_URL = "/en/product-1";

	/**
	 * Stores two translations of `description`, data stored only in German and a global value next to a globally
	 * unique localized `url`, whose lookup derives the implicit locale.
	 */
	@DataSet(value = DATA_SET)
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
				.withAssociatedData(DESCRIPTION, String.class, AssociatedDataSchemaEditor::localized)
				.withAssociatedData("details", String.class, which -> which.localized().nullable())
				.withAssociatedData(CODE, String.class)
				.updateVia(session);
			session.createNewEntity(Entities.PRODUCT, 1)
				.setAttribute(URL, Locale.ENGLISH, ENGLISH_URL)
				.setAttribute(URL, Locale.GERMAN, "/de/produkt-1")
				.setAssociatedData(DESCRIPTION, Locale.ENGLISH, "English description")
				.setAssociatedData(DESCRIPTION, Locale.GERMAN, "Deutsche Beschreibung")
				.setAssociatedData("details", Locale.GERMAN, "Other data")
				.setAssociatedData(CODE, "product-1")
				.upsertVia(session);
		});
		return new DataCarrier();
	}

	/**
	 * Fetches the product by its English `url` and checks that the lookup really made English the implicit locale,
	 * because without one the assertions below would not reach the implicit-locale path at all.
	 */
	@Nonnull
	private static SealedEntity queryByEnglishUrl(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntityContentRequire... requirements
	) {
		final SealedEntity entity = session.queryOneSealedEntity(query(
			collection(Entities.PRODUCT),
			filterBy(attributeEquals(URL, ENGLISH_URL)),
			require(entityFetch(requirements))
		)).orElseThrow();
		assertEquals(Locale.ENGLISH, ((EntityDecorator) entity).getImplicitLocale());
		return entity;
	}

	/**
	 * Returns the keys of the associated data the entity exposes, so that unrequested locales show up as extra keys.
	 */
	@Nonnull
	private static Set<AssociatedDataKey> associatedDataKeysOf(@Nonnull SealedEntity entity) {
		return entity.getAssociatedDataValues()
			.stream()
			.map(AssociatedDataValue::key)
			.collect(Collectors.toSet());
	}

	/**
	 * Covers the scan over all stored keys, which keeps a localized key only in a requested locale. Named localized
	 * associated data cannot be fetched this way: a query naming them without a locale is rejected while it is
	 * planned, before any implicit locale is derived.
	 */
	@Test
	@DisplayName("Returns the implicit-locale translations next to global data when fetching all associated data")
	@UseDataSet(DATA_SET)
	void shouldFetchImplicitLocaleTranslationsOfAllAssociatedData(@Nonnull Evita evita) {
		try (final EvitaSessionContract session = evita.createReadOnlySession(TEST_CATALOG)) {
			final SealedEntity entity = queryByEnglishUrl(session, associatedDataContentAll());

			assertEquals("English description", entity.getAssociatedData(DESCRIPTION, Locale.ENGLISH));
			assertEquals("product-1", entity.getAssociatedData(CODE));
			assertEquals(
				Set.of(new AssociatedDataKey(CODE), new AssociatedDataKey(DESCRIPTION, Locale.ENGLISH)),
				associatedDataKeysOf(entity)
			);
		}
	}
}
