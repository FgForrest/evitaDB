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

package io.evitadb.test.client.query;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.descriptor.ConstraintCreator;
import io.evitadb.api.query.descriptor.ConstraintCreator.ParameterDescriptor;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.filter.AssociatedDataIn;
import io.evitadb.api.query.filter.EntityIn;
import io.evitadb.api.query.filter.ReferenceIn;
import io.evitadb.api.query.filter.TextMatches;
import io.evitadb.api.query.order.EntityWeight;
import io.evitadb.api.query.order.ReferenceWeight;
import io.evitadb.api.query.order.RelevanceText;
import io.evitadb.api.query.order.Segment;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
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

	/**
	 * Reads a creator parameter, including fulltext child arrays and the segment factory adapter's ordering/limit.
	 *
	 * @param constraint constraint with already split single-name selectors
	 * @param parameter creator parameter to read
	 * @return the parameter value, absent for a missing optional value
	 */
	@Nonnull
	public Optional<?> resolveParameterValue(@Nonnull Constraint<?> constraint, @Nonnull ParameterDescriptor parameter) {
		if (constraint instanceof AssociatedDataIn selector &&
			parameter instanceof ConstraintCreator.ClassifierParameterDescriptor) {
			return Optional.of(selector.getAssociatedDataNames()[0]);
		}
		if (parameter instanceof ConstraintCreator.ChildParameterDescriptor && isTextChildContainer(constraint)) {
			return Optional.of(((ConstraintContainer<?>) constraint).getChildren());
		}
		if (constraint instanceof Segment segment &&
			parameter instanceof ConstraintCreator.ChildParameterDescriptor child) {
			if (child.type().isArray()) {
				return Optional.of(segment.getOrderBy().getChildren());
			}
			return segment.getLimit().isPresent() ?
				Optional.of(QueryConstraints.limit(segment.getLimit().getAsInt())) : Optional.empty();
		}
		final Class<?> constraintClass = constraint.getClass();

		final Method getter = findGetter(constraintClass.getDeclaredMethods(), parameter.name())
			.or(() -> findGetter(constraintClass.getMethods(), parameter.name()))
			.orElseThrow(() ->
				new GenericEvitaInternalError("Could not find getter for parameter `" + parameter.name() + "` in constraint `" + constraintClass.getSimpleName() + "`."));
		getter.trySetAccessible();

		final Object parameterValue;
		try {
			parameterValue = getter.invoke(constraint);
		} catch (Exception e) {
			throw new GenericEvitaInternalError(
				"Could not invoke getter (`" + getter.toGenericString() + "`) " +
					"for parameter `" + parameter.name() + "` " +
					"in constraint `" + constraintClass.getName() + "`.",
				e
			);
		}

		if (parameterValue instanceof Optional<?> optionalParameterValue) {
			return optionalParameterValue;
		}
		return Optional.ofNullable(parameterValue);
	}

	/**
	 * Tells the fulltext containers whose creator child array is read as the container's children.
	 *
	 * @param constraint constraint whose child parameter is read
	 * @return true for `textMatches`, `inReference`, `inEntity`, `relevanceText`, `referenceWeight` and `entityWeight`
	 */
	private static boolean isTextChildContainer(@Nonnull Constraint<?> constraint) {
		return constraint instanceof TextMatches || constraint instanceof ReferenceIn ||
			constraint instanceof EntityIn || constraint instanceof RelevanceText ||
			constraint instanceof ReferenceWeight || constraint instanceof EntityWeight;
	}

	@Nonnull
	private static Optional<Method> findGetter(@Nonnull Method[] methods, @Nonnull String parameterName) {
		return Arrays.stream(methods)
			.filter(it -> it.getName().equals("get" + StringUtils.capitalize(parameterName)) && it.getParameterCount() == 0)
			.findFirst()
			.or(() -> Arrays.stream(methods)
				.filter(it -> it.getAnnotation(AliasForParameter.class) != null &&
					it.getAnnotation(AliasForParameter.class).value().equals(parameterName) &&
					it.getParameters().length == 0)
				.findFirst());
	}
}
