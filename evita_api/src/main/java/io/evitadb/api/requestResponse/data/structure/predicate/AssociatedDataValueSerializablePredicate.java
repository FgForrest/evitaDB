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
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.SealedEntity;
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
 * This predicate allows to limit number of associated data visible to the client based on query constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AssociatedDataValueSerializablePredicate implements SerializablePredicate<AssociatedDataValue> {
	public static final AssociatedDataValueSerializablePredicate DEFAULT_INSTANCE = new AssociatedDataValueSerializablePredicate(Collections.emptySet(), Collections.emptySet(), true);
	@Serial private static final long serialVersionUID = 85644932696677698L;
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
	 * to be carried around even if {@link io.evitadb.api.EntityCollectionContract#limitEntity(SealedEntity, EvitaRequest)}
	 * is invoked on the entity.
	 */
	@Nullable @Getter private final AssociatedDataValueSerializablePredicate underlyingPredicate;

	public AssociatedDataValueSerializablePredicate() {
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
		this.locales = evitaRequest.getRequiredLocales();
		this.associatedDataSet = evitaRequest.getEntityAssociatedDataSet();
		this.requiresEntityAssociatedData = evitaRequest.isRequiresEntityAssociatedData();
		this.underlyingPredicate = underlyingPredicate;
	}

	public AssociatedDataValueSerializablePredicate(@Nonnull EvitaRequest evitaRequest) {
		this.locales = evitaRequest.getRequiredLocales();
		this.associatedDataSet = evitaRequest.getEntityAssociatedDataSet();
		this.requiresEntityAssociatedData = evitaRequest.isRequiresEntityAssociatedData();
		this.underlyingPredicate = null;
	}

	AssociatedDataValueSerializablePredicate(@Nullable Set<Locale> locales, @Nonnull Set<String> associatedDataSet, boolean requiresEntityAssociatedData) {
		this.locales = locales;
		this.associatedDataSet = associatedDataSet;
		this.requiresEntityAssociatedData = requiresEntityAssociatedData;
		this.underlyingPredicate = null;
	}

	@Override
	public boolean test(@Nonnull AssociatedDataValue associatedDataValue) {
		if (requiresEntityAssociatedData) {
			final AssociatedDataKey key = associatedDataValue.getKey();
			return associatedDataValue.exists() &&
				(
					!key.isLocalized() ||
						(locales != null && (locales.isEmpty() || locales.contains(key.getLocale())))
				) &&
				(associatedDataSet.isEmpty() || associatedDataSet.contains(key.getAssociatedDataName()));
		} else {
			return false;
		}
	}

	public AssociatedDataValueSerializablePredicate createRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		final Set<Locale> requiredLanguages = combineLocales(evitaRequest);
		final Set<String> requiredAssociatedDataSet = combineAssociatedData(evitaRequest);
		if ((this.requiresEntityAssociatedData || this.requiresEntityAssociatedData == evitaRequest.isRequiresEntityAssociatedData()) &&
			Objects.equals(this.locales, requiredLanguages) &&
			Objects.equals(this.associatedDataSet, requiredAssociatedDataSet)) {
			return this;
		} else {
			return new AssociatedDataValueSerializablePredicate(
				requiredLanguages,
				requiredAssociatedDataSet,
				evitaRequest.isRequiresEntityAssociatedData() || this.requiresEntityAssociatedData
			);
		}
	}

	public boolean wasFetched(@Nonnull AssociatedDataKey associatedDataKey) {
		if (this.requiresEntityAssociatedData) {
			return this.associatedDataSet.contains(associatedDataKey.getAssociatedDataName()) &&
				(associatedDataKey.getLocale() == null || (this.locales != null && this.locales.contains(associatedDataKey.getLocale())));
		} else {
			return false;
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
				requiredAssociatedDataSet.addAll(associatedDataSet);
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
			requiredLanguages = locales;
		}
		return requiredLanguages;
	}
}
