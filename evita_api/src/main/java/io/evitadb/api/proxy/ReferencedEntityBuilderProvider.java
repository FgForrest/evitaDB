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
import io.evitadb.api.proxy.SealedEntityProxy.EntityBuilderWithCallback;
import io.evitadb.api.proxy.SealedEntityProxy.Propagation;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.stream.Stream;

/**
 * Provides access to entity builders for referenced entities that were modified through a proxy object tree.
 *
 * This interface is implemented by {@link SealedEntityProxy} and {@link SealedEntityReferenceProxy} to enable
 * deep upsert operations where modifications cascade through the entire object graph. When a proxy wraps an
 * entity that has references to other entities (e.g., product → categories, product → brand), and those
 * referenced entities are also accessed/modified through nested proxies, this interface collects all the
 * pending mutations.
 *
 * **Deep Upsert Use Case:**
 *
 * Deep upsert ({@link EvitaSessionContract#upsertEntityDeeply(Serializable)}) saves not only the main entity
 * but also all referenced entities that were modified through the proxy tree. For example:
 *
 * ```java
 * Product product = session.getEntity(Product.class, 1, ...);
 * Category category = product.getMainCategory();
 * category.setName("Updated Category");  // modifies referenced entity
 * product.setName("Updated Product");    // modifies main entity
 * session.upsertEntityDeeply(product);   // saves both entities
 * ```
 *
 * The `getReferencedEntityBuildersWithCallback` method returns builders for all modified referenced entities
 * (in this case, the category), allowing the session to persist them alongside the main entity.
 *
 * **Callback Mechanism:**
 *
 * Each builder is wrapped with a callback ({@link SealedEntityProxy.EntityBuilderWithCallback}) that is
 * invoked after the referenced entity is persisted. This allows the main entity to be updated with newly
 * assigned primary keys or other post-upsert information (e.g., version numbers).
 *
 * @see SealedEntityProxy
 * @see SealedEntityReferenceProxy
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface ReferencedEntityBuilderProvider {

	/**
	 * Returns a stream of entity builders for all referenced entities that were modified through this proxy.
	 *
	 * This method is used by {@link EvitaSessionContract#upsertEntityDeeply(Serializable)} to collect all
	 * pending mutations across the entire object graph. The returned builders represent entities that were:
	 * - Accessed through reference methods on this proxy (e.g., `product.getCategories()`)
	 * - Modified through their own proxy instances
	 * - Not yet persisted to the database
	 *
	 * Each builder is wrapped with a callback that is executed after the referenced entity is successfully
	 * upserted. The callback typically updates internal references (e.g., setting newly assigned primary keys).
	 *
	 * **Propagation Mode:**
	 *
	 * The `propagation` parameter controls how deep the collection should go:
	 * - {@link SealedEntityProxy.Propagation#SHALLOW}: Only collect builders from the first level of proxies
	 *   (proxies created directly from the main entity)
	 * - {@link SealedEntityProxy.Propagation#DEEP}: Collect builders from all proxies in the entire object tree
	 *   (transitive closure of all referenced entities)
	 *
	 * @param propagation determines whether to collect builders from immediate proxies only or from the entire
	 *                    object tree
	 * @return stream of entity builders with callbacks for all modified referenced entities; empty stream if no
	 *         referenced entities were modified
	 */
	@Nonnull
	Stream<EntityBuilderWithCallback> getReferencedEntityBuildersWithCallback(@Nonnull Propagation propagation);

}
