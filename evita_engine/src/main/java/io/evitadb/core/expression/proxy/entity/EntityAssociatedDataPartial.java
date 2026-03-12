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

import io.evitadb.api.requestResponse.data.AssociatedDataAvailabilityChecker;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.core.expression.proxy.EntityProxyState;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart;
import one.edee.oss.proxycian.PredicateMethodClassification;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Partial providing associated data method implementations for entity expression proxies.
 *
 * Associated data storage parts are stored in a `Map<AssociatedDataKey, AssociatedDataStoragePart>` for O(1) lookup
 * by name and locale combination.
 *
 * Contains classifications for:
 * - `getAssociatedData(String)` from {@link AssociatedDataContract} - returns global associated data value
 * - `getAssociatedData(String, Locale)` from {@link AssociatedDataContract} - returns localized value
 * - `getAssociatedDataSchema(String)` from {@link AssociatedDataContract} - delegates to entity schema
 * - `getAssociatedDataLocales()` from {@link AssociatedDataContract} - collects locales from map keys
 * - `associatedDataAvailable()` from {@link AssociatedDataAvailabilityChecker} - returns `true`
 */
public final class EntityAssociatedDataPartial {

	/**
	 * Matches `getAssociatedData(String)` declared on {@link AssociatedDataContract} with exactly one String parameter.
	 * Returns the global associated data value using O(1) map lookup.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_ASSOCIATED_DATA =
		new PredicateMethodClassification<>(
			"getAssociatedData(String)",
			(method, proxyState) ->
				"getAssociatedData".equals(method.getName())
					&& method.getParameterCount() == 1
					&& method.getParameterTypes()[0] == String.class,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				final String name = (String) args[0];
				final Map<AssociatedDataKey, AssociatedDataStoragePart> parts = proxyState.associatedDataParts();
				if (parts == null) {
					return null;
				}
				final AssociatedDataStoragePart part = parts.get(new AssociatedDataKey(name));
				if (part == null || part.getValue() == null || part.getValue().dropped()) {
					return null;
				}
				return part.getValue().value();
			}
		);

	/**
	 * Matches `getAssociatedData(String, Locale)` declared on {@link AssociatedDataContract} with exactly two
	 * parameters. Returns the localized associated data value using O(1) map lookup.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState>
		GET_ASSOCIATED_DATA_LOCALIZED = new PredicateMethodClassification<>(
		"getAssociatedData(String, Locale)",
		(method, proxyState) ->
			"getAssociatedData".equals(method.getName())
				&& method.getParameterCount() == 2
				&& method.getParameterTypes()[0] == String.class
				&& method.getParameterTypes()[1] == Locale.class,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
			final String name = (String) args[0];
			final Locale locale = (Locale) args[1];
			final Map<AssociatedDataKey, AssociatedDataStoragePart> parts = proxyState.associatedDataParts();
			if (parts == null) {
				return null;
			}
			final AssociatedDataStoragePart part = parts.get(new AssociatedDataKey(name, locale));
			if (part == null) {
				return null;
			}
			final AssociatedDataValue associatedDataValue = part.getValue();
			return associatedDataValue != null && associatedDataValue.exists() ? associatedDataValue.value() : null;
		}
	);

	/**
	 * Matches `getAssociatedDataSchema(String)` declared on {@link AssociatedDataContract} and delegates to the
	 * entity schema.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState>
		GET_ASSOCIATED_DATA_SCHEMA = new PredicateMethodClassification<>(
		"getAssociatedDataSchema",
		(method, proxyState) ->
			"getAssociatedDataSchema".equals(method.getName())
				&& method.getParameterCount() == 1
				&& method.getParameterTypes()[0] == String.class,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) ->
			proxyState.schema().getAssociatedData((String) args[0])
	);

	/**
	 * Matches `getAssociatedDataLocales()` declared on {@link AssociatedDataContract} and returns the set of
	 * non-null locales present in the associated data map keys.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState>
		GET_ASSOCIATED_DATA_LOCALES = new PredicateMethodClassification<>(
		"getAssociatedDataLocales",
		(method, proxyState) ->
			"getAssociatedDataLocales".equals(method.getName())
				&& method.getParameterCount() == 0,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
			final Map<AssociatedDataKey, AssociatedDataStoragePart> parts = proxyState.associatedDataParts();
			if (parts == null) {
				return Set.of();
			}
			final Set<Locale> locales = createHashSet(parts.size());
			for (final AssociatedDataKey key : parts.keySet()) {
				final Locale locale = key.locale();
				if (locale != null) {
					locales.add(locale);
				}
			}
			return locales;
		}
	);

	/**
	 * Matches `associatedDataAvailable()` declared on {@link AssociatedDataAvailabilityChecker} and always returns
	 * `true` since the proxy is constructed with the required associated data.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState>
		ASSOCIATED_DATA_AVAILABLE = new PredicateMethodClassification<>(
		"associatedDataAvailable",
		(method, proxyState) ->
			"associatedDataAvailable".equals(method.getName())
				&& method.getParameterCount() == 0,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> true
	);

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private EntityAssociatedDataPartial() {
		// utility class
	}
}
