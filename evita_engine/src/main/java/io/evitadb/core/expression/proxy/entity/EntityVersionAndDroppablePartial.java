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

import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.core.expression.proxy.EntityProxyState;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.util.ReflectionUtils;

/**
 * Always-included partial providing version, droppable, scope, and locale method implementations for entity expression
 * proxies.
 *
 * Contains classifications for:
 * - `version()` from {@link Versioned} - returns the body part version
 * - `dropped()` from {@link Droppable} - always returns `false`
 * - `getScope()` from {@link EntityContract} - returns the body part scope
 * - `getAllLocales()` from {@link EntityContract} - returns the body part locales
 * - `getLocales()` from {@link EntityContract} - returns the body part locales
 */
public final class EntityVersionAndDroppablePartial {

	/**
	 * Matches `version()` declared on {@link Versioned} and returns the version from the entity body storage part.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> VERSION =
		new PredicateMethodClassification<>(
			"version",
			(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(method, Versioned.class, "version"),
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) ->
				proxyState.bodyPartOrThrowException().getVersion()
		);

	/**
	 * Matches `dropped()` declared on {@link Droppable} and always returns `false` since expression proxies represent
	 * live entities.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> DROPPED =
		new PredicateMethodClassification<>(
			"dropped",
			(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(method, Droppable.class, "dropped"),
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> false
		);

	/**
	 * Matches `getScope()` declared on {@link EntityContract} and returns the scope from the entity body storage part.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_SCOPE =
		new PredicateMethodClassification<>(
			"getScope",
			(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(
				method, EntityContract.class, "getScope"
			),
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) ->
				proxyState.bodyPartOrThrowException().getScope()
		);

	/**
	 * Matches `getAllLocales()` declared on {@link EntityContract} and returns the locales from the entity body
	 * storage part.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_ALL_LOCALES =
		new PredicateMethodClassification<>(
			"getAllLocales",
			(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(
				method, EntityContract.class, "getAllLocales"
			),
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) ->
				proxyState.bodyPartOrThrowException().getLocales()
		);

	/**
	 * Matches `getLocales()` declared on {@link EntityContract} and returns the locales from the entity body
	 * storage part.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_LOCALES =
		new PredicateMethodClassification<>(
			"getLocales",
			(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(
				method, EntityContract.class, "getLocales"
			),
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) ->
				proxyState.bodyPartOrThrowException().getLocales()
		);

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private EntityVersionAndDroppablePartial() {
		// utility class
	}
}
