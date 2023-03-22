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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.attribute;

import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaTypeMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.ModifyAttributeSchemaTypeMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ModifyAttributeSchemaTypeMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class ModifyAttributeSchemaTypeMutationConverterTest {

	private ModifyAttributeSchemaTypeMutationConverter converter;

	@BeforeEach
	void init() {
		converter = new ModifyAttributeSchemaTypeMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final ModifyAttributeSchemaTypeMutation expectedMutation = new ModifyAttributeSchemaTypeMutation(
			"code",
			Integer.class,
			3
		);

		final ModifyAttributeSchemaTypeMutation convertedMutation1 = converter.convert(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(ModifyAttributeSchemaTypeMutationDescriptor.TYPE.name(), Integer.class)
				.e(ModifyAttributeSchemaTypeMutationDescriptor.INDEXED_DECIMAL_PLACES.name(), 3)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final ModifyAttributeSchemaTypeMutation convertedMutation2 = converter.convert(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(ModifyAttributeSchemaTypeMutationDescriptor.TYPE.name(), "Integer")
				.e(ModifyAttributeSchemaTypeMutationDescriptor.INDEXED_DECIMAL_PLACES.name(), "3")
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(ModifyAttributeSchemaTypeMutationDescriptor.TYPE.name(), Integer.class)
					.e(ModifyAttributeSchemaTypeMutationDescriptor.INDEXED_DECIMAL_PLACES.name(), 3)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
					.e(ModifyAttributeSchemaTypeMutationDescriptor.INDEXED_DECIMAL_PLACES.name(), 3)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
					.e(ModifyAttributeSchemaTypeMutationDescriptor.TYPE.name(), Integer.class)
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}
}