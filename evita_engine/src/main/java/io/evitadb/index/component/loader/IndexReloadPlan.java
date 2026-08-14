/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.component.loader;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The reload-side counterpart of `EntityIndex.components`: an ordered list of
 * {@link ComponentLoader}s plus a finalizer that pieces the loaded bundles back into a concrete
 * `EntityIndex` subclass. Each `EntityIndex` subclass advertises its plan via a static
 * `reloadPlan()` accessor; the dispatcher looks the plan up by `EntityIndexType` and runs it.
 *
 * The plan is a value object — immutable after `Builder.build()`, safe to cache statically per
 * subclass, and contains no per-call state. Per-call state (catalog version, storage service,
 * etc.) flows through {@link LoadContext}.
 */
public final class IndexReloadPlan {

	/**
	 * Ordered list of loaders to run for this `EntityIndex` subclass. The order is significant:
	 * reshuffling can surface subtle ordering-dependent bugs in `KeyCompressor` initialization
	 * or `OffsetIndex` read locality.
	 */
	@Nonnull private final List<ComponentLoader> loaders;
	/**
	 * The subclass-specific constructor invocation. Receives both the bundles indexed by their
	 * concrete class and the original {@link LoadContext} so the finalizer can pick exactly the
	 * shapes its constructor needs and pull the manifest / entity name / scope from the
	 * context. This lambda isolates the per-subclass argument shape inside the subclass file
	 * rather than branching on `EntityIndexType` in the dispatcher.
	 */
	@Nonnull private final BiFunction<Map<Class<? extends LoadedComponentBundle>, LoadedComponentBundle>,
		LoadContext, EntityIndex> finalizer;

	private IndexReloadPlan(
		@Nonnull List<ComponentLoader> loaders,
		@Nonnull BiFunction<Map<Class<? extends LoadedComponentBundle>, LoadedComponentBundle>,
			LoadContext, EntityIndex> finalizer
	) {
		this.loaders = List.copyOf(loaders);
		this.finalizer = finalizer;
	}

	/**
	 * @return a fresh empty {@link Builder} for constructing one plan.
	 */
	@Nonnull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Drives every {@link ComponentLoader} in registration order, collects the resulting
	 * {@link LoadedComponentBundle}s into a `Class`-keyed map, and hands the map to the
	 * subclass finalizer. Returns the fully-reconstructed `EntityIndex`.
	 *
	 * @param context the per-call reload context
	 * @return the rehydrated entity index
	 */
	@Nonnull
	public EntityIndex run(@Nonnull LoadContext context) {
		// HashMap is fine here; reload is not on the hot path
		final int initialCapacity = this.loaders.size() << 1;
		final Map<Class<? extends LoadedComponentBundle>, LoadedComponentBundle> bundles =
			new HashMap<>(initialCapacity);
		// no plan should register two loaders that return the same bundle class — any collision
		// is a programming error
		final Map<Class<? extends LoadedComponentBundle>, ComponentLoader> producers = new HashMap<>(initialCapacity);
		for (final ComponentLoader loader : this.loaders) {
			final LoadedComponentBundle bundle = loader.load(context);
			final ComponentLoader previousProducer = producers.put(bundle.getClass(), loader);
			final LoadedComponentBundle previous = bundles.put(bundle.getClass(), bundle);
			if (previous != null) {
				throw new GenericEvitaInternalError(
					"Duplicate LoadedComponentBundle of class " + bundle.getClass().getName() +
						" produced by loader " + loader.getClass().getName() +
						" (previously produced by " +
						(previousProducer == null ? "<unknown>" : previousProducer.getClass().getName()) +
						")"
				);
			}
		}
		return this.finalizer.apply(bundles, context);
	}

	/**
	 * Returns the ordered list of registered loaders. Exposed so the symmetry tests can verify
	 * that every `IndexComponent` registered on the write side has a matching `ComponentLoader`
	 * on the read side.
	 *
	 * @return immutable view of the registered loaders
	 */
	@Nonnull
	public List<ComponentLoader> loaders() {
		return this.loaders;
	}

	/**
	 * Mutable accumulator for {@link IndexReloadPlan}. Built once at subclass class-init time and
	 * passed to a finalizer lambda; the resulting plan is cached statically.
	 */
	public static final class Builder {

		@Nonnull private final List<ComponentLoader> loaders = new ArrayList<>(8);

		private Builder() {
		}

		/**
		 * Appends a loader to the plan in registration order.
		 *
		 * @param loader the loader to append
		 * @return this builder for chaining
		 */
		@Nonnull
		public Builder add(@Nonnull ComponentLoader loader) {
			this.loaders.add(loader);
			return this;
		}

		/**
		 * Finalizes the plan with the subclass-specific constructor invocation.
		 *
		 * @param finalizer lambda that pieces the loaded bundles back into a concrete
		 *                  `EntityIndex` subclass
		 * @return the immutable plan
		 */
		@Nonnull
		public IndexReloadPlan build(
			@Nonnull BiFunction<Map<Class<? extends LoadedComponentBundle>, LoadedComponentBundle>,
				LoadContext, EntityIndex> finalizer
		) {
			return new IndexReloadPlan(this.loaders, finalizer);
		}

	}

}
