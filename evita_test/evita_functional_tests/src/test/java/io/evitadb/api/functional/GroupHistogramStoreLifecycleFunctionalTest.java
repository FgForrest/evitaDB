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
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.MandatoryAttributesNotProvidedException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.CollectionIndexSummary;
import io.evitadb.api.statistics.CollectionIndexSummary.IndexTypeCount;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GroupHistogramEntityIndex;
import io.evitadb.index.HistogramCapableEntityIndex;
import io.evitadb.index.HistogramIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.virtual.CatalogReloadTestSupport;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ExceptionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.histogramStatistics;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryOfReferenceWithHistograms;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.WAL;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the life of the histogram store of a group - the index holding the reference histograms of one referenced
 * group as a whole - through every write that can create, change, empty or remove it, in a catalog in warm-up and in
 * an alive one.
 *
 * The reference of a product to a brand allows duplicates, is grouped by a brand group and indexed with both its
 * entity and its group component in the live and the archived scope, and faceted in the live scope so that a query
 * can compute its reference summary there. Its representative attribute `rank` decides
 * which reduced index of the group a reference sits in. It carries two histograms: `shareHistogram` over its own
 * `share` attribute, admitting only a reference with a positive rank, in both scopes, and `weightHistogram` over the
 * `weight` of the referenced brand, without a condition, in the live scope. The brand 10 weighs 5, the brand 11
 * weighs nothing, the brand 12 weighs 10; all of them are referenced in the group 1. The product carries a mandatory
 * `code`, so that an upsert omitting it fails after its references have been indexed.
 *
 * The store is described by the contributions of each histogram as `value@owner`, see {@link #describeStore}, and
 * what a query reads from it by the range of each histogram, see {@link #describeQueriedHistograms}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("The histogram store of a group through its life")
@Tag(ENGINE)
@Tag(INDEXING)
@Tag(HISTOGRAM)
class GroupHistogramStoreLifecycleFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String ENTITY_BRAND_GROUP = "brandGroup";
	private static final String REFERENCE_BRAND = "brand";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_SHARE = "share";
	private static final String ATTRIBUTE_RANK = "rank";
	private static final String ATTRIBUTE_WEIGHT = "weight";
	private static final String HISTOGRAM_SHARE = "shareHistogram";
	private static final String HISTOGRAM_WEIGHT = "weightHistogram";
	/**
	 * The condition of the share histogram: only a reference with a positive rank contributes to it.
	 */
	private static final String CONDITION_RANKED = "($reference.attributes['" + ATTRIBUTE_RANK + "'] ?? 0) > 0";
	private static final int PRODUCT_PK = 1;
	private static final int ANCHOR_PK = 100;
	private static final int SECOND_PRODUCT_PK = 2;
	private static final int GROUP_PK = 1;
	private static final int WEIGHTED_BRAND_PK = 10;
	private static final int WEIGHTLESS_BRAND_PK = 11;
	private static final int ANCHOR_BRAND_PK = 12;
	private static final int BRAND_WEIGHT = 5;
	private static final int CHANGED_BRAND_WEIGHT = 6;
	private static final int ANCHOR_BRAND_WEIGHT = 10;
	private static final int LATE_WEIGHT = 7;
	private static final int SHARE = 5;
	private static final int CHANGED_SHARE = 6;
	private static final int UNRANKED = 0;
	private static final int FIRST_RANK = 1;
	private static final int SECOND_RANK = 2;
	private static final int BUCKET_COUNT = 10;
	/**
	 * The description of a scope without the store of the group.
	 */
	private static final String NO_STORE = "no store";
	/**
	 * The description of a histogram the reference summary holds no entry for.
	 */
	private static final String NO_HISTOGRAM = "none";
	/**
	 * The description of a scope without the group-type index of the reference to a brand.
	 */
	private static final String NO_GROUP_TYPE_INDEX = "no group-type index";
	/**
	 * The checkpoint interval that keeps the committed tail of the write-ahead log unpublished until the engine closes.
	 */
	private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;

	/**
	 * A running engine over the stored data of one case, restartable on the same data.
	 */
	private static final class Engine {
		/**
		 * The configuration every start of the engine uses.
		 */
		@Nonnull private final EvitaConfiguration configuration;
		/**
		 * The engine started last.
		 */
		@Nonnull private Evita evita;

		/**
		 * Starts the engine.
		 *
		 * @param configuration the configuration of the engine
		 */
		Engine(@Nonnull EvitaConfiguration configuration) {
			this.configuration = configuration;
			this.evita = new Evita(configuration);
		}

		/**
		 * Returns the engine started last.
		 *
		 * @return the engine
		 */
		@Nonnull
		Evita evita() {
			return this.evita;
		}

		/**
		 * Closes the engine and starts a new one on the stored data.
		 */
		void restart() {
			this.evita.close();
			this.evita = new Evita(this.configuration);
			this.evita.waitUntilFullyInitialized();
		}

		/**
		 * Applies `upsert` in a session of the test catalog.
		 *
		 * @param upsert the writes of the session
		 */
		void update(@Nonnull Consumer<EvitaSessionContract> upsert) {
			this.evita.updateCatalog(TEST_CATALOG, upsert);
		}
	}

	/**
	 * The failure with which the application of a case gives up its transaction. The engine passes on an exception it
	 * does not know wrapped, so a case finds this one in the cause chain of what the session throws.
	 */
	private static final class TransactionGivenUpException extends RuntimeException {
		@Serial private static final long serialVersionUID = 1L;

		/**
		 * Creates the failure.
		 */
		TransactionGivenUpException() {
			super("The application gives up the transaction.");
		}
	}

	/**
	 * The body of one case, run against the engine after the catalog has been set up.
	 */
	@FunctionalInterface
	private interface CaseBody {

		/**
		 * Runs the case.
		 *
		 * @param engine the engine of the case
		 */
		void run(@Nonnull Engine engine);
	}

	/**
	 * A store is created by the first value a histogram of the group admits, not by a reference contributing nothing,
	 * and a restart keeps it under the same primary key with the same contributions.
	 *
	 * @param state the state the catalog is in when the case writes
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a store is created by the first admitted value and survives a restart")
	void shouldCreateStoreWithFirstAdmittedValueAndKeepItOverRestart(@Nonnull CatalogState state) {
		runCase(state, "created", engine -> {
			engine.update(createProduct(PRODUCT_PK, WEIGHTLESS_BRAND_PK, UNRANKED, SHARE));
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE), "no value is admitted yet");

			engine.update(rankOfEveryReference(PRODUCT_PK, FIRST_RANK));
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.LIVE));
			final int primaryKey = storePrimaryKey(engine.evita(), Scope.LIVE);

			engine.restart();
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.LIVE), "after a restart");
			assertEquals(primaryKey, storePrimaryKey(engine.evita(), Scope.LIVE), "the same store after a restart");
		});
	}

	/**
	 * A local change replacing the only contribution of the group withdraws it and adds its successor within one
	 * upsert; the store emptied in between is not dropped, it keeps its primary key.
	 *
	 * @param state the state the catalog is in when the case writes
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a store keeps its primary key when its only contribution is replaced")
	void shouldKeepStoreWhenItsOnlyContributionIsReplaced(@Nonnull CatalogState state) {
		runCase(state, "replaced", engine -> {
			engine.update(createProduct(PRODUCT_PK, WEIGHTLESS_BRAND_PK, FIRST_RANK, SHARE));
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.LIVE));
			final int primaryKey = storePrimaryKey(engine.evita(), Scope.LIVE);

			engine.update(shareOfEveryReference(PRODUCT_PK, CHANGED_SHARE));
			assertEquals("share=[6@1] weight=[]", describeStore(engine.evita(), Scope.LIVE));
			assertEquals(primaryKey, storePrimaryKey(engine.evita(), Scope.LIVE), "the same store");
		});
	}

	/**
	 * A local change removing the last contribution of the group drops the store, and a restart does not bring it
	 * back.
	 *
	 * @param state the state the catalog is in when the case writes
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a local change removing the last contribution drops the store")
	void shouldDropStoreWhenLocalChangeRemovesLastContribution(@Nonnull CatalogState state) {
		runCase(state, "droppedLocally", engine -> {
			engine.update(createProduct(PRODUCT_PK, WEIGHTLESS_BRAND_PK, FIRST_RANK, SHARE));
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.LIVE));

			engine.update(removeEveryReference(PRODUCT_PK));
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE));

			engine.restart();
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE), "after a restart");
		});
	}

	/**
	 * Two changes of the referenced brand in one session create the store with a value and take the value away
	 * again. The change that empties the store drops it, a restart finds none, and the next admitted value creates a
	 * store again, which a restart keeps.
	 *
	 * @param state the state the catalog is in when the case writes
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a store created and emptied by changes of the referenced entity in one session is dropped")
	void shouldDropStoreCreatedAndEmptiedByCrossEntityChangesInOneSession(@Nonnull CatalogState state) {
		runCase(state, "crossEntityEmptied", engine -> {
			engine.update(createProduct(PRODUCT_PK, WEIGHTLESS_BRAND_PK, UNRANKED, SHARE));
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE));

			engine.update(session -> {
				weightOf(WEIGHTLESS_BRAND_PK, LATE_WEIGHT).accept(session);
				weightOf(WEIGHTLESS_BRAND_PK, null).accept(session);
			});
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE), "dropped by the change emptying it");

			engine.restart();
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE), "after a restart");

			engine.update(rankOfEveryReference(PRODUCT_PK, FIRST_RANK));
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.LIVE));

			engine.restart();
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.LIVE), "after a restart");
		});
	}

	/**
	 * A store persisted by a restart and then emptied by a change of the referenced brand is dropped by that change
	 * and stays dropped over a restart. The next admitted value creates a store under another primary key, which a
	 * restart keeps.
	 *
	 * @param state the state the catalog is in when the case writes
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a persisted store emptied by a change of the referenced entity is dropped")
	void shouldDropPersistedStoreWhenCrossEntityChangeEmptiesIt(@Nonnull CatalogState state) {
		runCase(state, "crossEntitySweptPersisted", engine -> {
			engine.update(createProduct(PRODUCT_PK, WEIGHTED_BRAND_PK, UNRANKED, SHARE));
			assertEquals("share=[] weight=[5@1]", describeStore(engine.evita(), Scope.LIVE));
			final int primaryKey = storePrimaryKey(engine.evita(), Scope.LIVE);
			engine.restart();
			assertEquals("share=[] weight=[5@1]", describeStore(engine.evita(), Scope.LIVE), "persisted");

			engine.update(weightOf(WEIGHTED_BRAND_PK, null));
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE), "dropped by the change emptying it");

			engine.restart();
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE), "after a restart");

			engine.update(weightOf(WEIGHTED_BRAND_PK, CHANGED_BRAND_WEIGHT));
			assertEquals("share=[] weight=[6@1]", describeStore(engine.evita(), Scope.LIVE));
			final int newPrimaryKey = storePrimaryKey(engine.evita(), Scope.LIVE);
			assertNotEquals(primaryKey, newPrimaryKey, "a new store");

			engine.restart();
			assertEquals("share=[] weight=[6@1]", describeStore(engine.evita(), Scope.LIVE), "after a restart");
			assertEquals(newPrimaryKey, storePrimaryKey(engine.evita(), Scope.LIVE), "the same store after a restart");
		});
	}

	/**
	 * A change of the referenced brand writes the values of a grouped reference into the store of the group only, so
	 * the group-type index never holds a histogram. Once the last reference of the group leaves, the group-type index
	 * is empty and is dropped, also after a restart.
	 *
	 * @param state the state the catalog is in when the case writes
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a change of the referenced entity leaves no histogram in the group-type index")
	void shouldLeaveNoHistogramInGroupTypeIndexWhenReferencedEntityChanges(@Nonnull CatalogState state) {
		runCase(state, "groupTypeIndexWithoutResidue", engine -> {
			engine.update(createProduct(PRODUCT_PK, WEIGHTED_BRAND_PK, FIRST_RANK, SHARE));
			assertEquals("share=[5@1] weight=[5@1]", describeStore(engine.evita(), Scope.LIVE));
			assertEquals("share=[] weight=[]", describeGroupTypeIndex(engine.evita()), "after the local change");

			engine.update(weightOf(WEIGHTED_BRAND_PK, CHANGED_BRAND_WEIGHT));
			assertEquals("share=[5@1] weight=[6@1]", describeStore(engine.evita(), Scope.LIVE));
			assertEquals(
				"share=[] weight=[]", describeGroupTypeIndex(engine.evita()),
				"after the change of the referenced entity"
			);

			engine.update(removeEveryReference(PRODUCT_PK));
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE));
			assertEquals(
				NO_GROUP_TYPE_INDEX, describeGroupTypeIndex(engine.evita()),
				"dropped once the last reference of the group left"
			);

			engine.restart();
			assertEquals(NO_GROUP_TYPE_INDEX, describeGroupTypeIndex(engine.evita()), "after a restart");
		});
	}

	/**
	 * A store that changes of the referenced brand emptied is dropped, so a query computes no histogram of the group,
	 * before and after a restart, while the next admitted value is read again.
	 *
	 * @param state the state the catalog is in when the case writes
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a store emptied by changes of the referenced entity gives a query no histogram")
	void shouldAnswerNoHistogramWhenStoreIsEmptiedByCrossEntityChanges(@Nonnull CatalogState state) {
		runCase(state, "crossEntityEmptiedQueried", engine -> {
			engine.update(createProduct(PRODUCT_PK, WEIGHTLESS_BRAND_PK, UNRANKED, SHARE));
			engine.update(session -> {
				weightOf(WEIGHTLESS_BRAND_PK, LATE_WEIGHT).accept(session);
				weightOf(WEIGHTLESS_BRAND_PK, null).accept(session);
			});
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE), "dropped by the change emptying it");
			assertEquals("share=none weight=none", describeQueriedHistograms(engine.evita()));

			engine.restart();
			assertEquals("share=none weight=none", describeQueriedHistograms(engine.evita()), "after a restart");

			engine.update(rankOfEveryReference(PRODUCT_PK, FIRST_RANK));
			assertEquals("share=5..5 weight=none", describeQueriedHistograms(engine.evita()), "the next value");
		});
	}

	/**
	 * A transaction rolled back after a change of the referenced brand created the store leaves no store behind, and
	 * the same change committed creates one.
	 */
	@Test
	@DisplayName("a rolled-back transaction leaves no store a change of the referenced entity created")
	void shouldLeaveNoStoreWhenTransactionCreatingItFromReferencedEntityIsRolledBack() {
		runCase(CatalogState.ALIVE, "crossEntityRolledBack", engine -> {
			engine.update(createProduct(PRODUCT_PK, WEIGHTLESS_BRAND_PK, UNRANKED, SHARE));
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE));

			assertThrows(
				RollbackException.class,
				() -> engine.update(session -> {
					weightOf(WEIGHTLESS_BRAND_PK, LATE_WEIGHT).accept(session);
					session.setRollbackOnly();
				})
			);
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE));

			engine.update(weightOf(WEIGHTLESS_BRAND_PK, LATE_WEIGHT));
			assertEquals("share=[] weight=[7@1]", describeStore(engine.evita(), Scope.LIVE));

			engine.restart();
			assertEquals("share=[] weight=[7@1]", describeStore(engine.evita(), Scope.LIVE), "after a restart");
		});
	}

	/**
	 * An upsert that fails after its reference has been indexed leaves no store behind, and the same upsert done
	 * right creates one.
	 *
	 * @param state the state the catalog is in when the case writes
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a failed upsert leaves no store behind")
	void shouldLeaveNoStoreWhenFailedUpsertIsRolledBack(@Nonnull CatalogState state) {
		runCase(state, "failedUpsert", engine -> {
			engine.update(
				session -> assertThrows(
					MandatoryAttributesNotProvidedException.class,
					() -> session.upsertEntity(
						newProduct(session, PRODUCT_PK, WEIGHTLESS_BRAND_PK, FIRST_RANK, SHARE, false)
					)
				)
			);
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE));

			engine.update(createProduct(PRODUCT_PK, WEIGHTLESS_BRAND_PK, FIRST_RANK, SHARE));
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.LIVE));

			engine.restart();
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.LIVE), "after a restart");
		});
	}

	/**
	 * A transaction rolled back after its upsert created the store leaves no store behind, and the same upsert
	 * committed creates one.
	 */
	@Test
	@DisplayName("a rolled-back transaction leaves no store behind")
	void shouldLeaveNoStoreWhenTransactionIsRolledBack() {
		runCase(CatalogState.ALIVE, "rolledBack", engine -> {
			assertThrows(
				RollbackException.class,
				() -> engine.update(session -> {
					createProduct(PRODUCT_PK, WEIGHTLESS_BRAND_PK, FIRST_RANK, SHARE).accept(session);
					session.setRollbackOnly();
				})
			);
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE));

			engine.update(createProduct(PRODUCT_PK, WEIGHTLESS_BRAND_PK, FIRST_RANK, SHARE));
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.LIVE));

			engine.restart();
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.LIVE), "after a restart");
		});
	}

	/**
	 * The store of a scope follows its owners: archiving the only owner moves its contribution into the store of the
	 * archived scope and drops the store of the live one, restoring it moves it back.
	 *
	 * @param state the state the catalog is in when the case writes
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("the store of each scope follows its owner there and back")
	void shouldMoveContributionBetweenScopeStoresWithOwner(@Nonnull CatalogState state) {
		runCase(state, "scopes", engine -> {
			engine.update(createProduct(PRODUCT_PK, WEIGHTLESS_BRAND_PK, FIRST_RANK, SHARE));
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.LIVE));
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.ARCHIVED));

			engine.update(session -> session.archiveEntity(ENTITY_PRODUCT, PRODUCT_PK));
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE), "archived");
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.ARCHIVED), "archived");

			engine.update(session -> session.restoreEntity(ENTITY_PRODUCT, PRODUCT_PK));
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.LIVE), "restored");
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.ARCHIVED), "restored");

			engine.restart();
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.LIVE), "after a restart");
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.ARCHIVED), "after a restart");
		});
	}

	/**
	 * Removing the only owner of the group drops the store.
	 *
	 * @param state the state the catalog is in when the case writes
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("removing the only owner drops the store")
	void shouldDropStoreWhenOnlyOwnerIsRemoved(@Nonnull CatalogState state) {
		runCase(state, "ownerRemoved", engine -> {
			engine.update(createProduct(PRODUCT_PK, WEIGHTED_BRAND_PK, FIRST_RANK, SHARE));
			assertEquals("share=[5@1] weight=[5@1]", describeStore(engine.evita(), Scope.LIVE));

			engine.update(session -> session.deleteEntity(ENTITY_PRODUCT, PRODUCT_PK));
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE));

			engine.restart();
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE), "after a restart");
		});
	}

	/**
	 * A collection dropped and defined again starts without the store its predecessor had, loads after a restart and
	 * creates a new store with its first admitted value.
	 *
	 * @param state the state the catalog is in when the case writes
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a collection defined again starts without the store of its predecessor")
	void shouldStartWithoutStoreWhenCollectionIsDefinedAgain(@Nonnull CatalogState state) {
		runCase(state, "collectionRecreated", engine -> {
			engine.update(createProduct(PRODUCT_PK, WEIGHTLESS_BRAND_PK, FIRST_RANK, SHARE));
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.LIVE));

			engine.update(session -> session.deleteCollection(ENTITY_PRODUCT));
			engine.update(session -> {
				defineProduct(session);
				createProduct(PRODUCT_PK, WEIGHTLESS_BRAND_PK, UNRANKED, SHARE).accept(session);
			});
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE));

			engine.restart();
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE), "after a restart");

			engine.update(rankOfEveryReference(PRODUCT_PK, FIRST_RANK));
			assertEquals("share=[5@1] weight=[]", describeStore(engine.evita(), Scope.LIVE));
		});
	}

	/**
	 * With the owners of the group in two reduced indexes, a change of the weight of a referenced brand replaces the
	 * value in the store, and the local removal of the reference then withdraws the replaced value from it. The
	 * group-type index lists the two reduced indexes and never the store, whose rows stay empty.
	 *
	 * @param state the state the catalog is in when the case writes
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a change of the referenced entity updates the store a local change then withdraws from")
	void shouldUpdateStoreFromReferencedEntityAndWithdrawFromItLocally(@Nonnull CatalogState state) {
		runCase(state, "crossPath", engine -> {
			engine.update(createProduct(ANCHOR_PK, ANCHOR_BRAND_PK, FIRST_RANK, null));
			engine.update(createProduct(PRODUCT_PK, WEIGHTED_BRAND_PK, SECOND_RANK, null));
			assertEquals("share=[] weight=[5@1, 10@100]", describeStore(engine.evita(), Scope.LIVE));
			assertListsReducedIndexesOnly(engine.evita());

			engine.update(weightOf(WEIGHTED_BRAND_PK, CHANGED_BRAND_WEIGHT));
			assertEquals("share=[] weight=[6@1, 10@100]", describeStore(engine.evita(), Scope.LIVE));

			engine.update(removeEveryReference(PRODUCT_PK));
			assertEquals("share=[] weight=[10@100]", describeStore(engine.evita(), Scope.LIVE));

			engine.restart();
			assertEquals("share=[] weight=[10@100]", describeStore(engine.evita(), Scope.LIVE), "after a restart");
		});
	}

	/**
	 * A persisted store a change of the referenced brand empties and drops comes back whole when the transaction making
	 * the change is rolled back, and the same change committed drops it for good.
	 *
	 * The rollback is the transaction's. A change of a referenced entity drops the store in the dispatch that follows
	 * the consistency check of its own mutation, and a nested change of another entity writes only references of that
	 * entity, which a histogram may not read - so no failure of the mutation dropping the store can follow the drop.
	 *
	 * In one transaction of an alive catalog, removing the weight of the brand 10 withdraws the last value of the store
	 * and drops it, which the transaction reads itself, and a later upsert of the same transaction fails its
	 * consistency check, so the whole transaction is rolled back. The store keeps its primary key, its value and its
	 * place in both index maps, the index summary counts it once, a query reads its value, and a restart keeps it.
	 */
	@Test
	@DisplayName("a rolled-back transaction brings back the store a change of the referenced entity dropped")
	void shouldRestoreStoreWhenTransactionDroppingItIsRolledBack() {
		runCase(CatalogState.ALIVE, "sweepRolledBack", engine -> {
			engine.update(createProduct(PRODUCT_PK, WEIGHTED_BRAND_PK, UNRANKED, SHARE));
			assertEquals("share=[] weight=[5@1]", describeStore(engine.evita(), Scope.LIVE));
			final int primaryKey = storePrimaryKey(engine.evita(), Scope.LIVE);
			engine.restart();
			assertEquals("share=[] weight=[5@1]", describeStore(engine.evita(), Scope.LIVE), "persisted");
			assertEquals(1, countedStores(engine.evita()), "the index summary counts the persisted store");

			assertThrows(
				MandatoryAttributesNotProvidedException.class,
				() -> engine.update(session -> {
					weightOf(WEIGHTED_BRAND_PK, null).accept(session);
					// the transaction reads its own drop, so the rollback below has a drop to undo
					assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE), "the transaction dropped it");
					assertNull(
						productCollection(engine.evita()).getIndexByPrimaryKeyIfExists(primaryKey),
						"the transaction resolves the primary key of the dropped store to nothing"
					);
					assertEquals(
						NO_HISTOGRAM,
						describeQueriedHistogram(queryGroupStatistics(session, HISTOGRAM_WEIGHT), HISTOGRAM_WEIGHT),
						"a query of the transaction reads no histogram"
					);
					// the owner lacks its mandatory code, so the upsert fails and takes the transaction down with it
					session.upsertEntity(
						newProduct(session, SECOND_PRODUCT_PK, WEIGHTED_BRAND_PK, UNRANKED, SHARE, false)
					);
				})
			);
			assertEquals("share=[] weight=[5@1]", describeStore(engine.evita(), Scope.LIVE), "the store came back");
			assertEquals(primaryKey, storePrimaryKey(engine.evita(), Scope.LIVE), "under its primary key");
			assertSame(
				store(engine.evita(), Scope.LIVE),
				productCollection(engine.evita()).getIndexByPrimaryKeyIfExists(primaryKey),
				"the same store under its key and under its primary key"
			);
			assertEquals(1, countedStores(engine.evita()), "the index summary counts the store once");
			assertEquals("share=none weight=5..5", describeQueriedHistograms(engine.evita()), "a query reads it");

			engine.restart();
			assertEquals("share=[] weight=[5@1]", describeStore(engine.evita(), Scope.LIVE), "after a restart");
			assertEquals(primaryKey, storePrimaryKey(engine.evita(), Scope.LIVE), "the same store after a restart");
			assertEquals(1, countedStores(engine.evita()), "the index summary counts the store after a restart");

			engine.update(weightOf(WEIGHTED_BRAND_PK, null));
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE), "dropped by the change committed");
			assertEquals(0, countedStores(engine.evita()), "the index summary counts no store");

			engine.restart();
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE), "after a restart");
		});
	}

	/**
	 * Two changes of referenced brands in one transaction drop the store through the first and create it again
	 * through the second, so the store is replaced within the transaction; a rollback restores the old store, and the
	 * same changes committed leave the new one.
	 *
	 * The rollback is the transaction's, for the reason
	 * {@link #shouldRestoreStoreWhenTransactionDroppingItIsRolledBack} gives, and the two changes are two mutations of
	 * one transaction, because each change of a referenced entity reaches the store in a dispatch of its own.
	 *
	 * The product 1 references the brand 10 and holds the only value of the store; the product 2 references the brand
	 * 11, which weighs nothing, and so contributes nothing. In one transaction the weight of the brand 10 is removed,
	 * which drops the store, and the brand 11 gets a weight, which creates the store again under another primary key
	 * with the value of the product 2 only - the transaction reads both itself. The application then gives up the
	 * transaction: the old store comes back with its primary key, its value and its place in both index maps, and the
	 * primary key of the new one resolves to nothing. Committed, the changes leave the new store only, the old primary
	 * key resolves to nothing, and a restart keeps the new store.
	 */
	@Test
	@DisplayName("one transaction drops the store and creates it again; a rollback restores the old one")
	void shouldReplaceStoreDroppedAndCreatedAgainInOneTransaction() {
		runCase(CatalogState.ALIVE, "sweptAndRecreated", engine -> {
			engine.update(createProduct(PRODUCT_PK, WEIGHTED_BRAND_PK, UNRANKED, SHARE));
			engine.update(createProduct(SECOND_PRODUCT_PK, WEIGHTLESS_BRAND_PK, UNRANKED, SHARE));
			assertEquals(
				"share=[] weight=[5@1]", describeStore(engine.evita(), Scope.LIVE), "the product 2 contributes nothing"
			);
			final int primaryKey = storePrimaryKey(engine.evita(), Scope.LIVE);

			final AtomicInteger rolledBackPrimaryKey = new AtomicInteger();
			final RuntimeException givenUp = assertThrows(
				RuntimeException.class,
				() -> engine.update(session -> {
					weightOf(WEIGHTED_BRAND_PK, null).accept(session);
					assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE), "the first change dropped it");
					weightOf(WEIGHTLESS_BRAND_PK, LATE_WEIGHT).accept(session);
					assertEquals(
						"share=[] weight=[7@2]", describeStore(engine.evita(), Scope.LIVE),
						"the second change created it again"
					);
					rolledBackPrimaryKey.set(storePrimaryKey(engine.evita(), Scope.LIVE));
					assertNotEquals(primaryKey, rolledBackPrimaryKey.get(), "under another primary key");
					assertNull(
						productCollection(engine.evita()).getIndexByPrimaryKeyIfExists(primaryKey),
						"the transaction resolves the old primary key to nothing"
					);
					assertEquals(
						"7..7",
						describeQueriedHistogram(queryGroupStatistics(session, HISTOGRAM_WEIGHT), HISTOGRAM_WEIGHT),
						"a query of the transaction reads the new store"
					);
					throw new TransactionGivenUpException();
				})
			);
			assertNotNull(
				ExceptionUtils.findInCauseChain(givenUp, TransactionGivenUpException.class),
				() -> "The session must fail with the failure the application gave up with, but threw " + givenUp
			);
			assertEquals("share=[] weight=[5@1]", describeStore(engine.evita(), Scope.LIVE), "the old store came back");
			assertEquals(primaryKey, storePrimaryKey(engine.evita(), Scope.LIVE), "under its primary key");
			assertSame(
				store(engine.evita(), Scope.LIVE),
				productCollection(engine.evita()).getIndexByPrimaryKeyIfExists(primaryKey),
				"the same store under its key and under its primary key"
			);
			assertNull(
				productCollection(engine.evita()).getIndexByPrimaryKeyIfExists(rolledBackPrimaryKey.get()),
				"the primary key of the rolled-back store resolves to nothing"
			);
			assertEquals(1, countedStores(engine.evita()), "the index summary counts the old store once");
			assertEquals("share=none weight=5..5", describeQueriedHistograms(engine.evita()), "a query reads it");

			engine.update(session -> {
				weightOf(WEIGHTED_BRAND_PK, null).accept(session);
				weightOf(WEIGHTLESS_BRAND_PK, LATE_WEIGHT).accept(session);
			});
			assertEquals(
				"share=[] weight=[7@2]", describeStore(engine.evita(), Scope.LIVE), "the store holds the new value only"
			);
			final int newPrimaryKey = storePrimaryKey(engine.evita(), Scope.LIVE);
			assertNotEquals(
				primaryKey, newPrimaryKey, "the first change dropped the store before it was created again"
			);
			assertNull(
				productCollection(engine.evita()).getIndexByPrimaryKeyIfExists(primaryKey),
				"the old primary key resolves to nothing"
			);
			assertSame(
				store(engine.evita(), Scope.LIVE),
				productCollection(engine.evita()).getIndexByPrimaryKeyIfExists(newPrimaryKey),
				"the new store under its key and under its primary key"
			);
			assertEquals(1, countedStores(engine.evita()), "the index summary counts the new store only");

			engine.restart();
			assertEquals("share=[] weight=[7@2]", describeStore(engine.evita(), Scope.LIVE), "after a restart");
			assertEquals(newPrimaryKey, storePrimaryKey(engine.evita(), Scope.LIVE), "the new store after a restart");
			assertNull(
				productCollection(engine.evita()).getIndexByPrimaryKeyIfExists(primaryKey),
				"the old primary key resolves to nothing after a restart"
			);
			assertEquals(1, countedStores(engine.evita()), "the index summary counts one store after a restart");
		});
	}

	/**
	 * A transaction that drops the store, committed into the write-ahead log but not yet checkpointed, is replayed
	 * when the engine starts again, without a session, and drops the store again.
	 *
	 * The checkpoint is deferred, so the committed tail stays unpublished until the engine closes. The store of the
	 * weight of the brand 10 is persisted by a restart; the transaction removing the weight empties and drops it, and
	 * the storage is copied into an image before the transaction is published. The engine booted over the image loads
	 * the persisted store, replays the transaction and ends at its version without a store, which the index summary
	 * does not count. The next admitted value creates a store, which a restart keeps.
	 */
	@Test
	@Tag(WAL)
	@DisplayName("the replay of a write-ahead log tail drops the store its transaction emptied")
	void shouldDropStoreWhenReplayingTransactionEmptyingItFromWriteAheadLog() {
		final TestPaths paths = createTestPaths("GroupHistogramStoreLifecycle_sweepReplayed");
		final TestPaths image = createTestPaths("GroupHistogramStoreLifecycle_sweepReplayed_image");
		final TransactionOptions deferredCheckpoint = TransactionOptions.builder()
			.checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS)
			.build();
		final Engine engine = new Engine(
			newTestEvitaConfigurationBuilder(paths).transaction(deferredCheckpoint).build()
		);
		Engine replayed = null;
		try {
			engine.evita().defineCatalog(TEST_CATALOG);
			engine.update(session -> {
				setUpCatalog(session);
				session.goLiveAndClose();
			});
			engine.update(createProduct(PRODUCT_PK, WEIGHTED_BRAND_PK, UNRANKED, SHARE));
			// going live and the product are deferred like any other round; the clean close of the restart publishes
			// them
			engine.restart();
			assertEquals("share=[] weight=[5@1]", describeStore(engine.evita(), Scope.LIVE), "persisted");

			engine.update(weightOf(WEIGHTED_BRAND_PK, null));
			assertEquals(NO_STORE, describeStore(engine.evita(), Scope.LIVE), "dropped by the change emptying it");
			final long appliedVersion = takeImageOfUnpublishedTail(engine.evita(), paths, image);
			engine.evita().close();

			replayed = new Engine(newTestEvitaConfigurationBuilder(image).transaction(deferredCheckpoint).build());
			CatalogReloadTestSupport.awaitCatalogLoaded(replayed.evita(), TEST_CATALOG);
			assertEquals(appliedVersion, catalog(replayed.evita()).getVersion(), "the image reaches the transaction");
			assertEquals(NO_STORE, describeStore(replayed.evita(), Scope.LIVE), "dropped by the replayed transaction");
			assertEquals(0, countedStores(replayed.evita()), "the index summary counts no store");

			replayed.update(weightOf(WEIGHTED_BRAND_PK, CHANGED_BRAND_WEIGHT));
			assertEquals("share=[] weight=[6@1]", describeStore(replayed.evita(), Scope.LIVE));

			replayed.restart();
			assertEquals("share=[] weight=[6@1]", describeStore(replayed.evita(), Scope.LIVE), "after a restart");
		} finally {
			engine.evita().close();
			if (replayed != null) {
				replayed.evita().close();
			}
			cleanupTestPaths(paths);
			cleanupTestPaths(image);
		}
	}

	/**
	 * Runs a case on the catalog {@link #setUpCatalog} sets up, see
	 * {@link #runCase(CatalogState, String, Consumer, CaseBody)}.
	 *
	 * @param state the state the catalog is in when the case writes
	 * @param label names the directory of the case
	 * @param body  the case
	 */
	private void runCase(@Nonnull CatalogState state, @Nonnull String label, @Nonnull CaseBody body) {
		runCase(state, label, GroupHistogramStoreLifecycleFunctionalTest::setUpCatalog, body);
	}

	/**
	 * Sets the catalog up in warm-up, makes it alive where `state` says so, runs the case and closes the engine.
	 *
	 * @param state   the state the catalog is in when the case writes
	 * @param label   names the directory of the case
	 * @param setUp   defines the schemas and stores the entities of the case, in warm-up
	 * @param body    the case
	 */
	private void runCase(
		@Nonnull CatalogState state,
		@Nonnull String label,
		@Nonnull Consumer<EvitaSessionContract> setUp,
		@Nonnull CaseBody body
	) {
		final TestPaths paths = createTestPaths("GroupHistogramStoreLifecycle_" + label + "_" + state);
		final Engine engine = new Engine(newTestEvitaConfigurationBuilder(paths).build());
		try {
			engine.evita().defineCatalog(TEST_CATALOG);
			engine.update(session -> {
				setUp.accept(session);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});
			body.run(engine);
		} finally {
			engine.evita().close();
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Asserts that the group-type index lists exactly two indexes for the group, both reduced indexes, and that the
	 * store of the group holds no entity primary key and no language.
	 *
	 * @param evita the engine
	 */
	private static void assertListsReducedIndexesOnly(@Nonnull Evita evita) {
		final EntityCollection collection = productCollection(evita);
		final EntityIndex groupTypeIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, Scope.LIVE, REFERENCE_BRAND)
		);
		assertInstanceOf(ReferencedTypeEntityIndex.class, groupTypeIndex);
		final GroupHistogramEntityIndex store = store(evita, Scope.LIVE);
		assertNotNull(store);
		final int[] listed = ((ReferencedTypeEntityIndex) groupTypeIndex).getAllReferenceIndexes(GROUP_PK);
		assertEquals(2, listed.length, "one reduced index per rank");
		for (final int indexPrimaryKey : listed) {
			assertInstanceOf(ReducedGroupEntityIndex.class, collection.getIndexByPrimaryKeyIfExists(indexPrimaryKey));
			assertTrue(indexPrimaryKey != store.getPrimaryKey(), "the store is never listed");
		}
		assertTrue(store.getAllPrimaryKeys().isEmpty(), "a store holds no entity primary key");
		assertTrue(store.getLanguages().isEmpty(), "a store holds no language");
		assertFalse(store.isEmpty(), "a store holding values is not empty");
	}

	/**
	 * Returns the primary key of the store of the group in `scope`, failing when there is none.
	 *
	 * @param evita the engine
	 * @param scope the scope
	 * @return the primary key of the store
	 */
	private static int storePrimaryKey(@Nonnull Evita evita, @Nonnull Scope scope) {
		final GroupHistogramEntityIndex store = store(evita, scope);
		assertNotNull(store, "the store of the group in " + scope);
		return store.getPrimaryKey();
	}

	/**
	 * Describes the store of the group in `scope`: the contributions of the share and of the weight histogram, see
	 * {@link #describeHistogram}, or {@link #NO_STORE}.
	 *
	 * @param evita the engine
	 * @param scope the scope
	 * @return the description
	 */
	@Nonnull
	private static String describeStore(@Nonnull Evita evita, @Nonnull Scope scope) {
		final GroupHistogramEntityIndex store = store(evita, scope);
		if (store == null) {
			return NO_STORE;
		}
		return "share=" + describeHistogram(store, HISTOGRAM_SHARE) +
			" weight=" + describeHistogram(store, HISTOGRAM_WEIGHT);
	}

	/**
	 * Returns how many histogram stores of groups the index summary of the product collection counts in the live
	 * scope. The summary reads the index population of the collection, which the drop of a store and its reinstatement
	 * by a rollback move - in place in warm-up, at the commit in a transaction.
	 *
	 * @param evita the engine
	 * @return the number of stores the summary counts
	 */
	private static int countedStores(@Nonnull Evita evita) {
		final CollectionIndexSummary summary = evita.management()
			.getEntityCollectionStatistics(
				TEST_CATALOG, ENTITY_PRODUCT, EnumSet.of(CatalogStatisticsComponent.INDEX_SUMMARY)
			)
			.indexSummaryIfPresent()
			.orElseThrow();
		int count = 0;
		for (final IndexTypeCount typeCount : summary.byTypeAndScope()) {
			final boolean liveStores = typeCount.indexType() == EntityIndexType.REFERENCED_GROUP_HISTOGRAM &&
				typeCount.scope() == Scope.LIVE;
			if (liveStores) {
				count += typeCount.count();
			}
		}
		return count;
	}

	/**
	 * Describes the histograms the group-type index of the reference to a brand holds in the live scope: the
	 * contributions of the share and of the weight histogram, see {@link #describeHistogram}, or
	 * {@link #NO_GROUP_TYPE_INDEX}.
	 *
	 * @param evita the engine
	 * @return the description, `share=[] weight=[]` when the index holds no histogram
	 */
	@Nonnull
	private static String describeGroupTypeIndex(@Nonnull Evita evita) {
		final EntityIndex index = productCollection(evita).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, Scope.LIVE, REFERENCE_BRAND)
		);
		if (index == null) {
			return NO_GROUP_TYPE_INDEX;
		}
		final ReferencedTypeEntityIndex groupTypeIndex = assertInstanceOf(ReferencedTypeEntityIndex.class, index);
		return "share=" + describeHistogram(groupTypeIndex, HISTOGRAM_SHARE) +
			" weight=" + describeHistogram(groupTypeIndex, HISTOGRAM_WEIGHT);
	}

	/**
	 * Describes the contributions of one histogram of an index as `value@owner`, in ascending order of the values
	 * and the owners.
	 *
	 * @param index         the index holding the histogram
	 * @param histogramName the name of the histogram
	 * @return the description, for instance `[5@1, 10@100]`
	 */
	@Nonnull
	private static String describeHistogram(@Nonnull HistogramCapableEntityIndex index, @Nonnull String histogramName) {
		final HistogramIndex histogram = index.getHistogramIndex(histogramName);
		final FilterIndex filterIndex = histogram == null ? null : histogram.getFilterIndex(null);
		final List<String> contributions = new ArrayList<>(4);
		if (filterIndex != null) {
			for (final ValueToRecordBitmap bucket : filterIndex.getInvertedIndex().getValueToRecordBitmap()) {
				for (final int ownerPk : bucket.getRecordIds().getArray()) {
					contributions.add(bucket.getValue() + "@" + ownerPk);
				}
			}
		}
		return contributions.toString();
	}

	/**
	 * Describes the histograms of the group 1 the reference summary of the live scope computes: the share and the
	 * weight histogram, see {@link #describeQueriedHistogram}.
	 *
	 * @param evita the engine
	 * @return the description, for instance `share=5..5 weight=none`
	 */
	@Nonnull
	private static String describeQueriedHistograms(@Nonnull Evita evita) {
		return evita.queryCatalog(TEST_CATALOG, session -> {
			final ReferenceGroupStatistics statistics = queryGroupStatistics(
				session, HISTOGRAM_SHARE, HISTOGRAM_WEIGHT
			);
			return "share=" + describeQueriedHistogram(statistics, HISTOGRAM_SHARE) +
				" weight=" + describeQueriedHistogram(statistics, HISTOGRAM_WEIGHT);
		});
	}

	/**
	 * Queries the statistics of the group 1 the reference summary of the live scope computes in `session`, with the
	 * histograms `histogramNames`. A query reads what its own session has written, also inside a transaction.
	 *
	 * @param session        the session to query in
	 * @param histogramNames the names of the histograms to compute
	 * @return the statistics of the group, or `null` when the summary holds none
	 */
	@Nullable
	private static ReferenceGroupStatistics queryGroupStatistics(
		@Nonnull EvitaSessionContract session,
		@Nonnull String... histogramNames
	) {
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(scope(Scope.LIVE)),
				require(
					referenceSummaryOfReferenceWithHistograms(
						REFERENCE_BRAND, FacetStatisticsDepth.COUNTS, null, null,
						histogramStatistics(BUCKET_COUNT, histogramNames)
					)
				)
			),
			EntityReference.class
		);
		final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
		return summary == null ? null : summary.getReferenceGroupStatistics(REFERENCE_BRAND, GROUP_PK);
	}

	/**
	 * Describes one histogram of the statistics of the group as `min..max`, or {@link #NO_HISTOGRAM} when the group
	 * has no statistics or they hold no entry for the histogram.
	 *
	 * @param statistics    the statistics of the group, or `null` when the summary holds none
	 * @param histogramName the name of the histogram
	 * @return the description
	 */
	@Nonnull
	private static String describeQueriedHistogram(
		@Nullable ReferenceGroupStatistics statistics,
		@Nonnull String histogramName
	) {
		final HistogramContract histogram = statistics == null ?
			null : statistics.getHistogramStatistics(histogramName);
		if (histogram == null) {
			return NO_HISTOGRAM;
		}
		return histogram.getMin().stripTrailingZeros().toPlainString() + ".." +
			histogram.getMax().stripTrailingZeros().toPlainString();
	}

	/**
	 * Waits until the alive catalog has applied everything its write-ahead log holds, asserts that the last checkpoint
	 * lies behind it, and copies the storage into the image - the state a crash would leave, with the committed tail
	 * not yet published. The copy is taken while the engine is idle and before it closes, because a clean close
	 * publishes the tail.
	 *
	 * @param evita the running engine
	 * @param paths the directories of the engine
	 * @param image the directories to copy the storage into
	 * @return the catalog version the tail reaches
	 */
	private static long takeImageOfUnpublishedTail(
		@Nonnull Evita evita,
		@Nonnull TestPaths paths,
		@Nonnull TestPaths image
	) {
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(() -> {
				final Catalog current = catalog(evita);
				return current.getVersion() == current.getLastCatalogVersionInMutationStream();
			});
		final Catalog catalog = catalog(evita);
		final long appliedVersion = catalog.getVersion();
		final long persistedVersion = catalog.getLastPersistedCatalogVersion();
		assertTrue(
			persistedVersion < appliedVersion,
			"The image must hold the write in the write-ahead log past the last checkpoint: applied " +
				appliedVersion + ", checkpointed " + persistedVersion
		);
		CatalogReloadTestSupport.copyRecursively(paths.storage(), image.storage());
		assertSame(catalog, catalog(evita), "The catalog must not move while its storage is copied");
		return appliedVersion;
	}

	/**
	 * Returns the current catalog of the engine.
	 *
	 * @param evita the engine
	 * @return the catalog
	 */
	@Nonnull
	private static Catalog catalog(@Nonnull Evita evita) {
		return (Catalog) evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
	}

	/**
	 * Returns the store of the group in `scope`, or `null` when the collection holds none.
	 *
	 * @param evita the engine
	 * @param scope the scope
	 * @return the store or `null`
	 */
	@Nullable
	private static GroupHistogramEntityIndex store(@Nonnull Evita evita, @Nonnull Scope scope) {
		final EntityIndex index = productCollection(evita).getIndexByKeyIfExists(
			GroupHistogramEntityIndex.keyOf(REFERENCE_BRAND, GROUP_PK, scope)
		);
		if (index == null) {
			return null;
		}
		return assertInstanceOf(GroupHistogramEntityIndex.class, index);
	}

	/**
	 * Returns the product collection of the running engine.
	 *
	 * @param evita the engine
	 * @return the collection
	 */
	@Nonnull
	private static EntityCollection productCollection(@Nonnull Evita evita) {
		return (EntityCollection) evita.getCatalogInstance(TEST_CATALOG)
			.orElseThrow()
			.getCollectionForEntity(ENTITY_PRODUCT)
			.orElseThrow();
	}

	/**
	 * Creates the upsert storing the owner `ownerPk` with its code and one reference to the brand `brandPk` in the
	 * group 1 at `rank`, carrying `share` when it is not `null`.
	 *
	 * @param ownerPk the primary key of the owner
	 * @param brandPk the primary key of the referenced brand
	 * @param rank    the rank of the reference
	 * @param share   the share of the reference, or `null` for none
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createProduct(
		int ownerPk,
		int brandPk,
		int rank,
		@Nullable Integer share
	) {
		return session -> session.upsertEntity(newProduct(session, ownerPk, brandPk, rank, share, true));
	}

	/**
	 * Builds the owner `ownerPk` with one reference to the brand `brandPk` in the group 1 at `rank`, carrying `share`
	 * when it is not `null`, and with its mandatory code where `withCode` says so.
	 *
	 * @param session  the session to build in
	 * @param ownerPk  the primary key of the owner
	 * @param brandPk  the primary key of the referenced brand
	 * @param rank     the rank of the reference
	 * @param share    the share of the reference, or `null` for none
	 * @param withCode TRUE to give the owner its mandatory code
	 * @return the builder of the owner
	 */
	@Nonnull
	private static EntityBuilder newProduct(
		@Nonnull EvitaSessionContract session,
		int ownerPk,
		int brandPk,
		int rank,
		@Nullable Integer share,
		boolean withCode
	) {
		final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, ownerPk);
		if (withCode) {
			product.setAttribute(ATTRIBUTE_CODE, "product-" + ownerPk);
		}
		// the predicate matches no stored reference, so a new one is created - the only form a reference that allows
		// duplicates accepts
		product.setOrUpdateReference(
			REFERENCE_BRAND, brandPk, reference -> false,
			whichIs -> {
				whichIs.setGroup(ENTITY_BRAND_GROUP, GROUP_PK).setAttribute(ATTRIBUTE_RANK, rank);
				if (share != null) {
					whichIs.setAttribute(ATTRIBUTE_SHARE, share);
				}
			}
		);
		return product;
	}

	/**
	 * Creates the upsert setting the rank of every reference of the owner `ownerPk` to `rank`.
	 *
	 * @param ownerPk the primary key of the owner
	 * @param rank    the new rank
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> rankOfEveryReference(int ownerPk, int rank) {
		return everyReferenceAttribute(ownerPk, ATTRIBUTE_RANK, rank);
	}

	/**
	 * Creates the upsert setting the share of every reference of the owner `ownerPk` to `share`.
	 *
	 * @param ownerPk the primary key of the owner
	 * @param share   the new share
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> shareOfEveryReference(int ownerPk, int share) {
		return everyReferenceAttribute(ownerPk, ATTRIBUTE_SHARE, share);
	}

	/**
	 * Creates the upsert setting the attribute `attributeName` of every reference of the owner `ownerPk` to `value`.
	 * The references allow duplicates, so each mutation addresses its reference by the full key read from storage
	 * right before the upsert.
	 *
	 * @param ownerPk       the primary key of the owner
	 * @param attributeName the name of the reference attribute
	 * @param value         the new value
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> everyReferenceAttribute(
		int ownerPk,
		@Nonnull String attributeName,
		int value
	) {
		return session -> {
			final Collection<ReferenceContract> references = storedReferences(session, ownerPk);
			final List<LocalMutation<?, ?>> mutations = new ArrayList<>(references.size());
			for (final ReferenceContract reference : references) {
				mutations.add(
					new ReferenceAttributeMutation(
						reference.getReferenceKey(), new UpsertAttributeMutation(attributeName, value)
					)
				);
			}
			upsert(session, ownerPk, mutations);
		};
	}

	/**
	 * Creates the upsert removing every reference of the owner `ownerPk` to a brand.
	 *
	 * @param ownerPk the primary key of the owner
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> removeEveryReference(int ownerPk) {
		return session -> {
			final Collection<ReferenceContract> references = storedReferences(session, ownerPk);
			final List<LocalMutation<?, ?>> mutations = new ArrayList<>(references.size());
			for (final ReferenceContract reference : references) {
				mutations.add(new RemoveReferenceMutation(reference.getReferenceKey()));
			}
			upsert(session, ownerPk, mutations);
		};
	}

	/**
	 * Creates the upsert setting the weight of the brand `brandPk` to `weight`, or removing it when `weight` is
	 * `null`.
	 *
	 * @param brandPk the primary key of the brand
	 * @param weight  the new weight, or `null` for none
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> weightOf(int brandPk, @Nullable Integer weight) {
		return session -> {
			final EntityBuilder brand = session.getEntity(ENTITY_BRAND, brandPk, entityFetchAllContent())
				.orElseThrow()
				.openForWrite();
			if (weight == null) {
				brand.removeAttribute(ATTRIBUTE_WEIGHT);
			} else {
				brand.setAttribute(ATTRIBUTE_WEIGHT, weight);
			}
			brand.upsertVia(session);
		};
	}

	/**
	 * Returns the stored references of the owner `ownerPk` to a brand.
	 *
	 * @param session the session to read from
	 * @param ownerPk the primary key of the owner
	 * @return the stored references
	 */
	@Nonnull
	private static Collection<ReferenceContract> storedReferences(
		@Nonnull EvitaSessionContract session,
		int ownerPk
	) {
		return session.getEntity(ENTITY_PRODUCT, ownerPk, entityFetchAllContent())
			.orElseThrow()
			.getReferences(REFERENCE_BRAND);
	}

	/**
	 * Applies one upsert of the existing owner `ownerPk` made of `mutations`.
	 *
	 * @param session   the session to write in
	 * @param ownerPk   the primary key of the owner
	 * @param mutations the local mutations of the upsert, in the order they are applied
	 */
	private static void upsert(
		@Nonnull EvitaSessionContract session,
		int ownerPk,
		@Nonnull List<LocalMutation<?, ?>> mutations
	) {
		session.upsertEntity(
			new EntityUpsertMutation(
				ENTITY_PRODUCT, ownerPk, EntityExistence.MUST_EXIST, mutations.toArray(LocalMutation<?, ?>[]::new)
			)
		);
	}

	/**
	 * Defines the schemas and stores the group and the brands with their weights.
	 *
	 * @param session the session of the catalog in warm-up
	 */
	private static void setUpCatalog(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_BRAND)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_WEIGHT, Integer.class, thatIs -> thatIs.filterable().nullable())
			.updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
		defineProduct(session);

		session.upsertEntity(session.createNewEntity(ENTITY_BRAND_GROUP, GROUP_PK));
		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, WEIGHTED_BRAND_PK).setAttribute(
			ATTRIBUTE_WEIGHT, BRAND_WEIGHT
		));
		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, WEIGHTLESS_BRAND_PK));
		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, ANCHOR_BRAND_PK).setAttribute(
			ATTRIBUTE_WEIGHT, ANCHOR_BRAND_WEIGHT
		));
	}

	/**
	 * Defines the product schema with its mandatory code and its reference to a brand, see the class description.
	 *
	 * @param session the session to define it in
	 */
	private static void defineProduct(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_CODE, String.class)
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND,
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> whichIs
					.withGroupTypeRelatedToEntity(ENTITY_BRAND_GROUP)
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.facetedInScope(Scope.LIVE)
					.indexedWithComponentsInScope(
						Scope.LIVE,
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.indexedWithComponentsInScope(
						Scope.ARCHIVED,
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.withAttribute(
						ATTRIBUTE_SHARE, Integer.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
					)
					.withAttribute(
						ATTRIBUTE_RANK, Integer.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED).representative()
					)
					.bucketedInScope(
						Scope.LIVE, HISTOGRAM_SHARE, ExpressionFactory.parse(shareExpression()),
						ExpressionFactory.parse(CONDITION_RANKED)
					)
					.bucketedInScope(
						Scope.ARCHIVED, HISTOGRAM_SHARE, ExpressionFactory.parse(shareExpression()),
						ExpressionFactory.parse(CONDITION_RANKED)
					)
					.bucketedInScope(
						Scope.LIVE, HISTOGRAM_WEIGHT,
						ExpressionFactory.parse("$reference.referencedEntity?.attributes['" + ATTRIBUTE_WEIGHT + "']"),
						null
					)
			)
			.updateVia(session);
	}

	/**
	 * Returns the expression of the values of the share histogram.
	 *
	 * @return the expression
	 */
	@Nonnull
	private static String shareExpression() {
		return "$reference.attributes['" + ATTRIBUTE_SHARE + "']";
	}

}
