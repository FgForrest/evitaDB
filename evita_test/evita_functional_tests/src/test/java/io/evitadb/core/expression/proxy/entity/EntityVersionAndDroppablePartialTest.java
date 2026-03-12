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
import io.evitadb.dataType.Scope;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.utils.ArrayUtils.EMPTY_CLASS_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_OBJECT_ARRAY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntityVersionAndDroppablePartial} verifying that version, droppable, scope, and locale method
 * implementations correctly delegate to the entity body storage part.
 */
@SuppressWarnings("rawtypes")
@DisplayName("Entity version and droppable partial")
class EntityVersionAndDroppablePartialTest {

	/**
	 * Unique marker interface to avoid Proxycian classification cache pollution between test classes.
	 */
	private interface VersionTestEntity extends EntityContract {}

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
			new Class<?>[]{ VersionTestEntity.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
	}

	/**
	 * Creates an EntityBodyStoragePart with specific version, scope, and locales using the full constructor.
	 *
	 * @param version    the version number
	 * @param primaryKey the primary key
	 * @param scope      the entity scope
	 * @param locales    the set of locales
	 * @return configured body storage part
	 */
	@Nonnull
	private static EntityBodyStoragePart createBodyPart(
		int version, int primaryKey, @Nonnull Scope scope, @Nonnull Set<Locale> locales) {
		return new EntityBodyStoragePart(
			version, primaryKey, scope, null, locales,
			new LinkedHashSet<>(), new LinkedHashSet<>(), 0
		);
	}

	@Test
	@DisplayName("version() returns body part version")
	void shouldReturnBodyPartVersion() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		final EntityBodyStoragePart bodyPart = createBodyPart(3, 1, Scope.LIVE, new LinkedHashSet<>());
		final EntityProxyState state = new EntityProxyState(mockSchema, bodyPart, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state,
			EntityVersionAndDroppablePartial.VERSION,
			EntityVersionAndDroppablePartial.DROPPED,
			EntityVersionAndDroppablePartial.GET_SCOPE,
			EntityVersionAndDroppablePartial.GET_ALL_LOCALES,
			EntityVersionAndDroppablePartial.GET_LOCALES
		);

		assertEquals(3, proxy.version(), "version() should return 3");
	}

	@Test
	@DisplayName("dropped() returns false")
	void shouldReturnFalseForDropped() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		final EntityBodyStoragePart bodyPart = createBodyPart(1, 1, Scope.LIVE, new LinkedHashSet<>());
		final EntityProxyState state = new EntityProxyState(mockSchema, bodyPart, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state,
			EntityVersionAndDroppablePartial.VERSION,
			EntityVersionAndDroppablePartial.DROPPED,
			EntityVersionAndDroppablePartial.GET_SCOPE,
			EntityVersionAndDroppablePartial.GET_ALL_LOCALES,
			EntityVersionAndDroppablePartial.GET_LOCALES
		);

		assertFalse(proxy.dropped(), "dropped() should return false");
	}

	@Test
	@DisplayName("getScope() returns body part scope")
	void shouldReturnBodyPartScope() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		final EntityBodyStoragePart bodyPart = createBodyPart(1, 1, Scope.LIVE, new LinkedHashSet<>());
		final EntityProxyState state = new EntityProxyState(mockSchema, bodyPart, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state,
			EntityVersionAndDroppablePartial.VERSION,
			EntityVersionAndDroppablePartial.DROPPED,
			EntityVersionAndDroppablePartial.GET_SCOPE,
			EntityVersionAndDroppablePartial.GET_ALL_LOCALES,
			EntityVersionAndDroppablePartial.GET_LOCALES
		);

		assertEquals(Scope.LIVE, proxy.getScope(), "getScope() should return Scope.LIVE");
	}

	@Test
	@DisplayName("getAllLocales() returns body part locales")
	void shouldReturnBodyPartLocales() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		final LinkedHashSet<Locale> locales = new LinkedHashSet<>();
		locales.add(Locale.ENGLISH);
		locales.add(Locale.GERMAN);
		final EntityBodyStoragePart bodyPart = createBodyPart(1, 1, Scope.LIVE, locales);
		final EntityProxyState state = new EntityProxyState(mockSchema, bodyPart, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state,
			EntityVersionAndDroppablePartial.VERSION,
			EntityVersionAndDroppablePartial.DROPPED,
			EntityVersionAndDroppablePartial.GET_SCOPE,
			EntityVersionAndDroppablePartial.GET_ALL_LOCALES,
			EntityVersionAndDroppablePartial.GET_LOCALES
		);

		final Set<Locale> result = proxy.getAllLocales();
		assertTrue(result.contains(Locale.ENGLISH), "getAllLocales() should contain ENGLISH");
		assertTrue(result.contains(Locale.GERMAN), "getAllLocales() should contain GERMAN");
		assertEquals(2, result.size(), "getAllLocales() should contain exactly 2 locales");
	}

	@Test
	@DisplayName("version() throws ExpressionEvaluationException when bodyPart is null")
	void shouldThrowExceptionWhenBodyPartIsNullForVersion() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state,
			EntityVersionAndDroppablePartial.VERSION,
			EntityVersionAndDroppablePartial.DROPPED,
			EntityVersionAndDroppablePartial.GET_SCOPE,
			EntityVersionAndDroppablePartial.GET_ALL_LOCALES,
			EntityVersionAndDroppablePartial.GET_LOCALES
		);

		final ExpressionEvaluationException versionException = assertThrows(
			ExpressionEvaluationException.class,
			proxy::version,
			"version() should throw ExpressionEvaluationException when bodyPart is null"
		);
		assertTrue(
			versionException.getPrivateMessage().contains("Product"),
			"version() exception should mention entity type 'Product'"
		);

		final ExpressionEvaluationException scopeException = assertThrows(
			ExpressionEvaluationException.class,
			proxy::getScope,
			"getScope() should throw ExpressionEvaluationException when bodyPart is null"
		);
		assertTrue(
			scopeException.getPrivateMessage().contains("body"),
			"getScope() exception should mention 'body' storage part"
		);

		final ExpressionEvaluationException localesException = assertThrows(
			ExpressionEvaluationException.class,
			proxy::getAllLocales,
			"getAllLocales() should throw ExpressionEvaluationException when bodyPart is null"
		);
		assertTrue(
			localesException.getPrivateMessage().contains("Product"),
			"getAllLocales() exception should mention entity type 'Product'"
		);
	}
}
