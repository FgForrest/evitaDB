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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This predicate allows limiting number of attributes visible to the client based on query constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeValueSerializablePredicate implements SerializablePredicate<AttributeValue> {
	public static final AttributeValueSerializablePredicate DEFAULT_INSTANCE = new AttributeValueSerializablePredicate(null, null, Collections.emptySet(), Collections.emptySet(), true);
	@Serial private static final long serialVersionUID = 2628834850476260927L;
	/**
	 * Contains information about single locale defined for the entity.
	 */
	@Nullable @Getter private final Locale locale;
	/**
	 * Contains information about implicitly derived locale during entity fetch.
	 */
	@Nullable @Getter private final Locale implicitLocale;
	/**
	 * Contains information about all attribute locales that has been fetched / requested for the entity.
	 */
	@Nullable private final Set<Locale> locales;
	/**
	 * Contains information about all attribute names that has been fetched / requested for the entity.
	 */
	@Nonnull @Getter private final Set<String> attributeSet;
	/**
	 * Contains true if any of the attributes of the entity has been fetched / requested.
	 */
	@Getter private final boolean requiresEntityAttributes;
	/**
	 * Contains information about underlying predicate that is bound to the {@link EntityDecorator}. This underlying
	 * predicate represents the scope of the fetched (enriched) entity in its true form (i.e. {@link Entity}) and needs
	 * to be carried around even if {@link io.evitadb.api.EntityCollectionContract#limitEntity(EntityContract, EvitaRequest, EvitaSessionContract)}
	 * is invoked on the entity.
	 */
	@Nullable @Getter private final AttributeValueSerializablePredicate underlyingPredicate;

	public AttributeValueSerializablePredicate() {
		this.requiresEntityAttributes = false;
		this.attributeSet = Collections.emptySet();
		this.locale = null;
		this.implicitLocale  = null;
		this.locales = null;
		this.underlyingPredicate = null;
	}

	public AttributeValueSerializablePredicate(@Nonnull EvitaRequest evitaRequest) {
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.locale = ofNullable(this.implicitLocale)
			.orElseGet(
				() -> ofNullable(evitaRequest.getLocale())
					.orElseGet(() -> this.locales != null && this.locales.size() == 1 ? this.locales.iterator().next() : null)
			);
		this.attributeSet = evitaRequest.getEntityAttributeSet();
		this.requiresEntityAttributes = evitaRequest.isRequiresEntityAttributes();
		this.underlyingPredicate = null;
	}

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
		this.locale = ofNullable(this.implicitLocale)
			.orElseGet(
				() -> ofNullable(evitaRequest.getLocale())
					.orElseGet(() -> this.locales != null && this.locales.size() == 1 ? this.locales.iterator().next() : null)
			);
		this.attributeSet = evitaRequest.getEntityAttributeSet();
		this.requiresEntityAttributes = evitaRequest.isRequiresEntityAttributes();
		this.underlyingPredicate = underlyingPredicate;
	}

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
	 * Returns true if the attributes were fetched along with the entity.
	 */
	public boolean wasFetched() {
		return this.requiresEntityAttributes;
	}

	/**
	 * Returns true if the attributes in specified locale were fetched along with the entity.
	 */
	public boolean wasFetched(@Nonnull Locale locale) {
		return this.locales != null && (this.locales.isEmpty() || this.locales.contains(locale));
	}

	/**
	 * Returns true if the attribute of particular name was fetched along with the entity.
	 */
	public boolean wasFetched(@Nonnull String attributeName) {
		return this.requiresEntityAttributes && (this.attributeSet.isEmpty() || this.attributeSet.contains(attributeName));
	}

	/**
	 * Returns true if the attribute of particular name was in specified locale were fetched along with the entity.
	 */
	public boolean wasFetched(@Nonnull String attributeName, @Nonnull Locale locale) {
		return (this.requiresEntityAttributes && (this.attributeSet.isEmpty() || this.attributeSet.contains(attributeName))) &&
			(this.locales != null && (this.locales.isEmpty() || this.locales.contains(locale)));
	}

	/**
	 * Method verifies that attributes were fetched with the entity.
	 */
	public void checkFetched() throws ContextMissingException {
		if (!this.requiresEntityAttributes) {
			throw ContextMissingException.attributeContextMissing();
		}
	}

	/**
	 * Method verifies that the requested attribute was fetched with the entity.
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

	public boolean isLocaleSet() {
		return this.locale != null || this.implicitLocale != null || this.locales != null;
	}

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

	public AttributeValueSerializablePredicate createRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		final Set<Locale> requiredLocales = combineLocales(evitaRequest);
		final Set<String> requiredAttributeSet = combineAttributes(evitaRequest);
		Assert.isPremiseValid(
			evitaRequest.getImplicitLocale() == null ||
				this.implicitLocale == null ||
				Objects.equals(this.implicitLocale, evitaRequest.getImplicitLocale()),
			"Implicit locales cannot differ (`" + this.implicitLocale + "` vs. `" + evitaRequest.getImplicitLocale() + "`)!"
		);

		if ((this.requiresEntityAttributes || this.requiresEntityAttributes == evitaRequest.isRequiresEntityAttributes()) &&
			Objects.equals(this.locales, requiredLocales) &&
			Objects.equals(this.attributeSet, requiredAttributeSet) &&
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
			return new AttributeValueSerializablePredicate(
				resultImplicitLocale,
				resultLocale,
				requiredLocales,
				requiredAttributeSet,
				evitaRequest.isRequiresEntityAttributes() || this.requiresEntityAttributes
			);
		}
	}

	@Nullable
	public Set<Locale> getAllLocales() {
		if (this.implicitLocale != null && this.locales == null) {
			return Set.of(this.implicitLocale);
		} else if (this.implicitLocale != null) {
			return Stream.concat(
				Stream.of(this.implicitLocale),
				this.locales.stream()
			).collect(Collectors.toSet());
		} else {
			return this.locales;
		}
	}

	private Set<String> combineAttributes(@Nonnull EvitaRequest evitaRequest) {
		Set<String> requiredAttributeSet;
		final Set<String> newlyRequiredAttributeSet = evitaRequest.getEntityAttributeSet();
		if (this.requiresEntityAttributes && evitaRequest.isRequiresEntityAttributes()) {
			if (this.attributeSet.isEmpty()) {
				requiredAttributeSet = this.attributeSet;
			} else if (newlyRequiredAttributeSet.isEmpty()) {
				requiredAttributeSet = newlyRequiredAttributeSet;
			} else {
				requiredAttributeSet = new HashSet<>(this.attributeSet.size(), newlyRequiredAttributeSet.size());
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
