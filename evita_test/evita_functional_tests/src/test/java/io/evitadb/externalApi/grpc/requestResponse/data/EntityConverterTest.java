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
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PriceRangeForSale;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.AssociatedData;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityAttributes;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Prices;
import io.evitadb.api.requestResponse.data.structure.References;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySortableAttributeCompoundSchema;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcBinaryEntity;
import io.evitadb.externalApi.grpc.generated.GrpcPrice;
import io.evitadb.externalApi.grpc.generated.GrpcSealedEntity;
import io.evitadb.externalApi.grpc.testUtils.GrpcAssertions;
import io.evitadb.test.Entities;
import io.evitadb.utils.VersionUtils.SemVer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoField;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies functionalities of methods in {@link EntityConverter} class.
 *
 * @author Tomáš Pozler, 2022
 */
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(PRICE)
class EntityConverterTest {

	private static final Currency CZK = Currency.getInstance("CZK");
	private static final Currency EUR = Currency.getInstance("EUR");
	private static final String BASIC = "basic";
	private static final OffsetDateTime MOMENT_2020 = OffsetDateTime.of(
		2020, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC
	);


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
				EntitySortableAttributeCompoundSchema._internalBuild(
					"compoundAttribute", "This is compound attribute", null, new Scope[] { Scope.LIVE },
					Arrays.asList(
						new AttributeElement("test1", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
						new AttributeElement("test2", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
					)
				)
			)
		);
	}

	/**
	 * Builds an {@link EntityDecorator} populated with the given prices and a synthetic price-for-sale context
	 * (currency = CZK, valid-in = 2020-12-31, price-list = `basic`). The resulting decorator returns a populated
	 * {@code priceForSale} via {@link io.evitadb.api.requestResponse.data.PricesContract#getPriceForSale()} and a
	 * matching {@link PriceRangeForSale} via
	 * {@link io.evitadb.api.requestResponse.data.PricesContract#getPriceRangeForSaleIfAvailable()} so that
	 * {@link EntityConverter#toGrpcSealedEntity(SealedEntity, SemVer)} emits the new
	 * {@code priceForSaleMin} / {@code priceForSaleMax} fields.
	 */
	@Nonnull
	private static EntityDecorator buildDecoratorWithPriceContext(
		@Nonnull EntitySchema schema,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nonnull List<PriceContract> prices
	) {
		return buildDecoratorWithPriceContext(schema, innerRecordHandling, prices, CZK);
	}

	/**
	 * Variant of {@link #buildDecoratorWithPriceContext(EntitySchema, PriceInnerRecordHandling, List)} that lets
	 * the caller pick the currency advertised by the synthetic {@link EvitaRequest}. Passing a currency that no
	 * stored price uses lets a test verify the gRPC converter's behaviour when the request predicate filters out
	 * the selling price.
	 */
	@Nonnull
	private static EntityDecorator buildDecoratorWithPriceContext(
		@Nonnull EntitySchema schema,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nonnull List<PriceContract> prices,
		@Nonnull Currency requestedCurrency
	) {
		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.getRequiresEntityPrices()).thenReturn(PriceContentMode.RESPECTING_FILTER);
		Mockito.when(evitaRequest.getRequiresCurrency()).thenReturn(requestedCurrency);
		Mockito.when(evitaRequest.getRequiresPriceValidIn()).thenReturn(MOMENT_2020);
		Mockito.when(evitaRequest.getRequiresPriceLists()).thenReturn(new String[]{BASIC});
		Mockito.when(evitaRequest.getFetchesAdditionalPriceLists()).thenReturn(new String[0]);
		Mockito.when(evitaRequest.getAccompanyingPrices()).thenReturn(new AccompanyingPrice[0]);
		Mockito.when(evitaRequest.getQueryPriceMode()).thenReturn(QueryPriceMode.WITH_TAX);

		final Entity delegate = Entity._internalBuild(
			1,
			1,
			schema,
			null,
			new References(schema),
			new EntityAttributes(schema),
			new AssociatedData(schema),
			new Prices(schema, 1, prices, innerRecordHandling),
			Collections.emptySet(),
			Scope.DEFAULT_SCOPE,
			false
		);

		return new EntityDecorator(
			delegate,
			schema,
			null,
			new LocaleSerializablePredicate(evitaRequest),
			new HierarchySerializablePredicate(evitaRequest),
			new AttributeValueSerializablePredicate(evitaRequest),
			new AssociatedDataValueSerializablePredicate(evitaRequest),
			new ReferenceContractSerializablePredicate(evitaRequest),
			new PriceContractSerializablePredicate(evitaRequest, Boolean.TRUE),
			MOMENT_2020
		);
	}

	/**
	 * Asserts that the converted gRPC entity carries `priceForSaleMin` / `priceForSaleMax` matching the
	 * {@link PriceRangeForSale} computed from the source entity for the same currency / valid-in / price-list filters.
	 */
	private static void assertPriceRangeRoundTripsThrough(
		@Nonnull EntityDecorator decorator,
		@Nonnull GrpcSealedEntity grpcEntity
	) {
		assertTrue(grpcEntity.hasPriceForSale(), "priceForSale must be emitted as the prerequisite for the range");
		assertTrue(grpcEntity.hasPriceForSaleMin(), "priceForSaleMin must be emitted alongside priceForSale");
		assertTrue(grpcEntity.hasPriceForSaleMax(), "priceForSaleMax must be emitted alongside priceForSale");

		final Optional<PriceRangeForSale> expectedRange = decorator.getPriceRangeForSaleIfAvailable();
		assertTrue(expectedRange.isPresent(), "source entity must expose a PriceRangeForSale");
		final PriceRangeForSale range = expectedRange.get();

		GrpcAssertions.assertPrice(range.lowestPrice(), grpcEntity.getPriceForSaleMin());
		GrpcAssertions.assertPrice(range.highestPrice(), grpcEntity.getPriceForSaleMax());
		GrpcAssertions.assertPrice(range.priceForSale(), grpcEntity.getPriceForSale());
	}

	/**
	 * Builds a price contract with the supplied amount under the default `basic` / CZK / no-validity / indexed setup.
	 */
	@Nonnull
	private static PriceContract priceOf(int priceId, @Nullable Integer innerRecordId, @Nonnull BigDecimal amount) {
		return new Price(
			new Price.PriceKey(priceId, BASIC, CZK),
			innerRecordId,
			amount,
			new BigDecimal("21"),
			amount.multiply(new BigDecimal("1.21")),
			null,
			true
		);
	}

	@Nested
	@DisplayName("Price range round-trip via gRPC EntityConverter")
	class PriceRangeRoundTripTests {

		@Test
		@DisplayName("NONE strategy collapses range to selling price")
		void shouldRoundTripPriceRangeForNoneStrategy() {
			final EntitySchema schema = createEntitySchema();
			final List<PriceContract> prices = List.of(
				priceOf(1, null, new BigDecimal("100"))
			);
			final EntityDecorator decorator = buildDecoratorWithPriceContext(
				schema, PriceInnerRecordHandling.NONE, prices
			);

			final GrpcSealedEntity grpcEntity = EntityConverter.toGrpcSealedEntity(decorator, new SemVer(2025, 4));

			assertPriceRangeRoundTripsThrough(decorator, grpcEntity);
			// NONE collapses min == max == priceForSale
			assertEquals(grpcEntity.getPriceForSale(), grpcEntity.getPriceForSaleMin());
			assertEquals(grpcEntity.getPriceForSale(), grpcEntity.getPriceForSaleMax());
			// gRPC emits all source prices so the receiver can recompute the same range
			assertEquals(prices.size(), grpcEntity.getPricesCount());
		}

		@Test
		@DisplayName("LOWEST_PRICE strategy: lowest equals priceForSale, highest is most expensive variant")
		void shouldRoundTripPriceRangeForLowestPriceStrategy() {
			final EntitySchema schema = createEntitySchema();
			final List<PriceContract> prices = List.of(
				priceOf(1, 100, new BigDecimal("80")),
				priceOf(2, 200, new BigDecimal("120")),
				priceOf(3, 300, new BigDecimal("150"))
			);
			final EntityDecorator decorator = buildDecoratorWithPriceContext(
				schema, PriceInnerRecordHandling.LOWEST_PRICE, prices
			);

			final GrpcSealedEntity grpcEntity = EntityConverter.toGrpcSealedEntity(decorator, new SemVer(2025, 4));

			assertPriceRangeRoundTripsThrough(decorator, grpcEntity);
			// LOWEST_PRICE: min equals priceForSale, max is the most expensive per-inner-record price
			assertEquals(grpcEntity.getPriceForSale(), grpcEntity.getPriceForSaleMin());
			assertFalse(
				grpcEntity.getPriceForSaleMin().equals(grpcEntity.getPriceForSaleMax()),
				"min and max must differ when there are multiple inner records"
			);
			assertEquals(
				new BigDecimal("80"),
				priceWithoutTax(grpcEntity.getPriceForSaleMin())
			);
			assertEquals(
				new BigDecimal("150"),
				priceWithoutTax(grpcEntity.getPriceForSaleMax())
			);
			assertEquals(prices.size(), grpcEntity.getPricesCount());
		}

		@Test
		@DisplayName("SUM strategy: priceForSale is cumulated, bounds are component prices")
		void shouldRoundTripPriceRangeForSumStrategy() {
			final EntitySchema schema = createEntitySchema();
			final List<PriceContract> prices = List.of(
				priceOf(1, 100, new BigDecimal("80")),
				priceOf(2, 200, new BigDecimal("120")),
				priceOf(3, 300, new BigDecimal("150"))
			);
			final EntityDecorator decorator = buildDecoratorWithPriceContext(
				schema, PriceInnerRecordHandling.SUM, prices
			);

			final GrpcSealedEntity grpcEntity = EntityConverter.toGrpcSealedEntity(decorator, new SemVer(2025, 4));

			assertPriceRangeRoundTripsThrough(decorator, grpcEntity);
			// SUM: min / max are component prices (cheapest and most expensive variant)
			assertEquals(
				new BigDecimal("80"),
				priceWithoutTax(grpcEntity.getPriceForSaleMin())
			);
			assertEquals(
				new BigDecimal("150"),
				priceWithoutTax(grpcEntity.getPriceForSaleMax())
			);
			// SUM: priceForSale is cumulated, so it must equal the sum of all three component prices
			assertEquals(
				new BigDecimal("350"),
				priceWithoutTax(grpcEntity.getPriceForSale())
			);
			assertEquals(prices.size(), grpcEntity.getPricesCount());
		}

		/**
		 * Verifies that when the synthetic {@link EvitaRequest} predicate filters out the selling price (here,
		 * by requesting EUR while the entity only carries CZK prices), the gRPC converter must NOT emit any of
		 * `priceForSale`, `priceForSaleMin`, or `priceForSaleMax`. The range bounds are emitted as a
		 * sibling of the selling price; without a selling price they would be meaningless on the wire.
		 */
		@Test
		@DisplayName("No priceForSale, priceForSaleMin or priceForSaleMax emitted when request filters out the selling price")
		void shouldNotEmitPriceRangeFieldsWhenPriceForSaleIsAbsent() {
			final EntitySchema schema = createEntitySchema();
			// stored prices are in CZK only — request currency EUR will filter them all out
			final List<PriceContract> prices = List.of(
				priceOf(1, 100, new BigDecimal("80")),
				priceOf(2, 200, new BigDecimal("120")),
				priceOf(3, 300, new BigDecimal("150"))
			);
			final EntityDecorator decorator = buildDecoratorWithPriceContext(
				schema, PriceInnerRecordHandling.LOWEST_PRICE, prices, EUR
			);

			final GrpcSealedEntity grpcEntity = EntityConverter.toGrpcSealedEntity(decorator, new SemVer(2025, 4));

			// the selling price guard must short-circuit emission of all three fields
			assertFalse(grpcEntity.hasPriceForSale(), "priceForSale must not be emitted when the selling price is absent");
			assertFalse(
				grpcEntity.hasPriceForSaleMin(),
				"priceForSaleMin must not be emitted without a selling price"
			);
			assertFalse(
				grpcEntity.hasPriceForSaleMax(),
				"priceForSaleMax must not be emitted without a selling price"
			);
		}
	}

	/**
	 * Extracts the price-without-tax from a {@link GrpcPrice} for assertions.
	 */
	@Nonnull
	private static BigDecimal priceWithoutTax(@Nonnull GrpcPrice grpcPrice) {
		return io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter
			.toBigDecimal(grpcPrice.getPriceWithoutTax());
	}
}
