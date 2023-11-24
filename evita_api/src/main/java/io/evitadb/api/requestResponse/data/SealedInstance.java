/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serializable;
import java.util.Collection;

/**
 * Sealed instance is read only form of model entity that contains seal-breaking actions such as opening its contents
 * to write actions using {@link InstanceEditor} or accepting mutations that create {@link EntityMutation} objects. All 
 * seal breaking actions don't modify {@link SealedInstance} contents and only create new objects based on it. 
 * This keeps this class immutable and thread safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
@Immutable
public interface SealedInstance<READ_INTERFACE extends Serializable, WRITE_INTERFACE extends InstanceEditor<READ_INTERFACE>> {

	/**
	 * Opens entity for update - returns {@link InstanceEditor} that allows modification of the entity internals and
	 * fabricates new immutable copy of the entity with altered data. Returned EntityBuilder is NOT THREAD SAFE.
	 *
	 * {@link InstanceEditor} doesn't alter contents of {@link SealedInstance} but allows to create new version based on
	 * the version that is represented by this sealed entity.
	 */
	@Nonnull
	WRITE_INTERFACE openForWrite();

	/**
	 * Opens entity for update - returns {@link InstanceEditor} and incorporates the passed array of `localMutations`
	 * in the returned {@link InstanceEditor} right away. The builder allows modification of the entity internals and
	 * fabricates new immutable copy of the entity with altered data. Returned {@link InstanceEditor} is NOT THREAD SAFE.
	 *
	 * {@link InstanceEditor} doesn't alter contents of {@link SealedInstance} but allows to create new version based on
	 * the version that is represented by this sealed entity.
	 */
	@Nonnull
	WRITE_INTERFACE withMutations(@Nonnull LocalMutation<?, ?>... localMutations);

	/**
	 * Opens entity for update - returns {@link InstanceEditor} and incorporates the passed collection of `localMutations`
	 * in the returned {@link InstanceEditor} right away. The builder allows modification of the entity internals and
	 * fabricates new immutable copy of the entity with altered data. Returned {@link InstanceEditor} is NOT THREAD SAFE.
	 *
	 * {@link InstanceEditor} doesn't alter contents of {@link SealedInstance} but allows to create new version based on
	 * the version that is represented by this sealed entity.
	 */
	@Nonnull
	WRITE_INTERFACE withMutations(@Nonnull Collection<LocalMutation<?, ?>> localMutations);

}
