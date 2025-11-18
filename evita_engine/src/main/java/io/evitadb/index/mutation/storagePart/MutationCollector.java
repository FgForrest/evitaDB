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
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.transaction.stage.mutation.ServerEntityRemoveMutation;
import io.evitadb.core.transaction.stage.mutation.ServerEntityUpsertMutation;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Objects;

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
	private Map<EntityReference, EntityMutation> externalMutations;

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
			this.externalMutations = CollectionUtils.createLinkedHashMap(16);
		}
		this.externalMutations.compute(
			new EntityReference(mutation.getEntityType(), Objects.requireNonNull(mutation.getEntityPrimaryKey())),
			(entityReference, existingMutation) -> {
				if (existingMutation == null) {
					return mutation;
				} else if (existingMutation instanceof ServerEntityUpsertMutation seum) {
					if (mutation instanceof ServerEntityUpsertMutation newSeum) {
						return seum.mergeWith(newSeum);
					} else {
						throw new GenericEvitaInternalError(
							"Cannot merge external mutation " + mutation + " because there is already existing mutation " + existingMutation + " for the same entity!"
						);
					}
				} else if (existingMutation instanceof ServerEntityRemoveMutation reum) {
					if (mutation instanceof ServerEntityRemoveMutation newReum) {
						return reum.mergeWith(newReum);
					} else {
						throw new GenericEvitaInternalError(
							"Cannot merge external mutation " + mutation + " because there is already existing mutation " + existingMutation + " for the same entity!"
						);
					}
				} else {
					throw new GenericEvitaInternalError(
						"Cannot merge external mutation " + mutation + " because there is already existing mutation " + existingMutation + " for the same entity!"
					);
				}
			}
		);
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
			this.externalMutations == null ? NO_ENTITY_MUTATIONS : this.externalMutations.values().toArray(EntityMutation[]::new)
		);
	}

}
