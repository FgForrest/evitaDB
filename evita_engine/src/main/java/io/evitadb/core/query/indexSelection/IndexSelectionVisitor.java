/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.query.indexSelection;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.HierarchyWithinRoot;
import io.evitadb.api.query.filter.IndexUsingConstraint;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.hierarchy.HierarchyWithinRootTranslator;
import io.evitadb.core.query.filter.translator.hierarchy.HierarchyWithinTranslator;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.Index;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * This visitor examines {@link Query#getFilterBy()} query and tries to construct multiple {@link TargetIndexes}
 * alternative that can be used to fully interpret the filtering query. These alternative index sets will compete
 * one with another to produce filtering query with minimal execution costs.
 *
 * Currently, the logic is quite stupid - it searches the filter for all constraints within AND relation and when
 * relation or hierarchy query is encountered, it adds specific {@link EntityIndexType#REFERENCED_ENTITY} or
 * {@link EntityIndexType#REFERENCED_HIERARCHY_NODE} that contains limited subset of the entities related to that
 * placement/relation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class IndexSelectionVisitor implements ConstraintVisitor {
	private final QueryContext queryContext;
	@Getter private final List<TargetIndexes<? extends Index<?>>> targetIndexes = new LinkedList<>();
	@Getter private boolean targetIndexQueriedByOtherConstraints;
	private FilterByVisitor filterByVisitor;

	public IndexSelectionVisitor(@Nonnull QueryContext queryContext) {
		this.queryContext = queryContext;
		final Optional<EntityIndex> entityIndex = queryContext.getIndex(new EntityIndexKey(EntityIndexType.GLOBAL));
		if (entityIndex.isPresent()) {
			final EntityIndex eix = entityIndex.get();
			this.targetIndexes.add(
				new TargetIndexes<>(
					eix.getIndexKey().getType().name(),
					EntityIndex.class,
					Collections.singletonList(eix)
				)
			);
		} else {
			queryContext.getIndex(CatalogIndexKey.INSTANCE)
				.ifPresent(it -> this.targetIndexes.add(
						new TargetIndexes<>(
							it.getIndexKey().toString(),
							CatalogIndex.class,
							Collections.singletonList((CatalogIndex) it)
						)
					)
				);
		}
	}

	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		final FilterConstraint filterConstraint = (FilterConstraint) constraint;

		if (filterConstraint instanceof And || filterConstraint instanceof FilterBy) {
			// if query is AND query we may traverse further
			@SuppressWarnings("unchecked") final FilterConstraint[] subConstraints = ((ConstraintContainer<FilterConstraint>) filterConstraint).getChildren();
			for (FilterConstraint subConstraint : subConstraints) {
				subConstraint.accept(this);
			}
		} else if (filterConstraint instanceof HierarchyFilterConstraint hierarchyFilterConstraint) {
			// if query is hierarchy filtering query targeting different entity
			addHierarchyIndexOption(hierarchyFilterConstraint);
		} else if (filterConstraint instanceof final ReferenceHaving referenceHaving) {
			// if query is hierarchy filtering query targeting different entity
			addReferenceIndexOption(referenceHaving);
			for (FilterConstraint subConstraint : referenceHaving.getChildren()) {
				if (!(subConstraint instanceof EntityHaving)) {
					subConstraint.accept(this);
				}
			}
		} else if (filterConstraint instanceof IndexUsingConstraint) {
			this.targetIndexQueriedByOtherConstraints = true;
		}
	}

	/**
	 * Registers {@link TargetIndexes} that represents hierarchy placement. It finds collection of
	 * {@link EntityIndexType#REFERENCED_HIERARCHY_NODE} indexes that contains all relevant data for entities that
	 * are part of the requested tree. This significantly limits the scope that needs to be examined.
	 */
	private void addHierarchyIndexOption(@Nonnull HierarchyFilterConstraint constraint) {
		constraint.getReferenceName().ifPresent(
			filteredHierarchyReferenceName -> {
				final ReferenceSchemaContract referencedSchema = queryContext.getSchema().getReferenceOrThrowException(filteredHierarchyReferenceName);
				if (referencedSchema.isReferencedEntityTypeManaged()) {
					final Formula requestedHierarchyNodesFormula;
					if (constraint instanceof final HierarchyWithinRoot hierarchyWithinRoot) {
						requestedHierarchyNodesFormula = HierarchyWithinRootTranslator.createFormulaFromHierarchyIndex(
							hierarchyWithinRoot, getFilterByVisitor()
						);
					} else if (constraint instanceof final HierarchyWithin hierarchyWithin) {
						requestedHierarchyNodesFormula = HierarchyWithinTranslator.createFormulaFromHierarchyIndex(
							hierarchyWithin, getFilterByVisitor()
						);
					} else {
						//sanity check only
						throw new GenericEvitaInternalError("Should never happen");
					}
					if (requestedHierarchyNodesFormula instanceof EmptyFormula) {
						// if target entity has no global index present, it means that the query cannot be fulfilled
						// we may quickly return empty result
						targetIndexes.add(TargetIndexes.EMPTY);
					}
					// locate all hierarchy indexes
					final Bitmap requestedHierarchyNodes = requestedHierarchyNodesFormula.compute();
					final List<EntityIndex> theTargetIndexes = new ArrayList<>(requestedHierarchyNodes.size());
					for (Integer hierarchyEntityId : requestedHierarchyNodes) {
						queryContext.getIndex(
							new EntityIndexKey(
								EntityIndexType.REFERENCED_HIERARCHY_NODE,
								new ReferenceKey(filteredHierarchyReferenceName, hierarchyEntityId)
							)
						)
							.map(EntityIndex.class::cast)
							.ifPresent(theTargetIndexes::add);
					}
					// add indexes as potential target indexes
					this.targetIndexes.add(
						new TargetIndexes<>(
							EntityIndexType.REFERENCED_HIERARCHY_NODE.name() +
								" composed of " + requestedHierarchyNodes.size() + " indexes",
							constraint,
							EntityIndex.class,
							theTargetIndexes
						)
					);
				}
			}
		);
	}

	/**
	 * Registers {@link TargetIndexes} that represents hierarchy placement. It finds collection of
	 * {@link EntityIndexType#REFERENCED_ENTITY} indexes that contains all relevant data for entities that
	 * are related to respective entity type and id. This may significantly limit the scope that needs to be examined.
	 */
	private void addReferenceIndexOption(@Nonnull ReferenceHaving constraint) {
		final List<ReducedEntityIndex> theTargetIndexes = getFilterByVisitor().getReferencedRecordEntityIndexes(constraint);

		// add indexes as potential target indexes
		this.targetIndexes.add(
			new TargetIndexes<>(
				EntityIndexType.REFERENCED_ENTITY.name() +
					" composed of " + theTargetIndexes.size() + " indexes",
				constraint,
				ReducedEntityIndex.class,
				theTargetIndexes
			)
		);
	}

	private FilterByVisitor getFilterByVisitor() {
		if (filterByVisitor == null) {
			// create lightweight visitor that can evaluate referenced attributes index location only
			filterByVisitor = new FilterByVisitor(
				this.queryContext,
				Collections.emptyList(),
				TargetIndexes.EMPTY,
				false
			);
		}
		return filterByVisitor;
	}

}
