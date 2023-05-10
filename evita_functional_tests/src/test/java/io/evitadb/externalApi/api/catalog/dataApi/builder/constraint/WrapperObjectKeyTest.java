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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.AdditionalChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for {@link WrapperObjectKey}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class WrapperObjectKeyTest {

	@Test
	void shouldEqualsWhenFlatStructure() {
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(),
				List.of()
			),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(),
				List.of()
			)
		);
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(),
				List.of()
			)
				.hashCode(),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(),
				List.of()
			)
				.hashCode()
		);
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(),
				List.of()
			),
			new WrapperObjectKey(
				ConstraintType.ORDER,
				new EntityDataLocator("category"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(),
				List.of()
			)
		);
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(),
				List.of()
			)
				.hashCode(),
			new WrapperObjectKey(
				ConstraintType.ORDER,
				new EntityDataLocator("category"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(),
				List.of()
			)
				.hashCode()
		);
	}

	@Test
	void shouldEqualsWhenComplexStructure() {
		// same properties
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
		);
		// same properties
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.hashCode(),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.hashCode()
		);
		// same properties with only child parameter
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of()
			),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of()
			)
		);
		// same properties with only child parameter
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of()
			)
				.hashCode(),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of()
			)
				.hashCode()
		);
		// same properties with only additional child parameter
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(),
				List.of(),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(),
				List.of(),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
		);
		// same properties with only additional child parameter
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(),
				List.of(),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.hashCode(),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(),
				List.of(),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.hashCode()
		);
		// dynamic child domain with same data locator
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new HierarchyDataLocator("product", "category"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new HierarchyDataLocator("product", "category"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
		);
		// dynamic additional child domain with same data locator
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new HierarchyDataLocator("product", "category"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET)
				)
			),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new HierarchyDataLocator("product", "category"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET)
				)
			)
		);
	}

	@Test
	void shouldNotEqualsWhenComplexStructure() {
		// different data locator
		assertNotEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			),
			new WrapperObjectKey(
				ConstraintType.ORDER,
				new EntityDataLocator("category"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
		);
		// different data locator
		assertNotEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.hashCode(),
			new WrapperObjectKey(
				ConstraintType.ORDER,
				new EntityDataLocator("category"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.hashCode()
		);
		// different parameters
		assertNotEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(),
				List.of()
			),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of()
			)
		);
		// different parameters
		assertNotEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(),
				List.of()
			)
				.hashCode(),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of()
			)
				.hashCode()
		);
		// different children parameters
		assertNotEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of()
			),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(),
				List.of(),
				List.of(new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT))
			)
		);
		// different children parameters
		assertNotEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of()
			)
				.hashCode(),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(),
				List.of(),
				List.of(new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT))
			)
				.hashCode()
		);
		// dynamic child domain with different data locator
		assertNotEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new HierarchyDataLocator("category"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new HierarchyDataLocator("product", "category"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
		);
		// dynamic additional child domain with different data locator
		assertNotEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new HierarchyDataLocator("category"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET)
				)
			),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new HierarchyDataLocator("product", "category"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET)
				)
			)
		);
	}

	@Test
	void shouldGenerateSameHashes() {
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.toHash(),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.toHash()
		);
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(),
				List.of()
			)
				.toHash(),
			new WrapperObjectKey(
				ConstraintType.ORDER,
				new EntityDataLocator("category"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(),
				List.of()
			)
				.toHash()
		);
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.toHash(),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.toHash()
		);
		// resolved to same child domain
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("name", String.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.toHash(),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("name", String.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.ENTITY, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.toHash()
		);
		// resolved to same additional child domain
		assertEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("name", String.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.toHash(),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("name", String.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.ENTITY)
				)
			)
				.toHash()
		);
	}

	@Test
	void shouldNotGenerateSameHashes() {
		// different value parameter name
		assertNotEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.toHash(),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("name", String.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.toHash()
		);
		// different data locator
		assertNotEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new EntityDataLocator("product"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.toHash(),
			new WrapperObjectKey(
				ConstraintType.ORDER,
				new EntityDataLocator("category"),
				List.of(
					new ValueParameterDescriptor("id", Integer.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.toHash()
		);

		// dynamic child domain
		assertNotEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new HierarchyDataLocator("category"),
				List.of(
					new ValueParameterDescriptor("name", String.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.toHash(),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new HierarchyDataLocator("product", "category"),
				List.of(
					new ValueParameterDescriptor("name", String.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.DEFAULT)
				)
			)
				.toHash()
		);
		// dynamic additional child domain
		assertNotEquals(
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new HierarchyDataLocator("category"),
				List.of(
					new ValueParameterDescriptor("name", String.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET)
				)
			)
				.toHash(),
			new WrapperObjectKey(
				ConstraintType.FILTER,
				new HierarchyDataLocator("product", "category"),
				List.of(
					new ValueParameterDescriptor("name", String.class, true, false)
				),
				List.of(
					new ChildParameterDescriptor("with", FilterConstraint.class, true, ConstraintDomain.DEFAULT, false, Set.of(), Set.of())
				),
				List.of(
					new AdditionalChildParameterDescriptor(ConstraintType.ORDER, "orderBy", OrderConstraint.class, true, ConstraintDomain.HIERARCHY_TARGET)
				)
			)
				.toHash()
		);
	}
}