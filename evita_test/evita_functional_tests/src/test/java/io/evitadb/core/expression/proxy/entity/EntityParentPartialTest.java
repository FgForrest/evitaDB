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

import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.expression.proxy.CatchAllPartial;
import io.evitadb.core.expression.proxy.EntityProxyState;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Optional;

import static io.evitadb.utils.ArrayUtils.EMPTY_CLASS_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_OBJECT_ARRAY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntityParentPartial} verifying that parent method implementations correctly delegate to the proxy
 * state.
 */
@SuppressWarnings("rawtypes")
@DisplayName("Entity parent partial")
class EntityParentPartialTest {

	/** Unique marker interface to avoid Proxycian classification cache pollution between test classes. */
	private interface ParentTestEntity extends EntityContract {}

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

		return ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(state, all),
			new Class<?>[]{ ParentTestEntity.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
	}

	@Test
	@DisplayName("parentAvailable() returns true")
	void shouldReturnTrueForParentAvailable() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Category");

		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state, EntityParentPartial.PARENT_AVAILABLE, EntityParentPartial.GET_PARENT_ENTITY
		);

		assertTrue(proxy.parentAvailable(), "parentAvailable() should return true");
	}

	@Test
	@DisplayName("getParentEntity() returns empty Optional")
	void shouldReturnEmptyOptionalForParentEntity() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Category");

		final EntityBodyStoragePart bodyPart = new EntityBodyStoragePart(10);
		bodyPart.setParent(5);

		final EntityProxyState state = new EntityProxyState(mockSchema, bodyPart, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state, EntityParentPartial.PARENT_AVAILABLE, EntityParentPartial.GET_PARENT_ENTITY
		);

		final Optional<EntityClassifierWithParent> parentEntity = proxy.getParentEntity();
		assertTrue(
			parentEntity.isEmpty(),
			"getParentEntity() should return empty Optional since expression evaluation doesn't need parent entity traversal"
		);
	}

	@Test
	@DisplayName("parentAvailable() returns true even when body part has no parent set")
	void shouldReturnTrueForParentAvailableWhenNoParentSet() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Category");

		final EntityBodyStoragePart bodyPart = new EntityBodyStoragePart(10);
		// parent is null by default

		final EntityProxyState state = new EntityProxyState(mockSchema, bodyPart, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state, EntityParentPartial.PARENT_AVAILABLE, EntityParentPartial.GET_PARENT_ENTITY
		);

		assertTrue(proxy.parentAvailable(), "parentAvailable() should return true");
		assertTrue(proxy.getParentEntity().isEmpty(), "getParentEntity() should return empty");
	}

	@Test
	@DisplayName("Should return parent PK when parent is present")
	void shouldReturnParentPkWhenPresent() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Category");

		final EntityBodyStoragePart bodyPart = new EntityBodyStoragePart(10);
		bodyPart.setParent(5);

		final EntityProxyState state = new EntityProxyState(mockSchema, bodyPart, null, null, null, null);

		// verify that the proxy state correctly exposes the parent PK through the body part
		final Integer parentPk = state.bodyPartOrThrowException().getParent();
		assertEquals(Integer.valueOf(5), parentPk, "Parent PK should be 5");

		// also verify the proxy still works correctly with parent-related partials
		final EntityContract proxy = createEntityProxy(
			state, EntityParentPartial.PARENT_AVAILABLE, EntityParentPartial.GET_PARENT_ENTITY
		);

		assertTrue(proxy.parentAvailable(), "parentAvailable() should return true");
		assertTrue(
			proxy.getParentEntity().isEmpty(),
			"getParentEntity() should return empty since expression proxy doesn't traverse hierarchy"
		);
	}

	@Test
	@DisplayName("Should return empty OptionalInt when parent is null")
	void shouldReturnEmptyWhenParentNull() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Category");

		final EntityBodyStoragePart bodyPart = new EntityBodyStoragePart(10);
		// parent is null by default — do not call setParent

		final EntityProxyState state = new EntityProxyState(mockSchema, bodyPart, null, null, null, null);

		// verify that the proxy state correctly reports null parent through the body part
		assertNull(state.bodyPartOrThrowException().getParent(), "Parent should be null when not set");

		// also verify the proxy still works correctly with parent-related partials
		final EntityContract proxy = createEntityProxy(
			state, EntityParentPartial.PARENT_AVAILABLE, EntityParentPartial.GET_PARENT_ENTITY
		);

		assertTrue(proxy.parentAvailable(), "parentAvailable() should return true even when parent is null");
		assertTrue(proxy.getParentEntity().isEmpty(), "getParentEntity() should return empty");
	}
}
