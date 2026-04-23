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

package io.evitadb.core.query.filter.translator.histogram;

import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.HistogramHaving;
import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.core.expression.trigger.HistogramValueDescriptor;
import io.evitadb.core.expression.trigger.HistogramValueDescriptorFactory;
import io.evitadb.core.expression.trigger.HistogramValueSource;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.filter.HistogramHavingFormula;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.Index;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeBetween;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;

/**
 * Translator for the {@link HistogramHaving} filter constraint. Rewrites the user-facing
 * `histogramHaving(referenceName, histogramName?, from?, to?, groupSelector?)` constraint into the equivalent
 * `referenceHaving(...) / entityHaving(...) / attributeBetween(...)` subtree and dispatches the rewrite back
 * through the {@link FilterByVisitor}. The returned child formula is wrapped in {@link HistogramHavingFormula}
 * so the engine can locate the range-carrier by type
 * ({@link io.evitadb.core.query.algebra.filter.AttributeRangeCarrierFormula} — attribute-family histogram
 * baseline peeling).
 *
 * The translator performs the following at plan time:
 *
 * 1. Resolves the `(ReferenceSchema, HistogramIndexDefinition)` tuple from the current processing scope and the
 *    `(referenceName, histogramName?)` pair. If `histogramName` is omitted, the reference must host exactly one
 *    histogram; if the reference hosts several, the user must disambiguate — otherwise an
 *    {@link EvitaInvalidUsageException} is thrown. If the named histogram does not exist on the reference, an
 *    {@link EvitaInvalidUsageException} is thrown. The resolution walks every active {@link Scope} in the
 *    processing scope and enforces cross-scope consistency of the resolved {@link HistogramValueDescriptor}
 *    (same source, attribute, plain type, localization flag).
 *
 * 2. When a `groupSelector` is present, it must be an {@link EntityHaving} container (enforced at
 *    constraint-construction time). The translator evaluates the inner filter against the referenced-group's
 *    global index and asserts that the result contains exactly one primary key. A zero-match or multi-match
 *    result is rejected — the slot addressed by a single `histogramHaving` must identify a single group.
 *
 * 3. Produces the rewrite per {@link HistogramValueSource}:
 *    - `REFERENCED_ENTITY_ATTRIBUTE`:
 *      ```
 *      referenceHaving(refName,
 *          entityHaving(
 *              attributeBetween(attr, from, to),
 *              referenceHaving(groupRef, entityPrimaryKeyInSet(resolvedGroupPk))
 *          )
 *      )
 *      ```
 *    - `REFERENCE_ATTRIBUTE`:
 *      ```
 *      referenceHaving(refName,
 *          attributeBetween(attr, from, to),
 *          referenceHaving(groupRef, entityPrimaryKeyInSet(resolvedGroupPk))
 *      )
 *      ```
 *    When `groupSelector` is null, the inner group-selecting `referenceHaving` is omitted — the rewrite
 *    targets the ungrouped histogram slot.
 *
 * 4. Dispatches the rewrite through {@link FilterByVisitor#visit(io.evitadb.api.query.Constraint)} so the
 *    standard translator pipeline produces a {@link Formula}. Because the visitor's level stack tracks
 *    collected formulas per container level, the rewrite's produced formula is picked up from the current level.
 *
 * 5. Wraps the produced formula in {@link HistogramHavingFormula} and returns it — the wrapper exposes this
 *    sub-tree as an {@link io.evitadb.core.query.algebra.filter.AttributeRangeCarrierFormula} range carrier
 *    that the attribute-family histogram baseline cloner will peel when computing its own `[min, max]` span.
 *
 * The translator implements {@link SelfTraversingTranslator} because the {@link HistogramHaving} constraint is
 * a container whose `groupSelector` child is an {@link EntityHaving} — default child traversal would attempt
 * to translate that {@link EntityHaving} in the entity domain, which requires a surrounding `ReferenceHaving`
 * parent scope. By self-traversing, the translator controls the dispatch order and emits the fully rewritten
 * tree into the visitor.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class HistogramHavingTranslator
	implements FilteringConstraintTranslator<HistogramHaving>, SelfTraversingTranslator {

	/**
	 * Error message emitted when the resolved group-selector bitmap does not contain exactly one primary key.
	 */
	private static final String ERROR_GROUP_SELECTOR_NOT_UNIQUE =
		"`groupSelector` must select exactly one group entity";

	/**
	 * Rejects {@link HistogramHaving} when it is nested inside a non-{@link And}, non-{@link UserFilter}
	 * container strictly between itself and an enclosing {@link UserFilter}. The canonical pathological
	 * shape is `userFilter(not(histogramHaving(...)))`: the `HistogramHavingFormula` is the range carrier
	 * that the attribute-histogram relaxer peels via `AttributeRangeCarrierFormula`, but the relaxer walks through
	 * conjunctive containers only — a `NotFormula` hides the carrier from the peeling pass, leaving the
	 * histogram baseline computed against the negated filter with request flags that don't reflect any
	 * user selection.
	 *
	 * Outside `userFilter`, `not(histogramHaving(...))` remains legitimate (it's just a negated reference
	 * subquery), so this guard only fires when a `UserFilter` ancestor actually exists and the path from
	 * `HistogramHaving` up to that `UserFilter` contains something other than `And` / `UserFilter`.
	 *
	 * @param processingScope the current processing scope holding the parent chain
	 */
	private static void rejectIllegalParentWithinUserFilter(
		@Nonnull ProcessingScope<? extends Index<?>> processingScope
	) {
		FilterConstraint offender = null;
		for (final FilterConstraint parent : processingScope.getProcessedConstraintParents()) {
			if (parent instanceof UserFilter) {
				if (offender != null) {
					throw new EvitaInvalidUsageException(
						"Filtering constraint `histogramHaving` must not be nested inside `" +
							offender.getName() + "` within `userFilter` — the range carrier cannot be " +
							"peeled from a non-conjunctive subtree (e.g. a negation), which would make " +
							"the computed histogram's baseline inconsistent with the user-selected slot. " +
							"Place `histogramHaving` directly inside `userFilter` (optionally nested under " +
							"`and(...)`)."
					);
				}
				return;
			}
			if (!(parent instanceof And)) {
				offender = parent;
			}
		}
		// no UserFilter ancestor — any container shape is legitimate outside userFilter
	}

	@Nonnull
	@Override
	public Formula translate(
		@Nonnull HistogramHaving histogramHaving,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
		rejectIllegalParentWithinUserFilter(processingScope);
		final EntitySchemaContract entitySchema = processingScope.getEntitySchema();
		if (entitySchema == null) {
			throw new EvitaInvalidUsageException(
				"Filtering constraint `histogramHaving` needs an entity scope to be resolvable."
			);
		}

		final String referenceName = histogramHaving.getReferenceName();
		final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName)
			.orElseThrow(() -> new ReferenceNotFoundException(referenceName, entitySchema));

		final Set<Scope> scopes = processingScope.getScopes();
		final ResolvedSlot resolvedSlot = resolveDescriptor(
			referenceSchema, histogramHaving.getHistogramName(), scopes, filterByVisitor
		);
		final HistogramValueDescriptor descriptor = resolvedSlot.descriptor();

		final Serializable from = histogramHaving.getFrom();
		final Serializable to = histogramHaving.getTo();

		final Integer resolvedGroupPk = resolveGroupPk(
			histogramHaving.getGroupSelector(), referenceSchema, scopes, filterByVisitor
		);

		// stash the resolved tuple so ReferenceHistogramStatisticsTranslator can consume it without
		// re-walking the filter tree or re-running the group-selector bitmap computation
		filterByVisitor.getQueryContext().registerResolvedHistogramHaving(
			new ResolvedHistogramHaving(
				referenceName,
				resolvedSlot.histogramName(),
				resolvedGroupPk == null
					? ResolvedHistogramHaving.NON_GROUPED_SENTINEL
					: resolvedGroupPk,
				toBigDecimalOrNull(from),
				toBigDecimalOrNull(to)
			)
		);

		final FilterConstraint rewrite = buildRewrite(
			referenceSchema, descriptor, from, to, resolvedGroupPk
		);

		// dispatch through the visitor so the standard pipeline produces the underlying Formula; the
		// result lands on the current level stack, which the visitor pops wholesale after we return —
		// only the Formula this method returns is promoted, so we can safely read the level's top
		final int formulasBefore = filterByVisitor.getCollectedFormulasOnCurrentLevel().length;
		rewrite.accept(filterByVisitor);
		final Formula[] collected = filterByVisitor.getCollectedFormulasOnCurrentLevel();
		if (collected.length != formulasBefore + 1) {
			throw new GenericEvitaInternalError(
				"Expected exactly one formula from `histogramHaving` rewrite dispatch, got " +
					(collected.length - formulasBefore) + "."
			);
		}
		final Formula rewritten = collected[collected.length - 1];
		return new HistogramHavingFormula(rewritten);
	}

	/**
	 * Builds the equivalent {@link FilterConstraint} rewrite for the given histogram descriptor and resolved
	 * parameters. The shape of the rewrite depends on the descriptor's {@link HistogramValueSource} — values
	 * sourced from the referenced entity's attributes live inside an {@link EntityHaving} container, whereas
	 * values sourced from the reference's own attributes live directly inside the outer `referenceHaving(...)`.
	 *
	 * @param referenceSchema   the reference schema hosting the histogram
	 * @param descriptor        the resolved histogram value descriptor (source + attribute name)
	 * @param from              the inclusive lower bound of the range (nullable)
	 * @param to                the inclusive upper bound of the range (nullable)
	 * @param resolvedGroupPk   the single group primary key resolved from the `groupSelector`, or null when no
	 *                          group selector was supplied
	 * @return the rewritten filter constraint that evaluates to the same entity set as the original
	 *         `histogramHaving(...)` would narrow the result set to
	 */
	@Nonnull
	private static FilterConstraint buildRewrite(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull HistogramValueDescriptor descriptor,
		@Nullable Serializable from,
		@Nullable Serializable to,
		@Nullable Integer resolvedGroupPk
	) {
		final String referenceName = referenceSchema.getName();
		final String attributeName = descriptor.sourceAttributeName();
		final FilterConstraint attrBetween = attributeBetween(attributeName, from, to);
		final FilterConstraint innerGroupSelector = resolvedGroupPk == null ? null : referenceHaving(
			Objects.requireNonNull(
				referenceSchema.getReferencedGroupType(),
				"Reference `" + referenceName + "` does not declare a referenced group type, " +
					"but a group selector was supplied to `histogramHaving`."
			),
			entityPrimaryKeyInSet(resolvedGroupPk)
		);

		return switch (descriptor.source()) {
			case REFERENCED_ENTITY_ATTRIBUTE -> {
				// attribute lives on the referenced entity — nest inside EntityHaving
				final FilterConstraint entityInner = innerGroupSelector == null
					? entityHaving(attrBetween)
					: entityHaving(and(attrBetween, innerGroupSelector));
				yield referenceHaving(referenceName, entityInner);
			}
			case REFERENCE_ATTRIBUTE -> {
				// attribute lives on the reference itself — place attributeBetween directly inside
				yield innerGroupSelector == null
					? referenceHaving(referenceName, attrBetween)
					: referenceHaving(referenceName, attrBetween, innerGroupSelector);
			}
		};
	}

	/**
	 * Resolves the single group primary key identified by the supplied `groupSelector`. The selector must be an
	 * {@link EntityHaving} container (enforced at constraint-construction time). The inner filter constraint is
	 * evaluated against the referenced-group's global index across all active scopes; the resulting bitmap must
	 * contain exactly one primary key — otherwise an {@link EvitaInvalidUsageException} is thrown.
	 *
	 * Returns `null` when no `groupSelector` was supplied — the caller omits the inner group-narrowing
	 * `referenceHaving` in that case.
	 *
	 * @param groupSelector   the optional group selector from the {@link HistogramHaving} constraint
	 * @param referenceSchema the reference schema whose `referencedGroupType` identifies the target entity type
	 * @param scopes          the active scopes to consult when looking up the group entity
	 * @param filterByVisitor the visitor providing access to the planning context
	 * @return the single resolved group primary key, or null when `groupSelector` is absent
	 */
	@Nullable
	private static Integer resolveGroupPk(
		@Nullable FilterConstraint groupSelector,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Set<Scope> scopes,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		if (groupSelector == null) {
			return null;
		}
		if (!(groupSelector instanceof EntityHaving entityHavingSelector)) {
			throw new EvitaInvalidUsageException(
				"`groupSelector` of `histogramHaving` must be an `entityHaving(...)` container."
			);
		}
		final FilterConstraint innerSelector = entityHavingSelector.getChild();
		if (innerSelector == null) {
			throw new EvitaInvalidUsageException(
				"`groupSelector` of `histogramHaving` must contain a non-empty filter."
			);
		}
		final String groupEntityType = referenceSchema.getReferencedGroupType();
		if (groupEntityType == null) {
			throw new EvitaInvalidUsageException(
				"Reference `" + referenceSchema.getName() +
					"` does not declare a referenced group type, but `groupSelector` was supplied " +
					"to `histogramHaving`."
			);
		}
		if (!referenceSchema.isReferencedGroupTypeManaged()) {
			throw new EvitaInvalidUsageException(
				"Reference `" + referenceSchema.getName() +
					"` targets a non-managed group type `" + groupEntityType +
					"` — `groupSelector` requires a managed group type."
			);
		}

		final QueryPlanningContext queryContext = filterByVisitor.getQueryContext();
		final FilterBy groupFilterBy = filterBy(innerSelector);
		// collect the global entity indexes for the group type across active scopes; they form the
		// `computeOnlyOnce` cache key alongside `innerSelector` so a repeated group selector (duplicate
		// `histogramHaving` with the same selector, or planner retries) reuses the memoised formula
		final List<EntityIndex> groupEntityIndexes = new ArrayList<>(scopes.size());
		for (final Scope scope : scopes) {
			queryContext.getEntityIndex(
				groupEntityType, new EntityIndexKey(EntityIndexType.GLOBAL, scope), EntityIndex.class
			).ifPresent(groupEntityIndexes::add);
		}
		// plan-time resolution is necessary because the rewrite embeds the resolved PK as a constant
		// (`entityPrimaryKeyInSet(resolvedGroupPk)`) and the single-match assertion must surface as a
		// user-facing validation before execution; route through `computeOnlyOnce` so the formula is
		// built, initialised, and cached — avoiding redundant work across planner retries
		final Formula groupFormula = queryContext.computeOnlyOnce(
			groupEntityIndexes,
			innerSelector,
			() -> FilterByVisitor.createFormulaForTheFilter(
				queryContext,
				scopes,
				Objects.requireNonNull(groupFilterBy),
				null,
				groupEntityType,
				() -> "resolving `histogramHaving` group selector on `" + groupEntityType + "`"
			)
		);
		final Bitmap resolved = groupFormula.compute();
		final int size = resolved.size();
		if (size == 0) {
			throw new EvitaInvalidUsageException(
				ERROR_GROUP_SELECTOR_NOT_UNIQUE + " (got 0 matches)."
			);
		}
		if (size > 1) {
			throw new EvitaInvalidUsageException(
				ERROR_GROUP_SELECTOR_NOT_UNIQUE + " (got " + size + " matches)."
			);
		}
		return resolved.getFirst();
	}

	/**
	 * Resolves a consistent {@link HistogramValueDescriptor} for the given histogram name across all active
	 * scopes. When `requestedHistogramName` is null, the reference schema must host exactly one histogram in
	 * every active scope; ambiguity (multiple histograms defined on the reference) is rejected with an
	 * actionable error. When `requestedHistogramName` is supplied, it must resolve to an existing
	 * {@link HistogramIndexDefinition} in every active scope. The resolved value expressions must agree on
	 * source, attribute name, plain type, and localization flag across scopes.
	 *
	 * This mirrors {@code ReferenceHistogramStatisticsTranslator#resolveDescriptor} (the private helper in the
	 * require-constraint translator); the two implementations are semantically equivalent.
	 *
	 * @param referenceSchema        the reference schema hosting the histograms
	 * @param requestedHistogramName the histogram name from the constraint; null when the user omitted it
	 * @param scopes                 the active scopes to consult
	 * @param filterByVisitor        the visitor providing access to the catalog schema (for entity-type lookups
	 *                               during descriptor factory resolution)
	 * @return the resolved histogram value descriptor; never null
	 * @throws EvitaInvalidUsageException when no histogram is defined, the name is ambiguous, the name is
	 *                                    unknown, or cross-scope descriptors diverge
	 */
	@Nonnull
	private static ResolvedSlot resolveDescriptor(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable String requestedHistogramName,
		@Nonnull Set<Scope> scopes,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final String referenceName = referenceSchema.getName();
		HistogramValueDescriptor canonical = null;
		String effectiveHistogramName = requestedHistogramName;

		for (final Scope scope : scopes) {
			final Map<String, HistogramIndexDefinition> definitions =
				referenceSchema.getHistogramIndexDefinitions(scope);
			if (definitions == null || definitions.isEmpty()) {
				throw new EvitaInvalidUsageException(
					"Reference `" + referenceName + "` hosts no histogram in scope `" + scope.name() +
						"` — `histogramHaving` cannot resolve a target."
				);
			}
			final HistogramIndexDefinition definition;
			if (effectiveHistogramName == null) {
				if (definitions.size() != 1) {
					throw new EvitaInvalidUsageException(
						"`histogramName` must be specified when the reference `" + referenceName +
							"` has multiple histograms (found " + definitions.size() +
							" in scope `" + scope.name() + "`)."
					);
				}
				// single-histogram slot — pin the name from the first (and only) scope encountered
				definition = definitions.values().iterator().next();
				effectiveHistogramName = definition.nameOfTheIndex();
			} else {
				definition = definitions.get(effectiveHistogramName);
				if (definition == null) {
					throw new EvitaInvalidUsageException(
						"No histogram with name `" + effectiveHistogramName + "` on reference `" +
							referenceName + "` in scope `" + scope.name() + "`."
					);
				}
			}

			final Expression valueExpression = definition.valueExpression();
			if (valueExpression == null) {
				throw new EvitaInvalidUsageException(
					"Histogram `" + effectiveHistogramName + "` on reference `" + referenceName +
						"` in scope `" + scope.name() + "` has no value expression."
				);
			}
			final HistogramValueDescriptor current = HistogramValueDescriptorFactory.build(
				valueExpression,
				referenceName,
				effectiveHistogramName,
				scope,
				referenceSchema,
				filterByVisitor.getQueryContext()::getSchema
			);
			if (canonical == null) {
				canonical = current;
			} else if (!descriptorsAgree(canonical, current)) {
				throw new EvitaInvalidUsageException(
					"Histogram `" + effectiveHistogramName + "` on reference `" + referenceName +
						"` has incompatible value expressions across scopes."
				);
			}
		}
		if (canonical == null) {
			// no scopes provided — fail fast rather than silently falling back to an empty formula
			throw new GenericEvitaInternalError(
				"No scopes resolved for `histogramHaving` on reference `" + referenceName + "`."
			);
		}
		return new ResolvedSlot(
			Objects.requireNonNull(
				effectiveHistogramName,
				"effective histogram name must have been pinned before canonical descriptor was resolved"
			),
			canonical
		);
	}

	/**
	 * Compares two {@link HistogramValueDescriptor} instances for semantic equality on the fields that matter
	 * for cross-scope consistency: source, attribute name, plain type, and localization flag.
	 *
	 * @param a first descriptor
	 * @param b second descriptor
	 * @return true when the two descriptors resolve the same attribute in a compatible way
	 */
	private static boolean descriptorsAgree(
		@Nonnull HistogramValueDescriptor a,
		@Nonnull HistogramValueDescriptor b
	) {
		return a.source() == b.source()
			&& a.sourceAttributeName().equals(b.sourceAttributeName())
			&& a.plainType() == b.plainType()
			&& a.localized() == b.localized();
	}

	/**
	 * Converts a raw {@link HistogramHaving} bound argument to {@link BigDecimal}. A {@code null} bound
	 * means "no bound on this side" and is preserved as {@code null} in the resulting
	 * {@link ResolvedHistogramHaving#from()} / {@link ResolvedHistogramHaving#to()} slot.
	 *
	 * @param value the raw bound (may be null)
	 * @return the converted {@link BigDecimal}, or null when the input was null
	 */
	@Nullable
	private static BigDecimal toBigDecimalOrNull(@Nullable Serializable value) {
		return value == null ? null : EvitaDataTypes.toTargetType(value, BigDecimal.class);
	}

	/**
	 * Pairs the histogram's effective (post-shorthand-resolution) name with its resolved value descriptor.
	 * Returned by {@link #resolveDescriptor} so the caller can both stash the resolved name on the planning
	 * context and hand the descriptor to the rewrite builder.
	 *
	 * @param histogramName the concrete histogram index name (shorthand has been expanded)
	 * @param descriptor    the canonical value descriptor consistent across all active scopes
	 */
	private record ResolvedSlot(
		@Nonnull String histogramName,
		@Nonnull HistogramValueDescriptor descriptor
	) {
	}

}
