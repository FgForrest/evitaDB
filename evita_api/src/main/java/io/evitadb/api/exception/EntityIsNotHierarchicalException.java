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

package io.evitadb.api.exception;

import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.HierarchyWithinRoot;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * Exception thrown when hierarchy-related operations or query constraints target an entity collection that does not
 * support hierarchical structure.
 *
 * Entity collections must explicitly enable hierarchy support via {@link EntitySchemaContract#isWithHierarchy()} in
 * their schema definition. This exception is thrown when:
 *
 * - **Query constraints** use hierarchy filtering (e.g., {@link HierarchyWithin}, {@link HierarchyWithinRoot})
 * - **Extra result computation** requests hierarchy statistics for non-hierarchical entities
 * - **Entity operations** attempt to access parent entity relationships on non-hierarchical entities
 * - **Reference filtering** uses hierarchy constraints on references to non-hierarchical entity types
 * - **Facet hierarchy filtering** attempts to filter by facet hierarchy when faceted entity is not hierarchical
 *
 * **Common scenarios:**
 *
 * - Direct hierarchy queries: `hierarchyWithin('Product', 100)` on non-hierarchical Product entities
 * - Referenced entity hierarchies: `hierarchyWithin('Brand', 'brand', 50)` when Brand is not hierarchical
 * - Hierarchy statistics requests: `hierarchyOfSelf()` or `hierarchyOfReference()` on non-hierarchical entities
 * - Parent entity access: calling `getParent()` on entities without hierarchy support
 *
 * **Resolution**: Enable hierarchy support in the entity schema by setting `withHierarchy()` during schema
 * definition, or remove hierarchy-related constraints from queries targeting non-hierarchical entity collections.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityIsNotHierarchicalException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -2138081433947529964L;
	/**
	 * The reference name through which the hierarchical entity was accessed, or null if accessing directly.
	 */
	@Getter private final String referenceName;
	/**
	 * The entity type that does not support hierarchical structure.
	 */
	@Getter private final String entityType;

	/**
	 * Creates exception for direct hierarchy access on non-hierarchical entity.
	 *
	 * @param entityType the name of the entity collection that does not support hierarchy
	 */
	public EntityIsNotHierarchicalException(@Nonnull String entityType) {
		super(
			"Entity `" + entityType + "` is not hierarchical!"
		);
		this.referenceName = null;
		this.entityType = entityType;
	}

	/**
	 * Creates exception for hierarchy access through a reference or direct query with context.
	 *
	 * @param referenceName the name of the reference through which hierarchy was accessed, or null for direct access
	 * @param entityType the name of the entity collection that does not support hierarchy
	 */
	public EntityIsNotHierarchicalException(@Nullable String referenceName, @Nonnull String entityType) {
		super(
			referenceName == null ?
				"Entity `" + entityType + "` targeted by query within hierarchy is not hierarchical!" :
				"Entity `" + entityType + "` targeted by query within hierarchy through reference `" + referenceName + "` is not hierarchical!"
		);
		this.referenceName = referenceName;
		this.entityType = entityType;
	}
}
