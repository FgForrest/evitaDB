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


package io.evitadb.externalApi.api.catalog.dataApi.builder.constraint;

import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.filter.TextMatches;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSearchMode;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.CatalogDataApiGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.FilterConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.GraphQLConstraintSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.rest.api.catalog.CatalogRestBuilder;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/** Checks fulltext schema discovery against the engine's one-hop and child-marker boundaries. */
@ExtendWith(EvitaParameterResolver.class)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@DisplayName("Bounded fulltext schema places")
class FulltextSearchablePlacesSchemaTest {
	/** Shared real catalogs; no server is needed to inspect the production schema builders. */
	private static final String DATA_SET = "BoundedFulltextPlaces";
	/** Only a second composition hop has text. */
	private static final String TWO_HOP = "twoHopText";
	/** A composed target has text only on its reference body. */
	private static final String REFERENCE_ONLY = "referenceOnlyText";
	/** Small binary reference graph used for operation counts. */
	private static final String DISCOVERY = "textDiscovery";

	/**
	 * Defines the real schema graphs and one actual reference-only target match.
	 *
	 * @param evita engine providing the schemas and the native-query oracle
	 */
	@DataSet(DATA_SET)
	void setUp(@Nonnull Evita evita) {
		for (final String catalog : List.of(TWO_HOP, REFERENCE_ONLY)) {
			evita.defineCatalog(catalog);
			evita.updateCatalog(catalog, session -> {
				session.defineEntitySchema("leaf").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("name", String.class, a -> a.localized().searchable()).updateVia(session);
				final var middle = session.defineEntitySchema("middle").withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH);
				if (catalog.equals(REFERENCE_ONLY)) {
					middle.withReferenceToEntity("child", "leaf", Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFiltering().withAttribute("label", String.class,
							a -> a.localized().searchable()));
				} else {
					middle.withReferenceToEntity("child", "leaf", Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFiltering().searchableThrough(ReferenceSearchMode.COMPOSITION));
				}
				middle.updateVia(session);
				session.defineEntitySchema("owner").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("context", String.class, a -> a.localized())
					.withReferenceToEntity("middle", "middle", Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFiltering().searchableThrough(ReferenceSearchMode.COMPOSITION))
					.updateVia(session);
				session.upsertEntity(session.createNewEntity("leaf", 10)
					.setAttribute("name", Locale.ENGLISH, "quartz"));
				session.upsertEntity(session.createNewEntity("middle", 20).setReference("child", 10, reference -> {
					if (catalog.equals(REFERENCE_ONLY)) {
						reference.setAttribute("label", Locale.ENGLISH, "quartz");
					}
				}));
				session.upsertEntity(session.createNewEntity("owner", 1)
					.setAttribute("context", Locale.ENGLISH, "context")
					.setReference("middle", 20));
				session.goLiveAndClose();
			});
		}
		evita.defineCatalog(DISCOVERY);
		evita.updateCatalog(DISCOVERY, session -> {
			session.defineEntitySchema("node0").updateVia(session);
			for (int depth = 1; depth <= 10; depth++) {
				final String target = "node" + (depth - 1);
				session.defineEntitySchema("node" + depth)
					.withReferenceToEntity("left", target, Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFiltering().searchableThrough(ReferenceSearchMode.COMPOSITION))
					.withReferenceToEntity("right", target, Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFiltering().searchableThrough(ReferenceSearchMode.COMPOSITION))
					.updateVia(session);
			}
			session.goLiveAndClose();
		});
	}

	/**
	 * Counts discoveries rather than elapsed time, and verifies reuse in the same shared build context.
	 *
	 * @param evita engine holding the depth-ten binary graph
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("A depth-ten binary graph needs only bounded discoveries")
	void shouldBoundSearchablePlaceDiscoveries(@Nonnull Evita evita) {
		final var context = new CatalogGraphQLSchemaBuildingContext(new GraphQLOptions("localhost:5555"),
			evita, evita.getCatalogInstance(DISCOVERY).orElseThrow());
		/** Counts real attribute-schema discoveries made by the production eligibility check. */
		class CountingBuilder extends FilterConstraintSchemaBuilder {
			/** Counts schema discoveries without a timing assertion. */
			@Nonnull
			private final AtomicInteger discoveries = new AtomicInteger();

			/** Creates a builder sharing the production catalog context. */
			CountingBuilder() {
				super(new GraphQLConstraintSchemaBuildingContext(context));
			}

			/**
			 * Counts each metadata discovery before delegating to the production implementation.
			 *
			 * @param locator entity or reference whose attributes are requested
			 * @return production attribute selection
			 */
			@Nonnull
			@Override
			protected Collection<? extends AttributeSchemaContract> findAttributeSchemas(@Nonnull DataLocator locator) {
				this.discoveries.incrementAndGet();
				return super.findAttributeSchemas(locator);
			}

			/**
			 * Uses the public descriptor's child marker through the production eligibility guard.
			 *
			 * @return whether the graph's root has a legal fulltext place
			 */
			boolean check() {
				return canFieldBeCreatedFromConstraintDescriptor(new ConstraintBuildContext(
					new EntityDataLocator(new ManagedEntityTypePointer("node10"))),
					ConstraintDescriptorProvider.getConstraint(TextMatches.class));
			}
		}
		final CountingBuilder builder = new CountingBuilder();
		assertFalse(builder.check());
		final int discoveries = builder.discoveries.get();
		assertTrue(discoveries > 0 && discoveries <= 16, "Discoveries: " + discoveries);
		assertFalse(builder.check());
		assertEquals(discoveries, builder.discoveries.get(), "Shared build context must reuse discovery");
	}

	/**
	 * Requires GraphQL to omit searches whose only text is beyond the single legal composition hop.
	 *
	 * @param evita engine containing the two-hop catalog
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("GraphQL suppresses two-hop-only fulltext parents")
	void shouldSuppressTwoHopGraphQLPlaces(@Nonnull Evita evita) {
		final GraphQLSchema schema = graphQL(evita, TWO_HOP);
		assertNull(queryInput(schema, "owner", "filterBy").getFieldDefinition("textMatches"));
		assertNull(queryInput(schema, "owner", "orderBy").getFieldDefinition("relevanceText"));
		assertNotNull(queryInput(schema, "middle", "filterBy").getFieldDefinition("textMatches"));
	}

	/**
	 * Requires the same one-hop boundary in the generated REST request schema.
	 *
	 * @param evita engine containing the two-hop catalog
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("REST suppresses two-hop-only fulltext parents")
	void shouldSuppressTwoHopRestPlaces(@Nonnull Evita evita) {
		final OpenAPI api = rest(evita, TWO_HOP);
		assertNull(restObject(api, restProperty(api, request(api), "filterBy")).getProperties().get("textMatches"));
		assertNull(restObject(api, restProperty(api, request(api), "orderBy")).getProperties().get("relevanceText"));
	}

	/**
	 * Reference-body text cannot use a composed target even when inReference can transition to that target.
	 *
	 * @param evita engine containing the reference-only catalog
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("GraphQL reference-body text requires a reference attribute")
	void shouldSuppressGraphQLCompositionOnlyReferenceBody(@Nonnull Evita evita) {
		final GraphQLInputObjectType filter = queryInput(graphQL(evita, REFERENCE_ONLY), "owner", "filterBy");
		assertNull(field(filter, "referenceMiddleHaving").getFieldDefinition("textMatches"));
		final GraphQLInputObjectType target = field(
			field(field(field(filter, "textMatches"), "in"), "referenceMiddleIn"), "entityIn");
		assertNull(target.getFieldDefinition("referenceChildIn"),
			"Target selectors cannot take a second reference hop");
	}

	/**
	 * Checks the same reference-body versus target distinction in REST.
	 *
	 * @param evita engine containing the reference-only catalog
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("REST reference-body text requires a reference attribute")
	void shouldSuppressRestCompositionOnlyReferenceBody(@Nonnull Evita evita) {
		final OpenAPI api = rest(evita, REFERENCE_ONLY);
		final Schema<?> filter = restProperty(api, request(api), "filterBy");
		assertNull(restObject(api, restProperty(api, filter, "referenceMiddleHaving"))
			.getProperties().get("textMatches"));
		final Schema<?> text = restProperty(api, filter, "textMatches");
		final Schema<?> selected = restProperty(api, text, "in");
		final Schema<?> reference = restProperty(api, selected, "referenceMiddleIn");
		final Schema<?> target = restObject(api, restProperty(api, reference, "entityIn"));
		assertNull(target.getProperties().get("referenceChildIn"), "No second target-selector reference hop");
	}

	/**
	 * A wildcard selects only the places of its own level, so a level without any omits it: `owner` has neither
	 * attributes nor associated data, and its `middle` reference has no attribute, while the composed target stays
	 * reachable through `entityIn`.
	 *
	 * @param evita engine containing the reference-only catalog
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("GraphQL omits wildcard selectors at levels without their places")
	void shouldSuppressReferenceOnlyWildcards(@Nonnull Evita evita) {
		final GraphQLInputObjectType places = field(field(
			queryInput(graphQL(evita, REFERENCE_ONLY), "owner", "filterBy"), "textMatches"), "in");
		assertNull(places.getFieldDefinition("attributeInAll"));
		assertNull(places.getFieldDefinition("associatedDataInAll"));
		final GraphQLInputObjectType reference = field(places, "referenceMiddleIn");
		assertNull(reference.getFieldDefinition("attributeInAll"));
		assertNotNull(reference.getFieldDefinition("entityIn"));
	}

	/**
	 * Target reference attributes remain searchable by an empty entity selector, without addressable target weights.
	 *
	 * @param evita engine containing a real target reference-attribute match
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Empty entityIn searches a target's reference attributes")
	void shouldPreserveEmptyTargetSelector(@Nonnull Evita evita) {
		evita.queryCatalog(REFERENCE_ONLY, session -> {
			final Query query = Query.query(collection("owner"), filterBy(entityLocaleEquals(Locale.ENGLISH),
				textMatches("quartz", inReference("middle", inEntity()))));
			assertEquals(List.of(1), session.query(query, EntityReference.class).getRecordData().stream()
				.map(EntityReference::getPrimaryKey).toList());
		});
	}

	/**
	 * No target-weight child can address a target reference attribute; the impossible override must disappear.
	 *
	 * @param evita engine containing the reference-only target
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("GraphQL omits unaddressable composed weight containers")
	void shouldSuppressUnaddressableGraphQLWeights(@Nonnull Evita evita) {
		final GraphQLInputObjectType orders = queryInput(graphQL(evita, REFERENCE_ONLY), "owner", "orderBy");
		assertNotNull(orders.getFieldDefinition("relevanceText"), "Default relevance must remain available");
		final var weights = GraphQLTypeUtil.unwrapAll(orders.getFieldDefinition("relevanceText").getType());
		if (weights instanceof GraphQLInputObjectType object) {
			assertNull(object.getFieldDefinition("referenceMiddleWeight"));
		} else {
			assertEquals(GraphQLScalars.BOOLEAN, weights,
				"Existing framework limit: an empty container falls back to Boolean, not an empty-only list");
		}
	}

	/**
	 * No Boolean entityWeight or referenceWeight placeholder can advertise an impossible override.
	 *
	 * @param evita engine containing the reference-only target
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("REST omits unaddressable composed weight containers")
	void shouldSuppressUnaddressableRestWeights(@Nonnull Evita evita) {
		final OpenAPI api = rest(evita, REFERENCE_ONLY);
		final Schema<?> orders = restObject(api, restProperty(api, request(api), "orderBy"));
		final Schema<?> relevance = (Schema<?>) orders.getProperties().get("relevanceText");
		assertNotNull(relevance, "Default relevance must remain available");
		final Schema<?> weights = restObject(api, relevance);
		if (weights.getProperties() != null) {
			assertNull(weights.getProperties().get("referenceMiddleWeight"));
		} else {
			assertEquals("boolean", weights.getType(), "Existing empty-container framework limit");
		}
	}

	/**
	 * Builds the production GraphQL schema for a fixture catalog.
	 *
	 * @param evita engine containing the catalog
	 * @param catalog catalog to expose
	 * @return generated data API schema
	 */
	@Nonnull
	private static GraphQLSchema graphQL(@Nonnull Evita evita, @Nonnull String catalog) {
		return new CatalogDataApiGraphQLSchemaBuilder(new GraphQLOptions("localhost:5555"), evita,
			evita.getCatalogInstance(catalog).orElseThrow()).build();
	}

	/**
	 * Builds the production REST schema for a fixture catalog.
	 *
	 * @param evita engine containing the catalog
	 * @param catalog catalog to expose
	 * @return generated OpenAPI document
	 */
	@Nonnull
	private static OpenAPI rest(@Nonnull Evita evita, @Nonnull String catalog) {
		return new CatalogRestBuilder(new RestOptions(true, "localhost:5555", null, null, null, "rest", null),
			HeaderOptions.builder().build(), evita, evita.getCatalogInstance(catalog).orElseThrow()).build().openApi();
	}

	/**
	 * Resolves a query argument to its input object, including list/non-null wrappers.
	 *
	 * @param schema generated GraphQL schema
	 * @param collection queried collection
	 * @param argument filterBy or orderBy argument
	 * @return unwrapped constraint container
	 */
	@Nonnull
	private static GraphQLInputObjectType queryInput(
		@Nonnull GraphQLSchema schema, @Nonnull String collection, @Nonnull String argument
	) {
		final String query = "query" + Character.toUpperCase(collection.charAt(0)) + collection.substring(1);
		return (GraphQLInputObjectType) GraphQLTypeUtil.unwrapAll(
			schema.getQueryType().getFieldDefinition(query).getArgument(argument).getType());
	}

	/**
	 * Follows a GraphQL input-object field.
	 *
	 * @param parent containing input object
	 * @param name field to follow
	 * @return unwrapped field object
	 */
	@Nonnull
	private static GraphQLInputObjectType field(@Nonnull GraphQLInputObjectType parent, @Nonnull String name) {
		return (GraphQLInputObjectType) GraphQLTypeUtil.unwrapAll(parent.getFieldDefinition(name).getType());
	}

	/**
	 * Returns the owner's REST query request schema.
	 *
	 * @param api fixture OpenAPI document
	 * @return request body schema
	 */
	@Nonnull
	private static Schema<?> request(@Nonnull OpenAPI api) {
		return api.getPaths().get("/owner/query").getPost().getRequestBody().getContent()
			.get("application/json").getSchema();
	}

	/**
	 * Resolves component and array wrappers while following a field path.
	 *
	 * @param api document containing referenced components
	 * @param schema component reference, array or object
	 * @return resolved object or scalar
	 */
	@Nonnull
	private static Schema<?> restObject(@Nonnull OpenAPI api, @Nonnull Schema<?> schema) {
		if (schema.get$ref() != null) {
			return restObject(api, api.getComponents().getSchemas()
				.get(schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1)));
		}
		return schema.getItems() == null ? schema : restObject(api, schema.getItems());
	}

	/**
	 * Follows one named REST object property.
	 *
	 * @param api document containing referenced components
	 * @param parent parent schema
	 * @param name property to read
	 * @return property schema before array unwrapping
	 */
	@Nonnull
	private static Schema<?> restProperty(@Nonnull OpenAPI api, @Nonnull Schema<?> parent, @Nonnull String name) {
		return (Schema<?>) restObject(api, parent).getProperties().get(name);
	}
}
