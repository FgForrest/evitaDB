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

import io.evitadb.exception.ExpressionEvaluationException;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;

/**
 * Safety net partial that is always present as the last classification in every expression proxy.
 *
 * - `OBJECT_METHODS` delegates `Object` methods (toString, hashCode, equals) to the default implementation via
 *   `invokeSuper`.
 * - `INSTANCE` catches all remaining unhandled methods and throws `ExpressionEvaluationException` with the method
 *   name.
 *
 * When composing proxy classifications, the ordering must be: specific partials first, then `OBJECT_METHODS`, then
 * `INSTANCE` last.
 */
public final class CatchAllPartial {

	/**
	 * Handles Object.class methods by delegating to the superclass implementation. Must appear before {@link #INSTANCE}
	 * in the classification list.
	 */
	public static final PredicateMethodClassification<Object, Void, Object> OBJECT_METHODS =
		new PredicateMethodClassification<>(
			"Object methods",
			(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, Object.class),
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				try {
					return invokeSuper.call();
				} catch (Exception e) {
					throw new InvocationTargetException(e);
				}
			}
		);

	/**
	 * Catches all methods not handled by any specific partial. Throws `ExpressionEvaluationException` with the method
	 * name, providing fail-fast safety when proxy forwards a call to a method the expression does not need. Must be
	 * the last classification in the composition.
	 */
	public static final PredicateMethodClassification<Object, Void, Object> INSTANCE =
		new PredicateMethodClassification<>(
			"Unsupported expression method",
			(method, proxyState) -> true,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				throw new ExpressionEvaluationException(
					"Method " + method.getName()
						+ "() is not available during expression evaluation — the expression does not"
						+ " access this data.",
					"Cannot access " + method.getName() + "."
				);
			}
		);

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private CatchAllPartial() {
		// utility class
	}
}
