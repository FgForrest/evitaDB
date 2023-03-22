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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.reference;

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.CreateReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link CreateReferenceSchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class CreateReferenceSchemaMutationConverterTest {

	private CreateReferenceSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		converter = new CreateReferenceSchemaMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final CreateReferenceSchemaMutation expectedMutation = new CreateReferenceSchemaMutation(
			"tags",
			"desc",
			"depr",
			Cardinality.ZERO_OR_MORE,
			"tag",
			true,
			"tagGroup",
			true,
			true,
			false
		);

		final CreateReferenceSchemaMutation convertedMutation1 = converter.convert(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(CreateReferenceSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateReferenceSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateReferenceSchemaMutationDescriptor.CARDINALITY.name(), Cardinality.ZERO_OR_MORE)
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), true)
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE.name(), "tagGroup")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), true)
				.e(CreateReferenceSchemaMutationDescriptor.FILTERABLE.name(), true)
				.e(CreateReferenceSchemaMutationDescriptor.FACETED.name(), false)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final CreateReferenceSchemaMutation convertedMutation2 = converter.convert(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(CreateReferenceSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateReferenceSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateReferenceSchemaMutationDescriptor.CARDINALITY.name(), "ZERO_OR_MORE")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), "true")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE.name(), "tagGroup")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), "true")
				.e(CreateReferenceSchemaMutationDescriptor.FILTERABLE.name(), "true")
				.e(CreateReferenceSchemaMutationDescriptor.FACETED.name(), "false")
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final CreateReferenceSchemaMutation expectedMutation = new CreateReferenceSchemaMutation(
			"tags",
			null,
			null,
			null,
			"tag",
			true,
			null,
			false,
			true,
			false
		);

		final CreateReferenceSchemaMutation convertedMutation1 = converter.convert(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), true)
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), false)
				.e(CreateReferenceSchemaMutationDescriptor.FILTERABLE.name(), true)
				.e(CreateReferenceSchemaMutationDescriptor.FACETED.name(), false)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), true)
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), false)
					.e(CreateReferenceSchemaMutationDescriptor.FILTERABLE.name(), true)
					.e(CreateReferenceSchemaMutationDescriptor.FACETED.name(), false)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), true)
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), false)
					.e(CreateReferenceSchemaMutationDescriptor.FILTERABLE.name(), true)
					.e(CreateReferenceSchemaMutationDescriptor.FACETED.name(), false)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), false)
					.e(CreateReferenceSchemaMutationDescriptor.FILTERABLE.name(), true)
					.e(CreateReferenceSchemaMutationDescriptor.FACETED.name(), false)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), true)
					.e(CreateReferenceSchemaMutationDescriptor.FILTERABLE.name(), true)
					.e(CreateReferenceSchemaMutationDescriptor.FACETED.name(), false)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), true)
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), false)
					.e(CreateReferenceSchemaMutationDescriptor.FACETED.name(), false)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), true)
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), false)
					.e(CreateReferenceSchemaMutationDescriptor.FILTERABLE.name(), true)
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}
}