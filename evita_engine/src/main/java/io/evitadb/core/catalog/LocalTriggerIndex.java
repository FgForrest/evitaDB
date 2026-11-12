/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.catalog;

import io.evitadb.dataType.Scope;
import io.evitadb.core.expression.trigger.FacetExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramExpressionTrigger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Immutable, thread-safe index for local trigger lookup by `(ownerEntityType, referenceName, scope)`.
 * Merges both facet and histogram triggers into a single index — the shared three-level key
 * structure (`ownerEntityType -> referenceName -> scope`) is represented once, with the leaf
 * {@link LocalReferenceTriggers} record holding both trigger types.
 *
 * Follows a copy-on-write pattern: {@link #toBuilder()} produces a mutable {@link Builder} that
 * creates a new frozen instance via {@link Builder#build()}.
 *
 * The index also inverts the local histogram triggers by the owner input their conditions read - an owner attribute,
 * the parent, or an associated data - so that a mutation of that input finds the affected reference names with map
 * lookups only.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see CatalogExpressionTriggerRegistry
 */
final class LocalTriggerIndex {

	/**
	 * Empty singleton — no triggers registered.
	 */
	static final LocalTriggerIndex EMPTY = new LocalTriggerIndex(Collections.emptyMap());

	/**
	 * Frozen nested map: owner entity type -> reference name -> scope -> leaf triggers.
	 * All maps are unmodifiable after construction.
	 */
	@Nonnull
	private final Map<String, Map<String, Map<Scope, LocalReferenceTriggers>>> index;

	/**
	 * Owner entity type -> scope -> owner attribute -> affected reference names. Built from the deduplicated local
	 * histogram triggers when the registry is rebuilt; mutations perform only map lookups and a gated reference walk.
	 */
	@Nonnull
	private final Map<String, Map<Scope, Map<String, Set<String>>>> histogramReferencesByEntityAttribute;

	/**
	 * Owner entity type -> scope -> reference names whose local histogram trigger reads the parent of the owner. Built
	 * together with {@link #histogramReferencesByEntityAttribute}; a parent mutation performs only map lookups.
	 */
	@Nonnull
	private final Map<String, Map<Scope, Set<String>>> histogramReferencesByParent;

	/**
	 * Owner entity type -> scope -> associated data name -> reference names whose local histogram trigger reads that
	 * associated data of the owner. Built together with {@link #histogramReferencesByEntityAttribute}; an associated
	 * data mutation performs only map lookups.
	 */
	@Nonnull
	private final Map<String, Map<Scope, Map<String, Set<String>>>> histogramReferencesByAssociatedData;

	/**
	 * Creates a frozen index from an already-frozen map. Callers must ensure the map and all nested
	 * structures are unmodifiable.
	 *
	 * @param frozenIndex the frozen nested map
	 */
	private LocalTriggerIndex(
		@Nonnull Map<String, Map<String, Map<Scope, LocalReferenceTriggers>>> frozenIndex
	) {
		this.index = frozenIndex;
		this.histogramReferencesByEntityAttribute = indexHistogramEntityAttributes(frozenIndex);
		this.histogramReferencesByParent = indexHistogramReferencesReadingParent(frozenIndex);
		this.histogramReferencesByAssociatedData = indexHistogramReferencesByOwnerInput(
			frozenIndex, HistogramExpressionTrigger::getLocalAssociatedData
		);
	}

	/**
	 * Returns the facet trigger for inline expression evaluation within `ReferenceIndexMutator`.
	 * Both local-only and cross-entity triggers are eligible — they share the same `evaluate()` method.
	 *
	 * @param ownerEntityType the entity type that owns the reference
	 * @param referenceName   the reference name carrying the expression
	 * @param scope           the scope the trigger applies to
	 * @return the trigger, or null if no expression is defined for the triple
	 */
	@Nullable
	FacetExpressionTrigger getFacetTrigger(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		final LocalReferenceTriggers leaf = resolve(ownerEntityType, referenceName, scope);
		return leaf != null ? leaf.facetTrigger() : null;
	}

	/**
	 * Returns the histogram triggers for the given owner entity type, reference name, and scope.
	 * Each trigger carries its histogram name via
	 * {@link HistogramExpressionTrigger#getHistogramIndexName()}.
	 *
	 * @param ownerEntityType the entity type that owns the reference
	 * @param referenceName   the reference name carrying the histogram definitions
	 * @param scope           the scope the triggers apply to
	 * @return histogram triggers (empty collection if none defined, never null)
	 */
	@Nonnull
	Collection<HistogramExpressionTrigger> getHistogramTriggers(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		final LocalReferenceTriggers leaf = resolve(ownerEntityType, referenceName, scope);
		return leaf != null ? leaf.histogramTriggers().values() : Collections.emptyList();
	}

	/**
	 * Returns the reference names whose histogram conditions read this owner attribute in the given scope.
	 */
	@Nonnull
	Set<String> getHistogramReferencesForEntityAttribute(
		@Nonnull String ownerEntityType,
		@Nonnull Scope scope,
		@Nonnull String attributeName
	) {
		final Map<Scope, Map<String, Set<String>>> byScope =
			this.histogramReferencesByEntityAttribute.get(ownerEntityType);
		final Map<String, Set<String>> byAttribute = byScope == null ? null : byScope.get(scope);
		return byAttribute == null ? Collections.emptySet() :
			byAttribute.getOrDefault(attributeName, Collections.emptySet());
	}

	/**
	 * Inverts the frozen local histogram triggers into an immutable attribute relevance lookup. Duplicate dependency
	 * variants and histograms sharing an owner attribute contribute their reference name only once.
	 */
	@Nonnull
	private static Map<String, Map<Scope, Map<String, Set<String>>>> indexHistogramEntityAttributes(
		@Nonnull Map<String, Map<String, Map<Scope, LocalReferenceTriggers>>> triggers
	) {
		return indexHistogramReferencesByOwnerInput(triggers, HistogramExpressionTrigger::getLocalEntityAttributes);
	}

	/**
	 * Returns the reference names whose histogram conditions read the parent of the owner in the given scope.
	 *
	 * @param ownerEntityType the entity type owning the references
	 * @param scope           the scope of the local mutation
	 * @return immutable affected reference names, or an empty set
	 */
	@Nonnull
	Set<String> getHistogramReferencesForParent(@Nonnull String ownerEntityType, @Nonnull Scope scope) {
		final Map<Scope, Set<String>> byScope = this.histogramReferencesByParent.get(ownerEntityType);
		final Set<String> references = byScope == null ? null : byScope.get(scope);
		return references == null ? Collections.emptySet() : references;
	}

	/**
	 * Returns the reference names whose histogram conditions read this associated data of the owner in the given
	 * scope.
	 *
	 * @param ownerEntityType    the entity type owning the references
	 * @param scope              the scope of the local mutation
	 * @param associatedDataName the associated data being changed
	 * @return immutable affected reference names, or an empty set
	 */
	@Nonnull
	Set<String> getHistogramReferencesForAssociatedData(
		@Nonnull String ownerEntityType,
		@Nonnull Scope scope,
		@Nonnull String associatedDataName
	) {
		final Map<Scope, Map<String, Set<String>>> byScope =
			this.histogramReferencesByAssociatedData.get(ownerEntityType);
		final Map<String, Set<String>> byAssociatedData = byScope == null ? null : byScope.get(scope);
		return byAssociatedData == null ? Collections.emptySet() :
			byAssociatedData.getOrDefault(associatedDataName, Collections.emptySet());
	}

	/**
	 * Inverts the frozen local histogram triggers into an immutable lookup of reference names by the owner input
	 * `inputsOf` names for each trigger - an owner attribute or an associated data. Duplicate dependency variants and
	 * histograms sharing an input contribute their reference name only once.
	 *
	 * @param triggers the frozen nested map of local triggers
	 * @param inputsOf the names of the owner inputs a trigger's condition reads
	 * @return owner entity type -> scope -> input name -> reference names, frozen at every level
	 */
	@Nonnull
	private static Map<String, Map<Scope, Map<String, Set<String>>>> indexHistogramReferencesByOwnerInput(
		@Nonnull Map<String, Map<String, Map<Scope, LocalReferenceTriggers>>> triggers,
		@Nonnull Function<HistogramExpressionTrigger, Set<String>> inputsOf
	) {
		if (triggers.isEmpty()) {
			return Collections.emptyMap();
		}
		final Map<String, Map<Scope, Map<String, Set<String>>>> result = createHashMap(triggers.size());
		for (final Entry<String, Map<String, Map<Scope, LocalReferenceTriggers>>> owner : triggers.entrySet()) {
			for (final Entry<String, Map<Scope, LocalReferenceTriggers>> reference : owner.getValue().entrySet()) {
				for (final Entry<Scope, LocalReferenceTriggers> scope : reference.getValue().entrySet()) {
					for (final HistogramExpressionTrigger trigger : scope.getValue().histogramTriggers().values()) {
						for (final String input : inputsOf.apply(trigger)) {
							result.computeIfAbsent(owner.getKey(), ignored -> new EnumMap<>(Scope.class))
								.computeIfAbsent(scope.getKey(), ignored -> createHashMap(4))
								.computeIfAbsent(input, ignored -> createHashSet(4))
								.add(reference.getKey());
						}
					}
				}
			}
		}
		for (final Map<Scope, Map<String, Set<String>>> byScope : result.values()) {
			for (final Map<String, Set<String>> byInput : byScope.values()) {
				byInput.replaceAll((input, references) -> Collections.unmodifiableSet(references));
			}
			byScope.replaceAll((scope, inputs) -> Collections.unmodifiableMap(inputs));
		}
		result.replaceAll((owner, scopes) -> Collections.unmodifiableMap(scopes));
		return Collections.unmodifiableMap(result);
	}

	/**
	 * Inverts the frozen local histogram triggers into an immutable lookup of the reference names whose condition reads
	 * the parent of the owner. Duplicate dependency variants and several histograms of one reference contribute its
	 * name only once.
	 *
	 * @param triggers the frozen nested map of local triggers
	 * @return owner entity type -> scope -> reference names, frozen at every level
	 */
	@Nonnull
	private static Map<String, Map<Scope, Set<String>>> indexHistogramReferencesReadingParent(
		@Nonnull Map<String, Map<String, Map<Scope, LocalReferenceTriggers>>> triggers
	) {
		if (triggers.isEmpty()) {
			return Collections.emptyMap();
		}
		final Map<String, Map<Scope, Set<String>>> result = createHashMap(triggers.size());
		for (final Entry<String, Map<String, Map<Scope, LocalReferenceTriggers>>> owner : triggers.entrySet()) {
			for (final Entry<String, Map<Scope, LocalReferenceTriggers>> reference : owner.getValue().entrySet()) {
				for (final Entry<Scope, LocalReferenceTriggers> scope : reference.getValue().entrySet()) {
					for (final HistogramExpressionTrigger trigger : scope.getValue().histogramTriggers().values()) {
						if (trigger.usesParent()) {
							result.computeIfAbsent(owner.getKey(), ignored -> new EnumMap<>(Scope.class))
								.computeIfAbsent(scope.getKey(), ignored -> createHashSet(4))
								.add(reference.getKey());
						}
					}
				}
			}
		}
		for (final Map<Scope, Set<String>> byScope : result.values()) {
			byScope.replaceAll((scope, references) -> Collections.unmodifiableSet(references));
		}
		result.replaceAll((owner, scopes) -> Collections.unmodifiableMap(scopes));
		return Collections.unmodifiableMap(result);
	}

	/**
	 * Returns `true` if this index contains no triggers.
	 *
	 * @return true if empty
	 */
	boolean isEmpty() {
		return this.index.isEmpty();
	}

	/**
	 * Creates a mutable {@link Builder} pre-populated with a deep copy of this index's data.
	 *
	 * @return a new builder for copy-on-write rebuilds
	 */
	@Nonnull
	Builder toBuilder() {
		return new Builder(this);
	}

	/**
	 * Creates an empty mutable {@link Builder}.
	 *
	 * @param expectedEntityTypes estimated number of distinct owner entity types
	 * @return a new empty builder
	 */
	@Nonnull
	static Builder newBuilder(int expectedEntityTypes) {
		return new Builder(expectedEntityTypes);
	}

	/**
	 * Resolves the leaf triggers for the given three-level key. Returns null if any level is absent.
	 *
	 * @param ownerEntityType the owner entity type
	 * @param referenceName   the reference name
	 * @param scope           the scope
	 * @return the leaf triggers, or null if not found
	 */
	@Nullable
	private LocalReferenceTriggers resolve(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		final Map<String, Map<Scope, LocalReferenceTriggers>> byRef = this.index.get(ownerEntityType);
		if (byRef == null) {
			return null;
		}
		final Map<Scope, LocalReferenceTriggers> byScope = byRef.get(referenceName);
		if (byScope == null) {
			return null;
		}
		return byScope.get(scope);
	}

	/**
	 * Leaf record combining both facet and histogram triggers for a single
	 * `(ownerEntityType, referenceName, scope)` triple.
	 *
	 * @param facetTrigger      the facet trigger (null if no facet expression defined)
	 * @param histogramTriggers histogram name to trigger map (empty map if none, never null)
	 */
	record LocalReferenceTriggers(
		@Nullable FacetExpressionTrigger facetTrigger,
		@Nonnull Map<String, HistogramExpressionTrigger> histogramTriggers
	) {
	}

	/**
	 * Mutable builder for constructing a {@link LocalTriggerIndex}. Uses
	 * {@link MutableReferenceTriggers} as the mutable leaf type during construction, then freezes
	 * each leaf into a {@link LocalReferenceTriggers} record in {@link #build()}.
	 */
	static final class Builder {

		/**
		 * Mutable nested map: owner entity type -> reference name -> scope -> mutable leaf.
		 */
		@Nonnull
		private final Map<String, Map<String, Map<Scope, MutableReferenceTriggers>>> mutableIndex;

		/**
		 * Creates an empty builder with the given expected capacity.
		 *
		 * @param expectedSize estimated number of distinct owner entity types
		 */
		private Builder(int expectedSize) {
			this.mutableIndex = createHashMap(expectedSize);
		}

		/**
		 * Creates a builder pre-populated with a deep mutable copy of the given frozen index.
		 *
		 * @param source the frozen index to copy from
		 */
		private Builder(@Nonnull LocalTriggerIndex source) {
			this.mutableIndex = deepCopy(source.index);
		}

		/**
		 * Inserts a facet trigger. Uses `putIfAbsent` semantics — when multiple cross-entity
		 * triggers exist for the same `(ownerEntityType, referenceName, scope)` triple, only the
		 * first is stored since they are functionally equivalent for `evaluate()`.
		 *
		 * @param trigger the facet trigger to insert
		 */
		void insertFacetTrigger(@Nonnull FacetExpressionTrigger trigger) {
			this.mutableIndex
				.computeIfAbsent(trigger.getOwnerEntityType(), k -> createHashMap(4))
				.computeIfAbsent(trigger.getReferenceName(), k -> new EnumMap<>(Scope.class))
				.computeIfAbsent(trigger.getScope(), k -> new MutableReferenceTriggers())
				.setFacetTriggerIfAbsent(trigger);
		}

		/**
		 * Inserts a histogram trigger. Uses `putIfAbsent` semantics per histogram name.
		 *
		 * @param trigger the histogram trigger to insert
		 */
		void insertHistogramTrigger(@Nonnull HistogramExpressionTrigger trigger) {
			this.mutableIndex
				.computeIfAbsent(trigger.getOwnerEntityType(), k -> createHashMap(4))
				.computeIfAbsent(trigger.getReferenceName(), k -> new EnumMap<>(Scope.class))
				.computeIfAbsent(trigger.getScope(), k -> new MutableReferenceTriggers())
				.addHistogramTriggerIfAbsent(trigger);
		}

		/**
		 * Removes all triggers owned by the specified entity type. Since the top-level key is the
		 * owner entity type, this is a single O(1) map removal.
		 *
		 * @param ownerEntityType the owner entity type to remove
		 */
		void removeByOwner(@Nonnull String ownerEntityType) {
			this.mutableIndex.remove(ownerEntityType);
		}

		/**
		 * Freezes the mutable state into an immutable {@link LocalTriggerIndex}. Returns
		 * {@link LocalTriggerIndex#EMPTY} if the index is empty.
		 *
		 * @return the frozen index
		 */
		@Nonnull
		LocalTriggerIndex build() {
			if (this.mutableIndex.isEmpty()) {
				return EMPTY;
			}
			final Map<String, Map<String, Map<Scope, LocalReferenceTriggers>>> frozen =
				createHashMap(this.mutableIndex.size());
			for (final Entry<String, Map<String, Map<Scope, MutableReferenceTriggers>>> ownerEntry :
				this.mutableIndex.entrySet()) {
				final Map<String, Map<Scope, MutableReferenceTriggers>> refMap = ownerEntry.getValue();
				final Map<String, Map<Scope, LocalReferenceTriggers>> frozenRefMap =
					createHashMap(refMap.size());
				for (final Entry<String, Map<Scope, MutableReferenceTriggers>> refEntry :
					refMap.entrySet()) {
					final Map<Scope, MutableReferenceTriggers> scopeMap = refEntry.getValue();
					final EnumMap<Scope, LocalReferenceTriggers> frozenScopeMap =
						new EnumMap<>(Scope.class);
					for (final Entry<Scope, MutableReferenceTriggers> scopeEntry :
						scopeMap.entrySet()) {
						frozenScopeMap.put(scopeEntry.getKey(), scopeEntry.getValue().freeze());
					}
					frozenRefMap.put(refEntry.getKey(), Collections.unmodifiableMap(frozenScopeMap));
				}
				frozen.put(ownerEntry.getKey(), Collections.unmodifiableMap(frozenRefMap));
			}
			return new LocalTriggerIndex(Collections.unmodifiableMap(frozen));
		}

		/**
		 * Creates a deep mutable copy of the given frozen index, converting each
		 * {@link LocalReferenceTriggers} leaf into a {@link MutableReferenceTriggers}.
		 *
		 * @param source the frozen nested map
		 * @return a fully mutable deep copy
		 */
		@Nonnull
		private static Map<String, Map<String, Map<Scope, MutableReferenceTriggers>>> deepCopy(
			@Nonnull Map<String, Map<String, Map<Scope, LocalReferenceTriggers>>> source
		) {
			final Map<String, Map<String, Map<Scope, MutableReferenceTriggers>>> copy =
				createHashMap(source.size());
			for (final Entry<String, Map<String, Map<Scope, LocalReferenceTriggers>>> ownerEntry :
				source.entrySet()) {
				final Map<String, Map<Scope, LocalReferenceTriggers>> refMap = ownerEntry.getValue();
				final Map<String, Map<Scope, MutableReferenceTriggers>> refCopy =
					createHashMap(refMap.size());
				for (final Entry<String, Map<Scope, LocalReferenceTriggers>> refEntry :
					refMap.entrySet()) {
					final EnumMap<Scope, MutableReferenceTriggers> scopeCopy =
						new EnumMap<>(Scope.class);
					for (final Entry<Scope, LocalReferenceTriggers> scopeEntry :
						refEntry.getValue().entrySet()) {
						scopeCopy.put(
							scopeEntry.getKey(),
							new MutableReferenceTriggers(scopeEntry.getValue())
						);
					}
					refCopy.put(refEntry.getKey(), scopeCopy);
				}
				copy.put(ownerEntry.getKey(), refCopy);
			}
			return copy;
		}

		/**
		 * Mutable leaf type used during construction. Holds an optional facet trigger and a mutable
		 * map of histogram triggers. Converted to an immutable {@link LocalReferenceTriggers}
		 * record via {@link #freeze()}.
		 */
		private static final class MutableReferenceTriggers {

			/**
			 * The facet trigger for this `(owner, ref, scope)` triple, or null if none.
			 */
			@Nullable
			private FacetExpressionTrigger facetTrigger;

			/**
			 * Mutable map of histogram name to histogram trigger.
			 */
			@Nonnull
			private final Map<String, HistogramExpressionTrigger> histogramTriggers;

			/**
			 * Creates an empty mutable leaf.
			 */
			MutableReferenceTriggers() {
				this.histogramTriggers = createHashMap(4);
			}

			/**
			 * Creates a mutable leaf from a frozen {@link LocalReferenceTriggers}.
			 *
			 * @param source the frozen leaf to copy from
			 */
			MutableReferenceTriggers(@Nonnull LocalReferenceTriggers source) {
				this.facetTrigger = source.facetTrigger();
				this.histogramTriggers = createHashMap(source.histogramTriggers().size());
				this.histogramTriggers.putAll(source.histogramTriggers());
			}

			/**
			 * Sets the facet trigger if not already set (putIfAbsent semantics).
			 *
			 * @param trigger the facet trigger
			 */
			void setFacetTriggerIfAbsent(@Nonnull FacetExpressionTrigger trigger) {
				if (this.facetTrigger == null) {
					this.facetTrigger = trigger;
				}
			}

			/**
			 * Adds a histogram trigger if not already present for the given histogram name.
			 *
			 * @param trigger the histogram trigger
			 */
			void addHistogramTriggerIfAbsent(@Nonnull HistogramExpressionTrigger trigger) {
				this.histogramTriggers.putIfAbsent(trigger.getHistogramIndexName(), trigger);
			}

			/**
			 * Freezes this mutable leaf into an immutable {@link LocalReferenceTriggers} record.
			 *
			 * @return the frozen leaf
			 */
			@Nonnull
			LocalReferenceTriggers freeze() {
				return new LocalReferenceTriggers(
					this.facetTrigger,
					this.histogramTriggers.isEmpty()
						? Collections.emptyMap()
						: Collections.unmodifiableMap(this.histogramTriggers)
				);
			}
		}
	}

}
