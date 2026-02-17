/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The `scope` constraint specifies which data scopes the query should search within. evitaDB organizes entities into separate scopes with distinct
 * indexing characteristics and access patterns. This constraint controls which scope(s) are included in the query execution, enabling searches
 * across active data, archived data, or both simultaneously.
 *
 * ## Available Scopes
 *
 * evitaDB currently supports two scopes defined by {@link Scope}:
 *
 * - **{@link Scope#LIVE}**: Entities that are currently active and reside in fully-indexed data sets. All attributes, facets, hierarchies, and
 *   prices are indexed and available for filtering, sorting, and aggregation. This is the default scope if none is specified.
 * - **{@link Scope#ARCHIVED}**: Entities that have been soft-deleted or archived. They reside in separate indexes with limited accessibility—fewer
 *   attributes are indexed, and facets and hierarchies are typically unavailable. Archived entities can still be queried when necessary and can
 *   be restored back to the LIVE scope.
 *
 * ## Default Behavior
 *
 * If no `scope` constraint is present in a query, evitaDB defaults to searching only the **LIVE scope** (`scope(LIVE)`). The ARCHIVED scope is
 * never searched unless explicitly requested.
 *
 * ## Multi-Scope Queries
 *
 * When multiple scopes are specified (e.g., `scope(LIVE, ARCHIVED)`), evitaDB searches both scopes and merges the results. However, there are
 * important behavioral considerations:
 *
 * **Scope Priority for Duplicates**: If the same entity exists in multiple scopes (which should be rare but is technically possible during
 * transitions), evitaDB prioritizes the entity from the **first declared scope** in the argument list. For example, `scope(LIVE, ARCHIVED)` will
 * return the LIVE version of an entity if it exists in both scopes.
 *
 * **Unique Constraint Enforcement**: Unique attribute constraints are enforced **within each scope independently**, not globally. This means two
 * entities in different scopes can have the same value for a unique attribute without violating uniqueness. For example, an ARCHIVED entity and
 * a LIVE entity can both have `code='ABC-123'` if `code` is defined as unique.
 *
 * **Filtering Constraints**: When querying multiple scopes, filtering constraints apply to all scopes unless wrapped in {@link FilterInScope},
 * which restricts constraints to a specific scope. This is critical because attributes indexed in LIVE may not be indexed in ARCHIVED, and
 * attempting to filter by non-indexed attributes will cause query failures.
 *
 * ## EvitaQL Syntax
 *
 * ```
 * scope(LIVE|ARCHIVED+)
 * ```
 *
 * The constraint accepts one or more {@link Scope} enum values as varargs.
 *
 * ## Usage Examples
 *
 * **Example 1: Searching only archived entities**
 *
 * ```
 * query(
 *     collection('Product'),
 *     filterBy(
 *         scope(ARCHIVED)
 *     )
 * )
 * ```
 *
 * This query searches only the ARCHIVED scope, returning soft-deleted or historical products.
 *
 * **Example 2: Searching both LIVE and ARCHIVED entities**
 *
 * ```
 * query(
 *     collection('Product'),
 *     filterBy(
 *         scope(LIVE, ARCHIVED)
 *     )
 * )
 * ```
 *
 * This query searches both scopes and returns all products regardless of their archival status. LIVE entities are prioritized if duplicates exist.
 *
 * **Example 3: Combining scope with scope-specific filters**
 *
 * ```
 * query(
 *     collection('Product'),
 *     filterBy(
 *         and(
 *             attributeEquals('category', 'electronics'), // applies to both scopes
 *             inScope(LIVE, attributeEquals('inStock', true)), // only filter LIVE entities by stock
 *             scope(LIVE, ARCHIVED)
 *         )
 *     )
 * )
 * ```
 *
 * This query searches both scopes for electronics products, but only filters LIVE entities by stock availability (assuming `inStock` is not
 * indexed in ARCHIVED scope).
 *
 * **Example 4: Default scope behavior (implicit LIVE)**
 *
 * ```
 * query(
 *     collection('Product'),
 *     filterBy(
 *         attributeEquals('available', true)
 *     )
 *     // No scope constraint: defaults to scope(LIVE)
 * )
 * ```
 *
 * This query implicitly searches only the LIVE scope because no `scope` constraint is specified.
 *
 * ## Relationship to Uniqueness and Constraints
 *
 * **Unique Attributes**: When an attribute is marked as unique in the schema, evitaDB enforces uniqueness **per scope**. This allows the same
 * unique value to exist in LIVE and ARCHIVED scopes simultaneously without conflict. For example:
 *
 * ```
 * // These two entities can coexist without violating unique constraint on 'code':
 * Entity in LIVE: { primaryKey: 1, code: 'PROD-001' }
 * Entity in ARCHIVED: { primaryKey: 2, code: 'PROD-001' }
 * ```
 *
 * **Scope Isolation**: Each scope maintains independent indexes, so queries that operate on a single scope never "see" entities from other scopes
 * unless explicitly requested via multi-scope queries.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/behavioral#scope)
 *
 * @see Scope
 * @see FilterInScope
 * @see SeparateEntityScopeContainer
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "scope",
	shortDescription = "The constraint controls the scope of the entity search, accepting one or more scopes (LIVE, ARCHIVED)" +
		" where entities should be searched.",
	userDocsLink = "/documentation/query/filtering/behavioral#scope",
	supportedIn = ConstraintDomain.ENTITY
)
public class EntityScope extends AbstractFilterConstraintLeaf implements GenericConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -7172389493449298316L;
	public static final String CONSTRAINT_NAME = "scope";
	private final Set<Scope> theScope;

	private EntityScope(@Nonnull Serializable[] arguments) {
		super(CONSTRAINT_NAME, arguments);
		this.theScope = getScope();
	}

	@Creator
	public EntityScope(@Nonnull Scope... scope) {
		super(CONSTRAINT_NAME, scope);
		this.theScope = getScope();
	}

	/**
	 * Returns requested scopes.
	 */
	@Nonnull
	public Set<Scope> getScope() {
		if (this.theScope == null) {
			final EnumSet<Scope> result = EnumSet.noneOf(Scope.class);
			for (final Serializable argument : getArguments()) {
				if (argument instanceof Scope scope) {
					result.add(scope);
				}
			}
			return Collections.unmodifiableSet(result);
		} else {
			return this.theScope;
		}
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length >= 1;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityScope(newArguments);
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		final EntityScope that = (EntityScope) o;
		return getScope().equals(that.getScope());
	}

	@Override
	public int hashCode() {
		return getScope().hashCode();
	}
}