/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.functional.storage;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.SESSION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Reproduction of a close-time flush hang triggered by a throwing `WARM_UP` session flush.
 *
 * When a `WARM_UP` session's close-time flush throws, the flush future is built and popped SYNCHRONOUSLY inside
 * {@link Catalog#flush()} (via {@link EntityCollection#createFlushFuture()} →
 * {@code dataStoreBuffer.popTrappedChanges()}). The throw therefore escapes {@code EvitaSession.closeInternal}
 * before it can complete `commitProgress` / `closingSequenceFuture`, leaving them pending forever. A subsequent
 * close (e.g. {@code Evita.close()} → {@code SessionRegistry.closeAllActiveSessionsAndSuspend}, whose
 * {@code allOf().join()} and {@code .exceptionally(ex -> null)} guard can only help futures that COMPLETE) then
 * hangs the whole engine.
 *
 * The fix must complete the session close future EXCEPTIONALLY on any close-time flush throw so the close
 * terminates in bounded time. This test injects a plain {@link RuntimeException} into the collection flush (no
 * index corruption needed) and asserts the close surfaces the failure and {@code Evita.close()} still returns.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(SESSION)
@DisplayName("Warm-up close-time flush failure must not hang the session close")
class WarmUpFlushFailureCloseTest implements EvitaTestSupport {

	private static final String ATTRIBUTE_CODE = "code";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("WarmUpFlushFailureClose");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	@AfterEach
	@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
	@DisplayName("a warm-up session whose close-time flush throws fails the close and never hangs Evita.close()")
	void shouldFailCloseAndNotHangWhenWarmUpFlushThrows() {
		this.evita.defineCatalog(TEST_CATALOG);
		final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG);
		session.defineEntitySchema(Entities.PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_CODE, String.class)
			.updateVia(session);
		session.upsertEntity(
			session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_CODE, "the-product")
		);

		// make the close-time warm-up flush of the PRODUCT collection throw a plain RuntimeException at the exact
		// point the production incident threw: synchronously, inside popTrappedChanges
		injectFlushFailure(Entities.PRODUCT);

		// the close-time flush throws: the close MUST surface the failure (an exceptionally-completed close
		// future or a synchronous throw - both bounded), NEVER leave commitProgress / closingSequenceFuture
		// pending forever
		assertThrows(
			RuntimeException.class,
			() -> session.closeNow(CommitBehavior.WAIT_FOR_CHANGES_VISIBLE).toCompletableFuture().join(),
			"Closing the warm-up session whose flush throws must surface the failure, not swallow it"
		);

		// the decisive assertion: the whole engine must still shut down in bounded time. Before the fix the
		// never-completed close future hangs SessionRegistry.closeAllActiveSessionsAndSuspend -> Evita.close()
		// forever (this method returning under the @Timeout is the pass condition)
		this.evita.close();
	}

	/**
	 * Replaces the {@link DataStoreMemoryBuffer} of the given entity collection with a proxy that throws a plain
	 * {@link RuntimeException} from {@code popTrappedChanges()} — the synchronous step {@link Catalog#flush()}
	 * runs while building the close-time flush future — and delegates every other call to the real buffer.
	 *
	 * @param entityType the entity collection whose flush should be made to throw
	 */
	private void injectFlushFailure(@Nonnull String entityType) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection =
			(EntityCollection) catalog.getCollectionForEntity(entityType).orElseThrow();
		try {
			final Field field = EntityCollection.class.getDeclaredField("dataStoreBuffer");
			field.setAccessible(true);
			final DataStoreMemoryBuffer real = (DataStoreMemoryBuffer) field.get(collection);
			final DataStoreMemoryBuffer poisoned = (DataStoreMemoryBuffer) Proxy.newProxyInstance(
				DataStoreMemoryBuffer.class.getClassLoader(),
				new Class<?>[]{DataStoreMemoryBuffer.class},
				(proxy, method, args) -> {
					if ("popTrappedChanges".equals(method.getName())) {
						throw new IllegalStateException("Injected warm-up flush failure (close-time flush hang)");
					}
					try {
						return method.invoke(real, args);
					} catch (InvocationTargetException ex) {
						throw ex.getCause();
					}
				}
			);
			field.set(collection, poisoned);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Failed to inject the flush failure for `" + entityType + "`", ex);
		}
	}
}
