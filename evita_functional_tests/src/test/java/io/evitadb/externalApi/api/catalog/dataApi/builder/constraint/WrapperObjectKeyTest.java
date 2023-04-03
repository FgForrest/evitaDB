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
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
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
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), null),
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), null)
		);
		assertEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), null)
				.hashCode(),
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), null)
				.hashCode()
		);
		assertEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), null),
			new WrapperObjectKey(ConstraintType.ORDER, new EntityDataLocator("category"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), null)
		);
		assertEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), null)
				.hashCode(),
			new WrapperObjectKey(ConstraintType.ORDER, new EntityDataLocator("category"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), null)
				.hashCode()
		);
	}

	@Test
	void shouldEqualsWhenComplexStructure() {
		assertEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of())),
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
		);
		assertEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
				.hashCode(),
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
				.hashCode()
		);
		assertEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of())),
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
		);
		assertEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
				.hashCode(),
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
				.hashCode()
		);
	}

	@Test
	void shouldNotEqualsWhenComplexStructure() {
		assertNotEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of())),
			new WrapperObjectKey(ConstraintType.ORDER, new EntityDataLocator("category"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
		);
		assertNotEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
				.hashCode(),
			new WrapperObjectKey(ConstraintType.ORDER, new EntityDataLocator("category"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
				.hashCode()
		);
		assertNotEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), null),
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
		);
		assertNotEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), null)
				.hashCode(),
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
				.hashCode()
		);
	}

	@Test
	void shouldGenerateSameHashes() {
		assertEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
				.toHash(),
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
				.toHash()
		);
		assertEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), null)
				.toHash(),
			new WrapperObjectKey(ConstraintType.ORDER, new EntityDataLocator("category"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), null)
				.toHash()
		);
		assertEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
				.toHash(),
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
				.toHash()
		);
	}

	@Test
	void shouldNotGenerateSameHashes() {
		assertNotEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
				.toHash(),
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("name", String.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
				.toHash()
		);
		assertNotEquals(
			new WrapperObjectKey(ConstraintType.FILTER, new EntityDataLocator("product"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
				.toHash(),
			new WrapperObjectKey(ConstraintType.ORDER, new EntityDataLocator("category"), List.of(new ValueParameterDescriptor("id", Integer.class, true, false)), new ChildParameterDescriptor("with", FilterConstraint.class, true, false, Set.of(), Set.of()))
				.toHash()
		);
	}
}