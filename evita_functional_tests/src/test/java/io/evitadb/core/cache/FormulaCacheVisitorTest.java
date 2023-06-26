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

package io.evitadb.core.cache;

import io.evitadb.core.EvitaSession;
import io.evitadb.core.cache.model.CacheRecordAdept;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.scheduling.Scheduler;
import io.evitadb.index.bitmap.TransactionalBitmap;
import net.openhft.hashing.LongHashFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies behaviour of {@link FormulaCacheVisitor}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class FormulaCacheVisitorTest {
	public static final String SOME_ENTITY = "SomeEntity";
	private static final LongHashFunction HASH_FUNCTION = CacheSupervisor.createHashFunction();
	private static final int MINIMAL_USAGE_THRESHOLD = 1;
	private CacheAnteroom cacheAnteroom;
	private CacheEden cacheEden;

	@Nonnull
	static ConstantFormula toConstantFormula(int... recordIds) {
		return new ConstantFormula(new TransactionalBitmap(recordIds));
	}

	@BeforeEach
	void setUp() {
		this.cacheEden = new CacheEden(1_000_000, MINIMAL_USAGE_THRESHOLD, 100L);
		this.cacheAnteroom = new CacheAnteroom(
			10_000, 30L,
			cacheEden,
			new Scheduler(new ScheduledThreadPoolExecutor(4))
		);
	}

	@Test
	void shouldNotRegisterFormulaWithLowComplexity() {
		final Formula inputFormula = toConstantFormula(1, 2, 3, 4, 5, 6, 7, 8);

		final Formula possiblyUpdatedFormula = FormulaCacheVisitor.analyse(
			Mockito.mock(EvitaSession.class),
			SOME_ENTITY,
			inputFormula,
			cacheAnteroom
		);
		assertEquals(inputFormula.computeHash(HASH_FUNCTION), inputFormula.computeHash(HASH_FUNCTION));
		assertSame(inputFormula, possiblyUpdatedFormula);

		final CacheRecordAdept cacheAdept = cacheAnteroom.getCacheAdept(TEST_CATALOG, SOME_ENTITY, inputFormula);
		assertNull(cacheAdept);
	}

	@Test
	void shouldRegisterFormulaWithHighComplexity() {
		final Formula inputFormula = new AndFormula(
			toConstantFormula(1, 2, 3, 4, 5, 6, 7, 8),
			toConstantFormula(4, 5, 6, 7, 8, 9, 10),
			toConstantFormula(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
		);

		final EvitaSession evitaSession = Mockito.mock(EvitaSession.class);
		Mockito.when(evitaSession.getCatalogName()).thenReturn(TEST_CATALOG);
		final Formula possiblyUpdatedFormula = FormulaCacheVisitor.analyse(
			evitaSession,
			SOME_ENTITY,
			inputFormula,
			cacheAnteroom
		);
		assertEquals(inputFormula.computeHash(HASH_FUNCTION), inputFormula.computeHash(HASH_FUNCTION));
		assertNotSame(inputFormula, possiblyUpdatedFormula);

		// compute the instrumented formula
		possiblyUpdatedFormula.compute();

		final CacheRecordAdept cacheAdept = cacheAnteroom.getCacheAdept(TEST_CATALOG, SOME_ENTITY, inputFormula);
		assertNotNull(cacheAdept);
		assertTrue(cacheAdept.getSizeInBytes() > 120);
		assertEquals(0, cacheAdept.getSpaceToPerformanceRatio(MINIMAL_USAGE_THRESHOLD));

		assertEquals(CacheAnteroom.computeDataStructureHash(TEST_CATALOG, SOME_ENTITY, inputFormula), cacheAdept.getRecordHash());
	}

	@Test
	void shouldRegisterMultipleFormulaWithHighComplexityButIgnoreThoseWithLowComplexity() {
		// high complexity
		final Formula inputFormula = new AndFormula(
			// high complexity
			new OrFormula(
				toConstantFormula(1, 2, 3, 4, 5, 6, 7, 8),
				// high complexity
				new AndFormula(
					toConstantFormula(4, 5, 6, 7, 8, 9, 10),
					toConstantFormula(4, 5, 6, 7, 8, 9, 10),
					toConstantFormula(4, 5, 6, 7, 8, 9, 10)
				)
			),
			// low complexity
			new OrFormula(
				toConstantFormula(1),
				toConstantFormula(2)
			)
		);

		final EvitaSession evitaSession = Mockito.mock(EvitaSession.class);
		Mockito.when(evitaSession.getCatalogName()).thenReturn(TEST_CATALOG);
		final Formula possiblyUpdatedFormula = FormulaCacheVisitor.analyse(
			evitaSession,
			SOME_ENTITY,
			inputFormula,
			cacheAnteroom
		);

		// compute the instrumented formula
		possiblyUpdatedFormula.compute();

		assertNotNull(cacheAnteroom.getCacheAdept(TEST_CATALOG, SOME_ENTITY, inputFormula));
		assertNotNull(cacheAnteroom.getCacheAdept(TEST_CATALOG, SOME_ENTITY, inputFormula.getInnerFormulas()[0]));
		assertNull(cacheAnteroom.getCacheAdept(TEST_CATALOG, SOME_ENTITY, inputFormula.getInnerFormulas()[1]));
		assertNull(cacheAnteroom.getCacheAdept(TEST_CATALOG, SOME_ENTITY, inputFormula.getInnerFormulas()[0].getInnerFormulas()[0]));
		assertNotNull(cacheAnteroom.getCacheAdept(TEST_CATALOG, SOME_ENTITY, inputFormula.getInnerFormulas()[0].getInnerFormulas()[1]));
	}

	@Test
	void shouldNotRegisterFormulasThatContainUserFilterFormula() {
		// high complexity, but contains user filter
		final Formula inputFormula = new AndFormula(
			// high complexity, but contains user filter
			new OrFormula(
				toConstantFormula(1, 2, 3, 4, 5, 6, 7, 8),
				// high complexity, but represents user filter
				new UserFilterFormula(
					toConstantFormula(4, 5, 6, 7, 8, 9, 10),
					toConstantFormula(4, 5, 6, 7, 8, 9, 10),
					toConstantFormula(4, 5, 6, 7, 8, 9, 10)
				)
			),
			// high complexity
			new OrFormula(
				toConstantFormula(4, 5, 6, 7, 8, 9, 10),
				toConstantFormula(4, 5, 6, 7, 8, 9, 10)
			)
		);

		final EvitaSession evitaSession = Mockito.mock(EvitaSession.class);
		Mockito.when(evitaSession.getCatalogName()).thenReturn(TEST_CATALOG);
		final Formula possiblyUpdatedFormula = FormulaCacheVisitor.analyse(
			evitaSession,
			SOME_ENTITY,
			inputFormula,
			cacheAnteroom
		);

		// compute the instrumented formula
		possiblyUpdatedFormula.compute();

		assertNull(cacheAnteroom.getCacheAdept(TEST_CATALOG, SOME_ENTITY, inputFormula));
		assertNull(cacheAnteroom.getCacheAdept(TEST_CATALOG, SOME_ENTITY, inputFormula.getInnerFormulas()[0]));
		assertNotNull(cacheAnteroom.getCacheAdept(TEST_CATALOG, SOME_ENTITY, inputFormula.getInnerFormulas()[1]));
		assertNull(cacheAnteroom.getCacheAdept(TEST_CATALOG, SOME_ENTITY, inputFormula.getInnerFormulas()[0].getInnerFormulas()[0]));
		assertNull(cacheAnteroom.getCacheAdept(TEST_CATALOG, SOME_ENTITY, inputFormula.getInnerFormulas()[0].getInnerFormulas()[1]));
	}

	@Test
	void shouldComputeAppropriateSpaceToPerformanceRatioThatIncreaseWithUsageCount() {
		final Formula inputFormula = new AndFormula(
			toConstantFormula(1, 2, 3, 4, 5, 6, 7, 8),
			toConstantFormula(4, 5, 6, 7, 8, 9, 10),
			toConstantFormula(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
		);

		final EvitaSession evitaSession = Mockito.mock(EvitaSession.class);
		Mockito.when(evitaSession.getCatalogName()).thenReturn(TEST_CATALOG);

		final Formula possiblyUpdatedFormula = FormulaCacheVisitor.analyse(evitaSession, SOME_ENTITY, inputFormula, cacheAnteroom);

		// compute the instrumented formula
		possiblyUpdatedFormula.compute();

		for (int i = 0; i < MINIMAL_USAGE_THRESHOLD + 10; i++) {
			FormulaCacheVisitor.analyse(evitaSession, SOME_ENTITY, inputFormula, cacheAnteroom);
		}

		final CacheRecordAdept cacheAdept = cacheAnteroom.getCacheAdept(TEST_CATALOG, SOME_ENTITY, inputFormula);
		assertNotNull(cacheAdept);
		assertEquals(3, cacheAdept.getSpaceToPerformanceRatio(MINIMAL_USAGE_THRESHOLD));

		for (int i = 0; i < 100; i++) {
			FormulaCacheVisitor.analyse(evitaSession, SOME_ENTITY, inputFormula, cacheAnteroom);
		}

		assertEquals(32, cacheAdept.getSpaceToPerformanceRatio(MINIMAL_USAGE_THRESHOLD));
	}

}