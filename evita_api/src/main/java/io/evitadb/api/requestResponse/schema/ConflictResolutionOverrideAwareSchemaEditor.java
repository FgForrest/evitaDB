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
 * Shared editor mixin for every sub-entity schema item that may carry a per-item override of the transaction conflict
 * resolution granularity (see {@link ConflictResolutionOverrideAwareSchemaContract} for the read-only counterpart and
 * the meaning of the individual values).
 *
 * The self-type parameter `T` follows the fluent-builder convention already used by
 * {@link NamedSchemaWithDeprecationEditor} so each concrete editor keeps returning its own builder type from the
 * inherited method.
 *
 * @param <T> the concrete editor/builder type returned for fluent chaining
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ConflictResolutionOverrideAwareSchemaEditor<T extends ConflictResolutionOverrideAwareSchemaEditor<T>> {

	/**
	 * Sets the per-item override of the conflict resolution granularity for this schema item. The override refines -
	 * for this single item only - the conflict resolution resolved from the enclosing entity schema, catalog schema and
	 * engine configuration. See {@link ConflictResolutionOverrideAwareSchemaContract#getConflictResolutionOverride()}
	 * for the meaning of the individual values.
	 *
	 * @param conflictResolutionOverride the override to apply to this item (never `null`; use
	 *                                   {@link ConflictResolutionOverride#INHERITED} to follow the resolved conflict
	 *                                   resolution)
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T withConflictResolutionOverride(@Nonnull ConflictResolutionOverride conflictResolutionOverride);

}
