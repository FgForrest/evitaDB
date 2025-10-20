/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NamedSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NamedSchemaWithDeprecationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ReferenceSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexTypeDescriptor;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.*;

/**
 * Tests for GraphQL updating catalog schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLUpdateCatalogSchemaQueryFunctionalTest extends CatalogGraphQLEvitaSchemaEndpointFunctionalTest {

	private static final String CATALOG_SCHEMA_PATH = "data.getCatalogSchema";
	private static final String UPDATE_CATALOG_SCHEMA_PATH = "data.updateCatalogSchema";
	private static final String MY_NEW_COLLECTION_SCHEMA_PATH = "data.getMyNewCollectionSchema";
	private static final String NEW_COLLECTION_NAME = "myNewCollection";
	public static final String GRAPHQL_THOUSAND_PRODUCTS_CATALOG_SCHEMA_CHANGE = GRAPHQL_THOUSAND_PRODUCTS + "forCatalogSchemaChange";

	@Override
	@DataSet(value = GRAPHQL_THOUSAND_PRODUCTS_CATALOG_SCHEMA_CHANGE, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita) {
		return super.setUpData(evita, 20, false);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_CATALOG_SCHEMA_CHANGE)
	@DisplayName("Should return error for missing mutations when updating catalog schema")
	void shouldReturnErrorForMissingMutationsWhenUpdatingCatalogSchema(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateCatalogSchema {
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
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_CATALOG_SCHEMA_CHANGE)
	@DisplayName("Should not update catalog schema when no mutations")
	void shouldNotUpdateCatalogSchemaWhenNoMutations(GraphQLTester tester) {
		final int initialCatalogSchemVersion = getCatalogSchemaVersion(tester);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateCatalogSchema (
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
				UPDATE_CATALOG_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialCatalogSchemVersion)
						.build()
				)
			);

	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_CATALOG_SCHEMA_CHANGE)
	@DisplayName("Should change description of catalog schema")
	void shouldChangeDescriptionOfCatalogSchema(GraphQLTester tester) {
		final int initialCatalogSchemVersion = getCatalogSchemaVersion(tester);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateCatalogSchema (
						mutations: [
							{
								modifyCatalogSchemaDescriptionMutation: {
									description: "desc"
								}
							}
						]
					) {
						version
						description
					}
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_CATALOG_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialCatalogSchemVersion + 1)
						.e(NamedSchemaDescriptor.DESCRIPTION.name(), "desc")
						.build()
				)
			);

	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_CATALOG_SCHEMA_CHANGE)
	@DisplayName("Should create new catalog attribute schema")
	void shouldCreateNewCatalogAttributeSchema(GraphQLTester tester) {
		final int initialCatalogSchemVersion = getCatalogSchemaVersion(tester);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateCatalogSchema (
						mutations: [
							{
								createGlobalAttributeSchemaMutation: {
									name: "mySpecialCode"
									uniqueInScopes: [
										{
											scope: LIVE,
											uniquenessType: UNIQUE_WITHIN_COLLECTION
										}
									]
									uniqueGloballyInScopes: [
										{
											scope: LIVE,
											uniquenessType: UNIQUE_WITHIN_CATALOG
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
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_CATALOG_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialCatalogSchemVersion + 1)
						.build()
				)
			);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
                query {
                    getCatalogSchema {
                        attributes {
                            mySpecialCode {
								name
								description
								deprecationNotice
								uniquenessType {
									scope
									uniquenessType
								}
								globalUniquenessType {
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
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				CATALOG_SCHEMA_PATH,
				equalTo(
					map()
						.e(CatalogSchemaDescriptor.ATTRIBUTES.name(), map()
							.e("mySpecialCode", map()
								.e(NamedSchemaDescriptor.NAME.name(), "mySpecialCode")
								.e(NamedSchemaDescriptor.DESCRIPTION.name(), null)
								.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), null)
								.e(AttributeSchemaDescriptor.UNIQUENESS_TYPE.name(), createAttributeUniquenessTypeDto(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION))
								.e(GlobalAttributeSchemaDescriptor.GLOBAL_UNIQUENESS_TYPE.name(), createGlobalAttributeUniquenessTypeDto(GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG))
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

		// revert
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateCatalogSchema (
						mutations: [
							{
								removeAttributeSchemaMutation: {
									name: "mySpecialCode"
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
				UPDATE_CATALOG_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialCatalogSchemVersion + 2)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_CATALOG_SCHEMA_CHANGE)
	@DisplayName("Should create and remove new empty entity schema")
	void shouldCreateAndRemoveNewEmptyEntitySchema(GraphQLTester tester) {
		final int initialCatalogSchemaVersion = getCatalogSchemaVersion(tester);

		// create collection
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateCatalogSchema (
						mutations: [
							{
								createEntitySchemaMutation: {
									name: "%s"
								}
							}
						]
					) {
						version
					}
				}
				""",
				NEW_COLLECTION_NAME
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_CATALOG_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialCatalogSchemaVersion + 1)
						.build()
				)
			);

		// verify new collection schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
                query {
                    getMyNewCollectionSchema {
                        name
						version
						allAttributes {
							... on EntityAttributeSchema {
								__typename
								name
							}
							... on GlobalAttributeSchema {
								name
							}
						}
						allAssociatedData {
							name
						}
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
				MY_NEW_COLLECTION_SCHEMA_PATH,
				equalTo(
					map()
						.e(NamedSchemaDescriptor.NAME.name(), NEW_COLLECTION_NAME)
						.e(VersionedDescriptor.VERSION.name(), 1)
						.e(EntitySchemaDescriptor.ALL_ATTRIBUTES.name(), List.of())
						.e(EntitySchemaDescriptor.ALL_ASSOCIATED_DATA.name(), List.of())
						.e(EntitySchemaDescriptor.ALL_REFERENCES.name(), List.of())
						.build()
				)
			);

		// remove new collection
		removeCollection(tester, NEW_COLLECTION_NAME, initialCatalogSchemaVersion + 2);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_CATALOG_SCHEMA_CHANGE)
	@DisplayName("Should create and remove new filled entity schema")
	void shouldCreateAndRemoveNewFilledEntitySchema(GraphQLTester tester) {
		final int initialCatalogSchemaVersion = getCatalogSchemaVersion(tester);

		// create collection
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateCatalogSchema (
						mutations: [
							{
								createEntitySchemaMutation: {
									name: "%s"
								},
								modifyEntitySchemaMutation: {
									name: "%s",
									schemaMutations: [
										{
											createAttributeSchemaMutation: {
												name: "code"
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
											},
											createReferenceSchemaMutation: {
												name: "tags"
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
								}
							}
						]
					) {
						version
					}
				}
				""",
				NEW_COLLECTION_NAME,
				NEW_COLLECTION_NAME
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_CATALOG_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), initialCatalogSchemaVersion + 2)
						.build()
				)
			);

		// verify new collection schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
                query {
                    getMyNewCollectionSchema {
                        name
						version
						attributes {
							code {
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
						allAssociatedData {
							name
						}
						references {
							tags {
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
				MY_NEW_COLLECTION_SCHEMA_PATH,
				equalTo(
					map()
						.e(NamedSchemaDescriptor.NAME.name(), NEW_COLLECTION_NAME)
						.e(VersionedDescriptor.VERSION.name(), 3)
						.e(EntitySchemaDescriptor.ATTRIBUTES.name(), map()
							.e(ATTRIBUTE_CODE, map()
								.e(NamedSchemaDescriptor.NAME.name(), ATTRIBUTE_CODE)
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
						.e(EntitySchemaDescriptor.ALL_ASSOCIATED_DATA.name(), List.of())
						.e(EntitySchemaDescriptor.REFERENCES.name(), map()
							.e("tags", map()
								.e(NamedSchemaDescriptor.NAME.name(), "tags")
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
												.e(ScopedReferenceIndexTypeDescriptor.SCOPE.name(), Scope.LIVE.name())
												.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), ReferenceIndexType.FOR_FILTERING.name())
										)
								)
								.e(ReferenceSchemaDescriptor.FACETED.name(), list().i(Scope.LIVE.name()))
								.build())
							.build())
						.build()
				)
			);

		// remove new collection
		removeCollection(tester, NEW_COLLECTION_NAME, initialCatalogSchemaVersion + 3);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_CATALOG_SCHEMA_CHANGE)
	@DisplayName("Should rename entity schema")
	void shouldRenameEntitySchema(GraphQLTester tester) {
		final int initialCatalogSchemaVersion = getCatalogSchemaVersion(tester);

		// rename existing collection
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateCatalogSchema (
						mutations: [
							{
								modifyEntitySchemaNameMutation: {
									name: "%s"
									newName: "%s"
									overwriteTarget: false
								}
							}
						]
					) {
						version
						allEntitySchemas {
							name
						}
					}
				}	
				""",
				Entities.PRODUCT,
				NEW_COLLECTION_NAME
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_CATALOG_SCHEMA_PATH + "." + VersionedDescriptor.VERSION.name(),
				equalTo(initialCatalogSchemaVersion + 1)
			)
			.body(
				UPDATE_CATALOG_SCHEMA_PATH + "." + CatalogSchemaDescriptor.ALL_ENTITY_SCHEMAS.name() + "." + NamedSchemaDescriptor.NAME.name(),
				containsInRelativeOrder(NEW_COLLECTION_NAME)
			)
			.body(
				UPDATE_CATALOG_SCHEMA_PATH + "." + CatalogSchemaDescriptor.ALL_ENTITY_SCHEMAS.name() + "." + NamedSchemaDescriptor.NAME.name(),
				not(containsInRelativeOrder(Entities.PRODUCT))
			);

		// rename collection back
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				mutation {
					updateCatalogSchema (
						mutations: [
							{
								modifyEntitySchemaNameMutation: {
									name: "%s"
									newName: "%s"
									overwriteTarget: false
								}
							}
						]
					) {
						version
						allEntitySchemas {
							name
						}
					}
				}	
				""",
				NEW_COLLECTION_NAME,
				Entities.PRODUCT
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_CATALOG_SCHEMA_PATH + "." + VersionedDescriptor.VERSION.name(),
				equalTo(initialCatalogSchemaVersion + 2)
			)
			.body(
				UPDATE_CATALOG_SCHEMA_PATH + "." + CatalogSchemaDescriptor.ALL_ENTITY_SCHEMAS.name() + "." + NamedSchemaDescriptor.NAME.name(),
				containsInRelativeOrder(Entities.PRODUCT)
			)
			.body(
				UPDATE_CATALOG_SCHEMA_PATH + "." + CatalogSchemaDescriptor.ALL_ENTITY_SCHEMAS.name() + "." + NamedSchemaDescriptor.NAME.name(),
				not(containsInRelativeOrder(NEW_COLLECTION_NAME))
			);
	}

	private int getCatalogSchemaVersion(@Nonnull GraphQLTester tester) {
		return tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
				query {
					getCatalogSchema {
						version
					}
				}
				"""
			)
			.executeAndThen()
			.extract()
			.jsonPath()
			.get(CATALOG_SCHEMA_PATH + "." + VersionedDescriptor.VERSION.name());
	}

	private void removeCollection(@Nonnull GraphQLTester tester, @Nonnull String entityType, int expectedCatalogVersion) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
                mutation {
                    updateCatalogSchema (
                        mutations: [
                            {
                                removeEntitySchemaMutation: {
                                    name: "%s"
                                }
                            }
                        ]
                    ) {
                        version
                    }
                }
				""",
				entityType
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_CATALOG_SCHEMA_PATH,
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), expectedCatalogVersion)
						.build()
				)
			);
	}
}
