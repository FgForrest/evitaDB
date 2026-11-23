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
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.api.requestResponse.schema.OrderBehaviour.NULLS_LAST;
import static io.evitadb.dataType.Scope.ARCHIVED;
import static io.evitadb.dataType.Scope.LIVE;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a scoped reference ordering applies only to owners in that scope. Other owners pass to the next
 * comparator or use the default ascending referenced primary keys.
 *
 * | Live brand | Name  | Group on brands | Group on brandsInAllScopes | Lane on brands |
 * |------------|-------|-----------------|---------------------------|----------------|
 * | 1 (A)      | Alpha | 11 (Zulu)       | 11 (Zulu)                 | 2              |
 * | 2 (B)      | Zulu  | 11 (Zulu)       | 10 (Alpha)                | 1              |
 * | 3 (C)      | Bravo | 10 (Alpha)      | 10 (Alpha)                | 1              |
 *
 * | Product | Scope    | Ranks A, B, C | Plain row insertion | Reflected chain |
 * |---------|----------|---------------|---------------------|-----------------|
 * | 1       | LIVE     | 1, 2, 3       | 2, 1, 3             | 2, 1, 3         |
 * | 2       | LIVE     | 2, 3, 1       | 2, 3, 1             | 1, 3, 2         |
 * | 3       | ARCHIVED | 1, 3, 2       | 1, 3, 2             | 3, 2, 1         |
 * | 4       | ARCHIVED | 3, 1, 2       | 3, 1, 2             | 1, 3, 2         |
 * | 5       | ARCHIVED | 3, 2, 1       | 3, 2, 1             | 2, 3, 1         |
 * | 6       | ARCHIVED | 2, 1, 3       | 1, 2, 3             | 3, 1, 2         |
 *
 * The plain `brands` reference is indexed in LIVE. The `brandsInAllScopes` index supports rank ordering in both
 * scopes, and the brand name is declared sortable in both scopes. Only brand 1 and group 10 have a `code`, so an
 * ordering by it leaves the other rows to the next ordering. The `laneAndRank` compound on `brands` and
 * `partlyRankedBrands` declares both elements ascending with `NULLS_LAST`. Both live products have the following
 * rows in `partlyRankedBrands`.
 *
 * | Brand | Lane | Rank | Plain predecessor chain of product owners |
 * |-------|------|------|-------------------------------------------|
 * | 1     | 1    | null | 2, 1                                      |
 * | 2     | null | null | 1, 2                                      |
 * | 3     | 1    | 1    | 2, 1                                      |
 *
 * The `chainedBrands` rows reflect `BRAND.products`, whose `Predecessor` links supply the
 * [`ReferencedEntityPredecessor` chains][chains] used by this fixture.
 *
 * [chains]: /documentation/user/en/query/ordering/reference.md#pick-first-by-entity-property
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Owner scopes in reference content ordering")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(ORDER)
@Tag(REFERENCE)
class ReferenceContentOrderingInScopeFunctionalTest {
	private static final String DATA_SET = "referenceContentOrderingInScope";
	private static final String PRODUCT = "PRODUCT";
	private static final String BRAND = "BRAND";
	private static final String BRAND_GROUP = "BRAND_GROUP";
	private static final String BRANDS = "brands";
	private static final String CHAINED_BRANDS = "chainedBrands";
	private static final String BRAND_PRODUCTS = "products";
	private static final String BRANDS_IN_ALL_SCOPES = "brandsInAllScopes";
	private static final String PARTLY_RANKED_BRANDS = "partlyRankedBrands";
	private static final String NAME = "name";
	private static final String CODE = "code";
	private static final String RANK = "rank";
	private static final String LANE = "lane";
	private static final String LANE_AND_RANK = "laneAndRank";
	private static final String PREDECESSOR = "predecessor";
	private static final Scope[] BOTH_SCOPES = {LIVE, ARCHIVED};
	private static final List<Integer> DEFAULT_ORDER = List.of(1, 2, 3);
	private static final List<Integer> DESCENDING_KEYS = List.of(3, 2, 1);
	private static final List<ProductData> PRODUCTS = List.of(
		new ProductData(1, LIVE, List.of(1, 2, 3), List.of(2, 1, 3), List.of(2, 1, 3)),
		new ProductData(2, LIVE, List.of(2, 3, 1), List.of(2, 3, 1), List.of(1, 3, 2)),
		new ProductData(3, ARCHIVED, List.of(1, 3, 2), List.of(1, 3, 2), List.of(3, 2, 1)),
		new ProductData(4, ARCHIVED, List.of(3, 1, 2), List.of(3, 1, 2), List.of(1, 3, 2)),
		new ProductData(5, ARCHIVED, List.of(3, 2, 1), List.of(3, 2, 1), List.of(2, 3, 1)),
		new ProductData(6, ARCHIVED, List.of(2, 1, 3), List.of(1, 2, 3), List.of(3, 1, 2))
	);

	/**
	 * Writes the schemas, brands, groups and products with their plain rows while the catalog warms up, goes live,
	 * archives products 3–6 and then writes every owner's chain rows.
	 *
	 * @param evita engine hosting the shared read-only fixture
	 */
	@DataSet(value = DATA_SET, destroyAfterClass = true)
	void setUp(Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			createFixture(session);
			session.goLiveAndClose();
		});
		evita.updateCatalog(TEST_CATALOG, session -> {
			for (final ProductData product : PRODUCTS) {
				if (product.scope() == ARCHIVED) {
					session.archiveEntity(PRODUCT, product.primaryKey());
				}
			}
			for (final ProductData product : PRODUCTS) {
				if (product.scope() == LIVE) {
					installLiveChain(session, product);
				} else {
					installArchivedChain(session, product);
				}
			}
		});
	}

	/**
	 * Defines the schemas and inserts the brands, groups and products while all indexes are active.
	 *
	 * @param session write session for the warming-up catalog
	 */
	private static void createFixture(EvitaSessionContract session) {
		session.defineEntitySchema(BRAND_GROUP)
			.withoutGeneratedPrimaryKey()
			.withAttribute(NAME, String.class, AttributeSchemaEditor::sortable)
			.withAttribute(CODE, String.class, attribute -> attribute.sortable().nullable())
			.updateVia(session);
		session.defineEntitySchema(BRAND)
			.withoutGeneratedPrimaryKey()
			.withAttribute(NAME, String.class, attribute -> attribute.sortableInScope(Scope.values()))
			.withAttribute(CODE, String.class, attribute -> attribute.sortableInScope(Scope.values()).nullable())
			.withReferenceToEntity(
				BRAND_PRODUCTS, PRODUCT, Cardinality.ZERO_OR_MORE,
				reference -> reference.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.REFERENCED_ENTITY)
					.withAttribute(PREDECESSOR, Predecessor.class, attribute -> attribute.sortable().nullable())
			)
			.updateVia(session);
		session.defineEntitySchema(PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				BRANDS, BRAND, Cardinality.ZERO_OR_MORE,
				reference -> reference.withGroupTypeRelatedToEntity(BRAND_GROUP)
					.indexedForFiltering()
					.indexedWithComponents(
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.withAttribute(RANK, Integer.class, attribute -> attribute.filterable().sortable())
					.withAttribute(LANE, Integer.class, AttributeSchemaEditor::sortable)
					.withSortableAttributeCompound(
						LANE_AND_RANK,
						new AttributeElement(LANE, ASC, NULLS_LAST),
						new AttributeElement(RANK, ASC, NULLS_LAST)
					)
			)
			.withReflectedReferenceToEntity(
				CHAINED_BRANDS, BRAND, BRAND_PRODUCTS, reference -> reference.withAttributesInherited()
			)
			.withReferenceToEntity(
				BRANDS_IN_ALL_SCOPES, BRAND, Cardinality.ZERO_OR_MORE,
				reference -> reference.withGroupTypeRelatedToEntity(BRAND_GROUP)
					.indexedForFilteringInScope(Scope.values())
					.indexedWithComponents(
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.indexedWithComponentsInScope(
						ARCHIVED,
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.withAttribute(
						RANK, Integer.class, attribute -> attribute
							.filterableInScope(Scope.values())
							.sortableInScope(Scope.values())
					)
			)
			.withReferenceToEntity(
				PARTLY_RANKED_BRANDS, BRAND, Cardinality.ZERO_OR_MORE,
				reference -> reference.indexedForFilteringAndPartitioning()
					.withAttribute(RANK, Integer.class, attribute -> attribute.sortable().nullable())
					.withAttribute(LANE, Integer.class, attribute -> attribute.sortable().nullable())
					.withAttribute(PREDECESSOR, Predecessor.class, AttributeSchemaEditor::sortable)
					.withSortableAttributeCompound(
						LANE_AND_RANK,
						new AttributeElement(LANE, ASC, NULLS_LAST),
						new AttributeElement(RANK, ASC, NULLS_LAST)
					)
			)
			.updateVia(session);

		session.createNewEntity(BRAND, 1).setAttribute(NAME, "Alpha").setAttribute(CODE, "A").upsertVia(session);
		session.createNewEntity(BRAND, 2).setAttribute(NAME, "Zulu").upsertVia(session);
		session.createNewEntity(BRAND, 3).setAttribute(NAME, "Bravo").upsertVia(session);
		session.createNewEntity(BRAND_GROUP, 10).setAttribute(NAME, "Alpha").setAttribute(CODE, "A")
			.upsertVia(session);
		session.createNewEntity(BRAND_GROUP, 11).setAttribute(NAME, "Zulu").upsertVia(session);

		for (final ProductData product : PRODUCTS) {
			createProduct(session, product);
		}
	}

	/**
	 * Creates the product with the plain reference rows used by the ordering tests.
	 *
	 * @param session writer of the owner and its reference rows
	 * @param product owner and its rank values and reference insertion order
	 */
	private static void createProduct(EvitaSessionContract session, ProductData product) {
		final EntityBuilder builder = session.createNewEntity(PRODUCT, product.primaryKey());
		for (final int brand : product.insertion()) {
			final int group = brand == 3 ? 10 : 11;
			final int rank = product.ranks().get(brand - 1);
			builder.setReference(
				BRANDS, brand, reference -> reference.setGroup(group)
					.setAttribute(RANK, rank)
					.setAttribute(LANE, brand == 1 ? 2 : 1)
			);
			builder.setReference(
				BRANDS_IN_ALL_SCOPES, brand, reference -> reference.setGroup(brand == 1 ? 11 : 10)
					.setAttribute(RANK, rank)
			);
		}
		if (product.scope() == LIVE) {
			final Predecessor outsideBrandTwo = product.primaryKey() == 2 ? Predecessor.HEAD : new Predecessor(2);
			final Predecessor onBrandTwo = product.primaryKey() == 1 ? Predecessor.HEAD : new Predecessor(1);
			builder.setReference(
				PARTLY_RANKED_BRANDS, 1,
				reference -> reference.setAttribute(LANE, 1).setAttribute(PREDECESSOR, outsideBrandTwo)
			);
			builder.setReference(
				PARTLY_RANKED_BRANDS, 2, reference -> reference.setAttribute(PREDECESSOR, onBrandTwo)
			);
			builder.setReference(
				PARTLY_RANKED_BRANDS, 3,
				reference -> reference.setAttribute(LANE, 1).setAttribute(RANK, 1)
					.setAttribute(PREDECESSOR, outsideBrandTwo)
			);
		}
		builder.upsertVia(session);
	}

	/**
	 * Writes the brand-side references that supply the live owner's reflected chain.
	 *
	 * @param session writer of the brands in the chain partition
	 * @param product live owner and the rows to add to its chain partition
	 */
	private static void installLiveChain(EvitaSessionContract session, ProductData product) {
		for (final int brand : product.insertion()) {
			final int position = product.chain().indexOf(brand);
			final Predecessor predecessor = position == 0
				? Predecessor.HEAD
				: new Predecessor(product.chain().get(position - 1));
			session.getEntity(BRAND, brand, referenceContentWithAttributes(BRAND_PRODUCTS))
				.orElseThrow().openForWrite()
				.setReference(
					BRAND_PRODUCTS, product.primaryKey(), reference -> reference.setAttribute(PREDECESSOR, predecessor)
				)
				.upsertVia(session);
		}
	}

	/**
	 * Writes the archived owner's rows and chain directly, after the move has disabled their live index.
	 *
	 * @param session writer that can update archived owners by primary key
	 * @param product archived owner and the reference rows to store
	 */
	private static void installArchivedChain(EvitaSessionContract session, ProductData product) {
		final EntityBuilder builder = session.getEntity(
			PRODUCT, product.primaryKey(), BOTH_SCOPES, referenceContentWithAttributes(CHAINED_BRANDS)
		).orElseThrow().openForWrite();
		for (final int brand : product.insertion()) {
			final int position = product.chain().indexOf(brand);
			final ReferencedEntityPredecessor predecessor = position == 0
				? ReferencedEntityPredecessor.HEAD
				: new ReferencedEntityPredecessor(product.chain().get(position - 1));
			builder.setReference(
				CHAINED_BRANDS, brand, reference -> reference.setAttribute(PREDECESSOR, predecessor)
			);
		}
		builder.upsertVia(session);
	}

	/**
	 * Fetches owners using the supplied reference requirement and query scopes.
	 *
	 * @param session reader of the shared fixture
	 * @param requirement reference content, including its comparator and any inner page
	 * @param scopes owner scopes to include in the query
	 * @return products whose reference order will be inspected
	 */
	private static List<SealedEntity> queryOwners(
		EvitaSessionContract session, ReferenceContent requirement, Scope... scopes
	) {
		return session.queryListOfSealedEntities(
			query(collection(PRODUCT), filterBy(scope(scopes)), require(entityFetch(requirement)))
		);
	}

	/**
	 * Captures the iteration order of the fetched references without sorting them.
	 *
	 * @param owners products returned by the query
	 * @param referenceName reference to inspect on every product
	 * @return referenced keys by owner primary key
	 */
	private static Map<Integer, List<Integer>> referenceOrders(List<SealedEntity> owners, String referenceName) {
		return owners.stream().collect(
			Collectors.toMap(SealedEntity::getPrimaryKeyOrThrowException, owner -> referencedKeys(owner, referenceName))
		);
	}

	/**
	 * Reads the referenced keys exactly as the owner exposes them.
	 *
	 * @param owner product whose references were fetched
	 * @param referenceName reference carrying the ordering under test
	 * @return referenced keys in the returned iteration order
	 */
	private static List<Integer> referencedKeys(SealedEntity owner, String referenceName) {
		return owner.getReferences(referenceName).stream().map(ReferenceContract::getReferencedPrimaryKey).toList();
	}

	/**
	 * Builds a reference requirement that either returns all rows or pages them while fetching their brand bodies.
	 *
	 * @param referenceName reference ordered by the query
	 * @param ordering comparator chain as supplied to the query
	 * @param shape whether to request a page and referenced entity bodies
	 * @return requirement exercising the chosen reference fetch path
	 */
	private static ReferenceContent orderedReferences(String referenceName, OrderBy ordering, FetchShape shape) {
		return shape == FetchShape.PAGED
			? referenceContent(referenceName, ordering, entityFetch(attributeContent(NAME)), page(1, 2))
			: referenceContent(referenceName, ordering);
	}

	/**
	 * Checks the complete reference list or its first page for each product returned by a query.
	 *
	 * @param evita engine hosting both owner scopes
	 * @param referenceName reference ordered by the query
	 * @param ordering comparator chain as supplied to the query
	 * @param shape whether to request a page and referenced entity bodies
	 * @param expected full reference orders keyed by product
	 * @param scopes owner scopes requested by the query
	 */
	private static void assertQueryOrders(
		Evita evita, String referenceName, OrderBy ordering, FetchShape shape,
		Map<Integer, List<Integer>> expected, Scope... scopes
	) {
		final boolean paged = shape == FetchShape.PAGED;
		final ReferenceContent requirement = orderedReferences(referenceName, ordering, shape);
		final List<SealedEntity> owners = evita.queryCatalog(
			TEST_CATALOG, (EvitaSessionContract session) -> queryOwners(session, requirement, scopes)
		);
		final Map<Integer, List<Integer>> actual = referenceOrders(owners, referenceName);
		final String context = shape + " " + referenceName + " " + ordering + " over " + Arrays.toString(scopes);
		assertEquals(expected.keySet(), actual.keySet(), context + " must return the expected products");
		final Map<Integer, Scope> ownerScopes = owners.stream()
			.collect(Collectors.toMap(SealedEntity::getPrimaryKeyOrThrowException, SealedEntity::getScope));
		final Stream<Executable> orderChecks = PRODUCTS.stream()
			.filter(product -> expected.containsKey(product.primaryKey()))
			.map(product -> () -> {
				final int primaryKey = product.primaryKey();
				final String ownerContext = "Product " + primaryKey + " (" + product.scope() + "), " + context;
				final List<Integer> fullOrder = expected.get(primaryKey);
				final List<Integer> keys = paged ? fullOrder.subList(0, 2) : fullOrder;
				assertAll(
					() -> assertEquals(product.scope(), ownerScopes.get(primaryKey), ownerContext + " owner scope"),
					() -> assertEquals(keys, actual.get(primaryKey), ownerContext + " referenced primary keys")
				);
			});
		final Stream<Executable> bodyChecks = paged
			? owners.stream().flatMap(owner -> owner.getReferences(referenceName).stream().map(reference -> () ->
				assertTrue(
					reference.getReferencedEntity().isPresent(),
					"Product " + owner.getPrimaryKeyOrThrowException() + " (" + owner.getScope() + "), " + context
						+ " must fetch brand " + reference.getReferencedPrimaryKey()
				)
			))
			: Stream.empty();
		assertAll(context, Stream.concat(orderChecks, bodyChecks));
	}

	/**
	 * Applies one comparator to live owners and expects archived owners to retain the default reference order.
	 *
	 * @param evita engine hosting both owner scopes
	 * @param shape whether to include reference pagination and body fetching
	 * @param referenceName reference carrying the scoped comparator
	 * @param comparator ordering whose applicability is limited to LIVE
	 * @param productOne full reference order of live product 1
	 * @param productTwo full reference order of live product 2
	 */
	private static void assertLiveScopedOrder(
		Evita evita, FetchShape shape, String referenceName, OrderConstraint comparator,
		List<Integer> productOne, List<Integer> productTwo
	) {
		assertQueryOrders(
			evita, referenceName, orderBy(inScope(LIVE, comparator)), shape,
			Map.of(
				1, productOne, 2, productTwo, 3, DEFAULT_ORDER, 4, DEFAULT_ORDER, 5, DEFAULT_ORDER, 6, DEFAULT_ORDER
			),
			BOTH_SCOPES
		);
	}

	/**
	 * Verifies the result shared by chains that delegate from a live comparator to ascending rank.
	 *
	 * @param evita engine hosting both owner scopes
	 * @param shape whether to include reference pagination and body fetching
	 * @param fallback second comparator that should handle archived owners
	 */
	private static void assertRankFallback(Evita evita, FetchShape shape, OrderConstraint fallback) {
		assertQueryOrders(
			evita, BRANDS_IN_ALL_SCOPES, orderBy(inScope(LIVE, attributeNatural(RANK, DESC)), fallback), shape,
			Map.of(
				1, DESCENDING_KEYS, 2, List.of(2, 1, 3),
				3, List.of(1, 3, 2), 4, List.of(2, 3, 1), 5, DESCENDING_KEYS, 6, List.of(2, 1, 3)
			),
			BOTH_SCOPES
		);
	}

	/**
	 * Supplies the unwrapped comparators with the expected reference order for each live owner.
	 *
	 * @return independently named controls for the live-only query
	 */
	private static Stream<Arguments> liveOrders() {
		return Stream.of(
			Arguments.of("Rank DESC", BRANDS, attributeNatural(RANK, DESC), DESCENDING_KEYS, List.of(2, 1, 3)),
			Arguments.of("Rank ASC", BRANDS, attributeNatural(RANK, ASC), DEFAULT_ORDER, List.of(3, 1, 2)),
			Arguments.of(
				"Referenced key DESC", BRANDS, entityPrimaryKeyNatural(DESC), DESCENDING_KEYS, DESCENDING_KEYS
			),
			Arguments.of(
				"Exact referenced keys", BRANDS, entityPrimaryKeyExact(2, 3, 1), List.of(2, 3, 1), List.of(2, 3, 1)
			),
			Arguments.of(
				"Brand name", BRANDS, entityProperty(attributeNatural(NAME, ASC)), List.of(1, 3, 2), List.of(1, 3, 2)
			),
			Arguments.of(
				"Group name", BRANDS, entityGroupProperty(attributeNatural(NAME, ASC)),
				List.of(3, 1, 2), List.of(3, 1, 2)
			),
			Arguments.of(
				"Lane and rank", BRANDS, attributeNatural(LANE_AND_RANK, ASC), List.of(2, 3, 1), DESCENDING_KEYS
			),
			Arguments.of(
				"Predecessor chain", CHAINED_BRANDS, attributeNatural(PREDECESSOR, ASC),
				List.of(2, 1, 3), List.of(1, 3, 2)
			)
		);
	}

	/**
	 * Checks that the direct fetch returned the requested owner and the correct reference order.
	 *
	 * @param owner fetched or enriched product
	 * @param product requested primary key
	 * @param ownerScope scope in which the fixture placed the product
	 * @param expected complete order expected from the live-scoped descending rank comparator
	 */
	private static void assertDirectOrder(SealedEntity owner, int product, Scope ownerScope, List<Integer> expected) {
		final String context = "Product " + product + " (" + ownerScope + "), inScope(LIVE, rank DESC)";
		assertAll(
			() -> assertEquals(product, owner.getPrimaryKeyOrThrowException(), context + " owner primary key"),
			() -> assertEquals(ownerScope, owner.getScope(), context + " owner scope"),
			() -> assertEquals(expected, referencedKeys(owner, BRANDS), context + " referenced primary keys")
		);
	}

	/**
	 * Checks the validation message for an ordering that needs the missing archived reference index.
	 *
	 * @param evita engine hosting the live-only reference index
	 * @param ordering ordering that requires the archived index
	 */
	private static void assertOrderingRefusal(Evita evita, OrderBy ordering) {
		assertRefusal(
			evita, referenceContent(BRANDS, ordering),
			"LIVE and ARCHIVED products ordered by " + ordering + " on " + BRANDS,
			List.of("not indexed in scope `ARCHIVED`", "cannot be ordered by"), List.of("filtered"), BOTH_SCOPES
		);
	}

	/**
	 * Checks that the query refuses the reference requirement and that the message names what it must.
	 *
	 * @param evita engine hosting the fixture
	 * @param requirement reference requirement whose planning must fail
	 * @param context description of the query for the assertion messages
	 * @param expectedFragments text the message must contain
	 * @param absentFragments text the message must not contain
	 * @param scopes owner scopes requested by the query
	 */
	private static void assertRefusal(
		Evita evita, ReferenceContent requirement, String context,
		List<String> expectedFragments, List<String> absentFragments, Scope... scopes
	) {
		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class,
			() -> evita.queryCatalog(
				TEST_CATALOG, (EvitaSessionContract session) -> queryOwners(session, requirement, scopes)
			),
			context + " must be refused"
		);
		final String message = exception.getMessage();
		final String diagnostic = context + " reported " + exception.getClass().getName() + ": " + message;
		assertAll(Stream.concat(
			expectedFragments.stream().map(fragment -> () -> assertTrue(message.contains(fragment), diagnostic)),
			absentFragments.stream().map(fragment -> () -> assertFalse(message.contains(fragment), diagnostic))
		));
	}

	/**
	 * Chooses which reference sorting paths a fetch request can exercise.
	 */
	private enum FetchShape {
		/** Reaches the post-fetch decorator sort only. */
		UNPAGED,
		/** Requests referenced bodies and an inner page to also reach the pre-fetch sorted slice when supported. */
		PAGED
	}

	/**
	 * Supplies the same brand rows to each of a product's reference sets.
	 *
	 * @param primaryKey product primary key
	 * @param scope final owner scope at test time
	 * @param ranks rank values in brand primary-key order
	 * @param insertion sequence in which the brand rows are written
	 * @param chain brand sequence encoded by the predecessor links
	 */
	private record ProductData(
		int primaryKey, Scope scope, List<Integer> ranks, List<Integer> insertion, List<Integer> chain
	) {
	}

	/**
	 * Orders the plain reference by its live-only `rank` attribute under `inScope(LIVE, ...)`.
	 */
	@Nested
	@DisplayName("Rank ordering confined to live owners")
	class ScopedRank {

		/**
		 * Verifies descending live ranks and default archival order, including the reverse-inserted product 5.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting products in both scopes
		 */
		@DisplayName(
			"Should order only the live owners' references by rank descending " +
				"and keep the archived owners' default order"
		)
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldOrderOnlyLiveOwnersByRankDescending(FetchShape shape, Evita evita) {
			assertLiveScopedOrder(
				evita, shape, BRANDS, attributeNatural(RANK, DESC), DESCENDING_KEYS, List.of(2, 1, 3)
			);
		}

		/**
		 * Verifies ascending live ranks and default archival order, including the reverse-inserted product 5.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting products in both scopes
		 */
		@DisplayName(
			"Should order only the live owners' references by rank ascending " +
				"and keep the archived owners' default order"
		)
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldOrderOnlyLiveOwnersByRankAscending(FetchShape shape, Evita evita) {
			assertLiveScopedOrder(evita, shape, BRANDS, attributeNatural(RANK, ASC), DEFAULT_ORDER, List.of(3, 1, 2));
		}

		/**
		 * Verifies that an ordering scoped to live owners is refused in a query that does not request the live scope.
		 *
		 * @param shape whether the reference requirement includes bodies and an inner page
		 * @param evita engine hosting products in both scopes
		 */
		@DisplayName("Should refuse the live-scoped rank ordering when the query requests only archived owners")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldRefuseLiveScopedRankWhenQueryRequestsOnlyArchivedOwners(FetchShape shape, Evita evita) {
			final OrderBy ordering = orderBy(inScope(LIVE, attributeNatural(RANK, DESC)));
			assertRefusal(
				evita, orderedReferences(BRANDS, ordering, shape),
				shape + " " + BRANDS + " " + ordering + " over ARCHIVED",
				List.of("Scope `LIVE` used in `inScope` order container was not requested"), List.of(), ARCHIVED
			);
		}

		/**
		 * Verifies that an ordering scoped to archived owners orders every owner of a query requesting only them.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting a rank index in both scopes
		 */
		@DisplayName("Should order every archived owner by the archived-scoped rank when the query requests only them")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldOrderEveryArchivedOwnerWhenQueryRequestsOnlyArchivedOwners(FetchShape shape, Evita evita) {
			assertQueryOrders(
				evita, BRANDS_IN_ALL_SCOPES, orderBy(inScope(ARCHIVED, attributeNatural(RANK, ASC))), shape,
				Map.of(3, List.of(1, 3, 2), 4, List.of(2, 3, 1), 5, DESCENDING_KEYS, 6, List.of(2, 1, 3)),
				ARCHIVED
			);
		}
	}

	/**
	 * Orders the reference indexed in both scopes by a live-scoped comparator followed by another one, which must
	 * receive the archived owners' references.
	 */
	@Nested
	@DisplayName("Reference comparator fallback")
	class ComparatorFallback {

		/**
		 * Verifies that the archived comparator receives the owners skipped by the live comparator.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting a rank index in both scopes
		 */
		@DisplayName("Should order the live owners by rank descending and the archived owners by scoped rank ascending")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldOrderLiveOwnersByRankDescendingAndArchivedOwnersByScopedRankAscending(
			FetchShape shape, Evita evita
		) {
			assertRankFallback(evita, shape, inScope(ARCHIVED, attributeNatural(RANK, ASC)));
		}

		/**
		 * Verifies that an unwrapped comparator can receive the owners skipped by a scoped comparator.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting a rank index in both scopes
		 */
		@DisplayName(
			"Should order the live owners by rank descending and the archived owners by unwrapped rank ascending"
		)
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldOrderLiveOwnersByRankDescendingAndArchivedOwnersByUnwrappedRankAscending(
			FetchShape shape, Evita evita
		) {
			assertRankFallback(evita, shape, attributeNatural(RANK, ASC));
		}
	}

	/**
	 * Follows comparator chains with successors that order rows skipped by their predecessors.
	 */
	@Nested
	@DisplayName("Reference comparator chain composition")
	class ComparatorChainComposition {

		/**
		 * Checks that a third comparator leaves the middle comparator available for keys absent from the exact list.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting live owners with sortable reference ranks
		 */
		@DisplayName("Should use the middle comparator for keys missing from the exact order")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldUseMiddleComparatorWhenKeysAreMissingFromExactOrder(FetchShape shape, Evita evita) {
			assertQueryOrders(
				evita, BRANDS,
				orderBy(entityPrimaryKeyExact(3), attributeNatural(RANK, DESC), entityPrimaryKeyNatural(ASC)),
				shape, Map.of(1, DESCENDING_KEYS, 2, DESCENDING_KEYS), LIVE
			);
		}

		/**
		 * Checks that the natural key comparator hands archived owners to their scoped rank comparator.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting a rank index in both owner scopes
		 */
		@DisplayName("Should use rank for archived owners when the natural key comparator is scoped to live owners")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldUseRankWhenNaturalKeyHeadSkipsArchivedOwners(FetchShape shape, Evita evita) {
			assertQueryOrders(
				evita, BRANDS_IN_ALL_SCOPES,
				orderBy(inScope(LIVE, entityPrimaryKeyNatural(DESC)), inScope(ARCHIVED, attributeNatural(RANK, ASC))),
				shape, Map.of(
					1, DESCENDING_KEYS, 2, DESCENDING_KEYS,
					3, List.of(1, 3, 2), 4, List.of(2, 3, 1), 5, DESCENDING_KEYS, 6, List.of(2, 1, 3)
				),
				BOTH_SCOPES
			);
		}

		/**
		 * Checks that the compound comparator hands archived owners to descending key order.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting the live compound and archived owners
		 */
		@DisplayName("Should use descending keys for archived owners when the compound is scoped to live owners")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldUseDescendingKeysWhenCompoundHeadSkipsArchivedOwners(FetchShape shape, Evita evita) {
			assertQueryOrders(
				evita, BRANDS,
				orderBy(inScope(LIVE, attributeNatural(LANE_AND_RANK, ASC)), entityPrimaryKeyNatural(DESC)),
				shape, Map.of(
					1, List.of(2, 3, 1), 2, DESCENDING_KEYS,
					3, DESCENDING_KEYS, 4, DESCENDING_KEYS, 5, DESCENDING_KEYS, 6, DESCENDING_KEYS
				),
				BOTH_SCOPES
			);
		}

		/**
		 * Checks that rows whose brand has no `code` go to the next ordering instead of staying in key order.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting the live brands and their owners
		 */
		@DisplayName("Should order by rank the rows whose brand lacks the nested ordering value")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldUseNextComparatorWhenBrandLacksNestedOrderingValue(FetchShape shape, Evita evita) {
			assertQueryOrders(
				evita, BRANDS, orderBy(entityProperty(attributeNatural(CODE, ASC)), attributeNatural(RANK, DESC)),
				shape, Map.of(1, List.of(1, 3, 2), 2, List.of(1, 2, 3)), LIVE
			);
		}

		/**
		 * Checks that a nested ordering by the brand's primary key ranks every row, leaving none to the next ordering.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting the live brands and their owners
		 */
		@DisplayName("Should rank every row when the nested ordering is by the brand's primary key")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldRankEveryRowWhenNestedOrderingIsByPrimaryKey(FetchShape shape, Evita evita) {
			assertQueryOrders(
				evita, BRANDS, orderBy(entityProperty(entityPrimaryKeyNatural(ASC)), attributeNatural(RANK, DESC)),
				shape, Map.of(1, DEFAULT_ORDER, 2, DEFAULT_ORDER), LIVE
			);
		}

		/**
		 * Checks that rows whose group has no `code` go to the next ordering instead of staying in key order.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting the brand groups and their reference owners
		 */
		@DisplayName("Should order by rank the rows whose group lacks the nested ordering value")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldUseNextComparatorWhenGroupLacksNestedOrderingValue(FetchShape shape, Evita evita) {
			assertQueryOrders(
				evita, BRANDS, orderBy(entityGroupProperty(attributeNatural(CODE, ASC)), attributeNatural(RANK, DESC)),
				shape, Map.of(1, DESCENDING_KEYS, 2, DESCENDING_KEYS), LIVE
			);
		}

		/**
		 * Checks that the nested comparator is prepared when another comparator follows it.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting the live brands and their owners
		 */
		@DisplayName("Should order by brand name when the nested comparator is followed by rank")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldUseBrandNameWhenNestedComparatorIsFollowedByRank(FetchShape shape, Evita evita) {
			final List<Integer> brandNameOrder = List.of(1, 3, 2);
			assertQueryOrders(
				evita, BRANDS, orderBy(entityProperty(attributeNatural(NAME, ASC)), attributeNatural(RANK, ASC)),
				shape, Map.of(1, brandNameOrder, 2, brandNameOrder), LIVE
			);
		}
	}

	/**
	 * Exercises the shared nested comparator when its entity and group constraints specify owner scopes.
	 */
	@Nested
	@DisplayName("Owner scope of combined entity and group ordering")
	class NestedComparatorScopes {

		/**
		 * Checks that planning refuses entity and group orderings placed under different owner scopes.
		 *
		 * @param shape whether the reference requirement includes bodies and an inner page
		 * @param evita engine hosting products with grouped references in both scopes
		 */
		@DisplayName("Should refuse entity and group orderings under different owner scopes")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldRefuseNestedOrderingsWhenOwnerScopesDiffer(FetchShape shape, Evita evita) {
			final OrderBy ordering = orderBy(
				inScope(LIVE, entityGroupProperty(attributeNatural(NAME, ASC))),
				inScope(ARCHIVED, entityProperty(attributeNatural(NAME, ASC)))
			);
			assertRefusal(
				evita, orderedReferences(BRANDS_IN_ALL_SCOPES, ordering, shape),
				shape + " " + BRANDS_IN_ALL_SCOPES + " " + ordering + " over LIVE and ARCHIVED",
				List.of("entityProperty", "entityGroupProperty", "same `inScope` container"), List.of(), BOTH_SCOPES
			);
		}

		/**
		 * Checks that one shared scope orders live references by group and then by brand within the group.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting products with grouped references in both scopes
		 */
		@DisplayName("Should order only live owners by group and then brand name under one owner scope")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldUseGroupThenBrandNameWhenNestedOrderingsShareOwnerScope(FetchShape shape, Evita evita) {
			assertQueryOrders(
				evita, BRANDS_IN_ALL_SCOPES,
				orderBy(inScope(LIVE,
					entityGroupProperty(attributeNatural(NAME, ASC)), entityProperty(attributeNatural(NAME, ASC))
				)),
				shape, Map.of(
					1, DESCENDING_KEYS, 2, DESCENDING_KEYS,
					3, DEFAULT_ORDER, 4, DEFAULT_ORDER, 5, DEFAULT_ORDER, 6, DEFAULT_ORDER
				),
				BOTH_SCOPES
			);
		}
	}

	/**
	 * Pins current ordering behavior for nullable compounds and plain predecessors.
	 */
	@Nested
	@DisplayName("Reference ordering with nullable compounds and plain predecessors")
	class OrderingPins {

		/**
		 * Checks the position of a brand whose compound has two null elements in each direction.
		 *
		 * @param direction whole-compound direction passed to the ordering
		 * @param evita engine hosting both live products with partially filled compounds
		 */
		@DisplayName("Should place the all-null compound according to the requested direction")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(OrderDirection.class)
		void shouldPlaceAllNullTupleWhenOrderingByCompound(OrderDirection direction, Evita evita) {
			// SortIndex compares tuple elements with NULLS_LAST, so the all-null tuple sorts last in ASC.
			// ReferenceCompoundAttributeReferenceComparator reverses the whole comparison for DESC, putting it first.
			final List<Integer> expected = direction == ASC ? List.of(3, 1, 2) : List.of(2, 1, 3);
			assertQueryOrders(
				evita, PARTLY_RANKED_BRANDS, orderBy(attributeNatural(LANE_AND_RANK, direction)),
				FetchShape.UNPAGED, Map.of(1, expected, 2, expected), LIVE
			);
		}

		/**
		 * Checks that a product's references keep key order when each brand belongs to its own owner chain.
		 *
		 * @param evita engine hosting the two owners that make up the brand-specific chains
		 */
		@Test
		@DisplayName("Should use referenced keys when plain predecessors belong to different brand chains")
		@UseDataSet(DATA_SET)
		void shouldUseReferencedKeysWhenPlainPredecessorsBelongToDifferentChains(Evita evita) {
			final ReferenceContent requirement = referenceContent(
				PARTLY_RANKED_BRANDS, orderBy(attributeNatural(PREDECESSOR, ASC))
			);
			final List<SealedEntity> owners = evita.queryCatalog(
				TEST_CATALOG,
				(EvitaSessionContract session) -> session.queryListOfSealedEntities(
					query(
						collection(PRODUCT), filterBy(scope(LIVE), entityPrimaryKeyInSet(1)),
						require(entityFetch(requirement))
					)
				)
			);
			// Each Predecessor orders owners within one brand. Rows targeting different brands compare by key.
			assertEquals(
				Map.of(1, DEFAULT_ORDER), referenceOrders(owners, PARTLY_RANKED_BRANDS),
				"Product 1 (LIVE), partlyRankedBrands predecessor ASC referenced primary keys"
			);
		}
	}

	/**
	 * Wraps each of the remaining reference comparators in `inScope(LIVE, ...)`.
	 */
	@Nested
	@DisplayName("Other reference comparators confined to live owners")
	class ScopedComparators {

		/**
		 * Verifies that referenced keys descend only for live owners.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting products in both scopes
		 */
		@DisplayName(
			"Should order only the live owners' references by descending referenced key " +
				"and keep the archived owners' default order"
		)
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldOrderOnlyLiveOwnersByDescendingReferencedKey(FetchShape shape, Evita evita) {
			assertLiveScopedOrder(
				evita, shape, BRANDS, entityPrimaryKeyNatural(DESC), DESCENDING_KEYS, DESCENDING_KEYS
			);
		}

		/**
		 * Verifies that the exact referenced-key sequence is applied only to live owners.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting products in both scopes
		 */
		@DisplayName(
			"Should apply the exact referenced key order only to live owners " +
				"and keep the archived owners' default order"
		)
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldOrderOnlyLiveOwnersByExactReferencedKeys(FetchShape shape, Evita evita) {
			assertLiveScopedOrder(
				evita, shape, BRANDS, entityPrimaryKeyExact(2, 3, 1), List.of(2, 3, 1), List.of(2, 3, 1)
			);
		}

		/**
		 * Verifies that the live brands' names drive ordering only for live owners.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting the brands and their owners
		 */
		@DisplayName(
			"Should order only the live owners' references by brand name " +
				"and keep the archived owners' default order"
		)
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldOrderOnlyLiveOwnersByBrandName(FetchShape shape, Evita evita) {
			assertLiveScopedOrder(
				evita, shape, BRANDS, entityProperty(attributeNatural(NAME, ASC)), List.of(1, 3, 2), List.of(1, 3, 2)
			);
		}

		/**
		 * Verifies group-name ordering and the tie between brands 1 and 2 under the live owner scope.
		 *
		 * @param shape whether to page the group-ordered references and fetch their brand bodies
		 * @param evita engine hosting the brand groups and their reference owners
		 */
		@DisplayName(
			"Should order only the live owners' references by group name " +
				"and keep the archived owners' default order"
		)
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldOrderOnlyLiveOwnersByGroupName(FetchShape shape, Evita evita) {
			assertLiveScopedOrder(
				evita, shape, BRANDS, entityGroupProperty(attributeNatural(NAME, ASC)),
				List.of(3, 1, 2), List.of(3, 1, 2)
			);
		}

		/**
		 * Verifies that lane and its rank tie-breaker order references only for live owners.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting the compound reference attribute
		 */
		@DisplayName(
			"Should order only the live owners' references by lane and rank " +
				"and keep the archived owners' default order"
		)
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldOrderOnlyLiveOwnersByLaneAndRank(FetchShape shape, Evita evita) {
			assertLiveScopedOrder(
				evita, shape, BRANDS, attributeNatural(LANE_AND_RANK, ASC), List.of(2, 3, 1), DESCENDING_KEYS
			);
		}

		/**
		 * Verifies that each live owner follows its own chain in a query that also fetches archived owners.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting the live predecessor indexes
		 */
		@DisplayName(
			"Should order only the live owners' references by chain " +
				"and keep the archived owners' default order"
		)
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldOrderOnlyLiveOwnersByChain(FetchShape shape, Evita evita) {
			assertLiveScopedOrder(
				evita, shape, CHAINED_BRANDS, attributeNatural(PREDECESSOR, ASC), List.of(2, 1, 3), List.of(1, 3, 2)
			);
		}
	}

	/**
	 * Orders two live owners whose unranked rows target the same referenced keys, so that the rows handed on to the
	 * next comparator are tracked per owner rather than per referenced key.
	 */
	@Nested
	@DisplayName("Passing unranked rows to the next comparator for each owner")
	class UnrankedRowFallback {

		/**
		 * Checks that shared unranked brand keys reach the fallback independently for both owners.
		 *
		 * @param shape whether to exercise the sorted reference slice
		 * @param evita engine hosting the two live owners with the same unranked brand keys
		 */
		@DisplayName("Should sort the ranked brand first and pass both owners' unranked brands to the next comparator")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(FetchShape.class)
		void shouldPassUnrankedBrandsToTheNextComparatorForEveryOwner(FetchShape shape, Evita evita) {
			assertQueryOrders(
				evita, PARTLY_RANKED_BRANDS, orderBy(attributeNatural(RANK, ASC), entityPrimaryKeyNatural(DESC)),
				shape, Map.of(1, DESCENDING_KEYS, 2, DESCENDING_KEYS), LIVE
			);
		}
	}

	/**
	 * Applies every comparator unwrapped in a query over live owners only, which proves the fixture and the live
	 * expectations the scoped tests rely on.
	 */
	@Nested
	@DisplayName("Live-only reference ordering controls")
	class LiveOnlyControls {

		/**
		 * Checks each comparator against the fixture using the live indexes with no scope wrapper.
		 *
		 * @param label comparator description in the parameterized test result
		 * @param referenceName reference providing the data for the comparator
		 * @param comparator ordering to verify independently of the scope wrapper
		 * @param productOne expected full order for live product 1
		 * @param productTwo expected full order for live product 2
		 * @param evita engine hosting the live reference and entity indexes
		 */
		@DisplayName("Should apply the comparator when the query requests only live owners")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.functional.fetch.ReferenceContentOrderingInScopeFunctionalTest#liveOrders")
		void shouldApplyComparatorWhenQueryRequestsOnlyLiveOwners(
			String label, String referenceName, OrderConstraint comparator,
			List<Integer> productOne, List<Integer> productTwo, Evita evita
		) {
			assertQueryOrders(
				evita, referenceName, orderBy(comparator), FetchShape.UNPAGED,
				Map.of(1, productOne, 2, productTwo), LIVE
			);
		}
	}

	/**
	 * Proves that every owner, live or archived, carries its three chain rows, which the chain test expects the
	 * archived owners to return in the default order.
	 */
	@Nested
	@DisplayName("Chain fixture control")
	class ChainFixtureControl {

		/**
		 * Fetches the chain rows of all owners without an ordering.
		 *
		 * @param evita engine hosting products in both scopes
		 */
		@Test
		@DisplayName("Should return the chain rows of every owner in the default order when they are not ordered")
		@UseDataSet(DATA_SET)
		void shouldReturnChainRowsOfEveryOwnerWhenNotOrdered(Evita evita) {
			final List<SealedEntity> owners = evita.queryCatalog(
				TEST_CATALOG,
				(EvitaSessionContract session) -> queryOwners(session, referenceContent(CHAINED_BRANDS), BOTH_SCOPES)
			);
			final Map<Integer, List<Integer>> expected = Map.of(
				1, DEFAULT_ORDER, 2, DEFAULT_ORDER, 3, DEFAULT_ORDER,
				4, DEFAULT_ORDER, 5, DEFAULT_ORDER, 6, DEFAULT_ORDER
			);
			assertEquals(
				expected, referenceOrders(owners, CHAINED_BRANDS),
				"LIVE and ARCHIVED products must each return all three " + CHAINED_BRANDS + " rows in the default order"
			);
		}
	}

	/**
	 * Applies the live-scoped ordering through `getEntity` and `enrichEntity` instead of a query.
	 */
	@Nested
	@DisplayName("Direct entity reference ordering")
	class DirectEntityCalls {

		/**
		 * Checks both owners using the multi-scope getEntity overload.
		 *
		 * @param evita engine hosting the archived and live versions of the fixture owners
		 */
		@Test
		@DisplayName(
			"Should order only the live owner by rank descending in getEntity " +
				"and keep the archived owners' default order"
		)
		@UseDataSet(DATA_SET)
		void shouldOrderOnlyLiveOwnersByRankDescendingWhenFetchingDirectly(Evita evita) {
			final ReferenceContent requirement = referenceContent(
				BRANDS, orderBy(inScope(LIVE, attributeNatural(RANK, DESC)))
			);
			evita.queryCatalog(TEST_CATALOG, session -> {
				final SealedEntity live = session.getEntity(PRODUCT, 1, BOTH_SCOPES, requirement)
					.orElseThrow(() -> new AssertionError("Missing LIVE product 1 for inScope(LIVE, rank DESC)"));
				final SealedEntity archived = session.getEntity(PRODUCT, 3, BOTH_SCOPES, requirement)
					.orElseThrow(() -> new AssertionError("Missing ARCHIVED product 3 for inScope(LIVE, rank DESC)"));
				assertAll(
					() -> assertDirectOrder(live, 1, LIVE, DESCENDING_KEYS),
					() -> assertDirectOrder(archived, 3, ARCHIVED, DEFAULT_ORDER)
				);
				return null;
			});
		}

		/**
		 * Enriches a bare archived owner fetched through a query requesting both scopes.
		 *
		 * @param evita engine whose read session performs the initial fetch and enrichment
		 */
		@Test
		@DisplayName("Should keep default ordering when enriching an owner outside the ordering scope")
		@UseDataSet(DATA_SET)
		void shouldKeepDefaultOrderingWhenEnrichingOwnerOutsideOrderingScope(Evita evita) {
			final ReferenceContent requirement = referenceContent(
				BRANDS, orderBy(inScope(LIVE, attributeNatural(RANK, DESC)))
			);
			final SealedEntity enriched = evita.queryCatalog(TEST_CATALOG, session -> {
				final SealedEntity archived = session.queryOneSealedEntity(
					query(
						collection(PRODUCT),
						filterBy(scope(BOTH_SCOPES), entityPrimaryKeyInSet(3)),
						require(entityFetch())
					)
				).orElseThrow(
					() -> new AssertionError("Missing ARCHIVED product 3 for enrichment using inScope(LIVE, rank DESC)")
				);
				return session.enrichEntity(archived, requirement);
			});
			assertDirectOrder(enriched, 3, ARCHIVED, DEFAULT_ORDER);
		}

		/**
		 * Enriches a bare archived owner with an unwrapped rank ordering of `brands`, indexed in LIVE only. The
		 * enrichment selects no scope, so the ordering is checked in the live scope and orders the stored rows.
		 *
		 * @param evita engine whose read session performs the initial fetch and enrichment
		 */
		@Test
		@DisplayName("Should order an archived owner by rank when enriching it with an unwrapped ordering")
		@UseDataSet(DATA_SET)
		void shouldOrderArchivedOwnerByRankWhenEnrichingWithUnwrappedOrdering(Evita evita) {
			final ReferenceContent requirement = referenceContent(BRANDS, orderBy(attributeNatural(RANK, DESC)));
			final SealedEntity enriched = evita.queryCatalog(TEST_CATALOG, session -> {
				final SealedEntity archived = session.queryOneSealedEntity(
					query(
						collection(PRODUCT),
						filterBy(scope(BOTH_SCOPES), entityPrimaryKeyInSet(3)),
						require(entityFetch())
					)
				).orElseThrow(() -> new AssertionError("Missing ARCHIVED product 3 for enrichment using rank DESC"));
				return session.enrichEntity(archived, requirement);
			});
			// product 3 ranks the brands 1, 2 and 3 with 1, 3 and 2
			assertAll(
				() -> assertEquals(ARCHIVED, enriched.getScope(), "Product 3 owner scope"),
				() -> assertEquals(List.of(2, 3, 1), referencedKeys(enriched, BRANDS), "Product 3 rank DESC")
			);
		}
	}

	/**
	 * Requests an ordering the archived owners have no index for and inspects the refusal.
	 */
	@Nested
	@DisplayName("Reference ordering validation messages")
	class IndexRefusal {

		/**
		 * Verifies that an unwrapped comparator's missing index error names ordering.
		 *
		 * @param evita engine with the brands reference indexed in LIVE only
		 */
		@Test
		@DisplayName("Should describe ordering when an unwrapped comparator lacks the archived index")
		@UseDataSet(DATA_SET)
		void shouldDescribeOrderingWhenUnwrappedComparatorLacksArchivedIndex(Evita evita) {
			assertOrderingRefusal(evita, orderBy(attributeNatural(RANK, DESC)));
		}

		/**
		 * Verifies that a comparator scoped to ARCHIVED reports its missing ordering index.
		 *
		 * @param evita engine with the brands reference indexed in LIVE only
		 */
		@Test
		@DisplayName("Should describe ordering when the archived comparator lacks its index")
		@UseDataSet(DATA_SET)
		void shouldDescribeOrderingWhenArchivedComparatorLacksItsIndex(Evita evita) {
			assertOrderingRefusal(evita, orderBy(inScope(ARCHIVED, attributeNatural(RANK, DESC))));
		}
	}
}
