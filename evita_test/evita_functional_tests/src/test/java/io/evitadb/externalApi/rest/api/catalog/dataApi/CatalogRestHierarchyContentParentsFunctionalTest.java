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
import io.evitadb.core.Evita;
import io.evitadb.externalApi.rest.RestProvider;
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
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
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
 * pointer and `1` with a body again.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(QUERY)
public class CatalogRestHierarchyContentParentsFunctionalTest extends CatalogRestDataEndpointFunctionalTest {

	private static final String DATA_SET = "RestHierarchyContentParents";
	private static final String CATEGORY_LIST_URL = "/CATEGORY/list";
	private static final Locale LOCALE_CZECH = new Locale("cs");

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
			.body("[0].parentEntityComplete.parentEntityComplete.parentEntityComplete.parentEntityComplete", nullValue());
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
			.body("[0].parentEntityComplete", nullValue());
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
