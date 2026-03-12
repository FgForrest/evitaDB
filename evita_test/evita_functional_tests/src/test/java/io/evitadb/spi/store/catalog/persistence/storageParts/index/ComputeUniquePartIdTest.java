/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.spi.store.catalog.persistence.storageParts.index;

import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for the `computeUniquePartId` static method and `computeUniquePartIdAndSet` default method
 * on {@link AttributeIndexStoragePart}, tested via a concrete {@link FilterIndexStoragePart} implementation.
 * Also covers {@link CatalogIndexStoragePart#getStoragePartPKForScope(Scope)}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("computeUniquePartId pattern and CatalogIndexStoragePart scope mapping")
class ComputeUniquePartIdTest {

	private static final int ENTITY_INDEX_PK = 5;
	private static final int COMPRESSED_KEY_ID = 42;
	private static final AttributeIndexKey ATTR_KEY = new AttributeIndexKey(null, "code", null);

	/**
	 * Creates a mock {@link KeyCompressor} that always returns the given compressed ID for any key.
	 */
	@Nonnull
	private static KeyCompressor mockKeyCompressor(int compressedId) {
		final KeyCompressor keyCompressor = Mockito.mock(KeyCompressor.class);
		when(keyCompressor.getId(any())).thenReturn(compressedId);
		return keyCompressor;
	}

	/**
	 * Creates a {@link FilterIndexStoragePart} with the given entity index PK, attribute key, and optional
	 * pre-assigned storage part PK.
	 */
	@Nonnull
	private static FilterIndexStoragePart createFilterPart(@Nullable Long preAssignedPK) {
		final FilterIndexStoragePart part = new FilterIndexStoragePart(
			ENTITY_INDEX_PK,
			ATTR_KEY,
			String.class,
			new ValueToRecordBitmap[0],
			null
		);
		if (preAssignedPK != null) {
			part.setStoragePartPK(preAssignedPK);
		}
		return part;
	}

	@Nested
	@DisplayName("Static computeUniquePartId method")
	class StaticComputeUniquePartIdTest {

		@Test
		@DisplayName("should return NumberUtils.join of entity index PK and compressed key ID")
		void shouldReturnNumberUtilsJoinOfEntityIndexPkAndCompressedKeyId() {
			final KeyCompressor keyCompressor = mockKeyCompressor(COMPRESSED_KEY_ID);
			final long expectedId = NumberUtils.join(ENTITY_INDEX_PK, COMPRESSED_KEY_ID);

			final long result = AttributeIndexStoragePart.computeUniquePartId(
				ENTITY_INDEX_PK, AttributeIndexType.FILTER, ATTR_KEY, keyCompressor
			);

			assertEquals(expectedId, result);
		}

		@Test
		@DisplayName("should produce different IDs for different entity index PKs")
		void shouldProduceDifferentIdsForDifferentEntityIndexPks() {
			final KeyCompressor keyCompressor = mockKeyCompressor(COMPRESSED_KEY_ID);

			final long id1 = AttributeIndexStoragePart.computeUniquePartId(
				1, AttributeIndexType.FILTER, ATTR_KEY, keyCompressor
			);
			final long id2 = AttributeIndexStoragePart.computeUniquePartId(
				2, AttributeIndexType.FILTER, ATTR_KEY, keyCompressor
			);

			// the high 32 bits differ, so the results must differ
			assertEquals(NumberUtils.join(1, COMPRESSED_KEY_ID), id1);
			assertEquals(NumberUtils.join(2, COMPRESSED_KEY_ID), id2);
		}
	}

	@Nested
	@DisplayName("Default computeUniquePartIdAndSet method")
	class ComputeUniquePartIdAndSetTest {

		@Test
		@DisplayName("should set storage part PK when it is null")
		void shouldSetStoragePartPkWhenNull() {
			final FilterIndexStoragePart part = createFilterPart(null);
			final KeyCompressor keyCompressor = mockKeyCompressor(COMPRESSED_KEY_ID);
			final long expectedId = NumberUtils.join(ENTITY_INDEX_PK, COMPRESSED_KEY_ID);

			final long result = part.computeUniquePartIdAndSet(keyCompressor);

			assertEquals(expectedId, result);
			assertEquals(expectedId, part.getStoragePartPK());
		}

		@Test
		@DisplayName("should succeed when storage part PK already matches computed value")
		void shouldSucceedWhenStoragePartPkAlreadyMatchesComputedValue() {
			final long expectedId = NumberUtils.join(ENTITY_INDEX_PK, COMPRESSED_KEY_ID);
			final FilterIndexStoragePart part = createFilterPart(expectedId);
			final KeyCompressor keyCompressor = mockKeyCompressor(COMPRESSED_KEY_ID);

			final long result = part.computeUniquePartIdAndSet(keyCompressor);

			assertEquals(expectedId, result);
		}

		@Test
		@DisplayName("should throw when storage part PK differs from computed value")
		void shouldThrowWhenStoragePartPkDiffersFromComputedValue() {
			final FilterIndexStoragePart part = createFilterPart(999L);
			final KeyCompressor keyCompressor = mockKeyCompressor(COMPRESSED_KEY_ID);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> part.computeUniquePartIdAndSet(keyCompressor)
			);
		}
	}

	@Nested
	@DisplayName("CatalogIndexStoragePart scope-based PK mapping")
	class CatalogIndexScopeTest {

		@Test
		@DisplayName("should return 1L for LIVE scope")
		void shouldReturn1ForLiveScope() {
			assertEquals(1L, CatalogIndexStoragePart.getStoragePartPKForScope(Scope.LIVE));
		}

		@Test
		@DisplayName("should return 2L for ARCHIVED scope")
		void shouldReturn2ForArchivedScope() {
			assertEquals(2L, CatalogIndexStoragePart.getStoragePartPKForScope(Scope.ARCHIVED));
		}

		@Test
		@DisplayName("should return correct PK from instance getStoragePartPK for LIVE scope")
		void shouldReturnCorrectPkFromInstanceForLiveScope() {
			final CatalogIndexStoragePart part = new CatalogIndexStoragePart(
				1, new CatalogIndexKey(Scope.LIVE), Set.of()
			);

			assertEquals(1L, part.getStoragePartPK());
		}

		@Test
		@DisplayName("should return correct PK from instance getStoragePartPK for ARCHIVED scope")
		void shouldReturnCorrectPkFromInstanceForArchivedScope() {
			final CatalogIndexStoragePart part = new CatalogIndexStoragePart(
				1, new CatalogIndexKey(Scope.ARCHIVED), Set.of()
			);

			assertEquals(2L, part.getStoragePartPK());
		}

		@Test
		@DisplayName("should return scope-based PK from computeUniquePartIdAndSet")
		void shouldReturnScopeBasedPkFromComputeUniquePartIdAndSet() {
			final CatalogIndexStoragePart livePart = new CatalogIndexStoragePart(
				1, new CatalogIndexKey(Scope.LIVE), Set.of()
			);
			final CatalogIndexStoragePart archivedPart = new CatalogIndexStoragePart(
				1, new CatalogIndexKey(Scope.ARCHIVED), Set.of()
			);
			final KeyCompressor keyCompressor = mockKeyCompressor(0);

			assertEquals(1L, livePart.computeUniquePartIdAndSet(keyCompressor));
			assertEquals(2L, archivedPart.computeUniquePartIdAndSet(keyCompressor));
		}
	}
}
