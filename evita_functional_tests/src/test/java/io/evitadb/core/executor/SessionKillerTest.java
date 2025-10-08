/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.core.executor;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.core.task.SessionKiller;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static graphql.Assert.assertFalse;
import static graphql.Assert.assertNotNull;
import static graphql.Assert.assertTrue;
import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;

/**
 * This test verifies the correct functionality of the {@link SessionKiller} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("Session killer functionality")
@Tag(LONG_RUNNING_TEST)
class SessionKillerTest implements EvitaTestSupport {
	public static final String SUB_DIRECTORY = "SessionKillerTest";
	public static final String SUB_DIRECTORY_EXPORT = "SessionKillerTest_export";
	private Evita evita;
	private SessionKiller sessionKiller;

	@BeforeEach
	void setUp() throws IOException, NoSuchFieldException, IllegalAccessException {
		cleanTestSubDirectory(SUB_DIRECTORY);
		cleanTestSubDirectory(SUB_DIRECTORY_EXPORT);
		this.evita = new Evita(
			EvitaConfiguration.builder()
				.storage(
					StorageOptions.builder()
						.storageDirectory(getTestDirectory().resolve(SUB_DIRECTORY))
						.exportDirectory(getTestDirectory().resolve(SUB_DIRECTORY_EXPORT))
						.build()
				)
				.server(
					ServerOptions.builder()
						.closeSessionsAfterSecondsOfInactivity(1)
						.build()
				)
				.build()
		);
		final Field sessionKillerField = Evita.class.getDeclaredField("sessionKiller");
		sessionKillerField.setAccessible(true);
		this.sessionKiller = (SessionKiller) sessionKillerField.get(this.evita);
	}

	@AfterEach
	void tearDown() throws IOException {
		this.evita.close();
		cleanTestSubDirectory(SUB_DIRECTORY);
		cleanTestSubDirectory(SUB_DIRECTORY_EXPORT);
	}

	@Test
	void shouldKillSessionAfterIntervalOfInactivity() throws InterruptedException {
		this.evita.defineCatalog("test");
		final EvitaSessionContract session = this.evita.createReadOnlySession("test");
		synchronized (this.evita) {
			this.evita.wait(2000);
		}
		this.sessionKiller.run();
		assertFalse(session.isActive());
	}

	@Test
	void shouldNotKillSessionWhenThereAreInvocations() {
		this.evita.defineCatalog("test");
		final EvitaSessionContract session = this.evita.createReadOnlySession("test");
		final long start = System.currentTimeMillis();
		while (System.currentTimeMillis() - start < 2000) {
			assertNotNull(session.getCatalogName());
			Thread.onSpinWait();
		}
		this.sessionKiller.run();
		assertTrue(session.isActive());
	}

	@Test
	void shouldNotKillSessionWhenThereIsLongLastingInvocationCallActive() throws InterruptedException {
		final AtomicBoolean finishMethodCall = new AtomicBoolean(false);
		try {
			this.evita.defineCatalog("test");
			final EvitaSessionContract session = this.evita.createReadOnlySession("test");
			final Runnable asyncCall = () -> {
				final Query query = Mockito.mock(Query.class);
				Mockito.when(query.normalizeQuery()).thenAnswer(invocation -> {
					do {
						Thread.onSpinWait();
					} while (!finishMethodCall.get());
					return Query.query(QueryConstraints.collection("unknownEntity"));
				});
				try {
					session.query(
						query, EntityReference.class
					);
				} catch (CollectionNotFoundException e) {
					// expected
					System.out.println("Async call finished");
				}
			};
			final CompletableFuture<Void> future = CompletableFuture.runAsync(asyncCall);
			synchronized (this.evita) {
				this.evita.wait(2000);
			}

			this.sessionKiller.run();

			System.out.println("Finishing async call");
			finishMethodCall.set(true);
			future.join();

			assertTrue(session.isActive());

			System.out.println("Waiting for session killer to finish");
			synchronized (this.evita) {
				this.evita.wait(2000);
			}

			this.sessionKiller.run();
			assertFalse(session.isActive());
		} finally {
			finishMethodCall.set(true);
		}
	}
}