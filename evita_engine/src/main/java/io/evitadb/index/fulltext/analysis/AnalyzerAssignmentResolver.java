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
import java.util.Locale;
import java.util.Optional;

/**
 * The seam through which a schema overrides the built-in language table.
 *
 * It is deliberately the whole boundary between the two worlds: a schema supplies analyzer **names** (strings)
 * in an {@link AnalyzerAssignment}, the registry translates those names into instances, and no Lucene type ever
 * appears in a schema. An empty result means "take the default for the locale's language".
 *
 * The granularity is (collection, locale) because a schema may override the choice per collection — the language
 * of CMS articles and the language of product names are the same language but not necessarily the same analyzer.
 * Whether a finer granularity is needed — a product name and a long description in the same collection and the
 * same language plausibly want different analyzers, one being a short structured field and the other continuous
 * text — is an open question the schema work decides; it would change this signature, not the registry around
 * it.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@FunctionalInterface
public interface AnalyzerAssignmentResolver {

	/**
	 * Resolver that never overrides anything, i.e. leaves every combination on its language default.
	 */
	AnalyzerAssignmentResolver DEFAULT = (entityType, locale) -> Optional.empty();

	/**
	 * Resolves the analyzers a schema prescribes for the given combination.
	 *
	 * @param entityType entity collection the analysed value belongs to
	 * @param locale     locale of the analysed value
	 * @return analyzers to use, or empty when the language default should be used
	 */
	@Nonnull
	Optional<AnalyzerAssignment> resolveAnalyzers(@Nonnull String entityType, @Nonnull Locale locale);

}
