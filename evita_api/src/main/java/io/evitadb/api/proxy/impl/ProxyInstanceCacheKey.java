/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.proxy.impl;


/**
 * Marker interface for cache keys used to identify and deduplicate generated proxy instances within
 * an {@link AbstractEntityProxyState}.
 *
 * When a proxy wraps an entity that references other entities (e.g., product → categories), accessing
 * those references through proxy methods creates nested proxy instances. To ensure that repeated calls
 * to the same getter return the **same proxy instance** (identity guarantee), generated proxies are cached
 * in a `Map<ProxyInstanceCacheKey, ProxyWithUpsertCallback>`. This interface is the common type for all
 * cache key variants.
 *
 * **Implementations:**
 *
 * Each cache key variant captures a different kind of proxy relationship:
 *
 * - **ReferencedEntityProxyCacheKey** — identifies a proxy for a referenced entity (target or group)
 *   by reference name, entity primary key, and {@link ReferencedObjectType}
 * - **ParentProxyCacheKey** — identifies a proxy for a parent entity by its primary key
 * - **ReferenceProxyCacheKey** — identifies a proxy for a reference itself (the relationship object
 *   with attributes), keyed by reference name and primary keys
 *
 * **Why a Marker Interface:**
 *
 * The different cache key types have different fields (some need reference names, some need object type
 * discriminators, etc.), so a common base record is not practical. The marker interface allows them all
 * to be stored in the same map while remaining type-safe.
 *
 * @see AbstractEntityProxyState
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ProxyInstanceCacheKey {

}
