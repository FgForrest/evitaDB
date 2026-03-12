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

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.expression.trigger.FacetExpressionTriggerFactory;
import io.evitadb.index.mutation.DependencyType;
import io.evitadb.index.mutation.ExpressionIndexTrigger;
import io.evitadb.index.mutation.FacetExpressionTrigger;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Immutable, thread-safe implementation of {@link CatalogExpressionTriggerRegistry}. Stores triggers in a nested map
 * keyed by `(mutatedEntityType, DependencyType)` for O(1) lookup. All collections are wrapped as unmodifiable.
 *
 * The registry follows a copy-on-write pattern: {@link #rebuildForEntityType} produces a new instance with the
 * updated index, leaving the original untouched for concurrent readers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see CatalogExpressionTriggerRegistry
 */
@ThreadSafe
class CatalogExpressionTriggerRegistryImpl implements CatalogExpressionTriggerRegistry {

	/**
	 * Nested immutable map: mutated entity type -> (DependencyType -> trigger list). Both the outer map,
	 * inner maps, and trigger lists are unmodifiable.
	 */
	@Nonnull
	private final Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> triggerIndex;

	/**
	 * Creates a new registry from a pre-built trigger index. The constructor stores a deep-immutable defensive copy:
	 * the outer map, each inner `EnumMap`, and each trigger list are wrapped as unmodifiable.
	 *
	 * @param triggerIndex the mutable trigger index to copy and freeze
	 */
	CatalogExpressionTriggerRegistryImpl(
		@Nonnull Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> triggerIndex
	) {
		if (triggerIndex.isEmpty()) {
			this.triggerIndex = Collections.emptyMap();
		} else {
			final Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> frozen =
				createHashMap(triggerIndex.size());
			for (final Entry<String, Map<DependencyType, List<ExpressionIndexTrigger>>> outerEntry :
				triggerIndex.entrySet()) {
				final Map<DependencyType, List<ExpressionIndexTrigger>> innerMap = outerEntry.getValue();
				final EnumMap<DependencyType, List<ExpressionIndexTrigger>> frozenInner =
					new EnumMap<>(DependencyType.class);
				for (final Entry<DependencyType, List<ExpressionIndexTrigger>> innerEntry : innerMap.entrySet()) {
					frozenInner.put(innerEntry.getKey(), Collections.unmodifiableList(innerEntry.getValue()));
				}
				frozen.put(outerEntry.getKey(), Collections.unmodifiableMap(frozenInner));
			}
			this.triggerIndex = Collections.unmodifiableMap(frozen);
		}
	}

	/**
	 * Builds a fully populated registry by scanning all entity schemas and their reference schemas for
	 * `facetedPartiallyInScopes` expressions. Used during cold start / catalog initialization.
	 *
	 * Currently only builds facet triggers via {@link FacetExpressionTriggerFactory}. When histogram trigger
	 * support is added, this method must be extended to also call the histogram trigger factory.
	 *
	 * @param entitySchemaIndex all loaded entity schemas keyed by entity type name
	 * @return a fully populated registry, or {@link CatalogExpressionTriggerRegistry#EMPTY} if no schemas
	 *         carry conditional expressions
	 */
	@Nonnull
	static CatalogExpressionTriggerRegistry buildFromSchemas(
		@Nonnull Map<String, EntitySchemaContract> entitySchemaIndex
	) {
		final Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> mutableIndex =
			createHashMap(entitySchemaIndex.size());

		for (final EntitySchemaContract entitySchema : entitySchemaIndex.values()) {
			final String ownerEntityType = entitySchema.getName();
			for (final ReferenceSchemaContract referenceSchema : entitySchema.getReferences().values()) {
				final List<FacetExpressionTrigger> triggers =
					FacetExpressionTriggerFactory.buildTriggersForReference(ownerEntityType, referenceSchema);
				for (final FacetExpressionTrigger trigger : triggers) {
					insertTrigger(mutableIndex, trigger);
				}
			}
		}

		if (mutableIndex.isEmpty()) {
			return CatalogExpressionTriggerRegistry.EMPTY;
		}
		return new CatalogExpressionTriggerRegistryImpl(mutableIndex);
	}

	@Nonnull
	@Override
	public List<ExpressionIndexTrigger> getTriggersFor(
		@Nonnull String mutatedEntityType,
		@Nonnull DependencyType dependencyType
	) {
		final Map<DependencyType, List<ExpressionIndexTrigger>> innerMap =
			this.triggerIndex.get(mutatedEntityType);
		if (innerMap == null) {
			return Collections.emptyList();
		}
		final List<ExpressionIndexTrigger> triggers = innerMap.get(dependencyType);
		return triggers != null ? triggers : Collections.emptyList();
	}

	@Nonnull
	@Override
	public List<ExpressionIndexTrigger> getTriggersForAttribute(
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

	@Nonnull
	@Override
	public CatalogExpressionTriggerRegistry rebuildForEntityType(
		@Nonnull String entityType,
		@Nonnull List<ExpressionIndexTrigger> newTriggers
	) {
		// deep-copy the current index into a mutable structure
		final Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> mutableIndex =
			deepCopyIndex(this.triggerIndex);

		// remove all triggers owned by the specified entity type from ALL keys
		removeTriggersOwnedBy(mutableIndex, entityType);

		// insert new triggers under their respective (mutatedEntityType, dependencyType) keys
		for (final ExpressionIndexTrigger trigger : newTriggers) {
			insertTrigger(mutableIndex, trigger);
		}

		if (mutableIndex.isEmpty()) {
			return CatalogExpressionTriggerRegistry.EMPTY;
		}
		return new CatalogExpressionTriggerRegistryImpl(mutableIndex);
	}

	/**
	 * Creates a deep mutable copy of the trigger index. Each inner map and list is independently mutable.
	 *
	 * @param source the immutable source index
	 * @return a fully mutable deep copy
	 */
	@Nonnull
	private static Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> deepCopyIndex(
		@Nonnull Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> source
	) {
		final Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> copy = createHashMap(source.size());
		for (final Entry<String, Map<DependencyType, List<ExpressionIndexTrigger>>> outerEntry : source.entrySet()) {
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

	/**
	 * Removes all triggers owned by the specified entity type from every key in the mutable index.
	 * Iterates all outer keys because a single owner entity type's references may produce triggers indexed under
	 * different mutated entity types.
	 *
	 * @param mutableIndex the mutable index to modify in-place
	 * @param ownerEntityType the owner entity type whose triggers should be removed
	 */
	private static void removeTriggersOwnedBy(
		@Nonnull Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> mutableIndex,
		@Nonnull String ownerEntityType
	) {
		final Iterator<Entry<String, Map<DependencyType, List<ExpressionIndexTrigger>>>> outerIt =
			mutableIndex.entrySet().iterator();
		while (outerIt.hasNext()) {
			final Entry<String, Map<DependencyType, List<ExpressionIndexTrigger>>> outerEntry = outerIt.next();
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
	 * Inserts a single trigger into the mutable index under its `(mutatedEntityType, dependencyType)` key.
	 * The mutated entity type and dependency type are derived from the trigger itself.
	 *
	 * If both `mutatedEntityType` and `dependencyType` are null, the trigger is local-only and is silently
	 * skipped. If exactly one is null, an {@link IllegalStateException} is thrown because this indicates
	 * an inconsistent trigger construction.
	 *
	 * @param mutableIndex the mutable index to insert into
	 * @param trigger      the trigger to insert
	 * @throws IllegalStateException if exactly one of mutatedEntityType/dependencyType is null
	 */
	private static void insertTrigger(
		@Nonnull Map<String, Map<DependencyType, List<ExpressionIndexTrigger>>> mutableIndex,
		@Nonnull ExpressionIndexTrigger trigger
	) {
		final String mutatedEntityType = trigger.getMutatedEntityType();
		final DependencyType dependencyType = trigger.getDependencyType();
		if (mutatedEntityType == null && dependencyType == null) {
			// local-only triggers are not registered in the cross-entity registry
			return;
		}
		if (mutatedEntityType == null || dependencyType == null) {
			// exactly one is null — inconsistent trigger construction
			throw new IllegalStateException(
				"ExpressionIndexTrigger for reference `" + trigger.getReferenceName() +
					"` on entity `" + trigger.getOwnerEntityType() +
					"` has inconsistent null state: mutatedEntityType=" + mutatedEntityType +
					", dependencyType=" + dependencyType +
					". Both must be null (local-only) or both non-null (cross-entity)."
			);
		}
		mutableIndex
			.computeIfAbsent(mutatedEntityType, k -> new EnumMap<>(DependencyType.class))
			.computeIfAbsent(dependencyType, k -> new ArrayList<>(4))
			.add(trigger);
	}

}
