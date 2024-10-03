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
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;

import javax.annotation.Nonnull;
import java.util.EnumSet;

/**
 * This interface marks server side entity mutations that has been already verified when the transaction was first
 * applied and are replayed from WAL log. In this case some kind of checks can be skipped. It's also used for marking
 * mutations generated as implicit mutations to avoid infinite loops and unnecessary checks.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface ServerEntityMutation {

	/**
	 * Determines whether the applying logic should try to undo actions when error occurs.
	 * This may not be necessary when replaying mutations from WAL log, since then the entire memory is thrown out.
	 *
	 * @return true if the applying logic should try to undo actions when error occurs, false otherwise
	 */
	boolean shouldApplyUndoOnError();

	/**
	 * Determines whether the method should verify the consistency of the entity. This is not necessary if the mutation
	 * is replayed from WAL log, because there the consistency is already verified.
	 *
	 * @return true if the method should verify the consistency of the entity, false otherwise
	 */
	boolean shouldVerifyConsistency();

	/**
	 * Determines whether the method should produce implicit mutations.
	 *
	 * @return the set of implicit mutations that should be generated
	 */
	@Nonnull
	EnumSet<ImplicitMutationBehavior> getImplicitMutationsBehavior();

	/**
	 * Returns the original entity mutation this server entity mutation is based on.
	 * @return the original entity mutation
	 */
	@Nonnull
	EntityMutation getDelegate();

}
