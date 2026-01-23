/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.ReflectionLookup;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.Serial;
import java.io.Serializable;
import java.util.Currency;
import java.util.Locale;

import static io.evitadb.api.query.QueryConstraints.associatedDataContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.test.Entities.BRAND;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test verifies entity indexing behavior and checks whether the implementation prevents to upsert entities with
 * invalid or non-unique data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita indexing API")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
public class EvitaIndexingFunctionalTest {
	public static final String LOGO = "https://www.siemens.com/logo.png";
	public static final String SIEMENS_TITLE = "Siemens";
	public static final String ATTRIBUTE_CODE = "code";

	@DisplayName("Fail to create entity with conflicting unique attribute")
	@Test
	void shouldFailToInsertConflictingUniqueAttributes(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(BRAND)
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique())
					.updateVia(session);
				session.upsertEntity(createBrand(session, 1));
				assertThrows(UniqueValueViolationException.class, () -> session.upsertEntity(createBrand(session, 2)));
			}
		);
	}

	@DisplayName("Allow to reuse unique attribute when changed in original entity")
	@Test
	void shouldAllowToUpdateExistingUniqueAttributeAndReuseItForAnotherEntity(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(BRAND)
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique())
					.updateVia(session);

				session.upsertEntity(createBrand(session, 1));

				// change the unique value
				final SealedEntity theBrand = session.getEntity(BRAND, 1, attributeContentAll())
					.orElseThrow();

				session.upsertEntity(theBrand.openForWrite().setAttribute(ATTRIBUTE_CODE, "otherCode"));

				// now we can use original code for different entity
				session.upsertEntity(createBrand(session, 2));
			}
		);
	}

	@DisplayName("Allow to reuse unique attribute when original entity is removed")
	@Test
	void shouldAllowToInsertUniqueAttributeWhenOriginalEntityRemoved(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(BRAND)
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique().nullable())
					.updateVia(session);

				session.upsertEntity(createBrand(session, 1));

				// change the unique value
				final SealedEntity theBrand = session.getEntity(BRAND, 1, attributeContentAll())
					.orElseThrow();
				session.deleteEntity(BRAND, theBrand.getPrimaryKey());

				// now we can use original code for different entity
				session.upsertEntity(createBrand(session, 2));
			}
		);
	}

	@DisplayName("Tests situation when user creates minimal products and read it")
	@Test
	void shouldStoreMinimalEntityBugRegression(Evita evita) {
		evita.defineCatalog("differentCatalog")
			.withEntitySchema(
				"Product",
				entitySchema -> entitySchema
					.withGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH, Locale.GERMAN)
					.withPriceInCurrency(
						Currency.getInstance("USD"), Currency.getInstance("EUR")
					)
					.withAttribute(
						"name", String.class,
						whichIs -> whichIs
							.withDescription("The apt product name.")
							.localized()
							.filterable()
							.sortable()
							.nullable()
					)
			)
			.updateAndFetchViaNewSession(evita);

		evita.updateCatalog(
			"differentCatalog",
			session -> {
				session.createNewEntity("Product")
					.setAssociatedData(
						"stockAvailability",
						new ProductStockAvailability(10)
					)
					.upsertVia(session);

				//some custom logic to load proper entity
				final SealedEntity entity = session
					.getEntity("Product", 1, associatedDataContentAll())
					.orElseThrow();
				//deserialize the associated data
				assertNotNull(
					entity.getAssociatedData(
						"stockAvailability", ProductStockAvailability.class,
						new ReflectionLookup(ReflectionCachingBehaviour.NO_CACHE)
					)
				);
			}
		);
	}

	/*
		HELPER METHODS
	 */

	private EntityBuilder createBrand(EvitaSessionContract session, Integer primaryKey) {
		final EntityBuilder newBrand = session.createNewEntity(BRAND, primaryKey);
		newBrand.setAttribute(ATTRIBUTE_CODE, "siemens");
		newBrand.setAttribute("name", Locale.ENGLISH, SIEMENS_TITLE);
		newBrand.setAttribute("logo", LOGO);
		newBrand.setAttribute("productCount", 1);
		return newBrand;
	}

	@RequiredArgsConstructor
	public static class ProductStockAvailability implements Serializable {
		@Serial private static final long serialVersionUID = 373668161042101104L;

		@Getter private final Integer available;
	}

}
