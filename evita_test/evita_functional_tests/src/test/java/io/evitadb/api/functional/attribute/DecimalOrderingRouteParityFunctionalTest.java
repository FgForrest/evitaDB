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

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.PlanPreference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Verifies that ordering by a `BigDecimal` attribute holding more decimal places than its schema indexes gives the same
 * result on both routes the engine sorts by - the sort index, and the comparators of prefetched entity bodies.
 *
 * The sort index of a plain attribute holds a `BigDecimal` rounded to the attribute schema's `indexedDecimalPlaces`,
 * while the entity body keeps the value as it was written. Values that differ only past the schema scale are therefore
 * one value to the index - a tie the index breaks by primary key - and the comparators must treat them as one value
 * too, rather than ordering them by the digits the index does not see. A sortable attribute compound is the exception:
 * its index keeps a `BigDecimal` element exact, so both routes must order its elements by the exact value.
 *
 * Three products hold values rounding to `1.23` in an exact order that differs from their primary key order in both
 * directions, and a fourth holds `1.1`, which orders before them on every route. Each product carries the same values
 * on its reference to the brand, and a fifth product references every brand with them, for the ordering of the
 * references of one entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Ordering by a BigDecimal finer than its indexed decimal places on the index and the prefetch route")
@Tag(ENGINE)
@Tag(ORDER)
@Tag(ATTRIBUTE)
class DecimalOrderingRouteParityFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String REF_BRAND = "brand";
	private static final String ATTR_PRICE = "price";
	private static final String ATTR_RANK = "rank";
	private static final String COMPOUND_PRICE_RANK = "priceRank";
	private static final int SCHEMA_SCALE = 2;
	/**
	 * Products sorted by the scenarios, and the primary keys of the brands referenced by the fifth product.
	 */
	private static final int[] PRODUCTS = {1, 2, 3, 4};
	/**
	 * A product referencing every brand, each reference carrying the values of the product with the same primary key.
	 */
	private static final int PRODUCT_WITH_ALL_BRANDS = 5;

	private TestPaths paths;
	private Evita evita;

	/**
	 * The orderings of the entities, each served by a different comparator on the prefetch route. The secondary
	 * variants append an ordering by an integer, which must not decide the order of the tied products on one route
	 * only.
	 */
	private enum Ordering {
		ATTRIBUTE,
		ATTRIBUTE_THEN_RANK,
		PICK_FIRST_REFERENCE_ATTRIBUTE,
		PICK_FIRST_REFERENCE_ATTRIBUTE_THEN_RANK,
		TRAVERSED_REFERENCE_ATTRIBUTE,
		TRAVERSED_REFERENCE_ATTRIBUTE_THEN_RANK;

		/**
		 * Creates the ordering constraint in the given direction of the decimal attribute.
		 *
		 * @param direction the direction of the decimal attribute
		 * @return the ordering constraint
		 */
		@Nonnull
		OrderConstraint[] constraint(@Nonnull OrderDirection direction) {
			return switch (this) {
				case ATTRIBUTE -> new OrderConstraint[]{attributeNatural(ATTR_PRICE, direction)};
				case ATTRIBUTE_THEN_RANK -> new OrderConstraint[]{
					attributeNatural(ATTR_PRICE, direction), attributeNatural(ATTR_RANK, OrderDirection.ASC)
				};
				case PICK_FIRST_REFERENCE_ATTRIBUTE -> new OrderConstraint[]{
					referenceProperty(
						REF_BRAND,
						pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
						attributeNatural(ATTR_PRICE, direction)
					)
				};
				case PICK_FIRST_REFERENCE_ATTRIBUTE_THEN_RANK -> new OrderConstraint[]{
					referenceProperty(
						REF_BRAND,
						pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
						attributeNatural(ATTR_PRICE, direction),
						attributeNatural(ATTR_RANK, OrderDirection.ASC)
					)
				};
				case TRAVERSED_REFERENCE_ATTRIBUTE -> new OrderConstraint[]{
					referenceProperty(
						REF_BRAND,
						traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
						attributeNatural(ATTR_PRICE, direction)
					)
				};
				case TRAVERSED_REFERENCE_ATTRIBUTE_THEN_RANK -> new OrderConstraint[]{
					referenceProperty(
						REF_BRAND,
						traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
						attributeNatural(ATTR_PRICE, direction),
						attributeNatural(ATTR_RANK, OrderDirection.ASC)
					)
				};
			};
		}
	}

	/**
	 * Provides every combination of the catalog state, the ordering and its direction.
	 *
	 * @return arguments of the parameterized scenario
	 */
	@Nonnull
	static Stream<Arguments> stateOrderingAndDirection() {
		return Stream.of(CatalogState.WARMING_UP, CatalogState.ALIVE)
			.flatMap(
				state -> Arrays.stream(Ordering.values())
					.flatMap(
						ordering -> Arrays.stream(OrderDirection.values())
							.map(direction -> Arguments.of(state, ordering, direction))
					)
			);
	}

	/**
	 * Provides every combination of the catalog state and the direction.
	 *
	 * @return arguments of the parameterized scenario
	 */
	@Nonnull
	static Stream<Arguments> stateAndDirection() {
		return Stream.of(CatalogState.WARMING_UP, CatalogState.ALIVE)
			.flatMap(state -> Arrays.stream(OrderDirection.values()).map(direction -> Arguments.of(state, direction)));
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("DecimalOrderingRouteParityFunctionalTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@ParameterizedTest(name = "{0}, {1}, {2}")
	@MethodSource("stateOrderingAndDirection")
	@DisplayName("the prefetch route orders values tied at the schema scale as the index route does")
	void shouldOrderEntitiesAlikeOnBothRoutes(CatalogState state, Ordering ordering, OrderDirection direction) {
		prepare(state);
		final int[] index = queryOrder(PlanPreference.INDEX_SCAN, ordering.constraint(direction));
		final int[] prefetch = queryOrder(PlanPreference.PREFETCH, ordering.constraint(direction));
		assertArrayEquals(
			index, prefetch,
			"the prefetch route must order as the index route, which ordered " + Arrays.toString(index)
		);
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("stateAndDirection")
	@DisplayName("both routes order a decimal element of a compound by its exact value")
	void shouldOrderCompoundElementByExactValueOnBothRoutes(CatalogState state, OrderDirection direction) {
		prepare(state);
		// 1.1 < 1.231 < 1.232 < 1.234 - the products 4, 3, 1, 2 hold them
		final int[] expected = direction == OrderDirection.ASC ? new int[]{4, 3, 1, 2} : new int[]{2, 1, 3, 4};
		final OrderConstraint[] orderings = {
			attributeNatural(COMPOUND_PRICE_RANK, direction),
			referenceProperty(
				REF_BRAND,
				pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
				attributeNatural(COMPOUND_PRICE_RANK, direction)
			)
		};
		for (OrderConstraint ordering : orderings) {
			for (PlanPreference route : PlanPreference.values()) {
				assertArrayEquals(expected, queryOrder(route, ordering), ordering + " on the " + route);
			}
		}
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("stateAndDirection")
	@DisplayName("the references of a fetched entity tie on values equal at the schema scale")
	void shouldTieReferencesOfEntityEqualAtSchemaScale(CatalogState state, OrderDirection direction) {
		prepare(state);
		final SealedEntity product = this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, SealedEntity>) session -> session.queryOne(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(entityPrimaryKeyInSet(PRODUCT_WITH_ALL_BRANDS)),
					require(
						entityFetch(
							referenceContent(REF_BRAND, orderBy(attributeNatural(ATTR_PRICE, direction)))
						)
					)
				),
				SealedEntity.class
			).orElseThrow()
		);
		// the brands 1, 2 and 3 tie at 1.23 and keep the order they are held in, the brand 4 holds 1.1
		final int[] expected = direction == OrderDirection.ASC ? new int[]{4, 1, 2, 3} : new int[]{1, 2, 3, 4};
		assertArrayEquals(
			expected,
			product.getReferences(REF_BRAND).stream().mapToInt(ReferenceContract::getReferencedPrimaryKey).toArray(),
			"references ordered by " + ATTR_PRICE + " " + direction
		);
	}

	/**
	 * Queries the sorted products on the route and returns their primary keys in the result order, after proving from
	 * the telemetry that the query really took the route.
	 *
	 * @param route     the route the query is steered to
	 * @param orderings the ordering constraints of the query
	 * @return primary keys of the products in the result order
	 */
	@Nonnull
	private int[] queryOrder(@Nonnull PlanPreference route, @Nonnull OrderConstraint... orderings) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, int[]>) session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(route.filter(PRODUCTS, entityPrimaryKeyInSet(PRODUCTS))),
						orderBy(orderings),
						require(queryTelemetry(), route.debug())
					),
					EntityReference.class
				);
				route.assertTaken(response);
				return response.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).toArray();
			}
		);
	}

	/**
	 * Returns the decimal value of the given product or brand. The first three round to `1.23` at the schema scale and
	 * ascend in the order 3, 1, 2 - neither their primary key order nor its reverse - the fourth is `1.1`.
	 *
	 * @param primaryKey primary key of the product or brand
	 * @return the decimal value
	 */
	@Nonnull
	private static BigDecimal priceOf(int primaryKey) {
		return switch (primaryKey) {
			case 1 -> new BigDecimal("1.232");
			case 2 -> new BigDecimal("1.234");
			case 3 -> new BigDecimal("1.231");
			case 4 -> new BigDecimal("1.1");
			default -> throw new IllegalArgumentException("Unexpected primary key " + primaryKey);
		};
	}

	/**
	 * Returns the integer rank of the given product or brand, ordering the products tied at the schema scale 2, 3, 1 -
	 * neither by their primary keys nor by their exact values in either direction.
	 *
	 * @param primaryKey primary key of the product or brand
	 * @return the rank
	 */
	private static int rankOf(int primaryKey) {
		return switch (primaryKey) {
			case 1 -> 3;
			case 2 -> 1;
			case 3 -> 2;
			default -> 0;
		};
	}

	/**
	 * Creates the schema and the data and brings the catalog to the requested state. Each sorted product carries its
	 * decimal value and rank both on itself and on its single reference to the first brand; the last product
	 * references every brand, with the values of the product sharing the brand's primary key.
	 *
	 * @param state target catalog state
	 */
	private void prepare(@Nonnull CatalogState state) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withAttribute(
						ATTR_PRICE, BigDecimal.class,
						thatIs -> thatIs.sortable().nullable().indexDecimalPlaces(SCHEMA_SCALE)
					)
					.withAttribute(ATTR_RANK, Integer.class, thatIs -> thatIs.sortable().nullable())
					.withSortableAttributeCompound(
						COMPOUND_PRICE_RANK,
						new AttributeElement(ATTR_PRICE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTR_RANK, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					)
					.withReferenceToEntity(
						REF_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(
								ATTR_PRICE, BigDecimal.class,
								thatIs -> thatIs.sortable().nullable().indexDecimalPlaces(SCHEMA_SCALE)
							)
							.withAttribute(ATTR_RANK, Integer.class, thatIs -> thatIs.sortable().nullable())
							.withSortableAttributeCompound(
								COMPOUND_PRICE_RANK,
								new AttributeElement(ATTR_PRICE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
								new AttributeElement(ATTR_RANK, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							)
					)
					.updateVia(session);
				for (int pk : PRODUCTS) {
					session.createNewEntity(ENTITY_BRAND, pk).upsertVia(session);
				}
				for (int pk : PRODUCTS) {
					session.createNewEntity(ENTITY_PRODUCT, pk)
						.setAttribute(ATTR_PRICE, priceOf(pk))
						.setAttribute(ATTR_RANK, rankOf(pk))
						.setReference(
							REF_BRAND, 1,
							whichIs -> whichIs
								.setAttribute(ATTR_PRICE, priceOf(pk))
								.setAttribute(ATTR_RANK, rankOf(pk))
						)
						.upsertVia(session);
				}
				final var productWithAllBrands = session.createNewEntity(ENTITY_PRODUCT, PRODUCT_WITH_ALL_BRANDS);
				for (int pk : PRODUCTS) {
					productWithAllBrands.setReference(
						REF_BRAND, pk,
						whichIs -> whichIs.setAttribute(ATTR_PRICE, priceOf(pk)).setAttribute(ATTR_RANK, rankOf(pk))
					);
				}
				productWithAllBrands.upsertVia(session);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			}
		);
	}

	/**
	 * Builds the engine configuration over the test folder, with sessions that never expire.
	 *
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.build();
	}
}
