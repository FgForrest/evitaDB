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
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test verifies {@link MakeCatalogAliveMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("MakeCatalogAliveMutation tests")
public class MakeCatalogAliveMutationTest {

    @Test
    @DisplayName("Should throw exception when catalog doesn't exist")
    void shouldThrowExceptionWhenCatalogDoesntExist() {
        MakeCatalogAliveMutation mutation = new MakeCatalogAliveMutation("nonExistentCatalog");

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
    @DisplayName("Should throw exception when catalog is not in warming up state")
    void shouldThrowExceptionWhenCatalogIsNotInWarmingUpState() {
        MakeCatalogAliveMutation mutation = new MakeCatalogAliveMutation("existingCatalog");

        // Mock EvitaContract
        EvitaContract evita = Mockito.mock(EvitaContract.class);
        Mockito.when(evita.getCatalogNames()).thenReturn(Set.of("existingCatalog"));
        Mockito.when(evita.getCatalogState("existingCatalog")).thenReturn(Optional.of(CatalogState.ALIVE));

        // Verify that an exception is thrown
        InvalidMutationException exception = assertThrows(
            InvalidMutationException.class,
            () -> mutation.verifyApplicability(evita)
        );

        // Verify the exception message
        assertEquals("Catalog `existingCatalog` is not in warming up state and cannot be transitioned to live state!", exception.getMessage());
    }

    @Test
    @DisplayName("Should not throw exception when catalog exists and is in warming up state")
    void shouldNotThrowExceptionWhenCatalogExistsAndIsInWarmingUpState() {
        MakeCatalogAliveMutation mutation = new MakeCatalogAliveMutation("existingCatalog");

        // Mock EvitaContract
        EvitaContract evita = Mockito.mock(EvitaContract.class);
        Mockito.when(evita.getCatalogNames()).thenReturn(Set.of("existingCatalog"));
        Mockito.when(evita.getCatalogState("existingCatalog")).thenReturn(Optional.of(CatalogState.WARMING_UP));

        // Verify that no exception is thrown
        assertDoesNotThrow(() -> mutation.verifyApplicability(evita));
    }

    @Test
    @DisplayName("Should return UPSERT operation")
    void shouldReturnUpsertOperation() {
        MakeCatalogAliveMutation mutation = new MakeCatalogAliveMutation("catalogName");

        assertEquals("UPSERT", mutation.operation().name());
    }

    @Test
    @DisplayName("Should have correct string representation")
    void shouldHaveCorrectStringRepresentation() {
        MakeCatalogAliveMutation mutation = new MakeCatalogAliveMutation("catalogName");

        assertEquals("Transition catalog `catalogName` to live state", mutation.toString());
    }
}
