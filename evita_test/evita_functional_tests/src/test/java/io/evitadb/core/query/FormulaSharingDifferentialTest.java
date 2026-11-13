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

package io.evitadb.core.query;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.exception.InconsistentResultsException;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.RandomUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises sharing verification through the planner with real contexts and a test-owned index supplying its trees.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula sharing differential verification")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(CACHE)
class FormulaSharingDifferentialTest implements EvitaTestSupport {
	/** Catalog owned by this test. */
	private static final String CATALOG = "formulaSharingDifferential";
	/** Collection whose primary keys the supplied index answers. */
	private static final String PRODUCT = "product";
	/** Isolated storage and export paths. */
	private TestPaths paths;
	/** Engine providing real schemas and a pinned catalog. */
	private Evita evita;

	/** Creates two products independently of all other test fixtures. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FormulaSharingDifferentialTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(CATALOG, session -> {
			session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey().updateVia(session);
			session.upsertEntity(session.createNewEntity(PRODUCT, 1));
			session.upsertEntity(session.createNewEntity(PRODUCT, 2));
		});
	}

	/** Closes the test-owned engine and removes its directories. */
	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/** Verifies an actual merge, response equality and deterministic randomized ordering. */
	@Test
	@DisplayName("Matches the key-free response when equal subtrees really share an instance")
	void shouldMatchKeyFreeResponseWhenSubtreesMerge() {
		final SupersetIndex index = new SupersetIndex(() -> new OrFormula(
			new AndFormula(leaf(1, 2), leaf(1)),
			new AndFormula(leaf(1, 2), leaf(2))
		));
		final QueryPlanningContext context = context(index, true);
		final QueryPlan preferred = QueryPlanner.planQuery(context);
		final QueryPlan baseline = QueryPlanner.planQuery(context.createVerificationSibling());
		final OrFormula sharedTree = FormulaFinder.find(preferred.getFilter(), OrFormula.class, LookUp.SHALLOW)
			.iterator().next();
		final OrFormula unsharedTree = FormulaFinder.find(baseline.getFilter(), OrFormula.class, LookUp.SHALLOW)
			.iterator().next();
		assertSame(
			sharedTree.getInnerFormulas()[0].getInnerFormulas()[0],
			sharedTree.getInnerFormulas()[1].getInnerFormulas()[0]
		);
		assertNotSame(
			unsharedTree.getInnerFormulas()[0].getInnerFormulas()[0],
			unsharedTree.getInnerFormulas()[1].getInnerFormulas()[0]
		);
		final byte[] random = RandomUtils.getFrozenRandom();
		final EvitaResponse<EntityReference> response = preferred.execute(random);
		assertEquals(response, baseline.execute(random));
		assertEquals(2, response.getTotalRecordCount());
	}

	/** Planning without the verification flag leaves the filtering computation for the caller. */
	@Test
	@DisplayName("Leaves execution deferred when formula-sharing verification was not requested")
	void shouldDeferExecutionWithoutVerificationFlag() {
		final QueryPlanningContext context = context(
			new SupersetIndex(() -> new OrFormula(leaf(1), leaf(2))), false
		);
		final QueryPlan plan = QueryPlanner.planQuery(context);
		assertFalse(context.isFormulaSharingVerificationRequired());
		assertNull(plan.getFilter().getMemoizedResult());
	}

	/** An omitted identity input must make the flagged planner fail, while the baseline never requests its key. */
	@Test
	@DisplayName("Rejects a flagged query when a defective identity merges different results")
	void shouldRejectQueryWhenIdentityOmitsAnInput() {
		final InputBlindFormula first = new InputBlindFormula(1);
		final InputBlindFormula second = new InputBlindFormula(2);
		assertNotEquals(first.compute(), second.compute());
		assertEquals(first.getKey(), second.getKey());
		final List<InputBlindFormula> supplied = new ArrayList<>(8);
		final SupersetIndex index = new SupersetIndex(() -> {
			final InputBlindFormula left = new InputBlindFormula(1);
			final InputBlindFormula right = new InputBlindFormula(2);
			supplied.add(left);
			supplied.add(right);
			return new OrFormula(left, right);
		});

		final InconsistentResultsException error = assertThrows(
			InconsistentResultsException.class, () -> QueryPlanner.planQuery(context(index, true))
		);

		assertTrue(error.getMessage().contains("key-free baseline"), error.getMessage());
		assertTrue(error.getMessage().contains("preferred plan"), error.getMessage());
		assertEquals(4, supplied.size(), "One independent pair is planned on each side");
		assertTrue(supplied.get(0).keyRequests > 0, "Sharing must request the first operand's key");
		assertTrue(supplied.get(1).keyRequests > 0, "Sharing must request the second operand's key");
		for (final InputBlindFormula baselineOperand : supplied.subList(2, 4)) {
			assertEquals(0, baselineOperand.keyRequests, "The baseline must request no keys");
			assertNotNull(baselineOperand.getMemoizedResult(), "Every key-free operand must actually compute");
		}
		System.out.println("Non-vacuity witness: InconsistentResultsException: " + error.getPublicMessage());
		System.out.println("Merged defective formulas: 1 and 2; equal key hash=" + first.getKey().getHash64());
	}

	/** A sibling carries suppression into child contexts, even if their caller supplies a telemetry root. */
	@Test
	@DisplayName("Inherits key-free verification state in nested contexts")
	void shouldInheritVerificationStateInNestedContexts() {
		final SupersetIndex index = new SupersetIndex(() -> new OrFormula(leaf(1), leaf(2)));
		final QueryPlanningContext context = context(index, true);
		final QueryPlanningContext sibling = context.createVerificationSibling();
		final QueryPlanningContext nested = new QueryPlanningContext(
			sibling, (Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG),
			sibling.getEntityCollectionOrThrowException(PRODUCT, "test nested planning"),
			null, sibling.getEvitaRequest(), new QueryTelemetry(QueryPhase.OVERALL),
			Map.of(index.getIndexKey(), index), Map.of(index.getPrimaryKey(), index), NoCacheSupervisor.INSTANCE
		);
		assertTrue(context.isFormulaSharingVerificationRequired());
		assertTrue(context.isAlternativeIndexVerificationRequired());
		for (final QueryPlanningContext verification : List.of(sibling, nested)) {
			assertTrue(verification.isVerificationSibling());
			assertFalse(verification.isFormulaSharingEnabled());
			assertFalse(verification.isFormulaSharingVerificationRequired());
			assertFalse(verification.isAlternativeIndexVerificationRequired());
			assertFalse(verification.isTelemetryPlanCollected());
			assertNull(verification.getQueryFinishedEvent());
			assertSame(NoCacheSupervisor.INSTANCE, verification.getCacheSupervisor());
		}
		assertNull(QueryPlanner.planQuery(nested).getFilter().getMemoizedResult());
		assertNull(QueryPlanner.planNestedQuery(nested, () -> "nested verification baseline")
			.getFilter().getMemoizedResult());
		assertEquals(0, index.getActivity().getQueryCount());
	}

	/** Builds a session-optional real planning context over a supplied global index and both verification flags. */
	@Nonnull
	private QueryPlanningContext context(@Nonnull SupersetIndex index, boolean verify) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG);
		final EntityCollection collection = catalog.getCollectionForEntityInternal(PRODUCT).orElseThrow();
		final Query query = Query.query(
			collection(PRODUCT), filterBy(entityPrimaryKeyInSet(1, 2)), orderBy(random()),
			require(debug(verify ? new DebugMode[]{
				DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS
			} : new DebugMode[]{DebugMode.PREFER_INDEX_SCAN}))
		);
		return new QueryPlanningContext(
			catalog, collection, null, new EvitaRequest(query, OffsetDateTime.now(), EntityReference.class, null),
			Map.of(index.getIndexKey(), index), Map.of(index.getPrimaryKey(), index), NoCacheSupervisor.INSTANCE
		);
	}

	/** Supplies an independent constant operand. */
	@Nonnull
	private static Formula leaf(@Nonnull int... primaryKeys) {
		return new ConstantFormula(new BaseBitmap(primaryKeys));
	}

	/**
	 * Index seam that supplies fresh formula instances on every planning pass over the same two primary keys.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class SupersetIndex extends GlobalEntityIndex {
		/** Creates each pass's independent formula tree. */
		private final Supplier<Formula> formulas;

		/** Initializes the index's actual primary-key set and its formula supplier. */
		private SupersetIndex(@Nonnull Supplier<Formula> formulas) {
			super(1, PRODUCT, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE), true);
			this.formulas = formulas;
			insertPrimaryKeyIfMissing(1);
			insertPrimaryKeyIfMissing(2);
		}

		/** Supplies the tree before the production optimizer and deduplicator run. */
		@Nonnull
		@Override
		public Formula getAllPrimaryKeysFormula() {
			return this.formulas.get();
		}
	}

	/**
	 * Deliberately omits the primary key it returns from its identity, producing equal keys for different results.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class InputBlindFormula extends AbstractFormula {
		/** Stable discriminator generated from the formula's binary name. */
		private static final long CLASS_ID = -3944212450679939487L;
		/** Computation input deliberately absent from the identity. */
		private final int primaryKey;
		/** Counts identity evaluation to prove the baseline never requests a key. */
		private int keyRequests;

		/** Captures the input and initializes the leaf's cost. */
		private InputBlindFormula(int primaryKey) {
			this.primaryKey = primaryKey;
			initFields();
		}

		/** Deliberately drops the computation input. */
		@Nonnull
		@Override
		protected IdentityRecord identity() {
			this.keyRequests++;
			return new Identity();
		}

		/** Returns this formula's own discriminator. */
		@Override
		protected long getClassId() {
			return CLASS_ID;
		}

		/** Produces the result determined by the omitted input. */
		@Nonnull
		@Override
		protected Bitmap computeInternal() {
			return new BaseBitmap(this.primaryKey);
		}

		/** The leaf always returns one primary key. */
		@Override
		public int getEstimatedCardinality() {
			return 1;
		}

		/** Gives this small leaf a nonzero cost. */
		@Override
		public long getOperationCost() {
			return 1;
		}

		/** A terminal formula has no children to replace. */
		@Nonnull
		@Override
		public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
			assertEquals(0, innerFormulas.length);
			return this;
		}

		/**
		 * Empty identity used solely to prove that response verification catches an omitted input.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		private record Identity() implements IdentityRecord {
			/** Retains the correct class discriminator while omitting the primary key. */
			@Override
			public long classId() {
				return CLASS_ID;
			}
		}
	}
}
