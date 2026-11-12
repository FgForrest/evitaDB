/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.structure.RangeHolder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.ReflectionLookup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies end to end that an upsert of a range value is stored whenever the new range differs from the stored one in
 * anything a reader sees, although ranges compare equal by their encoded bounds - `[1.0,2.0]` and `[10,20]` both
 * encode to the comparable longs `10` and `20` at their own scale - and that an upsert of the very same value changes
 * nothing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Upsert of range values encoded to the same comparable longs")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(ATTRIBUTE)
@Tag(FILTER)
class RangeValueUpsertFunctionalTest {
	private static final String PRODUCT = "product";
	private static final String BRAND = "brand";
	private static final String RANGE = "range";
	private static final String HOLDER = "holder";
	private static final String HOLDERS = "holders";
	private static final int PK = 1;
	private static final Currency CZK = Currency.getInstance("CZK");

	/**
	 * Returns the range `[1.0,2.0]`, written first.
	 *
	 * @return a new instance of the range
	 */
	@Nonnull
	private static BigDecimalNumberRange oneToTwo() {
		return BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"));
	}

	/**
	 * Returns the range `[10,20]`, which encodes to the same comparable longs as {@link #oneToTwo()}.
	 *
	 * @return a new instance of the range
	 */
	@Nonnull
	private static BigDecimalNumberRange tenToTwenty() {
		return BigDecimalNumberRange.between(new BigDecimal("10"), new BigDecimal("20"));
	}

	/**
	 * Returns primary keys of the products matching the passed filter.
	 *
	 * @param evita  the evitaDB instance to query
	 * @param filter the filter constraint
	 * @return primary keys of the matching products
	 */
	@Nonnull
	private static List<Integer> queryPrimaryKeys(
		@Nonnull Evita evita,
		@Nonnull FilterConstraint filter
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<EntityReferenceContract> references = session.queryListOfEntityReferences(
					// a cheap prefetch would match the fetched entities and never reach the index
					query(collection(PRODUCT), filterBy(filter), require(debug(DebugMode.PREFER_INDEX_SCAN)))
				);
				final List<Integer> primaryKeys = new ArrayList<>(references.size());
				for (final EntityReferenceContract reference : references) {
					primaryKeys.add(reference.getPrimaryKey());
				}
				return primaryKeys;
			}
		);
	}

	/**
	 * Creates the product with an array of ranges in associated data {@link #RANGE} and an array of DTOs in associated
	 * data {@link #HOLDERS}, both holding {@link #oneToTwo()}.
	 *
	 * @param evita the evitaDB instance the product is created in
	 */
	private static void createProductWithArrayAssociatedData(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT).updateVia(session);
				session.upsertEntity(
					new EntityUpsertMutation(
						PRODUCT, PK, EntityExistence.MAY_EXIST,
						arrayAssociatedDataUpsert(oneToTwo()).getLocalMutations()
					)
				);
			}
		);
	}

	/**
	 * Builds an upsert of the product setting associated data {@link #RANGE} to an array holding the passed range and
	 * associated data {@link #HOLDERS} to an array holding a DTO with that range, converted to complex data the way
	 * the entity builder converts it - a single complex object with an array root.
	 *
	 * @param range the range of both arrays
	 * @return the upsert mutation of the existing product
	 */
	@Nonnull
	private static EntityUpsertMutation arrayAssociatedDataUpsert(@Nonnull BigDecimalNumberRange range) {
		return new EntityUpsertMutation(
			PRODUCT, PK, EntityExistence.MUST_EXIST,
			new UpsertAssociatedDataMutation(RANGE, new BigDecimalNumberRange[]{range}),
			new UpsertAssociatedDataMutation(
				HOLDERS,
				ComplexDataObjectConverter.getSerializableForm(new RangeHolder[]{new RangeHolder(HOLDER, range)})
			)
		);
	}

	@Nested
	@DisplayName("A range encoded to the same longs as the stored one")
	class DifferentRangeTest {

		@Test
		@DisplayName("should replace an entity attribute and find the entity by the new range only")
		void shouldReplaceEntityAttributeAndFindEntityByNewRange(Evita evita) {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAttribute(
							RANGE, BigDecimalNumberRange.class, whichIs -> whichIs.filterable().indexDecimalPlaces(1)
						)
						.updateVia(session);
					session.createNewEntity(PRODUCT, PK).setAttribute(RANGE, oneToTwo()).upsertVia(session);
				}
			);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(PRODUCT, PK, attributeContentAll()).orElseThrow()
						.openForWrite()
						.setAttribute(RANGE, tenToTwenty())
						.upsertVia(session);
				}
			);

			final SealedEntity product = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(PRODUCT, PK, attributeContentAll()).orElseThrow();
				}
			);
			assertAll(
				() -> assertEquals("[10,20]", String.valueOf((Object) product.getAttribute(RANGE))),
				() -> assertEquals(List.of(PK), queryPrimaryKeys(evita, attributeEquals(RANGE, tenToTwenty()))),
				() -> assertEquals(List.of(), queryPrimaryKeys(evita, attributeEquals(RANGE, oneToTwo())))
			);
		}

		@Test
		@DisplayName("should replace a reference attribute and find the entity by the new range only")
		void shouldReplaceReferenceAttributeAndFindEntityByNewRange(Evita evita) {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withReferenceTo(
							BRAND, BRAND, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedForFiltering()
								.withAttribute(
									RANGE, BigDecimalNumberRange.class,
									thatIs -> thatIs.filterable().indexDecimalPlaces(1)
								)
						)
						.updateVia(session);
					session.createNewEntity(PRODUCT, PK)
						.setReference(BRAND, 1, whichIs -> whichIs.setAttribute(RANGE, oneToTwo()))
						.upsertVia(session);
				}
			);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(PRODUCT, PK, referenceContentAllWithAttributes()).orElseThrow()
						.openForWrite()
						.setReference(BRAND, 1, whichIs -> whichIs.setAttribute(RANGE, tenToTwenty()))
						.upsertVia(session);
				}
			);

			final SealedEntity product = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(PRODUCT, PK, referenceContentAllWithAttributes()).orElseThrow();
				}
			);
			assertAll(
				() -> assertEquals(
					"[10,20]", String.valueOf((Object) product.getReference(BRAND, 1).orElseThrow().getAttribute(RANGE))
				),
				() -> assertEquals(
					List.of(PK),
					queryPrimaryKeys(evita, referenceHaving(BRAND, attributeEquals(RANGE, tenToTwenty())))
				),
				() -> assertEquals(
					List.of(),
					queryPrimaryKeys(evita, referenceHaving(BRAND, attributeEquals(RANGE, oneToTwo())))
				)
			);
		}

		@Test
		@DisplayName("should replace associated data")
		void shouldReplaceAssociatedData(Evita evita) {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAssociatedData(RANGE, BigDecimalNumberRange.class)
						.updateVia(session);
					session.createNewEntity(PRODUCT, PK).setAssociatedData(RANGE, oneToTwo()).upsertVia(session);
				}
			);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(PRODUCT, PK, associatedDataContentAll()).orElseThrow()
						.openForWrite()
						.setAssociatedData(RANGE, tenToTwenty())
						.upsertVia(session);
				}
			);

			final SealedEntity product = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(PRODUCT, PK, associatedDataContentAll()).orElseThrow();
				}
			);
			assertEquals("[10,20]", String.valueOf((Object) product.getAssociatedData(RANGE)));
		}

		/**
		 * A DTO with a range property is stored as complex associated data, whose range leaf compares equal by its
		 * encoded bounds - so complex data differing only in that leaf compare equal as a whole.
		 */
		@Test
		@DisplayName("should replace the range leaf of complex associated data")
		void shouldReplaceRangeLeafOfComplexAssociatedData(Evita evita) {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAssociatedData(HOLDER, RangeHolder.class)
						.updateVia(session);
					session.createNewEntity(PRODUCT, PK)
						.setAssociatedData(HOLDER, new RangeHolder(HOLDER, oneToTwo()))
						.upsertVia(session);
				}
			);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(PRODUCT, PK, associatedDataContentAll()).orElseThrow()
						.openForWrite()
						.setAssociatedData(HOLDER, new RangeHolder(HOLDER, tenToTwenty()))
						.upsertVia(session);
				}
			);

			final SealedEntity product = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(PRODUCT, PK, associatedDataContentAll()).orElseThrow();
				}
			);
			assertEquals(
				"[10,20]",
				String.valueOf(
					product.getAssociatedData(HOLDER, RangeHolder.class, ReflectionLookup.NO_CACHE_INSTANCE).range()
				)
			);
		}

		/**
		 * Associated data holding an array of ranges, or an array of DTOs - stored as complex data with an array
		 * root - must be replaced when a range encodes to the same longs as the stored one. The values are upserted
		 * directly, as the builder of an existing entity cannot set array associated data again.
		 */
		@Test
		@DisplayName("should replace a range in array associated data upserted directly")
		void shouldReplaceRangeInArrayAssociatedData(Evita evita) {
			createProductWithArrayAssociatedData(evita);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.upsertEntity(arrayAssociatedDataUpsert(tenToTwenty()));
				}
			);

			final SealedEntity product = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(PRODUCT, PK, associatedDataContentAll()).orElseThrow();
				}
			);
			final String holders = String.valueOf((Object) product.getAssociatedData(HOLDERS));
			assertAll(
				() -> assertEquals("[[10,20]]", Arrays.deepToString((Object[]) product.getAssociatedData(RANGE))),
				() -> assertTrue(holders.contains("[10,20]") && !holders.contains("[1.0,2.0]"), holders)
			);
		}
	}

	@Nested
	@DisplayName("A price validity naming the same instants at another zone offset")
	class PriceValidityTest {

		@Test
		@DisplayName("should replace the validity of a price")
		void shouldReplaceValidityOfPrice(Evita evita) {
			final LocalDateTime noon = LocalDateTime.of(2024, 1, 1, 12, 0);
			final DateTimeRange storedValidity = DateTimeRange.between(
				noon.atOffset(ZoneOffset.ofHours(2)), noon.plusHours(1).atOffset(ZoneOffset.ofHours(2))
			);
			final DateTimeRange newValidity = DateTimeRange.between(
				noon.minusHours(1).atOffset(ZoneOffset.ofHours(1)), noon.atOffset(ZoneOffset.ofHours(1))
			);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
					session.createNewEntity(PRODUCT, PK)
						.setPrice(
							1, "basic", CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, storedValidity, true
						)
						.upsertVia(session);
				}
			);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(PRODUCT, PK, priceContentAll()).orElseThrow()
						.openForWrite()
						.setPrice(
							1, "basic", CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, newValidity, true
						)
						.upsertVia(session);
				}
			);

			final SealedEntity product = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(PRODUCT, PK, priceContentAll()).orElseThrow();
				}
			);
			assertEquals(
				newValidity.toString(),
				String.valueOf(product.getPrice(1, "basic", CZK).orElseThrow().validity())
			);
		}
	}

	@Nested
	@DisplayName("The very same value as the stored one")
	class SameValueTest {

		/**
		 * Builds a fresh instance of every value shape the comparison treats specially - each call returns new objects
		 * equal to those of the previous call, never the same instances.
		 *
		 * @return attribute name mapped to a supplier of a fresh instance of its value
		 */
		@Nonnull
		private static Map<String, Supplier<Serializable>> valueShapes() {
			final Map<String, Supplier<Serializable>> shapes = new LinkedHashMap<>(16);
			shapes.put("decimalRange", RangeValueUpsertFunctionalTest::oneToTwo);
			shapes.put(
				"decimalRangeWithPlaces",
				() -> BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"), 1)
			);
			shapes.put("openDecimalRange", () -> BigDecimalNumberRange.from(new BigDecimal("1.5")));
			shapes.put("openLongRange", () -> LongNumberRange.from(5L));
			shapes.put("saturatedLongRange", () -> LongNumberRange.between(Long.MIN_VALUE, Long.MAX_VALUE));
			shapes.put(
				"dateTimeRange",
				() -> DateTimeRange.between(
					OffsetDateTime.of(2024, 1, 1, 12, 0, 0, 123_456_789, ZoneOffset.ofHours(2)),
					OffsetDateTime.of(2024, 1, 2, 12, 0, 0, 0, ZoneOffset.ofHours(2))
				)
			);
			shapes.put("rangeArray", () -> new BigDecimalNumberRange[]{oneToTwo(), tenToTwenty()});
			shapes.put("plainArray", () -> new Integer[]{1, 2});
			return shapes;
		}

		@Test
		@DisplayName("should keep the version of every value upserted again unchanged")
		void shouldKeepVersionOfEveryValueUpsertedAgainUnchanged(Evita evita) {
			final Map<String, Supplier<Serializable>> shapes = valueShapes();
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(PRODUCT).updateVia(session);
					final EntityBuilder product =
						session.createNewEntity(PRODUCT, PK);
					for (final Map.Entry<String, Supplier<Serializable>> shape : shapes.entrySet()) {
						product.setAttribute(shape.getKey(), shape.getValue().get());
					}
					product.upsertVia(session);
				}
			);

			// the builder sees no change against the stored values
			final boolean builderEmitsMutation = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EntityBuilder builder =
						session.getEntity(PRODUCT, PK, attributeContentAll()).orElseThrow().openForWrite();
					for (final Map.Entry<String, Supplier<Serializable>> shape : shapes.entrySet()) {
						builder.setAttribute(shape.getKey(), shape.getValue().get());
					}
					return builder.toMutation().isPresent();
				}
			);
			// and neither does the server, when it receives the upserts directly
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final List<LocalMutation<?, ?>> mutations = new ArrayList<>(shapes.size());
					for (final Map.Entry<String, Supplier<Serializable>> shape : shapes.entrySet()) {
						mutations.add(new UpsertAttributeMutation(shape.getKey(), shape.getValue().get()));
					}
					session.upsertEntity(new EntityUpsertMutation(PRODUCT, PK, EntityExistence.MUST_EXIST, mutations));
				}
			);

			final SealedEntity product = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(PRODUCT, PK, attributeContentAll()).orElseThrow();
				}
			);
			final Map<String, Integer> versions = new LinkedHashMap<>(16);
			for (final String attributeName : shapes.keySet()) {
				versions.put(attributeName, product.getAttributeValue(attributeName).orElseThrow().version());
			}
			assertAll(
				() -> assertFalse(builderEmitsMutation, "the builder emitted a mutation for unchanged values"),
				() -> assertEquals(
					Map.of(
						"decimalRange", 1, "decimalRangeWithPlaces", 1, "openDecimalRange", 1, "openLongRange", 1,
						"saturatedLongRange", 1, "dateTimeRange", 1, "rangeArray", 1, "plainArray", 1
					),
					versions
				)
			);
		}

		/**
		 * Complex associated data holding a range leaf, read back from the storage and set again, is no change - for
		 * the builder and for the server receiving the upsert directly.
		 */
		@Test
		@DisplayName("should keep the version of complex associated data with a range leaf upserted again unchanged")
		void shouldKeepVersionOfComplexAssociatedDataUpsertedAgainUnchanged(Evita evita) {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAssociatedData(HOLDER, RangeHolder.class)
						.updateVia(session);
					session.createNewEntity(PRODUCT, PK)
						.setAssociatedData(HOLDER, new RangeHolder(HOLDER, oneToTwo()))
						.upsertVia(session);
				}
			);

			// the builder sees no change against the stored value
			final boolean builderEmitsMutation = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(PRODUCT, PK, associatedDataContentAll()).orElseThrow()
						.openForWrite()
						.setAssociatedData(HOLDER, new RangeHolder(HOLDER, oneToTwo()))
						.toMutation()
						.isPresent();
				}
			);
			// and neither does the server, when it receives the upsert directly
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.upsertEntity(
						new EntityUpsertMutation(
							PRODUCT, PK, EntityExistence.MUST_EXIST,
							new UpsertAssociatedDataMutation(
								HOLDER,
								ComplexDataObjectConverter.getSerializableForm(new RangeHolder(HOLDER, oneToTwo()))
							)
						)
					);
				}
			);

			final SealedEntity product = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(PRODUCT, PK, associatedDataContentAll()).orElseThrow();
				}
			);
			assertAll(
				() -> assertFalse(builderEmitsMutation, "the builder emitted a mutation for an unchanged value"),
				() -> assertEquals(1, product.getAssociatedDataValue(HOLDER).orElseThrow().version())
			);
		}

		/**
		 * Associated data holding an array of ranges, or an array of DTOs, upserted again with the very same items is
		 * no change.
		 */
		@Test
		@DisplayName("should keep the version of array associated data upserted directly again unchanged")
		void shouldKeepVersionOfArrayAssociatedDataUpsertedAgainUnchanged(Evita evita) {
			createProductWithArrayAssociatedData(evita);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.upsertEntity(arrayAssociatedDataUpsert(oneToTwo()));
				}
			);

			final SealedEntity product = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(PRODUCT, PK, associatedDataContentAll()).orElseThrow();
				}
			);
			assertAll(
				() -> assertEquals(1, product.getAssociatedDataValue(RANGE).orElseThrow().version()),
				() -> assertEquals(1, product.getAssociatedDataValue(HOLDERS).orElseThrow().version())
			);
		}
	}
}
