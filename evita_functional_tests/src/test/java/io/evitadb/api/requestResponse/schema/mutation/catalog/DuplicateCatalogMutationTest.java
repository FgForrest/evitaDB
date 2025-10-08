/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.schema.mutation.catalog;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test verifies {@link DuplicateCatalogMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("DuplicateCatalogMutation tests")
public class DuplicateCatalogMutationTest {

    @Test
    @DisplayName("Should throw exception when source catalog doesn't exist")
    void shouldThrowExceptionWhenSourceCatalogDoesntExist() {
        DuplicateCatalogMutation mutation = new DuplicateCatalogMutation("nonExistentCatalog", "newCatalog");

        // Mock EvitaContract
        EvitaContract evita = Mockito.mock(EvitaContract.class);
        Mockito.when(evita.getCatalogNames()).thenReturn(Set.of("existingCatalog"));

        // Verify that an exception is thrown
        InvalidMutationException exception = assertThrows(
            InvalidMutationException.class,
            () -> mutation.verifyApplicability(evita)
        );

        // Verify the exception message
        assertEquals("Catalog `nonExistentCatalog` doesn't exist!", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw exception when target catalog already exists")
    void shouldThrowExceptionWhenTargetCatalogAlreadyExists() {
        DuplicateCatalogMutation mutation = new DuplicateCatalogMutation("existingCatalog", "alreadyExistingCatalog");

        // Mock EvitaContract
        EvitaContract evita = Mockito.mock(EvitaContract.class);
        Mockito.when(evita.getCatalogNames()).thenReturn(Set.of("existingCatalog", "alreadyExistingCatalog"));
        Mockito.when(evita.getCatalogState("existingCatalog")).thenReturn(Optional.of(CatalogState.ALIVE));

        // Verify that an exception is thrown
        InvalidMutationException exception = assertThrows(
            InvalidMutationException.class,
            () -> mutation.verifyApplicability(evita)
        );

        // Verify the exception message
        assertEquals("Catalog `alreadyExistingCatalog` already exists!", exception.getMessage());
    }

    @Test
    @DisplayName("Should not throw exception when source catalog exists and target catalog doesn't exist")
    void shouldNotThrowExceptionWhenSourceCatalogExistsAndTargetCatalogDoesntExist() {
        DuplicateCatalogMutation mutation = new DuplicateCatalogMutation("existingCatalog", "newCatalog");

        // Mock EvitaContract
        EvitaContract evita = Mockito.mock(EvitaContract.class);
        Mockito.when(evita.getCatalogNames()).thenReturn(Set.of("existingCatalog"));
        Mockito.when(evita.getCatalogState("existingCatalog")).thenReturn(Optional.of(CatalogState.ALIVE));

        // Verify that no exception is thrown
        assertDoesNotThrow(() -> mutation.verifyApplicability(evita));
    }

    @Test
    @DisplayName("Should throw exception when source catalog is in invalid state")
    void shouldThrowExceptionWhenSourceCatalogIsInInvalidState() {
        DuplicateCatalogMutation mutation = new DuplicateCatalogMutation("existingCatalog", "newCatalog");

        EvitaContract evita = Mockito.mock(EvitaContract.class);
        Mockito.when(evita.getCatalogNames()).thenReturn(Set.of("existingCatalog"));
        Mockito.when(evita.getCatalogState("existingCatalog")).thenReturn(Optional.of(CatalogState.CORRUPTED));

        InvalidMutationException exception = assertThrows(
            InvalidMutationException.class,
            () -> mutation.verifyApplicability(evita)
        );

        assertEquals("Catalog `existingCatalog` is not in a valid state for this operation! Current state: CORRUPTED", exception.getMessage());
    }

    @Test
    @DisplayName("Should allow duplicating ALIVE catalog")
    void shouldAllowDuplicatingAliveCatalog() {
        DuplicateCatalogMutation mutation = new DuplicateCatalogMutation("existingCatalog", "newCatalog");

        EvitaContract evita = Mockito.mock(EvitaContract.class);
        Mockito.when(evita.getCatalogNames()).thenReturn(Set.of("existingCatalog"));
        Mockito.when(evita.getCatalogState("existingCatalog")).thenReturn(Optional.of(CatalogState.ALIVE));

        assertDoesNotThrow(() -> mutation.verifyApplicability(evita));
    }

    @Test
    @DisplayName("Should allow duplicating WARMING_UP catalog")
    void shouldAllowDuplicatingWarmingUpCatalog() {
        DuplicateCatalogMutation mutation = new DuplicateCatalogMutation("existingCatalog", "newCatalog");

        EvitaContract evita = Mockito.mock(EvitaContract.class);
        Mockito.when(evita.getCatalogNames()).thenReturn(Set.of("existingCatalog"));
        Mockito.when(evita.getCatalogState("existingCatalog")).thenReturn(Optional.of(CatalogState.WARMING_UP));

        assertDoesNotThrow(() -> mutation.verifyApplicability(evita));
    }

    @Test
    @DisplayName("Should allow duplicating INACTIVE catalog")
    void shouldAllowDuplicatingInactiveCatalog() {
        DuplicateCatalogMutation mutation = new DuplicateCatalogMutation("existingCatalog", "newCatalog");

        EvitaContract evita = Mockito.mock(EvitaContract.class);
        Mockito.when(evita.getCatalogNames()).thenReturn(Set.of("existingCatalog"));
        Mockito.when(evita.getCatalogState("existingCatalog")).thenReturn(Optional.of(CatalogState.INACTIVE));

        InvalidMutationException exception = assertThrows(
            InvalidMutationException.class,
            () -> mutation.verifyApplicability(evita)
        );

        assertEquals("Catalog `existingCatalog` is not in a valid state for this operation! Current state: INACTIVE", exception.getMessage());
    }

    @Test
    @DisplayName("Should create mutation with correct catalog names")
    void shouldCreateMutationWithCorrectCatalogNames() {
        DuplicateCatalogMutation mutation = new DuplicateCatalogMutation("sourceCatalog", "targetCatalog");

        assertEquals("sourceCatalog", mutation.getCatalogName());
        assertEquals("targetCatalog", mutation.getNewCatalogName());
    }

    @Test
    @DisplayName("Should have correct toString representation")
    void shouldHaveCorrectToStringRepresentation() {
        DuplicateCatalogMutation mutation = new DuplicateCatalogMutation("sourceCatalog", "targetCatalog");

        assertEquals("Duplicate catalog `sourceCatalog` to `targetCatalog`", mutation.toString());
    }

}