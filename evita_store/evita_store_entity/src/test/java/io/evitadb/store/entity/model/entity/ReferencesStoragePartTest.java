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

package io.evitadb.store.entity.model.entity;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.data.structure.ReferenceAttributes;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReferencesStoragePart focusing on sorted order of references and key operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ReferencesStoragePart behavioral tests")
class ReferencesStoragePartTest {
	public static final String EXAMPLE_ENTITY_TYPE = "someType";

	@Nonnull
	private static EntitySchemaContract mockEntitySchema() {
		final EntitySchemaContract schema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(schema.getReference(ArgumentMatchers.anyString())).thenReturn(Optional.empty());
		return schema;
	}

	@Nonnull
	private static Reference newRef(
		@Nonnull String refName,
		int pk,
		int internalPk,
		@Nullable GroupEntityReference group,
		boolean dropped
	) {
		final EntitySchemaContract schema = mockEntitySchema();
		return new Reference(
			schema,
			ReferencesBuilder.createImplicitSchema(
				mockEntitySchema(), refName, EXAMPLE_ENTITY_TYPE, Cardinality.ZERO_OR_MORE, group
			),
			1,
			new ReferenceKey(refName, pk, internalPk),
			group,
			dropped
		);
	}

	@Nonnull
	private static GroupEntityReference group(int pk) {
		return new GroupEntityReference("group", pk, 1, false);
	}

	@Test
	@DisplayName("should assign ids and maintain sorted order for new references with negative keys")
	void shouldAssignIdsAndMaintainSortedOrderWhenInsertingNewReferencesWithNegativeOrZeroKeys() {
		final ReferencesStoragePart part = new ReferencesStoragePart(1);

		part.replaceOrAddReference(
			new ReferenceKey("A", 10, -5),
			existing -> newRef("A", 10, -5, null, false)
		);
		part.replaceOrAddReference(
			new ReferenceKey("A", 20, -22),
			existing -> newRef("A", 20, -22, null, false)
		);
		part.replaceOrAddReference(
			new ReferenceKey("B", 1, -1),
			existing -> newRef("B", 1, -1, null, false)
		);
		// replace an existing negative key with new content
		part.replaceOrAddReference(
			new ReferenceKey("A", 10, -5),
			existing -> newRef(
				"A", 10, -5,
				new GroupEntityReference("group", 1, 1, false),
				false
			)
		);

		// order must be by referenceName then primary key
		final ReferenceContract[] arrBefore = part.getReferencesAsCollection().toArray(new ReferenceContract[0]);
		assertEquals("A", arrBefore[0].getReferenceName());
		assertEquals(10, arrBefore[0].getReferencedPrimaryKey());
		assertEquals("A", arrBefore[1].getReferenceName());
		assertEquals(20, arrBefore[1].getReferencedPrimaryKey());
		assertEquals("B", arrBefore[2].getReferenceName());

		// verify group was set in replacement
		final GroupEntityReference group = arrBefore[0].getGroup().orElse(null);
		assertNotNull(group);
		assertEquals(1, group.primaryKey());
		assertEquals("group", group.getType());

		// Assign ids
		part.assignMissingIdsAndSort();

		final ReferenceContract[] arr = part.getReferencesAsCollection().toArray(new ReferenceContract[0]);
		for (int i = 0; i < arr.length; i++) {
			final ReferenceContract ref = arr[i];
			assertTrue(ref.getReferenceKey().isKnownInternalPrimaryKey());
			if (i > 0) {
				assertTrue(arr[i - 1].getReferenceKey().compareTo(ref.getReferenceKey()) < 0);
			}
		}
	}

	@Test
	@DisplayName("should replace existing reference when internal key known and keep order")
	void shouldReplaceExistingReferenceWhenInternalKeyKnownAndMaintainOrder() {
		final ReferencesStoragePart part = new ReferencesStoragePart(
			2, 10,
			new Reference[]{
				newRef("B", 1, 1, null, false),
				newRef("D", 1, 4, null, false),
				newRef("D", 2, 6, null, false),
				newRef("E", 1, 7, null, false)
			},
			128
		);
		final ReferenceContract r1 = part.replaceOrAddReference(
			new ReferenceKey("A", 10, -1),
			existing -> newRef("A", 10, -1, null, false)
		);
		final ReferenceContract r2 = part.replaceOrAddReference(
			new ReferenceKey("D", 10, -1),
			existing -> newRef("D", 10, -1, null, false)
		);
		// assign id to the existing record
		part.assignMissingIdsAndSort();

		// now we know the internal id - use it to replace the record
		final int r1AssignedId = part.findReferenceOrThrowException(r1.getReferenceKey())
		                             .getReferenceKey()
		                             .internalPrimaryKey();
		final int r2AssignedId = part.findReferenceOrThrowException(r2.getReferenceKey())
		                             .getReferenceKey()
		                             .internalPrimaryKey();

		// verify the last assigned primary key
		assertEquals(12, part.getLastUsedPrimaryKey());
		assertEquals(11, r1AssignedId);
		assertEquals(12, r2AssignedId);

		// replace the record with a known internal primary key
		assertEquals(
			r1AssignedId,
			part.replaceOrAddReference(
				    new ReferenceKey("A", 10, r1AssignedId),
				    existing -> newRef("A", 10, r1AssignedId, group(7), false)
			    )
			    .getReferenceKey()
			    .internalPrimaryKey()
		);
		assertEquals(
			r2AssignedId,
			part.replaceOrAddReference(
				    new ReferenceKey("D", 10, r2AssignedId),
				    existing -> newRef("D", 10, r2AssignedId, group(9), false)
			    )
			    .getReferenceKey()
			    .internalPrimaryKey()
		);

		// verify the order is still kept
		final ReferenceContract[] arr = part.getReferencesAsCollection().toArray(new ReferenceContract[0]);
		for (int i = 1; i < arr.length; i++) {
			assertTrue(arr[i - 1].getReferenceKey().compareTo(arr[i].getReferenceKey()) < 0);
		}
	}

	@Test
	@DisplayName("should allow zero-key replacement when unique and throw when duplicates exist")
	void shouldReplaceWithZeroKeyWhenSingleBusinessKeyExistsAndThrowWhenDuplicateExists() {
		final ReferencesStoragePart part = new ReferencesStoragePart(
			2, 10,
			new Reference[]{
				newRef("B", 1, 1, null, false),
				newRef("D", 1, 4, null, false),
				newRef("D", 1, 6, null, false),
				newRef("E", 1, 7, null, false)
			},
			128
		);

		// this is OK - a single business key exists
		part.replaceOrAddReference(
			new ReferenceKey("B", 1, 0),
			existing -> newRef("B", 1, existing.getReferenceKey().internalPrimaryKey(), group(7), false)
		);

		// sanity check - internal key must remain the same
		assertThrows(
			GenericEvitaInternalError.class,
			() -> part.replaceOrAddReference(
				new ReferenceKey("B", 1, 0),
				existing -> newRef("B", 1, 7777, group(7), false)
			)
		);

		assertEquals(
			7,
			part.findReferenceOrThrowException(new ReferenceKey("B", 1, 0))
			       .getGroup()
			       .orElseThrow()
			       .primaryKey()
		);

		// this fails - multiple business keys exist
		assertThrows(
			GenericEvitaInternalError.class,
			() -> part.replaceOrAddReference(
				new ReferenceKey("D", 1, 0),
				existing -> newRef("D", 1, existing.getReferenceKey().internalPrimaryKey(), group(7), false)
			)
		);

		// this is OK - adding new reference D but with a different primary key
		part.replaceOrAddReference(
			new ReferenceKey("D", 4, 0),
			existing -> newRef("D", 4, -10, group(7), false)
		);

		// this is OK - replacing the first reference
		part.replaceOrAddReference(
			new ReferenceKey("B", 1, 0),
			existing -> newRef("B", 1, existing.getReferenceKey().internalPrimaryKey(), group(7), false)
		);

		// this is OK - replacing the last reference
		part.replaceOrAddReference(
			new ReferenceKey("E", 1, 0),
			existing -> newRef("E", 1, existing.getReferenceKey().internalPrimaryKey(), group(7), false)
		);

		// this is OK - adding new reference with zero key
		part.replaceOrAddReference(
			new ReferenceKey("A", 1, 0),
			existing -> newRef("A", 1, -15, group(7), false)
		);
	}

	@Test
	@DisplayName("should support contains for known and unknown keys")
	void shouldContainsWorkForKnownAndUnknownKeys() {
		final ReferencesStoragePart part = new ReferencesStoragePart(
			2, 10,
			new Reference[]{
				newRef("B", 1, 1, null, false),
				newRef("D", 1, 4, null, false),
				newRef("D", 1, 6, null, false),
				newRef("E", 1, 7, null, false)
			},
			128
		);

		assertTrue(part.contains(new ReferenceKey("B", 1, 1)));
		assertTrue(part.contains(new ReferenceKey("D", 1, 4)));
		assertTrue(part.contains(new ReferenceKey("D", 1, 6)));
		assertTrue(part.contains(new ReferenceKey("E", 1, 7)));
		assertTrue(part.contains(new ReferenceKey("B", 1, 0)));
		assertTrue(part.contains(new ReferenceKey("D", 1, 0)));
		assertTrue(part.contains(new ReferenceKey("E", 1, 0)));

		assertFalse(part.contains(new ReferenceKey("A", 1, 0)));
		assertFalse(part.contains(new ReferenceKey("B", 2, 0)));
		assertFalse(part.contains(new ReferenceKey("B", 1, 456)));
	}

	@Test
	@DisplayName("should support find for known and unknown keys")
	void shouldFindReferenceWorkForKnownAndUnknownKeys() {
		final ReferencesStoragePart part = new ReferencesStoragePart(
			2, 10,
			new Reference[]{
				newRef("B", 1, 1, null, false),
				newRef("D", 1, 4, null, false),
				newRef("D", 1, 6, null, false),
				newRef("E", 1, 7, null, false)
			},
			128
		);

		assertEquals(1, part.findReferenceOrThrowException(new ReferenceKey("B", 1, 1)).getReferenceKey().internalPrimaryKey());
		assertEquals(4, part.findReferenceOrThrowException(new ReferenceKey("D", 1, 4)).getReferenceKey().internalPrimaryKey());
		assertEquals(6, part.findReferenceOrThrowException(new ReferenceKey("D", 1, 6)).getReferenceKey().internalPrimaryKey());
		assertEquals(7, part.findReferenceOrThrowException(new ReferenceKey("E", 1, 7)).getReferenceKey().internalPrimaryKey());

		assertEquals(1, part.findReferenceOrThrowException(new ReferenceKey("B", 1, 0)).getReferenceKey().internalPrimaryKey());
		assertThrows(GenericEvitaInternalError.class, () -> part.findReferenceOrThrowException(new ReferenceKey("D", 1, 0)));
		assertEquals(7, part.findReferenceOrThrowException(new ReferenceKey("E", 1, 0)).getReferenceKey().internalPrimaryKey());

		assertThrows(GenericEvitaInternalError.class, () -> part.findReferenceOrThrowException(new ReferenceKey("A", 1, 0)));
		assertThrows(GenericEvitaInternalError.class, () -> part.findReferenceOrThrowException(new ReferenceKey("B", 2, 0)));
		assertThrows(GenericEvitaInternalError.class, () -> part.findReferenceOrThrowException(new ReferenceKey("B", 1, 456)));
	}

	@Test
	@DisplayName("should return referenced ids, distinct ids and group ids and ignore dropped")
	void shouldReturnReferencedIdsAndDistinctIdsAndGroupIdsAndIgnoreDropped() {
		final ReferencesStoragePart part = new ReferencesStoragePart(
			6, 0, new Reference[]{
			newRef("A", 1, 10, group(1), false),
			newRef("A", 1, 11, group(1), true), // dropped, ignored
			newRef("A", 2, 12, group(2), false),
			newRef("B", 1, 13, group(2), false),
			newRef("B", 1, 14, group(3), false)
		}, -1
		);

		assertArrayEquals(new int[]{1, 2}, part.getReferencedIds("A"));
		assertArrayEquals(new int[]{1, 2}, part.getDistinctReferencedIds("A"));
		assertArrayEquals(new int[]{1, 2}, part.getDistinctReferencedGroupIds("A"));
		assertArrayEquals(new int[]{1, 1}, part.getReferencedIds("B"));
		assertArrayEquals(new int[]{1}, part.getDistinctReferencedIds("B"));
		assertArrayEquals(new int[]{2, 3}, part.getDistinctReferencedGroupIds("B"));
	}

	@Test
	@DisplayName("should report locale presence across references and handle empty container")
	void shouldReportLocalePresenceFromReferenceAttributesAndHandleEmptyContainer() {
		final ReferencesStoragePart empty = new ReferencesStoragePart(7);
		assertFalse(empty.isLocalePresent(Locale.ENGLISH));

		final Reference rEn = new Reference(
			ReferencesBuilder.createImplicitSchema(
				mockEntitySchema(), "A", EXAMPLE_ENTITY_TYPE, Cardinality.ZERO_OR_MORE, null
			),
			1,
			new ReferenceKey("A", 1, 1),
			null,
			new ReferenceAttributes(
				mockEntitySchema(),
				Mockito.mock(ReferenceSchemaContract.class),
				List.of(
					new AttributeValue(
						1,
						new AttributeKey("code", Locale.ENGLISH),
						"some value"
					),
					new AttributeValue(
						1,
						new AttributeKey("code", Locale.GERMAN),
						"some value"
					)
				),
				Map.of(
					"code", Mockito.mock(AttributeSchemaContract.class)
				)
			),
			false
		);

		final ReferencesStoragePart part = new ReferencesStoragePart(8, 1, new Reference[]{rEn}, -1);
		assertTrue(part.isLocalePresent(Locale.ENGLISH));
		assertTrue(part.isLocalePresent(Locale.GERMAN));
		assertFalse(part.isLocalePresent(Locale.FRENCH));
	}

	@Test
	@DisplayName("should throw on assignMissingIds when array is not sorted by business key")
	void shouldThrowWhenAssigningIdsOnUnsortedArray() {
		final Reference b = newRef("B", 1, -1, null, false);
		final Reference a = newRef("A", 1, -2, null, false);
		final ReferencesStoragePart part = new ReferencesStoragePart(9, 0, new Reference[]{b, a}, -1);
		part.replaceOrAddReference(new ReferenceKey("D", 1, -2), existing -> newRef("D", 1, -2, null, false));
		assertThrows(RuntimeException.class, part::assignMissingIdsAndSort);
	}

	@Test
	@DisplayName("should be empty and return no ids when all references are dropped")
	void shouldBeEmptyAndReturnNoIdsWhenAllReferencesAreDropped() {
		final ReferencesStoragePart part = new ReferencesStoragePart(
			10, 0, new Reference[]{
				newRef("A", 1, 100, group(1), true),
				newRef("A", 2, 101, group(2), true),
				newRef("B", 3, 102, group(3), true)
			}, -1
		);

		// isEmpty must report true when only dropped references are present
		assertTrue(part.isEmpty());

		// all getters must ignore dropped references and return empty arrays
		assertArrayEquals(new int[0], part.getReferencedIds("A"));
		assertArrayEquals(new int[0], part.getReferencedIds("B"));
		assertArrayEquals(new int[0], part.getDistinctReferencedIds("A"));
		assertArrayEquals(new int[0], part.getDistinctReferencedIds("B"));
		assertArrayEquals(new int[0], part.getDistinctReferencedGroupIds("A"));
		assertArrayEquals(new int[0], part.getDistinctReferencedGroupIds("B"));
	}
}