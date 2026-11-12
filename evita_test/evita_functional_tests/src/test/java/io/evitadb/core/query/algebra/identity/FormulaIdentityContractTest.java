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

package io.evitadb.core.query.algebra.identity;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.facet.FacetGroupAndFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.identity.IdentityContractVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.CACHE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Inventories concrete holders and enforces complete declared identities or explicit pending status.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula identity contract discovery")
@Tag(ENGINE)
@Tag(CACHE)
class FormulaIdentityContractTest implements EvitaTestSupport {
	/** Allows holders with neither a declared identity nor a fixture. */
	private static final boolean MIGRATION_MODE = true;
	/** Minimum engine inventory, preventing a truncated source scan from passing. */
	private static final int ENGINE_FLOOR = 64;
	/** Minimum inventory of functional test holders, including named nested classes. */
	private static final int TEST_FLOOR = 4;
	/** Binary name of the mutable delegating wrapper. */
	private static final String MUTABLE_FORMULA =
		"io.evitadb.core.query.extraResult.translator.reference.producer.MutableFormula";
	/** Named exceptions to the declared identity pair, each with its reason. */
	private static final Map<String, String> EXEMPTIONS = Map.of(
		MUTABLE_FORMULA, "Mutable wrapper forwards its current delegate's key instead of declaring an identity."
	);

	/** Verifies the engine and test inventories independently against their floors. */
	@Test
	@DisplayName("Discovers engine and test holders and verifies their migration state")
	void shouldVerifyEveryDiscoveredHolder() throws ReflectiveOperationException {
		final Path root = getRootDirectory();
		final Set<Class<?>> engine = IdentityContractVerifier.discover(root.resolve("evita_engine/src/main/java"));
		final Set<Class<?>> tests = IdentityContractVerifier.discover(root.resolve(
			"evita_test/evita_functional_tests/src/test/java"), root.resolve("evita_engine/src/main/java"));
		assertTrue(engine.size() >= ENGINE_FLOOR, "Engine inventory below floor: " + engine.size());
		assertTrue(tests.size() >= TEST_FLOOR, "Functional inventory below floor: " + tests.size());
		for (final Map.Entry<String, String> exemption : EXEMPTIONS.entrySet()) {
			assertFalse(exemption.getValue().isBlank(), "Named exemption needs a reason");
			assertTrue(engine.stream().anyMatch(type -> type.getName().equals(exemption.getKey())) ||
				tests.stream().anyMatch(type -> type.getName().equals(exemption.getKey())),
				"Stale named exemption: " + exemption.getKey());
		}
		final int engineMigrated = verifyInventory(engine);
		final int testsMigrated = verifyInventory(tests);
		System.out.println("Identity discovery: engine=" + engine.size() + "/floor=" + ENGINE_FLOOR +
			", functional=" + tests.size() + "/floor=" + TEST_FLOOR + ", migrated=" +
			(engineMigrated + testsMigrated) + ", pending=" +
			(engine.size() + tests.size() - engineMigrated - testsMigrated));
	}

	/** Verifies each discovered class, with the mutable wrapper checked by its forwarding contract. */
	private static int verifyInventory(@Nonnull Set<Class<?>> inventory) throws ReflectiveOperationException {
		int migrated = 0;
		for (final Class<?> type : inventory) {
			if (EXEMPTIONS.containsKey(type.getName())) {
				if (type.getName().equals(MUTABLE_FORMULA)) {
					if (verifyMutable(type)) {
						migrated++;
					}
				}
			} else if (IdentityContractVerifier.verifyDiscovered(type, MIGRATION_MODE)) {
				migrated++;
			}
		}
		return migrated;
	}

	/** Checks pending status or, once declared, forwarding before and after a delegate swap. */
	private static boolean verifyMutable(@Nonnull Class<?> type) throws ReflectiveOperationException {
		try {
			type.getDeclaredMethod("getKey");
		} catch (NoSuchMethodException ignored) {
			return IdentityContractVerifier.verifyDiscovered(type, MIGRATION_MODE);
		}
		final FacetGroupFormula first = new FacetGroupAndFormula("reference", 1,
			new BaseBitmap(1), new BaseBitmap(10));
		final FacetGroupFormula second = new FacetGroupAndFormula("reference", 1,
			new BaseBitmap(2), new BaseBitmap(20));
		final Constructor<?> constructor = type.getDeclaredConstructor(FacetGroupFormula.class);
		constructor.setAccessible(true);
		final Formula mutable = (Formula) constructor.newInstance(first);
		assertSame(first.getKey(), mutable.getKey(), "Mutable wrapper must forward the delegate key");
		final Method swap = type.getDeclaredMethod("setDelegate", FacetGroupFormula.class);
		swap.setAccessible(true);
		swap.invoke(mutable, second);
		assertSame(second.getKey(), mutable.getKey(), "Mutable wrapper must follow a delegate swap");
		assertNotEquals(first.getKey(), mutable.getKey(), "Delegate swap must move the key");
		return true;
	}
}
