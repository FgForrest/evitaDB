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

import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
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
 * Serializable predicate that filters entity associated data based on query requirements.
 *
 * This predicate controls which associated data are visible to clients by filtering based on associated data names
 * and locales specified in the query. Associated data are larger data blobs (typically JSON, arrays, or complex
 * objects) that complement entity attributes. The predicate supports:
 * - Name-based filtering (specific associated data or all associated data)
 * - Locale-based filtering (specific locales, implicit locale, or all locales)
 * - Combined name + locale filtering for localized associated data
 *
 * The predicate is used by {@link EntityDecorator} to ensure that only requested associated data are exposed
 * in entity data, even when the underlying cached entity contains additional associated data. This enables
 * efficient entity caching while maintaining query-specific data visibility contracts.
 *
 * **Thread-safety**: This class is immutable and thread-safe.
 *
 * **Underlying predicate pattern**: Supports an optional underlying predicate that represents the original entity's
 * complete associated data scope. This pattern is used when creating limited views from fully-fetched entities.
 *
 * **Empty set semantics**: An empty `associatedDataSet` means "all associated data are allowed" when
 * `requiresEntityAssociatedData` is true. Similarly, an empty `locales` set means "all locales are allowed".
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AssociatedDataValueSerializablePredicate implements SerializablePredicate<AssociatedDataValue> {
	public static final AssociatedDataValueSerializablePredicate DEFAULT_INSTANCE = new AssociatedDataValueSerializablePredicate(
		null, null, Collections.emptySet(), Collections.emptySet(), true
	);
	@Serial private static final long serialVersionUID = 85644932696677698L;
	/**
	 * Single resolved locale for the entity, derived from `implicitLocale` or `locales` when exactly one locale
	 * is present. Used for single-locale entity access patterns. May be null if multiple locales are requested.
	 */
	@Nullable @Getter private final Locale locale;
	/**
	 * Implicitly derived locale determined from query context or defaults. Takes precedence over explicit locales
	 * when evaluating localized associated data visibility. May be null if no implicit locale was derived.
	 */
	@Nullable @Getter private final Locale implicitLocale;
	/**
	 * Set of explicitly requested locales from the query. An empty set means all locales are allowed; null means
	 * no locales were requested. Used in conjunction with `implicitLocale` for filtering localized associated data.
	 */
	@Nullable @Getter private final Set<Locale> locales;
	/**
	 * Set of explicitly requested associated data names from the query. An empty set means all associated data
	 * are allowed (when `requiresEntityAssociatedData` is true); otherwise specific associated data are filtered
	 * by name.
	 */
	@Nonnull @Getter private final Set<String> associatedDataSet;
	/**
	 * Indicates whether any associated data were requested with the entity. When false, all associated data access
	 * will fail. When true, associated data are accessible subject to name and locale filtering.
	 */
	@Getter private final boolean requiresEntityAssociatedData;
	/**
	 * Optional underlying predicate representing the complete entity's associated data scope. Used when creating
	 * limited views from fully-fetched entities via
	 * {@link EntityCollectionContract#limitEntity(EntityContract, EvitaRequest, EvitaSessionContract)}.
	 * Must not be nested (only one level allowed).
	 */
	@Nullable @Getter private final AssociatedDataValueSerializablePredicate underlyingPredicate;

	/**
	 * Creates a default predicate with associated data access disabled.
	 */
	public AssociatedDataValueSerializablePredicate() {
		this.locale = null;
		this.implicitLocale = null;
		this.locales = null;
		this.associatedDataSet = Collections.emptySet();
		this.requiresEntityAssociatedData = false;
		this.underlyingPredicate = null;
	}

	/**
	 * Creates an associated data predicate with an underlying predicate for entity limitation scenarios.
	 *
	 * This constructor is used when applying additional restrictions to an already-fetched entity
	 * (e.g., when calling `limitEntity`). The underlying predicate preserves the original fetch scope.
	 *
	 * @param evitaRequest the request containing new associated data and locale requirements
	 * @param underlyingPredicate the predicate representing the original entity's complete associated data scope
	 * @throws io.evitadb.exception.GenericEvitaInternalError if underlyingPredicate is already nested
	 */
	public AssociatedDataValueSerializablePredicate(@Nonnull EvitaRequest evitaRequest, @Nonnull AssociatedDataValueSerializablePredicate underlyingPredicate) {
		Assert.isPremiseValid(
			underlyingPredicate.getUnderlyingPredicate() == null,
			"Underlying predicates cannot be nested! " +
				"Underlying predicate composition expects to be maximally one: " +
				"limited view -> complete view and never limited view -> limited view -> complete view."
		);
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.locale = ofNullable(this.implicitLocale)
			.orElseGet(() -> this.locales != null && this.locales.size() == 1 ? this.locales.iterator().next() : null);
		this.associatedDataSet = evitaRequest.getEntityAssociatedDataSet();
		this.requiresEntityAssociatedData = evitaRequest.isRequiresEntityAssociatedData();
		this.underlyingPredicate = underlyingPredicate;
	}

	/**
	 * Creates an associated data predicate from an Evita request.
	 *
	 * Extracts associated data name requirements, locale requirements, and derives the single locale if applicable.
	 * The single `locale` field is populated only when exactly one locale is specified (either implicitly or
	 * explicitly), enabling optimized single-locale access patterns.
	 *
	 * @param evitaRequest the request containing associated data and locale requirements
	 */
	public AssociatedDataValueSerializablePredicate(@Nonnull EvitaRequest evitaRequest) {
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		// Derive single locale from implicit locale or single-element locale set
		this.locale = ofNullable(this.implicitLocale)
			.orElseGet(() -> this.locales != null && this.locales.size() == 1 ? this.locales.iterator().next() : null);
		this.associatedDataSet = evitaRequest.getEntityAssociatedDataSet();
		this.requiresEntityAssociatedData = evitaRequest.isRequiresEntityAssociatedData();
		this.underlyingPredicate = null;
	}

	/**
	 * Package-private constructor for creating predicates with specific associated data and locale configuration.
	 *
	 * Used internally for creating enriched copies via {@link #createRicherCopyWith(EvitaRequest)}.
	 *
	 * @param implicitLocale the implicit locale, if any
	 * @param locale the single resolved locale, if exactly one locale is specified
	 * @param locales the set of explicitly requested locales, if any
	 * @param associatedDataSet the set of associated data names to include
	 * @param requiresEntityAssociatedData whether associated data are required at all
	 */
	AssociatedDataValueSerializablePredicate(
		@Nullable Locale implicitLocale,
		@Nullable Locale locale,
		@Nullable Set<Locale> locales,
		@Nonnull Set<String> associatedDataSet,
		boolean requiresEntityAssociatedData
	) {
		this.implicitLocale = implicitLocale;
		this.locales = locales;
		this.locale = locale;
		this.associatedDataSet = associatedDataSet;
		this.requiresEntityAssociatedData = requiresEntityAssociatedData;
		this.underlyingPredicate = null;
	}


	/**
	 * Checks whether any associated data were fetched with the entity.
	 *
	 * @return true if associated data are accessible (subject to name/locale filtering)
	 */
	public boolean wasFetched() {
		return this.requiresEntityAssociatedData;
	}

	/**
	 * Checks whether associated data in the specified locale were fetched with the entity.
	 *
	 * An empty `locales` set means all locales were fetched; null means no locales were requested.
	 *
	 * @param locale the locale to check
	 * @return true if associated data in this locale are accessible
	 */
	public boolean wasFetched(@Nonnull Locale locale) {
		return this.locales != null && (this.locales.isEmpty() || this.locales.contains(locale));
	}

	/**
	 * Verifies that associated data were fetched with the entity, throwing an exception if not.
	 *
	 * This method should be called before accessing any associated data to ensure the data is available.
	 *
	 * @throws ContextMissingException if no associated data were fetched with the entity
	 */
	public void checkFetched() throws ContextMissingException {
		if (!this.requiresEntityAssociatedData) {
			throw ContextMissingException.associatedDataContextMissing();
		}
	}

	/**
	 * Checks whether associated data with the specified name was fetched with the entity.
	 *
	 * An empty `associatedDataSet` means all associated data were fetched (when
	 * `requiresEntityAssociatedData` is true).
	 *
	 * @param attributeName the associated data name to check
	 * @return true if the associated data is accessible
	 */
	public boolean wasFetched(@Nonnull String attributeName) {
		return this.requiresEntityAssociatedData && (this.associatedDataSet.isEmpty() || this.associatedDataSet.contains(attributeName));
	}

	/**
	 * Checks whether localized associated data with the specified name and locale was fetched with the entity.
	 *
	 * Combines both associated data name and locale filtering logic.
	 *
	 * @param attributeName the associated data name to check
	 * @param locale the locale to check
	 * @return true if the localized associated data is accessible
	 */
	public boolean wasFetched(@Nonnull String attributeName, @Nonnull Locale locale) {
		return (this.requiresEntityAssociatedData && (this.associatedDataSet.isEmpty() || this.associatedDataSet.contains(attributeName))) &&
			(this.locales != null && (this.locales.isEmpty() || this.locales.contains(locale)));
	}

	/**
	 * Method return TRUE if associated data with particular `associatedDataKey` was fetched with the entity.
	 */
	public boolean wasFetched(@Nonnull AssociatedDataKey associatedDataKey) {
		if (this.requiresEntityAssociatedData) {
			return (this.associatedDataSet.isEmpty() || this.associatedDataSet.contains(associatedDataKey.associatedDataName())) &&
				(associatedDataKey.locale() == null || (this.locales != null && (this.locales.isEmpty() || this.locales.contains(associatedDataKey.locale()))));
		} else {
			return false;
		}
	}

	/**
	 * Method verifies that the requested associated data was fetched with the entity.
	 */
	public void checkFetched(@Nonnull AssociatedDataKey associatedDataKey) throws ContextMissingException {
		if (!(this.requiresEntityAssociatedData && (this.associatedDataSet.isEmpty() || this.associatedDataSet.contains(associatedDataKey.associatedDataName())))) {
			throw ContextMissingException.associatedDataContextMissing(associatedDataKey.associatedDataName());
		}
		final Locale adkLocale = associatedDataKey.locale();
		if (adkLocale != null && !(Objects.equals(this.locale, adkLocale) || this.locales != null && (this.locales.isEmpty() || this.locales.contains(adkLocale)))) {
			throw ContextMissingException.associatedDataLocalizationContextMissing(
				associatedDataKey.associatedDataName(),
				adkLocale,
				Stream.concat(
					this.locale == null ? Stream.empty() : Stream.of(this.locale),
					this.locales == null ? Stream.empty() : this.locales.stream()
				).distinct()
			);
		}
	}

	public boolean isLocaleSet() {
		return PredicateLocaleHelper.isLocaleSet(this.locale, this.implicitLocale, this.locales);
	}

	/**
	 * Tests whether the given associated data value should be visible based on query requirements.
	 *
	 * An associated data value passes the test if all of the following conditions are met:
	 * - Associated data are required (`requiresEntityAssociatedData` is true)
	 * - The associated data exists (not dropped)
	 * - The associated data name matches (if `associatedDataSet` is non-empty)
	 * - For localized associated data: the locale matches implicit locale, OR explicit locales are empty/contain it
	 *
	 * @param associatedDataValue the associated data value to test
	 * @return true if the associated data should be visible to the client
	 */
	@Override
	public boolean test(AssociatedDataValue associatedDataValue) {
		if (this.requiresEntityAssociatedData) {
			final AssociatedDataKey key = associatedDataValue.key();
			final Locale attributeLocale = associatedDataValue.key().locale();
			return associatedDataValue.exists() &&
				(
					!key.localized() ||
						(this.locales != null && (this.locales.isEmpty() || this.locales.contains(attributeLocale))) ||
						(this.implicitLocale != null && Objects.equals(this.implicitLocale, attributeLocale))
				) &&
				(this.associatedDataSet.isEmpty() || this.associatedDataSet.contains(key.associatedDataName()));
		} else {
			return false;
		}
	}

	/**
	 * Creates an enriched copy that combines this predicate's associated data scope with additional requirements.
	 *
	 * This method is used when progressively enriching an entity with more data. If the new request doesn't
	 * add any new associated data or locale requirements, returns this instance for efficiency. The implicit
	 * locale cannot change between enrichments.
	 *
	 * Enrichment logic:
	 * - Associated data: Union of existing and new associated data sets (empty set dominates as "all data")
	 * - Locales: Union of existing and new locale sets
	 * - RequiresAssociatedData: OR logic (once true, stays true)
	 *
	 * @param evitaRequest the request containing additional associated data and locale requirements to merge
	 * @return an enriched predicate, or this instance if no changes are needed
	 * @throws io.evitadb.exception.GenericEvitaInternalError if implicit locales differ
	 */
	@Nonnull
	public AssociatedDataValueSerializablePredicate createRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		final Set<Locale> requiredLocales = PredicateLocaleHelper.combineLocales(this.locales, evitaRequest);
		final Set<String> requiredAssociatedDataSet = combineAssociatedData(evitaRequest);
		PredicateLocaleHelper.assertImplicitLocalesConsistent(this.implicitLocale, evitaRequest);

		if ((this.requiresEntityAssociatedData || this.requiresEntityAssociatedData == evitaRequest.isRequiresEntityAssociatedData()) &&
			Objects.equals(this.locales, requiredLocales) &&
			Objects.equals(this.associatedDataSet, requiredAssociatedDataSet) &&
			(Objects.equals(this.implicitLocale, evitaRequest.getImplicitLocale()) || evitaRequest.getImplicitLocale() == null)) {
			return this;
		} else {
			return new AssociatedDataValueSerializablePredicate(
				PredicateLocaleHelper.resolveImplicitLocale(this.implicitLocale, evitaRequest),
				PredicateLocaleHelper.resolveLocale(this.locale, evitaRequest),
				requiredLocales,
				requiredAssociatedDataSet,
				evitaRequest.isRequiresEntityAssociatedData() || this.requiresEntityAssociatedData
			);
		}
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
	 * Combines existing associated data names with newly requested associated data from an Evita request.
	 *
	 * If either set is empty (meaning "all associated datas"), the empty set is preserved. Otherwise, both sets
	 * are merged to form a union.
	 *
	 * @param evitaRequest the request containing potentially new associated data requirements
	 * @return the combined set of associated data names
	 */
	@Nonnull
	private Set<String> combineAssociatedData(@Nonnull EvitaRequest evitaRequest) {
		Set<String> requiredAssociatedDataSet;
		final Set<String> newlyRequiredAssociatedDataSet = evitaRequest.getEntityAssociatedDataSet();
		if (this.requiresEntityAssociatedData && evitaRequest.isRequiresEntityAssociatedData()) {
			if (this.associatedDataSet.isEmpty()) {
				requiredAssociatedDataSet = this.associatedDataSet;
			} else if (newlyRequiredAssociatedDataSet.isEmpty()) {
				requiredAssociatedDataSet = newlyRequiredAssociatedDataSet;
			} else {
				requiredAssociatedDataSet = new HashSet<>(this.associatedDataSet.size() + newlyRequiredAssociatedDataSet.size());
				requiredAssociatedDataSet.addAll(this.associatedDataSet);
				requiredAssociatedDataSet.addAll(newlyRequiredAssociatedDataSet);
			}
		} else if (this.requiresEntityAssociatedData) {
			requiredAssociatedDataSet = this.associatedDataSet;
		} else {
			requiredAssociatedDataSet = newlyRequiredAssociatedDataSet;
		}
		return requiredAssociatedDataSet;
	}

}
