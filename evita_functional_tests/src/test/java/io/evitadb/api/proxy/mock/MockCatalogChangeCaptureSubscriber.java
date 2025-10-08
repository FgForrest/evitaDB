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

import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutation;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * This observer allows to test {@link Subscriber} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class MockCatalogChangeCaptureSubscriber implements Subscriber<ChangeCatalogCapture> {
	private final int initialRequestCount;
	private Subscription subscription;

	private final Map<String, Integer> entityCollectionCreated = new HashMap<>();
	private final Map<String, Integer> entityCollectionUpdated = new HashMap<>();
	private final Map<String, Integer> entityCollectionDeleted = new HashMap<>();
	private final Map<String, Integer> entityCollectionSchemaUpdated = new HashMap<>();
	@Getter private int received = 0;
	@Getter private int completed = 0;
	@Getter private int errors = 0;
	private CompletableFuture<Void> future;
	private BooleanSupplier waitCondition;

	public MockCatalogChangeCaptureSubscriber() {
		this(Integer.MAX_VALUE);
	}

	public MockCatalogChangeCaptureSubscriber(int initialRequestCount) {
		this.initialRequestCount = initialRequestCount;
	}

	public void reset() {
		this.entityCollectionCreated.clear();
		this.entityCollectionUpdated.clear();
		this.entityCollectionDeleted.clear();
		this.entityCollectionSchemaUpdated.clear();
	}

	public int getEntityCollectionCreated(@Nonnull String entityType) {
		return this.entityCollectionCreated.getOrDefault(entityType, 0);
	}

	public int getEntityCollectionCreated(@Nonnull String entityType, int timeout, @Nonnull TimeUnit timeUnit, int expectedValue) {
		final int result = this.entityCollectionCreated.getOrDefault(entityType, 0);
		if (result < expectedValue) {
			try {
				this.future = new CompletableFuture<>();
				this.waitCondition = () -> this.entityCollectionCreated.getOrDefault(entityType, 0) >= expectedValue;
				this.future.get(timeout, timeUnit);
				return this.entityCollectionCreated.getOrDefault(entityType, 0);
			} catch (Exception e) {
				return this.entityCollectionCreated.getOrDefault(entityType, 0);
			}
		} else {
			return result;
		}
	}

	public int getEntityCollectionUpdated(@Nonnull String entityType) {
		return this.entityCollectionUpdated.getOrDefault(entityType, 0);
	}

	public int getEntityCollectionUpdated(@Nonnull String entityType, int timeout, @Nonnull TimeUnit timeUnit, int expectedValue) {
		final int result = this.entityCollectionUpdated.getOrDefault(entityType, 0);
		if (result < expectedValue) {
			try {
				this.future = new CompletableFuture<>();
				this.waitCondition = () -> this.entityCollectionUpdated.getOrDefault(entityType, 0) >= expectedValue;
				this.future.get(timeout, timeUnit);
				return this.entityCollectionUpdated.getOrDefault(entityType, 0);
			} catch (Exception e) {
				return this.entityCollectionUpdated.getOrDefault(entityType, 0);
			}
		} else {
			return result;
		}
	}

	public int getEntityCollectionDeleted(@Nonnull String entityType) {
		return this.entityCollectionDeleted.getOrDefault(entityType, 0);
	}

	public int getEntityCollectionDeleted(@Nonnull String entityType, int timeout, @Nonnull TimeUnit timeUnit, int expectedValue) {
		final int result = this.entityCollectionDeleted.getOrDefault(entityType, 0);
		if (result < expectedValue) {
			try {
				this.future = new CompletableFuture<>();
				this.waitCondition = () -> this.entityCollectionDeleted.getOrDefault(entityType, 0) >= expectedValue;
				this.future.get(timeout, timeUnit);
				return this.entityCollectionDeleted.getOrDefault(entityType, 0);
			} catch (Exception e) {
				return this.entityCollectionDeleted.getOrDefault(entityType, 0);
			}
		} else {
			return result;
		}
	}

	public int getEntityCollectionSchemaUpdated(@Nonnull String entityType) {
		return this.entityCollectionSchemaUpdated.getOrDefault(entityType, 0);
	}

	public int getEntityCollectionSchemaUpdated(@Nonnull String entityType, int timeout, @Nonnull TimeUnit timeUnit, int expectedValue) {
		final int result = this.entityCollectionSchemaUpdated.getOrDefault(entityType, 0);
		if (result < expectedValue) {
			try {
				this.future = new CompletableFuture<>();
				this.waitCondition = () -> this.entityCollectionSchemaUpdated.getOrDefault(entityType, 0) >= expectedValue;
				this.future.get(timeout, timeUnit);
				return this.entityCollectionSchemaUpdated.getOrDefault(entityType, 0);
			} catch (Exception e) {
				return this.entityCollectionSchemaUpdated.getOrDefault(entityType, 0);
			}
		} else {
			return result;
		}
	}

	@Override
	public void onSubscribe(Subscription subscription) {
		this.subscription = subscription;
		if (this.initialRequestCount > 0) {
			this.subscription.request(this.initialRequestCount);
		}
	}

	@Override
	public void onNext(ChangeCatalogCapture item) {
		this.received++;
		if (item.body() instanceof CreateEntitySchemaMutation) {
			this.entityCollectionCreated
				.compute(item.entityType(), (entityType, counter) -> counter == null ? 1 : counter + 1);
		} else if (item.body() instanceof RemoveEntitySchemaMutation) {
			this.entityCollectionDeleted
				.compute(item.entityType(), (entityType, counter) -> counter == null ? 1 : counter + 1);
		} else if (item.body() instanceof ModifyEntitySchemaNameMutation mcsnm) {
			this.entityCollectionDeleted
				.compute(mcsnm.getName(), (entityType, counter) -> counter == null ? 1 : counter + 1);
			if (mcsnm.isOverwriteTarget()) {
				this.entityCollectionSchemaUpdated
					.compute(mcsnm.getNewName(), (entityType, counter) -> counter == null ? 1 : counter + 1);
			} else {
				this.entityCollectionCreated
					.compute(mcsnm.getNewName(), (entityType, counter) -> counter == null ? 1 : counter + 1);
			}
		} else if (item.body() instanceof ModifyEntitySchemaMutation) {
			this.entityCollectionSchemaUpdated
				.compute(item.entityType(), (entityType, counter) -> counter == null ? 1 : counter + 1);
		} else if (item.area() == CaptureArea.DATA) {
			this.entityCollectionUpdated
				.compute(item.entityType(), (entityType, counter) -> counter == null ? 1 : counter + 1);
		}

		// check if we are waiting for some condition to be met
		if (this.waitCondition != null && this.waitCondition.getAsBoolean()) {
			this.future.complete(null);
			this.waitCondition = null;
			this.future = null;
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

}
