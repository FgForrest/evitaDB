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
import java.util.Optional;
import java.util.Set;

/**
 * This predicate allows limiting number of attributes visible to the client based on query constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferenceAttributeValueSerializablePredicate implements SerializablePredicate<AttributeValue> {
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
	 * Contains information about all attribute names that has been requested for the entity reference.
	 */
	@Nonnull @Getter private final AttributeRequest referenceAttributes;

	ReferenceAttributeValueSerializablePredicate(
		@Nullable Locale implicitLocale,
		@Nullable Set<Locale> locales,
		@Nonnull AttributeRequest referenceAttributes
	) {
		this.locale = Optional.ofNullable(implicitLocale)
			.orElseGet(() -> locales != null && locales.size() == 1 ? locales.iterator().next() : null);
		this.implicitLocale = implicitLocale;
		this.locales = locales;
		this.referenceAttributes = referenceAttributes;
	}

	public boolean isLocaleSet() {
		return this.locale != null || this.implicitLocale != null || this.locales != null;
	}

	@Override
	public boolean test(AttributeValue attributeValue) {
		if (referenceAttributes.isRequiresEntityAttributes()) {
			final AttributeKey key = attributeValue.getKey();
			final Locale attributeLocale = attributeValue.getKey().getLocale();
			final Set<String> attributeSet = referenceAttributes.attributeSet();
			return attributeValue.exists() &&
			(
				!key.isLocalized() ||
					(this.locales != null && (this.locales.isEmpty() || this.locales.contains(attributeLocale))) ||
					(this.implicitLocale != null && Objects.equals(this.implicitLocale, attributeLocale))
				) &&
				(attributeSet.isEmpty() || attributeSet.contains(key.getAttributeName()));
		} else {
			return false;
		}
	}
}
