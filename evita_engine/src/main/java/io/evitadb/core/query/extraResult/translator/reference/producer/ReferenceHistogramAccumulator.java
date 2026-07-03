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

package io.evitadb.core.query.extraResult.translator.reference.producer;

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.expression.trigger.HistogramValueDescriptor;
import io.evitadb.core.expression.trigger.HistogramValueSource;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.filter.AttributeRangeCarrierFormula;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.translator.common.RangeCarrierGroup;
import io.evitadb.core.query.extraResult.translator.common.UserFilterRelaxer;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramComputer;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramProducer.AttributeHistogramRequest;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.HistogramRequest;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.RequestedBucketRange;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Functions;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;

/**
 * Helper that post-processes the {@code statisticsByReferenceName} map produced by
 * {@link ReferenceSummaryProducer#fabricate} to attach computed histograms to each reference group.
 *
 * Scope of the current implementation:
 *
 * - computes histograms via the existing {@link AttributeHistogramComputer} machinery — shares bucket
 * behaviors, cache hooks, and number-type handling with attribute-level histograms;
 * - for grouped references, a histogram is computed per {@link ReducedGroupEntityIndex} (one per group)
 * and attached to the matching {@link ReferenceGroupStatistics} entry identified by the group PK;
 * - for ungrouped references, a single histogram is computed from the
 * {@link ReferencedTypeEntityIndex} and attached to the (existing or newly-injected) non-grouped
 * entry (the entry with {@code groupEntity == null});
 * - histogram-only groups — references with no facets but with histogram data — get a synthetic
 * {@link ReferenceGroupStatistics} entry so the relaxed group filter keeps them in the result;
 * - boundary entity resolution (`minReferencedEntity` / `maxReferencedEntity`) runs in two passes —
 * first computes histograms and extracts candidate PKs from the referenced entity's own
 * {@link FilterIndex}, then batch-fetches the distinct PKs by `(entityType, entityFetch)` tuple
 * and attaches them to the final {@link HistogramContract} via the new `convertToHistogram`
 * overload. Resolution applies to {@link HistogramValueSource#REFERENCED_ENTITY_ATTRIBUTE}
 * descriptors only — reference-level attributes carry no natural referenced-entity anchor (the
 * value lives on the edge), so their histograms remain without boundary entities.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class ReferenceHistogramAccumulator {

	/**
	 * Upper bound on the Stage-1 {@code pending} list's initial capacity. Grouped references with
	 * thousands of groups are extreme edge cases — over-allocating beyond this limit wastes heap
	 * without shaving meaningful resize cost.
	 */
	private static final int PENDING_SANITY_CAP = 4096;

	/**
	 * Upper bound on the Stage-3 {@code histogramsByGroupKey} map's initial capacity. Covers
	 * almost every realistic catalog while keeping the initial bucket-array allocation bounded.
	 */
	private static final int HISTOGRAM_GROUP_MAP_SANITY_CAP = 2048;

	/**
	 * Entry point invoked from {@link ReferenceSummaryProducer#fabricate}. Returns a new map with
	 * each reference's collection rebuilt to include histogram data.
	 *
	 * @param statisticsByReferenceName      the facet-statistics map produced by the first fabrication phase;
	 *                                       entries are rebuilt in-place and histogram-only groups are appended
	 * @param histogramRequestsByReference   per-reference list of {@link HistogramRequest}s registered by the
	 *                                       histogram translator during planning; outer key is reference name
	 * @param attributeHistogramBaselineFormula baseline filter formula against which each reference histogram is
	 *                                          computed — carriers tagged with {@link AttributeRangeCarrierFormula}
	 *                                          (the attribute-family slider picks) have been stripped by the caller
	 *                                          via {@link UserFilterRelaxer}
	 *                                          with {@link RangeCarrierGroup#ATTRIBUTE_HISTOGRAM};
	 *                                          facet and price-range carriers remain applied so each reference
	 *                                          histogram still reflects the user's facet / price narrowings.
	 *                                          {@code null} signals "no mandatory filter remains / all records pass"
	 *                                          — the relaxer collapsed the whole tree (e.g. filterBy consisted of
	 *                                          nothing but peeled attribute-range carriers); each histogram spans
	 *                                          the catalog-wide superset in that case
	 * @param context                        the execution context providing access to entity collections and
	 *                                       entity-fetch infrastructure
	 * @param resultAdapter                  adapter that creates the correct {@link ReferenceGroupStatistics}
	 *                                       subtype when synthesizing histogram-only groups
	 * @param facetSorterByReferenceName     resolves the `facetSorter` (when one is configured for the enclosing
	 *                                       `referenceSummaryOfReference`) so boundary PK selection can honour the
	 *                                       user's `orderBy`. Returns `null` when no sorter is wired — the
	 *                                       accumulator then falls back to the lowest-PK rule.
	 * @param groupEntityFetcherByReferenceName resolves the batched group-entity fetcher honouring the enclosing
	 *                                          {@code referenceSummary}'s {@code entityGroupFetch} requirement, so
	 *                                          histogram-only synthesized groups arrive at the consumer with the
	 *                                          same enrichment shape (attributes, references, etc.) as the
	 *                                          facet-bearing groups produced by the first fabrication phase.
	 *                                          Returns {@code null} when no fetcher is available — the accumulator
	 *                                          then falls back to a bare {@link EntityReference}.
	 * @param groupPredicateByReferenceName  resolves the per-reference `filterGroupBy` predicate so the histogram
	 *                                       path drops groups the caller did not select — mirroring the facet path's
	 *                                       group filtering. Returns {@code null} when no `filterGroupBy` was supplied
	 *                                       for the reference, which means "no group filtering" (every group passes).
	 */
	@Nonnull
	static <T extends ReferenceGroupStatistics> Map<String, Collection<T>> injectHistograms(
		@Nonnull Map<String, Collection<T>> statisticsByReferenceName,
		@Nonnull Map<String, List<HistogramRequest>> histogramRequestsByReference,
		@Nullable Formula attributeHistogramBaselineFormula,
		@Nonnull QueryExecutionContext context,
		@Nonnull ReferenceSummaryResultAdapter<T> resultAdapter,
		@Nonnull Function<String, NestedContextSorter> facetSorterByReferenceName,
		@Nonnull Function<String, Function<int[], EntityClassifier[]>> groupEntityFetcherByReferenceName,
		@Nonnull Function<String, IntPredicate> groupPredicateByReferenceName
	) {
		final Map<String, Collection<T>> result = createLinkedHashMap(statisticsByReferenceName.size());
		result.putAll(statisticsByReferenceName);

		for (final Map.Entry<String, List<HistogramRequest>> entry : histogramRequestsByReference.entrySet()) {
			final String referenceName = entry.getKey();
			final List<HistogramRequest> requests = entry.getValue();
			if (requests.isEmpty()) {
				continue;
			}
			final ReferenceSchemaContract referenceSchema = requests.get(0).referenceSchema();
			final Collection<T> existing = result.getOrDefault(referenceName, List.of());
			final NestedContextSorter facetSorter = facetSorterByReferenceName.apply(referenceName);
			final Function<int[], EntityClassifier[]> groupEntityFetcher = groupEntityFetcherByReferenceName.apply(referenceName);
			final IntPredicate groupPredicate = groupPredicateByReferenceName.apply(referenceName);
			final Collection<T> rebuilt = computeForReference(
				referenceSchema, requests, existing,
				attributeHistogramBaselineFormula, context, resultAdapter, facetSorter, groupEntityFetcher,
				groupPredicate
			);
			result.put(referenceName, rebuilt);
		}
		return result;
	}

	/**
	 * Rebuilds the collection of {@link ReferenceGroupStatistics} for a single reference.
	 *
	 * Runs in three stages:
	 *
	 * 1. compute every cacheable histogram and collect a {@link PendingHistogram} descriptor per
	 * (group, histogramName) tuple — together with candidate min/max PKs resolved against the
	 * referenced entity's global {@link FilterIndex};
	 * 2. batch-fetch the distinct boundary PKs, keyed by `(entityType, entityFetch)`, so histograms
	 * sharing an entity fetch shape pay only one round-trip to the entity collection;
	 * 3. emit final {@link HistogramContract} DTOs — either re-creating an existing DTO with its
	 * histogram map attached or synthesizing a new DTO for histogram-only groups.
	 */
	@Nonnull
	private static <T extends ReferenceGroupStatistics> Collection<T> computeForReference(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull List<HistogramRequest> requests,
		@Nonnull Collection<T> existing,
		@Nullable Formula attributeHistogramBaselineFormula,
		@Nonnull QueryExecutionContext context,
		@Nonnull ReferenceSummaryResultAdapter<T> resultAdapter,
		@Nullable NestedContextSorter facetSorter,
		@Nullable Function<int[], EntityClassifier[]> groupEntityFetcher,
		@Nullable IntPredicate groupPredicate
	) {
		final boolean grouped = referenceSchema.getReferencedGroupType() != null
			&& referenceSchema.isReferencedGroupTypeManaged();
		final String referenceName = referenceSchema.getName();
		final String entityType = Objects.requireNonNull(context.getQueryContext().getEntityType());
		final Set<Scope> scopes = context.getQueryContext().getScopes();

		// Stage 1: compute cacheable histograms + resolve boundary PKs (no fetch yet). RGEIs/RTEIs
		// live on the entity collection, located per scope via QueryPlanningContext#getEntityIndex.
		// Sizing: non-grouped = req × scope (floor); grouped grows by per-group RGEIs — probe the
		// RTEI's group count and clamp to a sanity cap against pathological group counts.
		final int pendingEstimate;
		if (grouped) {
			int groupsProbe = 0;
			for (final Scope scope : scopes) {
				final EntityIndexKey probeKey = new EntityIndexKey(
					EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, scope, referenceName
				);
				final ReferencedTypeEntityIndex probe = context.getQueryContext()
					.getEntityIndex(entityType, probeKey, ReferencedTypeEntityIndex.class)
					.orElse(null);
				if (probe != null) {
					groupsProbe += probe.getAllReferencedPrimaryKeys().size();
				}
			}
			pendingEstimate = Math.min(
				Math.max(requests.size() * scopes.size(), requests.size() * groupsProbe),
				PENDING_SANITY_CAP
			);
		} else {
			pendingEstimate = Math.min(requests.size() * scopes.size(), PENDING_SANITY_CAP);
		}
		final List<PendingHistogram> pending = new ArrayList<>(pendingEstimate);
		// hoisted out of the scope × group loop — both depend only on the request
		final List<ResolvedRequest> resolved = new ArrayList<>(requests.size());
		for (final HistogramRequest req : requests) {
			final AttributeSchemaContract attributeSchema = resolveSourceAttributeSchema(req, context);
			@SuppressWarnings("rawtypes") final Comparator comparator = FilterIndex.getComparator(
				AttributeIndex.createAttributeKey(
					req.valueDescriptor().source() == HistogramValueSource.REFERENCE_ATTRIBUTE
						? req.referenceSchema() : null,
					attributeSchema,
					req.locale()
				),
				attributeSchema.getPlainType()
			);
			resolved.add(new ResolvedRequest(req, attributeSchema, comparator));
		}
		for (final ResolvedRequest resolvedReq : resolved) {
			for (final Scope scope : scopes) {
				if (grouped) {
					collectGroupedPending(
						resolvedReq, entityType, referenceName, scope,
						attributeHistogramBaselineFormula, context, facetSorter, groupPredicate, pending
					);
				} else {
					collectNonGroupedPending(
						resolvedReq, entityType, referenceName, scope,
						attributeHistogramBaselineFormula, context, facetSorter, pending
					);
				}
			}
		}

		if (pending.isEmpty()) {
			return existing;
		}

		// Stage 2: batch-fetch boundary entities by (entityType, entityFetch) tuple
		final BoundaryEntityCache entityCache = BoundaryEntityCache.prefetch(pending, context);

		// Stage 3: materialize HistogramContract DTOs. Non-grouped collapses to the `0` sentinel
		// (single outer entry); grouped is bounded by distinct groupPks ≤ pending.size()
		final Map<Integer, Map<String, HistogramContract>> histogramsByGroupKey = createLinkedHashMap(
			Math.min(grouped ? pending.size() : 1, HISTOGRAM_GROUP_MAP_SANITY_CAP)
		);
		for (final PendingHistogram ph : pending) {
			final HistogramContract histogram = ph.materialize(entityCache);
			histogramsByGroupKey
				.computeIfAbsent(ph.groupPk(), k -> new LinkedHashMap<>(4))
				.put(ph.request().histogramName(), histogram);
		}

		return mergeWithExisting(
			referenceSchema, existing, histogramsByGroupKey, grouped, resultAdapter, groupEntityFetcher
		);
	}

	/**
	 * Collects a pending histogram per {@link ReducedGroupEntityIndex} tracked by the reference's
	 * {@code REFERENCED_GROUP_ENTITY_TYPE} index in the given scope. Iterates all known group PKs
	 * and their per-group storage indexes.
	 *
	 * When a {@code groupPredicate} is supplied (the caller attached a `filterGroupBy` to the
	 * enclosing `referenceSummaryOfReference`), group PKs that fail the predicate are skipped — the
	 * same group selection the facet path applies, so the histogram path never emits histograms for
	 * groups the caller did not request. A {@code null} predicate means "no group filtering".
	 */
	private static void collectGroupedPending(
		@Nonnull ResolvedRequest resolved,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nullable Formula attributeHistogramBaselineFormula,
		@Nonnull QueryExecutionContext context,
		@Nullable NestedContextSorter facetSorter,
		@Nullable IntPredicate groupPredicate,
		@Nonnull List<PendingHistogram> pending
	) {
		final EntityIndexKey rteiKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, scope, referenceName
		);
		final ReferencedTypeEntityIndex rtei = context.getQueryContext()
			.getEntityIndex(entityType, rteiKey, ReferencedTypeEntityIndex.class)
			.orElse(null);
		if (rtei == null) {
			return;
		}
		final Bitmap groupPks = rtei.getAllReferencedPrimaryKeys();
		if (groupPks.isEmpty()) {
			return;
		}
		final PrimitiveIterator.OfInt groupPkIt = groupPks.iterator();
		while (groupPkIt.hasNext()) {
			final int groupPk = groupPkIt.nextInt();
			// invariant: group PK `0` is reserved as the non-grouped sentinel across the reference
			// histogram subsystem (both in `histogramsByGroupKey` / `buildGroupEntity` here and in
			// `HistogramRequest.NON_GROUPED_SENTINEL` / `ResolvedHistogramHaving.NON_GROUPED_SENTINEL`
			// on the request side); a real group PK of `0` would collide with that slot and silently
			// swallow the grouped histogram
			if (groupPk == 0) {
				throw new GenericEvitaInternalError(
					"Group primary key must be non-zero — PK `0` is reserved as the non-grouped " +
						"sentinel. Got: 0 for reference `" + referenceName + "`."
				);
			}
			// honour the enclosing referenceSummary's `filterGroupBy`: a group failing the predicate
			// is intentional filtering (the caller did not select it), mirroring the facet path
			if (groupPredicate != null && !groupPredicate.test(groupPk)) {
				continue;
			}
			final int[] rgeiPks = rtei.getAllReferenceIndexes(groupPk);
			for (final int rgeiPk : rgeiPks) {
				final ReducedGroupEntityIndex rgei = context.getQueryContext()
					.getEntityIndexByPrimaryKey(rgeiPk, ReducedGroupEntityIndex.class);
				computePendingHistogram(
					resolved, rgei, groupPk, attributeHistogramBaselineFormula, context, facetSorter
				).ifPresent(pending::add);
			}
		}
	}

	/**
	 * Collects a pending histogram from the non-grouped {@link ReferencedTypeEntityIndex}
	 * (key type {@link EntityIndexType#REFERENCED_ENTITY_TYPE}). Uses the sentinel group PK `0`
	 * so the downstream merge slots the histogram into the non-grouped DTO position.
	 */
	private static void collectNonGroupedPending(
		@Nonnull ResolvedRequest resolved,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nullable Formula attributeHistogramBaselineFormula,
		@Nonnull QueryExecutionContext context,
		@Nullable NestedContextSorter facetSorter,
		@Nonnull List<PendingHistogram> pending
	) {
		final EntityIndexKey rteiKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName
		);
		final ReferencedTypeEntityIndex rtei = context.getQueryContext()
			.getEntityIndex(entityType, rteiKey, ReferencedTypeEntityIndex.class)
			.orElse(null);
		if (rtei == null) {
			return;
		}
		computePendingHistogram(resolved, rtei, 0, attributeHistogramBaselineFormula, context, facetSorter)
			.ifPresent(pending::add);
	}

	/**
	 * Computes a single cacheable histogram and, when the descriptor points at a referenced entity
	 * attribute, resolves candidate min/max PKs via the entity's global {@link FilterIndex}
	 * intersected with {@code index.getAllReferencedPrimaryKeys()}. Returns {@link Optional#empty()}
	 * when the histogram resolves to {@link CacheableHistogramContract#EMPTY} or when the source
	 * attribute cannot be located.
	 *
	 * Boundary PK resolution uses the histogram's raw (native-typed) min/max — the exact values as
	 * stored in the attribute's {@link FilterIndex}. When either is {@code null} (legacy cache entry
	 * or an EMPTY histogram) boundary resolution is skipped, and the histogram is emitted without
	 * boundary entities.
	 */
	@Nonnull
	private static Optional<PendingHistogram> computePendingHistogram(
		@Nonnull ResolvedRequest resolved,
		@Nonnull EntityIndex sourceIndex,
		int groupPk,
		@Nullable Formula attributeHistogramBaselineFormula,
		@Nonnull QueryExecutionContext context,
		@Nullable NestedContextSorter facetSorter
	) {
		final HistogramRequest req = resolved.request();
		final FilterIndex filterIndex = histogramFilterIndexFor(sourceIndex, req);
		if (filterIndex == null) {
			return Optional.empty();
		}
		final CacheableHistogramContract cacheable = computeCacheable(
			resolved, filterIndex, attributeHistogramBaselineFormula, context
		);
		// match the JavaDoc contract: skip EMPTY-sentinel histograms entirely so they never enter
		// the `histogramsByGroupKey` map. Avoids allocating a PendingHistogram, a boundary-prefetch
		// slot, and a synthetic group DTO carrying nothing the client can use
		if (cacheable == CacheableHistogramContract.EMPTY) {
			return Optional.empty();
		}
		final Serializable rawMin = cacheable.getRawMin();
		final Serializable rawMax = cacheable.getRawMax();
		final BoundaryPks boundaryPks = (rawMin == null || rawMax == null)
			? BoundaryPks.NONE
			: resolveBoundaryPks(
				req, sourceIndex, resolved.attributeSchema(), rawMin, rawMax, context, facetSorter
			);
		return Optional.of(new PendingHistogram(groupPk, req, cacheable, boundaryPks));
	}

	/**
	 * Looks up the histogram-backed {@link FilterIndex} on the appropriate index subtype. Grouped and
	 * non-grouped references expose the same contract via separate methods so the dispatch stays local.
	 * Any other subtype is a programming error and must surface immediately.
	 */
	@Nullable
	private static FilterIndex histogramFilterIndexFor(
		@Nonnull EntityIndex sourceIndex,
		@Nonnull HistogramRequest req
	) {
		if (sourceIndex instanceof ReducedGroupEntityIndex rgei) {
			return rgei.getHistogramFilterIndex(req.histogramName(), req.locale());
		}
		if (sourceIndex instanceof ReferencedTypeEntityIndex rtei) {
			return rtei.getHistogramFilterIndex(req.histogramName(), req.locale());
		}
		throw new GenericEvitaInternalError(
			"Unexpected EntityIndex subtype `" + sourceIndex.getClass().getName() + "` passed to " +
				"histogramFilterIndexFor — only ReducedGroupEntityIndex and ReferencedTypeEntityIndex " +
				"expose a histogram filter index."
		);
	}

	/**
	 * Delegates the actual histogram math to {@link AttributeHistogramComputer}. Builds a one-off
	 * {@link AttributeHistogramRequest} wrapping the single {@link FilterIndex} backing this
	 * (reference, group, histogram) tuple and routes the computation through
	 * {@link QueryExecutionContext#analyse(CacheableEvitaResponseExtraResultComputer)} so the result
	 * participates in the shared extra-result cache. The {@link FilterIndex#getId()} identifies
	 * each RGEI / RTEI histogram index uniquely at the process level, so cache keys collide neither
	 * across references nor with attribute-level histograms.
	 */
	@Nonnull
	private static CacheableHistogramContract computeCacheable(
		@Nonnull ResolvedRequest resolved,
		@Nonnull FilterIndex filterIndex,
		@Nullable Formula attributeHistogramBaselineFormula,
		@Nonnull QueryExecutionContext context
	) {
		final HistogramRequest req = resolved.request();
		final AttributeHistogramRequest histRequest = new AttributeHistogramRequest(
			resolved.attributeSchema(),
			resolved.comparator(),
			List.of(filterIndex)
		);
		final AttributeHistogramComputer computer = new AttributeHistogramComputer(
			req.histogramName(), attributeHistogramBaselineFormula,
			req.bucketCount(), req.behavior(), histRequest
		);
		computer.initialize(context);
		return context.analyse(computer).compute();
	}

	/**
	 * Resolves candidate min/max referenced PKs for the histogram. Dispatches on the histogram's
	 * value source:
	 *
	 * - {@link HistogramValueSource#REFERENCED_ENTITY_ATTRIBUTE} — uses the referenced entity
	 * collection's own global {@link FilterIndex} on the source attribute. The global filter index
	 * is keyed on the referenced entity's primary key by definition, so
	 * {@link FilterIndex#getRecordsEqualTo} returns the set of candidate PKs directly.
	 * - {@link HistogramValueSource#REFERENCE_ATTRIBUTE} — uses the reference's own attribute
	 * {@link FilterIndex} on {@link ReducedGroupEntityIndex} / {@link ReferencedTypeEntityIndex}.
	 * Both indexes key the reference-attribute FilterIndex on the reference's reduced-index PK
	 * (the recordId is swapped during insert via `ReferenceIndexMutator#executeWithDifferentPrimaryKeyToIndex`),
	 * so `getRecordsEqualTo(value)` returns reduced-index PKs which are mapped back to referenced
	 * entity PKs via `sourceIndex.getReferencedPrimaryKeysForIndexPks(...)`. Returns
	 * {@link BoundaryPks#NONE} when the attribute is not configured as filterable (no
	 * FilterIndex to query).
	 *
	 * The candidate set is intersected with the referenced PKs visible in the current group / type
	 * index so out-of-scope entities do not surface as boundary anchors.
	 *
	 * The {@code minValue} / {@code maxValue} parameters are the histogram's raw native-typed
	 * bounds — they are passed to {@link FilterIndex#getRecordsEqualTo(Serializable)} verbatim
	 * since the index was populated with values of that same type.
	 */
	@Nonnull
	private static BoundaryPks resolveBoundaryPks(
		@Nonnull HistogramRequest req,
		@Nonnull EntityIndex sourceIndex,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Serializable minValue,
		@Nonnull Serializable maxValue,
		@Nonnull QueryExecutionContext context,
		@Nullable NestedContextSorter facetSorter
	) {
		final HistogramValueSource source = req.valueDescriptor().source();
		if (source == HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE) {
			return resolveBoundaryPksFromReferencedEntity(
				req, sourceIndex, attributeSchema, minValue, maxValue, context, facetSorter
			);
		}
		if (source == HistogramValueSource.REFERENCE_ATTRIBUTE) {
			return resolveBoundaryPksFromReferenceAttribute(
				req, sourceIndex, attributeSchema, minValue, maxValue, facetSorter
			);
		}
		throw new GenericEvitaInternalError(
			"Unexpected histogram value source: " + source
		);
	}

	/**
	 * Resolves min/max referenced PKs when the histogram source is a referenced-entity attribute.
	 * Looks up the referenced entity collection's own global {@link FilterIndex} and intersects with
	 * the referenced PKs visible in the current group / type index.
	 */
	@Nonnull
	private static BoundaryPks resolveBoundaryPksFromReferencedEntity(
		@Nonnull HistogramRequest req,
		@Nonnull EntityIndex sourceIndex,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Serializable minValue,
		@Nonnull Serializable maxValue,
		@Nonnull QueryExecutionContext context,
		@Nullable NestedContextSorter facetSorter
	) {
		final String sourceEntityType = req.valueDescriptor().sourceEntityType();
		if (sourceEntityType == null) {
			return BoundaryPks.NONE;
		}
		final Scope scope = sourceIndex.getIndexKey().scope();
		final GlobalEntityIndex globalIndex = context.getQueryContext()
			.getGlobalEntityIndexIfExists(sourceEntityType, scope)
			.orElse(null);
		if (globalIndex == null) {
			return BoundaryPks.NONE;
		}
		final FilterIndex attributeFilterIndex = globalIndex.getFilterIndex(
			null, attributeSchema, req.locale()
		);
		if (attributeFilterIndex == null) {
			return BoundaryPks.NONE;
		}
		final Bitmap inGroup = referencedPrimaryKeys(sourceIndex);
		if (inGroup.isEmpty()) {
			return BoundaryPks.NONE;
		}
		final Integer minPk = pickBoundaryPk(attributeFilterIndex, minValue, inGroup, facetSorter);
		final Integer maxPk;
		if (Objects.equals(maxValue, minValue)) {
			maxPk = minPk;
		} else {
			maxPk = pickBoundaryPk(attributeFilterIndex, maxValue, inGroup, facetSorter);
		}
		return new BoundaryPks(sourceEntityType, minPk, maxPk);
	}

	/**
	 * Resolves min/max referenced PKs when the histogram source is a reference-level attribute
	 * (an attribute that lives on the reference edge). Uses the reference's own
	 * {@link FilterIndex} — which is keyed on reduced-index PK on both RGEI and RTEI — and maps
	 * each result back to the referenced entity PK via the index's
	 * `getReferencedPrimaryKeysForIndexPks(...)` method.
	 *
	 * The referenced entity type is taken from the reference schema; boundary fetch uses that type
	 * against the entity-fetch infrastructure later in the pipeline.
	 */
	@Nonnull
	private static BoundaryPks resolveBoundaryPksFromReferenceAttribute(
		@Nonnull HistogramRequest req,
		@Nonnull EntityIndex sourceIndex,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Serializable minValue,
		@Nonnull Serializable maxValue,
		@Nullable NestedContextSorter facetSorter
	) {
		final String referencedEntityType = req.referenceSchema().getReferencedEntityType();
		final FilterIndex referenceAttributeFilterIndex = sourceIndex.getFilterIndex(
			req.referenceSchema(), attributeSchema, req.locale()
		);
		if (referenceAttributeFilterIndex == null) {
			// attribute is not marked `.filterable()` on the reference schema — no FilterIndex to
			// query, so no boundary entities can be produced for this histogram
			return BoundaryPks.NONE;
		}
		final Bitmap inScope = referencedPrimaryKeys(sourceIndex);
		if (inScope.isEmpty()) {
			return BoundaryPks.NONE;
		}
		final Integer minPk = pickBoundaryPkFromReferenceAttribute(
			referenceAttributeFilterIndex, sourceIndex, minValue, inScope, facetSorter
		);
		final Integer maxPk;
		if (Objects.equals(maxValue, minValue)) {
			maxPk = minPk;
		} else {
			maxPk = pickBoundaryPkFromReferenceAttribute(
				referenceAttributeFilterIndex, sourceIndex, maxValue, inScope, facetSorter
			);
		}
		return new BoundaryPks(referencedEntityType, minPk, maxPk);
	}

	/**
	 * Returns the set of referenced entity PKs tracked by the given index — the intersection base used
	 * when picking a boundary PK. Both grouped and non-grouped indexes expose this under the same
	 * name; any other index subtype is a programming error and must surface immediately.
	 */
	@Nonnull
	private static Bitmap referencedPrimaryKeys(@Nonnull EntityIndex sourceIndex) {
		if (sourceIndex instanceof ReducedGroupEntityIndex rgei) {
			return rgei.getAllReferencedPrimaryKeys();
		}
		if (sourceIndex instanceof ReferencedTypeEntityIndex rtei) {
			return rtei.getAllReferencedPrimaryKeys();
		}
		throw new GenericEvitaInternalError(
			"Unexpected EntityIndex subtype `" + sourceIndex.getClass().getName() + "` passed to " +
				"referencedPrimaryKeys — only ReducedGroupEntityIndex and ReferencedTypeEntityIndex " +
				"are valid histogram source indexes."
		);
	}

	/**
	 * Resolves a single boundary PK for a REFERENCE_ATTRIBUTE histogram. Queries the reference's own
	 * attribute {@link FilterIndex} (keyed on reduced-index PK), maps the result to referenced
	 * entity PKs via the index's `getReferencedPrimaryKeysForIndexPks(...)` method, intersects
	 * with the set of referenced PKs tracked in the current group / type index, and picks the
	 * final PK via the same sorter/lowest-PK rule as {@link #pickBoundaryPk}.
	 *
	 * The {@code value} is the histogram's raw native-typed bound — passed to the FilterIndex
	 * lookup verbatim since the index was populated with values of that same type.
	 */
	@Nullable
	private static Integer pickBoundaryPkFromReferenceAttribute(
		@Nonnull FilterIndex referenceAttributeFilterIndex,
		@Nonnull EntityIndex sourceIndex,
		@Nonnull Serializable value,
		@Nonnull Bitmap inScope,
		@Nullable NestedContextSorter facetSorter
	) {
		final Bitmap indexPkCandidates = resolveBoundaryCandidates(
			referenceAttributeFilterIndex, value
		);
		if (indexPkCandidates.isEmpty()) {
			return null;
		}
		final Bitmap referencedPkCandidates = referencedPrimaryKeysForIndexPks(sourceIndex, indexPkCandidates);
		return intersectAndPickBoundaryPk(referencedPkCandidates, inScope, facetSorter);
	}

	/**
	 * Resolves the candidate record-id bitmap for a histogram boundary value. For scalar attribute
	 * filter indexes this is the conventional `FilterIndex.getRecordsEqualTo(value)` lookup. For
	 * range-typed leaves the bucket key is a threshold value (`Byte`/`Short`/`Integer`/`Long`/
	 * `BigDecimal`) — not a `Range` instance — so the call must envelope the threshold via the
	 * leaf's {@link io.evitadb.index.range.RangeIndex} companion. Returns every record whose stored
	 * range covers the supplied bound, matching the closed-interval semantics emitted by the sweep.
	 *
	 * @param filterIndex the source FilterIndex (may or may not have a `RangeIndex` companion)
	 * @param value       the boundary value (threshold-typed for range histograms; native-typed
	 *                    otherwise)
	 * @return bitmap of record ids matching the value; empty if none qualify
	 */
	@Nonnull
	private static Bitmap resolveBoundaryCandidates(
		@Nonnull FilterIndex filterIndex,
		@Nonnull Serializable value
	) {
		if (filterIndex.getRangeIndex() != null) {
			return filterIndex.getRecordsValidIn(FilterIndex.fromBucketKey(value));
		}
		return filterIndex.getRecordsEqualTo(value);
	}

	/**
	 * Dispatches the reverse lookup `reduced-index PK → referenced entity PK` on the appropriate
	 * index subtype. Both {@link ReducedGroupEntityIndex} and {@link ReferencedTypeEntityIndex}
	 * expose {@code getReferencedPrimaryKeysForIndexPks(Bitmap)} with identical semantics.
	 */
	@Nonnull
	private static Bitmap referencedPrimaryKeysForIndexPks(
		@Nonnull EntityIndex sourceIndex,
		@Nonnull Bitmap indexPrimaryKeys
	) {
		if (sourceIndex instanceof ReducedGroupEntityIndex rgei) {
			return rgei.getReferencedPrimaryKeysForIndexPks(indexPrimaryKeys);
		}
		if (sourceIndex instanceof ReferencedTypeEntityIndex rtei) {
			return rtei.getReferencedPrimaryKeysForIndexPks(indexPrimaryKeys);
		}
		throw new GenericEvitaInternalError(
			"Unexpected EntityIndex subtype `" + sourceIndex.getClass().getName() + "` passed to " +
				"referencedPrimaryKeysForIndexPks — only ReducedGroupEntityIndex and " +
				"ReferencedTypeEntityIndex expose this reverse lookup."
		);
	}

	/**
	 * Resolves a single PK matching {@code value} on the attribute's global filter index, intersected
	 * with {@code inGroup}. When a {@code facetSorter} is available, the intersection is sorted via
	 * the reference's configured sorter and the first PK is taken — matching the `orderBy` the user
	 * attached to `referenceSummaryOfReference`. Otherwise falls back to the lowest-valued PK. Returns
	 * `null` when the intersection is empty (can happen when the histogram covers a broader source
	 * set than the current group).
	 *
	 * The {@code value} is the histogram's raw native-typed bound — passed to the FilterIndex
	 * lookup verbatim since the index was populated with values of that same type.
	 */
	@Nullable
	private static Integer pickBoundaryPk(
		@Nonnull FilterIndex attributeFilterIndex,
		@Nonnull Serializable value,
		@Nonnull Bitmap inGroup,
		@Nullable NestedContextSorter facetSorter
	) {
		return intersectAndPickBoundaryPk(
			resolveBoundaryCandidates(attributeFilterIndex, value), inGroup, facetSorter
		);
	}

	/**
	 * Intersects the candidate referenced-PK bitmap with the in-scope bitmap and picks a single PK —
	 * via the reference's configured sorter when available (honouring the user's `orderBy`), or the
	 * lowest-valued PK otherwise. Returns `null` when either input is empty or the intersection is
	 * empty (can happen when the histogram covers a broader source set than the current group).
	 */
	@Nullable
	private static Integer intersectAndPickBoundaryPk(
		@Nonnull Bitmap candidates,
		@Nonnull Bitmap inScope,
		@Nullable NestedContextSorter facetSorter
	) {
		if (candidates.isEmpty()) {
			return null;
		}
		final PersistentRoaringBitmap candidatesRoaring = RoaringBitmapBackedBitmap.getRoaringBitmap(candidates);
		final PersistentRoaringBitmap inScopeRoaring = RoaringBitmapBackedBitmap.getRoaringBitmap(inScope);
		final PersistentRoaringBitmap intersection = PersistentRoaringBitmap.and(candidatesRoaring, inScopeRoaring);
		if (intersection.isEmpty()) {
			return null;
		}
		// short-circuit when only one PK survives the intersection — the sorter cannot reorder a
		// single element, so skipping the allocation + sortAndSlice call is a straight win
		if (intersection.getLongCardinality() == 1L) {
			return intersection.first();
		}
		if (facetSorter != null) {
			// honours the reference's configured orderBy; PERFORMANCE: sortAndSlice sorts the full
			// intersection and we keep only [0] — a bounded `sortAndSlice(Bitmap, int limit)` overload
			// would avoid the waste on large groups. Bounded to ≤ 2× per histogram request.
			final int[] sorted = facetSorter.sortAndSlice(new BaseBitmap(intersection));
			if (sorted.length > 0) {
				return sorted[0];
			}
		}
		return intersection.first();
	}

	/**
	 * Combines histogram-only group keys with the existing DTOs: DTOs are re-created (adapter
	 * doesn't expose a mutator) with histograms attached; groups that had histograms but no facets
	 * get a new synthetic DTO with empty facet statistics.
	 */
	@Nonnull
	private static <T extends ReferenceGroupStatistics> Collection<T> mergeWithExisting(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Collection<T> existing,
		@Nonnull Map<Integer, Map<String, HistogramContract>> histogramsByGroupKey,
		boolean grouped,
		@Nonnull ReferenceSummaryResultAdapter<T> resultAdapter,
		@Nullable Function<int[], EntityClassifier[]> groupEntityFetcher
	) {
		final List<T> rebuilt = new ArrayList<>(existing.size() + histogramsByGroupKey.size());
		final Map<Integer, Map<String, HistogramContract>> remaining = new LinkedHashMap<>(histogramsByGroupKey);

		for (final T group : existing) {
			final int key = group.getGroupEntity() == null ? 0 : group.getGroupEntity().getPrimaryKeyOrThrowException();
			final Map<String, HistogramContract> histograms = remaining.remove(key);
			if (histograms == null) {
				rebuilt.add(group);
			} else {
				rebuilt.add(
					resultAdapter.createGroupStatistics(
						referenceSchema,
						group.getGroupEntity(),
						group.getCount(),
						asFacetStatisticsMap(group),
						histograms
					)
				);
			}
		}

		// histogram-only groups need fully-fetched entities (matching the enclosing referenceSummary's
		// entityGroupFetch shape) so downstream consumers — notably the GraphQL AttributesDataFetcher —
		// can read attributes/references. Without this batch fetch the synthesizer falls back to a bare
		// EntityReference and the GraphQL `groupEntity { attributes { ... } }` selection ClassCasts.
		final Map<Integer, EntityClassifier> fetchedGroupEntities = prefetchSyntheticGroupEntities(
			grouped, remaining, groupEntityFetcher
		);

		// synthesize DTOs for groups that had histograms but no facets
		for (final Map.Entry<Integer, Map<String, HistogramContract>> leftover : remaining.entrySet()) {
			final int key = leftover.getKey();
			final EntityClassifier groupEntity = buildGroupEntity(referenceSchema, grouped, key, fetchedGroupEntities);
			rebuilt.add(
				resultAdapter.createGroupStatistics(
					referenceSchema,
					groupEntity,
					0,
					Map.of(),
					leftover.getValue()
				)
			);
		}
		return rebuilt;
	}

	/**
	 * Batch-fetches fully-enriched group entities for histogram-only synthetic groups using the
	 * caller-supplied fetcher. Returns an empty map when the reference is non-grouped, when no
	 * leftover keys remain, or when no fetcher was wired (deprecated FacetSummary adapter path).
	 */
	@Nonnull
	private static Map<Integer, EntityClassifier> prefetchSyntheticGroupEntities(
		boolean grouped,
		@Nonnull Map<Integer, Map<String, HistogramContract>> remaining,
		@Nullable Function<int[], EntityClassifier[]> groupEntityFetcher
	) {
		if (!grouped || groupEntityFetcher == null || remaining.isEmpty()) {
			return Collections.emptyMap();
		}
		// drop the non-grouped sentinel `0` early so the fetcher input stays minimal — buildGroupEntity
		// filters it again on the consumer side as a coherence check
		final int[] keys = new int[remaining.size()];
		int idx = 0;
		for (final Integer key : remaining.keySet()) {
			if (key != 0) {
				keys[idx++] = key;
			}
		}
		if (idx == 0) {
			return Collections.emptyMap();
		}
		final int[] trimmed = idx == keys.length ? keys : Arrays.copyOf(keys, idx);
		final EntityClassifier[] fetched = groupEntityFetcher.apply(trimmed);
		if (fetched == null || fetched.length == 0) {
			return Collections.emptyMap();
		}
		final Map<Integer, EntityClassifier> result = createLinkedHashMap(fetched.length);
		for (final EntityClassifier classifier : fetched) {
			// EntityClassifier#getPrimaryKey is contractually @Nullable; in practice fetched entities
			// always carry a PK, but a missing one would put a null key in the map and silently fail
			// the lookup in buildGroupEntity. Guard explicitly.
			if (classifier == null) {
				continue;
			}
			final Integer primaryKey = classifier.getPrimaryKey();
			if (primaryKey != null) {
				result.put(primaryKey, classifier);
			}
		}
		return result;
	}

	/**
	 * Extracts facet statistics from an existing DTO into the keyed map format the adapter expects.
	 * Preserves insertion order using a {@link LinkedHashMap}.
	 */
	@Nonnull
	private static Map<Integer, FacetStatistics> asFacetStatisticsMap(@Nonnull ReferenceGroupStatistics group) {
		final Collection<FacetStatistics> source = group.getFacetStatistics();
		if (source.isEmpty()) {
			return Collections.emptyMap();
		}
		final Map<Integer, FacetStatistics> out = createLinkedHashMap(source.size());
		for (final FacetStatistics stat : source) {
			out.put(stat.getFacetEntity().getPrimaryKeyOrThrowException(), stat);
		}
		return out;
	}

	/**
	 * Builds an {@link EntityClassifier} for a histogram-only group. For the non-grouped slot
	 * (key == 0) returns {@code null}; for grouped references prefers a fully-fetched entity
	 * pulled from {@code fetchedGroupEntities} (matching the enclosing referenceSummary's
	 * entityGroupFetch shape) and falls back to a minimal {@link EntityReference} when no
	 * fetcher was wired or the fetcher could not resolve the key — keeping the downstream DTO
	 * split ({@code groupEntity == null} ⇒ non-grouped) coherent.
	 */
	@Nullable
	private static EntityClassifier buildGroupEntity(
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean grouped,
		int key,
		@Nonnull Map<Integer, EntityClassifier> fetchedGroupEntities
	) {
		if (!grouped || key == 0) {
			return null;
		}
		final EntityClassifier fetched = fetchedGroupEntities.get(key);
		if (fetched != null) {
			return fetched;
		}
		final String groupType = referenceSchema.getReferencedGroupType();
		return groupType != null ? new EntityReference(groupType, key) : null;
	}

	/**
	 * Produces the predicate used to flag per-bucket {@code requested} at conversion time. Mirrors the
	 * semantics of {@code AttributeBetweenTranslator.createBigDecimalPredicate} — a bucket threshold is
	 * considered "requested" when it sits inside the closed `[from, to]` range extracted from the
	 * `userFilter → referenceHaving(... attributeBetween ...)` subtree. Returns an always-true predicate
	 * when no range was registered (e.g. the query had no matching `userFilter` subtree) so that "nothing
	 * selected" reads as "everything selected" — this matches the long-standing
	 * {@link io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramProducer}
	 * contract and lets clients render a min-to-max slider widget without special-casing the empty state.
	 */
	@Nonnull
	private static Predicate<BigDecimal> requestedBucketPredicate(@Nullable RequestedBucketRange range) {
		if (range == null) {
			return Functions.alwaysTrue();
		}
		final BigDecimal from = range.from();
		final BigDecimal to = range.to();
		return threshold -> (from == null || threshold.compareTo(from) >= 0)
			&& (to == null || threshold.compareTo(to) <= 0);
	}

	/**
	 * Resolves the {@link AttributeSchemaContract} referenced by the histogram's value expression —
	 * either the reference-level attribute (when source is {@link HistogramValueSource#REFERENCE_ATTRIBUTE}
	 * — `sourceEntityType` is `null` in that case, per the descriptor contract) or the referenced
	 * entity's attribute.
	 */
	@Nonnull
	private static AttributeSchemaContract resolveSourceAttributeSchema(
		@Nonnull HistogramRequest req,
		@Nonnull QueryExecutionContext context
	) {
		final HistogramValueDescriptor descriptor = req.valueDescriptor();
		final String attributeName = descriptor.sourceAttributeName();
		if (descriptor.source() == HistogramValueSource.REFERENCE_ATTRIBUTE) {
			final String owningEntityType = Objects.requireNonNull(context.getQueryContext().getEntityType());
			final EntitySchemaContract owningEntitySchema = context
				.getEntityCollectionOrThrowException(owningEntityType, "resolve histogram source attribute")
				.getSchema();
			return req.referenceSchema().getAttribute(attributeName)
				.orElseThrow(
					() -> new AttributeNotFoundException(attributeName, req.referenceSchema(), owningEntitySchema)
				);
		}
		final String entityType = Objects.requireNonNull(descriptor.sourceEntityType());
		final EntitySchemaContract entitySchema = context
			.getEntityCollectionOrThrowException(entityType, "resolve histogram source attribute")
			.getSchema();
		return entitySchema.getAttribute(attributeName)
			.orElseThrow(() -> new AttributeNotFoundException(attributeName, entitySchema));
	}

	private ReferenceHistogramAccumulator() {
	}

	/**
	 * Pre-resolved per-request artefacts that depend only on the {@link HistogramRequest} — cached
	 * across the `scopes × groups` loop so schema and comparator resolution happens exactly once
	 * per request regardless of group fan-out.
	 *
	 * @param request         original histogram request
	 * @param attributeSchema schema of the attribute the histogram is computed over
	 * @param comparator      comparator for the attribute's plain type, bound to the right
	 *                        attribute key (reference-level vs. referenced-entity-level)
	 */
	private record ResolvedRequest(
		@Nonnull HistogramRequest request,
		@Nonnull AttributeSchemaContract attributeSchema,
		@SuppressWarnings("rawtypes") @Nonnull Comparator comparator
	) {
	}

	/**
	 * Intermediate descriptor produced in Stage 1 — holds the cacheable histogram together with the
	 * resolved candidate boundary PKs so Stage 3 can emit the final DTO without re-computing.
	 *
	 * @param groupPk     group primary key; `0` sentinel means the non-grouped slot
	 * @param request     original histogram request carrying the predicate range and entity fetch
	 * @param cacheable   intermediate cacheable histogram produced by {@link AttributeHistogramComputer}
	 * @param boundaryPks resolved boundary PK pair ({@link BoundaryPks#NONE} when not resolvable)
	 */
	private record PendingHistogram(
		int groupPk,
		@Nonnull HistogramRequest request,
		@Nonnull CacheableHistogramContract cacheable,
		@Nonnull BoundaryPks boundaryPks
	) {
		/**
		 * Converts the cacheable form to the final {@link HistogramContract} attaching the resolved
		 * boundary entities (if any). When only one side can be resolved both are dropped — the
		 * {@link io.evitadb.api.requestResponse.extraResult.Histogram} DTO enforces "both or neither".
		 */
		@Nonnull
		HistogramContract materialize(@Nonnull BoundaryEntityCache entityCache) {
			// fall back to NON_GROUPED_SENTINEL for groupSelector-less histogramHaving; absent on both
			// keys means no slider was registered — requestedBucketPredicate returns always-true so
			// every bucket is rendered as "selected" (the empty userFilter == full range convention)
			final Map<Integer, RequestedBucketRange> rangesByGroupPk = this.request.requestedRangesByGroupPk();
			RequestedBucketRange range = rangesByGroupPk.get(this.groupPk);
			if (range == null) {
				range = rangesByGroupPk.get(HistogramRequest.NON_GROUPED_SENTINEL);
			}
			final Predicate<BigDecimal> predicate = requestedBucketPredicate(range);
			final SealedEntity minEntity = entityCache.resolve(this.boundaryPks.entityType(), this.boundaryPks.minPk());
			final SealedEntity maxEntity = entityCache.resolve(this.boundaryPks.entityType(), this.boundaryPks.maxPk());
			if (minEntity == null || maxEntity == null) {
				return this.cacheable.convertToHistogram(predicate);
			}
			return this.cacheable.convertToHistogram(predicate, minEntity, maxEntity);
		}
	}

	/**
	 * Resolved pair of boundary PKs (plus the entity type they resolve against). The {@link #NONE}
	 * sentinel is used when no resolution was attempted or produced — for example, when the
	 * descriptor points at a reference-level attribute, when the referenced entity type is external,
	 * or when the global attribute index has no records equal to the boundary value inside the
	 * current group.
	 */
	private record BoundaryPks(
		@Nullable String entityType,
		@Nullable Integer minPk,
		@Nullable Integer maxPk
	) {
		static final BoundaryPks NONE = new BoundaryPks(null, null, null);
	}

	/**
	 * Batch-fetches boundary entities grouped by `(entityType, entityFetch)` in a single call per
	 * tuple. Avoids N×2 roundtrips when many groups share the same fetch shape.
	 *
	 * @param byTypeThenPk Nested lookup — outer key is entity type, inner key is referenced entity
	 *                     PK. The entity-fetch requirement (if any) from the first histogram to
	 *                     register this PK wins; when later histograms request the same PK with a
	 *                     different richness, they see the first one — matches the "duplicate
	 *                     request must have identical parameters" contract enforced at translator
	 *                     time.
	 */
	private record BoundaryEntityCache(
		@Nonnull Map<String, Map<Integer, SealedEntity>> byTypeThenPk
	) {

		/**
		 * Collects every unique `(entityType, entityFetch, pk)` tuple from {@code pending}, issues one
		 * {@code fetchEntities} call per `(entityType, entityFetch)` tuple, and returns a populated
		 * cache. When a histogram carries no explicit entity fetch, a plain
		 * {@link EntityFetch#EntityFetch() empty-body fetch} is used so the response still carries a
		 * {@link SealedEntity} (the DTO slot is typed on {@code SealedEntity}, not
		 * {@link EntityClassifier}).
		 */
		@Nonnull
		static BoundaryEntityCache prefetch(
			@Nonnull List<PendingHistogram> pending,
			@Nonnull QueryExecutionContext context
		) {
			// collect (entityType, entityFetchKey) → RoaringBitmapWriter of PKs; entityFetchKey is
			// the EntityFetch reference (or a sentinel) so identical fetches collapse to one call —
			// pre-size to `pending.size()` so the fabrication hot path never rehashes
			final Map<FetchTuple, RoaringBitmapWriter<PersistentRoaringBitmap>> pksByTuple =
				createLinkedHashMap(pending.size());
			// defensive tracker: detects the contract violation where the same (entityType, pk) is
			// registered under two different EntityFetch references — would otherwise cause the
			// second fetch to clobber the first entry in the output map and potentially under-fetch
			final Map<String, Map<Integer, FetchTuple>> firstTupleByTypeAndPk = createHashMap(pending.size());
			for (final PendingHistogram ph : pending) {
				final BoundaryPks bounds = ph.boundaryPks();
				if (bounds.entityType() == null) {
					continue;
				}
				final FetchTuple tuple = new FetchTuple(bounds.entityType(), ph.request().entityFetch());
				final Map<Integer, FetchTuple> seenForType = firstTupleByTypeAndPk
					.computeIfAbsent(tuple.entityType(), k -> new HashMap<>());
				if (bounds.minPk() != null) {
					assertSameFetchTuple(seenForType, tuple, bounds.minPk());
					pksByTuple.computeIfAbsent(tuple, k -> RoaringBitmapBackedBitmap.buildWriter())
						.add(bounds.minPk());
				}
				if (bounds.maxPk() != null
					&& (bounds.minPk() == null || bounds.maxPk().intValue() != bounds.minPk().intValue())) {
					assertSameFetchTuple(seenForType, tuple, bounds.maxPk());
					pksByTuple.computeIfAbsent(tuple, k -> RoaringBitmapBackedBitmap.buildWriter())
						.add(bounds.maxPk());
				}
			}

			if (pksByTuple.isEmpty()) {
				return new BoundaryEntityCache(Map.of());
			}

			// outer size bounded by distinct entity types (≤ pksByTuple.size()), not entity count
			final Map<String, Map<Integer, SealedEntity>> out =
				createLinkedHashMap(Math.min(pksByTuple.size(), 64));
			for (final Entry<FetchTuple, RoaringBitmapWriter<PersistentRoaringBitmap>> entry : pksByTuple.entrySet()) {
				final FetchTuple tuple = entry.getKey();
				final int[] pks = entry.getValue().get().toArray();
				if (pks.length == 0) {
					continue;
				}
				final EntityFetch fetch = tuple.entityFetch() != null
					? tuple.entityFetch() : new EntityFetch();
				final List<SealedEntity> fetched = context.fetchEntities(
					tuple.entityType(), pks, fetch
				);
				final Map<Integer, SealedEntity> byPk = out.computeIfAbsent(
					tuple.entityType(),
					k -> new HashMap<>(Math.min(pks.length << 1, 256))
				);
				for (final SealedEntity entity : fetched) {
					byPk.put(entity.getPrimaryKeyOrThrowException(), entity);
				}
			}
			return new BoundaryEntityCache(out);
		}

		/**
		 * Looks up an entity previously fetched for the given `(entityType, pk)` pair. Returns
		 * {@code null} when either input is `null` (no boundary was resolved) or the fetch never
		 * produced an entity for this PK (should not happen — fetch is synchronous and the PK came
		 * from a FilterIndex lookup).
		 */
		@Nullable
		SealedEntity resolve(@Nullable String entityType, @Nullable Integer pk) {
			if (entityType == null || pk == null) {
				return null;
			}
			final Map<Integer, SealedEntity> byPk = this.byTypeThenPk.get(entityType);
			return byPk == null ? null : byPk.get(pk);
		}

		/**
		 * Defensive guard: enforces the translator-level contract that within a single reference summary
		 * fabrication the same `(entityType, pk)` pair must never appear under two different
		 * {@link FetchTuple}s. Since {@code FetchTuple} uses reference-identity semantics on
		 * {@link EntityFetch}, a collision means the same boundary PK was registered twice with
		 * structurally distinct fetch requirements — which would silently clobber one fetch's entity
		 * under the other in {@link #byTypeThenPk} and potentially under-fetch. Throws immediately so
		 * the contract breach surfaces at the first violating histogram rather than as mystery data loss.
		 */
		private static void assertSameFetchTuple(
			@Nonnull Map<Integer, FetchTuple> seenForType,
			@Nonnull FetchTuple tuple,
			int pk
		) {
			final FetchTuple prior = seenForType.putIfAbsent(pk, tuple);
			if (prior != null && prior.entityFetch() != tuple.entityFetch()) {
				throw new GenericEvitaInternalError(
					"Boundary PK " + pk + " for entity type `" + tuple.entityType() +
						"` is registered under two distinct EntityFetch instances — the histogram " +
						"translator contract requires identical entityFetch references for duplicate " +
						"registrations."
				);
			}
		}

		/**
		 * Identity of an entity-fetch round-trip. Record-generated `equals`/`hashCode` delegate to
		 * {@link Objects#equals} which in turn calls `EntityFetch.equals` — and
		 * {@link EntityFetch} does not override that, so the inherited {@link Object#equals(Object)}
		 * provides identity-based semantics. This is exactly what we want: the translator enforces
		 * "duplicate histogram registrations must share the same entityFetch reference", so identical
		 * references collapse to a single tuple while structurally-equal but distinct instances stay
		 * separate — avoiding silent conflation of two fetch requirements that happen to look alike.
		 *
		 * @param entityType  target entity type — required
		 * @param entityFetch user-supplied entity fetch, or `null` when the query did not specify one
		 */
		private record FetchTuple(@Nonnull String entityType, @Nullable EntityFetch entityFetch) {
		}
	}

}
