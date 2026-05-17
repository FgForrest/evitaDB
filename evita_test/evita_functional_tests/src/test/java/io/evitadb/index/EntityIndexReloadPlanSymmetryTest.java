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

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.dataType.Scope;
import io.evitadb.index.component.AttributeCardinalityIndexMapComponent;
import io.evitadb.index.component.AttributeIndexComponent;
import io.evitadb.index.component.GroupCardinalityComponent;
import io.evitadb.index.component.HistogramIndexMapComponent;
import io.evitadb.index.component.IndexComponent;
import io.evitadb.index.component.PriceIndexComponent;
import io.evitadb.index.component.ReferenceTypeCardinalityComponent;
import io.evitadb.index.component.loader.AttributeCardinalityIndexMapLoader;
import io.evitadb.index.component.loader.AttributeIndexLoader;
import io.evitadb.index.component.loader.ComponentLoader;
import io.evitadb.index.component.loader.FacetIndexLoader;
import io.evitadb.index.component.loader.GroupCardinalityLoader;
import io.evitadb.index.component.loader.HierarchyIndexLoader;
import io.evitadb.index.component.loader.HistogramIndexMapLoader;
import io.evitadb.index.component.loader.IndexReloadPlan;
import io.evitadb.index.component.loader.PriceRefIndexLoader;
import io.evitadb.index.component.loader.PriceSuperIndexLoader;
import io.evitadb.index.component.loader.ReferenceTypeCardinalityLoader;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 M4 structural guardrail. The split between the write side
 * ({@link IndexComponent}, driven by `EntityIndex.getModifiedStorageParts`) and the read side
 * ({@link ComponentLoader} composed into {@link IndexReloadPlan}, driven by
 * `DefaultEntityCollectionPersistenceService.readEntityIndex`) means that adding a new sub-index
 * family requires changing two files. This test pins the invariant so that a future Phase 5+
 * commit which only updates one side fails immediately with a clear diagnostic — rather than
 * silently producing entity indexes whose persisted data is reloaded incompletely.
 *
 * For every concrete `EntityIndex` subclass we:
 *
 * 1. instantiate a populated fresh instance (no persisted state needed — `components` is
 *    populated by the constructor),
 * 2. walk its registered {@link IndexComponent} list via the package-public
 *    `EntityIndex.getRegisteredComponents()` accessor,
 * 3. resolve every component to the expected {@link ComponentLoader} class via the static
 *    {@link #COMPONENT_TO_LOADER} mapping (with one subclass-driven exception for
 *    {@link PriceIndexComponent}, which maps to either {@link PriceSuperIndexLoader} for
 *    `GlobalEntityIndex` or {@link PriceRefIndexLoader} for reduced indexes, or to no loader
 *    for `ReferencedTypeEntityIndex` whose price slot carries a `VoidPriceIndex.INSTANCE`),
 * 4. assert that the matching loader class appears in `reloadPlan().loaders()`.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EntityIndex IndexComponent/ComponentLoader symmetry")
@Tag(INDEXING)
@Tag(STORAGE)
class EntityIndexReloadPlanSymmetryTest {

	private static final String ENTITY_TYPE = "Product";
	private static final String REFERENCE_NAME = "CATEGORY";
	private static final int INDEX_PK = 1;

	/**
	 * Static mapping from write-side component classes to the read-side loader class that
	 * rehydrates the same sub-index family on reload. The mapping is canonical for every
	 * component except {@link PriceIndexComponent}, which is subclass-dependent and resolved
	 * dynamically by {@link #expectedLoaderFor(IndexComponent, EntityIndex)}.
	 *
	 * Every entry here MUST stay in lock-step with the loader package — when a new
	 * `IndexComponent` is added, a matching `ComponentLoader` must be added and registered here
	 * for the symmetry test to pass.
	 */
	private static final Map<Class<? extends IndexComponent>, Class<? extends ComponentLoader>>
		COMPONENT_TO_LOADER = Map.of(
			AttributeIndexComponent.class, AttributeIndexLoader.class,
			HierarchyIndex.class, HierarchyIndexLoader.class,
			FacetIndex.class, FacetIndexLoader.class,
			AttributeCardinalityIndexMapComponent.class, AttributeCardinalityIndexMapLoader.class,
			HistogramIndexMapComponent.class, HistogramIndexMapLoader.class,
			GroupCardinalityComponent.class, GroupCardinalityLoader.class,
			ReferenceTypeCardinalityComponent.class, ReferenceTypeCardinalityLoader.class
		);

	@Test
	@DisplayName("GlobalEntityIndex.reloadPlan() covers every registered IndexComponent")
	void shouldCoverEveryComponentOfGlobalEntityIndex() {
		final EntityIndexKey key = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE);
		final GlobalEntityIndex index = new GlobalEntityIndex(INDEX_PK, ENTITY_TYPE, key);
		assertReloadPlanCovers(index, GlobalEntityIndex.reloadPlan());
	}

	@Test
	@DisplayName("ReducedEntityIndex.reloadPlan() covers every registered IndexComponent")
	void shouldCoverEveryComponentOfReducedEntityIndex() {
		final EntityIndexKey key = new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
			new RepresentativeReferenceKey(
				new io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey(REFERENCE_NAME, 1)
			)
		);
		final ReducedEntityIndex index = new ReducedEntityIndex(INDEX_PK, ENTITY_TYPE, key);
		assertReloadPlanCovers(index, ReducedEntityIndex.reloadPlan());
	}

	@Test
	@DisplayName("ReducedGroupEntityIndex.reloadPlan() covers every registered IndexComponent")
	void shouldCoverEveryComponentOfReducedGroupEntityIndex() {
		final EntityIndexKey key = new EntityIndexKey(
			EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE,
			new RepresentativeReferenceKey(
				new io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey(REFERENCE_NAME, 1)
			)
		);
		final ReducedGroupEntityIndex index = new ReducedGroupEntityIndex(INDEX_PK, ENTITY_TYPE, key);
		assertReloadPlanCovers(index, ReducedGroupEntityIndex.reloadPlan());
	}

	@Test
	@DisplayName("ReferencedTypeEntityIndex.reloadPlan() covers every registered IndexComponent")
	void shouldCoverEveryComponentOfReferencedTypeEntityIndex() {
		final EntityIndexKey key = new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME
		);
		final ReferencedTypeEntityIndex index = new ReferencedTypeEntityIndex(INDEX_PK, ENTITY_TYPE, key);
		assertReloadPlanCovers(index, ReferencedTypeEntityIndex.reloadPlan());
	}

	/**
	 * Walks every registered {@link IndexComponent} on `index` and asserts that the matching
	 * {@link ComponentLoader} is present in `plan.loaders()`.
	 *
	 * @param index the populated entity index to introspect
	 * @param plan  the reload plan returned by the subclass's static `reloadPlan()`
	 */
	private static void assertReloadPlanCovers(
		@Nonnull EntityIndex index,
		@Nonnull IndexReloadPlan plan
	) {
		final Set<Class<? extends ComponentLoader>> registeredLoaderClasses = plan.loaders().stream()
			.map(ComponentLoader::getClass)
			.collect(Collectors.toSet());
		final List<IndexComponent> components = index.getRegisteredComponents();
		assertTrue(!components.isEmpty(), "index has no components — fixture bug");
		for (final IndexComponent component : components) {
			final Class<? extends ComponentLoader> expectedLoader = expectedLoaderFor(component, index);
			if (expectedLoader == null) {
				// the void price component has no on-disk footprint and intentionally lacks a loader
				continue;
			}
			assertTrue(
				registeredLoaderClasses.contains(expectedLoader),
				() -> "Component " + component.getClass().getSimpleName() +
					" registered on " + index.getClass().getSimpleName() +
					" has no matching ComponentLoader of type " + expectedLoader.getSimpleName() +
					" in reloadPlan(); registered loaders are " + registeredLoaderClasses
			);
		}
	}

	/**
	 * Resolves the expected {@link ComponentLoader} class for `component` on `owner`. Most
	 * components map statically via {@link #COMPONENT_TO_LOADER}; the price component is
	 * subclass-dependent: super for `GlobalEntityIndex`, ref for the reduced variants, and
	 * `null` (no loader) for `ReferencedTypeEntityIndex` whose price slot is the void instance.
	 *
	 * @param component the write-side component to resolve
	 * @param owner     the owning entity index (used for the price-flavour discriminator)
	 * @return the expected loader class, or `null` if the component intentionally has no loader
	 */
	private static Class<? extends ComponentLoader> expectedLoaderFor(
		@Nonnull IndexComponent component,
		@Nonnull EntityIndex owner
	) {
		if (component instanceof PriceIndexComponent) {
			if (owner instanceof GlobalEntityIndex) {
				return PriceSuperIndexLoader.class;
			}
			if (owner instanceof AbstractReducedEntityIndex) {
				return PriceRefIndexLoader.class;
			}
			// ReferencedTypeEntityIndex registers a void PriceIndexComponent for shape parity
			// but has no on-disk footprint — the reload plan intentionally omits a price loader
			return null;
		}
		final Class<? extends ComponentLoader> loader = COMPONENT_TO_LOADER.get(component.getClass());
		assertNotNull(loader,
			"No entry in COMPONENT_TO_LOADER for " + component.getClass().getSimpleName() +
				" — add the new component/loader pair to the symmetry mapping"
		);
		return loader;
	}

	@Test
	@DisplayName("COMPONENT_TO_LOADER covers every known IndexComponent shape")
	void shouldCoverEveryKnownIndexComponentShape() {
		// pin the inventory of write-side components — if a new IndexComponent is added without
		// updating this set, the test fails immediately
		final Set<Class<? extends IndexComponent>> knownComponents = Set.of(
			AttributeIndexComponent.class,
			HierarchyIndex.class,
			FacetIndex.class,
			PriceIndexComponent.class,
			AttributeCardinalityIndexMapComponent.class,
			HistogramIndexMapComponent.class,
			GroupCardinalityComponent.class,
			ReferenceTypeCardinalityComponent.class
		);
		for (final Class<? extends IndexComponent> componentClass : knownComponents) {
			if (componentClass == PriceIndexComponent.class) {
				// price is subclass-dispatched; symmetry is asserted by the per-subclass tests
				continue;
			}
			assertTrue(
				COMPONENT_TO_LOADER.containsKey(componentClass),
				() -> "COMPONENT_TO_LOADER missing entry for " + componentClass.getSimpleName()
			);
		}
		assertEquals(
			knownComponents.size() - 1,
			COMPONENT_TO_LOADER.size(),
			"COMPONENT_TO_LOADER size diverged from knownComponents; new component without " +
				"a matching loader, or extra entry without a corresponding component"
		);
	}

}
