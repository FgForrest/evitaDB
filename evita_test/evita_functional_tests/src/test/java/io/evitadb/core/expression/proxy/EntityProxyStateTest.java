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

package io.evitadb.core.expression.proxy;

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntityProxyState} verifying that the record's accessor methods, or-throw-exception methods,
 * and the static `indexReferences` factory method behave correctly.
 */
@SuppressWarnings("SameParameterValue")
@DisplayName("Entity proxy state")
class EntityProxyStateTest {

	private static final int ENTITY_PK = 1;

	/**
	 * Creates a mock entity schema returning the given entity type name.
	 *
	 * @param entityType the entity type name
	 * @return mock entity schema
	 */
	@Nonnull
	private static EntitySchemaContract mockSchema(@Nonnull String entityType) {
		final EntitySchemaContract schema = mock(EntitySchemaContract.class);
		when(schema.getName()).thenReturn(entityType);
		return schema;
	}

	/**
	 * Creates a simple Reference with the given name and referenced entity PK using a mock schema.
	 *
	 * @param entitySchema      the entity schema owning the reference
	 * @param referenceName     the reference name
	 * @param referencedEntityPk the referenced entity primary key
	 * @param internalPk        the internal reference primary key
	 * @return a new Reference instance
	 */
	@Nonnull
	private static Reference createReference(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String referenceName,
		int referencedEntityPk,
		int internalPk
	) {
		final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
		when(refSchema.getName()).thenReturn(referenceName);
		when(refSchema.getAttributes()).thenReturn(Collections.emptyMap());
		final ReferenceKey refKey = new ReferenceKey(referenceName, referencedEntityPk, internalPk);
		return new Reference(entitySchema, refSchema, refKey, null);
	}

	/**
	 * Tests for {@link EntityProxyState#bodyPartOrThrowException()}.
	 */
	@Nested
	@DisplayName("Body part access")
	class BodyPartAccessTest {

		@Test
		@DisplayName("Should return body part when present")
		void shouldReturnBodyPartWhenPresent() {
			final EntitySchemaContract schema = mockSchema("Product");
			final EntityBodyStoragePart bodyPart = new EntityBodyStoragePart(ENTITY_PK);
			final EntityProxyState state = new EntityProxyState(
				schema, bodyPart, null, null, null, null
			);

			final EntityBodyStoragePart result = state.bodyPartOrThrowException();

			assertSame(bodyPart, result, "Should return the same body part instance");
		}

		@Test
		@DisplayName("Should throw ExpressionEvaluationException when body part is null")
		void shouldThrowExpressionEvaluationExceptionWhenBodyPartNull() {
			final EntitySchemaContract schema = mockSchema("Product");
			final EntityProxyState state = new EntityProxyState(
				schema, null, null, null, null, null
			);

			final ExpressionEvaluationException exception = assertThrows(
				ExpressionEvaluationException.class,
				state::bodyPartOrThrowException
			);
			assertTrue(
				exception.getPrivateMessage().contains("Product"),
				"Private message should contain the entity type name"
			);
		}
	}

	/**
	 * Tests for {@link EntityProxyState#referencesByNameOrThrowException()}.
	 */
	@Nested
	@DisplayName("References by name access")
	class ReferencesByNameAccessTest {

		@Test
		@DisplayName("Should return references map when present")
		void shouldReturnReferencesByNameWhenPresent() {
			final EntitySchemaContract schema = mockSchema("Product");
			final Map<String, List<ReferenceContract>> refsMap = Map.of(
				"brand", List.of()
			);
			final EntityProxyState state = new EntityProxyState(
				schema, null, null, null, null, refsMap
			);

			final Map<String, List<ReferenceContract>> result = state.referencesByNameOrThrowException();

			assertSame(refsMap, result, "Should return the same references map instance");
		}

		@Test
		@DisplayName("Should throw ExpressionEvaluationException when references map is null")
		void shouldThrowExpressionEvaluationExceptionWhenReferencesNull() {
			final EntitySchemaContract schema = mockSchema("Product");
			final EntityProxyState state = new EntityProxyState(
				schema, null, null, null, null, null
			);

			final ExpressionEvaluationException exception = assertThrows(
				ExpressionEvaluationException.class,
				state::referencesByNameOrThrowException
			);
			assertTrue(
				exception.getPrivateMessage().contains("Product"),
				"Private message should contain the entity type name"
			);
		}
	}

	/**
	 * Tests for {@link EntityProxyState#indexReferences(ReferencesStoragePart)}.
	 */
	@Nested
	@DisplayName("Index references")
	class IndexReferencesTest {

		@Test
		@DisplayName("Should return null for null input")
		void shouldReturnNullForNullInput() {
			final Map<String, List<ReferenceContract>> result = EntityProxyState.indexReferences(null);

			assertNull(result, "indexReferences(null) should return null");
		}

		@Test
		@DisplayName("Should return empty map for empty references array")
		void shouldReturnEmptyMapForEmptyReferencesArray() {
			final ReferencesStoragePart refsPart = new ReferencesStoragePart(ENTITY_PK);

			final Map<String, List<ReferenceContract>> result = EntityProxyState.indexReferences(refsPart);

			assertNotNull(result, "Result should not be null");
			assertTrue(result.isEmpty(), "Result should be an empty map");
		}

		@Test
		@DisplayName("Should skip dropped references")
		void shouldSkipDroppedReferences() {
			final EntitySchemaContract schema = mockSchema("Product");
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			when(refSchema.getName()).thenReturn("brand");
			when(refSchema.getAttributes()).thenReturn(Collections.emptyMap());

			// create a dropped reference using the constructor with dropped flag
			final ReferenceKey refKey = new ReferenceKey("brand", 10, 1);
			final Reference droppedRef = new Reference(
				schema, refSchema, 1, refKey, null,
				Collections.emptyList(), Collections.emptyMap(), true
			);

			final ReferencesStoragePart refsPart = new ReferencesStoragePart(
				ENTITY_PK, 1, new Reference[]{ droppedRef }, -1
			);

			final Map<String, List<ReferenceContract>> result = EntityProxyState.indexReferences(refsPart);

			assertNotNull(result, "Result should not be null");
			assertTrue(result.isEmpty(), "Result should be empty when all references are dropped");
		}

		@Test
		@DisplayName("Should group references by name")
		void shouldGroupReferencesByName() {
			final EntitySchemaContract schema = mockSchema("Product");
			final Reference brand1 = createReference(schema, "brand", 10, 1);
			final Reference brand2 = createReference(schema, "brand", 20, 2);
			final Reference category = createReference(schema, "category", 30, 3);

			final ReferencesStoragePart refsPart = new ReferencesStoragePart(
				ENTITY_PK, 3, new Reference[]{ brand1, brand2, category }, -1
			);

			final Map<String, List<ReferenceContract>> result = EntityProxyState.indexReferences(refsPart);

			assertNotNull(result, "Result should not be null");
			assertEquals(2, result.size(), "Should have 2 groups (brand and category)");
			assertEquals(2, result.get("brand").size(), "brand group should have 2 references");
			assertEquals(1, result.get("category").size(), "category group should have 1 reference");
		}
	}
}
