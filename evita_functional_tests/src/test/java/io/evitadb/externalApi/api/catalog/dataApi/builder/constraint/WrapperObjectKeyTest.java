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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.AdditionalChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.Not;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for {@link WrapperObjectKey}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class WrapperObjectKeyTest {

	@ParameterizedTest
	@MethodSource("equalingFlatStructureKeys")
	void shouldEqualsWhenFlatStructure(WrapperObjectKey keyA, WrapperObjectKey keyB) {
		assertEquals(keyA, keyB);
		assertEquals(keyA.hashCode(), keyB.hashCode());
		assertEquals(keyA.toHash(), keyB.toHash());
	}

	@Nonnull
	static Stream<Arguments> equalingFlatStructureKeys() {
		return Stream.of(
			// comparing same definition
			Arguments.of(
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					List.of(new ValueParameterDescriptor("id", Integer.class, true, false))
				),
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					List.of(new ValueParameterDescriptor("id", Integer.class, true, false))
				)
			),
			// comparing different definitions which same key parts
			Arguments.of(
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					List.of(new ValueParameterDescriptor("id", Integer.class, true, false))
				),
				new WrapperObjectKey(
					ConstraintType.ORDER,
					new EntityDataLocator(new ManagedEntityTypePointer("category")),
					List.of(new ValueParameterDescriptor("id", Integer.class, true, false))
				)
			)
		);
	}

	@ParameterizedTest
	@MethodSource("equalingComplexStructureKeys")
	void shouldEqualsWhenComplexStructure(WrapperObjectKey keyA, WrapperObjectKey keyB) {
		assertEquals(keyA, keyB);
		assertEquals(keyA.hashCode(), keyB.hashCode());
		assertEquals(keyA.toHash(), keyB.toHash());
	}

	@Nonnull
	static Stream<Arguments> equalingComplexStructureKeys() {
		return Stream.of(
			// same properties
			Arguments.of(
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					List.of(
						new ValueParameterDescriptor("id", Integer.class, true, false)
					),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
					)
				),
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					List.of(
						new ValueParameterDescriptor("id", Integer.class, true, false)
					),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
					)
				)
			),
			// same properties with only child parameter
			Arguments.of(
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
					),
					Map.of()
				),
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
					),
					Map.of()
				)
			),
			// same properties with only additional child parameter
			Arguments.of(
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					Map.of(),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
					)
				),
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					Map.of(),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
					)
				)
			),
			// dynamic child domain with same data locator
			Arguments.of(
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new HierarchyDataLocator(new ManagedEntityTypePointer("product"), "category"),
					List.of(
						new ValueParameterDescriptor("id", Integer.class, true, false)
					),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET, false, Set.of(), Set.of())
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
					)
				),
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new HierarchyDataLocator(new ManagedEntityTypePointer("product"), "category"),
					List.of(
						new ValueParameterDescriptor("id", Integer.class, true, false)
					),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET, false, Set.of(), Set.of())
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
					)
				)
			),
			// dynamic additional child domain with same data locator
			Arguments.of(
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new HierarchyDataLocator(new ManagedEntityTypePointer("product"), "category"),
					List.of(
						new ValueParameterDescriptor("id", Integer.class, true, false)
					),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET)
					)
				),
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new HierarchyDataLocator(new ManagedEntityTypePointer("product"), "category"),
					List.of(
						new ValueParameterDescriptor("id", Integer.class, true, false)
					),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET)
					)
				)
			),
			// same properties with global constraint settings
			Arguments.of(
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					constructChildParameterForKey(
						new ChildParameterDescriptor("filtering", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of()),
						Set.of(And.class), Set.of(Not.class)
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT),
						Set.of(AttributeNatural.class), Set.of(PriceNatural.class)
					)
				),
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					constructChildParameterForKey(
						new ChildParameterDescriptor("filtering", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of()),
						Set.of(And.class), Set.of(Not.class)
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT),
						Set.of(AttributeNatural.class), Set.of(PriceNatural.class)
					)
				)
			)
		);
	}

	@ParameterizedTest
	@MethodSource("notEqualingComplexStructureKeys")
	void shouldNotEqualsWhenComplexStructure(WrapperObjectKey keyA, WrapperObjectKey keyB) {
		assertNotEquals(keyA, keyB);
		assertNotEquals(keyA.hashCode(), keyB.hashCode());
		assertNotEquals(keyA.toHash(), keyB.toHash());
	}

	@Nonnull
	static Stream<Arguments> notEqualingComplexStructureKeys() {
		return Stream.of(
			// different data locator
			Arguments.of(
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					List.of(
						new ValueParameterDescriptor("id", Integer.class, true, false)
					),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
					)
				),
				new WrapperObjectKey(
					ConstraintType.ORDER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					List.of(
						new ValueParameterDescriptor("id", Integer.class, true, false)
					),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
					)
				)
			),
			// different parameters
			Arguments.of(
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					List.of(
						new ValueParameterDescriptor("id", Integer.class, true, false)
					)
				),
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
					),
					Map.of()
				)
			),
			// different children parameters
			Arguments.of(
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
					),
					Map.of()
				),
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					Map.of(),
					constructAdditionalChildParameterForKey(new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT))
				)
			),
			// dynamic child domain with different data locator
			Arguments.of(
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new HierarchyDataLocator(new ManagedEntityTypePointer("product")),
					List.of(
						new ValueParameterDescriptor("id", Integer.class, true, false)
					),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET, false, Set.of(), Set.of())
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
					)
				),
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new HierarchyDataLocator(new ManagedEntityTypePointer("product"), "category"),
					List.of(
						new ValueParameterDescriptor("id", Integer.class, true, false)
					),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET, false, Set.of(), Set.of())
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
					)
				)
			),
			// dynamic additional child domain with different data locator
			Arguments.of(
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new HierarchyDataLocator(new ManagedEntityTypePointer("product")),
					List.of(
						new ValueParameterDescriptor("id", Integer.class, true, false)
					),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET)
					)
				),
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new HierarchyDataLocator(new ManagedEntityTypePointer("product"), "category"),
					List.of(
						new ValueParameterDescriptor("id", Integer.class, true, false)
					),
					constructChildParameterForKey(
						new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET)
					)
				)
			),
			// same properties with different global constraint settings
			Arguments.of(
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					constructChildParameterForKey(
						new ChildParameterDescriptor("filtering", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of()),
						Set.of(And.class), Set.of()
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT),
						Set.of(AttributeNatural.class), Set.of(PriceNatural.class)
					)
				),
				new WrapperObjectKey(
					ConstraintType.FILTER,
					new EntityDataLocator(new ManagedEntityTypePointer("product")),
					constructChildParameterForKey(
						new ChildParameterDescriptor("filtering", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of()),
						Set.of(And.class), Set.of(Not.class)
					),
					constructAdditionalChildParameterForKey(
						new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT),
						Set.of(AttributeNatural.class), Set.of()
					)
				)
			)
		);
	}

	@Nonnull
	private static Map<ChildParameterDescriptor, AllowedConstraintPredicate> constructChildParameterForKey(
		@Nonnull ChildParameterDescriptor childParameter
	) {
		return constructChildParameterForKey(childParameter, Set.of(), Set.of());
	}

	@Nonnull
	private static Map<ChildParameterDescriptor, AllowedConstraintPredicate> constructChildParameterForKey(
		@Nonnull ChildParameterDescriptor childParameter,
		@Nonnull Set<Class<? extends Constraint<?>>> globallyAllowedConstraints,
		@Nonnull Set<Class<? extends Constraint<?>>> globallyForbiddenConstraints
	) {
		return Map.of(
			childParameter,
			new AllowedConstraintPredicate(childParameter, globallyAllowedConstraints, globallyForbiddenConstraints)
		);
	}

	@Nonnull
	private static Map<AdditionalChildParameterDescriptor, AllowedConstraintPredicate> constructAdditionalChildParameterForKey(
		@Nonnull AdditionalChildParameterDescriptor additionalChildParameter
	) {
		return constructAdditionalChildParameterForKey(additionalChildParameter, Set.of(), Set.of());
	}

	@Nonnull
	private static Map<AdditionalChildParameterDescriptor, AllowedConstraintPredicate> constructAdditionalChildParameterForKey(
		@Nonnull AdditionalChildParameterDescriptor additionalChildParameter,
		@Nonnull Set<Class<? extends Constraint<?>>> globallyAllowedConstraints,
		@Nonnull Set<Class<? extends Constraint<?>>> globallyForbiddenConstraints
	) {
		return Map.of(
			additionalChildParameter,
			new AllowedConstraintPredicate(additionalChildParameter, globallyAllowedConstraints, globallyForbiddenConstraints)
		);
	}
}
