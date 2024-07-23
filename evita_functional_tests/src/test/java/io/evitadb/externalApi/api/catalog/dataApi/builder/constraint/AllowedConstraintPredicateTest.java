/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.AttributeStartsWith;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.Strip;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AllowedConstraintPredicate}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class AllowedConstraintPredicateTest {

	@ParameterizedTest
	@MethodSource("allowedConstraints")
	void shouldAllowConstraint(Class<? extends Constraint<?>> constraintClass,
	                           Set<Class<? extends Constraint<?>>> allowed,
	                           Set<Class<? extends Constraint<?>>> forbidden,
	                           Class<? extends Constraint<?>> testedConstraint) {
		final AllowedConstraintPredicate predicate = new AllowedConstraintPredicate(
			ConstraintDescriptorProvider.getConstraint(constraintClass).creator().childParameters().get(0),
			allowed,
			forbidden
		);
		assertTrue(predicate.test(ConstraintDescriptorProvider.getConstraint(testedConstraint)));
	}

	@Nonnull
	static Stream<Arguments> allowedConstraints() {
		return Stream.of(
			Arguments.of(And.class, Set.of(), Set.of(), AttributeEquals.class),
			Arguments.of(And.class, Set.of(And.class, AttributeEquals.class), Set.of(), AttributeEquals.class),
			Arguments.of(And.class, Set.of(), Set.of(And.class), AttributeEquals.class),
			Arguments.of(And.class, Set.of(And.class, AttributeEquals.class, Or.class), Set.of(Or.class, AttributeStartsWith.class), AttributeEquals.class),
			Arguments.of(EntityFetch.class, Set.of(HierarchyContent.class), Set.of(), HierarchyContent.class)
		);
	}

	@ParameterizedTest
	@MethodSource("notAllowedConstraints")
	void shouldNotAllowConstraint(Class<? extends Constraint<?>> constraintClass,
	                              Set<Class<? extends Constraint<?>>> allowed,
	                              Set<Class<? extends Constraint<?>>> forbidden,
	                              Class<? extends Constraint<?>> testedConstraint) {
		final AllowedConstraintPredicate predicate = new AllowedConstraintPredicate(
			ConstraintDescriptorProvider.getConstraint(constraintClass).creator().childParameters().get(0),
			allowed,
			forbidden
		);
		assertFalse(predicate.test(ConstraintDescriptorProvider.getConstraint(testedConstraint)));
	}

	@Nonnull
	static Stream<Arguments> notAllowedConstraints() {
		return Stream.of(
			Arguments.of(And.class, Set.of(And.class, AttributeEquals.class), Set.of(), EntityLocaleEquals.class),
			Arguments.of(And.class, Set.of(), Set.of(), AttributeNatural.class),
			Arguments.of(EntityFetch.class, Set.of(Strip.class), Set.of(), Strip.class),
			Arguments.of(UserFilter.class, Set.of(), Set.of(AttributeEquals.class), AttributeEquals.class)
		);
	}
}
