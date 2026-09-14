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

package io.evitadb.api.requestResponse.schema.mutation.engine;

import io.evitadb.api.EvitaContract;
import io.evitadb.api.exception.CatalogAlreadyPresentException;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;

/**
 * This test verifies {@link ModifyCatalogSchemaNameMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Tag(CONTRACT)
@Tag(SCHEMA)
public class ModifyCatalogSchemaNameMutationTest {

	@Test
	void shouldMutateCatalogSchema() {
		ModifyCatalogSchemaNameMutation mutation = new ModifyCatalogSchemaNameMutation("catalog", "newCatalog", true);
		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchema.version()).thenReturn(1);
		final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema);
		final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
		assertNull(result.entitySchemaMutations());
		assertEquals(2, newCatalogSchema.version());
		assertEquals("newCatalog", newCatalogSchema.getName());
	}

	@Test
	void shouldRefuseAFreeTargetNameThatCollidesInANamingConvention() {
		// `overwriteTarget` says the caller is willing to take a target over - it does not say there is one to
		// take over. With nothing holding `myCatalog`, this is a rename into a new name, and a new name has to
		// clear the same uniqueness bar as any other: `my_catalog` already occupies it in camel case.
		final ModifyCatalogSchemaNameMutation mutation =
			new ModifyCatalogSchemaNameMutation("catalog", "myCatalog", true);

		assertThrows(
			CatalogAlreadyPresentException.class,
			() -> mutation.verifyApplicability(evitaHolding("catalog", "my_catalog"))
		);
	}

	@Test
	void shouldNotRevalidateTheNameOfAnOccupiedTarget() {
		// The opposite case, and the reason the check cannot simply be run unconditionally: `myCatalog` is held
		// by the catalog being replaced, so it collides with itself in every convention. Replacing it only
		// removes a name from the set, which cannot break a uniqueness that already held.
		final ModifyCatalogSchemaNameMutation mutation =
			new ModifyCatalogSchemaNameMutation("catalog", "myCatalog", true);

		assertDoesNotThrow(() -> mutation.verifyApplicability(evitaHolding("catalog", "myCatalog")));
	}

	@Test
	void shouldAllowAReplaceWhoseOnlyCollisionIsTheDepartingSource() {
		// `my-catalog` and `myCatalog` are the same name in camel case, and the catalog holding the second one is
		// the very catalog this mutation renames away. The name it collides with leaves the set in the same act
		// that introduces the new one, so the set that results is unique and the operation is legitimate.
		final ModifyCatalogSchemaNameMutation mutation =
			new ModifyCatalogSchemaNameMutation("myCatalog", "my-catalog", true);

		assertDoesNotThrow(() -> mutation.verifyApplicability(evitaHolding("myCatalog")));
	}

	@Test
	void shouldAllowARenameWhoseOnlyCollisionIsTheDepartingSource() {
		// the same thing without the overwrite flag: a plain rename between two spellings of one name. This path
		// has always run the uniqueness check, so it refused this rename for as long as it existed.
		final ModifyCatalogSchemaNameMutation mutation =
			new ModifyCatalogSchemaNameMutation("myCatalog", "my-catalog", false);

		assertDoesNotThrow(() -> mutation.verifyApplicability(evitaHolding("myCatalog")));
	}

	@Test
	void shouldRefuseAnOccupiedTargetWithoutTheOverwriteFlag() {
		final ModifyCatalogSchemaNameMutation mutation =
			new ModifyCatalogSchemaNameMutation("catalog", "myCatalog", false);

		assertThrows(
			InvalidSchemaMutationException.class,
			() -> mutation.verifyApplicability(evitaHolding("catalog", "myCatalog"))
		);
	}

	/**
	 * Builds an engine answering with exactly the given catalog names.
	 *
	 * @param catalogNames names the engine is to report
	 * @return the mock
	 */
	@Nonnull
	private static EvitaContract evitaHolding(@Nonnull String... catalogNames) {
		final EvitaContract evita = Mockito.mock(EvitaContract.class);
		Mockito.when(evita.getCatalogNames()).thenReturn(Set.of(catalogNames));
		return evita;
	}

}
