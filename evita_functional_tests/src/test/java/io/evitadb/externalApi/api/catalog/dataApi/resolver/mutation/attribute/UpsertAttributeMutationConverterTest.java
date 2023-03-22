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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.attribute;

import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.UpsertAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link UpsertAttributeMutationConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class UpsertAttributeMutationConverterTest {

	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_QUANTITY = "quantity";

	private UpsertAttributeMutationConverter converter;

	@BeforeEach
	void init() {
		final EntitySchemaContract entitySchema = new InternalEntitySchemaBuilder(
			CatalogSchema._internalBuild(TestConstants.TEST_CATALOG, Map.of(), entityType -> null),
			EntitySchema._internalBuild(Entities.PRODUCT)
		)
			.withAttribute(ATTRIBUTE_CODE, String.class)
			.withPrice()
			.toInstance();
		converter =  new UpsertAttributeMutationConverter(entitySchema, new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final UpsertAttributeMutation expectedMutation = new UpsertAttributeMutation(ATTRIBUTE_CODE, Locale.ENGLISH, "phone");

		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(UpsertAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE)
				.e(UpsertAttributeMutationDescriptor.LOCALE.name(), Locale.ENGLISH)
				.e(UpsertAttributeMutationDescriptor.VALUE.name(), "phone")
				.build()
		);
		assertEquals(expectedMutation, localMutation);

		final LocalMutation<?, ?> localMutation2 = converter.convert(
			map()
				.e(UpsertAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE)
				.e(UpsertAttributeMutationDescriptor.LOCALE.name(), "en")
				.e(UpsertAttributeMutationDescriptor.VALUE.name(), "phone")
				.build()
		);
		assertEquals(expectedMutation, localMutation2);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(UpsertAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE)
				.e(UpsertAttributeMutationDescriptor.VALUE.name(), "phone")
				.build()
		);
		assertEquals(
			new UpsertAttributeMutation(ATTRIBUTE_CODE, "phone"),
			localMutation
		);
	}

	@Test
	void shouldResolveMutationWithNewAttribute() {
		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(UpsertAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_QUANTITY)
				.e(UpsertAttributeMutationDescriptor.VALUE.name(), "1.2")
				.e(UpsertAttributeMutationDescriptor.VALUE_TYPE.name(), BigDecimal.class)
				.build()
		);
		assertEquals(
			new UpsertAttributeMutation(ATTRIBUTE_QUANTITY, BigDecimal.valueOf(1.2)),
			localMutation
		);
	}

	@Test
	void shouldResolveMutationWithNewRangeAttribute() {
		final List<Long> range = new ArrayList<>(2);
		range.add(null);
		range.add(20L);

		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(UpsertAttributeMutationDescriptor.NAME.name(), "longNumberRangeAttribute")
				.e(UpsertAttributeMutationDescriptor.VALUE.name(), range)
				.e(UpsertAttributeMutationDescriptor.VALUE_TYPE.name(), LongNumberRange.class)
				.build()
		);
		assertEquals(
			new UpsertAttributeMutation("longNumberRangeAttribute", LongNumberRange.to(20L)),
			localMutation
		);
	}

	@Test
	void shouldResolveMutationWithNewArrayOfRangesAttribute() {
		final List<Long> toRange = new ArrayList<>(2);
		toRange.add(null);
		toRange.add(20L);
		final List<Long> fromRange = new ArrayList<>(2);
		fromRange.add(10L);
		fromRange.add(null);

		final UpsertAttributeMutation localMutation = (UpsertAttributeMutation) converter.convert(
			map()
				.e(UpsertAttributeMutationDescriptor.NAME.name(), "arrayOfRangesAttribute")
				.e(
					UpsertAttributeMutationDescriptor.VALUE.name(),
					List.of(
						toRange,
						fromRange,
						List.of(10, 20)
					)
				)
				.e(UpsertAttributeMutationDescriptor.VALUE_TYPE.name(), LongNumberRange[].class)
				.build()
		);
		assertEquals("arrayOfRangesAttribute", localMutation.getAttributeKey().getAttributeName());
		assertArrayEquals(
			new LongNumberRange[] {
				LongNumberRange.to(20L),
				LongNumberRange.from(10L),
				LongNumberRange.between(10L, 20L)
			},
			(LongNumberRange[]) localMutation.getAttributeValue()
		);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(UpsertAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(UpsertAttributeMutationDescriptor.VALUE.name(), "phone")
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}

	@Test
	void shouldNotResolveInputWhenIncorrectValueType() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(UpsertAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE)
					.e(UpsertAttributeMutationDescriptor.VALUE.name(), "phone")
					.e(UpsertAttributeMutationDescriptor.VALUE_TYPE.name(), Integer.class)
					.build()
			)
		);
	}

	@Test
	void shouldResolveMutationWithNewAttributeWhenValueTypeIsMissing() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(UpsertAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_QUANTITY)
					.e(UpsertAttributeMutationDescriptor.VALUE.name(), "1.2")
					.build()
			)
		);
	}
}