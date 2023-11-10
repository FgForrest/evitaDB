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

package io.evitadb.api.proxy;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * This interface is implemented by all proxy types that wrap a sealed entity and provide access to an instance of
 * the {@link SealedEntity} trapped in the proxy state object.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface SealedEntityProxy extends EvitaProxy {

	/**
	 * Returns the primary key of the underlying sealed entity. The primary key might be null if the entity hasn't been
	 * yet upserted to the database.
	 *
	 * @return the primary key of the underlying sealed entity
	 */
	@Nullable
	Integer getPrimaryKey();

	/**
	 * Returns the underlying sealed entity that is wrapped into a requested proxy type.
	 *
	 * @return the underlying sealed entity
	 */
	@Nonnull
	EntityContract getEntity();

	/**
	 * Returns the entity mutation that contains all the {@link LocalMutation} related to the wrapped
	 * {@link #getEntity()} opened for write.
	 *
	 * @return the entity mutation or empty value if no mutations were performed
	 */
	@Nonnull
	Optional<EntityBuilderWithCallback> getEntityBuilderWithCallback();

	/**
	 * Returns stream of entity mutations that related not to internal wrapped {@link #getEntity()} but to
	 * entities that are referenced from this internal entity. This stream is used in method
	 * {@link EvitaSessionContract#upsertEntityDeeply(Serializable)} to store all changes that were made to the object
	 * tree originating from this proxy.
	 *
	 * @return stream of all mutations that were made to the object tree originating from this proxy, empty stream if
	 * no mutations were made or mutations were made only to the internally wrapped {@link #getEntity()}
	 */
	@Nonnull
	Stream<EntityBuilderWithCallback> getReferencedEntityBuildersWithCallback();

	/**
	 * Types of generated proxies.
	 */
	enum ProxyType {
		PARENT,
		REFERENCE,
		REFERENCED_ENTITY
	}

	/**
	 * Record wrapping {@link EntityBuilder} and callback that is called when the builder mutations is applied via
	 * {@link EvitaSessionContract#upsertEntityDeeply(Serializable)} method.
	 */
	record EntityBuilderWithCallback(
		@Nonnull EntityBuilder builder,
		@Nullable Consumer<EntityReference> upsertCallback
	) {

		/**
		 * Method invokes the {@link #upsertCallback} callback if it is present.
		 */
		public void updateEntityReference(@Nonnull EntityReference entityReference) {
			if (upsertCallback != null) {
				upsertCallback.accept(entityReference);
			}
		}

	}

}
