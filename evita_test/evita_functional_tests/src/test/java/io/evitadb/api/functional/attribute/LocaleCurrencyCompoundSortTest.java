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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that a sortable attribute compound may have a `Locale` or a `Currency` element: entities carrying such a
 * compound can be stored, ordered by it, and ordered by it again after the catalog is loaded from disk.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Sortable attribute compounds with Locale and Currency elements")
@Tag(ATTRIBUTE)
@Tag(ORDER)
@Tag(STORAGE)
class LocaleCurrencyCompoundSortTest implements EvitaTestSupport {
	private static final String LANGUAGE = "language";
	private static final String CURRENCY = "currency";
	private static final String PRIORITY = "priority";
	private static final String LANGUAGE_AND_PRIORITY = "languageAndPriority";
	private static final String CURRENCY_AND_PRIORITY = "currencyAndPriority";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("LocaleCurrencyCompoundSort");
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
	@DisplayName("ordering by a compound with a Locale element works before and after a restart")
	void shouldOrderByCompoundWithLocaleElementBeforeAndAfterRestart() {
		defineAndPopulate(LANGUAGE_AND_PRIORITY, LANGUAGE, OrderDirection.DESC);
		// language tags order `cs`, `de`, `en`; priority descending inside one language
		assertOrderSurvivesRestart(LANGUAGE_AND_PRIORITY, List.of(4, 3, 1, 2));
	}

	@Test
	@DisplayName("ordering by a compound with a Currency element works before and after a restart")
	void shouldOrderByCompoundWithCurrencyElementBeforeAndAfterRestart() {
		defineAndPopulate(CURRENCY_AND_PRIORITY, CURRENCY, OrderDirection.ASC);
		// currency codes order `CZK`, `EUR`, `USD`; priority ascending inside one currency
		assertOrderSurvivesRestart(CURRENCY_AND_PRIORITY, List.of(3, 2, 4, 1));
	}

	/**
	 * Defines the product schema with one compound of the given element followed by the priority, and stores four
	 * products - in warm-up, then the catalog goes live.
	 *
	 * @param compoundName      the sortable attribute compound
	 * @param elementName       the `Locale` or `Currency` attribute the compound starts with
	 * @param priorityDirection the direction of the priority element
	 */
	private void defineAndPopulate(
		@Nonnull String compoundName,
		@Nonnull String elementName,
		@Nonnull OrderDirection priorityDirection
	) {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					// ordering by a compound on prefetched entities reads its elements as sortable attributes
					.withAttribute(LANGUAGE, Locale.class, whichIs -> whichIs.sortable())
					.withAttribute(CURRENCY, Currency.class, whichIs -> whichIs.sortable())
					.withAttribute(PRIORITY, Integer.class, whichIs -> whichIs.sortable())
					.withSortableAttributeCompound(
						compoundName,
						AttributeElement.attributeElement(elementName),
						AttributeElement.attributeElement(PRIORITY, priorityDirection)
					)
					.updateVia(session);
				upsert(session, 1, Locale.GERMAN, Currency.getInstance("USD"), 1);
				upsert(session, 2, Locale.ENGLISH, Currency.getInstance("CZK"), 5);
				upsert(session, 3, Locale.GERMAN, Currency.getInstance("CZK"), 2);
				upsert(session, 4, Locale.forLanguageTag("cs"), Currency.getInstance("EUR"), 3);
			}
		);
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Asserts the order by the compound - from the sort index and on prefetched entities - before and after a restart.
	 *
	 * @param compoundName the sortable attribute compound
	 * @param expected     the expected primary keys in order
	 */
	private void assertOrderSurvivesRestart(@Nonnull String compoundName, @Nonnull List<Integer> expected) {
		assertEquals(expected, orderedBy(compoundName));
		assertEquals(expected, orderedPrefetchedBy(compoundName));

		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		// the catalog is loaded in the background - its load is what reads the compound values back
		this.evita.waitUntilFullyInitialized();

		assertEquals(expected, orderedBy(compoundName));
		assertEquals(expected, orderedPrefetchedBy(compoundName));
	}

	/**
	 * Stores a product with the given attribute values.
	 *
	 * @param session    the session
	 * @param primaryKey the product primary key
	 * @param language   the language attribute
	 * @param currency   the currency attribute
	 * @param priority   the priority attribute
	 */
	private static void upsert(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nonnull Locale language,
		@Nonnull Currency currency,
		int priority
	) {
		session.upsertEntity(
			session.createNewEntity(Entities.PRODUCT, primaryKey)
				.setAttribute(LANGUAGE, language)
				.setAttribute(CURRENCY, currency)
				.setAttribute(PRIORITY, priority)
		);
	}

	/**
	 * Lists all products ordered by the compound on the prefetched entities - the route that compares the values the
	 * entities carry instead of reading the sort index - and returns their primary keys in order.
	 *
	 * @param compoundName the sortable attribute compound
	 * @return the primary keys
	 */
	@Nonnull
	private List<Integer> orderedPrefetchedBy(@Nonnull String compoundName) {
		final Function<EvitaSessionContract, List<Integer>> primaryKeys = session -> session
			.queryListOfEntityReferences(
				query(
					collection(Entities.PRODUCT),
					filterBy(entityPrimaryKeyInSet(1, 2, 3, 4)),
					orderBy(attributeNatural(compoundName)),
					require(debug(DebugMode.PREFER_PREFETCHING))
				)
			)
			.stream()
			.map(EntityReferenceContract::getPrimaryKey)
			.toList();
		return this.evita.queryCatalog(TEST_CATALOG, primaryKeys);
	}

	/**
	 * Lists all products ordered by the compound and returns their primary keys in order.
	 *
	 * @param compoundName the sortable attribute compound
	 * @return the primary keys
	 */
	@Nonnull
	private List<Integer> orderedBy(@Nonnull String compoundName) {
		final Function<EvitaSessionContract, List<Integer>> primaryKeys = session -> session
			.queryListOfEntityReferences(query(collection(Entities.PRODUCT), orderBy(attributeNatural(compoundName))))
			.stream()
			.map(EntityReferenceContract::getPrimaryKey)
			.toList();
		return this.evita.queryCatalog(TEST_CATALOG, primaryKeys);
	}
}
