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

package io.evitadb.api.query.expression.evaluate.object.accessor;

import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Service Provider Interface (SPI) for accessing elements within collection-like
 * {@link Serializable} objects by string key or numeric index. This enables the EvitaEL
 * expression language to evaluate bracket-access expressions such as `object['key']`
 * or `object[0]`.
 *
 * Implementations are discovered via {@link java.util.ServiceLoader} and registered in
 * {@link ObjectAccessorRegistry}, which dispatches element access
 * calls to the appropriate
 * accessor based on the runtime type of the target object. The registry supports type
 * hierarchy traversal, so an accessor registered for a supertype will also handle its
 * subtypes.
 *
 * Built-in implementations cover the most common collection types:
 *
 * - {@link io.evitadb.api.query.expression.evaluate.object.accessor.common.ArrayElementAccessor} — arrays
 * - {@link io.evitadb.api.query.expression.evaluate.object.accessor.common.ListElementAccessor} — lists
 * - {@link io.evitadb.api.query.expression.evaluate.object.accessor.common.MapElementAccessor} — maps
 *
 * Custom implementations can be added for domain-specific types by implementing this
 * interface and registering it in the `META-INF/services` file or `module-info.java` file.
 *
 * Not every collection type supports both keyed and indexed access. The two `get` methods
 * have default implementations that throw {@link UnsupportedOperationException}, so
 * implementations only need to override the access mode(s) they actually support.
 *
 * @see ObjectPropertyAccessor sibling interface for dot-property access (e.g. `object.property`)
 * @see ObjectAccessorRegistry singleton registry that manages accessor lookup and caching
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public interface ObjectElementAccessor {

	/**
	 * Returns the set of {@link Serializable} types this accessor can handle. The
	 * {@link ObjectAccessorRegistry} uses these types to dispatch element access calls
	 * to the correct accessor implementation.
	 *
	 * @return array of supported types, must not be empty
	 */
	@Nonnull
	Class<? extends Serializable>[] getSupportedTypes();

	/**
	 * Accesses an element by string key (e.g. map key lookup via `object['key']`).
	 *
	 * The default implementation throws {@link UnsupportedOperationException} because not
	 * all collection types support keyed access. Implementations that do support it (such
	 * as map accessors) should override this method.
	 *
	 * @param object      the target object to access the element from
	 * @param elementName the string key identifying the element
	 * @return the element value, or `null` if the key is not present
	 * @throws ExpressionEvaluationException if the access fails for a domain-specific reason
	 * @throws UnsupportedOperationException if keyed access is not supported by this accessor
	 */
	@Nullable
	default Serializable get(@Nonnull Serializable object, @Nonnull String elementName) throws ExpressionEvaluationException {
		throw new UnsupportedOperationException(
			"Cannot access element `'" + elementName + "'` on object `" + object.getClass().getName() + "`. Not supported."
		);
	};

	/**
	 * Accesses an element by numeric index (e.g. array/list index via `object[0]`).
	 *
	 * The default implementation throws {@link UnsupportedOperationException} because not
	 * all collection types support indexed access. Implementations that do support it (such
	 * as array or list accessors) should override this method.
	 *
	 * @param object       the target object to access the element from
	 * @param elementIndex the zero-based index of the element
	 * @return the element value, or `null` if the index maps to a null entry
	 * @throws ExpressionEvaluationException if the access fails for a domain-specific reason
	 * @throws UnsupportedOperationException if indexed access is not supported by this accessor
	 */
	@Nullable
	default Serializable get(@Nonnull Serializable object, int elementIndex) throws ExpressionEvaluationException {
		throw new UnsupportedOperationException(
			"Cannot access element `[" + elementIndex + "]` on object `" + object.getClass().getName() + "`. Not supported."
		);
	};
}
