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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.EXPORT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the file-name rewrite shared by the backup and restore tasks.
 *
 * This is the single point at which an archive's entry names are decided, so a mistake here is silent: the archive
 * is written successfully and only fails to restore later, somewhere else.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(EXPORT)
@DisplayName("Catalog file naming for backup and restore")
class CatalogFileNamingTest {

	@Test
	@DisplayName("Rewrites bootstrap, data and WAL names onto the target prefix")
	void shouldRewritePrefixedFileNamesOntoTargetPrefix() {
		assertEquals("target.boot", CatalogFileNaming.canonicalizeTo("source.boot", "target"));
		assertEquals("target_3.catalog", CatalogFileNaming.canonicalizeTo("source_3.catalog", "target"));
		assertEquals("target_7.wal", CatalogFileNaming.canonicalizeTo("source_7.wal", "target"));
	}

	@Test
	@DisplayName("Reads the index without knowing the source prefix")
	void shouldRewriteWhenSourcePrefixIsUnrelatedToTheArchiveDirectory() {
		// an archive taken from a renamed catalog carries a prefix that matches nothing else about it - the rewrite
		// must not need to be told what that prefix was
		assertEquals("target_12.catalog", CatalogFileNaming.canonicalizeTo("some.other_name_12.catalog", "target"));
		assertEquals("target_0.wal", CatalogFileNaming.canonicalizeTo("some.other_name_0.wal", "target"));
	}

	@Test
	@DisplayName("Leaves names that carry no catalog prefix untouched")
	void shouldLeaveUnprefixedFileNamesUnchanged() {
		// entity collection files are named after their entity type and primary key, never after the catalog
		assertEquals("product-1_0.collection", CatalogFileNaming.canonicalizeTo("product-1_0.collection", "target"));
		// marker files have fixed names
		assertEquals(".restored", CatalogFileNaming.canonicalizeTo(".restored", "target"));
	}

	@Test
	@DisplayName("Keeps a target prefix that contains a regex wildcard intact")
	void shouldRewriteOntoTargetPrefixContainingDot() {
		// `.` is legal in a catalog name; the rewrite builds names by concatenation and must not treat it specially
		assertEquals("my.catalog_1.catalog", CatalogFileNaming.canonicalizeTo("source_1.catalog", "my.catalog"));
	}

}
