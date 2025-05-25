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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This predicate allows to limit number of associated data visible to the client based on query constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AssociatedDataValueSerializablePredicate implements SerializablePredicate<AssociatedDataValue> {
	public static final AssociatedDataValueSerializablePredicate DEFAULT_INSTANCE = new AssociatedDataValueSerializablePredicate(
		null, null, Collections.emptySet(), Collections.emptySet(), true
	);
	@Serial private static final long serialVersionUID = 85644932696677698L;
	/**
	 * Contains information about single locale defined for the entity.
	 */
	@Nullable @Getter private final Locale locale;
	/**
	 * Contains information about implicitly derived locale during entity fetch.
	 */
	@Nullable @Getter private final Locale implicitLocale;
	/**
	 * Contains information about all locales of the associated data that has been fetched / requested for the entity.
	 */
	@Nullable @Getter private final Set<Locale> locales;
	/**
	 * Contains information about all associated data names that has been fetched / requested for the entity.
	 */
	@Nonnull @Getter private final Set<String> associatedDataSet;
	/**
	 * Contains true if any of the associated data of the entity has been fetched / requested.
	 */
	@Getter private final boolean requiresEntityAssociatedData;
	/**
	 * Contains information about underlying predicate that is bound to the {@link EntityDecorator}. This underlying
	 * predicate represents the scope of the fetched (enriched) entity in its true form (i.e. {@link Entity}) and needs
	 * to be carried around even if {@link EntityCollectionContract#limitEntity(EntityContract, EvitaRequest, EvitaSessionContract)}
	 * is invoked on the entity.
	 */
	@Nullable @Getter private final AssociatedDataValueSerializablePredicate underlyingPredicate;

	public AssociatedDataValueSerializablePredicate() {
		this.locale = null;
		this.implicitLocale = null;
		this.locales = null;
		this.associatedDataSet = Collections.emptySet();
		this.requiresEntityAssociatedData = false;
		this.underlyingPredicate = null;
	}

	public AssociatedDataValueSerializablePredicate(@Nonnull EvitaRequest evitaRequest, @Nonnull AssociatedDataValueSerializablePredicate underlyingPredicate) {
		Assert.isPremiseValid(
			underlyingPredicate.getUnderlyingPredicate() == null,
			"Underlying predicates cannot be nested! " +
				"Underlying predicate composition expects to be maximally one: " +
				"limited view -> complete view and never limited view -> limited view -> complete view."
		);
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.locale = Optional.ofNullable(this.implicitLocale)
			.orElseGet(() -> this.locales != null && this.locales.size() == 1 ? this.locales.iterator().next() : null);
		this.associatedDataSet = evitaRequest.getEntityAssociatedDataSet();
		this.requiresEntityAssociatedData = evitaRequest.isRequiresEntityAssociatedData();
		this.underlyingPredicate = underlyingPredicate;
	}

	public AssociatedDataValueSerializablePredicate(@Nonnull EvitaRequest evitaRequest) {
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.locale = Optional.ofNullable(this.implicitLocale)
			.orElseGet(() -> this.locales != null && this.locales.size() == 1 ? this.locales.iterator().next() : null);
		this.associatedDataSet = evitaRequest.getEntityAssociatedDataSet();
		this.requiresEntityAssociatedData = evitaRequest.isRequiresEntityAssociatedData();
		this.underlyingPredicate = null;
	}

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
	 * Returns true if the associated data were fetched along with the entity.
	 */
	public boolean wasFetched() {
		return this.requiresEntityAssociatedData;
	}

	/**
	 * Returns true if the associated data in specified locale were fetched along with the entity.
	 */
	public boolean wasFetched(@Nonnull Locale locale) {
		return this.locales != null && (this.locales.isEmpty() || this.locales.contains(locale));
	}

	/**
	 * Method verifies that associated data was fetched with the entity.
	 */
	public void checkFetched() throws ContextMissingException {
		if (!this.requiresEntityAssociatedData) {
			throw ContextMissingException.associatedDataContextMissing();
		}
	}

	/**
	 * Returns true if the associated data of particular name was fetched along with the entity.
	 */
	public boolean wasFetched(@Nonnull String attributeName) {
		return this.requiresEntityAssociatedData && (this.associatedDataSet.isEmpty() || this.associatedDataSet.contains(attributeName));
	}

	/**
	 * Returns true if the associated data of particular name was in specified locale were fetched along with the entity.
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
			return this.associatedDataSet.contains(associatedDataKey.associatedDataName()) &&
				(associatedDataKey.locale() == null || (this.locales != null && this.locales.contains(associatedDataKey.locale())));
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
		return this.locale != null || this.implicitLocale != null || this.locales != null;
	}

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

	public AssociatedDataValueSerializablePredicate createRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		final Set<Locale> requiredLocales = combineLocales(evitaRequest);
		final Set<String> requiredAssociatedDataSet = combineAssociatedData(evitaRequest);
		Assert.isPremiseValid(
			evitaRequest.getImplicitLocale() == null ||
				this.implicitLocale == null ||
				Objects.equals(this.implicitLocale, evitaRequest.getImplicitLocale()),
			"Implicit locales cannot differ (`" + this.implicitLocale + "` vs. `" + evitaRequest.getImplicitLocale() + "`)!"
		);

		if ((this.requiresEntityAssociatedData || this.requiresEntityAssociatedData == evitaRequest.isRequiresEntityAssociatedData()) &&
			Objects.equals(this.locales, requiredLocales) &&
			Objects.equals(this.associatedDataSet, requiredAssociatedDataSet) &&
			(Objects.equals(this.implicitLocale, evitaRequest.getImplicitLocale()) || evitaRequest.getImplicitLocale() == null)) {
			return this;
		} else {
			final Locale resultImplicitLocale = this.implicitLocale == null ? evitaRequest.getImplicitLocale() : this.implicitLocale;
			final Locale resultLocale = this.locale == null ?
				ofNullable(evitaRequest.getImplicitLocale())
					.orElseGet(
						() -> ofNullable(evitaRequest.getLocale())
							.orElseGet(() -> evitaRequest.getRequiredLocales() != null && evitaRequest.getRequiredLocales().size() == 1 ? evitaRequest.getRequiredLocales().iterator().next() : null)
					) : this.locale;
			return new AssociatedDataValueSerializablePredicate(
				resultImplicitLocale,
				resultLocale,
				requiredLocales,
				requiredAssociatedDataSet,
				evitaRequest.isRequiresEntityAssociatedData() || this.requiresEntityAssociatedData
			);
		}
	}

	private Set<String> combineAssociatedData(@Nonnull EvitaRequest evitaRequest) {
		Set<String> requiredAssociatedDataSet;
		final Set<String> newlyRequiredAssociatedDataSet = evitaRequest.getEntityAssociatedDataSet();
		if (this.requiresEntityAssociatedData && evitaRequest.isRequiresEntityAssociatedData()) {
			if (this.associatedDataSet.isEmpty()) {
				requiredAssociatedDataSet = this.associatedDataSet;
			} else if (newlyRequiredAssociatedDataSet.isEmpty()) {
				requiredAssociatedDataSet = newlyRequiredAssociatedDataSet;
			} else {
				requiredAssociatedDataSet = new HashSet<>(this.associatedDataSet.size(), newlyRequiredAssociatedDataSet.size());
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
