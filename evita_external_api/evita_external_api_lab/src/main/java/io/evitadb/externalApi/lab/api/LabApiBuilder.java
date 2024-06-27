/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.lab.api;

import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogUnionDescriptor;
import io.evitadb.externalApi.api.system.model.CorruptedCatalogDescriptor;
import io.evitadb.externalApi.lab.api.builder.GenericCatalogSchemaObjectBuilder;
import io.evitadb.externalApi.lab.api.builder.GenericEntityObjectBuilder;
import io.evitadb.externalApi.lab.api.builder.GenericEntitySchemaObjectBuilder;
import io.evitadb.externalApi.lab.api.builder.GenericFullResponseObjectBuilder;
import io.evitadb.externalApi.lab.api.builder.LabApiBuildingContext;
import io.evitadb.externalApi.lab.api.builder.LabApiEndpointBuilder;
import io.evitadb.externalApi.lab.api.model.GraphQLSchemaDiffResponseDescriptor;
import io.evitadb.externalApi.lab.api.model.GraphQLSchemaDiffResponseDescriptor.ChangeDescriptor;
import io.evitadb.externalApi.lab.api.model.OpenApiSchemaDiffResponseDescriptor;
import io.evitadb.externalApi.lab.api.model.QueryEntitiesRequestBodyDescriptor;
import io.evitadb.externalApi.lab.api.model.SchemaDiffRequestBodyDescriptor;
import io.evitadb.externalApi.lab.configuration.LabConfig;
import io.evitadb.externalApi.rest.api.Rest;
import io.evitadb.externalApi.rest.api.builder.FinalRestBuilder;
import io.evitadb.externalApi.rest.api.model.ErrorDescriptor;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObjectUnionType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiUnion;
import io.evitadb.externalApi.rest.api.system.model.LivenessDescriptor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Creates OpenAPI specification for evitaDB management.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class LabApiBuilder extends FinalRestBuilder<LabApiBuildingContext> {

	@Nonnull private final LabApiEndpointBuilder endpointBuilder;

	@Nonnull private final GenericEntityObjectBuilder entityObjectBuilder;
	@Nonnull private final GenericFullResponseObjectBuilder fullResponseObjectBuilder;

	@Nonnull private final GenericCatalogSchemaObjectBuilder catalogSchemaObjectBuilder;
	@Nonnull private final GenericEntitySchemaObjectBuilder entitySchemaObjectBuilder;

	/**
	 * Creates new builder.
	 */
	public LabApiBuilder(@Nullable String exposedOn, @Nonnull LabConfig labConfig, @Nonnull Evita evita) {
		super(new LabApiBuildingContext(exposedOn, labConfig, evita));
		this.endpointBuilder = new LabApiEndpointBuilder(operationPathParameterBuilderTransformer);

		this.entityObjectBuilder = new GenericEntityObjectBuilder(
			buildingContext,
			propertyBuilderTransformer,
			objectBuilderTransformer
		);
		this.fullResponseObjectBuilder = new GenericFullResponseObjectBuilder(
			buildingContext,
			propertyBuilderTransformer,
			objectBuilderTransformer,
			unionBuilderTransformer,
			dictionaryBuilderTransformer
		);

		this.catalogSchemaObjectBuilder = new GenericCatalogSchemaObjectBuilder(
			buildingContext,
			objectBuilderTransformer,
			dictionaryBuilderTransformer,
			propertyBuilderTransformer
		);
		this.entitySchemaObjectBuilder = new GenericEntitySchemaObjectBuilder(
			buildingContext,
			objectBuilderTransformer,
			unionBuilderTransformer,
			dictionaryBuilderTransformer,
			propertyBuilderTransformer
		);
	}

	/**
	 * Builds OpenAPI specification for evitaDB management.
	 *
	 * @return OpenAPI specification
	 */
	public Rest build() {
		buildCommonTypes();
		buildEndpoints();

		return buildingContext.buildRest();
	}

	private void buildCommonTypes() {
		// common
		buildingContext.registerType(NameVariantsDescriptor.THIS.to(objectBuilderTransformer).build());

		// data api
		buildingContext.registerType(ErrorDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(LivenessDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(CatalogDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(CorruptedCatalogDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(buildCatalogUnion());
		entityObjectBuilder.buildCommonTypes();
		fullResponseObjectBuilder.buildCommonTypes();
		buildingContext.registerType(QueryEntitiesRequestBodyDescriptor.THIS.to(objectBuilderTransformer).build());

		// schema api
		catalogSchemaObjectBuilder.buildCommonTypes();
		entitySchemaObjectBuilder.buildCommonTypes();

		// tools api
		buildingContext.registerType(SchemaDiffRequestBodyDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(GraphQLSchemaDiffResponseDescriptor.ChangeDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(GraphQLSchemaDiffResponseDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(OpenApiSchemaDiffResponseDescriptor.ChangeDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(OpenApiSchemaDiffResponseDescriptor.THIS.to(objectBuilderTransformer).build());
	}

	private void buildEndpoints() {
		buildingContext.registerEndpoint(endpointBuilder.buildOpenApiSpecificationEndpoint());
		buildingContext.registerEndpoint(endpointBuilder.buildLivenessEndpoint());

		// data api
		buildingContext.registerEndpoint(endpointBuilder.buildListCatalogsEndpoint());
		buildingContext.registerEndpoint(endpointBuilder.buildQueryEntitiesEndpoint());

		// schema api
		buildingContext.registerEndpoint(endpointBuilder.buildGetCatalogSchemaEndpoint());

		// tools api
		buildingContext.registerEndpoint(endpointBuilder.buildGraphQLSchemaDiffEndpoint());
		buildingContext.registerEndpoint(endpointBuilder.buildOpenApiSchemaDiffEndpoint());
	}

	@Nonnull
	private OpenApiUnion buildCatalogUnion() {
		return CatalogUnionDescriptor.THIS
			.to(unionBuilderTransformer)
			.type(OpenApiObjectUnionType.ONE_OF)
			.object(typeRefTo(CatalogDescriptor.THIS.name()))
			.object(typeRefTo(CorruptedCatalogDescriptor.THIS.name()))
			.build();
	}
}
