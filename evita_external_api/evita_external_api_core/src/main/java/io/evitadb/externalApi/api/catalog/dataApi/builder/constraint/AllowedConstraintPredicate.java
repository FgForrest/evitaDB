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

package io.evitadb.externalApi.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.AdditionalChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ConstraintParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Predicate for filtering constraints by list of allowed and forbidden ones.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@EqualsAndHashCode
public class AllowedConstraintPredicate implements Predicate<ConstraintDescriptor> {

	@Nonnull private final Class<? extends Constraint<?>> baseConstraintType;
	@Nonnull private final Set<Class<? extends Constraint<?>>> locallyAllowedConstraints;
	@Nonnull private final Set<Class<? extends Constraint<?>>> globallyAllowedConstraints;

	@Nonnull private final Set<Class<? extends Constraint<?>>> forbiddenConstraints;

	/**
	 * Creates predicate for constraint's child parameter. Tested constraint must be a subclass of parameter type,
	 * be allowed by parameter allowed set and be allowed by global set of constraints.
	 */
	public AllowedConstraintPredicate(@Nonnull ChildParameterDescriptor childParameter,
	                                  @Nonnull Set<Class<? extends Constraint<?>>> globallyAllowedConstraints,
	                                  @Nonnull Set<Class<? extends Constraint<?>>> globallyForbiddenConstraints) {
		this.baseConstraintType = resolveChildParameterBaseType(childParameter);
		this.locallyAllowedConstraints = childParameter.allowedChildTypes();
		this.globallyAllowedConstraints = globallyAllowedConstraints;

		this.forbiddenConstraints = resolveForbiddenConstraints(childParameter, globallyForbiddenConstraints);
	}

	/**
	 * Creates predicate for constraint's child parameter. Tested constraint must be a subclass of parameter type,
	 * be allowed by parameter allowed set and be allowed by global set of constraints.
	 */
	public AllowedConstraintPredicate(@Nonnull AdditionalChildParameterDescriptor additionalChildParameter,
	                                  @Nonnull Set<Class<? extends Constraint<?>>> globallyAllowedConstraints,
	                                  @Nonnull Set<Class<? extends Constraint<?>>> globallyForbiddenConstraints) {
		this.baseConstraintType = resolveChildParameterBaseType(additionalChildParameter);
		this.locallyAllowedConstraints = Set.of();
		this.globallyAllowedConstraints = globallyAllowedConstraints;

		this.forbiddenConstraints = globallyForbiddenConstraints;
	}

	@Override
	public boolean test(ConstraintDescriptor constraintDescriptor) {
		return isConstraintAllowed(constraintDescriptor.constraintClass()) &&
			!isConstraintForbidden(constraintDescriptor.constraintClass());
	}

	@Nonnull
	private Class<? extends Constraint<?>> resolveChildParameterBaseType(@Nonnull ConstraintParameterDescriptor childParameter) {
		final Class<?> parameterType = childParameter.type();
		if (parameterType.isArray()) {
			//noinspection unchecked
			return (Class<? extends Constraint<?>>) parameterType.getComponentType();
		} else {
			//noinspection unchecked
			return (Class<? extends Constraint<?>>) parameterType;
		}
	}

	@Nonnull
	private Set<Class<? extends Constraint<?>>> resolveForbiddenConstraints(@Nonnull ChildParameterDescriptor childParameter,
	                                                                        @Nonnull Set<Class<? extends Constraint<?>>> globallyForbiddenConstraints) {
		final Set<Class<? extends Constraint<?>>> locallyForbiddenConstraints = childParameter.forbiddenChildTypes();
		return Stream.concat(
				globallyForbiddenConstraints.stream(),
				locallyForbiddenConstraints.stream()
			)
			.collect(Collectors.toSet());
	}

	private boolean isConstraintAllowed(@Nonnull Class<?> constraint) {
		return this.baseConstraintType.isAssignableFrom(constraint) &&
			isConstraintAllowed(constraint, this.globallyAllowedConstraints) &&
			isConstraintAllowed(constraint, this.locallyAllowedConstraints);
	}

	private boolean isConstraintAllowed(@Nonnull Class<?> constraint,
	                                    @Nonnull Set<Class<? extends Constraint<?>>> set) {
		if (set.isEmpty()) {
			return true;
		}
		return isConstraintInSet(constraint, set);
	}

	private boolean isConstraintForbidden(@Nonnull Class<?> constraint) {
		if (this.forbiddenConstraints.isEmpty()) {
			return false;
		}
		return isConstraintInSet(constraint, this.forbiddenConstraints);
	}

	/**
	 * Checks if the set of constraints either directly contains the constraint or the constraint is subclass of any
	 * class in the set.
	 */
	private boolean isConstraintInSet(@Nonnull Class<?> constraint,
	                                  @Nonnull Set<Class<? extends Constraint<?>>> set) {
		return set.stream().anyMatch(it -> it.isAssignableFrom(constraint));
	}
}
