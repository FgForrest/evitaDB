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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData;

import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaTypeMutation;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaTypeMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ModifyAssociatedDataSchemaTypeMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class ModifyAssociatedDataSchemaTypeMutationConverterTest {

	private ModifyAssociatedDataSchemaTypeMutationConverter converter;

	@BeforeEach
	void init() {
		converter = new ModifyAssociatedDataSchemaTypeMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final ModifyAssociatedDataSchemaTypeMutation expectedMutation = new ModifyAssociatedDataSchemaTypeMutation(
			"labels",
			ComplexDataObject.class
		);

		final ModifyAssociatedDataSchemaTypeMutation convertedMutation1 = converter.convert(
			map()
				.e(ModifyAssociatedDataSchemaTypeMutationDescriptor.NAME.name(), "labels")
				.e(ModifyAssociatedDataSchemaTypeMutationDescriptor.TYPE.name(), ComplexDataObject.class)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final ModifyAssociatedDataSchemaTypeMutation convertedMutation2 = converter.convert(
			map()
				.e(ModifyAssociatedDataSchemaTypeMutationDescriptor.NAME.name(), "labels")
				.e(ModifyAssociatedDataSchemaTypeMutationDescriptor.TYPE.name(), "ComplexDataObject")
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
					.e(ModifyAssociatedDataSchemaTypeMutationDescriptor.NAME.name(), "labels")
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(ModifyAssociatedDataSchemaTypeMutationDescriptor.TYPE.name(), ComplexDataObject.class)
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}
}