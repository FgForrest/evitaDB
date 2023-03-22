/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.EntitySchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.ModifyEntitySchemaDescriptionMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
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
		converter = new ModifyEntitySchemaMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final ModifyEntitySchemaMutation expectedMutation = new ModifyEntitySchemaMutation(
			"product",
			new ModifyEntitySchemaDescriptionMutation("desc")
		);

		final ModifyEntitySchemaMutation convertedMutation = converter.convert(
			map()
				.e(ModifyEntitySchemaMutationDescriptor.ENTITY_TYPE.name(), "product")
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

		final ModifyEntitySchemaMutation convertedMutation = converter.convert(
			map()
				.e(ModifyEntitySchemaMutationDescriptor.ENTITY_TYPE.name(), "product")
				.e(ModifyEntitySchemaMutationDescriptor.SCHEMA_MUTATIONS.name(), List.of())
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
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
			() -> converter.convert(
				map()
					.e(ModifyEntitySchemaMutationDescriptor.ENTITY_TYPE.name(), "product")
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}
}