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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation;

import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.AllowLocaleInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.DisallowCurrencyInEntitySchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.EntitySchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.AllowLocaleInEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.DisallowCurrencyInEntitySchemaMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EntitySchemaMutationAggregateConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class EntitySchemaMutationAggregateConverterTest {

	private EntitySchemaMutationAggregateConverter converter;

	@BeforeEach
	void init() {
		converter = new EntitySchemaMutationAggregateConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final List<EntitySchemaMutation> expectedMutations = List.of(
			new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH),
			new DisallowCurrencyInEntitySchemaMutation(Currency.getInstance("EUR"))
		);

		final List<EntitySchemaMutation> convertedMutations1 = converter.convert(
			map()
				.e(EntitySchemaMutationAggregateDescriptor.ALLOW_LOCALE_IN_ENTITY_SCHEMA_MUTATION.name(), map()
					.e(AllowLocaleInEntitySchemaMutationDescriptor.LOCALES.name(), List.of(Locale.ENGLISH))
					.build())
				.e(EntitySchemaMutationAggregateDescriptor.DISALLOW_CURRENCY_IN_ENTITY_SCHEMA_MUTATION.name(), map()
					.e(DisallowCurrencyInEntitySchemaMutationDescriptor.CURRENCIES.name(), List.of(Currency.getInstance("EUR")))
					.build())
				.build()
		);
		assertEquals(expectedMutations, convertedMutations1);

		final List<EntitySchemaMutation> convertedMutations2 = converter.convert(
			map()
				.e(EntitySchemaMutationAggregateDescriptor.ALLOW_LOCALE_IN_ENTITY_SCHEMA_MUTATION.name(), map()
					.e(AllowLocaleInEntitySchemaMutationDescriptor.LOCALES.name(), List.of("en"))
					.build())
				.e(EntitySchemaMutationAggregateDescriptor.DISALLOW_CURRENCY_IN_ENTITY_SCHEMA_MUTATION.name(), map()
					.e(DisallowCurrencyInEntitySchemaMutationDescriptor.CURRENCIES.name(), List.of("EUR"))
					.build())
				.build()
		);
		assertEquals(expectedMutations, convertedMutations2);
	}
	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final List<EntitySchemaMutation> convertedMutations = converter.convert(Map.of());
		assertEquals(List.of(), convertedMutations);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(null));
	}
}