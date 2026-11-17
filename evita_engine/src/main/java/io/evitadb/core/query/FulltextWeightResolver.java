/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.exception.AssociatedDataNotFoundException;
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.FilterInScope;
import io.evitadb.api.query.filter.GroupHaving;
import io.evitadb.api.query.filter.HierarchyAnyHaving;
import io.evitadb.api.query.filter.HierarchyExcluding;
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.filter.HierarchyHaving;
import io.evitadb.api.query.filter.HierarchyReferenceSpecificationFilterConstraint;
import io.evitadb.api.query.filter.HistogramHaving;
import io.evitadb.api.query.filter.Not;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.filter.TextMatches;
import io.evitadb.api.query.order.*;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSearchMode;
import io.evitadb.core.query.filter.translator.fulltext.TextMatchesTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.fulltext.FulltextFieldDefinition;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextScoringPolicy.Place;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Validates each relevance ordering against schemas and its statically reachable scoring searches before any
 * index is consulted. Ordering scopes narrow the required capabilities; segment predicates belong only to their
 * own ordering. Immutable overrides travel to the sorter, never to search preparation.
 */
final class FulltextWeightResolver {
	/** Prevents construction of this stateless planning helper. */
	private FulltextWeightResolver() { }

	/**
	 * Tells whether the query states a place weight, including without text, so planning can reject weights without
	 * a collection before resolving schemas. Childless relevance is detected separately. It reads no schema.
	 *
	 * @param query the query being planned
	 * @return true when a weight constraint occurs anywhere in the ordering or the requirements of the query
	 */
	static boolean hasWeights(@Nonnull Query query) {
		return contains(query.getOrderBy(), FulltextWeightResolver::isWeight) ||
			contains(query.getRequire(), FulltextWeightResolver::isWeight);
	}

	/**
	 * Finds relevance orderings without the single-occurrence restriction of QueryUtils.findOrder.
	 *
	 * @param query the query being planned
	 * @return true when a `relevanceText` occurs anywhere in the ordering or the requirements of the query
	 */
	static boolean hasRelevance(@Nonnull Query query) {
		return contains(query.getOrderBy(), it -> it instanceof RelevanceText) ||
			contains(query.getRequire(), it -> it instanceof RelevanceText);
	}

	/**
	 * Searches both ordinary and additional children, including fetched-reference orderings.
	 *
	 * @param constraint root of the walked tree, NULL when the query lacks this part
	 * @param predicate  the test a constraint of the tree has to pass
	 * @return true when the root or any of its descendants satisfies the predicate, false for a NULL root
	 */
	private static boolean contains(
		@Nullable Constraint<?> constraint, @Nonnull Predicate<Constraint<?>> predicate
	) {
		if (constraint == null) {
			return false;
		}
		if (predicate.test(constraint)) {
			return true;
		}
		if (constraint instanceof ConstraintContainer<?> container) {
			for (final Constraint<?> child : container.getChildren()) {
				if (contains(child, predicate)) {
					return true;
				}
			}
			for (final Constraint<?> child : container.getAdditionalChildren()) {
				if (contains(child, predicate)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Identifies all four weight kinds independently of their level marker interfaces.
	 *
	 * @param constraint the constraint to classify
	 * @return true for `attributeWeight`, `associatedDataWeight`, `referenceWeight` and `entityWeight`
	 */
	private static boolean isWeight(@Nonnull Constraint<?> constraint) {
		return constraint instanceof AttributeWeight || constraint instanceof AssociatedDataWeight ||
			constraint instanceof ReferenceWeight || constraint instanceof EntityWeight;
	}

	/**
	 * Tells whether a weight stands directly in a container of its own level.
	 *
	 * @param weight the place weight whose placement is checked
	 * @param parent the constraint directly enclosing the weight, NULL when the weight is a root
	 * @return true when the parent is `relevanceText`, `referenceWeight` or `entityWeight` as the weight kind allows
	 */
	private static boolean isAllowedParent(@Nonnull Constraint<?> weight, @Nullable Constraint<?> parent) {
		if (weight instanceof AttributeWeight) {
			return parent instanceof RelevanceText || parent instanceof ReferenceWeight ||
				parent instanceof EntityWeight;
		} else if (weight instanceof AssociatedDataWeight) {
			return parent instanceof RelevanceText || parent instanceof EntityWeight;
		} else if (weight instanceof ReferenceWeight) {
			return parent instanceof RelevanceText;
		} else if (weight instanceof EntityWeight) {
			return parent instanceof ReferenceWeight;
		} else {
			throw new GenericEvitaInternalError("Constraint `" + weight + "` is not a place weight!");
		}
	}

	/**
	 * Tells whether an ordering container orders referenced entities or references rather than the queried ones.
	 *
	 * @param constraint the ordering constraint being walked
	 * @return true for `referenceProperty`, `entityProperty`, `entityGroupProperty`, `traverseByEntityProperty` and
	 *         `pickFirstByEntityProperty`
	 */
	private static boolean isNestedOrderingContainer(@Nonnull Constraint<?> constraint) {
		return constraint instanceof ReferenceProperty || constraint instanceof EntityProperty ||
			constraint instanceof EntityGroupProperty || constraint instanceof TraverseByEntityProperty ||
			constraint instanceof PickFirstByEntityProperty;
	}

	/**
	 * Checks placement and every relevance ordering independently, including on empty collections.
	 *
	 * @param context planning context of the queried collection, supplying the request, schemas and processed scopes
	 * @return the validated weights of every `relevanceText` of the main ordering, keyed by the ordering constraint
	 *         (equal orderings share one entry); an ordering stating no weight maps to an empty map
	 * @throws EvitaInvalidUsageException when an ordering or a weight is misplaced, a weight names an unknown or
	 *                                    unsearchable place, weights a place twice, is negative or outside the
	 *                                    internal float range, or weights a place no scoring search in its reach
	 *                                    reads
	 */
	@Nonnull
	static Map<RelevanceText, Map<Place, Float>> validate(@Nonnull QueryPlanningContext context) {
		final Query query = context.getEvitaRequest().getQuery();
		validatePlacement(query.getOrderBy(), null, true);
		validatePlacement(query.getRequire(), null, false);
		final Map<RelevanceText, Map<Place, Float>> weights = new HashMap<>();
		validateOrderings(context, query.getOrderBy(), context.getScopes(), null, weights);
		return Map.copyOf(weights);
	}

	/**
	 * Checks each relevance ordering against the main filter and only its own segment predicate.
	 *
	 * @param context planning context of the queried collection
	 * @param constraint the ordering constraint being walked, NULL when the query has no ordering
	 * @param scopes the scopes the enclosing ordering covers, narrowed by every `inScope` above `constraint`
	 * @param segmentPredicate the `entityHaving` of the enclosing segment, NULL outside a segment or for a segment
	 *                         without one
	 * @param resolved receives the validated weights of every `relevanceText` reached
	 */
	private static void validateOrderings(
		@Nonnull QueryPlanningContext context, @Nullable Constraint<?> constraint, @Nonnull Set<Scope> scopes,
		@Nullable EntityHaving segmentPredicate, @Nonnull Map<RelevanceText, Map<Place, Float>> resolved
	) {
		if (constraint == null || isNestedOrderingContainer(constraint)) {
			return;
		}
		final Set<Scope> currentScopes = scopesWithin(constraint, scopes);
		final EntityHaving predicate = constraint instanceof Segment segment ?
			segment.getEntityHaving().orElse(null) : segmentPredicate;
		if (constraint instanceof RelevanceText relevance) {
			final Query query = context.getEvitaRequest().getQuery();
			final Map<Place, Float> weights = new HashMap<>();
			final Map<Place, OrderConstraint> stated = new HashMap<>();
			collectWeights(context, context.getSchema(), null, null, relevance, weights, stated, currentScopes);
			final SearchCoverage coverage = new SearchCoverage();
			collectSearched(context, query.getFilterBy(), null, currentScopes, coverage, true, 0, true);
			if (predicate != null) {
				for (final Constraint<?> child : predicate.getChildren()) {
					collectSearched(context, child, null, currentScopes, coverage, true, 0, true);
				}
			}
			if (!coverage.scoring && coverage.hierarchy && !coverage.otherText) {
				throw invalid(relevance,
					"text in hierarchy having / anyHaving / excluding filters the tree and is not scored");
			}
			final boolean anyText = contains(query.getFilterBy(), it -> it instanceof TextMatches) ||
				contains(query.getOrderBy(), it -> it instanceof TextMatches) ||
				contains(query.getRequire(), it -> it instanceof TextMatches);
			if (!coverage.scoring && anyText) {
				collectSearched(context, query.getFilterBy(), null, context.getScopes(), coverage, true, 0, false);
				collectUnscoredSegmentPlaces(context, query.getOrderBy(), context.getScopes(), coverage);
			}
			if (coverage.scoring || anyText) {
				for (final Place place : weights.keySet()) {
					if (!coverage.places.contains(place)) {
						throw invalid(stated.get(place),
							"no scoring textMatches read by this relevanceText searches this place; " +
							"text in hierarchy predicates, negative reference positions, other segments or " +
							"require containers only filters and is not scored for this relevanceText");
					}
				}
			}
			resolved.put(relevance, Map.copyOf(weights));
			return;
		}
		if (constraint instanceof ConstraintContainer<?> container) {
			for (final Constraint<?> child : container.getChildren()) {
				validateOrderings(context, child, currentScopes, predicate, resolved);
			}
		}
	}

	/**
	 * Narrows the scopes an ordering covers at `inScope`: such a container keeps its own scope only when the enclosing
	 * ordering covers it, and covers nothing otherwise. Every other constraint inherits the enclosing scopes.
	 *
	 * @param constraint the ordering constraint being walked
	 * @param scopes the scopes covered by the constraint's parent
	 * @return the scopes covered by `constraint` and its children, empty when an `inScope` names an uncovered scope
	 */
	@Nonnull
	private static Set<Scope> scopesWithin(@Nonnull Constraint<?> constraint, @Nonnull Set<Scope> scopes) {
		if (!(constraint instanceof OrderInScope scoped)) {
			return scopes;
		}
		return scopes.contains(scoped.getScope()) ? Set.of(scoped.getScope()) : Set.of();
	}

	/** Schema-only reachability, deliberately independent of prepared formulas and the presence of index data. */
	private static final class SearchCoverage {
		/** Owner places searched by sources this ordering can score. */
		@Nonnull private final Set<Place> places = new HashSet<>();
		/** Whether a reachable search can supply scores, even when it currently matches nothing. */
		private boolean scoring;
		/** Whether a reachable hierarchy predicate contains text used only for tree membership. */
		private boolean hierarchy;
		/** Reachable text outside having/anyHaving/excluding, including unscored parent selectors. */
		private boolean otherText;
	}

	/**
	 * Checks ordering and requirement trees, refusing relevance outside the main ordering and misplaced weights.
	 *
	 * @param constraint the constraint being walked, NULL when the query lacks this part
	 * @param parent the constraint directly enclosing `constraint`, NULL at the root
	 * @param main true while the walk is inside the main query's ordering, false inside requirements and inside
	 *             orderings of referenced entities or references
	 */
	private static void validatePlacement(
		@Nullable Constraint<?> constraint, @Nullable Constraint<?> parent, boolean main
	) {
		if (constraint == null) {
			return;
		}
		final boolean mainOrdering = main && !isNestedOrderingContainer(constraint);
		if (constraint instanceof RelevanceText relevance) {
			if (!mainOrdering) {
				throw invalid(relevance, "can only be used in the top-level `orderBy` or inside `segment`");
			}
		} else if (isWeight(constraint) && !isAllowedParent(constraint, parent)) {
			throw invalid(constraint, "is outside its allowed weight parent");
		}
		if (constraint instanceof ConstraintContainer<?> container) {
			for (final Constraint<?> child : container.getChildren()) {
				validatePlacement(child, constraint, mainOrdering);
			}
			for (final Constraint<?> child : container.getAdditionalChildren()) {
				validatePlacement(child, constraint, mainOrdering);
			}
		}
	}

	/**
	 * Resolves each named place in the owner, reference or composed-target namespace against the schemas, validates
	 * its weight and records it. Nested `referenceWeight` and `entityWeight` containers are resolved recursively.
	 *
	 * @param context the planning context supplying the processed scopes and the catalog schema
	 * @param schema schema in whose namespace the container's children are resolved
	 * @param reference reference whose attributes the children name, NULL when they name attributes or associated
	 *                  data of `schema` itself
	 * @param composingReference name of the reference composing the searched entity, NULL for the queried entity's
	 *                           own places
	 * @param container the `relevanceText`, `referenceWeight` or `entityWeight` whose children are resolved
	 * @param overrides receives the weight of every resolved place
	 * @param stated receives the constraint that stated every resolved place, for later error reporting
	 * @param scopes scopes covered by this relevance ordering
	 * @throws EvitaInvalidUsageException when a place is unknown, not searchable, weighted twice, negatively or
	 *                                    outside the internal float range, or a nested container is empty or has no
	 *                                    managed target collection
	 */
	private static void collectWeights(
		@Nonnull QueryPlanningContext context, @Nonnull EntitySchemaContract schema,
		@Nullable ReferenceSchemaContract reference, @Nullable String composingReference,
		@Nonnull ConstraintContainer<OrderConstraint> container, @Nonnull Map<Place, Float> overrides,
		@Nonnull Map<Place, OrderConstraint> stated, @Nonnull Set<Scope> scopes
	) {
		if (!(container instanceof RelevanceText) && container.getChildrenCount() == 0) {
			throw invalid(container, "requires at least one child weight");
		}
		for (final OrderConstraint child : container.getChildren()) {
			final FulltextFieldKey key;
			final BigDecimal weight;
			if (child instanceof AttributeWeight attribute) {
				final String name = attribute.getAttributeName();
				final AttributeSchemaContract definition = reference == null ? schema.getAttributes().get(name) :
					FulltextFieldDefinition.availableReferenceAttributes(reference).get(name);
				if (definition == null) {
					throw new AttributeNotFoundException(name, schema, reference, child.toString());
				}
				assertScopes(child, scopes, definition::isSearchableInScope, "is not searchable");
				key = reference == null ? FulltextFieldKey.attribute(name) :
					FulltextFieldKey.referenceAttribute(reference.getName(), name);
				weight = attribute.getWeight();
			} else if (child instanceof AssociatedDataWeight data) {
				final String name = data.getAssociatedDataName();
				final AssociatedDataSchemaContract definition = schema.getAssociatedData(name)
					.orElseThrow(() -> new AssociatedDataNotFoundException(name, schema, child.toString()));
				assertScopes(child, scopes, definition::isSearchableInScope, "is not searchable");
				key = FulltextFieldKey.associatedData(name);
				weight = data.getWeight();
			} else if (child instanceof ReferenceWeight ref) {
				final ReferenceSchemaContract definition = schema.getReference(ref.getReferenceName())
					.orElseThrow(
						() -> new ReferenceNotFoundException(ref.getReferenceName(), schema, child.toString()));
				collectWeights(context, schema, definition, null, ref, overrides, stated, scopes);
				continue;
			} else if (child instanceof EntityWeight entity) {
				assertScopes(child, scopes,
					scope -> reference.getSearchableThrough(scope) == ReferenceSearchMode.COMPOSITION,
					"reference " + reference.getName() + " requires searchableThrough(COMPOSITION)");
				final EntitySchemaContract target = context.getCatalogSchema()
					.getEntitySchema(reference.getReferencedEntityType()).orElse(null);
				if (!reference.isReferencedEntityTypeManaged() || target == null) {
					throw invalid(child, "reference " + reference.getName() + " has no managed target collection");
				}
				collectWeights(context, target, null, reference.getName(), entity, overrides, stated, scopes);
				continue;
			} else {
				throw invalid(child, "is not a place weight");
			}
			if (weight.signum() < 0) {
				throw invalid(child, "requires a nonnegative weight");
			}
			final float effectiveWeight = weight.floatValue();
			if (!Float.isFinite(effectiveWeight)) {
				throw invalid(child, "weight overflows the internal float range");
			}
			if (weight.signum() != 0 && effectiveWeight == 0.0f) {
				throw invalid(child, "non-zero weight underflows the internal float range");
			}
			final Place place = new Place(composingReference, key);
			if (overrides.putIfAbsent(place, effectiveWeight) != null) {
				throw invalid(child, "weights the same place twice");
			}
			stated.put(place, child);
		}
	}

	/** Names all missing capabilities in enumeration order, matching filterable and sortable scope validation. */
	private static void assertScopes(
		@Nonnull Constraint<?> constraint, @Nonnull Set<Scope> scopes, @Nonnull Predicate<Scope> searchable,
		@Nonnull String reason
	) {
		final List<Scope> missing = new ArrayList<>(scopes.size());
		for (final Scope scope : Scope.values()) {
			if (scopes.contains(scope) && !searchable.test(scope)) {
				missing.add(scope);
			}
		}
		if (!missing.isEmpty()) {
			throw invalid(constraint, reason + (missing.size() == 1 ? " in scope " : " in scopes ") +
				String.join(", ", missing.stream().map(scope -> "`" + scope.name() + "`").toList()) +
				"; narrow the ordering with inScope");
		}
	}

	/**
	 * Classifies searches statically, respecting selectors and filter scopes. Hierarchy predicates never score.
	 * Direct reference text under odd body negation has no positive owners and cannot consume an override when
	 * scoringOnly is true. A false value preserves the prior searched-place check for pass-through relevance.
	 * Entity/group bodies supply fixed-policy scores in another namespace, so they contribute no owner places.
	 * Facet descendant predicates and histogram group selectors supply no owner scoring sources at all.
	 *
	 * @param context planning context supplying the queried schema and the catalog schema
	 * @param constraint the filter constraint being walked, NULL when the filter is absent
	 * @param reference the reference whose body is being walked, NULL outside a `referenceHaving` body
	 * @param scopes the scopes reachable at this point, narrowed by every filter `inScope` above it
	 * @param coverage receives the searched owner places and the kinds of text reached
	 * @param ownerNamespace false below `entityHaving` or `groupHaving`, whose text searches another collection and
	 *                       contributes no owner place
	 * @param negations number of `not` containers between the nearest enclosing `referenceHaving` (or the root) and
	 *                  `constraint`; an odd count leaves direct reference text without a positive owner
	 * @param scoringOnly true to count only text that can score for this ordering, which excludes all hierarchy
	 *                    filter text and direct reference text under odd negation; false to count reference text
	 *                    under odd negation and text in hierarchy filters of the queried collection itself, while
	 *                    hierarchy filters of a reference are still skipped
	 */
	private static void collectSearched(
		@Nonnull QueryPlanningContext context, @Nullable Constraint<?> constraint,
		@Nullable ReferenceSchemaContract reference, @Nonnull Set<Scope> scopes, @Nonnull SearchCoverage coverage,
		boolean ownerNamespace, int negations, boolean scoringOnly
	) {
		if (constraint == null || scopes.isEmpty()) {
			return;
		}
		if (constraint instanceof HierarchyReferenceSpecificationFilterConstraint ||
			constraint instanceof HistogramHaving) {
			// Facet descendants filter target hierarchy nodes; histogram group selectors filter group entities.
			// Both use separate predicate visitors whose scores never enter this owner's source registrations.
			coverage.otherText |= contains(constraint, it -> it instanceof TextMatches);
			return;
		}
		if (constraint instanceof HierarchyFilterConstraint hierarchy) {
			if (scoringOnly) {
				collectHierarchyText(constraint, scopes, coverage, false);
				return;
			} else if (hierarchy.getReferenceName().isPresent()) {
				return;
			}
		}
		final Set<Scope> currentScopes;
		if (constraint instanceof FilterInScope scoped) {
			if (!scopes.contains(scoped.getScope())) {
				return;
			}
			currentScopes = Set.of(scoped.getScope());
		} else {
			currentScopes = scopes;
		}
		final boolean currentNamespace = ownerNamespace &&
			!(constraint instanceof EntityHaving || constraint instanceof GroupHaving);
		final ReferenceSchemaContract currentReference;
		if (constraint instanceof ReferenceHaving having && currentNamespace) {
			currentReference = context.getSchema().getReference(having.getReferenceName()).orElseThrow(
				() -> new ReferenceNotFoundException(having.getReferenceName(), context.getSchema()));
		} else {
			currentReference = currentNamespace ? reference : null;
		}
		final int currentNegations = constraint instanceof ReferenceHaving ? 0 :
			negations + (constraint instanceof Not ? 1 : 0);
		if (constraint instanceof TextMatches text) {
			coverage.otherText = true;
			if (!scoringOnly || currentReference == null || currentNegations % 2 == 0) {
				coverage.scoring = true;
				if (currentNamespace) {
					coverage.places.addAll(TextMatchesTranslator.searchedPlaces(
						text, context.getSchema(), currentReference, currentScopes, context.getCatalogSchema()));
				}
			}
			return;
		}
		if (constraint instanceof ConstraintContainer<?> container) {
			for (final Constraint<?> child : container.getChildren()) {
				collectSearched(context, child, currentReference, currentScopes, coverage,
					currentNamespace, currentNegations, scoringOnly);
			}
			for (final Constraint<?> child : container.getAdditionalChildren()) {
				collectSearched(context, child, currentReference, currentScopes, coverage,
					currentNamespace, currentNegations, scoringOnly);
			}
		}
	}

	/**
	 * Distinguishes the specified tree predicates from parent-node selectors without reading an index. Only text
	 * confined to having/anyHaving/excluding triggers the hierarchy-only refusal; parent selection keeps its prior
	 * pass-through behavior. Scope containers narrow reachability just as they do in scored filters.
	 *
	 * @param constraint the constraint inside a hierarchy filter being walked
	 * @param scopes the scopes reachable at this point
	 * @param coverage receives whether text sits in a tree predicate or elsewhere
	 * @param withinPredicate true below `having`, `anyHaving` or `excluding`, whose text only filters the tree
	 */
	private static void collectHierarchyText(
		@Nonnull Constraint<?> constraint, @Nonnull Set<Scope> scopes, @Nonnull SearchCoverage coverage,
		boolean withinPredicate
	) {
		final Set<Scope> currentScopes;
		if (constraint instanceof FilterInScope scoped) {
			if (!scopes.contains(scoped.getScope())) {
				return;
			}
			currentScopes = Set.of(scoped.getScope());
		} else {
			currentScopes = scopes;
		}
		final boolean predicate = withinPredicate || constraint instanceof HierarchyHaving ||
			constraint instanceof HierarchyAnyHaving || constraint instanceof HierarchyExcluding;
		if (constraint instanceof TextMatches) {
			coverage.hierarchy |= predicate;
			coverage.otherText |= !predicate;
		} else if (constraint instanceof ConstraintContainer<?> container) {
			for (final Constraint<?> child : container.getChildren()) {
				collectHierarchyText(child, currentScopes, coverage, predicate);
			}
			for (final Constraint<?> child : container.getAdditionalChildren()) {
				collectHierarchyText(child, currentScopes, coverage, predicate);
			}
		}
	}

	/**
	 * Preserves the earlier searched-place check when this relevance has no scoring text in reach. This keeps
	 * require-only weights refused and weights searched solely by another segment judged as before; neither kind
	 * supplies scores here. Once scoring text is in reach, only its own scored places can consume weights.
	 *
	 * @param context planning context of the queried collection
	 * @param constraint the ordering constraint being walked, NULL when the query has no ordering
	 * @param scopes the scopes the enclosing ordering covers
	 * @param coverage receives the places searched by the predicate of every segment
	 */
	private static void collectUnscoredSegmentPlaces(
		@Nonnull QueryPlanningContext context, @Nullable Constraint<?> constraint, @Nonnull Set<Scope> scopes,
		@Nonnull SearchCoverage coverage
	) {
		if (constraint == null || isNestedOrderingContainer(constraint)) {
			return;
		}
		final Set<Scope> currentScopes = scopesWithin(constraint, scopes);
		if (constraint instanceof Segment segment && segment.getEntityHaving().isPresent()) {
			for (final Constraint<?> child : segment.getEntityHaving().orElseThrow().getChildren()) {
				collectSearched(context, child, null, currentScopes, coverage, true, 0, false);
			}
		}
		if (constraint instanceof ConstraintContainer<?> container) {
			for (final Constraint<?> child : container.getChildren()) {
				collectUnscoredSegmentPlaces(context, child, currentScopes, coverage);
			}
		}
	}

	/**
	 * Names the exact constraint as stated by the query in every planning refusal.
	 *
	 * @param constraint the offending constraint, quoted in the message
	 * @param reason what is wrong with it, appended to the message
	 * @return the exception to throw, it is not thrown here
	 */
	@Nonnull
	private static EvitaInvalidUsageException invalid(@Nonnull Constraint<?> constraint, @Nonnull String reason) {
		return new EvitaInvalidUsageException("Constraint `" + constraint + "` " + reason + "!");
	}
}
