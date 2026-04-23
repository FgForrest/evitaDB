/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Abstract base test class for {@link AbstractReducedEntityIndex} behavior. Tests in this class
 * verify the contract specific to reduced entity indexes: reference key resolution, hierarchy
 * operation guards, partitioning index assertions, and locale removal semantics.
 *
 * Concrete test classes must extend this and implement {@link #createInstance()} to provide
 * a fresh instance of their specific reduced index subtype.
 *
 * @param <T> the concrete AbstractReducedEntityIndex subtype being tested
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
abstract class AbstractReducedEntityIndexTest<T extends AbstractReducedEntityIndex>
	extends AbstractEntityIndexTest<T> {

	/**
	 * Attaches a mock catalog to the index after creation so that
	 * price-related operations (which require catalog attachment) work.
	 * The mock catalog provides a {@link GlobalEntityIndex} stub that returns
	 * a {@link PriceListAndCurrencyPriceSuperIndex} for any price index key,
	 * satisfying the {@link io.evitadb.index.price.PriceListAndCurrencyPriceRefIndex}
	 * initialization requirement.
	 */
	@BeforeEach
	void attachCatalog() {
		final GlobalEntityIndex globalEntityIndex = mock(GlobalEntityIndex.class);
		final PriceListAndCurrencyPriceSuperIndex priceSuperIndex =
			mock(PriceListAndCurrencyPriceSuperIndex.class);
		final io.evitadb.index.price.model.priceRecord.PriceRecordContract[] emptyPriceRecords =
			new io.evitadb.index.price.model.priceRecord.PriceRecordContract[0];
		when(priceSuperIndex.getPriceRecords())
			.thenReturn(emptyPriceRecords);
		when(priceSuperIndex.getPriceRecords(any()))
			.thenReturn(emptyPriceRecords);
		when(globalEntityIndex.getPriceIndex(any(PriceIndexKey.class)))
			.thenReturn(priceSuperIndex);

		final Catalog catalog = mock(Catalog.class);
		when(catalog.getEntityIndexIfExists(
			eq(ENTITY_TYPE), any(EntityIndexKey.class), eq(GlobalEntityIndex.class)
		)).thenReturn(Optional.of(globalEntityIndex));

		this.index.attachToCatalog(ENTITY_TYPE, catalog);
	}

	/**
	 * Creates a mock {@link ReferenceSchemaContract} with the given reference index type.
	 *
	 * @param indexType the reference index type to return from the mock
	 * @return a mocked reference schema
	 */
	@Nonnull
	protected ReferenceSchemaContract createReferenceSchema(@Nonnull ReferenceIndexType indexType) {
		final ReferenceSchemaContract schema = mock(ReferenceSchemaContract.class);
		when(schema.getReferenceIndexType(any(Scope.class))).thenReturn(indexType);
		return schema;
	}

	/**
	 * Tests for reference key resolution in {@link AbstractReducedEntityIndex}.
	 */
	@Nested
	@DisplayName("Reference key resolution")
	class ReferenceKeyResolutionTest {

		@Test
		@DisplayName("should extract ReferenceKey from discriminator")
		void shouldExtractReferenceKey() {
			final ReferenceKey referenceKey = AbstractReducedEntityIndexTest.this.index.getReferenceKey();

			assertNotNull(referenceKey);
			assertNotNull(referenceKey.referenceName());
			assertTrue(referenceKey.primaryKey() > 0);
		}

		@Test
		@DisplayName("should extract RepresentativeReferenceKey from discriminator")
		void shouldExtractRepresentativeReferenceKey() {
			final RepresentativeReferenceKey representativeKey = AbstractReducedEntityIndexTest.this.index.getRepresentativeReferenceKey();

			assertNotNull(representativeKey);
			assertNotNull(representativeKey.referenceKey());
			assertEquals(AbstractReducedEntityIndexTest.this.index.getReferenceKey(), representativeKey.referenceKey());
		}
	}

	/**
	 * Tests for hierarchy operation guards in {@link AbstractReducedEntityIndex}.
	 * Reduced indexes must reject hierarchy node operations.
	 */
	@Nested
	@DisplayName("Hierarchy operation guards")
	class HierarchyGuardsTest {

		@Test
		@DisplayName("should throw on addNode")
		void shouldThrowOnAddNode() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> AbstractReducedEntityIndexTest.this.index.addNode(1, null)
			);
		}

		@Test
		@DisplayName("should throw on removeNode")
		void shouldThrowOnRemoveNode() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> AbstractReducedEntityIndexTest.this.index.removeNode(1)
			);
		}
	}

	/**
	 * Tests for partitioning index assertion in {@link AbstractReducedEntityIndex}.
	 * Uses {@code addFacet} / {@code removeFacet} to exercise the assertion guard,
	 * avoiding the deep price-infrastructure mocking that {@code addPrice} would require.
	 */
	@Nested
	@DisplayName("Partitioning index assertion")
	class PartitioningAssertionTest {

		@Test
		@DisplayName("should reject null reference schema")
		void shouldRejectNullReferenceSchema() {
			assertThrows(
				Exception.class,
				() -> AbstractReducedEntityIndexTest.this.index.addFacet(
					null,
					new ReferenceKey("brand", 1),
					null,
					1
				)
			);
		}

		@Test
		@DisplayName("should reject FOR_FILTERING when operation requires partitioning")
		void shouldRejectFilteringOnlyForPriceOps() {
			final ReferenceSchemaContract schema = createReferenceSchema(
				ReferenceIndexType.FOR_FILTERING
			);

			assertThrows(
				Exception.class,
				() -> AbstractReducedEntityIndexTest.this.index.addFacet(
					schema,
					new ReferenceKey("brand", 1),
					null,
					1
				)
			);
		}

		@Test
		@DisplayName("should accept FOR_FILTERING_AND_PARTITIONING for facet ops")
		void shouldAcceptPartitioningForPriceOps() {
			final ReferenceSchemaContract schema = createReferenceSchema(
				ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING
			);

			// should not throw - the partitioning assertion passes
			assertDoesNotThrow(
				() -> AbstractReducedEntityIndexTest.this.index.addFacet(
					schema,
					new ReferenceKey("brand", 1),
					null,
					1
				)
			);
		}
	}

	/**
	 * Tests for locale removal semantics in reduced indexes.
	 * Reduced indexes have `isRequireLocaleRemoval()` returning
	 * false, meaning removeLanguage should NOT throw for
	 * non-existent locale+PK.
	 */
	@Nested
	@DisplayName("Locale removal behavior")
	class LocaleRemovalTest {

		@Test
		@DisplayName("should not throw when removing non-existent locale for reduced index")
		void shouldNotThrowOnRemoveNonExistentLocale() {
			// reduced indexes have isRequireLocaleRemoval() = false
			// so this should not throw even though locale/PK was never added
			assertDoesNotThrow(
				() -> AbstractReducedEntityIndexTest.this.index.removeLanguage(Locale.ENGLISH, 999)
			);
		}
	}

	/**
	 * Tests for isEmpty including facet index in reduced entity indexes.
	 */
	@Nested
	@DisplayName("isEmpty with facet index")
	class IsEmptyWithFacetIndexTest {

		@Test
		@DisplayName("should include facet index in isEmpty check")
		void shouldIncludeFacetIndexInIsEmpty() {
			// add a facet to make the facet index non-empty
			final ReferenceSchemaContract schema = createReferenceSchema(
				ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING
			);

			AbstractReducedEntityIndexTest.this.index.addFacet(
				schema,
				new ReferenceKey("brand", 1),
				null,
				1
			);

			// even though entityIds is empty, facet index is not
			assertFalse(AbstractReducedEntityIndexTest.this.index.isEmpty());
		}
	}
}
