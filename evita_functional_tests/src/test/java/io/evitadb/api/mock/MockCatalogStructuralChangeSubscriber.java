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

import io.evitadb.api.requestResponse.cdc.ChangeDataCaptureObserver;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureSubscriber;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

/**
 * This observer allows to test {@link Subscriber} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class MockCatalogStructuralChangeSubscriber implements Subscriber<ChangeSystemCapture> {

	private final int initialRequestCount;
	private Subscription subscription;

	private final Map<String, Integer> catalogCreated = new HashMap<>();
	private final Map<String, Integer> catalogUpdated = new HashMap<>();
	private final Map<String, Integer> catalogDeleted = new HashMap<>();
	private final Map<String, Integer> catalogSchemaUpdated = new HashMap<>();
	private final Map<EntityCollectionCatalogRecord, Integer> entityCollectionCreated = new HashMap<>();
	private final Map<EntityCollectionCatalogRecord, Integer> entityCollectionUpdated = new HashMap<>();
	private final Map<EntityCollectionCatalogRecord, Integer> entityCollectionDeleted = new HashMap<>();
	@Getter private int completed = 0;
	@Getter private int errors = 0;

	public MockCatalogStructuralChangeSubscriber() {
		this(Integer.MAX_VALUE);
	}

	public MockCatalogStructuralChangeSubscriber(int initialRequestCount) {
		this.initialRequestCount = initialRequestCount;
	}

	public void reset() {
		catalogCreated.clear();
		catalogUpdated.clear();
		catalogDeleted.clear();
		catalogSchemaUpdated.clear();
		entityCollectionCreated.clear();
		entityCollectionUpdated.clear();
		entityCollectionDeleted.clear();
		entityCollectionUpdated.clear();
	}

	public int getCatalogCreated(@Nonnull String catalogName) {
		return catalogCreated.getOrDefault(catalogName, 0);
	}

	public int getCatalogDeleted(@Nonnull String catalogName) {
		return catalogDeleted.getOrDefault(catalogName, 0);
	}

	public int getCatalogSchemaUpdated(@Nonnull String catalogName) {
		return catalogSchemaUpdated.getOrDefault(catalogName, 0);
	}

	public int getEntityCollectionCreated(@Nonnull String catalogName, @Nonnull String entityType) {
		return entityCollectionCreated.getOrDefault(new EntityCollectionCatalogRecord(catalogName, entityType), 0);
	}

	public int getEntityCollectionUpdated(@Nonnull String catalogName, @Nonnull String entityType) {
		return entityCollectionUpdated.getOrDefault(new EntityCollectionCatalogRecord(catalogName, entityType), 0);
	}

	public int getEntityCollectionDeleted(@Nonnull String catalogName, @Nonnull String entityType) {
		return entityCollectionDeleted.getOrDefault(new EntityCollectionCatalogRecord(catalogName, entityType), 0);
	}

	public int getEntityCollectionSchemaUpdated(@Nonnull String catalogName, @Nonnull String entityType) {
		return entityCollectionUpdated.getOrDefault(new EntityCollectionCatalogRecord(catalogName, entityType), 0);
	}

	@Override
	public void onSubscribe(Subscription subscription) {
		this.subscription = subscription;
		this.subscription.request(initialRequestCount);
	}

	@Override
	public void onNext(ChangeSystemCapture item) {
		switch (item.operation()) {
			case CREATE -> catalogCreated
				.compute(item.catalog(), (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
			case UPDATE -> catalogUpdated
				.compute(item.catalog(), (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
			case REMOVE -> catalogDeleted
				.compute(item.catalog(), (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
		}
	}

	@Override
	public void onError(Throwable throwable) {
		errors++;
		throw new RuntimeException(throwable);
	}

	@Override
	public void onComplete() {
		completed++;
	}

	public void request(long n) {
		subscription.request(n);
	}

	public void cancel() {
		subscription.cancel();
	}

	private record EntityCollectionCatalogRecord(@Nonnull String catalogName, @Nonnull String entityType) {
	}

}
