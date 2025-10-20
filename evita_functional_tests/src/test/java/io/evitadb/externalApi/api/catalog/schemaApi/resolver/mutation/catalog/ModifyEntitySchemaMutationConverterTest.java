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

import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDescriptionMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.EntitySchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.ModifyEntitySchemaDescriptionMutationDescriptor;
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
 * Tests for {@link ModifyEntitySchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class ModifyEntitySchemaMutationConverterTest {

	private ModifyEntitySchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new ModifyEntitySchemaMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final ModifyEntitySchemaMutation expectedMutation = new ModifyEntitySchemaMutation(
			"product",
			new ModifyEntitySchemaDescriptionMutation("desc")
		);

		final ModifyEntitySchemaMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(ModifyEntitySchemaMutationDescriptor.NAME.name(), "product")
				.e(ModifyEntitySchemaMutationDescriptor.SCHEMA_MUTATIONS.name(), List.of(
					map()
						.e(EntitySchemaMutationAggregateDescriptor.MODIFY_ENTITY_SCHEMA_DESCRIPTION_MUTATION.name(), map()
							.e(ModifyEntitySchemaDescriptionMutationDescriptor.DESCRIPTION.name(), "desc")
							.build())
						.build()
				))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}
	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final ModifyEntitySchemaMutation expectedMutation = new ModifyEntitySchemaMutation("product");

		final ModifyEntitySchemaMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(ModifyEntitySchemaMutationDescriptor.NAME.name(), "product")
				.e(ModifyEntitySchemaMutationDescriptor.SCHEMA_MUTATIONS.name(), List.of())
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ModifyEntitySchemaMutationDescriptor.SCHEMA_MUTATIONS.name(), List.of(
						map()
							.e(EntitySchemaMutationAggregateDescriptor.MODIFY_ENTITY_SCHEMA_DESCRIPTION_MUTATION.name(), map()
								.e(ModifyEntitySchemaDescriptionMutationDescriptor.DESCRIPTION.name(), "desc")
								.build())
							.build()
					))
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ModifyEntitySchemaMutationDescriptor.NAME.name(), "product")
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final ModifyEntitySchemaMutation inputMutation = new ModifyEntitySchemaMutation(
			"product",
			new ModifyEntitySchemaDescriptionMutation("desc")
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), ModifyEntitySchemaMutation.class.getSimpleName())
					.e(ModifyEntitySchemaMutationDescriptor.NAME.name(), "product")
					.e(ModifyEntitySchemaMutationDescriptor.SCHEMA_MUTATIONS.name(), List.of(
						map()
							.e(ModifyEntitySchemaDescriptionMutationDescriptor.MUTATION_TYPE.name(), ModifyEntitySchemaDescriptionMutation.class.getSimpleName())
							.e(ModifyEntitySchemaDescriptionMutationDescriptor.DESCRIPTION.name(), "desc")
							.build()
					))
					.build()
			);
	}
}
