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

package io.evitadb.api.functional;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies one entity upsert that changes data the indexes evaluate only after the local mutations preceding it have
 * been stored - a reference attribute feeding a reference histogram, a new reference whose histogram value is added
 * once the reference is stored, a new group of a grouped reference, or an entity or reference attribute read by the
 * condition of a histogram or of a conditionally faceted reference - and then changes the scope of the existing
 * entity. The evaluation such a change waits for belongs to the scope the entity was in when the change was made: the
 * histogram and the facet index of the scope the entity ends in must reflect the state the entity ends with, and those
 * of the scope it left must hold nothing of it - in the engine that applied the upsert, after the engine restarts on
 * the stored data, and after the entity later returns to the scope it left.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Changing the scope of an entity together with data its histograms and conditional facets derive from")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(HISTOGRAM)
@Tag(FACET)
class ScopeChangeDeferredReEvaluationFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String ENTITY_BRAND_GROUP = "brandGroup";
	private static final String ENTITY_TAG = "tag";
	private static final String REFERENCE_BRAND = "brand";
	private static final String REFERENCE_TAG = "tag";
	private static final String ATTRIBUTE_SHARE = "share";
	private static final String ATTRIBUTE_HIDDEN = "hidden";
	private static final String HISTOGRAM_SHARE = "shareHistogram";
	private static final Scope[] ALL_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	private static final int PRODUCT_PK = 1;
	private static final int LIVE_ANCHOR_PK = 100;
	private static final int ARCHIVED_ANCHOR_PK = 101;
	private static final int BRAND_PK = 10;
	private static final int SECOND_BRAND_PK = 11;
	private static final int FIRST_GROUP_PK = 1;
	private static final int SECOND_GROUP_PK = 2;
	private static final int TAG_PK = 20;
	private static final int ORIGINAL_SHARE = 1;
	private static final int CHANGED_SHARE = 2;
	private static final int FOLLOW_UP_SHARE = 3;
	private static final int ANCHOR_SHARE = 10;
	private static final int SECOND_ANCHOR_SHARE = 20;
	private static final int BUCKET_COUNT = 10;
	/**
	 * The share histogram of a scope - or of a group in a scope - holding only its anchor product.
	 */
	private static final String ANCHORS_ONLY = histogramRange(ANCHOR_SHARE, SECOND_ANCHOR_SHARE);

	/**
	 * The shapes of the upsert that queues a histogram re-evaluation before the product moves to the other scope.
	 */
	enum QueuedBeforeScopeChangeShape {
		/** one upsert changing the share, another one moving to `other` */
		ATTRIBUTE_THEN_SCOPE_SEPARATELY,
		/** one upsert changing the share, then moving to `other` */
		ATTRIBUTE_THEN_SCOPE,
		/** one upsert inserting the reference with its share, another one moving to `other` */
		INSERT_THEN_SCOPE_SEPARATELY,
		/** one upsert inserting the reference with its share, then moving to `other` */
		INSERT_THEN_SCOPE,
		/** one upsert removing the attribute that keeps the product out of the histogram, another one moving */
		UNHIDING_THEN_SCOPE_SEPARATELY,
		/** one upsert made by the entity builder, which orders the attribute removal before the scope change */
		UNHIDING_AND_SCOPE_BY_BUILDER;

		/**
		 * Returns TRUE when the product starts with the reference to the brand, FALSE when the upsert inserts it.
		 *
		 * @return TRUE when the product starts referencing the brand
		 */
		boolean startsReferenced() {
			return this != INSERT_THEN_SCOPE_SEPARATELY && this != INSERT_THEN_SCOPE;
		}

		/**
		 * Returns TRUE when the histogram admits only products without the `hidden` attribute and the product
		 * starts hidden.
		 *
		 * @return TRUE when the histogram is gated by the `hidden` attribute
		 */
		boolean gatedByHidden() {
			return this == UNHIDING_THEN_SCOPE_SEPARATELY || this == UNHIDING_AND_SCOPE_BY_BUILDER;
		}

		/**
		 * Returns the share the product's reference to the brand carries once the upsert is applied.
		 *
		 * @return the share after the upsert
		 */
		int shareAfterUpsert() {
			return gatedByHidden() ? ORIGINAL_SHARE : CHANGED_SHARE;
		}
	}

	/**
	 * The shapes of the upsert that moves the product's grouped reference to the brand to another group and moves
	 * the product to the other scope.
	 */
	enum GroupBeforeScopeChangeShape {
		/** one upsert moving the reference to the second group, another one moving to `other` */
		GROUP_THEN_SCOPE_SEPARATELY,
		/** one upsert moving the reference to the second group, then moving to `other` */
		GROUP_THEN_SCOPE
	}

	/**
	 * The data the condition of the conditionally faceted reference to the tag reads.
	 */
	enum ConditionSource {
		/** the condition reads the `hidden` attribute of the product */
		ENTITY_ATTRIBUTE,
		/** the condition reads the `hidden` attribute of the product's reference to the tag */
		REFERENCE_ATTRIBUTE
	}

	/**
	 * The way the conditionally faceted reference to the tag is indexed in both scopes.
	 */
	enum TagIndexing {
		/** indexed for filtering only - its facets live in the global index alone */
		FILTERING,
		/** indexed for filtering and partitioning - its facets live in the reduced indexes of the product as well */
		FILTERING_AND_PARTITIONING
	}

	/**
	 * The shapes of the upsert that removes the attribute the facet condition reads and moves the product.
	 */
	enum ConditionBeforeScopeChangeShape {
		/** one upsert moving to `other`, then removing the attribute */
		SCOPE_THEN_REMOVAL,
		/** one upsert removing the attribute, another one moving to `other` */
		REMOVAL_THEN_SCOPE_SEPARATELY,
		/** one upsert removing the attribute, then moving to `other` */
		REMOVAL_THEN_SCOPE,
		/** one upsert made by the entity builder, which orders the removal before the scope change */
		REMOVAL_AND_SCOPE_BY_BUILDER
	}

	/**
	 * Every combination of the catalog state, the scope the product starts in, the shape of the upsert and whether
	 * the engine restarts before the product returns.
	 *
	 * @return arguments of {@link #shouldLeaveNothingInHistogramOfScopeEntityLeavesWhenReEvaluationIsQueuedFirst}
	 */
	@Nonnull
	static Stream<Arguments> queuedBeforeScopeChangeVariants() {
		final List<Arguments> result = new ArrayList<>(48);
		for (CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (Scope origin : ALL_SCOPES) {
				for (QueuedBeforeScopeChangeShape shape : QueuedBeforeScopeChangeShape.values()) {
					for (boolean restart : new boolean[]{false, true}) {
						result.add(Arguments.of(state, origin, shape, restart));
					}
				}
			}
		}
		return result.stream();
	}

	/**
	 * Every combination of the catalog state, the scope the product starts in, the shape of the upsert and whether
	 * the engine restarts before the product returns.
	 *
	 * @return arguments of {@link #shouldMoveGroupedHistogramEntryOnlyInScopeEntityEndsInWhenGroupChangesFirst}
	 */
	@Nonnull
	static Stream<Arguments> groupBeforeScopeChangeVariants() {
		final List<Arguments> result = new ArrayList<>(16);
		for (CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (Scope origin : ALL_SCOPES) {
				for (GroupBeforeScopeChangeShape shape : GroupBeforeScopeChangeShape.values()) {
					for (boolean restart : new boolean[]{false, true}) {
						result.add(Arguments.of(state, origin, shape, restart));
					}
				}
			}
		}
		return result.stream();
	}

	/**
	 * Every combination of the catalog state, the scope the product starts in, the indexing of the tag reference,
	 * the data the facet condition reads and the shape of the upsert.
	 *
	 * @return arguments of {@link #shouldIndexConditionalFacetOnlyInScopeEntityMovesToWhenConditionChangesFirst}
	 */
	@Nonnull
	static Stream<Arguments> conditionBeforeScopeChangeVariants() {
		final List<Arguments> result = new ArrayList<>(64);
		for (CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (Scope origin : ALL_SCOPES) {
				for (TagIndexing indexing : TagIndexing.values()) {
					for (ConditionSource source : ConditionSource.values()) {
						for (ConditionBeforeScopeChangeShape shape : ConditionBeforeScopeChangeShape.values()) {
							result.add(Arguments.of(state, origin, indexing, source, shape));
						}
					}
				}
			}
		}
		return result.stream();
	}

	/**
	 * Changes the share of the product's reference to the brand, inserts the reference with that share, or removes the
	 * attribute that keeps the product out of the histogram, and then moves the product to the other scope - in one
	 * upsert or in two. The share histogram of the scope the product ends in must carry the share it ends with, and
	 * that of the scope it leaves must hold nothing of it, which a query answers only once the product is back,
	 * because the histogram of a scope is narrowed to the entities that scope holds: the product therefore returns and
	 * has its share changed once more, and the histogram of the scope it returned to must carry that share alone.
	 *
	 * @param state                 the state the catalog is in when the upsert is applied
	 * @param origin                the scope the product starts in
	 * @param shape                 the shape of the upsert
	 * @param restartBeforeFollowUp TRUE when the engine restarts on the stored data before the product returns
	 */
	@ParameterizedTest(name = "{0} from {1} {2} restart={3}")
	@MethodSource("queuedBeforeScopeChangeVariants")
	@DisplayName("a histogram re-evaluation queued before the scope change leaves nothing in the scope left")
	void shouldLeaveNothingInHistogramOfScopeEntityLeavesWhenReEvaluationIsQueuedFirst(
		@Nonnull CatalogState state,
		@Nonnull Scope origin,
		@Nonnull QueuedBeforeScopeChangeShape shape,
		boolean restartBeforeFollowUp
	) {
		final TestPaths paths = createTestPaths(
			"ScopeChangeHistogramQueued_" + state + "_" + origin + "_" + shape + "_" + restartBeforeFollowUp
		);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		final Scope other = otherThan(origin);
		final List<Executable> checks = new ArrayList<>(16);
		Evita evita = new Evita(configuration);
		try {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, session -> {
				setUpHistogramCatalog(session, origin, shape.startsReferenced(), shape.gatedByHidden());
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});
			// a product hidden or without the reference is absent from the histogram it later enters
			recordHistogram(
				evita, "before the upsert", origin,
				shape.startsReferenced() && !shape.gatedByHidden() ?
					histogramRange(ORIGINAL_SHARE, SECOND_ANCHOR_SHARE) : ANCHORS_ONLY,
				checks
			);

			final LocalMutation<?, ?> insertReference =
				new InsertReferenceMutation(new ReferenceKey(REFERENCE_BRAND, BRAND_PK));
			final LocalMutation<?, ?> changeShare = shareMutation(CHANGED_SHARE);
			final LocalMutation<?, ?> unhide = new RemoveAttributeMutation(ATTRIBUTE_HIDDEN);
			final LocalMutation<?, ?> toOther = new SetEntityScopeMutation(other);
			switch (shape) {
				case ATTRIBUTE_THEN_SCOPE_SEPARATELY -> {
					upsertProduct(evita, "the share change", checks, changeShare);
					upsertProduct(evita, "the scope change", checks, toOther);
				}
				case ATTRIBUTE_THEN_SCOPE -> upsertProduct(evita, shape.name(), checks, changeShare, toOther);
				case INSERT_THEN_SCOPE_SEPARATELY -> {
					upsertProduct(evita, "the insertion", checks, insertReference, changeShare);
					upsertProduct(evita, "the scope change", checks, toOther);
				}
				case INSERT_THEN_SCOPE ->
					upsertProduct(evita, shape.name(), checks, insertReference, changeShare, toOther);
				case UNHIDING_THEN_SCOPE_SEPARATELY -> {
					upsertProduct(evita, "the attribute removal", checks, unhide);
					upsertProduct(evita, "the scope change", checks, toOther);
				}
				case UNHIDING_AND_SCOPE_BY_BUILDER -> applyUpdate(
					evita, shape.name(), checks,
					session -> session
						.getEntity(ENTITY_PRODUCT, PRODUCT_PK, ALL_SCOPES, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeAttribute(ATTRIBUTE_HIDDEN)
						.setScope(other)
						.upsertVia(session)
				);
				default -> throw new IllegalStateException("Unexpected shape: " + shape);
			}
			final String moved = histogramRange(shape.shareAfterUpsert(), SECOND_ANCHOR_SHARE);
			recordHistogram(evita, "after the upsert", origin, ANCHORS_ONLY, checks);
			recordHistogram(evita, "after the upsert", other, moved, checks);

			if (restartBeforeFollowUp) {
				evita.close();
				evita = new Evita(configuration);
				evita.waitUntilFullyInitialized();
				recordHistogram(evita, "after restart", origin, ANCHORS_ONLY, checks);
				recordHistogram(evita, "after restart", other, moved, checks);
			}

			upsertProduct(evita, "the return", checks, new SetEntityScopeMutation(origin));
			upsertProduct(evita, "the follow-up share change", checks, shareMutation(FOLLOW_UP_SHARE));
			final String stage = "after the return and the follow-up share change";
			recordHistogram(evita, stage, origin, histogramRange(FOLLOW_UP_SHARE, SECOND_ANCHOR_SHARE), checks);
			recordHistogram(evita, stage, other, ANCHORS_ONLY, checks);
		} finally {
			evita.close();
			cleanupTestPaths(paths);
		}
		assertAll(checks);
	}

	/**
	 * Moves the product's grouped reference to the brand from the first group to the second one and moves the
	 * product to the other scope - in one upsert or in two. The share histogram of the second group in the scope the
	 * product ends in must carry its share, and no group histogram of the scope it left may hold anything of it,
	 * which - as the histogram of a scope is narrowed to the entities that scope holds - a query answers only once
	 * the product is back and has its share changed once more.
	 *
	 * @param state                 the state the catalog is in when the upsert is applied
	 * @param origin                the scope the product starts in
	 * @param shape                 the shape of the upsert
	 * @param restartBeforeFollowUp TRUE when the engine restarts on the stored data before the product returns
	 */
	@ParameterizedTest(name = "{0} from {1} {2} restart={3}")
	@MethodSource("groupBeforeScopeChangeVariants")
	@DisplayName("a group change before the scope change moves the grouped histogram entry within the new scope only")
	void shouldMoveGroupedHistogramEntryOnlyInScopeEntityEndsInWhenGroupChangesFirst(
		@Nonnull CatalogState state,
		@Nonnull Scope origin,
		@Nonnull GroupBeforeScopeChangeShape shape,
		boolean restartBeforeFollowUp
	) {
		final TestPaths paths = createTestPaths(
			"ScopeChangeGroupedHistogram_" + state + "_" + origin + "_" + shape + "_" + restartBeforeFollowUp
		);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		final Scope other = otherThan(origin);
		final List<Executable> checks = new ArrayList<>(16);
		Evita evita = new Evita(configuration);
		try {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, session -> {
				setUpGroupedHistogramCatalog(session, origin);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});
			recordGroupedHistograms(
				evita, "before the upsert", origin,
				histogramRange(ORIGINAL_SHARE, SECOND_ANCHOR_SHARE), ANCHORS_ONLY, checks
			);

			final LocalMutation<?, ?> toSecondGroup =
				new SetReferenceGroupMutation(new ReferenceKey(REFERENCE_BRAND, BRAND_PK), SECOND_GROUP_PK);
			final LocalMutation<?, ?> toOther = new SetEntityScopeMutation(other);
			switch (shape) {
				case GROUP_THEN_SCOPE_SEPARATELY -> {
					upsertProduct(evita, "the group change", checks, toSecondGroup);
					upsertProduct(evita, "the scope change", checks, toOther);
				}
				case GROUP_THEN_SCOPE -> upsertProduct(evita, shape.name(), checks, toSecondGroup, toOther);
				default -> throw new IllegalStateException("Unexpected shape: " + shape);
			}
			final String moved = histogramRange(ORIGINAL_SHARE, SECOND_ANCHOR_SHARE);
			recordGroupedHistograms(evita, "after the upsert", origin, ANCHORS_ONLY, ANCHORS_ONLY, checks);
			recordGroupedHistograms(evita, "after the upsert", other, ANCHORS_ONLY, moved, checks);

			if (restartBeforeFollowUp) {
				evita.close();
				evita = new Evita(configuration);
				evita.waitUntilFullyInitialized();
				recordGroupedHistograms(evita, "after restart", origin, ANCHORS_ONLY, ANCHORS_ONLY, checks);
				recordGroupedHistograms(evita, "after restart", other, ANCHORS_ONLY, moved, checks);
			}

			upsertProduct(evita, "the return", checks, new SetEntityScopeMutation(origin));
			upsertProduct(evita, "the follow-up share change", checks, shareMutation(FOLLOW_UP_SHARE));
			final String stage = "after the return and the follow-up share change";
			recordGroupedHistograms(
				evita, stage, origin, ANCHORS_ONLY, histogramRange(FOLLOW_UP_SHARE, SECOND_ANCHOR_SHARE), checks
			);
			recordGroupedHistograms(evita, stage, other, ANCHORS_ONLY, ANCHORS_ONLY, checks);
		} finally {
			evita.close();
			cleanupTestPaths(paths);
		}
		assertAll(checks);
	}

	/**
	 * Removes the attribute that keeps the conditionally faceted reference to the tag out of the facet index, and
	 * moves the product to the other scope - in one upsert in either order, or in two. The upsert must succeed, the
	 * stored product must end in the other scope without the attribute, and the facet must end up in the facet index
	 * of the scope the product ends in and in no other - also within the partition of the tag, when the reference is
	 * indexed for partitioning.
	 *
	 * @param state    the state the catalog is in when the upsert is applied
	 * @param origin   the scope the product starts in
	 * @param indexing the way the reference to the tag is indexed
	 * @param source   the data the facet condition reads
	 * @param shape    the shape of the upsert
	 */
	@ParameterizedTest(name = "{0} from {1} {2} {3} {4}")
	@MethodSource("conditionBeforeScopeChangeVariants")
	@DisplayName("a facet condition changed before the scope change is re-evaluated in the new scope only")
	void shouldIndexConditionalFacetOnlyInScopeEntityMovesToWhenConditionChangesFirst(
		@Nonnull CatalogState state,
		@Nonnull Scope origin,
		@Nonnull TagIndexing indexing,
		@Nonnull ConditionSource source,
		@Nonnull ConditionBeforeScopeChangeShape shape
	) {
		final TestPaths paths = createTestPaths(
			"ScopeChangeConditionalFacet_" + state + "_" + origin + "_" + indexing + "_" + source + "_" + shape
		);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		final Scope other = otherThan(origin);
		final List<Executable> checks = new ArrayList<>(16);
		try {
			try (final Evita evita = new Evita(configuration)) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					setUpConditionalFacetCatalog(session, origin, indexing, source);
					if (state == CatalogState.ALIVE) {
						session.goLiveAndClose();
					}
				});
				// the condition keeps the facet out while the attribute is set, so the facet that appears later is
				// the work of its re-evaluation
				recordFacetState(evita, "before the upsert", origin, 0, 1, checks);

				final LocalMutation<?, ?> removeHidden = source == ConditionSource.ENTITY_ATTRIBUTE ?
					new RemoveAttributeMutation(ATTRIBUTE_HIDDEN) :
					new ReferenceAttributeMutation(
						new ReferenceKey(REFERENCE_TAG, TAG_PK), new RemoveAttributeMutation(ATTRIBUTE_HIDDEN)
					);
				switch (shape) {
					case SCOPE_THEN_REMOVAL ->
						upsertProduct(evita, shape.name(), checks, new SetEntityScopeMutation(other), removeHidden);
					case REMOVAL_THEN_SCOPE_SEPARATELY -> {
						upsertProduct(evita, "the removal", checks, removeHidden);
						upsertProduct(evita, "the scope change", checks, new SetEntityScopeMutation(other));
					}
					case REMOVAL_THEN_SCOPE ->
						upsertProduct(evita, shape.name(), checks, removeHidden, new SetEntityScopeMutation(other));
					case REMOVAL_AND_SCOPE_BY_BUILDER -> applyUpdate(
						evita, shape.name(), checks,
						session -> {
							final EntityBuilder product = session
								.getEntity(ENTITY_PRODUCT, PRODUCT_PK, ALL_SCOPES, entityFetchAllContent())
								.orElseThrow()
								.openForWrite();
							if (source == ConditionSource.ENTITY_ATTRIBUTE) {
								product.removeAttribute(ATTRIBUTE_HIDDEN);
							} else {
								product.setReference(
									REFERENCE_TAG, TAG_PK, whichIs -> whichIs.removeAttribute(ATTRIBUTE_HIDDEN)
								);
							}
							product.setScope(other).upsertVia(session);
						}
					);
					default -> throw new IllegalStateException("Unexpected shape: " + shape);
				}
				recordConditionRemoved(evita, "after the upsert", other, source, checks);
				recordFacetState(evita, "after the upsert", other, 1, 1, checks);
				recordFacetState(evita, "after the upsert", origin, 0, 0, checks);
			}

			// the stored product and the facet index the engine loads on restart must agree with the ones it kept
			try (final Evita evita = new Evita(configuration)) {
				evita.waitUntilFullyInitialized();
				recordConditionRemoved(evita, "after restart", other, source, checks);
				recordFacetState(evita, "after restart", other, 1, 1, checks);
				recordFacetState(evita, "after restart", origin, 0, 0, checks);
			}
		} finally {
			cleanupTestPaths(paths);
		}
		assertAll(checks);
	}

	/**
	 * Removes the attribute that keeps the conditionally faceted reference to the tag out of the facet index, moves the
	 * product to the archive, sets the attribute again and moves the product back - all in one upsert, so each of its
	 * two scope changes follows a condition change, the first one made in the live scope and the second one in the
	 * archive. The upsert must succeed, and as the product ends hidden in the scope it started in, both scopes must end
	 * as they began: the facet in neither of them and the reference in the live one only - in the engine that applied
	 * the upsert and after the engine restarts on the stored data.
	 */
	@Test
	@DisplayName("a round trip in one upsert succeeds with a facet condition changed before each scope change")
	void shouldKeepConditionalFacetConsistentWhenEntityMovesThereAndBackWithinOneUpsert() {
		final TestPaths paths = createTestPaths("ScopeChangeConditionalFacetRoundTrip");
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		final List<Executable> checks = new ArrayList<>(16);
		try {
			try (final Evita evita = new Evita(configuration)) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					setUpConditionalFacetCatalog(
						session, Scope.LIVE, TagIndexing.FILTERING_AND_PARTITIONING, ConditionSource.ENTITY_ATTRIBUTE
					);
					session.goLiveAndClose();
				});
				recordFacetState(evita, "before the upsert", Scope.LIVE, 0, 1, checks);

				upsertProduct(
					evita, "the round trip", checks,
					new RemoveAttributeMutation(ATTRIBUTE_HIDDEN),
					new SetEntityScopeMutation(Scope.ARCHIVED),
					new UpsertAttributeMutation(ATTRIBUTE_HIDDEN, true),
					new SetEntityScopeMutation(Scope.LIVE)
				);
				recordFacetState(evita, "after the upsert", Scope.LIVE, 0, 1, checks);
				recordFacetState(evita, "after the upsert", Scope.ARCHIVED, 0, 0, checks);
			}

			// the facet index the engine loads on restart must agree with the one it kept
			try (final Evita evita = new Evita(configuration)) {
				evita.waitUntilFullyInitialized();
				recordFacetState(evita, "after restart", Scope.LIVE, 0, 1, checks);
				recordFacetState(evita, "after restart", Scope.ARCHIVED, 0, 0, checks);
			}
		} finally {
			cleanupTestPaths(paths);
		}
		assertAll(checks);
	}

	/**
	 * Returns the scope other than `scope`.
	 *
	 * @param scope the scope
	 * @return the other scope
	 */
	@Nonnull
	private static Scope otherThan(@Nonnull Scope scope) {
		return scope == Scope.LIVE ? Scope.ARCHIVED : Scope.LIVE;
	}

	/**
	 * Describes a share histogram spanning the shares `min` to `max`.
	 *
	 * @param min the smallest share
	 * @param max the largest share
	 * @return the description {@link #describeHistogram} produces for such a histogram
	 */
	@Nonnull
	private static String histogramRange(int min, int max) {
		return "min=" + min + " max=" + max;
	}

	/**
	 * Creates the mutation setting the share of the product's reference to the brand.
	 *
	 * @param share the share to set
	 * @return the mutation
	 */
	@Nonnull
	private static ReferenceAttributeMutation shareMutation(int share) {
		return new ReferenceAttributeMutation(
			new ReferenceKey(REFERENCE_BRAND, BRAND_PK),
			new UpsertAttributeMutation(ATTRIBUTE_SHARE, share)
		);
	}

	/**
	 * Applies one upsert of the product made of `mutations` and records a failed check when it throws.
	 *
	 * @param evita     the engine
	 * @param label     names the upsert in the failure message
	 * @param checks    the collected checks
	 * @param mutations the local mutations of the upsert, in the order they are applied
	 */
	private static void upsertProduct(
		@Nonnull Evita evita,
		@Nonnull String label,
		@Nonnull List<Executable> checks,
		@Nonnull LocalMutation<?, ?>... mutations
	) {
		applyUpdate(
			evita, label, checks,
			session -> session.upsertEntity(
				new EntityUpsertMutation(ENTITY_PRODUCT, PRODUCT_PK, EntityExistence.MUST_EXIST, mutations)
			)
		);
	}

	/**
	 * Applies `update` in its own read-write session and records a failed check when it throws.
	 *
	 * @param evita  the engine
	 * @param label  names the update in the failure message
	 * @param checks the collected checks
	 * @param update the update
	 */
	private static void applyUpdate(
		@Nonnull Evita evita,
		@Nonnull String label,
		@Nonnull List<Executable> checks,
		@Nonnull Consumer<EvitaSessionContract> update
	) {
		try {
			evita.updateCatalog(TEST_CATALOG, update);
		} catch (RuntimeException ex) {
			checks.add(() -> fail(label + " threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex));
		}
	}

	/**
	 * Records the check that the ungrouped share histogram of `scope` is described as `expected`.
	 *
	 * @param evita    the engine
	 * @param stage    names the moment of the check
	 * @param scope    the scope whose histogram is checked
	 * @param expected the expected description, see {@link #describeHistogram}
	 * @param checks   the collected checks
	 */
	private static void recordHistogram(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nonnull Scope scope,
		@Nonnull String expected,
		@Nonnull List<Executable> checks
	) {
		recordHistogram(evita, stage, scope, null, expected, checks);
	}

	/**
	 * Records the checks that the share histograms of the first and the second group in `scope` are described as
	 * expected.
	 *
	 * @param evita       the engine
	 * @param stage       names the moment of the check
	 * @param scope       the scope whose histograms are checked
	 * @param firstGroup  the expected description of the histogram of the first group
	 * @param secondGroup the expected description of the histogram of the second group
	 * @param checks      the collected checks
	 */
	private static void recordGroupedHistograms(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nonnull Scope scope,
		@Nonnull String firstGroup,
		@Nonnull String secondGroup,
		@Nonnull List<Executable> checks
	) {
		recordHistogram(evita, stage, scope, FIRST_GROUP_PK, firstGroup, checks);
		recordHistogram(evita, stage, scope, SECOND_GROUP_PK, secondGroup, checks);
	}

	/**
	 * Records the check that the share histogram of `scope` - of the group `groupPk`, or the ungrouped one when it is
	 * `null` - is described as `expected`.
	 *
	 * @param evita    the engine
	 * @param stage    names the moment of the check
	 * @param scope    the scope whose histogram is checked
	 * @param groupPk  the primary key of the group whose histogram is checked, or `null` for the ungrouped one
	 * @param expected the expected description, see {@link #describeHistogram}
	 * @param checks   the collected checks
	 */
	private static void recordHistogram(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nonnull Scope scope,
		@Nullable Integer groupPk,
		@Nonnull String expected,
		@Nonnull List<Executable> checks
	) {
		final String subject = "share histogram of " + scope + (groupPk == null ? "" : ", group " + groupPk);
		try {
			final String actual = evita.queryCatalog(TEST_CATALOG, session -> {
				return describeHistogram(session, scope, groupPk);
			});
			checks.add(() -> assertEquals(expected, actual, stage + ": " + subject));
		} catch (RuntimeException ex) {
			checks.add(() -> fail(stage + ": query of the " + subject + " threw " + ex, ex));
		}
	}

	/**
	 * Records the checks that the facet index of `scope` holds the product's tag facet `expectedFacets` times, that
	 * the reference index of the scope finds the product by its tag `expectedReferences` times, and that the products
	 * found by their tag carry its facet as many times as the facet is in the scope and the product with the tag - a
	 * query the indexes of the partition of the tag answer, when the reference is indexed for partitioning.
	 *
	 * @param evita              the engine
	 * @param stage              names the moment of the check
	 * @param scope              the scope checked
	 * @param expectedFacets     the expected count of the facet, 1 or 0
	 * @param expectedReferences the expected count of the reference, 1 or 0
	 * @param checks             the collected checks
	 */
	private static void recordFacetState(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nonnull Scope scope,
		int expectedFacets,
		int expectedReferences,
		@Nonnull List<Executable> checks
	) {
		final FilterConstraint isTag = entityPrimaryKeyInSet(TAG_PK);
		recordCount(evita, stage + ", facet", scope, facetHaving(REFERENCE_TAG, isTag), expectedFacets, checks);
		recordCount(
			evita, stage + ", reference", scope, referenceHaving(REFERENCE_TAG, isTag), expectedReferences, checks
		);
		recordCount(
			evita, stage + ", facet among the products with the tag", scope,
			and(referenceHaving(REFERENCE_TAG, isTag), facetHaving(REFERENCE_TAG, isTag)),
			Math.min(expectedFacets, expectedReferences), checks
		);
	}

	/**
	 * Records the checks that the stored product is in `expectedScope` and no longer carries the attribute the facet
	 * condition reads.
	 *
	 * @param evita         the engine
	 * @param stage         names the moment of the check
	 * @param expectedScope the scope the product must be stored in
	 * @param source        the data the facet condition reads
	 * @param checks        the collected checks
	 */
	private static void recordConditionRemoved(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nonnull Scope expectedScope,
		@Nonnull ConditionSource source,
		@Nonnull List<Executable> checks
	) {
		try {
			final SealedEntity product = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, ALL_SCOPES, entityFetchAllContent())
					.orElseThrow();
			});
			final Serializable hidden;
			if (source == ConditionSource.ENTITY_ATTRIBUTE) {
				hidden = product.getAttribute(ATTRIBUTE_HIDDEN);
			} else {
				final ReferenceContract tag = product.getReference(REFERENCE_TAG, TAG_PK).orElseThrow();
				hidden = tag.getAttribute(ATTRIBUTE_HIDDEN);
			}
			checks.add(() -> assertEquals(expectedScope, product.getScope(), stage + ": scope of the stored product"));
			checks.add(() -> assertNull(hidden, stage + ": " + source + " `hidden` of the stored product"));
		} catch (RuntimeException ex) {
			checks.add(() -> fail(stage + ": fetching the stored product threw " + ex, ex));
		}
	}

	/**
	 * Records the check that `constraint` finds `expected` products in `scope`.
	 *
	 * @param evita      the engine
	 * @param stage      names the moment and the subject of the check
	 * @param scope      the scope queried
	 * @param constraint the constraint to filter by
	 * @param expected   the expected number of products
	 * @param checks     the collected checks
	 */
	private static void recordCount(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nonnull Scope scope,
		@Nonnull FilterConstraint constraint,
		int expected,
		@Nonnull List<Executable> checks
	) {
		try {
			final int actual = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(constraint, scope(scope))
					),
					EntityReference.class
				).getTotalRecordCount();
			});
			checks.add(() -> assertEquals(expected, actual, stage + " in " + scope));
		} catch (RuntimeException ex) {
			checks.add(() -> fail(stage + " query in " + scope + " threw " + ex, ex));
		}
	}

	/**
	 * Describes the share histogram the reference summary of `scope` computes - for the group `groupPk`, or the
	 * ungrouped one when it is `null`: its smallest and largest share, or the absence of the histogram.
	 *
	 * @param session the session to query
	 * @param scope   the scope to query
	 * @param groupPk the primary key of the group whose histogram is described, or `null` for the ungrouped one
	 * @return the description
	 */
	@Nonnull
	private static String describeHistogram(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope scope,
		@Nullable Integer groupPk
	) {
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(scope(scope)),
				require(
					referenceSummaryOfReferenceWithHistograms(
						REFERENCE_BRAND, FacetStatisticsDepth.COUNTS, null, null,
						histogramStatistics(BUCKET_COUNT, HISTOGRAM_SHARE)
					)
				)
			),
			EntityReference.class
		);
		final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
		final ReferenceGroupStatistics statistics;
		if (summary == null) {
			statistics = null;
		} else if (groupPk == null) {
			statistics = summary.getReferenceGroupStatistics(REFERENCE_BRAND);
		} else {
			statistics = summary.getReferenceGroupStatistics(REFERENCE_BRAND, groupPk);
		}
		final HistogramContract histogram = statistics == null ?
			null : statistics.getHistogramStatistics(HISTOGRAM_SHARE);
		return histogram == null ?
			"no histogram" : "min=" + plain(histogram.getMin()) + " max=" + plain(histogram.getMax());
	}

	/**
	 * Formats `number` without trailing zeros and without an exponent.
	 *
	 * @param number the number
	 * @return the plain form of the number
	 */
	@Nonnull
	private static String plain(@Nullable BigDecimal number) {
		return number == null ? "null" : number.stripTrailingZeros().toPlainString();
	}

	/**
	 * Defines the schemas - the reference of a product to a brand indexed for filtering and faceted in both scopes,
	 * with a share attribute bucketed into the share histogram in both scopes - two brands, an anchor product in each
	 * scope referencing both brands with two distinct shares, so the histogram of every scope spans a range of its
	 * own, and the product, stored in `origin`.
	 *
	 * @param session    the session of the catalog in warm-up
	 * @param origin     the scope the product starts in
	 * @param referenced TRUE when the product references the brand with the original share, FALSE when it starts
	 *                   without any reference
	 * @param gated      TRUE when the histogram admits only products whose `hidden` attribute is not TRUE, and the
	 *                   product starts hidden
	 */
	private static void setUpHistogramCatalog(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope origin,
		boolean referenced,
		boolean gated
	) {
		session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
		final String share = "$reference.attributes['" + ATTRIBUTE_SHARE + "']";
		final String visible = "($entity.attributes['" + ATTRIBUTE_HIDDEN + "'] ?? false) == false";
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_HIDDEN, Boolean.class, whichIs -> whichIs.filterableInScope(ALL_SCOPES).nullable())
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					whichIs
						.indexedForFilteringInScope(ALL_SCOPES)
						.facetedInScope(ALL_SCOPES)
						.withAttribute(
							ATTRIBUTE_SHARE, Integer.class,
							thatIs -> thatIs.filterableInScope(ALL_SCOPES).nullable()
						);
					for (Scope scope : ALL_SCOPES) {
						whichIs.bucketedInScope(scope, HISTOGRAM_SHARE, ExpressionFactory.parse(share));
						if (gated) {
							whichIs.bucketedPartiallyInScope(scope, ExpressionFactory.parse(visible));
						}
					}
				}
			)
			.updateVia(session);

		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK));
		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, SECOND_BRAND_PK));
		for (Scope scope : ALL_SCOPES) {
			session.createNewEntity(ENTITY_PRODUCT, scope == Scope.LIVE ? LIVE_ANCHOR_PK : ARCHIVED_ANCHOR_PK)
				.setReference(REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, ANCHOR_SHARE))
				.setReference(
					REFERENCE_BRAND, SECOND_BRAND_PK,
					whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, SECOND_ANCHOR_SHARE)
				)
				.setScope(scope)
				.upsertVia(session);
		}
		final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK).setScope(origin);
		if (referenced) {
			product.setReference(
				REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, ORIGINAL_SHARE)
			);
		}
		if (gated) {
			product.setAttribute(ATTRIBUTE_HIDDEN, true);
		}
		product.upsertVia(session);
	}

	/**
	 * Defines the schemas - the reference of a product to a brand, grouped by a brand group, indexed for filtering
	 * with both its entity and group components and faceted in both scopes, with a share attribute bucketed into the
	 * share histogram of every group in both scopes - two groups, five brands, an anchor product in each scope
	 * referencing two brands in each group with two distinct shares, so the histogram of every group in every scope
	 * spans a range of its own, and the product, stored in `origin`, referencing the brand in the first group.
	 *
	 * @param session the session of the catalog in warm-up
	 * @param origin  the scope the product starts in
	 */
	private static void setUpGroupedHistogramCatalog(@Nonnull EvitaSessionContract session, @Nonnull Scope origin) {
		session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
		final String share = "$reference.attributes['" + ATTRIBUTE_SHARE + "']";
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					whichIs
						.withGroupTypeRelatedToEntity(ENTITY_BRAND_GROUP)
						.indexedForFilteringInScope(ALL_SCOPES)
						.facetedInScope(ALL_SCOPES)
						.withAttribute(
							ATTRIBUTE_SHARE, Integer.class,
							thatIs -> thatIs.filterableInScope(ALL_SCOPES).nullable()
						);
					for (Scope scope : ALL_SCOPES) {
						whichIs
							.indexedWithComponentsInScope(
								scope,
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.bucketedInScope(scope, HISTOGRAM_SHARE, ExpressionFactory.parse(share));
					}
				}
			)
			.updateVia(session);

		for (int groupPk : new int[]{FIRST_GROUP_PK, SECOND_GROUP_PK}) {
			session.upsertEntity(session.createNewEntity(ENTITY_BRAND_GROUP, groupPk));
		}
		// the brand of the product, and two brands per group for the anchors
		for (int brandPk = BRAND_PK; brandPk <= BRAND_PK + 4; brandPk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_BRAND, brandPk));
		}
		for (Scope scope : ALL_SCOPES) {
			final EntityBuilder anchor = session
				.createNewEntity(ENTITY_PRODUCT, scope == Scope.LIVE ? LIVE_ANCHOR_PK : ARCHIVED_ANCHOR_PK)
				.setScope(scope);
			int brandPk = BRAND_PK + 1;
			for (int groupPk : new int[]{FIRST_GROUP_PK, SECOND_GROUP_PK}) {
				for (int anchorShare : new int[]{ANCHOR_SHARE, SECOND_ANCHOR_SHARE}) {
					anchor.setReference(
						REFERENCE_BRAND, brandPk++,
						whichIs -> whichIs
							.setGroup(ENTITY_BRAND_GROUP, groupPk)
							.setAttribute(ATTRIBUTE_SHARE, anchorShare)
					);
				}
			}
			anchor.upsertVia(session);
		}
		session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			.setReference(
				REFERENCE_BRAND, BRAND_PK,
				whichIs -> whichIs
					.setGroup(ENTITY_BRAND_GROUP, FIRST_GROUP_PK)
					.setAttribute(ATTRIBUTE_SHARE, ORIGINAL_SHARE)
			)
			.setScope(origin)
			.upsertVia(session);
	}

	/**
	 * Defines the schemas - the reference of a product to a tag indexed as `indexing` says and faceted in both scopes,
	 * but only while the `hidden` attribute `source` names is not TRUE - a tag, and the product referencing it,
	 * hidden, stored in `origin`.
	 *
	 * @param session  the session of the catalog in warm-up
	 * @param origin   the scope the product starts in
	 * @param indexing the way the reference to the tag is indexed
	 * @param source   the data the facet condition reads
	 */
	private static void setUpConditionalFacetCatalog(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope origin,
		@Nonnull TagIndexing indexing,
		@Nonnull ConditionSource source
	) {
		session.defineEntitySchema(ENTITY_TAG).withoutGeneratedPrimaryKey().updateVia(session);
		final String condition = "(" +
			(source == ConditionSource.ENTITY_ATTRIBUTE ? "$entity" : "$reference") +
			".attributes['" + ATTRIBUTE_HIDDEN + "'] ?? false) == false";
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_HIDDEN, Boolean.class, whichIs -> whichIs.filterableInScope(ALL_SCOPES).nullable())
			.withReferenceToEntity(
				REFERENCE_TAG, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					if (indexing == TagIndexing.FILTERING) {
						whichIs.indexedForFilteringInScope(ALL_SCOPES);
					} else {
						whichIs.indexedForFilteringAndPartitioningInScope(ALL_SCOPES);
					}
					whichIs
						.facetedInScope(ALL_SCOPES)
						.withAttribute(
							ATTRIBUTE_HIDDEN, Boolean.class,
							thatIs -> thatIs.filterableInScope(ALL_SCOPES).nullable()
						)
						.facetedPartiallyInScope(Scope.LIVE, ExpressionFactory.parse(condition))
						.facetedPartiallyInScope(Scope.ARCHIVED, ExpressionFactory.parse(condition));
				}
			)
			.updateVia(session);

		session.upsertEntity(session.createNewEntity(ENTITY_TAG, TAG_PK));
		final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK).setScope(origin);
		if (source == ConditionSource.ENTITY_ATTRIBUTE) {
			product
				.setAttribute(ATTRIBUTE_HIDDEN, true)
				.setReference(REFERENCE_TAG, TAG_PK);
		} else {
			product.setReference(REFERENCE_TAG, TAG_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_HIDDEN, true));
		}
		product.upsertVia(session);
	}

}
