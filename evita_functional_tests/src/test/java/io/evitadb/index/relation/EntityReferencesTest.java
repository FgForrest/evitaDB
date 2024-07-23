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

package io.evitadb.index.relation;

import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies behaviour of {@link EntityReferences} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class EntityReferencesTest {

	@Test
	void shouldAddMultiplePks() {
		final EntityReferences ref1 = new EntityReferences(1);
		final EntityReferences ref2 = ref1.withReferencedEntityPrimaryKey(0, 2, "abc");
		final EntityReferences ref3 = ref2.withReferencedEntityPrimaryKey(0, 3, "abc");

		assertArrayEquals(
			new int[] {1, 2, 3},
			ref3.referencedEntityPrimaryKeys()
		);

		assertArrayEquals(
			new int[0],
			ref3.referencedEntityGroupPrimaryKeys()
		);
	}

	@Test
	void shouldAddAndRemovePks() {
		final EntityReferences ref1 = new EntityReferences(1);
		final EntityReferences ref2 = ref1.withReferencedEntityPrimaryKey(0, 2, "abc");
		final EntityReferences ref3 = ref2.withReferencedEntityPrimaryKey(0, 3, "abc");
		final EntityReferences ref4 = ref3.withoutReferencedEntityPrimaryKey(0, 1, "abc");
		final EntityReferences ref5 = ref4.withoutReferencedEntityPrimaryKey(0, 3, "abc");
		final EntityReferences ref6 = ref5.withoutReferencedEntityPrimaryKey(0, 2, "abc");

		assertArrayEquals(
			new int[] {2, 3},
			ref4.referencedEntityPrimaryKeys()
		);
		assertFalse(ref4.isEmpty());

		assertArrayEquals(
			new int[] {2},
			ref5.referencedEntityPrimaryKeys()
		);
		assertFalse(ref5.isEmpty());

		assertArrayEquals(
			new int[0],
			ref6.referencedEntityPrimaryKeys()
		);
		assertTrue(ref6.isEmpty());
	}

	@Test
	void shouldFailToAddDuplicatePks() {
		final EntityReferences ref1 = new EntityReferences(1);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> ref1.withReferencedEntityPrimaryKey(0, 1, "abc")
		);
	}

	@Test
	void shouldAddMultipleGroupPks() {
		final EntityReferences ref1 = new EntityReferences(1, 1);
		final EntityReferences ref2 = ref1.withReferencedEntityGroupPrimaryKey(2);
		final EntityReferences ref3 = ref2.withReferencedEntityGroupPrimaryKey(3);
		final EntityReferences ref4 = ref3.withReferencedEntityGroupPrimaryKey(2);

		assertArrayEquals(
			new int[] {1, 2, 2, 3},
			ref4.referencedEntityGroupPrimaryKeys()
		);

		assertArrayEquals(
			new int[] {1},
			ref4.referencedEntityPrimaryKeys()
		);
	}

	@Test
	void shouldAddAndRemoveGroupPks() {
		final EntityReferences ref1 = new EntityReferences(1, 1);
		final EntityReferences ref2 = ref1.withReferencedEntityGroupPrimaryKey(2);
		final EntityReferences ref3 = ref2.withReferencedEntityGroupPrimaryKey(3);
		final EntityReferences ref4 = ref3.withReferencedEntityGroupPrimaryKey(2);
		final EntityReferences ref5 = ref4.withoutReferencedEntityGroupPrimaryKey(0, 2, "abc");
		final EntityReferences ref6 = ref5.withoutReferencedEntityGroupPrimaryKey(0, 3, "abc");
		final EntityReferences ref7 = ref6.withoutReferencedEntityGroupPrimaryKey(0, 1, "abc");
		final EntityReferences ref8 = ref7.withoutReferencedEntityGroupPrimaryKey(0, 2, "abc");

		assertArrayEquals(
			new int[] {1, 2, 3},
			ref5.referencedEntityGroupPrimaryKeys()
		);
		assertFalse(ref5.isEmpty());

		assertArrayEquals(
			new int[] {1, 2},
			ref6.referencedEntityGroupPrimaryKeys()
		);
		assertFalse(ref6.isEmpty());

		assertArrayEquals(
			new int[] {2},
			ref7.referencedEntityGroupPrimaryKeys()
		);
		assertFalse(ref7.isEmpty());

		assertArrayEquals(
			new int[0],
			ref8.referencedEntityGroupPrimaryKeys()
		);

		assertTrue(ref8.withoutReferencedEntityPrimaryKey(0, 1, "abc").isEmpty());
	}

}
