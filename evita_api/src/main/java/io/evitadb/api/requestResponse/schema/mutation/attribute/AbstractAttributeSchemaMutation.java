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

package io.evitadb.api.requestResponse.schema.mutation.attribute;


import io.evitadb.api.requestResponse.mutation.conflict.CatalogConflictKey;
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
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Base class for attribute schema mutations that operate on a single attribute identified by its
 * {@code name}.
 *
 * This class centralizes two cross-cutting concerns common to attribute schema mutations:
 *
 * - container addressing: {@link #containerName()} returns the attribute name so mutation processing
 *   can route the change to the correct schema container
 * - conflict scoping: {@link #collectConflictKeys(ConflictGenerationContext, Set)} yields a single
 *   conflict key that scopes the mutation either to the current entity collection (when an entity
 *   type is present in the {@link ConflictGenerationContext}) or to the catalog as a whole (when no
 *   entity type is present). This allows the conflict resolver to detect and serialize concurrent
 *   schema changes that would otherwise clash
 *
 * Characteristics:
 *
 * - immutable and thread-safe
 * - value-based equality and hash code (via Lombok)
 * - stores only the attribute {@code name}, leaving concrete mutation details to subclasses
 *
 * Typical subclasses include mutations that create, remove, or rename attribute schemas, both at the
 * catalog level and within a particular entity collection. Implementors should focus on the mutation's
 * semantics; naming and conflict-key generation are handled here.
 *
 * @see io.evitadb.api.requestResponse.schema.mutation.attribute.GlobalAttributeSchemaMutation
 * @see io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
@Immutable
@RequiredArgsConstructor
@EqualsAndHashCode
abstract class AbstractAttributeSchemaMutation implements NamedSchemaMutation {
	@Serial private static final long serialVersionUID = -1239026715678744015L;
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
		return context.isEntityTypePresent() ?
			Stream.of(new CollectionConflictKey(context.getEntityType())) :
			Stream.of(new CatalogConflictKey(context.getCatalogName()));
	}

	/**
	 * Joins an array of {@link ScopedAttributeUniquenessType} into a single string representation.
	 * Each element in the array is transformed into a string format combining its scope and uniqueness type
	 * (e.g., "scope: UNIQUENESS_TYPE") and concatenated with a comma separator.
	 *
	 * @param scopes an array of {@link ScopedAttributeUniquenessType} defining the scope and uniqueness type combinations;
	 *               must not be null.
	 * @return a comma-separated string representation of the input array; never null.
	 */
	@Nonnull
	protected static String join(@Nonnull ScopedAttributeUniquenessType[] scopes) {
		return Arrays.stream(scopes)
			.map(it -> it.scope() + ": " + it.uniquenessType().name())
			.collect(Collectors.joining(", "));
	}

	/**
	 * Concatenates an array of {@code ScopedGlobalAttributeUniquenessType} entries into a single string.
	 * Each entry in the array is transformed into a string representation in the format:
	 * {@code "scope: uniquenessType"} and entries are joined with a comma and space.
	 *
	 * @param scopes an array of {@code ScopedGlobalAttributeUniquenessType} objects representing
	 *               attribute uniqueness types scoped by a specific domain or context.
	 * @return a concatenated string representation of all entries in the {@code scopes} array.
	 *         Returns an empty string if the input array is null or empty.
	 */
	@Nonnull
	protected static String join(ScopedGlobalAttributeUniquenessType[] scopes) {
		return Arrays.stream(scopes)
			.map(it -> it.scope() + ": " + it.uniquenessType().name())
			.collect(Collectors.joining(", "));
	}

}
