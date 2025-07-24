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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.associatedData;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.UpsertAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link UpsertAssociatedDataMutationConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class UpsertAssociatedDataMutationConverterTest {

	private static final String ASSOCIATED_DATA_LABELS = "labels";
	private static final String ASSOCIATED_DATA_CODE = "code";
	private static final String ASSOCIATED_DATA_QUANTITY = "quantity";
	private UpsertAssociatedDataMutationConverter converter;

	@BeforeEach
	void init() {
		final EntitySchemaContract entitySchema = new InternalEntitySchemaBuilder(
			CatalogSchema._internalBuild(TestConstants.TEST_CATALOG, Map.of(), EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE),
			EntitySchema._internalBuild(Entities.PRODUCT)
		)
			.withAssociatedData(ASSOCIATED_DATA_LABELS, Dummy.class)
			.withAssociatedData(ASSOCIATED_DATA_CODE, String.class)
			.toInstance();
		converter =  new UpsertAssociatedDataMutationConverter(new ObjectMapper(), entitySchema, new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final UpsertAssociatedDataMutation expectedMutation = new UpsertAssociatedDataMutation(ASSOCIATED_DATA_CODE, Locale.ENGLISH, "phone");

		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(UpsertAssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_CODE)
				.e(UpsertAssociatedDataMutationDescriptor.LOCALE.name(), Locale.ENGLISH)
				.e(UpsertAssociatedDataMutationDescriptor.VALUE.name(), "phone")
				.build()
		);
		assertEquals(expectedMutation, localMutation);

		final LocalMutation<?, ?> localMutation2 = converter.convert(
			map()
				.e(UpsertAssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_CODE)
				.e(UpsertAssociatedDataMutationDescriptor.LOCALE.name(), "en")
				.e(UpsertAssociatedDataMutationDescriptor.VALUE.name(), "phone")
				.build()
		);
		assertEquals(expectedMutation, localMutation2);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(UpsertAssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_CODE)
				.e(UpsertAssociatedDataMutationDescriptor.VALUE.name(), "some")
				.build()
		);
		assertEquals(
			new UpsertAssociatedDataMutation(ASSOCIATED_DATA_CODE, "some"),
			localMutation
		);
	}

	@Test
	void shouldResolveMutationWithNewAssociatedData() {
		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(UpsertAssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_QUANTITY)
				.e(UpsertAssociatedDataMutationDescriptor.VALUE.name(), "1.2")
				.e(UpsertAssociatedDataMutationDescriptor.VALUE_TYPE.name(), BigDecimal.class)
				.build()
		);

		assertEquals(
			new UpsertAssociatedDataMutation(ASSOCIATED_DATA_QUANTITY, BigDecimal.valueOf(1.2)),
			localMutation
		);
	}

	@Test
	void shouldResolveMutationWithComplexDataObject() {
		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(UpsertAssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS)
				.e(UpsertAssociatedDataMutationDescriptor.VALUE.name(), map()
					.e("s", "string")
					.build())
				.build()
		);

		assertEquals(
			new UpsertAssociatedDataMutation(
				ASSOCIATED_DATA_LABELS,
				new ComplexDataObject(new DataItemMap(Map.of("s", new DataItemValue("string"))))
			),
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
				.e(UpsertAssociatedDataMutationDescriptor.NAME.name(), "longNumberRangeAttribute")
				.e(UpsertAssociatedDataMutationDescriptor.VALUE.name(), range)
				.e(UpsertAssociatedDataMutationDescriptor.VALUE_TYPE.name(), LongNumberRange.class)
				.build()
		);
		assertEquals(
			new UpsertAssociatedDataMutation("longNumberRangeAttribute", LongNumberRange.to(20L)),
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

		final UpsertAssociatedDataMutation localMutation = (UpsertAssociatedDataMutation) converter.convert(
			map()
				.e(UpsertAssociatedDataMutationDescriptor.NAME.name(), "arrayOfRangesAttribute")
				.e(
					UpsertAssociatedDataMutationDescriptor.VALUE.name(),
					List.of(
						toRange,
						fromRange,
						List.of(10, 20)
					)
				)
				.e(UpsertAssociatedDataMutationDescriptor.VALUE_TYPE.name(), LongNumberRange[].class)
				.build()
		);
		assertEquals("arrayOfRangesAttribute", localMutation.getAssociatedDataKey().associatedDataName());
		assertArrayEquals(
			new LongNumberRange[] {
				LongNumberRange.to(20L),
				LongNumberRange.from(10L),
				LongNumberRange.between(10L, 20L)
			},
			(LongNumberRange[]) localMutation.getAssociatedDataValue()
		);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(UpsertAssociatedDataMutationDescriptor.NAME.name(), "labels")
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(UpsertAssociatedDataMutationDescriptor.VALUE.name(), "some")
					.build()
			)
		);
	}

	@Test
	void shouldNotResolveInputWhenIncorrectValueType() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(UpsertAssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_CODE)
					.e(UpsertAssociatedDataMutationDescriptor.VALUE.name(), "phone")
					.e(UpsertAssociatedDataMutationDescriptor.VALUE_TYPE.name(), Integer.class)
					.build()
			)
		);
	}

	@Test
	void shouldResolveMutationWithNewAssociatedDataWhenValueTypeIsMissing() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(UpsertAssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_QUANTITY)
					.e(UpsertAssociatedDataMutationDescriptor.VALUE.name(), "1.2")
					.build()
			)
		);
	}

	@Test
	void shouldNotConvertIfInputMutationIsEmpty() {
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}

	@Data
	@AllArgsConstructor
	private static class Dummy implements Serializable {

		@Serial private static final long serialVersionUID = 4926339123678740470L;

		private String s;
	}
}
