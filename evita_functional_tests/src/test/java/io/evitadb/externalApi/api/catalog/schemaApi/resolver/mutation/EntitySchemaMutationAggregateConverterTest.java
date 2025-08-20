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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation;

import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
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

import static io.evitadb.utils.ListBuilder.array;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
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
		this.converter = new EntitySchemaMutationAggregateConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final List<EntitySchemaMutation> expectedMutations = List.of(
			new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH),
			new DisallowCurrencyInEntitySchemaMutation(Currency.getInstance("EUR"))
		);

		final List<LocalEntitySchemaMutation> convertedMutations1 = this.converter.convertFromInput(
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

		final List<LocalEntitySchemaMutation> convertedMutations2 = this.converter.convertFromInput(
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
		final List<LocalEntitySchemaMutation> convertedMutations = this.converter.convertFromInput(Map.of());
		assertEquals(List.of(), convertedMutations);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final List<LocalEntitySchemaMutation> inputMutation = List.of(
			new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH),
			new DisallowCurrencyInEntitySchemaMutation(Currency.getInstance("EUR"))
		);

		//noinspection unchecked
		final List<Map<String, Object>> serializedMutation = (List<Map<String, Object>>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				list()
					.i(map()
						.e(EntitySchemaMutationAggregateDescriptor.ALLOW_LOCALE_IN_ENTITY_SCHEMA_MUTATION.name(), map()
							.e(AllowLocaleInEntitySchemaMutationDescriptor.LOCALES.name(), array()
								.i(Locale.ENGLISH.toLanguageTag()))))
					.i(map()
						.e(EntitySchemaMutationAggregateDescriptor.DISALLOW_CURRENCY_IN_ENTITY_SCHEMA_MUTATION.name(), map()
							.e(DisallowCurrencyInEntitySchemaMutationDescriptor.CURRENCIES.name(), list()
								.i("EUR"))))
					.build()
			);
	}
}
