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

import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.CreateGlobalAttributeSchemaMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link CreateGlobalAttributeSchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class CreateGlobalAttributeSchemaMutationConverterTest {

	private CreateGlobalAttributeSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		converter = new CreateGlobalAttributeSchemaMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final CreateGlobalAttributeSchemaMutation expectedMutation = new CreateGlobalAttributeSchemaMutation(
			"code",
			"desc",
			"depr",
			true,
			false,
			true,
			false,
			true,
			false,
			String.class,
			"defaultCode",
			2
		);

		final CreateGlobalAttributeSchemaMutation convertedMutation1 = converter.convert(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE.name(), true)
				.e(CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE_GLOBALLY.name(), false)
				.e(CreateGlobalAttributeSchemaMutationDescriptor.FILTERABLE.name(), true)
				.e(CreateGlobalAttributeSchemaMutationDescriptor.SORTABLE.name(), false)
				.e(CreateGlobalAttributeSchemaMutationDescriptor.LOCALIZED.name(), true)
				.e(CreateGlobalAttributeSchemaMutationDescriptor.NULLABLE.name(), false)
				.e(CreateGlobalAttributeSchemaMutationDescriptor.TYPE.name(), String.class)
				.e(CreateGlobalAttributeSchemaMutationDescriptor.DEFAULT_VALUE.name(), "defaultCode")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.INDEXED_DECIMAL_PLACES.name(), 2)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final CreateGlobalAttributeSchemaMutation convertedMutation2 = converter.convert(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE.name(), "true")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE_GLOBALLY.name(), "false")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.FILTERABLE.name(), "true")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.SORTABLE.name(), "false")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.LOCALIZED.name(), "true")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.NULLABLE.name(), "false")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.TYPE.name(), "String")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.DEFAULT_VALUE.name(), "defaultCode")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.INDEXED_DECIMAL_PLACES.name(), "2")
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}
	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final CreateGlobalAttributeSchemaMutation expectedMutation = new CreateGlobalAttributeSchemaMutation(
			"code",
			null,
			null,
			false,
			false,
			false,
			false,
			false,
			false,
			String.class,
			null,
			0
		);

		final CreateGlobalAttributeSchemaMutation convertedMutation1 = converter.convert(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.TYPE.name(), String.class)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(CreateGlobalAttributeSchemaMutationDescriptor.TYPE.name(), String.class)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}
}