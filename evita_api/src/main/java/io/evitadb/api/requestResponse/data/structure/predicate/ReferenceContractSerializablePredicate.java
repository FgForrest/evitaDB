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
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * Serializable predicate that filters entity references based on query requirements.
 *
 * This predicate controls which references (relationships to other entities) are visible to clients by filtering
 * based on reference names and their associated attribute requirements specified in the query. Each reference can
 * have its own specific attribute filtering rules, allowing fine-grained control over what reference data is
 * exposed.
 *
 * The predicate supports:
 * - Reference name-based filtering (specific references or all references)
 * - Per-reference attribute requirements (managed via `AttributeRequest` objects)
 * - Default attribute requirements for references without explicit attribute rules
 * - Locale filtering for reference attributes (inherited from entity-level locale requirements)
 *
 * Reference filtering is hierarchical: first references are filtered by name, then attributes within each
 * reference are filtered by name and locale. The predicate creates {@link ReferenceAttributeValueSerializablePredicate}
 * instances for individual references to handle attribute-level filtering.
 *
 * **Thread-safety**: This class is immutable and thread-safe.
 *
 * **Underlying predicate pattern**: Supports an optional underlying predicate that represents the original entity's
 * complete reference scope. This pattern is used when creating limited views from fully-fetched entities.
 *
 * **Empty map semantics**: An empty `referenceSet` map means "all references are allowed" when
 * `requiresEntityReferences` is true.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferenceContractSerializablePredicate implements SerializablePredicate<ReferenceContract> {
	public static final ReferenceContractSerializablePredicate DEFAULT_INSTANCE = new ReferenceContractSerializablePredicate();
	@Serial private static final long serialVersionUID = -3182607338600238414L;

	/**
	 * Map of reference names to their associated attribute requirements. Each entry specifies which attributes
	 * should be fetched for a particular reference type. An empty map means all references are allowed when
	 * `requiresEntityReferences` is true; otherwise specific references are filtered by name.
	 */
	@Nonnull @Getter private final Map<String, AttributeRequest> referenceSet;
	/**
	 * Default attribute requirements applied to references that don't have explicit attribute requirements in
	 * `referenceSet`. This allows setting a baseline attribute policy for all references while allowing specific
	 * overrides per reference type. May be null if no default is specified.
	 */
	@Nullable @Getter private final AttributeRequest defaultAttributeRequest;
	/**
	 * Indicates whether any references were requested with the entity. When false, all reference access will fail.
	 * When true, references are accessible subject to name and attribute filtering.
	 */
	@Getter private final boolean requiresEntityReferences;
	/**
	 * Implicitly derived locale determined from query context or defaults. Passed to reference attribute predicates
	 * for filtering localized reference attributes. May be null if no implicit locale was derived.
	 */
	@Nullable @Getter private final Locale implicitLocale;
	/**
	 * Set of explicitly requested locales from the query. Passed to reference attribute predicates for filtering
	 * localized reference attributes. An empty set means all locales are allowed; null means no locales were
	 * requested.
	 */
	@Nullable private final Set<Locale> locales;
	/**
	 * Optional underlying predicate representing the complete entity's reference scope. Used when creating
	 * limited views from fully-fetched entities via
	 * {@link io.evitadb.api.EntityCollectionContract#limitEntity(EntityContract, EvitaRequest, EvitaSessionContract)}.
	 * Must not be nested (only one level allowed).
	 */
	@Nullable @Getter private final ReferenceContractSerializablePredicate underlyingPredicate;

	/**
	 * Creates a map of reference names to their associated attribute requests based on the entity fetch
	 * requirements specified in the provided `EvitaRequest`.
	 * Only unnamed references are processed; named references are ignored.
	 *
	 * @param evitaRequest the `EvitaRequest` containing details about the reference entity fetch requirements.
	 * @return a map where the keys are reference names and the values are the associated `AttributeRequest` objects.
	 */
	@Nonnull
	private static Map<String, AttributeRequest> getReferenceSet(@Nonnull EvitaRequest evitaRequest) {
		return evitaRequest.getReferenceEntityFetch()
			.entrySet()
			.stream()
			.collect(
				Collectors.toMap(
					Entry::getKey,
					entry -> entry.getValue().attributeRequest()
				)
			);
	}

	/**
	 * Merges two AttributeRequest objects. If either of the input requests is null,
	 * the method returns the non-null request. If both requests are non-null, the method
	 * combines their attribute sets and consolidates the entity attribute requirements.
	 *
	 * @param existingAttributeRequest the existing AttributeRequest to be merged.
	 * @param newAttributeRequest      the new AttributeRequest to be merged.
	 * @return a merged AttributeRequest that combines the attribute sets and entity attribute requirements
	 * from the provided requests, or null if both inputs are null.
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
			if (existingAttributeRequest.isRequiresEntityAttributes() && existingAttributeRequest.attributeSet()
				.isEmpty()) {
				attributeRequest = existingAttributeRequest;
			} else if (newAttributeRequest.isRequiresEntityAttributes() && newAttributeRequest.attributeSet()
				.isEmpty()) {
				attributeRequest = newAttributeRequest;
			} else {
				attributeRequest = new AttributeRequest(
					CollectionUtils.combine(
						existingAttributeRequest.attributeSet(), newAttributeRequest.attributeSet()),
					existingAttributeRequest.isRequiresEntityAttributes() ||
						newAttributeRequest.isRequiresEntityAttributes()
				);
			}
			mergedAttributeRequest = attributeRequest;
		}
		return mergedAttributeRequest;
	}

	/**
	 * Creates a default predicate with reference access enabled but no specific reference filtering.
	 *
	 * This allows all references to be visible.
	 */
	public ReferenceContractSerializablePredicate() {
		this.requiresEntityReferences = true;
		this.referenceSet = Collections.emptyMap();
		this.defaultAttributeRequest = null;
		this.implicitLocale = null;
		this.locales = Collections.emptySet();
		this.underlyingPredicate = null;
	}

	/**
	 * Creates a reference predicate from an Evita request.
	 *
	 * Extracts reference requirements, attribute requirements per reference, default attribute requirements,
	 * and locale requirements from the request. This constructor is typically used when building entity
	 * decorators for query responses.
	 *
	 * @param evitaRequest the request containing reference and attribute requirements
	 */
	public ReferenceContractSerializablePredicate(@Nonnull EvitaRequest evitaRequest) {
		this.requiresEntityReferences = evitaRequest.isRequiresEntityReferences();
		this.referenceSet = getReferenceSet(evitaRequest);
		this.defaultAttributeRequest = ofNullable(evitaRequest.getDefaultReferenceRequirement())
			.map(RequirementContext::attributeRequest)
			.orElse(null);
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.underlyingPredicate = null;
	}

	/**
	 * Creates a reference predicate for a single specific reference with explicit attribute requirements.
	 *
	 * This constructor is used when filtering for a specific reference type with known attribute requirements,
	 * such as when processing reference-specific queries.
	 *
	 * @param evitaRequest the request containing locale and default reference requirements
	 * @param referenceName the specific reference name to filter for
	 * @param requirementContext the attribute requirements for this specific reference
	 */
	public ReferenceContractSerializablePredicate(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull String referenceName,
		@Nonnull RequirementContext requirementContext
	) {
		this.requiresEntityReferences = true;
		this.referenceSet = Map.of(
			referenceName,
			requirementContext.attributeRequest()
		);
		this.defaultAttributeRequest = ofNullable(evitaRequest.getDefaultReferenceRequirement())
			.map(RequirementContext::attributeRequest)
			.orElse(null);
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.underlyingPredicate = null;
	}

	/**
	 * Creates a simple reference predicate with explicit reference requirement flag.
	 *
	 * Used internally for creating enriched copies via {@link #createRicherCopyWith(EvitaRequest)}.
	 *
	 * @param requiresEntityReferences whether references are required at all
	 */
	public ReferenceContractSerializablePredicate(boolean requiresEntityReferences) {
		this.requiresEntityReferences = requiresEntityReferences;
		this.referenceSet = Collections.emptyMap();
		this.defaultAttributeRequest = null;
		this.implicitLocale = null;
		this.locales = Collections.emptySet();
		this.underlyingPredicate = null;
	}

	/**
	 * Creates a reference predicate with an underlying predicate for entity limitation scenarios.
	 *
	 * This constructor is used when applying additional restrictions to an already-fetched entity
	 * (e.g., when calling `limitEntity`). The underlying predicate preserves the original fetch scope.
	 *
	 * @param evitaRequest the request containing new reference and attribute requirements
	 * @param underlyingPredicate the predicate representing the original entity's complete reference scope
	 * @throws io.evitadb.exception.GenericEvitaInternalError if underlyingPredicate is already nested
	 */
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
		this.referenceSet = getReferenceSet(evitaRequest);
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
	 * Checks whether any references were fetched with the entity.
	 *
	 * @return true if references are accessible (subject to name and attribute filtering)
	 */
	public boolean wasFetched() {
		return this.requiresEntityReferences;
	}

	/**
	 * Checks whether references with the specified name were fetched with the entity.
	 *
	 * An empty `referenceSet` means all references were fetched (when `requiresEntityReferences` is true).
	 *
	 * @param referenceName the reference name to check
	 * @return true if the reference is accessible
	 */
	public boolean wasFetched(@Nonnull String referenceName) {
		return this.requiresEntityReferences &&
			(this.referenceSet.isEmpty() || this.referenceSet.containsKey(referenceName));
	}

	/**
	 * Verifies that references were fetched with the entity, throwing an exception if not.
	 *
	 * This method should be called before accessing any reference data to ensure the data is available.
	 *
	 * @throws ContextMissingException if no references were fetched with the entity
	 */
	public void checkFetched() throws ContextMissingException {
		if (!this.requiresEntityReferences) {
			throw ContextMissingException.referenceContextMissing();
		}
	}

	/**
	 * Verifies that references with a specific name were fetched with the entity, throwing an exception if not.
	 *
	 * This method should be called before accessing specific reference data to ensure the data is available.
	 *
	 * @param referenceName the reference name to check
	 * @throws ContextMissingException if the reference was not fetched with the entity
	 */
	public void checkFetched(@Nonnull String referenceName) throws ContextMissingException {
		if (!(this.requiresEntityReferences && (this.referenceSet.isEmpty() || this.referenceSet.containsKey(
			referenceName)))) {
			throw ContextMissingException.referenceContextMissing(referenceName);
		}
	}

	/**
	 * Tests whether the given reference should be visible based on query requirements.
	 *
	 * A reference passes the test if all of the following conditions are met:
	 * - References are required (`requiresEntityReferences` is true)
	 * - The reference exists (not dropped)
	 * - The reference name matches (if `referenceSet` is non-empty)
	 *
	 * Note: This method only tests reference-level visibility, not attribute-level filtering within the reference.
	 *
	 * @param reference the reference to test
	 * @return true if the reference should be visible to the client
	 */
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
	 * @return `true` if references are required and the reference name is either part of the set
	 * or the set is empty; `false` otherwise.
	 */
	public boolean isReferenceRequested(@Nonnull String referenceName) {
		if (this.requiresEntityReferences) {
			return this.referenceSet.isEmpty() || this.referenceSet.containsKey(referenceName);
		} else {
			return false;
		}
	}

	/**
	 * Creates a richer copy of the current `ReferenceContractSerializablePredicate` instance
	 * by combining its existing state with the details from the provided `EvitaRequest`.
	 *
	 * @param evitaRequest the `EvitaRequest` containing additional requirements and state to merge into the new instance.
	 * @return a new `ReferenceContractSerializablePredicate` instance that combines the state from the current instance
	 * with the requirements from the provided `EvitaRequest`.
	 */
	@Nonnull
	public ReferenceContractSerializablePredicate createRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		final Set<Locale> requiredLocales = PredicateLocaleHelper.combineLocales(this.locales, evitaRequest);
		PredicateLocaleHelper.assertImplicitLocalesConsistent(this.implicitLocale, evitaRequest);

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
				PredicateLocaleHelper.resolveImplicitLocale(this.implicitLocale, evitaRequest),
				requiredLocales
			);
		}
	}

	/**
	 * Retrieves a predicate that can be used to filter attribute values for a specific reference.
	 *
	 * @param referenceName the name of the reference for which to obtain the attribute predicate.
	 * @return a `ReferenceAttributeValueSerializablePredicate` configured for the specified reference name.
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
	 *
	 * @return a `ReferenceAttributeValueSerializablePredicate` configured to include all attributes.
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
		return PredicateLocaleHelper.getAllLocales(this.implicitLocale, this.locales);
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
			requiredReferences = getReferenceSet(evitaRequest);
		} else if (evitaRequest.isRequiresEntityReferences()) {
			requiredReferences = new HashMap<>(this.referenceSet.size() + referenceEntityFetch.size());
			requiredReferences.putAll(this.referenceSet);
			for (Entry<String, RequirementContext> newEntry : referenceEntityFetch.entrySet()) {
				final String referenceName = newEntry.getKey();
				final AttributeRequest existingAttributeRequest = requiredReferences.get(referenceName);
				final AttributeRequest newAttributeRequest = newEntry.getValue().attributeRequest();
				final AttributeRequest mergedAttributeRequest = mergeAttributeRequests(
					existingAttributeRequest, newAttributeRequest
				);
				requiredReferences.put(referenceName, mergedAttributeRequest);
			}
		} else {
			requiredReferences = this.referenceSet;
		}
		return requiredReferences;
	}

}
