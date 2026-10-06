/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.query.filter.translator.reference;

import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.FilterByVisitor.ReducedIndexCandidates;
import io.evitadb.core.query.filter.FilterByVisitor.SplitReducedIndexCandidates;
import io.evitadb.core.query.filter.VirtualPartitionRows.InScopeReading;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.Index;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartitionSplit;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static io.evitadb.api.query.QueryConstraints.referenceContent;
import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link ReferenceHaving} to
 * {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferenceHavingTranslator implements FilteringConstraintTranslator<ReferenceHaving>, SelfTraversingTranslator {
	/**
	 * Applies a search operation on specified indexes based on the given filter constraints, context, and schema
	 * configurations. The method builds and executes the necessary formulas for filtering and efficiently retrieves
	 * the data satisfying the filter criteria.
	 *
	 * @param filterConstraint              the filtering constraint specifying conditions to evaluate against the references.
	 * @param filterByVisitor               the visitor responsible for collecting the results of the filtering logic across schemas.
	 * @param entitySchema                  the entity schema defining the structure and attributes of the entity being queried.
	 * @param referenceSchema               the reference schema containing metadata about the reference relation and its attributes.
	 * @param processingScope               the processing scope providing additional contextual properties required during the filtering.
	 * @param referencedEntityIndexSupplier the supplier that provides a list of reduced entity indexes for processing references.
	 * @return the resulting formula representing the combined computation steps for the filtering operation.
	 */
	@Nonnull
	private static Formula applySearchOnIndexes(
		@Nonnull ReferenceHaving filterConstraint,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ProcessingScope<?> processingScope,
		@Nonnull Supplier<List<ReducedEntityIndex>> referencedEntityIndexSupplier
	) {
		final String referenceName = referenceSchema.getName();
		return filterByVisitor.executeInContextAndIsolatedFormulaStack(
			ReducedEntityIndex.class,
			referencedEntityIndexSupplier,
			ReferenceContent.ALL_REFERENCES,
			entitySchema,
			referenceSchema,
			processingScope.getNestedQueryRestriction(),
			processingScope.getEntityNestedQueryComparator(),
			processingScope.withReferenceSchemaAccessor(referenceName),
			(entityContract, attributeName, locale) -> {
				// this is the place for which we need to prefetch the reference content
				return entityContract.getReferences(referenceName)
					.stream()
					.map(it -> it.getAttributeValue(attributeName, locale));
			},
			() -> {
				getFilterByFormula(filterConstraint).ifPresent(it -> it.accept(filterByVisitor));
				final Formula[] collectedFormulas = filterByVisitor.getCollectedFormulasOnCurrentLevel();
				// leaves inside this body carry the index that produced them, so the body can be rebuilt one
				// reference row at a time; the tags are consumed there and never reach the enclosing tree.
				// The index stream is passed as a supplier because only a negated body needs it - see the
				// transposer for why a negation cannot settle for the indexes that tagged something.
				return ReferenceBodyTransposer.transpose(
					switch (collectedFormulas.length) {
						// when there was no filter constraint at all, we can safely use super set formula
						// e.g. all primary keys in reduced entity indexes
						case 0 -> filterByVisitor.getSuperSetFormula();
						case 1 -> collectedFormulas[0];
						// `getFilterByFormula` folds several children into a single `And`, and translating one
						// non-suppressed constraint contributes exactly one formula - so this level holds 0 or 1
						default -> throw new GenericEvitaInternalError(
							"Expected at most one formula on the isolated `referenceHaving` level, got " +
								collectedFormulas.length + " for: `" + filterConstraint + "`."
						);
					},
					() -> filterByVisitor.getEntityIndexStream().toList()
				);
			}
		);
	}

	@Nonnull
	private static Optional<FilterConstraint> getFilterByFormula(@Nonnull ReferenceHaving filterConstraint) {
		final FilterConstraint[] children = filterConstraint.getChildren();
		if (children.length == 0) {
			return empty();
		} else if (children.length == 1) {
			return of(children[0]);
		} else {
			return of(new And(children));
		}
	}

	/**
	 * Returns the candidate partitions of the `referenceHaving` split by representation - those index selection already
	 * discovered when it registered the constraint as a target index option, otherwise discovered now.
	 *
	 * @param filterByVisitor the visitor translating the constraint
	 * @param referenceHaving the constraint
	 * @param scopes          the scopes the constraint is evaluated in
	 * @return the candidates read through their reduced entity indexes, resolved, and the split carrying the ones read
	 *         virtually
	 */
	@Nonnull
	private static SplitReducedIndexCandidates getTargetIndexes(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull ReferenceHaving referenceHaving,
		@Nonnull Set<Scope> scopes
	) {
		final TargetIndexes<?> targetIndexes = filterByVisitor.findTargetIndexSet(referenceHaving);
		final ReducedIndexCandidates candidates = targetIndexes == null ? null : targetIndexes.getReducedIndexCandidates();
		if (candidates != null) {
			// without a virtual candidate the option's own memoized index objects are the answer
			//noinspection unchecked
			return candidates.resolveSplit(() -> (List<ReducedEntityIndex>) targetIndexes.getIndexes());
		} else if (targetIndexes == null) {
			return filterByVisitor.getReferencedRecordEntityIndexCandidates(referenceHaving, scopes).resolveSplit();
		} else {
			// an option registered without its candidates reads the index objects alone - every candidate is one
			//noinspection unchecked
			return new SplitReducedIndexCandidates(
				(List<ReducedEntityIndex>) targetIndexes.getIndexes(),
				ReferencePartitions.split(EmptyBitmap.INSTANCE)
			);
		}
	}

	@Nonnull
	@Override
	public Formula translate(@Nonnull ReferenceHaving referenceHaving, @Nonnull FilterByVisitor filterByVisitor) {
		final String referenceName = referenceHaving.getReferenceName();
		final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
		final EntitySchemaContract entitySchema = processingScope.getEntitySchema();

		Assert.isTrue(
			entitySchema != null,
			() -> "Entity type must be known when filtering by `referenceHaving`."
		);

		final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName)
			.orElseThrow(() -> new ReferenceNotFoundException(referenceName, entitySchema));

		// the reference content needs to be prefetched in order to bea able to apply the filter on prefetched data
		// i.e. access the reference attributes
		filterByVisitor.addRequirementToPrefetch(referenceContent(referenceName));

		// when the reference is one end of a bidirectional pair, the very same question can be answered by visiting one
		// index per candidate owner instead of one per matching referenced entity - the rewriter returns an empty
		// optional for every shape it cannot faithfully reproduce
		final Optional<Formula> rewritten = BidirectionalReferenceRewriter.tryRewrite(
			referenceHaving, filterByVisitor, entitySchema, referenceSchema, processingScope
		);
		return rewritten.orElseGet(
			() -> applySearchOnCandidates(
				referenceHaving, filterByVisitor, entitySchema, referenceSchema, processingScope,
				getTargetIndexes(filterByVisitor, referenceHaving, processingScope.getScopes())
			)
		);
	}

	/**
	 * Answers the `referenceHaving` over its candidate partitions. The candidates read through their reduced entity
	 * indexes are evaluated as they always were - the body against each index, rebuilt one reference row at a time by
	 * {@link ReferenceBodyTransposer}. The candidates read virtually are answered by
	 * {@link VirtualPartitionBodyEvaluator}: from the candidate set alone when the body is row-exact, otherwise by one
	 * exact evaluation of the body per scope holding virtual candidates. Either way their owners join the answer as one
	 * content-keyed constant OR'ed outside the transposed body, so no index tag of the body ever meets a virtual row.
	 *
	 * The virtual part costs one pass over the virtual candidates per query, on top of the split, and for a body that
	 * is not row-exact one evaluation of the body against the referenced-type entity index per scope; the materialized
	 * part costs what the whole evaluation cost before, minus the virtual candidates.
	 *
	 * @param referenceHaving the constraint
	 * @param filterByVisitor the visitor translating the constraint
	 * @param entitySchema    the schema of the entity owning the reference
	 * @param referenceSchema the reference
	 * @param processingScope the processing scope the constraint is translated in
	 * @param candidates      the candidate partitions split by representation
	 * @return the formula computing the owners satisfying the constraint
	 */
	@Nonnull
	private static Formula applySearchOnCandidates(
		@Nonnull ReferenceHaving referenceHaving,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ProcessingScope<?> processingScope,
		@Nonnull SplitReducedIndexCandidates candidates
	) {
		final ReferencePartitionSplit split = candidates.split();
		final List<ReducedEntityIndex> materializedIndexes = candidates.materializedIndexes();
		if (split.getVirtualCount() == 0) {
			return applySearchOnIndexes(
				referenceHaving, filterByVisitor, entitySchema, referenceSchema, processingScope,
				() -> materializedIndexes
			);
		}
		// a body with no virtual reading is refused before any part of the answer is built - never answered partially
		final boolean rowExact = VirtualPartitionBodyEvaluator.isRowExact(
			getFilterByFormula(referenceHaving).orElse(null), referenceSchema.getName()
		);
		// TODO JNO - remove at the end of #1615 (the non-vacuity probe of the read parity tests)
		filterByVisitor.recordVirtualRowsRead(split.getVirtualCount());
		final Formula virtualOwners = rowExact ?
			VirtualPartitionBodyEvaluator.getOwnersFormula(split) :
			VirtualPartitionBodyEvaluator.getOwnersFormula(
				split,
				VirtualPartitionBodyEvaluator.getMatchingPartitions(
					filterByVisitor, entitySchema, referenceSchema, new FilterBy(referenceHaving.getChildren()), split,
					// the transposed body never runs the post-processor of `inScope`, so a constraint confined to
					// another scope contributes nothing for a partition
					InScopeReading.OTHER_SCOPES_UNMET,
					processingScope.getNestedQueryRestriction()
				)
			);
		if (materializedIndexes.isEmpty()) {
			return virtualOwners;
		}
		return FormulaFactory.or(
			applySearchOnIndexes(
				referenceHaving, filterByVisitor, entitySchema, referenceSchema, processingScope,
				() -> materializedIndexes
			),
			virtualOwners
		);
	}

}
