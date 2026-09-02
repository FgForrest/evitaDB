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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Thrown when a catalog is already being materialised into a folder and a second operation asks to materialise the
 * same name at the same time.
 *
 * Creating, restoring and duplicating a catalog all begin by allocating a directory for it, and the name is only
 * registered in the engine state at the very end. Between those two moments the operation is invisible to the
 * ordinary "does this catalog exist?" check — so two overlapping restores of `products`, or a restore racing a
 * duplicate onto the same target, both pass validation and both start writing.
 *
 * **Refusing the second one is a deliberate trade against what used to happen.** Previously the second allocation
 * silently displaced the first's claim, and the first then bound its catalog to the second's half-written folder
 * while its own restored data was left unreferenced and reclaimed as an abandoned allocation. The first operation
 * reported success and served the wrong data. A refusal is a visible failure of one operation instead of silent
 * data loss across both.
 *
 * **When this is thrown:**
 * - Two `restoreCatalog` calls for the same catalog name overlap in time
 * - A `restoreCatalog` and a `duplicateCatalog` target the same catalog name concurrently
 * - A previous attempt on this name has not yet released its claim
 *
 * Retrying once the in-flight operation has finished is the correct response. The claim is taken when the restore
 * actually starts, and given back when it ends — on failure and cancellation as well as on success. An upload
 * that is started and then abandoned therefore never runs its restore and holds nothing; only an operation
 * genuinely in flight holds a name.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ConcurrentCatalogMaterializationException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -6521437219905174298L;
	@Getter private final String catalogName;

	/**
	 * Reports a refusal to materialise a catalog under a name another operation is already writing into.
	 *
	 * @param catalogName name both operations want to materialise
	 * @param heldFolder  token naming the folder the operation already in flight is writing into
	 */
	public ConcurrentCatalogMaterializationException(@Nonnull String catalogName, @Nonnull String heldFolder) {
		super(
			"Catalog `" + catalogName + "` is already being written into folder `" + heldFolder + "` by another " +
				"operation that has not finished yet - refusing to materialise it a second time, because both " +
				"copies would compete for the same name and one of them would be lost!",
			"Catalog `" + catalogName + "` is already being created or restored - please wait for that operation " +
				"to finish and try again."
		);
		this.catalogName = catalogName;
	}

}
