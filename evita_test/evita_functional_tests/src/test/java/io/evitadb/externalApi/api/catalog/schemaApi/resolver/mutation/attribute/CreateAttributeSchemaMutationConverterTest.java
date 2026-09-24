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

import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeFilterAcceleratorsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.CreateAttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import org.junit.jupiter.api.Tag;

import static io.evitadb.utils.ListBuilder.array;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * Tests for {@link CreateAttributeSchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class CreateAttributeSchemaMutationConverterTest {

	private CreateAttributeSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new CreateAttributeSchemaMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final CreateAttributeSchemaMutation expectedMutation = new CreateAttributeSchemaMutation(
			"code",
			"desc",
			"depr",
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			},
			new Scope[] { Scope.LIVE },
			new Scope[] { Scope.LIVE },
			false,
			true,
			true,
			String.class,
			"defaultCode",
			2
		);

		final CreateAttributeSchemaMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateAttributeSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateAttributeSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateAttributeSchemaMutationDescriptor.UNIQUE_IN_SCOPES.name(), list()
					.i(map()
						.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
						.e(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)))
				.e(CreateAttributeSchemaMutationDescriptor.FILTERABLE_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.e(CreateAttributeSchemaMutationDescriptor.SORTABLE_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.e(CreateAttributeSchemaMutationDescriptor.LOCALIZED.name(), false)
				.e(CreateAttributeSchemaMutationDescriptor.NULLABLE.name(), true)
				.e(CreateAttributeSchemaMutationDescriptor.REPRESENTATIVE.name(), true)
				.e(CreateAttributeSchemaMutationDescriptor.TYPE.name(), String.class)
				.e(CreateAttributeSchemaMutationDescriptor.DEFAULT_VALUE.name(), "defaultCode")
				.e(CreateAttributeSchemaMutationDescriptor.INDEXED_DECIMAL_PLACES.name(), 2)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final CreateAttributeSchemaMutation convertedMutation2 = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateAttributeSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateAttributeSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateAttributeSchemaMutationDescriptor.UNIQUE_IN_SCOPES.name(), list()
					.i(map()
						.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
						.e(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION.name())))
				.e(CreateAttributeSchemaMutationDescriptor.FILTERABLE_IN_SCOPES.name(), list()
					.i(Scope.LIVE.name()))
				.e(CreateAttributeSchemaMutationDescriptor.SORTABLE_IN_SCOPES.name(), list()
					.i(Scope.LIVE.name()))
				.e(CreateAttributeSchemaMutationDescriptor.LOCALIZED.name(), "false")
				.e(CreateAttributeSchemaMutationDescriptor.NULLABLE.name(), "true")
				.e(CreateAttributeSchemaMutationDescriptor.REPRESENTATIVE.name(), "true")
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

		final CreateAttributeSchemaMutation convertedMutation1 = this.converter.convertFromInput(
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
			() -> this.converter.convertFromInput(
				map()
					.e(CreateAttributeSchemaMutationDescriptor.TYPE.name(), String.class)
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
	@SneakyThrows
	void shouldSerializeLocalMutationToOutput() {
		final CreateAttributeSchemaMutation inputMutation = new CreateAttributeSchemaMutation(
			"code",
			"desc",
			"depr",
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			},
			new Scope[] { Scope.LIVE },
			new Scope[] { Scope.LIVE },
			false,
			true,
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
					.e(MutationDescriptor.MUTATION_TYPE.name(), CreateAttributeSchemaMutation.class.getSimpleName())
					.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
					.e(CreateAttributeSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
					.e(CreateAttributeSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
					.e(CreateAttributeSchemaMutationDescriptor.UNIQUE_IN_SCOPES.name(), list()
						.i(map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
							.e(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION.name())))
					.e(CreateAttributeSchemaMutationDescriptor.FILTERABLE_IN_SCOPES.name(), array()
						.i(Scope.LIVE.name()))
					.e(CreateAttributeSchemaMutationDescriptor.ACCELERATORS_IN_SCOPES.name(), list())
					.e(CreateAttributeSchemaMutationDescriptor.SORTABLE_IN_SCOPES.name(), array()
						.i(Scope.LIVE.name()))
					.e(CreateAttributeSchemaMutationDescriptor.LOCALIZED.name(), false)
					.e(CreateAttributeSchemaMutationDescriptor.NULLABLE.name(), true)
					.e(CreateAttributeSchemaMutationDescriptor.REPRESENTATIVE.name(), true)
					.e(CreateAttributeSchemaMutationDescriptor.TYPE.name(), String.class.getSimpleName())
					.e(CreateAttributeSchemaMutationDescriptor.DEFAULT_VALUE.name(), "defaultCode")
					.e(CreateAttributeSchemaMutationDescriptor.INDEXED_DECIMAL_PLACES.name(), 2)
					.e(CreateAttributeSchemaMutationDescriptor.CONFLICT_RESOLUTION_OVERRIDE.name(), ConflictResolutionOverride.INHERITED.name())
					.build()
			);
	}

	@Test
	void shouldResolveInputWithFilterCapabilities() {
		final CreateAttributeSchemaMutation expectedMutation = new CreateAttributeSchemaMutation(
			"code",
			null,
			null,
			null,
			new Scope[] { Scope.LIVE },
			new ScopedAttributeFilterAccelerators[] {
				new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
			},
			null,
			false,
			false,
			false,
			String.class,
			null,
			0,
			ConflictResolutionOverride.INHERITED
		);

		final CreateAttributeSchemaMutation convertedFromEnums = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateAttributeSchemaMutationDescriptor.TYPE.name(), String.class)
				.e(CreateAttributeSchemaMutationDescriptor.FILTERABLE_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.e(CreateAttributeSchemaMutationDescriptor.ACCELERATORS_IN_SCOPES.name(), list()
					.i(map()
						.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
						.e(ScopedAttributeFilterAcceleratorsDescriptor.ACCELERATORS.name(), list()
							.i(AttributeFilterAccelerator.SUBSTRING_SEARCH))))
				.build()
		);
		assertEquals(expectedMutation, convertedFromEnums);

		final CreateAttributeSchemaMutation convertedFromStrings = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateAttributeSchemaMutationDescriptor.TYPE.name(), "String")
				.e(CreateAttributeSchemaMutationDescriptor.FILTERABLE_IN_SCOPES.name(), list()
					.i(Scope.LIVE.name()))
				.e(CreateAttributeSchemaMutationDescriptor.ACCELERATORS_IN_SCOPES.name(), list()
					.i(map()
						.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
						.e(ScopedAttributeFilterAcceleratorsDescriptor.ACCELERATORS.name(), list()
							.i(AttributeFilterAccelerator.SUBSTRING_SEARCH.name()))))
				.build()
		);
		assertEquals(expectedMutation, convertedFromStrings);
	}

	@Test
	void shouldResolveInputWithoutFilterCapabilities() {
		// an old client never sends the property at all - the mutation must come out with no capability anywhere
		// rather than refusing the input
		final CreateAttributeSchemaMutation converted = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateAttributeSchemaMutationDescriptor.TYPE.name(), String.class)
				.e(CreateAttributeSchemaMutationDescriptor.FILTERABLE_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.build()
		);
		assertNotNull(converted.getAcceleratorsInScopes());
		assertEquals(0, converted.getAcceleratorsInScopes().length);
	}

	@Test
	void shouldRoundTripFilterCapabilities() {
		final CreateAttributeSchemaMutation inputMutation = new CreateAttributeSchemaMutation(
			"code",
			"desc",
			"depr",
			null,
			new Scope[] { Scope.LIVE },
			new ScopedAttributeFilterAccelerators[] {
				new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
			},
			null,
			false,
			false,
			false,
			String.class,
			null,
			0,
			ConflictResolutionOverride.INHERITED
		);

		final CreateAttributeSchemaMutation roundTripped =
			this.converter.convertFromInput(this.converter.convertToOutput(inputMutation));
		assertEquals(inputMutation, roundTripped);
	}

	@Test
	void shouldRoundTripNonDefaultConflictResolutionOverride() {
		final CreateAttributeSchemaMutation inputMutation = new CreateAttributeSchemaMutation(
			"code",
			"desc",
			"depr",
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			},
			new Scope[] { Scope.LIVE },
			null,
			new Scope[] { Scope.LIVE },
			false,
			true,
			true,
			String.class,
			"defaultCode",
			2,
			ConflictResolutionOverride.ENTITY
		);

		final CreateAttributeSchemaMutation roundTripped =
			this.converter.convertFromInput(this.converter.convertToOutput(inputMutation));
		assertEquals(ConflictResolutionOverride.ENTITY, roundTripped.getConflictResolutionOverride());
		assertEquals(inputMutation, roundTripped);
	}
}
