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
public class MockEngineChangeCaptureSubscriber implements Subscriber<ChangeSystemCapture>, AutoCloseable {
	private final int initialRequestCount;
	private final Map<String, Integer> catalogCreated = new HashMap<>();
	private final Map<String, Integer> catalogDeleted = new HashMap<>();
	private final Map<String, Integer> catalogUpdated = new HashMap<>();
	private Subscription subscription;
	@Getter private int received = 0;
	@Getter private int completed = 0;
	@Getter private int errors = 0;
	private CompletableFuture<Void> future;
	private BooleanSupplier waitCondition;

	public MockEngineChangeCaptureSubscriber() {
		this(Integer.MAX_VALUE);
	}

	public MockEngineChangeCaptureSubscriber(int initialRequestCount) {
		this.initialRequestCount = initialRequestCount;
	}

	public void reset() {
		this.catalogCreated.clear();
		this.catalogDeleted.clear();
		this.catalogUpdated.clear();
	}

	public int getCatalogCreated(@Nonnull String catalogName) {
		return this.catalogCreated.getOrDefault(catalogName, 0);
	}

	public int getCatalogCreated(
		@Nonnull String catalogName, int timeout, @Nonnull TimeUnit timeUnit, int expectedValue
	) {
		final int result = this.catalogCreated.getOrDefault(catalogName, 0);
		if (result < expectedValue) {
			try {
				this.future = new CompletableFuture<>();
				this.waitCondition = () -> this.catalogCreated.getOrDefault(catalogName, 0) >= expectedValue;
				this.future.get(timeout, timeUnit);
				return this.catalogCreated.getOrDefault(catalogName, 0);
			} catch (Exception e) {
				return this.catalogCreated.getOrDefault(catalogName, 0);
			}
		} else {
			return result;
		}
	}

	public int getCatalogDeleted(@Nonnull String catalogName) {
		return this.catalogDeleted.getOrDefault(catalogName, 0);
	}

	public int getCatalogDeleted(
		@Nonnull String catalogName, int timeout, @Nonnull TimeUnit timeUnit, int expectedValue) {
		final int result = this.catalogDeleted.getOrDefault(catalogName, 0);
		if (result < expectedValue) {
			try {
				this.future = new CompletableFuture<>();
				this.waitCondition = () -> this.catalogDeleted.getOrDefault(catalogName, 0) >= expectedValue;
				this.future.get(timeout, timeUnit);
				return this.catalogDeleted.getOrDefault(catalogName, 0);
			} catch (Exception e) {
				return this.catalogDeleted.getOrDefault(catalogName, 0);
			}
		} else {
			return result;
		}
	}

	public int getCatalogUpdated(@Nonnull String catalogName) {
		return this.catalogUpdated.getOrDefault(catalogName, 0);
	}

	public int getCatalogUpdated(
		@Nonnull String catalogName, int timeout, @Nonnull TimeUnit timeUnit,
		int expectedValue
	) {
		final int result = this.catalogUpdated.getOrDefault(catalogName, 0);
		if (result < expectedValue) {
			try {
				this.future = new CompletableFuture<>();
				this.waitCondition = () -> this.catalogUpdated.getOrDefault(catalogName, 0) >= expectedValue;
				this.future.get(timeout, timeUnit);
				return this.catalogUpdated.getOrDefault(catalogName, 0);
			} catch (Exception e) {
				return this.catalogUpdated.getOrDefault(catalogName, 0);
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
	public void onNext(ChangeSystemCapture item) {
		this.received++;
		if (item.body() instanceof CreateCatalogSchemaMutation ccsm) {
			this.catalogCreated
				.compute(ccsm.getCatalogName(), (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
		} else if (item.body() instanceof RemoveCatalogSchemaMutation rccs) {
			this.catalogDeleted
				.compute(rccs.getCatalogName(), (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
		} else if (item.body() instanceof ModifyCatalogSchemaNameMutation mcsnm) {
			this.catalogDeleted
				.compute(mcsnm.getCatalogName(), (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
			if (mcsnm.isOverwriteTarget()) {
				this.catalogUpdated
					.compute(mcsnm.getNewCatalogName(), (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
			} else {
				this.catalogCreated
					.compute(mcsnm.getNewCatalogName(), (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
			}
		} else if (item.body() instanceof ModifyCatalogSchemaMutation mcsm) {
			this.catalogUpdated
				.compute(mcsm.getCatalogName(), (theCatalogName, counter) -> counter == null ? 1 : counter + 1);
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

	@Override
	public void close() {
		this.subscription.cancel();
	}
}
