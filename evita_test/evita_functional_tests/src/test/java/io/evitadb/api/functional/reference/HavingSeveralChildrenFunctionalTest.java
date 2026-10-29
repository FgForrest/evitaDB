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

package io.evitadb.api.functional.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.tester.GraphQLSchemaTester;
import io.evitadb.test.tester.GraphQLTester;
import io.evitadb.test.tester.RestTester;
import io.evitadb.utils.CollectionUtils;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies conjunction in entity and group scopes through the engine and both object-based query APIs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Several children in entityHaving and groupHaving")
@ExtendWith(EvitaParameterResolver.class)
@Tag(ENGINE)
@Tag(REFERENCE)
@Tag(FILTER)
public class HavingSeveralChildrenFunctionalTest {
	private static final String DATA_SET = "havingSeveralChildren";

	/**
	 * Creates one target satisfying both conditions and two targets satisfying exactly one each.
	 * Owners 1-3 each reference one target, while owner 4 references all three for row filtering.
	 */
	@DataSet(value = DATA_SET, openWebApi = {GraphQLProvider.CODE, RestProvider.CODE})
	@Nonnull
	DataCarrier setUp(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			for (final String type : new String[]{"Brand", "Store"}) {
				session.defineEntitySchema(type).withoutGeneratedPrimaryKey()
					.withAttribute("first", Boolean.class, AttributeSchemaEditor::filterable)
					.withAttribute("second", Boolean.class, AttributeSchemaEditor::filterable)
					.updateAndFetchVia(session);
				for (int pk = 1; pk <= 3; pk++) {
					session.upsertEntity(session.createNewEntity(type, pk)
						.setAttribute("first", pk != 3).setAttribute("second", pk != 2));
				}
			}
			session.defineEntitySchema("Product").withoutGeneratedPrimaryKey()
				.withReferenceToEntity("brand", "Brand", Cardinality.ONE_OR_MORE,
					thatIs -> thatIs.indexed()
						.withGroupTypeRelatedToEntity("Store")
						.indexedWithComponents(ReferenceIndexedComponents.values()))
				.updateAndFetchVia(session);
			for (int pk = 1; pk <= 3; pk++) {
				final int target = pk;
				session.upsertEntity(session.createNewEntity("Product", pk)
					.setReference("brand", pk, ref -> ref.setGroup("Store", target)));
			}
			session.upsertEntity(session.createNewEntity("Product", 4)
				.setReference("brand", 1, ref -> ref.setGroup("Store", 1))
				.setReference("brand", 2, ref -> ref.setGroup("Store", 2))
				.setReference("brand", 3, ref -> ref.setGroup("Store", 3)));
		});
		return new DataCarrier();
	}

	/**
	 * Accepting several children must not turn the published GraphQL scope filters into lists: each stays a single
	 * input object whose fields are combined with AND.
	 */
	@Test
	@Tag(GRAPHQL)
	@Tag(EXTERNAL_API)
	@UseDataSet(DATA_SET)
	@DisplayName("GraphQL publishes scope filters as single input objects")
	void shouldKeepGraphQLInputObjectsWhenSeveralChildrenAllowed(@Nonnull GraphQLSchemaTester graphQL) {
		final String schema = graphQL.test(TEST_CATALOG).executeAndExpectOkAndThen().extract().asString();

		final Set<String> published = CollectionUtils.createHashSet(2);
		final Matcher fields = Pattern.compile("(?m)^\\s*(entityHaving|groupHaving): (\\S+)").matcher(schema);
		while (fields.find()) {
			published.add(fields.group(1));
			assertFalse(fields.group(2).contains("["), "Scope filters must remain single GraphQL input objects");
		}

		assertEquals(Set.of("entityHaving", "groupHaving"), published, "Both scope filters must be published");
	}

	/**
	 * Accepting several children must not turn the published OpenAPI scope filters into arrays: each property stays
	 * a reference to a single object schema whose properties are combined with AND.
	 */
	@Test
	@Tag(REST)
	@Tag(EXTERNAL_API)
	@UseDataSet(DATA_SET)
	@DisplayName("REST publishes scope filters as single input objects")
	void shouldKeepRestInputObjectsWhenSeveralChildrenAllowed(@Nonnull RestTester rest) throws Exception {
		final String openapi = rest.test(TEST_CATALOG).httpMethod(RestTester.Request.METHOD_GET)
			.executeAndExpectOkAndThen().extract().asString();

		final JsonNode schemas = new ObjectMapper().readTree(openapi).path("components").path("schemas");
		final Set<String> published = CollectionUtils.createHashSet(2);
		for (final JsonNode schema : schemas) {
			for (final String name : new String[]{"entityHaving", "groupHaving"}) {
				final JsonNode property = schema.path("properties").path(name);
				if (!property.isMissingNode()) {
					published.add(name);
					assertTrue(property.has("$ref"), "Scope filters must remain single REST input objects");
					final String reference = property.path("$ref").asText();
					assertEquals("object", schemas.path(reference.substring(reference.lastIndexOf('/') + 1))
						.path("type").asText());
				}
			}
		}

		assertEquals(Set.of("entityHaving", "groupHaving"), published, "Both scope filters must be published");
	}

	/**
	 * Verifies conjunctions through owner filtering and fetched reference rows.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Reference filtering")
	class EngineTest {
		/**
		 * Each individual condition admits an extra owner, so dropping either child changes the result.
		 */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@UseDataSet(DATA_SET)
		@DisplayName("All children constrain the same entity or group")
		void shouldApplyAllChildrenWhenReferenceHaving(boolean group, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final FilterConstraint first = attributeEquals("first", true);
				final FilterConstraint second = attributeEquals("second", true);
				assertEquals(List.of(1, 2, 4), owners(session, scope(group, first)));
				assertEquals(List.of(1, 3, 4), owners(session, scope(group, second)));
				final List<Integer> explicit = owners(session, scope(group, and(first, second)));
				assertEquals(List.of(1, 4), explicit);
				assertEquals(
					explicit, owners(session, scope(group, first, second)), "Both children must filter owners"
				);
				return null;
			});
		}

		/**
		 * Owner 4 has all targets, but only target 1 satisfies both children of the fetched row filter.
		 */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@UseDataSet(DATA_SET)
		@DisplayName("All children filter fetched reference rows")
		void shouldApplyAllChildrenWhenReferenceContentFiltered(boolean group, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final FilterConstraint first = attributeEquals("first", true);
				final FilterConstraint second = attributeEquals("second", true);
				assertEquals(List.of(1, 2), rows(session, scope(group, first)));
				assertEquals(List.of(1, 3), rows(session, scope(group, second)));
				final List<Integer> explicit = rows(session, scope(group, and(first, second)));
				assertEquals(List.of(1), explicit);
				assertEquals(explicit, rows(session, scope(group, first, second)), "Both children must filter rows");
				return null;
			});
		}
	}

	/**
	 * Verifies that both fields of each API scope object contribute to filtering.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Object query APIs")
	@Tag(EXTERNAL_API)
	class ApiTest {
		/**
		 * Both fields of the GraphQL input object must contribute to the implicit conjunction.
		 */
		@ParameterizedTest
		@ValueSource(strings = {"entityHaving", "groupHaving"})
		@UseDataSet(DATA_SET)
		@Tag(GRAPHQL)
		@DisplayName("GraphQL accepts two fields in a scope filter object")
		void shouldApplyAllChildrenWhenGraphQLObjectHasTwoFields(@Nonnull String scope, @Nonnull GraphQLTester tester) {
			final String document = """
				{
				  first: listProduct(
				    filterBy: {referenceBrandHaving: {%1$s: {attributeFirstEquals: true}}}
				  ) {primaryKey}
				  second: listProduct(
				    filterBy: {referenceBrandHaving: {%1$s: {attributeSecondEquals: true}}}
				  ) {primaryKey}
				  explicit: listProduct(filterBy: {referenceBrandHaving: {%1$s: {
				    and: [{attributeFirstEquals: true}, {attributeSecondEquals: true}]
				  }}}) {primaryKey}
				  combined: listProduct(filterBy: {referenceBrandHaving: {%1$s: {
				    attributeFirstEquals: true, attributeSecondEquals: true
				  }}}) {primaryKey}
				}
				""".formatted(scope);
			final JsonPath response = tester.test(TEST_CATALOG).document(document)
				.executeAndExpectOkAndThen().extract().jsonPath();
			assertEquals(List.of(1, 2, 4), response.getList("data.first.primaryKey", Integer.class));
			assertEquals(List.of(1, 3, 4), response.getList("data.second.primaryKey", Integer.class));
			assertEquals(List.of(1, 4), response.getList("data.explicit.primaryKey", Integer.class));
			assertEquals(response.getList("data.explicit.primaryKey"), response.getList("data.combined.primaryKey"));
		}

		/**
		 * REST uses the same object shape and AND semantics as GraphQL.
		 */
		@ParameterizedTest
		@ValueSource(strings = {"entityHaving", "groupHaving"})
		@UseDataSet(DATA_SET)
		@Tag(REST)
		@DisplayName("REST accepts two fields in a scope filter object")
		void shouldApplyAllChildrenWhenRestObjectHasTwoFields(@Nonnull String scope, @Nonnull RestTester tester) {
			assertEquals(List.of(1, 2, 4), restOwners(tester, scope, "\"attributeFirstEquals\": true"));
			assertEquals(List.of(1, 3, 4), restOwners(tester, scope, "\"attributeSecondEquals\": true"));
			final List<Integer> explicit = restOwners(tester, scope,
				"\"and\": [{\"attributeFirstEquals\": true}, {\"attributeSecondEquals\": true}]");
			assertEquals(List.of(1, 4), explicit);
			assertEquals(explicit, restOwners(tester, scope,
				"\"attributeFirstEquals\": true, \"attributeSecondEquals\": true"));
		}
	}

	/**
	 * Constructs the scope being tested without introducing a logical wrapper.
	 */
	@Nonnull
	private static FilterConstraint scope(boolean group, @Nonnull FilterConstraint... children) {
		return group ? groupHaving(children) : entityHaving(children);
	}

	/**
	 * Reads all owners selected through a reference scope.
	 */
	@Nonnull
	private static List<Integer> owners(@Nonnull EvitaSessionContract session, @Nonnull FilterConstraint scope) {
		return session.queryListOfEntityReferences(query(collection("Product"),
			filterBy(referenceHaving("brand", scope)), require(page(1, 20))))
			.stream().map(EntityReferenceContract::getPrimaryKey).toList();
	}

	/**
	 * Reads the target keys of the multi-reference owner after applying its row filter.
	 */
	@Nonnull
	private static List<Integer> rows(@Nonnull EvitaSessionContract session, @Nonnull FilterConstraint scope) {
		final SealedEntity entity = session.getEntity("Product", 4,
			referenceContent("brand", filterBy(scope))).orElseThrow();
		return entity.getReferences("brand").stream().map(ReferenceContract::getReferencedPrimaryKey).sorted().toList();
	}

	/**
	 * Executes the REST scope filter and extracts the selected primary keys.
	 */
	@Nonnull
	private static List<Integer> restOwners(
		@Nonnull RestTester tester, @Nonnull String scope, @Nonnull String fields
	) {
		return tester.test(TEST_CATALOG).urlPathSuffix("/Product/list").httpMethod(RestTester.Request.METHOD_POST)
			.requestBody("{\"filterBy\": {\"referenceBrandHaving\": [{\"" + scope + "\": {" + fields + "}}]}}")
			.executeAndExpectOkAndThen().extract().jsonPath().getList("primaryKey", Integer.class);
	}

	/**
	 * Reuses the target flags with a conditional facet expression to exercise cross-entity re-evaluation.
	 */
	@DataSet(value = "havingSeveralChildrenReevaluation", readOnly = false, destroyAfterClass = true)
	@Nonnull
	DataCarrier setUpReevaluation(@Nonnull Evita evita) {
		setUp(evita);
		evita.updateCatalog(TEST_CATALOG, session -> {
			for (final boolean group : new boolean[]{false, true}) {
				final String name = group ? "groupFacet" : "entityFacet";
				final String path = group ? "groupEntity" : "referencedEntity";
				session.defineEntitySchema("Product")
					.withReferenceToEntity(name, "Brand", Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs.indexed().indexedWithComponents(ReferenceIndexedComponents.values())
							.withGroupTypeRelatedToEntity("Store").faceted().facetedPartially(ExpressionFactory.parse(
								"($reference." + path + "?.attributes['first'] ?? false) == true && " +
									"($reference." + path + "?.attributes['second'] ?? false) == true")))
					.updateAndFetchVia(session);
				session.getEntity("Product", 4, entityFetchAllContent()).orElseThrow().openForWrite()
					.setReference(name, 1, ref -> ref.setGroup("Store", 1))
					.setReference(name, 2, ref -> ref.setGroup("Store", 2))
					.setReference(name, 3, ref -> ref.setGroup("Store", 3)).upsertVia(session);
			}
		});
		return new DataCarrier();
	}

	/**
	 * Verifies that re-evaluation retains the PK child beside existing scope filters.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Conditional facet re-evaluation")
	class ReevaluationTest {
		/**
		 * A matching sibling cannot qualify a changed target: the injected PK must remain an AND child.
		 */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@UseDataSet(value = "havingSeveralChildrenReevaluation", destroyAfterTest = true)
		@Tag(INDEXING)
		@Tag(FACET)
		@DisplayName("Re-evaluation keeps the injected PK beside the existing filter")
		void shouldKeepPkConjunctWhenReferencedEntityChanges(boolean group, @Nonnull Evita evita) {
			final String reference = group ? "groupFacet" : "entityFacet";
			final String type = group ? "Store" : "Brand";
			assertEquals(List.of(4), facetOwners(evita, reference, 1));
			assertEquals(List.of(), facetOwners(evita, reference, 2));
			evita.updateCatalog(TEST_CATALOG, session -> {
				session.getEntity(type, 2, attributeContentAll()).orElseThrow().openForWrite()
					.setAttribute("first", false).upsertVia(session);
			});
			assertEquals(List.of(), facetOwners(evita, reference, 2), "A matching sibling must not qualify target 2");
			evita.updateCatalog(TEST_CATALOG, session -> {
				session.getEntity(type, 2, attributeContentAll()).orElseThrow().openForWrite()
					.setAttribute("second", true).upsertVia(session);
			});
			assertEquals(List.of(), facetOwners(evita, reference, 2), "Both target flags must match");
			evita.updateCatalog(TEST_CATALOG, session -> {
				session.getEntity(type, 2, attributeContentAll()).orElseThrow().openForWrite()
					.setAttribute("first", true).upsertVia(session);
			});
			assertEquals(List.of(4), facetOwners(evita, reference, 2));
		}
	}

	/**
	 * Reads owners that actually carry the requested conditional facet in the index.
	 */
	@Nonnull
	private static List<Integer> facetOwners(@Nonnull Evita evita, @Nonnull String reference, int target) {
		return evita.queryCatalog(TEST_CATALOG, session -> {
			return session.queryListOfEntityReferences(
				query(
					collection("Product"),
					filterBy(userFilter(facetHaving(reference, entityPrimaryKeyInSet(target)))),
					require(page(1, 20))
				)
			).stream().map(EntityReferenceContract::getPrimaryKey).toList();
		});
	}

}
