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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer;

import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.FetchRequirementCollector;
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.function.IntObjBiFunction;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.hierarchy.HierarchyVisitor;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * {@link HierarchyStatisticsProducer} creates {@link Hierarchy} DTO instance and does the heavy lifting to
 * compute all information necessary. The producer aggregates {@link AbstractHierarchyStatisticsComputer} for each
 * {@link HierarchyOfSelf} requirement and combines them into the single result.
 *
 * Producer uses {@link HierarchyIndex} of the targeted entity to {@link HierarchyIndex#traverseHierarchy(HierarchyVisitor, HierarchyFilteringPredicate)}
 * finding all entities linked to the queried entity type. It respects {@link EntityLocaleEquals} and {@link HierarchyWithin}
 * filtering constraints when filtering the entity tree. For each such hierarchical entity node it finds all entity
 * primary keys of the queried entity type connected to it, combines them with entity id array that is produced by
 * the query (only matching ids will remain) and uses this information to fill the {@link LevelInfo#queriedEntityCount()}
 * and {@link LevelInfo#childrenCount()} information. {@link LevelInfo} with zero cardinality are filtered out from
 * the result.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class HierarchyStatisticsProducer implements ExtraResultProducer {
	/**
	 * Contains language specified in {@link io.evitadb.api.requestResponse.EvitaRequest}. Language is valid for entire query.
	 */
	@Nullable private final Locale language;
	/**
	 * Contains set of producer instances, that should build collections of {@link LevelInfo} for each requested
	 * references. Each producer contains all information necessary.
	 */
	@Nonnull private final Map<String, HierarchySet> hierarchyRequests = new HashMap<>(16);
	/**
	 * The reference contains the information captured at the moment when translator visitor encounters constraints
	 * {@link HierarchyOfSelf} or {@link HierarchyOfReference} and contains shared context for all internal
	 * sub-translators.
	 */
	private final AtomicReference<HierarchyProducerContext> context = new AtomicReference<>();
	/**
	 * Contains producer instance of the computer that should build collections of {@link LevelInfo} for each queried
	 * hierarchical entity. Each producer contains all information necessary.
	 */
	@Nullable private HierarchySet selfHierarchyRequest;

	@Nullable
	@Override
	public <T extends Serializable> EvitaResponseExtraResult fabricate(@Nonnull QueryExecutionContext context) {
		return new Hierarchy(
			ofNullable(this.selfHierarchyRequest)
				.map(it -> it.createStatistics(context, this.language))
				.orElse(null),
			this.hierarchyRequests
				.entrySet()
				.stream()
				.collect(
					Collectors.toMap(
						Entry::getKey,
						it -> it.getValue().createStatistics(context, this.language)
					)
				)
		);
	}

	@Nonnull
	@Override
	public String getDescription() {
		if (this.selfHierarchyRequest == null && this.hierarchyRequests.isEmpty()) {
			return "empty hierarchy";
		} else if (this.selfHierarchyRequest != null && this.hierarchyRequests.isEmpty()) {
			return "self hierarchy";
		} else if (this.selfHierarchyRequest == null) {
			return "referenced entity " + this.hierarchyRequests.keySet().stream().map(it -> '`' + it + '`').collect(Collectors.joining(" ,")) + " hierarchies";
		} else {
			return "referenced entity " + this.hierarchyRequests.keySet().stream().map(it -> '`' + it + '`').collect(Collectors.joining(" ,")) + " hierarchies and self";
		}
	}

	/**
	 * Registers new {@link LevelInfo} collection computation request for passed `referenceSchema`.
	 *
	 * @param entitySchema                           target hierarchy entity {@link EntitySchema}
	 * @param referenceSchema                        relates to reference schema {@link ReferenceSchema} that target the hierarchy entity
	 * @param attributeSchemaAccessor                object that provides access to the attribute schemas from the entity schema
	 * @param hierarchyWithin                        limits the statistics to certain subtree of the hierarchy
	 * @param targetIndex                            owner entityIndex for hierarchy index
	 * @param directlyQueriedEntitiesFormulaProducer represents function that produces bitmap of queried entity ids connected
	 *                                               with particular hierarchical entity
	 * @param behaviour                              controls whether items with {@link LevelInfo#queriedEntityCount()} equal to zero should be excluded
	 * @param hierarchyFilterPredicateProducer       lambda that creates a {@link HierarchyFilteringPredicate} based respecting the statistics base
	 * @param orderBy                                the `orderBy` constraint the `sorter` was built from, `null`
	 *                                               when the constraint declares none
	 * @param sorter                                 sorter for sorting {@link LevelInfo}, `null` exactly when
	 *                                               `orderBy` is `null`
	 * @param interpretationLambda                   lambda that allows additional configuration of the {@link AbstractHierarchyStatisticsComputer}
	 */
	public void interpret(
		@Nonnull Supplier<Bitmap> rootHierarchyNodesSupplier,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
		@Nullable HierarchyFilterConstraint hierarchyWithin,
		@Nonnull GlobalEntityIndex targetIndex,
		@Nullable FetchRequirementCollector fetchRequirementCollector,
		@Nonnull IntObjBiFunction<StatisticsBase, Formula> directlyQueriedEntitiesFormulaProducer,
		@Nullable Function<StatisticsBase, HierarchyFilteringPredicate> hierarchyFilterPredicateProducer,
		@Nonnull EmptyHierarchicalEntityBehaviour behaviour,
		@Nullable OrderBy orderBy,
		@Nullable NestedContextSorter sorter,
		@Nonnull Runnable interpretationLambda
	) {
		Assert.isTrue(this.context.get() == null, "HierarchyOfSelf / HierarchyOfReference cannot be nested inside each other!");
		try {
			this.context.set(
				new HierarchyProducerContext(
					rootHierarchyNodesSupplier,
					entitySchema,
					referenceSchema,
					attributeSchemaAccessor,
					hierarchyWithin,
					targetIndex,
					fetchRequirementCollector,
					directlyQueriedEntitiesFormulaProducer,
					hierarchyFilterPredicateProducer,
					behaviour == EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY
				)
			);
			interpretationLambda.run();
			// a constraint that declares no order of its own leaves the order declared by a sibling constraint in
			// effect - the siblings feed a single `HierarchySet` and overwriting it with nothing would retroactively
			// re-sort the output names the sibling registered
			if (orderBy != null) {
				final HierarchySet hierarchySet = referenceSchema == null ?
					this.selfHierarchyRequest : this.hierarchyRequests.get(referenceSchema.getName());
				if (hierarchySet != null) {
					assertOrderNotContradicted(hierarchySet, referenceSchema, orderBy);
					// safe: `createSorter` never returns null, so a declared order always carries its sorter
					hierarchySet.setSorter(orderBy, Objects.requireNonNull(sorter));
				}
			}
		} finally {
			this.context.set(null);
		}
	}

	/**
	 * Verifies that the hierarchy of a single target is not ordered by two contradicting `orderBy` constraints.
	 * Sibling {@link HierarchyOfSelf} / {@link HierarchyOfReference} constraints aimed at the same target contribute
	 * their output names to one shared {@link HierarchySet}, and that set carries exactly one order - so a second,
	 * different order would silently re-sort the results of the first constraint as well.
	 *
	 * @param hierarchySet    the shared container the sibling constraints contribute to
	 * @param referenceSchema the reference the hierarchy is computed for, `null` for the queried entity itself
	 * @param orderBy         the order declared by the constraint being interpreted
	 * @throws EvitaInvalidUsageException when a different order has already been declared for the same target
	 */
	private static void assertOrderNotContradicted(
		@Nonnull HierarchySet hierarchySet,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull OrderBy orderBy
	) {
		final OrderBy alreadyDeclared = hierarchySet.getOrderConstraint();
		if (alreadyDeclared != null && !alreadyDeclared.equals(orderBy)) {
			final String reason = "Hierarchy statistics of " +
				(referenceSchema == null ?
					"the queried entity itself" : "reference `" + referenceSchema.getName() + "`") +
				" are ordered by two different `orderBy` constraints - all `hierarchyOf" +
				(referenceSchema == null ? "Self" : "Reference") + "` constraints aimed at a single target share " +
				"a single order, so state the same one on each of them";
			throw new EvitaInvalidUsageException(
				reason + ": " + alreadyDeclared + " and " + orderBy + ".", reason + "."
			);
		}
	}

	/**
	 * Methods registers a specific implementation {@link AbstractHierarchyStatisticsComputer} instance for computing
	 * a result labeled as `outputName`.
	 */
	public void addComputer(
		@Nonnull String constraintName,
		@Nonnull String outputName,
		@Nonnull AbstractHierarchyStatisticsComputer computer
	) {
		final HierarchyProducerContext ctx = getContext(constraintName);
		final HierarchySet hierarchySet;
		if (ctx.referenceSchema() == null) {
			if (this.selfHierarchyRequest == null) {
				this.selfHierarchyRequest = new HierarchySet();
			}
			hierarchySet = this.selfHierarchyRequest;
		} else {
			hierarchySet = this.hierarchyRequests.computeIfAbsent(
				ctx.referenceSchema().getName(),
				s -> new HierarchySet()
			);
		}
		assertOutputNameFree(hierarchySet, ctx.referenceSchema(), outputName);
		hierarchySet.addComputer(outputName, computer);
	}

	/**
	 * Verifies that `outputName` is not already claimed within the hierarchy of a single target. Every
	 * `hierarchyOf...` constraint aimed at one target contributes to a single result map indexed by output name, so
	 * a repeated name has no result of its own to point at.
	 *
	 * Without this check the collision surfaces only once both hierarchies have been fully computed, as an internal
	 * `IllegalStateException` raised by the `Collectors.toMap` in {@link HierarchySet#createStatistics} - whose
	 * message interpolates both computed trees.
	 *
	 * @param hierarchySet    the container the output names of a single target are registered in
	 * @param referenceSchema the reference the hierarchy is computed for, `null` for the queried entity itself
	 * @param outputName      the label the result of the computer being registered would be indexed by
	 * @throws EvitaInvalidUsageException when the name is already claimed within the same target
	 */
	private static void assertOutputNameFree(
		@Nonnull HierarchySet hierarchySet,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull String outputName
	) {
		if (hierarchySet.containsOutputName(outputName)) {
			final String reason = "Hierarchy output name `" + outputName + "` is requested twice for " +
				(referenceSchema == null ?
					"the queried entity's own hierarchy" :
					"the hierarchy of reference `" + referenceSchema.getName() + "`") +
				" - each hierarchy result is indexed by its output name, so the names have to differ";
			throw new EvitaInvalidUsageException(reason + ".");
		}
	}

	/**
	 * Method returns a {@link HierarchyProducerContext} reference that could be accessed in translators that are placed
	 * within a scope of {@link HierarchyOfSelf} or {@link HierarchyOfReference}.
	 */
	@Nonnull
	public HierarchyProducerContext getContext(@Nonnull String constraintName) {
		return ofNullable(this.context.get())
			.orElseThrow(
				() -> new EvitaInvalidUsageException(
					constraintName + " constraint must be used inside HierarchyOfSelf or HierarchyOfReference " +
						"constraint container!"
				)
			);
	}

}
