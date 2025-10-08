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

package io.evitadb.api.query.descriptor;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ClassifierParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.FixedImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ParameterDescriptor;
import io.evitadb.api.query.filter.And;
import io.evitadb.exception.EvitaInternalError;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ConstraintDescriptor}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class ConstraintDescriptorTest {

	@Test
	void shouldCreateCorrectDescriptor() {
		assertDoesNotThrow(() -> createBaseFilterDescriptor(ConstraintPropertyType.GENERIC, "and"));
		assertDoesNotThrow(() -> createBaseFilterDescriptor(ConstraintPropertyType.GENERIC, "andSomething"));
		assertDoesNotThrow(() -> createBaseFilterDescriptor(
			ConstraintPropertyType.ATTRIBUTE,
			"equals",
			createCreator(new ClassifierParameterDescriptor("attributeName"))
		));
	}

	@Test
	void shouldNotAcceptIncorrectName() {
		assertThrows(EvitaInternalError.class, () -> createBaseFilterDescriptor(ConstraintPropertyType.GENERIC, ""));
		assertThrows(EvitaInternalError.class, () -> createBaseFilterDescriptor(ConstraintPropertyType.GENERIC, "OR"));
	}

	@Test
	void shouldNotAcceptClassifierPresentForGenericConstraint() {
		assertThrows(
			EvitaInternalError.class,
			() -> createBaseFilterDescriptor(
				ConstraintPropertyType.GENERIC,
				"and",
				createCreator(new ClassifierParameterDescriptor("name"))
			)
		);
	}

	@ParameterizedTest
	@MethodSource("comparableDescriptors")
	void shouldCompareDescriptors(ConstraintDescriptor d1, ConstraintDescriptor d2, int expectedOrder) {
		assertEquals(expectedOrder, d1.compareTo(d2));
	}

	private static Stream<Arguments> comparableDescriptors() {
		return Stream.of(
			// should equal
			Arguments.of(
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.ENTITY, "some", false, false),
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.ENTITY, "some", false, false),
				0
			),
			Arguments.of(
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.ENTITY, "some", true, false),
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.ENTITY, "some", true, false),
				0
			),
			Arguments.of(
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.ENTITY, "some", false, true),
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.ENTITY, "some", false, true),
				0
			),

			// should compare basic properties
			Arguments.of(
				createComparableDescriptor(ConstraintType.FILTER, ConstraintPropertyType.GENERIC, "some", false, false),
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.GENERIC, "some", false, false),
				-1
			),
			Arguments.of(
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.ATTRIBUTE, "some", false, false),
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.ENTITY, "some", false, false),
				1
			),
			Arguments.of(
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.GENERIC, "some", false, false),
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.GENERIC, "someA", false, false),
				-1
			),

			// should compare different types of classifiers
			Arguments.of(
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.ENTITY, "some", false, false),
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.ENTITY, "some", true, false),
				-1
			),
			Arguments.of(
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.ENTITY, "some", false, false),
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.ENTITY, "some", false, true),
				-1
			),
			Arguments.of(
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.ENTITY, "some", false, true),
				createComparableDescriptor(ConstraintType.ORDER, ConstraintPropertyType.ENTITY, "some", true, false),
				1
			)
		);
	}


	@Nonnull
	@SneakyThrows
	private static ConstraintDescriptor createComparableDescriptor(@Nonnull ConstraintType type,
	                                                               @Nonnull ConstraintPropertyType propertyType,
	                                                               @Nonnull String name,
	                                                               boolean implicitClassifier,
	                                                               boolean classifierParameter) {
		final ConstraintCreator creator;
		if (implicitClassifier) {
			creator = createCreator(new FixedImplicitClassifier("name"));
		} else if (classifierParameter) {
			creator = createCreator(new ClassifierParameterDescriptor("name"));
		} else {
			creator = createCreator();
		}

		return new ConstraintDescriptor(
			And.class,
			type,
			propertyType,
			name,
			"This is a description.",
			"/link",
			Set.of(ConstraintDomain.ENTITY),
			null,
			creator
		);
	}

	@Nonnull
	@SneakyThrows
	private ConstraintDescriptor createBaseFilterDescriptor(@Nonnull ConstraintPropertyType propertyType, @Nonnull String fullName) {
		return createBaseFilterDescriptor(
			propertyType,
			fullName,
			createCreator(
				new ChildParameterDescriptor(
					"children",
					FilterConstraint[].class,
					true,
					ConstraintDomain.DEFAULT,
					false,
					Set.of(),
					Set.of()
				)
			)
		);
	}

	@Nonnull
	private ConstraintDescriptor createBaseFilterDescriptor(@Nonnull ConstraintPropertyType propertyType, @Nonnull String name, @Nonnull ConstraintCreator creator) {
		return new ConstraintDescriptor(
			And.class,
			ConstraintType.FILTER,
			propertyType,
			name,
			"This is a description.",
			"/link",
			Set.of(ConstraintDomain.ENTITY),
			null,
			creator
		);
	}

	@Nonnull
	private static ConstraintCreator createCreator(@Nonnull ParameterDescriptor... parameters) throws NoSuchMethodException {
		return new ConstraintCreator(
			And.class.getConstructor(FilterConstraint[].class),
			List.of(parameters)
		);
	}

	@Nonnull
	private static ConstraintCreator createCreator(@Nonnull ImplicitClassifier implicitClassifier, @Nonnull ParameterDescriptor... parameters) throws NoSuchMethodException {
		return new ConstraintCreator(
			And.class.getConstructor(FilterConstraint[].class),
			List.of(parameters),
			implicitClassifier
		);
	}


}
