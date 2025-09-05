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
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcBinaryEntity;
import io.evitadb.externalApi.grpc.generated.GrpcPrice;
import io.evitadb.externalApi.grpc.generated.GrpcSealedEntity;
import io.evitadb.externalApi.grpc.testUtils.GrpcAssertions;
import io.evitadb.test.Entities;
import io.evitadb.utils.VersionUtils.SemVer;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoField;
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
	void buildSealedEntityOldVersion() {
		final SealedEntity entity = new InitialEntityBuilder(createEntitySchema(), 1)
			.setReference("test2", 1)
			.setAttribute("test1", Locale.ENGLISH, LocalDateTime.now())
			.setAssociatedData("test2", Locale.ENGLISH, new String[]{"test1", "test2"})
			.setPrice(1, "test", Currency.getInstance("CZK"), BigDecimal.ONE, BigDecimal.TEN, BigDecimal.valueOf(1.1), true)
			.toInstance();

		final GrpcSealedEntity grpcEntity = EntityConverter.toGrpcSealedEntity(entity, null);

		GrpcAssertions.assertEntity(entity, grpcEntity);
	}

	@Test
	void buildSealedEntityCurrentVersion() {
		final SealedEntity entity = new InitialEntityBuilder(createEntitySchema(), 1)
			.setReference("test2", 1)
			.setAttribute("test1", Locale.ENGLISH, LocalDateTime.now())
			.setAssociatedData("test2", Locale.ENGLISH, new String[]{"test1", "test2"})
			.setPrice(1, "test", Currency.getInstance("CZK"), BigDecimal.ONE, BigDecimal.TEN, BigDecimal.valueOf(1.1), true)
			.toInstance();

		final GrpcSealedEntity grpcEntity = EntityConverter.toGrpcSealedEntity(entity, new SemVer(2025, 4));

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
			DateTimeRange.since(OffsetDateTime.now().with(ChronoField.MILLI_OF_SECOND, 0)),
			true
		);

		final GrpcPrice grpcPrice = EntityConverter.toGrpcPrice(price);

		GrpcAssertions.assertPrice(price, grpcPrice);
	}

	@Nonnull
	private static EntitySchema createEntitySchema() {
		return EntitySchema._internalBuild(
			1,
			Entities.PRODUCT,
			"Lorem ipsum dolor sit amet.",
			"Alert! Deprecated!",
			false,
			false,
			Scope.NO_SCOPE,
			true,
			new Scope[] { Scope.LIVE },
			2,
			Set.of(Locale.ENGLISH, Locale.GERMAN),
			Set.of(Currency.getInstance("EUR"), Currency.getInstance("USD"), Currency.getInstance("CZK")),
			Map.of(
				"test1", EntityAttributeSchema._internalBuild("test1", LocalDateTime.class, true),
				"test2", EntityAttributeSchema._internalBuild("test2", Boolean[].class, true)
			),
			Map.of(
				"test1", AssociatedDataSchema._internalBuild("test1", "Lorem ipsum", "Alert", Integer.class, false, true),
				"test2", AssociatedDataSchema._internalBuild("test2", "Lorem ipsum", "Alert", String[].class, true, true)
			),
			Map.of(
				"test1", ReferenceSchema._internalBuild("test1", Entities.PARAMETER, true, Cardinality.ZERO_OR_MORE, Entities.PARAMETER_GROUP, false, new ScopedReferenceIndexType[] { new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING) }, new Scope[] { Scope.LIVE }),
				"test2", ReferenceSchema._internalBuild("test2", Entities.CATEGORY, false, Cardinality.ONE_OR_MORE, null, false, new ScopedReferenceIndexType[] { new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING) }, new Scope[] { Scope.LIVE })
			),
			Set.of(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_ATTRIBUTES),
			Map.of(
				"compoundAttribute",
				SortableAttributeCompoundSchema._internalBuild(
					"compoundAttribute", "This is compound attribute", null, new Scope[] { Scope.LIVE },
					Arrays.asList(
						new AttributeElement("test1", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
						new AttributeElement("test2", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
					)
				)
			)
		);
	}
}
