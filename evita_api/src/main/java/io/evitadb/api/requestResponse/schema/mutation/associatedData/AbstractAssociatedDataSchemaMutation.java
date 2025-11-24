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

package io.evitadb.api.requestResponse.schema.mutation.associatedData;


import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.schema.mutation.AssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.NamedSchemaMutation;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Base class for mutations that change the schema of associated data within a single entity schema.
 *
 * It carries the `name` of the associated data that a concrete mutation targets and provides common
 * behavior shared by all associated data schema mutations:
 *
 * - implements `NamedSchemaMutation` so the mutation can be addressed by its container name;
 * - exposes {@link #containerName()} that resolves to the associated data name;
 * - defines a default conflict key at the entity-collection level for optimistic conflict detection.
 *
 * ### Concurrency and conflict resolution
 * The default implementation of {@link #collectConflictKeys(ConflictGenerationContext, java.util.Set)}
 * groups conflicts by the entity collection (identified by `entityType` from the context) using a single
 * `CollectionConflictKey`. This ensures two associated data schema mutations for the same entity type are
 * considered conflicting and must be serialized by the engine.
 *
 * ### Thread-safety and immutability
 * Instances are immutable and thread-safe; all state is provided via constructor arguments.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
@Immutable
@RequiredArgsConstructor
@EqualsAndHashCode
abstract class AbstractAssociatedDataSchemaMutation
	implements LocalEntitySchemaMutation, AssociatedDataSchemaMutation, NamedSchemaMutation {
	@Serial private static final long serialVersionUID = 5905310313777673325L;

	/**
	 * Name of the associated data schema affected by this mutation. Never `null`.
	 */
	@Getter @Nonnull protected final String name;

	@Nonnull
	public String containerName() {
		// Container name for associated data mutations equals the associated data schema name
		return this.name;
	}

	@Nonnull
	public Stream<ConflictKey> collectConflictKeys(
		@Nonnull ConflictGenerationContext context,
		@Nonnull Set<ConflictPolicy> conflictPolicies
	) {
		/*
		 * Default: mark entire entity collection (by entity type) as the conflict scope so that
		 * concurrent associated data schema mutations for the same entity type are serialized.
		 */
		return Stream.of(new CollectionConflictKey(context.getEntityType()));
	}
}
