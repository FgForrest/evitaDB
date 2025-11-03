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
import io.evitadb.api.requestResponse.EvitaRequest.AttributeRequest;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
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

import static java.util.Optional.ofNullable;

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
	 * Contains information about default attribute request for references that has no explicit attribute request.
	 */
	@Nullable @Getter private final AttributeRequest defaultAttributeRequest;
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
	 * to be carried around even if {@link io.evitadb.api.EntityCollectionContract#limitEntity(EntityContract, EvitaRequest, EvitaSessionContract)}
	 * is invoked on the entity.
	 */
	@Nullable @Getter private final ReferenceContractSerializablePredicate underlyingPredicate;


	public ReferenceContractSerializablePredicate() {
		this.requiresEntityReferences = true;
		this.referenceSet = Collections.emptyMap();
		this.defaultAttributeRequest = null;
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
		this.defaultAttributeRequest = ofNullable(evitaRequest.getDefaultReferenceRequirement())
			.map(RequirementContext::attributeRequest)
			.orElse(null);
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.underlyingPredicate = null;
	}

	public ReferenceContractSerializablePredicate(boolean requiresEntityReferences) {
		this.requiresEntityReferences = requiresEntityReferences;
		this.referenceSet = Collections.emptyMap();
		this.defaultAttributeRequest = null;
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
		this.defaultAttributeRequest = ofNullable(evitaRequest.getDefaultReferenceRequirement())
			.map(RequirementContext::attributeRequest)
			.orElse(null);
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.underlyingPredicate = underlyingPredicate;
	}

	ReferenceContractSerializablePredicate(
		@Nonnull Map<String, AttributeRequest> referenceSet,
		@Nullable AttributeRequest defaultAttributeRequest,
		boolean requiresEntityReferences,
		@Nullable Locale implicitLocale,
		@Nullable Set<Locale> locales
	) {
		this.referenceSet = referenceSet;
		this.defaultAttributeRequest = defaultAttributeRequest;
		this.requiresEntityReferences = requiresEntityReferences;
		this.implicitLocale = implicitLocale;
		this.locales = locales;
		this.underlyingPredicate = null;
	}

	/**
	 * Returns true if the references were fetched along with the entity.
	 */
	public boolean wasFetched() {
		return this.requiresEntityReferences;
	}

	/**
	 * Returns true if the references of particular name were fetched along with the entity.
	 */
	public boolean wasFetched(@Nonnull String referenceName) {
		return this.requiresEntityReferences && (this.referenceSet.isEmpty() || this.referenceSet.containsKey(referenceName));
	}

	/**
	 * Method verifies that references were fetched with the entity.
	 */
	public void checkFetched() throws ContextMissingException {
		if (!this.requiresEntityReferences) {
			throw ContextMissingException.referenceContextMissing();
		}
	}

	/**
	 * Method verifies that the requested attribute was fetched with the entity.
	 */
	public void checkFetched(@Nonnull String referenceName) throws ContextMissingException {
		if (!(this.requiresEntityReferences && (this.referenceSet.isEmpty() || this.referenceSet.containsKey(referenceName)))) {
			throw ContextMissingException.referenceContextMissing(referenceName);
		}
	}

	@Override
	public boolean test(ReferenceContract reference) {
		if (this.requiresEntityReferences) {
			final String referenceName = reference.getReferenceName();
			return reference.exists() &&
				(this.referenceSet.isEmpty() || this.referenceSet.containsKey(referenceName));
		} else {
			return false;
		}
	}

	/**
	 * Determines if a reference with a given name has been requested based on the current state
	 * of reference requirements and the reference set.
	 *
	 * @param referenceName the name of the reference to check.
	 * @return {@code true} if references are required and the reference name is either part of the set
	 *         or the set is empty; {@code false} otherwise.
	 */
	public boolean isReferenceRequested(@Nonnull String referenceName) {
		if (this.requiresEntityReferences) {
			return this.referenceSet.isEmpty() || this.referenceSet.containsKey(referenceName);
		} else {
			return false;
		}
	}

	/**
	 * Creates a richer copy of the current {@code ReferenceContractSerializablePredicate} instance
	 * by combining its existing state with the details from the provided {@code EvitaRequest}.
	 *
	 * @param evitaRequest the {@code EvitaRequest} containing additional requirements and state to merge into the new instance.
	 * @return a new {@code ReferenceContractSerializablePredicate} instance that combines the state from the current instance
	 *         with the requirements from the provided {@code EvitaRequest}.
	 */
	@Nonnull
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
		final AttributeRequest defaultAttributeRequest = ofNullable(evitaRequest.getDefaultReferenceRequirement())
			.map(RequirementContext::attributeRequest)
			.orElse(null);

		if ((this.requiresEntityReferences || !doesRequireEntityReferences) &&
			Objects.equals(this.referenceSet, requiredReferencedEntities) &&
			Objects.equals(this.defaultAttributeRequest, defaultAttributeRequest) &&
			Objects.equals(this.implicitLocale, evitaRequest.getImplicitLocale()) &&
			Objects.equals(this.locales, requiredLocales)
		) {
			return this;
		} else {
			return new ReferenceContractSerializablePredicate(
				requiredReferencedEntities,
				mergeAttributeRequests(this.defaultAttributeRequest, defaultAttributeRequest),
				this.requiresEntityReferences || doesRequireEntityReferences,
				this.implicitLocale,
				requiredLocales
			);
		}
	}

	/**
	 * Retrieves a predicate that can be used to filter attribute values for a specific reference.
	 *
	 * @param referenceName the name of the reference for which to obtain the attribute predicate.
	 * @return a {@code ReferenceAttributeValueSerializablePredicate} configured for the specified reference name.
	 */
	@Nonnull
	public ReferenceAttributeValueSerializablePredicate getAttributePredicate(@Nonnull String referenceName) {
		return new ReferenceAttributeValueSerializablePredicate(
			this.implicitLocale,
			this.locales,
			this.referenceSet.isEmpty() ?
				(this.defaultAttributeRequest == null ? AttributeRequest.EMPTY : this.defaultAttributeRequest) :
				this.referenceSet.getOrDefault(referenceName, AttributeRequest.EMPTY)
		);
	}

	/**
	 * Retrieves a predicate that includes all attributes for the reference.
	 * @return a {@code ReferenceAttributeValueSerializablePredicate} configured to include all attributes.
	 */
	@Nonnull
	public ReferenceAttributeValueSerializablePredicate getAllAttributePredicate() {
		return new ReferenceAttributeValueSerializablePredicate(
			this.implicitLocale,
			this.locales,
			AttributeRequest.ALL
		);
	}

	/**
	 * Retrieves a set of all locales available in the current instance. If an implicit locale is set and
	 * no explicit locales are available, a set containing only the implicit locale is returned. If both
	 * implicit and explicit locales are present, a merged set of both is returned. If no implicit locale
	 * is set, the explicit locales are returned.
	 *
	 * @return a set of available locales, potentially empty, or null if no locales are defined.
	 */
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

	/**
	 * Combines and merges referenced entity attribute requests from the provided EvitaRequest.
	 *
	 * @param evitaRequest the EvitaRequest containing the reference entity fetch requirements.
	 * @return a map of combined AttributeRequests for referenced entities.
	 */
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
				final AttributeRequest newAttributeRequest = newEntry.getValue().attributeRequest();
				final AttributeRequest mergedAttributeRequest = mergeAttributeRequests(existingAttributeRequest, newAttributeRequest);
				requiredReferences.put(newEntry.getKey(), mergedAttributeRequest);
			}
		} else {
			requiredReferences = this.referenceSet;
		}
		return requiredReferences;
	}

	/**
	 * Merges two AttributeRequest objects. If either of the input requests is null,
	 * the method returns the non-null request. If both requests are non-null, the method
	 * combines their attribute sets and consolidates the entity attribute requirements.
	 *
	 * @param existingAttributeRequest the existing AttributeRequest to be merged.
	 * @param newAttributeRequest the new AttributeRequest to be merged.
	 * @return a merged AttributeRequest that combines the attribute sets and entity attribute requirements
	 *         from the provided requests, or null if both inputs are null.
	 */
	@Nullable
	private static AttributeRequest mergeAttributeRequests(
		@Nullable AttributeRequest existingAttributeRequest,
		@Nullable AttributeRequest newAttributeRequest
	) {
		final AttributeRequest mergedAttributeRequest;
		if (existingAttributeRequest == null) {
			mergedAttributeRequest = newAttributeRequest;
		} else if (newAttributeRequest == null) {
			mergedAttributeRequest = existingAttributeRequest;
		} else {
			final AttributeRequest attributeRequest;
			if (existingAttributeRequest.isRequiresEntityAttributes() && existingAttributeRequest.attributeSet().isEmpty()) {
				attributeRequest = existingAttributeRequest;
			} else if (newAttributeRequest.isRequiresEntityAttributes() && newAttributeRequest.attributeSet().isEmpty()) {
				attributeRequest = newAttributeRequest;
			} else {
				attributeRequest = new AttributeRequest(
					CollectionUtils.combine(existingAttributeRequest.attributeSet(), newAttributeRequest.attributeSet()),
					existingAttributeRequest.isRequiresEntityAttributes() ||
						newAttributeRequest.isRequiresEntityAttributes()
				);
			}
			mergedAttributeRequest = attributeRequest;
		}
		return mergedAttributeRequest;
	}

	/**
	 * Combines the locales from the current instance and the given EvitaRequest.
	 *
	 * @param evitaRequest the EvitaRequest containing additional locales to merge.
	 * @return a set of combined locales, potentially null if no locales are present.
	 */
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
