/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

/**
 * Mutation executor applies local mutations on entity contents. Entity contents can be organized in different way than
 * entity itself and may be more optimal for storage / reading / writing handling.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface LocalMutationExecutor {

	/**
	 * Method is called when {@link LocalMutation} is applied on entity instance.
	 *
	 * @param localMutation mutation to process
	 */
	void applyMutation(@Nonnull LocalMutation<?, ?> localMutation);

	/**
	 * Method allows to apply all changes that has been recorded by this executor during multiple execution of
	 * {@link #applyMutation(LocalMutation)}. The method is called only once after all mutations has been applied.
	 */
	void commit();

	/**
	 * Rolls back all changes that has been recorded by this executor during multiple execution of
	 * {@link #applyMutation(LocalMutation)}. The method is called only once before the executor is thrown out.
	 */
	void rollback();
}
