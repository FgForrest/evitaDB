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

package io.evitadb.index;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.attribute.OwnerUniqueIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the invariant that keeps query state out of the index layer:
 *
 * **No object with index lifetime may hold a [Formula].**
 *
 * A formula node is *per-query* state. `AbstractFormula#initialize(QueryExecutionContext)` writes the executing
 * query's context onto every node of the plan it is part of, and that context transitively reaches the
 * `EvitaSession` and the whole catalog generation the query ran against. An index that memoizes a formula and hands
 * the same instance to successive query plans therefore pins the first session that ever touched it — together with
 * everything that session reached — until the index is written to again. On a read-mostly index that is never, which
 * is what made this leak grow monotonically in production rather than plateau.
 *
 * The remedy applied across the index layer is to memoize the **bitmap** (the part that is expensive to produce) and
 * to build a fresh, cheap {@link io.evitadb.core.query.algebra.base.ConstantFormula} wrapper per call. This test
 * exists because per-class discipline is not self-enforcing: it walks the declared fields of the index types that
 * carry such caches and fails if any of them retains a formula, so the next memoization added to an index cannot
 * silently reintroduce the leak.
 *
 * Ref: #1458
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(INDEXING)
@DisplayName("Index structures must never retain a Formula")
class IndexFormulaRetentionTest {

	/**
	 * Walks every non-static field declared by the object's class and its superclasses and fails when any of them
	 * holds a {@link Formula}.
	 *
	 * The stateless singletons are exempt: {@link EmptyFormula#INSTANCE} and {@link SkipFormula#INSTANCE} both
	 * override `initialize` with a deliberate no-op precisely so that they can be shared, so neither can capture a
	 * query's execution context.
	 *
	 * @param index the index instance to inspect, with all of its caches already warmed
	 */
	private static void assertRetainsNoFormula(@Nonnull Object index) throws IllegalAccessException {
		for (Class<?> type = index.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				field.setAccessible(true);
				final Object value = field.get(index);
				if (value instanceof Formula && value != EmptyFormula.INSTANCE && value != SkipFormula.INSTANCE) {
					fail(
						"Field `" + type.getSimpleName() + "#" + field.getName() + "` retains a " +
							value.getClass().getSimpleName() + " for the lifetime of the index. A formula carries " +
							"per-query state once a plan initializes it, so holding one pins the session and the " +
							"catalog generation that first used it. Memoize the bitmap instead and wrap it in a " +
							"fresh ConstantFormula per call - see FilterIndex#memoizedAllRecords."
					);
				}
			}
		}
	}

	@Test
	@DisplayName("FilterIndex memoizes its all-records bitmap without retaining the formula")
	void shouldNotRetainFormulaInFilterIndex() throws IllegalAccessException {
		final OwnerFilterIndex index = new OwnerFilterIndex(
			new AttributeIndexKey(null, "a", null), String.class
		);
		index.addRecord(1, "A");
		index.addRecord(2, "B");

		// warm every cache the index has, through the accessors a query plan uses
		final Formula first = index.getAllRecordsFormula();
		final Formula second = index.getAllRecordsFormula();
		assertNotSame(first, second);

		assertRetainsNoFormula(index);
	}

	@Test
	@DisplayName("OwnerUniqueIndex builds its record-ids formula fresh and retains none")
	void shouldNotRetainFormulaInOwnerUniqueIndex() throws IllegalAccessException {
		final OwnerUniqueIndex index = new OwnerUniqueIndex(
			Entities.PRODUCT,
			new AttributeIndexKey(null, "code", null),
			String.class
		);
		index.registerUniqueKey("A", 1);
		index.registerUniqueKey("B", 2);

		final Formula first = index.getRecordIdsFormula();
		final Formula second = index.getRecordIdsFormula();
		assertNotSame(first, second);

		assertRetainsNoFormula(index);
	}

	@Test
	@DisplayName("HierarchyIndex memoizes its all-nodes bitmap without retaining the formula")
	void shouldNotRetainFormulaInHierarchyIndex() throws IllegalAccessException {
		final HierarchyIndex index = new HierarchyIndex();
		index.addNode(1, null);
		index.addNode(2, 1);

		final Formula first = index.getAllHierarchyNodesFormula();
		final Formula second = index.getAllHierarchyNodesFormula();
		assertNotSame(first, second);

		assertRetainsNoFormula(index);
	}

	@Test
	@DisplayName("an empty HierarchyIndex parks no formula either")
	void shouldNotRetainFormulaInEmptyHierarchyIndex() throws IllegalAccessException {
		final HierarchyIndex index = new HierarchyIndex();

		// an empty index short-circuits to the shared EmptyFormula singleton, which is safe to hand out repeatedly
		index.getAllHierarchyNodesFormula();

		assertRetainsNoFormula(index);
	}

	@Test
	@DisplayName("the guard detects a retained formula, and tolerates the safe singletons")
	void shouldDetectRetainedFormula() {
		// calibration: without this, a guard that silently inspected nothing would pass every test above and
		// report coverage it does not have
		assertThrows(AssertionError.class, () -> assertRetainsNoFormula(new LeakyHolder()));

		// the two singletons whose initialize() is a documented no-op must not trip the guard
		assertDoesNotThrow(() -> assertRetainsNoFormula(new ExemptHolder()));
	}

	/**
	 * Stands in for an index that memoized a formula, so that the guard above can be shown to fail when the
	 * invariant is actually violated.
	 */
	@SuppressWarnings("unused")
	private static final class LeakyHolder {
		private final Formula retained = new ConstantFormula(new BaseBitmap(1, 2));
	}

	/**
	 * Holds only the shared stateless formula singletons, which are safe to retain and must be tolerated.
	 */
	@SuppressWarnings("unused")
	private static final class ExemptHolder {
		private final Formula empty = EmptyFormula.INSTANCE;
		private final Formula skip = SkipFormula.INSTANCE;
	}
}
