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

package io.evitadb.externalApi.grpc.requestResponse.data;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.externalApi.grpc.generated.GrpcBinaryEntity;
import io.evitadb.externalApi.grpc.generated.GrpcPrice;
import io.evitadb.externalApi.grpc.generated.GrpcSealedEntity;
import io.evitadb.externalApi.grpc.testUtils.GrpcAssertions;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * This test verifies functionalities of methods in {@link EntityConverter} class.
 *
 * @author Tomáš Pozler, 2022
 */
class EntityConverterTest {

	@Test
	void buildSealedEntity() {
		final SealedEntity entity = new InitialEntityBuilder(createEntitySchema(), 1)
			.setReference("test2", 1)
			.setAttribute("test1", Locale.ENGLISH, LocalDateTime.now())
			.setAssociatedData("test2", Locale.ENGLISH, new String[]{"test1", "test2"})
			.setPrice(1, "test", Currency.getInstance("CZK"), BigDecimal.ONE, BigDecimal.TEN, BigDecimal.valueOf(1.1), true)
			.toInstance();

		final GrpcSealedEntity grpcEntity = EntityConverter.toGrpcSealedEntity(entity);

		GrpcAssertions.assertEntity(entity, grpcEntity);
	}

	@Test
	void buildBinaryEntity() {
		final BinaryEntity binaryEntity = new BinaryEntity(
			createEntitySchema(), 1,
			new byte[]{1, 2, 3},
			new byte[][]{new byte[]{1, 2, 3}, new byte[]{4, 5, 6}},
			new byte[][]{new byte[]{1, 2, 3}, new byte[]{4, 5, 6}},
			new byte[]{1, 2, 3},
			new byte[]{1, 2, 3},
			new BinaryEntity[0]
		);
		final GrpcBinaryEntity grpcBinaryEntity = EntityConverter.toGrpcBinaryEntity(binaryEntity);

		GrpcAssertions.assertBinaryEntity(binaryEntity, grpcBinaryEntity);
	}

	@Test
	void buildGrpcPrice() {
		final Price price = new Price(
			1,
			new Price.PriceKey(1, "test", Currency.getInstance("CZK")),
			5,
			BigDecimal.ONE,
			BigDecimal.TEN,
			new BigDecimal("1.1"),
			DateTimeRange.since(OffsetDateTime.now()),
			true
		);

		final GrpcPrice grpcPrice = EntityConverter.toGrpcPrice(price);

		GrpcAssertions.assertPrice(price, grpcPrice);
	}

	@Nonnull
	private EntitySchema createEntitySchema() {
		return EntitySchema._internalBuild(
			1,
			Entities.PRODUCT,
			"Lorem ipsum dolor sit amet.",
			"Alert! Deprecated!",
			true,
			false,
			true,
			2,
			Set.of(Locale.ENGLISH, Locale.GERMAN),
			Set.of(Currency.getInstance("EUR"), Currency.getInstance("USD")),
			Map.of(
				"test1", AttributeSchema._internalBuild("test1", LocalDateTime.class, true),
				"test2", AttributeSchema._internalBuild("test2", Boolean[].class, true)
			),
			Map.of(
				"test1", AssociatedDataSchema._internalBuild("test1", "Lorem ipsum", "Alert", Integer.class, false, true),
				"test2", AssociatedDataSchema._internalBuild("test2", "Lorem ipsum", "Alert", String[].class, true, true)
			),
			Map.of(
				"test1", ReferenceSchema._internalBuild("test1", Entities.PARAMETER, true, Cardinality.ZERO_OR_MORE, Entities.PARAMETER_GROUP, false, true, true),
				"test2", ReferenceSchema._internalBuild("test2", Entities.CATEGORY, false, Cardinality.ONE_OR_MORE, null, false, true, true)
			),
			Set.of(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_ATTRIBUTES),
			Map.of(
				"compoundAttribute",
				SortableAttributeCompoundSchema._internalBuild(
					"compoundAttribute", "This is compound attribute", null,
					Arrays.asList(
						new AttributeElement("test1", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
						new AttributeElement("test2", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
					)
				)
			)
		);
	}
}