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
package io.evitadb.api.requestResponse.schema.analysis;

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * A list of synonym groups stored as a named fulltext resource - the content read by the
 * {@link AnalysisSteps#synonyms(FulltextResourceReference...)} step. Created by
 * {@link AnalysisSteps#synonymList(SynonymGroup...)}.
 *
 * @param groups the synonym groups in declaration order
 */
public record SynonymList(@Nonnull List<SynonymGroup> groups) implements FulltextResourceContent {

	/**
	 * Validates the groups and keeps an immutable copy of them.
	 *
	 * @param groups the synonym groups in declaration order
	 */
	public SynonymList {
		Assert.notNull(groups, "Synonym groups must not be null.");
		for (final SynonymGroup group : groups) {
			Assert.notNull(group, "A synonym group must not be null.");
		}
		groups = List.copyOf(groups);
	}

	@Nonnull
	@Override
	public FulltextResourceKind kind() {
		return FulltextResourceKind.SYNONYM_LIST;
	}

	@Nonnull
	@Override
	public String toString() {
		return "synonymList" + this.groups;
	}

}
