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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeBetween;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeInRange;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceContentAllWithAttributes;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the indexing of {@link BigDecimalNumberRange} attribute values whose
 * intrinsic scale differs from the attribute schema's `indexedDecimalPlaces`.
 *
 * When a range value is supplied to the engine already typed as {@link BigDecimalNumberRange}
 * (the normal Java / embedded-client case), it must be re-encoded to the schema's
 * `indexedDecimalPlaces` before its comparable `from`/`to` longs are written into the range index.
 * If that re-encoding is skipped, the value keeps the scale derived from the input BigDecimals
 * (via the 2-arg {@link BigDecimalNumberRange#between(BigDecimal, BigDecimal)} factory), and the
 * stored longs land in a different order of magnitude than the longs the query side derives from
 * the schema's `indexedDecimalPlaces`. Overlap tests then never match and the query returns nothing
 * even though the ranges clearly overlap.
 *
 * Every test here deliberately constructs values at scale 5 while the schema uses
 * `indexedDecimalPlaces = 4`, so the input scale differs from the indexed scale (the trigger).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("BigDecimalNumberRange attribute value scale must match indexedDecimalPlaces")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(FILTER)
@Tag(ATTRIBUTE)
class BigDecimalNumberRangeValueScaleFunctionalTest {
	private static final int INDEXED_DECIMAL_PLACES = 4;
	private static final String ATTR_RANGE = "priceRange";
	private static final String ATTR_RANGE_ARRAY = "priceRanges";
	private static final String ATTR_SCALAR = "quantity";
	private static final String ATTR_SCALAR_ARRAY = "quantities";

	/**
	 * Core reproduction: a single {@link BigDecimalNumberRange} attribute whose value is built from
	 * scale-5 BigDecimals while the schema indexes 4 decimal places. The query bounds overlap the
	 * stored range, so the entity must be returned.
	 */
	@DisplayName("Single range value built at scale 5 must be found by overlapping attributeBetween")
	@Test
	void shouldFindEntityWhenRangeValueScaleDiffersFromIndexedDecimalPlaces(@Nonnull Evita evita) {
		final String entityType = "rangeSingle";
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(entityType)
					.withAttribute(
						ATTR_RANGE, BigDecimalNumberRange.class,
						whichIs -> whichIs.filterable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
					)
					.updateVia(session);

				// intrinsic scale 5 (differs from indexedDecimalPlaces = 4) -> triggers the bug
				session.createNewEntity(entityType, 1)
					.setAttribute(
						ATTR_RANGE,
						BigDecimalNumberRange.between(new BigDecimal("88.10000"), new BigDecimal("118.10000"))
					)
					.upsertVia(session);
			}
		);

		evita.queryCatalog(TEST_CATALOG, session -> {
			// [88.1, 118.1] clearly overlaps [88, 100]
			final List<EntityReferenceContract> result = session.queryListOfEntityReferences(
				query(
					collection(entityType),
					filterBy(attributeBetween(ATTR_RANGE, 88, 100))
				)
			);
			assertEquals(
				1, result.size(),
				"Stored range [88.1, 118.1] (built at scale 5) overlaps query bounds [88, 100] but was " +
					"not found - the range value was not re-encoded to indexedDecimalPlaces=" + INDEXED_DECIMAL_PLACES +
					", so the indexed longs are at a different scale than the query bounds. Expected 1 record, got " +
					result.size() + "."
			);
			assertEquals(1, result.get(0).getPrimaryKey());
			return null;
		});
	}

	/**
	 * Negative control: with the same scale-5 value and same schema, query bounds that genuinely do
	 * not overlap the stored range must return nothing. This proves the positive test above cannot
	 * pass trivially by "always returning everything".
	 */
	@DisplayName("Non-overlapping attributeBetween must return nothing (negative control)")
	@Test
	void shouldNotFindEntityWhenRangeDoesNotOverlap(@Nonnull Evita evita) {
		final String entityType = "rangeSingleNegative";
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(entityType)
					.withAttribute(
						ATTR_RANGE, BigDecimalNumberRange.class,
						whichIs -> whichIs.filterable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
					)
					.updateVia(session);

				session.createNewEntity(entityType, 1)
					.setAttribute(
						ATTR_RANGE,
						BigDecimalNumberRange.between(new BigDecimal("88.10000"), new BigDecimal("118.10000"))
					)
					.upsertVia(session);
			}
		);

		evita.queryCatalog(TEST_CATALOG, session -> {
			// [10, 20] does not overlap [88.1, 118.1]
			final List<EntityReferenceContract> result = session.queryListOfEntityReferences(
				query(
					collection(entityType),
					filterBy(attributeBetween(ATTR_RANGE, 10, 20))
				)
			);
			assertTrue(
				result.isEmpty(),
				"Query bounds [10, 20] do not overlap stored range [88.1, 118.1] yet " + result.size() +
					" record(s) were returned."
			);
			return null;
		});
	}

	/**
	 * Array variant: a {@link BigDecimalNumberRange}[] attribute where one element is built at scale 5.
	 * A query overlapping that element must return the entity (array matches if any element overlaps).
	 */
	@DisplayName("Array range element built at scale 5 must be found by overlapping attributeBetween")
	@Test
	void shouldFindEntityWhenArrayRangeElementScaleDiffersFromIndexedDecimalPlaces(@Nonnull Evita evita) {
		final String entityType = "rangeArray";
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(entityType)
					.withAttribute(
						ATTR_RANGE_ARRAY, BigDecimalNumberRange[].class,
						whichIs -> whichIs.filterable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
					)
					.updateVia(session);

				final BigDecimalNumberRange[] ranges = new BigDecimalNumberRange[] {
					// element built at scale 5 (differs from indexedDecimalPlaces = 4)
					BigDecimalNumberRange.between(new BigDecimal("88.10000"), new BigDecimal("118.10000")),
					// an unrelated element that must NOT match the query bounds below
					BigDecimalNumberRange.between(new BigDecimal("500.00000"), new BigDecimal("600.00000"))
				};
				session.createNewEntity(entityType, 1)
					.setAttribute(ATTR_RANGE_ARRAY, ranges)
					.upsertVia(session);
			}
		);

		evita.queryCatalog(TEST_CATALOG, session -> {
			final List<EntityReferenceContract> result = session.queryListOfEntityReferences(
				query(
					collection(entityType),
					filterBy(attributeBetween(ATTR_RANGE_ARRAY, 88, 100))
				)
			);
			assertEquals(
				1, result.size(),
				"Array element [88.1, 118.1] (built at scale 5) overlaps query bounds [88, 100] but the " +
					"entity was not found - the array range element was not re-encoded to indexedDecimalPlaces=" +
					INDEXED_DECIMAL_PLACES + ". Expected 1 record, got " + result.size() + "."
			);
			assertEquals(1, result.get(0).getPrimaryKey());
			return null;
		});
	}

	/**
	 * attributeInRange variant: a single numeric value that falls inside the stored scale-5 range
	 * must locate the entity.
	 */
	@DisplayName("attributeInRange must find entity whose range value was built at scale 5")
	@Test
	void shouldFindEntityWithAttributeInRangeWhenScaleDiffers(@Nonnull Evita evita) {
		final String entityType = "rangeInRange";
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(entityType)
					.withAttribute(
						ATTR_RANGE, BigDecimalNumberRange.class,
						whichIs -> whichIs.filterable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
					)
					.updateVia(session);

				session.createNewEntity(entityType, 1)
					.setAttribute(
						ATTR_RANGE,
						BigDecimalNumberRange.between(new BigDecimal("88.10000"), new BigDecimal("118.10000"))
					)
					.upsertVia(session);
			}
		);

		evita.queryCatalog(TEST_CATALOG, session -> {
			// 90 falls within [88.1, 118.1]
			final List<EntityReferenceContract> result = session.queryListOfEntityReferences(
				query(
					collection(entityType),
					filterBy(attributeInRange(ATTR_RANGE, 90))
				)
			);
			assertEquals(
				1, result.size(),
				"Value 90 falls within stored range [88.1, 118.1] (built at scale 5) but the entity was " +
					"not found via attributeInRange - the range value was not re-encoded to indexedDecimalPlaces=" +
					INDEXED_DECIMAL_PLACES + ". Expected 1 record, got " + result.size() + "."
			);
			assertEquals(1, result.get(0).getPrimaryKey());
			return null;
		});
	}

	/**
	 * Insert / update / remove symmetry: after the entity is upserted, its range is updated to a
	 * different (also scale-5) value. The old bounds must no longer match (no stale index entry) and
	 * the new bounds must match (the new value must be correctly indexed). Finally the entity is
	 * removed and must not be found by either query.
	 */
	@DisplayName("Updating and removing a scale-5 range value must keep the index consistent")
	@Test
	void shouldKeepIndexConsistentOnUpdateAndRemove(@Nonnull Evita evita) {
		final String entityType = "rangeUpdate";
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(entityType)
					.withAttribute(
						ATTR_RANGE, BigDecimalNumberRange.class,
						whichIs -> whichIs.filterable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
					)
					.updateVia(session);

				session.createNewEntity(entityType, 1)
					.setAttribute(
						ATTR_RANGE,
						BigDecimalNumberRange.between(new BigDecimal("88.10000"), new BigDecimal("118.10000"))
					)
					.upsertVia(session);
			}
		);

		// update the range to a non-overlapping window (still scale 5)
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(entityType, 1, attributeContentAll()).orElseThrow()
					.openForWrite()
					.setAttribute(
						ATTR_RANGE,
						BigDecimalNumberRange.between(new BigDecimal("200.10000"), new BigDecimal("230.10000"))
					)
					.upsertVia(session);
			}
		);

		evita.queryCatalog(TEST_CATALOG, session -> {
			// the OLD window [88, 100] must no longer match (no stale index entry)
			final List<EntityReferenceContract> staleResult = session.queryListOfEntityReferences(
				query(
					collection(entityType),
					filterBy(attributeBetween(ATTR_RANGE, 88, 100))
				)
			);
			assertTrue(
				staleResult.isEmpty(),
				"After updating the range to [200.1, 230.1], the obsolete query [88, 100] still matched " +
					staleResult.size() + " record(s) - stale index entry leaked."
			);

			// the NEW window [210, 220] must match the updated value
			final List<EntityReferenceContract> freshResult = session.queryListOfEntityReferences(
				query(
					collection(entityType),
					filterBy(attributeBetween(ATTR_RANGE, 210, 220))
				)
			);
			assertEquals(
				1, freshResult.size(),
				"Updated range [200.1, 230.1] (built at scale 5) overlaps query [210, 220] but was not " +
					"found - the updated range value was not re-encoded to indexedDecimalPlaces=" +
					INDEXED_DECIMAL_PLACES + ". Expected 1 record, got " + freshResult.size() + "."
			);
			return null;
		});

		// remove the entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.deleteEntity(entityType, 1);
			}
		);

		evita.queryCatalog(TEST_CATALOG, session -> {
			final List<EntityReferenceContract> afterRemoval = session.queryListOfEntityReferences(
				query(
					collection(entityType),
					filterBy(attributeBetween(ATTR_RANGE, 210, 220))
				)
			);
			assertTrue(
				afterRemoval.isEmpty(),
				"After removing the entity, query [210, 220] still matched " + afterRemoval.size() +
					" record(s) - the index was not cleaned up."
			);
			return null;
		});
	}

	/**
	 * Scalar {@link BigDecimal} counterpart of the range suite. Scalars are matched through the inverted
	 * index, which keys values by {@code compareTo} (scale-insensitive), so a value supplied at scale 5
	 * while the schema indexes 4 decimal places is unaffected by the range bug. These tests mirror the
	 * range coverage (single / array / equality / update / remove) and must pass both before and after the
	 * range fix — they document that only range attributes encode to scale-sensitive comparable longs.
	 */
	@Nested
	@DisplayName("Scalar BigDecimal attribute paths (scale-insensitive control)")
	@Tag(CONTRACT)
	@Tag(FILTER)
	@Tag(ATTRIBUTE)
	class ScalarBigDecimalAttributePaths {

		@DisplayName("Scalar value at scale 5 must be found by overlapping attributeBetween")
		@Test
		void shouldFindScalarByOverlappingAttributeBetween(@Nonnull Evita evita) {
			final String entityType = "scalarSingle";
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(entityType)
						.withAttribute(
							ATTR_SCALAR, BigDecimal.class,
							whichIs -> whichIs.filterable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
						)
						.updateVia(session);

					// scale 5, differs from indexedDecimalPlaces = 4
					session.createNewEntity(entityType, 1)
						.setAttribute(ATTR_SCALAR, new BigDecimal("88.10000"))
						.upsertVia(session);
				}
			);

			evita.queryCatalog(TEST_CATALOG, session -> {
				final List<EntityReferenceContract> result = session.queryListOfEntityReferences(
					query(
						collection(entityType),
						filterBy(attributeBetween(ATTR_SCALAR, new BigDecimal("88"), new BigDecimal("100")))
					)
				);
				assertEquals(
					1, result.size(),
					"Scalar BigDecimal 88.1 (scale 5) within [88, 100] should always be found regardless of " +
						"indexedDecimalPlaces. Expected 1 record, got " + result.size() + "."
				);
				assertEquals(1, result.get(0).getPrimaryKey());
				return null;
			});
		}

		@DisplayName("Non-overlapping attributeBetween on a scalar must return nothing (negative control)")
		@Test
		void shouldNotFindScalarByNonOverlappingAttributeBetween(@Nonnull Evita evita) {
			final String entityType = "scalarSingleNegative";
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(entityType)
						.withAttribute(
							ATTR_SCALAR, BigDecimal.class,
							whichIs -> whichIs.filterable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
						)
						.updateVia(session);

					session.createNewEntity(entityType, 1)
						.setAttribute(ATTR_SCALAR, new BigDecimal("88.10000"))
						.upsertVia(session);
				}
			);

			evita.queryCatalog(TEST_CATALOG, session -> {
				final List<EntityReferenceContract> result = session.queryListOfEntityReferences(
					query(
						collection(entityType),
						filterBy(attributeBetween(ATTR_SCALAR, new BigDecimal("10"), new BigDecimal("20")))
					)
				);
				assertTrue(
					result.isEmpty(),
					"Query bounds [10, 20] do not contain scalar 88.1 yet " + result.size() +
						" record(s) were returned."
				);
				return null;
			});
		}

		@DisplayName("attributeEquals must match a scalar regardless of the query value scale")
		@Test
		void shouldFindScalarByAttributeEqualsRegardlessOfScale(@Nonnull Evita evita) {
			final String entityType = "scalarEquals";
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(entityType)
						.withAttribute(
							ATTR_SCALAR, BigDecimal.class,
							whichIs -> whichIs.filterable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
						)
						.updateVia(session);

					// stored at scale 5
					session.createNewEntity(entityType, 1)
						.setAttribute(ATTR_SCALAR, new BigDecimal("88.10000"))
						.upsertVia(session);
				}
			);

			evita.queryCatalog(TEST_CATALOG, session -> {
				// query value at scale 3 - numerically equal, must match (inverted index uses compareTo)
				final List<EntityReferenceContract> result = session.queryListOfEntityReferences(
					query(
						collection(entityType),
						filterBy(attributeEquals(ATTR_SCALAR, new BigDecimal("88.100")))
					)
				);
				assertEquals(
					1, result.size(),
					"Scalar 88.10000 must match attributeEquals(88.100) - scalar equality is scale-insensitive. " +
						"Expected 1 record, got " + result.size() + "."
				);
				assertEquals(1, result.get(0).getPrimaryKey());
				return null;
			});
		}

		@DisplayName("Scalar array element at scale 5 must be found by overlapping attributeBetween")
		@Test
		void shouldFindScalarArrayElementRegardlessOfScale(@Nonnull Evita evita) {
			final String entityType = "scalarArray";
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(entityType)
						.withAttribute(
							ATTR_SCALAR_ARRAY, BigDecimal[].class,
							whichIs -> whichIs.filterable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
						)
						.updateVia(session);

					session.createNewEntity(entityType, 1)
						.setAttribute(
							ATTR_SCALAR_ARRAY,
							new BigDecimal[]{new BigDecimal("88.10000"), new BigDecimal("500.00000")}
						)
						.upsertVia(session);
				}
			);

			evita.queryCatalog(TEST_CATALOG, session -> {
				final List<EntityReferenceContract> result = session.queryListOfEntityReferences(
					query(
						collection(entityType),
						filterBy(attributeBetween(ATTR_SCALAR_ARRAY, new BigDecimal("88"), new BigDecimal("100")))
					)
				);
				assertEquals(
					1, result.size(),
					"Array element 88.1 (scale 5) within [88, 100] should always be found regardless of " +
						"indexedDecimalPlaces. Expected 1 record, got " + result.size() + "."
				);
				assertEquals(1, result.get(0).getPrimaryKey());
				return null;
			});
		}

		@DisplayName("Updating and removing a scale-5 scalar value must keep the index consistent")
		@Test
		void shouldKeepScalarIndexConsistentOnUpdateAndRemove(@Nonnull Evita evita) {
			final String entityType = "scalarUpdate";
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(entityType)
						.withAttribute(
							ATTR_SCALAR, BigDecimal.class,
							whichIs -> whichIs.filterable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
						)
						.updateVia(session);

					session.createNewEntity(entityType, 1)
						.setAttribute(ATTR_SCALAR, new BigDecimal("88.10000"))
						.upsertVia(session);
				}
			);

			// update the scalar to a different (also scale-5) value
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(entityType, 1, attributeContentAll()).orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_SCALAR, new BigDecimal("200.10000"))
						.upsertVia(session);
				}
			);

			evita.queryCatalog(TEST_CATALOG, session -> {
				// the OLD value must no longer match
				final List<EntityReferenceContract> staleResult = session.queryListOfEntityReferences(
					query(
						collection(entityType),
						filterBy(attributeBetween(ATTR_SCALAR, new BigDecimal("88"), new BigDecimal("100")))
					)
				);
				assertTrue(
					staleResult.isEmpty(),
					"After updating the scalar to 200.1, the obsolete query [88, 100] still matched " +
						staleResult.size() + " record(s) - stale index entry leaked."
				);

				// the NEW value must match
				final List<EntityReferenceContract> freshResult = session.queryListOfEntityReferences(
					query(
						collection(entityType),
						filterBy(attributeBetween(ATTR_SCALAR, new BigDecimal("190"), new BigDecimal("210")))
					)
				);
				assertEquals(
					1, freshResult.size(),
					"Updated scalar 200.1 within [190, 210] was not found. Expected 1 record, got " +
						freshResult.size() + "."
				);
				return null;
			});

			// remove the entity
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(entityType, 1);
				}
			);

			evita.queryCatalog(TEST_CATALOG, session -> {
				final List<EntityReferenceContract> afterRemoval = session.queryListOfEntityReferences(
					query(
						collection(entityType),
						filterBy(attributeBetween(ATTR_SCALAR, new BigDecimal("190"), new BigDecimal("210")))
					)
				);
				assertTrue(
					afterRemoval.isEmpty(),
					"After removing the entity, query [190, 210] still matched " + afterRemoval.size() +
						" record(s) - the index was not cleaned up."
				);
				return null;
			});
		}
	}

	/**
	 * Reference-attribute insertion paths. Reference attributes are indexed through
	 * {@code ReferenceIndexMutator}, which delegates to the same {@code AttributeIndexMutator} upsert /
	 * removal routine as entity attributes. These tests assert the range-value re-encoding holds on that
	 * route too — for single values, array variants, and across update / remove.
	 */
	@Nested
	@DisplayName("Reference attribute insertion paths")
	@Tag(CONTRACT)
	@Tag(FILTER)
	@Tag(ATTRIBUTE)
	@Tag(REFERENCE)
	class ReferenceAttributePaths {
		private static final String PRODUCT = "product";
		private static final String BRAND = "brand";

		/**
		 * Defines a product with an indexed reference to a brand carrying a filterable
		 * {@link BigDecimalNumberRange} attribute, and creates a single brand to point at.
		 */
		private static void defineSchema(
			@Nonnull Evita evita,
			@Nonnull Class<? extends java.io.Serializable> rangeAttributeType,
			@Nonnull String attributeName
		) {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(BRAND).updateVia(session);
					session.defineEntitySchema(PRODUCT)
						.withReferenceToEntity(
							BRAND, BRAND, Cardinality.ZERO_OR_MORE,
							thatIs -> thatIs.indexed().withAttribute(
								attributeName, rangeAttributeType,
								whichIs -> whichIs.filterable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
							)
						)
						.updateVia(session);
					session.createNewEntity(BRAND, 100).upsertVia(session);
				}
			);
		}

		@DisplayName("Reference range attribute built at scale 5 must be found via referenceHaving")
		@Test
		void shouldFindEntityByReferenceRangeAttributeBuiltAtScale5(@Nonnull Evita evita) {
			defineSchema(evita, BigDecimalNumberRange.class, ATTR_RANGE);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(PRODUCT, 1)
						.setReference(
							BRAND, 100,
							whichIs -> whichIs.setAttribute(
								ATTR_RANGE,
								BigDecimalNumberRange.between(new BigDecimal("88.10000"), new BigDecimal("118.10000"))
							)
						)
						.upsertVia(session);
				}
			);

			evita.queryCatalog(TEST_CATALOG, session -> {
				final List<EntityReferenceContract> result = session.queryListOfEntityReferences(
					query(
						collection(PRODUCT),
						filterBy(referenceHaving(BRAND, attributeBetween(ATTR_RANGE, 88, 100)))
					)
				);
				assertEquals(
					1, result.size(),
					"Reference range [88.1, 118.1] (built at scale 5) overlaps [88, 100] but the owning entity " +
						"was not found via referenceHaving - the reference range value was not re-encoded to " +
						"indexedDecimalPlaces=" + INDEXED_DECIMAL_PLACES + ". Expected 1 record, got " + result.size() + "."
				);
				assertEquals(1, result.get(0).getPrimaryKey());
				return null;
			});
		}

		@DisplayName("Reference range array element built at scale 5 must be found via referenceHaving")
		@Test
		void shouldFindEntityByReferenceRangeArrayElementBuiltAtScale5(@Nonnull Evita evita) {
			defineSchema(evita, BigDecimalNumberRange[].class, ATTR_RANGE_ARRAY);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(PRODUCT, 1)
						.setReference(
							BRAND, 100,
							whichIs -> whichIs.setAttribute(
								ATTR_RANGE_ARRAY,
								new BigDecimalNumberRange[]{
									BigDecimalNumberRange.between(new BigDecimal("88.10000"), new BigDecimal("118.10000")),
									BigDecimalNumberRange.between(new BigDecimal("500.00000"), new BigDecimal("600.00000"))
								}
							)
						)
						.upsertVia(session);
				}
			);

			evita.queryCatalog(TEST_CATALOG, session -> {
				final List<EntityReferenceContract> result = session.queryListOfEntityReferences(
					query(
						collection(PRODUCT),
						filterBy(referenceHaving(BRAND, attributeBetween(ATTR_RANGE_ARRAY, 88, 100)))
					)
				);
				assertEquals(
					1, result.size(),
					"Reference range array element [88.1, 118.1] (built at scale 5) overlaps [88, 100] but the " +
						"owning entity was not found - the reference range array element was not re-encoded to " +
						"indexedDecimalPlaces=" + INDEXED_DECIMAL_PLACES + ". Expected 1 record, got " + result.size() + "."
				);
				assertEquals(1, result.get(0).getPrimaryKey());
				return null;
			});
		}

		@DisplayName("Updating and removing a scale-5 reference range value must keep the index consistent")
		@Test
		void shouldKeepReferenceIndexConsistentOnUpdateAndRemove(@Nonnull Evita evita) {
			defineSchema(evita, BigDecimalNumberRange.class, ATTR_RANGE);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(PRODUCT, 1)
						.setReference(
							BRAND, 100,
							whichIs -> whichIs.setAttribute(
								ATTR_RANGE,
								BigDecimalNumberRange.between(new BigDecimal("88.10000"), new BigDecimal("118.10000"))
							)
						)
						.upsertVia(session);
				}
			);

			// update the reference attribute to a non-overlapping window (still scale 5)
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(PRODUCT, 1, attributeContentAll(), referenceContentAllWithAttributes())
						.orElseThrow()
						.openForWrite()
						.setReference(
							BRAND, 100,
							whichIs -> whichIs.setAttribute(
								ATTR_RANGE,
								BigDecimalNumberRange.between(new BigDecimal("200.10000"), new BigDecimal("230.10000"))
							)
						)
						.upsertVia(session);
				}
			);

			evita.queryCatalog(TEST_CATALOG, session -> {
				final List<EntityReferenceContract> staleResult = session.queryListOfEntityReferences(
					query(
						collection(PRODUCT),
						filterBy(referenceHaving(BRAND, attributeBetween(ATTR_RANGE, 88, 100)))
					)
				);
				assertTrue(
					staleResult.isEmpty(),
					"After updating the reference range to [200.1, 230.1], the obsolete query [88, 100] still " +
						"matched " + staleResult.size() + " record(s) - stale reference index entry leaked."
				);

				final List<EntityReferenceContract> freshResult = session.queryListOfEntityReferences(
					query(
						collection(PRODUCT),
						filterBy(referenceHaving(BRAND, attributeBetween(ATTR_RANGE, 210, 220)))
					)
				);
				assertEquals(
					1, freshResult.size(),
					"Updated reference range [200.1, 230.1] (scale 5) overlaps [210, 220] but was not found - " +
						"the updated reference range value was not re-encoded to indexedDecimalPlaces=" +
						INDEXED_DECIMAL_PLACES + ". Expected 1 record, got " + freshResult.size() + "."
				);
				return null;
			});

			// remove the owning entity
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(PRODUCT, 1);
				}
			);

			evita.queryCatalog(TEST_CATALOG, session -> {
				final List<EntityReferenceContract> afterRemoval = session.queryListOfEntityReferences(
					query(
						collection(PRODUCT),
						filterBy(referenceHaving(BRAND, attributeBetween(ATTR_RANGE, 210, 220)))
					)
				);
				assertTrue(
					afterRemoval.isEmpty(),
					"After removing the entity, query [210, 220] still matched " + afterRemoval.size() +
						" record(s) - the reference index was not cleaned up."
				);
				return null;
			});
		}
	}
}
