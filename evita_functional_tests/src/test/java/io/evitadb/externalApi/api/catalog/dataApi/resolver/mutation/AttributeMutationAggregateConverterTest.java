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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation;

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ApplyDeltaAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.AttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ReferenceAttributeMutationAggregateDescriptor;
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

import static io.evitadb.utils.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link AttributeMutationAggregateConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class AttributeMutationAggregateConverterTest {

	private static final String ATTRIBUTE_QUANTITY = "quantity";

	private EntitySchemaContract entitySchema;
	private Map<String, Object> mutationConverterContext;
	private AttributeMutationAggregateConverter converter;

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
		this.converter =  new AttributeMutationAggregateConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final List<AttributeMutation> expectedMutations = List.of(
			new ApplyDeltaAttributeMutation<>(ATTRIBUTE_QUANTITY, Locale.ENGLISH, 10, IntegerNumberRange.between(0, 20)),
			new RemoveAttributeMutation(ATTRIBUTE_QUANTITY, Locale.ENGLISH)
		);

		final List<AttributeMutation> convertedMutations = this.converter.convertFromInput(
			map()
				.e(ReferenceAttributeMutationAggregateDescriptor.APPLY_DELTA_ATTRIBUTE_MUTATION.name(), map()
					.e(AttributeMutationDescriptor.NAME.name(), ATTRIBUTE_QUANTITY)
					.e(AttributeMutationDescriptor.LOCALE.name(), Locale.ENGLISH)
					.e(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), 10)
					.e(ApplyDeltaAttributeMutationDescriptor.REQUIRED_RANGE_AFTER_APPLICATION.name(), List.of(0, 20)))
				.e(ReferenceAttributeMutationAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.name(), map()
					.e(AttributeMutationDescriptor.NAME.name(), ATTRIBUTE_QUANTITY)
					.e(AttributeMutationDescriptor.LOCALE.name(), Locale.ENGLISH))
				.build(),
			this.mutationConverterContext
		);

		assertEquals(expectedMutations, convertedMutations);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final List<AttributeMutation> convertedMutations = this.converter.convertFromInput(Map.of());
		assertEquals(List.of(), convertedMutations);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}
}
