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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.GroupHaving;

/**
 * Enum specifying which components of a reference should be indexed for querying purposes.
 * A reference indexes its referenced entity, and optionally its referenced group entity as well.
 *
 * This allows control over what gets indexed per scope. For example, a reference might only need
 * to index the referenced entity for {@link EntityHaving} filtering but not the group entity for
 * {@link GroupHaving} filtering. The opposite is not possible: every scope in which a reference is
 * indexed must contain {@link #REFERENCED_ENTITY}, because the reduced entity indexes it builds are
 * what every query over the reference reads - `referenceHaving` (including one nesting only
 * `groupHaving`), `hierarchyWithin`, `hierarchyOfReference`, `referenceContent` with a filter and
 * `referenceProperty` ordering. A scope indexed for {@link #REFERENCED_GROUP_ENTITY} alone, or with
 * no component at all, is refused when the session defining it closes; a catalog stored with such a
 * scope before the rule existed still loads, but queries over the reference in that scope are refused.
 *
 * **Default behavior:** when no explicit indexed components are configured,
 * the reference defaults to indexing `{REFERENCED_ENTITY}` only — preserving the current implicit behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum ReferenceIndexedComponents {

	/**
	 * The reference has index for the referenced entity allowing it to be queried by {@link EntityHaving} constraint.
	 * This means that the reference can be used in query filtering and sorting by the existence of the reference and
	 * by the attributes of the referenced entity.
	 */
	REFERENCED_ENTITY,
	/**
	 * The reference has index for the referenced group entity allowing it to be queried by {@link GroupHaving} constraint.
	 * This means that the reference can be used in query filtering by the attributes of the referenced group entity.
	 * It is an addition to {@link #REFERENCED_ENTITY}, never a replacement - it must be declared together with it in
	 * every scope.
	 */
	REFERENCED_GROUP_ENTITY;

	/**
	 * Default indexed components for references when no explicit configuration is provided.
	 * By default, only the referenced entity is indexed to maintain backward compatibility with existing schemas.
	 */
	public static final ReferenceIndexedComponents[] DEFAULT_INDEXED_COMPONENTS = new ReferenceIndexedComponents[] {
		ReferenceIndexedComponents.REFERENCED_ENTITY
	};
	/**
	 * Reusable empty array constant to avoid repeated zero-length array allocations.
	 */
	public static final ReferenceIndexedComponents[] EMPTY = new ReferenceIndexedComponents[0];
}
