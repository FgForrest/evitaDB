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
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.SerializablePredicate;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Serializable predicate that filters entity locales based on query requirements.
 *
 * This predicate controls which locales are visible to clients by filtering based on the locale constraints
 * specified in the query. It supports both explicit locale requirements (specific locales requested) and implicit
 * locale derivation (automatically determined from context). The predicate is used by {@link EntityDecorator} to
 * ensure that only requested locales are exposed in entity data, even when the underlying cached entity contains
 * data for additional locales.
 *
 * **Thread-safety**: This class is immutable and thread-safe.
 *
 * **Underlying predicate pattern**: Supports an optional underlying predicate that represents the original entity's
 * complete locale scope. This allows the system to distinguish between a limited view (client-facing) and the
 * complete view (internal cached entity). Nesting is limited to one level: limited view -> complete view.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class LocaleSerializablePredicate implements SerializablePredicate<Locale> {
	public static final LocaleSerializablePredicate DEFAULT_INSTANCE = new LocaleSerializablePredicate(null, Collections.emptySet());
	@Serial private static final long serialVersionUID = 2628834850476260927L;
	/**
	 * Implicitly derived locale determined from query context or defaults. Takes precedence when evaluating
	 * locale visibility. May be null if no implicit locale was derived.
	 */
	@Nullable @Getter private final Locale implicitLocale;
	/**
	 * Set of explicitly requested locales from the query. An empty set means all locales are allowed;
	 * null means no locales were requested. Used in conjunction with `implicitLocale` for filtering.
	 */
	@Nullable @Getter private final Set<Locale> locales;
	/**
	 * Optional underlying predicate representing the complete entity's locale scope. Used when creating
	 * limited views from fully-fetched entities via
	 * {@link EntityCollectionContract#limitEntity(EntityContract, EvitaRequest, EvitaSessionContract)}.
	 * This ensures the original fetched scope is preserved. Must not be nested (only one level allowed).
	 */
	@Nullable @Getter private final LocaleSerializablePredicate underlyingPredicate;

	/**
	 * Creates a locale predicate from an Evita request.
	 *
	 * Extracts implicit and explicit locale requirements from the request. This constructor is typically used
	 * when building entity decorators for query responses.
	 *
	 * @param evitaRequest the request containing locale requirements
	 */
	public LocaleSerializablePredicate(@Nonnull EvitaRequest evitaRequest) {
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.underlyingPredicate = null;
	}

	/**
	 * Creates a locale predicate with an underlying predicate for entity limitation scenarios.
	 *
	 * This constructor is used when applying additional restrictions to an already-fetched entity
	 * (e.g., when calling `limitEntity`). The underlying predicate preserves the original fetch scope.
	 *
	 * @param evitaRequest the request containing new locale requirements
	 * @param underlyingPredicate the predicate representing the original entity's complete locale scope
	 * @throws io.evitadb.exception.GenericEvitaInternalError if underlyingPredicate is already nested
	 */
	public LocaleSerializablePredicate(@Nonnull EvitaRequest evitaRequest, @Nonnull LocaleSerializablePredicate underlyingPredicate) {
		Assert.isPremiseValid(
			underlyingPredicate.getUnderlyingPredicate() == null,
			"Underlying predicates cannot be nested! " +
				"Underlying predicate composition expects to be maximally one: " +
				"limited view -> complete view and never limited view -> limited view -> complete view."
		);
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.underlyingPredicate = underlyingPredicate;
	}

	/**
	 * Package-private constructor for creating predicates with specific locale configuration.
	 *
	 * Used internally for creating enriched copies via {@link #createRicherCopyWith(EvitaRequest)}.
	 *
	 * @param implicitLocale the implicit locale, if any
	 * @param locales the set of explicitly requested locales, if any
	 */
	LocaleSerializablePredicate(@Nullable Locale implicitLocale, @Nullable Set<Locale> locales) {
		this.implicitLocale = implicitLocale;
		this.locales = locales;
		this.underlyingPredicate = null;
	}

	/**
	 * Tests whether the given locale should be visible based on query requirements.
	 *
	 * A locale passes the test if:
	 * - It matches the implicit locale (if set), OR
	 * - The explicit locales set is null/empty (meaning all locales allowed), OR
	 * - The explicit locales set contains the locale
	 *
	 * @param locale the locale to test
	 * @return true if the locale should be visible to the client
	 */
	@Override
	public boolean test(Locale locale) {
		return (this.locales != null && (this.locales.isEmpty() || this.locales.contains(locale))) ||
			(this.implicitLocale != null && Objects.equals(this.implicitLocale, locale));
	}

	/**
	 * Creates an enriched copy that combines this predicate's locale scope with additional requirements.
	 *
	 * This method is used when progressively enriching an entity with more data. If the new request doesn't
	 * add any new locale requirements, returns this instance for efficiency. The implicit locale cannot change
	 * between enrichments.
	 *
	 * @param evitaRequest the request containing additional locale requirements to merge
	 * @return an enriched predicate, or this instance if no changes are needed
	 * @throws io.evitadb.exception.GenericEvitaInternalError if implicit locales differ
	 */
	@Nonnull
	public LocaleSerializablePredicate createRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		final Set<Locale> requiredLanguages = PredicateLocaleHelper.combineLocales(this.locales, evitaRequest);
		PredicateLocaleHelper.assertImplicitLocalesConsistent(this.implicitLocale, evitaRequest);
		if (Objects.equals(this.locales, requiredLanguages) && (Objects.equals(this.implicitLocale, evitaRequest.getImplicitLocale()) || evitaRequest.getImplicitLocale() == null)) {
			return this;
		} else {
			return new LocaleSerializablePredicate(
				PredicateLocaleHelper.resolveImplicitLocale(this.implicitLocale, evitaRequest),
				requiredLanguages
			);
		}
	}

}
