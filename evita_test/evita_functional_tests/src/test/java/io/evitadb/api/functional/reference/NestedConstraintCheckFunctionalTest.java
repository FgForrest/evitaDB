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
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.exception.AttributeNotSortableException;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.IndexActivity;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the checks the planner makes of nested constraints before it knows whether there is any data to evaluate them
 * over - the filter of the references `referenceContent` fetches, the filter of a nested query planned in a scope the
 * target entity type holds no entity of, and the ordering by a reference no entity holds a row of - or, traversing a
 * tree, none of whose referenced entities sits in a tree. Such a check must refuse what the schema refuses, accept what
 * the query evaluated over data accepts - the same nested scope, the same entity type the nested filter is resolved
 * against - and evaluate nothing.
 *
 * ## The fixture
 *
 * `checkTag` is hierarchical (indexed in both scopes, live data only): tag 1 is a root, tag 2 its child. `checkBrand`
 * references the tags by `tags`, whose `weight` is filterable in the live scope only. `checkProduct` references the
 * brands by `brand` and - with no row at all - by `formerBrand`; both carry `priority`, sortable in the live scope
 * only. Every `code` is filterable in the live scope only; no `note` is filterable or sortable anywhere.
 *
 * | entity        | scope    | code | references                                   |
 * |---------------|----------|------|----------------------------------------------|
 * | tag 1         | LIVE     | t1   |                                              |
 * | tag 2         | LIVE     | t2   | parent tag 1                                 |
 * | brand 1       | LIVE     | x    | tags: 1                                      |
 * | brand 2       | LIVE     | y    | tags: 2                                      |
 * | brand 3       | ARCHIVED | x    | tags: 1                                      |
 * | product 1     | LIVE     |      | brand: 1 (priority 1), 2 (priority 2)        |
 * | product 2     | ARCHIVED |      | brand: 1 (priority 3), 3 (priority 4)        |
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Nested constraints checked while planning")
@ExtendWith(EvitaParameterResolver.class)
@Tag(ENGINE)
@Tag(FILTER)
@Tag(REFERENCE)
public class NestedConstraintCheckFunctionalTest {
	private static final String NESTED_CHECK = "nestedConstraintCheck";
	private static final String ENTITY_TAG = "checkTag";
	private static final String ENTITY_BRAND = "checkBrand";
	private static final String ENTITY_PRODUCT = "checkProduct";
	private static final String REF_TAGS = "tags";
	private static final String REF_BRAND = "brand";
	private static final String REF_FORMER_BRAND = "formerBrand";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_NOTE = "note";
	private static final String ATTRIBUTE_WEIGHT = "weight";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	private static final String ATTRIBUTE_MISSING = "missing";
	/**
	 * A product primary key no product has.
	 */
	private static final int NO_PRODUCT = 999;

	/**
	 * Builds the fixture described on the class.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = NESTED_CHECK, destroyAfterClass = true)
	void setUpNestedCheckDataSet(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TAG)
					.withoutGeneratedPrimaryKey()
					.withHierarchyIndexedInScope(Scope.values())
					.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE))
					.withAttribute(ATTRIBUTE_NOTE, String.class, AttributeSchemaEditor::nullable)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(ENTITY_TAG, 1).setAttribute(ATTRIBUTE_CODE, "t1"));
				session.upsertEntity(
					session.createNewEntity(ENTITY_TAG, 2).setParent(1).setAttribute(ATTRIBUTE_CODE, "t2")
				);
				session.defineEntitySchema(ENTITY_BRAND)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE))
					.withAttribute(ATTRIBUTE_NOTE, String.class, AttributeSchemaEditor::nullable)
					.withReferenceToEntity(
						REF_TAGS, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.values())
							.withAttribute(
								ATTRIBUTE_WEIGHT, Integer.class, thatIs -> thatIs.filterableInScope(Scope.LIVE)
							)
							.withAttribute(ATTRIBUTE_NOTE, String.class, AttributeSchemaEditor::nullable)
					)
					.updateVia(session);
				upsertBrand(session, 1, "x", 1);
				upsertBrand(session, 2, "y", 2);
				upsertBrand(session, 3, "x", 1);
				session.archiveEntity(ENTITY_BRAND, 3);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.values())
							.withAttribute(
								ATTRIBUTE_PRIORITY, Integer.class, thatIs -> thatIs.sortableInScope(Scope.LIVE)
							)
							.withAttribute(ATTRIBUTE_NOTE, String.class, AttributeSchemaEditor::nullable)
					)
					.withReferenceToEntity(
						REF_FORMER_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.values())
							.withAttribute(
								ATTRIBUTE_PRIORITY, Integer.class, thatIs -> thatIs.sortableInScope(Scope.LIVE)
							)
							.withAttribute(ATTRIBUTE_NOTE, String.class, AttributeSchemaEditor::nullable)
					)
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_PRIORITY, 1))
						.setReference(REF_BRAND, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_PRIORITY, 2))
				);
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(REF_BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_PRIORITY, 3))
						.setReference(REF_BRAND, 3, whichIs -> whichIs.setAttribute(ATTRIBUTE_PRIORITY, 4))
				);
				session.archiveEntity(ENTITY_PRODUCT, 2);
			}
		);
	}

	/**
	 * A nested filter is checked as the nested query evaluated over data plans it: in the scopes its `scope(...)`
	 * names, and against the entity type it targets rather than the entity type of the enclosing query. A query the
	 * evaluation accepts is accepted and returns what it returned before the check existed.
	 */
	@DisplayName("Nested filter resolved as the nested query resolves it")
	@Nested
	class NestedFilterResolution {

		@DisplayName("Should fetch references filtered by an entity filter restricted to the scope it is evaluable in")
		@UseDataSet(NESTED_CHECK)
		@Test
		void shouldFetchReferencesFilteredByEntityFilterRestrictedToScopeItIsEvaluableIn(Evita evita) {
			// `code` is filterable in the live scope only, and the nested query looks for the brands in the live
			// scope only - the archived brand 3 is not a candidate, whatever scope its owner lives in
			assertEquals(
				Map.of(1, List.of(1), 2, List.of(1)),
				fetchedBrands(
					evita,
					query(
						collection(ENTITY_PRODUCT),
						filterBy(scope(Scope.LIVE, Scope.ARCHIVED)),
						require(
							entityFetch(
								referenceContent(
									REF_BRAND,
									filterBy(entityHaving(and(scope(Scope.LIVE), attributeEquals(ATTRIBUTE_CODE, "x"))))
								)
							)
						)
					)
				)
			);
		}

		@DisplayName(
			"Should filter by an entity filter restricted to the scope it is evaluable in, over a scope the target " +
				"holds no entity of"
		)
		@UseDataSet(NESTED_CHECK)
		@Test
		void shouldFilterByEntityFilterRestrictedToScopeItIsEvaluableInOverScopeWithoutTarget(Evita evita) {
			// the tags hold no archived entity, so the nested query is checked in the archived scope as well - but the
			// nested filter names the live scope only, and `code` is filterable there
			assertEquals(
				List.of(1, 3),
				queriedPrimaryKeys(
					evita,
					query(
						collection(ENTITY_BRAND),
						filterBy(
							scope(Scope.LIVE, Scope.ARCHIVED),
							referenceHaving(
								REF_TAGS, entityHaving(and(scope(Scope.LIVE), attributeEquals(ATTRIBUTE_CODE, "t1")))
							)
						)
					)
				)
			);
		}

		@DisplayName("Should fetch references filtered by the hierarchy a reference of the referenced entity reaches")
		@UseDataSet(NESTED_CHECK)
		@Test
		void shouldFetchReferencesFilteredByHierarchyOfReferencedEntity(Evita evita) {
			// `hierarchyWithin(tags, ...)` names a reference of the brands, not of the products fetching them - tag 2
			// is a child of tag 1, so both live brands are within it
			assertEquals(
				Map.of(1, List.of(1, 2)),
				fetchedBrands(
					evita,
					query(
						collection(ENTITY_PRODUCT),
						filterBy(scope(Scope.LIVE)),
						require(
							entityFetch(
								referenceContent(
									REF_BRAND,
									filterBy(entityHaving(hierarchyWithin(REF_TAGS, entityPrimaryKeyInSet(1))))
								)
							)
						)
					)
				)
			);
		}

		@DisplayName("Should filter by the own hierarchy of a target over a scope the target holds no entity of")
		@UseDataSet(NESTED_CHECK)
		@Test
		void shouldFilterByOwnHierarchyOfTargetOverScopeWithoutTarget(Evita evita) {
			// the tags are hierarchical, the brands are not - the nested filter is resolved against the tags
			assertEquals(
				List.of(1, 2, 3),
				queriedPrimaryKeys(
					evita,
					query(
						collection(ENTITY_BRAND),
						filterBy(
							scope(Scope.LIVE, Scope.ARCHIVED),
							referenceHaving(REF_TAGS, entityHaving(hierarchyWithinRootSelf()))
						)
					)
				)
			);
		}

	}

	/**
	 * The filter of the fetched references is checked by a visitor that evaluates nothing - not even the reference
	 * constraints nested in its `entityHaving`, which look up the indexes of yet another entity type and plan a
	 * nested query over them when they are evaluated.
	 */
	@DisplayName("Nested reference constraint of the fetched references")
	@Nested
	class NestedReferenceConstraintOfFetchedReferences {

		/**
		 * Returns the rows of the nested reference constraints the schema refuses. Each row is a label, the filter of
		 * the fetched brands, the primary key of the queried product and the exception the query must fail with.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> refusedNestedReferenceConstraintRows() {
			return Stream.of(1, NO_PRODUCT)
				.flatMap(
					productPk -> Stream.of(
						Arguments.of(
							"entity filter of the tags of the brands of product " + productPk,
							entityHaving(
								referenceHaving(REF_TAGS, entityHaving(attributeEquals(ATTRIBUTE_NOTE, "anything")))
							),
							productPk, AttributeNotFilterableException.class
						),
						Arguments.of(
							"attribute filter of the tags of the brands of product " + productPk,
							entityHaving(referenceHaving(REF_TAGS, attributeEquals(ATTRIBUTE_NOTE, "anything"))),
							productPk, AttributeNotFilterableException.class
						)
					)
				);
		}

		@DisplayName("Should check a nested reference constraint without planning a nested query of its target")
		@UseDataSet(NESTED_CHECK)
		@Test
		void shouldCheckNestedReferenceConstraintWithoutPlanningNestedQueryOfItsTarget(Evita evita) {
			final IndexActivity tagIndexActivity = liveGlobalIndexActivity(evita, ENTITY_TAG);
			final FilterConstraint tagReference = referenceHaving(
				REF_TAGS, entityHaving(attributeEquals(ATTRIBUTE_CODE, "t1"))
			);

			// the premise: the same nested filter evaluated over data plans a nested query of the tags, which the
			// global index of the tags counts - so a zero below is not a counter that never moves
			final long beforeEvaluation = tagIndexActivity.getQueryCount();
			assertEquals(
				List.of(1),
				queriedPrimaryKeys(
					evita, query(collection(ENTITY_BRAND), filterBy(scope(Scope.LIVE), tagReference))
				)
			);
			assertTrue(
				tagIndexActivity.getQueryCount() > beforeEvaluation,
				"The evaluated filter did not plan a nested query of the tags, so the check below proves nothing"
			);

			// no product is returned, so nothing is fetched - whatever the query plans over the tags, the check did
			final long beforeCheck = tagIndexActivity.getQueryCount();
			assertEquals(
				Map.of(),
				fetchedBrands(
					evita,
					query(
						collection(ENTITY_PRODUCT),
						filterBy(entityPrimaryKeyInSet(NO_PRODUCT)),
						require(entityFetch(referenceContent(REF_BRAND, filterBy(entityHaving(tagReference)))))
					)
				)
			);
			assertEquals(
				0L, tagIndexActivity.getQueryCount() - beforeCheck,
				"The check of the fetched references planned a nested query of the tags"
			);
		}

		@DisplayName("Should fail the query whose nested reference constraint the schema refuses")
		@UseDataSet(NESTED_CHECK)
		@ParameterizedTest(name = "{0}")
		@MethodSource("refusedNestedReferenceConstraintRows")
		void shouldFailQueryWhoseNestedReferenceConstraintSchemaRefuses(
			@Nonnull String label,
			@Nonnull FilterConstraint brandFilter,
			int productPk,
			@Nonnull Class<? extends EvitaInvalidUsageException> expectedException,
			Evita evita
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException exception = assertThrowsExactly(
						expectedException,
						() -> session.queryList(
							query(
								collection(ENTITY_PRODUCT),
								filterBy(entityPrimaryKeyInSet(productPk)),
								require(entityFetch(referenceContent(REF_BRAND, filterBy(brandFilter))))
							),
							EntityClassifier.class
						)
					);
					assertTrue(
						exception.getMessage().contains("`" + ATTRIBUTE_NOTE + "`"),
						"the message `" + exception.getMessage() + "` must name `" + ATTRIBUTE_NOTE + "`"
					);
					return null;
				}
			);
		}

	}

	/**
	 * The default ordering of a reference to a non-hierarchical entity picks the first reference of each entity. An
	 * ordering by a reference no entity holds a row of sorts nothing, but its attribute is checked all the same.
	 */
	@DisplayName("Pick-first ordering by a reference without rows")
	@Nested
	@Tag(ORDER)
	class PickFirstOrderingWithoutRows {

		/**
		 * Returns the rows of the reference orderings the schema refuses. Each row is a label, the scope queried, the
		 * ordered reference, the ordered attribute and the exception the query must fail with. Every row is paired
		 * with the same ordering by {@link #REF_BRAND}, which has rows in both scopes and fails the same way.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> refusedReferenceOrderingRows() {
			return Stream.of(REF_FORMER_BRAND, REF_BRAND)
				.flatMap(
					referenceName -> Stream.of(
						Arguments.of(
							"missing attribute of " + referenceName + " in the live scope", Scope.LIVE, referenceName,
							ATTRIBUTE_MISSING, AttributeNotFoundException.class
						),
						Arguments.of(
							"missing attribute of " + referenceName + " in the archived scope", Scope.ARCHIVED,
							referenceName, ATTRIBUTE_MISSING, AttributeNotFoundException.class
						),
						Arguments.of(
							"attribute of " + referenceName + " sortable nowhere", Scope.LIVE, referenceName,
							ATTRIBUTE_NOTE, AttributeNotSortableException.class
						),
						Arguments.of(
							"attribute of " + referenceName + " sortable in the live scope only, in the archived scope",
							Scope.ARCHIVED, referenceName, ATTRIBUTE_PRIORITY, AttributeNotSortableException.class
						)
					)
				);
		}

		@DisplayName("Should fail the ordering whose attribute the schema refuses, with or without reference rows")
		@UseDataSet(NESTED_CHECK)
		@ParameterizedTest(name = "{0}")
		@MethodSource("refusedReferenceOrderingRows")
		void shouldFailOrderingWhoseAttributeSchemaRefuses(
			@Nonnull String label,
			@Nonnull Scope scope,
			@Nonnull String referenceName,
			@Nonnull String attributeName,
			@Nonnull Class<? extends EvitaInvalidUsageException> expectedException,
			Evita evita
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException exception = assertThrowsExactly(
						expectedException,
						() -> session.queryList(
							query(
								collection(ENTITY_PRODUCT),
								filterBy(scope(scope)),
								orderBy(referenceProperty(referenceName, attributeNatural(attributeName)))
							),
							EntityClassifier.class
						)
					);
					assertTrue(
						exception.getMessage().contains("`" + attributeName + "`"),
						"the message `" + exception.getMessage() + "` must name `" + attributeName + "`"
					);
					return null;
				}
			);
		}

		@DisplayName("Should accept the ordering by an evaluable attribute of a reference without rows")
		@UseDataSet(NESTED_CHECK)
		@Test
		void shouldAcceptOrderingByEvaluableAttributeOfReferenceWithoutRows(Evita evita) {
			assertEquals(
				List.of(1),
				queriedPrimaryKeys(
					evita,
					query(
						collection(ENTITY_PRODUCT),
						filterBy(scope(Scope.LIVE)),
						orderBy(referenceProperty(REF_FORMER_BRAND, attributeNatural(ATTRIBUTE_PRIORITY)))
					)
				)
			);
		}

		@DisplayName("Should prefetch nothing for the ordering by a reference without rows")
		@UseDataSet(NESTED_CHECK)
		@Test
		void shouldPrefetchNothingForOrderingByReferenceWithoutRows(Evita evita) {
			final Query unordered = query(collection(ENTITY_PRODUCT), filterBy(scope(Scope.LIVE)));
			final List<String> unorderedRequirements = plannedRequirementsToPrefetch(evita, unordered);
			// the ordering by the brands, which have rows, prefetches the sorted attribute of the brand references -
			// the reading below sees the requirements an ordering registers, so an equal reading is not vacuous
			final List<String> requirementsOfOrderingWithRows = plannedRequirementsToPrefetch(
				evita,
				query(
					collection(ENTITY_PRODUCT),
					filterBy(scope(Scope.LIVE)),
					orderBy(referenceProperty(REF_BRAND, attributeNatural(ATTRIBUTE_PRIORITY)))
				)
			);
			final List<String> addedByOrderingWithRows = new ArrayList<>(requirementsOfOrderingWithRows);
			addedByOrderingWithRows.removeAll(unorderedRequirements);
			assertEquals(
				List.of(referenceContentWithAttributes(REF_BRAND, ATTRIBUTE_PRIORITY).forPrefetch().toString()),
				addedByOrderingWithRows,
				"the ordering by the reference with rows must prefetch its sorted attribute"
			);

			// the ordering by the former brands, which no product holds a row of, sorts nothing - checking its
			// attribute must not widen what the query prefetches
			assertEquals(
				unorderedRequirements,
				plannedRequirementsToPrefetch(
					evita,
					query(
						collection(ENTITY_PRODUCT),
						filterBy(scope(Scope.LIVE)),
						orderBy(referenceProperty(REF_FORMER_BRAND, attributeNatural(ATTRIBUTE_PRIORITY)))
					)
				)
			);
		}

	}

	/**
	 * A `traverseByEntityProperty` ordering - the default of a reference to a hierarchical entity - sorts the owners
	 * block by block of the referenced entities they hold rows of, and only by those sitting in a tree of the processed
	 * scopes. An ordering with no block to sort - no owner holds a row of the reference, or none of the referenced
	 * entities sits in a tree - sorts nothing, but the attribute it orders the blocks by is checked all the same.
	 */
	@DisplayName("Traversal ordering by a reference without rows")
	@Nested
	@Tag(ORDER)
	class TraversalOrderingWithoutRows {

		/**
		 * Returns the rows of the traversal orderings the schema refuses. Each row is a label, the queried entity type,
		 * the scope queried, the ordered reference, the ordered attribute and the exception the query must fail with.
		 * The traversal of the brands is explicit - the brands are not hierarchical - and every row of
		 * {@link #REF_FORMER_BRAND}, which has no rows, is paired with the same ordering by {@link #REF_BRAND}, which
		 * has rows in both scopes. The tags the brands reference are hierarchical, so their traversal is the implicit
		 * default: in the live scope the rows of the brands sit in the tree of the tags, in the archived scope the
		 * archived brand holds a row of a tag, but the tags have no archived tree to sit in.
		 *
		 * @return the row arguments
		 */
		@Nonnull
		static Stream<Arguments> refusedTraversalOrderingRows() {
			final Stream<Arguments> brandRows = Stream.of(REF_FORMER_BRAND, REF_BRAND)
				.flatMap(
					referenceName -> Stream.of(
						Arguments.of(
							"missing attribute of " + referenceName + " in the live scope", ENTITY_PRODUCT, Scope.LIVE,
							referenceName, ATTRIBUTE_MISSING, AttributeNotFoundException.class
						),
						Arguments.of(
							"missing attribute of " + referenceName + " in the archived scope", ENTITY_PRODUCT,
							Scope.ARCHIVED, referenceName, ATTRIBUTE_MISSING, AttributeNotFoundException.class
						),
						Arguments.of(
							"attribute of " + referenceName + " sortable nowhere", ENTITY_PRODUCT, Scope.LIVE,
							referenceName, ATTRIBUTE_NOTE, AttributeNotSortableException.class
						),
						Arguments.of(
							"attribute of " + referenceName + " sortable in the live scope only, in the archived scope",
							ENTITY_PRODUCT, Scope.ARCHIVED, referenceName, ATTRIBUTE_PRIORITY,
							AttributeNotSortableException.class
						)
					)
				);
			final Stream<Arguments> tagRows = Stream.of(Scope.LIVE, Scope.ARCHIVED)
				.flatMap(
					scope -> Stream.of(
						Arguments.of(
							"missing attribute of " + REF_TAGS + " in the " + scope + " scope", ENTITY_BRAND, scope,
							REF_TAGS, ATTRIBUTE_MISSING, AttributeNotFoundException.class
						),
						Arguments.of(
							"attribute of " + REF_TAGS + " sortable nowhere, in the " + scope + " scope", ENTITY_BRAND,
							scope, REF_TAGS, ATTRIBUTE_NOTE, AttributeNotSortableException.class
						),
						Arguments.of(
							"attribute of " + REF_TAGS + " filterable only, in the " + scope + " scope", ENTITY_BRAND,
							scope, REF_TAGS, ATTRIBUTE_WEIGHT, AttributeNotSortableException.class
						)
					)
				);
			return Stream.concat(brandRows, tagRows);
		}

		@DisplayName("Should fail the traversal ordering whose attribute the schema refuses, with or without blocks")
		@UseDataSet(NESTED_CHECK)
		@ParameterizedTest(name = "{0}")
		@MethodSource("refusedTraversalOrderingRows")
		void shouldFailTraversalOrderingWhoseAttributeSchemaRefuses(
			@Nonnull String label,
			@Nonnull String entityType,
			@Nonnull Scope scope,
			@Nonnull String referenceName,
			@Nonnull String attributeName,
			@Nonnull Class<? extends EvitaInvalidUsageException> expectedException,
			Evita evita
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException exception = assertThrowsExactly(
						expectedException,
						() -> session.queryList(
							query(
								collection(entityType),
								filterBy(scope(scope)),
								orderBy(traversalOrdering(entityType, referenceName, attributeName))
							),
							EntityClassifier.class
						)
					);
					assertTrue(
						exception.getMessage().contains("`" + attributeName + "`"),
						"the message `" + exception.getMessage() + "` must name `" + attributeName + "`"
					);
					return null;
				}
			);
		}

		@DisplayName("Should accept the traversal ordering by an evaluable attribute of a reference without rows")
		@UseDataSet(NESTED_CHECK)
		@Test
		void shouldAcceptTraversalOrderingByEvaluableAttributeOfReferenceWithoutRows(Evita evita) {
			assertEquals(
				List.of(1),
				queriedPrimaryKeys(
					evita,
					query(
						collection(ENTITY_PRODUCT),
						filterBy(scope(Scope.LIVE)),
						orderBy(traversalOrdering(ENTITY_PRODUCT, REF_FORMER_BRAND, ATTRIBUTE_PRIORITY))
					)
				)
			);
		}

		/**
		 * Returns the traversal ordering by the attribute of the reference: the explicit traversal of the referenced
		 * entities by their primary key for the references of the products, whose brands are not hierarchical, and the
		 * implicit default for the references of the brands, whose tags are.
		 *
		 * @param entityType    the queried entity type
		 * @param referenceName the ordered reference
		 * @param attributeName the ordered attribute
		 * @return the ordering
		 */
		@Nonnull
		private static OrderConstraint traversalOrdering(
			@Nonnull String entityType,
			@Nonnull String referenceName,
			@Nonnull String attributeName
		) {
			return ENTITY_PRODUCT.equals(entityType) ?
				referenceProperty(
					referenceName,
					traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
					attributeNatural(attributeName)
				) :
				referenceProperty(referenceName, attributeNatural(attributeName));
		}

	}

	/**
	 * Upserts one brand with its code and one tag.
	 *
	 * @param session the session to write in
	 * @param brandPk the primary key of the brand
	 * @param code    the code of the brand
	 * @param tagPk   the tag the brand references
	 */
	private static void upsertBrand(
		@Nonnull EvitaSessionContract session,
		int brandPk,
		@Nonnull String code,
		int tagPk
	) {
		session.upsertEntity(
			session.createNewEntity(ENTITY_BRAND, brandPk)
				.setAttribute(ATTRIBUTE_CODE, code)
				.setReference(REF_TAGS, tagPk, whichIs -> whichIs.setAttribute(ATTRIBUTE_WEIGHT, tagPk))
		);
	}

	/**
	 * Runs the query and returns the primary keys of the entities it returns, in the order it returns them.
	 *
	 * @param evita the engine instance
	 * @param query the query to run
	 * @return the primary keys
	 */
	@Nonnull
	private static List<Integer> queriedPrimaryKeys(@Nonnull Evita evita, @Nonnull Query query) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryList(query, EntityClassifier.class)
					.stream()
					.map(EntityClassifier::getPrimaryKeyOrThrowException)
					.toList();
			}
		);
	}

	/**
	 * Plans the query in a planning context of its own, against the real product collection, and returns what the
	 * planned query prefetches - the requirements the translators of its constraints registered on the context.
	 *
	 * The session is the only mock: the planning context accepts the session class itself, while the engine hands its
	 * clients a proxy of it, and the planning of these queries touches the session only through paths that degrade
	 * gracefully without one.
	 *
	 * @param evita the engine instance
	 * @param query the query to plan
	 * @return the requirements to prefetch, as their textual form
	 */
	@Nonnull
	private static List<String> plannedRequirementsToPrefetch(@Nonnull Evita evita, @Nonnull Query query) {
		final QueryPlanningContext queryContext = ((Catalog) evita.getCatalogInstanceOrThrowException(TEST_CATALOG))
			.getCollectionForEntityInternal(ENTITY_PRODUCT)
			.orElseThrow()
			.createQueryContext(
				new EvitaRequest(query.normalizeQuery(), OffsetDateTime.now(), EntityReference.class, null),
				Mockito.mock(EvitaSession.class)
			);
		QueryPlanner.planQuery(queryContext);
		return Arrays.stream(queryContext.getRequirementsToPrefetch())
			.map(EntityContentRequire::toString)
			.toList();
	}

	/**
	 * Runs the query fetching the products with their references to the brands and returns the brands each returned
	 * product holds.
	 *
	 * @param evita the engine instance
	 * @param query the query to run
	 * @return the primary keys of the referenced brands, by the primary key of the product
	 */
	@Nonnull
	private static Map<Integer, List<Integer>> fetchedBrands(@Nonnull Evita evita, @Nonnull Query query) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Map<Integer, List<Integer>> result = new LinkedHashMap<>(4);
				for (final SealedEntity product : session.queryList(query, SealedEntity.class)) {
					result.put(
						product.getPrimaryKeyOrThrowException(),
						product.getReferences(REF_BRAND)
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.sorted()
							.toList()
					);
				}
				return result;
			}
		);
	}

	/**
	 * Reads the activity of the live global index of the entity type - it counts every plan built over the index,
	 * including the plan of a nested query.
	 *
	 * @param evita      the engine instance
	 * @param entityType the entity type
	 * @return the activity of the index
	 */
	@Nonnull
	private static IndexActivity liveGlobalIndexActivity(@Nonnull Evita evita, @Nonnull String entityType) {
		final EntityIndex index = ((Catalog) evita.getCatalogInstanceOrThrowException(TEST_CATALOG))
			.getCollectionForEntityInternal(entityType)
			.orElseThrow()
			.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE));
		assertNotNull(index, "The live global index of `" + entityType + "` does not exist");
		final IndexActivity activity = index.getActivity();
		assertNotNull(activity, "The live global index of `" + entityType + "` counts no activity");
		return activity;
	}

}
