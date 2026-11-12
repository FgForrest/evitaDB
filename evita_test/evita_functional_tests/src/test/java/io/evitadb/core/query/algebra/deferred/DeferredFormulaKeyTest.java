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

package io.evitadb.core.query.algebra.deferred;

import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.index.bitmap.BaseBitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import io.evitadb.core.query.algebra.identity.*;
import io.evitadb.test.identity.*;
import java.lang.reflect.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Supplier;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.CACHE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies detached computation identity on real holders.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Deferred formula identity")
@Tag(ENGINE)
@Tag(CACHE)
class DeferredFormulaKeyTest {
	/** Supplier identity distinguishes deferred computations. */
	@Test
	@DisplayName("Includes supplier identity")
	void shouldDistinguishSuppliers() {
		final DeferredFormula first = new DeferredFormula(new FormulaWrapper(
			new ConstantFormula(new BaseBitmap(1)), () -> "nested query"));
		final DeferredFormula second = new DeferredFormula(new FormulaWrapper(
			new ConstantFormula(new BaseBitmap(2)), () -> "nested query"));
		assertNotEquals(first.getKey(), second.getKey());
		assertNotEquals(first.getKey().getHash64(), second.getKey().getHash64());
	}

	/** Opaque supplier inputs bind each deferred holder separately. */
	@Test
	@DisplayName("Propagates opaque supplier identity to the holder")
	void shouldBindOpaqueSupplierToDeferredHolder() {
		final FormulaWrapper wrapper = FormulaWrapper.custom(
			new ConstantFormula(new BaseBitmap(1)), (context, formula) -> formula.compute());
		final DeferredFormula first = new DeferredFormula(wrapper);
		final DeferredFormula second = new DeferredFormula(wrapper);
		assertTrue(first.getKey().isOpaque());
		assertNotEquals(first.getKey(), second.getKey());
		assertNotEquals(first.getKey().getHash64(), second.getKey().getHash64());
		assertThrows(UnsupportedOperationException.class, first::getCloneWithInnerFormulas);
	}

	/**
	 * Each changing fixture component gets its own test, including a hash assertion. This exposes every omitted
	 * description independently when several holder descriptions are defective at the same time.
	 */
	@Nonnull
	@TestFactory
	@DisplayName("Hashes every detached component independently")
	Collection<DynamicTest> shouldDescribeEveryIdentityComponent() throws ReflectiveOperationException {
		final List<DynamicTest> tests = new ArrayList<>();
		final String[] holders = {
			"core.query.algebra.hierarchy.HierarchyFormula",
			"core.query.algebra.deferred.DeferredFormula",
			"core.query.algebra.deferred.FormulaWrapper",
			"core.query.QueryPlanner$FutureNotFormula",
			"index.hierarchy.suppliers.HierarchyByParentBitmapSupplier",
			"index.hierarchy.suppliers.HierarchyByParentIncludingSelfBitmapSupplier",
			"index.hierarchy.suppliers.HierarchyForParentBitmapSupplier",
			"index.hierarchy.suppliers.HierarchyByParentDownToLevelBitmapSupplier",
			"index.hierarchy.suppliers.HierarchyByParentDownToLevelIncludingSelfBitmapSupplier",
			"index.hierarchy.suppliers.HierarchyRootsBitmapSupplier",
			"index.hierarchy.suppliers.HierarchyRootsDownBitmapSupplier",
			"index.hierarchy.suppliers.HierarchyRootsDownToLevelBitmapSupplier",
			"index.hierarchy.predicate.HierarchyFilteringPredicate$AndHierarchyFilteringPredicate",
			"index.hierarchy.predicate.HierarchyFilteringPredicate$OrHierarchyFilteringPredicate",
			"index.hierarchy.predicate.HierarchyFilteringPredicate$NegatedHierarchyFilteringPredicate",
			"index.hierarchy.predicate.MatchNodeIdHierarchyFilteringPredicate",
			"index.hierarchy.predicate.LocaleHierarchyEntityPredicate",
			"index.hierarchy.predicate.FilteringFormulaHierarchyEntityPredicate"
		};
		for (final String name : holders) {
			final Class<?> holder = Class.forName("io.evitadb." + name);
			final String fixtureName = holder.getPackageName() + "." +
				holder.getName().substring(holder.getPackageName().length() + 1).replace("$", "") +
				"IdentityFixture";
			final IdentityFixture<?> fixture = (IdentityFixture<?>) Class.forName(fixtureName)
				.getConstructor().newInstance();
			for (final Map.Entry<String, ? extends ConstructionMode<?>> modeEntry : fixture.modes().entrySet()) {
				final ConstructionMode<?> mode = modeEntry.getValue();
				if (mode.base() == null) {
					continue;
				}
				final IdentityRecord base = identityOf(mode.base().get());
				final List<Object> variants = new ArrayList<>();
				for (final Map<String, ? extends Supplier<?>> inputs :
					List.of(mode.variants(), mode.opaqueVariants(), mode.oversizedVariants())) {
					for (final Supplier<?> input : inputs.values()) {
						variants.add(input.get());
					}
				}
				for (final RecordComponent component : base.getClass().getRecordComponents()) {
					for (final Object variant : variants) {
						final Object before = component.getAccessor().invoke(base);
						final Object after = component.getAccessor().invoke(identityOf(variant));
						if (!Objects.equals(before, after)) {
							final IdentityRecord changed = replaceComponent(base, component.getName(), after);
							tests.add(DynamicTest.dynamicTest(
								name + "/" + modeEntry.getKey() + "/" + component.getName(),
								() -> assertNotEquals(
									FormulaKey.of(base, base.classId(), FormulaKey.NO_CHILDREN, base).getHash64(),
									FormulaKey.of(changed, changed.classId(), FormulaKey.NO_CHILDREN,
										base).getHash64())));
							break;
						}
					}
				}
			}
		}
		assertTrue(tests.size() >= 35, "Component inventory must include every changing input");
		return tests;
	}

	/** Reads the holder's own declaration through the test-only reflective boundary. */
	@Nonnull
	private static IdentityRecord identityOf(@Nonnull Object holder) throws ReflectiveOperationException {
		final Method method = holder.getClass().getDeclaredMethod("identity");
		method.setAccessible(true);
		return (IdentityRecord) method.invoke(holder);
	}

	/** Constructs the same record type with precisely one input replaced. */
	@Nonnull
	private static IdentityRecord replaceComponent(
		@Nonnull IdentityRecord base, @Nonnull String name, @Nullable Object replacement
	)
		throws ReflectiveOperationException {
		final RecordComponent[] components = base.getClass().getRecordComponents();
		final Class<?>[] types = new Class<?>[components.length];
		final Object[] arguments = new Object[components.length];
		for (int i = 0; i < components.length; i++) {
			types[i] = components[i].getType();
			arguments[i] = components[i].getName().equals(name) ? replacement :
				components[i].getAccessor().invoke(base);
		}
		return (IdentityRecord) base.getClass().getDeclaredConstructor(types).newInstance(arguments);
	}
}
