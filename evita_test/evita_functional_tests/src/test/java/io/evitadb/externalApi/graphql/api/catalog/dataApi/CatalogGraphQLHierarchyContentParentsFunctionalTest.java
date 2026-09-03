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
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
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
 * pointer and `1` with a body again. A second, separate chain - `5` and `6` in Czech below the English `7` - covers
 * the opposite extreme, in which no ancestor at all can be materialized under an English query locale.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(HIERARCHY)
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

				// a second, separate chain in which *no* ancestor can be materialized under an English query locale -
				// the only shape that leaves the inference rules of both parent fields without a materialized ancestor
				// to read the requirement off
				createCategory(session, 5, null, LOCALE_CZECH);
				createCategory(session, 6, 5, LOCALE_CZECH);
				createCategory(session, 7, 6, Locale.ENGLISH);
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

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should cut the matching chain even when only the complete field asked for bodies")
	void shouldCutTheMatchingChainWhenOnlyTheCompleteFieldAsksForBodies(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document("""
				{
					getCategory(primaryKey: 4, locale: en) {
						parents {
							primaryKey
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
			// `parents` selects nothing but a classifier, so it is the selected `parentsComplete` sibling alone that
			// tells `ParentsDataFetcher#requestsAncestorBodies` the shared requirement asked for ancestor bodies
			.body(
				GET_CATEGORY_PATH,
				equalTo(
					map()
						.e(
							GraphQLEntityDescriptor.PARENTS.name(),
							List.of(map().e(EntityDescriptor.PRIMARY_KEY.name(), 3).build())
						)
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
	@DisplayName("Should return an empty matching chain when nothing materialized and only classifiers were selected")
	void shouldCutTheClassifierOnlyChainToNothingWhenTheCompleteFieldIsSelectedBeside(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document("""
				{
					getCategory(primaryKey: 7, locale: en) {
						parents {
							primaryKey
						}
						parentsComplete {
							__typename
							... on CategoryParentPointer {
								primaryKey
								type
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			// no ancestor of `7` holds English data, so the chain carries no evidence of the requirement at all -
			// the selected `parentsComplete` sibling is what says ancestor bodies were asked for, and every element
			// of the chain is therefore a bodyless pointer the `MATCHING` view cuts away
			.body(
				GET_CATEGORY_PATH,
				equalTo(
					map()
						.e(GraphQLEntityDescriptor.PARENTS.name(), List.of())
						.e(
							GraphQLEntityDescriptor.PARENTS_COMPLETE.name(),
							List.of(bodylessAncestorDto(5), bodylessAncestorDto(6))
						)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should return an empty matching chain when no ancestor could be materialized")
	void shouldCutTheMatchingChainToNothingWhenNothingMaterialized(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document("""
				{
					getCategory(primaryKey: 7, locale: en) {
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
			// the selection reaches past the classifier fields, so the whole chain is cut away below its first
			// element - the field reports an empty list rather than being absent or NULL
			.body(
				GET_CATEGORY_PATH,
				equalTo(
					map()
						.e(GraphQLEntityDescriptor.PARENTS.name(), List.of())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should serve parentPrimaryKey beside the complete chain without bounding it")
	void shouldServeParentPrimaryKeyBesideTheCompleteChain(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document("""
				{
					getCategory(primaryKey: 4, locale: en) {
						parentPrimaryKey
						parentsComplete {
							... on NonHierarchicalCategory {
								primaryKey
							}
							... on CategoryParentPointer {
								primaryKey
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			// `parentPrimaryKey` on its own is served by a `hierarchyContent(stopAt(distance(1)))`; the complete
			// chain must not be truncated to that single ancestor when the two are selected together
			.body(
				GET_CATEGORY_PATH,
				equalTo(
					map()
						.e(GraphQLEntityDescriptor.PARENT_PRIMARY_KEY.name(), 3)
						.e(
							GraphQLEntityDescriptor.PARENTS_COMPLETE.name(),
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

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should refuse a bound written on one parent field only")
	void shouldRefuseABoundOnOneParentFieldOnly(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document("""
				{
					getCategory(primaryKey: 4, locale: en) {
						parents(stopAt: { distance: 1 }) {
							primaryKey
						}
						parentsComplete {
							__typename
							... on CategoryParentPointer {
								primaryKey
							}
						}
					}
				}
				""")
			.executeAndThen()
			// one requirement serves both fields, so an absent bound is not silently widened into the bound the
			// sibling field wrote - even though `HierarchyContent#combineWith` would define that merge. The message
			// has to name omission as a failure of its own, since "must be equal" reads as if an absent bound were
			// simply compared against the written one.
			.statusCode(200)
			.body(GET_CATEGORY_PATH, nullValue())
			.body(ERRORS_PATH, hasSize(greaterThan(0)))
			.body(
				ERRORS_PATH + "[0].message",
				containsString("must either both be omitted or both carry the same bound")
			);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should declare the parent union and the parent pointer object in the schema")
	void shouldDeclareTheParentUnionInTheSchema(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document("""
				{
					__type(name: "CategoryParentUnion") {
						kind
						possibleTypes {
							name
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body("data.__type.kind", equalTo("UNION"))
			.body(
				"data.__type.possibleTypes.name",
				containsInAnyOrder(NON_HIERARCHICAL_CATEGORY_TYPE, CATEGORY_PARENT_POINTER_TYPE)
			);

		tester.test(TEST_CATALOG)
			.document("""
				{
					__type(name: "CategoryParentPointer") {
						kind
						fields {
							name
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body("data.__type.kind", equalTo("OBJECT"))
			// the pointer promises the classifier and nothing else - a version or a scope is exactly what an
			// ancestor whose body could not be materialized cannot supply
			.body(
				"data.__type.fields.name",
				containsInAnyOrder(EntityDescriptor.PRIMARY_KEY.name(), EntityDescriptor.TYPE.name())
			);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should report a materializable ancestor as an entity even when only its key is selected")
	void shouldReportAMaterializableAncestorAsAnEntityEvenWhenOnlyItsKeyIsSelected(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document("""
				{
					getCategory(primaryKey: 4, locale: en) {
						parentsComplete {
							__typename
							... on NonHierarchicalCategory {
								primaryKey
							}
							... on CategoryParentPointer {
								primaryKey
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			// a selection limited to classifier fields derives no inner `entityFetch` of its own, yet the union
			// member has to keep meaning "the body could not be materialized" rather than "no body was asked for" -
			// so the requirement carries an empty `entityFetch()` and ancestors 1 and 3, which hold English data,
			// are reported as entities. Only the Czech-only ancestor 2 stays a pointer.
			.body(
				GET_CATEGORY_PATH,
				equalTo(
					map()
						.e(
							GraphQLEntityDescriptor.PARENTS_COMPLETE.name(),
							List.of(
								classifierOnlyEntityDto(1),
								classifierOnlyPointerDto(2),
								classifierOnlyEntityDto(3)
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
	 * Expected shape of a union element reported as a bodyless pointer by a selection that asked for the primary key
	 * alone - the pointer fragment carries no `type`, so only the discriminating `__typename` and the key remain.
	 *
	 * @param primaryKey primary key of the ancestor
	 * @return the expected DTO
	 */
	@Nonnull
	private static Map<String, Object> classifierOnlyPointerDto(int primaryKey) {
		return map()
			.e(TYPENAME_FIELD, CATEGORY_PARENT_POINTER_TYPE)
			.e(EntityDescriptor.PRIMARY_KEY.name(), primaryKey)
			.build();
	}

	/**
	 * Expected shape of a union element reported as a materialized ancestor by a selection that asked for the primary
	 * key alone - the entity fragment carries no attributes, so only the discriminating `__typename` and the key
	 * remain.
	 *
	 * @param primaryKey primary key of the ancestor
	 * @return the expected DTO
	 */
	@Nonnull
	private static Map<String, Object> classifierOnlyEntityDto(int primaryKey) {
		return map()
			.e(TYPENAME_FIELD, NON_HIERARCHICAL_CATEGORY_TYPE)
			.e(EntityDescriptor.PRIMARY_KEY.name(), primaryKey)
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
