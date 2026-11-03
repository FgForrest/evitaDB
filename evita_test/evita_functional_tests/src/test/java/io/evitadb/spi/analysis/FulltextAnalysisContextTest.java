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


package io.evitadb.spi.analysis;

import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.api.requestResponse.schema.analysis.WordList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies what a fulltext step gets besides its parameters: the locale and the resources, kept as they were given
 * when the chain was compiled.
 */
@DisplayName("Fulltext analysis context")
@Tag(ENGINE)
@Tag(FULLTEXT)
class FulltextAnalysisContextTest {
	/** The resource the contexts here are built with. */
	private static final WordList BRANDS = new WordList(List.of("acme"));

	/** A caller changing its map afterwards does not change the resources the steps read. */
	@Test
	@DisplayName("should keep an unmodifiable copy of the resources")
	void shouldKeepUnmodifiableCopyOfResources() {
		final Map<String, FulltextResourceContent> source = new HashMap<>();
		source.put("brands", BRANDS);

		final FulltextAnalysisContext context = new FulltextAnalysisContext(Locale.ROOT, source);
		source.clear();

		assertEquals(Map.of("brands", BRANDS), context.resources());
		assertThrows(UnsupportedOperationException.class, () -> context.resources().put("other", BRANDS));
	}
}
