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

package io.evitadb.index.fulltext.analysis;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

/** Receives one index token together with the input carried through its stemming operation. */
@FunctionalInterface
public interface IndexedTermConsumer {
	/**
	 * Receives a token without changing its posting attributes.
	 *
	 * @param term final index term
	 * @param preStem normalized pre-stem lookup characters, or the final term without stemming; null when capture
	 *                is absent or cleared. This borrowed view is valid only during the callback and must not be
	 *                retained without copying. A value consumer materializes only distinct pairs.
	 * @param surfaceForm best-effort display slice of the original NFC input, computed only when asked for - a
	 *                    consumer that keeps the first spelling of a pair asks once per new pair; the `preStem`
	 *                    characters instead when a text step left the offsets outside the input
	 * @param startOffset original start offset
	 * @param endOffset original end offset
	 * @param positionIncrement position increment of the emitted token
	 */
	void accept(
		@Nonnull String term, @Nullable CharSequence preStem, @Nonnull Supplier<String> surfaceForm,
		int startOffset, int endOffset, int positionIncrement
	);
}
