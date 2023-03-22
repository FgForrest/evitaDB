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
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AssociatedDataSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ReferenceSchemaDescriptor;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.ENTITY_EMPTY;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.builder.MapBuilder.map;
import static org.hamcrest.Matchers.*;

/**
 * Tests for GraphQL updating entity schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLUpdateEntitySchemaQueryFunctionalTest extends CatalogGraphQLSchemaEndpointFunctionalTest {

	private static final String ERRORS_PATH = "errors";
	private static final String EMPTY_SCHEMA_PATH = "data.get_empty_schema";
	private static final String UPDATE_EMPTY_SCHEMA_PATH = "data.update_empty_schema";


	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing mutations when updating entity schema")
	void shouldReturnErrorForMissingMutationsWhenUpdatingEntitySchema(Evita evita) {
		testGraphQLCall()
			.document(
				"""
				mutation {
					update_empty_schema {
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
	@DisplayName("Should not update entity schema when no mutations")
	void shouldNotUpdateCatalogSchemaWhenNoMutations(Evita evita) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(ENTITY_EMPTY);

		testGraphQLCall()
			.document(
				"""
				mutation {
					update_empty_schema (
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
						.e(CatalogSchemaDescriptor.VERSION.name(), initialEntitySchemaVersion)
						.build()
				)
			);

	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should change entity schema itself")
	void shouldChangeEntitySchemaItself(Evita evita) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(ENTITY_EMPTY);

		// allow new locales
		testGraphQLCall()
			.document(
				"""
				mutation {
					update_empty_schema (
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
						.e(EntitySchemaDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.e(EntitySchemaDescriptor.LOCALES.name(), List.of("fr", "it"))
						.build()
				)
			);

		// revert
		testGraphQLCall()
			.document(
				"""
				mutation {
					update_empty_schema (
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
						.e(EntitySchemaDescriptor.VERSION.name(), initialEntitySchemaVersion + 2)
						.e(EntitySchemaDescriptor.LOCALES.name(), List.of())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should change attribute schema")
	void shouldChangeAttributeSchema(Evita evita) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(ENTITY_EMPTY);

		// add new attribute
		testGraphQLCall()
			.document(
				"""
				mutation {
					update_empty_schema (
						mutations: [
							{
								createAttributeSchemaMutation: {
									name: "mySpecialCode"
									unique: true
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
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(EntitySchemaDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.build()
				)
			);

		// verify new attribute schema
		testGraphQLCall()
			.document(
				"""
                query {
                    get_empty_schema {
                        name
						version
						attributes {
							mySpecialCode {
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
						.e(EntitySchemaDescriptor.NAME.name(), ENTITY_EMPTY)
						.e(EntitySchemaDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.e(EntitySchemaDescriptor.ATTRIBUTES.name(), map()
							.e("mySpecialCode", map()
								.e(AttributeSchemaDescriptor.NAME.name(), "mySpecialCode")
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
						.build()
				)
			);

		// update attribute schema
		testGraphQLCall()
			.document(
				"""
				mutation {
					update_empty_schema (
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
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPDATE_EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(EntitySchemaDescriptor.VERSION.name(), initialEntitySchemaVersion + 2)
						.e(EntitySchemaDescriptor.ATTRIBUTES.name(), map()
							.e("mySpecialCode", map()
								.e(AttributeSchemaDescriptor.DESCRIPTION.name(), "desc")
								.build())
							.build())
						.build()
				)
			);

		// remove attribute
		testGraphQLCall()
			.document(
				"""
				mutation {
					update_empty_schema (
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
				UPDATE_EMPTY_SCHEMA_PATH + "." + EntitySchemaDescriptor.VERSION.name(),
				equalTo(initialEntitySchemaVersion + 3)
			)
			.body(
				UPDATE_EMPTY_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_ATTRIBUTES.name() + "." + AttributeSchemaDescriptor.NAME.name(),
				not(containsInRelativeOrder("mySpecialCode"))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should change associated data schema")
	void shouldChangeAssociatedDataSchema(Evita evita) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(ENTITY_EMPTY);

		// add new associated data
		testGraphQLCall()
			.document(
				"""
				mutation {
					update_empty_schema (
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
						.e(EntitySchemaDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.build()
				)
			);

		// verify new associated data schema
		testGraphQLCall()
			.document(
				"""
                query {
                    get_empty_schema {
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
						.e(EntitySchemaDescriptor.NAME.name(), ENTITY_EMPTY)
						.e(EntitySchemaDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.e(EntitySchemaDescriptor.ASSOCIATED_DATA.name(), map()
							.e("mySpecialLabel", map()
								.e(AssociatedDataSchemaDescriptor.NAME.name(), "mySpecialLabel")
								.e(AssociatedDataSchemaDescriptor.DESCRIPTION.name(), null)
								.e(AssociatedDataSchemaDescriptor.DEPRECATION_NOTICE.name(), null)
								.e(AssociatedDataSchemaDescriptor.TYPE.name(), String.class.getSimpleName())
								.e(AssociatedDataSchemaDescriptor.LOCALIZED.name(), true)
								.e(AssociatedDataSchemaDescriptor.NULLABLE.name(), false)
								.build())
							.build())
						.build()
				)
			);

		// update associated data schema
		testGraphQLCall()
			.document(
				"""
				mutation {
					update_empty_schema (
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
						.e(EntitySchemaDescriptor.VERSION.name(), initialEntitySchemaVersion + 2)
						.e(EntitySchemaDescriptor.ASSOCIATED_DATA.name(), map()
							.e("mySpecialLabel", map()
								.e(AssociatedDataSchemaDescriptor.DESCRIPTION.name(), "desc")
								.build())
							.build())
						.build()
				)
			);

		// remove associated data
		testGraphQLCall()
			.document(
				"""
				mutation {
					update_empty_schema (
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
				UPDATE_EMPTY_SCHEMA_PATH + "." + EntitySchemaDescriptor.VERSION.name(),
				equalTo(initialEntitySchemaVersion + 3)
			)
			.body(
				UPDATE_EMPTY_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_ASSOCIATED_DATA.name() + "." + AssociatedDataSchemaDescriptor.NAME.name(),
				not(containsInRelativeOrder("mySpecialLabel"))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should change reference schema")
	void shouldChangeReferenceSchema(Evita evita) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(ENTITY_EMPTY);

		// add new reference
		testGraphQLCall()
			.document(
				"""
				mutation {
					update_empty_schema (
						mutations: [
							{
								createReferenceSchemaMutation: {
									name: "mySpecialTags"
									referencedEntityType: "tag"
									referencedEntityTypeManaged: false
									referencedGroupTypeManaged: false
									filterable: true
									faceted: true
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
						.e(EntitySchemaDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.build()
				)
			);

		// verify new reference schema
		testGraphQLCall()
			.document(
				"""
                query {
                    get_empty_schema {
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
				EMPTY_SCHEMA_PATH,
				equalTo(
					map()
						.e(EntitySchemaDescriptor.NAME.name(), ENTITY_EMPTY)
						.e(EntitySchemaDescriptor.VERSION.name(), initialEntitySchemaVersion + 1)
						.e(EntitySchemaDescriptor.REFERENCES.name(), map()
							.e("mySpecialTags", map()
								.e(ReferenceSchemaDescriptor.NAME.name(), "mySpecialTags")
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

		// update reference schema
		testGraphQLCall()
			.document(
				"""
				mutation {
					update_empty_schema (
						mutations: [
							{
								modifyReferenceAttributeSchemaMutation: {
									name: "mySpecialTags"
									attributeSchemaMutation: {
										createAttributeSchemaMutation: {
											name: "mySpecialCode"
											unique: false
											filterable: true
											sortable: false
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
				UPDATE_EMPTY_SCHEMA_PATH + "." + EntitySchemaDescriptor.VERSION.name(),
				equalTo(initialEntitySchemaVersion + 2)
			)
			.body(
				UPDATE_EMPTY_SCHEMA_PATH + "." + EntitySchemaDescriptor.REFERENCES.name() + ".mySpecialTags." + ReferenceSchemaDescriptor.ALL_ATTRIBUTES.name() + "." + AttributeSchemaDescriptor.NAME.name(),
				containsInRelativeOrder("mySpecialCode")
			);

		// remove reference
		testGraphQLCall()
			.document(
				"""
				mutation {
					update_empty_schema (
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
				UPDATE_EMPTY_SCHEMA_PATH + "." + EntitySchemaDescriptor.VERSION.name(),
				equalTo(initialEntitySchemaVersion + 3)
			)
			.body(
				UPDATE_EMPTY_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_REFERENCES.name() + "." + ReferenceSchemaDescriptor.NAME.name(),
				not(containsInRelativeOrder("mySpecialTags"))
			);
	}


	private int getEntitySchemaVersion(@Nonnull String entityType) {
		return testGraphQLCall()
			.document(
				"""
				query {
					get_%s_schema {
						version
					}
				}
				""",
				entityType
			)
			.executeAndThen()
			.extract()
			.jsonPath()
			.get("data.get_" + entityType + "_schema." + EntitySchemaDescriptor.VERSION.name());
	}
}
