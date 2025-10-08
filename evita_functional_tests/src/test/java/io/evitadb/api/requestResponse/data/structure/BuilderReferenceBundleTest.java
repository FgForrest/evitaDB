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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BuilderReferenceBundle behavior")
class BuilderReferenceBundleTest {

	@Nonnull
	private static Map<String, AttributeSchemaContract> representativeSchema() {
		final Map<String, AttributeSchemaContract> attrs = new HashMap<>();
		// representative attribute
		attrs.put(
			"country",
			AttributeSchema._internalBuild(
				"country",
				new io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType[0],
				Scope.NO_SCOPE,
				Scope.NO_SCOPE,
				false,
				true,
				true,
				String.class,
				null
			)
		);
		// non-representative noise attribute
		attrs.put(
			"note",
			AttributeSchema._internalBuild(
				"note",
				new io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType[0],
				Scope.NO_SCOPE,
				Scope.NO_SCOPE,
				false,
				true,
				false,
				String.class,
				null
			)
		);
		return Collections.unmodifiableMap(attrs);
	}

	@Nonnull
	private static ReferenceContract mockReference(
		int internalPk,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Map<String, Serializable> attributeValues
	) {
		final ReferenceContract reference = Mockito.mock(ReferenceContract.class);
		final ReferenceKey key = new ReferenceKey("brand", 1, internalPk);
		Mockito.when(reference.getReferenceKey()).thenReturn(key);
		Mockito.when(reference.getReferenceName()).thenReturn("brand");
		Mockito.when(reference.getReferenceSchema()).thenReturn(Optional.of(referenceSchema));
		for (final Map.Entry<String, Serializable> entry : attributeValues.entrySet()) {
			final AttributesContract.AttributeKey attributeKey = new AttributesContract.AttributeKey(entry.getKey());
			final AttributesContract.AttributeValue attributeValue = new AttributesContract.AttributeValue(
				attributeKey,
				entry.getValue()
			);
			Mockito.when(reference.getAttributeValue(entry.getKey()))
				.thenReturn(Optional.of(attributeValue));
		}
		// missing attributes return empty
		Mockito.when(reference.getAttributeValue(ArgumentMatchers.anyString())).thenAnswer(invocation -> {
			final String name = invocation.getArgument(0);
			if (attributeValues.containsKey(name)) {
				final AttributesContract.AttributeKey attributeKey = new AttributesContract.AttributeKey(name);
				final AttributesContract.AttributeValue attributeValue = new AttributesContract.AttributeValue(
					attributeKey,
					attributeValues.get(name)
				);
				return Optional.of(attributeValue);
			}
			return Optional.empty();
		});
		return reference;
	}

	@Test
	@DisplayName("shouldAddNonDuplicateReferenceAndCountWhenSingleReference")
	void shouldUpsertNonDuplicateReferenceAndCountWhenSingleReference() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(2);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());
		final ReferenceContract ref = mockReference(
			1001,
			referenceSchema,
			Collections.singletonMap("country", "CZ")
		);
		bundle.upsertNonDuplicateReference(ref);
		assertEquals(1, bundle.count());
	}

	@Test
	@DisplayName("shouldThrowWhenAddingDuplicateNonDuplicateReference")
	void shouldThrowWhenAddingDuplicateNonDuplicateReference() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(2);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(Collections.emptyMap());
		final ReferenceContract ref1 = mockReference(
			1001,
			referenceSchema,
			Collections.emptyMap()
		);
		final ReferenceContract ref2 = mockReference(
			2002,
			referenceSchema,
			Collections.emptyMap()
		);
		bundle.upsertNonDuplicateReference(ref1);
		assertThrows(GenericEvitaInternalError.class, () -> bundle.upsertNonDuplicateReference(ref2));
	}

	@Test
	@DisplayName("shouldConvertToDuplicateReferenceAndAllowTwoWithDifferentRepAttrs")
	void shouldConvertToDuplicateReferenceAndAllowTwoWithDifferentRepAttrs() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(2);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());

		final ReferenceContract prev = mockReference(
			1001,
			referenceSchema,
			Collections.singletonMap("country", "CZ")
		);
		final ReferenceContract next = mockReference(
			2002,
			referenceSchema,
			Collections.singletonMap("country", "US")
		);

		bundle.upsertNonDuplicateReference(prev);
		bundle.convertToDuplicateReference(next, prev);
		assertEquals(2, bundle.count());
	}

	@Test
	@DisplayName("shouldThrowWhenDuplicateWithSameRepAttrsHasDifferentPk")
	void shouldThrowWhenDuplicateWithSameRepAttrsHasDifferentPk() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(3);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());

		final ReferenceContract prev = mockReference(1001, referenceSchema, Collections.singletonMap("country", "US"));
		final ReferenceContract dupl = mockReference(2002, referenceSchema, Collections.singletonMap("country", "US"));
		final ReferenceContract inv = mockReference(2003, referenceSchema, Collections.singletonMap("country", "US"));

		bundle.upsertNonDuplicateReference(prev);
		assertThrows(InvalidMutationException.class, () -> bundle.convertToDuplicateReference(dupl, inv));
	}

	@Test
	@DisplayName("shouldRemoveNonDuplicateReference")
	void shouldRemoveNonDuplicateReference() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(1);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(Collections.emptyMap());
		final ReferenceContract ref = mockReference(1001, referenceSchema, Collections.emptyMap());
		bundle.upsertNonDuplicateReference(ref);
		bundle.removeNonDuplicateReference(ref);
		assertEquals(0, bundle.count());
	}

	@Test
	@DisplayName("shouldRemoveDuplicateReference")
	void shouldRemoveDuplicateReference() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(2);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());

		final ReferenceContract prev = mockReference(1001, referenceSchema, Collections.singletonMap("country", "CZ"));
		final ReferenceContract next = mockReference(2002, referenceSchema, Collections.singletonMap("country", "US"));

		bundle.upsertNonDuplicateReference(prev);
		bundle.convertToDuplicateReference(next, prev);
		bundle.removeDuplicateReference(next);
		assertEquals(1, bundle.count());
	}

	@Test
	@DisplayName("shouldDiscardDuplicatesAndResetState")
	void shouldDiscardDuplicatesAndResetState() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(2);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());

		final ReferenceContract prev = mockReference(1001, referenceSchema, Collections.singletonMap("country", "CZ"));
		final ReferenceContract next = mockReference(2002, referenceSchema, Collections.singletonMap("country", "US"));

		bundle.upsertNonDuplicateReference(prev);
		bundle.convertToDuplicateReference(next, prev);
		// remove one duplicate so that only single mapping remains
		bundle.removeDuplicateReference(next);
		assertEquals(1, bundle.count());

		// now discard duplicates and return to non-duplicate state
		bundle.discardDuplicates(prev.getReferenceKey());
		assertEquals(1, bundle.count());

		// verify we can remove the last reference in non-duplicate mode
		bundle.removeNonDuplicateReference(prev);
		assertEquals(0, bundle.count());
	}

	@Test
	@DisplayName("shouldThrowWhenAddingDuplicateInNonDuplicateMode")
	void shouldThrowWhenAddingDuplicateInNonDuplicateMode() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(1);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());
		final ReferenceContract ref = mockReference(1001, referenceSchema, Collections.singletonMap("country", "CZ"));
		assertThrows(GenericEvitaInternalError.class, () -> bundle.upsertDuplicateReference(ref));
	}

	@Test
	@DisplayName("shouldThrowOnRemoveNonDuplicateWhenNotPresent")
	void shouldThrowOnRemoveNonDuplicateWhenNotPresent() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(1);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(Collections.emptyMap());
		final ReferenceContract ref = mockReference(1001, referenceSchema, Collections.emptyMap());
		assertThrows(GenericEvitaInternalError.class, () -> bundle.removeNonDuplicateReference(ref));
	}

	@Test
	@DisplayName("shouldThrowOnRemoveDuplicateWhenNotPresent")
	void shouldThrowOnRemoveDuplicateWhenNotPresent() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(1);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());
		final ReferenceContract ref = mockReference(1001, referenceSchema, Collections.singletonMap("country", "CZ"));
		// activate duplicate mode with previous different PK and immediately discard mapping
		final ReferenceContract prev = mockReference(2002, referenceSchema, Collections.singletonMap("country", "US"));
		bundle.upsertNonDuplicateReference(prev);
		bundle.convertToDuplicateReference(ref, prev);
		bundle.removeDuplicateReference(prev);
		assertThrows(GenericEvitaInternalError.class, () -> bundle.removeDuplicateReference(prev));
	}

	@Test
	@DisplayName("shouldReturnTrueForIsDuplicateWhenInDuplicateMode")
	void shouldReturnTrueForIsDuplicateWhenInDuplicateMode() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(2);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());

		final ReferenceContract prev = mockReference(1001, referenceSchema, Collections.singletonMap("country", "CZ"));
		final ReferenceContract next = mockReference(2002, referenceSchema, Collections.singletonMap("country", "US"));

		bundle.upsertNonDuplicateReference(prev);
		bundle.convertToDuplicateReference(next, prev);

		assertTrue(bundle.isDuplicate(prev.getReferenceKey()));
		assertTrue(bundle.isDuplicate(next.getReferenceKey()));
	}

	@Test
	@DisplayName("shouldReturnFalseForIsDuplicateWhenNotInDuplicateMode")
	void shouldReturnFalseForIsDuplicateWhenNotInDuplicateMode() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(1);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());
		final ReferenceContract ref = mockReference(1001, referenceSchema, Collections.singletonMap("country", "CZ"));

		bundle.upsertNonDuplicateReference(ref);
		assertFalse(bundle.isDuplicate(ref.getReferenceKey()));
	}

	@Test
	@DisplayName("shouldMarkAsNonDuplicateAfterDiscardingDuplicates")
	void shouldMarkAsNonDuplicateAfterDiscardingDuplicates() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(2);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());

		final ReferenceContract prev = mockReference(1001, referenceSchema, Collections.singletonMap("country", "CZ"));
		final ReferenceContract next = mockReference(2002, referenceSchema, Collections.singletonMap("country", "US"));

		bundle.upsertNonDuplicateReference(prev);
		bundle.convertToDuplicateReference(next, prev);

		// discard duplicates for prev
		bundle.discardDuplicates(prev.getReferenceKey());
		assertFalse(bundle.isDuplicate(prev.getReferenceKey()));
	}

	@Test
	@DisplayName("shouldInitializeBundleOnlyOnce")
	void shouldInitializeBundleOnlyOnce() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(1);
		final int[] counter = new int[] { 0 };
		bundle.initializeBundleIfNecessary(b -> counter[0]++);
		bundle.initializeBundleIfNecessary(b -> counter[0]++);
		assertEquals(1, counter[0]);
	}

	@Test
	@DisplayName("shouldInsertNonDuplicateWhenNoGenericMapping")
	void shouldInsertNonDuplicateWhenNoGenericMapping() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(1);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());
		final ReferenceContract ref = mockReference(1001, referenceSchema, Collections.singletonMap("country", "CZ"));
		bundle.upsertWithDuplicateReferenceConversion(ref, key -> null);
		assertEquals(1, bundle.count());
		assertFalse(bundle.isDuplicate(ref.getReferenceKey()));
	}

	@Test
	@DisplayName("shouldConvertAndUpsertWhenGenericKeyBelongsToDifferentPk")
	void shouldConvertAndUpsertWhenGenericKeyBelongsToDifferentPk() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(2);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());

		final ReferenceContract prev = mockReference(1001, referenceSchema, Collections.singletonMap("country", "CZ"));
		final ReferenceContract next = mockReference(2002, referenceSchema, Collections.singletonMap("country", "US"));

		bundle.upsertNonDuplicateReference(prev);
		bundle.upsertWithDuplicateReferenceConversion(next, key -> prev);

		// two representative keys
		assertEquals(2, bundle.count());
		assertTrue(bundle.isDuplicate(prev.getReferenceKey()));
		assertTrue(bundle.isDuplicate(next.getReferenceKey()));
	}

	@Test
	@DisplayName("shouldUpsertDuplicateWhenGenericKeyMarkedZero")
	void shouldUpsertDuplicateWhenGenericKeyMarkedZero() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(3);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());

		final ReferenceContract prev = mockReference(1001, referenceSchema, Collections.singletonMap("country", "CZ"));
		final ReferenceContract next = mockReference(2002, referenceSchema, Collections.singletonMap("country", "US"));
		final ReferenceContract third = mockReference(3003, referenceSchema, Collections.singletonMap("country", "DE"));

		bundle.upsertNonDuplicateReference(prev);
		bundle.upsertWithDuplicateReferenceConversion(next, key -> prev);
		bundle.upsertWithDuplicateReferenceConversion(third, key -> prev);

		// three representative keys
		assertEquals(3, bundle.count());
	}

	@Test
	@DisplayName("shouldUpdateExistingDuplicateWhenInternalPkAlreadyPresent")
	void shouldUpdateExistingDuplicateWhenInternalPkAlreadyPresent() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(2);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());

		final ReferenceContract prev = mockReference(1001, referenceSchema, Collections.singletonMap("country", "CZ"));
		final ReferenceContract next = mockReference(2002, referenceSchema, Collections.singletonMap("country", "US"));
		final ReferenceContract nextUpdated = mockReference(2002, referenceSchema, Collections.singletonMap("country", "CA"));

		bundle.upsertNonDuplicateReference(prev);
		bundle.upsertWithDuplicateReferenceConversion(next, key -> prev);
		final int before = bundle.count();
		bundle.upsertWithDuplicateReferenceConversion(nextUpdated, key -> prev);
		assertEquals(before, bundle.count());
		assertTrue(bundle.isDuplicate(nextUpdated.getReferenceKey()));
	}

	@Test
	@DisplayName("shouldDoNothingWhenGenericMappingMatchesSamePk")
	void shouldDoNothingWhenGenericMappingMatchesSamePk() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(1);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());

		final ReferenceContract ref = mockReference(1001, referenceSchema, Collections.singletonMap("country", "CZ"));
		bundle.upsertNonDuplicateReference(ref);
		bundle.upsertWithDuplicateReferenceConversion(ref, key -> null);
		assertEquals(1, bundle.count());
	}

	@Test
	@DisplayName("shouldThrowWhenDuplicateAttrsCollideInUpsertWithConversion")
	void shouldThrowWhenDuplicateAttrsCollideInUpsertWithConversion() {
		final BuilderReferenceBundle bundle = new BuilderReferenceBundle(3);
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchemaContract.class);
		Mockito.when(referenceSchema.getAttributes()).thenReturn(representativeSchema());

		final ReferenceContract prev = mockReference(1001, referenceSchema, Collections.singletonMap("country", "CZ"));
		final ReferenceContract dupl = mockReference(2002, referenceSchema, Collections.singletonMap("country", "US"));
		final ReferenceContract colliding = mockReference(3003, referenceSchema, Collections.singletonMap("country", "CZ"));

		bundle.upsertNonDuplicateReference(prev);
		bundle.upsertWithDuplicateReferenceConversion(dupl, key -> prev);
		assertThrows(InvalidMutationException.class, () -> bundle.upsertWithDuplicateReferenceConversion(colliding, key -> prev));
	}
}
