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

import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.AttributesAvailabilityChecker;
import io.evitadb.core.expression.proxy.EntityProxyState;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import one.edee.oss.proxycian.PredicateMethodClassification;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Partial providing attribute-related method implementations for entity expression proxies.
 *
 * Global attributes are accessed via the dedicated `globalAttributesPart` field (direct O(1) access). Locale-specific
 * attributes are stored in a `Map<Locale, AttributesStoragePart>` for O(1) locale lookup, whose key set directly
 * serves as the attribute locales set (zero-copy via `Collections.unmodifiableSet`).
 *
 * Contains classifications for:
 * - `getAttribute(String)` from {@link AttributesContract} - returns global attribute value by binary search
 * - `getAttribute(String, Locale)` from {@link AttributesContract} - returns localized attribute value by binary search
 * - `getAttributeSchema(String)` from {@link AttributesContract} - delegates to entity schema
 * - `getAttributeLocales()` from {@link AttributesContract} - returns unmodifiable view of locale map key set
 * - `attributesAvailable()` from {@link AttributesAvailabilityChecker} - returns `true`
 */
public final class EntityAttributePartial {

	/**
	 * Matches `getAttribute(String)` declared on {@link AttributesContract} and returns the attribute value from the
	 * global attributes storage part using binary search.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_ATTRIBUTE =
		new PredicateMethodClassification<>(
			"getAttribute(String)",
			(method, proxyState) ->
				"getAttribute".equals(method.getName())
					&& method.getParameterCount() == 1
					&& method.getParameterTypes()[0] == String.class,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				final String name = (String) args[0];
				final AttributesStoragePart globalPart = proxyState.globalAttributesPart();
				if (globalPart == null) {
					return null;
				}
				final AttributeValue attributeValue = globalPart.findAttribute(new AttributeKey(name));
				return attributeValue != null && attributeValue.exists() ? attributeValue.value() : null;
			}
		);

	/**
	 * Matches `getAttribute(String, Locale)` declared on {@link AttributesContract} and returns the localized
	 * attribute value from the locale-specific storage part using binary search.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_ATTRIBUTE_LOCALIZED =
		new PredicateMethodClassification<>(
			"getAttribute(String, Locale)",
			(method, proxyState) ->
				"getAttribute".equals(method.getName())
					&& method.getParameterCount() == 2
					&& method.getParameterTypes()[0] == String.class
					&& method.getParameterTypes()[1] == Locale.class,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				final String name = (String) args[0];
				final Locale locale = (Locale) args[1];
				final Map<Locale, AttributesStoragePart> parts = proxyState.localeAttributesParts();
				if (parts == null) {
					return null;
				}
				final AttributesStoragePart localePart = parts.get(locale);
				if (localePart == null) {
					return null;
				}
				final AttributeValue attributeValue = localePart.findAttribute(new AttributeKey(name, locale));
				return attributeValue != null && attributeValue.exists() ? attributeValue.value() : null;
			}
		);

	/**
	 * Matches `getAttributeSchema(String)` declared on {@link AttributesContract} and delegates to the entity schema.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_ATTRIBUTE_SCHEMA =
		new PredicateMethodClassification<>(
			"getAttributeSchema",
			(method, proxyState) ->
				"getAttributeSchema".equals(method.getName())
					&& method.getParameterCount() == 1
					&& method.getParameterTypes()[0] == String.class,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) ->
				proxyState.schema().getAttribute((String) args[0])
		);

	/**
	 * Matches `getAttributeLocales()` declared on {@link AttributesContract} and returns unmodifiable view of the
	 * locale map key set — zero allocation, zero copying.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_ATTRIBUTE_LOCALES =
		new PredicateMethodClassification<>(
			"getAttributeLocales",
			(method, proxyState) ->
				"getAttributeLocales".equals(method.getName())
					&& method.getParameterCount() == 0,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				final Map<Locale, AttributesStoragePart> parts = proxyState.localeAttributesParts();
				if (parts == null) {
					return Set.of();
				}
				return Collections.unmodifiableSet(parts.keySet());
			}
		);

	/**
	 * Matches `attributesAvailable()` declared on {@link AttributesAvailabilityChecker} and always returns `true`
	 * since the proxy is constructed with the required attribute data.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> ATTRIBUTES_AVAILABLE =
		new PredicateMethodClassification<>(
			"attributesAvailable",
			(method, proxyState) ->
				"attributesAvailable".equals(method.getName())
					&& method.getParameterCount() == 0,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> true
		);

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private EntityAttributePartial() {
		// utility class
	}
}
