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

import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.ReferenceHistogramStatistics;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.core.expression.trigger.HistogramValueDescriptor;
import io.evitadb.core.expression.trigger.HistogramValueDescriptorFactory;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor.ProcessingScope;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryAdapter;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.HistogramRequest;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.RequestedBucketRange;
import io.evitadb.core.query.filter.translator.histogram.ResolvedHistogramHaving;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
	 * After validation, {@link #extractRequestedBucketRanges} is called to extract the optional filter range used
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

		// snapshot the resolved `histogramHaving` registry once per translator invocation — the filter
		// translator has already done all the walking, descriptor resolution, and group-selector bitmap
		// computation so we just read back the pre-computed tuples. The registry is owned by this
		// plan's FilterByVisitor (not the shared QueryPlanningContext) so alternative-plan filter
		// translations don't contribute duplicate entries.
		final List<ResolvedHistogramHaving> resolvedHistogramHavings =
			extraResultPlanner.getFilterByVisitor().getResolvedHistogramHavings();
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

			final Map<Integer, RequestedBucketRange> requestedRangesByGroupPk = extractRequestedBucketRanges(
				resolvedHistogramHavings, referenceName, histogramName
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
					requestedRangesByGroupPk
				)
			);
		}

		return producer;
	}

	/**
	 * Filters the planning context's pre-resolved `histogramHaving` registry for entries addressing the
	 * `(referenceName, histogramName)` slot and materialises them into a per-group map of
	 * {@link RequestedBucketRange}. Each group's histogram consumes the entry matching its own group PK
	 * at fabrication time to flag per-bucket {@code requested} — siblings addressing different group slots
	 * therefore cannot contaminate each other's flagging.
	 *
	 * Because the filter translator has already resolved every `histogramHaving` (descriptor + group PK)
	 * once, this method does nothing beyond a linear scan and a linked-map assembly — no filter-tree walk,
	 * no bitmap computation, no duplicate schema lookups.
	 *
	 * Returns an empty map (never `null`) when the registry contains no entries targeting this slot.
	 *
	 * **Map keying.** Each entry is keyed by its pre-resolved group PK, or by
	 * {@link HistogramRequest#NON_GROUPED_SENTINEL} when the originating `histogramHaving` carried no
	 * `groupSelector`. The consumer falls back to the sentinel entry when no per-group entry matches the
	 * current group.
	 *
	 * **Duplicate detection.** Two registry entries with identical `(referenceName, histogramName, groupPk)`
	 * tuples address the exact same slot; the contract is one range per slot, so the second entry throws
	 * {@link EvitaInvalidUsageException}. Entries sharing the slot's reference/histogram pair but carrying
	 * different group PKs address different group slots and are legal — each contributes one entry to the
	 * returned map.
	 *
	 * @param resolvedHistogramHavings the planning-context registry populated by
	 *                                 {@code HistogramHavingTranslator}
	 * @param referenceName            the reference hosting the histogram slot
	 * @param histogramName            the histogram slot name
	 * @return per-group map of resolved ranges; empty when no registered `histogramHaving` targets the slot
	 */
	@Nonnull
	private static Map<Integer, RequestedBucketRange> extractRequestedBucketRanges(
		@Nonnull List<ResolvedHistogramHaving> resolvedHistogramHavings,
		@Nonnull String referenceName,
		@Nonnull String histogramName
	) {
		if (resolvedHistogramHavings.isEmpty()) {
			return Map.of();
		}
		// lazy allocation — most histograms have no matching carrier in the registry
		Map<Integer, RequestedBucketRange> rangesByGroupPk = null;
		for (final ResolvedHistogramHaving entry : resolvedHistogramHavings) {
			if (!referenceName.equals(entry.referenceName())
				|| !histogramName.equals(entry.histogramName())) {
				continue;
			}
			if (rangesByGroupPk == null) {
				rangesByGroupPk = CollectionUtils.createLinkedHashMap(resolvedHistogramHavings.size());
			}
			final RequestedBucketRange previous = rangesByGroupPk.put(
				entry.groupPk(),
				new RequestedBucketRange(entry.from(), entry.to())
			);
			if (previous != null) {
				throw new EvitaInvalidUsageException(
					"Histogram `" + histogramName + "` on reference `" + referenceName +
						"` has multiple `histogramHaving` siblings addressing the same group slot" +
						" — a single `[from, to]` range per slot is required."
				);
			}
		}
		return rangesByGroupPk == null ? Map.of() : rangesByGroupPk;
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
