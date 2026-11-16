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
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks parent-reading histogram conditions when new hierarchy nodes enter a scope, including inactive orphans, and
 * that the attribute such a condition watches can be written on an entity the scope's hierarchy holds no node for.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Parent histogram conditions across hierarchy node lifecycles")
@Tag(CONTRACT)
@Tag(HISTOGRAM)
@Tag(HIERARCHY)
class ParentHistogramConditionLifecycleFunctionalTest implements EvitaTestSupport {

	/**
	 * The histogram bounds every node change ends with: the two children of the active parent contribute 10 and 20,
	 * and the inactive product 300 admits nothing under it - in {@link NodeChange#ORPHAN_INACTIVE} that keeps out the
	 * share 1 of its child 301, which waited for it as an orphan.
	 */
	private static final String EXPECTED_BOUNDS = "10:20";
	/**
	 * The condition admitting a child whose parent is active.
	 */
	private static final String ACTIVE_PARENT_CONDITION =
		"($entity.parentEntity?.attributes['status'] ?? '') == 'ACTIVE'";
	/**
	 * The condition admitting a child that has no parent, or whose parent has no status.
	 */
	private static final String NO_PARENT_STATUS_CONDITION =
		"($entity.parentEntity?.attributes['status'] ?? 'NONE') == 'NONE'";

	/**
	 * How product 300 enters the scope under test with the status `INACTIVE`.
	 */
	enum NodeChange {
		/**
		 * Product 300 is created in the live scope.
		 */
		CREATE(Scope.LIVE, false, false),
		/**
		 * The stored live product 300 gets the status - the control for creation.
		 */
		CONTROL(Scope.LIVE, true, false),
		/**
		 * Product 300 is created in the live scope while its child 301 already waits for it as an orphan.
		 */
		ORPHAN_INACTIVE(Scope.LIVE, false, true),
		/**
		 * Product 300 is created in the archive.
		 */
		ARCHIVED_CREATE(Scope.ARCHIVED, false, false),
		/**
		 * The stored live product 300 moves to the archive and gets the status in the same upsert.
		 */
		ARCHIVED_MOVE(Scope.ARCHIVED, true, false);

		/**
		 * The scope product 300 ends in, and the scope whose histogram is checked.
		 */
		private final Scope targetScope;
		/**
		 * TRUE when product 300 is stored in the live scope before the change.
		 */
		private final boolean storedBefore;
		/**
		 * TRUE when child 301 is stored as an orphan of product 300 before the change.
		 */
		private final boolean orphanChildBefore;

		/**
		 * Creates the node change.
		 *
		 * @param targetScope       the scope product 300 ends in
		 * @param storedBefore      TRUE when product 300 is stored in the live scope before the change
		 * @param orphanChildBefore TRUE when child 301 is stored as an orphan of product 300 before the change
		 */
		NodeChange(@Nonnull Scope targetScope, boolean storedBefore, boolean orphanChildBefore) {
			this.targetScope = targetScope;
			this.storedBefore = storedBefore;
			this.orphanChildBefore = orphanChildBefore;
		}
	}

	/**
	 * Combines creation, its control, inactive orphan and archived-scope cases with both catalog states.
	 */
	@Nonnull
	static Stream<Arguments> scenarios() {
		final List<Arguments> arguments = new ArrayList<>(10);
		for (final CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (final NodeChange change : NodeChange.values()) {
				arguments.add(Arguments.of(state, change));
			}
		}
		return arguments.stream();
	}

	/**
	 * Verifies histogram bounds after the mutation and after reopening the catalog.
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("scenarios")
	@DisplayName("maintains the same histogram as the final hierarchy state")
	void shouldMaintainHistogramWhenHierarchyNodeChanges(
		@Nonnull CatalogState state, @Nonnull NodeChange change
	) {
		final TestPaths paths = createTestPaths("parentHistogram_" + state + "_" + change);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		final Scope targetScope = change.targetScope;
		try {
			try (final Evita evita = new Evita(configuration)) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					setUpCatalog(session, targetScope == Scope.ARCHIVED);
					if (change.orphanChildBefore) {
						session.createNewEntity("product", 301).setParent(300)
							.setReference("brand", 1, ref -> ref.setAttribute("share", 1)).upsertVia(session);
					} else if (change.storedBefore) {
						session.createNewEntity("product", 300).upsertVia(session);
					}
					if (state == CatalogState.ALIVE) {
						session.goLiveAndClose();
					}
				});
				evita.updateCatalog(TEST_CATALOG, session -> {
					if (change.storedBefore) {
						session.getEntity("product", 300, attributeContent()).orElseThrow().openForWrite()
							.setScope(targetScope).setAttribute("status", "INACTIVE").upsertVia(session);
					} else {
						session.createNewEntity("product", 300).setScope(targetScope)
							.setAttribute("status", "INACTIVE")
							.upsertVia(session);
					}
				});
				assertEquals(EXPECTED_BOUNDS, describeHistogram(evita, TEST_CATALOG, targetScope));
			}
			try (final Evita evita = new Evita(configuration)) {
				evita.waitUntilFullyInitialized();
				assertEquals(EXPECTED_BOUNDS, describeHistogram(evita, TEST_CATALOG, targetScope));
			}
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * The ways an entity writes the attribute a parent-reading condition watches while the archive's hierarchy index
	 * holds no node for it.
	 */
	enum UnplacedParentCase {
		/**
		 * The entity is created in the archive together with the attribute.
		 */
		ARCHIVED_CREATE,
		/**
		 * The entity is stored in the archive first and gets the attribute in a later upsert.
		 */
		ARCHIVED_UPDATE,
		/**
		 * The live entity moves to the archive and gets the attribute in the same upsert.
		 */
		ARCHIVED_MOVE,
		/**
		 * The entity is stored in the archive, the hierarchy is then indexed in the archive as well, and the entity
		 * gets the attribute afterwards - it was never placed in the archive's hierarchy.
		 */
		WIDENED_HIERARCHY
	}

	/**
	 * Combines every way of writing the watched attribute of an unplaced entity with both catalog states.
	 */
	@Nonnull
	static Stream<Arguments> unplacedParentScenarios() {
		final List<Arguments> arguments = new ArrayList<>(8);
		for (final CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (final UnplacedParentCase unplacedCase : UnplacedParentCase.values()) {
				arguments.add(Arguments.of(state, unplacedCase));
			}
		}
		return arguments.stream();
	}

	/**
	 * Writes the attribute a parent-reading histogram condition watches on an entity the archive's hierarchy index
	 * holds no node for, and checks that the write is stored rather than refused - an entity without a node heads no
	 * subtree there, so its change has no child to re-evaluate.
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("unplacedParentScenarios")
	@DisplayName("stores a watched attribute of an entity the scope's hierarchy index holds no node for")
	void shouldStoreEntityWhenHierarchyIsNotIndexedInHistogramScope(
		@Nonnull CatalogState state, @Nonnull UnplacedParentCase unplacedCase
	) {
		final TestPaths paths = createTestPaths("parentHistogramUnplaced_" + state + "_" + unplacedCase);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		try (final Evita evita = new Evita(configuration)) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, session -> {
				setUpLiveOnlyHierarchy(session);
				if (unplacedCase != UnplacedParentCase.ARCHIVED_CREATE) {
					final Scope storedScope = unplacedCase == UnplacedParentCase.ARCHIVED_MOVE ?
						Scope.LIVE : Scope.ARCHIVED;
					session.createNewEntity("product", 1).setScope(storedScope).upsertVia(session);
				}
				if (unplacedCase == UnplacedParentCase.WIDENED_HIERARCHY) {
					session.getEntitySchema("product").orElseThrow().openForWrite()
						.withHierarchyIndexedInScope(Scope.values()).updateVia(session);
				}
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});

			assertDoesNotThrow(() -> evita.updateCatalog(TEST_CATALOG, session -> {
				if (unplacedCase == UnplacedParentCase.ARCHIVED_CREATE) {
					session.createNewEntity("product", 1).setScope(Scope.ARCHIVED)
						.setAttribute("status", "ACTIVE").upsertVia(session);
				} else {
					session.getEntity("product", 1, Scope.values(), attributeContent()).orElseThrow().openForWrite()
						.setScope(Scope.ARCHIVED).setAttribute("status", "ACTIVE").upsertVia(session);
				}
			}));

			final SealedEntity product = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.getEntity("product", 1, Scope.values(), attributeContent()).orElseThrow();
			});
			assertEquals(Scope.ARCHIVED, product.getScope());
			assertEquals("ACTIVE", product.getAttribute("status"));
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Defines a product hierarchy indexed in the live scope only, with a histogram whose condition reads the parent's
	 * `status` in both scopes - defined while the collection is still empty, so no re-indexing takes part.
	 */
	private static void setUpLiveOnlyHierarchy(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema("brand").withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema("product").withoutGeneratedPrimaryKey()
			.withHierarchy()
			.withAttribute("status", String.class, attr -> attr.filterableInScope(Scope.values()).nullable())
			.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE, ref -> {
				ref.indexedForFilteringInScope(Scope.values()).facetedInScope(Scope.values())
					.withAttribute("share", Integer.class, attr -> attr.filterableInScope(Scope.values()).nullable());
				for (final Scope scope : Scope.values()) {
					ref.bucketedInScope(scope, "shares", ExpressionFactory.parse("$reference.attributes['share']"),
						ExpressionFactory.parse("($entity.parentEntity?.attributes['status'] ?? '') == 'ACTIVE'"));
				}
			}).updateVia(session);
		session.createNewEntity("brand", 1).upsertVia(session);
	}

	/**
	 * Reads the public histogram bounds for one scope.
	 */
	@Nonnull
	private static String describeHistogram(@Nonnull Evita evita, @Nonnull String catalog, @Nonnull Scope scope) {
		return evita.queryCatalog(catalog, session -> {
			final ReferenceSummary summary = session.query(query(collection("product"), filterBy(scope(scope)),
				require(referenceSummaryOfReferenceWithHistograms("brand", FacetStatisticsDepth.COUNTS, null, null,
					histogramStatistics(10, "shares")))), EntityReference.class).getExtraResult(ReferenceSummary.class);
			final HistogramContract histogram = summary.getReferenceGroupStatistics("brand")
				.getHistogramStatistics("shares");
			return histogram.getMin().toPlainString() + ":" + histogram.getMax().toPlainString();
		});
	}

	/**
	 * Moves an entity holding a value whose condition its current parent does not satisfy to the archive and back.
	 * Unindexing the entity from the live scope must remove the value anyway, and re-indexing it must add nothing,
	 * so the anchors' values alone remain - after the return, after a later deletion and after a restart. The direct
	 * deletion of such an entity is covered by
	 * {@link #shouldRemoveOwnerValueWhenChildEntityIsDeletedDirectly(CatalogState)}.
	 *
	 * @param state the catalog state during removal
	 */
	@ParameterizedTest
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("Remove all owner values whose condition no longer holds")
	void shouldRemoveOwnerValuesWhenCurrentParentNoLongerQualifies(@Nonnull CatalogState state) {
		final TestPaths paths = createTestPaths("parentHistogramRemoval_" + state);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		try {
			try (final Evita evita = new Evita(configuration)) {
				reachStaleParentContribution(evita, state);
				for (final Scope scope : new Scope[]{Scope.ARCHIVED, Scope.LIVE}) {
					evita.updateCatalog(TEST_CATALOG, session -> {
						session.upsertEntity(new EntityUpsertMutation(
							"product", 1, EntityExistence.MUST_EXIST, List.of(new SetEntityScopeMutation(scope))
						));
					});
					assertEquals(EXPECTED_BOUNDS, describeHistogram(evita, TEST_CATALOG, Scope.LIVE));
				}
				evita.updateCatalog(TEST_CATALOG, session -> {
					session.deleteEntity("product", 1);
				});
				assertEquals(EXPECTED_BOUNDS, describeHistogram(evita, TEST_CATALOG, Scope.LIVE));
			}
			try (final Evita evita = new Evita(configuration)) {
				evita.waitUntilFullyInitialized();
				assertEquals(EXPECTED_BOUNDS, describeHistogram(evita, TEST_CATALOG, Scope.LIVE));
			}
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Deletes a child entity whose histogram condition is false while it has its parent and true without one. Entity
	 * removal drops the parent before the references, so a deletion that evaluated the condition would withdraw a
	 * value the child never contributed. The deletion must succeed and leave the anchors' values alone, in memory and
	 * after a restart.
	 *
	 * @param state the catalog state during removal
	 */
	@ParameterizedTest
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("Delete a child whose condition holds only without a parent")
	void shouldDeleteChildEntityWhenParentConditionHoldsOnlyWithoutParent(@Nonnull CatalogState state) {
		final TestPaths paths = createTestPaths("parentHistogramDeletionWithoutParent_" + state);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		try {
			try (final Evita evita = new Evita(configuration)) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					setUpCatalog(session, false, NO_PARENT_STATUS_CONDITION);
					session.createNewEntity("product", 1).setParent(200)
						.setReference("brand", 1, ref -> ref.setAttribute("share", 5)).upsertVia(session);
					if (state == CatalogState.ALIVE) {
						session.goLiveAndClose();
					}
				});
				assertEquals(EXPECTED_BOUNDS, describeHistogram(evita, TEST_CATALOG, Scope.LIVE));

				evita.updateCatalog(TEST_CATALOG, session -> {
					session.deleteEntity("product", 1);
				});

				assertEquals(EXPECTED_BOUNDS, describeHistogram(evita, TEST_CATALOG, Scope.LIVE));
			}
			try (final Evita evita = new Evita(configuration)) {
				evita.waitUntilFullyInitialized();
				assertEquals(EXPECTED_BOUNDS, describeHistogram(evita, TEST_CATALOG, Scope.LIVE));
			}
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Deletes an entity directly while it holds a value whose condition its current parent does not satisfy. The
	 * deletion must remove the value anyway: the entity is stored again under the inactive parent, which adds nothing,
	 * so a value the deletion left behind would show up in the bounds, in memory and after a restart.
	 *
	 * @param state the catalog state during removal
	 */
	@ParameterizedTest
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("Remove the owner's value when the entity is deleted while its condition does not hold")
	void shouldRemoveOwnerValueWhenChildEntityIsDeletedDirectly(@Nonnull CatalogState state) {
		final TestPaths paths = createTestPaths("parentHistogramDirectDeletion_" + state);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		try {
			try (final Evita evita = new Evita(configuration)) {
				reachStaleParentContribution(evita, state);

				evita.updateCatalog(TEST_CATALOG, session -> {
					session.deleteEntity("product", 1);
				});
				evita.updateCatalog(TEST_CATALOG, session -> {
					session.createNewEntity("product", 1).setParent(300)
						.setReference("brand", 1, ref -> ref.setAttribute("share", 15)).upsertVia(session);
				});

				assertEquals(EXPECTED_BOUNDS, describeHistogram(evita, TEST_CATALOG, Scope.LIVE));
			}
			try (final Evita evita = new Evita(configuration)) {
				evita.waitUntilFullyInitialized();
				assertEquals(EXPECTED_BOUNDS, describeHistogram(evita, TEST_CATALOG, Scope.LIVE));
			}
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Stores the inactive product 300 and product 1 under it with the share 1 before the condition is attached, and
	 * checks that the share 1 is held. An attached condition is not recomputed (#409), so the value outlives a
	 * condition it does not satisfy. A parent change re-evaluates the condition (#1923), so this is the public way left
	 * to reach such a value.
	 *
	 * @param evita the instance holding no catalog yet
	 * @param state the catalog state the catalog is left in
	 */
	private static void reachStaleParentContribution(@Nonnull Evita evita, @Nonnull CatalogState state) {
		evita.defineCatalog(TEST_CATALOG);
		evita.updateCatalog(TEST_CATALOG, session -> {
			setUpCatalog(session, false, ACTIVE_PARENT_CONDITION, beforeCondition -> {
				beforeCondition.createNewEntity("product", 300).setAttribute("status", "INACTIVE")
					.upsertVia(beforeCondition);
				beforeCondition.createNewEntity("product", 1).setParent(300)
					.setReference("brand", 1, ref -> ref.setAttribute("share", 1)).upsertVia(beforeCondition);
			});
			if (state == CatalogState.ALIVE) {
				session.goLiveAndClose();
			}
		});
		assertEquals(
			"1:20", describeHistogram(evita, TEST_CATALOG, Scope.LIVE),
			"the share 1 stored before the condition was attached outlives it, because an attached condition is not "
				+ "recomputed (#409); once it is, the tests reaching a value whose condition no longer holds need "
				+ "another way to get there"
		);
	}

	/**
	 * Defines a conditional histogram admitting children of an active parent, and qualifying anchors in both scopes.
	 */
	private static void setUpCatalog(@Nonnull EvitaSessionContract session, boolean bothScopes) {
		setUpCatalog(session, bothScopes, ACTIVE_PARENT_CONDITION);
	}

	/**
	 * Defines a conditional histogram and anchors in both scopes. The anchors are stored before the condition is
	 * attached, so they contribute 10 and 20 whatever the condition says.
	 *
	 * @param session    the session of the catalog in warm-up
	 * @param bothScopes TRUE when the histogram and the anchors exist in the archive as well
	 * @param condition  the histogram condition attached after the anchors are stored
	 */
	private static void setUpCatalog(
		@Nonnull EvitaSessionContract session, boolean bothScopes, @Nonnull String condition
	) {
		setUpCatalog(session, bothScopes, condition, null);
	}

	/**
	 * Defines a conditional histogram and anchors in both scopes. The anchors, and whatever `beforeCondition` stores,
	 * are stored before the condition is attached, so they contribute whatever the condition says.
	 *
	 * @param session         the session of the catalog in warm-up
	 * @param bothScopes      TRUE when the histogram and the anchors exist in the archive as well
	 * @param condition       the histogram condition attached after the anchors are stored
	 * @param beforeCondition stores more entities after the anchors and before the condition is attached, or `null`
	 */
	private static void setUpCatalog(
		@Nonnull EvitaSessionContract session,
		boolean bothScopes,
		@Nonnull String condition,
		@Nullable Consumer<EvitaSessionContract> beforeCondition
	) {
		final Scope[] histogramScopes = bothScopes ? Scope.values() : new Scope[]{Scope.LIVE};
		session.defineEntitySchema("brand").withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema("product").withoutGeneratedPrimaryKey()
			.withHierarchyIndexedInScope(Scope.values())
			.withAttribute("status", String.class, attr -> attr.filterableInScope(Scope.values()).nullable())
			.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE, ref -> {
				ref.indexedForFilteringInScope(Scope.values()).facetedInScope(Scope.values())
					.withAttribute("share", Integer.class, attr -> attr.filterableInScope(Scope.values()).nullable());
				for (final Scope scope : histogramScopes) {
					ref.bucketedInScope(scope, "shares", ExpressionFactory.parse("$reference.attributes['share']"),
						null);
				}
			}).updateVia(session);
		session.createNewEntity("brand", 1).upsertVia(session);
		for (final Scope scope : histogramScopes) {
			final int parent = scope == Scope.LIVE ? 200 : 400;
			session.createNewEntity("product", parent).setScope(scope).upsertVia(session);
			session.getEntity("product", parent, Scope.values(), attributeContent()).orElseThrow().openForWrite()
				.setAttribute("status", "ACTIVE").upsertVia(session);
			for (final int value : new int[]{10, 20}) {
				session.createNewEntity("product", parent + value).setScope(scope).setParent(parent)
					.setReference("brand", 1, ref -> ref.setAttribute("share", value)).upsertVia(session);
			}
		}
		if (beforeCondition != null) {
			beforeCondition.accept(session);
		}
		session.getEntitySchema("product").orElseThrow().openForWrite()
			.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE, ref -> {
				for (final Scope scope : histogramScopes) {
					ref.bucketedInScope(scope, "shares", ExpressionFactory.parse("$reference.attributes['share']"),
						ExpressionFactory.parse(condition));
				}
			}).updateVia(session);
	}
}
