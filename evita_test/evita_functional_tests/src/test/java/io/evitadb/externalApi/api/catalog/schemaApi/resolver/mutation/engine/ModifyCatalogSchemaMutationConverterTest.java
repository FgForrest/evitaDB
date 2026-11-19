/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.engine;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSearchableMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.CreateEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.system.model.mutation.engine.EngineMutationDescriptor;
import io.evitadb.externalApi.api.system.model.mutation.engine.ModifyCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.system.resolver.mutation.engine.ModifyCatalogSchemaMutationConverter;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static java.math.BigDecimal.valueOf;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;

/**
 * Test class for {@link ModifyCatalogSchemaMutationConverter}. This test suite verifies
 * the functionality of the catalog schema modification mutation converter, ensuring proper
 * conversion and handling of catalog schema modification operations in the external API context.
 * Tests cover various catalog modification scenarios including description updates and other schema changes.
 *
 * @author Lukáš Hornych, 2023
 */
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
public class ModifyCatalogSchemaMutationConverterTest {

	private ModifyCatalogSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new ModifyCatalogSchemaMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolverInputToLocalMutation() {
		final ModifyCatalogSchemaMutation expectedMutation = new ModifyCatalogSchemaMutation(
			"testCatalog",
			null,
			new CreateEntitySchemaMutation("product")
		);
		final ModifyCatalogSchemaMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(EngineMutationDescriptor.CATALOG_NAME.name(), "testCatalog")
				.e(ModifyCatalogSchemaMutationDescriptor.SCHEMA_MUTATIONS.name(), List.of(
					map()
						.e(
							LocalCatalogSchemaMutationInputAggregateDescriptor.CREATE_ENTITY_SCHEMA_MUTATION.name(), map()
							.e(CreateEntitySchemaMutationDescriptor.NAME.name(), "product"))
						.build()
				))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(EngineMutationDescriptor.CATALOG_NAME.name(), "testCatalog")
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ModifyCatalogSchemaMutationDescriptor.SCHEMA_MUTATIONS.name(), List.of(
						map()
							.e(
								LocalCatalogSchemaMutationInputAggregateDescriptor.CREATE_ENTITY_SCHEMA_MUTATION.name(), map()
								.e(CreateEntitySchemaMutationDescriptor.NAME.name(), "product"))
							.build()
					))
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final ModifyCatalogSchemaMutation inputMutation = new ModifyCatalogSchemaMutation(
			"testCatalog",
			null,
			new CreateEntitySchemaMutation("product")
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), ModifyCatalogSchemaMutation.class.getSimpleName())
					.e(EngineMutationDescriptor.CATALOG_NAME.name(), "testCatalog")
					.e(ModifyCatalogSchemaMutationDescriptor.SCHEMA_MUTATIONS.name(), List.of(
						map()
							.e(MutationDescriptor.MUTATION_TYPE.name(), CreateEntitySchemaMutation.class.getSimpleName())
							.e(CreateEntitySchemaMutationDescriptor.NAME.name(), "product")
							.build()
					))
					.build()
			);
	}

	/**
	 * The REST mapper serializes every nested dispatcher result into a JSON tree; the wrapper must embed the nested
	 * schema mutations as plain values, otherwise a system capture of any catalog schema change cannot be written.
	 */
	@Test
	void shouldSerializeNestedSchemaMutationsThroughRestMapper() {
		final ModifyCatalogSchemaMutationConverter restConverter = new ModifyCatalogSchemaMutationConverter(
			new RestMutationObjectMapper(new ObjectMapper()), TestMutationResolvingExceptionFactory.INSTANCE
		);
		final ModifyCatalogSchemaMutation inputMutation = new ModifyCatalogSchemaMutation(
			"testCatalog",
			null,
			new ModifyEntitySchemaMutation(
				"product",
				new SetAttributeSchemaSearchableMutation("text", Scope.DEFAULT_SCOPES, "text", 73, valueOf(0))
			)
		);

		final JsonNode serializedMutation = (JsonNode) restConverter.convertToOutput(inputMutation);

		final JsonNode entitySchemaMutation = serializedMutation.at("/schemaMutations/0");
		assertEquals(
			ModifyEntitySchemaMutation.class.getSimpleName(), entitySchemaMutation.at("/mutationType").asText()
		);
		final JsonNode attributeMutation = entitySchemaMutation.at("/schemaMutations/0");
		assertEquals(
			SetAttributeSchemaSearchableMutation.class.getSimpleName(), attributeMutation.at("/mutationType").asText()
		);
		assertEquals("0", attributeMutation.at("/searchWeight").asText());
	}
}
