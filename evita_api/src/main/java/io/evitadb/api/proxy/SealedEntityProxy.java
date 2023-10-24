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
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

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
	SealedEntity getSealedEntity();

	/**
	 * Returns the entity mutation that contains all the {@link LocalMutation} related to the wrapped
	 * {@link #getSealedEntity()} opened for write.
	 *
	 * @return the entity mutation or empty value if no mutations were performed
	 */
	@Nonnull
	Optional<EntityMutationWithCallback> getEntityMutation();

	/**
	 * Returns stream of entity mutations that related not to internal wrapped {@link #getSealedEntity()} but to
	 * entities that are referenced from this internal entity. This stream is used in method
	 * {@link EvitaSessionContract#upsertEntityDeeply(Serializable)} to store all changes that were made to the object
	 * tree originating from this proxy.
	 *
	 * @return stream of all mutations that were made to the object tree originating from this proxy, empty stream if
	 * no mutations were made or mutations were made only to the internally wrapped {@link #getSealedEntity()}
	 */
	@Nonnull
	Stream<EntityMutationWithCallback> getReferencedEntityMutations();

	/**
	 * Method registers created proxy object that was created by this proxy instance and relates to referenced objects
	 * accessed via it. We need to provide exactly the same instances of those objects when the same method is called
	 * or the logically same object is retrieved via different method with compatible type.
	 *
	 * @param referencedEntityType the {@link EntitySchemaContract#getName()} of the referenced entity type
	 * @param referencedPrimaryKey the {@link EntityContract#getPrimaryKey()} of the referenced entity
	 * @param proxy                the proxy object
	 * @param proxyType            the base type of the proxy object
	 * @param logicalType          logical type of the proxy object
	 */
	void registerReferencedEntityObject(
		@Nonnull String referencedEntityType,
		int referencedPrimaryKey,
		@Nonnull Object proxy,
		@Nonnull Class<?> proxyType,
		@Nonnull ProxyType logicalType
	);

	/**
	 * Returns the registered proxy, matching the registration context in method
	 * {@link #registerReferencedEntityObject(String, int, Object, Class, ProxyType)}.
	 *
	 * @param referencedEntityType the {@link EntitySchemaContract#getName()} of the referenced entity type
	 * @param referencedPrimaryKey the {@link EntityContract#getPrimaryKey()} of the referenced entity
	 * @param expectedType         the expected class type of the proxy
	 * @param proxyType            set of logical types to be searched for the proxy instance
	 */
	@Nonnull
	<T extends Serializable> Optional<T> getReferencedEntityObject(
		@Nonnull String referencedEntityType,
		int referencedPrimaryKey,
		@Nonnull Class<T> expectedType,
		@Nonnull ProxyType... proxyType
	);

	/**
	 * Types of generated proxies.
	 */
	enum ProxyType {
		ENTITY,
		ENTITY_BUILDER,
		PARENT,
		PARENT_BUILDER,
		REFERENCE,
		REFERENCE_BUILDER,
		REFERENCED_ENTITY_BUILDER
	}

	/**
	 * Record wrapping {@link EntityMutation} and callback that is called when the mutation is applied via
	 * {@link EvitaSessionContract#upsertEntity(EntityMutation)} method.
	 */
	record EntityMutationWithCallback(
		@Nonnull EntityMutation theMutation,
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
