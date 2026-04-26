/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.extraResult.translator.reference.producer;

import com.carrotsearch.hppc.IntHashSet;
import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.ReferenceSummary;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.expression.trigger.HistogramValueDescriptor;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.common.RangeCarrierGroup;
import io.evitadb.core.query.extraResult.translator.common.UserFilterRelaxer;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.function.TriFunction;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.facet.FacetIdIndex;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;
import static java.util.Optional.ofNullable;

/**
 * Performs the heavy computation for the reference-summary (and legacy facet-summary) extra result. Instances are
 * created during the planning phase by {@link io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryTranslator}
 * and {@link io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryOfReferenceTranslator}; the
 * actual work is deferred to {@link #fabricate(io.evitadb.core.query.QueryExecutionContext)}.
 *
 * **Facet statistics pipeline** (first phase of fabrication):
 *
 * - Gathers all {@link FacetReferenceIndex} entries from the query's target indexes.
 * - Merges per-index data via {@link OrFormula} to produce a complete group-facet → entity PK mapping.
 * - Intersects each facet's entity PK set with {@link #filterFormula} to count only entities in the current
 *   result, and with {@link #filterFormulaWithoutUserFilter} to compute impact projections.
 *
 * **Histogram injection** (second phase — only when histogram requests are registered):
 *
 * - After the facet statistics map is assembled, {@link ReferenceHistogramAccumulator#injectHistograms} rebuilds
 *   each reference's group-statistics collection to attach computed histogram DTOs.
 * - For grouped references, one histogram is computed per {@link io.evitadb.index.ReducedGroupEntityIndex};
 *   for non-grouped references, one histogram is computed from the
 *   {@link io.evitadb.index.ReferencedTypeEntityIndex}.
 * - Groups that have histogram data but no facets receive a synthetic {@link io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics}
 *   entry so they appear in the response.
 *
 * **Adapter pattern** — the concrete extra-result DTO ({@link ReferenceSummary} vs. the deprecated
 * {@link io.evitadb.api.requestResponse.extraResult.FacetSummary}) is determined by {@link #resultAdapter},
 * which is injected at construction time by the translator. A mixed request (both deprecated and canonical
 * constraints in the same query) creates two independent producer instances, one per adapter.
 *
 * When requested, {@link RequestImpact} is computed for each facet that is not already requested and estimates
 * the potential change in the returned entity count should that facet be added to the filter.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@SuppressWarnings("deprecation")
public class ReferenceSummaryProducer implements ExtraResultProducer {
	private static final String ERROR_SANITY_CHECK = "Sanity check!";

	/**
	 * Default implementation of the {@link EntityClassifier} fetcher method that simply converts the data into
	 * plain {@link EntityReference} type containing only the data in the input of the function.
	 */
	private static final TriFunction<QueryExecutionContext, String, int[], EntityClassifier[]> ENTITY_REFERENCE_CONVERTER =
		(context, entityType, facetIds) -> {
			final EntityClassifier[] result = new EntityClassifier[facetIds.length];
			for (int i = 0; i < facetIds.length; i++) {
				result[i] = new EntityReference(entityType, facetIds[i]);
			}
			return result;
		};

	/**
	 * Filter formula produces all entity ids that are going to be returned by current query (including user-defined
	 * filter).
	 */
	private final Formula filterFormula;
	/**
	 * Filter formula produces all entity ids that are going to be returned by current query (excluding user-defined
	 * filter).
	 */
	private final Formula filterFormulaWithoutUserFilter;
	/**
	 * Contains references to all {@link FacetIndex#getFacetingEntities()} that were involved in query resolution.
	 */
	private final List<Map<String, FacetReferenceIndex>> facetIndexes;
	/**
	 * Contains index of all requested {@link FacetHaving()} facets in the input query grouped by their
	 * {@link FacetHaving#getReferenceName()}.
	 */
	private final Map<String, Bitmap> requestedFacets;
	/**
	 * Contains the reference summary configuration set specifically for facets of certain reference.
	 * The {@link ReferenceSchema#getName()} is used as a key of this map.
	 */
	@Nonnull
	private final Map<String, ReferenceSummaryRequest> referenceSummaryRequests = createLinkedHashMap(16);
	/**
	 * Per-reference list of histogram requests registered by the {@code histogramStatistics} constraint translator.
	 * The outer key is the reference name; the inner list preserves registration order so multiple requests
	 * for the same reference (distinct histogram names) are evaluated deterministically. Duplicates for the
	 * same histogram name on the same reference are rejected at translator time.
	 */
	@Nonnull
	private final Map<String, List<HistogramRequest>> histogramRequests = createLinkedHashMap(8);
	/**
	 * Contains default settings for reference summary construction and entity fetching.
	 */
	@Nullable
	private DefaultReferenceSummaryRequest defaultRequest;
	/**
	 * Adapter that wraps the intermediate statistics map into the concrete extra-result DTO.
	 * Picked by the translator — {@link FacetSummaryAdapter} for the deprecated
	 * {@link io.evitadb.api.query.require.FacetSummary} /
	 * {@link io.evitadb.api.query.require.FacetSummaryOfReference} constraints, or
	 * {@link ReferenceSummaryAdapter} for the canonical
	 * {@link io.evitadb.api.query.require.ReferenceSummary} /
	 * {@link io.evitadb.api.query.require.ReferenceSummaryOfReference} constraints. When a
	 * request mixes both constraint forms, two {@link ReferenceSummaryProducer} instances
	 * are registered on the planner — one per adapter class — so both DTOs appear in the
	 * response under their own {@code .class} keys.
	 */
	@Nonnull
	private final ReferenceSummaryResultAdapter<? extends ReferenceGroupStatistics> resultAdapter;

	/**
	 * Returns a function that allows to fetch {@link EntityClassifier} for passed `entityType` and multiple `facetIds`
	 * that represents primary keys of the group entity. The form and richness of the returned {@link EntityClassifier}
	 * is controlled by the passed `entityFetch` argument.
	 */
	@Nonnull
	private static <T extends EntityFetchRequire>
		TriFunction<QueryExecutionContext, String, int[], EntityClassifier[]> createFetcherFunction(
		@Nonnull QueryExecutionContext executionContext,
		@Nullable T entityFetch
	) {
		if (entityFetch == null) {
			return ENTITY_REFERENCE_CONVERTER;
		} else {
			final T enrichedEntityFetch = executionContext.enrichEntityFetch(entityFetch);
			return (context, entityType, facetIds) ->
				context.fetchEntities(entityType, facetIds, enrichedEntityFetch).toArray(EntityClassifier[]::new);
		}
	}

	public ReferenceSummaryProducer(
		@Nonnull Formula filterFormula,
		@Nonnull Formula filterFormulaWithoutUserFilter,
		@Nonnull List<Map<String, FacetReferenceIndex>> facetIndexes,
		@Nonnull Map<String, Bitmap> requestedFacets,
		@Nonnull ReferenceSummaryResultAdapter<? extends ReferenceGroupStatistics> resultAdapter
	) {
		this.filterFormula = filterFormula;
		this.filterFormulaWithoutUserFilter = filterFormulaWithoutUserFilter;
		this.facetIndexes = facetIndexes;
		this.requestedFacets = requestedFacets;
		this.resultAdapter = resultAdapter;
	}

	/**
	 * Returns the adapter wired into this producer. Used by translators when looking for an
	 * existing producer to reuse — a deprecated translator only reuses a producer whose
	 * adapter is {@link FacetSummaryAdapter}, and vice versa for the canonical translator.
	 */
	@Nonnull
	public ReferenceSummaryResultAdapter<? extends ReferenceGroupStatistics> getResultAdapter() {
		return this.resultAdapter;
	}

	/**
	 * Registers default settings for facet summary in terms of entity richness (both group and facet) and also
	 * a default type of statistics depth. These settings will be used for all facet references that are not explicitly
	 * configured by {@link #requireReferenceReferenceSummary(ReferenceSchemaContract, FacetStatisticsDepth,
	 * IntPredicate, IntPredicate, NestedContextSorter, NestedContextSorter, EntityFetch, EntityGroupFetch)}.
	 */
	public void requireDefaultReferenceSummary(
		@Nonnull FacetStatisticsDepth facetStatisticsDepth,
		@Nullable Function<ReferenceSchemaContract, IntPredicate> facetPredicate,
		@Nullable Function<ReferenceSchemaContract, IntPredicate> groupPredicate,
		@Nullable Function<ReferenceSchemaContract, NestedContextSorter> facetSorter,
		@Nullable Function<ReferenceSchemaContract, NestedContextSorter> groupSorter,
		@Nullable EntityFetch facetEntityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		this.defaultRequest = new DefaultReferenceSummaryRequest(
			facetPredicate, groupPredicate,
			facetSorter, groupSorter,
			facetEntityRequirement,
			groupEntityRequirement,
			facetStatisticsDepth
		);
	}

	/**
	 * Registers specific settings for facets of certain reference with passed `referenceName` that will
	 * extend / override the default settings set in
	 * {@link #requireDefaultReferenceSummary(FacetStatisticsDepth, Function, Function, Function, Function,
	 * EntityFetch, EntityGroupFetch)}, should there be any.
	 */
	public void requireReferenceReferenceSummary(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull FacetStatisticsDepth facetStatisticsDepth,
		@Nullable IntPredicate facetPredicate,
		@Nullable IntPredicate groupPredicate,
		@Nullable NestedContextSorter facetSorter,
		@Nullable NestedContextSorter groupSorter,
		@Nullable EntityFetch facetEntityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		this.referenceSummaryRequests.put(
			referenceSchema.getName(),
			new ReferenceSummaryRequest(
				this.referenceSummaryRequests.size() + 1,
				referenceSchema,
				facetPredicate, groupPredicate,
				facetSorter, groupSorter,
				facetEntityRequirement,
				groupEntityRequirement,
				facetStatisticsDepth
			)
		);
	}

	/**
	 * Registers a histogram request for the given reference. Multiple requests for the same reference are
	 * allowed (one per histogram index name); a second registration for the *same* `(referenceName, histogramName)`
	 * pair whose configuration diverges from the existing one throws. Triggered by
	 * {@link io.evitadb.core.query.extraResult.translator.reference.ReferenceHistogramStatisticsTranslator}.
	 *
	 * @param request the fully-resolved histogram request to register
	 * @throws EvitaInvalidUsageException when a conflicting registration already exists
	 */
	public void addHistogramRequest(@Nonnull HistogramRequest request) {
		final String referenceName = request.referenceSchema().getName();
		final List<HistogramRequest> existing = this.histogramRequests.computeIfAbsent(
			referenceName, k -> new ArrayList<>(4)
		);
		for (final HistogramRequest already : existing) {
			if (already.histogramName().equals(request.histogramName())) {
				if (already.bucketCount() != request.bucketCount()
					|| already.behavior() != request.behavior()
					|| !Objects.equals(already.entityFetch(), request.entityFetch())) {
					throw new EvitaInvalidUsageException(
						"Histogram `" + request.histogramName() + "` on reference `" + referenceName +
							"` is requested with conflicting parameters (bucket count, behavior or entity fetch)."
					);
				}
				return;
			}
		}
		existing.add(request);
	}

	/**
	 * Entry point for the fabrication phase. Delegates to {@link #doFabricate} which binds the wildcard
	 * in {@link #resultAdapter} to a fresh type variable so the intermediate map stays statically typed.
	 */
	@Nonnull
	@Override
	public EvitaResponseExtraResult fabricate(@Nonnull QueryExecutionContext context) {
		// delegate to a generic helper so the wildcard captured from this.resultAdapter is
		// bound to a fresh type variable T — this way the intermediate
		// Map<String, Collection<T>> lines up with ReferenceSummaryResultAdapter#createResult
		// without any unchecked cast
		return doFabricate(context, this.resultAdapter);
	}

	/**
	 * Type-capture helper for {@link #fabricate}. Executes the two-phase fabrication:
	 *
	 * **Phase 1 — facet statistics**: streams all {@link FacetReferenceIndex} entries through
	 * {@link FacetGroupStatisticsCollector}, which computes per-facet entity counts and impact projections
	 * and assembles the `Map<String, Collection<T>>` keyed by reference name.
	 *
	 * **Phase 2 — histogram injection**: when {@link #histogramRequests} is non-empty, delegates to
	 * {@link ReferenceHistogramAccumulator#injectHistograms} to attach computed {@link io.evitadb.api.requestResponse.extraResult.HistogramContract}
	 * DTOs to each matching group-statistics entry. Groups that have histogram data but no facets receive a
	 * synthetic entry.
	 *
	 * The type variable {@code T} binds the wildcard captured from the {@link #resultAdapter} field so that the
	 * intermediate map and the adapter's {@link ReferenceSummaryResultAdapter#createResult} signature align
	 * without an unchecked cast.
	 */
	@Nonnull
	private <T extends ReferenceGroupStatistics> EvitaResponseExtraResult doFabricate(
		@Nonnull QueryExecutionContext context,
		@Nonnull ReferenceSummaryResultAdapter<T> resultAdapter
	) {
		// create facet calculators - in reaction to the requested depth level
		final MemoizingFacetCalculator universalCalculator = new MemoizingFacetCalculator(
			context, this.filterFormula, this.filterFormulaWithoutUserFilter
		);
		final AtomicInteger counter = new AtomicInteger();
		final FacetGroupStatisticsCollector<T> collector = new FacetGroupStatisticsCollector<>(
			resultAdapter,
			context,
			// translates Facet#type to EntitySchema#reference#groupType
			referenceName -> context.getSchema().getReferenceOrThrowException(referenceName),
			referenceSchema -> resolveReferenceRequest(referenceSchema, counter),
			this.requestedFacets,
			universalCalculator,
			universalCalculator
		);
		// drive the collector imperatively: partition FacetReferenceIndex entries by reference name
		// into per-reference GroupAccumulator maps (matches Collectors.groupingBy semantics — each bucket
		// gets its own fresh supplier state), then run the collector's finisher on each bucket. Avoids
		// the Stream+Collectors.groupingBy+Collectors.mapping pipeline's lambda captures, spliterator
		// state, and collector container allocations on the hot fabrication path.
		final BiConsumer<LinkedHashMap<Integer, GroupAccumulator>, FacetReferenceIndex> accumulator =
			collector.accumulator();
		final Map<String, LinkedHashMap<Integer, GroupAccumulator>> accByReference = createHashMap(
			this.referenceSummaryRequests.isEmpty() ? 8 : this.referenceSummaryRequests.size()
		);
		for (final Map<String, FacetReferenceIndex> facetIndex : this.facetIndexes) {
			for (final FacetReferenceIndex ix : facetIndex.values()) {
				final String referenceName = ix.getReferenceName();
				if (this.defaultRequest == null && !this.referenceSummaryRequests.containsKey(referenceName)) {
					continue;
				}
				accumulator.accept(
					accByReference.computeIfAbsent(referenceName, k -> new LinkedHashMap<>()),
					ix
				);
			}
		}
		Map<String, Collection<T>> statisticsByReferenceName;
		if (accByReference.isEmpty()) {
			statisticsByReferenceName = new HashMap<>();
		} else {
			final Function<LinkedHashMap<Integer, GroupAccumulator>, Collection<T>> finisher = collector.finisher();
			statisticsByReferenceName = createHashMap(accByReference.size());
			for (final Entry<String, LinkedHashMap<Integer, GroupAccumulator>> entry : accByReference.entrySet()) {
				statisticsByReferenceName.put(entry.getKey(), finisher.apply(entry.getValue()));
			}
		}
		if (!this.histogramRequests.isEmpty()) {
			// peel attribute-range carriers so a slider does not contract its own `[min, max]` span;
			// facet and price carriers stay so the histogram still reflects those picks. Relaxer's
			// EmptyFormula sentinel means every carrier was peeled — map to null so the accumulator
			// spans the catalog-wide superset instead of AND-ing against an empty bitmap.
			final Formula relaxedBaseline = UserFilterRelaxer.relax(
				this.filterFormula, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM
			);
			final Formula histogramBaseline = relaxedBaseline == EmptyFormula.INSTANCE
				? null : relaxedBaseline;
			statisticsByReferenceName = ReferenceHistogramAccumulator.injectHistograms(
				statisticsByReferenceName,
				this.histogramRequests,
				histogramBaseline,
				context,
				resultAdapter,
				referenceName -> ofNullable(this.referenceSummaryRequests.get(referenceName))
					.map(ReferenceSummaryRequest::facetSorter)
					.orElse(null),
				referenceName -> resolveGroupEntityFetcher(referenceName, context)
			);
		}
		return resultAdapter.createResult(statisticsByReferenceName);
	}

	/**
	 * Resolves the batched group-entity fetcher for the histogram accumulator. Reuses the
	 * fetcher cached on the {@link ReferenceSummaryRequest} so a histogram-only synthesized
	 * group arrives at the consumer with the same enrichment shape (attributes, references)
	 * as the facet-bearing groups built in phase 1. Returns {@code null} when no specific
	 * request was registered for the reference — the accumulator then falls back to a bare
	 * {@link EntityReference}, which is enough for callers that do not navigate into the
	 * group entity.
	 */
	@Nullable
	private Function<int[], EntityClassifier[]> resolveGroupEntityFetcher(
		@Nonnull String referenceName,
		@Nonnull QueryExecutionContext context
	) {
		final ReferenceSummaryRequest specific = this.referenceSummaryRequests.get(referenceName);
		if (specific == null) {
			return null;
		}
		return specific.getGroupEntityFetcher(context, specific.referenceSchema());
	}

	/**
	 * Resolves the effective {@link ReferenceSummaryRequest} for a given reference schema by merging the
	 * explicit per-reference request (if registered) with the {@link #defaultRequest} fallback.
	 */
	@Nonnull
	private ReferenceSummaryRequest resolveReferenceRequest(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AtomicInteger counter
	) {
		final ReferenceSummaryRequest specific = this.referenceSummaryRequests.get(
			referenceSchema.getName()
		);
		if (specific != null) {
			return this.defaultRequest == null ? specific : mergeSpecificWithDefault(specific);
		}
		return buildFromDefault(referenceSchema, counter);
	}

	/**
	 * Overlays the reference-specific request onto {@link #defaultRequest}, combining entity fetches and
	 * falling back to per-schema predicates/sorters derived from the default whenever the specific request
	 * does not supply its own.
	 */
	@Nonnull
	private ReferenceSummaryRequest mergeSpecificWithDefault(
		@Nonnull ReferenceSummaryRequest specific
	) {
		// caller in resolveReferenceRequest guards against null defaultRequest; pin the invariant here
		final DefaultReferenceSummaryRequest fallback = Objects.requireNonNull(this.defaultRequest);
		final ReferenceSchemaContract schema = specific.referenceSchema();

		// combine entity-fetch requirements: specific extends default when both exist, else use default's
		final EntityFetch combinedFacetEntityRequirement = specific.facetEntityRequirement() == null
			? fallback.facetEntityRequirement()
			: specific.facetEntityRequirement().combineWith(fallback.facetEntityRequirement());
		final EntityGroupFetch combinedGroupEntityRequirement = specific.groupEntityRequirement() == null
			? fallback.groupEntityRequirement()
			: specific.groupEntityRequirement().combineWith(fallback.groupEntityRequirement());

		final IntPredicate facetPredicate = specific.facetPredicate() != null
			? specific.facetPredicate()
			: applyToSchema(fallback.facetPredicate(), schema);
		final IntPredicate groupPredicate = specific.groupPredicate() != null
			? specific.groupPredicate()
			: applyToSchema(fallback.groupPredicate(), schema);
		final NestedContextSorter facetSorter = specific.facetSorter() != null
			? specific.facetSorter()
			: applyToSchema(fallback.facetSorter(), schema);
		final NestedContextSorter groupSorter = specific.groupSorter() != null
			? specific.groupSorter()
			: applyToSchema(fallback.groupSorter(), schema);

		return new ReferenceSummaryRequest(
			specific.order(),
			schema,
			facetPredicate,
			groupPredicate,
			facetSorter,
			groupSorter,
			combinedFacetEntityRequirement,
			combinedGroupEntityRequirement,
			specific.facetStatisticsDepth()
		);
	}

	/**
	 * Builds a {@link ReferenceSummaryRequest} for a reference that has no explicit per-reference entry,
	 * deriving predicates/sorters from {@link #defaultRequest} via its per-schema functions. Reachable only
	 * when {@link #defaultRequest} is non-null — the upstream filter guarantees that invariant.
	 */
	@Nonnull
	private ReferenceSummaryRequest buildFromDefault(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AtomicInteger counter
	) {
		final DefaultReferenceSummaryRequest fallback = Objects.requireNonNull(this.defaultRequest);
		return new ReferenceSummaryRequest(
			this.referenceSummaryRequests.size() + counter.incrementAndGet(),
			referenceSchema,
			applyToSchema(fallback.facetPredicate(), referenceSchema),
			applyToSchema(fallback.groupPredicate(), referenceSchema),
			applyToSchema(fallback.facetSorter(), referenceSchema),
			applyToSchema(fallback.groupSorter(), referenceSchema),
			fallback.facetEntityRequirement(),
			fallback.groupEntityRequirement(),
			fallback.facetStatisticsDepth()
		);
	}

	/**
	 * Applies a nullable per-schema resolver to the given schema, returning `null` when the resolver is
	 * absent. Lets the merge/build helpers express predicate and sorter fallbacks uniformly.
	 */
	@Nullable
	private static <R> R applyToSchema(
		@Nullable Function<ReferenceSchemaContract, R> resolver,
		@Nonnull ReferenceSchemaContract schema
	) {
		return resolver == null ? null : resolver.apply(schema);
	}

	@Nonnull
	@Override
	public String getDescription() {
		if (this.referenceSummaryRequests.size() == 1) {
			return "facet summary for `" + this.referenceSummaryRequests.keySet().iterator().next() + "` references";
		} else {
			return "facet summary for " + this.referenceSummaryRequests.keySet().stream()
				.map(it -> '`' + it + '`')
				.collect(Collectors.joining(" ,")) + " references";
		}
	}

	/**
	 * Collector translates data from {@link FacetReferenceIndex} to {@link ReferenceGroupStatistics}.
	 *
	 * @param <T> the concrete {@link ReferenceGroupStatistics} subtype produced by
	 *            {@link ReferenceSummaryResultAdapter#createGroupStatistics} — keeps the
	 *            collector's output type aligned with the adapter's so the enclosing
	 *            {@code fabricate} method never needs an unchecked cast
	 */
	@RequiredArgsConstructor
	private static class FacetGroupStatisticsCollector<T extends ReferenceGroupStatistics>
		implements Collector<FacetReferenceIndex, LinkedHashMap<Integer, GroupAccumulator>, Collection<T>> {
		/**
		 * The adapter used to create the appropriate {@link ReferenceGroupStatistics} subtype.
		 */
		private final ReferenceSummaryResultAdapter<T> resultAdapter;
		/**
		 * The query execution context to provide access to the schema and other necessary data.
		 */
		private final QueryExecutionContext context;
		/**
		 * Translates {@link FacetHaving#getReferenceName()} to {@link EntitySchema#getReference(String)}.
		 */
		private final Function<String, ReferenceSchemaContract> referenceSchemaLocator;
		/**
		 * Function allowing to locate the appropriate {@link ReferenceSummaryRequest} for facets of particular
		 * {@link ReferenceSchema#getName()}.
		 */
		private final Function<ReferenceSchemaContract, ReferenceSummaryRequest> referenceRequestLocator;
		/**
		 * Contains for each {@link FacetHaving#getType()} set of requested facets.
		 */
		private final Map<String, Bitmap> requestedFacets;
		/**
		 * Facet calculator computes the entity count that relate to each facet.
		 */
		private final FacetCalculator countCalculator;
		/**
		 * Impact calculator computes the potential entity count returned should the facet be selected as well.
		 */
		private final ImpactCalculator impactCalculator;

		/**
		 * Method returns an index of fetched {@link EntityClassifier facet groups} indexed by their primary key
		 * for passed {@link GroupAccumulator} entry.
		 */
		@Nonnull
		private static Map<Integer, EntityClassifier> fetchGroups(
			@Nonnull QueryExecutionContext context,
			@Nonnull Entry<String, List<GroupAccumulator>> entry
		) {
			final List<GroupAccumulator> accs = entry.getValue();
			// Collectors.groupingBy never emits an empty bucket, so accs.get(0) matches the original findFirst() semantics
			final GroupAccumulator groupAcc = accs.get(0);
			// single pass: size the buffer optimistically and trim only if nulls were skipped
			final int[] buffer = new int[accs.size()];
			int count = 0;
			for (GroupAccumulator acc : accs) {
				final Integer groupId = acc.getGroupId();
				if (groupId != null) {
					buffer[count++] = groupId;
				}
			}
			if (count == 0) {
				return Collections.emptyMap();
			}
			final int[] groupIds = count == buffer.length ? buffer : Arrays.copyOf(buffer, count);
			final EntityClassifier[] fetched = groupAcc.getReferenceSummaryRequest()
				.getGroupEntityFetcher(context, groupAcc.getReferenceSchema())
				.apply(groupIds);
			final Map<Integer, EntityClassifier> result = createLinkedHashMap(fetched.length);
			for (final EntityClassifier classifier : fetched) {
				result.put(classifier.getPrimaryKey(), classifier);
			}
			return result;
		}

		/**
		 * Method returns an index of fetched {@link EntityClassifier facets} indexed by their primary key
		 * for passed {@link GroupAccumulator} entry.
		 */
		@Nonnull
		private static Map<Integer, EntityClassifier> fetchFacetEntities(
			@Nonnull QueryExecutionContext context,
			@Nonnull Entry<String, List<GroupAccumulator>> entry
		) {
			final List<GroupAccumulator> accs = entry.getValue();
			// Collectors.groupingBy never emits an empty bucket, so accs.get(0) matches the original findFirst() semantics
			final GroupAccumulator groupAcc = accs.get(0);
			// collect distinct facet ids as primitives to avoid the Stream#distinct auto-boxing pipeline
			final IntHashSet distinctFacetIds = new IntHashSet(32);
			for (GroupAccumulator acc : accs) {
				for (final FacetAccumulator facetAcc : acc.getFacetStatistics().values()) {
					distinctFacetIds.add(facetAcc.getFacetId());
				}
			}
			if (distinctFacetIds.isEmpty()) {
				return Collections.emptyMap();
			}
			final int[] facetIds = distinctFacetIds.toArray();
			final EntityClassifier[] fetched = groupAcc.getReferenceSummaryRequest()
				.getFacetEntityFetcher(context, groupAcc.getReferenceSchema())
				.apply(facetIds);
			final Map<Integer, EntityClassifier> result = createLinkedHashMap(fetched.length);
			for (final EntityClassifier classifier : fetched) {
				result.put(classifier.getPrimaryKey(), classifier);
			}
			return result;
		}

		/**
		 * Returns group entity object from the `groupEntitiesIndex` in case the referenced group is managed.
		 */
		@Nullable
		private static EntityClassifier getGroupEntity(
			@Nonnull GroupAccumulator groupAcc,
			@Nonnull ReferenceSchemaContract referenceSchema,
			@Nonnull Map<Integer, EntityClassifier> groupEntitiesIndex
		) {
			if (groupAcc.getGroupId() == null) {
				return null;
			} else if (referenceSchema.isReferencedGroupTypeManaged()) {
				return ofNullable(groupEntitiesIndex.get(groupAcc.getGroupId()))
					.orElseGet(() -> new EntityReference(
						Objects.requireNonNull(referenceSchema.getReferencedGroupType()),
						groupAcc.getGroupId()
					));
			} else {
				return new EntityReference(Objects.requireNonNull(referenceSchema.getReferencedGroupType()), groupAcc.getGroupId());
			}
		}

		/**
		 * Method collects all group ids in the {@link Bitmap} containers and returns them indexed by
		 * {@link ReferenceSchemaContract#getName()}.
		 */
		@Nonnull
		private static Map<String, Bitmap> getGroupIdsByReferenceName(@Nonnull Map<Integer, GroupAccumulator> entityAcc) {
			// in practice all accumulators within a single per-reference bucket share the same reference
			// schema, so the map usually holds a single entry — still keyed by reference name for callers
			// that rely on the grouped shape
			Map<String, RoaringBitmapWriter<RoaringBitmap>> writers = null;
			for (final GroupAccumulator acc : entityAcc.values()) {
				final Integer groupId = acc.getGroupId();
				if (groupId == null) {
					continue;
				}
				if (writers == null) {
					writers = createHashMap(1);
				}
				writers
					.computeIfAbsent(acc.getReferenceSchema().getName(), k -> RoaringBitmapBackedBitmap.buildWriter())
					.add(groupId);
			}
			if (writers == null) {
				return Map.of();
			}
			final Map<String, Bitmap> result = createHashMap(writers.size());
			for (final Entry<String, RoaringBitmapWriter<RoaringBitmap>> entry : writers.entrySet()) {
				result.put(entry.getKey(), new BaseBitmap(entry.getValue().get()));
			}
			return result;
		}

		/**
		 * Method fetches facet entity bodies if requested (otherwise simple {@link EntityReference} is used) and
		 * indexes them by their {@link EntityClassifier#getPrimaryKey()} in the maps that are then returned in
		 * index where the key is  {@link ReferenceSchemaContract#getName()}.
		 */
		@Nonnull
		private static Map<String, Map<Integer, EntityClassifier>> getFacetEntitiesIndexedByReferenceName(
			@Nonnull QueryExecutionContext context,
			@Nonnull Collection<GroupAccumulator> entityAcc
		) {
			// group accumulators by reference name in a single pass, then fetch once per group
			final Map<String, List<GroupAccumulator>> accByReference = createHashMap(entityAcc.size());
			for (final GroupAccumulator acc : entityAcc) {
				accByReference.computeIfAbsent(acc.getReferenceSchema().getName(), k -> new ArrayList<>()).add(acc);
			}
			if (accByReference.isEmpty()) {
				return Map.of();
			}
			final Map<String, Map<Integer, EntityClassifier>> result = createHashMap(accByReference.size());
			for (final Entry<String, List<GroupAccumulator>> entry : accByReference.entrySet()) {
				result.put(entry.getKey(), fetchFacetEntities(context, entry));
			}
			return result;
		}

		/**
		 * Method fetches facet group entity bodies if requested (otherwise simple {@link EntityReference} is used) and
		 * indexes them by their {@link EntityClassifier#getPrimaryKey()} in the maps that are then returned in
		 * index where the key is  {@link ReferenceSchemaContract#getName()}.
		 */
		@Nonnull
		private static Map<String, Map<Integer, EntityClassifier>> getGroupEntitiesIndexedByReferenceName(
			@Nonnull QueryExecutionContext context,
			@Nonnull Collection<GroupAccumulator> accumulators
		) {
			// group accumulators with a non-null group id by reference name in a single pass, then fetch once per group
			final Map<String, List<GroupAccumulator>> accByReference = createHashMap(accumulators.size());
			for (final GroupAccumulator acc : accumulators) {
				if (acc.getGroupId() == null) {
					continue;
				}
				accByReference.computeIfAbsent(acc.getReferenceSchema().getName(), k -> new ArrayList<>()).add(acc);
			}
			if (accByReference.isEmpty()) {
				return Map.of();
			}
			final Map<String, Map<Integer, EntityClassifier>> result = createHashMap(accByReference.size());
			for (final Entry<String, List<GroupAccumulator>> entry : accByReference.entrySet()) {
				result.put(entry.getKey(), fetchGroups(context, entry));
			}
			return result;
		}

		/**
		 * This method takes a map of facet statistics and a nested context sorter, and returns an array of sorted
		 * facet primary keys.
		 *
		 * @param theFacetStatistics map of facet statistics, where the key is the facet primary key and the value
		 *                           is the facet accumulator
		 * @param sorter             nested context sorter used for sorting the facets
		 * @return array of sorted facet primary keys
		 */
		@Nonnull
		private static int[] getSortedFacets(
			@Nonnull Map<Integer, FacetAccumulator> theFacetStatistics,
			@Nonnull NestedContextSorter sorter
		) {
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			// iterate the accumulators directly to read the facet id as primitive int — avoids boxing each Integer key
			for (final FacetAccumulator facetAcc : theFacetStatistics.values()) {
				writer.add(facetAcc.getFacetId());
			}
			return sorter.sortAndSlice(new BaseBitmap(writer.get()));
		}

		/**
		 * Compares two {@link GroupAccumulator} objects based on their facet group summaries.
		 * The comparison logic is as follows:
		 * 1. If the reference schema of o1 and o2 are different, compare based on the order of their facet summary requests.
		 * 2. If the facet summary request of o1 has a group sorter defined, the facet group summaries are sorted using
		 * the sorter. The sorted group summaries are then used to determine the order of o1 and o2 based on their
		 * group ids.
		 * 3. If the facet summary request of o2 has a group sorter defined, the facet group summaries are sorted using
		 * the sorter. The sorted group summaries are then used to determine the order of o1 and o2 based on their
		 * group ids.
		 * 4. If neither o1 or o2 have a group sorter defined and o1's group id is null, o1 is considered greater
		 * than o2.
		 * 5. If neither o1 or o2 have a group sorter defined and o2's group id is null, o1 is considered less than o2.
		 * 6. If neither o1 or o2 have a group sorter defined and both o1 and o2 have group ids, compare based on
		 * their group ids.
		 *
		 * @param groupIdIndex   the index of facet groups by reference name
		 * @param sortedGroupIds the sorted group ids by reference name
		 * @param o1             the first GroupAccumulator object to compare
		 * @param o2             the second GroupAccumulator object to compare
		 * @return a negative integer, zero, or a positive integer as o1 is less than, equal to, or greater than o2
		 */
		private static int compareFacetGroupSummaries(
			@Nonnull Map<String, Bitmap> groupIdIndex,
			@Nonnull Map<String, int[]> sortedGroupIds,
			@Nonnull GroupAccumulator o1,
			@Nonnull GroupAccumulator o2
		) {
			if (o1.getReferenceSchema() != o2.getReferenceSchema()) {
				return Integer.compare(o1.getReferenceSummaryRequest().order(), o2.getReferenceSummaryRequest().order());
			} else if (o1.getReferenceSummaryRequest().groupSorter() != null) {
				final NestedContextSorter sorter = o1.getReferenceSummaryRequest().groupSorter();
				// create sorted array using the sorter
				final String referenceName = o1.getReferenceSummaryRequest().referenceSchema().getName();
				final int[] sortedEntities = sortedGroupIds.computeIfAbsent(
					referenceName,
					theReferenceName -> sorter.sortAndSlice(groupIdIndex.get(theReferenceName))
				);
				return Integer.compare(
					ArrayUtils.indexOf(Objects.requireNonNull(o1.getGroupId()), sortedEntities),
					ArrayUtils.indexOf(Objects.requireNonNull(o2.getGroupId()), sortedEntities)
				);
			} else {
				if (o1.getGroupId() == null) {
					return 1;
				} else if (o2.getGroupId() == null) {
					return -1;
				} else {
					return Integer.compare(o1.getGroupId(), o2.getGroupId());
				}
			}
		}

		/**
		 * Returns TRUE if facet with `facetId` of specified `referenceName` was requested by the user.
		 */
		public boolean isRequested(@Nonnull String referenceName, int facetId) {
			final Bitmap bitmap = this.requestedFacets.get(referenceName);
			return bitmap != null && bitmap.contains(facetId);
		}

		@Override
		public Supplier<LinkedHashMap<Integer, GroupAccumulator>> supplier() {
			return LinkedHashMap::new;
		}

		@Override
		public BiConsumer<LinkedHashMap<Integer, GroupAccumulator>, FacetReferenceIndex> accumulator() {
			return (acc, facetEntityTypeIndex) -> {
				final String referenceName = facetEntityTypeIndex.getReferenceName();
				final ReferenceSchemaContract referenceSchema = this.referenceSchemaLocator.apply(referenceName);
				final ReferenceSummaryRequest referenceSummaryRequest = this.referenceRequestLocator.apply(referenceSchema);
				final IntPredicate groupPredicate = referenceSummaryRequest.groupPredicate();
				final IntPredicate facetPredicate = referenceSummaryRequest.facetPredicate();
				final IntPredicate isRequestedResolver = facetId -> isRequested(referenceName, facetId);

				// only a stream-based accessor is exposed on FacetReferenceIndex; iterate it imperatively to avoid the
				// nested Optional/Stream filter allocations present in the previous implementation
				facetEntityTypeIndex.getFacetGroupIndexesAsStream().forEach(groupIx -> {
					if (groupPredicate != null) {
						final Integer groupId = groupIx.getGroupId();
						// preserve the original `ofNullable(groupId).map(predicate::test).orElse(false)` semantics:
						// a group without a group id is skipped when a predicate is supplied
						if (groupId == null || !groupPredicate.test(groupId)) {
							return;
						}
					}
					final GroupAccumulator groupAcc = acc.computeIfAbsent(
						groupIx.getGroupId(),
						gId -> new GroupAccumulator(
							referenceSchema,
							referenceSummaryRequest,
							gId,
							this.countCalculator,
							this.impactCalculator
						)
					);
					for (final FacetIdIndex facetIx : groupIx.getFacetIdIndexes().values()) {
						if (facetPredicate != null && !facetPredicate.test(facetIx.getFacetId())) {
							continue;
						}
						groupAcc.addStatistics(facetIx, isRequestedResolver);
					}
				});
			};
		}

		@Override
		public BinaryOperator<LinkedHashMap<Integer, GroupAccumulator>> combiner() {
			return (left, right) -> {
				// combine two HashMap<Integer, GroupAccumulator> together, right one is fully merged into left
				right.forEach((key, value) -> left.merge(key, value, GroupAccumulator::combine));
				return left;
			};
		}

		@Override
		public Function<LinkedHashMap<Integer, GroupAccumulator>, Collection<T>> finisher() {
			return entityAcc -> {
				final Map<String, Map<Integer, EntityClassifier>> groupEntities =
					getGroupEntitiesIndexedByReferenceName(this.context, entityAcc.values());
				final Map<String, Map<Integer, EntityClassifier>> facetEntities =
					getFacetEntitiesIndexedByReferenceName(this.context, entityAcc.values());
				final Map<String, Bitmap> groupIdIndex = getGroupIdsByReferenceName(entityAcc);
				final Map<String, int[]> sortedGroupIds = createHashMap(groupIdIndex.size());

				final GroupAccumulator[] sortedGroups = entityAcc.values().toArray(new GroupAccumulator[0]);
				Arrays.sort(sortedGroups, (o1, o2) -> compareFacetGroupSummaries(groupIdIndex, sortedGroupIds, o1, o2));

				final List<T> result = new ArrayList<>(sortedGroups.length);
				for (final GroupAccumulator groupAcc : sortedGroups) {
					final Map<Integer, FacetAccumulator> theFacetStatistics = groupAcc.getFacetStatistics();
					if (theFacetStatistics.isEmpty()) {
						continue;
					}
					final ReferenceSchemaContract referenceSchema = groupAcc.getReferenceSchema();

					// flatten per-facet entity id bitmaps into a sized array — replaces the allocation-heavy
					// flatMap+toArray pipeline used previously
					int totalBitmapCount = 0;
					for (final FacetAccumulator fa : theFacetStatistics.values()) {
						totalBitmapCount += fa.getFacetEntityIds().size();
					}
					final Bitmap[] allFacetEntityIds = new Bitmap[totalBitmapCount];
					int bitmapIdx = 0;
					for (final FacetAccumulator fa : theFacetStatistics.values()) {
						for (final Bitmap bitmap : fa.getFacetEntityIds()) {
							allFacetEntityIds[bitmapIdx++] = bitmap;
						}
					}

					final Formula entityMatchingAnyOfGroupFacetFormula = this.countCalculator.createGroupCountFormula(
						referenceSchema, groupAcc.getGroupId(), allFacetEntityIds
					);
					final int entityMatchingAnyOfGroupFacet = entityMatchingAnyOfGroupFacetFormula.compute().size();
					if (entityMatchingAnyOfGroupFacet == 0) {
						continue;
					}

					final Map<Integer, EntityClassifier> facetEntitiesIndex =
						Objects.requireNonNull(facetEntities.get(referenceSchema.getName()));
					final NestedContextSorter facetSorter = groupAcc.getReferenceSummaryRequest().facetSorter();

					// materialize the facet iteration order without wrapping each facet in an Optional; when a sorter
					// supplies an id missing from the map we preserve the prior `filter(Objects::nonNull)` behaviour
					// by leaving a null slot in the array and skipping it below
					final FacetAccumulator[] orderedFacets;
					if (facetSorter != null) {
						final int[] sortedIds = getSortedFacets(theFacetStatistics, facetSorter);
						orderedFacets = new FacetAccumulator[sortedIds.length];
						for (int i = 0; i < sortedIds.length; i++) {
							orderedFacets[i] = theFacetStatistics.get(sortedIds[i]);
						}
					} else {
						orderedFacets = theFacetStatistics.values().toArray(new FacetAccumulator[0]);
						Arrays.sort(orderedFacets, Comparator.comparingInt(FacetAccumulator::getFacetId));
					}

					final LinkedHashMap<Integer, FacetStatistics> facetStatistics = createLinkedHashMap(orderedFacets.length);
					for (final FacetAccumulator fa : orderedFacets) {
						if (fa == null) {
							continue;
						}
						if (!fa.hasAnyResults()) {
							continue;
						}
						final EntityClassifier ec = facetEntitiesIndex.get(fa.getFacetId());
						if (ec == null) {
							continue;
						}
						final FacetStatistics stats = fa.toFacetStatistics(ec);
						if (facetStatistics.put(stats.getFacetEntity().getPrimaryKey(), stats) != null) {
							throw new IllegalStateException("Unexpectedly found two facets in stream!");
						}
					}

					final Map<Integer, EntityClassifier> groupEntitiesIndex = groupEntities.get(referenceSchema.getName());
					final EntityClassifier groupEntity = getGroupEntity(groupAcc, referenceSchema, groupEntitiesIndex);
					final T groupStats = this.resultAdapter.createGroupStatistics(
						referenceSchema,
						groupEntity,
						entityMatchingAnyOfGroupFacet,
						facetStatistics,
						Map.of()
					);
					if (!groupStats.getFacetStatistics().isEmpty() || !groupStats.getHistogramStatistics().isEmpty()) {
						result.add(groupStats);
					}
				}
				return result;
			};
		}

		@Override
		public Set<Characteristics> characteristics() {
			return Set.of(Characteristics.UNORDERED);
		}
	}

	/**
	 * This mutable accumulator contains statistics for all facets of same `entityType` and `groupId`.
	 */
	@Data
	private static class GroupAccumulator {
		/**
		 * Contains {@link ReferenceSchema} related to {@link FacetHaving#getReferenceName()}.
		 */
		@Nonnull private final ReferenceSchemaContract referenceSchema;
		/**
		 * Contains configuration of the facet summary requirement that controls output of this accumulator.
		 */
		@Nonnull private final ReferenceSummaryRequest referenceSummaryRequest;
		/**
		 * Contains group id of the facets in this accumulator.
		 */
		@Nullable private final Integer groupId;
		/**
		 * Facet calculator computes the entity count that relate to each facet.
		 */
		private final FacetCalculator countCalculator;
		/**
		 * Impact calculator computes the potential entity count returned should the facet be selected as well.
		 */
		private final ImpactCalculator impactCalculator;
		/**
		 * Contains statistic accumulator for each of the facet.
		 */
		private final Map<Integer, FacetAccumulator> facetStatistics = createLinkedHashMap(32);

		public GroupAccumulator(
			@Nonnull ReferenceSchemaContract referenceSchema,
			@Nonnull ReferenceSummaryRequest referenceSummaryRequest,
			@Nullable Integer groupId,
			@Nonnull FacetCalculator countCalculator,
			@Nonnull ImpactCalculator impactCalculator
		) {
			this.referenceSchema = referenceSchema;
			this.referenceSummaryRequest = referenceSummaryRequest;
			this.groupId = groupId;
			this.countCalculator = countCalculator;
			this.impactCalculator = referenceSummaryRequest.facetStatisticsDepth() == FacetStatisticsDepth.COUNTS ?
				ImpactCalculator.NO_IMPACT : impactCalculator;
		}

		/**
		 * Registers new {@link FacetAccumulator} statistics in the local state.
		 */
		public void addStatistics(
			@Nonnull FacetIdIndex facetIx,
			@Nonnull IntPredicate requestedResolver
		) {
			this.facetStatistics.compute(
				facetIx.getFacetId(),
				(fId, facetAccumulator) -> {
					final FacetAccumulator newAccumulator = new FacetAccumulator(
						this.referenceSchema,
						fId,
						this.groupId,
						requestedResolver.test(fId),
						facetIx.getRecords(),
						this.countCalculator,
						this.impactCalculator
					);
					if (facetAccumulator == null) {
						return newAccumulator;
					} else {
						return facetAccumulator.combine(newAccumulator);
					}
				}
			);
		}

		/**
		 * Combines two GroupAccumulator together. It adds everything from the `otherAccumulator` to self
		 * instance and returns self.
		 */
		public GroupAccumulator combine(GroupAccumulator otherAccumulator) {
			Assert.isPremiseValid(this.referenceSchema.equals(otherAccumulator.referenceSchema), ERROR_SANITY_CHECK);
			Assert.isPremiseValid(Objects.equals(this.groupId, otherAccumulator.groupId), ERROR_SANITY_CHECK);
			otherAccumulator.getFacetStatistics()
				.forEach((key, value) -> this.facetStatistics.merge(key, value, FacetAccumulator::combine));
			return this;
		}

	}

	/**
	 * This mutable accumulator contains statistics for single facet.
	 */
	@Data
	private static class FacetAccumulator {
		private static final Formula[] EMPTY_INT_FORMULA = Formula.EMPTY_FORMULA_ARRAY;
		private static final Bitmap[] EMPTY_BITMAP = new Bitmap[0];
		/**
		 * Contains {@link ReferenceSchema}.
		 */
		private final ReferenceSchemaContract referenceSchema;
		/**
		 * Contains facet group id - primary key of {@link ReferenceSchema#getReferencedGroupType()} entity.
		 */
		private final Integer facetGroupId;
		/**
		 * Contains facetId - primary key of {@link ReferenceSchema#getReferencedEntityType()} entity.
		 */
		@Getter private final int facetId;
		/**
		 * Contains TRUE if this particular facet was requested by in the input query.
		 */
		private final boolean requested;
		/**
		 * Facet calculator computes the entity count that relate to each facet.
		 */
		private final FacetCalculator countCalculator;
		/**
		 * Impact calculator computes the potential entity count returned should the facet be selected as well.
		 */
		private final ImpactCalculator impactCalculator;
		/**
		 * Contains finished result formula so that {@link #getCount()} can be called multiple times without performance
		 * penalty.
		 */
		private Formula resultFormula;
		/**
		 * Contains bitmaps of all entity primary keys that posses this facet. All bitmaps need to be combined with OR
		 * relation in order to get full entity primary key list.
		 */
		private List<Bitmap> facetEntityIds = new ArrayList<>(4);
		/**
		 * Cached snapshot of {@link #facetEntityIds} converted to an array. Invalidated whenever {@link #facetEntityIds}
		 * is mutated — currently only in {@link #combine(FacetAccumulator)}.
		 */
		@Nullable private Bitmap[] facetEntityIdsArray;

		public FacetAccumulator(
			@Nonnull ReferenceSchemaContract referenceSchema,
			int facetId,
			@Nullable Integer facetGroupId,
			boolean requested,
			@Nonnull Bitmap facetEntityIds,
			@Nonnull FacetCalculator countCalculator,
			@Nonnull ImpactCalculator impactCalculator
		) {
			this.referenceSchema = referenceSchema;
			this.facetId = facetId;
			this.facetGroupId = facetGroupId;
			this.requested = requested;
			this.countCalculator = countCalculator;
			this.impactCalculator = impactCalculator;
			this.facetEntityIds.add(facetEntityIds);
		}

		/**
		 * Produces final result of this accumulator.
		 */
		public FacetStatistics toFacetStatistics(@Nonnull EntityClassifier facetEntity) {
			return new FacetStatistics(
				facetEntity,
				this.requested,
				getCount(),
				this.impactCalculator.calculateImpact(
					this.referenceSchema, this.facetId, this.facetGroupId, this.requested,
					getEntityIdsArray()
				)
			);
		}

		/**
		 * Returns a lazily memoized array view of {@link #facetEntityIds}. The array is cached to avoid repeated
		 * allocation on hot paths ({@link #getCount()} and {@link #toFacetStatistics}) and is only invalidated when
		 * the backing list is mutated.
		 */
		@Nonnull
		private Bitmap[] getEntityIdsArray() {
			Bitmap[] array = this.facetEntityIdsArray;
			if (array == null) {
				array = this.facetEntityIds.toArray(EMPTY_BITMAP);
				this.facetEntityIdsArray = array;
			}
			return array;
		}

		/**
		 * Combines two FacetAccumulator together. It adds everything from the `otherAccumulator` to self
		 * instance and returns self.
		 */
		public FacetAccumulator combine(FacetAccumulator otherAccumulator) {
			Assert.isPremiseValid(this.facetId == otherAccumulator.facetId, ERROR_SANITY_CHECK);
			Assert.isPremiseValid(this.requested == otherAccumulator.requested, ERROR_SANITY_CHECK);
			this.facetEntityIds.addAll(otherAccumulator.getFacetEntityIds());
			this.facetEntityIdsArray = null;
			return this;
		}

		/**
		 * Returns true if there is at least one entity in the query result that has this facet.
		 */
		public boolean hasAnyResults() {
			return getCount() > 0;
		}

		/**
		 * Returns count of all entities in the query response that has this facet.
		 */
		public int getCount() {
			if (this.resultFormula == null) {
				// we need to combine all collected facet formulas and then AND them with base formula to get rid
				// of entity primary keys that haven't passed the filter logic
				this.resultFormula = this.countCalculator.createCountFormula(
					this.referenceSchema, this.facetId, this.facetGroupId,
					getEntityIdsArray()
				);
			}
			// this is the most expensive call in this very class
			return this.resultFormula.compute().size();
		}
	}

	/**
	 * Record captures the facet summary requirements.
	 */
	@RequiredArgsConstructor
	private static class ReferenceSummaryRequest {
		private final int order;
		private final @Nonnull ReferenceSchemaContract referenceSchema;
		private final @Nullable IntPredicate facetPredicate;
		private final @Nullable IntPredicate groupPredicate;
		private final @Nullable NestedContextSorter facetSorter;
		private final @Nullable NestedContextSorter groupSorter;
		private final @Nullable EntityFetch facetEntityRequirement;
		private final @Nullable EntityGroupFetch groupEntityRequirement;
		private final @Nonnull FacetStatisticsDepth facetStatisticsDepth;
		private Function<int[], EntityClassifier[]> entityFetcherFunction;
		private Function<int[], EntityClassifier[]> entityGroupFetcherFunction;

		public int order() {
			return this.order;
		}

		@Nonnull
		public ReferenceSchemaContract referenceSchema() {
			return this.referenceSchema;
		}

		@Nullable
		public IntPredicate facetPredicate() {
			return this.facetPredicate;
		}

		@Nullable
		public IntPredicate groupPredicate() {
			return this.groupPredicate;
		}

		@Nullable
		public NestedContextSorter facetSorter() {
			return this.facetSorter;
		}

		@Nullable
		public NestedContextSorter groupSorter() {
			return this.groupSorter;
		}

		@Nullable
		public EntityFetch facetEntityRequirement() {
			return this.facetEntityRequirement;
		}

		@Nullable
		public EntityGroupFetch groupEntityRequirement() {
			return this.groupEntityRequirement;
		}

		@Nonnull
		public FacetStatisticsDepth facetStatisticsDepth() {
			return this.facetStatisticsDepth;
		}

		/**
		 * Returns a function that fetches an array of {@link EntityClassifier} instances based on the provided facet IDs.
		 * The function is initialized depending on whether the referenced entity type in the schema is managed.
		 *
		 * @param context the {@link QueryExecutionContext} containing the execution context for queries
		 * @param referenceSchema the {@link ReferenceSchemaContract} defining the schema for the referenced entity
		 * @return a {@link Function} that maps an array of facet IDs to an array of {@link EntityClassifier}
		 */
		@Nonnull
		public Function<int[], EntityClassifier[]> getFacetEntityFetcher(
			@Nonnull QueryExecutionContext context,
			@Nonnull ReferenceSchemaContract referenceSchema
		) {
			if (this.entityFetcherFunction == null) {
				this.entityFetcherFunction = referenceSchema.isReferencedEntityTypeManaged() ?
					facetIds -> createFetcherFunction(context, this.facetEntityRequirement)
						.apply(context, referenceSchema.getReferencedEntityType(), facetIds) :
					facetIds -> ENTITY_REFERENCE_CONVERTER
						.apply(context, referenceSchema.getReferencedEntityType(), facetIds);
			}
			return this.entityFetcherFunction;
		}

		/**
		 * Returns a function that can fetch a group of {@link EntityClassifier} instances based on the provided group IDs.
		 * The function is initialized based on whether the referenced group type in the provided schema is managed.
		 *
		 * @param context the {@link QueryExecutionContext} containing the execution context for queries.
		 * @param referenceSchema the {@link ReferenceSchemaContract} that defines the schema for the referenced group.
		 * @return a {@link Function} that maps an array of group IDs to an array of {@link EntityClassifier}.
		 */
		@Nonnull
		public Function<int[], EntityClassifier[]> getGroupEntityFetcher(
			@Nonnull QueryExecutionContext context,
			@Nonnull ReferenceSchemaContract referenceSchema
		) {
			if (this.entityGroupFetcherFunction == null) {
				this.entityGroupFetcherFunction = referenceSchema.isReferencedGroupTypeManaged() ?
					groupIds -> createFetcherFunction(context, this.groupEntityRequirement)
						.apply(context, Objects.requireNonNull(referenceSchema.getReferencedGroupType()), groupIds) :
					groupIds -> ENTITY_REFERENCE_CONVERTER
						.apply(context, Objects.requireNonNull(referenceSchema.getReferencedGroupType()), groupIds);
			}
			return this.entityGroupFetcherFunction;
		}

	}

	/**
	 * Record captures the facet summary requirements.
	 *
	 * @param facetStatisticsDepth Contains {@link io.evitadb.api.query.require.ReferenceSummary#getStatisticsDepth()}
	 *                             information.
	 */
	private record DefaultReferenceSummaryRequest(
		@Nullable Function<ReferenceSchemaContract, IntPredicate> facetPredicate,
		@Nullable Function<ReferenceSchemaContract, IntPredicate> groupPredicate,
		@Nullable Function<ReferenceSchemaContract, NestedContextSorter> facetSorter,
		@Nullable Function<ReferenceSchemaContract, NestedContextSorter> groupSorter,
		@Nullable EntityFetch facetEntityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nonnull FacetStatisticsDepth facetStatisticsDepth
	) {

	}

	/**
	 * Immutable descriptor of a single `histogramStatistics` request registered against a reference by the
	 * translator. Holds everything the computer needs to derive bucket data at fabrication time without
	 * having to re-inspect the request tree.
	 *
	 * @param referenceSchema  the reference on which this histogram is defined
	 * @param histogramName    the histogram index name (must exist on the reference schema in every active scope)
	 * @param bucketCount      number of buckets requested by the client
	 * @param behavior         histogram-behavior flag controlling bucket layout and empty-bucket suppression
	 * @param locale           locale used to pick the correct {@code FilterIndex} when the source attribute is
	 *                         localized; `null` otherwise
	 * @param valueDescriptor  resolved metadata about the source attribute (entity vs reference, type, default)
	 * @param entityFetch              optional `EntityFetch` controlling boundary entity richness; `null` → plain
	 *                                 {@code EntityReference} classifier
	 * @param requestedRangesByGroupPk optional `[lo, hi]` ranges extracted from `userFilter → histogramHaving(...)`
	 *                                 siblings, keyed by resolved group PK. The sentinel key
	 *                                 {@link #NON_GROUPED_SENTINEL} is reserved for a `histogramHaving` without a
	 *                                 `groupSelector`. An empty map means no range restriction was requested.
	 */
	public record HistogramRequest(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String histogramName,
		int bucketCount,
		@Nonnull HistogramBehavior behavior,
		@Nullable Locale locale,
		@Nonnull HistogramValueDescriptor valueDescriptor,
		@Nullable EntityFetch entityFetch,
		@Nonnull Map<Integer, RequestedBucketRange> requestedRangesByGroupPk
	) {
		/**
		 * Sentinel group PK used as the map key for a `histogramHaving` that omits its `groupSelector`. evitaDB
		 * reserves the value `0` as the non-grouped sentinel across the entire reference histogram subsystem —
		 * client-supplied group entity PKs of `0` collide with this slot and are rejected by
		 * `ReferenceHistogramAccumulator` with a hard throw.
		 */
		public static final int NON_GROUPED_SENTINEL = 0;
	}

	/**
	 * Inclusive numeric range `[from, to]` extracted from a
	 * {@code userFilter → histogramHaving(refName, histName, from, to, groupSelector?)} constraint.
	 * Used by the computer to flag per-bucket {@code requested} at bucket-value granularity.
	 *
	 * @param from lower bound (inclusive); `null` means "no lower bound"
	 * @param to   upper bound (inclusive); `null` means "no upper bound"
	 */
	public record RequestedBucketRange(
		@Nullable BigDecimal from,
		@Nullable BigDecimal to
	) {
	}

}
