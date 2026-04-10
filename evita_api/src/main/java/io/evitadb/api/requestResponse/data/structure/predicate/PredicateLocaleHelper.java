/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.requestResponse.data.structure.predicate;

import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.SerializablePredicate;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static java.util.Optional.ofNullable;

/**
 * Shared locale-handling utility methods used by multiple {@link SerializablePredicate} implementations.
 *
 * This class extracts common locale resolution, combination, and validation logic that was previously
 * duplicated across {@link AttributeValueSerializablePredicate},
 * {@link AssociatedDataValueSerializablePredicate}, {@link LocaleSerializablePredicate},
 * {@link ReferenceContractSerializablePredicate}, and {@link ReferenceAttributeValueSerializablePredicate}.
 *
 * All methods are stateless and operate on the locale fields passed as parameters.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
final class PredicateLocaleHelper {

	/**
	 * No instances.
	 */
	private PredicateLocaleHelper() {
	}

	/**
	 * Combines existing locales with newly requested locales from an Evita request.
	 *
	 * If `existingLocales` is null, the request's locales are returned as-is. If both are non-null,
	 * they are merged into a new set. If only existing locales are present, they are returned unchanged.
	 *
	 * @param existingLocales the current set of locales, or null if none were previously requested
	 * @param evitaRequest the request containing potentially new locale requirements
	 * @return the combined set of locales, or null if no locales are specified in either source
	 */
	@Nullable
	static Set<Locale> combineLocales(
		@Nullable Set<Locale> existingLocales,
		@Nonnull EvitaRequest evitaRequest
	) {
		final Set<Locale> newlyRequiredLanguages = evitaRequest.getRequiredLocales();
		if (existingLocales == null) {
			return newlyRequiredLanguages;
		} else if (newlyRequiredLanguages != null) {
			final Set<Locale> combined = new HashSet<>(existingLocales.size() + newlyRequiredLanguages.size());
			combined.addAll(existingLocales);
			combined.addAll(newlyRequiredLanguages);
			return combined;
		} else {
			return existingLocales;
		}
	}

	/**
	 * Resolves the single locale to use, preferring the current locale if already set.
	 *
	 * Falls back through: current locale -> request's implicit locale -> request's explicit locale ->
	 * single-element required locales set -> null.
	 *
	 * @param currentLocale the currently resolved locale, or null
	 * @param evitaRequest the request to derive the locale from if current is null
	 * @return the resolved locale, or null if none can be determined
	 */
	@Nullable
	static Locale resolveLocale(
		@Nullable Locale currentLocale,
		@Nonnull EvitaRequest evitaRequest
	) {
		if (currentLocale != null) {
			return currentLocale;
		}
		return ofNullable(evitaRequest.getImplicitLocale())
			.orElseGet(
				() -> ofNullable(evitaRequest.getLocale())
					.orElseGet(() -> {
						final Set<Locale> requiredLocales = evitaRequest.getRequiredLocales();
						return requiredLocales != null && requiredLocales.size() == 1
							? requiredLocales.iterator().next()
							: null;
					})
			);
	}

	/**
	 * Resolves the implicit locale, preferring the current value if already set.
	 *
	 * @param currentImplicitLocale the currently set implicit locale, or null
	 * @param evitaRequest the request to derive the implicit locale from if current is null
	 * @return the resolved implicit locale, or null if neither source provides one
	 */
	@Nullable
	static Locale resolveImplicitLocale(
		@Nullable Locale currentImplicitLocale,
		@Nonnull EvitaRequest evitaRequest
	) {
		return currentImplicitLocale == null ? evitaRequest.getImplicitLocale() : currentImplicitLocale;
	}

	/**
	 * Asserts that the implicit locale in the current predicate and the request do not conflict.
	 *
	 * Both may be null, or one may be null (no conflict), but if both are non-null they must be equal.
	 *
	 * @param currentImplicitLocale the currently set implicit locale
	 * @param evitaRequest the request containing a potentially different implicit locale
	 * @throws io.evitadb.exception.GenericEvitaInternalError if both are non-null and differ
	 */
	static void assertImplicitLocalesConsistent(
		@Nullable Locale currentImplicitLocale,
		@Nonnull EvitaRequest evitaRequest
	) {
		Assert.isPremiseValid(
			evitaRequest.getImplicitLocale() == null ||
				currentImplicitLocale == null ||
				Objects.equals(currentImplicitLocale, evitaRequest.getImplicitLocale()),
			"Implicit locales cannot differ (`" + currentImplicitLocale + "` vs. `" + evitaRequest.getImplicitLocale() + "`)!"
		);
	}

	/**
	 * Returns the single requested locale, or null if none or multiple locales are set.
	 *
	 * Priority: locale -> implicitLocale -> single-element locales set.
	 *
	 * @param locale the resolved single locale, if any
	 * @param implicitLocale the implicit locale, if any
	 * @param locales the set of explicitly requested locales, if any
	 * @return the single requested locale or null
	 */
	@Nullable
	static Locale getRequestedLocale(
		@Nullable Locale locale,
		@Nullable Locale implicitLocale,
		@Nullable Set<Locale> locales
	) {
		if (locale != null) {
			return locale;
		} else if (implicitLocale != null) {
			return implicitLocale;
		} else if (locales != null && locales.size() == 1) {
			return locales.iterator().next();
		} else {
			return null;
		}
	}

	/**
	 * Checks whether any locale information is present.
	 *
	 * @param locale the resolved single locale
	 * @param implicitLocale the implicit locale
	 * @param locales the set of explicitly requested locales
	 * @return true if any of the three locale sources is non-null
	 */
	static boolean isLocaleSet(
		@Nullable Locale locale,
		@Nullable Locale implicitLocale,
		@Nullable Set<Locale> locales
	) {
		return locale != null || implicitLocale != null || locales != null;
	}

	/**
	 * Returns all locales available (implicit + explicit combined).
	 *
	 * If only an implicit locale is set (no explicit locales), returns a singleton set.
	 * If both are set, returns a merged set. If only explicit locales are set, returns them.
	 * If neither is set, returns null.
	 *
	 * @param implicitLocale the implicit locale, if any
	 * @param locales the set of explicitly requested locales, if any
	 * @return a set of all locales, or null if no locales are defined
	 */
	@Nullable
	static Set<Locale> getAllLocales(
		@Nullable Locale implicitLocale,
		@Nullable Set<Locale> locales
	) {
		if (implicitLocale != null && locales == null) {
			return Set.of(implicitLocale);
		} else if (implicitLocale != null) {
			final Set<Locale> allLocales = new HashSet<>(locales.size() + 1);
			allLocales.add(implicitLocale);
			allLocales.addAll(locales);
			return allLocales;
		} else {
			return locales;
		}
	}
}
