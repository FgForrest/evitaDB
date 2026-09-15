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
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * Thrown when an operation that was issued against one particular catalog is applied at a moment when its target
 * name no longer holds that catalog.
 *
 * A long operation names its target when it is *submitted* and acts on it much later. The name is only a label,
 * and in between anything may have taken it: the catalog can be dropped, dropped and recreated, renamed away, or —
 * where the target was free at submission — created by somebody else. An operation that then proceeds on the name
 * alone destroys or overwrites a catalog nobody asked it to touch, and reports success while doing it.
 *
 * The engine therefore lets such an operation record what it expects the name to hold, and refuses it if the
 * expectation no longer matches. The identity compared is the catalog's folder token rather than its name or its
 * health: the token embeds a generation that never repeats for a name while the process runs, so it distinguishes
 * one *incarnation* of a catalog from the next one to wear the same name. It is also readable for a catalog that
 * has become unusable, which matters because the question being asked is whether the catalog was substituted, not
 * whether it is well.
 *
 * **When this is thrown:**
 * - A restore-to-version finishes and the catalog it was asked to replace has since been dropped or replaced
 * - A restore-to-version was aimed at a free name and something else has since taken it
 * - The scratch catalog an operation built for itself has been removed and recreated by another operation
 *
 * Retrying is the correct response for a caller that still wants the operation, but it is deliberately not
 * automatic: the catalog now standing behind the name is a different one, and whether it should be replaced is
 * the caller's decision to retake rather than the engine's to assume.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class UnexpectedCatalogIncarnationException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -8127566417834821904L;
	@Getter private final String catalogName;

	/**
	 * Reports that the catalog behind a name is not the one an operation was issued against.
	 *
	 * @param catalogName    name whose occupant changed
	 * @param expectedFolder token of the folder the name was bound to when the operation was issued, or `null`
	 *                       when the operation was issued against a name nothing held
	 * @param actualFolder   token of the folder the name is bound to now, or `null` when nothing holds it
	 */
	public UnexpectedCatalogIncarnationException(
		@Nonnull String catalogName,
		@Nullable String expectedFolder,
		@Nullable String actualFolder
	) {
		super(
			describe(catalogName, expectedFolder, actualFolder),
			"Catalog `" + catalogName + "` is no longer the catalog this operation was started against - it was " +
				"changed by someone else in the meantime. Please check its current content and, if you still want " +
				"the operation, start it again."
		);
		this.catalogName = catalogName;
	}

	/**
	 * Builds the private message, which names what the operation expected and what it found. The three reachable
	 * shapes are told apart because they call for different follow-up: a catalog that went away is a different
	 * problem from one that was swapped, and a name that filled up behind a caller who found it free is a third.
	 *
	 * @param catalogName    name whose occupant changed
	 * @param expectedFolder token expected behind the name, or `null` when the name was expected to be free
	 * @param actualFolder   token found behind the name, or `null` when nothing holds it
	 * @return message describing the mismatch
	 */
	@Nonnull
	private static String describe(
		@Nonnull String catalogName,
		@Nullable String expectedFolder,
		@Nullable String actualFolder
	) {
		if (expectedFolder == null) {
			return "Catalog `" + catalogName + "` did not exist when this operation was started, but it is now " +
				"bound to folder `" + actualFolder + "` - refusing to continue, because taking the name over " +
				"would discard a catalog this operation was never asked to touch!";
		} else if (actualFolder == null) {
			return "Catalog `" + catalogName + "` was bound to folder `" + expectedFolder + "` when this " +
				"operation was started and is no longer bound to anything - refusing to continue, because the " +
				"catalog this operation was asked to act on no longer exists!";
		} else {
			return "Catalog `" + catalogName + "` was bound to folder `" + expectedFolder + "` when this " +
				"operation was started and is now bound to folder `" + actualFolder + "` - refusing to continue, " +
				"because the catalog behind the name has been replaced by a different one!";
		}
	}

}
