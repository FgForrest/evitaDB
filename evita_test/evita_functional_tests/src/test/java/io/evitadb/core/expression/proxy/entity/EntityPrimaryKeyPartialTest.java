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
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.expression.proxy.CatchAllPartial;
import io.evitadb.core.expression.proxy.EntityProxyState;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.utils.ArrayUtils.EMPTY_CLASS_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_OBJECT_ARRAY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntityPrimaryKeyPartial} verifying that the primary key method implementation correctly delegates to
 * the entity body storage part.
 */
@SuppressWarnings("rawtypes")
@DisplayName("Entity primary key partial")
class EntityPrimaryKeyPartialTest {

	/** Unique marker interface to avoid Proxycian classification cache pollution between test classes. */
	private interface PrimaryKeyTestEntity extends EntityContract {}

	/**
	 * Creates an EntityContract proxy with the given partials plus the CatchAllPartial safety net.
	 *
	 * @param state    the proxy state record
	 * @param partials specific partials to include
	 * @return entity contract proxy
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
			new Class<?>[]{ PrimaryKeyTestEntity.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
	}

	@Test
	@DisplayName("getPrimaryKey() returns value from body storage part")
	void shouldReturnPrimaryKeyFromBodyStoragePart() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		final EntityBodyStoragePart bodyPart = new EntityBodyStoragePart(42);
		final EntityProxyState state = new EntityProxyState(mockSchema, bodyPart, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state,
			EntitySchemaPartial.GET_SCHEMA,
			EntitySchemaPartial.GET_TYPE,
			EntityPrimaryKeyPartial.GET_PRIMARY_KEY
		);

		assertEquals(42, proxy.getPrimaryKey(), "getPrimaryKey() should return 42");
	}

	@Test
	@DisplayName("getPrimaryKey() works with boundary values")
	void shouldReturnCorrectPrimaryKeyWithBoundaryValues() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		// Test with PK=0
		final EntityBodyStoragePart bodyPartZero = new EntityBodyStoragePart(0);
		final EntityProxyState stateZero = new EntityProxyState(mockSchema, bodyPartZero, null, null, null, null);
		final EntityContract proxyZero = createEntityProxy(stateZero, EntityPrimaryKeyPartial.GET_PRIMARY_KEY);
		assertEquals(0, proxyZero.getPrimaryKey(), "getPrimaryKey() should return 0");

		// Test with PK=Integer.MAX_VALUE
		final EntityBodyStoragePart bodyPartMax = new EntityBodyStoragePart(Integer.MAX_VALUE);
		final EntityProxyState stateMax = new EntityProxyState(mockSchema, bodyPartMax, null, null, null, null);
		final EntityContract proxyMax = createEntityProxy(stateMax, EntityPrimaryKeyPartial.GET_PRIMARY_KEY);
		assertEquals(Integer.MAX_VALUE, proxyMax.getPrimaryKey(), "getPrimaryKey() should return Integer.MAX_VALUE");
	}

	@Test
	@DisplayName("getPrimaryKey() throws ExpressionEvaluationException when bodyPart is null")
	void shouldThrowExceptionWhenBodyPartIsNull() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, null);

		final EntityContract proxy = createEntityProxy(state, EntityPrimaryKeyPartial.GET_PRIMARY_KEY);

		final ExpressionEvaluationException exception = assertThrows(
			ExpressionEvaluationException.class,
			proxy::getPrimaryKey,
			"getPrimaryKey() should throw ExpressionEvaluationException when bodyPart is null"
		);
		assertTrue(
			exception.getPrivateMessage().contains("Product"),
			"Private message should contain the entity type name 'Product'"
		);
		assertTrue(
			exception.getPrivateMessage().contains("body"),
			"Private message should mention 'body' storage part"
		);
	}
}
