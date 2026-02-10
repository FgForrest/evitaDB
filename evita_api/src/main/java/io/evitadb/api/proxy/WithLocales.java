/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.proxy;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Set;

/**
 * Provides access to locale information for entities with localized data.
 *
 * This interface can be implemented by client-defined proxy contracts when they need to introspect or work
 * with locale-specific data on entities. evitaDB supports fully localized entities where attributes and
 * associated data can have different values per locale (e.g., product names in English, Czech, German).
 *
 * **Two Views of Locales:**
 *
 * This interface provides two methods that represent different aspects of locale handling:
 *
 * 1. **{@link #allLocales()}**: All locales for which the entity has **any** data, regardless of whether
 *    that data was fetched in the current query. This reflects the complete localization coverage of the
 *    entity in the database.
 *
 * 2. **{@link #locales()}**: Only the locales that were explicitly requested in the query (via `entityLocaleEquals`
 *    constraint) and were successfully fetched. This reflects what data is actually available in the current
 *    entity instance.
 *
 * **Use Cases:**
 *
 * - **Language switching**: Check which locales are available and fetch the entity again with a different locale
 * - **Completeness checking**: Determine if the entity has translations for all required languages
 * - **Conditional rendering**: Show/hide UI elements based on available localized data
 * - **Validation**: Ensure required locales have data before publishing content
 *
 * **Implementation Note:**
 *
 * When a client proxy contract implements this interface, the proxy infrastructure automatically provides
 * the implementation by delegating to the underlying entity's locale tracking mechanisms.
 *
 * **Example Usage:**
 *
 * ```java
 * public interface Product extends WithLocales {
 *     String getName();  // returns name in fetched locale
 *
 *     default boolean hasTranslationFor(Locale locale) {
 *         return allLocales().contains(locale);
 *     }
 *
 *     default boolean isFullyTranslated(Set<Locale> requiredLocales) {
 *         return allLocales().containsAll(requiredLocales);
 *     }
 * }
 * ```
 *
 * @see WithEntitySchema
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface WithLocales {

	/**
	 * Returns all locales for which the entity has any localized data in the database.
	 *
	 * This set represents the complete localization coverage of the entity, regardless of what was fetched
	 * in the current query. It includes all locales that have at least one localized attribute or associated
	 * data value defined for this entity.
	 *
	 * **Note on Fetch Requirements:**
	 *
	 * This information is always available even if specific locale data was not fetched. evitaDB tracks
	 * locale presence separately from the actual localized values.
	 *
	 * @return set of all locales the entity has data for (may be empty if entity has no localized data)
	 */
	@Nonnull
	Set<Locale> allLocales();

	/**
	 * Returns the locales that were actually fetched and are present in this entity instance.
	 *
	 * This set reflects the locales requested in the query via `entityLocaleEquals` constraint. Only data
	 * for these locales is available in the current entity instance. Attempting to access localized attributes
	 * or associated data in other locales will return null or throw an exception depending on the access pattern.
	 *
	 * **Relationship to Query:**
	 *
	 * If the query specified `entityLocaleEquals(Locale.ENGLISH, Locale.GERMAN)`, this method returns a set
	 * containing English and German (assuming the entity has data in those locales). If the entity doesn't
	 * have German data, only English would be in the returned set.
	 *
	 * **Edge Cases:**
	 *
	 * - If no locale constraint was used in the query, this may return an empty set or a default locale
	 *   depending on the entity's configuration
	 * - This set is always a subset (or equal to) {@link #allLocales()}
	 *
	 * @return set of locales that were fetched with this entity instance (may be empty)
	 */
	@Nonnull
	Set<Locale> locales();

}
