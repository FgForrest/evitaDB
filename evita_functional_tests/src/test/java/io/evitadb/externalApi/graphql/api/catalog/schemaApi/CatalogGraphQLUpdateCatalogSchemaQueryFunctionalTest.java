/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.api.catalog.schemaApi;

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ReferenceSchemaDescriptor;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLTester;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static org.hamcrest.Matchers.*;

/**
 * Tests for GraphQL updating catalog schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLUpdateCatalogSchemaQueryFunctionalTest extends CatalogGraphQLSchemaEndpointFunctionalTest {

	private static final String ERRORS_PATH = "errors";
	private static final String CATALOG_SCHEMA_PATH = "data.get_catalog_schema";
	private static final String UPDATE_CATALOG_SCHEMA_PATH = "data.update_catalog_schema";
	private static final String MY_NEW_COLLECTION_SCHEMA_PATH = "data.get_myNewCollection_schema";
	private static final String NEW_COLLECTION_NAME = "myNewCollection";


	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing mutations when updating catalog schema")
	void shouldReturnErrorForMissingMutationsWhenUpdatingCatalogSchema(GraphQLTester tester) {
		tester.test(TEST_CATALOG_SCHEMA)
			.document(
				"""
				mutation {
					update_catalog_schema {
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
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should not update catalog schema when no mutations")
	void shouldNotUpdateCatalogSchemaWhenNoMutations(GraphQLTester tester) {
		final int initialCatalogSchemVersion = getCatalogSchemaVersion(tester);

		tester.test(TEST_CATALOG_SCHEMA)
			.document(
				"""
				mutation {
					update_catalog_schema (
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
						.e(CatalogSchemaDescriptor.VERSION.name(), initialCatalogSchemVersion)
						.build()
				)
			);

	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should change description of catalog schema")
	void shouldChangeDescriptionOfCatalogSchema(GraphQLTester tester) {
		final int initialCatalogSchemVersion = getCatalogSchemaVersion(tester);

		tester.test(TEST_CATALOG_SCHEMA)
			.document(
				"""
				mutation {
					update_catalog_schema (
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
						.e(CatalogSchemaDescriptor.VERSION.name(), initialCatalogSchemVersion + 1)
						.e(CatalogSchemaDescriptor.DESCRIPTION.name(), "desc")
						.build()
				)
			);

	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should create new catalog attribute schema")
	void shouldCreateNewCatalogAttributeSchema(GraphQLTester tester) {
		final int initialCatalogSchemVersion = getCatalogSchemaVersion(tester);

		tester.test(TEST_CATALOG_SCHEMA)
			.document(
				"""
				mutation {
					update_catalog_schema (
						mutations: [
							{
								createGlobalAttributeSchemaMutation: {
									name: "mySpecialCode"
									unique: true
									uniqueGlobally: true
									filterable: true
									sortable: true
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
						.e(CatalogSchemaDescriptor.VERSION.name(), initialCatalogSchemVersion + 1)
						.build()
				)
			);

		tester.test(TEST_CATALOG_SCHEMA)
			.document(
				"""
                query {
                    get_catalog_schema {
                        attributes {
                            mySpecialCode {
								name
								description
								deprecationNotice
								unique
								uniqueGlobally
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
								.e(GlobalAttributeSchemaDescriptor.NAME.name(), "mySpecialCode")
								.e(GlobalAttributeSchemaDescriptor.DESCRIPTION.name(), null)
								.e(GlobalAttributeSchemaDescriptor.DEPRECATION_NOTICE.name(), null)
								.e(GlobalAttributeSchemaDescriptor.UNIQUE.name(), true)
								.e(GlobalAttributeSchemaDescriptor.UNIQUE_GLOBALLY.name(), true)
								.e(GlobalAttributeSchemaDescriptor.FILTERABLE.name(), true)
								.e(GlobalAttributeSchemaDescriptor.SORTABLE.name(), true)
								.e(GlobalAttributeSchemaDescriptor.LOCALIZED.name(), false)
								.e(GlobalAttributeSchemaDescriptor.NULLABLE.name(), false)
								.e(GlobalAttributeSchemaDescriptor.TYPE.name(), String.class.getSimpleName())
								.e(GlobalAttributeSchemaDescriptor.DEFAULT_VALUE.name(), null)
								.e(GlobalAttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), 0)
								.build())
							.build())
						.build()
				)
			);

		// revert
		tester.test(TEST_CATALOG_SCHEMA)
			.document(
				"""
				mutation {
					update_catalog_schema (
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
						.e(CatalogSchemaDescriptor.VERSION.name(), initialCatalogSchemVersion + 2)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should create and remove new empty entity schema")
	void shouldCreateAndRemoveNewEmptyEntitySchema(GraphQLTester tester) {
		final int initialCatalogSchemaVersion = getCatalogSchemaVersion(tester);

		// create collection
		tester.test(TEST_CATALOG_SCHEMA)
			.document(
				"""
				mutation {
					update_catalog_schema (
						mutations: [
							{
								createEntitySchemaMutation: {
									entityType: "%s"
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
						.e(CatalogSchemaDescriptor.VERSION.name(), initialCatalogSchemaVersion + 1)
						.build()
				)
			);

		// verify new collection schema
		tester.test(TEST_CATALOG_SCHEMA)
			.document(
				"""
                query {
                    get_myNewCollection_schema {
                        name
						version
						allAttributes {
							... on AttributeSchema {
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
						.e(EntitySchemaDescriptor.NAME.name(), NEW_COLLECTION_NAME)
						.e(EntitySchemaDescriptor.VERSION.name(), 1)
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
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should create and remove new filled entity schema")
	void shouldCreateAndRemoveNewFilledEntitySchema(GraphQLTester tester) {
		final int initialCatalogSchemaVersion = getCatalogSchemaVersion(tester);

		// create collection
		tester.test(TEST_CATALOG_SCHEMA)
			.document(
				"""
				mutation {
					update_catalog_schema (
						mutations: [
							{
								createEntitySchemaMutation: {
									entityType: "%s"
								},
								modifyEntitySchemaMutation: {
									entityType: "%s",
									schemaMutations: [
										{
											createAttributeSchemaMutation: {
												name: "code"
												unique: true
												filterable: true
												sortable: true
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
												filterable: true
												faceted: true
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
						.e(CatalogSchemaDescriptor.VERSION.name(), initialCatalogSchemaVersion + 1)
						.build()
				)
			);

		// verify new collection schema
		tester.test(TEST_CATALOG_SCHEMA)
			.document(
				"""
                query {
                    get_myNewCollection_schema {
                        name
						version
						attributes {
							code {
								name
								description
								deprecationNotice
								unique
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
								filterable
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
						.e(EntitySchemaDescriptor.NAME.name(), NEW_COLLECTION_NAME)
						.e(EntitySchemaDescriptor.VERSION.name(), 3)
						.e(EntitySchemaDescriptor.ATTRIBUTES.name(), map()
							.e(ATTRIBUTE_CODE, map()
								.e(AttributeSchemaDescriptor.NAME.name(), ATTRIBUTE_CODE)
								.e(AttributeSchemaDescriptor.DESCRIPTION.name(), null)
								.e(AttributeSchemaDescriptor.DEPRECATION_NOTICE.name(), null)
								.e(AttributeSchemaDescriptor.UNIQUE.name(), true)
								.e(AttributeSchemaDescriptor.FILTERABLE.name(), true)
								.e(AttributeSchemaDescriptor.SORTABLE.name(), true)
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
								.e(ReferenceSchemaDescriptor.NAME.name(), "tags")
								.e(ReferenceSchemaDescriptor.DESCRIPTION.name(), null)
								.e(ReferenceSchemaDescriptor.DEPRECATION_NOTICE.name(), null)
								.e(ReferenceSchemaDescriptor.CARDINALITY.name(), Cardinality.ZERO_OR_MORE.name())
								.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
								.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), false)
								.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE.name(), null)
								.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), false)
								.e(ReferenceSchemaDescriptor.FILTERABLE.name(), true)
								.e(ReferenceSchemaDescriptor.FACETED.name(), true)
								.build())
							.build())
						.build()
				)
			);

		// remove new collection
		removeCollection(tester, NEW_COLLECTION_NAME, initialCatalogSchemaVersion + 2);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should rename entity schema")
	void shouldRenameEntitySchema(GraphQLTester tester) {
		final int initialCatalogSchemaVersion = getCatalogSchemaVersion(tester);

		// rename existing collection
		tester.test(TEST_CATALOG_SCHEMA)
			.document(
				"""
				mutation {
					update_catalog_schema (
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
				UPDATE_CATALOG_SCHEMA_PATH + "." + CatalogSchemaDescriptor.VERSION.name(),
				equalTo(initialCatalogSchemaVersion + 1)
			)
			.body(
				UPDATE_CATALOG_SCHEMA_PATH + "." + CatalogSchemaDescriptor.ALL_ENTITY_SCHEMAS.name() + "." + EntitySchemaDescriptor.NAME.name(),
				containsInRelativeOrder(NEW_COLLECTION_NAME)
			)
			.body(
				UPDATE_CATALOG_SCHEMA_PATH + "." + CatalogSchemaDescriptor.ALL_ENTITY_SCHEMAS.name() + "." + EntitySchemaDescriptor.NAME.name(),
				not(containsInRelativeOrder(Entities.PRODUCT))
			);

		// rename collection back
		tester.test(TEST_CATALOG_SCHEMA)
			.document(
				"""
				mutation {
					update_catalog_schema (
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
				UPDATE_CATALOG_SCHEMA_PATH + "." + CatalogSchemaDescriptor.VERSION.name(),
				equalTo(initialCatalogSchemaVersion + 2)
			)
			.body(
				UPDATE_CATALOG_SCHEMA_PATH + "." + CatalogSchemaDescriptor.ALL_ENTITY_SCHEMAS.name() + "." + EntitySchemaDescriptor.NAME.name(),
				containsInRelativeOrder(Entities.PRODUCT)
			)
			.body(
				UPDATE_CATALOG_SCHEMA_PATH + "." + CatalogSchemaDescriptor.ALL_ENTITY_SCHEMAS.name() + "." + EntitySchemaDescriptor.NAME.name(),
				not(containsInRelativeOrder(NEW_COLLECTION_NAME))
			);
	}

	private int getCatalogSchemaVersion(@Nonnull GraphQLTester tester) {
		return tester.test(TEST_CATALOG_SCHEMA)
			.document(
				"""
				query {
					get_catalog_schema {
						version
					}
				}
				"""
			)
			.executeAndThen()
			.extract()
			.jsonPath()
			.get(CATALOG_SCHEMA_PATH + "." + CatalogSchemaDescriptor.VERSION.name());
	}

	private void removeCollection(@Nonnull GraphQLTester tester, @Nonnull String entityType, int expectedCatalogVersion) {
		tester.test(TEST_CATALOG_SCHEMA)
			.document(
				"""
                mutation {
                    update_catalog_schema (
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
						.e(CatalogSchemaDescriptor.VERSION.name(), expectedCatalogVersion)
						.build()
				)
			);
	}
}
