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

package io.evitadb.store.engine;

import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.FileUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Removes the storage folders boot-time classification found positive evidence that evitaDB abandoned.
 *
 * This is the only code in the folder-decoupling work that destroys anything, so it is deliberately narrow.
 * It never decides *whether* a folder is expendable — {@link CatalogFolderClassifier} does that, and the drain
 * consumes {@link CatalogFolderState#isDeletable()} rather than re-deriving it. Two copies of that policy could
 * drift, and the drift that matters removes something the classifier said to keep.
 *
 * **Only {@link CatalogFolderState#PROVISIONAL} is drained today.** {@link CatalogFolderState#RETIRED} is
 * equally expendable, but removing a tombstoned folder without also dropping its tombstone from the engine
 * state would leak that tombstone permanently: the folder is gone, so the classifier never reports it again,
 * and the entry accumulates in persisted state on every drop and replace. Tombstone removal needs the engine
 * mutation path, which does not exist at the point boot classification runs, so both halves land together with
 * the operators that produce tombstones. A provisional folder needs none of that — it is unreferenced and
 * untracked, so its removal updates nothing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public class CatalogFolderCleaner {

	/**
	 * States this drain acts on. Every member must be deletable, which the initialiser below enforces so the
	 * set cannot be widened past what the classifier authorises.
	 */
	private static final Set<CatalogFolderState> DRAINED_STATES = EnumSet.of(CatalogFolderState.PROVISIONAL);

	static {
		for (final CatalogFolderState state : DRAINED_STATES) {
			Assert.isPremiseValid(
				state.isDeletable(),
				"Catalog folder state `" + state + "` is drained but not marked deletable!"
			);
		}
	}

	private CatalogFolderCleaner() {
		// utility class, never instantiated
	}

	/**
	 * Removes every folder whose classification authorises it, and reports what actually went.
	 *
	 * A folder that cannot be removed is logged and left in place; the next boot classifies it again and tries
	 * again. That is what keeps a Windows lock from turning into a failed startup — nothing downstream depends
	 * on the removal having happened.
	 *
	 * @param storageDirectory root directory catalogs are stored under
	 * @param classifications  verdicts produced by {@link CatalogFolderClassifier} for that directory
	 * @return names of the folders that were removed, in the order they were processed; never null
	 */
	@Nonnull
	public static List<String> drain(
		@Nonnull Path storageDirectory,
		@Nonnull List<CatalogFolderClassification> classifications
	) {
		final List<String> removed = new ArrayList<>(classifications.size());
		for (final CatalogFolderClassification classification : classifications) {
			if (!DRAINED_STATES.contains(classification.state())) {
				continue;
			}
			// belt and braces: the set is guarded above, but this is the last line before an irreversible delete
			Assert.isPremiseValid(
				classification.state().isDeletable(),
				"Refusing to remove folder `" + classification.folderName() + "` in non-deletable state " +
					classification.state() + "!"
			);

			final String folderName = classification.folderName();
			final Path folder = storageDirectory.resolve(folderName);
			try {
				// FileUtils#deleteDirectory never traverses a symbolic link, so this cannot reach outside the
				// folder the classification authorised - see CatalogFolderCleanerTest containment coverage
				FileUtils.deleteDirectory(folder);
				log.info("Removed abandoned storage folder `{}` — an operation died while creating it.", folderName);
				removed.add(folderName);
			} catch (UnexpectedIOException ex) {
				log.warn(
					"Failed to remove abandoned storage folder `{}` — it will be retried on the next boot.",
					folderName, ex
				);
			}
		}
		return removed;
	}

}
