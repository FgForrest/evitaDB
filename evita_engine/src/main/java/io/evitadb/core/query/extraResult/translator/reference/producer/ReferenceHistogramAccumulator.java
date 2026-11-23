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

import com.carrotsearch.hppc.IntHashSet;
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
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.filter.AttributeRangeCarrierFormula;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.translator.common.RangeCarrierGroup;
import io.evitadb.core.query.extraResult.translator.common.UserFilterRelaxer;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramComputer;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramProducer.AttributeHistogramRequest;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.HistogramGroupFetcher;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.HistogramRequest;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.RegisteredHistogram;
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
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitionSplit;
import io.evitadb.index.ReferencePartitions;
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
 * - for grouped references, a histogram combines the {@link GroupHistogramEntityIndex} stores of a group in the
 * requested scopes and is attached to the matching {@link ReferenceGroupStatistics} entry;
 * - for ungrouped references, a single histogram combines the {@link ReferencedTypeEntityIndex} instances of the
 * requested scopes and is attached to the non-grouped entry (the entry with {@code groupEntity == null});
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
 * confined to the referenced entities of the boundary context: the type index of an ungrouped reference in each
 * contributing scope, or every reduced index of the group of a grouped one in each contributing scope - the reference
 * carrying the smallest or the largest value of the group may sit in any of them. Preference goes to candidates
 * tracked by a reduced group index or a reference partition for an owner the histogram counts at the bound.
 * An entity whose references belong only to owners the query or the histogram's condition leaves out
 * anchors nothing - unless no candidate of the whole boundary context passes that check, as at a bound the default of
 * the histogram's value expression gives a counted owner through a reference or a referenced entity no filter index
 * holds. Such a bound keeps every candidate, so the entity anchoring it may be one no owner the histogram counts
 * references. So may the entity of a bound whose pick among the checked candidates cannot be loaded: the bound then
 * takes the entity picked among every candidate, if that one loads, so a histogram has boundary entities wherever the
 * pick among every candidate gives it loadable ones.
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
	 * @param <T> concrete group statistics returned by the adapter
	 * @return statistics with histograms attached, including histogram-only groups
	 */
	@Nonnull
	static <T extends ReferenceGroupStatistics> Map<String, Collection<T>> injectHistograms(
		@Nonnull Map<String, Collection<T>> statisticsByReferenceName,
		@Nonnull Map<String, List<RegisteredHistogram>> histogramRequestsByReference,
		@Nullable Formula attributeHistogramBaselineFormula,
		@Nonnull QueryExecutionContext context,
		@Nonnull ReferenceSummaryResultAdapter<T> resultAdapter
	) {
		final Map<String, Collection<T>> result = createLinkedHashMap(statisticsByReferenceName.size());
		result.putAll(statisticsByReferenceName);

		for (final Map.Entry<String, List<RegisteredHistogram>> entry : histogramRequestsByReference.entrySet()) {
			final String referenceName = entry.getKey();
			final List<RegisteredHistogram> requests = entry.getValue();
			if (requests.isEmpty()) {
				continue;
			}
			final ReferenceSchemaContract referenceSchema = requests.getFirst().request().referenceSchema();
			final Collection<T> existing = result.getOrDefault(referenceName, List.of());
			final Collection<T> rebuilt = computeForReference(
				referenceSchema, requests, existing, attributeHistogramBaselineFormula, context, resultAdapter
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
	 * (group, histogramName) tuple — together with candidate min/max PKs resolved from the filter indexes of
	 * the histogram's source attribute. One histogram combines the filter indexes of every scope of its request,
	 * so a group held in several scopes still yields a single histogram;
	 * 2. batch-fetch the distinct boundary PKs, keyed by `(entityType, entityFetch)`, so histograms
	 * sharing an entity fetch shape pay only one round-trip to the entity collection, while each histogram
	 * receives its boundary entities in the shape its own entity fetch asks for;
	 * 3. emit final {@link HistogramContract} DTOs — either re-creating an existing DTO with its
	 * histogram map attached or synthesizing a new DTO for histogram-only groups.
	 *
	 * @param referenceSchema                   the reference whose histograms are computed
	 * @param requests                          the histogram requests registered for the reference
	 * @param existing                          the statistics the facet phase produced for the reference
	 * @param attributeHistogramBaselineFormula baseline formula selecting owners for histogram computation, or
	 *                                          `null` when every record passes
	 * @param context                           the query execution context
	 * @param resultAdapter                     creates the statistics of histogram-only groups
	 * @param <T>                               concrete group statistics returned by the adapter
	 * @return the statistics of the reference with the histograms attached, or `existing` itself when no histogram
	 *         has data
	 */
	@Nonnull
	private static <T extends ReferenceGroupStatistics> Collection<T> computeForReference(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull List<RegisteredHistogram> requests,
		@Nonnull Collection<T> existing,
		@Nullable Formula attributeHistogramBaselineFormula,
		@Nonnull QueryExecutionContext context,
		@Nonnull ReferenceSummaryResultAdapter<T> resultAdapter
	) {
		final boolean grouped = referenceSchema.getReferencedGroupType() != null
			&& referenceSchema.isReferencedGroupTypeManaged();
		final String referenceName = referenceSchema.getName();
		final String entityType = Objects.requireNonNull(context.getQueryContext().getEntityType());
		final BoundaryScopeOrder scopeOrder = new BoundaryScopeOrder(
			context.getEvitaRequest().getScopesAsArray(), context.getQueryContext(),
			referenceSchema.getReferencedEntityType()
		);

		// Stage 1: compute one cacheable histogram per request and group. Probe the requested scopes for
		// a capacity estimate before collecting the contributing indexes.
		final Set<Scope> requestedScopes = EnumSet.noneOf(Scope.class);
		for (final RegisteredHistogram registration : requests) {
			requestedScopes.addAll(registration.request().scopes());
		}
		final int pendingEstimate;
		if (grouped) {
			// long, so neither the sum over scopes nor its product with the request count can overflow
			long groupsProbe = 0;
			for (final Scope scope : requestedScopes) {
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
				requests.size() * groupsProbe, PENDING_SANITY_CAP
			);
		} else {
			pendingEstimate = requests.size();
		}
		final List<PendingHistogram> pending = new ArrayList<>(pendingEstimate);
		final Map<String, HistogramGroupFetcher> groupFetchers = grouped ? createHashMap(requests.size()) : Map.of();
		for (final RegisteredHistogram registration : requests) {
			final HistogramRequest req = registration.request();
			final NestedContextSorter facetSorter = registration.facetSorter();
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
			final ResolvedRequest resolved = new ResolvedRequest(req, attributeSchema, comparator);
			if (grouped) {
				if (registration.groupFetcher() != null) {
					groupFetchers.put(req.histogramName(), registration.groupFetcher());
				}
				computeGroupedHistograms(
					resolved, entityType, referenceName, attributeHistogramBaselineFormula,
					context, facetSorter, registration.groupPredicate(), scopeOrder, pending
				);
			} else {
				final Set<Scope> scopes = req.scopes();
				final EntitySchemaContract entitySchema = context.getQueryContext().getSchema();
				final List<HistogramSource> sources = new ArrayList<>(scopes.size());
				for (final Scope scope : scopes) {
					// The ungrouped source is the reference's entity type index.
					HavingTranslatorHelper.assertEntityComponentIndexed(entitySchema, referenceSchema, scope, scopes);
					collectNonGroupedSource(req, entityType, referenceName, scope, context, sources);
				}
				if (!sources.isEmpty()) {
					computePendingHistogram(
						resolved, sources, HistogramRequest.NON_GROUPED_SENTINEL,
						attributeHistogramBaselineFormula, context, facetSorter, scopeOrder
					).ifPresent(pending::add);
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
			referenceSchema, existing, histogramsByGroupKey, grouped, resultAdapter, groupFetchers, context
		);
	}

	/**
	 * Computes each group's histogram in the order of its first contributing scope and that scope's group PKs.
	 * The sources of a group held in several scopes follow the iteration order of the request's scopes.
	 * Only the current group's histogram and boundary indexes are held in the request's reusable source list.
	 *
	 * When a {@code groupPredicate} is supplied (the caller attached a `filterGroupBy` to the
	 * enclosing `referenceSummaryOfReference`), group PKs that fail the predicate are skipped — the
	 * same group selection the facet path applies, so the histogram path never emits histograms for
	 * groups the caller did not request. A {@code null} predicate means "no group filtering".
	 *
	 * @param resolved                          the pre-resolved histogram request
	 * @param entityType                        owner entity type
	 * @param referenceName                     reference being summarized
	 * @param attributeHistogramBaselineFormula baseline formula selecting owners for histogram computation
	 * @param context                           query execution context
	 * @param facetSorter                       the reference's sorter deciding ties among boundary PKs
	 * @param groupPredicate                    optional group selection
	 * @param scopeOrder                        preference for the referenced entities' scopes
	 * @param pending                           computed histograms awaiting boundary entity loading
	 */
	private static void computeGroupedHistograms(
		@Nonnull ResolvedRequest resolved,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nullable Formula attributeHistogramBaselineFormula,
		@Nonnull QueryExecutionContext context,
		@Nullable NestedContextSorter facetSorter,
		@Nullable IntPredicate groupPredicate,
		@Nonnull BoundaryScopeOrder scopeOrder,
		@Nonnull List<PendingHistogram> pending
	) {
		final HistogramRequest request = resolved.request();
		final List<GroupedScope> groupScopes = new ArrayList<>(request.scopes().size());
		for (final Scope scope : request.scopes()) {
			final EntityIndexKey rteiKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, scope, referenceName
			);
			final ReferencedTypeEntityIndex rtei = context.getQueryContext()
				.getEntityIndex(entityType, rteiKey, ReferencedTypeEntityIndex.class)
				.orElse(null);
			if (rtei != null) {
				final Bitmap groupPks = rtei.getAllReferencedPrimaryKeys();
				if (!groupPks.isEmpty()) {
					groupScopes.add(new GroupedScope(scope, rtei, groupPks));
				}
			}
		}

		// a group held in several scopes is computed once, at the first scope that yields a source for it, together
		// with the sources of the later scopes; those later scopes must then skip it
		final IntHashSet processedSharedGroups = groupScopes.size() > 1 ? new IntHashSet() : null;
		final List<HistogramSource> sources = new ArrayList<>(groupScopes.size());
		for (int i = 0; i < groupScopes.size(); i++) {
			final GroupedScope groupScope = groupScopes.get(i);
			final PrimitiveIterator.OfInt groupPkIt = groupScope.groupPks().iterator();
			while (groupPkIt.hasNext()) {
				final int groupPk = groupPkIt.nextInt();
				// PK `0` keys the non-grouped slot in Stage 3 and in the requested ranges of a `histogramHaving`, so
				// a real group with that PK would silently swallow the grouped histogram
				if (groupPk == HistogramRequest.NON_GROUPED_SENTINEL) {
					throw new GenericEvitaInternalError(
						"Group primary key must be non-zero — PK `0` is reserved as the non-grouped " +
							"sentinel. Got: 0 for reference `" + referenceName + "`."
					);
				}
				if (groupPredicate != null && !groupPredicate.test(groupPk)) {
					continue;
				}
				if (processedSharedGroups != null && processedSharedGroups.contains(groupPk)) {
					continue;
				}
				final HistogramSource first = readGroupedSource(
					request, entityType, referenceName, groupPk, groupScope, context
				);
				if (first == null) {
					continue;
				}
				sources.add(first);
				if (processedSharedGroups != null) {
					// Later scopes add to this group's histogram in scope order.
					for (int j = i + 1; j < groupScopes.size(); j++) {
						final GroupedScope otherScope = groupScopes.get(j);
						if (otherScope.groupPks().contains(groupPk)) {
							processedSharedGroups.add(groupPk);
							final HistogramSource source = readGroupedSource(
								request, entityType, referenceName, groupPk, otherScope, context
							);
							if (source != null) {
								sources.add(source);
							}
						}
					}
				}
				computePendingHistogram(
					resolved, sources, groupPk, attributeHistogramBaselineFormula, context, facetSorter, scopeOrder
				).ifPresent(pending::add);
				sources.clear();
			}
		}
	}

	/**
	 * Reads a group's histogram values from its store. All reduced indexes of the group provide the boundary context
	 * because the reference carrying the smallest or largest value may sit in any of them.
	 *
	 * @param request       histogram request
	 * @param entityType    owner entity type
	 * @param referenceName reference being summarized
	 * @param groupPk       group primary key
	 * @param groupScope    scope and group type index
	 * @param context       query execution context
	 * @return the scope's source for the group, or `null` when the store has no index for the requested histogram
	 */
	@Nullable
	private static HistogramSource readGroupedSource(
		@Nonnull HistogramRequest request,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		int groupPk,
		@Nonnull GroupedScope groupScope,
		@Nonnull QueryExecutionContext context
	) {
		final Scope scope = groupScope.scope();
		final GroupHistogramEntityIndex store = context.getQueryContext()
			.getEntityIndex(
				entityType, GroupHistogramEntityIndex.keyOf(referenceName, groupPk, scope),
				GroupHistogramEntityIndex.class
			)
			.orElse(null);
		if (store == null) {
			return null;
		}
		final FilterIndex histogramIndex = store.getHistogramFilterIndex(request.histogramName(), request.locale());
		if (histogramIndex == null) {
			return null;
		}
		final int[] rgeiPks = groupScope.typeIndex().getAllReferenceIndexes(groupPk);
		final ReducedGroupEntityIndex[] groupIndexes = new ReducedGroupEntityIndex[rgeiPks.length];
		for (int i = 0; i < rgeiPks.length; i++) {
			groupIndexes[i] = context.getQueryContext().getReducedGroupEntityIndexByPrimaryKey(rgeiPks[i]);
		}
		return new HistogramSource(scope, histogramIndex, groupIndexes);
	}

	/**
	 * Collects the histogram and boundary indexes of the non-grouped {@link ReferencedTypeEntityIndex}
	 * (key type {@link EntityIndexType#REFERENCED_ENTITY_TYPE}). Uses the sentinel group PK `0`
	 * so the downstream merge slots the histogram into the non-grouped DTO position. The type index
	 * tracks every reference of the reference name in the scope, so it is its own boundary context.
	 *
	 * @param request        histogram request
	 * @param entityType     owner entity type
	 * @param referenceName  reference being summarized
	 * @param scope          scope whose reference index is read
	 * @param context        query execution context
	 * @param sources        histogram and boundary indexes collected for the non-grouped entry
	 */
	private static void collectNonGroupedSource(
		@Nonnull HistogramRequest request,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull QueryExecutionContext context,
		@Nonnull List<HistogramSource> sources
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
		final FilterIndex histogramIndex = rtei.getHistogramFilterIndex(request.histogramName(), request.locale());
		if (histogramIndex != null) {
			sources.add(new HistogramSource(scope, histogramIndex, new EntityIndex[]{rtei}));
		}
	}

	/**
	 * Computes one histogram for a group from all contributing scopes. Histogram values come from the filter
	 * indexes that store them. Boundary candidates come from the indexes tracking the references the histogram covers,
	 * since the reference carrying a boundary value is indexed where its owner sits. For grouped references this
	 * includes every reduced index of the group in each contributing scope.
	 *
	 * The raw bounds are the keys exactly as stored in the histogram's {@link FilterIndex}. If either bound is
	 * {@code null}, as with a legacy cache entry, the histogram is emitted without boundary entities.
	 * Histograms over references to unmanaged entity types also have no boundary entities.
	 *
	 * Histogram filter indexes identify the owners counted at the bound (see {@link #countedOwners}). A grouped
	 * histogram checks owners in the reduced group indexes. An ungrouped histogram checks owners
	 * in the reference partitions tracked by its type indexes (see {@link #resolveBoundaryPks}).
	 *
	 * @param resolved                          the pre-resolved histogram request
	 * @param sources                           histogram and boundary indexes from the contributing scopes, never empty
	 * @param groupPk                           group primary key or the non-grouped sentinel
	 * @param attributeHistogramBaselineFormula baseline formula selecting owners for histogram computation
	 * @param context                           the query execution context
	 * @param facetSorter                       the reference's sorter deciding ties among boundary PKs
	 * @param scopeOrder                        preference for the referenced entities' scopes
	 * @return the pending histogram, or empty when there is nothing to emit
	 */
	@Nonnull
	private static Optional<PendingHistogram> computePendingHistogram(
		@Nonnull ResolvedRequest resolved,
		@Nonnull List<HistogramSource> sources,
		int groupPk,
		@Nullable Formula attributeHistogramBaselineFormula,
		@Nonnull QueryExecutionContext context,
		@Nullable NestedContextSorter facetSorter,
		@Nonnull BoundaryScopeOrder scopeOrder
	) {
		final HistogramRequest req = resolved.request();
		final List<FilterIndex> filterIndexes = new ArrayList<>(sources.size());
		for (final HistogramSource source : sources) {
			filterIndexes.add(source.histogramIndex());
		}
		final CacheableHistogramContract cacheable = computeCacheable(
			resolved, filterIndexes, attributeHistogramBaselineFormula, context
		);
		// Skip the EMPTY sentinel to avoid a pending histogram, a boundary-prefetch slot and a synthetic group.
		if (cacheable == CacheableHistogramContract.EMPTY) {
			return Optional.empty();
		}
		final Serializable rawMin = cacheable.getRawMin();
		final Serializable rawMax = cacheable.getRawMax();
		if (!req.referenceSchema().isReferencedEntityTypeManaged() || rawMin == null || rawMax == null) {
			return Optional.of(new PendingHistogram(groupPk, req, cacheable, BoundaryPks.NONE));
		}
		final EntityIndex[] boundaryContext = joinBoundaryContexts(sources);
		final boolean ungrouped = groupPk == HistogramRequest.NON_GROUPED_SENTINEL;
		// Ungrouped histogram owners are tracked by the reference partitions.
		final QueryPlanningContext partitionContext = ungrouped ? context.getQueryContext() : null;
		final BoundaryPks boundaryPks = resolveBoundaryPks(
			req, sources, boundaryContext, resolved.attributeSchema(),
			rawMin, rawMax, filterIndexes, attributeHistogramBaselineFormula, context, facetSorter,
			partitionContext, scopeOrder
		);
		return Optional.of(new PendingHistogram(groupPk, req, cacheable, boundaryPks));
	}

	/**
	 * Concatenates the boundary contexts of the sources in their order. A single source hands over its own array,
	 * so the common single-scope case allocates nothing.
	 *
	 * @param sources histogram and boundary indexes from the contributing scopes, never empty
	 * @return the indexes tracking the references the histogram covers
	 */
	@Nonnull
	private static EntityIndex[] joinBoundaryContexts(@Nonnull List<HistogramSource> sources) {
		if (sources.size() == 1) {
			return sources.getFirst().boundaryContext();
		}
		int boundaryIndexCount = 0;
		for (final HistogramSource source : sources) {
			boundaryIndexCount += source.boundaryContext().length;
		}
		final EntityIndex[] boundaryContext = new EntityIndex[boundaryIndexCount];
		int offset = 0;
		for (final HistogramSource source : sources) {
			final EntityIndex[] indexes = source.boundaryContext();
			System.arraycopy(indexes, 0, boundaryContext, offset, indexes.length);
			offset += indexes.length;
		}
		return boundaryContext;
	}

	/**
	 * Returns the owners the histogram counts at `value`: the owners its filter indexes hold at the value -
	 * the histogram index stores only the values the histogram's condition admits - narrowed by the records of the
	 * baseline formula exactly as {@link AttributeHistogramComputer} narrows the buckets of the histogram. The cached
	 * histogram keeps only its raw bounds, not the owners of its extreme buckets, so the owners are read again from
	 * the filter indexes.
	 *
	 * @param ownerFilterIndexes the histogram filter indexes, keyed on owner primary keys
	 * @param value              the raw bound of the histogram
	 * @param baselineFormula    the baseline formula the histogram is computed against, or `null` when no formula
	 *                           narrows the histogram
	 * @return the primary keys of the owners the histogram counts at the value
	 */
	@Nonnull
	private static Bitmap countedOwners(
		@Nonnull List<FilterIndex> ownerFilterIndexes,
		@Nonnull Serializable value,
		@Nullable Formula baselineFormula
	) {
		Bitmap owners = EmptyBitmap.INSTANCE;
		for (final FilterIndex filterIndex : ownerFilterIndexes) {
			owners = union(owners, resolveBoundaryCandidates(filterIndex, value));
		}
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
	 * Delegates the actual histogram math to {@link AttributeHistogramComputer}. Builds a one-off
	 * {@link AttributeHistogramRequest} wrapping the {@link FilterIndex} instances backing this
	 * (reference, group, histogram) tuple and routes the computation through
	 * {@link QueryExecutionContext#analyse(CacheableEvitaResponseExtraResultComputer)} so the result
	 * participates in the shared extra-result cache. The key includes the contributing index IDs and baseline formula.
	 *
	 * @param resolved                          the pre-resolved histogram request
	 * @param filterIndexes                     the indexes holding the histogram values
	 * @param attributeHistogramBaselineFormula baseline formula selecting owners for histogram computation
	 * @param context                           the query execution context
	 * @return the computed or cached histogram
	 */
	@Nonnull
	private static CacheableHistogramContract computeCacheable(
		@Nonnull ResolvedRequest resolved,
		@Nonnull List<FilterIndex> filterIndexes,
		@Nullable Formula attributeHistogramBaselineFormula,
		@Nonnull QueryExecutionContext context
	) {
		final HistogramRequest req = resolved.request();
		final AttributeHistogramRequest histRequest = new AttributeHistogramRequest(
			resolved.attributeSchema(),
			req.bucketCount(),
			req.behavior(),
			resolved.comparator(),
			filterIndexes
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
	 * The candidate set is intersected with the referenced PKs tracked by the boundary context - the type index of an
	 * ungrouped reference or the reduced indexes of each contributing group. Preference goes to candidates tracked
	 * by a producing index for an owner the histogram counts at the bound. The check applies to all the
	 * candidates of each bound: when no candidate of a bound passes it, the bound keeps every candidate of the
	 * context and picks among them by the same rule, so the entity anchoring such a bound may be one no owner the
	 * histogram counts references. When candidates pass it, the pick among every candidate rides along as the
	 * alternative the bound takes when the entity of its pick cannot be loaded (see {@link BoundaryPick}).
	 *
	 * The {@code minValue} / {@code maxValue} parameters are the histogram's raw bounds, the keys of its filter index -
	 * a `BigDecimal` value is keyed as an `Integer` scaled by the indexed decimal places of the source attribute. They
	 * are passed to {@link FilterIndex#getRecordsEqualTo(Serializable)} verbatim: a filter index of the source
	 * attribute keys its values in the same form, and its normalizer lets an already normalized value through.
	 *
	 * @param req                the histogram request
	 * @param sources            the histogram sources with their scopes
	 * @param boundaryContext    the indexes tracking the references the histogram covers
	 * @param attributeSchema    schema of the attribute the histogram is computed over
	 * @param minValue           the histogram's raw smallest value
	 * @param maxValue           the histogram's raw largest value
	 * @param ownerFilterIndexes histogram indexes for the counted owners at a bound (see {@link #countedOwners})
	 * @param baselineFormula    the baseline formula the histogram is computed against, or `null` for none
	 * @param context            the query execution context
	 * @param facetSorter        the reference's sorter deciding ties among boundary PKs, or {@code null} for the
	 *                           lowest-PK rule
	 * @param partitionContext   resolves reference partitions for an ungrouped histogram, or `null` for a grouped one
	 * @param scopeOrder         preference for the referenced entities' scopes
	 * @return the resolved boundary PKs, {@link BoundaryPks#NONE} when nothing can be resolved
	 */
	@Nonnull
	private static BoundaryPks resolveBoundaryPks(
		@Nonnull HistogramRequest req,
		@Nonnull List<HistogramSource> sources,
		@Nonnull EntityIndex[] boundaryContext,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Serializable minValue,
		@Nonnull Serializable maxValue,
		@Nonnull List<FilterIndex> ownerFilterIndexes,
		@Nullable Formula baselineFormula,
		@Nonnull QueryExecutionContext context,
		@Nullable NestedContextSorter facetSorter,
		@Nullable QueryPlanningContext partitionContext,
		@Nonnull BoundaryScopeOrder scopeOrder
	) {
		final HistogramValueSource source = req.valueDescriptor().source();
		if (source == HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE) {
			return resolveBoundaryPksFromReferencedEntity(
				req, sources, boundaryContext, attributeSchema, minValue, maxValue, ownerFilterIndexes,
				baselineFormula, context, facetSorter, partitionContext, scopeOrder
			);
		}
		if (source == HistogramValueSource.REFERENCE_ATTRIBUTE) {
			return resolveBoundaryPksFromReferenceAttribute(
				req, boundaryContext, attributeSchema, minValue, maxValue, ownerFilterIndexes,
				baselineFormula, facetSorter, partitionContext, scopeOrder
			);
		}
		throw new GenericEvitaInternalError(
			"Unexpected histogram value source: " + source
		);
	}

	/**
	 * Resolves min/max referenced PKs when the histogram source is a referenced-entity attribute.
	 * Joins matches from the referenced collection's global {@link FilterIndex} in each contributing scope and
	 * intersects them with the referenced PKs tracked by the boundary indexes. A candidate passes the owner check
	 * when a reduced group index or reference partition tracks it for a counted owner of the bound.
	 *
	 * A referenced entity without the attribute gives the histogram the default of its value expression, yet the global
	 * filter index does not hold it, so it is never a candidate, and the owners the histogram counts at a bound may
	 * reference no candidate at all. Such a bound keeps every candidate of the context (see {@link #pickBoundaryPk}),
	 * so the entity anchoring it may be one no owner the histogram counts references.
	 *
	 * @param req                the histogram request
	 * @param sources            the histogram sources with their scopes
	 * @param boundaryContext    the indexes tracking the references the histogram covers
	 * @param attributeSchema    schema of the referenced entity's attribute the histogram is computed over
	 * @param minValue           the histogram's raw smallest value
	 * @param maxValue           the histogram's raw largest value
	 * @param ownerFilterIndexes histogram indexes for counted owners at a bound
	 * @param baselineFormula    the baseline formula the histogram is computed against, or `null` for none
	 * @param context            the query execution context
	 * @param facetSorter        the reference's sorter deciding ties among boundary PKs, or {@code null} for the
	 *                           lowest-PK rule
	 * @param partitionContext   resolves reference partitions for an ungrouped histogram, or `null` for a grouped one
	 * @param scopeOrder         preference for the referenced entities' scopes
	 * @return the resolved boundary PKs, {@link BoundaryPks#NONE} when the request has no source entity type or the
	 *         boundary context tracks no referenced entity
	 */
	@Nonnull
	private static BoundaryPks resolveBoundaryPksFromReferencedEntity(
		@Nonnull HistogramRequest req,
		@Nonnull List<HistogramSource> sources,
		@Nonnull EntityIndex[] boundaryContext,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Serializable minValue,
		@Nonnull Serializable maxValue,
		@Nonnull List<FilterIndex> ownerFilterIndexes,
		@Nullable Formula baselineFormula,
		@Nonnull QueryExecutionContext context,
		@Nullable NestedContextSorter facetSorter,
		@Nullable QueryPlanningContext partitionContext,
		@Nonnull BoundaryScopeOrder scopeOrder
	) {
		final String sourceEntityType = req.valueDescriptor().sourceEntityType();
		if (sourceEntityType == null) {
			return BoundaryPks.NONE;
		}
		final Bitmap inGroup = referencedPrimaryKeys(boundaryContext);
		if (inGroup.isEmpty()) {
			return BoundaryPks.NONE;
		}
		final boolean sameBound = Objects.equals(maxValue, minValue);
		Bitmap minCandidates = EmptyBitmap.INSTANCE;
		Bitmap maxCandidates = EmptyBitmap.INSTANCE;
		for (final HistogramSource histogramSource : sources) {
			final GlobalEntityIndex globalIndex = context.getQueryContext()
				.getGlobalEntityIndexIfExists(sourceEntityType, histogramSource.scope())
				.orElse(null);
			if (globalIndex == null) {
				continue;
			}
			final FilterIndex attributeFilterIndex = globalIndex.getFilterIndex(null, attributeSchema, req.locale());
			if (attributeFilterIndex != null) {
				minCandidates = union(minCandidates, resolveBoundaryCandidates(attributeFilterIndex, minValue));
				if (!sameBound) {
					maxCandidates = union(maxCandidates, resolveBoundaryCandidates(attributeFilterIndex, maxValue));
				}
			}
		}
		final BoundaryPick min = pickBoundaryPk(
			minCandidates, minValue, inGroup, boundaryContext, ownerFilterIndexes, baselineFormula, facetSorter,
			partitionContext, scopeOrder
		);
		final BoundaryPick max = sameBound ? min : pickBoundaryPk(
			maxCandidates, maxValue, inGroup, boundaryContext, ownerFilterIndexes, baselineFormula, facetSorter,
			partitionContext, scopeOrder
		);
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
	 * @param req                the histogram request
	 * @param boundaryContext    the indexes tracking the references the histogram covers
	 * @param attributeSchema    schema of the reference attribute the histogram is computed over
	 * @param minValue           the histogram's raw smallest value
	 * @param maxValue           the histogram's raw largest value
	 * @param ownerFilterIndexes histogram indexes for counted owners at a bound
	 * @param baselineFormula    the baseline formula the histogram is computed against, or `null` for none
	 * @param facetSorter        the reference's sorter deciding ties among boundary PKs, or {@code null} for the
	 *                           lowest-PK rule
	 * @param partitionContext   resolves reference partitions for an ungrouped histogram, or `null` for a grouped one
	 * @param scopeOrder         preference for the referenced entities' scopes
	 * @return the resolved boundary PKs, {@link BoundaryPks#NONE} when nothing can be resolved
	 */
	@Nonnull
	private static BoundaryPks resolveBoundaryPksFromReferenceAttribute(
		@Nonnull HistogramRequest req,
		@Nonnull EntityIndex[] boundaryContext,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Serializable minValue,
		@Nonnull Serializable maxValue,
		@Nonnull List<FilterIndex> ownerFilterIndexes,
		@Nullable Formula baselineFormula,
		@Nullable NestedContextSorter facetSorter,
		@Nullable QueryPlanningContext partitionContext,
		@Nonnull BoundaryScopeOrder scopeOrder
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
			referenceAttributeFilterIndexes, boundaryContext, minValue, ownerFilterIndexes, baselineFormula, inScope,
			facetSorter, partitionContext, scopeOrder
		);
		final BoundaryPick max;
		if (Objects.equals(maxValue, minValue)) {
			max = min;
		} else {
			max = pickBoundaryPkFromReferenceAttribute(
				referenceAttributeFilterIndexes, boundaryContext, maxValue,
				ownerFilterIndexes, baselineFormula, inScope, facetSorter, partitionContext, scopeOrder
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
	 * 3. sets apart those an owner the histogram counts at the value references in the same index
	 *    (see {@link #referencedByCountedOwners}), where the reference carrying the value sits,
	 * 4. joins the set-apart PKs of all indexes, intersects them with the referenced PKs tracked by the context, and
	 *    picks the final PK via the same sorter/lowest-PK rule as {@link #pickBoundaryPk},
	 * 5. when that leaves no PK, does the same with the referenced PKs of the second step of all indexes joined.
	 *
	 * The fifth step depends on the outcome over the whole context, never on a single index: a candidate an index
	 * produced without an owner the histogram counts never competes with a candidate of another index that has one.
	 * It is decided on the candidate primary keys, before any entity is loaded, so a pick of the fourth step may be an
	 * entity the query cannot load - one that does not exist, lies outside the requested scopes or lacks the query's
	 * locale. Whenever the fourth step picks, the pick of the fifth step over every candidate is therefore carried
	 * along as the alternative the bound falls back to when the entity of the fourth step does not load (see
	 * {@link BoundaryPick}); it reuses the candidates of the second step and reads no index again.
	 *
	 * The steps run as written for an ungrouped histogram or when a sorter is present. For a grouped histogram
	 * without a sorter, {@link #pickLowestOwnedBoundaryPk} makes the same pick, including the alternative, by
	 * merging producing-index iterators in unsigned PK order without owned subsets or candidate unions. Equal PKs
	 * are checked in index-position order. A sorter keeps the steps as written, because the order it gives the
	 * set-apart PKs need not be their order among every candidate sorted - a limited segment or a seeded shuffle
	 * depends on what else it sorts. The method is package-private so that tests can compare its pick with an
	 * independent exhaustive implementation.
	 *
	 * The mapping of the second step differs by the kind of index: the type index keys its filter index on its own
	 * reduced-index PKs, which only it can translate, while a reduced group index keys it on referenced entity PKs
	 * and the mapping keeps those it tracks. The histogram filter indexes are read at the first nonempty candidate
	 * lookup. A referenced entity whose only references at the value are given the default is no candidate.
	 *
	 * The {@code value} is the histogram's raw bound in the key form of its filter index, which the filter index of
	 * the reference attribute shares, so it is passed to the lookup verbatim.
	 *
	 * @param referenceAttributeFilterIndexes the reference attribute's filter index of each index of the
	 *                                        context, at the same position; {@code null} where the index has none
	 * @param boundaryContext                 the indexes tracking the references the histogram covers
	 * @param value                           the boundary value
	 * @param ownerFilterIndexes              the histogram indexes tracking the counted owners at the bound
	 * @param baselineFormula                 the baseline formula the histogram is computed against, or `null` for
	 *                                        none
	 * @param inScope                         the referenced entity PKs tracked by the context
	 * @param facetSorter                     the reference's sorter deciding ties, or {@code null} for the
	 *                                        lowest-PK rule
	 * @param partitionContext                resolves the partitions of an ungrouped histogram,
	 *                                        or `null` for a grouped one
	 * @param scopeOrder                      preference for the referenced entities' scopes
	 * @return the pick of the bound, {@link BoundaryPick#NONE} when no reference of the context carries the value
	 */
	@Nonnull
	static BoundaryPick pickBoundaryPkFromReferenceAttribute(
		@Nonnull FilterIndex[] referenceAttributeFilterIndexes,
		@Nonnull EntityIndex[] boundaryContext,
		@Nonnull Serializable value,
		@Nonnull List<FilterIndex> ownerFilterIndexes,
		@Nullable Formula baselineFormula,
		@Nonnull Bitmap inScope,
		@Nullable NestedContextSorter facetSorter,
		@Nullable QueryPlanningContext partitionContext,
		@Nonnull BoundaryScopeOrder scopeOrder
	) {
		if (partitionContext == null && facetSorter == null) {
			return pickLowestOwnedBoundaryPk(
				referenceAttributeFilterIndexes, boundaryContext, value, ownerFilterIndexes, baselineFormula, inScope,
				scopeOrder
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
				if (ownersRoaring == null) {
					ownersRoaring = RoaringBitmapBackedBitmap.getRoaringBitmap(
						countedOwners(ownerFilterIndexes, value, baselineFormula)
					);
				}
				// each index answers for its own owners: a candidate is checked in the index whose filter index
				// produced it, where the reference carrying the value sits
				ownedPkCandidates = union(
					ownedPkCandidates,
					partitionContext == null ?
						referencedByCountedOwners(referencedPks, boundaryContext[i], ownersRoaring) :
						referencedByCountedOwners(
							referencedPks, boundaryContext[i], ownersRoaring, partitionContext
						)
				);
			}
		}
		final Integer ownedPk = intersectAndPickBoundaryPk(ownedPkCandidates, inScope, facetSorter, scopeOrder);
		if (ownedPk != null) {
			// the owned candidates are a subset of every candidate: equal sizes mean equal sets, so an equal pick
			return ownedPkCandidates.size() == referencedPkCandidates.size() ?
				BoundaryPick.of(ownedPk) :
				BoundaryPick.of(
					ownedPk, intersectAndPickBoundaryPk(referencedPkCandidates, inScope, facetSorter, scopeOrder)
				);
		}
		// no candidate of the whole context passes the owner check, so the bound keeps every candidate
		return BoundaryPick.of(intersectAndPickBoundaryPk(referencedPkCandidates, inScope, facetSorter, scopeOrder));
	}

	/**
	 * Makes the pick of {@link #pickBoundaryPkFromReferenceAttribute} without a sorter for a context confined to
	 * the owners counted by the group-store filter indexes. Picks the first candidate a counted owner references
	 * in a producing index, with the first unrestricted candidate as the fallback.
	 *
	 * A primitive-array heap merges the producing indexes in unsigned PK order within each scope priority, with
	 * ties broken by index position. The first candidate provides the unrestricted pick. The first passing owner
	 * check provides the primary pick and ends the merge. Each popped candidate is checked against the tracked
	 * keys and the current scope priority. Failed producers of the same PK remain independent.
	 *
	 * @param referenceAttributeFilterIndexes the reference attribute's filter index of each index of the
	 *                                        context, at the same position; {@code null} where the index has none
	 * @param boundaryContext                 the indexes tracking the references the histogram covers
	 * @param value                           the boundary value
	 * @param ownerFilterIndexes              group-store filter indexes identifying the owners counted at the value
	 * @param baselineFormula                 the baseline formula the histogram is computed against, or `null` for
	 *                                        none
	 * @param inScope                         the referenced entity PKs tracked by the context
	 * @param scopeOrder                       preference for the referenced entities' scopes
	 * @return the pick of the bound, {@link BoundaryPick#NONE} when no reference of the context carries the value or
	 *         no candidate is tracked
	 */
	@Nonnull
	private static BoundaryPick pickLowestOwnedBoundaryPk(
		@Nonnull FilterIndex[] referenceAttributeFilterIndexes,
		@Nonnull EntityIndex[] boundaryContext,
		@Nonnull Serializable value,
		@Nonnull List<FilterIndex> ownerFilterIndexes,
		@Nullable Formula baselineFormula,
		@Nonnull Bitmap inScope,
		@Nonnull BoundaryScopeOrder scopeOrder
	) {
		final Bitmap[] candidatesByIndex = new Bitmap[boundaryContext.length];
		PersistentRoaringBitmap ownersRoaring = null;
		for (int i = 0; i < boundaryContext.length; i++) {
			final FilterIndex referenceAttributeFilterIndex = referenceAttributeFilterIndexes[i];
			if (referenceAttributeFilterIndex == null) {
				continue;
			}
			final Bitmap indexPkCandidates = resolveBoundaryCandidates(referenceAttributeFilterIndex, value);
			if (!indexPkCandidates.isEmpty()) {
				candidatesByIndex[i] = referencedPrimaryKeysForIndexPks(boundaryContext[i], indexPkCandidates);
				// Read at the first nonempty raw lookup, even if mapping or scope removes every candidate.
				if (ownersRoaring == null) {
					ownersRoaring = RoaringBitmapBackedBitmap.getRoaringBitmap(
						countedOwners(ownerFilterIndexes, value, baselineFormula)
					);
				}
			}
		}
		if (ownersRoaring == null) {
			return BoundaryPick.NONE;
		}
		final PeekableIntIterator[] iterators = new PeekableIntIterator[boundaryContext.length];
		final int[] heapPks = new int[boundaryContext.length];
		final int[] heapIndexes = new int[boundaryContext.length];
		Integer unrestrictedPk = null;
		for (int priority = 0; priority < scopeOrder.priorityCount(); priority++) {
			int heapSize = 0;
			for (int i = 0; i < candidatesByIndex.length; i++) {
				final Bitmap candidatePks = candidatesByIndex[i];
				if (candidatePks != null && !candidatePks.isEmpty()) {
					final PeekableIntIterator iterator =
						RoaringBitmapBackedBitmap.getRoaringBitmap(candidatePks).getIntIterator();
					iterators[i] = iterator;
					heapPks[heapSize] = iterator.next();
					heapIndexes[heapSize++] = i;
				}
			}
			// Each active producer contributes one head to the unsigned, index-stable minimum heap.
			for (int i = (heapSize >>> 1) - 1; i >= 0; i--) {
				siftBoundaryCandidateDown(heapPks, heapIndexes, heapSize, i);
			}
			while (heapSize > 0) {
				final int referencedPk = heapPks[0];
				final int producingIndex = heapIndexes[0];
				if (inScope.contains(referencedPk) && scopeOrder.contains(priority, referencedPk)) {
					if (unrestrictedPk == null) {
						unrestrictedPk = referencedPk;
					}
					if (isReferencedByCountedOwner(boundaryContext[producingIndex], referencedPk, ownersRoaring)) {
						return BoundaryPick.of(referencedPk, unrestrictedPk);
					}
				}
				// Advance only the popped producer. Another producer of this PK gets its own owner check.
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
		}
		return BoundaryPick.of(unrestrictedPk);
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
	 * Selects the ungrouped candidates whose reference partitions contain a counted owner. The type index
	 * connects referenced PKs to partition PKs. Virtual rows provide their owner directly and materialized
	 * partitions provide the owner bitmap from their reduced index.
	 *
	 * @param candidates the referenced PKs matching the bound
	 * @param index the type index producing the candidates or contributing to the histogram
	 * @param owners the owners counted at the bound
	 * @param context resolves materialized partitions in the queried collection
	 * @return the candidates referenced by counted owners in the index's scope
	 */
	@Nonnull
	private static Bitmap referencedByCountedOwners(
		@Nonnull Bitmap candidates,
		@Nonnull EntityIndex index,
		@Nonnull PersistentRoaringBitmap owners,
		@Nonnull QueryPlanningContext context
	) {
		if (!(index instanceof ReferencedTypeEntityIndex typeIndex)) {
			throw new GenericEvitaInternalError("Ungrouped boundary context requires a referenced type index.");
		}
		if (candidates.isEmpty() || owners.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		}
		final Bitmap partitionPks = typeIndex.getIndexPrimaryKeys(
			RoaringBitmapBackedBitmap.getRoaringBitmap(candidates)
		);
		final ReferencePartitionSplit partitions = ReferencePartitions.split(partitionPks, typeIndex);
		final RoaringBitmapWriter<PersistentRoaringBitmap> referencedPks = RoaringBitmapBackedBitmap.buildWriter();
		for (int i = 0; i < partitions.getVirtualCount(); i++) {
			if (owners.contains(partitions.getVirtualOwnerPrimaryKey(i))) {
				referencedPks.add(partitions.getVirtualTargetPrimaryKey(i));
			}
		}
		final PrimitiveIterator.OfInt it = partitions.getMaterializedPrimaryKeys().iterator();
		while (it.hasNext()) {
			final ReferencePartition partition = context.resolvePartition(it.nextInt());
			if (PersistentRoaringBitmap.intersects(
				RoaringBitmapBackedBitmap.getRoaringBitmap(partition.getAllPrimaryKeys()), owners
			)) {
				referencedPks.add(partition.getReferenceKey().primaryKey());
			}
		}
		final PersistentRoaringBitmap result = referencedPks.get();
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
	 * A candidate passes the owner check when a reduced group index or a reference partition of
	 * {@code boundaryContext} tracks it for an owner the histogram counts at the value (see {@link #countedOwners}).
	 * The owners are read once a candidate exists.
	 * The value is an attribute of the referenced entity, so every reference to the candidate carries it; the
	 * candidate passes even when the reference of a counted owner to it is one the histogram's condition rejects.
	 * When no candidate of the group passes, every candidate of the group stays and the pick runs among them. This
	 * fallback is decided on the candidate primary keys, before any entity is loaded, so a pick among the passing
	 * candidates may be an entity the query cannot load, such as one lacking the query's locale. Whenever the passing
	 * candidates give the pick, the pick among every candidate of the group is therefore carried along as the
	 * alternative the bound falls back to when the entity of the pick does not load (see {@link BoundaryPick}); it
	 * reuses the candidates of the group and reads no index again.
	 *
	 * @param candidates         referenced primary keys matching the bound across the contributing scopes
	 * @param value              the boundary value
	 * @param inGroup            the referenced entity PKs tracked by the boundary context
	 * @param boundaryContext    the indexes tracking the references the histogram covers
	 * @param ownerFilterIndexes histogram indexes for counted owners at the bound
	 * @param baselineFormula    the baseline formula the histogram is computed against, or `null` for none
	 * @param facetSorter        the reference's sorter deciding ties, or {@code null} for the lowest-PK rule
	 * @param partitionContext   resolves reference partitions for an ungrouped histogram, or `null` for a grouped one
	 * @param scopeOrder         preference for the referenced entities' scopes
	 * @return the pick of the bound, {@link BoundaryPick#NONE} when no candidate qualifies
	 */
	@Nonnull
	private static BoundaryPick pickBoundaryPk(
		@Nonnull Bitmap candidates,
		@Nonnull Serializable value,
		@Nonnull Bitmap inGroup,
		@Nonnull EntityIndex[] boundaryContext,
		@Nonnull List<FilterIndex> ownerFilterIndexes,
		@Nullable Formula baselineFormula,
		@Nullable NestedContextSorter facetSorter,
		@Nullable QueryPlanningContext partitionContext,
		@Nonnull BoundaryScopeOrder scopeOrder
	) {
		if (candidates.isEmpty()) {
			return BoundaryPick.NONE;
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
			countedOwners(ownerFilterIndexes, value, baselineFormula)
		);
		if (partitionContext != null) {
			final Bitmap scopedCandidates = new BaseBitmap(candidatesInGroup);
			Bitmap owned = EmptyBitmap.INSTANCE;
			for (final EntityIndex index : boundaryContext) {
				owned = union(
					owned, referencedByCountedOwners(scopedCandidates, index, ownersRoaring, partitionContext)
				);
			}
			return pickOwnedBoundaryPk(
				RoaringBitmapBackedBitmap.getRoaringBitmap(owned), candidatesInGroup, facetSorter, scopeOrder
			);
		}
		if (facetSorter == null) {
			Integer unrestrictedPk = null;
			for (int priority = 0; priority < scopeOrder.priorityCount(); priority++) {
				final PeekableIntIterator it = scopeOrder.restrict(candidatesInGroup, priority).getIntIterator();
				while (it.hasNext()) {
					final int referencedPk = it.next();
					if (unrestrictedPk == null) {
						unrestrictedPk = referencedPk;
					}
					if (isReferencedByCountedOwnerInAnyIndex(boundaryContext, referencedPk, ownersRoaring)) {
						return BoundaryPick.of(referencedPk, unrestrictedPk);
					}
				}
			}
			return BoundaryPick.of(unrestrictedPk);
		}
		final PeekableIntIterator it = candidatesInGroup.getIntIterator();
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		while (it.hasNext()) {
			final int referencedPk = it.next();
			if (isReferencedByCountedOwnerInAnyIndex(boundaryContext, referencedPk, ownersRoaring)) {
				writer.add(referencedPk);
			}
		}
		return pickOwnedBoundaryPk(writer.get(), candidatesInGroup, facetSorter, scopeOrder);
	}

	/**
	 * Picks among the owned candidates when possible and retains the unrestricted pick as a loading alternative.
	 *
	 * @param ownedCandidates referenced entities of counted owners
	 * @param candidatesInGroup all referenced candidates in the boundary context
	 * @param facetSorter decides ties, or `null` for the lowest-PK rule
	 * @param scopeOrder preference for the referenced entities' scopes
	 * @return the candidate pick and its unrestricted alternative, if any
	 */
	@Nonnull
	private static BoundaryPick pickOwnedBoundaryPk(
		@Nonnull PersistentRoaringBitmap ownedCandidates,
		@Nonnull PersistentRoaringBitmap candidatesInGroup,
		@Nullable NestedContextSorter facetSorter,
		@Nonnull BoundaryScopeOrder scopeOrder
	) {
		if (ownedCandidates.isEmpty()) {
			return BoundaryPick.of(pickBoundaryPkAmong(candidatesInGroup, facetSorter, scopeOrder));
		}
		final int ownedPk = Objects.requireNonNull(pickBoundaryPkAmong(ownedCandidates, facetSorter, scopeOrder));
		return ownedCandidates.getLongCardinality() == candidatesInGroup.getLongCardinality() ?
			BoundaryPick.of(ownedPk) :
			BoundaryPick.of(ownedPk, pickBoundaryPkAmong(candidatesInGroup, facetSorter, scopeOrder));
	}

	/**
	 * Picks a boundary from the intersection of the matching and tracked entities.
	 *
	 * @param candidates referenced entity PKs matching the bound
	 * @param tracked referenced entity PKs tracked by the histogram's indexes
	 * @param facetSorter orders tied candidates within a scope, or null for PK order
	 * @param scopeOrder preference for the referenced entities' scopes
	 * @return the boundary PK, or null when the intersection is empty
	 */
	@Nullable
	private static Integer intersectAndPickBoundaryPk(
		@Nonnull Bitmap candidates,
		@Nonnull Bitmap tracked,
		@Nullable NestedContextSorter facetSorter,
		@Nonnull BoundaryScopeOrder scopeOrder
	) {
		if (candidates.isEmpty()) {
			return null;
		}
		final PersistentRoaringBitmap intersection = PersistentRoaringBitmap.and(
			RoaringBitmapBackedBitmap.getRoaringBitmap(candidates), RoaringBitmapBackedBitmap.getRoaringBitmap(tracked)
		);
		return pickBoundaryPkAmong(intersection, facetSorter, scopeOrder);
	}

	/**
	 * Picks the first candidate in the highest-priority scope, ordered by the facet sorter or PK there.
	 *
	 * @param candidates referenced entity PKs eligible for selection
	 * @param facetSorter orders tied candidates within a scope, or null for PK order
	 * @param scopeOrder preference for the referenced entities' scopes
	 * @return the boundary PK, or null when there is no candidate
	 */
	@Nullable
	private static Integer pickBoundaryPkAmong(
		@Nonnull PersistentRoaringBitmap candidates,
		@Nullable NestedContextSorter facetSorter,
		@Nonnull BoundaryScopeOrder scopeOrder
	) {
		if (candidates.isEmpty()) {
			return null;
		}
		if (candidates.getLongCardinality() == 1L) {
			return candidates.first();
		}
		for (int priority = 0; priority < scopeOrder.priorityCount(); priority++) {
			final PersistentRoaringBitmap eligible = scopeOrder.restrict(candidates, priority);
			if (!eligible.isEmpty()) {
				return pickBoundaryPkAmongNonEmpty(eligible, facetSorter);
			}
		}
		throw new GenericEvitaInternalError("Nonempty boundary candidates exhausted by scope ordering.");
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
	 *
	 * @param referenceSchema the reference whose groups to rebuild
	 * @param existing the groups emitted by the facet phase
	 * @param histogramsByGroupKey the materialized histograms for this reference
	 * @param grouped whether the reference has managed groups
	 * @param resultAdapter creates group statistics
	 * @param groupFetchers the group fetchers keyed by histogram name
	 * @param context the executing query
	 * @param <T> concrete group statistics returned by the adapter
	 * @return facet groups with their histograms, followed by histogram-only groups
	 */
	@Nonnull
	private static <T extends ReferenceGroupStatistics> Collection<T> mergeWithExisting(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Collection<T> existing,
		@Nonnull Map<Integer, Map<String, HistogramContract>> histogramsByGroupKey,
		boolean grouped,
		@Nonnull ReferenceSummaryResultAdapter<T> resultAdapter,
		@Nonnull Map<String, HistogramGroupFetcher> groupFetchers,
		@Nonnull QueryExecutionContext context
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

		final Map<Integer, EntityClassifier> fetchedGroupEntities = prefetchSyntheticGroupEntities(
			referenceSchema, remaining, groupFetchers, context
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
	 * Fetches histogram-only group bodies in batches using the controls of the histograms present in those groups.
	 * A shared group has one body; among histograms requesting it, the lowest histogram name selects the fetch.
	 *
	 * @param referenceSchema the reference whose groups are being fetched
	 * @param remaining the histogram-only groups and their histograms
	 * @param groupFetchers the fetchers supplied by individual histograms
	 * @param context the executing query
	 * @return the group bodies by primary key
	 */
	@Nonnull
	private static Map<Integer, EntityClassifier> prefetchSyntheticGroupEntities(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Map<Integer, Map<String, HistogramContract>> remaining,
		@Nonnull Map<String, HistogramGroupFetcher> groupFetchers,
		@Nonnull QueryExecutionContext context
	) {
		if (remaining.isEmpty() || groupFetchers.isEmpty()) {
			return Map.of();
		}
		final Map<HistogramGroupFetcher, RoaringBitmapWriter<PersistentRoaringBitmap>> keysByFetcher =
			createLinkedHashMap(groupFetchers.size());
		for (final Entry<Integer, Map<String, HistogramContract>> group : remaining.entrySet()) {
			String firstName = null;
			HistogramGroupFetcher fetcher = null;
			for (final String name : group.getValue().keySet()) {
				final HistogramGroupFetcher candidate = groupFetchers.get(name);
				if (candidate != null && (firstName == null || name.compareTo(firstName) < 0)) {
					firstName = name;
					fetcher = candidate;
				}
			}
			if (fetcher != null) {
				keysByFetcher.computeIfAbsent(fetcher, ignored -> RoaringBitmapBackedBitmap.buildWriter())
					.add(group.getKey());
			}
		}
		final Map<Integer, EntityClassifier> result = createHashMap(remaining.size());
		for (final Entry<HistogramGroupFetcher, RoaringBitmapWriter<PersistentRoaringBitmap>> entry :
			keysByFetcher.entrySet()) {
			final EntityClassifier[] fetched =
				entry.getKey().fetch(context, referenceSchema, entry.getValue().get().toArray());
			for (final EntityClassifier classifier : fetched) {
				final Integer primaryKey = classifier.getPrimaryKey();
				if (primaryKey != null) {
					result.put(primaryKey, classifier);
				}
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
	 * Pre-resolved per-request artefacts that depend only on the {@link HistogramRequest}, so schema and
	 * comparator resolution happens exactly once per request regardless of the number of its groups and scopes.
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
	 * Group type index and tracked group PKs in one scope.
	 *
	 * @param scope     scope holding the group type index
	 * @param typeIndex index tracking the reduced indexes for each group
	 * @param groupPks  primary keys of the tracked groups
	 */
	private record GroupedScope(
		@Nonnull Scope scope,
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		@Nonnull Bitmap groupPks
	) {
	}

	/**
	 * Indexes contributing to one group's histogram in one scope.
	 *
	 * @param scope           scope holding the histogram index
	 * @param histogramIndex  index holding the histogram values
	 * @param boundaryContext indexes tracking the references used to resolve the boundaries
	 */
	private record HistogramSource(
		@Nonnull Scope scope,
		@Nonnull FilterIndex histogramIndex,
		@Nonnull EntityIndex[] boundaryContext
	) {
	}

	/**
	 * Requested scope order for the referenced entity type. Boundary resolution for managed targets lazily loads
	 * one primary-key bitmap per scope. The final priority contains all candidates outside the requested scopes.
	 */
	static final class BoundaryScopeOrder {
		private final Scope[] scopes;
		private final QueryPlanningContext context;
		private final String entityType;
		private final PersistentRoaringBitmap[] members;

		/**
		 * Creates a scope order whose index lookups are shared across the groups and bounds of a reference.
		 *
		 * @param scopes requested scopes in client order
		 * @param context provides the global indexes for boundary resolution
		 * @param entityType referenced entity type, read to load scope membership during boundary resolution
		 *                   for managed targets
		 */
		BoundaryScopeOrder(
			@Nonnull Scope[] scopes, @Nonnull QueryPlanningContext context, @Nonnull String entityType
		) {
			this.scopes = scopes;
			this.context = context;
			this.entityType = entityType;
			this.members = new PersistentRoaringBitmap[scopes.length];
		}

		/**
		 * Counts the requested scope priorities and the final priority for unrequested scopes.
		 *
		 * @return the number of candidate passes
		 */
		private int priorityCount() {
			return this.scopes.length + 1;
		}

		/**
		 * Tests a candidate against the requested scope at this priority, or against all unsearched scopes.
		 *
		 * @param priority zero-based position in the scope order
		 * @param referencedPk the candidate's primary key
		 * @return whether the candidate belongs to this priority
		 */
		private boolean contains(int priority, int referencedPk) {
			if (priority < this.scopes.length) {
				return members(priority).contains(referencedPk);
			}
			for (int i = 0; i < this.scopes.length; i++) {
				if (members(i).contains(referencedPk)) {
					return false;
				}
			}
			return true;
		}

		/**
		 * Restricts candidates to the requested scope at this priority, or to outside the requested scopes.
		 *
		 * @param candidates referenced entity PKs eligible for selection
		 * @param priority zero-based position in the scope order
		 * @return the candidates at this priority
		 */
		@Nonnull
		private PersistentRoaringBitmap restrict(@Nonnull PersistentRoaringBitmap candidates, int priority) {
			if (candidates.isEmpty()) {
				return candidates;
			}
			if (priority < this.scopes.length) {
				return PersistentRoaringBitmap.and(candidates, members(priority));
			}
			PersistentRoaringBitmap remaining = candidates;
			for (int i = 0; i < this.scopes.length && !remaining.isEmpty(); i++) {
				remaining = PersistentRoaringBitmap.andNot(remaining, members(i));
			}
			return remaining;
		}

		/**
		 * Reads the global index's referenced entity PKs once per searched scope.
		 *
		 * @param priority the position of the requested scope
		 * @return the scope's primary keys
		 */
		@Nonnull
		private PersistentRoaringBitmap members(int priority) {
			final PersistentRoaringBitmap cached = this.members[priority];
			if (cached != null) {
				return cached;
			}
			final GlobalEntityIndex index = this.context
				.getGlobalEntityIndexIfExists(this.entityType, this.scopes[priority])
				.orElse(null);
			final PersistentRoaringBitmap loaded = RoaringBitmapBackedBitmap.getRoaringBitmap(
				index == null ? EmptyBitmap.INSTANCE : index.getAllPrimaryKeys()
			);
			this.members[priority] = loaded;
			return loaded;
		}
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
	 * sentinel is used when no resolution was attempted or produced — for example, when the reference targets an
	 * unmanaged entity type, the histogram has no raw bounds, no index of the boundary context holds a filter index
	 * of a reference attribute, or the boundary context tracks no referenced entity. A bound with no candidate holds
	 * {@link BoundaryPick#NONE} next to a resolved entity type instead, and so do both bounds of a referenced entity
	 * attribute whose collection holds no filter index of the attribute in any contributing scope. When the smallest
	 * and the largest value are equal, both bounds hold the same pick.
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
	 * The referenced entity anchoring a histogram bound and its optional loading alternative. When the primary pick
	 * comes from counted owners, the same scope and within-scope ordering picks the alternative among all candidates.
	 * The alternative is kept when the two picks differ. Both entities are fetched with the histogram's
	 * `entityFetch`.
	 *
	 * The bound takes the entity of `pk` when it loads and the entity of `alternativePk` otherwise. The bound has
	 * an entity whenever the pick among all candidates loads.
	 *
	 * @param pk            the referenced entity anchoring the bound, or `null` when no candidate qualifies
	 * @param alternativePk the referenced entity picked among every candidate of the bound, or `null` when it is `pk`
	 *                      or the bound keeps every candidate
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
	 * @param byTupleThenPk loaded entities by fetch tuple and primary key
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
				final List<SealedEntity> fetched = context.fetchEntities(tuple.entityType(), pks, fetch);
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
