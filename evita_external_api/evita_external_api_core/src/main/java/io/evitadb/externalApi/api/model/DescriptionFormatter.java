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

package io.evitadb.externalApi.api.model;

import io.evitadb.api.requestResponse.schema.NamedContract;

import javax.annotation.Nonnull;

/**
 * Shared formatter of parametrized descriptions of all descriptors in this package
 * ({@link PropertyDescriptor}, {@link ObjectDescriptor}, {@link UnionDescriptor} and {@link EndpointDescriptor}).
 *
 * <p>Description templates are plain {@link String#format(String, Object...)} patterns that usually want the
 * <em>name</em> of a schema, not the schema object itself. Relying on {@link Object#toString()} of a schema would
 * leak an internal class name — and, for schema decorators that don't override {@code toString()}, a JVM identity
 * hash that differs on every boot, making the published GraphQL/OpenAPI schema irreproducible. Therefore any
 * argument implementing {@link NamedContract} is resolved to its {@link NamedContract#getName()} before formatting,
 * so that callers may pass schemas directly and don't have to remember to unwrap them.</p>
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
class DescriptionFormatter {

	private DescriptionFormatter() {
	}

	/**
	 * Formats the passed description template with the passed arguments, resolving every {@link NamedContract}
	 * argument to its {@link NamedContract#getName()}.
	 *
	 * @param description description template with {@link String#format(String, Object...)} placeholders
	 * @param args        arguments to fill the placeholders with
	 * @return formatted description
	 */
	@Nonnull
	static String format(@Nonnull String description, @Nonnull Object... args) {
		final Object[] resolvedArgs = new Object[args.length];
		for (int i = 0; i < args.length; i++) {
			final Object arg = args[i];
			resolvedArgs[i] = arg instanceof NamedContract namedContract ? namedContract.getName() : arg;
		}
		return String.format(description, resolvedArgs);
	}

}
