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
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
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
 * `requiresEntityReferences` is true **and** no named reference content was requested - a named requirement never
 * reaches `referenceSet` yet does narrow the entity, see {@link #getVisibleReferenceNames()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferenceContractSerializablePredicate implements SerializablePredicate<ReferenceContract> {
	public static final ReferenceContractSerializablePredicate DEFAULT_INSTANCE = new ReferenceContractSerializablePredicate();
	@Serial private static final long serialVersionUID = -3182607338600238414L;

	/**
	 * Map of reference names to their associated attribute requirements. Each entry specifies which attributes
	 * should be fetched for a particular reference type. This map alone does not decide which references are
	 * visible - {@link #visibleReferenceNames} does, because a named requirement never lands here.
	 */
	@Nonnull @Getter private final Map<String, AttributeRequest> referenceSet;
	/**
	 * Names an **unnamed** requirement asked for. A subset of {@link #referenceSet}'s keys, which also carries the
	 * implicit requirement the request synthesises for a name only named requirements asked for - so this is what
	 * tells "the caller wanted the unnamed view of this name" apart from "the unnamed view was derived for it".
	 */
	@Nonnull private final Set<String> explicitlyUnnamedReferenceNames;
	/**
	 * Default attribute requirements applied to references that don't have explicit attribute requirements in
	 * `referenceSet`. This allows setting a baseline attribute policy for all references while allowing specific
	 * overrides per reference type. May be null if no default is specified.
	 */
	@Nullable @Getter private final AttributeRequest defaultAttributeRequest;
	/**
	 * Reference names requested through **named** reference content, i.e. `referenceContent(<instanceName>,
	 * '<referenceName>', ...)`, which every externally issued query produces - a GraphQL field alias or a REST
	 * projection name becomes the instance name.
	 *
	 * These never reach {@link #referenceSet}: {@link EvitaRequest#getReferenceEntityFetch()} routes named
	 * requirements into a separate map keyed by instance name, because attribute requirements of a named reference
	 * are resolved per instance rather than per reference name. That leaves `referenceSet` empty for such a query,
	 * which on its own reads as "no name was asked for" - i.e. as a request for everything. This set restores what
	 * the query actually asked for, and {@link #visibleReferenceNames} folds the two together so that both the
	 * storage read and the visibility of the composed entity are decided by the same names.
	 */
	@Nonnull private final Set<String> namedReferenceNames;
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
	 * The reference names this predicate lets through, NULL when it lets **all** of them through. Computed once in
	 * the constructor because it is consulted per reference on the entity composition path and the union of
	 * {@link #referenceSet} and {@link #namedReferenceNames} would otherwise be rebuilt on every single call.
	 *
	 * This is the single source of truth of *what this predicate is about*: both the storage layer, which decodes
	 * only these names, and the visibility methods, which hide everything else, read it through
	 * {@link #getVisibleReferenceNames()} and {@link #isReferenceNameVisible(String)}. Keeping the two sides on one
	 * field is what stops the read from being narrower than the visibility - a divergence that answers "this entity
	 * has no such reference" for a reference the entity does have.
	 */
	@Nullable private final Set<String> visibleReferenceNames;
	/**
	 * Lazily derived from {@link #visibleReferenceNames} and then kept, so that every read performed under this
	 * predicate binds the very same instance - see {@link #getDecodeCoverage()} for why identity matters here.
	 * Transient because it is a derivable cache, not state: a deserialized predicate rebuilds it on first ask.
	 */
	@Nullable private transient ReferenceDecodeCoverage memoizedDecodeCoverage;
	/**
	 * Referenced entity primary keys each visible reference name is bound to, empty when none is bound.
	 *
	 * Populated only from an {@link EvitaRequest}: a name lands here when **every** requirement asking for it names
	 * an exact key set in its filter's conjunctive root, which is precisely when the references outside that set
	 * cannot appear in the answer anyway. See {@link EvitaRequest#getReferenceKeyNarrowing()}.
	 */
	@Nonnull private final Map<String, int[]> referenceKeyNarrowing;

	/**
	 * Computes {@link #visibleReferenceNames} - the union of the explicitly requested reference names and the names
	 * of the named reference requirements, or NULL when the predicate does not narrow by name at all.
	 *
	 * Reads {@link #requiresEntityReferences}, {@link #defaultAttributeRequest}, {@link #referenceSet} and
	 * {@link #namedReferenceNames} directly - every constructor calls this as its last statement, once those four
	 * fields are already assigned.
	 *
	 * @return the allowed reference names, or NULL when every name is allowed
	 */
	@Nullable
	private Set<String> computeVisibleReferenceNames() {
		if (!this.requiresEntityReferences || this.defaultAttributeRequest != null) {
			// a default `referenceContent()` requirement asks for every reference there is
			return null;
		}
		if (this.namedReferenceNames.isEmpty()) {
			return this.referenceSet.isEmpty() ? null : this.referenceSet.keySet();
		}
		if (this.referenceSet.isEmpty()) {
			return this.namedReferenceNames;
		}
		final Set<String> result = CollectionUtils.createHashSet(
			this.referenceSet.size() + this.namedReferenceNames.size()
		);
		result.addAll(this.referenceSet.keySet());
		result.addAll(this.namedReferenceNames);
		return result;
	}

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
	 * Collects the distinct reference names of all **named** reference requirements of the request.
	 *
	 * @param evitaRequest the request to read the named requirements from
	 * @return distinct reference names named requirements point at, empty when there are none
	 */
	@Nonnull
	private static Set<String> getNamedReferenceNames(@Nonnull EvitaRequest evitaRequest) {
		final Map<ReferenceContentKey, RequirementContext> namedFetch = evitaRequest.getNamedReferenceEntityFetch();
		if (namedFetch.isEmpty()) {
			return Collections.emptySet();
		}
		final Set<String> result = CollectionUtils.createHashSet(namedFetch.size());
		for (final ReferenceContentKey key : namedFetch.keySet()) {
			result.add(key.referenceName());
		}
		return result;
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
		this.explicitlyUnnamedReferenceNames = Collections.emptySet();
		this.namedReferenceNames = Collections.emptySet();
		this.defaultAttributeRequest = null;
		this.implicitLocale = null;
		this.locales = Collections.emptySet();
		this.underlyingPredicate = null;
		this.referenceKeyNarrowing = Collections.emptyMap();
		this.visibleReferenceNames = computeVisibleReferenceNames();
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
		this.explicitlyUnnamedReferenceNames = evitaRequest.getExplicitlyUnnamedReferenceNames();
		this.namedReferenceNames = getNamedReferenceNames(evitaRequest);
		this.defaultAttributeRequest = ofNullable(evitaRequest.getDefaultReferenceRequirement())
			.map(RequirementContext::attributeRequest)
			.orElse(null);
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.underlyingPredicate = null;
		this.referenceKeyNarrowing = evitaRequest.getReferenceKeyNarrowing();
		this.visibleReferenceNames = computeVisibleReferenceNames();
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
		this.explicitlyUnnamedReferenceNames = Set.of(referenceName);
		this.referenceSet = Map.of(
			referenceName,
			requirementContext.attributeRequest()
		);
		this.namedReferenceNames = Collections.emptySet();
		this.defaultAttributeRequest = ofNullable(evitaRequest.getDefaultReferenceRequirement())
			.map(RequirementContext::attributeRequest)
			.orElse(null);
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.underlyingPredicate = null;
		this.referenceKeyNarrowing = Collections.emptyMap();
		this.visibleReferenceNames = computeVisibleReferenceNames();
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
		this.explicitlyUnnamedReferenceNames = Collections.emptySet();
		this.namedReferenceNames = Collections.emptySet();
		this.defaultAttributeRequest = null;
		this.implicitLocale = null;
		this.locales = Collections.emptySet();
		this.underlyingPredicate = null;
		this.referenceKeyNarrowing = Collections.emptyMap();
		this.visibleReferenceNames = computeVisibleReferenceNames();
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
		this.explicitlyUnnamedReferenceNames = evitaRequest.getExplicitlyUnnamedReferenceNames();
		this.namedReferenceNames = getNamedReferenceNames(evitaRequest);
		this.defaultAttributeRequest = ofNullable(evitaRequest.getDefaultReferenceRequirement())
			.map(RequirementContext::attributeRequest)
			.orElse(null);
		this.implicitLocale = evitaRequest.getImplicitLocale();
		this.locales = evitaRequest.getRequiredLocales();
		this.underlyingPredicate = underlyingPredicate;
		// inherited, never reset: a limited view shows LESS of an entity that has already been read, so it cannot
		// have decoded more than the read behind it did. Claiming an empty narrowing here would report a name as
		// decoded whole when the data behind it was decoded by key, and the enrichment gate - which compares
		// coverage - would then answer "already fetched" and serve a silently short reference set.
		this.referenceKeyNarrowing = underlyingPredicate.referenceKeyNarrowing;
		this.visibleReferenceNames = computeVisibleReferenceNames();
	}

	ReferenceContractSerializablePredicate(
		@Nonnull Map<String, AttributeRequest> referenceSet,
		@Nonnull Set<String> namedReferenceNames,
		@Nullable AttributeRequest defaultAttributeRequest,
		boolean requiresEntityReferences,
		@Nullable Locale implicitLocale,
		@Nullable Set<Locale> locales
	) {
		// every entry of an explicitly passed reference set came from an unnamed requirement - this overload is
		// never handed the implicit requirement the request synthesises, which only reaches the request-driven
		// constructors
		this(
			referenceSet, referenceSet.keySet(), namedReferenceNames, defaultAttributeRequest,
			requiresEntityReferences, implicitLocale, locales, Collections.emptyMap()
		);
	}

	/**
	 * Creates a predicate carrying an explicit per-name key narrowing, used when enriching one predicate with
	 * another's requirements.
	 *
	 * @param referenceSet             attribute requirements per unnamed reference name
	 * @param explicitlyUnnamedReferenceNames names an unnamed requirement asked for
	 * @param namedReferenceNames      names asked for through named requirements
	 * @param defaultAttributeRequest  attribute requirement of a default `referenceContent()`, NULL when absent
	 * @param requiresEntityReferences whether references are required at all
	 * @param implicitLocale           implicit locale of the request
	 * @param locales                  locales the request requires
	 * @param referenceKeyNarrowing    referenced primary keys each name is bound to, empty when none is bound
	 */
	ReferenceContractSerializablePredicate(
		@Nonnull Map<String, AttributeRequest> referenceSet,
		@Nonnull Set<String> explicitlyUnnamedReferenceNames,
		@Nonnull Set<String> namedReferenceNames,
		@Nullable AttributeRequest defaultAttributeRequest,
		boolean requiresEntityReferences,
		@Nullable Locale implicitLocale,
		@Nullable Set<Locale> locales,
		@Nonnull Map<String, int[]> referenceKeyNarrowing
	) {
		this.referenceSet = referenceSet;
		this.explicitlyUnnamedReferenceNames = explicitlyUnnamedReferenceNames;
		this.namedReferenceNames = namedReferenceNames;
		this.defaultAttributeRequest = defaultAttributeRequest;
		this.requiresEntityReferences = requiresEntityReferences;
		this.implicitLocale = implicitLocale;
		this.locales = locales;
		this.underlyingPredicate = null;
		this.referenceKeyNarrowing = referenceKeyNarrowing;
		this.visibleReferenceNames = computeVisibleReferenceNames();
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
	 * A name outside {@link #getVisibleReferenceNames()} is a name the storage read skipped, so the entity does not
	 * carry it even when it has such references - see {@link #isReferenceNameVisible(String)}.
	 *
	 * @param referenceName the reference name to check
	 * @return true if the reference is accessible
	 */
	public boolean wasFetched(@Nonnull String referenceName) {
		return this.requiresEntityReferences && isReferenceNameVisible(referenceName);
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
		if (!(this.requiresEntityReferences && isReferenceNameVisible(referenceName))) {
			throw ContextMissingException.referenceContextMissing(referenceName);
		}
	}

	/**
	 * Tests whether the given reference should be visible based on query requirements.
	 *
	 * A reference passes the test if all of the following conditions are met:
	 * - References are required (`requiresEntityReferences` is true)
	 * - The reference exists (not dropped)
	 * - The reference name is one of {@link #getVisibleReferenceNames()}
	 *
	 * Note: This method only tests reference-level visibility, not attribute-level filtering within the reference.
	 *
	 * @param reference the reference to test
	 * @return true if the reference should be visible to the client
	 */
	@Override
	public boolean test(ReferenceContract reference) {
		if (this.requiresEntityReferences) {
			return reference.exists() && isReferenceNameVisible(reference.getReferenceName());
		} else {
			return false;
		}
	}

	/**
	 * Returns how much of an entity's reference set a read performed under this predicate may materialize, or NULL
	 * when it may materialize all of it.
	 *
	 * This is the storage-facing form of {@link #getVisibleReferenceNames()}: the same decision, expressed in the
	 * vocabulary the decoder speaks. The two cannot drift because this is derived from that.
	 *
	 * **The instance is memoized**, and deliberately so. The deserializer compares the bound coverage by identity to
	 * resolve a per-name admission once per run of same-named references; handing it a fresh equal object per
	 * storage read would defeat that memo and re-resolve the admission for every one of the tens of thousands of
	 * references such a record can hold. It also keeps the coverage usable as part of a cache record's identity.
	 *
	 * @return the coverage, or NULL when nothing is narrowed away
	 */
	@Nullable
	public ReferenceDecodeCoverage getDecodeCoverage() {
		final Set<String> visibleNames = getVisibleReferenceNames();
		if (visibleNames == null) {
			return null;
		}
		if (this.memoizedDecodeCoverage == null) {
			if (this.referenceKeyNarrowing.isEmpty()) {
				this.memoizedDecodeCoverage = ReferenceDecodeCoverage.ofNames(visibleNames);
			} else {
				final Set<String> decodedWhole = CollectionUtils.createHashSet(visibleNames.size());
				final Map<String, int[]> decodedByKey = CollectionUtils.createHashMap(this.referenceKeyNarrowing.size());
				for (final String referenceName : visibleNames) {
					final int[] boundKeys = this.referenceKeyNarrowing.get(referenceName);
					if (boundKeys == null) {
						decodedWhole.add(referenceName);
					} else {
						decodedByKey.put(referenceName, boundKeys);
					}
				}
				this.memoizedDecodeCoverage = ReferenceDecodeCoverage.of(decodedWhole, decodedByKey);
			}
		}
		return this.memoizedDecodeCoverage;
	}

	/**
	 * Returns the reference names this predicate lets through, or NULL when it lets **all** of them through.
	 *
	 * This is the set form of {@link #isReferenceRequested(String)}, and the two cannot drift because both read
	 * {@link #visibleReferenceNames}: the storage layer uses this set to decode only the references the caller will
	 * be able to see (see `io.evitadb.spi.store.catalog.persistence.ReferenceDecodeCoverageContext`), so a name missing
	 * here is a name that is never materialized - and the visibility methods therefore have to report it as not
	 * fetched rather than as present and empty. NULL is returned both when all references are allowed and when none
	 * are - the latter never reaches the storage layer, which checks {@code isRequiresEntityReferences()} first.
	 *
	 * @return the allowed reference names, or NULL when the predicate does not narrow them by name
	 */
	@Nullable
	public Set<String> getVisibleReferenceNames() {
		return this.visibleReferenceNames;
	}

	/**
	 * Determines if a reference with a given name has been requested based on the current state
	 * of reference requirements and the reference names this predicate lets through.
	 *
	 * @param referenceName the name of the reference to check.
	 * @return `true` if references are required and the reference name is one of
	 * {@link #getVisibleReferenceNames()}; `false` otherwise.
	 */
	public boolean isReferenceRequested(@Nonnull String referenceName) {
		return this.requiresEntityReferences && isReferenceNameVisible(referenceName);
	}

	/**
	 * Tells whether `referenceName` was asked for **only** through a named reference content instance, i.e.
	 * `referenceContent(<instanceName>, '<referenceName>', ...)`, and through no unnamed requirement.
	 *
	 * Such a name is visible - {@link #isReferenceRequested(String)} answers TRUE for it, because
	 * {@link #getVisibleReferenceNames()} folds both kinds of requirement together so that the storage read is never
	 * narrower than the visibility - but nothing asked for the entity's unnamed reference view to carry it. The
	 * server-side decorator uses that to avoid materializing a second, unfiltered copy of a reference set it has
	 * already built as a named chunk.
	 *
	 * A catch-all `referenceContent()` (a non-NULL {@link #defaultAttributeRequest}) asks for every reference there
	 * is, so it makes this FALSE for every name.
	 *
	 * Asked of {@link #explicitlyUnnamedReferenceNames} rather than of {@link #referenceSet}, because the latter
	 * also carries the implicit requirement the request synthesises for a name only named requirements asked for -
	 * which is every such name, and testing it there would switch this off for all of them.
	 *
	 * @param referenceName name of the reference to decide about
	 * @return TRUE when only a named requirement asked for this reference name
	 */
	public boolean isReferenceRequestedOnlyAsNamed(@Nonnull String referenceName) {
		return this.defaultAttributeRequest == null
			&& this.namedReferenceNames.contains(referenceName)
			&& !this.explicitlyUnnamedReferenceNames.contains(referenceName);
	}

	/**
	 * Tells whether `referenceName` is one of the names {@link #getVisibleReferenceNames()} lets through.
	 *
	 * Callers are expected to have verified {@code isRequiresEntityReferences()} first - a predicate that requires
	 * no references at all narrows nothing by name and would be answered `true` here.
	 *
	 * @param referenceName name of the reference to decide about
	 * @return TRUE when the name is visible, FALSE when the predicate narrows it away
	 */
	private boolean isReferenceNameVisible(@Nonnull String referenceName) {
		return this.visibleReferenceNames == null || this.visibleReferenceNames.contains(referenceName);
	}

	/**
	 * Combines this predicate's per-name key narrowing with the one the passed request carries.
	 *
	 * A name stays narrowed only when **both** sides narrow it, and then to the union of their key sets - because
	 * the enriched entity has to satisfy both. When either side wants the name in full, or does not mention it at
	 * all, the name drops out and is read whole.
	 *
	 * That is deliberately conservative in one direction: a name this predicate narrowed and the new request never
	 * mentions is widened rather than kept narrow. Widening only ever costs a decode; keeping a narrowing the other
	 * side never agreed to would drop references, and enrichment is far too rare to trade that risk for the saving.
	 *
	 * @param evitaRequest the request being merged in
	 * @return the combined narrowing, never NULL
	 */
	@Nonnull
	private Map<String, int[]> combineReferenceKeyNarrowing(@Nonnull EvitaRequest evitaRequest) {
		if (this.referenceKeyNarrowing.isEmpty()) {
			return Collections.emptyMap();
		}
		final Map<String, int[]> requestNarrowing = evitaRequest.getReferenceKeyNarrowing();
		if (requestNarrowing.isEmpty()) {
			return Collections.emptyMap();
		}
		final Map<String, int[]> combined = CollectionUtils.createHashMap(this.referenceKeyNarrowing.size());
		for (final Entry<String, int[]> entry : this.referenceKeyNarrowing.entrySet()) {
			final int[] requestedKeys = requestNarrowing.get(entry.getKey());
			if (requestedKeys != null) {
				combined.put(entry.getKey(), ReferenceDecodeCoverage.unionSortedKeys(entry.getValue(), requestedKeys));
			}
		}
		return combined.isEmpty() ? Collections.emptyMap() : combined;
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
		final Set<String> combinedNamedReferenceNames = combineNamedReferenceNames(evitaRequest);
		final boolean doesRequireEntityReferences = evitaRequest.isRequiresEntityReferences();
		final AttributeRequest defaultAttributeRequest = ofNullable(evitaRequest.getDefaultReferenceRequirement())
			.map(RequirementContext::attributeRequest)
			.orElse(null);

		final Map<String, int[]> combinedKeyNarrowing = combineReferenceKeyNarrowing(evitaRequest);

		if ((this.requiresEntityReferences || !doesRequireEntityReferences) &&
			Objects.equals(this.referenceSet, requiredReferencedEntities) &&
			Objects.equals(this.namedReferenceNames, combinedNamedReferenceNames) &&
			Objects.equals(this.defaultAttributeRequest, defaultAttributeRequest) &&
			Objects.equals(this.implicitLocale, evitaRequest.getImplicitLocale()) &&
			Objects.equals(this.locales, requiredLocales) &&
			ReferenceDecodeCoverage.sameNarrowing(this.referenceKeyNarrowing, combinedKeyNarrowing)
		) {
			// identity matters here, not merely equality: `appliesExactly` compares predicates by reference and the
			// enrichment skips a storage round trip on it, so a copy allocated when nothing widened would turn every
			// enrichment into a re-read
			return this;
		} else {
			return new ReferenceContractSerializablePredicate(
				requiredReferencedEntities,
				// an enrichment can only ever add an explicit unnamed requirement, never retract one
				CollectionUtils.combine(
					this.explicitlyUnnamedReferenceNames, evitaRequest.getExplicitlyUnnamedReferenceNames()
				),
				combinedNamedReferenceNames,
				mergeAttributeRequests(this.defaultAttributeRequest, defaultAttributeRequest),
				this.requiresEntityReferences || doesRequireEntityReferences,
				PredicateLocaleHelper.resolveImplicitLocale(this.implicitLocale, evitaRequest),
				requiredLocales,
				combinedKeyNarrowing
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
	 * Combines the named reference names already carried by this predicate with those of the passed request, the same
	 * way {@link #combineReferencedEntities(EvitaRequest)} combines the unnamed ones. An enrichment must never narrow
	 * the set below what was already fetched, or the re-fetch decision would consider the previous read complete.
	 *
	 * @param evitaRequest the request whose named requirements are to be merged in
	 * @return the combined set of named reference names
	 */
	@Nonnull
	private Set<String> combineNamedReferenceNames(@Nonnull EvitaRequest evitaRequest) {
		final Set<String> newNames = getNamedReferenceNames(evitaRequest);
		if (this.namedReferenceNames.isEmpty()) {
			return newNames;
		} else if (newNames.isEmpty()) {
			return this.namedReferenceNames;
		}
		final Set<String> result = CollectionUtils.createHashSet(
			this.namedReferenceNames.size() + newNames.size()
		);
		result.addAll(this.namedReferenceNames);
		result.addAll(newNames);
		return result;
	}

	/**
	 * Combines the unnamed reference attribute requirements already carried by this predicate ({@link #referenceSet})
	 * with those of the passed request, merging the attribute requests of references present in both - the map
	 * counterpart of {@link #combineNamedReferenceNames(EvitaRequest)}, which combines the named reference names the
	 * same way.
	 *
	 * @param evitaRequest the request whose unnamed reference requirements are to be merged in
	 * @return the combined map of reference names to their merged attribute requests
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
