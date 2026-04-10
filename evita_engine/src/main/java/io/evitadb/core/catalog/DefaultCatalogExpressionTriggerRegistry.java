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
import io.evitadb.core.expression.trigger.HistogramExpressionTriggerFactory;
import io.evitadb.dataType.Scope;
import io.evitadb.core.expression.trigger.DependencyType;
import io.evitadb.core.expression.trigger.ExpressionIndexTrigger;
import io.evitadb.core.expression.trigger.FacetExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramExpressionTrigger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, thread-safe implementation of {@link CatalogExpressionTriggerRegistry}. Delegates to
 * {@link CrossEntityTriggerIndex} for cross-entity trigger lookup and {@link LocalTriggerIndex} for
 * local (inline evaluation) trigger lookup.
 *
 * The registry follows a copy-on-write pattern: {@link #rebuildForEntityType} produces a new instance
 * with rebuilt indexes, leaving the original untouched for concurrent readers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see CatalogExpressionTriggerRegistry
 * @see CrossEntityTriggerIndex
 * @see LocalTriggerIndex
 */
@ThreadSafe
class DefaultCatalogExpressionTriggerRegistry implements CatalogExpressionTriggerRegistry {

	/**
	 * Immutable cross-entity trigger index: mutated entity type + dependency type -> trigger list.
	 */
	@Nonnull
	private final CrossEntityTriggerIndex crossEntityIndex;

	/**
	 * Immutable local trigger index: owner entity type + reference name + scope -> facet/histogram triggers.
	 */
	@Nonnull
	private final LocalTriggerIndex localIndex;

	/**
	 * Creates a new registry from pre-built indexes.
	 *
	 * @param crossEntityIndex the cross-entity trigger index
	 * @param localIndex       the local trigger index
	 */
	DefaultCatalogExpressionTriggerRegistry(
		@Nonnull CrossEntityTriggerIndex crossEntityIndex,
		@Nonnull LocalTriggerIndex localIndex
	) {
		this.crossEntityIndex = crossEntityIndex;
		this.localIndex = localIndex;
	}

	/**
	 * Builds a fully populated registry by scanning all entity schemas and their reference schemas for
	 * conditional expressions. Used during cold start / catalog initialization.
	 *
	 * @param entitySchemaIndex all loaded entity schemas keyed by entity type name
	 * @return a fully populated registry, or {@link CatalogExpressionTriggerRegistry#EMPTY} if no schemas
	 *         carry conditional expressions
	 */
	@Nonnull
	static CatalogExpressionTriggerRegistry buildFromSchemas(
		@Nonnull Map<String, EntitySchemaContract> entitySchemaIndex
	) {
		final CrossEntityTriggerIndex.Builder crossBuilder =
			CrossEntityTriggerIndex.newBuilder(entitySchemaIndex.size());
		final LocalTriggerIndex.Builder localBuilder =
			LocalTriggerIndex.newBuilder(entitySchemaIndex.size());

		for (final EntitySchemaContract entitySchema : entitySchemaIndex.values()) {
			final String ownerEntityType = entitySchema.getName();
			for (final ReferenceSchemaContract referenceSchema : entitySchema.getReferences().values()) {
				final List<FacetExpressionTrigger> facetTriggers =
					FacetExpressionTriggerFactory.buildTriggersForReference(
						ownerEntityType, referenceSchema
					);
				for (final FacetExpressionTrigger trigger : facetTriggers) {
					crossBuilder.insert(trigger);
					localBuilder.insertFacetTrigger(trigger);
				}
				final List<HistogramExpressionTrigger> histTriggers =
					HistogramExpressionTriggerFactory.buildTriggersForReference(
						ownerEntityType, referenceSchema, entitySchemaIndex::get
					);
				for (final HistogramExpressionTrigger trigger : histTriggers) {
					crossBuilder.insert(trigger);
					localBuilder.insertHistogramTrigger(trigger);
				}
			}
		}

		final CrossEntityTriggerIndex cross = crossBuilder.build();
		final LocalTriggerIndex local = localBuilder.build();
		if (cross.isEmpty() && local.isEmpty()) {
			return CatalogExpressionTriggerRegistry.EMPTY;
		}
		return new DefaultCatalogExpressionTriggerRegistry(cross, local);
	}

	@Nonnull
	@Override
	public List<ExpressionIndexTrigger> getTriggersFor(
		@Nonnull String mutatedEntityType,
		@Nonnull DependencyType dependencyType
	) {
		return this.crossEntityIndex.getTriggersFor(mutatedEntityType, dependencyType);
	}

	@Nonnull
	@Override
	public List<ExpressionIndexTrigger> getTriggersForAttribute(
		@Nonnull String mutatedEntityType,
		@Nonnull DependencyType dependencyType,
		@Nonnull String attributeName
	) {
		return this.crossEntityIndex.getTriggersForAttribute(
			mutatedEntityType, dependencyType, attributeName
		);
	}

	@Override
	public boolean hasEntityAttributeTrigger(
		@Nonnull String mutatedEntityType,
		@Nonnull String attributeName
	) {
		return this.crossEntityIndex.hasEntityAttributeTrigger(mutatedEntityType, attributeName);
	}

	@Override
	public boolean hasAnyEntityAttributeTriggers(@Nonnull String mutatedEntityType) {
		return this.crossEntityIndex.hasAnyEntityAttributeTriggers(mutatedEntityType);
	}

	@Nonnull
	@Override
	public Set<String> getEntityAttributeNames(@Nonnull String mutatedEntityType) {
		return this.crossEntityIndex.getEntityAttributeNames(mutatedEntityType);
	}

	@Nullable
	@Override
	public FacetExpressionTrigger getLocalTrigger(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		return this.localIndex.getFacetTrigger(ownerEntityType, referenceName, scope);
	}

	@Nonnull
	@Override
	public Collection<HistogramExpressionTrigger> getLocalHistogramTriggers(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		return this.localIndex.getHistogramTriggers(ownerEntityType, referenceName, scope);
	}

	/**
	 * Returns the total number of cross-entity triggers. Used for cold start logging.
	 *
	 * @return the total trigger count
	 */
	int getTriggerCount() {
		return this.crossEntityIndex.triggerCount();
	}

	@Nonnull
	@Override
	public CatalogExpressionTriggerRegistry rebuildForEntityType(
		@Nonnull String entityType,
		@Nonnull List<ExpressionIndexTrigger> newTriggers
	) {
		// create mutable builders from the current state
		final CrossEntityTriggerIndex.Builder crossBuilder = this.crossEntityIndex.toBuilder();
		final LocalTriggerIndex.Builder localBuilder = this.localIndex.toBuilder();

		// remove all triggers owned by the specified entity type
		crossBuilder.removeTriggersOwnedBy(entityType);
		localBuilder.removeByOwner(entityType);

		// insert new triggers
		for (final ExpressionIndexTrigger trigger : newTriggers) {
			crossBuilder.insert(trigger);
			if (trigger instanceof FacetExpressionTrigger facetTrigger) {
				localBuilder.insertFacetTrigger(facetTrigger);
			}
			if (trigger instanceof HistogramExpressionTrigger histTrigger) {
				localBuilder.insertHistogramTrigger(histTrigger);
			}
		}

		final CrossEntityTriggerIndex cross = crossBuilder.build();
		final LocalTriggerIndex local = localBuilder.build();
		if (cross.isEmpty() && local.isEmpty()) {
			return CatalogExpressionTriggerRegistry.EMPTY;
		}
		return new DefaultCatalogExpressionTriggerRegistry(cross, local);
	}

}
