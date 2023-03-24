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

package io.evitadb.api.mock;

import io.evitadb.api.CatalogStructuralChangeObserver;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * This observer allows to test {@link CatalogStructuralChangeObserver} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class MockCatalogStructuralChangeObserver implements CatalogStructuralChangeObserver {
	private final static ThreadLocal<Map<String, Integer>> CATALOG_CREATED = new ThreadLocal<>();
	private final static ThreadLocal<Map<String, Integer>> CATALOG_DELETED = new ThreadLocal<>();
	private final static ThreadLocal<Map<String, Integer>> CATALOG_SCHEMA_UPDATED = new ThreadLocal<>();
	private final static ThreadLocal<Map<EntityCollectionCatalogRecord, Integer>> ENTITY_COLLECTION_CREATED = new ThreadLocal<>();
	private final static ThreadLocal<Map<EntityCollectionCatalogRecord, Integer>> ENTITY_COLLECTION_DELETED = new ThreadLocal<>();
	private final static ThreadLocal<Map<EntityCollectionCatalogRecord, Integer>> ENTITY_COLLECTION_SCHEMA_UPDATED = new ThreadLocal<>();

	public static void reset() {
		CATALOG_CREATED.remove();
		CATALOG_DELETED.remove();
		CATALOG_SCHEMA_UPDATED.remove();
		ENTITY_COLLECTION_CREATED.remove();
		ENTITY_COLLECTION_DELETED.remove();
		ENTITY_COLLECTION_SCHEMA_UPDATED.remove();
	}

	public static int getOrInitializeCollectionMonitor(@Nonnull String catalogName) {
		return getOrInitializeCatalogMonitor(CATALOG_CREATED)
			.getOrDefault(catalogName, 0);
	}

	public static int getCatalogDeleted(@Nonnull String catalogName) {
		return getOrInitializeCatalogMonitor(CATALOG_DELETED)
			.getOrDefault(catalogName, 0);
	}

	public static int getCatalogSchemaUpdated(@Nonnull String catalogName) {
		return getOrInitializeCatalogMonitor(CATALOG_SCHEMA_UPDATED)
			.getOrDefault(catalogName, 0);
	}

	public static int getEntityCollectionCreated(@Nonnull String catalogName, @Nonnull String entityType) {
		return getOrInitializeCollectionMonitor(ENTITY_COLLECTION_CREATED)
			.getOrDefault(new EntityCollectionCatalogRecord(catalogName, entityType), 0);
	}

	public static int getEntityCollectionDeleted(@Nonnull String catalogName, @Nonnull String entityType) {
		return getOrInitializeCollectionMonitor(ENTITY_COLLECTION_DELETED)
			.getOrDefault(new EntityCollectionCatalogRecord(catalogName, entityType), 0);
	}

	public static int getEntityCollectionSchemaUpdated(@Nonnull String catalogName, @Nonnull String entityType) {
		return getOrInitializeCollectionMonitor(ENTITY_COLLECTION_SCHEMA_UPDATED)
			.getOrDefault(new EntityCollectionCatalogRecord(catalogName, entityType), 0);
	}

	@Nonnull
	private static Map<String, Integer> getOrInitializeCatalogMonitor(ThreadLocal<Map<String, Integer>> threadLocal) {
		return Optional.ofNullable(threadLocal.get())
			.orElseGet(() -> {
				final HashMap<String, Integer> theMap = new HashMap<>();
				threadLocal.set(theMap);
				return theMap;
			});
	}

	@Nonnull
	private static Map<EntityCollectionCatalogRecord, Integer> getOrInitializeCollectionMonitor(ThreadLocal<Map<EntityCollectionCatalogRecord, Integer>> threadLocal) {
		return Optional.ofNullable(threadLocal.get())
			.orElseGet(() -> {
				final HashMap<EntityCollectionCatalogRecord, Integer> theMap = new HashMap<>();
				threadLocal.set(theMap);
				return theMap;
			});
	}

	@Override
	public void onCatalogCreate(@Nonnull String catalogName) {
		getOrInitializeCatalogMonitor(CATALOG_CREATED)
			.compute(catalogName, (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
	}

	@Override
	public void onCatalogDelete(@Nonnull String catalogName) {
		getOrInitializeCatalogMonitor(CATALOG_DELETED)
			.compute(catalogName, (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
	}

	@Override
	public void onEntityCollectionCreate(@Nonnull String catalogName, @Nonnull String entityType) {
		getOrInitializeCollectionMonitor(ENTITY_COLLECTION_CREATED)
			.compute(
				new EntityCollectionCatalogRecord(catalogName, entityType),
				(entityCollectionRecord, counter) -> counter == null ? 1 : counter + 1
			);
	}

	@Override
	public void onEntityCollectionDelete(@Nonnull String catalogName, @Nonnull String entityType) {
		getOrInitializeCollectionMonitor(ENTITY_COLLECTION_DELETED)
			.compute(
				new EntityCollectionCatalogRecord(catalogName, entityType),
				(entityCollectionRecord, counter) -> counter == null ? 1 : counter + 1
			);
	}

	@Override
	public void onCatalogSchemaUpdate(@Nonnull String catalogName) {
		getOrInitializeCatalogMonitor(CATALOG_SCHEMA_UPDATED)
			.compute(catalogName, (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
	}

	@Override
	public void onEntitySchemaUpdate(@Nonnull String catalogName, @Nonnull String entityType) {
		getOrInitializeCollectionMonitor(ENTITY_COLLECTION_SCHEMA_UPDATED)
			.compute(
				new EntityCollectionCatalogRecord(catalogName, entityType),
				(entityCollectionRecord, counter) -> counter == null ? 1 : counter + 1
			);
	}

	private record EntityCollectionCatalogRecord(@Nonnull String catalogName, @Nonnull String entityType) {
	}

}
