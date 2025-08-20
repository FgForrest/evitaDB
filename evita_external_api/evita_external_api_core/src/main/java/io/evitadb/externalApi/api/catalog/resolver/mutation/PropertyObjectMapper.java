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

package io.evitadb.externalApi.api.catalog.resolver.mutation;

import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Map;
import java.util.function.Function;

/**
 * Converts raw input object property into target object using provided mapper.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class PropertyObjectMapper<T extends Serializable> implements Function<Object, T> {

	/**
	 * Name of parent mutation for identification purposes.
	 */
	@Nonnull private final String mutationName;
	/**
	 * Parent exception factory.
	 */
	@Nonnull private final MutationResolvingExceptionFactory exceptionFactory;

	/**
	 * Name of property to be mapped.
	 */
	@Nonnull private final String propertyName;

	/**
	 * Maps raw object to target object.
	 */
	@Nonnull private final Function<Input, T> objectMapper;

	public PropertyObjectMapper(@Nonnull String mutationName,
	                            @Nonnull MutationResolvingExceptionFactory exceptionFactory,
	                            @Nonnull String propertyName,
	                            @Nonnull Function<Input, T> objectMapper) {
		this.mutationName = mutationName;
		this.exceptionFactory = exceptionFactory;
		this.propertyName = propertyName;
		this.objectMapper = objectMapper;
	}

	public PropertyObjectMapper(@Nonnull String mutationName,
	                            @Nonnull MutationResolvingExceptionFactory exceptionFactory,
	                            @Nonnull PropertyDescriptor property,
	                            @Nonnull Function<Input, T> objectMapper) {
		this(
			mutationName,
			exceptionFactory,
			property.name(),
			objectMapper
		);
	}

	@SuppressWarnings("unchecked")
	@Override
	public T apply(Object rawPropertyValue) {
		Assert.isTrue(
			rawPropertyValue instanceof Map<?, ?>,
			() -> this.exceptionFactory.createInvalidArgumentException("Item in property `" + this.propertyName + "` of mutation `" + this.mutationName + "` is expected to be an object.")
		);

		final Map<String, Object> element = (Map<String, Object>) rawPropertyValue;
		return this.objectMapper.apply(new Input(this.mutationName, element, this.exceptionFactory));
	}
}
