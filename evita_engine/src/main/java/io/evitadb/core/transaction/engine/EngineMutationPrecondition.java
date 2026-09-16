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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * What an engine mutation expects a catalog name to hold at the moment it is applied.
 *
 * An engine mutation names its catalogs, and a name is not an identity. Between the moment a long operation is
 * *issued* and the moment its mutation is *accepted*, the name can be dropped, dropped and recreated, renamed away,
 * or — if it was free — taken. The mutation would then act on a catalog nobody asked it to act on. A precondition
 * is how an operation says which catalog it meant, so the engine can refuse rather than guess.
 *
 * The identity compared is the **folder token**, for two reasons that pull in the same direction:
 *
 * - It is *readable for a catalog that has become unusable*. The question is whether the catalog was substituted,
 *   not whether it is healthy, and a corrupted target is exactly when getting this wrong is most expensive.
 *   `CatalogContract#getCatalogId` is unavailable there - `UnusableCatalog` throws from it - so it cannot answer.
 * - It *never repeats for a name while the process runs*. The token embeds a generation counter that is only ever
 *   moved forwards, so a catalog dropped and recreated under the same name is necessarily bound to a different
 *   token. See `Evita#catalogGenerationSequences`, which carries that guarantee and explains its bounds.
 *
 * That second property is what makes the comparison meaningful rather than merely plausible, and it is why the
 * counters are not reclaimed when a name stops being referenced. It is also **bounded by the process**: the
 * counter is in memory, and nothing records a durable generation peak today. A precondition may therefore only be
 * used by work that cannot outlive the process that started it - which is true of the in-flight tasks that use it,
 * and would not be true of anything resumed from durable state after a restart.
 *
 * Where the check runs, and why there: {@link EngineTransactionManager#applyMutation}, after the mutation's own
 * applicability check and before its conflict keys are registered. From registration onwards the conflict keys
 * hold the names until the operation completes, so the precondition covers precisely the interval the keys do not -
 * between the operation being issued and its mutation being accepted.
 *
 * @param catalogName     name whose occupant is being constrained; never null
 * @param expectedFolderId folder token the name is expected to be bound to, or `null` when the name is expected to
 *                         be bound to nothing at all
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record EngineMutationPrecondition(
	@Nonnull String catalogName,
	@Nullable CatalogFolderId expectedFolderId
) {

	/**
	 * Expects the name to be bound to exactly the passed folder - that is, to still hold the very catalog the
	 * caller observed, rather than a later one wearing the same name.
	 *
	 * @param catalogName name to constrain
	 * @param folderId    folder token the name must still be bound to
	 * @return precondition demanding that binding
	 */
	@Nonnull
	public static EngineMutationPrecondition expectingBoundTo(
		@Nonnull String catalogName,
		@Nonnull CatalogFolderId folderId
	) {
		return new EngineMutationPrecondition(catalogName, folderId);
	}

	/**
	 * Expects the name to be bound to nothing - that is, to still be free, as the caller found it.
	 *
	 * @param catalogName name to constrain
	 * @return precondition demanding the name is unbound
	 */
	@Nonnull
	public static EngineMutationPrecondition expectingUnbound(@Nonnull String catalogName) {
		return new EngineMutationPrecondition(catalogName, null);
	}

	/**
	 * Refuses the mutation when the passed state does not bind the name the way this precondition demands.
	 *
	 * Both halves of the comparison may be `null`, and `null` is a meaningful value on each side: an expectation
	 * of `null` says the caller found the name free, and an actual `null` says nothing holds it now. The check is
	 * therefore a plain equality over a nullable token rather than a presence test.
	 *
	 * @param engineState state to test the expectation against
	 * @throws UnexpectedCatalogIncarnationException when the name no longer holds what the caller expected
	 */
	public void verifyAgainst(@Nonnull ExpandedEngineState engineState) {
		final CatalogFolderId actualFolderId = engineState.boundFolderIdFor(this.catalogName);
		if (!Objects.equals(this.expectedFolderId, actualFolderId)) {
			throw new UnexpectedCatalogIncarnationException(
				this.catalogName,
				this.expectedFolderId == null ? null : this.expectedFolderId.id(),
				actualFolderId == null ? null : actualFolderId.id()
			);
		}
	}

}
