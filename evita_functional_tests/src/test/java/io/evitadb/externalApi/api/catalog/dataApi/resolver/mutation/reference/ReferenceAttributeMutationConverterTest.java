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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.reference;

import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ApplyDeltaAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ReferenceAttributeMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.RemoveAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.UpsertAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.ReferenceAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ReferenceAttributeMutationConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class ReferenceAttributeMutationConverterTest {

	private static final String REFERENCE_TAGS = "tags";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_ALT_CODE = "altCode";
	private static final String ATTRIBUTE_QUANTITY = "quantity";
	private ReferenceAttributeMutationConverter converter;

	@BeforeEach
	void init() {
		final EntitySchemaContract entitySchema = new InternalEntitySchemaBuilder(
			CatalogSchema._internalBuild(TestConstants.TEST_CATALOG, Map.of(), entityType -> null),
			EntitySchema._internalBuild(Entities.PRODUCT)
		)
			.withReferenceTo(
				REFERENCE_TAGS,
				"tag",
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.withAttribute(ATTRIBUTE_CODE, String.class)
					.withAttribute(ATTRIBUTE_QUANTITY, Integer.class)
			)
			.toInstance();
		converter =  new ReferenceAttributeMutationConverter(entitySchema, new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutationWithRemoveAttributeMutation() {
		final ReferenceAttributeMutation expectedMutation = new ReferenceAttributeMutation(
			new ReferenceKey(REFERENCE_TAGS, 1),
			new RemoveAttributeMutation(ATTRIBUTE_CODE)
		);

		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(ReferenceAttributeMutationDescriptor.NAME.name(), REFERENCE_TAGS)
				.e(ReferenceAttributeMutationDescriptor.PRIMARY_KEY.name(), 1)
				.e(ReferenceAttributeMutationDescriptor.ATTRIBUTE_MUTATION.name(), map()
					.e(ReferenceAttributeMutationAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.name(), map()
						.e(RemoveAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE)
						.build())
					.build())
				.build()
		);
		assertEquals(expectedMutation, localMutation);
	}

	@Test
	void shouldResolveInputToLocalMutationWithUpsertAttributeMutation() {
		final ReferenceAttributeMutation expectedMutation = new ReferenceAttributeMutation(
			new ReferenceKey(REFERENCE_TAGS, 1),
			new UpsertAttributeMutation(ATTRIBUTE_ALT_CODE, "phone")
		);

		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(ReferenceAttributeMutationDescriptor.NAME.name(), REFERENCE_TAGS)
				.e(ReferenceAttributeMutationDescriptor.PRIMARY_KEY.name(), 1)
				.e(ReferenceAttributeMutationDescriptor.ATTRIBUTE_MUTATION.name(), map()
					.e(ReferenceAttributeMutationAggregateDescriptor.UPSERT_ATTRIBUTE_MUTATION.name(), map()
						.e(UpsertAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_ALT_CODE)
						.e(UpsertAttributeMutationDescriptor.VALUE.name(), "phone")
						.e(UpsertAttributeMutationDescriptor.VALUE_TYPE.name(), String.class)
						.build())
					.build())
				.build()
		);
		assertEquals(expectedMutation, localMutation);
	}

	@Test
	void shouldResolveInputToLocalMutationWithApplyDeltaAttributeMutation() {
		final ReferenceAttributeMutation expectedMutation = new ReferenceAttributeMutation(
			new ReferenceKey(REFERENCE_TAGS, 1),
			new ApplyDeltaAttributeMutation<>(ATTRIBUTE_QUANTITY, 10)
		);

		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(ReferenceAttributeMutationDescriptor.NAME.name(), REFERENCE_TAGS)
				.e(ReferenceAttributeMutationDescriptor.PRIMARY_KEY.name(), 1)
				.e(ReferenceAttributeMutationDescriptor.ATTRIBUTE_MUTATION.name(), map()
					.e(ReferenceAttributeMutationAggregateDescriptor.APPLY_DELTA_ATTRIBUTE_MUTATION.name(), map()
						.e(ApplyDeltaAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_QUANTITY)
						.e(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), 10)
						.build())
					.build())
				.build()
		);
		assertEquals(expectedMutation, localMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(ReferenceAttributeMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(ReferenceAttributeMutationDescriptor.ATTRIBUTE_MUTATION.name(), map()
						.e(ReferenceAttributeMutationAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.name(), map()
							.e(RemoveAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE)
							.build())
						.build())
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(ReferenceAttributeMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(ReferenceAttributeMutationDescriptor.ATTRIBUTE_MUTATION.name(), map()
						.e(ReferenceAttributeMutationAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.name(), map()
							.e(RemoveAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE)
							.build())
						.build())
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(ReferenceAttributeMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(ReferenceAttributeMutationDescriptor.PRIMARY_KEY.name(), 1)
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}

	@Test
	void shouldNotResolveInputWhenMultipleAttributeMutationsArePresent() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(ReferenceAttributeMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(ReferenceAttributeMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(ReferenceAttributeMutationDescriptor.ATTRIBUTE_MUTATION.name(), map()
						.e(ReferenceAttributeMutationAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.name(), map()
							.e(RemoveAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE)
							.build())
						.e(ReferenceAttributeMutationAggregateDescriptor.UPSERT_ATTRIBUTE_MUTATION.name(), map()
							.e(UpsertAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_ALT_CODE)
							.e(UpsertAttributeMutationDescriptor.VALUE.name(), "phone")
							.e(UpsertAttributeMutationDescriptor.VALUE_TYPE.name(), String.class)
							.build())
						.build())
					.build()
			)
		);
	}
}