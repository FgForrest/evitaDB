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

package io.evitadb.performance.conditionalindex.state;

import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.performance.setup.EvitaCatalogSetup;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

/**
 * Fixture for {@link io.evitadb.performance.conditionalindex.ConditionalHistogramTriggerBenchmark}: a catalog
 * carrying two conditional bucketed histograms that differ in exactly one predicate, so the two evaluation
 * granularities of the cross-entity trigger path can be compared without rebuilding the engine.
 *
 * **Why a schema parameter rather than two builds.** The executor decides between one filter run per mutation
 * and one per resolved contribution by asking whether the condition reads anything that differs between an
 * owner's references. A condition reading only the owner's parent cannot, so it keeps the single run; adding
 * one predicate on the referenced entity makes it reference-grained. Both are legitimate schemas, so a single
 * binary measures both paths at the same fan-out, against the same mutation, on the same data — a sharper
 * comparison than an A/B across two commits, and immune to unrelated drift between them.
 *
 * Only the reference {@link #granularity} names exists in a given trial, so the measured mutation fires
 * exactly one of the paths rather than several at once.
 *
 * **Three points, because the obvious two confound two factors.** A condition that reads the referenced entity
 * differs from an owner-level one both in *granularity* and in simply having one more predicate to evaluate,
 * and a two-series comparison prices those together. `ownerLevelTwoPredicates` carries a second predicate that
 * still reads only the parent, so it is evaluated once per mutation like `ownerLevel`; the difference between
 * it and `referenceGrained` is then the granularity alone.
 *
 * **Every reference qualifies under the reference-grained predicate.** The executor picks its evaluation
 * granularity from the *shape* of the condition, never from the data, so making the predicate accept every
 * reference does not move the measurement off the per-contribution path - it only equalises what the two
 * granularities index. Letting the predicate reject some references would hand the reference-grained series a
 * discount on index writes that the owner-level series cannot get, and the gap would then price two things at
 * once. That the predicate genuinely discriminates is a correctness property, and it is proven by the
 * functional suite rather than here.
 *
 * The measured mutation is a single attribute write on the parent entity. It resolves `fanOut` contributions
 * for every owner beneath it, which is the axis the cost is expected to scale on.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@State(Scope.Benchmark)
public class ConditionalHistogramTriggerBenchmarkState implements EvitaCatalogSetup {
	private static final String CATALOG_NAME = "conditionalHistogramTrigger";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_PARAMETER_VALUE = "parameterValue";
	private static final String REF_BY_PARENT = "paramByParentAttr";
	private static final String REF_BY_PARENT_AND_REF_ENTITY = "paramByParentAndRefEntity";
	private static final String HISTOGRAM_BY_PARENT = "parentHistogram";
	private static final String HISTOGRAM_BY_PARENT_AND_REF_ENTITY = "parentMixHistogram";
	private static final String ATTR_STATUS = "status";
	private static final String ATTR_WEIGHT = "weight";
	private static final String ATTR_BASIC_UNIT_VALUE = "basicUnitValue";
	/** Primary key of the hierarchy root whose attribute write is the measured mutation. */
	private static final int PARENT_PK = 1;
	/** How many owner entities sit directly beneath the parent. Fixed: it sizes the bitmaps, not the work. */
	private static final int OWNER_COUNT = 50;
	/** Value of {@link #granularity} selecting the condition the executor answers per contribution. */
	private static final String REFERENCE_GRAINED = "referenceGrained";
	/** Value of {@link #granularity} selecting the two-predicate owner-level control. */
	private static final String OWNER_LEVEL_TWO_PREDICATES = "ownerLevelTwoPredicates";
	/** Value of {@link #granularity} selecting the single-predicate owner-level baseline. */
	private static final String OWNER_LEVEL = "ownerLevel";
	/** The status value the conditional histograms test for, and its complement. */
	private static final String STATUS_ACTIVE = "ACTIVE";
	private static final String STATUS_INACTIVE = "INACTIVE";
	/** Weight given to every referenced entity, chosen so the reference-grained predicate accepts all of them. */
	private static final int QUALIFYING_WEIGHT = 10;

	/**
	 * Distinct referenced entities each owner points at — the number of contributions one parent mutation
	 * resolves, and therefore the number of filter evaluations the reference-grained path spends.
	 */
	@Param({"1", "4", "16", "64"})
	public int fanOut;

	/**
	 * Which condition shape the trial's single conditional reference carries — `ownerLevel` for the condition
	 * the executor answers with one filter run, `referenceGrained` for the one it answers per contribution.
	 */
	@Param({"ownerLevel", "ownerLevelTwoPredicates", "referenceGrained"})
	public String granularity;

	@Getter private Evita evita;
	/** Flipped on every invocation so each measured mutation is a real attribute write, never a no-op. */
	private boolean parentActive;
	/**
	 * The two measured mutations, built once in {@link #setUp()}.
	 *
	 * The measured operation applies a prepared mutation rather than reading the entity and editing it,
	 * so the score is the mutation-application path — which is where the trigger runs — and not an entity
	 * fetch plus a builder diff in front of it.
	 */
	private EntityUpsertMutation activateParent;
	private EntityUpsertMutation deactivateParent;

	/**
	 * Builds the catalog once per trial: the referenced entities, the hierarchy root, and the owners beneath
	 * it, each holding `fanOut` references on the single conditional reference {@link #granularity} selected.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		this.evita = createEmptyEvitaInstance(CATALOG_NAME);
		this.parentActive = false;
		this.evita.updateCatalog(CATALOG_NAME, session -> {
			session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
				.withAttribute(ATTR_BASIC_UNIT_VALUE, BigDecimal.class, whichIs -> whichIs.filterable().nullable())
				.withAttribute(ATTR_WEIGHT, Integer.class, whichIs -> whichIs.filterable().nullable())
				.updateVia(session);

			final boolean referenceGrained = REFERENCE_GRAINED.equals(this.granularity);
			final String referenceName = referenceGrained ? REF_BY_PARENT_AND_REF_ENTITY : REF_BY_PARENT;
			final String histogramName = referenceGrained
				? HISTOGRAM_BY_PARENT_AND_REF_ENTITY : HISTOGRAM_BY_PARENT;
			final String condition = conditionFor(this.granularity);

			session.defineEntitySchema(ENTITY_PRODUCT)
				.withHierarchy()
				.withAttribute(ATTR_STATUS, String.class, whichIs -> whichIs.filterable().nullable())
				.withReferenceToEntity(
					referenceName, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioning()
						.bucketed(
							histogramName,
							ExpressionFactory.parse(
								"$reference.referencedEntity?.attributes['" + ATTR_BASIC_UNIT_VALUE + "']"
							)
						)
						.bucketedPartially(ExpressionFactory.parse(condition))
				)
				.updateVia(session);

			for (int refPK = 1; refPK <= this.fanOut; refPK++) {
				session.createNewEntity(ENTITY_PARAMETER_VALUE, refPK)
					// every reference satisfies the weight predicate, so both granularities end up indexing
					// the same entries and the gap between them is the evaluation cost alone - see the class
					// javadoc for why the discriminating case is deliberately not the one measured here
					.setAttribute(ATTR_WEIGHT, QUALIFYING_WEIGHT)
					.setAttribute(ATTR_BASIC_UNIT_VALUE, BigDecimal.valueOf(refPK))
					.upsertVia(session);
			}

			session.createNewEntity(ENTITY_PRODUCT, PARENT_PK)
				.setAttribute(ATTR_STATUS, STATUS_INACTIVE)
				.upsertVia(session);

			for (int owner = 0; owner < OWNER_COUNT; owner++) {
				final EntityBuilder product = session
					.createNewEntity(ENTITY_PRODUCT, PARENT_PK + 1 + owner)
					.setParent(PARENT_PK);
				for (int refPK = 1; refPK <= this.fanOut; refPK++) {
					product.setReference(referenceName, refPK);
				}
				product.upsertVia(session);
			}
		});
		this.activateParent = statusMutation(STATUS_ACTIVE);
		this.deactivateParent = statusMutation(STATUS_INACTIVE);
	}

	/**
	 * Builds the `bucketedPartially` condition for one point of the {@link #granularity} axis.
	 *
	 * The three conditions vary two factors independently: how many predicates the condition carries, and
	 * whether any of them reads the referenced entity. Only the last forces per-contribution evaluation, so
	 * comparing the two-predicate owner-level control against the reference-grained condition isolates the
	 * cost of the granularity from the cost of simply having one more predicate to evaluate.
	 *
	 * @param granularity value of the {@link #granularity} parameter
	 * @return the expression source to compile into the schema
	 */
	@Nonnull
	private static String conditionFor(@Nonnull String granularity) {
		final String parentIsActive = "($entity.parentEntity?.attributes['" + ATTR_STATUS + "'] ?? '') == '"
			+ STATUS_ACTIVE + "'";
		return switch (granularity) {
			case OWNER_LEVEL -> parentIsActive;
			// a second predicate that still reads only the owner's parent, so the executor keeps answering the
			// condition once per mutation - this is the control that prices the extra predicate on its own
			case OWNER_LEVEL_TWO_PREDICATES -> parentIsActive
				+ " && ($entity.parentEntity?.attributes['" + ATTR_STATUS + "'] ?? '') != '" + STATUS_INACTIVE + "'";
			// the same predicate count, but the second one reads the referenced entity, which is what makes the
			// executor answer the condition once per resolved contribution
			case REFERENCE_GRAINED -> parentIsActive
				+ " && ($reference.referencedEntity.attributes['" + ATTR_WEIGHT + "'] ?? 0) > 0";
			default -> throw new GenericEvitaInternalError(
				"Unknown granularity `" + granularity + "` - the @Param list and this switch disagree."
			);
		};
	}

	/**
	 * Builds the upsert mutation writing a single `status` attribute onto the hierarchy root.
	 *
	 * @param status value to write
	 * @return mutation ready to be applied repeatedly
	 */
	@Nonnull
	private static EntityUpsertMutation statusMutation(@Nonnull String status) {
		return new EntityUpsertMutation(
			ENTITY_PRODUCT, PARENT_PK, EntityExistence.MUST_EXIST,
			new UpsertAttributeMutation(ATTR_STATUS, status)
		);
	}

	/**
	 * Writes the parent's `status`, flipping it so the condition genuinely changes and the cross-entity
	 * trigger has work to do on every invocation.
	 *
	 * @return the value written, returned so JMH cannot eliminate the call
	 */
	@Nonnull
	public String flipParentStatus() {
		this.parentActive = !this.parentActive;
		final EntityUpsertMutation mutation = this.parentActive ? this.activateParent : this.deactivateParent;
		this.evita.updateCatalog(CATALOG_NAME, session -> {
			session.upsertEntity(mutation);
		});
		return this.parentActive ? STATUS_ACTIVE : STATUS_INACTIVE;
	}

	@TearDown(Level.Trial)
	public void tearDown() {
		if (this.evita != null) {
			this.evita.close();
		}
	}
}
