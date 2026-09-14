/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.transaction.engine;

import io.evitadb.api.exception.UnexpectedCatalogIncarnationException;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers what an {@link EngineMutationPrecondition} accepts and refuses.
 *
 * The comparison is over a *nullable* token and `null` carries meaning on both sides - an expectation of `null` says
 * the caller found the name free, an actual `null` says nothing holds it now - so the four combinations are four
 * distinct outcomes rather than one rule and three edge cases. Each is asserted here, because a check that treated
 * `null` as "no opinion" on either side would silently pass exactly the substitutions this exists to catch.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Engine mutation precondition")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class EngineMutationPreconditionTest {
	private static final String CATALOG = "products";
	private static final CatalogFolderId FIRST = new CatalogFolderId("products_1");
	private static final CatalogFolderId SECOND = new CatalogFolderId("products_2");

	@Test
	@DisplayName("accepts a name still bound to the folder it was bound to")
	void shouldAcceptAnUnchangedBinding() {
		assertDoesNotThrow(
			() -> EngineMutationPrecondition.expectingBoundTo(CATALOG, FIRST)
				.verifyAgainst(stateBinding(FIRST))
		);
	}

	@Test
	@DisplayName("refuses a name rebound to a different folder")
	void shouldRefuseASubstitutedCatalog() {
		// the catalog was dropped and recreated under the same name: same label, different catalog, and the whole
		// reason the generation counter is never allowed to walk backwards
		final UnexpectedCatalogIncarnationException ex = assertThrows(
			UnexpectedCatalogIncarnationException.class,
			() -> EngineMutationPrecondition.expectingBoundTo(CATALOG, FIRST)
				.verifyAgainst(stateBinding(SECOND))
		);
		assertTrue(
			ex.getMessage().contains("products_1") && ex.getMessage().contains("products_2"),
			"The refusal has to name both folders, or an operator cannot tell what was substituted for what: " +
				ex.getMessage()
		);
	}

	@Test
	@DisplayName("refuses a name that is no longer bound to anything")
	void shouldRefuseADepartedCatalog() {
		assertThrows(
			UnexpectedCatalogIncarnationException.class,
			() -> EngineMutationPrecondition.expectingBoundTo(CATALOG, FIRST)
				.verifyAgainst(stateBinding(null))
		);
	}

	@Test
	@DisplayName("accepts a name that was free and is still free")
	void shouldAcceptAStillFreeName() {
		assertDoesNotThrow(
			() -> EngineMutationPrecondition.expectingUnbound(CATALOG)
				.verifyAgainst(stateBinding(null))
		);
	}

	@Test
	@DisplayName("refuses a name that was free and has since been taken")
	void shouldRefuseANameTakenSinceSubmission() {
		// the case an operation aimed at a free name would otherwise overwrite, on the strength of an observation
		// made before the catalog it destroys existed
		assertThrows(
			UnexpectedCatalogIncarnationException.class,
			() -> EngineMutationPrecondition.expectingUnbound(CATALOG)
				.verifyAgainst(stateBinding(FIRST))
		);
	}

	/**
	 * Builds an engine state that binds {@link #CATALOG} to the passed folder.
	 *
	 * @param folderId folder the catalog name is bound to, or `null` when nothing holds it
	 * @return state answering that one question
	 */
	@Nonnull
	private static ExpandedEngineState stateBinding(@Nullable CatalogFolderId folderId) {
		final ExpandedEngineState engineState = mock(ExpandedEngineState.class);
		when(engineState.boundFolderIdFor(CATALOG)).thenReturn(folderId);
		return engineState;
	}

}
