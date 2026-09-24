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


package io.evitadb.api.functional.query;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards which obstacles index selection raises for a `referenceHaving`, and therefore how much work it does to
 * raise them.
 *
 * # What this is really testing
 *
 * Deciding that the reduced-index plan loses is cheap; *discovering* that it loses has historically been the most
 * expensive part of such a query, because `IndexSelectionVisitor` resolved every partition the reference
 * advertises before looking at the two schema fields that already ruled the plan out. Skipping that resolution is
 * invisible in a query's results — the same rows come back either way — so it cannot be tested directly.
 *
 * It is visible in exactly one place: the **obstacle set**. `HIGH_CARDINALITY` is raised from the summed owner
 * counts of the partitions, so a decision taken without resolving them can only raise it when a bound settles it.
 * The bound available is the candidate count, which is sound because an advertised partition always holds at
 * least one owner. That makes the obstacle set a proxy for which code path ran:
 *
 * - **many small partitions** (`countExceedsTheLimit`): the count alone exceeds the limit, so `HIGH_CARDINALITY`
 *   is raised without resolving anything — delete the bound and it disappears.
 * - **few large partitions** (`sumExceedsTheLimitButCountDoesNot`): the count cannot settle it and the sum is
 *   never taken, because the schema already ruled the plan out — restore the old ordering and `HIGH_CARDINALITY`
 *   comes back.
 *
 * So the two tests below fail in opposite directions, and between them they pin both halves of the change.
 *
 * @author Claude (issue #1603), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference index selection")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
class ReferenceIndexSelectionFunctionalTest implements EvitaTestSupport {
	/**
	 * A reference with one partition per product — so its partition count equals the owner count and therefore
	 * exceeds half of it.
	 */
	private static final String REFERENCE_ONE_CATEGORY_EACH = "oneCategoryEach";
	/**
	 * A reference all products share, so it has few partitions but many rows.
	 */
	private static final String REFERENCE_SHARED_CATEGORY = "sharedCategory";
	/**
	 * A partitioned reference held by a minority of products, so the plan stays worth considering.
	 */
	private static final String REFERENCE_PARTITIONED = "partitionedCategory";

	private static final int PRODUCT_COUNT = 20;
	private static final int SHARED_CATEGORY_PK = 1_000;
	private static final int PARTITIONED_CATEGORY_PK = 2_000;
	/**
	 * How many products hold the partitioned reference. Kept below half of {@link #PRODUCT_COUNT} so the summed
	 * owner count stays under the eligibility limit and the alternative survives as a real plan candidate.
	 */
	private static final int PARTITIONED_OWNER_COUNT = 5;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReferenceIndexSelectionFunctionalTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
		defineSchema();
		writeData();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("a partition count over the limit raises HIGH_CARDINALITY without resolving the partitions")
	void countExceedsTheLimit() {
		// 20 partitions against 20 products, so the count alone is over the limit of 10 - and the count is a
		// sound lower bound on the summed owner counts, because every advertised partition holds an owner
		final String plan = alternativeFor(REFERENCE_ONE_CATEGORY_EACH);

		assertTrue(
			plan.contains("NOT_PARTITIONED_INDEX"),
			() -> "the reference is not partitioned, so that obstacle must be raised: " + plan
		);
		assertTrue(
			plan.contains("HIGH_CARDINALITY"),
			() -> "the candidate count alone exceeds the limit, so the obstacle must still be raised even though "
				+ "the partitions were never resolved - this is what the count bound is for: " + plan
		);
	}

	@Test
	@DisplayName("a row count over the limit is not raised when the schema already settles eligibility")
	void sumExceedsTheLimitButCountDoesNot() {
		// one shared partition holding all 20 products: the summed owner count is 20, over the limit, but the
		// partition count is 1 and settles nothing. The schema check rejects the plan first, so the sum that
		// would have raised HIGH_CARDINALITY is never taken - and reporting an obstacle that was never
		// determined would be a lie about what the planner did.
		final String plan = alternativeFor(REFERENCE_SHARED_CATEGORY);

		assertTrue(
			plan.contains("NOT_PARTITIONED_INDEX"),
			() -> "the reference is not partitioned, so that obstacle must be raised: " + plan
		);
		assertFalse(
			plan.contains("HIGH_CARDINALITY"),
			() -> "the partitions were never summed, so this obstacle was never determined and must not be "
				+ "claimed - seeing it here means the family was resolved before the schema was consulted: " + plan
		);
	}

	@Test
	@DisplayName("a partitioned reference under the limit remains eligible for its own plan")
	void partitionedReferenceUnderTheLimitStaysEligible() {
		// the regression case: this is the shape for which the reduced-index plan is worth having at all, so it
		// must survive the short-circuiting above untouched
		final String plan = alternativeFor(REFERENCE_PARTITIONED);

		assertFalse(
			plan.contains("not eligible"),
			() -> "a partitioned reference whose summed owner count is under the limit must stay eligible: " + plan
		);
	}

	@Test
	@DisplayName("filtering still returns the right entities through every reference shape")
	void filteringReturnsTheSameEntitiesRegardless() {
		// the obstacle set decides which PLAN runs, never which entities come back - so whatever the tests above
		// observe about planning, these counts must not move
		assertEquals(1, countThrough(REFERENCE_ONE_CATEGORY_EACH, 1));
		assertEquals(PRODUCT_COUNT, countThrough(REFERENCE_SHARED_CATEGORY, SHARED_CATEGORY_PK));
		assertEquals(PARTITIONED_OWNER_COUNT, countThrough(REFERENCE_PARTITIONED, PARTITIONED_CATEGORY_PK));
	}

	/**
	 * Declares the three references whose shapes the tests turn on.
	 */
	private void defineSchema() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY).updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_ONE_CATEGORY_EACH, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE)
					)
					.withReferenceToEntity(
						REFERENCE_SHARED_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE)
					)
					.withReferenceToEntity(
						REFERENCE_PARTITIONED, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
					)
					.updateVia(session);
			}
		);
	}

	/**
	 * Creates the categories and the products that reference them, in the three shapes the tests need.
	 */
	private void writeData() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					session.createNewEntity(Entities.CATEGORY, i).upsertVia(session);
				}
				session.createNewEntity(Entities.CATEGORY, SHARED_CATEGORY_PK).upsertVia(session);
				session.createNewEntity(Entities.CATEGORY, PARTITIONED_CATEGORY_PK).upsertVia(session);
				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, i)
						.setReference(REFERENCE_ONE_CATEGORY_EACH, i)
						.setReference(REFERENCE_SHARED_CATEGORY, SHARED_CATEGORY_PK);
					if (i <= PARTITIONED_OWNER_COUNT) {
						product.setReference(REFERENCE_PARTITIONED, PARTITIONED_CATEGORY_PK);
					}
					product.upsertVia(session);
				}
			}
		);
	}

	/**
	 * Runs a bare `referenceHaving` and returns the planner's own description of the reduced-index alternative.
	 *
	 * @param referenceName the reference to filter on
	 * @return the alternative's description, including its obstacle list
	 */
	@Nonnull
	private String alternativeFor(@Nonnull String referenceName) {
		final EvitaResponse<EntityReference> response = this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, EvitaResponse<EntityReference>>) session -> session.query(
				query(
					collection(Entities.PRODUCT),
					filterBy(referenceHaving(referenceName)),
					require(page(1, 1), queryTelemetry())
				),
				EntityReference.class
			)
		);
		final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
		assertNotNull(telemetry, "query telemetry must be present - it is the observable this test reads!");
		final List<String> alternatives = new ArrayList<>();
		collectReducedIndexAlternatives(telemetry, alternatives);
		assertEquals(
			1, alternatives.size(),
			() -> "exactly one reduced-index alternative must be registered for `" + referenceName
				+ "`, found: " + alternatives
		);
		return alternatives.getFirst();
	}

	/**
	 * Walks a telemetry tree collecting every argument describing a reduced-index alternative.
	 *
	 * @param node the node to walk
	 * @param sink collected descriptions
	 */
	private static void collectReducedIndexAlternatives(
		@Nonnull QueryTelemetry node,
		@Nonnull List<String> sink
	) {
		for (final String argument : node.getArguments()) {
			if (argument.contains("REFERENCED_ENTITY composed of")) {
				sink.add(argument);
			}
		}
		for (final QueryTelemetry step : node.getSteps()) {
			collectReducedIndexAlternatives(step, sink);
		}
	}

	/**
	 * Counts the products reachable through one reference and referenced entity.
	 *
	 * @param referenceName the reference to filter on
	 * @param referencedPk  the referenced entity to filter by
	 * @return number of products found
	 */
	private int countThrough(@Nonnull String referenceName, int referencedPk) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, Integer>) session -> session.query(
				query(
					collection(Entities.PRODUCT),
					filterBy(
						referenceHaving(referenceName, entityPrimaryKeyInSet(referencedPk))
					),
					require(page(1, 1))
				),
				EntityReference.class
			).getTotalRecordCount()
		);
	}

	/**
	 * Builds the engine configuration for this test's isolated storage.
	 *
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}
}
