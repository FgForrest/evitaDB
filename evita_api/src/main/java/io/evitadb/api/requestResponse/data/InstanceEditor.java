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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.util.List;
import java.util.Optional;

/**
 * Interface that could be implemented by custom model classes that want to follow the immutable principles that are
 * used within evitaDB.
 *
 * Builder produces either {@link EntityMutation} that describes all changes to be made on entity model instance
 * to get it to "up-to-date" state or can provide new read-only version of the entity model instance  that may not
 * represent globally "up-to-date" state because it is based on the version of the entity known when builder was
 * created.
 *
 * Mutation allows Evita to perform surgical updates on the latest version of the entity model instance that
 * is in the database at the time update request arrives.
 */
@NotThreadSafe
public interface InstanceEditor<READ_INTERFACE extends Serializable> extends Serializable {

	/**
	 * Returns the contract class of model entity that is being edited.
	 * @return class of the model entity
	 */
	@Nonnull
	Class<READ_INTERFACE> getContract();

	/**
	 * Returns object that contains set of {@link LocalMutation} instances describing
	 * what changes occurred in the builder and which should be applied on the existing entity model instance version.
	 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race conditions
	 * based on "optimistic locking" mechanism in very granular way.
	 */
	@Nonnull
	Optional<EntityMutation> toMutation();

	/**
	 * Returns built "local up-to-date" entity model instance that may not represent globally "up-to-date"
	 * state because it is based on the version of the entity known when builder was created.
	 *
	 * Mutation allows Evita to perform surgical updates on the latest version of the entity model instance
	 * which is in the database at the time update request arrives.
	 *
	 * This method is particularly useful for tests.
	 */
	@Nonnull
	READ_INTERFACE toInstance();

	/**
	 * The method is a shortcut for calling {@link EvitaSessionContract#upsertEntity(Serializable)} the other
	 * way around. Method simplifies the statements, makes them more readable and in combination with builder
	 * pattern usage it's also easier to use.
	 *
	 * @param session to use for upserting the modified (built) entity
	 * @return the reference to the updated / created entity
	 */
	@Nonnull
	default EntityReferenceContract upsertVia(@Nonnull EvitaSessionContract session) {
		return session.upsertEntity(this);
	}

	/**
	 * The method is a shortcut for calling {@link EvitaSessionContract#upsertEntityDeeply(Serializable)} the other
	 * way around. Method simplifies the statements, makes them more readable and in combination with builder
	 * pattern usage it's also easier to use.
	 *
	 * @param session to use for upserting the modified (built) entity
	 * @return the reference to the updated / created entity
	 */
	@Nonnull
	default List<EntityReferenceContract> upsertDeeplyVia(@Nonnull EvitaSessionContract session) {
		return session.upsertEntityDeeply(this);
	}

}
