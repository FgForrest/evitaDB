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
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the checks the planner makes of nested constraints before it knows whether there is any data to evaluate them
 * over - the filter of the references `referenceContent` fetches, the filter of a nested query planned in a scope the
 * target entity type holds no entity of, and the ordering by a reference no entity holds a row of. Such a check must
 * refuse what the schema refuses, accept what the query evaluated over data accepts - the same nested scope, the same
 * entity type the nested filter is resolved against - and evaluate nothing.
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
	 * names. A query the evaluation accepts is accepted and returns what it returned before the check existed.
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

}
