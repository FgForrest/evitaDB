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

package io.evitadb.core.query.sort.fulltext.translator;

import io.evitadb.api.query.order.RelevanceText;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.fulltext.FulltextScoreAccessor;
import io.evitadb.core.query.algebra.fulltext.FulltextScoreCombiner;
import io.evitadb.core.query.algebra.fulltext.ScoredFilterFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.OrderByVisitor.ProcessingScope;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.fulltext.RelevanceTextSorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.core.query.sort.translator.ReferenceOrderingConstraintTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndex;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Translates {@link RelevanceText} into the {@link RelevanceTextSorter}, following the two-phase pattern of
 * `priceNatural`:
 * here the planner finds *where* the scores will be - the {@link FulltextScoreAccessor}s of the filter tree, which
 * {@link FulltextScoreCombiner#findSources(Formula)} looks up in {@link LookUp#SHALLOW} mode so that a prefetch wrapper
 * delegating the accessor stops the lookup - and the sorter pulls *what* they are only when it sorts.
 *
 * ## Scores of references and referenced entities
 *
 * A `textMatches` inside `entityHaving` (or `groupHaving`) searches the referenced entities, and its formula lives in
 * their primary-key space, hidden from the owner's filter tree. Its scores reach this translator through the filter
 * visitor of the plan, already carried over to the owners. Each such place first collapses its targets and
 * applies its 0.5 strength weight once. A `textMatches` directly in a `referenceHaving` body searches the
 * reference's own attributes: its filter tree holds only membership constants, so its source arrives the same way,
 * already in the owners' space, masked to owners whose row matched under an even number of negations and weighted
 * by the attributes' search weights, with no 0.5 factor. The sorter takes quality from the best source and combines
 * strength as best + t × others across the owner's own `textMatches` sources and these reference places.
 *
 * ## Without a fulltext filter
 *
 * `relevanceText()` without a `textMatches` in the filter is not an error: no sorter is emitted, so every entity
 * passes on to the next sorter, exactly as if `relevanceText()` were absent. An empty search box drops `textMatches`
 * (its factory returns null for a blank text), and the client must not have to branch its ordering on that. Place
 * weights inside it are still validated by the planner, and a query with invalid ones is refused. Text used only
 * in a reachable hierarchy predicate is refused because it filters the tree and supplies no ranking source.
 *
 * ## Place weights
 *
 * The translator consumes its weight children itself and never visits them: the planner has already resolved them
 * independently before index selection. The sorter supplies these weights with each scoring request. Inside a
 * segment, its predicate's own and additional owner-space sources join the main filter's sources. Copies of equal
 * searches collapse by structural identity; distinct sources combine once by the usual strength rule.
 *
 * ## Where it may stand
 *
 * Only in the ordering of the main query - top-level `orderBy`, `inScope` and `segment` - because only there is the
 * filter whose scores it reads in reach. A nested ordering (`entityProperty`, `entityGroupProperty`, and the
 * orderings inside `referenceProperty`, `traverseByEntityProperty` and `pickFirstByEntityProperty`) orders other
 * entities or references, which the filter did not score; it fails with its own message rather than the generic
 * "available only from the main query context" one. The ordering of fetched references (`referenceContent`) is
 * rejected the same way.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class RelevanceTextTranslator
	implements OrderingConstraintTranslator<RelevanceText>, ReferenceOrderingConstraintTranslator<RelevanceText>,
	SelfTraversingTranslator {

	/**
	 * Creates the exception for `relevanceText()` used outside the main query's ordering.
	 *
	 * @param relevanceText the misplaced constraint
	 * @return the exception
	 */
	@Nonnull
	private static EvitaInvalidUsageException misplaced(@Nonnull RelevanceText relevanceText) {
		return new EvitaInvalidUsageException(
			"Ordering constraint `" + relevanceText +
				"` can only be used in the top-level `orderBy` or inside `segment` " +
				"- it orders by the relevance the `textMatches` filter of the query computes, which is not " +
				"available in a nested ordering of other entities or references!"
		);
	}

	/** Collects the main filter and current segment's independent sources with this ordering's validated weights. */
	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull RelevanceText relevanceText, @Nonnull OrderByVisitor orderByVisitor) {
		final ProcessingScope processingScope = orderByVisitor.getProcessingScope();
		if (
			!orderByVisitor.isMainQueryContext() ||
				processingScope.referenceSchema() != null ||
				processingScope.pickFirstIndexResolver() != null
		) {
			throw misplaced(relevanceText);
		}

		final Map<Object, FulltextScoreAccessor> accessors = new LinkedHashMap<>(
			FulltextScoreCombiner.findSources(orderByVisitor.getFilteringFormula())
		);
		FulltextScoreCombiner.mergeSources(
			accessors, orderByVisitor.getFilterByVisitor().getAdditionalFulltextScoreSources()
		);
		final Formula segmentPredicate = orderByVisitor.getSegmentFilteringFormula();
		if (segmentPredicate instanceof ScoredFilterFormula scored) {
			FulltextScoreCombiner.mergeSources(accessors, scored.getFulltextScoreSources());
			FulltextScoreCombiner.mergeSources(accessors, scored.getAdditionalFulltextScoreSources());
		}
		if (accessors.isEmpty()) {
			// no fulltext filter - nothing to sort by, every entity passes on to the next sorter
			return Stream.empty();
		}

		return Stream.of(
			new RelevanceTextSorter(
				accessors.values().toArray(FulltextScoreAccessor[]::new),
				resolveScopeIndexes(orderByVisitor, processingScope),
				orderByVisitor.getQueryContext().getFulltextScoringPolicy(),
				orderByVisitor.getQueryContext().getFulltextWeights(relevanceText)
			)
		);
	}

	@Override
	public void createComparator(
		@Nonnull RelevanceText relevanceText,
		@Nonnull ReferenceOrderByVisitor orderByVisitor
	) {
		throw misplaced(relevanceText);
	}

	/**
	 * Returns the global indexes of the scopes the ordering is restricted to by `inScope`, or null when it covers
	 * every scope the query requested.
	 *
	 * @param orderByVisitor  the visitor of the main query
	 * @param processingScope the current processing scope
	 * @return the global indexes of the processed scopes, or null when no restriction applies
	 */
	@Nullable
	private static EntityIndex[] resolveScopeIndexes(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull ProcessingScope processingScope
	) {
		final Set<Scope> processedScopes = processingScope.getScopes();
		if (processedScopes.containsAll(orderByVisitor.getQueryContext().getScopes())) {
			return null;
		}
		final List<EntityIndex> scopeIndexes = new ArrayList<>(processedScopes.size());
		for (Scope scope : processedScopes) {
			orderByVisitor.getQueryContext()
				.getGlobalEntityIndexIfExists(scope)
				.ifPresent(scopeIndexes::add);
		}
		return scopeIndexes.toArray(EntityIndex[]::new);
	}

}
