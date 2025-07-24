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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.GraphQLTester;
import io.evitadb.utils.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.ENTITY_EMPTY;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.ListBuilder.list;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static org.hamcrest.Matchers.*;

/**
 * Tests for GraphQL updating entity schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLUpdateEntitySchemaQueryFunctionalTest extends CatalogGraphQLEvitaSchemaEndpointFunctionalTest {

	private static final String EMPTY_SCHEMA_PATH = "data.getEmptySchema";
	private static final String UPDATE_EMPTY_SCHEMA_PATH = "data.updateEmptySchema";
	public static final String GRAPHQL_THOUSAND_PRODUCTS_FOR_SCHEMA_CHANGE = GRAPHQL_THOUSAND_PRODUCTS + "forEntitySchemaChange";

	@Override
	@DataSet(value = GRAPHQL_THOUSAND_PRODUCTS_FOR_SCHEMA_CHANGE, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita) {
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


	private int getEntitySchemaVersion(@Nonnull GraphQLTester tester, @Nonnull String entityType) {
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
