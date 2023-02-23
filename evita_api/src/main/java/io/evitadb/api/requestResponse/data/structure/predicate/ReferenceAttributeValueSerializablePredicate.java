/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data.structure.predicate;

import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
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

/**
 * This predicate allows limiting number of attributes visible to the client based on query constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferenceAttributeValueSerializablePredicate implements SerializablePredicate<AttributeValue> {
	public static final ReferenceAttributeValueSerializablePredicate DEFAULT_INSTANCE = new ReferenceAttributeValueSerializablePredicate(null, Collections.emptySet());
	@Serial private static final long serialVersionUID = 2628834850476260927L;
	/**
	 * Contains information about implicitly derived locale during entity fetch.
	 */
	@Nullable @Getter private final Locale implicitLocale;
	/**
	 * Contains information about all attribute locales that has been fetched / requested for the entity.
	 */
	@Nullable private final Set<Locale> locales;

	public ReferenceAttributeValueSerializablePredicate() {
		this.implicitLocale  = null;
		this.locales = null;
	}

	public ReferenceAttributeValueSerializablePredicate(@Nonnull EvitaRequest evitaRequest) {
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
	}

	ReferenceAttributeValueSerializablePredicate(@Nullable Locale implicitLocale, @Nullable Set<Locale> locales) {
		this.implicitLocale = implicitLocale;
		this.locales = locales;
	}

	public boolean isLocaleSet() {
		return this.implicitLocale != null || this.locales != null;
	}

	@Override
	public boolean test(@Nonnull AttributeValue attributeValue) {
		final AttributeKey key = attributeValue.getKey();
		final Locale attributeLocale = attributeValue.getKey().getLocale();
		return attributeValue.exists() &&
			(
				!key.isLocalized() ||
					(this.locales != null && (this.locales.isEmpty() || this.locales.contains(attributeLocale))) ||
					(this.implicitLocale != null && Objects.equals(this.implicitLocale, attributeLocale))
			);
	}

	public ReferenceAttributeValueSerializablePredicate createRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		final Set<Locale> requiredLocales = combineLocales(evitaRequest);
		Assert.isPremiseValid(
			evitaRequest.getImplicitLocale() == null ||
				this.implicitLocale == null ||
				Objects.equals(this.implicitLocale, evitaRequest.getImplicitLocale()),
			"Implicit locales cannot differ (`" + this.implicitLocale + "` vs. `" + evitaRequest.getImplicitLocale() + "`)!"
		);

		if (Objects.equals(this.locales, requiredLocales) &&
			(Objects.equals(this.implicitLocale, evitaRequest.getImplicitLocale()) || evitaRequest.getImplicitLocale() == null)) {
			return this;
		} else {
			return new ReferenceAttributeValueSerializablePredicate(
				implicitLocale == null ? evitaRequest.getImplicitLocale() : implicitLocale,
				requiredLocales
			);
		}
	}

	@Nullable
	public Set<Locale> getAllLocales() {
		if (this.implicitLocale != null && this.locales == null) {
			return Set.of(this.implicitLocale);
		} else if (this.implicitLocale != null) {
			return Stream.concat(
				Stream.of(implicitLocale),
				locales.stream()
			).collect(Collectors.toSet());
		} else {
			return this.locales;
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
			requiredLanguages = locales;
		}
		return requiredLanguages;
	}
}
