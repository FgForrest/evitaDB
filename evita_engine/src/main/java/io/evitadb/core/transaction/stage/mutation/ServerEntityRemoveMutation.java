/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import io.evitadb.api.requestResponse.data.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutationBehavior;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.EnumSet;
import java.util.Optional;

/**
 * Represents a verified entity remove mutation. This is used to mark the entity remove mutation as verified
 * and thus it can be propagated to the "live view" of the evitaDB engine without undo support.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ServerEntityRemoveMutation extends EntityRemoveMutation implements ServerEntityMutation {
	@Serial private static final long serialVersionUID = 2860854495453490511L;
	@Getter private final EntityRemoveMutation delegate;
	private final boolean applyUndoOnError;
	private final boolean verifyConsistency;

	public ServerEntityRemoveMutation(
		@Nonnull EntityRemoveMutation entityRemoveMutation,
		boolean applyUndoOnError,
		boolean verifyConsistency
	) {
		super(
			entityRemoveMutation.getEntityType(),
			entityRemoveMutation.getEntityPrimaryKey()
		);
		this.delegate = entityRemoveMutation;
		this.applyUndoOnError = applyUndoOnError;
		this.verifyConsistency = verifyConsistency;
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
	public EnumSet<ImplicitMutationBehavior> getImplicitMutationsBehavior() {
		return EnumSet.noneOf(ImplicitMutationBehavior.class);
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
}
