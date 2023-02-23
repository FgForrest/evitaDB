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

package io.evitadb.externalApi.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import lombok.AccessLevel;
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
public class AllowedConstraintPredicate implements Predicate<ConstraintDescriptor> {

	private final Set<Class<? extends Constraint<?>>> allowedConstraints;
	private final Set<Class<? extends Constraint<?>>> forbiddenConstraints;

	/**
	 * Creates predicate from children parameter descriptor and its allowed/forbidden children.
	 */
	public AllowedConstraintPredicate(@Nonnull ChildParameterDescriptor childParameter) {
		this.allowedConstraints = childParameter.allowedChildTypes();
		this.forbiddenConstraints = childParameter.forbiddenChildTypes();
	}

	/**
	 * Creates predicate from children parameter descriptor and its allowed/forbidden children but these are
	 * restricted by {@code allowedConstraints}/{@code forbiddenConstraints} rules.
	 */
	public AllowedConstraintPredicate(@Nonnull ChildParameterDescriptor childParameter,
	                                  @Nonnull Set<Class<? extends Constraint<?>>> allowedConstraints,
	                                  @Nonnull Set<Class<? extends Constraint<?>>> forbiddenConstraints) {
		if (allowedConstraints.isEmpty() && childParameter.allowedChildTypes().isEmpty()) {
			final Class<? extends Constraint<?>> constraintBaseType;
			final Class<?> childParameterType = childParameter.type();
			if (childParameterType.isArray()) {
				//noinspection unchecked
				constraintBaseType = (Class<? extends Constraint<?>>) childParameterType.getComponentType();
			} else {
				//noinspection unchecked
				constraintBaseType = (Class<? extends Constraint<?>>) childParameterType;
			}

			this.allowedConstraints = Set.of(constraintBaseType);
		} else if (allowedConstraints.isEmpty()) {
			this.allowedConstraints = childParameter.allowedChildTypes();
		} else if (childParameter.allowedChildTypes().isEmpty()) {
			this.allowedConstraints = allowedConstraints;
		} else {
			// if both sets are not empty we want to do an intersection of both sets
			this.allowedConstraints = childParameter.allowedChildTypes()
				.stream()
				.filter(allowedConstraints::contains)
				.collect(Collectors.toSet());
		}

		this.forbiddenConstraints = Stream.concat(
				childParameter.forbiddenChildTypes().stream(),
				forbiddenConstraints.stream()
			)
			.collect(Collectors.toSet());
	}


	@Override
	public boolean test(@Nonnull ConstraintDescriptor constraintDescriptor) {
		if (allowedConstraints.isEmpty() && forbiddenConstraints.isEmpty()) {
			return true;
		} else if (!allowedConstraints.isEmpty() && !forbiddenConstraints.isEmpty()) {
			return testConstraint(allowedConstraints, constraintDescriptor.constraintClass()) &&
				!testConstraint(forbiddenConstraints, constraintDescriptor.constraintClass());
		} else if (!allowedConstraints.isEmpty()) {
			return testConstraint(allowedConstraints, constraintDescriptor.constraintClass());
		} else {
			return !testConstraint(forbiddenConstraints, constraintDescriptor.constraintClass());
		}
	}

	private boolean testConstraint(@Nonnull Set<Class<? extends Constraint<?>>> constraintSet,
	                               @Nonnull Class<?> testedConstraint) {
		return constraintSet.stream()
			.anyMatch(constraint -> constraint.isAssignableFrom(testedConstraint));
	}
}
