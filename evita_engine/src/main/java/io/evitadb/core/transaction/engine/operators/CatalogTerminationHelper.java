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

package io.evitadb.core.transaction.engine.operators;

import io.evitadb.api.CatalogContract;
import org.slf4j.Logger;

import javax.annotation.Nonnull;

/**
 * Releases a catalog's resources on behalf of an engine mutation operator that can no longer act on a failure.
 *
 * **The guarantee is that a best-effort release never propagates**, and it is the same argument at every call
 * site. Either the operation has already committed - so an exception escaping the release would report a failure
 * for work that succeeded - or the operation failed and its own exception is the one the caller must be told
 * about. What is left in both cases is releasing the handles the catalog's persistence service holds into the
 * storage folder, and that is best-effort by nature.
 *
 * The catch is deliberately {@link Throwable} rather than {@link Exception}: an `Error` escaping a release
 * performed in a `finally` would replace the failure the caller is in the middle of reporting.
 *
 * Centralised because three operators need it and the control flow above is the part that must not drift between
 * them. What legitimately differs is the **message** - each operator tells the operator-facing consequence of its
 * own path - so the message travels in from the call site, and so does the caller's {@link Logger}, keeping every
 * warning attributed to the operator that issued it rather than to this class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class CatalogTerminationHelper {

	private CatalogTerminationHelper() {
		throw new UnsupportedOperationException("This is a static helper class, no instances are allowed.");
	}

	/**
	 * Terminates the passed catalog, logging a failure rather than propagating it.
	 *
	 * @param log             logger of the operator issuing the release, so the warning keeps its origin; must not
	 *                        be null
	 * @param catalog         catalog whose resources are to be released; must not be null
	 * @param catalogName     name to report the catalog under, which is the name it answered to rather than the one
	 *                        it holds; must not be null
	 * @param failureMessage  SLF4J pattern warned on failure, carrying exactly one `{}` for the catalog name and
	 *                        naming the consequence of the leak on this particular path; must not be null
	 */
	static void terminateQuietly(
		@Nonnull Logger log,
		@Nonnull CatalogContract catalog,
		@Nonnull String catalogName,
		@Nonnull String failureMessage
	) {
		try {
			catalog.terminate();
		} catch (Throwable terminationFailure) {
			log.warn(failureMessage, catalogName, terminationFailure);
		}
	}

}
