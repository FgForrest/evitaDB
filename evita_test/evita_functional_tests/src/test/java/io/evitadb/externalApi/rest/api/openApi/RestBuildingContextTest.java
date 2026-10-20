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

package io.evitadb.externalApi.rest.api.openApi;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.server.Server;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.rest.api.Rest;
import io.evitadb.externalApi.rest.api.builder.RestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogEndpointBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.model.ErrorDescriptor;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDataTypeDescriptorToOpenApiTypeTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiOperationPathParameterTransformer;
import io.evitadb.externalApi.rest.api.system.builder.SystemEndpointBuilder;
import io.evitadb.externalApi.rest.api.system.builder.SystemRestBuildingContext;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.externalApi.rest.io.RestRouter;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.tester.RestOpenApiAssertions;
import io.evitadb.test.tester.RestTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiObject.newObject;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiProperty.newProperty;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that OpenAPI documentation errors are reported without preventing REST service.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ExtendWith(EvitaParameterResolver.class)
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(SCHEMA)
@DisplayName("REST document validation availability")
class RestBuildingContextTest {

	private static final String EMPTY_CATALOG = "RestBuildingContextEmptyCatalog";

	/**
	 * Shares the catalog between the catalog and system document cases.
	 */
	@DataSet(EMPTY_CATALOG)
	@Nonnull
	DataCarrier setUp() {
		return new DataCarrier();
	}

	/**
	 * Uses real building contexts, specification handlers and the REST router.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Dangling schema references")
	class DanglingReferences {

		/**
		 * A dangling reference remains visible in served JSON and produces a diagnostic with every missing name.
		 */
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {TEST_CATALOG, "system"})
		@UseDataSet(EMPTY_CATALOG)
		@DisplayName("Should log missing schemas and serve the document when references dangle")
		void shouldLogAndServeWhenReferencesDangle(@Nonnull String documentName, @Nonnull Evita evita)
			throws IOException {
			final RestOptions options = new RestOptions("localhost:5555");
			final HeaderOptions headers = HeaderOptions.builder().build();
			final RestBuildingContext context = createContext(documentName, options, headers, evita);
			context.registerType(newObject().name("DanglingContainer")
				.property(newProperty().name("first").type(typeRefTo("MissingFirst")))
				.property(newProperty().name("second").type(typeRefTo("MissingSecond")))
				.build());

			final Logger logger = assertInstanceOf(Logger.class, LoggerFactory.getLogger(RestBuildingContext.class));
			final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
			final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
				/**
				 * Materializes lazy log event fields before retaining the event for assertions.
				 */
				@Override
				protected void append(@Nonnull ILoggingEvent event) {
					event.prepareForDeferredProcessing();
					events.add(event);
				}
			};
			appender.start();
			logger.addAppender(appender);
			try {
				final Rest rest = assertDoesNotThrow(context::buildRest,
					"Dangling documentation references must not prevent REST API construction");
				assertEquals(Set.of("MissingFirst", "MissingSecond"),
					new OpenApiReferenceValidator(rest.openApi()).validateSchemaReferences());
				assertTrue(events.stream().anyMatch(event -> event.getLevel() == Level.ERROR
					&& event.getFormattedMessage().contains("document `" + documentName + "`")
					&& event.getFormattedMessage().contains("MissingFirst")
					&& event.getFormattedMessage().contains("MissingSecond")),
					"The error must name the document and every missing schema");
				final Server server = createServer(documentName, rest, options, headers);
				try {
					server.start().join();
					final JsonNode served = RestOpenApiAssertions.fetchDocument(
						new RestTester("http://localhost:" + server.activeLocalPort()), documentName
					);
					assertEquals("#/components/schemas/MissingFirst",
						served.at("/components/schemas/DanglingContainer/properties/first/$ref").asText());
					assertEquals("#/components/schemas/MissingSecond",
						served.at("/components/schemas/DanglingContainer/properties/second/$ref").asText());
					assertThrows(AssertionError.class,
						() -> RestOpenApiAssertions.assertReferencesResolve(served, documentName),
						"The shared strict check must reject the served dangling reference");
				} finally {
					server.stop().join();
				}
			} finally {
				logger.detachAppender(appender);
				appender.stop();
			}
		}

		/**
		 * Creates the requested document's building context with its specification endpoint and the error type.
		 */
		@Nonnull
		private RestBuildingContext createContext(
			@Nonnull String documentName,
			@Nonnull RestOptions options,
			@Nonnull HeaderOptions headers,
			@Nonnull Evita evita
		) {
			final RestBuildingContext context;
			if ("system".equals(documentName)) {
				final SystemRestBuildingContext systemContext = new SystemRestBuildingContext(options, headers, evita);
				systemContext.registerEndpoint(new SystemEndpointBuilder(
					new PropertyDescriptorToOpenApiOperationPathParameterTransformer(
						new PropertyDataTypeDescriptorToOpenApiTypeTransformer(systemContext)
					)
				).buildOpenApiSpecificationEndpoint());
				context = systemContext;
			} else {
				final CatalogRestBuildingContext catalogContext = new CatalogRestBuildingContext(
					options, headers, evita, evita.getCatalogInstanceOrThrowException(TEST_CATALOG)
				);
				catalogContext.registerEndpoint(
					new CatalogEndpointBuilder().buildOpenApiSpecificationEndpoint(catalogContext)
				);
				context = catalogContext;
			}
			context.registerType(ErrorDescriptor.THIS.to(new ObjectDescriptorToOpenApiObjectTransformer(
				new PropertyDescriptorToOpenApiPropertyTransformer(
					new PropertyDataTypeDescriptorToOpenApiTypeTransformer(context)
				)
			)).build());
			return context;
		}

		/**
		 * Creates an unstarted server on an ephemeral port that serves the document through the production router.
		 */
		@Nonnull
		private Server createServer(
			@Nonnull String documentName,
			@Nonnull Rest rest,
			@Nonnull RestOptions options,
			@Nonnull HeaderOptions headers
		) {
			final RestRouter router = new RestRouter(new ObjectMapper(), headers, options);
			if ("system".equals(documentName)) {
				router.registerSystemApi(rest);
			} else {
				router.registerCatalogApi(documentName, rest);
			}
			return Server.builder().http(0).serviceUnder("/", router).build();
		}
	}
}
