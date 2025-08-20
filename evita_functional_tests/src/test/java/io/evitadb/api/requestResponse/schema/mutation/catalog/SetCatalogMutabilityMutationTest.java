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
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link SetCatalogMutabilityMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("SetCatalogMutabilityMutation tests")
public class SetCatalogMutabilityMutationTest {

    @Test
    @DisplayName("Should throw exception when catalog doesn't exist")
    void shouldThrowExceptionWhenCatalogDoesntExist() {
        SetCatalogMutabilityMutation mutation = new SetCatalogMutabilityMutation("nonExistentCatalog", true);

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
    @DisplayName("Should not throw exception when catalog exists and is in valid state")
    void shouldNotThrowExceptionWhenCatalogExistsAndIsInValidState() {
        SetCatalogMutabilityMutation mutation = new SetCatalogMutabilityMutation("existingCatalog", false);

        // Mock EvitaContract
        EvitaContract evita = Mockito.mock(EvitaContract.class);
        Mockito.when(evita.getCatalogNames()).thenReturn(Set.of("existingCatalog"));
        Mockito.when(evita.getCatalogState("existingCatalog")).thenReturn(Optional.of(CatalogState.ALIVE));

        // Verify that no exception is thrown
        assertDoesNotThrow(() -> mutation.verifyApplicability(evita));
    }

    @Test
    @DisplayName("Should throw exception when catalog is in invalid state")
    void shouldThrowExceptionWhenCatalogIsInInvalidState() {
        SetCatalogMutabilityMutation mutation = new SetCatalogMutabilityMutation("existingCatalog", true);

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
    @DisplayName("Should allow setting mutability for ALIVE catalog")
    void shouldAllowSettingMutabilityForAliveCatalog() {
        SetCatalogMutabilityMutation mutation = new SetCatalogMutabilityMutation("existingCatalog", true);

        EvitaContract evita = Mockito.mock(EvitaContract.class);
        Mockito.when(evita.getCatalogNames()).thenReturn(Set.of("existingCatalog"));
        Mockito.when(evita.getCatalogState("existingCatalog")).thenReturn(Optional.of(CatalogState.ALIVE));

        assertDoesNotThrow(() -> mutation.verifyApplicability(evita));
    }

    @Test
    @DisplayName("Should allow setting mutability for WARMING_UP catalog")
    void shouldAllowSettingMutabilityForWarmingUpCatalog() {
        SetCatalogMutabilityMutation mutation = new SetCatalogMutabilityMutation("existingCatalog", false);

        EvitaContract evita = Mockito.mock(EvitaContract.class);
        Mockito.when(evita.getCatalogNames()).thenReturn(Set.of("existingCatalog"));
        Mockito.when(evita.getCatalogState("existingCatalog")).thenReturn(Optional.of(CatalogState.WARMING_UP));

        assertDoesNotThrow(() -> mutation.verifyApplicability(evita));
    }

    @Test
    @DisplayName("Should allow setting mutability for INACTIVE catalog")
    void shouldAllowSettingMutabilityForInactiveCatalog() {
        SetCatalogMutabilityMutation mutation = new SetCatalogMutabilityMutation("existingCatalog", true);

        EvitaContract evita = Mockito.mock(EvitaContract.class);
        Mockito.when(evita.getCatalogNames()).thenReturn(Set.of("existingCatalog"));
        Mockito.when(evita.getCatalogState("existingCatalog")).thenReturn(Optional.of(CatalogState.INACTIVE));

        assertDoesNotThrow(() -> mutation.verifyApplicability(evita));
    }

    @Test
    @DisplayName("Should create mutation with correct catalog name and mutability")
    void shouldCreateMutationWithCorrectCatalogNameAndMutability() {
        SetCatalogMutabilityMutation mutation = new SetCatalogMutabilityMutation("testCatalog", true);

        assertEquals("testCatalog", mutation.getCatalogName());
        assertTrue(mutation.isMutable());
    }

    @Test
    @DisplayName("Should create mutation with read-only flag")
    void shouldCreateMutationWithReadOnlyFlag() {
        SetCatalogMutabilityMutation mutation = new SetCatalogMutabilityMutation("testCatalog", false);

        assertEquals("testCatalog", mutation.getCatalogName());
        assertFalse(mutation.isMutable());
    }

}