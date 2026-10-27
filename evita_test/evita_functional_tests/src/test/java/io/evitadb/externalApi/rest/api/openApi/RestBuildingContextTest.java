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

import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.HeaderOptions;
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
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiObject.newObject;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiProperty.newProperty;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that an OpenAPI document with a dangling schema reference fails to build.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ExtendWith(EvitaParameterResolver.class)
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(SCHEMA)
@DisplayName("REST document reference validation")
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
	 * Uses real building contexts with their specification endpoints.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Dangling schema references")
	class DanglingReferences {

		/**
		 * A dangling reference fails the build with an error naming the document and every missing schema.
		 */
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {TEST_CATALOG, "system"})
		@UseDataSet(EMPTY_CATALOG)
		@DisplayName("Should fail building the document when references dangle")
		void shouldFailWhenReferencesDangle(@Nonnull String documentName, @Nonnull Evita evita) {
			final RestBuildingContext context = createContext(
				documentName, new RestOptions("localhost:5555"), HeaderOptions.builder().build(), evita
			);
			context.registerType(newObject().name("DanglingContainer")
				.property(newProperty().name("first").type(typeRefTo("MissingFirst")))
				.property(newProperty().name("second").type(typeRefTo("MissingSecond")))
				.build());

			final OpenApiBuildingError error = assertThrows(OpenApiBuildingError.class, context::buildRest);
			assertTrue(error.getPrivateMessage().contains("document `" + documentName + "`"),
				"The error must name the document");
			assertTrue(error.getPrivateMessage().contains("MissingFirst")
					&& error.getPrivateMessage().contains("MissingSecond"),
				"The error must name every missing schema");
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
	}
}
