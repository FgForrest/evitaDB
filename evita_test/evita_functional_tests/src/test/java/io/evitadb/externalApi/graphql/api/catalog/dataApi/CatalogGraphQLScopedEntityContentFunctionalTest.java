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

package io.evitadb.externalApi.graphql.api.catalog.dataApi;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Map;

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies that references and parents of entities can be fetched by a single GraphQL query spanning the `LIVE` and
 * `ARCHIVED` scopes, although the archived scope maintains none of the indexes the fetched content is declared with.
 *
 * Issue #1012 proposed an `inScope` container on entity objects, so that references and parents could be fetched
 * only for entities of a scope whose indexes they need. These tests document why the container is not needed:
 * fetching references and parents does not consult the indexes of the entity's scope. A reference filter by reference
 * attributes is evaluated row by row over the fetched references, the direct parent is read from the entity body and
 * the ancestors above it from the hierarchy index of the entity's scope, when it has one. Only the constraints nested
 * in the fetch that do read an index are refused - a reference ordering when the reference is not indexed in every
 * queried scope, an `entityHaving` searching the referenced entities in a scope that does not index the attribute,
 * a `stopAt` node filter by an attribute filterable in one scope. The filters are confined to the scope that indexes
 * them by an `inScope` container or by a `scope` named in the `entityHaving`; the ordering passes when wrapped in
 * an `inScope` container, which however does not confine it (see
 * `References#shouldAcceptOrderingReferencesWrappedInInScope`).
 *
 * The fixture keeps every index in the `LIVE` scope only (the default): the reference attribute `rank` is filterable
 * and sortable, the `brands` reference is indexed, the category hierarchy is indexed and the `code` attribute is
 * filterable there. Products 1 and 2 live, products 3 and 4 are archived; category chain 1 - 2 - 3 lives, leaf 12
 * of chain 10 - 11 - 12 is archived and the whole chain 20 - 21 - 22 is archived.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(REFERENCE)
@DisplayName("GraphQL fetch of references and parents across scopes")
public class CatalogGraphQLScopedEntityContentFunctionalTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final String DATA_SET = "GraphQLScopedEntityContent";
	private static final String PRODUCT_DATA_PATH = "data.queryProduct.recordPage.data";
	private static final String CATEGORY_DATA_PATH = "data.queryCategory.recordPage.data";
	private static final String REFERENCE_BRANDS = "brands";
	private static final String ATTRIBUTE_RANK = "rank";

	@DataSet(value = DATA_SET, openWebApi = GraphQLProvider.CODE)
	DataCarrier setUpScopedEntityContent(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.filterable())
					.updateVia(session);

				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withHierarchy()
					.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.filterable())
					.updateVia(session);

				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REFERENCE_BRANDS,
						Entities.BRAND,
						Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs
							.indexed()
							.withAttribute(ATTRIBUTE_RANK, Integer.class, it -> it.filterable().sortable())
					)
					.updateVia(session);

				for (int brandPk = 1; brandPk <= 3; brandPk++) {
					session.createNewEntity(Entities.BRAND, brandPk)
						.setAttribute(ATTRIBUTE_CODE, "brand-" + brandPk)
						.upsertVia(session);
				}

				createCategory(session, 1, null);
				createCategory(session, 2, 1);
				createCategory(session, 3, 2);
				createCategory(session, 10, null);
				createCategory(session, 11, 10);
				createCategory(session, 12, 11);
				session.archiveEntity(Entities.CATEGORY, 12);
				createCategory(session, 20, null);
				createCategory(session, 21, 20);
				createCategory(session, 22, 21);
				session.archiveEntity(Entities.CATEGORY, 22);
				session.archiveEntity(Entities.CATEGORY, 21);
				session.archiveEntity(Entities.CATEGORY, 20);

				createProduct(session, 1, 1, 2, 3);
				createProduct(session, 2, 1, 2, 3);
				// the archived products rank their brands differently, so that an ordering by their own ranks, by the
				// ranks of the live products and by the referenced primary key all give a different answer
				createProduct(session, 3, 2, 3, 1);
				createProduct(session, 4, 2, 3, 1);
				session.archiveEntity(Entities.PRODUCT, 3);
				session.archiveEntity(Entities.PRODUCT, 4);
			}
		);
		return new DataCarrier();
	}

	@Nested
	@DisplayName("References")
	class References {

		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Should filter references by an attribute filterable only in the live scope in both scopes")
		void shouldFilterReferencesOfEntitiesInBothScopes(GraphQLTester tester) {
			tester.test(TEST_CATALOG)
				.document("""
					{
						queryProduct(filterBy: {scope: [LIVE, ARCHIVED]}) {
							recordPage {
								data {
									primaryKey
									scope
									brands(filterBy: {attributeRankGreaterThan: 1}) {
										referencedPrimaryKey
									}
								}
							}
						}
					}
					""")
				.executeAndExpectOkAndThen()
				.body(ERRORS_PATH, nullValue())
				.body(
					PRODUCT_DATA_PATH,
					containsInAnyOrder(
						productDto(1, "LIVE", 2, 3),
						productDto(2, "LIVE", 2, 3),
						productDto(3, "ARCHIVED", 1, 2),
						productDto(4, "ARCHIVED", 1, 2)
					)
				);
		}

		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Should refuse filtering references by the referenced entity searched in every queried scope")
		void shouldRefuseEntityHavingSearchedInScopeWithoutIndex(GraphQLTester tester) {
			// the referenced entities are searched in every scope the query processes, and the brand `code` is
			// filterable only in the live scope - although no brand is archived
			tester.test(TEST_CATALOG)
				.document("""
					{
						queryProduct(filterBy: {scope: [LIVE, ARCHIVED]}) {
							recordPage {
								data {
									primaryKey
									brands(filterBy: {entityHaving: {attributeCodeEquals: "brand-2"}}) {
										referencedPrimaryKey
									}
								}
							}
						}
					}
					""")
				.executeAndThen()
				.statusCode(200)
				.body(ERRORS_PATH, hasSize(1))
				.body(ERRORS_PATH + "[0].message", containsString("in scope `ARCHIVED` and cannot be filtered by"));
		}

		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Should filter references by the referenced entity searched only in the scope named in it")
		void shouldFilterReferencesByReferencedEntitySearchedInNamedScope(GraphQLTester tester) {
			tester.test(TEST_CATALOG)
				.document("""
					{
						queryProduct(filterBy: {scope: [LIVE, ARCHIVED]}) {
							recordPage {
								data {
									primaryKey
									scope
									brands(filterBy: {entityHaving: {scope: [LIVE], attributeCodeEquals: "brand-2"}}) {
										referencedPrimaryKey
									}
								}
							}
						}
					}
					""")
				.executeAndExpectOkAndThen()
				.body(ERRORS_PATH, nullValue())
				.body(
					PRODUCT_DATA_PATH,
					containsInAnyOrder(
						productDto(1, "LIVE", 2),
						productDto(2, "LIVE", 2),
						productDto(3, "ARCHIVED", 2),
						productDto(4, "ARCHIVED", 2)
					)
				);
		}

		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Should filter references by the referenced entity only for owners in the scope of inScope")
		void shouldFilterReferencesByReferencedEntityWhenWrappedInInScope(GraphQLTester tester) {
			tester.test(TEST_CATALOG)
				.document("""
					{
						queryProduct(filterBy: {scope: [LIVE, ARCHIVED]}) {
							recordPage {
								data {
									primaryKey
									scope
									brands(
										filterBy: {
											inScope: {
												scope: LIVE,
												filtering: {entityHaving: {attributeCodeEquals: "brand-2"}}
											}
										}
									) {
										referencedPrimaryKey
									}
								}
							}
						}
					}
					""")
				.executeAndExpectOkAndThen()
				.body(ERRORS_PATH, nullValue())
				.body(
					PRODUCT_DATA_PATH,
					containsInAnyOrder(
						productDto(1, "LIVE", 2),
						productDto(2, "LIVE", 2),
						productDto(3, "ARCHIVED", 1, 2, 3),
						productDto(4, "ARCHIVED", 1, 2, 3)
					)
				);
		}

		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Should refuse ordering references that are not indexed in the archived scope")
		void shouldRefuseOrderingReferencesNotIndexedInArchivedScope(GraphQLTester tester) {
			tester.test(TEST_CATALOG)
				.document("""
					{
						queryProduct(filterBy: {scope: [LIVE, ARCHIVED]}) {
							recordPage {
								data {
									primaryKey
									brands(orderBy: {attributeRankNatural: DESC}) {
										referencedPrimaryKey
									}
								}
							}
						}
					}
					""")
				.executeAndThen()
				.statusCode(200)
				.body(ERRORS_PATH, hasSize(1))
				.body(ERRORS_PATH + "[0].message", containsString("not indexed in scope `ARCHIVED`"));
		}

		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Should accept ordering references wrapped in inScope and order the live owners' references")
		void shouldAcceptOrderingReferencesWrappedInInScope(GraphQLTester tester) {
			tester.test(TEST_CATALOG)
				.document("""
					{
						queryProduct(filterBy: {scope: [LIVE, ARCHIVED]}) {
							recordPage {
								data {
									primaryKey
									scope
									brands(orderBy: {inScope: {scope: LIVE, ordering: {attributeRankNatural: DESC}}}) {
										referencedPrimaryKey
									}
								}
							}
						}
					}
					""")
				.executeAndExpectOkAndThen()
				.body(ERRORS_PATH, nullValue())
				.body(brandsOfProductPath(1), contains(3, 2, 1))
				.body(brandsOfProductPath(2), contains(3, 2, 1))
				// the order of the references of the archived owners is deliberately not pinned: the wrapped ordering
				// currently orders them by their own ranks too (2, 1, 3), so unlike a wrapped filter, which leaves
				// the references of owners in another scope alone, it does not confine anything
				.body(brandsOfProductPath(3), containsInAnyOrder(1, 2, 3))
				.body(brandsOfProductPath(4), containsInAnyOrder(1, 2, 3));
		}
	}

	@Nested
	@Tag(HIERARCHY)
	@DisplayName("Parents")
	class Parents {

		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Should fetch parents of entities in both scopes from the hierarchy index of their scope")
		void shouldFetchParentsOfEntitiesInBothScopes(GraphQLTester tester) {
			tester.test(TEST_CATALOG)
				.document("""
					{
						queryCategory(filterBy: {entityPrimaryKeyInSet: [3, 12, 22], scope: [LIVE, ARCHIVED]}) {
							recordPage {
								data {
									primaryKey
									scope
									parentPrimaryKey
									parents {
										primaryKey
									}
								}
							}
						}
					}
					""")
				.executeAndExpectOkAndThen()
				.body(ERRORS_PATH, nullValue())
				.body(
					CATEGORY_DATA_PATH,
					containsInAnyOrder(
						categoryDto(3, "LIVE", 2, 1, 2),
						// the direct parent is read from the body of the archived leaf, the ancestors above it from
						// the hierarchy index of its scope - and the archived scope holds none, so the chain ends there
						categoryDto(12, "ARCHIVED", 11, 11),
						categoryDto(22, "ARCHIVED", 21, 21)
					)
				);
		}

		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Should refuse a stopAt node filter by an attribute filterable only in the live scope")
		void shouldRefuseStopAtNodeFilterableInOneScope(GraphQLTester tester) {
			tester.test(TEST_CATALOG)
				.document("""
					{
						queryCategory(filterBy: {entityPrimaryKeyInSet: [3, 12, 22], scope: [LIVE, ARCHIVED]}) {
							recordPage {
								data {
									primaryKey
									parents(stopAt: {node: {filterBy: {attributeCodeEquals: "category-2"}}}) {
										primaryKey
									}
								}
							}
						}
					}
					""")
				.executeAndThen()
				.statusCode(200)
				.body(ERRORS_PATH, hasSize(1))
				.body(
					ERRORS_PATH + "[0].message",
					containsString("`code` in entity `CATEGORY` is not marked as")
				)
				.body(ERRORS_PATH + "[0].message", containsString("in scope `ARCHIVED` and cannot be filtered by"));
		}

		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Should stop at a node in the live scope when the node filter is confined by inScope")
		void shouldStopAtNodeWhenConfinedByInScope(GraphQLTester tester) {
			tester.test(TEST_CATALOG)
				.document("""
					{
						queryCategory(filterBy: {entityPrimaryKeyInSet: [3, 12, 22], scope: [LIVE, ARCHIVED]}) {
							recordPage {
								data {
									primaryKey
									scope
									parentPrimaryKey
									parents(
										stopAt: {
											node: {
												filterBy: {
													inScope: {
														scope: LIVE,
														filtering: {attributeCodeEquals: "category-2"}
													}
												}
											}
										}
									) {
										primaryKey
									}
								}
							}
						}
					}
					""")
				.executeAndExpectOkAndThen()
				.body(ERRORS_PATH, nullValue())
				.body(
					CATEGORY_DATA_PATH,
					containsInAnyOrder(
						categoryDto(3, "LIVE", 2, 2),
						categoryDto(12, "ARCHIVED", 11, 11),
						categoryDto(22, "ARCHIVED", 21, 21)
					)
				);
		}
	}

	/**
	 * Returns the JSON path to the referenced primary keys of the brands of the given product in the query response.
	 *
	 * @param productPk primary key of the product
	 * @return the JSON path
	 */
	@Nonnull
	private static String brandsOfProductPath(int productPk) {
		return PRODUCT_DATA_PATH + ".find { it.primaryKey == " + productPk + " }." + REFERENCE_BRANDS +
			".referencedPrimaryKey";
	}

	/**
	 * Expected shape of a product with the primary keys of the brands it references, in the order reported.
	 *
	 * @param primaryKey        primary key of the product
	 * @param scope             name of the scope the product lives in
	 * @param referencedBrandPks primary keys of the referenced brands in the expected order
	 * @return the expected DTO
	 */
	@Nonnull
	private static Map<String, Object> productDto(int primaryKey, @Nonnull String scope, int... referencedBrandPks) {
		return map()
			.e("primaryKey", primaryKey)
			.e("scope", scope)
			.e(
				REFERENCE_BRANDS,
				Arrays.stream(referencedBrandPks)
					.mapToObj(pk -> map().e("referencedPrimaryKey", pk).build())
					.toList()
			)
			.build();
	}

	/**
	 * Expected shape of a category with its parent key and the primary keys of its reported ancestors.
	 *
	 * @param primaryKey       primary key of the category
	 * @param scope            name of the scope the category lives in
	 * @param parentPrimaryKey primary key of its direct parent
	 * @param ancestorPks      primary keys of the reported ancestors, root first
	 * @return the expected DTO
	 */
	@Nonnull
	private static Map<String, Object> categoryDto(
		int primaryKey,
		@Nonnull String scope,
		int parentPrimaryKey,
		int... ancestorPks
	) {
		return map()
			.e("primaryKey", primaryKey)
			.e("scope", scope)
			.e("parentPrimaryKey", parentPrimaryKey)
			.e(
				"parents",
				Arrays.stream(ancestorPks)
					.mapToObj(pk -> map().e("primaryKey", pk).build())
					.toList()
			)
			.build();
	}

	/**
	 * Creates a product referencing brands 1, 2 and 3 with the given values of the reference attribute `rank`.
	 *
	 * @param session    the session to upsert through
	 * @param primaryKey the primary key to assign
	 * @param rankOf1    rank of the reference to brand 1
	 * @param rankOf2    rank of the reference to brand 2
	 * @param rankOf3    rank of the reference to brand 3
	 */
	private static void createProduct(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		int rankOf1,
		int rankOf2,
		int rankOf3
	) {
		session.createNewEntity(Entities.PRODUCT, primaryKey)
			.setReference(REFERENCE_BRANDS, 1, ref -> ref.setAttribute(ATTRIBUTE_RANK, rankOf1))
			.setReference(REFERENCE_BRANDS, 2, ref -> ref.setAttribute(ATTRIBUTE_RANK, rankOf2))
			.setReference(REFERENCE_BRANDS, 3, ref -> ref.setAttribute(ATTRIBUTE_RANK, rankOf3))
			.upsertVia(session);
	}

	/**
	 * Creates a category carrying the `code` attribute.
	 *
	 * @param session          the session to upsert through
	 * @param primaryKey       the primary key to assign
	 * @param parentPrimaryKey the parent primary key, or NULL for a root node
	 */
	private static void createCategory(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable Integer parentPrimaryKey
	) {
		final EntityBuilder builder = session.createNewEntity(Entities.CATEGORY, primaryKey)
			.setAttribute(ATTRIBUTE_CODE, "category-" + primaryKey);
		if (parentPrimaryKey != null) {
			builder.setParent(parentPrimaryKey);
		}
		builder.upsertVia(session);
	}
}
