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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Filters entities by exact primary key match against one or more specified keys. This constraint enables rapid entity
 * retrieval using the most efficient lookup mechanism in evitaDB — primary key identity matching via specialized
 * bitmap indexes. It is functionally equivalent to a logical OR of multiple equality checks on the primary key field.
 *
 * **Purpose**
 *
 * Use this constraint when you need to retrieve a specific set of entities by their primary keys. This is the fastest
 * way to fetch entities in evitaDB because primary keys are indexed using highly optimized RoaringBitmap structures
 * that support O(1) lookup time per key. Common use cases include:
 * - Fetching entities after receiving primary keys from an external search service
 * - Retrieving specific items selected by the user (e.g., shopping cart contents, favorites)
 * - Loading entities matching IDs from another system
 * - Implementing pagination where you have a known set of primary keys to display
 *
 * **Constraint Classification**
 *
 * This constraint implements {@link EntityConstraint} because it operates on the built-in `primaryKey` property that
 * exists on every entity regardless of schema configuration. It is a {@link FilterConstraint} that narrows the result
 * set to entities whose primary key appears in the provided argument list.
 *
 * **Default Result Ordering**
 *
 * When no explicit ordering constraint is specified, entities matching this filter are returned in ascending order by
 * primary key (1, 2, 3, 5, 8...). If you need to preserve the exact order of primary keys as specified in the filter
 * arguments, use the `entityPrimaryKeyInFilter` ordering constraint in the `orderBy` section of your query.
 *
 * **Supported Constraint Domains**
 *
 * This constraint can be used in:
 * - **ENTITY**: Filter queried entities by primary key
 * - **REFERENCE**: Filter entities by the primary keys of their referenced entities (e.g., find products referencing
 *   brand entities with primary keys 1, 2, 3)
 * - **INLINE_REFERENCE**: Filter inline references by their referenced entity primary keys
 * - **FACET**: Filter by faceted reference primary keys
 *
 * **EvitaQL Syntax**
 *
 * ```
 * entityPrimaryKeyInSet(argument:int+)
 * ```
 *
 * Because this constraint implements the `EntityConstraint` marker interface and uses the `@Creator` annotation with
 * `implicitClassifier = "primaryKey"`, the constraint name prefix can be omitted in EvitaQL, allowing the shorthand
 * syntax `primaryKey(...)` as an alias for `entityPrimaryKeyInSet(...)`.
 *
 * **Usage Examples**
 *
 * ```java
 * // Retrieve specific products by primary key
 * query(
 *     collection("Product"),
 *     filterBy(
 *         entityPrimaryKeyInSet(1, 5, 8, 13, 21)
 *     )
 * )
 * ```
 *
 * ```java
 * // Preserve exact primary key order in results
 * query(
 *     collection("Product"),
 *     filterBy(
 *         entityPrimaryKeyInSet(5, 1, 8, 3)
 *     ),
 *     orderBy(
 *         entityPrimaryKeyInFilter()  // results will be ordered: 5, 1, 8, 3
 *     )
 * )
 * ```
 *
 * ```java
 * // Filter products by referenced brand entity primary keys
 * query(
 *     collection("Product"),
 *     filterBy(
 *         referenceHaving(
 *             "brand",
 *             entityPrimaryKeyInSet(10, 20, 30)  // only products referencing these brand entities
 *         )
 *     )
 * )
 * ```
 *
 * ```java
 * // Combine with other filters
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             entityPrimaryKeyInSet(1, 2, 3, 5, 8, 13, 21),
 *             attributeEquals("visible", true)
 *         )
 *     )
 * )
 * ```
 *
 * **Null Handling**
 *
 * Null values in the primary key array are automatically filtered out during constraint construction. This behavior is
 * implemented by the base class argument handling logic. An empty array or an array containing only nulls will produce
 * an applicable constraint that matches no entities.
 *
 * **Applicability**
 *
 * This constraint is always applicable, even with an empty primary key array. An empty array is a valid filter that
 * matches zero entities, which is semantically meaningful in query composition scenarios.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/constant#entity-primary-key-in-set)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inSet",
	shortDescription = "The constraint checks if primary key of the entity equals to at least one of the passed values. " +
		"The constraint is equivalent to one or more `equals` constraints combined with logical OR.",
	userDocsLink = "/documentation/query/filtering/constant#entity-primary-key-in-set",
	supportedIn = {
		ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE,
		ConstraintDomain.INLINE_REFERENCE, ConstraintDomain.FACET
	}
)
public class EntityPrimaryKeyInSet extends AbstractFilterConstraintLeaf
	implements EntityConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -6950287451642746676L;

	private EntityPrimaryKeyInSet(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator(implicitClassifier = "primaryKey")
	public EntityPrimaryKeyInSet(@Nonnull Integer... primaryKeys) {
		super(primaryKeys);
	}

	/**
	 * Returns primary keys of entities to lookup for.
	 */
	@Nonnull
	public int[] getPrimaryKeys() {
		final Serializable[] arguments = getArguments();
		final int[] result = new int[arguments.length];
		for (int i = 0; i < arguments.length; i++) {
			result[i] = (Integer) arguments[i];
		}
		return result;
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityPrimaryKeyInSet(newArguments);
	}
}
