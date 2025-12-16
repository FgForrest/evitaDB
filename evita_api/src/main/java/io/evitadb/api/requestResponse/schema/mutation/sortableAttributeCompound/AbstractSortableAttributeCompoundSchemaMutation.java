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

package io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound;


import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.schema.mutation.NamedSchemaMutation;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Base class for schema mutations that operate on a single sortable attribute compound
 * in an entity schema.
 *
 * A sortable attribute compound is a named composition of multiple attributes that is used to
 * predefine efficient sort orders. Concrete mutations (such as create, remove, or rename) extend
 * this class and share the common behavior defined here:
 *
 * - Keeps the target compound's {@link #name}
 * - Exposes the container name via {@link #containerName()} so the mutation framework can route
 *   the change to the correct schema part
 * - Produces collection-scoped conflict keys in {@link #collectConflictKeys(ConflictGenerationContext, Set)}
 *   so that mutations affecting the same entity type are detected and handled by the conflict engine
 *
 * Concurrency and safety:
 *
 * - The class is immutable and thread-safe
 * - Equality and hash code are derived from its state
 *
 * See also:
 *
 * - {@link io.evitadb.api.requestResponse.schema.mutation.NamedSchemaMutation}
 * - {@link io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey}
 * - {@link io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext}
 * - {@link io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
abstract class AbstractSortableAttributeCompoundSchemaMutation implements NamedSchemaMutation {
	@Serial private static final long serialVersionUID = 7905499994228359989L;
	@Getter @Nonnull protected final String name;

	AbstractSortableAttributeCompoundSchemaMutation(@Nonnull String name) {
		this.name = name;
	}

	@Nonnull
	@Override
	public String containerName() {
		return this.name;
	}

	@Nonnull
	@Override
	public Stream<ConflictKey> collectConflictKeys(
		@Nonnull ConflictGenerationContext context,
		@Nonnull Set<ConflictPolicy> conflictPolicies
	) {
		return Stream.of(new CollectionConflictKey(context.getEntityType()));
	}

}
