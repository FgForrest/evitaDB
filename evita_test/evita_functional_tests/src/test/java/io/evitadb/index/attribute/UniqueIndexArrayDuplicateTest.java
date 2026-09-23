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


package io.evitadb.index.attribute;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityTypeClassifierResolver;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the unique indexes against an array attribute that carries the SAME value twice.
 *
 * # The mechanism
 *
 * `unregisterUniqueKeyValue` verifies every element of the array first and only then mutates every element — but
 * the mutating call re-verifies from scratch. For a duplicated element the verify pass sees the registration
 * twice and passes twice; the mutate pass then retires it on the first element, so the second finds no owner and
 * fails. It is the same shape as the bucket-axis defect in {@link FilterIndexArrayFoldTest}: one array element
 * resolving a bucket that a sibling element of the same array still expects to find.
 *
 * # Why this needs a LITERAL duplicate
 *
 * The standalone unique tree carries no normalizer, so unlike the filter index it cannot be reached by two
 * distinct values folding onto one key — only by the same value appearing twice. That state is reachable:
 * registration explicitly tolerates re-registering a key to the record that already owns it, and nothing
 * upstream rejects a duplicated array element.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Unique indexes — an array carrying the same value twice")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class UniqueIndexArrayDuplicateTest {

	private static final String DUPLICATED = "A";
	private static final int RECORD = 5;

	private final Catalog catalog = Mockito.mock(Catalog.class);
	private final EntityTypeClassifierResolver classifierResolver = new EntityTypeClassifierResolver() {
		@Override
		public int toEntityTypePrimaryKey(@Nonnull String entityType) {
			return UniqueIndexArrayDuplicateTest.this.catalog
				.getCollectionForEntityOrThrowException(entityType).getEntityTypePrimaryKey();
		}

		@Nonnull
		@Override
		public String toEntityTypeName(int entityTypePrimaryKey) {
			return UniqueIndexArrayDuplicateTest.this.catalog
				.getCollectionForEntityPrimaryKeyOrThrowException(entityTypePrimaryKey).getEntityType();
		}
	};

	@BeforeEach
	void setUp() {
		final EntityCollection productCollection = Mockito.mock(EntityCollection.class);
		Mockito.when(productCollection.getEntityTypePrimaryKey()).thenReturn(1);
		Mockito.when(productCollection.getEntityType()).thenReturn(Entities.PRODUCT);
		Mockito.when(this.catalog.getCollectionForEntityPrimaryKeyOrThrowException(1)).thenReturn(productCollection);
		Mockito.when(this.catalog.getCollectionForEntityOrThrowException(Entities.PRODUCT)).thenReturn(productCollection);
	}

	@Test
	@DisplayName("registering an array that repeats one value is accepted — this is what makes the state reachable")
	void shouldRegisterAnArrayRepeatingOneValue() {
		final UniqueIndex index = ownerIndex();

		assertDoesNotThrow(
			() -> index.registerUniqueKey(new String[]{DUPLICATED, DUPLICATED}, RECORD),
			"registration refuses a key owned by ANOTHER record, never one already owned by this one, so the " +
				"duplicated element passes and the state this test exists for can exist"
		);
		assertTrue(index.getRecordIds().contains(RECORD));
	}

	@Test
	@DisplayName("unregistering that same array must retire the value once, not fail on the second element")
	void shouldUnregisterAnArrayRepeatingOneValue() {
		final UniqueIndex index = ownerIndex();
		index.registerUniqueKey(new String[]{DUPLICATED, DUPLICATED}, RECORD);

		assertDoesNotThrow(
			() -> index.unregisterUniqueKey(new String[]{DUPLICATED, DUPLICATED}, RECORD),
			"the first element retires the sole registration; the second must not then be asked to retire it again"
		);
		assertNull(index.getRecordIdByUniqueValue(DUPLICATED), "the value must be gone once");
	}

	@Test
	@DisplayName("an array repeating one value THREE times still retires it once")
	void shouldUnregisterAnArrayRepeatingOneValueThreeTimes() {
		// the shared helper compacts lazily, exactly like the filter fold; two occurrences exercise only the
		// first repeat, three exercise a repeat compared against an already-compacted prefix
		final UniqueIndex index = ownerIndex();
		index.registerUniqueKey(new String[]{DUPLICATED, DUPLICATED, DUPLICATED}, RECORD);

		assertDoesNotThrow(
			() -> index.unregisterUniqueKey(new String[]{DUPLICATED, DUPLICATED, DUPLICATED}, RECORD)
		);
		assertNull(index.getRecordIdByUniqueValue(DUPLICATED));
	}

	@Test
	@DisplayName("repeats INTERLEAVED with distinct values compact to the right survivors")
	void shouldUnregisterAnArrayOfInterleavedRepeats() {
		final UniqueIndex index = ownerIndex();
		final String[] mixed = {"A", "B", "A", "C", "B", "A"};
		index.registerUniqueKey(mixed, RECORD);

		assertDoesNotThrow(() -> index.unregisterUniqueKey(mixed, RECORD));
		assertNull(index.getRecordIdByUniqueValue("A"));
		assertNull(index.getRecordIdByUniqueValue("B"));
		assertNull(index.getRecordIdByUniqueValue("C"), "every distinct value leaves exactly once");
	}

	@Test
	@DisplayName("negative control — an array of distinct values unregisters cleanly")
	void shouldUnregisterAnArrayOfDistinctValues() {
		final UniqueIndex index = ownerIndex();
		index.registerUniqueKey(new String[]{"A", "B"}, RECORD);

		assertDoesNotThrow(() -> index.unregisterUniqueKey(new String[]{"A", "B"}, RECORD));
		assertNull(index.getRecordIdByUniqueValue("A"));
		assertNull(index.getRecordIdByUniqueValue("B"));
	}

	@Test
	@DisplayName("the globally-unique index carries the same defect and must be fixed with it")
	void shouldUnregisterARepeatedValueFromTheGlobalIndex() {
		final GlobalUniqueIndex index = new GlobalUniqueIndex(
			Scope.LIVE, new AttributeKey("whatever"), String.class
		);
		index.registerUniqueKey(
			new String[]{DUPLICATED, DUPLICATED}, Entities.PRODUCT, null, RECORD, this.classifierResolver
		);

		assertDoesNotThrow(
			() -> index.unregisterUniqueKey(
				new String[]{DUPLICATED, DUPLICATED}, Entities.PRODUCT, null, RECORD, this.classifierResolver
			),
			"same verify-all-then-mutate-all shape as the owner index, same remedy"
		);
	}

	@Nonnull
	private static UniqueIndex ownerIndex() {
		return new OwnerUniqueIndex(Entities.PRODUCT, new AttributeIndexKey(null, "code", null), String.class);
	}

}
