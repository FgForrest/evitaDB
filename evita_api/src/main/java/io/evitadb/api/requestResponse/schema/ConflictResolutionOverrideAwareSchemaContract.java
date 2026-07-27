/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;

import javax.annotation.Nonnull;

/**
 * Shared read-only contract for every sub-entity schema item that may carry a per-item override of the transaction
 * conflict resolution granularity — currently entity attributes and reference attributes
 * ({@link AttributeSchemaContract}), associated data ({@link AssociatedDataSchemaContract}) and references
 * ({@link ReferenceSchemaContract}).
 *
 * Extracting the getter into a single mixin keeps the override declaration in one place (mirroring
 * {@link NamedSchemaWithDeprecationContract}) so the three item schemas do not each repeat it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ConflictResolutionOverrideAwareSchemaContract {

	/**
	 * Per-item override of the conflict resolution granularity applied to this schema item. It refines - for this
	 * single item only - the conflict resolution resolved from the enclosing entity schema, catalog schema and engine
	 * configuration. The value is never `null`; the default is {@link ConflictResolutionOverride#INHERITED}.
	 *
	 * The three possible values carry the following meaning:
	 *
	 * - `INHERITED` (default): the item carries no opinion of its own and follows the conflict resolution resolved from
	 *   the entity, catalog or engine level.
	 * - `GRANULAR`: the item is given its OWN sub-entity conflict key, isolating concurrent writes that touch only this
	 *   item from writes touching other parts of the same entity. The concrete granularity depends on the item's kind
	 *   and location - an entity attribute isolates on the attribute dimension of the entity, an attribute nested in a
	 *   reference isolates on the reference-attribute dimension, a reference isolates on the reference dimension and
	 *   associated data isolates on the associated-data dimension.
	 * - `ENTITY`: the item explicitly serializes on the whole entity - writes touching it conflict with any concurrent
	 *   write to the same entity, regardless of any granular refinement resolved for its siblings.
	 *
	 * @return the per-item conflict resolution override, never `null`
	 */
	@Nonnull
	ConflictResolutionOverride getConflictResolutionOverride();

}
