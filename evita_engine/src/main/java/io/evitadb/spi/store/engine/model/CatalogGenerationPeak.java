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

package io.evitadb.spi.store.engine.model;

import javax.annotation.Nonnull;

import java.io.Serial;
import java.io.Serializable;

/**
 * Highest folder generation ever handed out for one catalog name.
 *
 * Folders the engine allocates are named `<catalogName>_<generation>`, the generation being drawn per catalog
 * from an engine-scoped sequence starting at 1. The sequence burns a number per *attempt*, so a failed
 * operation never leaves its number available to the retry — which is the whole point, because the folder the
 * failed attempt created may still be on disk (again, the Windows delete-pending case).
 *
 * At boot the sequence is seeded with `max(this peak, highest <name>_N suffix found on disk)`. **The two terms
 * cover disjoint failure modes and neither subsumes the other**, which is why both are kept:
 *
 * - the *scan* catches a folder an attempt created before dying without persisting anything — the peak knows
 *   nothing of it;
 * - the *peak* catches a name that is unusable but invisible to the scan. A delete the filesystem reported as
 *   done can still leave its name unusable (Windows marks a directory delete-pending behind an open handle;
 *   a parent whose traversal permission was lost hides its children), and {@link java.nio.file.Files#exists}
 *   answers `false` both when a path is absent and when its existence *cannot be determined* — it reports an
 *   `AccessDeniedException` as absence. Without the peak such a name is drawn again after every restart.
 *
 * **This peak is hygiene, not the liveness guarantee.** Allocation must treat a failed directory creation as
 * "burn this number, draw the next", bounded by a retry limit — precisely because the existence pre-check above
 * cannot be trusted, the create call is itself the decision point. Nothing may depend on a peak being present:
 * upgrading from a pre-`2026.3` engine state yields none at all, so the first boot after an upgrade always runs
 * with an empty peak set.
 *
 * A peak outlives the catalog's {@link CatalogFolderBinding}: dropping a catalog does not retire its peak,
 * because a folder carrying that name prefix may still be awaiting deletion (see {@link RetiredFolder}). Only
 * once no such folder remains on disk and no tombstone references one may the entry go — otherwise a
 * recreated catalog of the same name would restart at 1 and walk back onto surviving litter.
 *
 * @param catalogName name of the catalog the generation counter belongs to
 * @param peak        highest generation handed out so far; always positive
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CatalogGenerationPeak(
	@Nonnull String catalogName,
	int peak
) implements Serializable {
	@Serial private static final long serialVersionUID = 8912050137452276334L;

	@Nonnull
	@Override
	public String toString() {
		return this.catalogName + '@' + this.peak;
	}

}
