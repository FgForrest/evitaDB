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


package io.evitadb.externalApi;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.*;
import io.evitadb.api.query.order.*;
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSearchMode;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.CatalogDataApiGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.api.catalog.CatalogRestBuilder;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.client.query.graphql.GraphQLQueryConverter;
import io.evitadb.test.client.query.rest.RestQueryConverter;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.tester.GraphQLTester;
import io.evitadb.test.tester.RestTester;
import io.evitadb.utils.CollectionUtils;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.*;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises fulltext query shapes and engine diagnostics through both real catalog data APIs. */
@ExtendWith(EvitaParameterResolver.class)
@Tag(EXTERNAL_API)
@Tag(GRAPHQL)
@Tag(REST)
@Tag(QUERY)
@Tag(FULLTEXT)
@DisplayName("Fulltext query API registration")
class FulltextQueryApiFunctionalTest {
	/** Shared server and searchable schema fixture. */
	private static final String DATA_SET = "FulltextQueryApi";
	/** Collection catalog with searchable entity, reference and target places. */
	private static final String CATALOG = "fulltextQuery";
	/**
	 * Accepted positions whose result is legitimately empty: no item references an `external` target, and the only
	 * referenced block matches the text, so excluding it leaves nothing. Every other accepted position must match.
	 */
	private static final Set<String> EMPTY_POSITIONS =
		Set.of("referenceHaving external body", "hierarchy reference excluding");

	/**
	 * Carries the composition catalog without claiming String parameters supplied by parameterized test cases.
	 *
	 * @param name catalog containing the shared composition fixture entities
	 */
	private record CompositionCatalog(@Nonnull String name) {
	}

	/**
	 * Creates independent searchable places plus a collection with no searchable place and an unmanaged target.
	 *
	 * @param evita engine hosting both real APIs
	 * @return schemas for structural offering checks and the composition catalog
	 */
	@DataSet(value = DATA_SET, openWebApi = {GraphQLProvider.CODE, RestProvider.CODE})
	@Nonnull
	DataCarrier setUp(@Nonnull Evita evita) {
		evita.defineCatalog(CATALOG);
		evita.updateCatalog(CATALOG, session -> {
			session.defineEntitySchema("block").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH).withHierarchy()
				.withAttribute("title", String.class, a -> a.localized().searchable())
				.withAssociatedData("content", String.class, a -> a.localized().searchable()).updateVia(session);
			session.defineEntitySchema("group").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("name", String.class, a -> a.localized().searchable()).updateVia(session);
			session.defineEntitySchema("plain").withoutGeneratedPrimaryKey()
				.withAttribute("code", String.class, a -> a.filterable()).updateVia(session);
			session.defineEntitySchema("item").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH).withHierarchy()
				.withAttribute("name", String.class, a -> a.localized().searchable())
				.withAttribute("code", String.class, a -> a.localized().searchable())
				.withAttribute("archived", String.class,
					a -> a.localized().nullable().searchableInScope(Scope.ARCHIVED))
				.withAttribute("hidden", String.class, a -> a.nullable().filterable().sortable())
				.withAssociatedData("description", String.class, a -> a.localized().nullable().searchable())
				.withAssociatedData("summary", String.class, a -> a.localized().nullable().searchable())
				.withAssociatedData("archived", String.class,
					a -> a.localized().nullable().searchableInScope(Scope.ARCHIVED))
				.withReferenceToEntity("brand", "block", Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFilteringInScope(Scope.values()).indexedWithComponents(
					ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
					.faceted().withGroupTypeRelatedToEntity("group")
					.withAttribute("name", String.class, a -> a.localized().searchable())
					.withAttribute("archived", String.class,
						a -> a.localized().nullable().searchableInScope(Scope.ARCHIVED)))
				.withReferenceToEntity("blocks", "block", Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFiltering().searchableThrough(ReferenceSearchMode.COMPOSITION))
				.withReferenceTo("external", "external", Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFiltering().withAttribute("name", String.class, a -> a.localized().searchable()))
				.withReferenceToEntity("silent", "plain", Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFiltering()).updateVia(session);
			session.getEntitySchema("block").orElseThrow().openForWrite()
				.withReferenceToEntity("links", "item", Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFiltering().searchableThrough(ReferenceSearchMode.COMPOSITION)
						.withAttribute("name", String.class, a -> a.localized().searchable())).updateVia(session);
			session.upsertEntity(session.createNewEntity("group", 20).setAttribute("name", Locale.ENGLISH, "quartz"));
			session.upsertEntity(session.createNewEntity("block", 10)
				.setAttribute("title", Locale.ENGLISH, "quartz")
				.setAssociatedData("content", Locale.ENGLISH, "quartz lantern"));
			session.upsertEntity(session.createNewEntity("item", 1)
				.setAttribute("name", Locale.ENGLISH, "quartz").setAttribute("code", Locale.ENGLISH, "plain")
				.setAssociatedData("description", Locale.ENGLISH, "plain").setReference("blocks", 10)
				.setReference("brand", 10, r -> r.setAttribute("name", Locale.ENGLISH, "quartz")
					.setGroup("group", 20)));
			session.upsertEntity(session.createNewEntity("item", 2)
				.setAttribute("name", Locale.ENGLISH, "plain").setAttribute("code", Locale.ENGLISH, "quartz")
				.setAssociatedData("description", Locale.ENGLISH, "quartz").setReference("blocks", 10)
				.setReference("brand", 10, r -> r.setAttribute("name", Locale.ENGLISH, "quartz")
					.setGroup("group", 20)));
			session.upsertEntity(session.createNewEntity("item", 3)
				.setAttribute("name", Locale.ENGLISH, "plain").setAttribute("code", Locale.ENGLISH, "plain"));
			session.goLiveAndClose();
		});
		evita.defineCatalog("plainQuery");
		evita.updateCatalog("plainQuery", session -> {
			session.defineEntitySchema("plain").withAttribute("code", String.class, a -> a.filterable())
				.updateVia(session);
			session.goLiveAndClose();
		});
		final String compositionCatalog = FulltextSchemaFunctionalTestSupport.prepare(evita);
		evita.updateCatalog(compositionCatalog, session -> {
			session.updateCatalogSchema(FulltextSchemaFunctionalTestSupport.declarations()
				.toArray(LocalCatalogSchemaMutation[]::new));
		});
		FulltextSchemaFunctionalTestSupport.insertSharedTarget(evita, compositionCatalog);
		final GraphQLSchema graphQLSchema = graphQLSchema(evita, CATALOG);
		final OpenAPI openAPI = openAPI(evita, CATALOG);
		return new DataCarrier("graphQLSchema", graphQLSchema, "openAPI", openAPI,
			"compositionCatalog", new CompositionCatalog(compositionCatalog));
	}

	/**
	 * Builds the same GraphQL data schema used by the running server.
	 *
	 * @param evita engine with the catalog
	 * @param catalog catalog whose schema is built
	 * @return complete data API schema
	 */
	@Nonnull
	private static GraphQLSchema graphQLSchema(@Nonnull Evita evita, @Nonnull String catalog) {
		return new CatalogDataApiGraphQLSchemaBuilder(new GraphQLOptions("localhost:5555"), evita,
			evita.getCatalogInstance(catalog).orElseThrow()).build();
	}

	/**
	 * Builds the REST schema used by the running server.
	 *
	 * @param evita engine with the catalog
	 * @param catalog catalog whose schema is built
	 * @return complete catalog OpenAPI document
	 */
	@Nonnull
	private static OpenAPI openAPI(@Nonnull Evita evita, @Nonnull String catalog) {
		return new CatalogRestBuilder(new RestOptions(true, "localhost:5555", null, null, null, "rest", null),
			HeaderOptions.builder().build(), evita, evita.getCatalogInstance(catalog).orElseThrow()).build().openApi();
	}

	/**
	 * Checks quoted decimal forms, both empty container levels and omitted selectors through both APIs.
	 *
	 * @param evita Java query oracle
	 * @param graphql running GraphQL client
	 * @param rest running REST client
	 * @param compositionCatalog catalog holding the shared composition fixture
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Composition query matches Java on the shared composition fixture through both APIs")
	void shouldQuerySharedTargetComposition(
		@Nonnull Evita evita, @Nonnull GraphQLTester graphql, @Nonnull RestTester rest,
		@Nonnull CompositionCatalog compositionCatalog
	) {
		final GraphQLSchema compositionSchema = graphQLSchema(evita, compositionCatalog.name());
		final GraphQLInputObjectType ownerFilter = input(compositionSchema.getQueryType()
			.getFieldDefinition("queryOwner").getArgument("filterBy").getType());
		assertNotNull(field(field(ownerFilter, "textMatches"), "in").getFieldDefinition("associatedDataTextIn"));
		final GraphQLInputObjectType ownerOrder = input(compositionSchema.getQueryType()
			.getFieldDefinition("queryOwner").getArgument("orderBy").getType());
		assertNotNull(field(ownerOrder, "relevanceText").getFieldDefinition("associatedDataTextWeight"));
		final OpenAPI compositionRest = openAPI(evita, compositionCatalog.name());
		final Schema<?> ownerRequest = compositionRest.getPaths().get("/owner/query").getPost().getRequestBody()
			.getContent().get("application/json").getSchema();
		assertNotNull(restField(compositionRest,
			restField(compositionRest, restField(compositionRest, ownerRequest, "filterBy"), "textMatches"), "in")
			.getProperties().get("associatedDataTextIn"));
		final Query query = Query.query(collection("owner"),
			filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches("quartz")),
			orderBy(relevanceText(), entityPrimaryKeyNatural(OrderDirection.ASC)));
		final List<Integer> expected = evita.queryCatalog(compositionCatalog.name(), session -> {
			return session.query(query, EntityReference.class).getRecordData().stream()
				.map(EntityReference::getPrimaryKey).toList();
		});
		assertEquals(List.of(1, 2), expected);
		graphql.test(compositionCatalog.name()).document("""
			query { queryOwner(filterBy: {entityLocaleEquals: en, textMatches: {text: "quartz"}},
				orderBy: [{relevanceText: []}, {entityPrimaryKeyNatural: ASC}]) {
				recordPage { data { primaryKey } } } }
			""").executeAndExpectOkAndThen().body("data.queryOwner.recordPage.data.primaryKey", equalTo(expected));
		rest.test(compositionCatalog.name()).post("/owner/query").requestBody("""
			{"filterBy":{"entityLocaleEquals":"en","textMatches":{"text":"quartz"}},
			 "orderBy":[{"relevanceText":[]},{"entityPrimaryKeyNatural":"ASC"}]}
			""").executeAndExpectOkAndThen().body("recordPage.data.primaryKey", equalTo(expected));
	}

	/**
	 * Sends the filter and ordered weight containers in their object/list wire shapes.
	 *
	 * @param selectors empty reference body, empty target or explicit target attribute selection
	 * @param graphql running GraphQL client
	 * @param rest running REST client
	 */
	@ParameterizedTest
	@ValueSource(strings = {"{}", "{entityIn:{}}", "{entityIn:{attributeTitleIn:true}}"})
	@UseDataSet(DATA_SET)
	@DisplayName("Reference and entity selector containers preserve matches when empty or explicit")
	void shouldAcceptEmptyAndSelectedContainers(
		@Nonnull String selectors, @Nonnull GraphQLTester graphql, @Nonnull RestTester rest
	) {
		final boolean referenceBody = selectors.equals("{}");
		final String referenceKey = referenceBody ? "referenceBrandIn" : "referenceBlocksIn";
		final String weightedPlace = selectors.contains("attributeTitleIn") ?
			"attributeTitleWeight" : "associatedDataContentWeight";
		final String weights = referenceBody ? "{referenceBrandWeight:[{attributeNameWeight:\"3.0\"}]}" :
			"{referenceBlocksWeight:[{entityWeight:[{" + weightedPlace + ":\"3.0\"}]}]}";
		graphql.test(CATALOG).document("""
			query { queryItem(filterBy: {entityLocaleEquals: en, textMatches: {text: "quartz", in: {%s: %s}}},
				orderBy: [{relevanceText: [%s]}, {entityPrimaryKeyNatural: ASC}]) {
				recordPage { data { primaryKey } } } }
			""", referenceKey, selectors, weights).executeAndExpectOkAndThen()
			.body("data.queryItem.recordPage.data.primaryKey", equalTo(List.of(1, 2)));
		final String jsonSelectors = selectors.replace("entityIn", "\"entityIn\"")
			.replace("attributeTitleIn", "\"attributeTitleIn\"");
		final String jsonWeights = referenceBody ?
			"{\"referenceBrandWeight\":[{\"attributeNameWeight\":\"3.0\"}]}" :
			"{\"referenceBlocksWeight\":[{\"entityWeight\":[{\"" + weightedPlace + "\":\"3.0\"}]}]}";
		rest.test(CATALOG).post("/item/query").requestBody("""
			{"filterBy":{"entityLocaleEquals":"en","textMatches":{"text":"quartz","in":{"%s":%s}}},
			 "orderBy":[{"relevanceText":[%s]},{"entityPrimaryKeyNatural":"ASC"}]}
			""".formatted(referenceKey, jsonSelectors, jsonWeights)).executeAndExpectOkAndThen()
			.body("recordPage.data.primaryKey", equalTo(List.of(1, 2)));
	}

	/**
	 * Confirms each accepted GraphQL weight form using the existing decimal scalar.
	 *
	 * @param weight quoted decimal literal
	 * @param graphql running GraphQL client
	 */
	@ParameterizedTest
	@ValueSource(strings = {"\"3\"", "\"3.0\""})
	@UseDataSet(DATA_SET)
	@DisplayName("GraphQL accepts quoted integer and decimal weights")
	void shouldAcceptQuotedGraphQLWeights(@Nonnull String weight, @Nonnull GraphQLTester graphql) {
		graphql.test(CATALOG).document("""
			query { queryItem(filterBy: {entityLocaleEquals: en,
				textMatches: {text: "quartz", in: {attributeNameIn:true}}},
				orderBy: [{relevanceText: [{attributeNameWeight: %s}]}]) {
				recordPage { data { primaryKey } } } }
			""", weight).executeAndExpectOkAndThen()
			.body("data.queryItem.recordPage.data.primaryKey", equalTo(List.of(1)));
	}

	/**
	 * Pins both currently accepted numeric JSON forms as well as the advertised decimal string form.
	 *
	 * @param weight JSON number or decimal string
	 * @param rest running REST client
	 */
	@ParameterizedTest
	@ValueSource(strings = {"3", "3.0", "\"3\"", "\"3.0\""})
	@UseDataSet(DATA_SET)
	@DisplayName("REST accepts decimal strings and currently also accepts JSON numbers")
	void shouldAcceptRestWeightLiterals(@Nonnull String weight, @Nonnull RestTester rest) {
		rest.test(CATALOG).post("/item/query").requestBody("""
			{"filterBy":{"entityLocaleEquals":"en","textMatches":{"text":"quartz","in":{"attributeNameIn":true}}},
			 "orderBy":[{"relevanceText":[{"attributeNameWeight":%s}]}]}
			""".formatted(weight)).executeAndExpectOkAndThen()
			.body("recordPage.data.primaryKey", equalTo(List.of(1)));
	}

	/**
	 * Requires a readable scalar validation error for both unquoted GraphQL numeric AST forms.
	 *
	 * @param weight numeric GraphQL literal
	 * @param graphql running GraphQL client
	 */
	@ParameterizedTest
	@ValueSource(strings = {"3", "3.0"})
	@UseDataSet(DATA_SET)
	@DisplayName("GraphQL refuses unquoted numeric weights with a scalar validation error")
	void shouldRejectNumericGraphQLWeights(@Nonnull String weight, @Nonnull GraphQLTester graphql) {
		final String response = graphql.test(CATALOG).document("""
			query { queryItem(orderBy: [{relevanceText: [{attributeNameWeight: %s}]}]) {
				recordPage { data { primaryKey } } } }
			""", weight).executeAndExpectErrorsAndThen().extract().asString();
		assertTrue(response.contains("BigDecimal"), response);
		assertTrue(response.contains("String"), response);
	}

	/**
	 * Compares the two example converters against real requests with multi-name and wildcard selectors.
	 *
	 * @param evita source of schema metadata for the converters
	 * @param graphql running GraphQL client
	 * @param rest running REST client
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Example converters split names at every TEXT level without changing the Java query")
	void shouldSplitSelectorsInExampleConverters(
		@Nonnull Evita evita, @Nonnull GraphQLTester graphql, @Nonnull RestTester rest
	) {
		final Query query = Query.query(collection("item"),
			filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches("quartz",
			inAttribute("name", "code"), inAssociatedData("description", "summary"),
			inReference("blocks", inEntity(inAttribute("title"), inAssociatedDataAll())), inAttributeAll())),
			orderBy(relevanceText(attributeWeight("name", new BigDecimal("3.0"))),
				entityPrimaryKeyNatural(OrderDirection.ASC)),
			require(entityFetch()));
		final String original = query.toString();
		final String gql = GraphQLQueryConverter.convert(evita, CATALOG, query);
		final String json = new RestQueryConverter().convert(evita, CATALOG, query);
		for (final String key : List.of("attributeNameIn", "attributeCodeIn", "associatedDataDescriptionIn",
			"associatedDataSummaryIn", "attributeTitleIn", "associatedDataInAll", "attributeInAll")) {
			assertTrue(gql.contains(key), gql);
			assertTrue(json.contains(key), json);
		}
		assertEquals(original, query.toString());
		graphql.test(CATALOG).document(gql).executeAndExpectOkAndThen()
			.body("data.queryItem.recordPage.data.primaryKey", equalTo(List.of(1, 2)));
		rest.test(CATALOG).post("/item/query").requestBody(json.substring(json.indexOf('{')))
			.executeAndExpectOkAndThen().body("recordPage.data.primaryKey", equalTo(List.of(1, 2)));
	}

	/**
	 * Checks the level markers against real generated GraphQL input types; REST shares their schema builder rules.
	 *
	 * @param graphQLSchema schema of the running searchable catalog
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("TEXT selectors and weights stay in their searchable level and never leak into normal containers")
	void shouldOfferOnlySearchablePlacesInGraphQL(@Nonnull GraphQLSchema graphQLSchema) {
		final GraphQLInputObjectType filter = input(graphQLSchema.getQueryType().getFieldDefinition("queryItem")
			.getArgument("filterBy").getType());
		assertNull(filter.getFieldDefinition("attributeNameIn"));
		assertNull(field(filter, "userFilter").getFieldDefinition("textMatches"));
		assertNull(filter.getFieldDefinition("attributeNameWeight"));
		final GraphQLInputObjectType text = field(filter, "textMatches");
		final GraphQLInputObjectType places = field(text, "in");
		assertNotNull(places.getFieldDefinition("attributeNameIn"));
		assertNotNull(places.getFieldDefinition("attributeArchivedIn"));
		assertNotNull(places.getFieldDefinition("attributeInAll"));
		assertNotNull(places.getFieldDefinition("associatedDataDescriptionIn"));
		assertNotNull(places.getFieldDefinition("associatedDataArchivedIn"));
		assertNull(field(places, "referenceBrandIn").getFieldDefinition("entityIn"));
		assertNotNull(field(places, "referenceBrandIn").getFieldDefinition("attributeArchivedIn"));
		assertNotNull(places.getFieldDefinition("associatedDataInAll"));
		assertNull(places.getFieldDefinition("attributeHiddenIn"));
		assertNull(places.getFieldDefinition("entityIn"));
		assertNull(places.getFieldDefinition("referenceSilentIn"));
		final GraphQLInputObjectType reference = field(places, "referenceBlocksIn");
		final GraphQLInputObjectType target = field(reference, "entityIn");
		assertNotNull(target.getFieldDefinition("attributeTitleIn"));
		assertNotNull(target.getFieldDefinition("associatedDataContentIn"));
		assertNull(target.getFieldDefinition("referenceLinksIn"));
		assertNull(target.getFieldDefinition("entityIn"));
		final GraphQLInputObjectType referenceText = field(field(filter, "referenceBrandHaving"), "textMatches");
		final GraphQLInputObjectType referencePlaces = field(referenceText, "in");
		assertNotNull(referencePlaces.getFieldDefinition("attributeNameIn"));
		assertNull(referencePlaces.getFieldDefinition("associatedDataInAll"));
		assertNull(referencePlaces.getFieldDefinition("entityIn"));
		assertNull(referencePlaces.getFieldDefinition("referenceBlocksIn"));
		assertNull(field(filter, "facetBrandHaving").getFieldDefinition("textMatches"));
		final GraphQLInputObjectType blockFilter = input(graphQLSchema.getQueryType().getFieldDefinition("queryBlock")
			.getArgument("filterBy").getType());
		assertNotNull(field(field(blockFilter, "textMatches"), "in").getFieldDefinition("referenceLinksIn"));
		final GraphQLInputObjectType external = field(field(filter, "referenceExternalHaving"), "entityHaving");
		assertNull(external.getFieldDefinition("textMatches"));
		final GraphQLInputObjectType plain = input(graphQLSchema.getQueryType().getFieldDefinition("queryPlain")
			.getArgument("filterBy").getType());
		assertNull(plain.getFieldDefinition("textMatches"));
		final GraphQLInputObjectType plainOrder = input(graphQLSchema.getQueryType().getFieldDefinition("queryPlain")
			.getArgument("orderBy").getType());
		assertNull(plainOrder.getFieldDefinition("relevanceText"));
		final GraphQLInputObjectType orders = input(graphQLSchema.getQueryType().getFieldDefinition("queryItem")
			.getArgument("orderBy").getType());
		final GraphQLInputObjectType weights = field(orders, "relevanceText");
		assertEquals("BigDecimal", GraphQLTypeUtil.simplePrint(
			weights.getFieldDefinition("attributeNameWeight").getType()));
		assertNotNull(weights.getFieldDefinition("associatedDataDescriptionWeight"));
		assertNotNull(field(field(weights, "referenceBlocksWeight"), "entityWeight")
			.getFieldDefinition("associatedDataContentWeight"));
		assertNull(orders.getFieldDefinition("attributeNameWeight"));
		assertNull(field(orders, "referenceBrandProperty").getFieldDefinition("entityProperty"));
		assertNull(field(orders, "referenceBrandProperty").getFieldDefinition("entityGroupProperty"));
		final var referenceField = graphQLSchema.getObjectType("Item").getFieldDefinition("brand");
		assertNull(input(referenceField.getArgument("filterBy").getType()).getFieldDefinition("textMatches"));
		assertNull(input(referenceField.getArgument("orderBy").getType()).getFieldDefinition("relevanceText"));
	}

	/**
	 * Requires REST containers without a searchable place to expose no fulltext field anywhere in their subtree.
	 *
	 * @param openAPI REST schema of the running searchable catalog
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("REST keeps fulltext fields out of containers without a searchable place")
	void shouldKeepRestTextFieldsOutOfPlainContainers(@Nonnull OpenAPI openAPI) {
		final Schema<?> plainRequest = openAPI.getPaths().get("/plain/query").getPost().getRequestBody()
			.getContent().get("application/json").getSchema();
		assertNoRestTextFields(openAPI, plainRequest, CollectionUtils.createHashSet(16));
		final Schema<?> itemRequest = openAPI.getPaths().get("/item/query").getPost().getRequestBody()
			.getContent().get("application/json").getSchema();
		final Schema<?> restFilter = restField(openAPI, itemRequest, "filterBy");
		assertNoRestTextFields(openAPI,
			restField(openAPI, restField(openAPI, restFilter, "referenceExternalHaving"), "entityHaving"),
			CollectionUtils.createHashSet(16));
	}

	/**
	 * Requires REST to describe place weights as decimal strings, for attributes and associated data alike.
	 *
	 * @param openAPI REST schema of the running searchable catalog
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("REST describes place weights as decimal strings")
	void shouldDescribeRestWeightsAsDecimalStrings(@Nonnull OpenAPI openAPI) {
		final Schema<?> weight = openAPI.getComponents().getSchemas().values().stream()
			.filter(schema -> schema.getProperties() != null &&
				schema.getProperties().containsKey("attributeNameWeight"))
			.map(schema -> (Schema<?>) schema.getProperties().get("attributeNameWeight")).findFirst().orElseThrow();
		assertEquals("string", restSchema(openAPI, weight).getType());
		assertEquals("decimal", restSchema(openAPI, weight).getFormat());
		assertTrue(openAPI.getComponents().getSchemas().values().stream().anyMatch(schema ->
			schema.getProperties() != null &&
				schema.getProperties().containsKey("associatedDataDescriptionWeight")));
	}

	/**
	 * Requires GraphQL to offer a wildcard selector only at a level that has a searchable place of its kind: `group`
	 * has a searchable attribute but no associated data, and the `blocks` reference has no attribute at all.
	 *
	 * @param graphQLSchema schema of the running searchable catalog
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("GraphQL offers wildcard selectors only where they select a searchable place")
	void shouldOfferGraphQLWildcardsOnlyWithTheirPlaces(@Nonnull GraphQLSchema graphQLSchema) {
		final GraphQLInputObjectType groupPlaces = field(field(input(graphQLSchema.getQueryType()
			.getFieldDefinition("queryGroup").getArgument("filterBy").getType()), "textMatches"), "in");
		assertNotNull(groupPlaces.getFieldDefinition("attributeInAll"));
		assertNull(groupPlaces.getFieldDefinition("associatedDataInAll"));
		final GraphQLInputObjectType itemPlaces = field(field(input(graphQLSchema.getQueryType()
			.getFieldDefinition("queryItem").getArgument("filterBy").getType()), "textMatches"), "in");
		final GraphQLInputObjectType blocks = field(itemPlaces, "referenceBlocksIn");
		assertNotNull(blocks.getFieldDefinition("entityIn"));
		assertNull(blocks.getFieldDefinition("attributeInAll"));
		assertNotNull(field(itemPlaces, "referenceBrandIn").getFieldDefinition("attributeInAll"));
	}

	/**
	 * Requires the same wildcard offering in the generated REST request schemas.
	 *
	 * @param openAPI REST schema of the running searchable catalog
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("REST offers wildcard selectors only where they select a searchable place")
	void shouldOfferRestWildcardsOnlyWithTheirPlaces(@Nonnull OpenAPI openAPI) {
		final Map<String, Schema> groupPlaces = restPlaces(openAPI, "/group/query");
		assertNotNull(groupPlaces.get("attributeInAll"));
		assertNull(groupPlaces.get("associatedDataInAll"));
		final Map<String, Schema> itemPlaces = restPlaces(openAPI, "/item/query");
		final Map<String, Schema> blocks = restObject(openAPI, itemPlaces.get("referenceBlocksIn")).getProperties();
		assertNotNull(blocks.get("entityIn"));
		assertNull(blocks.get("attributeInAll"));
		assertNotNull(restObject(openAPI, itemPlaces.get("referenceBrandIn")).getProperties().get("attributeInAll"));
	}

	/**
	 * Proves through the Java API that each wildcard the schemas omit could never succeed.
	 *
	 * @param evita engine hosting the fixture
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("The engine refuses wildcard selectors that select no searchable place")
	void shouldRefuseWildcardsWithoutTheirPlaces(@Nonnull Evita evita) {
		evita.queryCatalog(CATALOG, session -> {
			for (final Query query : List.of(
				Query.query(collection("group"), filterBy(entityLocaleEquals(Locale.ENGLISH),
					textMatches("quartz", inAssociatedDataAll()))),
				Query.query(collection("item"), filterBy(entityLocaleEquals(Locale.ENGLISH),
					textMatches("quartz", inReference("blocks", inAttributeAll()))))
			)) {
				final EvitaInvalidUsageException refusal = assertThrows(EvitaInvalidUsageException.class,
					() -> session.query(query, EntityReference.class));
				assertTrue(refusal.getMessage().contains("selects no searchable"), refusal.getMessage());
			}
		});
	}

	/**
	 * Covers the Boolean marker convention independently for attributes and associated data.
	 *
	 * @return named selector key, input gate and expected primary keys
	 */
	@Nonnull
	private static Stream<Arguments> namedSelectorGates() {
		return Stream.of("attributeNameIn", "associatedDataDescriptionIn").flatMap(key ->
			Stream.of("true", "false", "null").map(gate -> Arguments.of(key, gate,
				gate.equals("true") ? List.of(key.startsWith("attribute") ? 1 : 2) : List.of(1, 2))));
	}

	/**
	 * Requires false and null to omit named selectors while true narrows the fulltext search.
	 *
	 * @param key named selector field
	 * @param gate Boolean or null input literal
	 * @param expected matches after applying the marker gate
	 * @param graphql real GraphQL client
	 */
	@ParameterizedTest
	@MethodSource("namedSelectorGates")
	@UseDataSet(DATA_SET)
	@DisplayName("GraphQL named fulltext selectors honor true, false and null")
	void shouldHonorGraphQLNamedSelectorGates(
		@Nonnull String key, @Nonnull String gate, @Nonnull List<Integer> expected, @Nonnull GraphQLTester graphql
	) {
		graphql.test(CATALOG).document("""
			query {queryItem(filterBy:{entityLocaleEquals:en,textMatches:{text:"quartz",in:{%s:%s}}},
				orderBy:[{entityPrimaryKeyNatural:ASC}]) {recordPage {data {primaryKey}}}}
			""", key, gate).executeAndExpectOkAndThen()
			.body("data.queryItem.recordPage.data.primaryKey", equalTo(expected));
	}

	/**
	 * Requires the same marker omission convention through the REST HTTP deserializer and resolver.
	 *
	 * @param key named selector field
	 * @param gate Boolean or null input literal
	 * @param expected matches after applying the marker gate
	 * @param rest real REST client
	 */
	@ParameterizedTest
	@MethodSource("namedSelectorGates")
	@UseDataSet(DATA_SET)
	@DisplayName("REST named fulltext selectors honor true, false and null")
	void shouldHonorRestNamedSelectorGates(
		@Nonnull String key, @Nonnull String gate, @Nonnull List<Integer> expected, @Nonnull RestTester rest
	) {
		rest.test(CATALOG).post("/item/query").requestBody("""
			{"filterBy":{"entityLocaleEquals":"en","textMatches":{"text":"quartz","in":{"%s":%s}}},
			 "orderBy":[{"entityPrimaryKeyNatural":"ASC"}]}
			""".formatted(key, gate)).executeAndExpectOkAndThen()
			.body("recordPage.data.primaryKey", equalTo(expected));
	}

	/**
	 * Supplies raw requests that must be rejected by planning or weight construction, with engine diagnostics. The
	 * first one never reaches the engine: the generated schemas offer no `textMatches` inside `facetHaving`, and
	 * the two APIs word that refusal differently, so only the rejected property name is common to both.
	 *
	 * @return wire body and a fragment of the refusal message distinguishing it from other errors
	 */
	@Nonnull
	private static Stream<Arguments> refusedRequests() {
		final String selected = "\"filterBy\":{\"entityLocaleEquals\":\"en\"," +
			"\"textMatches\":{\"text\":\"quartz\",\"in\":{\"attributeNameIn\":true}}}";
		final String bounds = "at most 100 significant digits";
		return Stream.concat(Stream.of(
			Arguments.of("{\"filterBy\":{\"entityLocaleEquals\":\"en\",\"facetBrandHaving\":{" +
				"\"textMatches\":{\"text\":\"quartz\"}}}}", "textMatches"),
			Arguments.of("{" + selected + ",\"orderBy\":[{\"relevanceText\":[{\"attributeCodeWeight\":\"3.0\"}]}]}",
				"searches this place"),
			Arguments.of("{" + selected + ",\"orderBy\":[{\"relevanceText\":[{\"attributeNameWeight\":\"3.0\"}," +
				"{\"attributeNameWeight\":\"2.0\"}]}]}", "weights the same place twice")
		), Stream.of(
			Map.entry("-1", "must be non-negative"), Map.entry("1E-101", bounds), Map.entry("1E+101", bounds),
			Map.entry("1E-46", "underflows the internal float range"),
			Map.entry("1E+39", "overflows the internal float range"), Map.entry("1." + "2".repeat(100), bounds)
		).map(weight ->
			Arguments.of("{" + selected + ",\"orderBy\":[{\"relevanceText\":[{\"attributeNameWeight\":\"" +
				weight.getKey() + "\"}]}]}",
				weight.getValue())));
	}

	/**
	 * Requires GraphQL errors and REST 400 responses with the engine's diagnostic rather than an internal error.
	 *
	 * @param body JSON query body with invalid fulltext placement/content
	 * @param diagnostic distinguishing engine message fragment
	 * @param graphql running GraphQL client
	 * @param rest running REST client
	 */
	@ParameterizedTest
	@MethodSource("refusedRequests")
	@UseDataSet(DATA_SET)
	@DisplayName("Engine refusals reach both clients as readable input errors")
	void shouldReturnEngineRefusals(
		@Nonnull String body, @Nonnull String diagnostic, @Nonnull GraphQLTester graphql, @Nonnull RestTester rest
	) {
		final Map<String, Object> request = new ObjectMapper().convertValue(
			FulltextSchemaFunctionalTestSupport.read(body), Map.class);
		final String arguments = FulltextMutationFunctionalTestSupport.graphql(request, "")
			.replace("entityLocaleEquals:\"en\"", "entityLocaleEquals:en");
		final String gql = graphql.test(CATALOG).document("query { queryItem(" +
			arguments.substring(1, arguments.length() - 1) + ") { recordPage { data { primaryKey } } } }")
			.executeAndExpectErrorsAndThen().extract().asString();
		assertTrue(gql.toLowerCase(Locale.ROOT).contains(diagnostic.toLowerCase(Locale.ROOT)), gql);
		final String json = rest.test(CATALOG).post("/item/query").requestBody(body)
			.executeAndExpectBadRequestAndThen().extract().asString();
		assertTrue(json.toLowerCase(Locale.ROOT).contains(diagnostic.toLowerCase(Locale.ROOT)), json);
	}

	/**
	 * Keeps the historical classified non-TEXT dispatch unchanged while TEXT selectors gain Boolean gates.
	 *
	 * @param evita catalog supplying the actual classifier names
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Legacy classified valueless variants still ignore false and omit null")
	void shouldRetainLegacyNamedMarkerGates(@Nonnull Evita evita) {
		final var catalog = evita.getCatalogInstance(CATALOG).orElseThrow().getSchema();
		final var gqlFilter = new io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint
			.FilterConstraintResolver(catalog);
		final var restFilter = new io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint
			.FilterConstraintResolver(catalog);
		final var gqlOrder = new io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint
			.OrderConstraintResolver(catalog, new AtomicReference<>(gqlFilter));
		final var restOrder = new io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint
			.OrderConstraintResolver(catalog, new AtomicReference<>(restFilter));
		for (final Boolean gate : List.of(true, false)) {
			assertEquals(new AttributeInRange("hidden"), gqlFilter.resolve("item", "attributeHiddenInRangeNow", gate));
			assertEquals(new AttributeInRange("hidden"), restFilter.resolve("item", "attributeHiddenInRangeNow", gate));
			assertEquals(attributeSetInFilter("hidden"), gqlOrder.resolve("item", "attributeHiddenSetInFilter", gate));
			assertEquals(attributeSetInFilter("hidden"), restOrder.resolve("item", "attributeHiddenSetInFilter", gate));
		}
		assertNull(gqlFilter.resolve("item", "attributeHiddenInRangeNow", null));
		assertNull(restFilter.resolve("item", "attributeHiddenInRangeNow", null));
		assertNull(gqlOrder.resolve("item", "attributeHiddenSetInFilter", null));
		assertNull(restOrder.resolve("item", "attributeHiddenSetInFilter", null));
	}

	/**
	 * Lists the distinct filtering and ordering contexts that can inherit ENTITY or REFERENCE domains.
	 *
	 * @return named queries covering schema positions offered after registration
	 */
	@Nonnull
	@SuppressWarnings({"deprecation", "removal"})
	private static Stream<Arguments> positions() {
		final TextMatches text = textMatches("quartz");
		final FilterBy locale = filterBy(entityLocaleEquals(Locale.ENGLISH));
		final FilterBy matching = filterBy(entityLocaleEquals(Locale.ENGLISH), text);
		return Stream.of(
			Arguments.of("filterBy", positionQuery(text, relevanceText())),
			Arguments.of("and", positionQuery(and(text, entityPrimaryKeyInSet(1, 2)), relevanceText())),
			Arguments.of("or", positionQuery(or(text, entityPrimaryKeyInSet(3)), relevanceText())),
			Arguments.of("not", positionQuery(not(text), null)),
			Arguments.of("filter inScope", positionQuery(inScope(Scope.LIVE, text), relevanceText())),
			Arguments.of("referenceHaving", positionQuery(referenceHaving("brand", text), relevanceText())),
			Arguments.of("referenceHaving external body", positionQuery(referenceHaving("external", text), null)),
			Arguments.of("referenceHaving entityHaving", positionQuery(
				referenceHaving("blocks", entityHaving(text)), relevanceText())),
			Arguments.of("referenceHaving groupHaving",
				positionQuery(referenceHaving("brand", groupHaving(text)), null)),
			Arguments.of("facetHaving groupHaving", positionQuery(facetHaving("brand", groupHaving(text)), null)),
			Arguments.of("facetHaving entityHaving", positionQuery(facetHaving("brand", entityHaving(text)), null)),
			Arguments.of("hierarchyWithinSelf ofParent", positionQuery(hierarchyWithinSelf(text), relevanceText())),
			Arguments.of("hierarchy having", positionQuery(hierarchyWithinRootSelf(having(text)), null)),
			Arguments.of("hierarchy anyHaving", positionQuery(hierarchyWithinRootSelf(anyHaving(text)), null)),
			Arguments.of("hierarchy reference having",
				positionQuery(hierarchyWithinRoot("blocks", having(text)), null)),
			Arguments.of("hierarchy reference anyHaving",
				positionQuery(hierarchyWithinRoot("blocks", anyHaving(text)), null)),
			Arguments.of("hierarchy reference excluding",
				positionQuery(hierarchyWithinRoot("blocks", excluding(text)), null)),
			Arguments.of("hierarchy excluding", positionQuery(hierarchyWithinRootSelf(excluding(text)), null)),
			Arguments.of("hierarchy having relevance", positionQuery(
				hierarchyWithinRootSelf(having(text)), relevanceText())),
			Arguments.of("hierarchy reference ofParent", positionQuery(hierarchyWithin("blocks", text), null)),
			Arguments.of("order inScope", positionQuery(text, inScope(Scope.LIVE, relevanceText()))),
			Arguments.of("referenceProperty", positionQuery(text, referenceProperty("brand", relevanceText()))),
			Arguments.of("referenceProperty traverseByEntityProperty", positionQuery(text,
				referenceProperty("blocks", traverseByEntityProperty(relevanceText())))),
			Arguments.of("referenceProperty pickFirstByEntityProperty", positionQuery(text,
				referenceProperty("blocks", pickFirstByEntityProperty(relevanceText())))),
			Arguments.of("segment filter and order", positionQuery(null, segments(segment(
				entityHaving(text), orderBy(relevanceText())),
				segment(orderBy(entityPrimaryKeyNatural(OrderDirection.ASC)))))),
			Arguments.of("referenceContent entityHaving filter", Query.query(collection("item"), locale,
				require(entityFetch(referenceContent("blocks", filterBy(entityHaving(text))))))),
			Arguments.of("referenceContent entityProperty order", Query.query(collection("item"), matching,
				require(entityFetch(referenceContent("blocks", orderBy(entityProperty(relevanceText()))))))),
			Arguments.of("referenceContent groupHaving filter", Query.query(collection("item"),
				locale,
				require(entityFetch(referenceContent("brand", filterBy(groupHaving(text))))))),
			Arguments.of("referenceContent entityGroupProperty order", Query.query(collection("item"),
				matching,
				require(entityFetch(referenceContent("brand", orderBy(entityGroupProperty(relevanceText()))))))),
			Arguments.of("facetGroupsConjunction", Query.query(collection("item"),
				locale,
				require(entityFetch(), facetGroupsConjunction("brand", filterBy(text))))),
			Arguments.of("facetGroupsDisjunction", Query.query(collection("item"),
				locale,
				require(entityFetch(), facetGroupsDisjunction("brand", filterBy(text))))),
			Arguments.of("facetGroupsNegation", Query.query(collection("item"),
				locale,
				require(entityFetch(), facetGroupsNegation("brand", filterBy(text))))),
			Arguments.of("facetGroupsExclusivity", Query.query(collection("item"),
				locale,
				require(entityFetch(), facetGroupsExclusivity("brand", filterBy(text))))),
			Arguments.of("histogramHaving groupHaving", positionQuery(
				new HistogramHaving("brand", null, BigDecimal.ZERO, BigDecimal.TEN, groupHaving(text)), null)),
			Arguments.of("facetSummary group filter", Query.query(collection("item"),
				locale,
				require(entityFetch(), facetSummaryOfReference("brand", (FilterBy) null, filterGroupBy(text),
					(OrderBy) null, (OrderGroupBy) null, entityGroupFetch())))),
			Arguments.of("facetSummary group order", Query.query(collection("item"),
				locale,
				require(entityFetch(), facetSummaryOfReference("brand", (FilterBy) null, (FilterGroupBy) null,
					(OrderBy) null, orderGroupBy(relevanceText()), entityGroupFetch())))),
			Arguments.of("facetSummary filter", Query.query(collection("item"), locale,
				require(entityFetch(), facetSummaryOfReference("brand", matching, (FilterGroupBy) null,
					(OrderBy) null, (OrderGroupBy) null, entityFetch())))),
			Arguments.of("facetSummary order", Query.query(collection("item"), matching,
				require(entityFetch(), facetSummaryOfReference("brand", (FilterBy) null, (FilterGroupBy) null,
					orderBy(relevanceText()), (OrderGroupBy) null, entityFetch())))),
			Arguments.of("referenceSummary filter", Query.query(collection("item"), locale,
				require(entityFetch(), referenceSummaryOfReference("brand", matching,
					(FilterGroupBy) null, (OrderBy) null, (OrderGroupBy) null, entityFetch())))),
			Arguments.of("referenceSummary order", Query.query(collection("item"), matching,
				require(entityFetch(), referenceSummaryOfReference("brand", (FilterBy) null,
					(FilterGroupBy) null, orderBy(relevanceText()), (OrderGroupBy) null, entityFetch())))),
			Arguments.of("referenceSummary group filter", Query.query(collection("item"),
				locale,
				require(entityFetch(), referenceSummaryOfReference("brand", (FilterBy) null, filterGroupBy(text),
					(OrderBy) null, (OrderGroupBy) null, entityGroupFetch())))),
			Arguments.of("referenceSummary group order", Query.query(collection("item"),
				locale,
				require(entityFetch(), referenceSummaryOfReference("brand", (FilterBy) null, (FilterGroupBy) null,
					(OrderBy) null, orderGroupBy(relevanceText()), entityGroupFetch())))),
			Arguments.of("hierarchyOfSelf node filter", Query.query(collection("item"),
				locale,
				require(entityFetch(), hierarchyOfSelf(fromRoot("tree", stopAt(node(filterBy(text)))))))),
			Arguments.of("hierarchyContent node filter", Query.query(collection("item"),
				locale,
				require(entityFetch(hierarchyContent(stopAt(node(filterBy(text)))))))),
			Arguments.of("hierarchyOfReference node filter", Query.query(collection("item"), locale,
				require(entityFetch(), new HierarchyOfReference("blocks", null, (OrderBy) null,
					fromRoot("tree", stopAt(node(filterBy(text)))))))),
			Arguments.of("hierarchyOfReference order", Query.query(collection("item"), matching,
				require(entityFetch(), new HierarchyOfReference("blocks", null,
					orderBy(relevanceText()), fromRoot("tree"))))),
			Arguments.of("hierarchyOfSelf order", Query.query(collection("item"), matching,
				require(entityFetch(), hierarchyOfSelf(orderBy(relevanceText()), fromRoot("tree")))))
		);
	}

	/**
	 * Builds an entity query with deterministic fallback ordering and a body fetch for converter/API parity.
	 *
	 * @param filter predicate whose placement is exercised
	 * @param order ordering whose placement is exercised
	 * @return a query usable by Java and both example converters
	 */
	@Nonnull
	private static Query positionQuery(
		@Nullable FilterConstraint filter, @Nullable OrderConstraint order
	) {
		return Query.query(collection("item"), filterBy(entityLocaleEquals(Locale.ENGLISH), filter),
			orderBy(order, entityPrimaryKeyNatural(OrderDirection.ASC)), require(entityFetch()));
	}

	/**
	 * Checks a real engine result or the engine's exact invalid-usage diagnostic through each public API.
	 *
	 * @param position readable schema placement
	 * @param query Java query used as the result/diagnostic oracle
	 * @param evita engine hosting the fixture
	 * @param graphql running GraphQL client
	 * @param rest running REST client
	 */
	@ParameterizedTest
	@MethodSource("positions")
	@UseDataSet(DATA_SET)
	@DisplayName("Offered nested positions return the Java result or the engine refusal cleanly")
	void shouldExposePositionOutcome(
		@Nonnull String position, @Nonnull Query query, @Nonnull Evita evita,
		@Nonnull GraphQLTester graphql, @Nonnull RestTester rest
	) {
		final String gql;
		if (position.startsWith("referenceSummary")) {
			final boolean order = position.endsWith("order");
			final boolean group = position.contains("group");
			gql = "query { queryItem(filterBy:{entityLocaleEquals:en" +
				(order ? ",textMatches:{text:\"quartz\"}" : "") + "}) {recordPage {data {primaryKey}} " +
				"extraResults {referenceSummary {brand" +
				(group ? "(" + (order ? "orderGroupBy:[{relevanceText:[]}]" :
					"filterGroupBy:{textMatches:{text:\"quartz\"}}") + ") {count}" :
					" {count facetStatistics(" + (order ? "orderBy:[{relevanceText:[]}]" :
						"filterBy:{textMatches:{text:\"quartz\"}}") + ") {count}}") + "}}}}";
		} else if (position.equals("histogramHaving groupHaving")) {
			gql = "query { queryItem(filterBy:{entityLocaleEquals:en,referenceBrandHistogramHaving:" +
				"{from:\"0\",to:\"10\",groupHaving:{textMatches:{text:\"quartz\"}}}})" +
				"{recordPage {data {primaryKey}}}}";
		} else {
			gql = GraphQLQueryConverter.convert(evita, CATALOG, query);
		}
		final String converted = position.equals("histogramHaving groupHaving") ||
			position.equals("hierarchyContent node filter") ? "" :
			new RestQueryConverter().convert(evita, CATALOG, query);
		final String body;
		if (position.equals("histogramHaving groupHaving")) {
			body = "{\"filterBy\":{\"entityLocaleEquals\":\"en\",\"referenceBrandHistogramHaving\":" +
				"{\"from\":\"0\",\"to\":\"10\",\"groupHaving\":{\"textMatches\":{\"text\":\"quartz\"}}}}," +
				"\"require\":{\"entityFetch\":{}}}";
		} else if (position.equals("hierarchyContent node filter")) {
			body = "{\"filterBy\":{\"entityLocaleEquals\":\"en\"},\"require\":{\"entityFetch\":" +
				"{\"hierarchyContent\":{\"stopAt\":{\"node\":{\"filterBy\":{" +
				"\"textMatches\":{\"text\":\"quartz\"}}}}}}}}";
		} else {
			body = converted.substring(converted.indexOf('{'));
		}
		final AtomicReference<String> refused = new AtomicReference<>();
		final List<Integer> expected = evita.queryCatalog(CATALOG, session -> {
			try {
				return session.query(query, SealedEntity.class).getRecordData().stream()
					.map(EntityClassifier::getPrimaryKey).toList();
			} catch (EvitaInvalidUsageException ex) {
				refused.set(ex.getMessage());
				return List.of();
			}
		});
		final String refusal = refused.get();
		if (refusal == null) {
			assertEquals(EMPTY_POSITIONS.contains(position), expected.isEmpty(), position + ": " + expected);
			graphql.test(CATALOG).document(gql).executeAndExpectOkAndThen()
				.body("data.queryItem.recordPage.data.primaryKey", equalTo(expected));
			rest.test(CATALOG).post("/item/query").requestBody(body).executeAndExpectOkAndThen()
				.body("recordPage.data.primaryKey", equalTo(expected));
		} else {
			final String graphqlResponse = graphql.test(CATALOG).document(gql)
				.executeAndExpectErrorsAndThen().extract().asString();
			final String restResponse = rest.test(CATALOG).post("/item/query").requestBody(body)
				.executeAndExpectBadRequestAndThen().extract().asString();
			assertTrue(FulltextSchemaFunctionalTestSupport.read(graphqlResponse).findValuesAsText("message")
				.stream().anyMatch(message -> message.contains(refusal)), graphqlResponse);
			assertTrue(FulltextSchemaFunctionalTestSupport.read(restResponse).findValuesAsText("message")
				.stream().anyMatch(message -> message.contains(refusal)), restResponse);
		}
	}

	/**
	 * Requires a catalog without searchable places to expose neither a fulltext container nor a wildcard selector in
	 * any GraphQL input type or REST component.
	 *
	 * @param evita engine containing a catalog without searchable places
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Catalog without searchable places generates no fulltext constraint fields or TEXT types")
	void shouldLeaveUnsearchableCatalogSchemaUnchanged(@Nonnull Evita evita) {
		final GraphQLSchema graphQL = graphQLSchema(evita, "plainQuery");
		for (final var type : graphQL.getAllTypesAsList()) {
			if (type instanceof GraphQLInputObjectType object) {
				assertNull(object.getFieldDefinition("textMatches"), object.getName());
				assertNull(object.getFieldDefinition("relevanceText"), object.getName());
				assertNull(object.getFieldDefinition("attributeInAll"), object.getName());
				assertNull(object.getFieldDefinition("associatedDataInAll"), object.getName());
			}
		}
		final OpenAPI rest = openAPI(evita, "plainQuery");
		for (final Schema<?> schema : rest.getComponents().getSchemas().values()) {
			if (schema.getProperties() != null) {
				for (final String key : List.of(
					"textMatches", "relevanceText", "attributeInAll", "associatedDataInAll")) {
					assertFalse(schema.getProperties().containsKey(key), key);
				}
			}
		}
	}

	/**
	 * Resolves an OpenAPI component reference for structural offering assertions.
	 *
	 * @param api document containing the referenced components
	 * @param schema inline schema or component reference
	 * @return the referenced component, or the original inline schema
	 */
	@Nonnull
	private static Schema<?> restSchema(@Nonnull OpenAPI api, @Nonnull Schema<?> schema) {
		if (schema.get$ref() != null) {
			return restSchema(api, api.getComponents().getSchemas()
				.get(schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1)));
		}
		return schema;
	}

	/**
	 * Follows a required property in the generated REST request schema.
	 *
	 * @param api document containing referenced components
	 * @param parent schema containing the property
	 * @param name property to follow
	 * @return the resolved property schema
	 */
	@Nonnull
	private static Schema<?> restField(@Nonnull OpenAPI api, @Nonnull Schema<?> parent, @Nonnull String name) {
		final Schema<?> resolved = restSchema(api, parent);
		final Schema<?> object = resolved.getItems() == null ? resolved : restSchema(api, resolved.getItems());
		assertNotNull(object.getProperties(), name);
		assertNotNull(object.getProperties().get(name), name);
		return restSchema(api, (Schema<?>) object.getProperties().get(name));
	}

	/**
	 * Resolves a component reference and an array wrapper to the object holding the properties.
	 *
	 * @param api document containing the referenced components
	 * @param schema property schema, component reference or array of either
	 * @return the resolved object schema
	 */
	@Nonnull
	private static Schema<?> restObject(@Nonnull OpenAPI api, @Nonnull Schema<?> schema) {
		final Schema<?> resolved = restSchema(api, schema);
		return resolved.getItems() == null ? resolved : restSchema(api, resolved.getItems());
	}

	/**
	 * Returns the TEXT place selectors offered by `textMatches.in` of a collection's REST query request.
	 *
	 * @param api document containing the referenced components
	 * @param path query endpoint of the collection
	 * @return selector properties keyed by their API names
	 */
	@Nonnull
	private static Map<String, Schema> restPlaces(@Nonnull OpenAPI api, @Nonnull String path) {
		final Schema<?> request = api.getPaths().get(path).getPost().getRequestBody().getContent()
			.get("application/json").getSchema();
		return restObject(api, restField(api, restField(api, restField(api, request, "filterBy"), "textMatches"),
			"in")).getProperties();
	}

	/**
	 * Proves that a non-searchable request subtree creates neither fulltext fields nor a reachable TEXT container.
	 *
	 * @param api document containing the referenced components
	 * @param schema request subtree to inspect
	 * @param visited component references already traversed to avoid recursive container loops
	 */
	private static void assertNoRestTextFields(
		@Nonnull OpenAPI api, @Nonnull Schema<?> schema, @Nonnull Set<String> visited
	) {
		if (schema.get$ref() != null && !visited.add(schema.get$ref())) {
			return;
		}
		final Schema<?> resolved = restSchema(api, schema);
		if (resolved.getProperties() != null) {
			for (final String key : List.of(
					"textMatches", "relevanceText", "attributeInAll", "associatedDataInAll")) {
				assertFalse(resolved.getProperties().containsKey(key), key);
			}
			for (final Schema<?> child : resolved.getProperties().values()) {
				assertNoRestTextFields(api, child, visited);
			}
		}
		if (resolved.getItems() != null) {
			assertNoRestTextFields(api, resolved.getItems(), visited);
		}
		if (resolved.getAllOf() != null) {
			for (final Schema<?> child : resolved.getAllOf()) {
				assertNoRestTextFields(api, child, visited);
			}
		}
	}

	/**
	 * Unwraps list and non-null wrappers from a GraphQL input type.
	 *
	 * @param type wrapped input type
	 * @return the underlying input object
	 */
	@Nonnull
	private static GraphQLInputObjectType input(@Nonnull GraphQLInputType type) {
		return (GraphQLInputObjectType) GraphQLTypeUtil.unwrapAll(type);
	}

	/**
	 * Follows a required input-object field in a schema assertion.
	 *
	 * @param parent object containing the field
	 * @param name field to follow
	 * @return the unwrapped input object
	 */
	@Nonnull
	private static GraphQLInputObjectType field(@Nonnull GraphQLInputObjectType parent, @Nonnull String name) {
		assertNotNull(parent.getFieldDefinition(name), parent.getName() + "." + name);
		return input(parent.getFieldDefinition(name).getType());
	}
}
