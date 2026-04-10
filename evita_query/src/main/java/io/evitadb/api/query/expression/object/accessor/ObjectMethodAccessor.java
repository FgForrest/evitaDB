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

package io.evitadb.api.query.expression.object.accessor;

import io.evitadb.api.query.expression.object.accessor.common.ListMethodAccessor;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;

/**
 * Service Provider Interface (SPI) for accessing methods on {@link Serializable}
 * objects via dot-notation. This enables the EvitaEL expression language to invoke
 * methods on objects.
 *
 * Implementations are discovered via {@link java.util.ServiceLoader} and registered in
 * {@link ObjectAccessorRegistry}, which dispatches method access calls to the appropriate
 * accessor based on the runtime type of the target object. The registry supports type
 * hierarchy traversal, so an accessor registered for a supertype will also handle its
 * subtypes.
 *
 * Built-in implementations cover the most common types:
 *
 * - {@link ListMethodAccessor} - lists
 *
 * Custom implementations can be added for domain-specific types by implementing this
 * interface and registering it in the `META-INF/services` file or `module-info.java` file.
 *
 * @see ObjectElementAccessor sibling interface for bracket-element access (e.g. `object['key']`)
 * @see ObjectAccessorRegistry singleton registry that manages accessor lookup and caching
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public interface ObjectMethodAccessor {

	/**
	 * Returns the set of {@link Serializable} types this accessor can handle. The
	 * {@link ObjectAccessorRegistry} uses these types to dispatch property access calls
	 * to the correct accessor implementation.
	 *
	 * @return array of supported types, must not be empty
	 */
	@Nonnull
	Class<? extends Serializable>[] getSupportedTypes();

	/**
	 * Accesses a method on the target object (e.g. `object.method(...)`).
	 *
	 * @param context            the expression evaluation context
	 * @param object             the target object to access the property from
	 * @param methodIdentifier the name of the method to access
	 * @param args               the arguments to pass to the method invocation, if any
	 * @return the property value, or `null` if the property is not present
	 * @throws ExpressionEvaluationException if the access fails for a domain-specific reason
	 */
	Serializable invoke(
		@Nonnull ExpressionEvaluationContext context,
		@Nonnull Serializable object,
		@Nonnull String methodIdentifier,
		@Nonnull List<ExpressionNode> args
	) throws ExpressionEvaluationException;
}
