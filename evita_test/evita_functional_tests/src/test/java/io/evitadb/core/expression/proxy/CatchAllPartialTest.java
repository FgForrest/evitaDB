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

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.ExpressionEvaluationException;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import one.edee.oss.proxycian.util.ReflectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.utils.ArrayUtils.EMPTY_CLASS_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_OBJECT_ARRAY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CatchAllPartial} verifying that the catch-all safety net correctly intercepts unhandled method calls
 * on expression proxies while allowing Object methods to pass through.
 */
@DisplayName("CatchAll partial")
class CatchAllPartialTest {

	/**
	 * Unique marker interface to avoid Proxycian classification cache pollution between test classes.
	 */
	private interface CatchAllTestEntity extends EntityContract {}

	/**
	 * Creates a minimal EntityContract proxy backed by only the CatchAllPartial classifications
	 * (OBJECT_METHODS + INSTANCE).
	 */
	@Nonnull
	private static EntityContract createCatchAllProxy() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, null);
		return ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(
				state,
				CatchAllPartial.OBJECT_METHODS,
				CatchAllPartial.INSTANCE
			),
			new Class<?>[]{ CatchAllTestEntity.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
	}

	@Test
	@DisplayName("Calling unhandled method throws ExpressionEvaluationException with method name")
	void shouldThrowExpressionEvaluationExceptionWithMethodName() {
		final EntityContract proxy = createCatchAllProxy();

		final ExpressionEvaluationException exception = assertThrows(
			ExpressionEvaluationException.class,
			proxy::getPrimaryKey
		);
		assertTrue(
			exception.getPrivateMessage().contains("getPrimaryKey"),
			"Private message should contain the method name"
		);
	}

	@Test
	@DisplayName("Exception public message includes human-readable context")
	void shouldIncludeHumanReadableContextInExceptionMessage() {
		final EntityContract proxy = createCatchAllProxy();

		final ExpressionEvaluationException exception = assertThrows(
			ExpressionEvaluationException.class,
			() -> proxy.getAttribute("code")
		);
		assertTrue(
			exception.getPublicMessage().contains("Cannot access getAttribute"),
			"Public message should contain 'Cannot access getAttribute'"
		);
	}

	@Test
	@DisplayName("Object methods (toString, hashCode, equals) do not throw")
	void shouldNotInterceptObjectMethods() {
		final EntityContract proxy = createCatchAllProxy();

		assertDoesNotThrow(proxy::toString, "toString() should not throw");
		assertDoesNotThrow(proxy::hashCode, "hashCode() should not throw");
		assertDoesNotThrow(() -> proxy.equals(proxy), "equals() should not throw");
	}

	@Test
	@DisplayName("Catch-all matches every unhandled method")
	void shouldMatchEveryUnhandledMethod() {
		final EntityContract proxy = createCatchAllProxy();

		final ExpressionEvaluationException scopeException = assertThrows(
			ExpressionEvaluationException.class, proxy::getScope, "getScope() should throw"
		);
		assertTrue(
			scopeException.getPrivateMessage().contains("getScope"),
			"Private message should contain the method name 'getScope'"
		);

		final ExpressionEvaluationException versionException = assertThrows(
			ExpressionEvaluationException.class, proxy::version, "version() should throw"
		);
		assertTrue(
			versionException.getPrivateMessage().contains("version"),
			"Private message should contain the method name 'version'"
		);
	}

	@Test
	@DisplayName("Specific partial before catch-all wins for matched method")
	void shouldLetSpecificPartialWinOverCatchAll() {
		interface CatchAllComposedTestEntity extends EntityContract {}

		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("TestProduct");
		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, null);

		// Custom classification that matches getType() and returns schema name
		final PredicateMethodClassification<Object, Void, Object> getTypeClassification =
			new PredicateMethodClassification<>(
				"getType",
				(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(
					method, EntityClassifier.class, "getType"
				),
				(method, s) -> null,
				(proxy, method, args, ctx, ps, invokeSuper) -> "TestProduct"
			);

		final EntityContract proxy = ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(
				state,
				getTypeClassification,
				CatchAllPartial.OBJECT_METHODS,
				CatchAllPartial.INSTANCE
			),
			new Class<?>[]{ CatchAllComposedTestEntity.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);

		// Specific partial should win for getType()
		assertEquals("TestProduct", proxy.getType(), "getType() should return value from specific partial");

		// Catch-all should still apply for getPrimaryKey()
		assertThrows(
			ExpressionEvaluationException.class,
			proxy::getPrimaryKey,
			"getPrimaryKey() should throw via catch-all"
		);
	}
}
