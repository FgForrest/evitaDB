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

import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.CreateAttributeSchemaMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link CreateAttributeSchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class CreateAttributeSchemaMutationConverterTest {

	private CreateAttributeSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		converter = new CreateAttributeSchemaMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final CreateAttributeSchemaMutation expectedMutation = new CreateAttributeSchemaMutation(
			"code",
			"desc",
			"depr",
			true,
			false,
			true,
			false,
			true,
			String.class,
			"defaultCode",
			2
		);

		final CreateAttributeSchemaMutation convertedMutation1 = converter.convert(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateAttributeSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateAttributeSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateAttributeSchemaMutationDescriptor.UNIQUE.name(), true)
				.e(CreateAttributeSchemaMutationDescriptor.FILTERABLE.name(), false)
				.e(CreateAttributeSchemaMutationDescriptor.SORTABLE.name(), true)
				.e(CreateAttributeSchemaMutationDescriptor.LOCALIZED.name(), false)
				.e(CreateAttributeSchemaMutationDescriptor.NULLABLE.name(), true)
				.e(CreateAttributeSchemaMutationDescriptor.TYPE.name(), String.class)
				.e(CreateAttributeSchemaMutationDescriptor.DEFAULT_VALUE.name(), "defaultCode")
				.e(CreateAttributeSchemaMutationDescriptor.INDEXED_DECIMAL_PLACES.name(), 2)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final CreateAttributeSchemaMutation convertedMutation2 = converter.convert(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateAttributeSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateAttributeSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateAttributeSchemaMutationDescriptor.UNIQUE.name(), "true")
				.e(CreateAttributeSchemaMutationDescriptor.FILTERABLE.name(), "false")
				.e(CreateAttributeSchemaMutationDescriptor.SORTABLE.name(), "true")
				.e(CreateAttributeSchemaMutationDescriptor.LOCALIZED.name(), "false")
				.e(CreateAttributeSchemaMutationDescriptor.NULLABLE.name(), "true")
				.e(CreateAttributeSchemaMutationDescriptor.TYPE.name(), "String")
				.e(CreateAttributeSchemaMutationDescriptor.DEFAULT_VALUE.name(), "defaultCode")
				.e(CreateAttributeSchemaMutationDescriptor.INDEXED_DECIMAL_PLACES.name(), "2")
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}
	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final CreateAttributeSchemaMutation expectedMutation = new CreateAttributeSchemaMutation(
			"code",
			null,
			null,
			false,
			false,
			false,
			false,
			false,
			String.class,
			null,
			0
		);

		final CreateAttributeSchemaMutation convertedMutation1 = converter.convert(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateAttributeSchemaMutationDescriptor.TYPE.name(), String.class)
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
					.e(CreateAttributeSchemaMutationDescriptor.TYPE.name(), String.class)
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