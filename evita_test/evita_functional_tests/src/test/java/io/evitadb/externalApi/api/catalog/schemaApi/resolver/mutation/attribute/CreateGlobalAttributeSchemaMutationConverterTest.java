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

import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeFilterAcceleratorsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedGlobalAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.CreateGlobalAttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
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
 * Tests for {@link CreateGlobalAttributeSchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class CreateGlobalAttributeSchemaMutationConverterTest {

	private CreateGlobalAttributeSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new CreateGlobalAttributeSchemaMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
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
			null,
			false,
			false,
			false,
			String.class,
			null,
			0,
			ConflictResolutionOverride.INHERITED
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
					.e(MutationDescriptor.MUTATION_TYPE.name(), CreateGlobalAttributeSchemaMutation.class.getSimpleName())
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
					.e(CreateGlobalAttributeSchemaMutationDescriptor.ACCELERATORS_IN_SCOPES.name(), list())
					.e(CreateGlobalAttributeSchemaMutationDescriptor.SORTABLE_IN_SCOPES.name(), array()
						.i(Scope.LIVE.name()))
					.e(CreateGlobalAttributeSchemaMutationDescriptor.LOCALIZED.name(), true)
					.e(CreateGlobalAttributeSchemaMutationDescriptor.NULLABLE.name(), false)
					.e(CreateGlobalAttributeSchemaMutationDescriptor.REPRESENTATIVE.name(), true)
					.e(CreateGlobalAttributeSchemaMutationDescriptor.TYPE.name(), String.class.getSimpleName())
					.e(CreateGlobalAttributeSchemaMutationDescriptor.DEFAULT_VALUE.name(), "defaultCode")
					.e(CreateGlobalAttributeSchemaMutationDescriptor.INDEXED_DECIMAL_PLACES.name(), 2)
					.e(CreateGlobalAttributeSchemaMutationDescriptor.CONFLICT_RESOLUTION_OVERRIDE.name(), ConflictResolutionOverride.INHERITED.name())
					.build()
			);
	}

	@Test
	void shouldResolveInputWithFilterCapabilities() {
		final CreateGlobalAttributeSchemaMutation expectedMutation = new CreateGlobalAttributeSchemaMutation(
			"code",
			null,
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

		final CreateGlobalAttributeSchemaMutation convertedFromEnums = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.TYPE.name(), String.class)
				.e(CreateGlobalAttributeSchemaMutationDescriptor.FILTERABLE_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.e(CreateGlobalAttributeSchemaMutationDescriptor.ACCELERATORS_IN_SCOPES.name(), list()
					.i(map()
						.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
						.e(ScopedAttributeFilterAcceleratorsDescriptor.ACCELERATORS.name(), list()
							.i(AttributeFilterAccelerator.SUBSTRING_SEARCH))))
				.build()
		);
		assertEquals(expectedMutation, convertedFromEnums);

		final CreateGlobalAttributeSchemaMutation convertedFromStrings = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.TYPE.name(), "String")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.FILTERABLE_IN_SCOPES.name(), list()
					.i(Scope.LIVE.name()))
				.e(CreateGlobalAttributeSchemaMutationDescriptor.ACCELERATORS_IN_SCOPES.name(), list()
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
		final CreateGlobalAttributeSchemaMutation converted = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateGlobalAttributeSchemaMutationDescriptor.TYPE.name(), String.class)
				.e(CreateGlobalAttributeSchemaMutationDescriptor.FILTERABLE_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.build()
		);
		assertNotNull(converted.getAcceleratorsInScopes());
		assertEquals(0, converted.getAcceleratorsInScopes().length);
	}

	@Test
	void shouldRoundTripFilterCapabilities() {
		final CreateGlobalAttributeSchemaMutation inputMutation = new CreateGlobalAttributeSchemaMutation(
			"code",
			"desc",
			"depr",
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

		final CreateGlobalAttributeSchemaMutation roundTripped =
			this.converter.convertFromInput(this.converter.convertToOutput(inputMutation));
		assertEquals(inputMutation, roundTripped);
	}

	@Test
	void shouldRoundTripNonDefaultConflictResolutionOverride() {
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
			null,
			new Scope[]{Scope.LIVE},
			true,
			false,
			true,
			String.class,
			"defaultCode",
			2,
			ConflictResolutionOverride.ENTITY
		);

		final CreateGlobalAttributeSchemaMutation roundTripped =
			this.converter.convertFromInput(this.converter.convertToOutput(inputMutation));
		assertEquals(ConflictResolutionOverride.ENTITY, roundTripped.getConflictResolutionOverride());
		assertEquals(inputMutation, roundTripped);
	}
}
