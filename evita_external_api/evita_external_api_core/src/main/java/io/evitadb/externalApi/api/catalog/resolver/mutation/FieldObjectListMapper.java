/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Converts raw input list field into target object array using provided item mapper.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class FieldObjectListMapper<T extends Serializable> implements Function<Object, T[]> {

	/**
	 * Name of parent mutation for identification purposes.
	 */
	@Nonnull private final String mutationName;
	/**
	 * Parent exception factory.
	 */
	@Nonnull private final MutationResolvingExceptionFactory exceptionFactory;

	/**
	 * Descriptor of field to be mapped.
	 */
	@Nonnull private final PropertyDescriptor field;
	/**
	 * Target item type.
	 */
	@Nonnull private final Class<T> objectType;

	/**
	 * Maps raw item object to target object.
	 */
	@Nonnull private final Function<InputMutation, T> objectMapper;

	@SuppressWarnings("unchecked")
	@Override
	public T[] apply(@Nonnull Object rawField) {
		Assert.isTrue(
			rawField instanceof List<?>,
			() -> exceptionFactory.createInvalidArgumentException("Field `" + field.name() + "` of mutation `" + mutationName + "` is expected to be an array.")
		);

		final List<Object> rawElements = (List<Object>) rawField;
		return rawElements.stream()
			.map(rawElement -> {
				Assert.isTrue(
					rawElement instanceof Map<?, ?>,
					() -> exceptionFactory.createInvalidArgumentException("Item in field `" + field.name() + "` of mutation `" + mutationName + "` is expected to be an object.")
				);

				final Map<String, Object> element = (Map<String, Object>) rawElement;
				return objectMapper.apply(new InputMutation(mutationName, element, exceptionFactory));
			})
			.toArray(size -> (T[]) Array.newInstance(objectType, size));
	}
}
