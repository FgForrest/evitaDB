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

import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.attribute.AttributesProviderDescriptor;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests of the two sibling parent fields of a hierarchical GraphQL entity object - `parents`, which reports the
 * ancestor axis under {@link io.evitadb.api.query.require.HierarchyParentsBehaviour#MATCHING}, and `parentsComplete`,
 * which reports it under {@link io.evitadb.api.query.require.HierarchyParentsBehaviour#COMPLETE} through a union of
 * the entity object and the bodyless pointer standing in for an ancestor whose body could not be materialized.
 *
 * See issue #1365 (https://github.com/FgForrest/evitaDB/issues/1365) and
 * `documentation/adr/2026-08-03-hierarchy-content-parents-behaviour.md`.
 *
 * The fixture is the shape the whole feature exists for: a chain whose middle ancestor holds no English data, with
 * a materializable root above it. Querying entity 4 in English therefore resolves `3` with a body, `2` as a bodyless
 * pointer and `1` with a body again.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(QUERY)
public class CatalogGraphQLHierarchyContentParentsFunctionalTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final String DATA_SET = "GraphQLHierarchyContentParents";
	private static final String GET_CATEGORY_PATH = "data.getCategory";
	private static final Locale LOCALE_CZECH = new Locale("cs");

	/**
	 * Name of the GraphQL union member reporting an ancestor together with the body that was asked for.
	 */
	private static final String NON_HIERARCHICAL_CATEGORY_TYPE = "NonHierarchicalCategory";
	/**
	 * Name of the GraphQL union member reporting an ancestor whose requested body could not be materialized.
	 */
	private static final String CATEGORY_PARENT_POINTER_TYPE = "CategoryParentPointer";

	@DataSet(value = DATA_SET, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	DataCarrier setUpHierarchyContentParents(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withHierarchy()
					.withLocale(LOCALE_CZECH, Locale.ENGLISH)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().nullable())
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::nullable)
					.updateAndFetchVia(session);

				createCategory(session, 1, null, Locale.ENGLISH);
				// the middle ancestor holds Czech data only and therefore cannot be materialized under an English
				// query locale - this is the node the whole behaviour is about
				createCategory(session, 2, 1, LOCALE_CZECH);
				createCategory(session, 3, 2, Locale.ENGLISH);
				createCategory(session, 4, 3, Locale.ENGLISH);
			}
		);
		return new DataCarrier();
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should cut the matching chain below the bodyless ancestor and report the complete one whole")
	void shouldReportBothParentViewsFromASingleFetch(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document("""
				{
					getCategory(primaryKey: 4, locale: en) {
						primaryKey
						parents {
							primaryKey
							attributes {
								name
							}
						}
						parentsComplete {
							__typename
							... on NonHierarchicalCategory {
								primaryKey
								attributes {
									name
								}
							}
							... on CategoryParentPointer {
								primaryKey
								type
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(
				GET_CATEGORY_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), 4)
						.e(GraphQLEntityDescriptor.PARENTS.name(), List.of(materializedAncestorDto(3)))
						.e(
							GraphQLEntityDescriptor.PARENTS_COMPLETE.name(),
							List.of(
								typedMaterializedAncestorDto(1),
								bodylessAncestorDto(2),
								typedMaterializedAncestorDto(3)
							)
						)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should cut the chain below the bodyless ancestor when only parents is selected")
	void shouldReportMatchingChainAlone(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document("""
				{
					getCategory(primaryKey: 4, locale: en) {
						parents {
							primaryKey
							attributes {
								name
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(
				GET_CATEGORY_PATH,
				equalTo(
					map()
						.e(GraphQLEntityDescriptor.PARENTS.name(), List.of(materializedAncestorDto(3)))
						.build()
				)
			);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should report a body above a bodyless pointer when only parentsComplete is selected")
	void shouldReportCompleteChainAlone(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document("""
				{
					getCategory(primaryKey: 4, locale: en) {
						parentsComplete {
							__typename
							... on NonHierarchicalCategory {
								primaryKey
								attributes {
									name
								}
							}
							... on CategoryParentPointer {
								primaryKey
								type
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(
				GET_CATEGORY_PATH,
				equalTo(
					map()
						.e(
							GraphQLEntityDescriptor.PARENTS_COMPLETE.name(),
							List.of(
								typedMaterializedAncestorDto(1),
								bodylessAncestorDto(2),
								typedMaterializedAncestorDto(3)
							)
						)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should apply one shared bound to both parent fields")
	void shouldApplyEqualStopAtToBothParentFields(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document("""
				{
					getCategory(primaryKey: 4, locale: en) {
						parents(stopAt: { distance: 2 }) {
							primaryKey
							attributes {
								name
							}
						}
						parentsComplete(stopAt: { distance: 2 }) {
							__typename
							... on NonHierarchicalCategory {
								primaryKey
								attributes {
									name
								}
							}
							... on CategoryParentPointer {
								primaryKey
								type
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(
				GET_CATEGORY_PATH,
				equalTo(
					map()
						.e(GraphQLEntityDescriptor.PARENTS.name(), List.of(materializedAncestorDto(3)))
						.e(
							GraphQLEntityDescriptor.PARENTS_COMPLETE.name(),
							List.of(bodylessAncestorDto(2), typedMaterializedAncestorDto(3))
						)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should refuse two different bounds on the two parent fields")
	void shouldRefuseUnequalStopAtOnParentFields(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document("""
				{
					getCategory(primaryKey: 4, locale: en) {
						parents(stopAt: { distance: 1 }) {
							primaryKey
						}
						parentsComplete(stopAt: { distance: 2 }) {
							__typename
							... on CategoryParentPointer {
								primaryKey
							}
						}
					}
				}
				""")
			.executeAndThen()
			.statusCode(200)
			.body(GET_CATEGORY_PATH, nullValue())
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should report the whole primary key chain when no ancestor body is asked for")
	void shouldReportWholeKeyChainWithoutBodies(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document("""
				{
					getCategory(primaryKey: 4, locale: en) {
						parents {
							primaryKey
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(
				GET_CATEGORY_PATH,
				equalTo(
					map()
						.e(
							GraphQLEntityDescriptor.PARENTS.name(),
							List.of(
								map().e(EntityDescriptor.PRIMARY_KEY.name(), 1).build(),
								map().e(EntityDescriptor.PRIMARY_KEY.name(), 2).build(),
								map().e(EntityDescriptor.PRIMARY_KEY.name(), 3).build()
							)
						)
						.build()
				)
			);
	}

	/**
	 * Expected shape of an ancestor whose body was materialized, as reported by the `parents` field.
	 *
	 * @param primaryKey primary key of the ancestor
	 * @return the expected DTO
	 */
	@Nonnull
	private static Map<String, Object> materializedAncestorDto(int primaryKey) {
		return map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), primaryKey)
			.e(
				AttributesProviderDescriptor.ATTRIBUTES.name(),
				map().e(ATTRIBUTE_NAME, "Category " + primaryKey).build()
			)
			.build();
	}

	/**
	 * Expected shape of an ancestor whose body was materialized, as reported by the `parentsComplete` union.
	 *
	 * @param primaryKey primary key of the ancestor
	 * @return the expected DTO
	 */
	@Nonnull
	private static Map<String, Object> typedMaterializedAncestorDto(int primaryKey) {
		return map()
			.e(TYPENAME_FIELD, NON_HIERARCHICAL_CATEGORY_TYPE)
			.e(EntityDescriptor.PRIMARY_KEY.name(), primaryKey)
			.e(
				AttributesProviderDescriptor.ATTRIBUTES.name(),
				map().e(ATTRIBUTE_NAME, "Category " + primaryKey).build()
			)
			.build();
	}

	/**
	 * Expected shape of an ancestor whose requested body could not be materialized.
	 *
	 * @param primaryKey primary key of the ancestor
	 * @return the expected DTO
	 */
	@Nonnull
	private static Map<String, Object> bodylessAncestorDto(int primaryKey) {
		return map()
			.e(TYPENAME_FIELD, CATEGORY_PARENT_POINTER_TYPE)
			.e(EntityDescriptor.PRIMARY_KEY.name(), primaryKey)
			.e(EntityDescriptor.TYPE.name(), Entities.CATEGORY)
			.build();
	}

	/**
	 * Creates a category carrying the global `code` attribute and a localized `name` in the single requested locale.
	 *
	 * @param session          the session to upsert through
	 * @param primaryKey       the primary key to assign
	 * @param parentPrimaryKey the parent primary key, or NULL for a root node
	 * @param locale           the only locale the created node holds data in
	 */
	private static void createCategory(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable Integer parentPrimaryKey,
		@Nonnull Locale locale
	) {
		final EntityBuilder builder = session.createNewEntity(Entities.CATEGORY, primaryKey)
			.setAttribute(ATTRIBUTE_CODE, "category-" + primaryKey)
			.setAttribute(ATTRIBUTE_NAME, locale, "Category " + primaryKey);
		if (parentPrimaryKey != null) {
			builder.setParent(parentPrimaryKey);
		}
		builder.upsertVia(session);
	}
}
