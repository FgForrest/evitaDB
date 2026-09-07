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

package io.evitadb.externalApi.rest.api.catalog.dataApi;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ReferenceSummaryDescriptor;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity.RestEntityDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.extraResult.LevelInfoDescriptor;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests of the two sibling parent properties of a hierarchical REST entity object - `parentEntity`, which reports the
 * ancestor axis under {@link io.evitadb.api.query.require.HierarchyParentsBehaviour#MATCHING}, and
 * `parentEntityComplete`, which reports it under
 * {@link io.evitadb.api.query.require.HierarchyParentsBehaviour#COMPLETE} through a `oneOf` of the entity object and
 * the bodyless pointer standing in for an ancestor whose body could not be materialized.
 *
 * See issue #1365 (https://github.com/FgForrest/evitaDB/issues/1365) and
 * `documentation/adr/2026-08-03-hierarchy-content-parents-behaviour.md`.
 *
 * The fixture is the shape the whole feature exists for: a chain whose middle ancestor holds no English data, with
 * a materializable root above it. Querying entity 4 in English therefore resolves `3` with a body, `2` as a bodyless
 * pointer and `1` with a body again. A second, separate chain - `5` and `6` in Czech below the English `7` - covers
 * the opposite extreme, in which no ancestor at all can be materialized under an English query locale.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(HIERARCHY)
public class CatalogRestHierarchyContentParentsFunctionalTest extends CatalogRestDataEndpointFunctionalTest {

	private static final String DATA_SET = "RestHierarchyContentParents";
	private static final String CATEGORY_LIST_URL = "/CATEGORY/list";
	private static final String CATEGORY_QUERY_URL = "/CATEGORY/query";
	private static final String PRODUCT_LIST_URL = "/PRODUCT/list";
	private static final String PRODUCT_QUERY_URL = "/PRODUCT/query";
	private static final Locale LOCALE_CZECH = new Locale("cs");
	/**
	 * Primary key of the single product holding a reference to the leaf of the mixed chain.
	 */
	private static final int PRODUCT_PRIMARY_KEY = 100;
	/**
	 * Primary key of the product holding a reference to the leaf of the chain whose every ancestor holds Czech data
	 * only - the chain on which a requirement asking for ancestor bodies materializes none of them.
	 */
	private static final int PRODUCT_WITH_BODYLESS_CHAIN_PRIMARY_KEY = 101;
	/**
	 * Path to the category reached through the product's reference in a `/PRODUCT/list` response.
	 */
	private static final String REFERENCED_CATEGORY_PATH = "[0].category.referencedEntity";
	/**
	 * Path to the bodyless pointer object of the `parentEntity` axis in the published OpenAPI document.
	 */
	private static final String POINTER_SCHEMA_PATH = "components.schemas.CategoryParentPointer";
	/**
	 * Path to the bodyless pointer object of the `parentEntityComplete` axis in the published OpenAPI document.
	 */
	private static final String COMPLETE_POINTER_SCHEMA_PATH = "components.schemas.CategoryCompleteParentPointer";
	/**
	 * Path to the single node of the hierarchy statistics tree published under the `subTree` output name.
	 */
	private static final String SUB_TREE_NODE_PATH =
		ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY.name() + "." +
			HierarchyDescriptor.SELF.name() + ".subTree[0]." + LevelInfoDescriptor.ENTITY.name();
	/**
	 * Path to the facet entity of the single facet reported for the `category` reference in a `/PRODUCT/query`
	 * response. The reference declares no group type, so its statistics are published as a single object rather than
	 * as an array of groups.
	 */
	private static final String FACET_ENTITY_PATH =
		ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.FACET_SUMMARY.name() + ".category." +
			FacetSummaryDescriptor.FacetGroupStatisticsDescriptor.FACET_STATISTICS.name() + "[0]." +
			ReferenceSummaryDescriptor.FacetStatisticsDescriptor.FACET_ENTITY.name();

	@DataSet(value = DATA_SET, openWebApi = RestProvider.CODE, readOnly = false, destroyAfterClass = true)
	DataCarrier setUpHierarchyContentParents(Evita evita, EvitaServer evitaServer) {
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

				// a second, separate chain in which *no* ancestor can be materialized under an English query locale
				createCategory(session, 5, null, LOCALE_CZECH);
				createCategory(session, 6, 5, LOCALE_CZECH);
				createCategory(session, 7, 6, Locale.ENGLISH);

				// a non-hierarchical collection referencing the leaf of the mixed chain, so that the parent axis can
				// be exercised on an entity reached through `referenceContent` rather than as a query result
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().nullable())
					.withReferenceToEntity(
						Entities.CATEGORY, Entities.CATEGORY, Cardinality.EXACTLY_ONE,
						// the reference carries an attribute of its own purely so that the second named
						// `referenceContent` variant - the one taking an `attributeContent` - is published for it, and
						// two sibling requirements covering the same reference can therefore be written at all. It is
						// faceted so that the same categories can also be reached as facets of a facet summary, where
						// the parent axis is serialized through an entirely different path
						whichIs -> whichIs.indexed()
							.faceted()
							.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::nullable)
					)
					.updateAndFetchVia(session);

				createProduct(session, PRODUCT_PRIMARY_KEY, 4);
				createProduct(session, PRODUCT_WITH_BODYLESS_CHAIN_PRIMARY_KEY, 7);
			}
		);
		return new DataCarrier();
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should report a body above a bodyless pointer in parentEntityComplete and cut parentEntity below it")
	void shouldReportCompleteChainNextToTheMatchingOne(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix(CATEGORY_LIST_URL)
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [4],
						"entityLocaleEquals": "en"
					},
					"require": {
						"entityFetch": {
							"hierarchyContent": {
								"parentsBehaviour": "COMPLETE",
								"entityFetch": {
									"attributeContent": ["name"]
								}
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body("", hasSize(1))
			.body("[0].primaryKey", equalTo(4))
			// the matching view is cut just below the ancestor that could not be materialized
			.body("[0].parentEntity.primaryKey", equalTo(3))
			.body("[0].parentEntity.attributes.localized.en.name", equalTo("Category 3"))
			.body("[0].parentEntity.parentEntity", nullValue())
			.body("[0].parentEntity.parentEntityComplete", nullValue())
			// the complete view keeps the bodyless ancestor and walks past it up to a body again
			.body("[0].parentEntityComplete.primaryKey", equalTo(3))
			.body("[0].parentEntityComplete.attributes.localized.en.name", equalTo("Category 3"))
			.body("[0].parentEntityComplete.parentEntityComplete.primaryKey", equalTo(2))
			.body("[0].parentEntityComplete.parentEntityComplete.type", equalTo(Entities.CATEGORY))
			.body("[0].parentEntityComplete.parentEntityComplete.version", nullValue())
			.body("[0].parentEntityComplete.parentEntityComplete.attributes", nullValue())
			.body("[0].parentEntityComplete.parentEntityComplete.parentEntityComplete.primaryKey", equalTo(1))
			.body(
				"[0].parentEntityComplete.parentEntityComplete.parentEntityComplete.attributes.localized.en.name",
				equalTo("Category 1")
			)
			.body(
				"[0].parentEntityComplete.parentEntityComplete.parentEntityComplete.parentEntityComplete",
				nullValue()
			);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should report only the matching chain when the default parents behaviour is asked for")
	void shouldReportMatchingChainUnderTheDefaultBehaviour(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix(CATEGORY_LIST_URL)
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [4],
						"entityLocaleEquals": "en"
					},
					"require": {
						"entityFetch": {
							"hierarchyContent": {
								"entityFetch": {
									"attributeContent": ["name"]
								}
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body("", hasSize(1))
			.body("[0].primaryKey", equalTo(4))
			.body("[0].parentEntity.primaryKey", equalTo(3))
			.body("[0].parentEntity.parentEntity", nullValue())
			// a matching chain holds no pointer, so the complete property has nothing to add
			.body("[0].parentEntityComplete", nullValue());
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should bound the complete chain by stopAt just like the matching one")
	void shouldBoundBothParentViewsByStopAt(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix(CATEGORY_LIST_URL)
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [4],
						"entityLocaleEquals": "en"
					},
					"require": {
						"entityFetch": {
							"hierarchyContent": {
								"parentsBehaviour": "COMPLETE",
								"stopAt": {
									"distance": 2
								},
								"entityFetch": {
									"attributeContent": ["name"]
								}
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body("", hasSize(1))
			.body("[0].parentEntity.primaryKey", equalTo(3))
			.body("[0].parentEntity.parentEntity", nullValue())
			.body("[0].parentEntityComplete.primaryKey", equalTo(3))
			.body("[0].parentEntityComplete.parentEntityComplete.primaryKey", equalTo(2))
			// the bound cuts the root away, so the pointer is the topmost element the complete view reports
			.body("[0].parentEntityComplete.parentEntityComplete.parentEntityComplete", nullValue());
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should report the whole primary key chain when no ancestor body is asked for")
	void shouldReportWholeKeyChainWithoutBodies(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix(CATEGORY_LIST_URL)
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [4],
						"entityLocaleEquals": "en"
					},
					"require": {
						"entityFetch": {
							"hierarchyContent": {}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body("", hasSize(1))
			.body("[0].parentEntity.primaryKey", equalTo(3))
			.body("[0].parentEntity.parentEntity.primaryKey", equalTo(2))
			.body("[0].parentEntity.parentEntity.parentEntity.primaryKey", equalTo(1))
			.body("[0].parentEntityComplete", nullValue())
			// every element of this chain must validate against the bodyless branch of the `parentEntity` union and
			// against that branch alone: it carries the classifier and the recursive link and nothing else, so it
			// supplies none of the `version`, `scope` and locale properties the entity branch marks required, and it
			// declares no property the closed pointer branch would refuse
			.body("[0].parentEntity", aMapWithSize(3))
			.body("[0].parentEntity.type", equalTo(Entities.CATEGORY))
			.body("[0].parentEntity.version", nullValue())
			.body("[0].parentEntity.scope", nullValue())
			.body("[0].parentEntity.locales", nullValue())
			.body("[0].parentEntity.allLocales", nullValue())
			.body("[0].parentEntity.parentEntity", aMapWithSize(3))
			// the root of the chain has nothing above it, so it carries the classifier alone
			.body("[0].parentEntity.parentEntity.parentEntity", aMapWithSize(2));
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should report the complete chain even when no ancestor could be materialized")
	void shouldReportTheCompleteChainWhenNothingMaterialized(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix(CATEGORY_LIST_URL)
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [7],
						"entityLocaleEquals": "en"
					},
					"require": {
						"entityFetch": {
							"hierarchyContent": {
								"parentsBehaviour": "COMPLETE",
								"entityFetch": {
									"attributeContent": ["name"]
								}
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body("", hasSize(1))
			.body("[0].primaryKey", equalTo(7))
			// the requirement asked for ancestor bodies, so neither ancestor is a plain primary key - both are
			// pointers standing in for a body that could not be materialized. The chain cut below the first of them
			// is therefore empty and `parentEntity`, which is declared as the entity object and would have to carry
			// a version and a scope, is absent altogether; the whole chain is reported under the property the caller
			// actually asked for.
			.body("[0].parentEntity", nullValue())
			.body("[0].parentEntityComplete.primaryKey", equalTo(6))
			.body("[0].parentEntityComplete.type", equalTo(Entities.CATEGORY))
			.body("[0].parentEntityComplete.version", nullValue())
			.body("[0].parentEntityComplete.parentEntityComplete.primaryKey", equalTo(5))
			.body("[0].parentEntityComplete.parentEntityComplete.version", nullValue())
			.body("[0].parentEntityComplete.parentEntityComplete.parentEntityComplete", nullValue());
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should type the parent axis of a hierarchy statistics node against the hierarchy requirement")
	void shouldTypeTheParentAxisOfAHierarchyStatisticsNode(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix(CATEGORY_QUERY_URL)
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityLocaleEquals": "en"
					},
					"require": {
						"page": {
							"number": 1,
							"size": 0
						},
						"hierarchyOfSelf": {
							"requirements": [
								{
									"fromNode": {
										"outputName": "subTree",
										"node": {
											"filterBy": {
												"entityPrimaryKeyInSet": [7]
											}
										},
										"entityFetch": {
											"hierarchyContent": {
												"parentsBehaviour": "COMPLETE",
												"entityFetch": {
													"attributeContent": ["name"]
												}
											}
										}
									}
								}
							]
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(SUB_TREE_NODE_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(), equalTo(7))
			// the node is fetched through the `entityFetch` written inside the hierarchy constraint, and that
			// requirement asked for ancestor bodies - neither ancestor of `7` holds English data, so both are bodyless
			// pointers rather than plain primary keys. `parentEntity` is declared as the entity object and would have
			// to carry a version and a scope, so the chain cut below the first pointer is empty and the property is
			// absent altogether; the pointers are reported under the union-typed sibling instead
			.body(SUB_TREE_NODE_PATH + "." + RestEntityDescriptor.PARENT_ENTITY.name(), nullValue())
			.body(SUB_TREE_NODE_PATH + "." + RestEntityDescriptor.PARENT_ENTITY_COMPLETE.name() + "." +
				EntityDescriptor.PRIMARY_KEY.name(), equalTo(6))
			.body(SUB_TREE_NODE_PATH + "." + RestEntityDescriptor.PARENT_ENTITY_COMPLETE.name() + ".version",
				nullValue())
			.body(SUB_TREE_NODE_PATH + "." + RestEntityDescriptor.PARENT_ENTITY_COMPLETE.name() + "." +
				RestEntityDescriptor.PARENT_ENTITY_COMPLETE.name() + "." + EntityDescriptor.PRIMARY_KEY.name(),
				equalTo(5))
			.body(SUB_TREE_NODE_PATH + "." + RestEntityDescriptor.PARENT_ENTITY_COMPLETE.name() + "." +
				RestEntityDescriptor.PARENT_ENTITY_COMPLETE.name() + ".version", nullValue());
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should type the parent axis of a facet entity against the facet summary requirement")
	void shouldTypeTheParentAxisOfAFacetEntity(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix(PRODUCT_QUERY_URL)
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [101],
						"entityLocaleEquals": "en"
					},
					"require": {
						"page": {
							"number": 1,
							"size": 0
						},
						"facetCategorySummary": {
							"statisticsDepth": "COUNTS",
							"requirements": {
								"entityFetch": {
									"hierarchyContent": {
										"parentsBehaviour": "COMPLETE",
										"entityFetch": {
											"attributeContent": ["name"]
										}
									}
								}
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			// the facet is fetched through the `entityFetch` written inside the facet summary constraint, and that
			// requirement asked for ancestor bodies - neither ancestor of `7` holds English data, so both are bodyless
			// pointers rather than plain primary keys. `parentEntity` is declared as the entity object and would have
			// to carry a version and a scope, so the chain cut below the first pointer is empty and the property is
			// absent altogether; the pointers are reported under the union-typed sibling instead
			.body(FACET_ENTITY_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(), equalTo(7))
			.body(FACET_ENTITY_PATH + "." + RestEntityDescriptor.PARENT_ENTITY.name(), nullValue())
			.body(FACET_ENTITY_PATH + "." + RestEntityDescriptor.PARENT_ENTITY_COMPLETE.name() + "." +
				EntityDescriptor.PRIMARY_KEY.name(), equalTo(6))
			.body(FACET_ENTITY_PATH + "." + RestEntityDescriptor.PARENT_ENTITY_COMPLETE.name() + ".version",
				nullValue())
			.body(FACET_ENTITY_PATH + "." + RestEntityDescriptor.PARENT_ENTITY_COMPLETE.name() + "." +
				RestEntityDescriptor.PARENT_ENTITY_COMPLETE.name() + "." + EntityDescriptor.PRIMARY_KEY.name(),
				equalTo(5))
			.body(FACET_ENTITY_PATH + "." + RestEntityDescriptor.PARENT_ENTITY_COMPLETE.name() + "." +
				RestEntityDescriptor.PARENT_ENTITY_COMPLETE.name() + ".version", nullValue());
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should omit both parent properties when hierarchyContent was not requested")
	void shouldOmitBothParentPropertiesWhenTheHierarchyWasNotFetched(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix(CATEGORY_LIST_URL)
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [4]
					},
					"require": {
						"entityFetch": {
							"attributeContent": ["code"]
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body("", hasSize(1))
			.body("[0].primaryKey", equalTo(4))
			.body("[0].attributes.global.code", equalTo("category-4"))
			// the hierarchy placement was never fetched, so asking the entity for its parent would throw - both
			// properties have to stay absent rather than the serializer walking an axis it may not read
			.body("[0].parentEntity", nullValue())
			.body("[0].parentEntityComplete", nullValue());
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should omit parentEntityComplete when every ancestor carried its body")
	void shouldNotEmitTheCompleteChainForAFullyMaterializedChain(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix(CATEGORY_LIST_URL)
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [4]
					},
					"require": {
						"entityFetch": {
							"hierarchyContent": {
								"parentsBehaviour": "COMPLETE",
								"entityFetch": {
									"attributeContent": ["code"]
								}
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body("", hasSize(1))
			// `code` is the global attribute every fixture node carries and no locale bounds the query, so every
			// ancestor materializes - the presence of `parentEntityComplete` must track the data, not the fact that
			// `COMPLETE` was asked for
			.body("[0].parentEntity.primaryKey", equalTo(3))
			.body("[0].parentEntity.attributes.global.code", equalTo("category-3"))
			.body("[0].parentEntity.parentEntity.primaryKey", equalTo(2))
			.body("[0].parentEntity.parentEntity.attributes.global.code", equalTo("category-2"))
			.body("[0].parentEntity.parentEntity.parentEntity.primaryKey", equalTo(1))
			.body("[0].parentEntity.parentEntity.parentEntity.parentEntity", nullValue())
			.body("[0].parentEntityComplete", nullValue());
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should serialize the parent axis of a referenced entity")
	void shouldSerializeTheParentAxisOfAReferencedEntity(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix(PRODUCT_LIST_URL)
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [%d],
						"entityLocaleEquals": "en"
					},
					"require": {
						"entityFetch": {
							"referenceCategoryContent": {
								"entityFetch": {
									"hierarchyContent": {
										"parentsBehaviour": "COMPLETE",
										"entityFetch": {
											"attributeContent": ["name"]
										}
									}
								}
							}
						}
					}
				}
				""", PRODUCT_PRIMARY_KEY)
			.executeAndExpectOkAndThen()
			.body("", hasSize(1))
			.body("[0].primaryKey", equalTo(PRODUCT_PRIMARY_KEY))
			// a referenced entity is serialized through the very same entity writer, so it must carry both parent
			// properties in the same shape a top-level entity would
			.body(REFERENCED_CATEGORY_PATH + ".primaryKey", equalTo(4))
			.body(REFERENCED_CATEGORY_PATH + ".parentEntity.primaryKey", equalTo(3))
			.body(REFERENCED_CATEGORY_PATH + ".parentEntity.parentEntity", nullValue())
			.body(REFERENCED_CATEGORY_PATH + ".parentEntityComplete.primaryKey", equalTo(3))
			.body(REFERENCED_CATEGORY_PATH + ".parentEntityComplete.parentEntityComplete.primaryKey", equalTo(2))
			.body(REFERENCED_CATEGORY_PATH + ".parentEntityComplete.parentEntityComplete.version", nullValue())
			.body(
				REFERENCED_CATEGORY_PATH
					+ ".parentEntityComplete.parentEntityComplete.parentEntityComplete.primaryKey",
				equalTo(1)
			);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should read the hierarchyContent from every sibling referenceContent covering the reference")
	void shouldCombineSiblingReferenceContentRequirementsCoveringOneReference(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix(PRODUCT_LIST_URL)
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [%d],
						"entityLocaleEquals": "en"
					},
					"require": {
						"entityFetch": {
							"referenceCategoryContentWithAttributes": {
								"attributeContentAll": true,
								"entityFetch": {
									"hierarchyContent": {
										"parentsBehaviour": "COMPLETE",
										"entityFetch": {
											"attributeContent": ["name"]
										}
									}
								}
							},
							"referenceCategoryContent": {
								"entityFetch": {
									"attributeContent": ["code"]
								}
							}
						}
					}
				}
				""", PRODUCT_WITH_BODYLESS_CHAIN_PRIMARY_KEY)
			.executeAndExpectOkAndThen()
			.body("", hasSize(1))
			.body("[0].primaryKey", equalTo(PRODUCT_WITH_BODYLESS_CHAIN_PRIMARY_KEY))
			.body(REFERENCED_CATEGORY_PATH + ".primaryKey", equalTo(7))
			// both siblings cover `category` and the `hierarchyContent` sits in only one of them - the serializer has
			// to reduce them rather than take whichever document order put first. No ancestor of `7` holds English
			// data, so the requirement is the only thing that can tell a bodyless pointer from a plain primary key:
			// read from the wrong sibling, the whole chain is written into `parentEntity` as plain keys instead.
			// Only the ancestor axis is asserted, because the engine resolves the reference to a single sibling
			// rather than to their union - the `attributeContent` of the other one is never served.
			.body(REFERENCED_CATEGORY_PATH + ".parentEntity", nullValue())
			.body(REFERENCED_CATEGORY_PATH + ".parentEntityComplete.primaryKey", equalTo(6))
			.body(REFERENCED_CATEGORY_PATH + ".parentEntityComplete.version", nullValue())
			.body(REFERENCED_CATEGORY_PATH + ".parentEntityComplete.parentEntityComplete.primaryKey", equalTo(5))
			.body(REFERENCED_CATEGORY_PATH + ".parentEntityComplete.parentEntityComplete.version", nullValue())
			.body(
				REFERENCED_CATEGORY_PATH + ".parentEntityComplete.parentEntityComplete.parentEntityComplete",
				nullValue()
			);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should declare the complete parent union and its pointer object in the OpenAPI schema")
	void shouldDeclareTheParentUnionInTheOpenApiSchema(RestTester tester) {
		final String parentEntityComplete = RestEntityDescriptor.PARENT_ENTITY_COMPLETE.name();
		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_GET)
			.executeAndExpectOkAndThen()
			.body(COMPLETE_POINTER_SCHEMA_PATH + ".required", hasSize(2))
			.body(
				COMPLETE_POINTER_SCHEMA_PATH + ".required",
				containsInAnyOrder(EntityDescriptor.PRIMARY_KEY.name(), EntityDescriptor.TYPE.name())
			)
			// the recursive link is what lets the axis continue above a pointer
			.body(COMPLETE_POINTER_SCHEMA_PATH + ".properties." + parentEntityComplete, notNullValue())
			.body(
				"components.schemas.Category.properties." + parentEntityComplete + ".$ref",
				equalTo("#/components/schemas/CategoryCompleteParentUnion")
			)
			.body("components.schemas.CategoryCompleteParentUnion.oneOf", hasSize(2))
			.body(
				"components.schemas.CategoryCompleteParentUnion.oneOf.$ref",
				containsInAnyOrder(
					"#/components/schemas/Category",
					"#/components/schemas/CategoryCompleteParentPointer"
				)
			)
			// what makes the two `oneOf` branches mutually exclusive: the pointer branch declares no property beyond
			// the classifier and the recursive link, and refuses every other one, so a materialized ancestor fails
			// it on its `version` and matches the entity branch alone
			.body(COMPLETE_POINTER_SCHEMA_PATH + ".additionalProperties", equalTo(false));
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should declare the parent union and its pointer object in the OpenAPI schema")
	void shouldDeclareTheParentEntityUnionInTheOpenApiSchema(RestTester tester) {
		final String parentEntity = RestEntityDescriptor.PARENT_ENTITY.name();
		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_GET)
			.executeAndExpectOkAndThen()
			// `parentEntity` cannot be typed as the entity object alone: a `hierarchyContent` asking for no ancestor
			// body reports the whole primary-key chain here, and those elements supply none of the properties that
			// object marks required
			.body(
				"components.schemas.Category.properties." + parentEntity + ".$ref",
				equalTo("#/components/schemas/CategoryParentUnion")
			)
			.body("components.schemas.CategoryParentUnion.oneOf", hasSize(2))
			.body(
				"components.schemas.CategoryParentUnion.oneOf.$ref",
				containsInAnyOrder("#/components/schemas/Category", "#/components/schemas/CategoryParentPointer")
			)
			.body(POINTER_SCHEMA_PATH + ".required", hasSize(2))
			.body(
				POINTER_SCHEMA_PATH + ".required",
				containsInAnyOrder(EntityDescriptor.PRIMARY_KEY.name(), EntityDescriptor.TYPE.name())
			)
			// this chain nests through `parentEntity`, so its pointer cannot be the one the complete axis uses - that
			// one is closed around `parentEntityComplete` and would refuse the link this chain actually carries
			.body(POINTER_SCHEMA_PATH + ".properties." + parentEntity, notNullValue())
			.body(
				POINTER_SCHEMA_PATH + ".properties." + parentEntity + ".$ref",
				equalTo("#/components/schemas/CategoryParentUnion")
			)
			.body(POINTER_SCHEMA_PATH + ".additionalProperties", equalTo(false));
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

	/**
	 * Creates an English product referencing a single category, with the reference carrying its own `code` attribute.
	 *
	 * @param session            the session to upsert through
	 * @param primaryKey         the primary key to assign
	 * @param categoryPrimaryKey the primary key of the referenced category
	 */
	private static void createProduct(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		int categoryPrimaryKey
	) {
		final String referenceCode = "product-" + primaryKey + "-category-" + categoryPrimaryKey;
		session.createNewEntity(Entities.PRODUCT, primaryKey)
			.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Product " + primaryKey)
			.setReference(
				Entities.CATEGORY, categoryPrimaryKey,
				whichIs -> whichIs.setAttribute(ATTRIBUTE_CODE, referenceCode)
			)
			.upsertVia(session);
	}
}
