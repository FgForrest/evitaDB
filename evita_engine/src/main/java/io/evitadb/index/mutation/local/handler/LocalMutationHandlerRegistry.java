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

package io.evitadb.index.mutation.local.handler;

import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Class-init registry mapping concrete `LocalMutation` subclasses to their `LocalMutationHandler`
 * singletons. Populated exactly once in the static initializer; lookup is a single immutable map
 * get keyed by `mutation.getClass()`.
 *
 * Unknown mutation classes surface as `GenericEvitaInternalError`. A new concrete mutation type
 * added to `evita_api` must be paired with a new entry here — `LocalMutationHandlerRegistryCoverageTest`
 * enforces this contract via classpath scan.
 */
public final class LocalMutationHandlerRegistry {

	/**
	 * Immutable handler registry keyed by concrete `LocalMutation` subclass. Populated once at
	 * class-init via `register` calls below. The same map is consulted on every `applyMutation`
	 * call; lookup is `O(1)` and allocation-free.
	 */
	@Nonnull
	private static final Map<Class<? extends LocalMutation<?, ?>>, LocalMutationHandler<?>> HANDLERS;

	static {
		final Map<Class<? extends LocalMutation<?, ?>>, LocalMutationHandler<?>> handlers =
			CollectionUtils.createHashMap(16);
		// Attribute family
		register(handlers, UpsertAttributeMutationHandler.INSTANCE);
		register(handlers, RemoveAttributeMutationHandler.INSTANCE);
		register(handlers, ApplyDeltaAttributeMutationHandler.INSTANCE);
		// AssociatedData family
		register(handlers, UpsertAssociatedDataMutationHandler.INSTANCE);
		register(handlers, RemoveAssociatedDataMutationHandler.INSTANCE);
		// Parent family
		register(handlers, SetParentMutationHandler.INSTANCE);
		register(handlers, RemoveParentMutationHandler.INSTANCE);
		// Price family
		register(handlers, UpsertPriceMutationHandler.INSTANCE);
		register(handlers, RemovePriceMutationHandler.INSTANCE);
		register(handlers, SetPriceInnerRecordHandlingMutationHandler.INSTANCE);
		// Reference family
		register(handlers, InsertReferenceMutationHandler.INSTANCE);
		register(handlers, RemoveReferenceMutationHandler.INSTANCE);
		register(handlers, SetReferenceGroupMutationHandler.INSTANCE);
		register(handlers, RemoveReferenceGroupMutationHandler.INSTANCE);
		register(handlers, ReferenceAttributeMutationHandler.INSTANCE);
		// Scope
		register(handlers, SetEntityScopeMutationHandler.INSTANCE);

		HANDLERS = Map.copyOf(handlers);
	}

	private LocalMutationHandlerRegistry() {
		// no instances
	}

	/**
	 * Resolves the handler for the given mutation's concrete class. A missing registration is a
	 * programming error and surfaces immediately as `GenericEvitaInternalError`.
	 *
	 * @param mutation the mutation to dispatch (only its concrete class is consulted)
	 * @return the handler singleton matching `mutation.getClass()`, never null
	 * @throws GenericEvitaInternalError if no handler is registered for the concrete class
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	public static <M extends LocalMutation<?, ?>> LocalMutationHandler<M> resolve(@Nonnull M mutation) {
		final LocalMutationHandler<?> handler = HANDLERS.get(mutation.getClass());
		if (handler == null) {
			throw new GenericEvitaInternalError(
				"No LocalMutationHandler registered for mutation class: " + mutation.getClass().getName()
			);
		}
		return (LocalMutationHandler<M>) handler;
	}

	/**
	 * Whether a handler is registered for the given concrete mutation class. Used by the
	 * coverage test (`LocalMutationHandlerRegistryCoverageTest`) to assert no concrete
	 * `LocalMutation` subclass is left orphaned.
	 *
	 * @param mutationClass the concrete `LocalMutation` subclass to test
	 * @return `true` if a handler is registered
	 */
	public static boolean hasHandler(@Nonnull Class<? extends LocalMutation<?, ?>> mutationClass) {
		return HANDLERS.containsKey(mutationClass);
	}

	private static <M extends LocalMutation<?, ?>> void register(
		@Nonnull Map<Class<? extends LocalMutation<?, ?>>, LocalMutationHandler<?>> sink,
		@Nonnull LocalMutationHandler<M> handler
	) {
		final Class<M> key = handler.handledType();
		final LocalMutationHandler<?> previous = sink.putIfAbsent(key, handler);
		if (previous != null) {
			throw new GenericEvitaInternalError(
				"Duplicate LocalMutationHandler registration for: " + key
			);
		}
	}

}
