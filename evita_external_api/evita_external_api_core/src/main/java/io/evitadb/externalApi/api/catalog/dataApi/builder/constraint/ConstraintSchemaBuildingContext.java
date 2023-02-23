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

package io.evitadb.externalApi.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.CatalogContract;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Context holding and caching constraint data during constraint schema building. This context object is supposed to be
 * shared across all constraint schema builders to ensure correct cache usage.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public abstract class ConstraintSchemaBuildingContext<ST> {

	@Nonnull
	@Getter
	protected final CatalogContract catalog;
	/**
	 * Already built containers ready for reuse.
	 */
	protected final Map<ContainerKey, ST> cachedContainers = createHashMap(200);
	/**
	 * Already built wrapper objects ready for reuse.
	 */
	protected final Map<WrapperObjectKey, ST> cachedWrapperObjects = createHashMap(200);

	/**
	 * Built schema types during build process that have to be registered in the API schema because build process
	 * works mainly with type references due to the cycling references to types.
	 */
	protected final List<ST> builtTypes = new LinkedList<>();

	/**
	 * Caches created container under specified key if absent.
	 */
	public void cacheContainer(@Nonnull ContainerKey key, @Nonnull ST container) {
		cachedContainers.putIfAbsent(key, container);
	}

	/**
	 * Tries to find cached container for specified key.
	 */
	@Nullable
	public ST getCachedContainer(@Nonnull ContainerKey key) {
		return cachedContainers.get(key);
	}

	/**
	 * Removes cached container for specified key
	 */
	public void removeCachedContainer(@Nonnull ContainerKey key) {
		cachedContainers.remove(key);
	}

	/**
	 * Caches created container under specified key if absent.
	 */
	public void cacheWrapperObject(@Nonnull WrapperObjectKey key, @Nonnull ST wrapperObject) {
		cachedWrapperObjects.putIfAbsent(key, wrapperObject);
	}

	/**
	 * Tries to find cached container for specified key.
	 */
	@Nullable
	public ST getCachedWrapperObject(@Nonnull WrapperObjectKey key) {
		return cachedWrapperObjects.get(key);
	}

	/**
	 * Add new built type that will be later registered.
	 */
	public void addNewType(@Nonnull ST type) {
		builtTypes.add(type);
	}

	/**
	 * Get all built types that needs registering.
	 */
	@Nonnull
	public List<ST> getBuiltTypes() {
		return Collections.unmodifiableList(builtTypes);
	}

}
