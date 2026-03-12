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

import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.AttributesAvailabilityChecker;
import io.evitadb.core.expression.proxy.ReferenceProxyState;
import one.edee.oss.proxycian.PredicateMethodClassification;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Locale;

/**
 * Partial providing attribute-related method implementations for reference expression proxies.
 *
 * Contains classifications for:
 * - `getAttribute(String)` from {@link AttributesContract} - returns reference attribute value by binary search
 * - `getAttribute(String, Locale)` from {@link AttributesContract} - returns localized reference attribute value by
 *   binary search
 * - `getAttributeSchema(String)` from {@link AttributesContract} - delegates to reference schema
 * - `getAttributeLocales()` from {@link AttributesContract} - returns attribute locales from proxy state
 * - `attributesAvailable()` from {@link AttributesAvailabilityChecker} - returns `true`
 */
public final class ReferenceAttributePartial {

	/**
	 * Matches `getAttribute(String)` declared on {@link AttributesContract} and returns the attribute value from the
	 * reference attributes array using binary search.
	 */
	public static final PredicateMethodClassification<Object, Void, ReferenceProxyState> GET_ATTRIBUTE =
		new PredicateMethodClassification<>(
			"getAttribute(String)",
			(method, proxyState) ->
				"getAttribute".equals(method.getName())
					&& method.getParameterCount() == 1
					&& method.getParameterTypes()[0] == String.class,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				final String name = (String) args[0];
				return findAttributeValue(proxyState.attributes(), new AttributeKey(name));
			}
		);

	/**
	 * Matches `getAttribute(String, Locale)` declared on {@link AttributesContract} and returns the localized
	 * attribute value from the reference attributes array using binary search.
	 */
	public static final PredicateMethodClassification<Object, Void, ReferenceProxyState>
		GET_ATTRIBUTE_LOCALIZED = new PredicateMethodClassification<>(
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
			return findAttributeValue(proxyState.attributes(), new AttributeKey(name, locale));
		}
	);

	/**
	 * Matches `getAttributeSchema(String)` declared on {@link AttributesContract} and delegates to the reference
	 * schema.
	 */
	public static final PredicateMethodClassification<Object, Void, ReferenceProxyState>
		GET_ATTRIBUTE_SCHEMA = new PredicateMethodClassification<>(
		"getAttributeSchema",
		(method, proxyState) ->
			"getAttributeSchema".equals(method.getName())
				&& method.getParameterCount() == 1
				&& method.getParameterTypes()[0] == String.class,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) ->
			proxyState.referenceSchema().getAttribute((String) args[0])
	);

	/**
	 * Matches `getAttributeLocales()` declared on {@link AttributesContract} and returns the attribute locales from
	 * the proxy state.
	 */
	public static final PredicateMethodClassification<Object, Void, ReferenceProxyState>
		GET_ATTRIBUTE_LOCALES = new PredicateMethodClassification<>(
		"getAttributeLocales",
		(method, proxyState) ->
			"getAttributeLocales".equals(method.getName())
				&& method.getParameterCount() == 0,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.attributeLocales()
	);

	/**
	 * Matches `attributesAvailable()` declared on {@link AttributesAvailabilityChecker} and always returns `true`
	 * since the proxy is constructed with the required attribute data.
	 */
	public static final PredicateMethodClassification<Object, Void, ReferenceProxyState>
		ATTRIBUTES_AVAILABLE = new PredicateMethodClassification<>(
		"attributesAvailable",
		(method, proxyState) ->
			"attributesAvailable".equals(method.getName())
				&& method.getParameterCount() == 0,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> true
	);

	/**
	 * Performs binary search on the sorted attribute values array for the given key and returns the value, or `null`
	 * if not found.
	 *
	 * @param attributes the sorted array of attribute values
	 * @param key        the attribute key to search for
	 * @return the attribute value, or `null` if not found
	 */
	@Nullable
	private static Object findAttributeValue(
		@Nullable AttributeValue[] attributes,
		@Nullable AttributeKey key
	) {
		if (attributes == null || key == null) {
			return null;
		}
		final AttributeValue searchKey = AttributeValue.createEmptyComparableAttributeValue(key);
		final int index = Arrays.binarySearch(attributes, searchKey);
		if (index >= 0 && attributes[index].exists()) {
			return attributes[index].value();
		}
		return null;
	}

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private ReferenceAttributePartial() {
		// utility class
	}
}
