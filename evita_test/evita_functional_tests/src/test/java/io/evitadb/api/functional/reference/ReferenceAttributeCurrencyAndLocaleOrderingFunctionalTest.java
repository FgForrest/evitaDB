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

package io.evitadb.api.functional.reference;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that ordering entities by a reference attribute of a type that is not {@link Comparable} itself -
 * {@link Currency} and {@link Locale} - answers the same order on the prefetch route as on the index route. The
 * comparator of the prefetch route used to compare such values with the comparator of the owning entities, which
 * failed with a `ClassCastException` on the first pair it compared.
 *
 * Products 1 to 3 each reference their own category with a currency and a locale on the reference; product 4
 * references a category without either value, so it is never sorted and comes last in both directions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(ORDER)
@Tag(REFERENCE)
@DisplayName("Ordering by Currency and Locale reference attributes")
class ReferenceAttributeCurrencyAndLocaleOrderingFunctionalTest implements EvitaTestSupport {
	private static final String CATALOG = "currencyAndLocaleOrdering";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String ATTRIBUTE_CURRENCY = "currency";
	private static final String ATTRIBUTE_LANGUAGE = "language";
	private TestPaths paths;
	private Evita evita;

	/**
	 * Every combination of the sorted attribute and the direction, with the order the index route
	 * answers: currencies by their code (CZK, EUR, USD), locales by their language tag (cs, de, en), the product
	 * without a value last.
	 *
	 * @return the arguments of {@link #shouldOrderByNonComparableReferenceAttributeOnEveryRoute}
	 */
	@Nonnull
	static Stream<Arguments> orderings() {
		return Stream.of(
			Arguments.of(ATTRIBUTE_CURRENCY, OrderDirection.ASC, List.of(2, 1, 3, 4)),
			Arguments.of(ATTRIBUTE_CURRENCY, OrderDirection.DESC, List.of(3, 1, 2, 4)),
			Arguments.of(ATTRIBUTE_LANGUAGE, OrderDirection.ASC, List.of(2, 3, 1, 4)),
			Arguments.of(ATTRIBUTE_LANGUAGE, OrderDirection.DESC, List.of(1, 3, 2, 4))
		);
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths(getClass().getSimpleName());
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(CATALOG, session -> {
			session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withReferenceToEntity(
					REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.indexedForFiltering()
						.withAttribute(ATTRIBUTE_CURRENCY, Currency.class, thatIs -> thatIs.sortable().nullable())
						.withAttribute(ATTRIBUTE_LANGUAGE, Locale.class, thatIs -> thatIs.sortable().nullable())
				)
				.updateVia(session);
			for (int pk = 1; pk <= 4; pk++) {
				session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk));
			}
			upsertProduct(session, 1, Currency.getInstance("EUR"), Locale.ENGLISH);
			upsertProduct(session, 2, Currency.getInstance("CZK"), Locale.forLanguageTag("cs"));
			upsertProduct(session, 3, Currency.getInstance("USD"), Locale.GERMAN);
			session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, 4).setReference(REFERENCE_CATEGORIES, 4));
		});
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@DisplayName("should order by a Currency or Locale reference attribute alike on the index and the prefetch route")
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("orderings")
	void shouldOrderByNonComparableReferenceAttributeOnEveryRoute(
		@Nonnull String attributeName,
		@Nonnull OrderDirection direction,
		@Nonnull List<Integer> expected
	) {
		final OrderConstraint ordering = referenceProperty(
			REFERENCE_CATEGORIES, attributeNatural(attributeName, direction)
		);
		assertEquals(expected, queryProductPrimaryKeys(ordering, DebugMode.PREFER_INDEX_SCAN), "index route");
		assertEquals(expected, queryProductPrimaryKeys(ordering, DebugMode.PREFER_PREFETCHING), "prefetch route");
	}

	/**
	 * Upserts a product referencing the category of the same primary key, the reference carrying both values.
	 *
	 * @param session    the read-write session
	 * @param primaryKey the primary key of the product and of the category it references
	 * @param currency   the currency stored on the reference
	 * @param locale     the locale stored on the reference
	 */
	private static void upsertProduct(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nonnull Currency currency,
		@Nonnull Locale locale
	) {
		session.upsertEntity(
			session.createNewEntity(ENTITY_PRODUCT, primaryKey)
				.setReference(
					REFERENCE_CATEGORIES, primaryKey,
					ref -> ref.setAttribute(ATTRIBUTE_CURRENCY, currency).setAttribute(ATTRIBUTE_LANGUAGE, locale)
				)
		);
	}

	/**
	 * Queries the primary keys of all products in the given order. The products are selected by their primary keys,
	 * which is what lets the engine choose the prefetch route when the debug mode prefers it.
	 *
	 * @param ordering  the ordering of the query
	 * @param debugMode the debug mode choosing the route
	 * @return the primary keys of the products in the order the engine answered
	 */
	@Nonnull
	private List<Integer> queryProductPrimaryKeys(@Nonnull OrderConstraint ordering, @Nonnull DebugMode debugMode) {
		return this.evita.queryCatalog(
			CATALOG,
			(Function<EvitaSessionContract, List<Integer>>) session -> session.query(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(entityPrimaryKeyInSet(1, 2, 3, 4)),
					orderBy(ordering),
					require(page(1, 10), debug(debugMode))
				),
				EntityReference.class
			).getRecordData().stream().map(EntityReference::getPrimaryKey).toList()
		);
	}
}
