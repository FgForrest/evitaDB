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

import io.evitadb.api.query.Query;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.TraversalMode;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.OrderConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.*;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Exercises REST list normalization through the shared resolver and real HTTP endpoint.
 */
@DisplayName("REST null list arguments")
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(QUERY)
class CatalogRestNullArgumentsTest extends CatalogRestDataEndpointFunctionalTest {
	/**
	 * Tests value lists without letting undefined items discard sibling constraints.
	 */
	@Nested
	@DisplayName("Value lists")
	class Values {
		/**
		 * Checks mixed and entirely undefined value lists through both API construction and HTTP execution.
		 *
		 * @param key constraint key
		 * @param shape where the undefined items sit in the list
		 * @param tester running REST endpoint client
		 * @param evita running database for schema-backed constraint resolvers
		 */
		@ParameterizedTest
		@CsvSource({
			"attributeCodeInSet,VALUE_THEN_NULL", "attributeCodeInSet,NULL_THEN_VALUE", "attributeCodeInSet,ONLY_NULLS",
			"priceInPriceLists,VALUE_THEN_NULL", "priceInPriceLists,NULL_THEN_VALUE", "priceInPriceLists,ONLY_NULLS",
			"attributeCodeSetExact,VALUE_THEN_NULL", "attributeCodeSetExact,NULL_THEN_VALUE",
			"attributeCodeSetExact,ONLY_NULLS"
		})
		@UseDataSet(REST_THOUSAND_PRODUCTS)
		@DisplayName("Value lists ignore nulls and preserve empty filter semantics")
		void shouldNormalizeValuesWhenListsContainNulls(
			@Nonnull String key, @Nonnull ValueListShape shape, @Nonnull RestTester tester, @Nonnull Evita evita
		) {
			final List<String> values = switch (shape) {
				case VALUE_THEN_NULL -> Arrays.asList("A", null);
				case NULL_THEN_VALUE -> Arrays.asList(null, "A");
				case ONLY_NULLS -> Arrays.asList(null, null);
			};
			final String json = switch (shape) {
				case VALUE_THEN_NULL -> "[\"A\",null]";
				case NULL_THEN_VALUE -> "[null,\"A\"]";
				case ONLY_NULLS -> "[null,null]";
			};
			final CatalogSchemaContract schema = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.getCatalogSchema();
			});
			final FilterConstraintResolver filters = new FilterConstraintResolver(schema);
			final OrderConstraintResolver orders = new OrderConstraintResolver(schema, new AtomicReference<>(filters));
			final boolean order = key.equals("attributeCodeSetExact");
			final String request = order ?
				"{\"orderBy\":[{\"attributeCodeSetExact\":%s},{\"entityPrimaryKeyNatural\":\"DESC\"}]}" :
				"{\"filterBy\":{\"%s\":%s}}";
			final String body = order ? request.formatted(json) : request.formatted(key, json);
			final ValidatableResponse response = tester.test(TEST_CATALOG).urlPathSuffix("/PRODUCT/query")
				.httpMethod(Request.METHOD_POST).requestBody(body).executeAndExpectOkAndThen();
			response.body("recordPage.totalRecordCount", equalTo(order ? 1000 : 0));
			if (order) {
				response.body("recordPage.data[0].primaryKey", equalTo(1000));
				final OrderConstraint resolved = orders.resolve("PRODUCT", key, values);
				assertEquals(
					Query.query(collection("PRODUCT"), orderBy(attributeSetExact("code", values.toArray(String[]::new)),
						entityPrimaryKeyNatural(DESC))).normalizeQuery(),
					Query.query(collection("PRODUCT"), orderBy(resolved, entityPrimaryKeyNatural(DESC)))
						.normalizeQuery());
			} else {
				final FilterConstraint resolved = filters.resolve("PRODUCT", key, values);
				assertEquals(key.equals("attributeCodeInSet") ? attributeInSet("code", values.toArray(String[]::new)) :
					priceInPriceLists(values.toArray(String[]::new)), resolved);
			}
		}
	}

	/**
	 * Tests application settings without silently stripping invalid items.
	 */
	@Nested
	@DisplayName("Name and enum lists")
	class Settings {
		/**
		 * Checks client errors for both null positions and all-null setting lists.
		 *
		 * @param body JSON request containing an invalid setting list
		 * @param tester running REST endpoint client
		 */
		@ParameterizedTest
		@ValueSource(strings = {
			"{\"filterBy\":{\"scope\":[\"LIVE\",null]}}",
			"{\"filterBy\":{\"scope\":[null,\"LIVE\"]}}",
			"{\"filterBy\":{\"scope\":[null,null]}}",
			"{\"require\":{\"entityFetch\":{\"attributeContent\":[\"code\",null]}}}",
			"{\"require\":{\"entityFetch\":{\"attributeContent\":[null,\"code\"]}}}",
			"{\"require\":{\"entityFetch\":{\"attributeContent\":[null,null]}}}"
		})
		@UseDataSet(REST_THOUSAND_PRODUCTS)
		@DisplayName("Null setting items produce HTTP 400 with a diagnostic")
		void shouldRejectSettingsWhenListsContainNulls(@Nonnull String body, @Nonnull RestTester tester) {
			tester.test(TEST_CATALOG).urlPathSuffix("/PRODUCT/query").httpMethod(Request.METHOD_POST)
				.requestBody(body).executeAndExpectBadRequestAndThen().body("message", containsString("null"));
		}

		/**
		 * Confirms the established whole-field omission behavior stays intact.
		 *
		 * @param tester running REST endpoint client
		 */
		@org.junit.jupiter.api.Test
		@UseDataSet(REST_THOUSAND_PRODUCTS)
		@DisplayName("A null constraint field is omitted")
		void shouldOmitConstraintWhenItsWholeValueIsNull(@Nonnull RestTester tester) {
			tester.test(TEST_CATALOG).urlPathSuffix("/PRODUCT/query").httpMethod(Request.METHOD_POST)
				.requestBody("{\"filterBy\":{\"attributeCodeInSet\":null}}")
				.executeAndExpectOkAndThen().body("recordPage.totalRecordCount", equalTo(1000));
		}
	}
	/**
	 * Exercises factory validation and omission at the external construction boundary.
	 */
	@Nested
	@DisplayName("Factory construction")
	class Construction {
		/**
		 * Fetches all reference attributes when the optional selection is absent.
		 *
		 * @param evita running database for schema-backed constraint resolution
		 */
		@org.junit.jupiter.api.Test
		@UseDataSet(REST_THOUSAND_PRODUCTS)
		@DisplayName("Absent reference attribute selection fetches all reference attributes")
		void shouldFetchAllReferenceAttributesWhenSelectionIsAbsent(@Nonnull Evita evita) {
			final CatalogSchemaContract schema = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.getCatalogSchema();
			});
			final FilterConstraintResolver filters = new FilterConstraintResolver(schema);
			final OrderConstraintResolver orders = new OrderConstraintResolver(schema, new AtomicReference<>(filters));
			final RequireConstraintResolver requirements = new RequireConstraintResolver(schema,
				new AtomicReference<>(filters), new AtomicReference<>(orders));
			assertEquals(entityFetch(referenceContentWithAttributes("CATEGORY", (AttributeContent) null)),
				requirements.resolve("PRODUCT", "entityFetch",
				Map.of("referenceCategoryContentWithAttributes", Map.of())));
		}

		/**
		 * Keeps the requested traversal mode with the default primary-key ordering.
		 *
		 * @param mode traversal mode explicitly provided by the client
		 * @param shape absent, empty or emptied ordering
		 * @param evita running database for schema-backed constraint resolution
		 */
		@ParameterizedTest
		@CsvSource({"DEPTH_FIRST,ABSENT", "DEPTH_FIRST,EMPTY", "DEPTH_FIRST,EMPTIED",
			"BREADTH_FIRST,ABSENT", "BREADTH_FIRST,EMPTY", "BREADTH_FIRST,EMPTIED"})
		@UseDataSet(REST_THOUSAND_PRODUCTS)
		@DisplayName("Empty traversal keeps its mode and orders by primary key ascending")
		void shouldKeepTraversalModeWhenOrderingHasNoChildren(
			@Nonnull String mode, @Nonnull OrderingShape shape, @Nonnull Evita evita
		) {
			final CatalogSchemaContract schema = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.getCatalogSchema();
			});
			final FilterConstraintResolver filters = new FilterConstraintResolver(schema);
			final OrderConstraintResolver orders = new OrderConstraintResolver(schema, new AtomicReference<>(filters));
			final Map<String, Object> traversal = createHashMap(2);
			traversal.put("traversalMode", TraversalMode.valueOf(mode));
			if (shape != OrderingShape.ABSENT) {
				traversal.put("orderBy", shape == OrderingShape.EMPTY ? List.of() :
					List.of(Collections.singletonMap("entityPrimaryKeyNatural", null)));
			}
			assertEquals(referenceProperty("CATEGORY", traverseByEntityProperty(
				TraversalMode.valueOf(mode), entityPrimaryKeyNatural(ASC))),
				orders.resolve("PRODUCT", "referenceCategoryProperty", List.of(Map.of(
					"traverseByEntityProperty", traversal))));
		}

		/**
		 * Checks traversal mode over the generated category hierarchy and its product references.
		 *
		 * @param tester running REST endpoint client
		 * @param evita running database for the Java factory result
		 */
		@org.junit.jupiter.api.Test
		@UseDataSet(REST_THOUSAND_PRODUCTS)
		@DisplayName("Traversal modes produce different HTTP result orders without explicit ordering")
		void shouldHonorTraversalModeWhenOrderingIsAbsent(@Nonnull RestTester tester, @Nonnull Evita evita) {
			final List<List<Integer>> results = new java.util.ArrayList<>();
			for (final TraversalMode mode : TraversalMode.values()) {
				final List<Integer> expected = evita.queryCatalog(TEST_CATALOG, session -> { return session.queryList(
					Query.query(collection("PRODUCT"), orderBy(referenceProperty("CATEGORY",
						traverseByEntityProperty(mode, entityPrimaryKeyNatural(ASC))), entityPrimaryKeyNatural(DESC)),
						require(page(1, 1000))), EntityReference.class
				).stream().map(EntityReference::getPrimaryKey).toList(); });
				tester.test(TEST_CATALOG).urlPathSuffix("/PRODUCT/query").httpMethod(Request.METHOD_POST)
					.requestBody("{\"orderBy\":[{\"referenceCategoryProperty\":[{\"traverseByEntityProperty\":"
						+ "{\"traversalMode\":\"" + mode + "\"}}]},{\"entityPrimaryKeyNatural\":\"DESC\"}],"
						+ "\"require\":{\"page\":{\"number\":1,\"size\":1000}}}")
					.executeAndExpectOkAndThen().body("recordPage.data.primaryKey", equalTo(expected));
				results.add(expected);
			}
			assertNotEquals(results.get(0), results.get(1), "The category fixture must distinguish traversal modes");
		}

		/**
		 * Omits segments with no surviving ordering before normalization can retain their limit alone.
		 *
		 * @param emptied whether an omitted child empties the ordering
		 * @param limited whether the segment retains a limit
		 * @param evita running database for schema-backed constraint resolution
		 */
		@ParameterizedTest
		@CsvSource({"false,false", "false,true", "true,false", "true,true"})
		@UseDataSet(REST_THOUSAND_PRODUCTS)
		@DisplayName("Segments without ordering are omitted and preserve their sibling at resolution")
		void shouldOmitSegmentWhenOrderingIsEmpty(boolean emptied, boolean limited, @Nonnull Evita evita) {
			final CatalogSchemaContract schema = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.getCatalogSchema();
			});
			final FilterConstraintResolver filters = new FilterConstraintResolver(schema);
			final OrderConstraintResolver orders = new OrderConstraintResolver(schema, new AtomicReference<>(filters));
			final Map<String, Object> segment = createHashMap(2);
			segment.put("orderBy", emptied ?
				List.of(Collections.singletonMap("entityPrimaryKeyNatural", null)) : List.of());
			if (limited) {
				segment.put("limit", 3);
			}
			final Map<String, Object> omitted = Map.of("segment", segment);
			final Map<String, Object> sibling = Map.of("segment", Map.of("orderBy",
				List.of(Map.of("entityPrimaryKeyNatural", DESC))));
			assertEquals(segments(segment(orderBy(entityPrimaryKeyNatural(DESC)))),
				orders.resolve("PRODUCT", "segments", List.of(omitted, sibling)));
			assertNull(orders.resolve("PRODUCT", "segments", List.of(omitted)));
		}

		/**
		 * Omits a hierarchy stop whose only definition is left out, as `stopAt(level(null))` does in Java.
		 *
		 * @param definition stop definition that resolves to nothing
		 * @param evita running database for schema-backed constraint resolution
		 */
		@ParameterizedTest
		@EnumSource(OmittedStopDefinition.class)
		@UseDataSet(REST_THOUSAND_PRODUCTS)
		@DisplayName("A stop without a surviving definition is omitted")
		void shouldOmitStopAtWhenItsDefinitionIsOmitted(
			@Nonnull OmittedStopDefinition definition, @Nonnull Evita evita
		) {
			final CatalogSchemaContract schema = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.getCatalogSchema();
			});
			final FilterConstraintResolver filters = new FilterConstraintResolver(schema);
			final OrderConstraintResolver orders = new OrderConstraintResolver(schema, new AtomicReference<>(filters));
			final RequireConstraintResolver requirements = new RequireConstraintResolver(schema,
				new AtomicReference<>(filters), new AtomicReference<>(orders));
			final HierarchyDataLocator locator = new HierarchyDataLocator(new ManagedEntityTypePointer("CATEGORY"));
			final Map<String, Object> stopAt = switch (definition) {
				case UNDEFINED_LEVEL -> Collections.singletonMap("level", null);
				case EMPTIED_NODE -> Map.of("node", Map.of("filterBy",
					Collections.singletonMap("entityPrimaryKeyInSet", null)));
				case ABSENT -> Map.of();
			};

			assertNull(requirements.resolve(locator, locator, "stopAt", stopAt));
		}

		/**
		 * Executes empty and emptied segment orderings with and without a limit over HTTP.
		 *
		 * @param ordering empty or emptied ordering JSON
		 * @param limit optional limit JSON
		 * @param tester running REST endpoint client
		 */
		@ParameterizedTest
		@CsvSource(value = {"[]|", "[]|,\"limit\":3",
			"[{\"entityPrimaryKeyNatural\":null}]|", "[{\"entityPrimaryKeyNatural\":null}]|,\"limit\":3"},
			delimiter = '|', nullValues = "")
		@UseDataSet(REST_THOUSAND_PRODUCTS)
		@DisplayName("Segments without ordering preserve the surviving segment's HTTP result order")
		void shouldPreserveSegmentSiblingWhenOrderingIsEmpty(
			@Nonnull String ordering, @Nullable String limit, @Nonnull RestTester tester
		) {
			tester.test(TEST_CATALOG).urlPathSuffix("/PRODUCT/query").httpMethod(Request.METHOD_POST)
				.requestBody("{\"orderBy\":[{\"segments\":[{\"segment\":{\"orderBy\":" + ordering
					+ (limit == null ? "" : limit) + "}},{\"segment\":{\"orderBy\":"
					+ "[{\"entityPrimaryKeyNatural\":\"DESC\"}]}}]}]}")
				.executeAndExpectOkAndThen().body("recordPage.totalRecordCount", equalTo(1000))
				.body("recordPage.data.primaryKey", equalTo(java.util.stream.IntStream.range(0, 20)
					.mapToObj(index -> 1000 - index).toList()));
		}

		/**
		 * Rejects blank scalar application names with a client diagnostic.
		 *
		 * @param body request containing an invalid scalar name
		 * @param tester running REST endpoint client
		 */
		@ParameterizedTest
		@ValueSource(strings = {
			"{\"require\":{\"entityFetch\":{\"priceAccompanyingPriceContent\":"
				+ "{\"accompanyingPriceName\":\" \",\"priceLists\":[\"basic\"]}}}}",
			"{\"require\":{\"hierarchyOfSelf\":{\"requirements\":[{\"fromRoot\":{\"outputName\":\" \"}}]}}}",
			"{\"filterBy\":{\"referenceCategoryHistogramHaving\":{\"histogramName\":\" \",\"from\":1,\"to\":10}}}",
			"{\"filterBy\":{\"referenceCategoryHistogramHaving\":{\"histogramName\":\"\",\"from\":1,\"to\":10}}}"
		})
		@UseDataSet(REST_THOUSAND_PRODUCTS)
		@DisplayName("Blank scalar names produce HTTP 400")
		void shouldRejectNamesWhenScalarNamesAreBlank(@Nonnull String body, @Nonnull RestTester tester) {
			final String entityType = body.contains("hierarchyOfSelf") ? "CATEGORY" : "PRODUCT";
			tester.test(TEST_CATALOG).urlPathSuffix("/" + entityType + "/query").httpMethod(Request.METHOD_POST)
				.requestBody(body).executeAndExpectBadRequestAndThen().body("message", containsString("blank"));
		}

		/**
		 * Retains the primary-key ordering when discount lists have no defined values.
		 *
		 * @param values JSON list with no defined price list
		 * @param tester running REST endpoint client
		 */
		@ParameterizedTest
		@ValueSource(strings = {"[null,null]", "[]", "[\" \",null]"})
		@UseDataSet(REST_THOUSAND_PRODUCTS)
		@DisplayName("Empty discount orderings preserve their sibling")
		void shouldPreserveOrderingWhenDiscountListsAreEmpty(@Nonnull String values, @Nonnull RestTester tester) {
			tester.test(TEST_CATALOG).urlPathSuffix("/PRODUCT/query").httpMethod(Request.METHOD_POST)
				.requestBody("{\"orderBy\":[{\"priceDiscount\":{\"order\":\"DESC\",\"inPriceLists\":" + values
					+ "}},{\"entityPrimaryKeyNatural\":\"DESC\"}]}")
				.executeAndExpectOkAndThen().body("recordPage.totalRecordCount", equalTo(1000))
				.body("recordPage.data[0].primaryKey", equalTo(1000));
		}

		/**
		 * Omits a scoped filter left without children and keeps its sibling filter, at resolution and over HTTP.
		 *
		 * @param emptied whether the scoped filter loses an undefined child rather than having none
		 * @param tester running REST endpoint client
		 * @param evita running database for schema-backed constraint resolution
		 */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@UseDataSet(REST_THOUSAND_PRODUCTS)
		@DisplayName("Emptied scoped filters preserve the sibling filter")
		void shouldPreserveSiblingFilterWhenScopedChildrenAreOmitted(
			boolean emptied, @Nonnull RestTester tester, @Nonnull Evita evita
		) {
			final CatalogSchemaContract schema = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.getCatalogSchema();
			});
			final List<?> filtering = emptied ?
				List.of(Collections.singletonMap("attributeCodeEquals", null)) : List.of();
			final String filteringJson = emptied ? "[{\"attributeCodeEquals\":null}]" : "[]";

			assertNull(new FilterConstraintResolver(schema).resolve("PRODUCT", "inScope",
				Map.of("scope", Scope.LIVE, "filtering", filtering)));
			tester.test(TEST_CATALOG).urlPathSuffix("/PRODUCT/query").httpMethod(Request.METHOD_POST)
				.requestBody("{\"filterBy\":{\"inScope\":{\"scope\":\"LIVE\",\"filtering\":" + filteringJson + "},"
					+ "\"entityPrimaryKeyInSet\":[2]},\"orderBy\":[{\"entityPrimaryKeyNatural\":\"DESC\"}]}")
				.executeAndExpectOkAndThen()
				.body("recordPage.totalRecordCount", equalTo(1))
				.body("recordPage.data[0].primaryKey", equalTo(2));
		}

		/**
		 * Omits scoped orderings and requirements left without children and keeps their query siblings.
		 *
		 * @param body request with an emptied order or require container
		 * @param tester running REST endpoint client
		 */
		@ParameterizedTest
		@ValueSource(strings = {
			"{\"orderBy\":[{\"inScope\":{\"scope\":\"LIVE\",\"ordering\":[{\"attributeCodeNatural\":null}]}},"
				+ "{\"entityPrimaryKeyNatural\":\"DESC\"}]}",
			"{\"require\":{\"inScope\":{\"scope\":\"LIVE\",\"require\":{\"priceDefaultAccompanyingPriceLists\":null}}},"
				+ "\"orderBy\":[{\"entityPrimaryKeyNatural\":\"DESC\"}]}"
		})
		@UseDataSet(REST_THOUSAND_PRODUCTS)
		@DisplayName("Emptied scoped orderings and requirements preserve query siblings")
		void shouldPreserveSiblingsWhenScopedChildrenAreOmitted(@Nonnull String body, @Nonnull RestTester tester) {
			tester.test(TEST_CATALOG).urlPathSuffix("/PRODUCT/query").httpMethod(Request.METHOD_POST)
				.requestBody(body).executeAndExpectOkAndThen()
				.body("recordPage.totalRecordCount", equalTo(1000))
				.body("recordPage.data[0].primaryKey", equalTo(1000));
		}
	}

	/**
	 * Positions of the undefined items in a value list.
	 */
	private enum ValueListShape {
		/**
		 * A defined value followed by an undefined one.
		 */
		VALUE_THEN_NULL,
		/**
		 * An undefined value followed by a defined one.
		 */
		NULL_THEN_VALUE,
		/**
		 * Undefined values only.
		 */
		ONLY_NULLS
	}

	/**
	 * Ways a traversal reaches the resolver without an ordering of its own.
	 */
	private enum OrderingShape {
		/**
		 * The ordering field is not sent.
		 */
		ABSENT,
		/**
		 * The ordering is an empty list.
		 */
		EMPTY,
		/**
		 * The ordering loses its only child to an undefined value.
		 */
		EMPTIED
	}

	/**
	 * Shapes of a `stopAt` body that leave it without a definition.
	 */
	private enum OmittedStopDefinition {
		/**
		 * The level is present but undefined.
		 */
		UNDEFINED_LEVEL,
		/**
		 * The node filter loses its only constraint to an undefined value.
		 */
		EMPTIED_NODE,
		/**
		 * No definition is written at all.
		 */
		ABSENT
	}
}
