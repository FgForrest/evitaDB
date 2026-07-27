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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor;

import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyEntityFetcher;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Accumulator serves to aggregate information about children before creating immutable statistics result.
 */
public class Accumulator {
	/**
	 * Execution context that is used for initialization of the formulas.
	 */
	private final QueryExecutionContext executionContext;
	/**
	 * Flag signalizing the entity was requested in request by {@link HierarchyWithin}
	 */
	@Getter private final boolean requested;
	/**
	 * Primary key of the hierarchical entity. Always known up-front so child sorting and similar lookups can be
	 * performed without materialising the full {@link EntityClassifier}.
	 *
	 * Sentinel value `-1` is used for the synthetic root accumulator that is not bound to any entity.
	 */
	private final int entityPrimaryKey;
	/**
	 * The hierarchical entity in proper form. May be `null` until {@link #getEntity()} is called for the first time
	 * and {@link #entityFetcher} resolves it lazily; for the synthetic root accumulator the value stays `null`
	 * forever.
	 */
	@Nullable private EntityClassifier entity;
	/**
	 * Optional fetcher used to lazily resolve {@link #entity} from {@link #entityPrimaryKey} when first needed.
	 * Avoids fetching the entity for nodes that get pruned by `removeEmptyResults` and never reach
	 * {@link #toLevelInfo(EnumSet)}.
	 */
	@Nullable private final HierarchyEntityFetcher entityFetcher;
	/**
	 * The formula that produces the bitmap of queried entities directly referencing this hierarchical entity
	 * (respecting current query filter).
	 */
	private final Supplier<Formula> directlyQueriedEntitiesFormulaProducer;
	/**
	 * Mutable container for gradually added children.
	 */
	@Getter private final LinkedList<Accumulator> children = new LinkedList<>();
	/**
	 * Counter for the children that would be returned in case the level predicate didn't stop the traversal.
	 */
	private int omittedChildren;
	/**
	 * List of formula that computes the queried entities that would be returned in case the level predicate didn't stop
	 * the traversal.
	 */
	private List<Formula> omittedQueuedEntities;
	/**
	 * Flag signalizing that the accumulator traverses the omission block.
	 */
	private boolean omissionBlock;
	/**
	 * Cached formula that computes the number of queried entities aggregating the information from:
	 *
	 * - {@link #directlyQueriedEntitiesFormulaProducer}
	 * - {@link #omittedQueuedEntities}
	 */
	private Formula directlyQueriedEntitiesFormula;
	/**
	 * Cached formula that computes the number of queried entities aggregating the information from:
	 *
	 * - {@link #directlyQueriedEntitiesFormulaProducer}
	 * - {@link #omittedQueuedEntities}
	 * - {@link #children}
	 */
	@Nullable private Formula queriedEntitiesFormula;
	/**
	 * Performance optimization variable that speeds up evaluation of {@link #hasQueriedEntity()}.
	 */
	private boolean hasQueriedEntity;

	public Accumulator(
		@Nonnull QueryExecutionContext executionContext,
		boolean requested,
		@Nullable EntityClassifier entity,
		@Nonnull Supplier<Formula> directlyQueriedEntitiesFormulaProducer
	) {
		this.executionContext = executionContext;
		this.requested = requested;
		this.entity = entity;
		this.entityPrimaryKey = entity == null ? -1 : entity.getPrimaryKeyOrThrowException();
		this.entityFetcher = null;
		this.directlyQueriedEntitiesFormulaProducer = directlyQueriedEntitiesFormulaProducer;
	}

	/**
	 * Constructor for the lazy-fetch path: the {@link EntityClassifier} is materialised only when
	 * {@link #getEntity()} is called for the first time. Used by the children/parent visitors so that nodes pruned
	 * by `removeEmptyResults` never trigger an entity fetch.
	 */
	public Accumulator(
		@Nonnull QueryExecutionContext executionContext,
		boolean requested,
		int entityPrimaryKey,
		@Nonnull HierarchyEntityFetcher entityFetcher,
		@Nonnull Supplier<Formula> directlyQueriedEntitiesFormulaProducer
	) {
		this.executionContext = executionContext;
		this.requested = requested;
		this.entity = null;
		this.entityPrimaryKey = entityPrimaryKey;
		this.entityFetcher = entityFetcher;
		this.directlyQueriedEntitiesFormulaProducer = directlyQueriedEntitiesFormulaProducer;
	}

	/**
	 * Alternative constructor that will not initialize entity reference.
	 */
	public Accumulator(
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull Supplier<Formula> directlyQueriedEntitiesFormulaProducer
	) {
		this.executionContext = executionContext;
		this.entity = null;
		this.entityPrimaryKey = -1;
		this.entityFetcher = null;
		this.requested = false;
		this.directlyQueriedEntitiesFormulaProducer = directlyQueriedEntitiesFormulaProducer;
	}

	/**
	 * Returns the primary key of the hierarchical entity this accumulator represents, or `-1` for the synthetic
	 * root accumulator. Available without triggering the lazy entity fetch.
	 */
	public int getEntityPrimaryKey() {
		return this.entityPrimaryKey;
	}

	/**
	 * Returns the materialised {@link EntityClassifier} for this accumulator, fetching it lazily on first call
	 * when the lazy-fetch constructor was used. Returns `null` only for the synthetic root accumulator.
	 */
	@Nullable
	public EntityClassifier getEntity() {
		EntityClassifier resolved = this.entity;
		if (resolved == null && this.entityFetcher != null) {
			resolved = this.entityFetcher.apply(this.executionContext, this.entityPrimaryKey);
			this.entity = resolved;
		}
		return resolved;
	}

	/**
	 * Adds information about this hierarchy node children statistics.
	 */
	public void add(@Nonnull Accumulator childNode) {
		final int childPk = childNode.getEntityPrimaryKey();
		if (!this.children.isEmpty() && this.children.getLast().getEntityPrimaryKey() > childPk) {
			// we need to keep the children sorted by their primary key in ascending order
			int index = Collections.binarySearch(this.children, childNode, Comparator.comparingInt(Accumulator::getEntityPrimaryKey));
			Assert.isPremiseValid(index < 0, "Child node already exists in the accumulator!");
			this.children.add(-index - 1, childNode);
		} else {
			this.children.add(childNode);
		}
		this.queriedEntitiesFormula = null;
		// we can init hasQueriedEntity if registered children have queried entities
		this.hasQueriedEntity = this.hasQueriedEntity || childNode.hasQueriedEntity;
	}

	/**
	 * Returns list of all formulas with omitted entities.
	 */
	@Nonnull
	public List<Formula> getOmittedQueuedEntities() {
		return ofNullable(this.omittedQueuedEntities).orElse(Collections.emptyList());
	}

	/**
	 * Converts accumulator data to immutable {@link LevelInfo} DTO.
	 */
	@Nonnull
	public LevelInfo toLevelInfo(@Nonnull EnumSet<StatisticsType> statisticsTypes) {
		final EntityClassifier resolvedEntity = getEntity();
		Assert.isPremiseValid(resolvedEntity != null, "Entity reference was not initialized for this accumulator!");
		// sort by their order in hierarchy
		return new LevelInfo(
			resolvedEntity,
			this.requested,
			statisticsTypes.contains(StatisticsType.QUERIED_ENTITY_COUNT) ? getQueriedEntitiesFormula().compute().size() : null,
			statisticsTypes.contains(StatisticsType.CHILDREN_COUNT) ? getChildrenCount() : null,
			getChildrenAsLevelInfo(statisticsTypes)
		);
	}

	/**
	 * Converts accumulator data of the immediate children to immutable list of {@link LevelInfo}.
	 */
	@Nonnull
	public List<LevelInfo> getChildrenAsLevelInfo(@Nonnull EnumSet<StatisticsType> statisticsTypes) {
		return this.children.stream()
			.map(it -> it.toLevelInfo(statisticsTypes))
			.toList();
	}

	/**
	 * Method computes the number of queried entities aggregating the information from children and also omitted
	 * entity count (the number of queried entities that belong to nodes that are not part of the requested output).
	 */
	public Formula getQueriedEntitiesFormula() {
		if (this.queriedEntitiesFormula == null) {
			this.queriedEntitiesFormula = FormulaFactory.or(
				Stream.of(
						Stream.of(this.directlyQueriedEntitiesFormulaProducer.get()),
						this.children.stream().map(Accumulator::getQueriedEntitiesFormula),
						ofNullable(this.omittedQueuedEntities).stream().flatMap(Collection::stream)
					)
					.flatMap(Function.identity())
					.toArray(Formula[]::new)
			);
			this.queriedEntitiesFormula.initialize(this.executionContext);
		}
		return this.queriedEntitiesFormula;
	}

	/**
	 * Method computes the number of queried entities aggregating the information from children and also omitted
	 * entity count (the number of queried entities that belong to nodes that are not part of the requested output).
	 */
	public Formula getDirectlyQueriedEntitiesFormula() {
		if (this.directlyQueriedEntitiesFormula == null) {
			this.directlyQueriedEntitiesFormula = FormulaFactory.or(
				Stream.concat(
						Stream.of(this.directlyQueriedEntitiesFormulaProducer.get()),
						ofNullable(this.omittedQueuedEntities).stream().flatMap(Collection::stream)
					)
					.toArray(Formula[]::new)
			);
			this.directlyQueriedEntitiesFormula.initialize(this.executionContext);
		}
		return this.directlyQueriedEntitiesFormula;
	}

	/**
	 * Method computes the number of immediate children nodes of this {@link #entity} combining the size of
	 * the {@link #children} and the count of omitted children that were not requested in the output.
	 */
	public int getChildrenCount() {
		return this.omittedChildren + this.children.size();
	}

	/**
	 * Registers a node that matches the requirement conditions but is not requested in output.
	 */
	public void registerOmittedChild() {
		this.omittedChildren++;
	}

	/**
	 * Registers a count of queried entities that are part of the requested tree that matches the filter but is not
	 * requested in the output.
	 */
	public void registerOmittedCardinality(@Nonnull Formula queriedEntities) {
		if (this.omittedQueuedEntities == null) {
			this.omittedQueuedEntities = new LinkedList<>();
		}
		this.omittedQueuedEntities.add(queriedEntities);
		queriedEntities.initialize(this.executionContext);
		this.queriedEntitiesFormula = null;
	}

	/**
	 * Invokes lambda function in an "omission block". It means that the logic within this block should start registering
	 * "omitted" data instead of regular data in the {@link #children}. This data were not requested in the output, but
	 * they still represent a valida data to be accounted.
	 */
	public void executeOmissionBlock(@Nonnull Runnable runnable) {
		try {
			Assert.isPremiseValid(!this.omissionBlock, "Already in omission block!");
			this.omissionBlock = true;
			runnable.run();
		} finally {
			this.omissionBlock = false;
		}
	}

	/**
	 * Methods return true if it finds at least one queried entity for this accumulator, omitted information or
	 * its children. The method is optimal in the sense that it stops on first occurrence and doesn't require
	 * computing the entire tree.
	 */
	public boolean hasQueriedEntity() {
		if (!this.hasQueriedEntity) {
			if (!getDirectlyQueriedEntitiesFormula().compute().isEmpty()) {
				this.hasQueriedEntity = true;
			}
			if (!this.hasQueriedEntity) {
				for (Accumulator child : this.children) {
					if (child.hasQueriedEntity()) {
						this.hasQueriedEntity = true;
						break;
					}
				}
			}
			if (!this.hasQueriedEntity && this.omittedQueuedEntities != null) {
				for (Formula omittedQueuedEntity : this.omittedQueuedEntities) {
					if (!omittedQueuedEntity.compute().isEmpty()) {
						this.hasQueriedEntity = true;
						break;
					}
				}
			}
		}
		return this.hasQueriedEntity;
	}

	/**
	 * Returns true if there is currently omission block active.
	 */
	public boolean isInOmissionBlock() {
		return this.omissionBlock;
	}
}
