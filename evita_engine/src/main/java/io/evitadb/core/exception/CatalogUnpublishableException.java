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


package io.evitadb.core.exception;

import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Reports that a catalog can no longer persist what it holds in memory, because an earlier warm-up failure left
 * its in-memory state impossible to publish safely.
 *
 * The failure is about publication, not about stored bytes. evitaDB data files are append-only and nothing in them
 * is reachable except through the bootstrap record, which is written last - so the on-disk catalog is intact at its
 * last published version, and reloading recovers it completely. What cannot be trusted is the in-memory state that
 * a further flush would derive a new bootstrap record from. See `.claude/rules/durability-model.md`.
 *
 * Thrown by every route that would publish ({@code Catalog#flush}, {@code Catalog#goLive}, the catalog replace
 * path) and by the next root entity mutation, so a doomed bulk load stops at once rather than pouring in work that
 * can never be saved. Catalog termination does NOT throw it - shutdown skips publication and still releases every
 * resource.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class CatalogUnpublishableException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -6008271446371539418L;

	/**
	 * Reports an attempt to write to, or publish, a catalog whose in-memory state can no longer be persisted.
	 *
	 * @param catalogName name of the catalog that can no longer persist its state
	 * @param cause       the warm-up failure that made the in-memory state unpublishable
	 */
	public CatalogUnpublishableException(
		@Nonnull String catalogName,
		@Nonnull Throwable cause
	) {
		super(
			"Catalog `" + catalogName + "` can no longer persist changes: an earlier warm-up failure made its " +
				"in-memory state impossible to publish safely (" + cause.getMessage() + "). The on-disk catalog is " +
				"intact at its last published version.",
			"Catalog `" + catalogName + "` can no longer persist changes because an earlier failure during bulk " +
				"indexing left its in-memory state untrustworthy. The stored data is intact at the version of the " +
				"last successful flush - close and reload the catalog, then replay everything written since then.",
			cause
		);
	}

}
