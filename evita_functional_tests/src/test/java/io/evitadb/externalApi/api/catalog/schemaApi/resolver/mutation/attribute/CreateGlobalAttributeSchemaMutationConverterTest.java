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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.attribute;

import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedGlobalAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.CreateGlobalAttributeSchemaMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.ListBuilder.array;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
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
		this.converter = new CreateGlobalAttributeSchemaMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final CreateGlobalAttributeSchemaMutation expectedMutation = new CreateGlobalAttributeSchemaMutation(
			"code",
			"desc",
			"depr",
			new ScopedAttributeUniquenessType[] {
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			},
			new ScopedGlobalAttributeUniquenessType[] {
				new ScopedGlobalAttributeUniquenessType(Scope.LIVE, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG)
			},
			new Scope[] { Scope.LIVE },
			new Scope[] { Scope.LIVE },
			true,
			false,
			true,
			String.class,
			"defaultCode",
			2
		);

		final CreateGlobalAttributeSchemaMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE_IN_SCOPES.name(), list()
					.i(map()
						.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
						.e(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)))
				.e(CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE_GLOBALLY_IN_SCOPES.name(), list()
					.i(map()
						.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
						.e(ScopedGlobalAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG)))
				.e(CreateGlobalAttributeSchemaMutationDescriptor.FILTERABLE_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.e(CreateGlobalAttributeSchemaMutationDescriptor.SORTABLE_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.e(CreateGlobalAttributeSchemaMutationDescriptor.LOCALIZED.name(), true)
				.e(CreateGlobalAttributeSchemaMutationDescriptor.NULLABLE.name(), false)
				.e(CreateGlobalAttributeSchemaMutationDescriptor.REPRESENTATIVE.name(), true)
				.e(CreateGlobalAttributeSchemaMutationDescriptor.TYPE.name(), String.class)
				.e(CreateGlobalAttributeSchemaMutationDescriptor.DEFAULT_VALUE.name(), "defaultCode")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.INDEXED_DECIMAL_PLACES.name(), 2)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final CreateGlobalAttributeSchemaMutation convertedMutation2 = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE_IN_SCOPES.name(), list()
					.i(map()
						.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
						.e(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION.name())))
				.e(CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE_GLOBALLY_IN_SCOPES.name(), list()
					.i(map()
						.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
						.e(ScopedGlobalAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG.name())))
				.e(CreateGlobalAttributeSchemaMutationDescriptor.FILTERABLE_IN_SCOPES.name(), list()
					.i(Scope.LIVE.name()))
				.e(CreateGlobalAttributeSchemaMutationDescriptor.SORTABLE_IN_SCOPES.name(), list()
					.i(Scope.LIVE.name()))
				.e(CreateGlobalAttributeSchemaMutationDescriptor.LOCALIZED.name(), "true")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.NULLABLE.name(), "false")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.REPRESENTATIVE.name(), "true")
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
			(ScopedAttributeUniquenessType[]) null,
			null,
			null,
			null,
			false,
			false,
			false,
			String.class,
			null,
			0
		);

		final CreateGlobalAttributeSchemaMutation convertedMutation1 = this.converter.convertFromInput(
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
			() -> this.converter.convertFromInput(
				map()
					.e(CreateGlobalAttributeSchemaMutationDescriptor.TYPE.name(), String.class)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final CreateGlobalAttributeSchemaMutation inputMutation = new CreateGlobalAttributeSchemaMutation(
			"code",
			"desc",
			"depr",
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			},
			new ScopedGlobalAttributeUniquenessType[]{
				new ScopedGlobalAttributeUniquenessType(Scope.LIVE, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG)
			},
			new Scope[]{Scope.LIVE},
			new Scope[]{Scope.LIVE},
			true,
			false,
			true,
			String.class,
			"defaultCode",
			2
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
					.e(CreateGlobalAttributeSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
					.e(CreateGlobalAttributeSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
					.e(CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE_IN_SCOPES.name(), list()
						.i(map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
							.e(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION.name())))
					.e(CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE_GLOBALLY_IN_SCOPES.name(), list()
						.i(map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
							.e(ScopedGlobalAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG.name())))
					.e(CreateGlobalAttributeSchemaMutationDescriptor.FILTERABLE_IN_SCOPES.name(), array()
						.i(Scope.LIVE.name()))
					.e(CreateGlobalAttributeSchemaMutationDescriptor.SORTABLE_IN_SCOPES.name(), array()
						.i(Scope.LIVE.name()))
					.e(CreateGlobalAttributeSchemaMutationDescriptor.LOCALIZED.name(), true)
					.e(CreateGlobalAttributeSchemaMutationDescriptor.NULLABLE.name(), false)
					.e(CreateGlobalAttributeSchemaMutationDescriptor.REPRESENTATIVE.name(), true)
					.e(CreateGlobalAttributeSchemaMutationDescriptor.TYPE.name(), String.class.getSimpleName())
					.e(CreateGlobalAttributeSchemaMutationDescriptor.DEFAULT_VALUE.name(), "defaultCode")
					.e(CreateGlobalAttributeSchemaMutationDescriptor.INDEXED_DECIMAL_PLACES.name(), 2)
					.build()
			);
	}
}
