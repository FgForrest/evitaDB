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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.SerializablePredicate;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Serializable predicate that filters entity attributes based on query requirements.
 *
 * This predicate controls which attributes are visible to clients by filtering based on attribute names and
 * locales specified in the query. It supports:
 * - Name-based filtering (specific attributes or all attributes)
 * - Locale-based filtering (specific locales, implicit locale, or all locales)
 * - Combined name + locale filtering for localized attributes
 *
 * The predicate is used by {@link EntityDecorator} to ensure that only requested attributes are exposed in
 * entity data, even when the underlying cached entity contains additional attributes. This enables efficient
 * entity caching while maintaining query-specific data visibility contracts.
 *
 * **Thread-safety**: This class is immutable and thread-safe.
 *
 * **Underlying predicate pattern**: Supports an optional underlying predicate that represents the original entity's
 * complete attribute scope. This pattern is used when creating limited views from fully-fetched entities.
 *
 * **Empty set semantics**: An empty `attributeSet` means "all attributes are allowed" when `requiresEntityAttributes`
 * is true. Similarly, an empty `locales` set means "all locales are allowed".
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeValueSerializablePredicate implements SerializablePredicate<AttributeValue> {
	public static final AttributeValueSerializablePredicate DEFAULT_INSTANCE = new AttributeValueSerializablePredicate(null, null, Collections.emptySet(), Collections.emptySet(), true);
	@Serial private static final long serialVersionUID = 2628834850476260927L;
	/**
	 * Single resolved locale for the entity, derived from `implicitLocale` or `locales` when exactly one locale
	 * is present. Used for single-locale entity access patterns. May be null if multiple locales are requested.
	 */
	@Nullable @Getter private final Locale locale;
	/**
	 * Implicitly derived locale determined from query context or defaults. Takes precedence over explicit locales
	 * when evaluating localized attribute visibility. May be null if no implicit locale was derived.
	 */
	@Nullable @Getter private final Locale implicitLocale;
	/**
	 * Set of explicitly requested locales from the query. An empty set means all locales are allowed; null means
	 * no locales were requested. Used in conjunction with `implicitLocale` for filtering localized attributes.
	 */
	@Nullable private final Set<Locale> locales;
	/**
	 * Set of explicitly requested attribute names from the query. An empty set means all attributes are allowed
	 * (when `requiresEntityAttributes` is true); otherwise specific attributes are filtered by name.
	 */
	@Nonnull @Getter private final Set<String> attributeSet;
	/**
	 * Indicates whether any attributes were requested with the entity. When false, all attribute access will fail.
	 * When true, attributes are accessible subject to name and locale filtering.
	 */
	@Getter private final boolean requiresEntityAttributes;
	/**
	 * Optional underlying predicate representing the complete entity's attribute scope. Used when creating
	 * limited views from fully-fetched entities via
	 * {@link io.evitadb.api.EntityCollectionContract#limitEntity(EntityContract, EvitaRequest, EvitaSessionContract)}.
	 * Must not be nested (only one level allowed).
	 */
	@Nullable @Getter private final AttributeValueSerializablePredicate underlyingPredicate;

	/**
	 * Creates a default predicate with attribute access disabled.
	 */
	public AttributeValueSerializablePredicate() {
		this.requiresEntityAttributes = false;
		this.attributeSet = Collections.emptySet();
		this.locale = null;
		this.implicitLocale = null;
		this.locales = null;
		this.underlyingPredicate = null;
	}

	/**
	 * Creates an attribute predicate from an Evita request.
	 *
	 * Extracts attribute name requirements, locale requirements, and derives the single locale if applicable.
	 * The single `locale` field is populated only when exactly one locale is specified (either implicitly or
	 * explicitly), enabling optimized single-locale access patterns.
	 *
	 * @param evitaRequest the request containing attribute and locale requirements
	 */
	public AttributeValueSerializablePredicate(@Nonnull EvitaRequest evitaRequest) {
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		// Derive single locale from implicit locale, explicit locale, or single-element locale set
		this.locale = ofNullable(this.implicitLocale)
			.orElseGet(
				() -> ofNullable(evitaRequest.getLocale())
					.orElseGet(() -> this.locales != null && this.locales.size() == 1 ? this.locales.iterator().next() : null)
			);
		this.attributeSet = evitaRequest.getEntityAttributeSet();
		this.requiresEntityAttributes = evitaRequest.isRequiresEntityAttributes();
		this.underlyingPredicate = null;
	}

	/**
	 * Creates an attribute predicate with an underlying predicate for entity limitation scenarios.
	 *
	 * This constructor is used when applying additional restrictions to an already-fetched entity
	 * (e.g., when calling `limitEntity`). The underlying predicate preserves the original fetch scope.
	 *
	 * @param evitaRequest the request containing new attribute and locale requirements
	 * @param underlyingPredicate the predicate representing the original entity's complete attribute scope
	 * @throws io.evitadb.exception.GenericEvitaInternalError if underlyingPredicate is already nested
	 */
	public AttributeValueSerializablePredicate(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull AttributeValueSerializablePredicate underlyingPredicate
	) {
		Assert.isPremiseValid(
			underlyingPredicate.getUnderlyingPredicate() == null,
			"Underlying predicates cannot be nested! " +
				"Underlying predicate composition expects to be maximally one: " +
				"limited view -> complete view and never limited view -> limited view -> complete view."
		);
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		// Derive single locale from implicit locale, explicit locale, or single-element locale set
		this.locale = ofNullable(this.implicitLocale)
			.orElseGet(
				() -> ofNullable(evitaRequest.getLocale())
					.orElseGet(() -> this.locales != null && this.locales.size() == 1 ? this.locales.iterator().next() : null)
			);
		this.attributeSet = evitaRequest.getEntityAttributeSet();
		this.requiresEntityAttributes = evitaRequest.isRequiresEntityAttributes();
		this.underlyingPredicate = underlyingPredicate;
	}

	/**
	 * Package-private constructor for creating predicates with specific attribute and locale configuration.
	 *
	 * Used internally for creating enriched copies via {@link #createRicherCopyWith(EvitaRequest)}.
	 *
	 * @param implicitLocale the implicit locale, if any
	 * @param locale the single resolved locale, if exactly one locale is specified
	 * @param locales the set of explicitly requested locales, if any
	 * @param attributeSet the set of attribute names to include
	 * @param requiresEntityAttributes whether attributes are required at all
	 */
	AttributeValueSerializablePredicate(
		@Nullable Locale implicitLocale,
		@Nullable Locale locale,
		@Nullable Set<Locale> locales,
		@Nonnull Set<String> attributeSet,
		boolean requiresEntityAttributes
	) {
		this.implicitLocale = implicitLocale;
		this.locales = locales;
		this.locale = locale;
		this.attributeSet = attributeSet;
		this.requiresEntityAttributes = requiresEntityAttributes;
		this.underlyingPredicate = null;
	}

	/**
	 * Checks whether any attributes were fetched with the entity.
	 *
	 * @return true if attributes are accessible (subject to name/locale filtering)
	 */
	public boolean wasFetched() {
		return this.requiresEntityAttributes;
	}

	/**
	 * Checks whether attributes in the specified locale were fetched with the entity.
	 *
	 * An empty `locales` set means all locales were fetched; null means no locales were requested.
	 *
	 * @param locale the locale to check
	 * @return true if attributes in this locale are accessible
	 */
	public boolean wasFetched(@Nonnull Locale locale) {
		return this.locales != null && (this.locales.isEmpty() || this.locales.contains(locale));
	}

	/**
	 * Checks whether an attribute with the specified name was fetched with the entity.
	 *
	 * An empty `attributeSet` means all attributes were fetched (when `requiresEntityAttributes` is true).
	 *
	 * @param attributeName the attribute name to check
	 * @return true if the attribute is accessible
	 */
	public boolean wasFetched(@Nonnull String attributeName) {
		return this.requiresEntityAttributes && (this.attributeSet.isEmpty() || this.attributeSet.contains(attributeName));
	}

	/**
	 * Checks whether a localized attribute with the specified name and locale was fetched with the entity.
	 *
	 * Combines both attribute name and locale filtering logic.
	 *
	 * @param attributeName the attribute name to check
	 * @param locale the locale to check
	 * @return true if the localized attribute is accessible
	 */
	public boolean wasFetched(@Nonnull String attributeName, @Nonnull Locale locale) {
		return (this.requiresEntityAttributes && (this.attributeSet.isEmpty() || this.attributeSet.contains(attributeName))) &&
			(this.locales != null && (this.locales.isEmpty() || this.locales.contains(locale)));
	}

	/**
	 * Verifies that attributes were fetched with the entity, throwing an exception if not.
	 *
	 * This method should be called before accessing any attribute data to ensure the data is available.
	 *
	 * @throws ContextMissingException if no attributes were fetched with the entity
	 */
	public void checkFetched() throws ContextMissingException {
		if (!this.requiresEntityAttributes) {
			throw ContextMissingException.attributeContextMissing();
		}
	}

	/**
	 * Verifies that a specific attribute was fetched with the entity, throwing an exception if not.
	 *
	 * Checks both attribute name availability and locale availability (for localized attributes).
	 * This method should be called before accessing specific attribute data to ensure the data is available.
	 *
	 * @param attributeKey the attribute key identifying the attribute by name and optionally locale
	 * @throws ContextMissingException if the attribute or its locale was not fetched with the entity
	 */
	public void checkFetched(@Nonnull AttributeKey attributeKey) throws ContextMissingException {
		if (!(this.requiresEntityAttributes && (this.attributeSet.isEmpty() || this.attributeSet.contains(attributeKey.attributeName())))) {
			throw ContextMissingException.attributeContextMissing(attributeKey.attributeName());
		}
		final Locale akLocale = attributeKey.locale();
		if (akLocale != null && !(Objects.equals(this.locale, akLocale) || this.locales != null && (this.locales.isEmpty() || this.locales.contains(akLocale)))) {
			throw ContextMissingException.attributeLocalizationContextMissing(
				attributeKey.attributeName(),
				akLocale,
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
	 * Tests whether the given attribute value should be visible based on query requirements.
	 *
	 * An attribute passes the test if all of the following conditions are met:
	 * - Attributes are required (`requiresEntityAttributes` is true)
	 * - The attribute exists (not dropped)
	 * - The attribute name matches (if `attributeSet` is non-empty)
	 * - For localized attributes: the locale matches implicit locale, OR explicit locales are empty/contain it
	 *
	 * @param attributeValue the attribute value to test
	 * @return true if the attribute should be visible to the client
	 */
	@Override
	public boolean test(AttributeValue attributeValue) {
		if (this.requiresEntityAttributes) {
			final AttributeKey key = attributeValue.key();
			final Locale attributeLocale = attributeValue.key().locale();
			return attributeValue.exists() &&
				(
					!key.localized() ||
						(this.locales != null && (this.locales.isEmpty() || this.locales.contains(attributeLocale))) ||
						(this.implicitLocale != null && Objects.equals(this.implicitLocale, attributeLocale))
				) &&
				(this.attributeSet.isEmpty() || this.attributeSet.contains(key.attributeName()));
		} else {
			return false;
		}
	}

	/**
	 * Creates an enriched copy that combines this predicate's attribute scope with additional requirements.
	 *
	 * This method is used when progressively enriching an entity with more data. If the new request doesn't
	 * add any new attribute or locale requirements, returns this instance for efficiency. The implicit locale
	 * cannot change between enrichments.
	 *
	 * Enrichment logic:
	 * - Attributes: Union of existing and new attribute sets (empty set dominates as "all attributes")
	 * - Locales: Union of existing and new locale sets
	 * - RequiresAttributes: OR logic (once true, stays true)
	 *
	 * @param evitaRequest the request containing additional attribute and locale requirements to merge
	 * @return an enriched predicate, or this instance if no changes are needed
	 * @throws io.evitadb.exception.GenericEvitaInternalError if implicit locales differ
	 */
	@Nonnull
	public AttributeValueSerializablePredicate createRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		final Set<Locale> requiredLocales = PredicateLocaleHelper.combineLocales(this.locales, evitaRequest);
		final Set<String> requiredAttributeSet = combineAttributes(evitaRequest);
		PredicateLocaleHelper.assertImplicitLocalesConsistent(this.implicitLocale, evitaRequest);

		if ((this.requiresEntityAttributes || this.requiresEntityAttributes == evitaRequest.isRequiresEntityAttributes()) &&
			Objects.equals(this.locales, requiredLocales) &&
			Objects.equals(this.attributeSet, requiredAttributeSet) &&
			(Objects.equals(this.implicitLocale, evitaRequest.getImplicitLocale()) || evitaRequest.getImplicitLocale() == null)) {
			return this;
		} else {
			return new AttributeValueSerializablePredicate(
				PredicateLocaleHelper.resolveImplicitLocale(this.implicitLocale, evitaRequest),
				PredicateLocaleHelper.resolveLocale(this.locale, evitaRequest),
				requiredLocales,
				requiredAttributeSet,
				evitaRequest.isRequiresEntityAttributes() || this.requiresEntityAttributes
			);
		}
	}

	/**
	 * Returns all locales available in this predicate (implicit + explicit).
	 *
	 * Combines implicit locale with the explicit locale set for a complete view of all accessible locales.
	 *
	 * @return a set of all locales, or null if no locales are defined
	 */
	@Nullable
	public Set<Locale> getAllLocales() {
		return PredicateLocaleHelper.getAllLocales(this.implicitLocale, this.locales);
	}

	/**
	 * Returns the single requested locale, or null if none or multiple locales are set.
	 *
	 * This method is used when the client expects exactly one locale to be present. The locale is derived from
	 * (in priority order): `locale`, `implicitLocale`, or single-element `locales` set.
	 *
	 * @return the single requested locale or null
	 */
	@Nullable
	public Locale getRequestedLocale() {
		return PredicateLocaleHelper.getRequestedLocale(this.locale, this.implicitLocale, this.locales);
	}

	/**
	 * Combines existing attribute names with newly requested attributes from an Evita request.
	 *
	 * If either set is empty (meaning "all attributes"), the empty set is preserved. Otherwise, both sets
	 * are merged to form a union.
	 *
	 * @param evitaRequest the request containing potentially new attribute requirements
	 * @return the combined set of attribute names
	 */
	@Nonnull
	private Set<String> combineAttributes(@Nonnull EvitaRequest evitaRequest) {
		Set<String> requiredAttributeSet;
		final Set<String> newlyRequiredAttributeSet = evitaRequest.getEntityAttributeSet();
		if (this.requiresEntityAttributes && evitaRequest.isRequiresEntityAttributes()) {
			// Both require attributes — merge sets (empty set means "all" and dominates)
			if (this.attributeSet.isEmpty()) {
				requiredAttributeSet = this.attributeSet;
			} else if (newlyRequiredAttributeSet.isEmpty()) {
				requiredAttributeSet = newlyRequiredAttributeSet;
			} else {
				requiredAttributeSet = new HashSet<>(this.attributeSet.size() + newlyRequiredAttributeSet.size());
				requiredAttributeSet.addAll(this.attributeSet);
				requiredAttributeSet.addAll(newlyRequiredAttributeSet);
			}
		} else if (this.requiresEntityAttributes) {
			requiredAttributeSet = this.attributeSet;
		} else {
			requiredAttributeSet = newlyRequiredAttributeSet;
		}
		return requiredAttributeSet;
	}

}
