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

import io.evitadb.core.expression.trigger.DependencyType;
import io.evitadb.core.expression.trigger.ExpressionIndexTrigger;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Immutable, thread-safe index for cross-entity trigger lookup by `(mutatedEntityType, dependencyType)`.
 * Internally backed by a nested map, but exposes only typed lookup methods — callers never see raw
 * `Map<String, Map<DependencyType, List<...>>>` signatures.
 *
 * Follows a copy-on-write pattern: {@link #toBuilder()} produces a mutable {@link Builder} that
 * creates a new frozen instance via {@link Builder#build()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see CatalogExpressionTriggerRegistry
 */
final class CrossEntityTriggerIndex {

	/**
	 * Empty singleton — no triggers registered.
	 */
	static final CrossEntityTriggerIndex EMPTY =
		new CrossEntityTriggerIndex(Collections.emptyMap(), Collections.emptyMap());

	/**
	 * Frozen nested map: mutated entity type -> dependency type -> trigger list.
	 * All maps and lists are unmodifiable after construction.
	 */
	@Nonnull
	private final Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> index;

	/**
	 * Pre-built reverse index: mutated entity type -> set of entity-level attribute names referenced
	 * by cross-entity triggers under entity-attribute dependency types ({@link DependencyType#isEntityAttributeDependency()}).
	 * Used for O(1) checks in {@link #hasEntityAttributeTrigger} and {@link #hasAnyEntityAttributeTriggers}
	 * to avoid unnecessary pre-mutation value capture when no trigger cares about the mutated attribute.
	 */
	@Nonnull
	private final Map<String, Set<String>> entityAttributeIndex;

	/**
	 * Creates a frozen index from already-frozen maps. Callers must ensure all nested structures
	 * are unmodifiable.
	 *
	 * @param frozenIndex          the frozen nested trigger map
	 * @param entityAttributeIndex the frozen entity-attribute reverse index
	 */
	private CrossEntityTriggerIndex(
		@Nonnull Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> frozenIndex,
		@Nonnull Map<String, Set<String>> entityAttributeIndex
	) {
		this.index = frozenIndex;
		this.entityAttributeIndex = entityAttributeIndex;
	}

	/**
	 * Returns all triggers that depend on the given entity type with the specified dependency relationship.
	 *
	 * @param mutatedEntityType the entity type being mutated (e.g., "parameterGroup")
	 * @param dependencyType    how the mutated entity relates to the trigger owner
	 * @return matching triggers (empty list if none, never null)
	 */
	@Nonnull
	List<ExpressionIndexTrigger> getTriggersFor(
		@Nonnull String mutatedEntityType,
		@Nonnull DependencyType dependencyType
	) {
		final Map<DependencyType, List<ExpressionIndexTrigger>> innerMap = this.index.get(mutatedEntityType);
		if (innerMap == null) {
			return Collections.emptyList();
		}
		final List<ExpressionIndexTrigger> triggers = innerMap.get(dependencyType);
		return triggers != null ? triggers : Collections.emptyList();
	}

	/**
	 * Returns only triggers whose {@link ExpressionIndexTrigger#getDependentAttributes()} contains the
	 * given attribute name. A trigger with an empty `dependentAttributes` set is never returned.
	 *
	 * @param mutatedEntityType the entity type being mutated
	 * @param dependencyType    how the mutated entity relates to the trigger owner
	 * @param attributeName     the attribute that changed
	 * @return matching triggers filtered by attribute (empty list if none, never null)
	 */
	@Nonnull
	List<ExpressionIndexTrigger> getTriggersForAttribute(
		@Nonnull String mutatedEntityType,
		@Nonnull DependencyType dependencyType,
		@Nonnull String attributeName
	) {
		final List<ExpressionIndexTrigger> allTriggers = getTriggersFor(mutatedEntityType, dependencyType);
		if (allTriggers.isEmpty()) {
			return Collections.emptyList();
		}
		final List<ExpressionIndexTrigger> filtered = new ArrayList<>(allTriggers.size());
		for (final ExpressionIndexTrigger trigger : allTriggers) {
			if (trigger.getDependentAttributes().contains(attributeName)) {
				filtered.add(trigger);
			}
		}
		return filtered.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(filtered);
	}

	/**
	 * Returns `true` if any cross-entity trigger under an entity-attribute dependency type references
	 * the given attribute name for the specified mutated entity type. Used to skip pre-mutation value
	 * capture when no trigger depends on the attribute being mutated.
	 *
	 * @param mutatedEntityType the entity type being mutated
	 * @param attributeName     the attribute that changed
	 * @return `true` if at least one entity-attribute trigger depends on this attribute
	 */
	boolean hasEntityAttributeTrigger(
		@Nonnull String mutatedEntityType,
		@Nonnull String attributeName
	) {
		final Set<String> attributes = this.entityAttributeIndex.get(mutatedEntityType);
		return attributes != null && attributes.contains(attributeName);
	}

	/**
	 * Returns `true` if any cross-entity trigger under an entity-attribute dependency type exists
	 * for the specified mutated entity type. Used to skip bulk pre-mutation value capture (e.g.,
	 * during scope changes) when no trigger depends on any attribute of this entity type.
	 *
	 * @param mutatedEntityType the entity type being mutated
	 * @return `true` if at least one entity-attribute trigger exists for this entity type
	 */
	boolean hasAnyEntityAttributeTriggers(@Nonnull String mutatedEntityType) {
		return this.entityAttributeIndex.containsKey(mutatedEntityType);
	}

	/**
	 * Returns the set of entity-level attribute names referenced by cross-entity triggers under
	 * entity-attribute dependency types for the specified mutated entity type. The returned set is
	 * unmodifiable and pre-computed at build time.
	 *
	 * @param mutatedEntityType the entity type being mutated
	 * @return attribute names referenced by triggers, or empty set if none
	 */
	@Nonnull
	Set<String> getEntityAttributeNames(@Nonnull String mutatedEntityType) {
		return this.entityAttributeIndex.getOrDefault(mutatedEntityType, Collections.emptySet());
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
	 * Returns the total number of triggers across all entity types and dependency types.
	 *
	 * @return the total trigger count
	 */
	int triggerCount() {
		int count = 0;
		for (final Map<DependencyType, List<ExpressionIndexTrigger>> innerMap : this.index.values()) {
			for (final List<ExpressionIndexTrigger> triggers : innerMap.values()) {
				count += triggers.size();
			}
		}
		return count;
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
	 * @param expectedEntityTypes estimated number of distinct mutated entity types
	 * @return a new empty builder
	 */
	@Nonnull
	static Builder newBuilder(int expectedEntityTypes) {
		return new Builder(expectedEntityTypes);
	}

	/**
	 * Mutable builder for constructing a {@link CrossEntityTriggerIndex}. Supports insertion and
	 * owner-based removal, then freezes the result into an immutable index via {@link #build()}.
	 */
	static final class Builder {

		/**
		 * Mutable nested map: mutated entity type -> dependency type -> trigger list.
		 */
		@Nonnull
		private final Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> mutableIndex;

		/**
		 * Creates an empty builder with the given expected capacity.
		 *
		 * @param expectedSize estimated number of distinct mutated entity types
		 */
		private Builder(int expectedSize) {
			this.mutableIndex = createHashMap(expectedSize);
		}

		/**
		 * Creates a builder pre-populated with a deep mutable copy of the given frozen index.
		 *
		 * @param source the frozen index to copy from
		 */
		private Builder(@Nonnull CrossEntityTriggerIndex source) {
			this.mutableIndex = deepCopy(source.index);
		}

		/**
		 * Inserts a trigger into the index under its `(mutatedEntityType, dependencyType)` key.
		 * Local-only triggers (both `mutatedEntityType` and `dependencyType` null) are silently
		 * skipped. An {@link IllegalStateException} is thrown if exactly one is null.
		 *
		 * @param trigger the trigger to insert
		 * @throws IllegalStateException if exactly one of mutatedEntityType/dependencyType is null
		 */
		void insert(@Nonnull ExpressionIndexTrigger trigger) {
			final String mutatedEntityType = trigger.getMutatedEntityType();
			final DependencyType dependencyType = trigger.getDependencyType();
			if (mutatedEntityType == null && dependencyType == null) {
				// local-only triggers are not registered in the cross-entity index
				return;
			}
			if (mutatedEntityType == null || dependencyType == null) {
				throw new IllegalStateException(
					"ExpressionIndexTrigger for reference `" + trigger.getReferenceName() +
						"` on entity `" + trigger.getOwnerEntityType() +
						"` has inconsistent null state: mutatedEntityType=" + mutatedEntityType +
						", dependencyType=" + dependencyType +
						". Both must be null (local-only) or both non-null (cross-entity)."
				);
			}
			this.mutableIndex
				.computeIfAbsent(mutatedEntityType, k -> new EnumMap<>(DependencyType.class))
				.computeIfAbsent(dependencyType, k -> new ArrayList<>(4))
				.add(trigger);
		}

		/**
		 * Removes all triggers owned by the specified entity type from every key in the index.
		 * Iterates all outer keys because a single owner entity type's references may produce
		 * triggers indexed under different mutated entity types.
		 *
		 * @param ownerEntityType the owner entity type whose triggers should be removed
		 */
		void removeTriggersOwnedBy(@Nonnull String ownerEntityType) {
			final Iterator<Entry<String, Map<DependencyType, List<ExpressionIndexTrigger>>>> outerIt =
				this.mutableIndex.entrySet().iterator();
			while (outerIt.hasNext()) {
				final Entry<String, Map<DependencyType, List<ExpressionIndexTrigger>>> outerEntry =
					outerIt.next();
				final Map<DependencyType, List<ExpressionIndexTrigger>> innerMap = outerEntry.getValue();
				final Iterator<Entry<DependencyType, List<ExpressionIndexTrigger>>> innerIt =
					innerMap.entrySet().iterator();
				while (innerIt.hasNext()) {
					final Entry<DependencyType, List<ExpressionIndexTrigger>> innerEntry = innerIt.next();
					innerEntry.getValue().removeIf(
						trigger -> ownerEntityType.equals(trigger.getOwnerEntityType())
					);
					if (innerEntry.getValue().isEmpty()) {
						innerIt.remove();
					}
				}
				if (innerMap.isEmpty()) {
					outerIt.remove();
				}
			}
		}

		/**
		 * Freezes the mutable state into an immutable {@link CrossEntityTriggerIndex}. Returns
		 * {@link CrossEntityTriggerIndex#EMPTY} if the index is empty. Pre-computes the entity
		 * attribute reverse index for O(1) attribute-level trigger existence checks.
		 *
		 * @return the frozen index
		 */
		@Nonnull
		CrossEntityTriggerIndex build() {
			if (this.mutableIndex.isEmpty()) {
				return EMPTY;
			}
			final Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> frozen =
				createHashMap(this.mutableIndex.size());
			final Map<String, Set<String>> entityAttrIndex = createHashMap(this.mutableIndex.size());
			for (final Entry<String, Map<DependencyType, List<ExpressionIndexTrigger>>> outerEntry :
				this.mutableIndex.entrySet()) {
				final String mutatedEntityType = outerEntry.getKey();
				final Map<DependencyType, List<ExpressionIndexTrigger>> innerMap = outerEntry.getValue();
				final EnumMap<DependencyType, List<ExpressionIndexTrigger>> frozenInner =
					new EnumMap<>(DependencyType.class);
				for (final Entry<DependencyType, List<ExpressionIndexTrigger>> innerEntry :
					innerMap.entrySet()) {
					frozenInner.put(
						innerEntry.getKey(),
						Collections.unmodifiableList(innerEntry.getValue())
					);
					// collect entity-attribute dependency names for the reverse index
					if (innerEntry.getKey().isEntityAttributeDependency()) {
						for (final ExpressionIndexTrigger trigger : innerEntry.getValue()) {
							final Set<String> dependentAttributes = trigger.getDependentAttributes();
							if (!dependentAttributes.isEmpty()) {
								entityAttrIndex
									.computeIfAbsent(mutatedEntityType, k -> createHashSet(8))
									.addAll(dependentAttributes);
							}
						}
					}
				}
				frozen.put(outerEntry.getKey(), Collections.unmodifiableMap(frozenInner));
			}
			// freeze the entity attribute index
			final Map<String, Set<String>> frozenEntityAttrIndex;
			if (entityAttrIndex.isEmpty()) {
				frozenEntityAttrIndex = Collections.emptyMap();
			} else {
				frozenEntityAttrIndex = createHashMap(entityAttrIndex.size());
				for (final Entry<String, Set<String>> entry : entityAttrIndex.entrySet()) {
					frozenEntityAttrIndex.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
				}
			}
			return new CrossEntityTriggerIndex(
				Collections.unmodifiableMap(frozen),
				Collections.unmodifiableMap(frozenEntityAttrIndex)
			);
		}

		/**
		 * Creates a deep mutable copy of the given frozen index.
		 *
		 * @param source the frozen nested map
		 * @return a fully mutable deep copy
		 */
		@Nonnull
		private static Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> deepCopy(
			@Nonnull Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> source
		) {
			final Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> copy =
				createHashMap(source.size());
			for (final Entry<String, Map<DependencyType, List<ExpressionIndexTrigger>>> outerEntry :
				source.entrySet()) {
				final EnumMap<DependencyType, List<ExpressionIndexTrigger>> innerCopy =
					new EnumMap<>(DependencyType.class);
				for (final Entry<DependencyType, List<ExpressionIndexTrigger>> innerEntry :
					outerEntry.getValue().entrySet()) {
					innerCopy.put(innerEntry.getKey(), new ArrayList<>(innerEntry.getValue()));
				}
				copy.put(outerEntry.getKey(), innerCopy);
			}
			return copy;
		}
	}

}
