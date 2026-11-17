/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.GraphQLTester;
import io.evitadb.utils.StringUtils;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.ENTITY_EMPTY;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for GraphQL updating entity schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
public class CatalogGraphQLUpdateEntitySchemaQueryFunctionalTest extends CatalogGraphQLEvitaSchemaEndpointFunctionalTest {

	private static final String EMPTY_SCHEMA_PATH = "data.getEmptySchema";
	private static final String UPDATE_EMPTY_SCHEMA_PATH = "data.updateEmptySchema";
	private static final String UPDATE_PRODUCT_SCHEMA_PATH = "data.updateProductSchema";
	private static final String GRAPHQL_THOUSAND_PRODUCTS_FOR_SCHEMA_CHANGE = GRAPHQL_THOUSAND_PRODUCTS + "forEntitySchemaChange";
	/**
	 * Data set served on real (asynchronous) thread pools, so that the GraphQL read fetchers run on request-pool
	 * workers exactly as in production instead of inline on the submitting thread.
	 */
	private static final String GRAPHQL_PRODUCTS_ON_REAL_THREAD_POOLS = GRAPHQL_THOUSAND_PRODUCTS + "onRealThreadPools";
	/**
	 * Number of aliases of each name-variant field selected per reference schema in one mutation response - every
	 * alias is one more fetcher call resolving the referenced schema through the mutation's session.
	 */
	private static final int NAME_VARIANT_ALIAS_COUNT = 16;
	/**
	 * Number of identical schema mutations sent in a row; each one resolves all the aliased name variants.
	 */
	private static final int MUTATION_REQUEST_COUNT = 20;
	/**
	 * Upper bound for the GraphQL API to serve the product schema after the data set was built - the API is rebuilt
	 * asynchronously on real thread pools.
	 */
	private static final long API_READY_TIMEOUT_SECONDS = 30;

	@Override
	@DataSet(value = GRAPHQL_THOUSAND_PRODUCTS_FOR_SCHEMA_CHANGE, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita) {
		return super.setUpData(evita, 20, false);
	}

	/**
	 * Builds the same catalog as {@link #setUp(Evita)}, but serves it on real thread pools.
	 */
	@DataSet(
		value = GRAPHQL_PRODUCTS_ON_REAL_THREAD_POOLS,
		openWebApi = GraphQLProvider.CODE,
		readOnly = false,
		destroyAfterClass = true,
		useRealThreadPools = true
	)
	@Nullable
	protected DataCarrier setUpOnRealThreadPools(@Nonnull Evita evita) {
		return super.setUpData(evita, 20, false);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_SCHEMA_CHANGE)
	@DisplayName("Should return error for missing mutations when updating entity schema")
	void shouldReturnErrorForMissingMutationsWhenUpdatingEntitySchema(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema {
						version
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_SCHEMA_CHANGE)
	@DisplayName("Should not update entity schema when no mutations")
	void shouldNotUpdateCatalogSchemaWhenNoMutations(GraphQLTester tester) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(tester, ENTITY_EMPTY);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: []
					) {
						version
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion)
						.build()
				)
			);

	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_SCHEMA_CHANGE)
	@DisplayName("Should change entity schema itself")
	void shouldChangeEntitySchemaItself(GraphQLTester tester) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(tester, ENTITY_EMPTY);

		// allow new locales
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								allowLocaleInEntitySchemaMutation: {
									locales: ["fr", "it"]
								}
							}
						]
					) {
						version
						locales
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.e(EntitySchemaDescriptor.LOCALES.name(), List.of("fr", "it"))
						.build()
				)
			);

		// revert
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								disallowLocaleInEntitySchemaMutation: {
									locales: ["fr", "it"]
								}
							}
						]
					) {
						version
						locales
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion + 2)
						.e(EntitySchemaDescriptor.LOCALES.name(), List.of())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_SCHEMA_CHANGE)
	@DisplayName("Should change conflict resolution of entity schema and attribute override")
	void shouldChangeConflictResolutionOfEntitySchema(GraphQLTester tester) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(tester, ENTITY_EMPTY);

		// create an attribute we can attach a non-default conflict resolution override to
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								createAttributeSchemaMutation: {
									name: "conflictAttribute"
									type: String
									indexedDecimalPlaces: 0
								}
							}
						]
					) {
						version
					}
				}
				"""
			)
			.executeAndExpectOkAndThen()
			.body(
				UPDATE_EMPTY_SCHEMA_PATH + "." + VersionedDescriptor.VERSION.name(),
				equalTo(initialEntitySchemaVersion + 1)
			);

		// set a non-default entity-level conflict resolution and a non-default attribute override, then read both back
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								modifyEntitySchemaConflictResolutionMutation: {
									conflictResolution: {
										policy: ENTITY
										granularity: [ENTITY_ATTRIBUTE]
									}
								}
							},
							{
								setAttributeSchemaConflictResolutionOverrideMutation: {
									name: "conflictAttribute"
									conflictResolutionOverride: GRANULAR
								}
							}
						]
					) {
						version
						conflictResolution {
							policy
							granularity
						}
						attributes {
							conflictAttribute {
								conflictResolutionOverride
							}
						}
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion + 3)
						.e(
							EntitySchemaDescriptor.CONFLICT_RESOLUTION.name(),
							map()
								.e(ConflictResolutionDescriptor.POLICY.name(), ConflictPolicy.ENTITY.name())
								.e(
									ConflictResolutionDescriptor.GRANULARITY.name(),
									List.of(GranularConflictPolicy.ENTITY_ATTRIBUTE.name())
								)
								.build()
						)
						.e(EntitySchemaDescriptor.ATTRIBUTES.name(), map()
							.e("conflictAttribute", map()
								.e(
									AttributeSchemaDescriptor.CONFLICT_RESOLUTION_OVERRIDE.name(),
									ConflictResolutionOverride.GRANULAR.name()
								)
								.build())
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_SCHEMA_CHANGE)
	@DisplayName("Should change attribute schema")
	void shouldChangeAttributeSchema(GraphQLTester tester) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(tester, ENTITY_EMPTY);

		// add new attribute
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								createAttributeSchemaMutation: {
									name: "mySpecialCode"
									uniqueInScopes: [
										{
											scope: LIVE,
											uniquenessType: UNIQUE_WITHIN_COLLECTION
										}
									]
									filterableInScopes: [LIVE]
									sortableInScopes: [LIVE]
									localized: false
									nullable: false
									type: String
									indexedDecimalPlaces: 0
								}
							}
						]
					) {
						version
					}
				}
				"""
			)
			.executeAndExpectOkAndThen()
			.body(
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.build()
				)
			);

		// verify new attribute schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
                query {
                    getEmptySchema {
                        name
						version
						attributes {
							mySpecialCode {
								name
								description
								deprecationNotice
								uniquenessType {
									scope
									uniquenessType
				    	        }
								filterable
								sortable
								localized
								nullable
								type
								defaultValue
								indexedDecimalPlaces
							}
						}
                    }
                }
				"""
			)
			.executeAndExpectOkAndThen()
			.body(
				EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(NamedSchemaDescriptor.NAME.name(), ENTITY_EMPTY)
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.e(EntitySchemaDescriptor.ATTRIBUTES.name(), map()
							.e("mySpecialCode", map()
								.e(NamedSchemaDescriptor.NAME.name(), "mySpecialCode")
								.e(NamedSchemaDescriptor.DESCRIPTION.name(), null)
								.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), null)
								.e(AttributeSchemaDescriptor.UNIQUENESS_TYPE.name(), createAttributeUniquenessTypeDto(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION))
								.e(AttributeSchemaDescriptor.FILTERABLE.name(), list().i(Scope.LIVE.name()))
								.e(AttributeSchemaDescriptor.SORTABLE.name(), list().i(Scope.LIVE.name()))
								.e(AttributeSchemaDescriptor.LOCALIZED.name(), false)
								.e(AttributeSchemaDescriptor.NULLABLE.name(), false)
								.e(AttributeSchemaDescriptor.TYPE.name(), String.class.getSimpleName())
								.e(AttributeSchemaDescriptor.DEFAULT_VALUE.name(), null)
								.e(AttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), 0)
								.build())
							.build())
						.build()
				)
			);

		// update attribute schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								modifyAttributeSchemaDescriptionMutation: {
									name: "mySpecialCode"
									description: "desc"
								}
							}
						]
					) {
						version
						attributes {
							mySpecialCode {
								description
							}
						}
					}
				}
				"""
			)
			.executeAndExpectOkAndThen()
			.body(
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion + 2)
						.e(EntitySchemaDescriptor.ATTRIBUTES.name(), map()
							.e("mySpecialCode", map()
								.e(NamedSchemaDescriptor.DESCRIPTION.name(), "desc")
								.build())
							.build())
						.build()
				)
			);

		// remove attribute
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								removeAttributeSchemaMutation: {
									name: "mySpecialCode"
								}
							}
						]
					) {
						version
						allAttributes {
							... on AttributeSchema {
								name
							}
							... on EntityAttributeSchema {
								__typename
								name
							}
							... on GlobalAttributeSchema {
								name
							}
						}
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_EMPTY_SCHEMA_PATH + "." + VersionedDescriptor.VERSION.name(),
				equalTo(initialEntitySchemaVersion + 3)
			)
			.body(
				UPDATE_EMPTY_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_ATTRIBUTES.name() + "." + NamedSchemaDescriptor.NAME.name(),
				not(containsInRelativeOrder("mySpecialCode"))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_SCHEMA_CHANGE)
	@DisplayName("Should change sortable attribute compound schema")
	void shouldChangeSortableAttributeCompoundSchema(GraphQLTester tester) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(tester, ENTITY_EMPTY);

		// add new sortable attribute compound
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					mutation {
						updateEmptySchema (
							mutations: [
								{
									createSortableAttributeCompoundSchemaMutation: {
										name: "mySpecialCompound"
										attributeElements: [
											{
												attributeName: "code"
												direction: ASC
												behaviour: NULLS_LAST
											},
											{
												attributeName: "name"
												direction: DESC
												behaviour: NULLS_FIRST
											}
										]
									}
								}
							]
						) {
							version
						}
					}
					"""
			)
			.executeAndExpectOkAndThen()
			.body(
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.build()
				)
			);

		// verify that new sortable attribute compound is present
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						getEmptySchema {
							name
							version
							sortableAttributeCompounds {
								mySpecialCompound {
									name
									attributeElements {
										attributeName
										direction
										behaviour
									}
								}
							}
						}
					}
					"""
			)
			.executeAndExpectOkAndThen()
			.body(
				EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(NamedSchemaDescriptor.NAME.name(), ENTITY_EMPTY)
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.e(SortableAttributeCompoundsSchemaProviderDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name(), map()
							.e("mySpecialCompound", map()
								.e(NamedSchemaDescriptor.NAME.name(), "mySpecialCompound")
								.e(SortableAttributeCompoundSchemaDescriptor.ATTRIBUTE_ELEMENTS.name(), List.of(
									map()
										.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), ATTRIBUTE_CODE)
										.e(AttributeElementDescriptor.DIRECTION.name(), OrderDirection.ASC.name())
										.e(AttributeElementDescriptor.BEHAVIOUR.name(), OrderBehaviour.NULLS_LAST.name())
										.build(),
									map()
										.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), ATTRIBUTE_NAME)
										.e(AttributeElementDescriptor.DIRECTION.name(), OrderDirection.DESC.name())
										.e(AttributeElementDescriptor.BEHAVIOUR.name(), OrderBehaviour.NULLS_FIRST.name())
										.build()
								))))
						.build()
				)
			);

		// update sortable attribute compound
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					mutation {
						updateEmptySchema (
							mutations: [
								{
									modifySortableAttributeCompoundSchemaDescriptionMutation: {
										name: "mySpecialCompound"
										description: "desc"
									}
								}
							]
						) {
							version
							sortableAttributeCompounds {
								mySpecialCompound {
									description
								}
							}
						}
					}
					"""
			)
			.executeAndExpectOkAndThen()
			.body(
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion + 2)
						.e(SortableAttributeCompoundsSchemaProviderDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name(), map()
							.e("mySpecialCompound", map()
								.e(NamedSchemaDescriptor.DESCRIPTION.name(), "desc")
							))
						.build()
				)
			);

		// remove sortable attribute compound
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					mutation {
						updateEmptySchema (
							mutations: [
								{
									removeSortableAttributeCompoundSchemaMutation: {
										name: "mySpecialCompound"
									}
								}
							]
						) {
							version
							allSortableAttributeCompounds {
								name
							}
						}
					}
					"""
			)
			.executeAndExpectOkAndThen()
			.body(
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion + 3)
						.e(SortableAttributeCompoundsSchemaProviderDescriptor.ALL_SORTABLE_ATTRIBUTE_COMPOUNDS.name(), List.of())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_SCHEMA_CHANGE)
	@DisplayName("Should change associated data schema")
	void shouldChangeAssociatedDataSchema(GraphQLTester tester) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(tester, ENTITY_EMPTY);

		// add new associated data
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								createAssociatedDataSchemaMutation: {
									name: "mySpecialLabel"
									type: String
									localized: true
									nullable: false
								}
							}
						]
					) {
						version
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.build()
				)
			);

		// verify new associated data schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
                query {
                    getEmptySchema {
                        name
						version
						associatedData {
							mySpecialLabel {
								name
								description
								deprecationNotice
								type
								localized
								nullable
							}
						}
                    }
                }
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(NamedSchemaDescriptor.NAME.name(), ENTITY_EMPTY)
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.e(EntitySchemaDescriptor.ASSOCIATED_DATA.name(), map()
							.e("mySpecialLabel", map()
								.e(NamedSchemaDescriptor.NAME.name(), "mySpecialLabel")
								.e(NamedSchemaDescriptor.DESCRIPTION.name(), null)
								.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), null)
								.e(AssociatedDataSchemaDescriptor.TYPE.name(), String.class.getSimpleName())
								.e(AssociatedDataSchemaDescriptor.LOCALIZED.name(), true)
								.e(AssociatedDataSchemaDescriptor.NULLABLE.name(), false)
								.build())
							.build())
						.build()
				)
			);

		// update associated data schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								modifyAssociatedDataSchemaDescriptionMutation: {
									name: "mySpecialLabel"
									description: "desc"
								}
							}
						]
					) {
						version
						associatedData {
							mySpecialLabel {
								description
							}
						}
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion + 2)
						.e(EntitySchemaDescriptor.ASSOCIATED_DATA.name(), map()
							.e("mySpecialLabel", map()
								.e(NamedSchemaDescriptor.DESCRIPTION.name(), "desc")
								.build())
							.build())
						.build()
				)
			);

		// remove associated data
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								removeAssociatedDataSchemaMutation: {
									name: "mySpecialLabel"
								}
							}
						]
					) {
						version
						allAssociatedData {
							name
						}
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_EMPTY_SCHEMA_PATH + "." + VersionedDescriptor.VERSION.name(),
				equalTo(initialEntitySchemaVersion + 3)
			)
			.body(
				UPDATE_EMPTY_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_ASSOCIATED_DATA.name() + "." + NamedSchemaDescriptor.NAME.name(),
				not(containsInRelativeOrder("mySpecialLabel"))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_SCHEMA_CHANGE)
	@DisplayName("Should change reference schema")
	void shouldChangeReferenceSchema(GraphQLTester tester) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(tester, ENTITY_EMPTY);

		// add new reference
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								createReferenceSchemaMutation: {
									name: "mySpecialTags"
									referencedEntityType: "tag"
									referencedEntityTypeManaged: false
									referencedGroupTypeManaged: false
									indexedInScopes: [
										{
											scope: LIVE
											indexType: FOR_FILTERING
										}
									]
									facetedInScopes: [LIVE]
								}
							}
						]
					) {
						version
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.build()
				)
			);

		// verify new reference schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
                query {
                    getEmptySchema {
                        name
						version
						references {
							mySpecialTags {
								name
								description
								deprecationNotice
								cardinality
								referencedEntityType
								referencedEntityTypeManaged
								referencedGroupType
								referencedGroupTypeManaged
								indexed {
									scope
									indexType
								}
								indexedComponents {
									scope
									indexedComponents
								}
								faceted
							}
						}
                    }
                }
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(NamedSchemaDescriptor.NAME.name(), ENTITY_EMPTY)
						.e(VersionedDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.e(EntitySchemaDescriptor.REFERENCES.name(), map()
							.e("mySpecialTags", map()
								.e(NamedSchemaDescriptor.NAME.name(), "mySpecialTags")
								.e(NamedSchemaDescriptor.DESCRIPTION.name(), null)
								.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), null)
								.e(ReferenceSchemaDescriptor.CARDINALITY.name(), Cardinality.ZERO_OR_MORE.name())
								.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
								.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), false)
								.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE.name(), null)
								.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), false)
								.e(
									ReferenceSchemaDescriptor.INDEXED.name(),
									list().i(
										map()
											.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
											.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), ReferenceIndexType.FOR_FILTERING.name())
									)
								)
								.e(
									ReferenceSchemaDescriptor.INDEXED_COMPONENTS.name(),
									list().i(
										map()
											.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
											.e(
												ScopedReferenceIndexedComponentsDescriptor.INDEXED_COMPONENTS.name(),
												list().i(ReferenceIndexedComponents.REFERENCED_ENTITY.name())
											)
									)
								)
								.e(ReferenceSchemaDescriptor.FACETED.name(), list().i(Scope.LIVE.name()))
								.build())
							.build())
						.build()
				)
			);

		// update reference schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								modifyReferenceAttributeSchemaMutation: {
									name: "mySpecialTags"
									attributeSchemaMutation: {
										createAttributeSchemaMutation: {
											name: "mySpecialCode"
											uniqueInScopes: [
												{
													scope: LIVE
													uniquenessType: NOT_UNIQUE
												}
											]
											filterableInScopes: [LIVE]
											sortableInScopes: []
											localized: false
											nullable: false
											type: String
											indexedDecimalPlaces: 0
										}
									}
								}
							}
						]
					) {
						version
						references {
							mySpecialTags {
								allAttributes {
									name
								}
							}
						}
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_EMPTY_SCHEMA_PATH + "." + VersionedDescriptor.VERSION.name(),
				equalTo(initialEntitySchemaVersion + 2)
			)
			.body(
				UPDATE_EMPTY_SCHEMA_PATH + "." + EntitySchemaDescriptor.REFERENCES.name() + ".mySpecialTags." + ReferenceSchemaDescriptor.ALL_ATTRIBUTES.name() + "." + NamedSchemaDescriptor.NAME.name(),
				containsInRelativeOrder("mySpecialCode")
			);

		// remove reference
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								removeReferenceSchemaMutation: {
									name: "mySpecialTags"
								}
							}
						]
					) {
						version
						allReferences {
							name
						}
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_EMPTY_SCHEMA_PATH + "." + VersionedDescriptor.VERSION.name(),
				equalTo(initialEntitySchemaVersion + 3)
			)
			.body(
				UPDATE_EMPTY_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_REFERENCES.name() + "." + NamedSchemaDescriptor.NAME.name(),
				not(containsInRelativeOrder("mySpecialTags"))
			);
	}


	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_SCHEMA_CHANGE)
	@DisplayName("Should create and update bucketed reference schema")
	void shouldCreateAndUpdateBucketedReferenceSchema(GraphQLTester tester) {
		// create reference with numeric filterable attribute for bucketed histogram
		createBucketedReferenceWithAttribute(tester);
		final int refCreatedVersion = getEntitySchemaVersion(tester, ENTITY_EMPTY);

		// set bucketed config with valid valueExpression referencing the attribute
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								setReferenceSchemaBucketedMutation: {
									name: "myBucketedRef"
									bucketedInScopes: [
										{
											scope: LIVE
											nameOfTheIndex: "priceHistogram"
											valueExpression: "$reference.attributes['quantity']"
										}
									]
									bucketedPartiallyInScopes: [
										{
											scope: LIVE
											expression: "1 > 0"
										}
									]
								}
							}
						]
					) {
						version
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), refCreatedVersion + 1)
						.build()
				)
			);

		// verify the bucketed reference schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				query {
					getEmptySchema {
						version
						references {
							myBucketedRef {
								name
								bucketed {
									scope
									nameOfTheIndex
									valueExpression
									assignedWhen
								}
								bucketedPartially {
									scope
									expression
								}
							}
						}
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), refCreatedVersion + 1)
						.e(EntitySchemaDescriptor.REFERENCES.name(), map()
							.e("myBucketedRef", map()
								.e(NamedSchemaDescriptor.NAME.name(), "myBucketedRef")
								.e(
									ReferenceSchemaDescriptor.BUCKETED.name(),
									list().i(
										map()
											.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
											.e(ScopedHistogramIndexDefinitionDescriptor.NAME_OF_THE_INDEX.name(), "priceHistogram")
											.e(ScopedHistogramIndexDefinitionDescriptor.VALUE_EXPRESSION.name(), "$reference.attributes['quantity']")
											.e(ScopedHistogramIndexDefinitionDescriptor.ASSIGNED_WHEN.name(), null)
									)
								)
								.e(
									ReferenceSchemaDescriptor.BUCKETED_PARTIALLY.name(),
									list().i(
										map()
											.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
											.e(ScopedBucketedPartiallyDescriptor.EXPRESSION.name(), "1 > 0")
									)
								)
								.build())
							.build())
						.build()
				)
			);

		// update bucketed config via setReferenceSchemaBucketedMutation with null valueExpression
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								setReferenceSchemaBucketedMutation: {
									name: "myBucketedRef"
									bucketedInScopes: [
										{
											scope: LIVE
											nameOfTheIndex: "countHistogram"
										}
									]
									bucketedPartiallyInScopes: []
								}
							}
						]
					) {
						version
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), refCreatedVersion + 2)
						.build()
				)
			);

		// verify updated bucketed config with null valueExpression
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				query {
					getEmptySchema {
						version
						references {
							myBucketedRef {
								name
								bucketed {
									scope
									nameOfTheIndex
									valueExpression
									assignedWhen
								}
								bucketedPartially {
									scope
									expression
								}
							}
						}
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), refCreatedVersion + 2)
						.e(EntitySchemaDescriptor.REFERENCES.name(), map()
							.e("myBucketedRef", map()
								.e(NamedSchemaDescriptor.NAME.name(), "myBucketedRef")
								.e(
									ReferenceSchemaDescriptor.BUCKETED.name(),
									list().i(
										map()
											.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
											.e(ScopedHistogramIndexDefinitionDescriptor.NAME_OF_THE_INDEX.name(), "countHistogram")
											.e(ScopedHistogramIndexDefinitionDescriptor.VALUE_EXPRESSION.name(), null)
											.e(ScopedHistogramIndexDefinitionDescriptor.ASSIGNED_WHEN.name(), null)
									)
								)
								.e(
									ReferenceSchemaDescriptor.BUCKETED_PARTIALLY.name(),
									List.of()
								)
								.build())
							.build())
						.build()
				)
			);

		// clean up: remove the reference
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								removeReferenceSchemaMutation: {
									name: "myBucketedRef"
								}
							}
						]
					) {
						version
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_EMPTY_SCHEMA_PATH + "." + VersionedDescriptor.VERSION.name(),
				equalTo(refCreatedVersion + 3)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_PRODUCTS_ON_REAL_THREAD_POOLS)
	@DisplayName("Should resolve managed reference type names in the response of an entity schema mutation")
	void shouldResolveManagedReferenceTypeNamesInResponseOfEntitySchemaMutation(@Nonnull GraphQLTester tester) {
		final JsonPath response = executeProductSchemaMutationRepeatedly(
			tester,
			"""
			allReferences {
				name
				%s
			}
			""".formatted(buildAliasedNameVariantFields())
		);

		assertParameterTypeNamesResolved(
			findReference(response.getList(UPDATE_PRODUCT_SCHEMA_PATH + ".allReferences"), Entities.PARAMETER)
		);
	}

	@Test
	@UseDataSet(GRAPHQL_PRODUCTS_ON_REAL_THREAD_POOLS)
	@DisplayName("Should resolve managed type names of named references in the response of an entity schema mutation")
	void shouldResolveManagedTypeNamesOfNamedReferencesInResponseOfEntitySchemaMutation(@Nonnull GraphQLTester tester) {
		final String aliasedFields = buildAliasedNameVariantFields();
		final List<String> referenceFields = List.of("category", "brand", "store", "brandWithGroup", "parameter");
		final StringBuilder referenceSelection = new StringBuilder(
			referenceFields.size() * (aliasedFields.length() + 32) + 32
		);
		referenceSelection.append("references {\n");
		for (final String referenceField : referenceFields) {
			referenceSelection.append(referenceField).append(" {\n").append(aliasedFields).append("}\n");
		}
		referenceSelection.append("}\n");

		final JsonPath response = executeProductSchemaMutationRepeatedly(tester, referenceSelection.toString());

		final String referencesPath = UPDATE_PRODUCT_SCHEMA_PATH + ".references.";
		assertEntityTypeNamesResolved(response.getMap(referencesPath + "category"), "category");
		assertEntityTypeNamesResolved(response.getMap(referencesPath + "brand"), "brand");
		assertEntityTypeNamesResolved(response.getMap(referencesPath + "store"), "store");
		assertEntityTypeNamesResolved(response.getMap(referencesPath + "brandWithGroup"), "brand");
		assertParameterTypeNamesResolved(response.getMap(referencesPath + "parameter"));
	}

	/**
	 * Creates a reference "myBucketedRef" to "tag" with a numeric filterable attribute "quantity"
	 * so that bucketed histogram expressions can reference it.
	 */
	private static void createBucketedReferenceWithAttribute(@Nonnull GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateEmptySchema (
						mutations: [
							{
								createReferenceSchemaMutation: {
									name: "myBucketedRef"
									referencedEntityType: "tag"
									referencedEntityTypeManaged: false
									referencedGroupTypeManaged: false
									indexedInScopes: [
										{
											scope: LIVE
											indexType: FOR_FILTERING
										}
									]
								}
							},
							{
								modifyReferenceAttributeSchemaMutation: {
									name: "myBucketedRef"
									attributeSchemaMutation: {
										createAttributeSchemaMutation: {
											name: "quantity"
											uniqueInScopes: [
												{
													scope: LIVE
													uniquenessType: NOT_UNIQUE
												}
											]
											filterableInScopes: [LIVE]
											sortableInScopes: []
											localized: false
											nullable: true
											type: Integer
											indexedDecimalPlaces: 0
										}
									}
								}
							}
						]
					) {
						version
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue());
	}

	/**
	 * Sends the same no-op product schema mutation {@link #MUTATION_REQUEST_COUNT} times in a row and asserts that
	 * none of the responses reports an error. The mutation list is empty, so the product schema - and the GraphQL
	 * schema the requests run against - stays the same across the requests, yet every request still runs in its own
	 * read-write session.
	 *
	 * @param tester            the GraphQL tester of the data set
	 * @param responseSelection the selection set of the mutated product schema to return
	 * @return the last response
	 */
	@Nonnull
	private static JsonPath executeProductSchemaMutationRepeatedly(
		@Nonnull GraphQLTester tester,
		@Nonnull String responseSelection
	) {
		awaitProductSchemaServed(tester);

		final String document = """
			mutation {
				updateProductSchema(mutations: []) {
					%s
				}
			}
			""".formatted(responseSelection);

		int failedRequests = 0;
		String firstError = null;
		JsonPath lastResponse = null;
		for (int i = 0; i < MUTATION_REQUEST_COUNT; i++) {
			lastResponse = tester.test(TEST_CATALOG)
				.urlPathSuffix("/schema")
				.document(document)
				.executeAndThen()
				.statusCode(200)
				.extract()
				.jsonPath();
			final List<Map<String, Object>> errors = lastResponse.getList(ERRORS_PATH);
			if (errors != null && !errors.isEmpty()) {
				failedRequests++;
				if (firstError == null) {
					firstError = String.valueOf(errors.get(0).get("message"));
				}
			}
		}

		assertEquals(
			0,
			failedRequests,
			failedRequests + " of " + MUTATION_REQUEST_COUNT + " entity schema mutations failed, first error: " +
				firstError
		);
		return Objects.requireNonNull(lastResponse);
	}

	/**
	 * Builds {@link #NAME_VARIANT_ALIAS_COUNT} aliases of both the entity type and the group type name variants of
	 * a reference schema. In the response of a schema mutation every alias of a managed type is one more read of the
	 * referenced entity schema through the read-write session of the mutation.
	 *
	 * @return the aliased fields to place into the selection set of a reference schema
	 */
	@Nonnull
	private static String buildAliasedNameVariantFields() {
		final StringBuilder aliasedFields = new StringBuilder(NAME_VARIANT_ALIAS_COUNT * 128);
		for (int i = 0; i < NAME_VARIANT_ALIAS_COUNT; i++) {
			aliasedFields.append("entity").append(i).append(": entityTypeNameVariants { camelCase }\n");
			aliasedFields.append("group").append(i).append(": groupTypeNameVariants { camelCase }\n");
		}
		return aliasedFields.toString();
	}

	/**
	 * Asserts that every entity type alias built by {@link #buildAliasedNameVariantFields()} resolved the managed
	 * entity type the reference points to.
	 *
	 * @param reference                   the reference schema DTO
	 * @param expectedEntityTypeCamelCase the camel case name of the referenced entity type
	 */
	private static void assertEntityTypeNamesResolved(
		@Nonnull Map<String, Object> reference,
		@Nonnull String expectedEntityTypeCamelCase
	) {
		for (int i = 0; i < NAME_VARIANT_ALIAS_COUNT; i++) {
			assertEquals(Map.of("camelCase", expectedEntityTypeCamelCase), reference.get("entity" + i));
		}
	}

	/**
	 * Asserts that every alias built by {@link #buildAliasedNameVariantFields()} resolved the managed referenced
	 * entity type and the managed group type of the parameter reference.
	 *
	 * @param parameterReference the reference schema DTO of the parameter reference
	 */
	private static void assertParameterTypeNamesResolved(@Nonnull Map<String, Object> parameterReference) {
		for (int i = 0; i < NAME_VARIANT_ALIAS_COUNT; i++) {
			assertEquals(Map.of("camelCase", "parameter"), parameterReference.get("entity" + i));
			assertEquals(Map.of("camelCase", "parameterGroup"), parameterReference.get("group" + i));
		}
	}

	/**
	 * Waits until the GraphQL schema API serves the product schema with its named `parameter` reference field - on
	 * real thread pools the API is registered and rebuilt asynchronously after the data set was built. The probe
	 * selects the same generated named field the tests select, so a schema API generated before that reference
	 * existed fails the probe's validation. Any response without the field counts as not ready yet, so a permanent
	 * defect surfaces only once the wait times out.
	 *
	 * @param tester the GraphQL tester of the data set
	 */
	private static void awaitProductSchemaServed(@Nonnull GraphQLTester tester) {
		await()
			.atMost(API_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.ignoreExceptions()
			.until(() -> {
				final String parameterReferenceName = tester.test(TEST_CATALOG)
					.urlPathSuffix("/schema")
					.document(
						"""
						query {
							getProductSchema {
								references {
									parameter {
										name
									}
								}
							}
						}
						"""
					)
					.executeAndThen()
					.extract()
					.jsonPath()
					.getString("data.getProductSchema.references.parameter.name");
				return Entities.PARAMETER.equals(parameterReferenceName);
			});
	}

	/**
	 * Finds the reference schema DTO of the given name in the list of reference schema DTOs.
	 *
	 * @param references the reference schema DTOs
	 * @param name       the name of the reference schema to find
	 * @return the reference schema DTO
	 * @throws AssertionError when no reference schema of the name is present
	 */
	@Nonnull
	private static Map<String, Object> findReference(
		@Nonnull List<Map<String, Object>> references,
		@Nonnull String name
	) {
		for (final Map<String, Object> reference : references) {
			if (name.equals(reference.get(NamedSchemaDescriptor.NAME.name()))) {
				return reference;
			}
		}
		throw new AssertionError("Reference schema `" + name + "` is missing in " + references);
	}

	private static int getEntitySchemaVersion(@Nonnull GraphQLTester tester, @Nonnull String entityType) {
		return tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				query {
					get%sSchema {
						version
					}
				}
				""",
				StringUtils.toPascalCase(entityType)
			)
			.executeAndThen()
			.extract()
			.jsonPath()
			.get("data.get" + StringUtils.toPascalCase(entityType) + "Schema." + VersionedDescriptor.VERSION.name());
	}
}
