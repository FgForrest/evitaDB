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
import io.evitadb.core.query.filter.translator.reference.HavingTranslatorHelper;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.GroupHistogramEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PeekableIntIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;
import io.evitadb.utils.Functions;

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
 * - for grouped references, a histogram is computed from the {@link GroupHistogramEntityIndex} of a group - the one
 * store holding the histograms of the group as a whole, however many {@link ReducedGroupEntityIndex} instances the
 * group spans when the reference has representative attributes - and attached to the matching
 * {@link ReferenceGroupStatistics} entry identified by the group PK;
 * - for ungrouped references, a single histogram is computed from the
 * {@link ReferencedTypeEntityIndex} and attached to the (existing or newly-injected) non-grouped
 * entry (the entry with {@code groupEntity == null});
 * - histogram-only groups — references with no facets but with histogram data — get a synthetic
 * {@link ReferenceGroupStatistics} entry so the relaxed group filter keeps them in the result;
 * - boundary entity resolution (`minReferencedEntity` / `maxReferencedEntity`) runs in two passes —
 * first computes histograms and extracts candidate PKs, then batch-fetches the distinct PKs by
 * `(entityType, entityFetch)` tuple and attaches them to the final {@link HistogramContract} via the
 * `convertToHistogram` overload - each histogram receives the entities fetched with its own `entityFetch`, also when
 * a histogram asking differently is anchored at the same entity. The candidates of a
 * {@link HistogramValueSource#REFERENCED_ENTITY_ATTRIBUTE} histogram come from the referenced entity's own
 * {@link FilterIndex}, those of a {@link HistogramValueSource#REFERENCE_ATTRIBUTE} histogram from the
 * reference attribute's {@link FilterIndex} of each index the references live in. Either way they are
 * confined to the referenced entities of the boundary context: the type index of an ungrouped reference, or
 * every reduced index of the group of a grouped one - the reference carrying the smallest or the largest
 * value of the group may sit in any of them. The candidates of a grouped histogram are further confined to the
 * referenced entities a reduced index of the group tracks for an owner the histogram counts at the bound, so an
 * entity whose references belong only to owners the query or the histogram's condition leaves out anchors nothing -
 * unless no candidate of the whole boundary context passes that check, as at a bound the default of the histogram's
 * value expression gives a counted owner through a reference or a referenced entity no filter index holds. Such a
 * bound keeps every candidate, so the entity anchoring it may be one no owner the histogram counts references. So
 * may the entity of a bound whose pick among the checked candidates cannot be loaded: the bound then takes the
 * entity picked among every candidate, if that one loads, so a histogram has boundary entities wherever the pick
 * among every candidate gives it loadable ones.
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
			final ReferenceSchemaContract referenceSchema = requests.getFirst().referenceSchema();
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
	 * sharing an entity fetch shape pay only one round-trip to the entity collection, while each histogram
	 * receives its boundary entities in the shape its own entity fetch asks for;
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

		// Stage 1: compute cacheable histograms + resolve boundary PKs (no fetch yet). Group stores and RTEIs
		// live on the entity collection, located per scope via QueryPlanningContext#getEntityIndex.
		// Sizing: non-grouped = req × scope (floor); grouped grows by one store per group — probe the
		// RTEI's group count and clamp to a sanity cap against pathological group counts.
		final int pendingEstimate;
		if (grouped) {
			// long, so neither the sum over scopes nor its product with the request count can overflow
			long groupsProbe = 0;
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
			pendingEstimate = (int) Math.min(
				requests.size() * Math.max(scopes.size(), groupsProbe), PENDING_SANITY_CAP
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
		if (!grouped) {
			// an ungrouped histogram is read from the type index of the entity component, which a scope indexed
			// without that component never builds - the histogram would silently be missing from the result
			final EntitySchemaContract entitySchema = context.getQueryContext().getSchema();
			for (final Scope scope : scopes) {
				HavingTranslatorHelper.assertEntityComponentIndexed(entitySchema, referenceSchema, scope, scopes);
			}
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
	 * Collects a pending histogram per group tracked by the reference's {@code REFERENCED_GROUP_ENTITY_TYPE} index in
	 * the given scope, read from the {@link GroupHistogramEntityIndex} of the group. A group without a store holds no
	 * histogram value and yields nothing.
	 *
	 * The reduced indexes of a group serve as the boundary context of the histogram: the owners of a group spread over
	 * several reduced indexes when the reference has representative attributes, and the reference carrying the
	 * smallest or the largest value may sit in any of them. The store itself tracks no reference, so it is never part
	 * of the context.
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
			final GroupHistogramEntityIndex store = context.getQueryContext()
				.getEntityIndex(
					entityType, GroupHistogramEntityIndex.keyOf(referenceName, groupPk, scope),
					GroupHistogramEntityIndex.class
				)
				.orElse(null);
			if (store == null) {
				// no value of the group was ever admitted, or the last one left - nothing to compute
				continue;
			}
			final int[] rgeiPks = rtei.getAllReferenceIndexes(groupPk);
			final ReducedGroupEntityIndex[] groupIndexes = new ReducedGroupEntityIndex[rgeiPks.length];
			for (int i = 0; i < rgeiPks.length; i++) {
				groupIndexes[i] = context.getQueryContext().getReducedGroupEntityIndexByPrimaryKey(rgeiPks[i]);
			}
			computePendingHistogram(
				resolved, store, groupIndexes, groupPk, attributeHistogramBaselineFormula, context, facetSorter
			).ifPresent(pending::add);
		}
	}

	/**
	 * Collects a pending histogram from the non-grouped {@link ReferencedTypeEntityIndex}
	 * (key type {@link EntityIndexType#REFERENCED_ENTITY_TYPE}). Uses the sentinel group PK `0`
	 * so the downstream merge slots the histogram into the non-grouped DTO position. The type index
	 * tracks every reference of the reference name in the scope, so it is its own boundary context.
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
		computePendingHistogram(
			resolved, rtei, new EntityIndex[]{rtei}, 0, attributeHistogramBaselineFormula, context, facetSorter
		).ifPresent(pending::add);
	}

	/**
	 * Computes a single cacheable histogram from {@code sourceIndex} and resolves its candidate min/max
	 * PKs over {@code boundaryContext} (see {@link #resolveBoundaryPks}). Returns {@link Optional#empty()}
	 * when the histogram resolves to {@link CacheableHistogramContract#EMPTY} or when the source
	 * attribute cannot be located.
	 *
	 * The histogram and its boundaries are read from different indexes: the histogram from the one index
	 * holding it, the boundaries from every index tracking the references the histogram covers, since the
	 * reference carrying a boundary value is indexed only where its owner sits.
	 *
	 * Boundary PK resolution uses the histogram's raw min/max — the keys exactly as stored in the
	 * attribute's {@link FilterIndex}. When either is {@code null} (legacy cache entry
	 * or an EMPTY histogram) boundary resolution is skipped, and the histogram is emitted without
	 * boundary entities.
	 *
	 * For a {@link GroupHistogramEntityIndex} the owners the histogram counts at a bound - read from its filter index
	 * once the bound has a candidate, see {@link #countedOwners} - confine the candidates to the referenced entities of
	 * those owners, and a bound none of whose candidates passes keeps them all (see {@link #resolveBoundaryPks}). A
	 * {@link ReferencedTypeEntityIndex} maps a referenced entity to the primary keys of the reduced indexes of its
	 * references, not to the owners referencing it, so the candidates of an ungrouped histogram are confined only to
	 * the referenced entities the type index tracks.
	 *
	 * @param resolved                          the pre-resolved histogram request
	 * @param sourceIndex                       the index holding the histogram
	 * @param boundaryContext                   the indexes tracking the references the histogram covers, in which
	 *                                          the boundary PKs are resolved: {@code sourceIndex} itself for an
	 *                                          ungrouped reference, the reduced indexes of the group for a grouped
	 *                                          one
	 * @param groupPk                           group primary key; `0` sentinel means the non-grouped slot
	 * @param attributeHistogramBaselineFormula baseline filter formula the histogram is computed against
	 * @param context                           the query execution context
	 * @param facetSorter                       the reference's sorter deciding ties among boundary PKs, or
	 *                                          {@code null} for the lowest-PK rule
	 * @return the pending histogram, or empty when there is nothing to emit
	 */
	@Nonnull
	private static Optional<PendingHistogram> computePendingHistogram(
		@Nonnull ResolvedRequest resolved,
		@Nonnull EntityIndex sourceIndex,
		@Nonnull EntityIndex[] boundaryContext,
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
		if (rawMin == null || rawMax == null) {
			return Optional.of(new PendingHistogram(groupPk, req, cacheable, BoundaryPks.NONE));
		}
		final FilterIndex ownerFilterIndex = sourceIndex instanceof GroupHistogramEntityIndex ? filterIndex : null;
		final BoundaryPks boundaryPks = resolveBoundaryPks(
			req, sourceIndex.getIndexKey().scope(), boundaryContext, resolved.attributeSchema(),
			rawMin, rawMax, ownerFilterIndex, attributeHistogramBaselineFormula, context, facetSorter
		);
		return Optional.of(new PendingHistogram(groupPk, req, cacheable, boundaryPks));
	}

	/**
	 * Returns the owners the histogram of a group counts at `value`: the owners its filter index holds at the value -
	 * the group store indexes only the values the histogram's condition admits - narrowed by the records of the
	 * baseline formula exactly as {@link AttributeHistogramComputer} narrows the buckets of the histogram. The cached
	 * histogram keeps only its raw bounds, not the owners of its extreme buckets, so the owners are read again from
	 * the filter index.
	 *
	 * @param histogramFilterIndex the filter index of the group store the histogram is computed from, keyed on owner
	 *                             primary keys
	 * @param value                the raw bound of the histogram
	 * @param baselineFormula      the baseline formula the histogram is computed against, or `null` when no formula
	 *                             narrows the histogram
	 * @return the primary keys of the owners the histogram counts at the value
	 */
	@Nonnull
	private static Bitmap countedOwners(
		@Nonnull FilterIndex histogramFilterIndex,
		@Nonnull Serializable value,
		@Nullable Formula baselineFormula
	) {
		final Bitmap owners = resolveBoundaryCandidates(histogramFilterIndex, value);
		if (baselineFormula == null || owners.isEmpty()) {
			return owners;
		}
		// formulas memoize their result, so the baseline is computed at most once for all histograms of the query
		return new BaseBitmap(
			PersistentRoaringBitmap.and(
				RoaringBitmapBackedBitmap.getRoaringBitmap(owners),
				RoaringBitmapBackedBitmap.getRoaringBitmap(baselineFormula.compute())
			)
		);
	}

	/**
	 * Looks up the histogram-backed {@link FilterIndex} on the appropriate index subtype: the
	 * {@link GroupHistogramEntityIndex} of a group for a grouped reference, the {@link ReferencedTypeEntityIndex} for
	 * an ungrouped one. Any other subtype is a programming error and must surface immediately.
	 */
	@Nullable
	private static FilterIndex histogramFilterIndexFor(
		@Nonnull EntityIndex sourceIndex,
		@Nonnull HistogramRequest req
	) {
		if (sourceIndex instanceof GroupHistogramEntityIndex store) {
			return store.getHistogramFilterIndex(req.histogramName(), req.locale());
		}
		if (sourceIndex instanceof ReferencedTypeEntityIndex rtei) {
			return rtei.getHistogramFilterIndex(req.histogramName(), req.locale());
		}
		throw new GenericEvitaInternalError(
			"Unexpected EntityIndex subtype `" + sourceIndex.getClass().getName() + "` passed to " +
				"histogramFilterIndexFor — only GroupHistogramEntityIndex and ReferencedTypeEntityIndex " +
				"expose a histogram filter index."
		);
	}

	/**
	 * Delegates the actual histogram math to {@link AttributeHistogramComputer}. Builds a one-off
	 * {@link AttributeHistogramRequest} wrapping the single {@link FilterIndex} backing this
	 * (reference, group, histogram) tuple and routes the computation through
	 * {@link QueryExecutionContext#analyse(CacheableEvitaResponseExtraResultComputer)} so the result
	 * participates in the shared extra-result cache. The {@link FilterIndex#getId()} identifies
	 * each group store / RTEI histogram index uniquely at the process level, so cache keys collide neither
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
			req.bucketCount(),
			req.behavior(),
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
	 * {@link FilterIndex} of each index of the boundary context ({@link ReducedGroupEntityIndex} /
	 * {@link ReferencedTypeEntityIndex}). Both kinds key the reference-attribute FilterIndex on their own
	 * record ids (the recordId is swapped during insert via
	 * `ReferenceIndexMutator#executeWithDifferentPrimaryKeyToIndex`), so `getRecordsEqualTo(value)` of each
	 * index is mapped back to referenced entity PKs via that same index's
	 * `getReferencedPrimaryKeysForIndexPks(...)` before the indexes' answers are joined. Returns
	 * {@link BoundaryPks#NONE} when no index of the context has a FilterIndex of the attribute (the
	 * attribute is not filterable, or no reference of the context carries it).
	 *
	 * The candidate set is intersected with the referenced PKs tracked by the boundary context - the type
	 * index of an ungrouped reference, every reduced index of the group of a grouped one - so out-of-scope
	 * entities do not surface as boundary anchors. With the owners of a bound, a candidate further stays only
	 * when a reduced index of the context tracks it for one of them. That check decides per bound over the
	 * candidates of the whole context: when no candidate of a bound passes it, the bound keeps every candidate of
	 * the context and picks among them by the same rule, so the entity anchoring such a bound may be one no owner the
	 * histogram counts references. When candidates pass it, the pick among every candidate rides along as the
	 * alternative the bound takes when the entity of its pick cannot be loaded (see {@link BoundaryPick}).
	 *
	 * The {@code minValue} / {@code maxValue} parameters are the histogram's raw bounds, the keys of its filter index -
	 * a `BigDecimal` value is keyed as an `Integer` scaled by the indexed decimal places of the source attribute. They
	 * are passed to {@link FilterIndex#getRecordsEqualTo(Serializable)} verbatim: a filter index of the source
	 * attribute keys its values in the same form, and its normalizer lets an already normalized value through.
	 *
	 * @param req              the histogram request
	 * @param scope            the scope of the indexes the histogram is read from
	 * @param boundaryContext  the indexes tracking the references the histogram covers
	 * @param attributeSchema  schema of the attribute the histogram is computed over
	 * @param minValue         the histogram's raw smallest value
	 * @param maxValue         the histogram's raw largest value
	 * @param ownerFilterIndex the filter index of the group store the histogram is computed from, which tells the
	 *                         owners the histogram counts at a bound (see {@link #countedOwners}), or `null` for a
	 *                         boundary context of the type index, whose candidates are not confined by owner
	 * @param baselineFormula  the baseline formula the histogram is computed against, or `null` for none
	 * @param context          the query execution context
	 * @param facetSorter      the reference's sorter deciding ties among boundary PKs, or {@code null} for the
	 *                         lowest-PK rule
	 * @return the resolved boundary PKs, {@link BoundaryPks#NONE} when nothing can be resolved
	 */
	@Nonnull
	private static BoundaryPks resolveBoundaryPks(
		@Nonnull HistogramRequest req,
		@Nonnull Scope scope,
		@Nonnull EntityIndex[] boundaryContext,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Serializable minValue,
		@Nonnull Serializable maxValue,
		@Nullable FilterIndex ownerFilterIndex,
		@Nullable Formula baselineFormula,
		@Nonnull QueryExecutionContext context,
		@Nullable NestedContextSorter facetSorter
	) {
		final HistogramValueSource source = req.valueDescriptor().source();
		if (source == HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE) {
			return resolveBoundaryPksFromReferencedEntity(
				req, scope, boundaryContext, attributeSchema, minValue, maxValue, ownerFilterIndex,
				baselineFormula, context, facetSorter
			);
		}
		if (source == HistogramValueSource.REFERENCE_ATTRIBUTE) {
			return resolveBoundaryPksFromReferenceAttribute(
				req, boundaryContext, attributeSchema, minValue, maxValue, ownerFilterIndex,
				baselineFormula, facetSorter
			);
		}
		throw new GenericEvitaInternalError(
			"Unexpected histogram value source: " + source
		);
	}

	/**
	 * Resolves min/max referenced PKs when the histogram source is a referenced-entity attribute.
	 * Looks up the referenced entity collection's own global {@link FilterIndex} and intersects with
	 * the referenced PKs tracked by the boundary context - the union over its indexes - of which a candidate
	 * stays, with the owners of the bound, only when some reduced index of the context tracks it for one of them.
	 *
	 * A referenced entity without the attribute gives the histogram the default of its value expression, yet the global
	 * filter index does not hold it, so it is never a candidate, and the owners the histogram counts at a bound may
	 * reference no candidate at all. Such a bound keeps every candidate of the context (see {@link #pickBoundaryPk}),
	 * so the entity anchoring it may be one no owner the histogram counts references.
	 *
	 * @param req              the histogram request
	 * @param scope            the scope of the indexes the histogram is read from
	 * @param boundaryContext  the indexes tracking the references the histogram covers
	 * @param attributeSchema  schema of the referenced entity's attribute the histogram is computed over
	 * @param minValue         the histogram's raw smallest value
	 * @param maxValue         the histogram's raw largest value
	 * @param ownerFilterIndex the filter index of the group store telling the owners the histogram counts at a bound,
	 *                         or `null` to keep every candidate
	 * @param baselineFormula  the baseline formula the histogram is computed against, or `null` for none
	 * @param context          the query execution context
	 * @param facetSorter      the reference's sorter deciding ties among boundary PKs, or {@code null} for the
	 *                         lowest-PK rule
	 * @return the resolved boundary PKs, {@link BoundaryPks#NONE} when nothing can be resolved
	 */
	@Nonnull
	private static BoundaryPks resolveBoundaryPksFromReferencedEntity(
		@Nonnull HistogramRequest req,
		@Nonnull Scope scope,
		@Nonnull EntityIndex[] boundaryContext,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Serializable minValue,
		@Nonnull Serializable maxValue,
		@Nullable FilterIndex ownerFilterIndex,
		@Nullable Formula baselineFormula,
		@Nonnull QueryExecutionContext context,
		@Nullable NestedContextSorter facetSorter
	) {
		final String sourceEntityType = req.valueDescriptor().sourceEntityType();
		if (sourceEntityType == null) {
			return BoundaryPks.NONE;
		}
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
		final Bitmap inGroup = referencedPrimaryKeys(boundaryContext);
		if (inGroup.isEmpty()) {
			return BoundaryPks.NONE;
		}
		final BoundaryPick min = pickBoundaryPk(
			attributeFilterIndex, minValue, inGroup, boundaryContext, ownerFilterIndex, baselineFormula, facetSorter
		);
		final BoundaryPick max;
		if (Objects.equals(maxValue, minValue)) {
			max = min;
		} else {
			max = pickBoundaryPk(
				attributeFilterIndex, maxValue, inGroup, boundaryContext, ownerFilterIndex, baselineFormula, facetSorter
			);
		}
		return new BoundaryPks(sourceEntityType, min, max);
	}

	/**
	 * Resolves min/max referenced PKs when the histogram source is a reference-level attribute
	 * (an attribute that lives on the reference edge). Uses the reference attribute's own
	 * {@link FilterIndex} of each index of the boundary context — keyed on the index's own record ids on
	 * both RGEI and RTEI — and maps each result back to the referenced entity PK via that index's
	 * `getReferencedPrimaryKeysForIndexPks(...)` method.
	 *
	 * An index of the context without a FilterIndex of the attribute holds no reference carrying it and is
	 * skipped. When no index of the context has one - the attribute is not marked `.filterable()` on the
	 * reference schema, or no reference of the context carries it - no boundary entities can be produced.
	 *
	 * The referenced entity type is taken from the reference schema; boundary fetch uses that type
	 * against the entity-fetch infrastructure later in the pipeline.
	 *
	 * A reference without the attribute gives the histogram the default of its value expression, yet no filter index
	 * holds such a reference, so the owners the histogram counts at a bound may reference no candidate there. Such a
	 * bound keeps every candidate of the context (see {@link #pickBoundaryPkFromReferenceAttribute}), so the entity
	 * anchoring it may be one no owner the histogram counts references.
	 *
	 * @param req              the histogram request
	 * @param boundaryContext  the indexes tracking the references the histogram covers
	 * @param attributeSchema  schema of the reference attribute the histogram is computed over
	 * @param minValue         the histogram's raw smallest value
	 * @param maxValue         the histogram's raw largest value
	 * @param ownerFilterIndex the filter index of the group store telling the owners the histogram counts at a bound,
	 *                         or `null` to keep every candidate
	 * @param baselineFormula  the baseline formula the histogram is computed against, or `null` for none
	 * @param facetSorter      the reference's sorter deciding ties among boundary PKs, or {@code null} for the
	 *                         lowest-PK rule
	 * @return the resolved boundary PKs, {@link BoundaryPks#NONE} when nothing can be resolved
	 */
	@Nonnull
	private static BoundaryPks resolveBoundaryPksFromReferenceAttribute(
		@Nonnull HistogramRequest req,
		@Nonnull EntityIndex[] boundaryContext,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Serializable minValue,
		@Nonnull Serializable maxValue,
		@Nullable FilterIndex ownerFilterIndex,
		@Nullable Formula baselineFormula,
		@Nullable NestedContextSorter facetSorter
	) {
		final String referencedEntityType = req.referenceSchema().getReferencedEntityType();
		// resolved once for both boundary values; the n-th filter index belongs to the n-th index of the context
		final FilterIndex[] referenceAttributeFilterIndexes = new FilterIndex[boundaryContext.length];
		boolean anyFilterIndex = false;
		for (int i = 0; i < boundaryContext.length; i++) {
			final FilterIndex filterIndex = boundaryContext[i].getFilterIndex(
				req.referenceSchema(), attributeSchema, req.locale()
			);
			referenceAttributeFilterIndexes[i] = filterIndex;
			anyFilterIndex = anyFilterIndex || filterIndex != null;
		}
		if (!anyFilterIndex) {
			return BoundaryPks.NONE;
		}
		final Bitmap inScope = referencedPrimaryKeys(boundaryContext);
		if (inScope.isEmpty()) {
			return BoundaryPks.NONE;
		}
		final BoundaryPick min = pickBoundaryPkFromReferenceAttribute(
			referenceAttributeFilterIndexes, boundaryContext, minValue, ownerFilterIndex, baselineFormula, inScope,
			facetSorter
		);
		final BoundaryPick max;
		if (Objects.equals(maxValue, minValue)) {
			max = min;
		} else {
			max = pickBoundaryPkFromReferenceAttribute(
				referenceAttributeFilterIndexes, boundaryContext, maxValue, ownerFilterIndex, baselineFormula, inScope,
				facetSorter
			);
		}
		return new BoundaryPks(referencedEntityType, min, max);
	}

	/**
	 * Returns the set of referenced entity PKs tracked by the boundary context — the union of
	 * {@link #referencedPrimaryKeys(EntityIndex)} over its indexes. A context of a single index answers with
	 * that index's own bitmap, so the common case of one index per group pays for no union.
	 *
	 * @param boundaryContext the indexes tracking the references a histogram covers
	 * @return the referenced entity PKs of all indexes of the context, may be empty
	 */
	@Nonnull
	private static Bitmap referencedPrimaryKeys(@Nonnull EntityIndex[] boundaryContext) {
		Bitmap result = EmptyBitmap.INSTANCE;
		for (final EntityIndex index : boundaryContext) {
			result = union(result, referencedPrimaryKeys(index));
		}
		return result;
	}

	/**
	 * Returns the union of two bitmaps. When either of them is empty the other one is returned as it is, so
	 * joining a single non-empty bitmap allocates nothing.
	 *
	 * @param first  the first bitmap
	 * @param second the second bitmap
	 * @return the union of both bitmaps
	 */
	@Nonnull
	private static Bitmap union(@Nonnull Bitmap first, @Nonnull Bitmap second) {
		if (first.isEmpty()) {
			return second;
		}
		if (second.isEmpty()) {
			return first;
		}
		return new BaseBitmap(
			PersistentRoaringBitmap.or(
				RoaringBitmapBackedBitmap.getRoaringBitmap(first), RoaringBitmapBackedBitmap.getRoaringBitmap(second)
			)
		);
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
	 * Resolves a single boundary PK for a REFERENCE_ATTRIBUTE histogram from the reference attribute's own
	 * {@link FilterIndex} of each index of the boundary context:
	 *
	 * 1. looks up the records holding the value in the filter index of the index,
	 * 2. maps them to referenced entity PKs via the same index's `getReferencedPrimaryKeysForIndexPks(...)`,
	 * 3. with {@code ownerFilterIndex}, sets apart those an owner the histogram counts at the value references (see
	 *    {@link #referencedByCountedOwners}) in the same index, where the reference carrying the value sits,
	 * 4. joins the set-apart PKs of all indexes, intersects them with the referenced PKs tracked by the context, and
	 *    picks the final PK via the same sorter/lowest-PK rule as {@link #pickBoundaryPk},
	 * 5. when that leaves no PK, or without {@code ownerFilterIndex}, does the same with the referenced PKs of the
	 *    second step of all indexes joined.
	 *
	 * The fifth step depends on the outcome over the whole context, never on a single index: a candidate an index
	 * produced without an owner the histogram counts never competes with a candidate of another index that has one.
	 * It is decided on the candidate primary keys, before any entity is loaded, so a pick of the fourth step may be an
	 * entity the query cannot load - one that does not exist, lies outside the requested scopes or lacks the query's
	 * locale. Whenever the fourth step picks, the pick of the fifth step over every candidate is therefore carried
	 * along as the alternative the bound falls back to when the entity of the fourth step does not load (see
	 * {@link BoundaryPick}); it reuses the candidates of the second step and reads no index again.
	 *
	 * The steps run as written with a sorter, or without {@code ownerFilterIndex}. Without a sorter and with
	 * {@code ownerFilterIndex}, {@link #pickLowestOwnedBoundaryPk} makes the identical pick, alternative included,
	 * by merging producing-index iterators in unsigned PK order, without owned subsets or candidate unions. Equal PKs
	 * are checked in index-position order. A sorter keeps the steps as written, because the order it gives the
	 * set-apart PKs need not be their order among every candidate sorted - a limited segment or a seeded shuffle
	 * depends on what else it sorts. The method is package-private so that tests can compare its pick with an
	 * independent exhaustive implementation.
	 *
	 * The mapping of the second step differs by the kind of index: the type index keys its filter index on its own
	 * reduced-index PKs, which only it can translate, while a reduced group index keys it on referenced entity PKs
	 * and the mapping keeps those it tracks. The owners are read from the group store at the first index holding a
	 * candidate, so a value no reference of the context carries reads none - and a referenced entity whose only
	 * references at the value are given the default is no candidate.
	 *
	 * The {@code value} is the histogram's raw bound in the key form of its filter index, which the filter index of
	 * the reference attribute shares, so it is passed to the lookup verbatim.
	 *
	 * @param referenceAttributeFilterIndexes the reference attribute's filter index of each index of the
	 *                                        context, at the same position; {@code null} where the index has none
	 * @param boundaryContext                 the indexes tracking the references the histogram covers
	 * @param value                           the boundary value
	 * @param ownerFilterIndex                the filter index of the group store telling the owners the histogram
	 *                                        counts at the value, or {@code null} to keep every candidate
	 * @param baselineFormula                 the baseline formula the histogram is computed against, or `null` for
	 *                                        none
	 * @param inScope                         the referenced entity PKs tracked by the context
	 * @param facetSorter                     the reference's sorter deciding ties, or {@code null} for the
	 *                                        lowest-PK rule
	 * @return the pick of the bound, {@link BoundaryPick#NONE} when no reference of the context carries the value
	 */
	@Nonnull
	static BoundaryPick pickBoundaryPkFromReferenceAttribute(
		@Nonnull FilterIndex[] referenceAttributeFilterIndexes,
		@Nonnull EntityIndex[] boundaryContext,
		@Nonnull Serializable value,
		@Nullable FilterIndex ownerFilterIndex,
		@Nullable Formula baselineFormula,
		@Nonnull Bitmap inScope,
		@Nullable NestedContextSorter facetSorter
	) {
		if (facetSorter == null && ownerFilterIndex != null) {
			return pickLowestOwnedBoundaryPk(
				referenceAttributeFilterIndexes, boundaryContext, value, ownerFilterIndex, baselineFormula, inScope
			);
		}

		// read at the first index holding a candidate, so a bound without one reads no owners
		PersistentRoaringBitmap ownersRoaring = null;
		Bitmap referencedPkCandidates = EmptyBitmap.INSTANCE;
		Bitmap ownedPkCandidates = EmptyBitmap.INSTANCE;
		for (int i = 0; i < boundaryContext.length; i++) {
			final FilterIndex referenceAttributeFilterIndex = referenceAttributeFilterIndexes[i];
			if (referenceAttributeFilterIndex == null) {
				continue;
			}
			final Bitmap indexPkCandidates = resolveBoundaryCandidates(referenceAttributeFilterIndex, value);
			if (!indexPkCandidates.isEmpty()) {
				final Bitmap referencedPks = referencedPrimaryKeysForIndexPks(boundaryContext[i], indexPkCandidates);
				referencedPkCandidates = union(referencedPkCandidates, referencedPks);
				if (ownerFilterIndex != null) {
					if (ownersRoaring == null) {
						ownersRoaring = RoaringBitmapBackedBitmap.getRoaringBitmap(
							countedOwners(ownerFilterIndex, value, baselineFormula)
						);
					}
					// each index answers for its own owners: a candidate is checked in the index whose filter index
					// produced it, where the reference carrying the value sits
					ownedPkCandidates = union(
						ownedPkCandidates, referencedByCountedOwners(referencedPks, boundaryContext[i], ownersRoaring)
					);
				}
			}
		}
		if (ownersRoaring != null) {
			final Integer ownedPk = intersectAndPickBoundaryPk(ownedPkCandidates, inScope, facetSorter);
			if (ownedPk != null) {
				// the owned candidates are a subset of every candidate: equal sizes mean equal sets, so an equal pick
				return ownedPkCandidates.size() == referencedPkCandidates.size() ?
					BoundaryPick.of(ownedPk) :
					BoundaryPick.of(ownedPk, intersectAndPickBoundaryPk(referencedPkCandidates, inScope, facetSorter));
			}
			// no candidate of the whole context passes the owner check, so the bound keeps every candidate
		}
		return BoundaryPick.of(intersectAndPickBoundaryPk(referencedPkCandidates, inScope, facetSorter));
	}

	/**
	 * Makes the pick of {@link #pickBoundaryPkFromReferenceAttribute} for a context confined to the owners
	 * `ownerFilterIndex` counts, without a sorter: the lowest candidate in scope that a counted owner references in an
	 * index whose filter index produced it, with the lowest candidate in scope as its alternative - or that lowest
	 * candidate alone when no candidate has such an owner.
	 *
	 * The pick is the one the steps of {@link #pickBoundaryPkFromReferenceAttribute} make with the lowest-PK rule, but
	 * a primitive-array heap merges the producing indexes' candidate iterators in unsigned PK order, the order
	 * {@code first()} uses, with ties broken by original index position. Each popped candidate is checked for scope
	 * before its owner predicate is evaluated in its producing index only. The first candidate in scope supplies the
	 * alternative; the first passing owner predicate supplies the owned minimum and ends the merge. No candidate
	 * unions or owned subsets are built, and failed producers of the same PK remain independent.
	 *
	 * @param referenceAttributeFilterIndexes the reference attribute's filter index of each index of the
	 *                                        context, at the same position; {@code null} where the index has none
	 * @param boundaryContext                 the indexes tracking the references the histogram covers
	 * @param value                           the boundary value
	 * @param ownerFilterIndex                the filter index of the group store telling the owners the histogram
	 *                                        counts at the value
	 * @param baselineFormula                 the baseline formula the histogram is computed against, or `null` for
	 *                                        none
	 * @param inScope                         the referenced entity PKs tracked by the context
	 * @return the pick of the bound, {@link BoundaryPick#NONE} when no reference of the context carries the value or
	 *         no candidate is in scope
	 */
	@Nonnull
	private static BoundaryPick pickLowestOwnedBoundaryPk(
		@Nonnull FilterIndex[] referenceAttributeFilterIndexes,
		@Nonnull EntityIndex[] boundaryContext,
		@Nonnull Serializable value,
		@Nonnull FilterIndex ownerFilterIndex,
		@Nullable Formula baselineFormula,
		@Nonnull Bitmap inScope
	) {
		final PeekableIntIterator[] iterators = new PeekableIntIterator[boundaryContext.length];
		final int[] heapPks = new int[boundaryContext.length];
		final int[] heapIndexes = new int[boundaryContext.length];
		int heapSize = 0;
		PersistentRoaringBitmap ownersRoaring = null;
		for (int i = 0; i < boundaryContext.length; i++) {
			final FilterIndex referenceAttributeFilterIndex = referenceAttributeFilterIndexes[i];
			if (referenceAttributeFilterIndex == null) {
				continue;
			}
			final Bitmap indexPkCandidates = resolveBoundaryCandidates(referenceAttributeFilterIndex, value);
			if (!indexPkCandidates.isEmpty()) {
				final Bitmap referencedPks =
					referencedPrimaryKeysForIndexPks(boundaryContext[i], indexPkCandidates);
				if (!referencedPks.isEmpty()) {
					final PeekableIntIterator iterator =
						RoaringBitmapBackedBitmap.getRoaringBitmap(referencedPks).getIntIterator();
					iterators[i] = iterator;
					heapPks[heapSize] = iterator.next();
					heapIndexes[heapSize++] = i;
				}
				// Read at the first nonempty raw lookup, even if mapping or scope removes every candidate.
				if (ownersRoaring == null) {
					ownersRoaring = RoaringBitmapBackedBitmap.getRoaringBitmap(
						countedOwners(ownerFilterIndex, value, baselineFormula)
					);
				}
			}
		}
		// Every live producer contributes exactly one head; build the unsigned, index-stable minimum heap.
		for (int i = (heapSize >>> 1) - 1; i >= 0; i--) {
			siftBoundaryCandidateDown(heapPks, heapIndexes, heapSize, i);
		}
		boolean hasCandidateInScope = false;
		int unrestrictedPk = 0;
		while (heapSize > 0) {
			final int referencedPk = heapPks[0];
			final int producingIndex = heapIndexes[0];
			if (inScope.contains(referencedPk)) {
				if (!hasCandidateInScope) {
					unrestrictedPk = referencedPk;
					hasCandidateInScope = true;
				}
				// ownersRoaring is never null here: every head was pushed in the branch that read the owners
				if (isReferencedByCountedOwner(boundaryContext[producingIndex], referencedPk, ownersRoaring)) {
					return referencedPk == unrestrictedPk ?
						BoundaryPick.of(referencedPk) : BoundaryPick.of(referencedPk, unrestrictedPk);
				}
			}
			// Advance only the popped producer; another producer of this PK still gets its own owner check.
			final PeekableIntIterator iterator = iterators[producingIndex];
			if (iterator.hasNext()) {
				heapPks[0] = iterator.next();
			} else {
				heapPks[0] = heapPks[--heapSize];
				heapIndexes[0] = heapIndexes[heapSize];
			}
			if (heapSize > 0) {
				siftBoundaryCandidateDown(heapPks, heapIndexes, heapSize, 0);
			}
		}
		// No scoped producer passed; only the first scoped candidate can supply the global fallback.
		return hasCandidateInScope ? BoundaryPick.of(unrestrictedPk) : BoundaryPick.NONE;
	}

	/**
	 * Restores the minimum-heap invariant after replacing one head, ordering unsigned PKs then index positions.
	 * Parallel primitive arrays retain producer provenance without allocating a pair for each candidate.
	 *
	 * @param pks     the candidate PKs at heap positions
	 * @param indexes the producing context positions at the same heap positions
	 * @param size    the number of live heads
	 * @param root    the replaced head, whose children already root valid heaps
	 */
	private static void siftBoundaryCandidateDown(
		@Nonnull int[] pks, @Nonnull int[] indexes, int size, int root
	) {
		final int pk = pks[root];
		final int index = indexes[root];
		int position = root;
		while (position < (size >>> 1)) {
			int child = (position << 1) + 1;
			final int right = child + 1;
			if (right < size && boundaryCandidatePrecedes(pks[right], indexes[right], pks[child], indexes[child])) {
				child = right;
			}
			if (!boundaryCandidatePrecedes(pks[child], indexes[child], pk, index)) {
				break;
			}
			pks[position] = pks[child];
			indexes[position] = indexes[child];
			position = child;
		}
		pks[position] = pk;
		indexes[position] = index;
	}

	/** Returns whether the first producing pair precedes the second in unsigned PK and index-position order. */
	private static boolean boundaryCandidatePrecedes(int pk, int index, int otherPk, int otherIndex) {
		final int comparison = Integer.compareUnsigned(pk, otherPk);
		return comparison < 0 || (comparison == 0 && index < otherIndex);
	}

	/**
	 * Returns the candidates the reduced index `index` tracks for at least one of `owners`, see
	 * {@link #isReferencedByCountedOwner(EntityIndex, int, PersistentRoaringBitmap)}.
	 *
	 * @param candidates the referenced entity PKs of the records the reference-attribute filter index of `index` holds
	 *                   at the bound
	 * @param index      the reduced index whose filter index produced the candidates
	 * @param owners     the owners the histogram counts at the bound
	 * @return the candidates referenced by one of `owners` in `index`, may be empty
	 */
	@Nonnull
	private static Bitmap referencedByCountedOwners(
		@Nonnull Bitmap candidates,
		@Nonnull EntityIndex index,
		@Nonnull PersistentRoaringBitmap owners
	) {
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		final PrimitiveIterator.OfInt it = candidates.iterator();
		while (it.hasNext()) {
			final int referencedPk = it.nextInt();
			if (isReferencedByCountedOwner(index, referencedPk, owners)) {
				writer.add(referencedPk);
			}
		}
		final PersistentRoaringBitmap result = writer.get();
		return result.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(result);
	}

	/**
	 * Returns TRUE when one of the reduced indexes of the group tracks the referenced entity `referencedPk` for at
	 * least one of `owners`, checking them one by one with
	 * {@link #isReferencedByCountedOwner(EntityIndex, int, PersistentRoaringBitmap)}.
	 *
	 * @param indexes      the reduced indexes of the group
	 * @param referencedPk the primary key of the candidate referenced entity
	 * @param owners       the owners the histogram counts at the bound
	 * @return TRUE when one of `owners` references the candidate in one of the indexes
	 * @throws GenericEvitaInternalError when an index is not a {@link ReducedGroupEntityIndex}
	 */
	private static boolean isReferencedByCountedOwnerInAnyIndex(
		@Nonnull EntityIndex[] indexes,
		int referencedPk,
		@Nonnull PersistentRoaringBitmap owners
	) {
		for (final EntityIndex index : indexes) {
			if (isReferencedByCountedOwner(index, referencedPk, owners)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns TRUE when the reduced index `index` of the group tracks the referenced entity `referencedPk` for at
	 * least one of `owners`, the owners the histogram counts at a bound.
	 *
	 * The candidate is matched against every owner referencing it in the index, not against the reference carrying
	 * the bound, so the confinement is approximate: the candidate passes whenever an owner the histogram counts at
	 * the bound references it in the index - through a reference with another value, or through one the histogram's
	 * condition rejects - even when the reference carrying the bound there belongs to an owner the histogram does
	 * not count, or is rejected by the condition.
	 *
	 * @param index        the reduced index of the group, the only kind of index a group histogram's boundary
	 *                     context holds
	 * @param referencedPk the primary key of the candidate referenced entity
	 * @param owners       the owners the histogram counts at the bound
	 * @return TRUE when one of `owners` references the candidate in `index`
	 * @throws GenericEvitaInternalError when `index` is not a {@link ReducedGroupEntityIndex}, which cannot map a
	 *                                   referenced entity to its owners
	 */
	private static boolean isReferencedByCountedOwner(
		@Nonnull EntityIndex index,
		int referencedPk,
		@Nonnull PersistentRoaringBitmap owners
	) {
		if (!(index instanceof ReducedGroupEntityIndex groupIndex)) {
			throw new GenericEvitaInternalError(
				"Unexpected EntityIndex subtype `" + index.getClass().getName() + "` in the boundary context of a " +
					"group histogram — only a ReducedGroupEntityIndex maps a referenced entity to its owners."
			);
		}
		final Bitmap referencingOwners = groupIndex.getOwnerPKsForReferencedEntity(referencedPk);
		return referencingOwners != null && PersistentRoaringBitmap.intersects(
			RoaringBitmapBackedBitmap.getRoaringBitmap(referencingOwners), owners
		);
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
	 * The {@code value} is the histogram's raw bound in the key form of its filter index, which the global filter
	 * index of the attribute shares, so it is passed to the lookup verbatim.
	 *
	 * With {@code ownerFilterIndex} a candidate stays only when some reduced index of {@code boundaryContext} tracks
	 * it for an owner the histogram counts at the value (see {@link #countedOwners}), read once a candidate exists.
	 * The value is an attribute of the referenced entity, so every reference to the candidate carries it; the
	 * candidate passes even when the reference of a counted owner to it is one the histogram's condition rejects.
	 * When no candidate of the group passes, every candidate of the group stays and the pick runs among them. This
	 * fallback is decided on the candidate primary keys, before any entity is loaded, so a pick among the passing
	 * candidates may be an entity the query cannot load, such as one lacking the query's locale. Whenever the passing
	 * candidates give the pick, the pick among every candidate of the group is therefore carried along as the
	 * alternative the bound falls back to when the entity of the pick does not load (see {@link BoundaryPick}); it
	 * reuses the candidates of the group and reads no index again.
	 *
	 * @param attributeFilterIndex the referenced entity collection's global filter index of the attribute
	 * @param value                the boundary value
	 * @param inGroup              the referenced entity PKs tracked by the boundary context
	 * @param boundaryContext      the indexes tracking the references the histogram covers
	 * @param ownerFilterIndex     the filter index of the group store telling the owners the histogram counts at the
	 *                             value, or {@code null} to keep every candidate
	 * @param baselineFormula      the baseline formula the histogram is computed against, or `null` for none
	 * @param facetSorter          the reference's sorter deciding ties, or {@code null} for the lowest-PK rule
	 * @return the pick of the bound, {@link BoundaryPick#NONE} when no candidate qualifies
	 */
	@Nonnull
	private static BoundaryPick pickBoundaryPk(
		@Nonnull FilterIndex attributeFilterIndex,
		@Nonnull Serializable value,
		@Nonnull Bitmap inGroup,
		@Nonnull EntityIndex[] boundaryContext,
		@Nullable FilterIndex ownerFilterIndex,
		@Nullable Formula baselineFormula,
		@Nullable NestedContextSorter facetSorter
	) {
		final Bitmap candidates = resolveBoundaryCandidates(attributeFilterIndex, value);
		if (ownerFilterIndex == null || candidates.isEmpty()) {
			return BoundaryPick.of(intersectAndPickBoundaryPk(candidates, inGroup, facetSorter));
		}
		// confined to the group first, so the owner lookups are bounded by the referenced entities of the group
		final PersistentRoaringBitmap candidatesInGroup = PersistentRoaringBitmap.and(
			RoaringBitmapBackedBitmap.getRoaringBitmap(candidates), RoaringBitmapBackedBitmap.getRoaringBitmap(inGroup)
		);
		if (candidatesInGroup.isEmpty()) {
			// nothing to pick even among every candidate, so the owners are not read
			return BoundaryPick.NONE;
		}
		final PersistentRoaringBitmap ownersRoaring = RoaringBitmapBackedBitmap.getRoaringBitmap(
			countedOwners(ownerFilterIndex, value, baselineFormula)
		);
		final PeekableIntIterator it = candidatesInGroup.getIntIterator();
		if (facetSorter == null) {
			// the lowest qualifying PK wins and the candidates ascend, so the scan stops at the first qualifying one
			while (it.hasNext()) {
				final int referencedPk = it.next();
				if (isReferencedByCountedOwnerInAnyIndex(boundaryContext, referencedPk, ownersRoaring)) {
					// the lowest candidate of the group is the pick among every candidate
					return BoundaryPick.of(referencedPk, candidatesInGroup.first());
				}
			}
			// no candidate of the group passes the owner check, so the bound keeps every candidate
			return BoundaryPick.of(pickBoundaryPkAmong(candidatesInGroup, null));
		}
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		while (it.hasNext()) {
			final int referencedPk = it.next();
			if (isReferencedByCountedOwnerInAnyIndex(boundaryContext, referencedPk, ownersRoaring)) {
				writer.add(referencedPk);
			}
		}
		final PersistentRoaringBitmap ownedCandidates = writer.get();
		if (ownedCandidates.isEmpty()) {
			// with no candidate of the group passing the owner check, the bound keeps every candidate
			return BoundaryPick.of(pickBoundaryPkAmong(candidatesInGroup, facetSorter));
		}
		final int ownedPk = pickBoundaryPkAmongNonEmpty(ownedCandidates, facetSorter);
		// every candidate of the group passing means one set, so the pick among every candidate is the same one
		return ownedCandidates.getLongCardinality() == candidatesInGroup.getLongCardinality() ?
			BoundaryPick.of(ownedPk) :
			BoundaryPick.of(ownedPk, pickBoundaryPkAmong(candidatesInGroup, facetSorter));
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
		return pickBoundaryPkAmong(PersistentRoaringBitmap.and(candidatesRoaring, inScopeRoaring), facetSorter);
	}

	/**
	 * Picks a single PK of the final candidates — via the reference's configured sorter when available (honouring
	 * the user's `orderBy`), or the lowest-valued PK otherwise.
	 *
	 * @param candidates  the final candidate referenced entity PKs
	 * @param facetSorter the reference's sorter deciding ties, or {@code null} for the lowest-PK rule
	 * @return the boundary PK, or {@code null} when there is no candidate
	 */
	@Nullable
	private static Integer pickBoundaryPkAmong(
		@Nonnull PersistentRoaringBitmap candidates,
		@Nullable NestedContextSorter facetSorter
	) {
		return candidates.isEmpty() ? null : pickBoundaryPkAmongNonEmpty(candidates, facetSorter);
	}

	/**
	 * Picks a single PK of final candidates the caller knows to be non-empty — via the reference's configured sorter
	 * when available (honouring the user's `orderBy`), or the lowest-valued PK otherwise.
	 *
	 * @param candidates  the final candidate referenced entity PKs, at least one
	 * @param facetSorter the reference's sorter deciding ties, or {@code null} for the lowest-PK rule
	 * @return the boundary PK
	 */
	private static int pickBoundaryPkAmongNonEmpty(
		@Nonnull PersistentRoaringBitmap candidates,
		@Nullable NestedContextSorter facetSorter
	) {
		// short-circuit when only one PK survives the intersection — the sorter cannot reorder a
		// single element, so skipping the allocation + sortAndSlice call is a straight win
		if (candidates.getLongCardinality() == 1L) {
			return candidates.first();
		}
		if (facetSorter != null) {
			// honours the reference's configured orderBy; PERFORMANCE: sortAndSlice sorts the full
			// intersection and we keep only [0] — a bounded `sortAndSlice(Bitmap, int limit)` overload
			// would avoid the waste on large groups. Bounded to ≤ 4× per pending histogram: a pick and its
			// alternative for each of the two bounds.
			final int[] sorted = facetSorter.sortAndSlice(new BaseBitmap(candidates));
			if (sorted.length > 0) {
				return sorted[0];
			}
		}
		return candidates.first();
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
		 * Returns the tuple the boundary entities of this histogram are fetched and looked up under: the entity type
		 * of its boundaries and the `entityFetch` of its request.
		 *
		 * @return the tuple, or `null` when no boundary entity type was resolved
		 */
		@Nullable
		FetchTuple fetchTuple() {
			final String entityType = this.boundaryPks.entityType();
			return entityType == null ? null : new FetchTuple(entityType, this.request.entityFetch());
		}

		/**
		 * Converts the cacheable form to the final {@link HistogramContract} attaching the resolved
		 * boundary entities (if any) - the ones fetched with the `entityFetch` of this histogram's own request, so
		 * they carry the same content as when the histogram is requested alone. Each bound takes the entity of its
		 * pick, or the entity of its alternative when the pick's entity did not load (see
		 * {@link BoundaryPick#resolve}). When only one side can be resolved both are dropped — the
		 * {@link io.evitadb.api.requestResponse.extraResult.Histogram} DTO enforces "both or neither".
		 *
		 * @param entityCache the boundary entities fetched for every histogram of the reference
		 * @return the histogram DTO
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
			final FetchTuple fetchTuple = fetchTuple();
			if (fetchTuple == null) {
				return this.cacheable.convertToHistogram(predicate);
			}
			// one lookup per histogram - the tuple hashes its entity fetch by content, which is not cached
			final Map<Integer, SealedEntity> fetched = entityCache.resolve(fetchTuple);
			final SealedEntity minEntity = this.boundaryPks.min().resolve(fetched);
			final SealedEntity maxEntity = this.boundaryPks.max().resolve(fetched);
			if (minEntity == null || maxEntity == null) {
				return this.cacheable.convertToHistogram(predicate);
			}
			return this.cacheable.convertToHistogram(predicate, minEntity, maxEntity);
		}
	}

	/**
	 * Resolved pair of boundary picks (plus the entity type they resolve against). The {@link #NONE}
	 * sentinel is used when no resolution was attempted or produced — for example, when the histogram has no raw
	 * bounds, when the referenced entity collection has no global index in the scope, when no index holds a filter
	 * index of the attribute, or when the boundary context tracks no referenced entity. A single bound no candidate
	 * qualifies for holds {@link BoundaryPick#NONE} next to a resolved entity type instead. When the smallest and the
	 * largest value are equal, both bounds hold the same pick.
	 *
	 * @param entityType the type of the referenced entities, or `null` when nothing was resolved
	 * @param min        the pick of the bound of the smallest value
	 * @param max        the pick of the bound of the largest value
	 */
	private record BoundaryPks(
		@Nullable String entityType,
		@Nonnull BoundaryPick min,
		@Nonnull BoundaryPick max
	) {
		static final BoundaryPks NONE = new BoundaryPks(null, BoundaryPick.NONE, BoundaryPick.NONE);
	}

	/**
	 * The pick of one bound of a histogram: the referenced entity anchoring it and, for a bound of a group histogram
	 * picked among the candidates an owner the histogram counts at the bound references, the referenced entity picked
	 * by the same sorter or lowest-PK rule among every candidate of the bound when that one differs. Both are picked
	 * on primary keys before any entity is loaded, and both are fetched with the `entityFetch` of the histogram; the
	 * bound takes the entity of `pk` when it loads and the entity of `alternativePk` otherwise, so the bound has an
	 * entity whenever the pick among every candidate - the only pick a context without owner confinement makes - has
	 * one.
	 *
	 * @param pk            the referenced entity anchoring the bound, or `null` when no candidate qualifies
	 * @param alternativePk the referenced entity picked among every candidate of the bound, or `null` when it is `pk`
	 *                      or the bound was picked among every candidate in the first place
	 */
	record BoundaryPick(@Nullable Integer pk, @Nullable Integer alternativePk) {
		/**
		 * The pick of a bound no candidate qualifies for.
		 */
		static final BoundaryPick NONE = new BoundaryPick(null, null);

		/**
		 * Returns the pick of a bound without an alternative.
		 *
		 * @param pk the referenced entity anchoring the bound, or `null` when no candidate qualifies
		 * @return the pick
		 */
		@Nonnull
		static BoundaryPick of(@Nullable Integer pk) {
			return pk == null ? NONE : new BoundaryPick(pk, null);
		}

		/**
		 * Returns the pick of a bound picked among the candidates of counted owners, with the pick among every
		 * candidate as its alternative unless the two coincide.
		 *
		 * @param pk            the pick among the candidates of counted owners
		 * @param alternativePk the pick among every candidate of the bound
		 * @return the pick
		 */
		@Nonnull
		static BoundaryPick of(int pk, @Nullable Integer alternativePk) {
			return new BoundaryPick(pk, alternativePk == null || alternativePk == pk ? null : alternativePk);
		}

		/**
		 * Registers the primary keys this pick needs fetched.
		 *
		 * @param pks the writer collecting the primary keys of one fetch tuple
		 */
		void registerTo(@Nonnull RoaringBitmapWriter<PersistentRoaringBitmap> pks) {
			if (this.pk != null) {
				pks.add(this.pk);
			}
			if (this.alternativePk != null) {
				pks.add(this.alternativePk);
			}
		}

		/**
		 * Returns the entity anchoring the bound: the entity of {@link #pk()} when it was fetched, the entity of
		 * {@link #alternativePk()} when only that one was.
		 *
		 * @param fetched the entities fetched under the fetch tuple of the histogram, by their primary keys
		 * @return the entity, or `null` when neither pick loaded
		 */
		@Nullable
		SealedEntity resolve(@Nonnull Map<Integer, SealedEntity> fetched) {
			if (this.pk == null) {
				return null;
			}
			final SealedEntity entity = fetched.get(this.pk);
			return entity == null && this.alternativePk != null ? fetched.get(this.alternativePk) : entity;
		}
	}

	/**
	 * The key a boundary entity is fetched and looked up under: the entity type and the `entityFetch` a histogram
	 * asks for its boundary entities with. The record's `equals` and `hashCode` compare the `entityFetch` by content,
	 * because an {@link EntityFetch} inherits the structural `equals` and `hashCode` of `BaseConstraint` and
	 * `ConstraintContainer` - its name, arguments and children. So histograms whose requirements are equal share one
	 * fetch, whether they hold one requirement instance or several, while histograms whose requirements differ are
	 * fetched and resolved apart.
	 *
	 * @param entityType  type of the boundary entities
	 * @param entityFetch the entity fetch of the histogram request, or `null` when the query specified none
	 */
	private record FetchTuple(@Nonnull String entityType, @Nullable EntityFetch entityFetch) {
	}

	/**
	 * Boundary entities of the histograms of one reference, batch-fetched in a single call per distinct
	 * {@link FetchTuple}, which avoids N×2 roundtrips when many groups share the same fetch shape. A histogram
	 * looks its boundary entities up under its own tuple, so histograms anchored at the same entity while asking for
	 * it with different `entityFetch` requirements each receive the entity fetched with their own requirement, and
	 * histograms asking alike share one instance of it.
	 *
	 * @param byTupleThenPk nested lookup — outer key is the tuple the entities were fetched under, inner key is the
	 *                      referenced entity PK
	 */
	private record BoundaryEntityCache(
		@Nonnull Map<FetchTuple, Map<Integer, SealedEntity>> byTupleThenPk
	) {

		/**
		 * Collects the boundary PKs of every histogram in {@code pending} under its {@link FetchTuple}, issues one
		 * {@code fetchEntities} call per distinct tuple, and returns a populated cache. The PKs of a histogram are the
		 * picks of both its bounds and their alternatives (see {@link BoundaryPick}), so an alternative is fetched in
		 * the same call and with the same entity fetch as the pick it stands in for. When a histogram carries no
		 * explicit entity fetch, a plain {@link EntityFetch#EntityFetch() empty-body fetch} is used so the response
		 * still carries a {@link SealedEntity} (the DTO slot is typed on {@code SealedEntity}, not
		 * {@link EntityClassifier}).
		 *
		 * @param pending the pending histograms of one reference with their resolved boundary PKs
		 * @param context the execution context the entities are fetched through
		 * @return the cache of the fetched boundary entities
		 */
		@Nonnull
		static BoundaryEntityCache prefetch(
			@Nonnull List<PendingHistogram> pending,
			@Nonnull QueryExecutionContext context
		) {
			// collect (entityType, entityFetch) → RoaringBitmapWriter of PKs; equal fetches collapse to one call —
			// pre-size to `pending.size()` so the fabrication hot path never rehashes
			final Map<FetchTuple, RoaringBitmapWriter<PersistentRoaringBitmap>> pksByTuple =
				createLinkedHashMap(pending.size());
			for (final PendingHistogram ph : pending) {
				final BoundaryPks bounds = ph.boundaryPks();
				final FetchTuple tuple = ph.fetchTuple();
				// an alternative exists only next to a pick, so a histogram with neither bound picked needs no fetch
				if (tuple == null || (bounds.min().pk() == null && bounds.max().pk() == null)) {
					continue;
				}
				final RoaringBitmapWriter<PersistentRoaringBitmap> pks =
					pksByTuple.computeIfAbsent(tuple, k -> RoaringBitmapBackedBitmap.buildWriter());
				// a PK registered twice is stored once, so coinciding picks of the two bounds need no check
				bounds.min().registerTo(pks);
				if (bounds.max() != bounds.min()) {
					bounds.max().registerTo(pks);
				}
			}

			if (pksByTuple.isEmpty()) {
				return new BoundaryEntityCache(Map.of());
			}

			final Map<FetchTuple, Map<Integer, SealedEntity>> out = createHashMap(pksByTuple.size());
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
				final Map<Integer, SealedEntity> byPk = createHashMap(fetched.size());
				for (final SealedEntity entity : fetched) {
					byPk.put(entity.getPrimaryKeyOrThrowException(), entity);
				}
				out.put(tuple, byPk);
			}
			return new BoundaryEntityCache(out);
		}

		/**
		 * Returns the entities fetched under `tuple`, keyed by their PKs. A PK is missing when no histogram resolved it
		 * as a boundary under the tuple, or when the fetch found no entity for it - a reference may point at an entity
		 * the query cannot load.
		 *
		 * @param tuple the entity type and the entity fetch a histogram asks for its boundary entities with
		 * @return the fetched entities by their PKs, empty when nothing was fetched under the tuple
		 */
		@Nonnull
		Map<Integer, SealedEntity> resolve(@Nonnull FetchTuple tuple) {
			final Map<Integer, SealedEntity> byPk = this.byTupleThenPk.get(tuple);
			return byPk == null ? Map.of() : byPk;
		}
	}

}
