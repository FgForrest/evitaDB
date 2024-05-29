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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound;

import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.SortableAttributeCompoundSchemaMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ModifySortableAttributeCompoundSchemaDescriptionMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class ModifySortableAttributeCompoundSchemaDescriptionMutationConverterTest {

	private ModifySortableAttributeCompoundSchemaDescriptionMutationConverter converter;

	@BeforeEach
	void init() {
		converter = new ModifySortableAttributeCompoundSchemaDescriptionMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final ModifySortableAttributeCompoundSchemaDescriptionMutation expectedMutation = new ModifySortableAttributeCompoundSchemaDescriptionMutation(
			"code",
			"desc"
		);
		final ModifySortableAttributeCompoundSchemaDescriptionMutation convertedMutation = converter.convert(
			map()
				.e(SortableAttributeCompoundSchemaMutationDescriptor.NAME.name(), "code")
				.e(ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor.DESCRIPTION.name(), "desc")
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}
	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final ModifySortableAttributeCompoundSchemaDescriptionMutation expectedMutation = new ModifySortableAttributeCompoundSchemaDescriptionMutation(
			"code",
			null
		);
		final ModifySortableAttributeCompoundSchemaDescriptionMutation convertedMutation = converter.convert(
			map()
				.e(SortableAttributeCompoundSchemaMutationDescriptor.NAME.name(), "code")
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}
}
