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
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.dataType.BigDecimalNumberRange;
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
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Verifies that ordering by a `BigDecimalNumberRange` gives the same result on both routes the engine sorts by - the
 * sort index, and the comparators of prefetched entity bodies.
 *
 * A range compares by its bounds encoded as longs at some decimal scale. The index holds every range encoded at the
 * attribute schema's `indexedDecimalPlaces`, while the entity body keeps each range at the scale of its own bounds, so
 * the bodies of different entities may hold the very same kind of value at different scales. A comparator that compared
 * the bodies as they are would order `[3.4,4.5]` (encoded `34`) before `[2.25,2.50]` (encoded `225`). Every comparator
 * reading a range from a body must therefore encode it at the schema scale first - for a plain attribute, an element of
 * a compound, a reference attribute and an element of a reference compound, whether the reference is picked or
 * traversed, and for the references ordered within the fetched entity.
 *
 * The fixture stores ranges with one and with two decimal places under a schema indexing them at two. The values are
 * chosen so that comparing them at their own scales gives a different order from comparing them at the schema scale.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Ordering by a BigDecimalNumberRange of mixed scales on the index and the prefetch route")
@Tag(ENGINE)
@Tag(ORDER)
@Tag(ATTRIBUTE)
class RangeOrderingRouteParityFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String REF_BRAND = "brand";
	private static final String ATTR_VALIDITY = "validity";
	private static final String ATTR_CODE = "code";
	private static final String COMPOUND_VALIDITY_CODE = "validityCode";
	private static final int SCHEMA_SCALE = 2;
	/**
	 * Products sorted by the scenarios - the expected ascending order is 3, 2, 1.
	 */
	private static final int[] PRODUCTS = {1, 2, 3};
	private static final int[] EXPECTED_ORDER = {3, 2, 1};
	/**
	 * A product referencing all brands, each reference carrying the range of the product with the same primary key -
	 * its references are expected in the order of brands 3, 2, 1.
	 */
	private static final int PRODUCT_WITH_ALL_BRANDS = 4;

	private TestPaths paths;
	private Evita evita;

	/**
	 * The orderings of the entities, each served by a different comparator on the prefetch route.
	 */
	private enum Ordering {
		ATTRIBUTE(() -> attributeNatural(ATTR_VALIDITY, OrderDirection.ASC)),
		COMPOUND(() -> attributeNatural(COMPOUND_VALIDITY_CODE, OrderDirection.ASC)),
		PICK_FIRST_REFERENCE_ATTRIBUTE(
			() -> referenceProperty(
				REF_BRAND,
				pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
				attributeNatural(ATTR_VALIDITY, OrderDirection.ASC)
			)
		),
		PICK_FIRST_REFERENCE_COMPOUND(
			() -> referenceProperty(
				REF_BRAND,
				pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
				attributeNatural(COMPOUND_VALIDITY_CODE, OrderDirection.ASC)
			)
		),
		TRAVERSED_REFERENCE_ATTRIBUTE(
			() -> referenceProperty(
				REF_BRAND,
				traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
				attributeNatural(ATTR_VALIDITY, OrderDirection.ASC)
			)
		),
		TRAVERSED_REFERENCE_COMPOUND(
			() -> referenceProperty(
				REF_BRAND,
				traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
				attributeNatural(COMPOUND_VALIDITY_CODE, OrderDirection.ASC)
			)
		);

		/**
		 * Supplies the ordering constraint.
		 */
		private final Supplier<OrderConstraint> constraint;

		Ordering(@Nonnull Supplier<OrderConstraint> constraint) {
			this.constraint = constraint;
		}
	}

	/**
	 * Provides every combination of the catalog state and the ordering.
	 *
	 * @return arguments of the parameterized scenario
	 */
	@Nonnull
	static Stream<Arguments> stateAndOrdering() {
		return Arrays.stream(new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE})
			.flatMap(state -> Arrays.stream(Ordering.values()).map(ordering -> Arguments.of(state, ordering)));
	}

	/**
	 * Provides every combination of the catalog state and the reference attribute or compound the references of an
	 * entity are ordered by.
	 *
	 * @return arguments of the parameterized scenario
	 */
	@Nonnull
	static Stream<Arguments> stateAndSortedReferenceProperty() {
		return Stream.of(CatalogState.WARMING_UP, CatalogState.ALIVE)
			.flatMap(state -> Stream.of(ATTR_VALIDITY, COMPOUND_VALIDITY_CODE).map(it -> Arguments.of(state, it)));
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("RangeOrderingRouteParityFunctionalTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("stateAndOrdering")
	@DisplayName("the prefetch route orders the entities as the index route does")
	void shouldOrderEntitiesAlikeOnBothRoutes(CatalogState state, Ordering ordering) {
		prepare(state);
		for (PlanPreference route : PlanPreference.values()) {
			assertArrayEquals(EXPECTED_ORDER, queryOrder(route, ordering.constraint.get()), "order on the " + route);
		}
	}

	@ParameterizedTest(name = "{0}, by {1}")
	@MethodSource("stateAndSortedReferenceProperty")
	@DisplayName("the references of a fetched entity are ordered by the range at the schema scale")
	void shouldOrderReferencesOfEntityByRange(CatalogState state, String sortedBy) {
		prepare(state);
		final SealedEntity product = this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, SealedEntity>) session -> session.queryOne(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(entityPrimaryKeyInSet(PRODUCT_WITH_ALL_BRANDS)),
					require(
						entityFetch(
							referenceContent(REF_BRAND, orderBy(attributeNatural(sortedBy, OrderDirection.ASC)))
						)
					)
				),
				SealedEntity.class
			).orElseThrow()
		);
		assertArrayEquals(
			EXPECTED_ORDER,
			product.getReferences(REF_BRAND).stream().mapToInt(ReferenceContract::getReferencedPrimaryKey).toArray(),
			"references ordered by " + sortedBy
		);
	}

	/**
	 * Queries the sorted products on the route and returns their primary keys in the result order, after proving from
	 * the telemetry that the query really took the route.
	 *
	 * @param route    the route the query is steered to
	 * @param ordering the ordering of the query
	 * @return primary keys of the products in the result order
	 */
	@Nonnull
	private int[] queryOrder(@Nonnull PlanPreference route, @Nonnull OrderConstraint ordering) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(route.filter(PRODUCTS, entityPrimaryKeyInSet(PRODUCTS))),
						orderBy(ordering),
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
	 * Returns the range of the given product or brand. The ranges ascend in the order 3, 2, 1, but the second one has
	 * two decimal places and the others one, so comparing them at their own scales would order them 3, 1, 2.
	 *
	 * @param primaryKey primary key of the product or brand
	 * @return the range
	 */
	@Nonnull
	private static BigDecimalNumberRange validityOf(int primaryKey) {
		return switch (primaryKey) {
			case 1 -> BigDecimalNumberRange.between(new BigDecimal("3.4"), new BigDecimal("4.5"));
			case 2 -> BigDecimalNumberRange.between(new BigDecimal("2.25"), new BigDecimal("2.50"));
			case 3 -> BigDecimalNumberRange.between(new BigDecimal("1.2"), new BigDecimal("1.3"));
			default -> throw new IllegalArgumentException("Unexpected primary key " + primaryKey);
		};
	}

	/**
	 * Creates the schema and the data and brings the catalog to the requested state. Each sorted product carries its
	 * range both on itself and on its single reference to the first brand; the last product references every brand,
	 * with the range of the product sharing the brand's primary key.
	 *
	 * @param state target catalog state
	 */
	private void prepare(@Nonnull CatalogState state) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withAttribute(
						ATTR_VALIDITY, BigDecimalNumberRange.class,
						thatIs -> thatIs.sortable().nullable().indexDecimalPlaces(SCHEMA_SCALE)
					)
					.withAttribute(ATTR_CODE, String.class, AttributeSchemaEditor::nullable)
					.withSortableAttributeCompound(
						COMPOUND_VALIDITY_CODE,
						new AttributeElement(ATTR_VALIDITY, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTR_CODE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					)
					.withReferenceToEntity(
						REF_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(
								ATTR_VALIDITY, BigDecimalNumberRange.class,
								thatIs -> thatIs.sortable().nullable().indexDecimalPlaces(SCHEMA_SCALE)
							)
							.withAttribute(ATTR_CODE, String.class, AttributeSchemaEditor::nullable)
							.withSortableAttributeCompound(
								COMPOUND_VALIDITY_CODE,
								new AttributeElement(ATTR_VALIDITY, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
								new AttributeElement(ATTR_CODE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							)
					)
					.updateVia(session);
				for (int pk : PRODUCTS) {
					session.createNewEntity(ENTITY_BRAND, pk).upsertVia(session);
				}
				for (int pk : PRODUCTS) {
					session.createNewEntity(ENTITY_PRODUCT, pk)
						.setAttribute(ATTR_VALIDITY, validityOf(pk))
						.setAttribute(ATTR_CODE, "code")
						.setReference(
							REF_BRAND, 1,
							whichIs -> whichIs
								.setAttribute(ATTR_VALIDITY, validityOf(pk))
								.setAttribute(ATTR_CODE, "code")
						)
						.upsertVia(session);
				}
				session.createNewEntity(ENTITY_PRODUCT, PRODUCT_WITH_ALL_BRANDS)
					.setReference(REF_BRAND, 1, whichIs -> whichIs.setAttribute(ATTR_VALIDITY, validityOf(1)))
					.setReference(REF_BRAND, 2, whichIs -> whichIs.setAttribute(ATTR_VALIDITY, validityOf(2)))
					.setReference(REF_BRAND, 3, whichIs -> whichIs.setAttribute(ATTR_VALIDITY, validityOf(3)))
					.upsertVia(session);
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
