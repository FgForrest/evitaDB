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

package io.evitadb.api.requestResponse.schema.mutation.reference;


import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
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
 * Base class for schema mutations that target a single reference schema of an entity collection.
 *
 * Responsibilities
 * - expose the reference `name` and implement `containerName()` from `NamedSchemaMutation` so the
 *   mutation is routed to the correct reference container
 * - provide a default `collectConflictKeys(...)` that scopes conflicts to the owning entity type via
 *   `CollectionConflictKey` (all reference-schema mutations of the same entity share the same domain)
 *
 * Concurrency and lifecycle
 * - instances are immutable and thread-safe
 * - intended to be extended by concrete reference schema mutations (e.g. rename reference, change
 *   cardinality, configure attributes)
 *
 * Context
 * Reference-schema mutations are evaluated within the entity type taken from the
 * `ConflictGenerationContext`; conflicts are detected at the collection level rather than for
 * individual references.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
@Immutable
@RequiredArgsConstructor
@EqualsAndHashCode
abstract class AbstractReferenceDataSchemaMutation implements NamedSchemaMutation {
	@Serial private static final long serialVersionUID = -6143626432682048663L;
	@Getter @Nonnull protected final String name;

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
