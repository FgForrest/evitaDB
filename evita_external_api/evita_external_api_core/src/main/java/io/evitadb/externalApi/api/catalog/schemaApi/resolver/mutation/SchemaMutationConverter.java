/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation;

import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.externalApi.api.resolver.mutation.MutationConverter;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.Output;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;

/**
 * Ancestor for all mutation resolver resolving {@link SchemaMutation}s
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class SchemaMutationConverter<M extends SchemaMutation> extends MutationConverter<M> {

	protected SchemaMutationConverter(@Nonnull MutationObjectMapper objectParser,
	                               @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	/**
	 * Writes a present ranking weight before reflection reaches it, because {@link Output} cannot serialize a `Float`.
	 * The weight is output-only so far - it has no descriptor and no input counterpart - so the property is written
	 * by name. A finite weight is written as a decimal; a non-finite one, which a mutation may carry until schema
	 * validation refuses it, has no decimal form and is written verbatim (`NaN`, `Infinity`, `-Infinity`).
	 *
	 * @param propertyName the output property receiving the weight
	 * @param weight the supplied ranking weight, or `null` to omit the property
	 * @param output the mutation output receiving the value
	 */
	protected static void writeSearchWeight(
		@Nonnull String propertyName, @Nullable Float weight, @Nonnull Output output
	) {
		if (weight != null) {
			output.setProperty(
				propertyName,
				Float.isFinite(weight) ? new BigDecimal(Float.toString(weight)) : Float.toString(weight)
			);
		}
	}
}
