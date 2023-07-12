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

package io.evitadb.test.client.query;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintCreator;
import io.evitadb.api.query.descriptor.ConstraintCreator.ParameterDescriptor;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

/**
 * Resolves actual value of constraint parameter ({@link ConstraintCreator#parameters()}). It tries to find any getter
 * for that parameter either directly by parameter name or using {@link AliasForParameter} annotation.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ConstraintParameterValueResolver {

	@Nonnull
	public Optional<?> resolveParameterValue(@Nonnull Constraint<?> constraint, @Nonnull ParameterDescriptor parameter) {
		final Class<?> constraintClass = constraint.getClass();

		final Method getter = findGetter(constraintClass.getDeclaredMethods(), parameter.name())
			.or(() -> findGetter(constraintClass.getMethods(), parameter.name()))
			.orElseThrow(() ->
				new EvitaInternalError("Could not find getter for parameter `" + parameter.name() + "` in constraint `" + constraintClass.getSimpleName() + "`."));
		getter.trySetAccessible();

		final Object parameterValue;
		try {
			parameterValue = getter.invoke(constraint);
		} catch (IllegalAccessException | InvocationTargetException e) {
			throw new EvitaInternalError("Could not invoke getter for parameter `" + parameter.name() + "` in constraint `" + constraintClass.getSimpleName() + "`.", e);
		}

		if (parameterValue instanceof Optional<?> optionalParameterValue) {
			return optionalParameterValue;
		}
		return Optional.ofNullable(parameterValue);
	}

	@Nonnull
	private Optional<Method> findGetter(@Nonnull Method[] methods, @Nonnull String parameterName) {
		return Arrays.stream(methods)
			.filter(it -> it.getName().equals("get" + StringUtils.capitalize(parameterName)))
			.findFirst()
			.or(() -> Arrays.stream(methods)
				.filter(it -> it.getAnnotation(AliasForParameter.class) != null &&
					it.getAnnotation(AliasForParameter.class).value().equals(parameterName) &&
					it.getParameters().length == 0)
				.findFirst());
	}
}
