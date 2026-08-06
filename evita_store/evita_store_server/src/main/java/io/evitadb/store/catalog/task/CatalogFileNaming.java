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

package io.evitadb.store.catalog.task;

import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.store.wal.AbstractMutationLog;

import javax.annotation.Nonnull;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.BOOT_FILE_SUFFIX;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.CATALOG_FILE_SUFFIX;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.WAL_FILE_SUFFIX;

/**
 * Rewrites the names of a catalog's files onto a chosen prefix, shared by the backup and restore tasks.
 *
 * A backup archive is the main way a catalog folder travels between instances, so its entries must carry the
 * catalog's *logical* name rather than whatever prefix the files happen to use on disk — the two stopped being
 * the same thing in issue #649, once a catalog's folder and file names were freed from its name. Restore performs
 * the mirror image of the same rewrite onto the target catalog's name.
 *
 * The rewrite never needs to know the *source* prefix. Both index parsers
 * ({@link CatalogPersistenceService#getIndexFromCatalogFileName} and
 * {@link AbstractMutationLog#getIndexFromWalFileName}) find the index by scanning digits backwards from the
 * suffix, so the incoming name is decomposed without reference to what it starts with. That is what makes this
 * safe for an archive produced from a renamed catalog, whose entries carry a prefix nothing else knows.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class CatalogFileNaming {

	private CatalogFileNaming() {
	}

	/**
	 * Rewrites a catalog file name so that it carries the passed prefix.
	 *
	 * Entity collection files are returned unchanged: they are named after their entity type and primary key
	 * (`entityType-primaryKey_index.collection`) and never carry the catalog prefix in the first place. Anything
	 * else unrecognised is likewise passed through, because a folder may legitimately hold marker files such as
	 * `.restored` whose names are fixed.
	 *
	 * @param fileName     bare file name, without any directory component
	 * @param targetPrefix prefix the returned name should carry
	 * @return the file name rewritten onto the target prefix, or unchanged when it carries no prefix
	 */
	@Nonnull
	static String canonicalizeTo(@Nonnull String fileName, @Nonnull String targetPrefix) {
		if (fileName.endsWith(BOOT_FILE_SUFFIX)) {
			return CatalogPersistenceService.getCatalogBootstrapFileName(targetPrefix);
		} else if (fileName.endsWith(CATALOG_FILE_SUFFIX)) {
			return CatalogPersistenceService.getCatalogDataStoreFileName(
				targetPrefix, CatalogPersistenceService.getIndexFromCatalogFileName(fileName)
			);
		} else if (fileName.endsWith(WAL_FILE_SUFFIX)) {
			return CatalogPersistenceService.getWalFileName(
				targetPrefix, AbstractMutationLog.getIndexFromWalFileName(fileName)
			);
		} else {
			return fileName;
		}
	}

}
