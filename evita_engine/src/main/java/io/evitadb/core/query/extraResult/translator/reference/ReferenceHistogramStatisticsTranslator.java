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

package io.evitadb.core.query.extraResult.translator.reference;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.AttributeBetween;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.ReferenceHistogramStatistics;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.core.expression.trigger.HistogramValueDescriptor;
import io.evitadb.core.expression.trigger.HistogramValueDescriptorFactory;
import io.evitadb.core.expression.trigger.HistogramValueSource;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor.ProcessingScope;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryAdapter;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.HistogramRequest;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.RequestedBucketRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Translator for the {@link ReferenceHistogramStatistics} require constraint. Resolves the enclosing
 * {@link ReferenceSummaryProducer} (created by {@link ReferenceSummaryTranslator} or
 * {@link ReferenceSummaryOfReferenceTranslator}) and registers one
 * {@link HistogramRequest} per requested histogram index name.
 *
 * The translator does not drive traversal itself — the enclosing reference-summary translators handle the
 * dispatch and push a {@link ProcessingScope} carrying the current reference schema before invoking this
 * translator, so lookups remain self-contained.
 *
 * Validation performed here (fail-fast at plan time):
 *
 * - the histogram index name must exist on the reference schema in **every** active scope
 * - the resolved {@link HistogramValueDescriptor} must be consistent across scopes (source, attribute name,
 *   locale flag, plain type) — a divergence indicates a schema bug and throws
 * - if the source attribute is localized, the request must carry a locale
 *
 * @author Jan Novotný (<a href="mailto:novotny@fg.cz">novotny@fg.cz</a>), FG Forrest, a.s. (c) 2026
 */
public class ReferenceHistogramStatisticsTranslator implements RequireConstraintTranslator<ReferenceHistogramStatistics> {

	/**
	 * Resolves the enclosing {@link ReferenceSummaryProducer} and registers one {@link HistogramRequest} for each
	 * histogram name listed in the constraint.
	 *
	 * The constraint is assumed to be applicable — inapplicable constraints are stripped in the query
	 * normalization phase before any translator is invoked, so there is no `isApplicable()` short-circuit here.
	 *
	 * Fail-fast validation order (each check throws immediately on failure, before processing any further names):
	 *
	 * 1. reference schema must be present in the current processing scope — indicates programmer error if absent;
	 * 2. a {@link ReferenceSummaryProducer} wired with {@link ReferenceSummaryAdapter} must already exist in the
	 *    planner — the enclosing reference-summary translator is responsible for creating it before dispatching children;
	 * 3. for each histogram name: the index must be defined on the reference schema in every active scope;
	 * 4. for each histogram name: the value expressions in all scopes must be consistent (same source, attribute,
	 *    plain type, and localization flag);
	 * 5. if the resolved attribute is localized, the query must carry a locale.
	 *
	 * After validation, {@link #extractRequestedBucketRange} is called to extract the optional filter range used
	 * to flag per-bucket {@code requested} at fabrication time.
	 *
	 * @return the same {@link ReferenceSummaryProducer} that was found, enriched with the new histogram request
	 */
	@Nonnull
	@Override
	public ExtraResultProducer createProducer(
		@Nonnull ReferenceHistogramStatistics constraint,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) {
		final ProcessingScope processingScope = extraResultPlanner.getProcessingScope();
		final ReferenceSchemaContract referenceSchema = processingScope.getReferenceSchema()
			.orElseThrow(() -> new GenericEvitaInternalError(
				"ReferenceHistogramStatistics must be translated inside a reference-summary scope."
			));

		final ReferenceSummaryProducer producer = extraResultPlanner.findExistingProducer(
			ReferenceSummaryProducer.class,
			existing -> existing.getResultAdapter() instanceof ReferenceSummaryAdapter
		);
		if (producer == null) {
			throw new GenericEvitaInternalError(
				"ReferenceSummaryProducer must exist before histogramStatistics is translated — " +
					"its parent translator should have created it first."
			);
		}

		final Set<Scope> scopes = processingScope.getScopes();
		final Locale requestLocale = extraResultPlanner.getEvitaRequest().getLocale();
		final String referenceName = referenceSchema.getName();
		final int bucketCount = constraint.getRequestedBucketCount();
		final HistogramBehavior behavior = constraint.getBehavior();
		final EntityFetch entityFetch = constraint.getEntityFetch().orElse(null);

		final FilterBy filterBy = extraResultPlanner.getFilterBy();
		for (final String histogramName : constraint.getIndexNames()) {
			final HistogramValueDescriptor descriptor = resolveDescriptor(
				referenceSchema, referenceName, histogramName, scopes, extraResultPlanner
			);

			final Locale effectiveLocale;
			if (descriptor.localized()) {
				if (requestLocale == null) {
					throw new EvitaInvalidUsageException(
						"Histogram `" + histogramName + "` on reference `" + referenceName +
							"` is built from the localized attribute `" + descriptor.sourceAttributeName() +
							"` — query must specify a locale."
					);
				}
				effectiveLocale = requestLocale;
			} else {
				effectiveLocale = null;
			}

			final RequestedBucketRange requestedRange = extractRequestedBucketRange(
				filterBy, referenceName, histogramName, descriptor
			);

			producer.addHistogramRequest(
				new HistogramRequest(
					referenceSchema,
					histogramName,
					bucketCount,
					behavior,
					effectiveLocale,
					descriptor,
					entityFetch,
					requestedRange
				)
			);
		}

		return producer;
	}

	/**
	 * Walks the query's {@link FilterBy} looking for a
	 * `userFilter → referenceHaving(referenceName, …)` subtree whose inner `attributeBetween` matches the
	 * descriptor's source attribute. Produces the `[from, to]` range used to flag per-bucket
	 * {@code requested} at fabrication time. Returns {@code null} when no matching subtree exists in the filter
	 * tree (e.g. the filter is absent, has no `userFilter`, or targets a different reference / attribute).
	 *
	 * Both `from` and `to` within a returned {@link RequestedBucketRange} may be `null` independently: a `null`
	 * `from` means "no lower bound" and a `null` `to` means "no upper bound", mirroring the semantics of
	 * {@link io.evitadb.api.query.filter.AttributeBetween}.
	 *
	 * For {@link HistogramValueSource#REFERENCE_ATTRIBUTE} the `attributeBetween` may live anywhere inside
	 * {@link ReferenceHaving} (except nested `EntityHaving` or nested `ReferenceHaving` — those point at a
	 * different attribute domain). For {@link HistogramValueSource#REFERENCED_ENTITY_ATTRIBUTE} the
	 * `attributeBetween` must sit inside an {@link EntityHaving} container.
	 *
	 * Throws when multiple independent `attributeBetween` subtrees target the same attribute on the same
	 * reference — the contract expects a single range per (reference, histogram) pair.
	 */
	@Nullable
	private static RequestedBucketRange extractRequestedBucketRange(
		@Nullable FilterBy filterBy,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nonnull HistogramValueDescriptor descriptor
	) {
		if (filterBy == null) {
			return null;
		}
		final UserFilter userFilter = FinderVisitor.findConstraint(filterBy, UserFilter.class::isInstance);
		if (userFilter == null) {
			return null;
		}
		final List<ReferenceHaving> referenceHavings = FinderVisitor.findConstraints(
			userFilter,
			c -> c instanceof ReferenceHaving rh && referenceName.equals(rh.getReferenceName())
		);
		if (referenceHavings.isEmpty()) {
			return null;
		}
		AttributeBetween matched = null;
		for (final ReferenceHaving refHaving : referenceHavings) {
			final List<AttributeBetween> candidates = findAttributeBetweenInScope(refHaving, descriptor);
			for (final AttributeBetween candidate : candidates) {
				if (matched != null) {
					throw new EvitaInvalidUsageException(
						"Histogram `" + histogramName + "` on reference `" + referenceName +
							"` has multiple `attributeBetween` subtrees targeting attribute `" +
							descriptor.sourceAttributeName() + "` inside `userFilter` — a single range per" +
							" (reference, histogram) pair is required."
					);
				}
				matched = candidate;
			}
		}
		if (matched == null) {
			return null;
		}
		return new RequestedBucketRange(
			toBigDecimalOrNull(matched.getFrom()),
			toBigDecimalOrNull(matched.getTo())
		);
	}

	/**
	 * Collects `attributeBetween` candidates inside the given `referenceHaving`, obeying the descriptor's
	 * attribute-domain classification:
	 *
	 * - For {@link HistogramValueSource#REFERENCE_ATTRIBUTE} candidates live outside any nested
	 *   {@link EntityHaving} or nested {@link ReferenceHaving} — those descend into a different
	 *   attribute domain and must be skipped.
	 * - For {@link HistogramValueSource#REFERENCED_ENTITY_ATTRIBUTE} candidates live inside an
	 *   {@link EntityHaving} attached directly to this `referenceHaving`; nested `ReferenceHaving` inside
	 *   the `EntityHaving` is skipped for the same reason.
	 */
	@Nonnull
	private static List<AttributeBetween> findAttributeBetweenInScope(
		@Nonnull ReferenceHaving referenceHaving,
		@Nonnull HistogramValueDescriptor descriptor
	) {
		final String attributeName = descriptor.sourceAttributeName();
		final List<AttributeBetween> results = new ArrayList<>(2);
		if (descriptor.source() == HistogramValueSource.REFERENCE_ATTRIBUTE) {
			for (final FilterConstraint child : referenceHaving.getChildren()) {
				collectAttributeBetween(child, attributeName, results,
					c -> c instanceof EntityHaving || c instanceof ReferenceHaving);
			}
		} else {
			for (final FilterConstraint child : referenceHaving.getChildren()) {
				collectAttributeBetweenInEntityHaving(child, attributeName, results);
			}
		}
		return results;
	}

	/**
	 * Looks for {@link EntityHaving} containers within the subtree and, once inside, collects
	 * {@link AttributeBetween} leaves targeting the given attribute. Nested `ReferenceHaving` inside the
	 * `EntityHaving` is skipped because it would point at a different reference's attributes.
	 */
	private static void collectAttributeBetweenInEntityHaving(
		@Nonnull FilterConstraint node,
		@Nonnull String attributeName,
		@Nonnull List<AttributeBetween> results
	) {
		if (node instanceof EntityHaving entityHaving) {
			for (final FilterConstraint child : entityHaving.getChildren()) {
				collectAttributeBetween(child, attributeName, results, ReferenceHaving.class::isInstance);
			}
			return;
		}
		// keep walking until we either hit EntityHaving or run out of containers; do not
		// descend into ReferenceHaving (different reference scope)
		if (node instanceof ReferenceHaving) {
			return;
		}
		if (node instanceof ConstraintContainer<?> container) {
			for (final Constraint<?> child : container.getChildren()) {
				if (child instanceof FilterConstraint filterChild) {
					collectAttributeBetweenInEntityHaving(filterChild, attributeName, results);
				}
			}
		}
	}

	/**
	 * Walks the subtree rooted at {@code node} collecting {@link AttributeBetween} leaves that target the
	 * specified attribute. Descent is pruned when {@code stopAt} matches so nested scopes that point at a
	 * different attribute domain are not traversed.
	 */
	private static void collectAttributeBetween(
		@Nonnull FilterConstraint node,
		@Nonnull String attributeName,
		@Nonnull List<AttributeBetween> results,
		@Nonnull Predicate<Constraint<?>> stopAt
	) {
		if (stopAt.test(node)) {
			return;
		}
		if (node instanceof AttributeBetween ab && attributeName.equals(ab.getAttributeName())) {
			results.add(ab);
			return;
		}
		if (node instanceof ConstraintContainer<?> container) {
			for (final Constraint<?> child : container.getChildren()) {
				if (child instanceof FilterConstraint filterChild) {
					collectAttributeBetween(filterChild, attributeName, results, stopAt);
				}
			}
		}
	}

	/**
	 * Converts the raw bound extracted from an {@link AttributeBetween} argument to {@link BigDecimal}. A
	 * {@code null} bound is allowed and means "no bound on this side".
	 */
	@Nullable
	private static BigDecimal toBigDecimalOrNull(@Nullable Serializable value) {
		return value == null ? null : EvitaDataTypes.toTargetType(value, BigDecimal.class);
	}

	/**
	 * Resolves a consistent {@link HistogramValueDescriptor} across all active scopes. The schema validates that
	 * the same histogram name defined in multiple scopes points to compatible expressions; here we verify this
	 * still holds and return the single canonical descriptor the computer will use at fabrication time.
	 */
	@Nonnull
	private static HistogramValueDescriptor resolveDescriptor(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nonnull Set<Scope> scopes,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) {
		HistogramValueDescriptor canonical = null;
		for (final Scope scope : scopes) {
			final HistogramIndexDefinition definition = referenceSchema.getHistogramIndexDefinition(scope, histogramName);
			if (definition == null) {
				throw new EvitaInvalidUsageException(
					"Histogram `" + histogramName + "` is not defined on reference `" + referenceName +
						"` in scope `" + scope.name() + "`."
				);
			}
			final Expression valueExpression = definition.valueExpression();
			if (valueExpression == null) {
				throw new EvitaInvalidUsageException(
					"Histogram `" + histogramName + "` on reference `" + referenceName +
						"` in scope `" + scope.name() + "` has no value expression."
				);
			}
			final HistogramValueDescriptor current = HistogramValueDescriptorFactory.build(
				valueExpression,
				referenceName,
				histogramName,
				scope,
				referenceSchema,
				extraResultPlanner::getSchema
			);
			if (canonical == null) {
				canonical = current;
			} else {
				assertConsistent(canonical, current, referenceName, histogramName);
			}
		}
		if (canonical == null) {
			throw new GenericEvitaInternalError(
				"No scopes resolved for histogram `" + histogramName + "` on reference `" + referenceName + "`."
			);
		}
		return canonical;
	}

	/**
	 * Verifies that two descriptors resolved from different scopes agree on the attribute they reference, its
	 * type, and localization — otherwise the cross-scope histogram result would be meaningless.
	 */
	private static void assertConsistent(
		@Nonnull HistogramValueDescriptor a,
		@Nonnull HistogramValueDescriptor b,
		@Nonnull String referenceName,
		@Nonnull String histogramName
	) {
		if (a.source() != b.source()
			|| !a.sourceAttributeName().equals(b.sourceAttributeName())
			|| a.plainType() != b.plainType()
			|| a.localized() != b.localized()) {
			throw new EvitaInvalidUsageException(
				"Histogram `" + histogramName + "` on reference `" + referenceName +
					"` has incompatible value expressions across scopes."
			);
		}
	}

}
