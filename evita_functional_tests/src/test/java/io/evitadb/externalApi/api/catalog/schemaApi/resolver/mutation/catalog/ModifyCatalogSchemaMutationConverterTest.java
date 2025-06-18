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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog;

import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.CreateEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyCatalogSchemaMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
public class ModifyCatalogSchemaMutationConverterTest {

	private ModifyCatalogSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new ModifyCatalogSchemaMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
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
				.e(ModifyCatalogSchemaMutationDescriptor.CATALOG_NAME.name(), "testCatalog")
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
					.e(ModifyCatalogSchemaMutationDescriptor.CATALOG_NAME.name(), "testCatalog")
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
					.e(ModifyCatalogSchemaMutationDescriptor.CATALOG_NAME.name(), "testCatalog")
					.e(ModifyCatalogSchemaMutationDescriptor.SCHEMA_MUTATIONS.name(), List.of(
						map()
							.e(LocalCatalogSchemaMutationAggregateDescriptor.CREATE_ENTITY_SCHEMA_MUTATION.name(), map()
								.e(CreateEntitySchemaMutationDescriptor.ENTITY_TYPE.name(), "product"))
							.build()
					))
					.build()
			);
	}
}
