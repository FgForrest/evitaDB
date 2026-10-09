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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeInSet;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins that a filterable array attribute of a type that is not comparable on its own - `Currency[]`, `Locale[]` -
 * is found by an equality lookup of one of its elements. The filter index keys such an element by its comparable
 * wrapper (`ComparableCurrency`, `ComparableLocale`), the very key a lookup probes with.
 *
 * ## The fixture
 *
 * | entity | `currencies` | `locales` |
 * |---|---|---|
 * | `product:1` | `CZK`, `EUR` | `cs`, `de` |
 * | `product:2` | `USD` | `en` |
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Filterable array of comparable-wrapped values")
@Tag(INDEXING)
@Tag(FILTER)
@Tag(ATTRIBUTE)
class ComparableWrapperArrayFilterFunctionalTest implements EvitaTestSupport {
	private static final String PRODUCT = "product";
	private static final String CURRENCIES = "currencies";
	private static final String LOCALES = "locales";
	private static final Currency CZK = Currency.getInstance("CZK");
	private static final Currency EUR = Currency.getInstance("EUR");
	private static final Currency USD = Currency.getInstance("USD");
	private static final Locale CS = Locale.forLanguageTag("cs");
	private static final Locale DE = Locale.forLanguageTag("de");
	private static final Locale EN = Locale.forLanguageTag("en");

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ComparableWrapperArrayFilter");
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
	@DisplayName("Should find entities by an element of a Currency or Locale array, before and after a reload")
	void shouldFindEntitiesByArrayElement() {
		this.evita.defineCatalog(TEST_CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(CURRENCIES, Currency[].class, thatIs -> thatIs.filterable().nullable())
					.withAttribute(LOCALES, Locale[].class, thatIs -> thatIs.filterable().nullable())
					.updateVia(session);
				session.createNewEntity(PRODUCT, 1)
					.setAttribute(CURRENCIES, new Currency[]{CZK, EUR})
					.setAttribute(LOCALES, new Locale[]{CS, DE})
					.upsertVia(session);
				session.createNewEntity(PRODUCT, 2)
					.setAttribute(CURRENCIES, new Currency[]{USD})
					.setAttribute(LOCALES, new Locale[]{EN})
					.upsertVia(session);
				session.goLiveAndClose();
			}
		);

		assertEveryLookupFinds();
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
		assertEveryLookupFinds();
	}

	/**
	 * Asserts every lookup of the fixture, each reported on its own.
	 */
	private void assertEveryLookupFinds() {
		final List<Executable> lookups = new ArrayList<>(6);
		lookups.add(() -> assertEquals(List.of(1), find(attributeEquals(CURRENCIES, CZK)), "currency CZK"));
		lookups.add(() -> assertEquals(List.of(2), find(attributeEquals(CURRENCIES, USD)), "currency USD"));
		lookups.add(() -> assertEquals(List.of(1, 2), find(attributeInSet(CURRENCIES, EUR, USD)), "currencies"));
		lookups.add(() -> assertEquals(List.of(1), find(attributeEquals(LOCALES, DE)), "locale de"));
		lookups.add(() -> assertEquals(List.of(2), find(attributeEquals(LOCALES, EN)), "locale en"));
		lookups.add(() -> assertEquals(List.of(1, 2), find(attributeInSet(LOCALES, CS, EN)), "locales"));
		assertAll(lookups);
	}

	/**
	 * Returns the sorted primary keys of the products `filter` matches.
	 */
	@Nonnull
	private List<Integer> find(@Nonnull FilterConstraint filter) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfEntityReferences(query(collection(PRODUCT), filterBy(filter)))
					.stream()
					.map(EntityReferenceContract::getPrimaryKey)
					.sorted()
					.toList();
			}
		);
	}

}
