/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.externalApi.rest.api.catalog;

import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.cdc.SystemCaptureBody;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.externalApi.rest.RestManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.concurrent.Flow.Subscription;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the schema-refresh **coalescing contract** of {@link SystemRestRefreshingObserver}:
 * per-mutation engine events ({@link ModifyCatalogSchemaMutation} / {@link SetCatalogMutabilityMutation})
 * must NOT trigger a per-mutation REST schema rebuild, while a coalesced
 * {@link HostSystemEvent.CatalogSchemaUpdated} host event must drive exactly one rebuild —
 * regardless of how many engine mutations preceded it.
 *
 * The remaining dispatch behavior of the observer (register / unregister on engine mutations,
 * route on {@link HostSystemEvent.CatalogInstalledIntoLiveView} / {@link HostSystemEvent.CatalogRemovedFromLiveView})
 * is covered end-to-end by `SystemRestStreamingFunctionalTest`, which exercises a real REST
 * server with real subscribers — those tests catch real schema-rebuild bugs that mock
 * verification cannot.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("SystemRestRefreshingObserver schema-refresh coalescing")
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(CDC)
@Tag(SCHEMA)
class SystemRestRefreshingObserverTest {

	/** Catalog name used by every fake capture in this suite. */
	private static final String CATALOG_NAME = "catalog-name";

	/** Mocked REST manager whose invocations are verified after each scenario. */
	private RestManager restManager;
	/** System under test — wired against the mocked manager. */
	private SystemRestRefreshingObserver observer;

	@BeforeEach
	void setUp() {
		this.restManager = mock(RestManager.class);
		// stub `refreshCatalog` to return `true` so the observer reaches the
		// follow-up `emitObservabilityEvents` branch (mirrors the production path
		// for an already-registered catalog being refreshed)
		when(this.restManager.refreshCatalog(any())).thenReturn(true);

		this.observer = new SystemRestRefreshingObserver(this.restManager);
		// drive `onSubscribe` with a mocked Subscription so the `subscription.request(1)`
		// callbacks issued from `onNext`'s finally block do not NPE
		final Subscription subscription = mock(Subscription.class);
		this.observer.onSubscribe(subscription);
	}

	@Test
	@DisplayName("no refresh per ModifyCatalogSchemaMutation engine mutation")
	void shouldNotRefreshCatalogPerModifyCatalogSchemaMutation() {
		// 20 schema mutations historically triggered 20 full schema rebuilds; after coalescing
		// they must trigger zero rebuilds — the host-event branch is the only refresh path.
		final ModifyCatalogSchemaMutation mutation = mock(ModifyCatalogSchemaMutation.class);
		when(mutation.getCatalogName()).thenReturn(CATALOG_NAME);

		for (int i = 0; i < 20; i++) {
			this.observer.onNext(captureOf(mutation));
		}

		verify(this.restManager, never()).refreshCatalog(any());
	}

	@Test
	@DisplayName("no refresh per SetCatalogMutabilityMutation engine mutation")
	void shouldNotRefreshCatalogPerSetCatalogMutabilityMutation() {
		// `SetCatalogMutabilityMutation` was the second per-mutation refresh trigger; like
		// `ModifyCatalogSchemaMutation` it must no longer cause a rebuild on its own.
		final SetCatalogMutabilityMutation mutation = mock(SetCatalogMutabilityMutation.class);
		when(mutation.getCatalogName()).thenReturn(CATALOG_NAME);

		for (int i = 0; i < 5; i++) {
			this.observer.onNext(captureOf(mutation));
		}

		verify(this.restManager, never()).refreshCatalog(any());
	}

	@Test
	@DisplayName("exactly one refresh per coalesced CatalogSchemaUpdated host event")
	void shouldRefreshCatalogExactlyOncePerCatalogSchemaUpdated() {
		// 20 schema mutations + 1 coalesced host event — must result in exactly one
		// refresh (the host event), regardless of how many mutations preceded it.
		final ModifyCatalogSchemaMutation mutation = mock(ModifyCatalogSchemaMutation.class);
		when(mutation.getCatalogName()).thenReturn(CATALOG_NAME);

		for (int i = 0; i < 20; i++) {
			this.observer.onNext(captureOf(mutation));
		}
		this.observer.onNext(
			captureOf(new HostSystemEvent.CatalogSchemaUpdated(CATALOG_NAME, 21, 100L))
		);

		verify(this.restManager, times(1)).refreshCatalog(CATALOG_NAME);
	}

	/**
	 * Wraps the supplied {@link SystemCaptureBody} into a synthetic {@link ChangeSystemCapture}
	 * suitable for direct injection into the observer. Version / index / timestamp are not
	 * inspected by the observer, so fixed dummy values are used.
	 *
	 * @param body the engine mutation or host event to deliver to the observer
	 * @return a capture record carrying the supplied body
	 */
	@Nonnull
	private static ChangeSystemCapture captureOf(@Nonnull SystemCaptureBody body) {
		return new ChangeSystemCapture(
			0L,
			0,
			OffsetDateTime.now(),
			Operation.UPSERT,
			body
		);
	}
}
