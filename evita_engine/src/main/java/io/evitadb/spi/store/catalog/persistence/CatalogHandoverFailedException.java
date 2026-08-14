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

package io.evitadb.spi.store.catalog.persistence;

import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Reports that a catalog rename or replace failed **after the point of no return** - the moment the folder's
 * stored identity became the incoming catalog's and stopped agreeing with engine state.
 *
 * This exception exists to be recognised, not merely reported. Everything a rename does is reversible right up
 * to that moment: the writes are buffered, the engine-state commit has not run, the session registries can be
 * put back, and the catalog is untouched. Past it the folder has been relabelled for a rename that will now
 * never be committed, and a caller that resumes session admission regardless is serving a catalog whose next
 * accepted transaction is appended to the write-ahead log and then replayed, at the following boot, against a
 * name engine state has never heard of - at which point the catalog does not load at all. A failure past the
 * close that follows carries the same marker for the further reason that no persistence service is left
 * behind the catalog either.
 *
 * A caller that sees this must therefore stop compensating and start declaring: bind the surviving name to an
 * unusable catalog carrying this as its cause, so the failure is refused legibly until a restart rebuilds the
 * catalog from its folder - which it can, precisely because nothing was allowed to be written in between.
 *
 * Thrown only by the storage layer, which is the only layer that knows where that line falls.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class CatalogHandoverFailedException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -7729061372554518891L;

	/**
	 * Reports a handover that failed once the folder had already been relabelled for it.
	 *
	 * @param catalogName name the catalog was being handed over to
	 * @param cause       failure that interrupted the handover
	 */
	public CatalogHandoverFailedException(@Nonnull String catalogName, @Nonnull Throwable cause) {
		super(
			"Handover of catalog `" + catalogName + "` failed after its storage folder had been relabelled " +
				"for it, so the folder no longer agrees with the engine state: " + cause.getMessage(),
			"Catalog `" + catalogName + "` could not be renamed and cannot be used until the server is " +
				"restarted, which restores it under the name it had before.",
			cause
		);
	}

}
