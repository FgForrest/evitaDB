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

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Marker interface for proxy instances that wrap entity references and provide reference-specific operations.
 *
 * This interface is implemented by all dynamically generated proxy classes that wrap {@link ReferenceContract}
 * instances. References in evitaDB represent relationships between entities (e.g., product → category) and can
 * carry their own attributes (e.g., "orderInCategory" on a product-category relationship).
 *
 * **Purpose:**
 *
 * Reference proxies enable type-safe access to reference data through client-defined interfaces, records, or
 * POJOs. They provide:
 * - Access to reference attributes (custom data on the relationship)
 * - Access to the referenced entity (if fetched with `entityFetch` in query)
 * - Access to group entity (if reference has a group and was fetched with `entityGroupFetch`)
 * - Mutation tracking for reference attributes via {@link ReferenceBuilder}
 * - Collection of mutations from nested referenced entities ({@link ReferencedEntityBuilderProvider})
 *
 * **Integration with Entity Proxies:**
 *
 * Reference proxies are typically accessed through methods on entity proxies. For example:
 * ```java
 * Product product = session.getEntity(Product.class, 1, ...);
 * List<ProductCategory> categories = product.getCategories();  // returns reference proxies
 * categories.get(0).setOrderInCategory(5);  // mutates reference attribute
 * ```
 *
 * **Owner Entity Context:**
 *
 * Reference proxies maintain a link to their owner entity (via {@link #getEntityClassifier()}) because
 * reference mutations must be applied in the context of the owning entity. When the owner entity is upserted,
 * all modified references are included in the mutation set.
 *
 * **Thread-Safety:**
 *
 * Reference proxy instances are not thread-safe. Concurrent modifications require external synchronization.
 *
 * @see ProxyReferenceFactory
 * @see SealedEntityProxy
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface SealedEntityReferenceProxy extends EvitaProxy, ReferencedEntityBuilderProvider {

	/**
	 * Returns the entity classifier of the owner entity that contains this reference.
	 *
	 * The entity classifier identifies the entity that "owns" this reference (e.g., the product that has a
	 * category reference). This information is essential for applying reference mutations, as they must be
	 * associated with a specific entity instance.
	 *
	 * @return entity classifier containing entity type and primary key of the owner entity
	 */
	@Nonnull
	EntityClassifier getEntityClassifier();

	/**
	 * Returns the underlying sealed entity reference wrapped by this proxy.
	 *
	 * The reference contract contains:
	 * - Reference name and type (e.g., "categories")
	 * - Referenced entity primary key and type
	 * - Optional group entity primary key and type
	 * - Reference attributes (if any)
	 * - Referenced entity body (if fetched with `entityFetch` in query)
	 * - Group entity body (if fetched with `entityGroupFetch` in query)
	 *
	 * @return the underlying reference instance (never a proxy, always the raw reference)
	 */
	@Nonnull
	ReferenceContract getReference();

	/**
	 * Returns the reference builder for capturing mutations on this reference, creating it if necessary.
	 *
	 * The reference builder tracks changes to reference attributes (e.g., `setOrderInCategory(5)`). When the
	 * owner entity is upserted, these mutations are applied to the database.
	 *
	 * **Side Effect:**
	 *
	 * If no mutations have been performed yet, this method creates a new builder by calling
	 * `getReference().openForWrite()`. This marks the reference as modified even if no actual changes are made.
	 * Use {@link #getReferenceBuilderIfPresent()} to avoid this side effect.
	 *
	 * @return existing reference builder if mutations were already performed, or newly created builder otherwise
	 */
	@Nonnull
	ReferenceBuilder getReferenceBuilder();

	/**
	 * Returns the reference builder if it has been created (i.e., if any mutation methods were called).
	 *
	 * This method allows checking whether the reference has been modified without triggering builder creation.
	 * It's useful for conditional logic that should only execute when changes have been made.
	 *
	 * **Use Cases:**
	 * - Checking if reference was modified before deciding whether to include it in entity mutations
	 * - Avoiding unnecessary builder creation in read-only scenarios
	 * - Implementing conditional persistence logic
	 *
	 * @return the reference builder if mutations were performed, empty otherwise
	 */
	@Nonnull
	Optional<ReferenceBuilder> getReferenceBuilderIfPresent();

	/**
	 * Notifies the proxy that the owner entity has been upserted and reference mutations have been persisted.
	 *
	 * This method is called by the proxy infrastructure after {@link EvitaSessionContract#upsertEntity} or
	 * {@link EvitaSessionContract#upsertEntityDeeply} successfully persists the owner entity. It allows the
	 * reference proxy to:
	 * - Update internal state to reflect the persisted entity reference
	 * - Clear or mark the builder as "persisted" to avoid duplicate mutations
	 * - Handle any post-persistence bookkeeping
	 *
	 * **Callback Chain:**
	 *
	 * If this reference proxy contains nested entity proxies (e.g., the referenced entity was fetched and
	 * accessed as a proxy), this method may trigger cascading notifications to those nested proxies.
	 *
	 * @param entityReference the entity reference returned from the database after upserting the owner entity;
	 *                        contains the owner entity's primary key, version, and type
	 */
	void notifyBuilderUpserted(@Nonnull EntityReferenceContract entityReference);

}
