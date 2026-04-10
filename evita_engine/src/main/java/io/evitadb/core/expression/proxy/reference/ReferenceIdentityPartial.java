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

package io.evitadb.core.expression.proxy.reference;

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.core.expression.proxy.ReferenceProxyState;
import one.edee.oss.proxycian.PredicateMethodClassification;

import java.util.Optional;

/**
 * Partial providing identity-related method implementations for reference expression proxies.
 *
 * Contains classifications for:
 * - `getReferenceKey()` from {@link ReferenceContract} - returns the reference key from proxy state
 * - `getReferencedEntityType()` from {@link ReferenceContract} - returns the referenced entity type from reference
 *   schema
 * - `getReferenceCardinality()` from {@link ReferenceContract} - returns the cardinality from reference schema
 * - `getReferenceSchema()` from {@link ReferenceContract} - returns `Optional.of(referenceSchema)`
 * - `getReferenceSchemaOrThrow()` from {@link ReferenceContract} - returns the reference schema directly
 *
 * Note: `getReferencedPrimaryKey()` and `getReferenceName()` are default methods on {@link ReferenceContract} that
 * delegate to `getReferenceKey()` and require no explicit handling.
 */
public final class ReferenceIdentityPartial {

	/**
	 * Matches `getReferenceKey()` declared on {@link ReferenceContract} and returns the reference key from the proxy
	 * state.
	 */
	public static final PredicateMethodClassification<Object, Void, ReferenceProxyState> GET_REFERENCE_KEY =
		new PredicateMethodClassification<>(
			"getReferenceKey",
			(method, proxyState) ->
				"getReferenceKey".equals(method.getName())
					&& method.getParameterCount() == 0,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.referenceKey()
		);

	/**
	 * Matches `getReferencedEntityType()` declared on {@link ReferenceContract} and returns the referenced entity type
	 * from the reference schema.
	 */
	public static final PredicateMethodClassification<Object, Void, ReferenceProxyState>
		GET_REFERENCED_ENTITY_TYPE = new PredicateMethodClassification<>(
		"getReferencedEntityType",
		(method, proxyState) ->
			"getReferencedEntityType".equals(method.getName())
				&& method.getParameterCount() == 0,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) ->
			proxyState.referenceSchema().getReferencedEntityType()
	);

	/**
	 * Matches `getReferenceCardinality()` declared on {@link ReferenceContract} and returns the cardinality from the
	 * reference schema.
	 */
	public static final PredicateMethodClassification<Object, Void, ReferenceProxyState>
		GET_REFERENCE_CARDINALITY = new PredicateMethodClassification<>(
		"getReferenceCardinality",
		(method, proxyState) ->
			"getReferenceCardinality".equals(method.getName())
				&& method.getParameterCount() == 0,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) ->
			proxyState.referenceSchema().getCardinality()
	);

	/**
	 * Matches `getReferenceSchema()` declared on {@link ReferenceContract} and returns `Optional.of(referenceSchema)`.
	 */
	public static final PredicateMethodClassification<Object, Void, ReferenceProxyState>
		GET_REFERENCE_SCHEMA = new PredicateMethodClassification<>(
		"getReferenceSchema",
		(method, proxyState) ->
			"getReferenceSchema".equals(method.getName())
				&& method.getParameterCount() == 0,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) ->
			Optional.of(proxyState.referenceSchema())
	);

	/**
	 * Matches `getReferencedPrimaryKey()` - a default method on {@link ReferenceContract} that delegates to
	 * `getReferenceKey().primaryKey()`. Explicit handling is needed because ByteBuddy proxies intercept default
	 * methods.
	 */
	public static final PredicateMethodClassification<Object, Void, ReferenceProxyState>
		GET_REFERENCED_PRIMARY_KEY = new PredicateMethodClassification<>(
		"getReferencedPrimaryKey",
		(method, proxyState) ->
			"getReferencedPrimaryKey".equals(method.getName())
				&& method.getParameterCount() == 0,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) ->
			proxyState.referenceKey().primaryKey()
	);

	/**
	 * Matches `getReferenceName()` - a default method on {@link ReferenceContract} that delegates to
	 * `getReferenceKey().referenceName()`. Explicit handling is needed because ByteBuddy proxies intercept default
	 * methods.
	 */
	public static final PredicateMethodClassification<Object, Void, ReferenceProxyState>
		GET_REFERENCE_NAME = new PredicateMethodClassification<>(
		"getReferenceName",
		(method, proxyState) ->
			"getReferenceName".equals(method.getName())
				&& method.getParameterCount() == 0,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) ->
			proxyState.referenceKey().referenceName()
	);

	/**
	 * Matches `getReferenceSchemaOrThrow()` declared on {@link ReferenceContract} and returns the reference schema
	 * directly.
	 */
	public static final PredicateMethodClassification<Object, Void, ReferenceProxyState>
		GET_REFERENCE_SCHEMA_OR_THROW = new PredicateMethodClassification<>(
		"getReferenceSchemaOrThrow",
		(method, proxyState) ->
			"getReferenceSchemaOrThrow".equals(method.getName())
				&& method.getParameterCount() == 0,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.referenceSchema()
	);

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private ReferenceIdentityPartial() {
		// utility class
	}
}
