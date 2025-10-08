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
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * This predicate allows limiting number of attributes visible to the client based on query constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class LocaleSerializablePredicate implements SerializablePredicate<Locale> {
	public static final LocaleSerializablePredicate DEFAULT_INSTANCE = new LocaleSerializablePredicate(null, Collections.emptySet());
	@Serial private static final long serialVersionUID = 2628834850476260927L;
	/**
	 * Contains information about implicitly derived locale during entity fetch.
	 */
	@Nullable @Getter private final Locale implicitLocale;
	/**
	 * Contains information about all locales that has been fetched / requested for the entity.
	 */
	@Nullable @Getter private final Set<Locale> locales;
	/**
	 * Contains information about underlying predicate that is bound to the {@link EntityDecorator}. This underlying
	 * predicate represents the scope of the fetched (enriched) entity in its true form (i.e. {@link Entity}) and needs
	 * to be carried around even if {@link EntityCollectionContract#limitEntity(EntityContract, EvitaRequest, EvitaSessionContract)}
	 * is invoked on the entity.
	 */
	@Nullable @Getter private final LocaleSerializablePredicate underlyingPredicate;

	public LocaleSerializablePredicate(@Nonnull EvitaRequest evitaRequest) {
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.underlyingPredicate = null;
	}

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

	LocaleSerializablePredicate(@Nullable Locale implicitLocale, @Nullable Set<Locale> locales) {
		this.implicitLocale = implicitLocale;
		this.locales = locales;
		this.underlyingPredicate = null;
	}

	@Override
	public boolean test(Locale locale) {
		return (this.locales != null && (this.locales.isEmpty() || this.locales.contains(locale))) ||
			(this.implicitLocale != null && Objects.equals(this.implicitLocale, locale));
	}

	public LocaleSerializablePredicate createRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		final Set<Locale> requiredLanguages = combineLocales(evitaRequest);
		Assert.isPremiseValid(
			evitaRequest.getImplicitLocale() == null ||
				this.implicitLocale == null ||
				Objects.equals(this.implicitLocale, evitaRequest.getImplicitLocale()),
			"Implicit locales cannot differ (`" + this.implicitLocale + "` vs. `" + evitaRequest.getImplicitLocale() + "`)!"
		);
		if (Objects.equals(this.locales, requiredLanguages) && (Objects.equals(this.implicitLocale, evitaRequest.getImplicitLocale()) || evitaRequest.getImplicitLocale() == null)) {
			return this;
		} else {
			return new LocaleSerializablePredicate(
				this.implicitLocale == null ? evitaRequest.getImplicitLocale() : this.implicitLocale,
				requiredLanguages
			);
		}
	}

	@Nullable
	private Set<Locale> combineLocales(@Nonnull EvitaRequest evitaRequest) {
		final Set<Locale> requiredLanguages;
		final Set<Locale> newlyRequiredLanguages = evitaRequest.getRequiredLocales();
		if (this.locales == null) {
			requiredLanguages = newlyRequiredLanguages;
		} else if (newlyRequiredLanguages != null) {
			requiredLanguages = new HashSet<>(this.locales.size() + newlyRequiredLanguages.size());
			requiredLanguages.addAll(this.locales);
			requiredLanguages.addAll(newlyRequiredLanguages);
		} else {
			requiredLanguages = this.locales;
		}
		return requiredLanguages;
	}
}
