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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.AttributeRequest;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.SerializablePredicate;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This predicate allows limiting number of references visible to the client based on query constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferenceContractSerializablePredicate implements SerializablePredicate<ReferenceContract> {
	public static final ReferenceContractSerializablePredicate DEFAULT_INSTANCE = new ReferenceContractSerializablePredicate();
	@Serial private static final long serialVersionUID = -3182607338600238414L;

	/**
	 * Contains information about all reference names that has been fetched / requested for the entity.
	 */
	@Nonnull @Getter private final Map<String, AttributeRequest> referenceSet;
	/**
	 * Contains true if any of the references of the entity has been fetched / requested.
	 */
	@Getter private final boolean requiresEntityReferences;
	/**
	 * Contains information about implicitly derived locale during entity fetch.
	 */
	@Nullable @Getter private final Locale implicitLocale;
	/**
	 * Contains information about all attribute locales that has been fetched / requested for the entity.
	 */
	@Nullable private final Set<Locale> locales;
	/**
	 * Contains information about underlying predicate that is bound to the {@link EntityDecorator}. This underlying
	 * predicate represents the scope of the fetched (enriched) entity in its true form (i.e. {@link Entity}) and needs
	 * to be carried around even if {@link io.evitadb.api.EntityCollectionContract#limitEntity(SealedEntity, EvitaRequest, EvitaSessionContract)}
	 * is invoked on the entity.
	 */
	@Nullable @Getter private final ReferenceContractSerializablePredicate underlyingPredicate;


	public ReferenceContractSerializablePredicate() {
		this.requiresEntityReferences = true;
		this.referenceSet = Collections.emptyMap();
		this.implicitLocale = null;
		this.locales = Collections.emptySet();
		this.underlyingPredicate = null;
	}

	public ReferenceContractSerializablePredicate(@Nonnull EvitaRequest evitaRequest) {
		this.requiresEntityReferences = evitaRequest.isRequiresEntityReferences();
		this.referenceSet = evitaRequest.getReferenceEntityFetch()
			.entrySet()
			.stream()
			.collect(
				Collectors.toMap(
					Map.Entry::getKey,
					entry -> entry.getValue().attributeRequest()
				)
			);
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.underlyingPredicate = null;
	}

	public ReferenceContractSerializablePredicate(boolean requiresEntityReferences) {
		this.requiresEntityReferences = requiresEntityReferences;
		this.referenceSet = Collections.emptyMap();
		this.implicitLocale = null;
		this.locales = Collections.emptySet();
		this.underlyingPredicate = null;
	}

	public ReferenceContractSerializablePredicate(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull ReferenceContractSerializablePredicate underlyingPredicate
	) {
		Assert.isPremiseValid(
			underlyingPredicate.getUnderlyingPredicate() == null,
			"Underlying predicates cannot be nested! " +
				"Underlying predicate composition expects to be maximally one: " +
				"limited view -> complete view and never limited view -> limited view -> complete view."
		);
		this.requiresEntityReferences = evitaRequest.isRequiresEntityReferences();
		this.referenceSet = evitaRequest.getReferenceEntityFetch()
			.entrySet()
			.stream()
			.collect(
				Collectors.toMap(
					Map.Entry::getKey,
					entry -> entry.getValue().attributeRequest()
				)
			);
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.underlyingPredicate = underlyingPredicate;
	}

	ReferenceContractSerializablePredicate(
		@Nonnull Map<String, AttributeRequest> referenceSet,
		boolean requiresEntityReferences,
		@Nullable Locale implicitLocale,
		@Nonnull Set<Locale> locales
	) {
		this.referenceSet = referenceSet;
		this.requiresEntityReferences = requiresEntityReferences;
		this.implicitLocale = implicitLocale;
		this.locales = locales;
		this.underlyingPredicate = null;
	}

	@Override
	public boolean test(ReferenceContract reference) {
		if (requiresEntityReferences) {
			final String referencedEntityType = reference.getReferenceName();
			return reference.exists() &&
				(referenceSet.isEmpty() || referenceSet.containsKey(referencedEntityType));
		} else {
			return false;
		}
	}

	public ReferenceContractSerializablePredicate createRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		final Set<Locale> requiredLocales = combineLocales(evitaRequest);
		Assert.isPremiseValid(
			evitaRequest.getImplicitLocale() == null ||
				this.implicitLocale == null ||
				Objects.equals(this.implicitLocale, evitaRequest.getImplicitLocale()),
			"Implicit locales cannot differ (`" + this.implicitLocale + "` vs. `" + evitaRequest.getImplicitLocale() + "`)!"
		);

		final Map<String, AttributeRequest> requiredReferencedEntities = combineReferencedEntities(evitaRequest);
		final boolean doesRequireEntityReferences = evitaRequest.isRequiresEntityReferences();

		if ((this.requiresEntityReferences || !doesRequireEntityReferences) &&
			Objects.equals(this.referenceSet, requiredReferencedEntities) &&
			Objects.equals(this.implicitLocale, evitaRequest.getImplicitLocale()) &&
			Objects.equals(this.locales, requiredLocales)
		) {
			return this;
		} else {
			return new ReferenceContractSerializablePredicate(
				requiredReferencedEntities,
				this.requiresEntityReferences || doesRequireEntityReferences,
				implicitLocale,
				requiredLocales
			);
		}
	}

	@Nonnull
	public ReferenceAttributeValueSerializablePredicate getAttributePredicate(@Nonnull String referenceName) {
		return new ReferenceAttributeValueSerializablePredicate(
			this.implicitLocale,
			this.locales,
			this.referenceSet.isEmpty() ?
				AttributeRequest.FULL :
				this.referenceSet.getOrDefault(referenceName, AttributeRequest.EMPTY)
		);
	}

	@Nonnull
	private Map<String, AttributeRequest> combineReferencedEntities(@Nonnull EvitaRequest evitaRequest) {
		final Map<String, AttributeRequest> requiredReferences;
		final Map<String, RequirementContext> referenceEntityFetch = evitaRequest.getReferenceEntityFetch();
		if (!this.requiresEntityReferences) {
			requiredReferences = referenceEntityFetch
				.entrySet()
				.stream()
				.collect(
					Collectors.toMap(
						Map.Entry::getKey,
						entry -> entry.getValue().attributeRequest()
					)
				);
		} else if (evitaRequest.isRequiresEntityReferences()) {
			requiredReferences = new HashMap<>(this.referenceSet.size() + referenceEntityFetch.size());
			requiredReferences.putAll(this.referenceSet);
			for (Entry<String, RequirementContext> newEntry : referenceEntityFetch.entrySet()) {
				final AttributeRequest existingAttributeRequest = requiredReferences.get(newEntry.getKey());
				if (existingAttributeRequest == null) {
					requiredReferences.put(newEntry.getKey(), newEntry.getValue().attributeRequest());
				} else {
					requiredReferences.put(
						newEntry.getKey(),
						new AttributeRequest(
							CollectionUtils.combine(existingAttributeRequest.attributeSet(), newEntry.getValue().attributeRequest().attributeSet()),
							existingAttributeRequest.isRequiresEntityAttributes() ||
								newEntry.getValue().attributeRequest().isRequiresEntityAttributes()
						)
					);
				}
			}
		} else {
			requiredReferences = this.referenceSet;
		}
		return requiredReferences;
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
