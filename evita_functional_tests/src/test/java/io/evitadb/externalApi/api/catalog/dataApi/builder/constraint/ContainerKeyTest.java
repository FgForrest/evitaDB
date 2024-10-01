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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.Not;
import io.evitadb.api.query.filter.Or;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ExternalEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ReferenceDataLocator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for {@link ContainerKey}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class ContainerKeyTest {

	@Test
	void shouldKeysBeEqual() {
		assertEquals(
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("product")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(), Set.of(), Set.of())
			),
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("product")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(), Set.of(), Set.of())
			)
		);
		assertEquals(
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("product")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(And.class, Or.class), Set.of(And.class), Set.of(Not.class))
			),
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("product")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(And.class, Or.class), Set.of(And.class), Set.of(Not.class))
			)
		);
	}

	@Test
	void shouldNotKeyBeEqual() {
		assertNotEquals(
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("product")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(), Set.of(), Set.of())
			),
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("order")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(), Set.of(), Set.of())
			)
		);
		assertNotEquals(
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("product")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(And.class, Or.class), Set.of(),  Set.of(Not.class))
			),
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("product")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(And.class), Set.of(), Set.of(Or.class, Not.class))
			)
		);
	}

	@Test
	void shouldGenerateSameHashes() {
		assertEquals(
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("product")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(), Set.of(), Set.of())
			).toHash(),
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("product")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(), Set.of(), Set.of())
			).toHash()
		);
		assertEquals(
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("product")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(And.class, Or.class), Set.of(And.class), Set.of(Not.class))
			).toHash(),
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("product")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(And.class, Or.class), Set.of(And.class), Set.of(Not.class))
			).toHash()
		);
	}

	@Test
	void shouldNotGenerateSameHashes() {
		assertNotEquals(
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("product")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(), Set.of(), Set.of())
			).toHash(),
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("order")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(), Set.of(), Set.of())
			).toHash()
		);
		assertNotEquals(
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("product")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(And.class, Or.class), Set.of(),  Set.of(Not.class))
			).toHash(),
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("product")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(And.class), Set.of(), Set.of(Or.class, Not.class))
			).toHash()
		);
	}

	@Test
	void shouldGenerateSpecificHashes() {
		assertEquals(
			"9921bb2864830a2a",
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ManagedEntityTypePointer("product")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(And.class, Or.class), Set.of(),  Set.of(Not.class))
			).toHash()
		);
		assertEquals(
			"c0637b3fd0fe2a6d",
			new ContainerKey(
				ConstraintType.FILTER,
				new ReferenceDataLocator(new ManagedEntityTypePointer("product"), "groups"),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(And.class, Or.class), Set.of(),  Set.of(Not.class))
			).toHash()
		);
		assertEquals(
			"751a19ce28527c46",
			new ContainerKey(
				ConstraintType.FILTER,
				new EntityDataLocator(new ExternalEntityTypePointer("tagCategory")),
				new AllowedConstraintPredicate(FilterConstraint.class, Set.of(And.class, Or.class), Set.of(),  Set.of(Not.class))
			).toHash()
		);
	}
}
