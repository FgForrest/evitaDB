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

package io.evitadb.core.transaction.stage.mutation;

import io.evitadb.index.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutationBehavior;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents a verified entity upsert mutation. This is used to mark the entity upsert mutation as verified
 * and thus it can be propagated to the "live view" of the evitaDB engine without primary key assignment
 * verifications and undo support.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ServerEntityUpsertMutation extends EntityUpsertMutation implements ServerEntityMutation {
	@Serial private static final long serialVersionUID = -5775248516292883577L;
	/**
	 * Specifies whether the implicit mutations should be generated or not.
	 */
	@Getter public final EnumSet<ImplicitMutationBehavior> implicitMutations;
	@Getter private final EntityUpsertMutation delegate;
	private final boolean verifyConsistency;
	private final boolean applyUndoOnError;

	public ServerEntityUpsertMutation(
		@Nonnull EntityUpsertMutation entityUpsertMutation,
		@Nonnull EnumSet<ImplicitMutationBehavior> implicitMutations,
		boolean applyUndoOnError,
		boolean verifyConsistency
	) {
		super(
			entityUpsertMutation.getEntityType(),
			entityUpsertMutation.getEntityPrimaryKey(),
			entityUpsertMutation.expects(),
			entityUpsertMutation.getLocalMutations()
		);
		this.delegate = entityUpsertMutation;
		this.implicitMutations = implicitMutations;
		this.applyUndoOnError = applyUndoOnError;
		this.verifyConsistency = verifyConsistency;
	}

	public ServerEntityUpsertMutation(
		@Nonnull String entityType,
		@Nullable Integer entityPrimaryKey,
		@Nonnull EntityExistence entityExistence,
		@Nonnull EnumSet<ImplicitMutationBehavior> implicitMutations,
		boolean applyUndoOnError,
		boolean verifyConsistency,
		@Nonnull LocalMutation<?, ?>... localMutations
	) {
		super(
			entityType,
			entityPrimaryKey,
			entityExistence,
			localMutations
		);
		this.delegate = new EntityUpsertMutation(entityType, entityPrimaryKey, entityExistence, localMutations);
		this.implicitMutations = implicitMutations;
		this.applyUndoOnError = applyUndoOnError;
		this.verifyConsistency = verifyConsistency;
	}

	@Nonnull
	@Override
	public EnumSet<ImplicitMutationBehavior> getImplicitMutationsBehavior() {
		return this.implicitMutations;
	}

	@Override
	public boolean shouldApplyUndoOnError() {
		return this.applyUndoOnError;
	}

	@Override
	public boolean shouldVerifyConsistency() {
		return this.verifyConsistency;
	}

	@Nonnull
	@Override
	public Optional<LocalEntitySchemaMutation[]> verifyOrEvolveSchema(
		@Nonnull SealedCatalogSchema catalogSchema,
		@Nonnull SealedEntitySchema entitySchema,
		boolean entityCollectionEmpty
	) {
		// this has already happened in the transactional memory layer
		// and all schema mutations have been already recorded
		return Optional.empty();
	}

	/**
	 * Merges the current {@code ServerEntityUpsertMutation} instance with another {@code ServerEntityUpsertMutation}.
	 * This operation combines the implicit mutation behaviors and the local mutations of both instances.
	 * The merging is only allowed if the {@code applyUndoOnError} and {@code verifyConsistency} flags are identical
	 * for both instances.
	 *
	 * @param anotherMutation the {@code ServerEntityUpsertMutation} instance to merge with the current instance.
	 *                        Must not be null.
	 * @return a new {@code ServerEntityUpsertMutation} instance containing the merged implicit mutation behaviors
	 *         and local mutations from both instances.
	 * @throws IllegalArgumentException if the {@code applyUndoOnError} or {@code verifyConsistency} flags differ
	 *                                  between the two {@code ServerEntityUpsertMutation} instances.
	 */
	@Nonnull
	public ServerEntityUpsertMutation mergeWith(@Nonnull ServerEntityUpsertMutation anotherMutation) {
		Assert.isPremiseValid(
			this.applyUndoOnError == anotherMutation.applyUndoOnError &&
			this.verifyConsistency == anotherMutation.verifyConsistency,
			"Cannot merge two ServerEntityUpsertMutations that have different applyUndoOnError or verifyConsistency flags!"
		);
		final EnumSet<ImplicitMutationBehavior> mergedImplicitMutations = EnumSet.copyOf(this.implicitMutations);
		mergedImplicitMutations.addAll(anotherMutation.implicitMutations);
		return new ServerEntityUpsertMutation(
			new EntityUpsertMutation(
				this.getEntityType(),
				this.getEntityPrimaryKey(),
				this.expects(),
				Stream.concat(
					this.getLocalMutations().stream(),
					anotherMutation.getLocalMutations().stream()
				).collect(Collectors.toCollection(ArrayList::new))
			),
			mergedImplicitMutations,
			this.applyUndoOnError,
			this.verifyConsistency
		);
	}
}
