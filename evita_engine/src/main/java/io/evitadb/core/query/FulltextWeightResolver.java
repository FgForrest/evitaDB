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
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
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
import io.evitadb.index.fulltext.FulltextScoringPolicy;
import io.evitadb.index.fulltext.FulltextScoringPolicy.Place;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Resolves the single fulltext weight policy of a query from the place weights of its `relevanceText` constraints.
 * It validates them against schemas only, before any index is selected or read, so a query is refused or accepted the
 * same way whether or not its collections hold any entity.
 *
 * It refuses `relevanceText` outside the main query's ordering, weights outside their allowed parent, unknown
 * attribute, associated data and reference names, places not searchable in any processed scope, target weights of
 * references that do not compose a managed collection in any processed scope, empty `referenceWeight` and
 * `entityWeight` containers, negative weights, places weighted twice, and `relevanceText` constraints stating
 * different weights. Only when the query contains a `textMatches` does it also refuse weights of places that no
 * `textMatches` of the main filter or of a segment's `entityHaving` searches.
 */
final class FulltextWeightResolver {
	/** Prevents construction of this stateless planning helper. */
	private FulltextWeightResolver() { }

	/**
	 * Tells whether the query states any place weight, so that the planner resolves a policy for it even without a
	 * `textMatches`, while childless relevance ordering resolves none. It reads no schema.
	 *
	 * @param query the query being planned
	 * @return true when a weight constraint occurs anywhere in the ordering or the requirements of the query
	 */
	static boolean hasWeights(@Nonnull Query query) {
		return contains(query.getOrderBy(), FulltextWeightResolver::isWeight) ||
			contains(query.getRequire(), FulltextWeightResolver::isWeight);
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
	 * Builds the effective policy of the context's own collection from its schema defaults and the query's weights,
	 * refusing the query when a weight breaks one of the rules listed on this class.
	 *
	 * @param context planning context of the queried collection, supplying the request, schemas and processed scopes
	 * @param defaults schema defaults of the queried collection
	 * @return `defaults` itself when no `relevanceText` states a weight, otherwise a new policy with the weights
	 *         applied
	 * @throws EvitaInvalidUsageException when a weight is misplaced, unknown, not searchable, negative, repeated or
	 *                                    disagrees with another `relevanceText`, or names a place the query does not
	 *                                    search
	 */
	@Nonnull
	static FulltextScoringPolicy resolve(
		@Nonnull QueryPlanningContext context, @Nonnull FulltextScoringPolicy defaults
	) {
		final Query query = context.getEvitaRequest().getQuery();
		final List<RelevanceText> containers = new ArrayList<>(2);
		collectOrderings(query.getOrderBy(), null, true, containers);
		collectOrderings(query.getRequire(), null, false, containers);
		Map<Place, Float> agreed = null;
		final Map<Place, OrderConstraint> stated = new HashMap<>();
		for (final RelevanceText container : containers) {
			final Map<Place, Float> overrides = new HashMap<>();
			collectWeights(context, context.getSchema(), null, null, container, overrides, stated);
			if (agreed != null && !agreed.equals(overrides)) {
				throw invalid(container, "all relevanceText containers of a query must state the same overrides");
			}
			agreed = overrides;
		}
		if (agreed == null || agreed.isEmpty()) {
			return defaults;
		}
		final Predicate<Constraint<?>> text = constraint -> constraint instanceof TextMatches;
		final boolean searchesText = contains(query.getFilterBy(), text) || contains(query.getOrderBy(), text) ||
			contains(query.getRequire(), text);
		if (searchesText) {
			final Set<Place> searched = new HashSet<>();
			collectSearched(context, query.getFilterBy(), null, context.getScopes(), searched);
			collectSegmentSearches(context, query.getOrderBy(), context.getScopes(), searched);
			for (final Place place : agreed.keySet()) {
				if (!searched.contains(place)) {
					throw invalid(stated.get(place), "the query does not search this place");
				}
			}
		}
		return defaults.withOverrides(agreed);
	}

	/**
	 * Walks an ordering or requirement tree, collects the `relevanceText` constraints of the main ordering, and refuses
	 * `relevanceText` anywhere else as well as any weight outside its allowed parent.
	 *
	 * @param constraint the constraint being walked, NULL when the query lacks this part
	 * @param parent the constraint directly enclosing `constraint`, NULL at the root
	 * @param main true while the walk is inside the main query's ordering, false inside requirements and inside
	 *             orderings of referenced entities or references
	 * @param containers the collected `relevanceText` constraints in query order
	 */
	private static void collectOrderings(
		@Nullable Constraint<?> constraint, @Nullable Constraint<?> parent, boolean main,
		@Nonnull List<RelevanceText> containers
	) {
		if (constraint == null) {
			return;
		}
		final boolean mainOrdering = main && !isNestedOrderingContainer(constraint);
		if (constraint instanceof RelevanceText relevance) {
			if (!mainOrdering) {
				throw invalid(relevance, "can only be used in the top-level `orderBy` or inside `segment`");
			}
			containers.add(relevance);
		} else if (isWeight(constraint) && !isAllowedParent(constraint, parent)) {
			throw invalid(constraint, "is outside its allowed weight parent");
		}
		if (constraint instanceof ConstraintContainer<?> container) {
			for (final Constraint<?> child : container.getChildren()) {
				collectOrderings(child, constraint, mainOrdering, containers);
			}
			for (final Constraint<?> child : container.getAdditionalChildren()) {
				collectOrderings(child, constraint, mainOrdering, containers);
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
	 * @throws EvitaInvalidUsageException when a place is unknown, not searchable, weighted twice or negatively, or
	 *                                    a nested container is empty or has no managed target collection
	 */
	private static void collectWeights(
		@Nonnull QueryPlanningContext context, @Nonnull EntitySchemaContract schema,
		@Nullable ReferenceSchemaContract reference, @Nullable String composingReference,
		@Nonnull ConstraintContainer<OrderConstraint> container, @Nonnull Map<Place, Float> overrides,
		@Nonnull Map<Place, OrderConstraint> stated
	) {
		if (!(container instanceof RelevanceText) && container.getChildrenCount() == 0) {
			throw invalid(container, "requires at least one child weight");
		}
		for (final OrderConstraint child : container.getChildren()) {
			final FulltextFieldKey key;
			final float weight;
			if (child instanceof AttributeWeight attribute) {
				final String name = attribute.getAttributeName();
				final AttributeSchemaContract definition = reference == null ? schema.getAttributes().get(name) :
					FulltextFieldDefinition.availableReferenceAttributes(reference).get(name);
				if (definition == null) {
					throw new AttributeNotFoundException(name, schema, reference, child.toString());
				}
				if (context.getScopes().stream().noneMatch(definition::isSearchableInScope)) {
					throw invalid(child, "is not searchable in any of the processed scopes " + context.getScopes());
				}
				key = reference == null ? FulltextFieldKey.attribute(name) :
					FulltextFieldKey.referenceAttribute(reference.getName(), name);
				weight = attribute.getWeight();
			} else if (child instanceof AssociatedDataWeight data) {
				final String name = data.getAssociatedDataName();
				final AssociatedDataSchemaContract definition = schema.getAssociatedData(name)
					.orElseThrow(() -> new AssociatedDataNotFoundException(name, schema, child.toString()));
				if (context.getScopes().stream().noneMatch(definition::isSearchableInScope)) {
					throw invalid(child, "is not searchable in any of the processed scopes " + context.getScopes());
				}
				key = FulltextFieldKey.associatedData(name);
				weight = data.getWeight();
			} else if (child instanceof ReferenceWeight ref) {
				final ReferenceSchemaContract definition = schema.getReference(ref.getReferenceName())
					.orElseThrow(
						() -> new ReferenceNotFoundException(ref.getReferenceName(), schema, child.toString()));
				collectWeights(context, schema, definition, null, ref, overrides, stated);
				continue;
			} else if (child instanceof EntityWeight entity) {
				if (context.getScopes().stream().noneMatch(
					scope -> reference.getSearchableThrough(scope) == ReferenceSearchMode.COMPOSITION)) {
					throw invalid(child, "reference " + reference.getName() +
						" requires searchableThrough(COMPOSITION) in at least one processed scope " +
						context.getScopes());
				}
				final EntitySchemaContract target = context.getCatalogSchema()
					.getEntitySchema(reference.getReferencedEntityType()).orElse(null);
				if (!reference.isReferencedEntityTypeManaged() || target == null) {
					throw invalid(child, "reference " + reference.getName() + " has no managed target collection");
				}
				collectWeights(context, target, null, reference.getName(), entity, overrides, stated);
				continue;
			} else {
				throw invalid(child, "is not a place weight");
			}
			if (weight < 0) {
				throw invalid(child, "requires a nonnegative weight");
			}
			final Place place = new Place(composingReference, key);
			if (overrides.putIfAbsent(place, weight) != null) {
				throw invalid(child, "weights the same place twice");
			}
			stated.put(place, child);
		}
	}

	/**
	 * Uses the selector resolver in each owner processing scope; entity/group bodies keep their fixed source
	 * policy.
	 *
	 * @param context the planning context supplying the schemas
	 * @param constraint the filter constraint being walked, NULL when the query has no such part
	 * @param reference reference the walked constraint is nested in via `referenceHaving`, NULL at the top level
	 * @param scopes processing scopes in effect, narrowed by `filterInScope`
	 * @param searched receives the places every reached `textMatches` searches
	 */
	private static void collectSearched(
		@Nonnull QueryPlanningContext context, @Nullable Constraint<?> constraint,
		@Nullable ReferenceSchemaContract reference, @Nonnull Set<Scope> scopes, @Nonnull Set<Place> searched
	) {
		if (constraint == null || constraint instanceof EntityHaving || constraint instanceof GroupHaving ||
			constraint instanceof HierarchyFilterConstraint hierarchy && hierarchy.getReferenceName().isPresent()) {
			return;
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
		final ReferenceSchemaContract currentReference;
		if (constraint instanceof ReferenceHaving having) {
			currentReference = context.getSchema().getReference(having.getReferenceName()).orElseThrow(
				() -> new ReferenceNotFoundException(having.getReferenceName(), context.getSchema()));
		} else {
			currentReference = reference;
		}
		if (constraint instanceof TextMatches text) {
			searched.addAll(TextMatchesTranslator.searchedPlaces(
				text, context.getSchema(), currentReference, currentScopes, context.getCatalogSchema()));
			return;
		}
		if (constraint instanceof ConstraintContainer<?> container) {
			for (final Constraint<?> child : container.getChildren()) {
				collectSearched(context, child, currentReference, currentScopes, searched);
			}
			for (final Constraint<?> child : container.getAdditionalChildren()) {
				collectSearched(context, child, currentReference, currentScopes, searched);
			}
		}
	}

	/**
	 * Segment predicates search the main collection even though their container is named entityHaving.
	 *
	 * @param context the planning context supplying the schemas
	 * @param constraint the ordering constraint being walked, NULL when the query has no ordering
	 * @param scopes processing scopes in effect, narrowed by `orderInScope`
	 * @param searched receives the places searched by the `entityHaving` predicates of the reached segments
	 */
	private static void collectSegmentSearches(
		@Nonnull QueryPlanningContext context, @Nullable Constraint<?> constraint,
		@Nonnull Set<Scope> scopes, @Nonnull Set<Place> searched
	) {
		if (constraint == null || isNestedOrderingContainer(constraint)) {
			return;
		}
		final Set<Scope> currentScopes;
		if (constraint instanceof OrderInScope scoped) {
			if (!scopes.contains(scoped.getScope())) {
				return;
			}
			currentScopes = Set.of(scoped.getScope());
		} else {
			currentScopes = scopes;
		}
		if (constraint instanceof Segment segment && segment.getEntityHaving().isPresent()) {
			for (final Constraint<?> child : segment.getEntityHaving().orElseThrow().getChildren()) {
				collectSearched(context, child, null, currentScopes, searched);
			}
		}
		if (constraint instanceof ConstraintContainer<?> container) {
			for (final Constraint<?> child : container.getChildren()) {
				collectSegmentSearches(context, child, currentScopes, searched);
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
