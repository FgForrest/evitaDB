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
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that a condition reading `$entity.parentEntity?.attributes['status']` sees no parent when the parent sits in
 * another scope than the one the condition is evaluated in, as `ExpressionProxyInstantiator` promises, instead of
 * failing to evaluate - so no write that evaluates such a condition is refused for it.
 *
 * Two shapes reach the evaluation on creation and on a scope change: a live product whose parent stays live is
 * archived, and a live product is created under an archived parent; each for a histogram condition and for a facet
 * condition. The changes of a live product whose current parent sits in the archive - a new parent, a removed parent,
 * a changed label - are stored as well, now that a parent or label change withdraws the histogram values of the
 * references reading it (#1923). The product reaches that state on two routes: its live parent is archived after the
 * product is stored, or the product is stored under the archived parent before the condition is attached (an attached
 * condition is not recomputed, #409).
 *
 * One condition cannot read both the label and an attribute of the parent: the schema refuses it, because the
 * cross-entity part has no filter equivalent for associated data. A label change therefore meets the parent-reading
 * condition through a second histogram of the same reference whose condition reads the label.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("A parent-reading condition of a product whose parent sits in the other scope")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(HISTOGRAM)
@Tag(FACET)
class CrossScopeParentConditionFunctionalTest implements EvitaTestSupport {

	private static final String PARENT_ACTIVE = "($entity.parentEntity?.attributes['status'] ?? '') == 'ACTIVE'";
	private static final String LABEL_SHOWN = "($entity.associatedData['label'] ?? '') == 'SHOWN'";
	private static final String SHARE_VALUE = "$reference.attributes['share']";

	/**
	 * What the parent-reading condition decides about.
	 */
	enum ConditionKind {
		/**
		 * The condition admits a reference to the `shares` histogram.
		 */
		HISTOGRAM,
		/**
		 * The condition admits a reference to the facet index.
		 */
		FACET
	}

	/**
	 * How the product comes to sit in the live scope under a parent in the archive before the change under test.
	 */
	enum Route {
		/**
		 * The product is stored under the live active parent 250, which is archived afterwards.
		 */
		PARENT_ARCHIVED_AFTER_PRODUCT,
		/**
		 * The product is stored under the archived active parent 400 before the condition is attached.
		 */
		STORED_BEFORE_CONDITION
	}

	/**
	 * The change of a live product whose parent sits in the archive, and the histograms of its reference.
	 */
	enum Change {
		/**
		 * The parent changes to the live active parent 200.
		 */
		SET_PARENT(false),
		/**
		 * The parent is removed.
		 */
		REMOVE_PARENT(false),
		/**
		 * The label changes; only the parent-reading histogram exists.
		 */
		LABEL_CHANGED_UNDER_PARENT_CONDITION(false),
		/**
		 * The label changes; beside the parent-reading histogram, the reference has a histogram whose condition reads
		 * the label.
		 */
		LABEL_CHANGED_WITH_LABEL_HISTOGRAM_BESIDE(true);

		/**
		 * TRUE when the reference has the label-reading histogram beside the parent-reading one.
		 */
		private final boolean labelHistogram;

		/**
		 * Creates the change.
		 *
		 * @param labelHistogram TRUE when the reference has the label-reading histogram beside the parent-reading one
		 */
		Change(boolean labelHistogram) {
			this.labelHistogram = labelHistogram;
		}

		/**
		 * Creates the local mutation of the product under test.
		 *
		 * @return the mutation
		 */
		@Nonnull
		LocalMutation<?, ?> mutation() {
			return switch (this) {
				case SET_PARENT -> new SetParentMutation(200);
				case REMOVE_PARENT -> new RemoveParentMutation();
				case LABEL_CHANGED_UNDER_PARENT_CONDITION, LABEL_CHANGED_WITH_LABEL_HISTOGRAM_BESIDE ->
					new UpsertAssociatedDataMutation("label", "HIDDEN");
			};
		}

		/**
		 * Describes product 1 once the change is stored, as {@link #describeStoredChange} reads it.
		 *
		 * @return the description of the stored change
		 */
		@Nonnull
		String storedState() {
			return switch (this) {
				case SET_PARENT -> "parent 200";
				case REMOVE_PARENT -> "no parent";
				case LABEL_CHANGED_UNDER_PARENT_CONDITION, LABEL_CHANGED_WITH_LABEL_HISTOGRAM_BESIDE -> "label HIDDEN";
			};
		}
	}

	/**
	 * Combines both catalog states with both condition kinds.
	 *
	 * @return the arguments of the shape tests
	 */
	@Nonnull
	static Stream<Arguments> kindsInBothCatalogStates() {
		final List<Arguments> result = new ArrayList<>(4);
		for (final CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (final ConditionKind kind : ConditionKind.values()) {
				result.add(Arguments.of(state, kind));
			}
		}
		return result.stream();
	}

	/**
	 * Combines both catalog states, both routes and every change.
	 *
	 * @return the arguments of {@link #shouldStoreChangeOfProductWhoseParentIsInOtherScope}
	 */
	@Nonnull
	static Stream<Arguments> changes() {
		final List<Arguments> result = new ArrayList<>(16);
		for (final CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (final Route route : Route.values()) {
				for (final Change change : Change.values()) {
					result.add(Arguments.of(state, route, change));
				}
			}
		}
		return result.stream();
	}

	/**
	 * Archives a live product whose parent stays live. Re-indexing it in the archive evaluates the condition against
	 * the live parent, which the archive does not see, so the product contributes nothing there.
	 *
	 * @param state the state the catalog is in when the product is archived
	 * @param kind  what the condition decides about
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("kindsInBothCatalogStates")
	@DisplayName("archives a product whose parent stays live")
	void shouldArchiveProductWhoseParentStaysLive(@Nonnull CatalogState state, @Nonnull ConditionKind kind) {
		final TestPaths paths = createTestPaths("crossScopeParent_archive_" + state + "_" + kind);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		try (final Evita evita = new Evita(configuration)) {
			evita.defineCatalog(TEST_CATALOG);
			attempt(evita, "set-up", session -> {
				setUpBothScopes(session, kind);
				session.createNewEntity("product", 1).setParent(200)
					.setReference("brand", 1, ref -> ref.setAttribute("share", 5)).upsertVia(session);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});
			attempt(evita, "archiving product 1", session -> {
				session.upsertEntity(new EntityUpsertMutation(
					"product", 1, EntityExistence.MUST_EXIST, List.of(new SetEntityScopeMutation(Scope.ARCHIVED))
				));
			});
			assertAnchorsOnly(evita, kind, Scope.ARCHIVED, 400);
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Creates a live product under an archived parent. The final add evaluates the condition against the archived
	 * parent, which the live scope does not see, so the product contributes nothing.
	 *
	 * @param state the state the catalog is in when the product is created
	 * @param kind  what the condition decides about
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("kindsInBothCatalogStates")
	@DisplayName("creates a live product under an archived parent")
	void shouldCreateLiveProductUnderArchivedParent(@Nonnull CatalogState state, @Nonnull ConditionKind kind) {
		final TestPaths paths = createTestPaths("crossScopeParent_create_" + state + "_" + kind);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		try (final Evita evita = new Evita(configuration)) {
			evita.defineCatalog(TEST_CATALOG);
			attempt(evita, "set-up", session -> {
				setUpBothScopes(session, kind);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});
			attempt(evita, "creating product 1", session -> {
				session.createNewEntity("product", 1).setParent(400)
					.setReference("brand", 1, ref -> ref.setAttribute("share", 5)).upsertVia(session);
			});
			assertAnchorsOnly(evita, kind, Scope.LIVE, 200);
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Brings product 1 into the live scope under a parent in the archive and applies the change under test, which
	 * must be stored. On both routes product 1 still holds the share 5 its condition no longer admits; the cell checks
	 * that first, so it knows the state it starts from. After the change it checks the histograms whose bounds the
	 * change decides: the live active parent 200 admits the share 5 to the shares, and the hidden label takes the share
	 * 5 out of the label shares. Under the other changes the parent condition stays false, so nothing enters or leaves
	 * the shares, and the share 5 left over keeps their bounds where they were - those bounds say nothing about the
	 * change.
	 *
	 * @param state  the state the catalog is in
	 * @param route  how the product reaches its state
	 * @param change the change under test
	 */
	@ParameterizedTest(name = "{0} {1} {2}")
	@MethodSource("changes")
	@DisplayName("stores a change of a live product whose parent sits in the archive")
	void shouldStoreChangeOfProductWhoseParentIsInOtherScope(
		@Nonnull CatalogState state, @Nonnull Route route, @Nonnull Change change
	) {
		final TestPaths paths = createTestPaths("crossScopeParentChange_" + state + "_" + route + "_" + change);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		try (final Evita evita = new Evita(configuration)) {
			evita.defineCatalog(TEST_CATALOG);
			attempt(evita, "set-up", session -> {
				setUpLiveHistograms(session, change.labelHistogram, route);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});
			if (route == Route.PARENT_ARCHIVED_AFTER_PRODUCT) {
				attempt(evita, "archiving parent 250", session -> {
					session.upsertEntity(new EntityUpsertMutation(
						"product", 250, EntityExistence.MUST_EXIST, List.of(new SetEntityScopeMutation(Scope.ARCHIVED))
					));
				});
			}
			assertEquals(
				"5:20", describeHistogram(evita, Scope.LIVE, "shares"),
				"shares before the change: the share 5 of product 1 outlives its condition, because neither archiving "
					+ "its parent nor attaching the condition re-evaluates it; once one does, this route needs another "
					+ "way to reach this state"
			);
			if (change.labelHistogram) {
				assertEquals(
					"5:20", describeHistogram(evita, Scope.LIVE, "labelShares"), "label shares before the change"
				);
			}

			attempt(evita, "the change " + change, session -> {
				session.upsertEntity(new EntityUpsertMutation(
					"product", 1, EntityExistence.MUST_EXIST, List.of(change.mutation())
				));
			});

			assertEquals(change.storedState(), describeStoredChange(evita, change), "product 1 after the change");
			if (change == Change.SET_PARENT) {
				assertEquals("5:20", describeHistogram(evita, Scope.LIVE, "shares"), "shares after the change");
			}
			if (change.labelHistogram) {
				assertEquals(
					"10:20", describeHistogram(evita, Scope.LIVE, "labelShares"), "label shares after the change"
				);
			}
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Applies `update` in its own read-write session and fails naming `step` and the exception when it throws.
	 *
	 * @param evita  the engine
	 * @param step   names the step
	 * @param update the update
	 */
	private static void attempt(
		@Nonnull Evita evita,
		@Nonnull String step,
		@Nonnull Consumer<EvitaSessionContract> update
	) {
		try {
			evita.updateCatalog(TEST_CATALOG, update);
		} catch (RuntimeException ex) {
			fail(step + " threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
		}
	}

	/**
	 * Checks that only the anchors under `anchorParent` contribute in `scope`: the `shares` histogram spans 10 to 20,
	 * or the facet of brand 1 is held by the two anchors alone.
	 *
	 * @param evita        the engine
	 * @param kind         what the condition decides about
	 * @param scope        the scope checked
	 * @param anchorParent the parent of the anchors of `scope`
	 */
	private static void assertAnchorsOnly(
		@Nonnull Evita evita,
		@Nonnull ConditionKind kind,
		@Nonnull Scope scope,
		int anchorParent
	) {
		switch (kind) {
			case HISTOGRAM -> assertEquals("10:20", describeHistogram(evita, scope, "shares"));
			case FACET -> assertEquals(
				new TreeSet<>(Set.of(anchorParent + 10, anchorParent + 20)), facetedProducts(evita, scope)
			);
		}
	}

	/**
	 * Reads the bounds of the histogram `histogramName` of `scope`.
	 *
	 * @param evita         the engine
	 * @param scope         the scope
	 * @param histogramName the name of the histogram: `shares` or `labelShares`
	 * @return the bounds as `min:max`, or `none` when the histogram is empty
	 */
	@Nonnull
	private static String describeHistogram(
		@Nonnull Evita evita,
		@Nonnull Scope scope,
		@Nonnull String histogramName
	) {
		return evita.queryCatalog(TEST_CATALOG, session -> {
			final ReferenceSummary summary = session.query(
				query(
					collection("product"),
					filterBy(scope(scope)),
					require(
						referenceSummaryOfReferenceWithHistograms(
							"brand", FacetStatisticsDepth.COUNTS, null, null, histogramStatistics(10, histogramName)
						)
					)
				),
				EntityReference.class
			).getExtraResult(ReferenceSummary.class);
			final ReferenceGroupStatistics statistics = summary == null ?
				null : summary.getReferenceGroupStatistics("brand");
			final HistogramContract histogram = statistics == null ?
				null : statistics.getHistogramStatistics(histogramName);
			return histogram == null ?
				"none" : histogram.getMin().toPlainString() + ":" + histogram.getMax().toPlainString();
		});
	}

	/**
	 * Describes what product 1 stores of the input `change` alters: its parent, or its label. Only that part is
	 * fetched, so a label change does not read the hierarchy placement of a product whose parent sits in the archive.
	 *
	 * @param evita  the engine
	 * @param change the change whose input is described
	 * @return the description, such as `parent 200`, `no parent` or `label HIDDEN`
	 */
	@Nonnull
	private static String describeStoredChange(@Nonnull Evita evita, @Nonnull Change change) {
		return evita.queryCatalog(TEST_CATALOG, session -> {
			return switch (change) {
				case SET_PARENT, REMOVE_PARENT -> session.getEntity("product", 1, hierarchyContent())
					.orElseThrow()
					.getParentEntity()
					.map(parent -> "parent " + parent.getPrimaryKey())
					.orElse("no parent");
				case LABEL_CHANGED_UNDER_PARENT_CONDITION, LABEL_CHANGED_WITH_LABEL_HISTOGRAM_BESIDE ->
					"label " + session.getEntity("product", 1, associatedDataContent("label"))
						.orElseThrow()
						.getAssociatedData("label");
			};
		});
	}

	/**
	 * Lists the products of `scope` holding the facet of brand 1.
	 *
	 * @param evita the engine
	 * @param scope the scope
	 * @return the primary keys, sorted
	 */
	@Nonnull
	private static TreeSet<Integer> facetedProducts(@Nonnull Evita evita, @Nonnull Scope scope) {
		return evita.queryCatalog(TEST_CATALOG, session -> {
			final TreeSet<Integer> result = new TreeSet<>();
			for (final EntityReference product : session.query(
				query(
					collection("product"),
					filterBy(scope(scope), facetHaving("brand", entityPrimaryKeyInSet(1))),
					require(page(1, 100))
				),
				EntityReference.class
			).getRecordData()) {
				result.add(product.getPrimaryKey());
			}
			return result;
		});
	}

	/**
	 * Defines the product hierarchy, the reference to a brand and the parent condition in both scopes - on the `shares`
	 * histogram or on the facet - and stores an active parent in each scope (200 live, 400 archived), each getting its
	 * status in an upsert of its own, with two anchor products under each (shares 10 and 20).
	 *
	 * @param session the session of the catalog in warm-up
	 * @param kind    what the condition decides about
	 */
	private static void setUpBothScopes(@Nonnull EvitaSessionContract session, @Nonnull ConditionKind kind) {
		session.defineEntitySchema("brand").withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema("product").withoutGeneratedPrimaryKey()
			.withHierarchyIndexedInScope(Scope.values())
			.withAttribute("status", String.class, attr -> attr.filterableInScope(Scope.values()).nullable())
			.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE, ref -> {
				ref.indexedForFilteringInScope(Scope.values()).facetedInScope(Scope.values())
					.withAttribute("share", Integer.class, attr -> attr.filterableInScope(Scope.values()).nullable());
				for (final Scope scope : Scope.values()) {
					switch (kind) {
						case HISTOGRAM -> ref.bucketedInScope(scope, "shares", ExpressionFactory.parse(SHARE_VALUE))
							.bucketedPartiallyInScope(scope, ExpressionFactory.parse(PARENT_ACTIVE));
						case FACET -> ref.facetedPartiallyInScope(scope, ExpressionFactory.parse(PARENT_ACTIVE));
					}
				}
			}).updateVia(session);
		session.createNewEntity("brand", 1).upsertVia(session);
		for (final Scope scope : Scope.values()) {
			final int parent = scope == Scope.LIVE ? 200 : 400;
			storeParent(session, parent, scope);
			for (final int value : new int[]{10, 20}) {
				session.createNewEntity("product", parent + value).setScope(scope).setParent(parent)
					.setReference("brand", 1, ref -> ref.setAttribute("share", value)).upsertVia(session);
			}
		}
	}

	/**
	 * Defines the product hierarchy, indexed in both scopes, with the live `shares` histogram reading the parent and,
	 * with `labelHistogram`, the live `labelShares` histogram reading the label; stores the live active parent 200 with
	 * the anchors 210 and 220 (shares 10 and 20) under it and product 1 with the share 5 and the label shown. On
	 * {@link Route#PARENT_ARCHIVED_AFTER_PRODUCT} the conditions are part of the schema from the start and product 1
	 * is stored under the live active parent 250; on {@link Route#STORED_BEFORE_CONDITION} product 1 is stored under
	 * the archived active parent 400 and the conditions are attached last.
	 *
	 * @param session        the session of the catalog in warm-up
	 * @param labelHistogram TRUE when the label-reading histogram exists beside the parent-reading one
	 * @param route          how the product reaches its state
	 */
	private static void setUpLiveHistograms(
		@Nonnull EvitaSessionContract session, boolean labelHistogram, @Nonnull Route route
	) {
		final boolean conditionFirst = route == Route.PARENT_ARCHIVED_AFTER_PRODUCT;
		session.defineEntitySchema("brand").withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema("product").withoutGeneratedPrimaryKey()
			.withHierarchyIndexedInScope(Scope.values())
			.withAttribute("status", String.class, attr -> attr.filterableInScope(Scope.values()).nullable())
			.withAssociatedData("label", String.class, data -> data.nullable())
			.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE, ref -> {
				ref.indexedForFilteringInScope(Scope.values()).facetedInScope(Scope.values())
					.withAttribute("share", Integer.class, attr -> attr.filterableInScope(Scope.values()).nullable());
				defineLiveHistograms(ref, labelHistogram, conditionFirst);
			}).updateVia(session);
		session.createNewEntity("brand", 1).upsertVia(session);
		storeParent(session, 200, Scope.LIVE);
		for (final int value : new int[]{10, 20}) {
			session.createNewEntity("product", 200 + value).setParent(200).setAssociatedData("label", "SHOWN")
				.setReference("brand", 1, ref -> ref.setAttribute("share", value)).upsertVia(session);
		}
		final int parent = conditionFirst ? 250 : 400;
		storeParent(session, parent, conditionFirst ? Scope.LIVE : Scope.ARCHIVED);
		session.createNewEntity("product", 1).setParent(parent).setAssociatedData("label", "SHOWN")
			.setReference("brand", 1, ref -> ref.setAttribute("share", 5)).upsertVia(session);
		if (!conditionFirst) {
			session.getEntitySchema("product").orElseThrow().openForWrite()
				.withReferenceToEntity(
					"brand", "brand", Cardinality.ZERO_OR_MORE, ref -> defineLiveHistograms(ref, labelHistogram, true)
				).updateVia(session);
		}
	}

	/**
	 * Defines the live `shares` histogram, reading the parent, and with `labelHistogram` the live `labelShares`
	 * histogram reading the label, both over the share.
	 *
	 * @param reference      the builder of the reference to the brand
	 * @param labelHistogram TRUE when the label-reading histogram is defined too
	 * @param withConditions TRUE when the histograms get their conditions, FALSE when they are defined without them
	 */
	private static void defineLiveHistograms(
		@Nonnull ReferenceSchemaBuilder reference, boolean labelHistogram, boolean withConditions
	) {
		final Expression value = ExpressionFactory.parse(SHARE_VALUE);
		reference.bucketedInScope(
			Scope.LIVE, "shares", value, withConditions ? ExpressionFactory.parse(PARENT_ACTIVE) : null
		);
		if (labelHistogram) {
			reference.bucketedInScope(
				Scope.LIVE, "labelShares", value, withConditions ? ExpressionFactory.parse(LABEL_SHOWN) : null
			);
		}
	}

	/**
	 * Stores an active parent in `scope` and gives it its status in an upsert of its own.
	 *
	 * @param session the session of the catalog in warm-up
	 * @param pk      the primary key of the parent
	 * @param scope   the scope of the parent
	 */
	private static void storeParent(@Nonnull EvitaSessionContract session, int pk, @Nonnull Scope scope) {
		session.createNewEntity("product", pk).setScope(scope).upsertVia(session);
		session.getEntity("product", pk, new Scope[]{scope}, attributeContent()).orElseThrow().openForWrite()
			.setAttribute("status", "ACTIVE").upsertVia(session);
	}
}
