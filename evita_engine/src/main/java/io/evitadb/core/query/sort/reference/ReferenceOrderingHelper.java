/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.sort.reference;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.ReferenceOrderingSpecification;
import io.evitadb.api.query.order.ReferenceProperty;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.QueryPlanningContext;

import javax.annotation.Nonnull;

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyNatural;
import static io.evitadb.api.query.QueryConstraints.pickFirstByEntityProperty;
import static io.evitadb.api.query.QueryConstraints.traverseByEntityProperty;

/**
 * Resolves reference ordering defaults consistently for filtering and ordering planners.
 */
public final class ReferenceOrderingHelper {
	/**
	 * Prevents instantiation of this static helper.
	 */
	private ReferenceOrderingHelper() {
	}

	/**
	 * Returns the explicit specification, or traversal for a managed hierarchical target and pick-first otherwise.
	 *
	 * @param referenceProperty the ordering being planned
	 * @param referenceSchema   the ordered reference
	 * @param queryContext      the context supplying the target schema
	 * @return the effective ordering specification
	 */
	@Nonnull
	public static ReferenceOrderingSpecification resolveOrderingSpecification(
		@Nonnull ReferenceProperty referenceProperty,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull QueryPlanningContext queryContext
	) {
		return referenceProperty.getReferenceOrderingSpecification().orElseGet(
			() -> isReferencedEntityHierarchical(referenceSchema, queryContext) ?
				traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)) :
				pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC))
		);
	}

	/**
	 * Tells whether the reference targets a managed entity type with a hierarchy.
	 *
	 * @param referenceSchema the reference whose target is checked
	 * @param queryContext    the context supplying the target schema
	 * @return `true` when target ordering can traverse a managed hierarchy
	 */
	public static boolean isReferencedEntityHierarchical(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull QueryPlanningContext queryContext
	) {
		return referenceSchema.isReferencedEntityTypeManaged() &&
			queryContext.getSchema(referenceSchema.getReferencedEntityType()).isWithHierarchy();
	}
}
