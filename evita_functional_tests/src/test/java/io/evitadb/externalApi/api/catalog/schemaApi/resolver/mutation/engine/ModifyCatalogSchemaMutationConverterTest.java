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

import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.CreateEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.ModifyCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.EngineMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test class for {@link ModifyCatalogSchemaMutationConverter}. This test suite verifies
 * the functionality of the catalog schema modification mutation converter, ensuring proper
 * conversion and handling of catalog schema modification operations in the external API context.
 * Tests cover various catalog modification scenarios including description updates and other schema changes.
 *
 * @author Lukáš Hornych, 2023
 */
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
						.e(LocalCatalogSchemaMutationAggregateDescriptor.CREATE_ENTITY_SCHEMA_MUTATION.name(), map()
							.e(CreateEntitySchemaMutationDescriptor.ENTITY_TYPE.name(), "product"))
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
							.e(LocalCatalogSchemaMutationAggregateDescriptor.CREATE_ENTITY_SCHEMA_MUTATION.name(), map()
								.e(CreateEntitySchemaMutationDescriptor.ENTITY_TYPE.name(), "product"))
							.build()
					))
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
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
							.e(CreateEntitySchemaMutationDescriptor.ENTITY_TYPE.name(), "product")
							.build()
					))
					.build()
			);
	}
}
