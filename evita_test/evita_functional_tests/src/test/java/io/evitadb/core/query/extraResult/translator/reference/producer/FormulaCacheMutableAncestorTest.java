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

package io.evitadb.core.query.extraResult.translator.reference.producer;

import com.github.benmanes.caffeine.cache.Ticker;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.FormulaCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupOrFormula;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves that the real facet delegate replacer unbinds ancestors whose memoized keys remain stale.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula cache mutable ancestors")
@Tag(ENGINE)
@Tag(CACHE)
@Tag(FACET)
class FormulaCacheMutableAncestorTest implements EvitaTestSupport {
	/** Exercises the actual replacement visitor, not an imitation of its memory-clearing behavior. */
	@Test
	@DisplayName("Neither reads nor writes the tier after replacing a mutable delegate")
	void shouldUnbindStaleAncestorsAfterReplacement() {
		final TestPaths paths = createTestPaths("FormulaCacheMutableAncestorTest");
		final Scheduler scheduler = new Scheduler(new ScheduledThreadPoolExecutor(1, runnable -> {
			final Thread thread = new Thread(runnable, "mutable-cache-test");
			thread.setDaemon(true);
			return thread;
		}));
		try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build());
			final FormulaCacheSupervisor cache = new FormulaCacheSupervisor(CacheOptions.builder().enabled(true)
				.minimalComplexityThreshold(0).cacheSizeInBytes(100_000_000L).verifyOneHitIn(0)
				.dropEntriesAfterSecondsOfInactivity(0).build(), scheduler, Ticker.systemTicker())) {
			evita.defineCatalog("mutableCache").updateViaNewSession(evita);
			evita.updateCatalog("mutableCache", session -> {
				session.defineEntitySchema("product").withoutGeneratedPrimaryKey().updateVia(session);
				session.upsertEntity(session.createNewEntity("product", 1));
				session.goLiveAndClose();
			});
			try (final EvitaSessionContract session = io.evitadb.core.cache.FormulaCacheTestSessions.readOnly(
				evita, "mutableCache")) {
				{
					final EvitaSessionContract internal = session;
					final Catalog catalog = (Catalog) evita.getCatalogInstanceOrThrowException("mutableCache");
					final QueryPlanningContext context = new QueryPlanningContext(catalog,
						catalog.getCollectionForEntityInternal("product").orElseThrow(), internal,
						new EvitaRequest(Query.query(collection("product")), OffsetDateTime.now(),
							EntityReference.class, null), Map.of(), Map.of(), cache);
					final MutableFormula mutable = new MutableFormula(new FacetGroupOrFormula("facet", 1,
						new BaseBitmap(1), new BaseBitmap(1)));
					final OrFormula ancestor = new OrFormula(mutable, new ConstantFormula(new BaseBitmap(2)));
					assertThrows(GenericEvitaInternalError.class, () -> context.analyse(ancestor));
					ancestor.initialize(context.createExecutionContext());
					// Seed the pre-replacement binding explicitly; the normal walk refuses hypothetical graphs.
					ancestor.bindFormulaCache(cache);
					assertArrayEquals(new int[]{1, 2}, ancestor.compute().getArray());
					final FormulaKey stale = ancestor.getKey();
					final FormulaCacheSupervisor.Statistics before = cache.getStatistics();
					ancestor.accept(new AbstractFacetFormulaGenerator.MutableFormulaFinderAndReplacer(
						ignored -> new FacetGroupOrFormula("facet", 1, new BaseBitmap(3), new BaseBitmap(3))));
					assertSame(stale, ancestor.getKey());
					assertArrayEquals(new int[]{2, 3}, ancestor.compute().getArray());
					assertEquals(before.hits(), cache.getStatistics().hits());
					assertEquals(before.misses(), cache.getStatistics().misses());
					assertEquals(before.admissions(), cache.getStatistics().admissions());
				}
			}
		} finally {
			scheduler.shutdownNow();
			cleanupTestPaths(paths);
		}
	}
}
