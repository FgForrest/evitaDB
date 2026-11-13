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
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.core.expression.trigger.FacetExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramExpressionTrigger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Immutable local trigger index with one {@link OwnerTriggers} object per owner entity type.
 * Each owner holds its reference and scope lookup once, with {@link LocalReferenceTriggers} combining facet and
 * histogram triggers. Reverse lookups start with separate kind maps for owner attributes, the parent and associated
 * data, keyed by owner. A lookup that finds nothing stops at the first level that lacks the kind, the owner or the
 * scope - a catalog without any reader of the kind answers from an empty map without hashing - so an entity type
 * none of whose histogram conditions reads that kind of input (any attribute, the parent, any associated data) in any
 * scope pays at most one map probe. Each kind map leads directly to the owner's populated scopes and inputs, without
 * passing through the forward index.
 *
 * Rebuilds replace complete owners. {@link #toBuilder()} shallowly copies the owner map and shares unchanged frozen
 * owners; only owners the builder inserts triggers for, whether new to the index or removed and reinserted, are
 * constructed anew. All reverse dependencies are derived together in one traversal per owner so they can be reused on
 * rebuild. The index assembles its kind maps from all owners, including shared owners, to preserve their reverse
 * lookups when another owner changes.
 *
 * Immutability of internal maps is guaranteed by encapsulation: they are built once, never exposed and never mutated
 * after construction. Unmodifiable map wrappers are omitted on purpose because each costs a dependent load on the
 * write path. The reference-name sets returned to callers and the histogram map in {@link LocalReferenceTriggers}
 * retain their unmodifiable wrappers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see CatalogExpressionTriggerRegistry
 */
@ThreadSafe
final class LocalTriggerIndex {

	/**
	 * Empty singleton — no triggers registered.
	 */
	static final LocalTriggerIndex EMPTY = new LocalTriggerIndex(Collections.emptyMap());

	/**
	 * Owner entity type to encapsulated, immutable forward and reverse trigger lookups.
	 */
	@Nonnull
	private final Map<String, OwnerTriggers> triggersByOwner;

	/**
	 * Owner entity type to populated attribute dependencies, empty when no owner reads an attribute.
	 */
	@Nonnull
	private final Map<String, ReferenceNamesByScopeAndInput> histogramReferencesByEntityAttribute;

	/**
	 * Owner entity type to populated parent dependencies, empty when no owner reads its parent.
	 */
	@Nonnull
	private final Map<String, ReferenceNamesByScope> histogramReferencesReadingParent;

	/**
	 * Owner entity type to populated associated data dependencies, empty when no owner reads associated data.
	 */
	@Nonnull
	private final Map<String, ReferenceNamesByScopeAndInput> histogramReferencesByAssociatedData;

	/**
	 * Takes ownership of a private frozen owner map and assembles the reverse kind maps from every owner, including
	 * shared unchanged owners. Internal maps must never be exposed or mutated after construction.
	 *
	 * @param triggersByOwner the complete owner map exclusively owned by this index
	 */
	private LocalTriggerIndex(@Nonnull Map<String, OwnerTriggers> triggersByOwner) {
		this.triggersByOwner = triggersByOwner;
		final Map<String, ReferenceNamesByScopeAndInput> byEntityAttribute = createHashMap(triggersByOwner.size());
		final Map<String, ReferenceNamesByScope> readingParent = createHashMap(triggersByOwner.size());
		final Map<String, ReferenceNamesByScopeAndInput> byAssociatedData = createHashMap(triggersByOwner.size());
		for (final Entry<String, OwnerTriggers> entry : triggersByOwner.entrySet()) {
			final OwnerTriggers owner = entry.getValue();
			if (owner.histogramReferencesByEntityAttribute != null) {
				byEntityAttribute.put(entry.getKey(), owner.histogramReferencesByEntityAttribute);
			}
			if (owner.histogramReferencesReadingParent != null) {
				readingParent.put(entry.getKey(), owner.histogramReferencesReadingParent);
			}
			if (owner.histogramReferencesByAssociatedData != null) {
				byAssociatedData.put(entry.getKey(), owner.histogramReferencesByAssociatedData);
			}
		}
		this.histogramReferencesByEntityAttribute = byEntityAttribute.isEmpty() ? Collections.emptyMap() :
			byEntityAttribute;
		this.histogramReferencesReadingParent = readingParent.isEmpty() ? Collections.emptyMap() : readingParent;
		this.histogramReferencesByAssociatedData = byAssociatedData.isEmpty() ? Collections.emptyMap() :
			byAssociatedData;
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
		final OwnerTriggers owner = this.triggersByOwner.get(ownerEntityType);
		final LocalReferenceTriggers leaf = owner == null ? null : owner.getTriggers(referenceName, scope);
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
		final OwnerTriggers owner = this.triggersByOwner.get(ownerEntityType);
		final LocalReferenceTriggers leaf = owner == null ? null : owner.getTriggers(referenceName, scope);
		return leaf != null ? leaf.histogramTriggers().values() : Collections.emptyList();
	}

	/**
	 * Returns the reference names whose histogram conditions read this owner attribute in the given scope.
	 *
	 * @param ownerEntityType the entity type owning the references
	 * @param scope           the scope of the local mutation
	 * @param attributeName   the owner attribute being changed
	 * @return immutable affected reference names, or an empty set
	 */
	@Nonnull
	Set<String> getHistogramReferencesForEntityAttribute(
		@Nonnull String ownerEntityType,
		@Nonnull Scope scope,
		@Nonnull String attributeName
	) {
		final ReferenceNamesByScopeAndInput references = this.histogramReferencesByEntityAttribute.get(ownerEntityType);
		return references == null ? Collections.emptySet() : references.get(scope, attributeName);
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
		final ReferenceNamesByScope references = this.histogramReferencesReadingParent.get(ownerEntityType);
		return references == null ? Collections.emptySet() : references.get(scope);
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
		final ReferenceNamesByScopeAndInput references = this.histogramReferencesByAssociatedData.get(ownerEntityType);
		return references == null ? Collections.emptySet() : references.get(scope, associatedDataName);
	}

	/**
	 * Returns `true` if this index contains no triggers.
	 *
	 * @return true if empty
	 */
	boolean isEmpty() {
		return this.triggersByOwner.isEmpty();
	}

	/**
	 * Creates a mutable {@link Builder} with a shallow copy of the owner map, sharing unchanged frozen owners.
	 * Triggers for an owner this index already holds can be inserted only after that owner is removed; inserting them
	 * earlier throws {@link GenericEvitaInternalError}.
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
	 * Immutable trigger lookups for one owner. Internal maps are exclusively owned and never exposed.
	 */
	private static final class OwnerTriggers {

		/**
		 * Reference name to scope to leaf triggers; the scope maps are enum maps.
		 */
		@Nonnull
		private final Map<String, Map<Scope, LocalReferenceTriggers>> triggersByReference;

		/**
		 * Derived attribute dependencies, or null when this owner reads no attributes.
		 */
		@Nullable
		private final ReferenceNamesByScopeAndInput histogramReferencesByEntityAttribute;

		/**
		 * Derived parent dependencies, or null when this owner never reads its parent.
		 */
		@Nullable
		private final ReferenceNamesByScope histogramReferencesReadingParent;

		/**
		 * Derived associated data dependencies, or null when this owner reads no associated data.
		 */
		@Nullable
		private final ReferenceNamesByScopeAndInput histogramReferencesByAssociatedData;

		/**
		 * Takes ownership of the frozen forward lookup and derives all local reverse dependencies in one traversal.
		 * Only populated kinds, scopes and inputs are retained, so unchanged owners can reuse these structures.
		 *
		 * @param triggersByReference the private forward lookup, never mutated after this call
		 */
		private OwnerTriggers(@Nonnull Map<String, Map<Scope, LocalReferenceTriggers>> triggersByReference) {
			this.triggersByReference = triggersByReference;
			final Map<Scope, Map<String, Set<String>>> byEntityAttribute = new EnumMap<>(Scope.class);
			final Map<Scope, Set<String>> readingParent = new EnumMap<>(Scope.class);
			final Map<Scope, Map<String, Set<String>>> byAssociatedData = new EnumMap<>(Scope.class);
			for (final Entry<String, Map<Scope, LocalReferenceTriggers>> referenceEntry :
				triggersByReference.entrySet()) {
				for (final Entry<Scope, LocalReferenceTriggers> scopeEntry : referenceEntry.getValue().entrySet()) {
					for (final HistogramExpressionTrigger trigger :
						scopeEntry.getValue().histogramTriggers().values()) {
						for (final String attribute : trigger.getLocalEntityAttributes()) {
							byEntityAttribute.computeIfAbsent(scopeEntry.getKey(), ignored -> createHashMap(4))
								.computeIfAbsent(attribute, ignored -> createHashSet(4)).add(referenceEntry.getKey());
						}
						if (trigger.usesParent()) {
							readingParent.computeIfAbsent(scopeEntry.getKey(), ignored -> createHashSet(4))
								.add(referenceEntry.getKey());
						}
						for (final String associatedData : trigger.getLocalAssociatedData()) {
							byAssociatedData.computeIfAbsent(scopeEntry.getKey(), ignored -> createHashMap(4))
								.computeIfAbsent(associatedData, ignored -> createHashSet(4))
								.add(referenceEntry.getKey());
						}
					}
				}
			}
			this.histogramReferencesByEntityAttribute = byEntityAttribute.isEmpty() ? null :
				new ReferenceNamesByScopeAndInput(byEntityAttribute);
			this.histogramReferencesReadingParent = readingParent.isEmpty() ? null :
				new ReferenceNamesByScope(readingParent);
			this.histogramReferencesByAssociatedData = byAssociatedData.isEmpty() ? null :
				new ReferenceNamesByScopeAndInput(byAssociatedData);
		}

		/**
		 * Returns the leaf for the reference and scope, or null if either is absent.
		 */
		@Nullable
		private LocalReferenceTriggers getTriggers(@Nonnull String referenceName, @Nonnull Scope scope) {
			final Map<Scope, LocalReferenceTriggers> byScope = this.triggersByReference.get(referenceName);
			return byScope == null ? null : byScope.get(scope);
		}
	}

	/**
	 * Encapsulated reference names for one owner and input kind, retaining only populated scopes.
	 */
	private static final class ReferenceNamesByScope {

		/**
		 * Scope to unmodifiable reference-name set, stored in an enum map.
		 */
		@Nonnull
		private final Map<Scope, Set<String>> byScope;

		/**
		 * Takes ownership of the populated scope map and wraps only the sets exposed to callers.
		 */
		private ReferenceNamesByScope(@Nonnull Map<Scope, Set<String>> byScope) {
			byScope.replaceAll((scope, references) -> Collections.unmodifiableSet(references));
			this.byScope = byScope;
		}

		/**
		 * Returns the references in the scope, or an empty set, with a single enum-map probe.
		 */
		@Nonnull
		private Set<String> get(@Nonnull Scope scope) {
			final Set<String> references = this.byScope.get(scope);
			return references == null ? Collections.emptySet() : references;
		}
	}

	/**
	 * Encapsulated reference names for one owner and input kind, retaining only populated scopes and input names.
	 */
	private static final class ReferenceNamesByScopeAndInput {

		/**
		 * Scope to input name to unmodifiable reference-name set; the scope map is an enum map.
		 */
		@Nonnull
		private final Map<Scope, Map<String, Set<String>>> byScope;

		/**
		 * Takes ownership of the populated maps and wraps only the sets exposed to callers.
		 */
		private ReferenceNamesByScopeAndInput(@Nonnull Map<Scope, Map<String, Set<String>>> byScope) {
			for (final Map<String, Set<String>> byInput : byScope.values()) {
				byInput.replaceAll((input, references) -> Collections.unmodifiableSet(references));
			}
			this.byScope = byScope;
		}

		/**
		 * Returns the references reading the input in the scope, or an empty set. An absent scope costs one probe.
		 */
		@Nonnull
		private Set<String> get(@Nonnull Scope scope, @Nonnull String inputName) {
			final Map<String, Set<String>> byInput = this.byScope.get(scope);
			return byInput == null ? Collections.emptySet() : byInput.getOrDefault(inputName, Collections.emptySet());
		}
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
	 * Mutable builder that shares unchanged frozen owners and accumulates complete replacements separately.
	 * An unchanged owner must be removed before inserting its replacement triggers. Building freezes only changed
	 * owners and copies the owner map, leaving previously built indexes untouched.
	 *
	 * A builder is used once: {@link #build()} hands the histogram trigger maps of the changed owners over to the
	 * built index, so the builder must be discarded afterwards.
	 */
	@NotThreadSafe
	static final class Builder {

		/**
		 * Shallow copy of the source owner map. Its frozen owner values are shared and never mutated.
		 */
		@Nonnull
		private final Map<String, OwnerTriggers> unchangedOwners;

		/**
		 * New or removed and reinserted owners, accumulated independently of every frozen index.
		 */
		@Nonnull
		private final Map<String, MutableOwnerTriggers> changedOwners;

		/**
		 * Creates an empty builder with the expected number of owner entity types.
		 */
		private Builder(int expectedSize) {
			this.unchangedOwners = createHashMap(0);
			this.changedOwners = createHashMap(expectedSize);
		}

		/**
		 * Shallowly copies the source owner map, sharing its immutable owner objects.
		 */
		private Builder(@Nonnull LocalTriggerIndex source) {
			this.unchangedOwners = createHashMap(source.triggersByOwner.size());
			this.unchangedOwners.putAll(source.triggersByOwner);
			this.changedOwners = createHashMap(4);
		}

		/**
		 * Inserts a facet trigger with putIfAbsent semantics for each owner, reference and scope triple.
		 * Dependency variants are equivalent for inline evaluation, so only the first is stored.
		 *
		 * @param trigger the facet trigger to insert
		 * @throws GenericEvitaInternalError if the trigger's owner is an unchanged owner shared from the source index
		 */
		void insertFacetTrigger(@Nonnull FacetExpressionTrigger trigger) {
			getMutableLeaf(trigger.getOwnerEntityType(), trigger.getReferenceName(), trigger.getScope())
				.setFacetTriggerIfAbsent(trigger);
		}

		/**
		 * Inserts a histogram trigger with putIfAbsent semantics per histogram name.
		 *
		 * @param trigger the histogram trigger to insert
		 * @throws GenericEvitaInternalError if the trigger's owner is an unchanged owner shared from the source index
		 */
		void insertHistogramTrigger(@Nonnull HistogramExpressionTrigger trigger) {
			getMutableLeaf(trigger.getOwnerEntityType(), trigger.getReferenceName(), trigger.getScope())
				.addHistogramTriggerIfAbsent(trigger);
		}

		/**
		 * Removes the owner's frozen and mutable entries with two constant-time map removals, permitting replacement.
		 */
		void removeByOwner(@Nonnull String ownerEntityType) {
			this.unchangedOwners.remove(ownerEntityType);
			this.changedOwners.remove(ownerEntityType);
		}

		/**
		 * Builds an immutable index sharing unchanged owners and freezing changed owners into new frozen owners.
		 * Returns {@link LocalTriggerIndex#EMPTY} when neither map contains an owner.
		 */
		@Nonnull
		LocalTriggerIndex build() {
			if (this.unchangedOwners.isEmpty() && this.changedOwners.isEmpty()) {
				return EMPTY;
			}
			final Map<String, OwnerTriggers> frozen =
				createHashMap(this.unchangedOwners.size() + this.changedOwners.size());
			frozen.putAll(this.unchangedOwners);
			for (final Entry<String, MutableOwnerTriggers> owner : this.changedOwners.entrySet()) {
				frozen.put(owner.getKey(), owner.getValue().freeze());
			}
			return new LocalTriggerIndex(frozen);
		}

		/**
		 * Resolves a changed owner or creates a new one. Inserting into an unchanged frozen owner is a programming
		 * error: rebuilds must remove that owner before supplying its complete replacement trigger list.
		 */
		@Nonnull
		private MutableOwnerTriggers getMutableOwner(@Nonnull String ownerEntityType) {
			final MutableOwnerTriggers changed = this.changedOwners.get(ownerEntityType);
			if (changed != null) {
				return changed;
			}
			if (this.unchangedOwners.containsKey(ownerEntityType)) {
				throw new GenericEvitaInternalError(
					"Cannot insert triggers for unchanged owner `" + ownerEntityType + "`; remove the owner first."
				);
			}
			final MutableOwnerTriggers owner = new MutableOwnerTriggers();
			this.changedOwners.put(ownerEntityType, owner);
			return owner;
		}

		/**
		 * Resolves the mutable leaf of a changed owner for the reference and scope, creating the missing levels.
		 */
		@Nonnull
		private MutableReferenceTriggers getMutableLeaf(
			@Nonnull String ownerEntityType,
			@Nonnull String referenceName,
			@Nonnull Scope scope
		) {
			return getMutableOwner(ownerEntityType).triggersByReference
				.computeIfAbsent(referenceName, ignored -> new EnumMap<>(Scope.class))
				.computeIfAbsent(scope, ignored -> new MutableReferenceTriggers());
		}

		/**
		 * Mutable forward lookup for one new or replacement owner, frozen once by {@link Builder#build()}.
		 */
		private static final class MutableOwnerTriggers {

			/**
			 * Reference name to scope to mutable leaf; scope maps are enum maps.
			 */
			@Nonnull
			private final Map<String, Map<Scope, MutableReferenceTriggers>> triggersByReference = createHashMap(4);

			/**
			 * Converts the mutable forward lookup into an exclusively owned frozen one and derives its reverse
			 * dependencies.
			 */
			@Nonnull
			private OwnerTriggers freeze() {
				final Map<String, Map<Scope, LocalReferenceTriggers>> frozen =
					createHashMap(this.triggersByReference.size());
				for (final Entry<String, Map<Scope, MutableReferenceTriggers>> referenceEntry :
					this.triggersByReference.entrySet()) {
					final Map<Scope, LocalReferenceTriggers> byScope = new EnumMap<>(Scope.class);
					for (final Entry<Scope, MutableReferenceTriggers> scopeEntry :
						referenceEntry.getValue().entrySet()) {
						byScope.put(scopeEntry.getKey(), scopeEntry.getValue().freeze());
					}
					frozen.put(referenceEntry.getKey(), byScope);
				}
				return new OwnerTriggers(frozen);
			}
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
			 * Freezes this mutable leaf into an immutable {@link LocalReferenceTriggers} record. The histogram map is
			 * handed over, not copied: the builder is discarded after {@link Builder#build()}.
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
