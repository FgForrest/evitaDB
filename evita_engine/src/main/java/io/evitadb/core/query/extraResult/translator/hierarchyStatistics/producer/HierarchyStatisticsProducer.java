/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.hierarchy.HierarchyVisitor;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * {@link HierarchyStatisticsProducer} creates {@link HierarchyStatistics} DTO instance and does the heavy lifting to
 * compute all information necessary. The producer aggregates {@link AbstractHierarchyStatisticsComputer} for each
 * {@link HierarchyOfSelf} requirement and combines them into the single result.
 *
 * Producer uses {@link HierarchyIndex} of the targeted entity to {@link HierarchyIndex#traverseHierarchy(HierarchyVisitor, int...)}
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
	 * Reference to the query context that allows to access entity bodies.
	 */
	@Nonnull private final QueryContext queryContext;
	/**
	 * Contains language specified in {@link io.evitadb.api.requestResponse.EvitaRequest}. Language is valid for entire query.
	 */
	@Nullable private final Locale language;
	/**
	 * Contains filtering formula tree that was used to produce results so that computed {@link LevelInfo#queriedEntityCount()}
	 * is reduced according to the input filter.
	 */
	@Nonnull private final Formula filteringFormula;
	/**
	 * Contains set of producer instances, that should build collections of {@link LevelInfo} for each requested
	 * references. Each producer contains all information necessary.
	 */
	@Nonnull private final Map<String, Map<String, AbstractHierarchyStatisticsComputer>> hierarchyRequests = new HashMap<>(16);
	/**
	 * Contains producer instance of the computer that should build collections of {@link LevelInfo} for each queried
	 * hierarchical entity. Each producer contains all information necessary.
	 */
	@Nullable private final Map<String, AbstractHierarchyStatisticsComputer> selfHierarchyRequest = new HashMap<>(16);
	/**
	 * The reference contains the information captured at the moment when translator visitor encounters constraints
	 * {@link HierarchyOfSelf} or {@link HierarchyOfReference} and contains shared context for all internal
	 * sub-translators.
	 */
	private final AtomicReference<HierarchyProducerContext> context = new AtomicReference<>();

	@Nullable
	@Override

	public <T extends Serializable> EvitaResponseExtraResult fabricate(@Nonnull List<T> entities) {
		return new HierarchyStatistics(
			ofNullable(selfHierarchyRequest)
				.map(it -> it.entrySet()
					.stream()
					.collect(
						Collectors.toMap(
							Entry::getKey,
							entry -> entry.getValue().createStatistics(filteringFormula, language)
						)
					)
				)
				.orElse(null),
			hierarchyRequests
				.entrySet()
				.stream()
				.collect(
					Collectors.toMap(
						Entry::getKey,
						it -> it.getValue()
							.entrySet()
							.stream()
							.collect(
								Collectors.toMap(
									Entry::getKey,
									entry -> entry.getValue().createStatistics(filteringFormula, language)
								)
							)
					)
				)
		);
	}

	/**
	 * Registers new {@link LevelInfo} collection computation request for passed `referenceSchema`.
	 *
	 * @param entitySchema                  target hierarchy entity {@link EntitySchema}
	 * @param referenceSchema               relates to reference schema {@link ReferenceSchema} that target the hierarchy entity
	 * @param hierarchyWithin               limits the statistics to certain subtree of the hierarchy
	 * @param targetIndex                   owner entityIndex for hierarchy index
	 * @param hierarchyReferencingEntityPks represents function that produces bitmap of queried entity ids connected
	 *                                      with particular hierarchical entity
	 * @param behaviour                     controls whether items with {@link LevelInfo#queriedEntityCount()} equal to zero should be excluded
	 * @param interpretationLambda          lambda that allows additional configuration of the {@link AbstractHierarchyStatisticsComputer}
	 */
	public void interpret(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable HierarchyFilterConstraint hierarchyWithin,
		@Nonnull EntityIndex targetIndex,
		@Nonnull IntFunction<Bitmap> hierarchyReferencingEntityPks,
		@Nonnull EmptyHierarchicalEntityBehaviour behaviour,
		@Nonnull Runnable interpretationLambda
	) {
		Assert.isTrue(context.get() == null, "HierarchyOfSelf / HierarchyOfReference cannot be nested inside each other!");
		try {
			context.set(
				new HierarchyProducerContext(
					queryContext,
					entitySchema, referenceSchema,
					hierarchyWithin,
					targetIndex,
					hierarchyReferencingEntityPks,
					behaviour == EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY
				)
			);
			interpretationLambda.run();
		} finally {
			context.set(null);
		}
	}

	/**
	 * TODO JNO - document me
	 */
	@Nonnull
	public ExtraResultProducer computeChildren(
		@Nonnull String outputName,
		@Nullable HierarchyEntityPredicate predicate,
		@Nullable HierarchyEntityFetcher entityFetcher
	) {
		addComputer(
			"HierarchyChildren",
			outputName,
			ctx -> new ChildrenStatisticsComputer(
				ctx, predicate, entityFetcher
			)
		);
		return this;
	}

	/**
	 * TODO JNO - document me
	 */
	@Nonnull
	public HierarchyProducerContext getContext(@Nonnull String constraintName) {
		return ofNullable(context.get())
			.orElseThrow(
				() -> new EvitaInvalidUsageException(
					constraintName + " constraint must be used inside HierarchyOfSelf or HierarchyOfReference " +
						"constraint container!"
				)
			);
	}

	private void addComputer(
		@Nonnull String constraintName,
		@Nonnull String outputName,
		@Nonnull Function<HierarchyProducerContext, ChildrenStatisticsComputer> computer
	) {
		final HierarchyProducerContext ctx = getContext(constraintName);
		if (ctx.referenceSchema() == null) {
			this.selfHierarchyRequest.put(outputName, computer.apply(ctx));
		} else {
			this.hierarchyRequests.computeIfAbsent(
					ctx.referenceSchema().getName(),
					s -> new HashMap<>(16)
				)
				.put(outputName, computer.apply(ctx));
		}
	}

}
