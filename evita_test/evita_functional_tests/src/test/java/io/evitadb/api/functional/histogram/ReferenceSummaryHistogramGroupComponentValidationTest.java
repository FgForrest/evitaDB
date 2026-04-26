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

package io.evitadb.api.functional.histogram;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the fail-fast contract for bucketed histograms declared on grouped references:
 * the engine stores grouped-reference histogram data in the `ReducedGroupEntityIndex`
 * (see spec §1.3 of `conditional-bucket-indexing.md`), and that requires the group
 * `ReferencedTypeEntityIndex` to exist. The schema must therefore include
 * `REFERENCED_GROUP_ENTITY` in `indexedComponentsInScopes` for every scope that has
 * any bucketed histogram entry — otherwise the runtime silently drops every histogram
 * value at indexing time, producing empty `histogramStatistics` results downstream.
 *
 * The validation runs in `ReferenceSchema.validate(catalogSchema, entitySchema)`, which
 * `CatalogSchema.validate()` invokes during the session-close commit pipeline — i.e.
 * after the **final** mutation in `CatalogContract.updateSchema` has been applied.
 * Per-mutation validation is deliberately avoided because schema mutations are applied
 * incrementally and any individual mutation may leave the schema temporarily
 * inconsistent on the way to a valid final state (e.g. bucketed is added before
 * `REFERENCED_GROUP_ENTITY` is added to indexedComponents in the same batch).
 *
 * Test #1 below exercises the schema-load fail-fast guard. Test #2 lives in
 * {@link ReferenceSummaryHistogramGroupComponentRuntimeTest} and exercises the
 * defense-in-depth runtime guard, since it has to bypass the schema-load validation
 * to construct the inconsistent state.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference summary histogram — REFERENCED_GROUP_ENTITY indexing requirement")
public class ReferenceSummaryHistogramGroupComponentValidationTest
	extends AbstractReferenceSummaryHistogramFunctionalTest {

	/**
	 * Schema-load fail-fast contract: a bucketed histogram on a grouped reference must
	 * not be accepted unless `REFERENCED_GROUP_ENTITY` is part of `indexedComponentsInScopes`
	 * for the same scope. Without that component the group `ReducedGroupEntityIndex` is
	 * never created and every histogram value is silently dropped at indexing time.
	 *
	 * The error message must name the offending reference, the missing component and the
	 * scope so operators can fix the schema without reading engine internals. The
	 * exception fires at session commit (when `CatalogSchema.validate()` runs over the
	 * final post-mutation state), which is why the assertion wraps the entire
	 * `runWithInlineSchema` invocation rather than the individual `updateVia(session)`.
	 */
	@Test
	@DisplayName(
		"schema load fails when histogram is bucketed on grouped reference without REFERENCED_GROUP_ENTITY indexing"
	)
	void shouldFailSchemaLoadWhenGroupComponentMissing() {
		final EvitaInvalidUsageException ex = assertThrows(
			EvitaInvalidUsageException.class,
			() -> runWithInlineSchema(
				"refSummaryHistogramGroupComponent_validation",
				session -> {
					session.defineEntitySchema(ENTITY_PARAMETER)
						.withAttribute(ATTR_NAME, String.class, whichIs -> whichIs.filterable().nullable())
						.updateVia(session);

					session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
						.withAttribute(ATTR_NAME, String.class, whichIs -> whichIs.filterable().nullable())
						.withAttribute(
							ATTR_BASIC_UNIT_VALUE, BigDecimal.class,
							whichIs -> whichIs.filterable().indexDecimalPlaces(2).nullable()
						)
						.updateVia(session);

					session.defineEntitySchema(ENTITY_PRODUCT)
						.withReferenceToEntity(
							REF_PARAM_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedForFilteringAndPartitioning()
								// intentionally omit REFERENCED_GROUP_ENTITY — only the entity
								// component is configured
								.indexedWithComponents(ReferenceIndexedComponents.REFERENCED_ENTITY)
								.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
								.bucketed(
									HISTOGRAM_PRICE,
									ExpressionFactory.parse(
										"$reference.referencedEntity?.attributes['basicUnitValue']"
									)
								)
						)
						.updateVia(session);
				},
				null,
				evita -> {
					// the schema definition above is expected to be rejected at session commit;
					// this assertion block is unreachable in the after-fix world
				}
			),
			"Schema commit must reject bucketed histogram on grouped reference without REFERENCED_GROUP_ENTITY"
		);
		final String message = collectMessages(ex);
		assertTrue(
			message.contains(REF_PARAM_VALUES),
			"Error must name the offending reference (was: " + message + ")"
		);
		assertTrue(
			message.contains("REFERENCED_GROUP_ENTITY"),
			"Error must name the missing indexed component (was: " + message + ")"
		);
		assertTrue(
			message.contains(Scope.LIVE.name()),
			"Error must name the offending scope (was: " + message + ")"
		);
	}

	/**
	 * Boundary case: when the bucketed histogram is added in the same batch that adds
	 * `REFERENCED_GROUP_ENTITY` to indexedComponents, the schema is consistent at the
	 * end of `CatalogContract.updateSchema` even if intermediate states are not. This
	 * pins the "validate the final state, not each step" contract: the user must be
	 * allowed to add bucketed and the group component in any order within a single
	 * schema update.
	 */
	@Test
	@DisplayName(
		"schema load succeeds when bucketed and REFERENCED_GROUP_ENTITY are both set in the same batch"
	)
	void shouldAcceptBucketedWithGroupComponentInSameBatch() {
		runWithInlineSchema(
			"refSummaryHistogramGroupComponent_sameBatch",
			session -> {
				session.defineEntitySchema(ENTITY_PARAMETER)
					.withAttribute(ATTR_NAME, String.class, whichIs -> whichIs.filterable().nullable())
					.updateVia(session);

				session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
					.withAttribute(ATTR_NAME, String.class, whichIs -> whichIs.filterable().nullable())
					.withAttribute(
						ATTR_BASIC_UNIT_VALUE, BigDecimal.class,
						whichIs -> whichIs.filterable().indexDecimalPlaces(2).nullable()
					)
					.updateVia(session);

				session.defineEntitySchema(ENTITY_PRODUCT)
					.withReferenceToEntity(
						REF_PARAM_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.indexedWithComponents(ReferenceIndexedComponents.values())
							.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
							.bucketed(
								HISTOGRAM_PRICE,
								ExpressionFactory.parse(
									"$reference.referencedEntity?.attributes['basicUnitValue']"
								)
							)
					)
					.updateVia(session);
			},
			null,
			evita -> {
				// no further assertion needed — schema setup itself is the assertion
			}
		);
	}

	/**
	 * Concatenates messages from the exception chain so the assertions can find diagnostic
	 * context regardless of which layer wrapped the original `InvalidSchemaMutationException`.
	 */
	@Nonnull
	private static String collectMessages(@Nonnull Throwable ex) {
		final StringBuilder sb = new StringBuilder(256);
		Throwable cursor = ex;
		while (cursor != null) {
			if (cursor.getMessage() != null) {
				sb.append(cursor.getMessage());
				sb.append('\n');
			}
			cursor = cursor.getCause();
		}
		return sb.toString();
	}
}
