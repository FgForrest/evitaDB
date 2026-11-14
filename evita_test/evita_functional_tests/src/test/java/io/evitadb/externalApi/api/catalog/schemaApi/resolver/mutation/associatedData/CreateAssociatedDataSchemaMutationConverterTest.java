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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.AssociatedDataSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.CreateAssociatedDataSchemaMutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nullable;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static io.evitadb.utils.ListBuilder.array;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;

/**
 * Tests for {@link CreateAssociatedDataSchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
class CreateAssociatedDataSchemaMutationConverterTest {

	private CreateAssociatedDataSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new CreateAssociatedDataSchemaMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final CreateAssociatedDataSchemaMutation expectedMutation = new CreateAssociatedDataSchemaMutation(
			"labels",
			"desc",
			"depr",
			String.class,
			true,
			true
		);

		final CreateAssociatedDataSchemaMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(AssociatedDataSchemaMutationDescriptor.NAME.name(), "labels")
				.e(CreateAssociatedDataSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateAssociatedDataSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateAssociatedDataSchemaMutationDescriptor.TYPE.name(), String.class)
				.e(CreateAssociatedDataSchemaMutationDescriptor.LOCALIZED.name(), true)
				.e(CreateAssociatedDataSchemaMutationDescriptor.NULLABLE.name(), true)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final CreateAssociatedDataSchemaMutation convertedMutation2 = this.converter.convertFromInput(
			map()
				.e(AssociatedDataSchemaMutationDescriptor.NAME.name(), "labels")
				.e(CreateAssociatedDataSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateAssociatedDataSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateAssociatedDataSchemaMutationDescriptor.TYPE.name(), "String")
				.e(CreateAssociatedDataSchemaMutationDescriptor.LOCALIZED.name(), "true")
				.e(CreateAssociatedDataSchemaMutationDescriptor.NULLABLE.name(), "true")
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}
	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final CreateAssociatedDataSchemaMutation expectedMutation = new CreateAssociatedDataSchemaMutation(
			"labels",
			null,
			null,
			String.class,
			false,
			false
		);

		final CreateAssociatedDataSchemaMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(AssociatedDataSchemaMutationDescriptor.NAME.name(), "labels")
				.e(CreateAssociatedDataSchemaMutationDescriptor.TYPE.name(), String.class)
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
					.e(CreateAssociatedDataSchemaMutationDescriptor.TYPE.name(), String.class)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(AssociatedDataSchemaMutationDescriptor.NAME.name(), "labels")
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final CreateAssociatedDataSchemaMutation inputMutation = new CreateAssociatedDataSchemaMutation(
			"labels",
			"desc",
			"depr",
			String.class,
			true,
			true
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(CreateAssociatedDataSchemaMutationDescriptor.MUTATION_TYPE.name(), CreateAssociatedDataSchemaMutation.class.getSimpleName())
					.e(AssociatedDataSchemaMutationDescriptor.NAME.name(), "labels")
					.e(CreateAssociatedDataSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
					.e(CreateAssociatedDataSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
					.e(CreateAssociatedDataSchemaMutationDescriptor.TYPE.name(), String.class.getSimpleName())
					.e(CreateAssociatedDataSchemaMutationDescriptor.LOCALIZED.name(), true)
					.e(CreateAssociatedDataSchemaMutationDescriptor.NULLABLE.name(), true)
					// no descriptor yet - the property is emitted by reflection over the mutation's creator (F1c)
					.e("searchableInScopes", array())
					.e(CreateAssociatedDataSchemaMutationDescriptor.CONFLICT_RESOLUTION_OVERRIDE.name(), ConflictResolutionOverride.INHERITED.name())
					.build()
			);
	}

	@Test
	void shouldRoundTripNonDefaultConflictResolutionOverride() {
		final CreateAssociatedDataSchemaMutation inputMutation = new CreateAssociatedDataSchemaMutation(
			"labels",
			"desc",
			"depr",
			String.class,
			true,
			true,
			ConflictResolutionOverride.ENTITY
		);

		final CreateAssociatedDataSchemaMutation roundTripped =
			this.converter.convertFromInput(this.converter.convertToOutput(inputMutation));
		assertEquals(ConflictResolutionOverride.ENTITY, roundTripped.getConflictResolutionOverride());
		assertEquals(inputMutation, roundTripped);
	}

	/**
	 * Emits a present finite ranking weight as a decimal value, a non-finite one verbatim, and omits an unspecified
	 * weight.
	 */
	@ParameterizedTest
	@NullSource
	@ValueSource(floats = { 0.0f, 2.5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY })
	void shouldSerializeSearchWeightToOutput(@Nullable Float weight) {
		final CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
			"labels", null, null, String.class, false, true, new Scope[] { Scope.LIVE }, null,
			ConflictResolutionOverride.INHERITED, null, null, weight
		);
		@SuppressWarnings("unchecked")
		final Map<String, Object> output = (Map<String, Object>) this.converter.convertToOutput(mutation);
		if (weight == null) {
			assertThat(output).doesNotContainKey("searchWeight");
		} else {
			// a finite weight goes out as a BigDecimal rendered as its plain-decimal string, a non-finite one as is
			assertThat(output).containsEntry("searchWeight", Float.toString(weight));
		}
	}
}
