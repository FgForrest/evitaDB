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

package io.evitadb.core.expression.proxy.entity;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.expression.proxy.CatchAllPartial;
import io.evitadb.core.expression.proxy.EntityProxyState;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.evitadb.utils.ArrayUtils.EMPTY_CLASS_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_OBJECT_ARRAY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntityReferencesPartial} verifying that reference method implementations correctly delegate to the
 * proxy state and references storage part.
 */
@SuppressWarnings("rawtypes")
@DisplayName("Entity references partial")
class EntityReferencesPartialTest {

	/** Unique marker interface to avoid Proxycian classification cache pollution between test classes. */
	private interface ReferencesTestEntity extends EntityContract {}

	private static final int ENTITY_PK = 1;

	/**
	 * Creates an EntityContract proxy with the given partials plus the CatchAllPartial safety net.
	 */
	@Nonnull
	private static EntityContract createEntityProxy(
		@Nonnull EntityProxyState state,
		@Nonnull PredicateMethodClassification<?, ?, ?>... partials
	) {
		final PredicateMethodClassification[] all = new PredicateMethodClassification[partials.length + 2];
		System.arraycopy(partials, 0, all, 0, partials.length);
		all[partials.length] = CatchAllPartial.OBJECT_METHODS;
		all[partials.length + 1] = CatchAllPartial.INSTANCE;

		return (EntityContract) ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(state, all),
			new Class<?>[]{ ReferencesTestEntity.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
	}

	/**
	 * Creates a simple Reference with the given name and referenced entity PK using mock schema.
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

	@Test
	@DisplayName("getReferences(String) filters by name")
	void shouldFilterReferencesByName() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final Reference brandRef1 = createReference(mockSchema, "brand", 10, 1);
		final Reference brandRef2 = createReference(mockSchema, "brand", 20, 2);
		final Reference categoryRef = createReference(mockSchema, "category", 30, 3);

		// References must be sorted by ReferenceKey (name ASC, then PK ASC, then internal PK ASC)
		final Reference[] sortedRefs = new Reference[]{ brandRef1, brandRef2, categoryRef };
		final ReferencesStoragePart refPart = new ReferencesStoragePart(ENTITY_PK, 3, sortedRefs, -1);
		final Map<String, List<ReferenceContract>> refsByName = EntityProxyState.indexReferences(refPart);

		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, refsByName);

		final EntityContract proxy = createEntityProxy(
			state,
			EntityReferencesPartial.GET_REFERENCES_BY_NAME,
			EntityReferencesPartial.GET_REFERENCE,
			EntityReferencesPartial.GET_ALL_REFERENCES,
			EntityReferencesPartial.REFERENCES_AVAILABLE
		);

		final Collection<ReferenceContract> brandRefs = proxy.getReferences("brand");
		assertEquals(2, brandRefs.size(), "Should return 2 brand references");

		final Collection<ReferenceContract> categoryRefs = proxy.getReferences("category");
		assertEquals(1, categoryRefs.size(), "Should return 1 category reference");
	}

	@Test
	@DisplayName("getReferences(String) returns empty for missing name")
	void shouldReturnEmptyForMissingReferenceName() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final ReferencesStoragePart refPart = new ReferencesStoragePart(ENTITY_PK);
		final Map<String, List<ReferenceContract>> refsByName = EntityProxyState.indexReferences(refPart);

		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, refsByName);

		final EntityContract proxy = createEntityProxy(
			state, EntityReferencesPartial.GET_REFERENCES_BY_NAME, EntityReferencesPartial.REFERENCES_AVAILABLE
		);

		final Collection<ReferenceContract> refs = proxy.getReferences("nonExistent");
		assertTrue(refs.isEmpty(), "Should return empty collection");
	}

	@Test
	@DisplayName("getReference(String, int) finds by name and PK")
	void shouldFindReferenceByNameAndPrimaryKey() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final Reference brandRef = createReference(mockSchema, "brand", 10, 1);
		final ReferencesStoragePart refPart = new ReferencesStoragePart(
			ENTITY_PK, 1, new Reference[]{ brandRef }, -1
		);
		final Map<String, List<ReferenceContract>> refsByName = EntityProxyState.indexReferences(refPart);

		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, refsByName);

		final EntityContract proxy = createEntityProxy(
			state, EntityReferencesPartial.GET_REFERENCE, EntityReferencesPartial.REFERENCES_AVAILABLE
		);

		final Optional<ReferenceContract> found = proxy.getReference("brand", 10);
		assertTrue(found.isPresent(), "Should find the brand reference");
		assertEquals(10, found.get().getReferencedPrimaryKey(), "Referenced PK should be 10");

		final Optional<ReferenceContract> notFound = proxy.getReference("brand", 999);
		assertTrue(notFound.isEmpty(), "Should not find a non-existent reference");
	}

	@Test
	@DisplayName("referencesAvailable() returns true")
	void shouldReturnTrueForReferencesAvailable() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, null);

		final EntityContract proxy = createEntityProxy(state, EntityReferencesPartial.REFERENCES_AVAILABLE);

		assertTrue(proxy.referencesAvailable(), "referencesAvailable() should return true");
	}

	@Test
	@DisplayName("getReferences(String) returns empty collection when referencesByName is null")
	void shouldReturnEmptyCollectionWhenReferencesByNameIsNull() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		// referencesByName is null in the state
		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state,
			EntityReferencesPartial.GET_REFERENCES_BY_NAME,
			EntityReferencesPartial.REFERENCES_AVAILABLE
		);

		final Collection<ReferenceContract> refs = proxy.getReferences("brand");
		assertNotNull(refs, "Should return non-null collection");
		assertTrue(refs.isEmpty(), "Should return empty collection when referencesByName is null");
	}
}
