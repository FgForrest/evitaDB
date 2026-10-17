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
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.exception.AttributeNotSortableException;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.PlanPreference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyNatural;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.queryTelemetry;
import static io.evitadb.api.query.QueryConstraints.referenceContent;
import static io.evitadb.api.query.QueryConstraints.referenceProperty;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.traverseByEntityProperty;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that ordering by a sortable attribute compound whose element attributes are not sortable themselves - the
 * compound being sortable is the whole contract, its elements only have to exist - gives the same answer whether the
 * planner reads the compound's sort index or compares the values of prefetched entities, for an entity compound,
 * a localized entity compound and a reference compound - picking the first reference or traversing them - alike. Ordering by an attribute that is not sortable, or by
 * a compound that does not exist, is refused on both routes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Ordering by a compound of non-sortable attributes")
@Tag(ENGINE)
@Tag(ORDER)
@Tag(ATTRIBUTE)
@Tag(REFERENCE)
class CompoundOrderingWithoutSortableElementsTest implements EvitaTestSupport {
	private static final String BRANDS = "brands";
	private static final String CODE = "code";
	private static final String PRIORITY = "priority";
	private static final String NAME = "name";
	private static final String LABEL = "label";
	private static final String RANK = "rank";
	private static final String CODE_AND_PRIORITY = "codeAndPriority";
	private static final String NAME_AND_PRIORITY = "nameAndPriority";
	private static final String LABEL_AND_RANK = "labelAndRank";
	private static final Locale LOCALE = Locale.ENGLISH;
	private static final int[] ALL_PRODUCTS = {1, 2, 3, 4, 5};

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CompoundOrderingWithoutSortableElements");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		defineAndPopulate();
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(PlanPreference.class)
	@DisplayName("an entity compound orders the same on both routes")
	void shouldOrderByEntityCompound(@Nonnull PlanPreference plan) {
		// code ascending with the missing code last, priority descending inside one code
		assertEquals(
			List.of(2, 5, 3, 1, 4),
			queryOrder(plan, new FilterConstraint[0], attributeNatural(CODE_AND_PRIORITY))
		);
		assertEquals(
			List.of(4, 1, 3, 5, 2),
			queryOrder(plan, new FilterConstraint[0], attributeNatural(CODE_AND_PRIORITY, OrderDirection.DESC))
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(PlanPreference.class)
	@DisplayName("a localized entity compound orders the same on both routes")
	void shouldOrderByLocalizedEntityCompound(@Nonnull PlanPreference plan) {
		// name ascending, priority descending inside one name
		assertEquals(
			List.of(3, 1, 2, 5, 4),
			queryOrder(
				plan, new FilterConstraint[]{entityLocaleEquals(LOCALE)}, attributeNatural(NAME_AND_PRIORITY)
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(PlanPreference.class)
	@DisplayName("a reference compound picking the first reference orders the same on both routes")
	void shouldOrderByReferenceCompoundPickingFirstReference(@Nonnull PlanPreference plan) {
		// each product is ordered by its reference to the brand with the lowest primary key: label ascending with the
		// missing label last, rank descending inside one label
		assertEquals(
			List.of(5, 2, 3, 1, 4),
			queryOrder(
				plan, new FilterConstraint[0], referenceProperty(BRANDS, attributeNatural(LABEL_AND_RANK))
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(PlanPreference.class)
	@DisplayName("a reference compound traversing the references orders the same on both routes")
	void shouldOrderByReferenceCompoundTraversingReferences(@Nonnull PlanPreference plan) {
		// the products of brand 1 first, ordered by the compound; then the products met only through brand 2
		assertEquals(
			List.of(5, 2, 1, 3, 4),
			queryOrder(
				plan,
				new FilterConstraint[0],
				referenceProperty(
					BRANDS,
					traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
					attributeNatural(LABEL_AND_RANK)
				)
			)
		);
	}

	@Test
	@DisplayName("a reference compound orders the references of a fetched entity")
	void shouldOrderReferencesOfFetchedEntityByReferenceCompound() {
		final Function<EvitaSessionContract, SealedEntity> fetchFirstProduct = session -> session
			.getEntity(Entities.PRODUCT, 1, referenceContent(BRANDS, orderBy(attributeNatural(LABEL_AND_RANK))))
			.orElseThrow();
		final SealedEntity product = this.evita.queryCatalog(TEST_CATALOG, fetchFirstProduct);
		// the brand 2 reference carries label `a`, the brand 1 reference label `b`
		assertEquals(
			List.of(2, 1),
			product.getReferences(BRANDS).stream().map(ReferenceContract::getReferencedPrimaryKey).toList()
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(PlanPreference.class)
	@DisplayName("an attribute that is not sortable is refused on both routes")
	void shouldRefuseOrderingByNonSortableAttribute(@Nonnull PlanPreference plan) {
		assertThrows(
			AttributeNotSortableException.class,
			() -> queryOrder(plan, new FilterConstraint[0], attributeNatural(CODE))
		);
		assertThrows(
			AttributeNotSortableException.class,
			() -> queryOrder(plan, new FilterConstraint[0], referenceProperty(BRANDS, attributeNatural(LABEL)))
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(PlanPreference.class)
	@DisplayName("a compound that does not exist is refused on both routes")
	void shouldRefuseOrderingByMissingCompound(@Nonnull PlanPreference plan) {
		assertThrows(
			AttributeNotFoundException.class,
			() -> queryOrder(plan, new FilterConstraint[0], attributeNatural("missingCompound"))
		);
		assertThrows(
			AttributeNotFoundException.class,
			() -> queryOrder(
				plan, new FilterConstraint[0], referenceProperty(BRANDS, attributeNatural("missingCompound"))
			)
		);
	}

	/**
	 * Defines the brand and product schemas - the compounds' elements carry no trait at all - and stores two brands
	 * and five products.
	 */
	private void defineAndPopulate() {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withLocale(LOCALE)
					.withAttribute(CODE, String.class, whichIs -> whichIs.nullable())
					.withAttribute(PRIORITY, Integer.class)
					.withAttribute(NAME, String.class, whichIs -> whichIs.localized())
					.withSortableAttributeCompound(
						CODE_AND_PRIORITY,
						new AttributeElement(CODE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(PRIORITY, OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
					)
					.withSortableAttributeCompound(
						NAME_AND_PRIORITY,
						AttributeElement.attributeElement(NAME),
						AttributeElement.attributeElement(PRIORITY, OrderDirection.DESC)
					)
					.withReferenceToEntity(
						BRANDS, Entities.BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFiltering()
							.withAttribute(LABEL, String.class, thatIs -> thatIs.nullable())
							.withAttribute(RANK, Integer.class)
							.withSortableAttributeCompound(
								LABEL_AND_RANK,
								new AttributeElement(LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
								new AttributeElement(RANK, OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
							)
					)
					.updateVia(session);
				session.createNewEntity(Entities.BRAND, 1).upsertVia(session);
				session.createNewEntity(Entities.BRAND, 2).upsertVia(session);
				upsertProduct(session, 1, "b", 1, "x", 1, "b", 1);
				upsertProduct(session, 2, "a", 2, "y", 1, "a", 1);
				upsertProduct(session, 3, "b", 3, "x", 2, "b", 5);
				upsertProduct(session, 4, null, 4, "z", 2, null, 2);
				upsertProduct(session, 5, "a", 1, "y", 1, "a", 3);
				// a second brand of product 1, which comes before its first one in the references of product 1
				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(BRANDS, 2, whichIs -> whichIs.setAttribute(LABEL, "a").setAttribute(RANK, 2))
					.upsertVia(session);
			}
		);
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Stores a product with one brand reference.
	 *
	 * @param session    the session
	 * @param primaryKey the product primary key
	 * @param code       the code, `null` to leave it out
	 * @param priority   the priority
	 * @param name       the name in {@link #LOCALE}
	 * @param brand      the referenced brand
	 * @param label      the label of the reference, `null` to leave it out
	 * @param rank       the rank of the reference
	 */
	private static void upsertProduct(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable String code,
		int priority,
		@Nonnull String name,
		int brand,
		@Nullable String label,
		int rank
	) {
		final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, primaryKey)
			.setAttribute(PRIORITY, priority)
			.setAttribute(NAME, LOCALE, name)
			.setReference(
				BRANDS, brand,
				whichIs -> {
					whichIs.setAttribute(RANK, rank);
					if (label != null) {
						whichIs.setAttribute(LABEL, label);
					}
				}
			);
		if (code != null) {
			product.setAttribute(CODE, code);
		}
		product.upsertVia(session);
	}

	/**
	 * Lists the products ordered by the constraint on the plan, asserts the plan was taken, and returns their primary
	 * keys in order.
	 *
	 * @param plan   the plan to steer the query onto
	 * @param filter the filter constraints
	 * @param order  the ordering
	 * @return the primary keys
	 */
	@Nonnull
	private List<Integer> queryOrder(
		@Nonnull PlanPreference plan,
		@Nonnull FilterConstraint[] filter,
		@Nonnull OrderConstraint order
	) {
		final Function<EvitaSessionContract, List<Integer>> primaryKeys = session -> {
			final EvitaResponse<EntityReference> response = session.query(
				query(
					collection(Entities.PRODUCT),
					filterBy(plan.filter(ALL_PRODUCTS, filter)),
					orderBy(order),
					require(plan.debug(), queryTelemetry())
				),
				EntityReference.class
			);
			plan.assertTaken(response);
			return response.getRecordData().stream().map(EntityReferenceContract::getPrimaryKey).toList();
		};
		return this.evita.queryCatalog(TEST_CATALOG, primaryKeys);
	}
}
