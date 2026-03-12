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
 * Tests for {@link EntitySchemaPartial} verifying that schema-related method implementations correctly delegate to the
 * proxy state.
 */
@SuppressWarnings("rawtypes")
@DisplayName("Entity schema partial")
class EntitySchemaPartialTest {

	/**
	 * Unique marker interface to avoid Proxycian classification cache pollution between test classes.
	 */
	private interface SchemaTestEntity extends EntityContract {}

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
			new Class<?>[]{ SchemaTestEntity.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
	}

	@Test
	@DisplayName("getSchema() returns the entity schema from state")
	void shouldReturnEntitySchemaFromState() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state, EntitySchemaPartial.GET_SCHEMA, EntitySchemaPartial.GET_TYPE
		);

		assertSame(
			mockSchema, proxy.getSchema(),
			"getSchema() should return the exact schema instance from the proxy state"
		);
	}

	@Test
	@DisplayName("getType() returns the schema name")
	void shouldReturnSchemaNameAsType() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state, EntitySchemaPartial.GET_SCHEMA, EntitySchemaPartial.GET_TYPE
		);

		assertEquals("Product", proxy.getType(), "getType() should return the schema name");
	}

	@Test
	@DisplayName("Unhandled methods throw when only schema partial is present")
	void shouldThrowForUnhandledMethodsWhenOnlySchemaPartialPresent() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state, EntitySchemaPartial.GET_SCHEMA, EntitySchemaPartial.GET_TYPE
		);

		// Use pricesAvailable() which will never be handled by any of our expression partials
		assertThrows(
			ExpressionEvaluationException.class, proxy::pricesAvailable,
			"pricesAvailable() should throw when only schema partial is present"
		);
	}
}
