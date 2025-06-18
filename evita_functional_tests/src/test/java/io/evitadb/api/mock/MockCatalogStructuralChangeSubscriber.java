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

package io.evitadb.api.mock;

import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

/**
 * This observer allows to test {@link Subscriber} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class MockCatalogStructuralChangeSubscriber implements Subscriber<ChangeSystemCapture> {

	@Getter
	private final CountDownLatch countDownLatch;
	private final int initialRequestCount;
	private Subscription subscription;

	private final Map<String, Integer> catalogUpserted = new HashMap<>();
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
		this(null, initialRequestCount);
	}

	public MockCatalogStructuralChangeSubscriber(@Nonnull CountDownLatch countDownLatch, int initialRequestCount) {
		this.countDownLatch = countDownLatch;
		this.initialRequestCount = initialRequestCount;
	}

	public void reset() {
		this.catalogUpserted.clear();
		this.catalogDeleted.clear();
		this.catalogSchemaUpdated.clear();
		this.entityCollectionCreated.clear();
		this.entityCollectionUpdated.clear();
		this.entityCollectionDeleted.clear();
		this.entityCollectionUpdated.clear();
	}

	public int getCatalogUpserted(@Nonnull String catalogName) {
		return this.catalogUpserted.getOrDefault(catalogName, 0);
	}

	public int getCatalogDeleted(@Nonnull String catalogName) {
		return this.catalogDeleted.getOrDefault(catalogName, 0);
	}

	public int getCatalogSchemaUpdated(@Nonnull String catalogName) {
		return this.catalogSchemaUpdated.getOrDefault(catalogName, 0);
	}

	public int getEntityCollectionCreated(@Nonnull String catalogName, @Nonnull String entityType) {
		return this.entityCollectionCreated.getOrDefault(new EntityCollectionCatalogRecord(catalogName, entityType), 0);
	}

	public int getEntityCollectionUpdated(@Nonnull String catalogName, @Nonnull String entityType) {
		return this.entityCollectionUpdated.getOrDefault(new EntityCollectionCatalogRecord(catalogName, entityType), 0);
	}

	public int getEntityCollectionDeleted(@Nonnull String catalogName, @Nonnull String entityType) {
		return this.entityCollectionDeleted.getOrDefault(new EntityCollectionCatalogRecord(catalogName, entityType), 0);
	}

	public int getEntityCollectionSchemaUpdated(@Nonnull String catalogName, @Nonnull String entityType) {
		return this.entityCollectionUpdated.getOrDefault(new EntityCollectionCatalogRecord(catalogName, entityType), 0);
	}

	@Override
	public void onSubscribe(Subscription subscription) {
		this.subscription = subscription;
		if (this.initialRequestCount > 0) {
			this.subscription.request(this.initialRequestCount);
		}
	}

	@Override
	public void onNext(ChangeSystemCapture item) {
		if (item.body() instanceof CreateCatalogSchemaMutation ccsm) {
			this.catalogUpserted
				.compute(ccsm.getCatalogName(), (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
		} else if (item.body() instanceof RemoveCatalogSchemaMutation rccs) {
			this.catalogDeleted
				.compute(rccs.getCatalogName(), (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
		} else if (item.body() instanceof ModifyCatalogSchemaNameMutation mcsnm) {
			this.catalogDeleted
				.compute(mcsnm.getCatalogName(), (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
			this.catalogUpserted
				.compute(mcsnm.getNewCatalogName(), (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
		} else if (item.body() instanceof ModifyCatalogSchemaMutation mcsm) {
			this.catalogUpserted
				.compute(mcsm.getCatalogName(), (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
		}

		if (this.countDownLatch != null) {
			this.countDownLatch.countDown();
		}
	}

	@Override
	public void onError(Throwable throwable) {
		this.errors++;
		throw new RuntimeException(throwable);
	}

	@Override
	public void onComplete() {
		this.completed++;
	}

	public void request(long n) {
		this.subscription.request(n);
	}

	public void cancel() {
		this.subscription.cancel();
	}

	private record EntityCollectionCatalogRecord(@Nonnull String catalogName, @Nonnull String entityType) {
	}

}
