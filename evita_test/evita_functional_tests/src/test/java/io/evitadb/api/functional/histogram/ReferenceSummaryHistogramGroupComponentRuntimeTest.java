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
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaBucketedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Defense-in-depth runtime guard: even if a catalog manages to install a reference
 * schema where bucketed histograms are configured but `REFERENCED_GROUP_ENTITY` is not
 * in `indexedComponentsInScopes` (e.g., a catalog persisted before the validation rule
 * existed, or a path that bypasses the validating `_internalBuild` overload), the
 * indexing code must NOT silently drop the histogram value. It must throw a clear
 * `EvitaInternalError` naming the offending reference, histogram, scope and the
 * missing index — silent drops are unacceptable.
 *
 * The bypass route used here is `SetReferenceSchemaBucketedMutation` applied via
 * `session.updateCatalogSchema(...)`. That mutation builds the resulting schema via
 * the non-validating `_internalBuild` overload, which is the only legitimate way for
 * a bucketed-without-group-component state to slip into a live catalog at runtime.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference summary histogram — runtime guard for missing group RTEI")
public class ReferenceSummaryHistogramGroupComponentRuntimeTest
	extends AbstractReferenceSummaryHistogramFunctionalTest {

	@Test
	@DisplayName("histogram insertion fails loudly when group RTEI is missing")
	void shouldFailLoudlyWhenGroupTypeIndexMissing() {
		runWithInlineSchema(
			"refSummaryHistogramGroupComponent_runtimeGuard",
			session -> {
				// Step 1: define a clean schema. The reference is grouped (managed group type)
				// but `indexedComponents` deliberately omits `REFERENCED_GROUP_ENTITY` so the
				// group `ReducedGroupEntityIndex` will never be created. The schema as defined
				// here is consistent (no bucketed histogram yet) and passes initial validation.
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
							.indexedWithComponents(ReferenceIndexedComponents.REFERENCED_ENTITY)
							.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					)
					.updateVia(session);

				// Step 2: seed parameter and parameter-value entities so the upsert below has
				// a real reference target with a real group.
				session.createNewEntity(ENTITY_PARAMETER, 1)
					.setAttribute(ATTR_NAME, "Width")
					.upsertVia(session);
				session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
					.setAttribute(ATTR_NAME, "10cm")
					.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("10"))
					.upsertVia(session);
			},
			null,
			evita -> {
				// Step 3: inject the inconsistent state via a raw `SetReferenceSchemaBucketedMutation`
				// applied through `session.updateCatalogSchema(...)`. This mutation reaches the
				// non-validating `_internalBuild` overload and therefore bypasses the schema-time
				// guard added in this fix — exactly the route a stale persisted catalog would take.
				// Then upsert a Product carrying a `parameterValues` reference with a real group.
				// Without the runtime guard the histogram value would silently drop; with the guard
				// in place the upsert must throw a diagnostic `EvitaInternalError`.
				final EvitaInternalError ex = assertThrows(
					EvitaInternalError.class,
					() -> evita.updateCatalog(TEST_CATALOG, session -> {
						session.updateCatalogSchema(
							new ModifyEntitySchemaMutation(
								ENTITY_PRODUCT,
								new SetReferenceSchemaBucketedMutation(
									REF_PARAM_VALUES,
									new ScopedHistogramIndexDefinition[]{
										new ScopedHistogramIndexDefinition(
											Scope.LIVE,
											HISTOGRAM_PRICE,
											ExpressionFactory.parse(
												"$reference.referencedEntity?.attributes['basicUnitValue']"
											)
										)
									}
								)
							)
						);
						session.createNewEntity(ENTITY_PRODUCT, 1)
							.setReference(
								REF_PARAM_VALUES, 1,
								ref -> ref.setGroup(ENTITY_PARAMETER, 1)
							)
							.upsertVia(session);
					}),
					"Inserting a histogram value with a missing group RTEI must throw, not silently drop"
				);
				final String message = ex.getMessage();
				assertTrue(
					message.contains(REF_PARAM_VALUES),
					"Error must name the offending reference (was: " + message + ")"
				);
				assertTrue(
					message.contains(HISTOGRAM_PRICE),
					"Error must name the offending histogram (was: " + message + ")"
				);
				assertTrue(
					message.contains(Scope.LIVE.name()),
					"Error must name the offending scope (was: " + message + ")"
				);
				assertTrue(
					message.contains("REFERENCED_GROUP_ENTITY"),
					"Error must name the missing index component (was: " + message + ")"
				);
			}
		);
	}
}
