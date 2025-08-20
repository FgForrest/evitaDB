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

package io.evitadb.api.proxy.mock;

import io.evitadb.api.CatalogStructuralChangeObserver;
import io.evitadb.api.CatalogStructuralChangeObserverWithEvitaContractCallback;
import io.evitadb.api.EvitaContract;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This observer allows to test {@link CatalogStructuralChangeObserver} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class MockCatalogStructuralChangeObserver implements CatalogStructuralChangeObserverWithEvitaContractCallback {
	private final static Map<String, Map<String, Integer>> CATALOG_CREATED = new ConcurrentHashMap<>();
	private final static Map<String, Map<String, Integer>> CATALOG_DELETED = new ConcurrentHashMap<>();
	private final static Map<String, Map<String, Integer>> CATALOG_SCHEMA_UPDATED = new ConcurrentHashMap<>();
	private final static Map<String, Map<EntityCollectionCatalogRecord, Integer>> ENTITY_COLLECTION_CREATED = new ConcurrentHashMap<>();
	private final static Map<String, Map<EntityCollectionCatalogRecord, Integer>> ENTITY_COLLECTION_DELETED = new ConcurrentHashMap<>();
	private final static Map<String, Map<EntityCollectionCatalogRecord, Integer>> ENTITY_COLLECTION_SCHEMA_UPDATED = new ConcurrentHashMap<>();
	private String evitaInstanceId;

	public static void reset(@Nonnull String evitaInstanceId) {
		CATALOG_CREATED.remove(evitaInstanceId);
		CATALOG_DELETED.remove(evitaInstanceId);
		CATALOG_SCHEMA_UPDATED.remove(evitaInstanceId);
		ENTITY_COLLECTION_CREATED.remove(evitaInstanceId);
		ENTITY_COLLECTION_DELETED.remove(evitaInstanceId);
		ENTITY_COLLECTION_SCHEMA_UPDATED.remove(evitaInstanceId);
	}

	public static int getOrInitializeCollectionMonitor(@Nonnull String evitaInstanceId, @Nonnull String catalogName) {
		return getOrInitializeCatalogMonitor(evitaInstanceId, CATALOG_CREATED)
			.getOrDefault(catalogName, 0);
	}

	public static int getCatalogDeleted(@Nonnull String evitaInstanceId, @Nonnull String catalogName) {
		return getOrInitializeCatalogMonitor(evitaInstanceId, CATALOG_DELETED)
			.getOrDefault(catalogName, 0);
	}

	public static int getCatalogSchemaUpdated(@Nonnull String evitaInstanceId, @Nonnull String catalogName) {
		return getOrInitializeCatalogMonitor(evitaInstanceId, CATALOG_SCHEMA_UPDATED)
			.getOrDefault(catalogName, 0);
	}

	public static int getEntityCollectionCreated(@Nonnull String evitaInstanceId, @Nonnull String catalogName, @Nonnull String entityType) {
		return getOrInitializeCollectionMonitor(evitaInstanceId, ENTITY_COLLECTION_CREATED)
			.getOrDefault(new EntityCollectionCatalogRecord(catalogName, entityType), 0);
	}

	public static int getEntityCollectionDeleted(@Nonnull String evitaInstanceId, @Nonnull String catalogName, @Nonnull String entityType) {
		return getOrInitializeCollectionMonitor(evitaInstanceId, ENTITY_COLLECTION_DELETED)
			.getOrDefault(new EntityCollectionCatalogRecord(catalogName, entityType), 0);
	}

	public static int getEntityCollectionSchemaUpdated(@Nonnull String evitaInstanceId, @Nonnull String catalogName, @Nonnull String entityType) {
		return getOrInitializeCollectionMonitor(evitaInstanceId, ENTITY_COLLECTION_SCHEMA_UPDATED)
			.getOrDefault(new EntityCollectionCatalogRecord(catalogName, entityType), 0);
	}

	@Nonnull
	private static Map<String, Integer> getOrInitializeCatalogMonitor(@Nonnull String evitaInstanceId, @Nonnull Map<String, Map<String, Integer>> centralIndex) {
		return centralIndex.computeIfAbsent(
			evitaInstanceId,
			eid -> new HashMap<>(8)
		);
	}

	@Nonnull
	private static Map<EntityCollectionCatalogRecord, Integer> getOrInitializeCollectionMonitor(@Nonnull String evitaInstanceId, @Nonnull Map<String, Map<EntityCollectionCatalogRecord, Integer>> centralIndex) {
		return centralIndex.computeIfAbsent(
			evitaInstanceId,
			eid -> new HashMap<>(8)
		);
	}

	@Override
	public void onInit(@Nonnull EvitaContract evita) {
		this.evitaInstanceId = evita.management().getSystemStatus().instanceId();
	}

	@Override
	public void onCatalogCreate(@Nonnull String catalogName) {
		getOrInitializeCatalogMonitor(evitaInstanceId, CATALOG_CREATED)
			.compute(catalogName, (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
	}

	@Override
	public void onCatalogDelete(@Nonnull String catalogName) {
		getOrInitializeCatalogMonitor(evitaInstanceId, CATALOG_DELETED)
			.compute(catalogName, (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
	}

	@Override
	public void onEntityCollectionCreate(@Nonnull String catalogName, @Nonnull String entityType) {
		getOrInitializeCollectionMonitor(evitaInstanceId, ENTITY_COLLECTION_CREATED)
			.compute(
				new EntityCollectionCatalogRecord(catalogName, entityType),
				(entityCollectionRecord, counter) -> counter == null ? 1 : counter + 1
			);
	}

	@Override
	public void onEntityCollectionDelete(@Nonnull String catalogName, @Nonnull String entityType) {
		getOrInitializeCollectionMonitor(evitaInstanceId, ENTITY_COLLECTION_DELETED)
			.compute(
				new EntityCollectionCatalogRecord(catalogName, entityType),
				(entityCollectionRecord, counter) -> counter == null ? 1 : counter + 1
			);
	}

	@Override
	public void onCatalogSchemaUpdate(@Nonnull String catalogName) {
		getOrInitializeCatalogMonitor(evitaInstanceId, CATALOG_SCHEMA_UPDATED)
			.compute(catalogName, (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
	}

	@Override
	public void onEntitySchemaUpdate(@Nonnull String catalogName, @Nonnull String entityType) {
		getOrInitializeCollectionMonitor(evitaInstanceId, ENTITY_COLLECTION_SCHEMA_UPDATED)
			.compute(
				new EntityCollectionCatalogRecord(catalogName, entityType),
				(entityCollectionRecord, counter) -> counter == null ? 1 : counter + 1
			);
	}

	private record EntityCollectionCatalogRecord(@Nonnull String catalogName, @Nonnull String entityType) {
	}

}
