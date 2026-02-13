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

package io.evitadb.api.proxy;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Marker interface for proxy instances that wrap a sealed entity and provide entity-specific operations.
 *
 * This interface is implemented by all dynamically generated proxy classes that wrap {@link SealedEntity}
 * instances. It combines multiple concerns:
 * - Access to the underlying sealed entity ({@link WithEntityContract})
 * - Mutation tracking via entity builder ({@link WithEntityBuilder})
 * - Collection of mutations from referenced entities ({@link ReferencedEntityBuilderProvider})
 * - Metadata about the proxy class itself ({@link EvitaProxy})
 *
 * **Proxy Creation:**
 *
 * Instances implementing this interface are created by {@link ProxyFactory} when client code queries entities
 * with a custom return type. The proxy infrastructure analyzes the client-defined class/interface and generates
 * bytecode that implements it while delegating to the wrapped sealed entity.
 *
 * **Lifecycle:**
 *
 * 1. **Read-only phase**: Proxy wraps a sealed entity, all getters delegate to entity data
 * 2. **Mutation phase**: When a setter/mutation method is called, an {@link EntityBuilder} is created
 * 3. **Persistence**: Builder mutations are applied via {@link EvitaSessionContract#upsertEntity} or
 *    {@link EvitaSessionContract#upsertEntityDeeply}
 * 4. **Post-upsert**: Proxy can be updated with the persisted entity reference (containing new primary key)
 *
 * **Primary Key Handling:**
 *
 * Newly created entities don't have a primary key until they're persisted. The proxy tracks the primary key
 * separately from the wrapped entity to handle the case where a new entity is created, modified through the
 * proxy, and then upserted (at which point the database assigns a primary key).
 *
 * **Thread-Safety:**
 *
 * Proxy instances are not thread-safe. Each thread should work with its own proxy instance or synchronize
 * access externally.
 *
 * @see ProxyFactory
 * @see SealedEntityReferenceProxy
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface SealedEntityProxy extends
	EvitaProxy,
	WithEntityContract,
	WithEntityBuilder,
	ReferencedEntityBuilderProvider
{

	/**
	 * Returns the primary key of the underlying sealed entity, or null if not assigned yet.
	 *
	 * The primary key may be null in two scenarios:
	 * - The proxy wraps a newly created entity that hasn't been persisted yet
	 * - The entity was queried without fetching the primary key (rare edge case)
	 *
	 * After calling {@link EvitaSessionContract#upsertEntity} on a new entity, the proxy is updated with the
	 * assigned primary key via the callback mechanism.
	 *
	 * @return the primary key of the underlying entity, or null if not assigned/available
	 */
	@Nullable
	Integer getPrimaryKey();

	/**
	 * Returns the primary key of the underlying sealed entity, throwing an exception if not assigned yet.
	 *
	 * This method is a convenience wrapper around {@link #getPrimaryKey()} that fails fast if the primary key
	 * is not available. Use this when the primary key is expected to exist (e.g., when working with entities
	 * retrieved from queries).
	 *
	 * @return the primary key of the underlying entity
	 * @throws io.evitadb.api.exception.PrimaryKeyNotAssignedException if the primary key is null
	 */
	int getPrimaryKeyOrThrowException();

	/**
	 * Returns the entity builder along with its post-upsert callback, if mutations have been performed.
	 *
	 * This method is used internally by {@link EvitaSessionContract} to collect entity mutations for persistence.
	 * The callback is invoked after the entity is successfully upserted, allowing the proxy to update its internal
	 * state with the persisted entity reference (which may contain a newly assigned primary key).
	 *
	 * **Propagation Mode:**
	 *
	 * The `propagation` parameter controls whether to return the builder only if this is the "original" proxy
	 * that initiated the mutation chain (SHALLOW), or to also consider secondary proxies created from the same
	 * entity (DEEP).
	 *
	 * @param propagation determines whether to include builders from derived proxies or only the primary proxy
	 * @return optional containing the builder and callback if mutations were performed, empty otherwise
	 */
	@Nonnull
	Optional<EntityBuilderWithCallback> getEntityBuilderWithCallback(@Nonnull Propagation propagation);

	/**
	 * Defines the propagation mode for operations that may affect multiple proxy instances of the same entity.
	 *
	 * In evitaDB's proxy system, the same underlying entity may be wrapped by multiple proxy instances:
	 * - The "primary" proxy created directly from a query result
	 * - "Secondary" proxies created when the same entity is accessed through references from other entities
	 * - "Isolated" proxies created with separate builders for independent modification
	 *
	 * The propagation mode controls whether operations (e.g., collecting builders for upsert) should include
	 * only the primary proxy (SHALLOW) or all related proxies (DEEP).
	 *
	 * **Shared vs. Isolated Builders:**
	 *
	 * When the first proxy is created with a builder, subsequent proxies of the same entity share that builder
	 * (shared mode). If the first proxy is read-only, later proxies that need mutation capabilities create
	 * their own isolated builders. The propagation mode determines whether isolated builders are included in
	 * operations like deep upsert.
	 */
	enum Propagation {
		/**
		 * Include only the primary proxy instance that was created first or owns the shared builder.
		 *
		 * Use SHALLOW when you want to persist only the mutations made through the original proxy, ignoring
		 * any changes made through secondary or isolated proxy instances.
		 */
		SHALLOW,
		/**
		 * Include all proxy instances that wrap the same entity, including those with isolated builders.
		 *
		 * Use DEEP when you want to collect all mutations across the entire object graph, regardless of which
		 * proxy instance was used to make the changes. This is the typical mode for
		 * {@link EvitaSessionContract#upsertEntityDeeply}.
		 */
		DEEP
	}

	/**
	 * Wraps an entity builder with a post-upsert callback for updating proxy state after persistence.
	 *
	 * This record is used internally by the proxy infrastructure to coordinate between entity mutations and
	 * proxy state updates. When {@link EvitaSessionContract#upsertEntity} or {@link EvitaSessionContract#upsertEntityDeeply}
	 * persists an entity, it invokes the callback with the resulting entity reference, allowing the proxy to
	 * update its internal state (e.g., storing the newly assigned primary key).
	 *
	 * **Callback Semantics:**
	 *
	 * The callback is optional (nullable) because not all builders require post-upsert actions. For example:
	 * - New entities need callbacks to capture assigned primary keys
	 * - Entities accessed through references may need callbacks to update reference pointers
	 * - Simple updates of existing entities with known primary keys may not need callbacks
	 *
	 * @param builder the entity builder containing accumulated mutations
	 * @param upsertCallback optional callback invoked after the entity is successfully persisted; receives
	 *                       the entity reference returned by the upsert operation (contains primary key,
	 *                       version, and entity type)
	 */
	record EntityBuilderWithCallback(
		@Nonnull EntityBuilder builder,
		@Nullable Consumer<EntityReferenceContract> upsertCallback
	) {

		/**
		 * Invokes the post-upsert callback with the persisted entity reference, if a callback is present.
		 *
		 * This method is called by {@link EvitaSessionContract} after successfully persisting an entity.
		 * If no callback was registered (null), this method does nothing.
		 *
		 * @param entityReference the entity reference returned from the database after upsert; contains the
		 *                        primary key (possibly newly assigned), version, and entity type
		 */
		public void entityUpserted(@Nonnull EntityReferenceContract entityReference) {
			if (this.upsertCallback != null) {
				this.upsertCallback.accept(entityReference);
			}
		}

	}

}
