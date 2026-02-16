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

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.requestResponse.EvitaRequest.AttributeRequest;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.structure.SerializablePredicate;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Serializable predicate that filters reference attributes based on query requirements.
 *
 * This predicate controls which attributes of entity references are visible to clients by filtering based on
 * attribute names and locales specified in the reference-specific query constraints. Unlike
 * {@link AttributeValueSerializablePredicate} which handles entity-level attributes, this predicate is scoped
 * to attributes within a specific reference.
 *
 * The predicate supports:
 * - Name-based filtering (specific attributes or all attributes within the reference)
 * - Locale-based filtering (specific locales, implicit locale, or all locales)
 * - Combined name + locale filtering for localized reference attributes
 *
 * Reference attributes are attributes attached to references (relationships between entities), not to entities
 * themselves. For example, a "product -> category" reference might have a "priority" attribute on the reference.
 *
 * **Thread-safety**: This class is immutable and thread-safe.
 *
 * **No underlying predicate**: Unlike entity-level predicates, reference attribute predicates do not support
 * the underlying predicate pattern, as they are already scoped to individual references.
 *
 * **Empty set semantics**: An empty `attributeSet` in `referenceAttributes` means "all attributes are allowed"
 * when attributes are required. Similarly, an empty `locales` set means "all locales are allowed".
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferenceAttributeValueSerializablePredicate implements SerializablePredicate<AttributeValue> {
	@Serial private static final long serialVersionUID = 2628834850476260927L;
	/**
	 * Single resolved locale for the reference, derived from `implicitLocale` or `locales` when exactly one locale
	 * is present. Used for single-locale reference attribute access patterns. May be null if multiple locales are
	 * requested.
	 */
	@Nullable @Getter private final Locale locale;
	/**
	 * Implicitly derived locale determined from query context or defaults. Takes precedence over explicit locales
	 * when evaluating localized reference attribute visibility. May be null if no implicit locale was derived.
	 */
	@Nullable @Getter private final Locale implicitLocale;
	/**
	 * Set of explicitly requested locales from the query. An empty set means all locales are allowed; null means
	 * no locales were requested. Used in conjunction with `implicitLocale` for filtering localized reference
	 * attributes.
	 */
	@Nullable private final Set<Locale> locales;
	/**
	 * Attribute requirements specific to this reference, containing both the flag indicating whether attributes
	 * are required and the set of specific attribute names (if any). Empty attribute set means all attributes
	 * are allowed when attributes are required.
	 */
	@Nonnull @Getter private final AttributeRequest referenceAttributes;

	/**
	 * Package-private constructor for creating reference attribute predicates with specific configuration.
	 *
	 * Used by {@link ReferenceContractSerializablePredicate} to create predicates for individual references.
	 * The single `locale` field is populated only when exactly one locale is specified.
	 *
	 * @param implicitLocale the implicit locale, if any
	 * @param locales the set of explicitly requested locales, if any
	 * @param referenceAttributes the attribute requirements for this reference
	 */
	ReferenceAttributeValueSerializablePredicate(
		@Nullable Locale implicitLocale,
		@Nullable Set<Locale> locales,
		@Nonnull AttributeRequest referenceAttributes
	) {
		// Derive single locale from implicit locale or single-element locale set
		this.locale = ofNullable(implicitLocale)
			.orElseGet(() -> locales != null && locales.size() == 1 ? locales.iterator().next() : null);
		this.implicitLocale = implicitLocale;
		this.locales = locales;
		this.referenceAttributes = referenceAttributes;
	}

	/**
	 * Checks whether any reference attributes were fetched with the reference.
	 *
	 * @return true if reference attributes are accessible (subject to name/locale filtering)
	 */
	public boolean wasFetched() {
		return this.referenceAttributes.isRequiresEntityAttributes();
	}

	/**
	 * Checks whether reference attributes in the specified locale were fetched with the reference.
	 *
	 * An empty `locales` set means all locales were fetched; null means no locales were requested.
	 *
	 * @param locale the locale to check
	 * @return true if reference attributes in this locale are accessible
	 */
	public boolean wasFetched(@Nonnull Locale locale) {
		return this.locales != null && (this.locales.isEmpty() || this.locales.contains(locale));
	}

	/**
	 * Checks whether a reference attribute with the specified name was fetched with the reference.
	 *
	 * An empty attribute set in `referenceAttributes` means all attributes were fetched (when attributes are
	 * required).
	 *
	 * @param attributeName the attribute name to check
	 * @return true if the reference attribute is accessible
	 */
	public boolean wasFetched(@Nonnull String attributeName) {
		return this.referenceAttributes.isRequiresEntityAttributes() && (this.referenceAttributes.attributeSet().isEmpty() || this.referenceAttributes.attributeSet().contains(attributeName));
	}

	/**
	 * Checks whether a localized reference attribute with the specified name and locale was fetched with
	 * the reference.
	 *
	 * Combines both attribute name and locale filtering logic.
	 *
	 * @param attributeName the attribute name to check
	 * @param locale the locale to check
	 * @return true if the localized reference attribute is accessible
	 */
	public boolean wasFetched(@Nonnull String attributeName, @Nonnull Locale locale) {
		return (this.referenceAttributes.isRequiresEntityAttributes() && (this.referenceAttributes.attributeSet().isEmpty() || this.referenceAttributes.attributeSet().contains(attributeName))) &&
			(this.locales != null && (this.locales.isEmpty() || this.locales.contains(locale)));
	}

	/**
	 * Verifies that reference attributes were fetched with the reference, throwing an exception if not.
	 *
	 * This method should be called before accessing any reference attribute data to ensure the data is available.
	 *
	 * @throws ContextMissingException if no reference attributes were fetched with the reference
	 */
	public void checkFetched() throws ContextMissingException {
		if (!this.referenceAttributes.isRequiresEntityAttributes()) {
			throw ContextMissingException.referenceAttributeContextMissing();
		}
	}

	/**
	 * Verifies that a specific reference attribute was fetched with the reference, throwing an exception if not.
	 *
	 * Checks both attribute name availability and locale availability (for localized attributes). This method
	 * should be called before accessing specific reference attribute data to ensure the data is available.
	 *
	 * @param attributeKey the attribute key identifying the attribute by name and optionally locale
	 * @throws ContextMissingException if the reference attribute or its locale was not fetched with the reference
	 */
	public void checkFetched(@Nonnull AttributeKey attributeKey) throws ContextMissingException {
		if (!(this.referenceAttributes.isRequiresEntityAttributes() && (this.referenceAttributes.attributeSet().isEmpty() || this.referenceAttributes.attributeSet().contains(attributeKey.attributeName())))) {
			throw ContextMissingException.referenceAttributeContextMissing(attributeKey.attributeName());
		}
		final Locale theLocale = attributeKey.locale();
		if (theLocale != null && !(Objects.equals(this.locale, theLocale) || this.locales != null && (this.locales.isEmpty() || this.locales.contains(theLocale)))) {
			throw ContextMissingException.attributeLocalizationContextMissing(
				attributeKey.attributeName(),
				theLocale,
				Stream.concat(
					this.locale == null ? Stream.empty() : Stream.of(this.locale),
					this.locales == null ? Stream.empty() : this.locales.stream()
				).distinct()
			);
		}
	}

	/**
	 * Checks whether any locale information is present in this predicate.
	 *
	 * @return true if locale, implicitLocale, or locales is set
	 */
	public boolean isLocaleSet() {
		return PredicateLocaleHelper.isLocaleSet(this.locale, this.implicitLocale, this.locales);
	}

	/**
	 * Tests whether the given reference attribute value should be visible based on query requirements.
	 *
	 * A reference attribute passes the test if all of the following conditions are met:
	 * - Reference attributes are required (`referenceAttributes.isRequiresEntityAttributes()` is true)
	 * - The attribute exists (not dropped)
	 * - The attribute name matches (if attribute set is non-empty)
	 * - For localized attributes: the locale matches implicit locale, OR explicit locales are empty/contain it
	 *
	 * @param attributeValue the reference attribute value to test
	 * @return true if the reference attribute should be visible to the client
	 */
	@Override
	public boolean test(AttributeValue attributeValue) {
		if (this.referenceAttributes.isRequiresEntityAttributes()) {
			final AttributeKey key = attributeValue.key();
			final Locale attributeLocale = attributeValue.key().locale();
			final Set<String> attributeSet = this.referenceAttributes.attributeSet();
			return attributeValue.exists() &&
			(
				!key.localized() ||
					(this.locales != null && (this.locales.isEmpty() || this.locales.contains(attributeLocale))) ||
					(this.implicitLocale != null && Objects.equals(this.implicitLocale, attributeLocale))
				) &&
				(attributeSet.isEmpty() || attributeSet.contains(key.attributeName()));
		} else {
			return false;
		}
	}
}
