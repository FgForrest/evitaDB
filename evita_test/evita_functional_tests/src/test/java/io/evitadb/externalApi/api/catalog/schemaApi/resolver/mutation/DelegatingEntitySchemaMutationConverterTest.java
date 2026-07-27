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

import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaConflictResolutionOverrideMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.AllowLocaleInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.DisallowCurrencyInEntitySchemaMutation;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.SetAttributeSchemaConflictResolutionOverrideMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.AllowLocaleInEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.DisallowCurrencyInEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static io.evitadb.utils.ListBuilder.array;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Tests for {@link DelegatingEntitySchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
class DelegatingEntitySchemaMutationConverterTest {

	private DelegatingEntitySchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new DelegatingEntitySchemaMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final List<EntitySchemaMutation> inputMutation = List.of(
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
						.e(AllowLocaleInEntitySchemaMutationDescriptor.MUTATION_TYPE.name(), AllowLocaleInEntitySchemaMutation.class.getSimpleName())
						.e(AllowLocaleInEntitySchemaMutationDescriptor.LOCALES.name(), array()
							.i(Locale.ENGLISH.toLanguageTag())))
					.i(map()
						.e(DisallowCurrencyInEntitySchemaMutationDescriptor.MUTATION_TYPE.name(), DisallowCurrencyInEntitySchemaMutation.class.getSimpleName())
						.e(DisallowCurrencyInEntitySchemaMutationDescriptor.CURRENCIES.name(), list()
							.i("EUR")))
					.build()
			);
	}

	@Tag(TRANSACTION)
	@Test
	void shouldSerializeConflictResolutionOverrideMutationThroughDelegate() {
		final List<EntitySchemaMutation> inputMutation = List.of(
			new SetAttributeSchemaConflictResolutionOverrideMutation("code", ConflictResolutionOverride.GRANULAR)
		);

		//noinspection unchecked
		final List<Map<String, Object>> serializedMutation = (List<Map<String, Object>>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				list()
					.i(map()
						.e(MutationDescriptor.MUTATION_TYPE.name(), SetAttributeSchemaConflictResolutionOverrideMutation.class.getSimpleName())
						.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
						.e(SetAttributeSchemaConflictResolutionOverrideMutationDescriptor.CONFLICT_RESOLUTION_OVERRIDE.name(), ConflictResolutionOverride.GRANULAR.name()))
					.build()
			);
	}
}
