/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.index.mutation.storagePart;


import io.evitadb.api.requestResponse.data.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutations;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.dataType.array.CompositeObjectArray;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Allows collecting mutations in a mutable and lazy fashion. It tries to allocate memory as late as possible.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class MutationCollector {
	@SuppressWarnings("rawtypes") private static final LocalMutation[] NO_LOCAL_MUTATIONS = new LocalMutation[0];
	private static final EntityMutation[] NO_ENTITY_MUTATIONS = new EntityMutation[0];

	/**
	 * Local mutations that are applied on the very same entity as it is being mutated.
	 */
	@SuppressWarnings("rawtypes")
	private CompositeObjectArray<LocalMutation> localMutations;
	/**
	 * External mutations that are applied on other (referenced) entities.
	 */
	private CompositeObjectArray<EntityMutation> externalMutations;

	/**
	 * Adds a local mutation to the mutation collector.
	 *
	 * @param mutation the local mutation to add
	 */
	public void addLocalMutation(@Nonnull LocalMutation<?, ?> mutation) {
		if (this.localMutations == null) {
			this.localMutations = new CompositeObjectArray<>(LocalMutation.class);
		}
		this.localMutations.add(mutation);
	}

	/**
	 * Adds an external mutation to the mutation collector.
	 *
	 * @param mutation the external mutation to add
	 */
	public void addExternalMutation(@Nonnull EntityMutation mutation) {
		if (this.externalMutations == null) {
			this.externalMutations = new CompositeObjectArray<>(EntityMutation.class);
		}
		this.externalMutations.add(mutation);
	}

	/**
	 * Converts the local and external mutations collected by the mutation collector into
	 * an instance of {@link ImplicitMutations}.
	 *
	 * @return an {@link ImplicitMutations} object containing the local and external mutations.
	 */
	@Nonnull
	public ImplicitMutations toImplicitMutations() {
		return new ImplicitMutations(
			this.localMutations == null ? NO_LOCAL_MUTATIONS : this.localMutations.toArray(),
			this.externalMutations == null ? NO_ENTITY_MUTATIONS : this.externalMutations.toArray()
		);
	}

	/**
	 * Retrieves a set of extracted local mutations from external entity mutations that match the specified entity type
	 * and primary key. The extraction is performed using the provided function.
	 *
	 * @param <T> the type of the objects to extract from the entity mutations
	 * @param entityType the type of the entity to filter the external mutations
	 * @param entityPrimaryKey the primary key of the entity to filter the external mutations
	 * @param extractFunction a function to map the {@link EntityUpsertMutation} objects to a stream of objects of type T
	 * @return a set of objects of type T extracted from the matching external entity mutations
	 */
	@Nonnull
	public <T> Set<T> getExternalEntityLocalMutations(
		@Nonnull String entityType,
		int entityPrimaryKey,
		@Nonnull Function<EntityUpsertMutation, Stream<T>> extractFunction
	) {
		Stream<T> result = null;
		if (this.externalMutations != null) {
			for (EntityMutation entityMutation : this.externalMutations) {
				if (
					entityMutation instanceof EntityUpsertMutation eup &&
						entityType.equals(eup.getEntityType()) &&
						eup.getEntityPrimaryKey() != null &&
						entityPrimaryKey == eup.getEntityPrimaryKey()
				) {
					final Stream<T> resultStream = extractFunction.apply(eup);
					result = result == null ?
						resultStream :
						Stream.concat(result, resultStream);
				}
			}
		}
		return result == null ?
			Collections.emptySet() : result.collect(Collectors.toSet());
	}
}
