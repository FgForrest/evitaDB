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

package io.evitadb.api.query;

import io.evitadb.api.query.filter.DifferentEntityFilterContainer;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.EntityScope;
import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.filter.FilterInScope;
import io.evitadb.api.query.filter.GroupHaving;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.filter.SeparateEntityScopeContainer;
import io.evitadb.api.query.order.EntityGroupProperty;
import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.ReferenceOrderingSpecification;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.query.require.SeparateEntityContentRequireContainer;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.query.visitor.FinderVisitor.PredicateWithDescription;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Utility class providing methods for searching, extracting, and analyzing constraints within {@link Query}
 * and {@link Constraint} trees.
 *
 * **Purpose**
 *
 * `QueryUtils` serves as the primary API for navigating constraint hierarchies. It provides methods to:
 * - Find single constraints by type or predicate
 * - Find all constraints matching a type or predicate
 * - Search within specific query sections (filter, order, require)
 * - Control search depth with stopper predicates
 * - Compare constraint argument values
 *
 * **Common Usage Patterns**
 *
 * Finding a specific constraint type:
 * ```java
 * AttributeEquals constraint = QueryUtils.findConstraint(filterBy, AttributeEquals.class);
 * ```
 *
 * Finding all constraints of a type:
 * ```java
 * List<AttributeEquals> allEquals = QueryUtils.findConstraints(filterBy, AttributeEquals.class);
 * ```
 *
 * Finding in specific query sections:
 * ```java
 * Page page = QueryUtils.findRequire(query, Page.class);
 * EntityPrimaryKeyInSet pkFilter = QueryUtils.findFilter(query, EntityPrimaryKeyInSet.class);
 * ```
 *
 * Stopping search at container boundaries:
 * ```java
 * // Find AttributeEquals but don't search inside Or containers
 * AttributeEquals found = QueryUtils.findConstraint(filterBy, AttributeEquals.class, Or.class);
 * ```
 *
 * **Implementation Details**
 *
 * All search methods delegate to {@link io.evitadb.api.query.visitor.FinderVisitor}, which traverses the
 * constraint tree using the visitor pattern. The visitor:
 * - Recursively walks the constraint tree
 * - Applies matcher predicates to find matching constraints
 * - Respects stopper predicates to limit search depth
 * - Collects results into lists (for `findConstraints`) or returns first match (for `findConstraint`)
 *
 * **Type Safety**
 *
 * Methods use generics to return correctly-typed constraints without requiring explicit casts:
 * ```java
 * // Type inference ensures 'page' is of type Page
 * Page page = QueryUtils.findRequire(query, Page.class);
 * ```
 *
 * **Performance Considerations**
 *
 * - Searches are linear in the size of the constraint tree
 * - Use stopper predicates to limit search depth when possible
 * - Use type-specific search methods (findFilter, findOrder, findRequire) when the section is known
 * - Finding a single constraint is more efficient than finding all and taking the first
 *
 * **Thread Safety**
 *
 * All methods are stateless and thread-safe. The FinderVisitor instances created internally are not shared
 * across calls.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class QueryUtils {

	/** Private constructor to prevent instantiation of utility class. */
	private QueryUtils() {
	}

	/**
	 * Resolves only the locale opinion of this entity level, without inheriting a locale from its caller.
	 * Repeated equal locales coalesce; different locales, even under `or`, are invalid at the same level.
	 * Children of {@link DifferentEntityFilterContainer} belong to another level and are not searched.
	 * Pass those children in a filter container to resolve that nested level itself.
	 *
	 * @param filter the level's filter, NULL when it has none
	 * @return its own locale, or NULL when the level has no opinion
	 * @throws EvitaInvalidUsageException when the level names conflicting locales
	 */
	@Nullable
	public static Locale resolveLocale(@Nullable Constraint<?> filter) {
		if (filter == null) {
			return null;
		}
		final List<EntityLocaleEquals> constraints = FinderVisitor.findConstraints(
			filter, EntityLocaleEquals.class::isInstance, DifferentEntityFilterContainer.class::isInstance
		);
		Locale locale = null;
		for (final EntityLocaleEquals constraint : constraints) {
			final Locale candidate = constraint.getLocale();
			if (locale != null && !locale.equals(candidate)) {
				throw new EvitaInvalidUsageException(
					"Conflicting locales `" + locale.toLanguageTag() + "` and `" + candidate.toLanguageTag() +
						"` at one entity level in constraint `" + filter + "`."
				);
			}
			locale = candidate;
		}
		return locale;
	}

	/**
	 * Resolves the locale the referenced entity body - or the group body - of a reference is fetched in: the body
	 * level's own opinion, otherwise the opinion of the reference level holding it, otherwise the enclosing level's
	 * locale. The entity and the group are separate levels, so neither inherits the other's opinion.
	 *
	 * The body level consists of the children of every {@link EntityHaving} (or {@link GroupHaving}) the reference
	 * filter holds outside a nested {@link SeparateEntityScopeContainer}. The reference level is resolved by
	 * {@link #resolveLocale(Constraint)}, which stops only at a {@link DifferentEntityFilterContainer}: it descends
	 * into {@link ReferenceHaving} and {@link FacetHaving}, whose children stay at the owner's level; the body lookup
	 * stops there, because an `entityHaving` below it describes the target of another reference. Both levels are
	 * always resolved, so a conflict on either of them is refused even when the other one decides.
	 *
	 * @param referenceFilter the filter of the reference, NULL when it has none
	 * @param group           true to resolve the group body, false for the referenced entity body
	 * @param enclosingLocale the resolved locale of the level enclosing the reference, NULL when it has none
	 * @return the locale of the body, NULL when no level names one
	 * @throws EvitaInvalidUsageException when the reference level or the body level names conflicting locales
	 */
	@Nullable
	public static Locale resolveReferenceBodyLocale(
		@Nullable FilterBy referenceFilter, boolean group, @Nullable Locale enclosingLocale
	) {
		if (referenceFilter == null) {
			return enclosingLocale;
		}
		final Locale referenceLocale = resolveLocale(referenceFilter);
		final List<ConstraintContainer<FilterConstraint>> bodyLevels = FinderVisitor.findConstraints(
			referenceFilter,
			constraint -> group ? constraint instanceof GroupHaving : constraint instanceof EntityHaving,
			SeparateEntityScopeContainer.class::isInstance
		);
		final List<FilterConstraint> bodyFilters = new ArrayList<>(bodyLevels.size());
		for (final ConstraintContainer<FilterConstraint> bodyLevel : bodyLevels) {
			Collections.addAll(bodyFilters, bodyLevel.getChildren());
		}
		final Locale bodyLocale = resolveLocale(new FilterBy(bodyFilters.toArray(FilterConstraint[]::new)));
		if (bodyLocale != null) {
			return bodyLocale;
		}
		return referenceLocale == null ? enclosingLocale : referenceLocale;
	}

	/**
	 * Finds the first constraint of the specified type within the constraint tree.
	 *
	 * This method recursively searches the constraint tree starting from the given root constraint.
	 * It returns the first constraint whose runtime type matches the specified class.
	 *
	 * @param constraint the root constraint to start searching from
	 * @param constraintType the class of the constraint to find
	 * @param <T> the type of the constraint to find
	 * @return the first matching constraint, or null if no match is found
	 */
	@Nullable
	public static <T extends Constraint<?>> T findConstraint(@Nonnull Constraint<?> constraint, @Nonnull Class<T> constraintType) {
		return FinderVisitor.findConstraint(constraint, new ConstraintTypeMatcher(constraintType));
	}

	/**
	 * Finds the first constraint matching the specified predicate within the constraint tree.
	 *
	 * This method recursively searches the constraint tree, applying the predicate to each constraint
	 * until a match is found.
	 *
	 * @param constraint the root constraint to start searching from
	 * @param predicate the predicate to test each constraint against
	 * @param <T> the type of the constraint to find
	 * @return the first matching constraint, or null if no match is found
	 */
	@Nullable
	public static <T extends Constraint<?>> T findConstraint(@Nonnull Constraint<?> constraint, @Nonnull Predicate<Constraint<?>> predicate) {
		return FinderVisitor.findConstraint(constraint, predicate);
	}

	/**
	 * Finds the first constraint of the specified type, stopping search at specified container boundaries.
	 *
	 * This method is useful when you want to search within a specific scope and avoid descending into
	 * certain container types. For example, finding an `AttributeEquals` but not searching inside `Or`
	 * containers.
	 *
	 * The search stops descending when it encounters a container of the stop type, but the stop container
	 * itself is still checked against the constraint type (unless it's the root).
	 *
	 * @param constraint the root constraint to start searching from
	 * @param constraintType the class of the constraint to find
	 * @param stopContainerType containers of this type will not be descended into
	 * @param <T> the type of the constraint to find
	 * @return the first matching constraint, or null if no match is found
	 */
	@Nullable
	public static <T extends Constraint<?>> T findConstraint(@Nonnull Constraint<?> constraint, @Nonnull Class<T> constraintType, @Nonnull Class<? extends Constraint<?>> stopContainerType) {
		return FinderVisitor.findConstraint(constraint, new ConstraintTypeMatcher(constraintType), cnt -> cnt != constraint && stopContainerType.isInstance(cnt));
	}

	/**
	 * Finds all constraints of the specified type within the constraint tree.
	 *
	 * This method recursively searches the entire constraint tree and returns all constraints whose
	 * runtime type matches the specified class.
	 *
	 * @param constraint the root constraint to start searching from
	 * @param constraintType the class of the constraints to find
	 * @param <T> the type of the constraints to find
	 * @return list of all matching constraints (empty list if none found)
	 */
	@Nonnull
	public static <T extends Constraint<?>> List<T> findConstraints(Constraint<?> constraint, @Nonnull Class<T> constraintType) {
		return FinderVisitor.findConstraints(constraint, new ConstraintTypeMatcher(constraintType));
	}

	/**
	 * Finds all constraints of the specified type, stopping search at specified container boundaries.
	 *
	 * @param constraint the root constraint to start searching from
	 * @param constraintType the class of the constraints to find
	 * @param stopContainerType containers of this type will not be descended into
	 * @param <T> the type of the constraints to find
	 * @return list of all matching constraints (empty list if none found)
	 */
	@Nonnull
	public static <T extends Constraint<?>> List<T> findConstraints(@Nonnull Constraint<?> constraint, @Nonnull Class<T> constraintType, @Nonnull Class<? extends Constraint<?>> stopContainerType) {
		return FinderVisitor.findConstraints(constraint, new ConstraintTypeMatcher(constraintType), cnt -> cnt != constraint && stopContainerType.isInstance(cnt));
	}

	/**
	 * Finds all constraints matching the specified predicate within the constraint tree.
	 *
	 * @param constraint the root constraint to start searching from
	 * @param predicate the predicate to test each constraint against
	 * @param <T> the type of the constraints to find
	 * @return list of all matching constraints (empty list if none found)
	 */
	@Nonnull
	public static <T extends Constraint<?>> List<T> findConstraints(@Nonnull Constraint<?> constraint, @Nonnull Predicate<Constraint<?>> predicate) {
		return FinderVisitor.findConstraints(constraint, predicate);
	}

	/**
	 * Finds all constraints matching the specified predicate, with custom stopping logic.
	 *
	 * @param constraint the root constraint to start searching from
	 * @param predicate the predicate to test each constraint against
	 * @param stopper predicate that determines which containers should stop descent (if returns true, children are not searched)
	 * @param <T> the type of the constraints to find
	 * @return list of all matching constraints (empty list if none found)
	 */
	@Nonnull
	public static <T extends Constraint<?>> List<T> findConstraints(@Nonnull Constraint<?> constraint, @Nonnull Predicate<Constraint<?>> predicate, @Nonnull Predicate<Constraint<?>> stopper) {
		return FinderVisitor.findConstraints(constraint, predicate, stopper);
	}

	/**
	 * Finds the first filter constraint of the specified type within the query's filter section.
	 *
	 * This is a convenience method that searches only within the {@link Query#getFilterBy()} section.
	 * If the query has no filter section, null is returned.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the filter constraint to find
	 * @param <T> the type of the filter constraint to find
	 * @return the first matching filter constraint, or null if none found or query has no filter section
	 */
	@Nullable
	public static <T extends FilterConstraint> T findFilter(@Nonnull Query query, @Nonnull Class<T> constraintType) {
		final FilterBy filterBy = query.getFilterBy();
		if (filterBy == null) {
			return null;
		}
		//noinspection unchecked
		return FinderVisitor.findConstraint(filterBy, new ConstraintTypeMatcher(constraintType));
	}

	/**
	 * Finds the first filter constraint of the specified type, stopping at specified container boundaries.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the filter constraint to find
	 * @param stopContainerType filter containers of this type will not be descended into
	 * @param <T> the type of the filter constraint to find
	 * @return the first matching filter constraint, or null if none found or query has no filter section
	 */
	@Nullable
	public static <T extends FilterConstraint> T findFilter(@Nonnull Query query, @Nonnull Class<T> constraintType, @Nonnull Class<? extends FilterConstraint> stopContainerType) {
		final FilterBy filterBy = query.getFilterBy();
		if (filterBy == null) {
			return null;
		}
		//noinspection unchecked
		return FinderVisitor.findConstraint(filterBy, new ConstraintTypeMatcher(constraintType), stopContainerType::isInstance);
	}

	/**
	 * Finds all filter constraints of the specified type within the query's filter section.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the filter constraints to find
	 * @param <T> the type of the filter constraints to find
	 * @return list of all matching filter constraints (empty list if none found or query has no filter section)
	 */
	@Nonnull
	public static <T extends FilterConstraint> List<T> findFilters(@Nonnull Query query, @Nonnull Class<T> constraintType) {
		final FilterBy filterBy = query.getFilterBy();
		if (filterBy == null) {
			return Collections.emptyList();
		}
		//noinspection unchecked
		return FinderVisitor.findConstraints(filterBy, new ConstraintTypeMatcher(constraintType));
	}

	/**
	 * Finds the first order constraint of the specified type within the query's order section.
	 *
	 * This is a convenience method that searches only within the {@link Query#getOrderBy()} section.
	 * If the query has no order section, null is returned.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the order constraint to find
	 * @param <T> the type of the order constraint to find
	 * @return the first matching order constraint, or null if none found or query has no order section
	 */
	@Nullable
	public static <T extends OrderConstraint> T findOrder(@Nonnull Query query, @Nonnull Class<T> constraintType) {
		final OrderBy orderBy = query.getOrderBy();
		if (orderBy == null) {
			return null;
		}
		//noinspection unchecked
		return FinderVisitor.findConstraint(orderBy, new ConstraintTypeMatcher(constraintType));
	}

	/**
	 * Finds the first require constraint of the specified type within the query's require section.
	 *
	 * This is a convenience method that searches only within the {@link Query#getRequire()} section.
	 * If the query has no require section, null is returned.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the require constraint to find
	 * @param <T> the type of the require constraint to find
	 * @return the first matching require constraint, or null if none found or query has no require section
	 */
	@Nullable
	public static <T extends RequireConstraint> T findRequire(@Nonnull Query query, @Nonnull Class<T> constraintType) {
		final Require require = query.getRequire();
		if (require == null) {
			return null;
		}
		//noinspection unchecked
		return FinderVisitor.findConstraint(require, new ConstraintTypeMatcher(constraintType));
	}

	/**
	 * Finds the first require constraint of the specified type, stopping at specified container boundaries.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the require constraint to find
	 * @param stopContainerType require containers of this type will not be descended into
	 * @param <T> the type of the require constraint to find
	 * @return the first matching require constraint, or null if none found or query has no require section
	 */
	@Nullable
	public static <T extends RequireConstraint> T findRequire(@Nonnull Query query, @Nonnull Class<T> constraintType, @Nonnull Class<? extends RequireConstraint> stopContainerType) {
		final Require require = query.getRequire();
		if (require == null) {
			return null;
		}
		//noinspection unchecked
		return FinderVisitor.findConstraint(require, new ConstraintTypeMatcher(constraintType), stopContainerType::isInstance);
	}

	/**
	 * Finds all require constraints of the specified type within the query's require section.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the require constraints to find
	 * @param <T> the type of the require constraints to find
	 * @return list of all matching require constraints (empty list if none found or query has no require section)
	 */
	@Nonnull
	public static <T extends RequireConstraint> List<T> findRequires(@Nonnull Query query, @Nonnull Class<T> constraintType) {
		final Require require = query.getRequire();
		if (require == null) {
			return Collections.emptyList();
		}
		//noinspection unchecked
		return FinderVisitor.findConstraints(require, new ConstraintTypeMatcher(constraintType));
	}

	/**
	 * Finds all require constraints of the specified type, stopping at specified container boundaries.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the require constraints to find
	 * @param stopContainerType require containers of this type will not be descended into
	 * @param <T> the type of the require constraints to find
	 * @return list of all matching require constraints (empty list if none found or query has no require section)
	 */
	@Nonnull
	public static <T extends RequireConstraint> List<T> findRequires(@Nonnull Query query, @Nonnull Class<T> constraintType, @Nonnull Class<? extends RequireConstraint> stopContainerType) {
		final Require require = query.getRequire();
		if (require == null) {
			return Collections.emptyList();
		}
		//noinspection unchecked
		return FinderVisitor.findConstraints(require, new ConstraintTypeMatcher(constraintType), stopContainerType::isInstance);
	}

	/**
	 * Refuses a query using a scope in a way the engine cannot honour, before anything of the query is evaluated. The
	 * engine runs this check at every entry point of a client request - `QueryPlanner#planQuery` and the direct calls
	 * of `EntityCollection` - so that the verdict depends neither on the data nor on whether a reference row reaches
	 * the constraint, and so that a call which writes before it fetches leaves the entity untouched when it is refused.
	 *
	 * The filter section is judged by {@link #assertFilterScopesApplicable(FilterBy, Set)} against the passed scopes,
	 * and every filter of the require section - of a `referenceContent`, a hierarchy `node`, a facet summary - at any
	 * depth, against the scopes {@link #getRequireFilterRequestedScopes} derives from them. A scope a `referenceContent`
	 * names for its bodies is not handed to a nested `referenceContent`: this check keeps to what the query as a whole
	 * requests.
	 *
	 * The check runs on every execution, so it looks no `scope(...)` up unless a filter holds an `inScope`.
	 *
	 * @param query the query to check
	 * @param requestedScopes the scopes the query requests
	 * @throws EvitaInvalidUsageException when an `inScope` names an unrequested scope or a `scope(...)` has no effect
	 */
	public static void assertFilterScopesApplicable(@Nonnull Query query, @Nonnull Set<Scope> requestedScopes) {
		final FilterBy filterBy = query.getFilterBy();
		if (filterBy != null) {
			assertFilterScopesApplicable(filterBy, requestedScopes);
		}
		final Require require = query.getRequire();
		if (require != null) {
			assertRequireFilterScopesApplicable(require, requestedScopes);
		}
	}

	/**
	 * Refuses a filter using a scope in a way the engine cannot honour, whatever the data:
	 *
	 * - an `inScope` naming a scope that was not requested. {@link EntityHaving} and {@link GroupHaving}, which plan a
	 *   nested query, are judged by a `scope(...)` of their own level when they name one; every other level inherits.
	 * - a `scope(...)` the engine would not apply. It is read only in the root of a filter, boolean containers
	 *   included, and at the level of {@link EntityHaving} and {@link GroupHaving}. Below any other
	 *   {@link SeparateEntityScopeContainer} nothing reads it, so the query would silently mean something else than
	 *   it says.
	 *
	 * The walk allocates nothing unless the filter holds an `inScope`, or fails.
	 *
	 * @param filterBy the original filter, before scope enforcement
	 * @param requestedScopes the scopes that permit `inScope`, independently of the scopes processed in this pass
	 * @throws EvitaInvalidUsageException when an `inScope` names an unrequested scope or a `scope(...)` has no effect
	 */
	public static void assertFilterScopesApplicable(
		@Nonnull FilterBy filterBy, @Nonnull Set<Scope> requestedScopes
	) {
		assertFilterScopesApplicable(filterBy, requestedScopes, null);
	}

	/**
	 * Walks the require section looking for its filters - see {@link #assertFilterScopesApplicable(Query, Set)}. A filter
	 * is an additional child of the requirement holding it, and a nested requirement is a child of its container.
	 *
	 * @param constraint the require constraint to examine
	 * @param requestedScopes the scopes the query requests
	 */
	private static void assertRequireFilterScopesApplicable(
		@Nonnull Constraint<?> constraint, @Nonnull Set<Scope> requestedScopes
	) {
		if (constraint instanceof FilterBy || constraint instanceof FilterGroupBy) {
			final FilterConstraint filter = (FilterConstraint) constraint;
			assertFilterScopesApplicable(filter, getRequireFilterRequestedScopes(filter, requestedScopes), null);
		} else if (constraint instanceof ConstraintContainer<?> container) {
			for (final Constraint<?> child : container.getChildren()) {
				assertRequireFilterScopesApplicable(child, requestedScopes);
			}
			for (final Constraint<?> additionalChild : container.getAdditionalChildren()) {
				assertRequireFilterScopesApplicable(additionalChild, requestedScopes);
			}
		}
	}

	/**
	 * Returns the scopes an `inScope` of a filter of the require section may name: the inherited ones united with the
	 * `scope(...)` in the root of that filter, which widens the scopes a `referenceContent` looks its references up in.
	 * A scope below a separate container belongs to the entity level it stands in, and admits nothing beside that level.
	 * A facet summary reads the root scope more narrowly when it plans its filter, and refuses what that leaves out.
	 *
	 * The inherited set itself is returned when no `inScope` can tell the difference, which spares the lookup.
	 *
	 * @param filter the filter of the require section
	 * @param inheritedScopes the scopes the enclosing request admits
	 * @return the scopes an `inScope` of the filter may name
	 */
	@Nonnull
	public static Set<Scope> getRequireFilterRequestedScopes(
		@Nonnull FilterConstraint filter, @Nonnull Set<Scope> inheritedScopes
	) {
		if (!holdsFilterInScope(filter)) {
			return inheritedScopes;
		}
		final EntityScope rootScope = findConstraint(filter, EntityScope.class, SeparateEntityScopeContainer.class);
		return rootScope == null || inheritedScopes.containsAll(rootScope.getScope()) ?
			inheritedScopes : CollectionUtils.combine(inheritedScopes, rootScope.getScope());
	}

	/**
	 * Walks one constraint of a filter - see {@link #assertFilterScopesApplicable(FilterBy, Set)}.
	 *
	 * @param constraint the original constraint to examine
	 * @param requestedScopes the scopes an `inScope` at this entity level may name
	 * @param enclosingContainer the nearest separate container enclosing the constraint, NULL when there is none
	 */
	private static void assertFilterScopesApplicable(
		@Nonnull Constraint<?> constraint,
		@Nonnull Set<Scope> requestedScopes,
		@Nullable SeparateEntityScopeContainer enclosingContainer
	) {
		if (constraint instanceof EntityScope entityScope) {
			if (enclosingContainer != null && !appliesOwnScope(enclosingContainer)) {
				throw new EvitaInvalidUsageException(
					"Constraint `" + entityScope + "` has no effect in `" + enclosingContainer.getName() +
						"` - it selects scopes only in the root of a filter and in `entityHaving` or `groupHaving`, " +
						"so it must be placed there or removed."
				);
			}
			return;
		}
		if (constraint instanceof FilterInScope inScope && !requestedScopes.contains(inScope.getScope())) {
			throw new EvitaInvalidUsageException(
				"Scope `" + inScope.getScope() +
					"` used in `inScope` filter container was not requested by `scope` constraint!"
			);
		}
		if (constraint instanceof ConstraintContainer<?> container) {
			final SeparateEntityScopeContainer separate = constraint instanceof SeparateEntityScopeContainer it ?
				it : null;
			final Set<Scope> nestedRequestedScopes = separate == null ?
				requestedScopes : getEntityLevelRequestedScopes(separate, requestedScopes);
			final SeparateEntityScopeContainer nestedEnclosingContainer = separate == null ?
				enclosingContainer : separate;
			// no filter container holds additional children
			for (final Constraint<?> child : container.getChildren()) {
				assertFilterScopesApplicable(child, nestedRequestedScopes, nestedEnclosingContainer);
			}
		}
	}

	/**
	 * Returns the scopes an `inScope` below the separate container may name.
	 *
	 * @param container the separate container
	 * @param inheritedScopes the scopes the container inherits
	 * @return the scopes of the `scope(...)` of the container when it applies one, otherwise the inherited ones
	 */
	@Nonnull
	private static Set<Scope> getEntityLevelRequestedScopes(
		@Nonnull SeparateEntityScopeContainer container, @Nonnull Set<Scope> inheritedScopes
	) {
		// the lookup allocates and the walk runs on every execution, while only an `inScope` can tell the difference
		if (!appliesOwnScope(container) || !holdsFilterInScope(container)) {
			return inheritedScopes;
		}
		final EntityScope ownScope = findConstraint(container, EntityScope.class, SeparateEntityScopeContainer.class);
		return ownScope == null ? inheritedScopes : ownScope.getScope();
	}

	/**
	 * Returns true when the engine reads a `scope(...)` at the level of the container, which is the nested query of
	 * {@link EntityHaving} and {@link GroupHaving}. A hierarchy is traversed in the scope being queried, and the direct
	 * children of the reference and facet containers filter the owner's references.
	 *
	 * @param container the separate container
	 * @return true when a `scope(...)` at the level of the container has an effect
	 */
	private static boolean appliesOwnScope(@Nonnull SeparateEntityScopeContainer container) {
		return container instanceof EntityHaving || container instanceof GroupHaving;
	}

	/**
	 * Returns true when the constraint is a filter `inScope` or holds one at any depth.
	 *
	 * @param constraint the filter constraint to examine
	 * @return true when an `inScope` was found
	 */
	private static boolean holdsFilterInScope(@Nonnull Constraint<?> constraint) {
		if (constraint instanceof FilterInScope) {
			return true;
		}
		if (constraint instanceof ConstraintContainer<?> container) {
			for (final Constraint<?> child : container.getChildren()) {
				if (holdsFilterInScope(child)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Refuses a query in which an `inScope` container holds another `inScope` container of the same kind (filter,
	 * order or require) anywhere among its descendants within one evaluation context.
	 *
	 * `inScope(S, ...)` applies its children only when entities of scope `S` are processed. A nested container of
	 * a different scope would apply its children only when entities of both scopes were processed at once, which
	 * never happens, so the nesting is contradictory; a nested container of the same scope is redundant. Neither is
	 * a query anybody means, and the engine has no sound interpretation of the contradictory one.
	 *
	 * The rule is checked on the **final** query tree when the query is about to be executed, not when the containers
	 * are constructed: copies made by `getCopyWithNewChildren` and trees produced by normalization are checked as
	 * well, while a stored query (a traffic recording, for example) that contains the shape stays readable.
	 *
	 * The search below a container stops at the containers whose children are evaluated against another entity
	 * collection, which has scopes of its own - an `inScope` placed there restricts that other entity and is not
	 * nested in the outer one:
	 *
	 * - filter: every {@link io.evitadb.api.query.filter.SeparateEntityScopeContainer} except
	 *   {@link ReferenceHaving} and {@link FacetHaving}, whose bodies filter reference attributes kept in the
	 *   owner's own indexes
	 * - order: {@link io.evitadb.api.query.order.EntityProperty}, {@link io.evitadb.api.query.order.EntityGroupProperty}
	 *   and every {@link io.evitadb.api.query.order.ReferenceOrderingSpecification}
	 * - require: every {@link io.evitadb.api.query.require.SeparateEntityContentRequireContainer}
	 *
	 * Every `inScope` container of the query is checked, including those inside the stopping containers, so a nesting
	 * within the other entity's own context is refused as well. The check is one traversal of the query that
	 * allocates nothing unless it fails.
	 *
	 * @param query the query to check
	 * @throws EvitaInvalidUsageException when a nested container of the same kind is found
	 */
	public static void assertNoNestedScopeContainers(@Nonnull Query query) {
		// one allocation-free pass per section: the query is checked on every execution, and almost no query
		// contains an `inScope` at all
		final FilterBy filterBy = query.getFilterBy();
		if (filterBy != null) {
			assertNoNestedScopeContainers(filterBy, null);
		}
		final OrderBy orderBy = query.getOrderBy();
		if (orderBy != null) {
			assertNoNestedScopeContainers(orderBy, null);
		}
		final Require require = query.getRequire();
		if (require != null) {
			assertNoNestedScopeContainers(require, null);
		}
	}

	/**
	 * Walks `constraint` and its descendants once, remembering the `inScope` container enclosing the visited constraint
	 * within the current evaluation context - see {@link #assertNoNestedScopeContainers(Query)}.
	 *
	 * Only a container of the same kind ({@link Constraint#getType()}) is refused. One enclosing container is enough to
	 * track all three kinds, because the kinds nest in one direction only - require constraints hold filter and order
	 * constraints (`referenceContent`), order constraints hold filter constraints (`segment`), never the other way
	 * round. Once the walk descends to another kind, a container of the kind it left cannot appear below, so an
	 * `inScope` of the new kind may replace it.
	 *
	 * A container whose children are evaluated against another entity collection starts a new evaluation context for
	 * its own kind: the enclosing container of that kind is forgotten below it, so an `inScope` there is accepted -
	 * yet still checked against any `inScope` nested in it within that other context.
	 *
	 * @param constraint the constraint to visit
	 * @param enclosing  the `inScope` container enclosing the constraint in the current context, or NULL
	 * @throws EvitaInvalidUsageException when an `inScope` is enclosed by another one of the same kind
	 */
	private static void assertNoNestedScopeContainers(
		@Nonnull Constraint<?> constraint,
		@Nullable InScopeContainer<?> enclosing
	) {
		InScopeContainer<?> context = enclosing;
		if (constraint instanceof final InScopeContainer<?> inScope) {
			if (enclosing != null && enclosing.getType() == inScope.getType()) {
				throw createNestedScopeContainerException(enclosing.getScope(), inScope, inScope.getScope());
			}
			context = inScope;
		} else if (
			enclosing != null && enclosing.getType() == constraint.getType() &&
				startsSeparateEntityContext(constraint)
		) {
			context = null;
		}
		if (constraint instanceof final ConstraintContainer<?> container) {
			for (final Constraint<?> child : container.getChildren()) {
				assertNoNestedScopeContainers(child, context);
			}
			for (final Constraint<?> additionalChild : container.getAdditionalChildren()) {
				assertNoNestedScopeContainers(additionalChild, context);
			}
		}
	}

	/**
	 * Returns true when the children of `constraint` are evaluated against another entity collection, which has scopes
	 * of its own - see {@link #assertNoNestedScopeContainers(Query)} for the list.
	 *
	 * @param constraint the constraint to examine
	 * @return true when the constraint starts a new evaluation context for its own kind
	 */
	private static boolean startsSeparateEntityContext(@Nonnull Constraint<?> constraint) {
		// The direct children of reference and facet filters both filter the owner's references.
		return (constraint instanceof SeparateEntityScopeContainer &&
			!(constraint instanceof ReferenceHaving) && !(constraint instanceof FacetHaving)) ||
			// the children order by the properties of another entity
			constraint instanceof EntityProperty ||
			constraint instanceof EntityGroupProperty ||
			constraint instanceof ReferenceOrderingSpecification ||
			// the children describe the content of another entity
			constraint instanceof SeparateEntityContentRequireContainer;
	}

	/**
	 * Creates the exception refusing `nestedContainer` nested in an `inScope` container of `enclosingScope`.
	 *
	 * @param enclosingScope  the scope of the enclosing container
	 * @param nestedContainer the nested container
	 * @param nestedScope     the scope of the nested container
	 * @return the exception to throw
	 */
	@Nonnull
	private static EvitaInvalidUsageException createNestedScopeContainerException(
		@Nonnull Scope enclosingScope,
		@Nonnull Constraint<?> nestedContainer,
		@Nonnull Scope nestedScope
	) {
		return new EvitaInvalidUsageException(
			nestedScope == enclosingScope ?
				"Constraint `" + nestedContainer + "` is nested in another `inScope(" + enclosingScope + ", ...)` " +
					"container, which is redundant - place its children directly in the outer container." :
				"Constraint `" + nestedContainer + "` is nested in an `inScope(" + enclosingScope + ", ...)` " +
					"container, which is contradictory - its children would apply only when entities of " +
					"scopes `" + enclosingScope + "` and `" + nestedScope + "` were processed at once, which never " +
					"happens. Place the two `inScope` containers side by side instead."
		);
	}

	/**
	 * Compares two serializable values for inequality, using `compareTo` for comparable types.
	 *
	 * This method is used for constraint argument comparison. It handles special cases:
	 * - **Comparable types**: Uses `compareTo()` instead of `equals()` to correctly compare types like
	 *   {@link java.math.BigDecimal} where `equals()` considers scale (e.g., `1.0 != 1.00` via equals,
	 *   but `1.0 == 1.00` via compareTo).
	 * - **Arrays**: Recursively compares array elements
	 * - **Null values**: Null is considered different from any non-null value
	 *
	 * @param thisValue the first value to compare; may be null
	 * @param otherValue the second value to compare; may be null
	 * @return true if the values differ, false if they are equal or both are null
	 */
	public static boolean valueDiffers(@Nullable Serializable thisValue, @Nullable Serializable otherValue) {
		// handle array comparison recursively
		if (thisValue instanceof final Object[] thisValueArray) {
			if (!(otherValue instanceof final Object[] otherValueArray)) {
				return true;
			}
			if (thisValueArray.length != otherValueArray.length) {
				return true;
			}
			// compare each element
			for (int i = 0; i < thisValueArray.length; i++) {
				if (valueDiffersInternal((Serializable) thisValueArray[i], (Serializable) otherValueArray[i])) {
					return true;
				}
			}
			return false;
		} else {
			return valueDiffersInternal(thisValue, otherValue);
		}
	}

	/**
	 * Internal comparison logic for non-array values.
	 *
	 * Uses `compareTo()` for Comparable types (like BigDecimal) to ensure numeric equality is based on value,
	 * not representation. For non-comparable types, uses standard `equals()`.
	 *
	 * @param thisValue the first value to compare; may be null
	 * @param otherValue the second value to compare; may be null
	 * @return true if the values differ, false if they are equal or both are null
	 */
	private static boolean valueDiffersInternal(@Nullable Serializable thisValue, @Nullable Serializable otherValue) {
		// for comparable types (e.g., BigDecimal), use compareTo to handle value-based equality correctly
		if (thisValue instanceof Comparable) {
			if (otherValue == null) {
				return true;
			} else {
				//noinspection unchecked,rawtypes
				return !thisValue.getClass().isInstance(otherValue) || ((Comparable) thisValue).compareTo(otherValue) != 0;
			}
		} else {
			return !Objects.equals(thisValue, otherValue);
		}
	}

	/**
	 * Predicate implementation that matches constraints by their runtime type using {@code isInstance} check.
	 *
	 * This internal helper class is used by all type-based finder methods to create predicates that match
	 * constraints of a specific class. It implements {@link PredicateWithDescription} to provide readable
	 * error messages when constraints are not found.
	 *
	 * The `toString()` method is used by {@link io.evitadb.api.query.visitor.FinderVisitor} to generate
	 * descriptive exception messages.
	 */
	@RequiredArgsConstructor
	private static class ConstraintTypeMatcher implements PredicateWithDescription<Constraint<?>> {
		/** The constraint class to match against. */
		private final Class<? extends Constraint<?>> type;

		/**
		 * Tests whether the constraint is an instance of the target type.
		 *
		 * @param constraint the constraint to test
		 * @return true if the constraint is an instance of the target type
		 */
		@Override
		public boolean test(Constraint<?> constraint) {
			return this.type.isInstance(constraint);
		}

		/**
		 * Returns a human-readable description of this matcher for error messages.
		 *
		 * @return description string like "constraint of type `AttributeEquals`"
		 */
		@Nonnull
		@Override
		public String toString() {
			return "constraint of type `" + this.type.getSimpleName() + "`";
		}

	}

}
