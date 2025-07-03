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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.reference;

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaCardinalityMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ModifyReferenceSchemaCardinalityMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ModifyReferenceSchemaCardinalityMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class ModifyReferenceSchemaCardinalityMutationConverterTest {

	private ModifyReferenceSchemaCardinalityMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new ModifyReferenceSchemaCardinalityMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final ModifyReferenceSchemaCardinalityMutation expectedMutation = new ModifyReferenceSchemaCardinalityMutation(
			"tags",
			Cardinality.ONE_OR_MORE
		);

		final ModifyReferenceSchemaCardinalityMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(ModifyReferenceSchemaCardinalityMutationDescriptor.CARDINALITY.name(), Cardinality.ONE_OR_MORE)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final ModifyReferenceSchemaCardinalityMutation convertedMutation2 = this.converter.convertFromInput(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(ModifyReferenceSchemaCardinalityMutationDescriptor.CARDINALITY.name(), "ONE_OR_MORE")
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ModifyReferenceSchemaCardinalityMutationDescriptor.CARDINALITY.name(), Cardinality.ONE_OR_MORE)
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final ModifyReferenceSchemaCardinalityMutation inputMutation = new ModifyReferenceSchemaCardinalityMutation(
			"tags",
			Cardinality.ONE_OR_MORE
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(ModifyReferenceSchemaCardinalityMutationDescriptor.CARDINALITY.name(), Cardinality.ONE_OR_MORE.name())
					.build()
			);
	}
}
