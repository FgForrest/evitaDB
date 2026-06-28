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

package io.evitadb.index.page;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * The granular write-path emission for one commit, produced by
 * {@link PageStreamRegistry#collectChangedPages(int, List, PageBuilder)}: the leaf pages to (re)write this commit, the
 * complete ordered list of live leaf-page sequences (the `PAGED` root's leaf list, ascending key order), the stream
 * high-water to persist in the root, and the page sequences a leaf merge dropped this commit (to be removed from storage
 * so they don't leak — the append-only OffsetIndex never reclaims an unreferenced-but-never-removed record).
 *
 * The changed-page payload type `P` is supplied by the caller's {@link PageBuilder}: each index materializes its own
 * per-leaf shape (slim value/payload columns, bucket bitmaps, range points, price records) while the page-sequence
 * reconciliation around it is shared.
 *
 * @param <P>                   the per-leaf payload type the caller's page builder produces
 * @param changedPages          the leaf pages whose content changed since the last baseline
 * @param orderedPageSequences  every live leaf's page sequence in ascending key order (the `PAGED` root's leaf list)
 * @param highWaterPageSequence the maximum page sequence ever allocated for the stream
 * @param freedPageSequences    page sequences dropped this commit (merged-away leaves) that must be removed from storage
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record PageEmission<P>(
	@Nonnull List<P> changedPages,
	@Nonnull int[] orderedPageSequences,
	int highWaterPageSequence,
	@Nonnull int[] freedPageSequences
) {
}
