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

package io.evitadb.api.query.descriptor;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ClassifierParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ParameterDescriptor;
import io.evitadb.api.query.filter.And;
import io.evitadb.exception.EvitaInternalError;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
			Set.of(ConstraintDomain.ENTITY),
			null,
			creator
		);
	}

	@Nonnull
	private static ConstraintCreator createCreator(@Nonnull ParameterDescriptor... parameters) throws NoSuchMethodException {
		return new ConstraintCreator(
			And.class.getConstructor(FilterConstraint[].class),
			List.of(parameters),
			null
		);
	}
}