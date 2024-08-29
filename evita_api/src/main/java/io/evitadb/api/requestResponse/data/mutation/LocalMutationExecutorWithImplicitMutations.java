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

package io.evitadb.api.requestResponse.data.mutation;


import javax.annotation.Nonnull;
import java.util.List;

/**
 * Interface extends basic {@link LocalMutationExecutor} with ability to produce lists of implicit mutations that need
 * to be applied in order to keep data consistent.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface LocalMutationExecutorWithImplicitMutations extends LocalMutationExecutor {

	/**
	 * Method returns and clears list of implicit mutations that needs to be executed to keep entity consistent.
	 * These implicit mutations usually represent initialization of default values on entity creation.
	 *
	 * @return list of implicit mutations
	 */
	@Nonnull
	ImplicitMutations popImplicitMutations(@Nonnull List<? extends LocalMutation<?, ?>> inputMutations);

	/**
	 * The ImplicitMutations class represents a collection of implicit mutations that need to be executed to keep
	 * an entity consistent and also other (referenced) entities consisteng. Implicit mutations are mutations that are
	 * usually applied during the initialization of default values on entity creation.
	 *
	 * This class is used by the LocalMutationExecutor interface, which is responsible for applying mutations to entity contents.
	 *
	 * @param localMutations    a list of LocalMutation instances representing the local mutations to be applied in order
	 *                          to keep processed entity consistent
	 * @param externalMutations a list of EntityMutation instances representing the external mutations to be applied
	 *                          in order to keep referenced entities consistent
	 */
	record ImplicitMutations(
		@Nonnull LocalMutation<?, ?>[] localMutations,
		@Nonnull EntityMutation[] externalMutations
	) {

	}

}
