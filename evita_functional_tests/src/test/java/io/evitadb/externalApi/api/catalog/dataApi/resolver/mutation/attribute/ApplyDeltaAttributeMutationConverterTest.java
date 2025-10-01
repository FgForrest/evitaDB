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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.attribute;

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ApplyDeltaAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.AttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.model.mutation.MutationConverterContext;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.utils.ListBuilder.array;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ApplyDeltaAttributeMutationConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class ApplyDeltaAttributeMutationConverterTest {

	private static final String ATTRIBUTE_QUANTITY = "quantity";

	private EntitySchemaContract entitySchema;
	private Map<String, Object> mutationConverterContext;
	private ApplyDeltaAttributeMutationConverter converter;

	@BeforeEach
	void init() {
		this.entitySchema = new InternalEntitySchemaBuilder(
			CatalogSchema._internalBuild(TestConstants.TEST_CATALOG, Map.of(), EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE),
			EntitySchema._internalBuild(Entities.PRODUCT)
		)
			.withAttribute(ATTRIBUTE_QUANTITY, Integer.class)
			.withPrice()
			.toInstance();
		this.mutationConverterContext = Map.of(
			MutationConverterContext.ENTITY_SCHEMA_KEY, this.entitySchema,
			MutationConverterContext.ATTRIBUTE_SCHEMA_PROVIDER_KEY, this.entitySchema
		);
		this.converter =  new ApplyDeltaAttributeMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final ApplyDeltaAttributeMutation<?> expectedMutation = new ApplyDeltaAttributeMutation<>(ATTRIBUTE_QUANTITY, Locale.ENGLISH, 10, IntegerNumberRange.between(0, 20));

		final LocalMutation<?, ?> localMutation = this.converter.convertFromInput(
			map()
				.e(AttributeMutationDescriptor.NAME.name(), ATTRIBUTE_QUANTITY)
				.e(AttributeMutationDescriptor.LOCALE.name(), Locale.ENGLISH)
				.e(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), 10)
				.e(ApplyDeltaAttributeMutationDescriptor.REQUIRED_RANGE_AFTER_APPLICATION.name(), List.of(0, 20))
				.build(),
			this.mutationConverterContext
		);
		assertEquals(expectedMutation, localMutation);

		final LocalMutation<?, ?> localMutation2 = this.converter.convertFromInput(
			map()
				.e(AttributeMutationDescriptor.NAME.name(), ATTRIBUTE_QUANTITY)
				.e(AttributeMutationDescriptor.LOCALE.name(), "en")
				.e(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), "10")
				.e(ApplyDeltaAttributeMutationDescriptor.REQUIRED_RANGE_AFTER_APPLICATION.name(), List.of("0", "20"))
				.build(),
			this.mutationConverterContext
		);
		assertEquals(expectedMutation, localMutation2);

	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final ApplyDeltaAttributeMutation<?> expectedMutation = new ApplyDeltaAttributeMutation<>(ATTRIBUTE_QUANTITY, 10);

		final LocalMutation<?, ?> localMutation = this.converter.convertFromInput(
			map()
				.e(AttributeMutationDescriptor.NAME.name(), ATTRIBUTE_QUANTITY)
				.e(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), 10)
				.build(),
			this.mutationConverterContext
		);
		assertEquals(expectedMutation, localMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(AttributeMutationDescriptor.NAME.name(), ATTRIBUTE_QUANTITY)
					.build(),
				this.mutationConverterContext
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), 10)
					.build(),
				this.mutationConverterContext
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final ApplyDeltaAttributeMutation<?> inputMutation = new ApplyDeltaAttributeMutation<>(ATTRIBUTE_QUANTITY, Locale.ENGLISH, 10, IntegerNumberRange.between(0, 20));


		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(ApplyDeltaAttributeMutationDescriptor.MUTATION_TYPE.name(), ApplyDeltaAttributeMutation.class.getSimpleName())
					.e(AttributeMutationDescriptor.NAME.name(), ATTRIBUTE_QUANTITY)
					.e(AttributeMutationDescriptor.LOCALE.name(), Locale.ENGLISH.toLanguageTag())
					.e(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), 10)
					.e(ApplyDeltaAttributeMutationDescriptor.REQUIRED_RANGE_AFTER_APPLICATION.name(), array()
						.i(0).i(20))
					.build()
			);

	}
}
