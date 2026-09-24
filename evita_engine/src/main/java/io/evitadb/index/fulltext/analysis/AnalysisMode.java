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

import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;

/**
 * Side of the full-text pipeline an analyzer may be used on.
 *
 * The index-time and the search-time chain differ **on purpose** — synonym and entity-recognition expansion
 * belongs to the query side only, because those dictionaries are meant to be swappable at runtime while the
 * index content must stay stable. This enum is what turns that intent into a checked property instead of a
 * promise: an analyzer declares its mode **where it is registered**, by whoever knows what its chain contains,
 * and every {@link AnalyzerSlot} it is later assigned to validates that declaration against the mode the slot
 * requires. A search-time only chain therefore cannot be baked into an index at all, however a schema assigns
 * it.
 *
 * **The declaration belongs to registration, not to the assignment.** Were the mode declared next to the slot,
 * the check would compare an author's assumption against the same author's assumption and guarantee nothing.
 * Said once by the party that registered the chain, it is a fact every later schema is measured against.
 *
 * Were the rule left to discipline, one accidental wiring of a swappable dictionary into the indexing chain
 * would make a later dictionary swap silently disagree with the stored index — surfacing as inexplicably
 * missing search results rather than as an exception, exactly the failure class the defensive-design rule in
 * `CLAUDE.md` forbids.
 *
 * An analyzer is registered as one opaque chain today, so the mode is a single declared value. Once a chain can
 * be described as a list of steps instead, its mode becomes the intersection of what its steps allow — folded
 * over the *token filters* alone, seeded with {@link #ALL}, because character filters and tokenizers carry no
 * mode in Lucene. That fold belongs with the code that owns the step list, and is deliberately not written
 * ahead of it.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum AnalysisMode {

	/**
	 * The analyzer is usable on both sides of the pipeline. This is the mode of every stateless language
	 * analyzer — a stemmer or a stop-word list produces the same terms whichever side asks for them.
	 */
	ALL,
	/**
	 * The analyzer may be used only while analysing the text of a query. Runtime-swappable artifacts
	 * (synonyms, entity dictionaries) live here, precisely so they can never be baked into an index.
	 */
	SEARCH_TIME,
	/**
	 * The analyzer may be used only while indexing a stored value.
	 */
	INDEX_TIME;

	/**
	 * Tells whether something declaring this mode may be used in a slot requiring `requiredMode`. {@link #ALL}
	 * passes every check; any other mode has to match the requirement exactly.
	 *
	 * The predicate is public beside {@link #checkAllowedInMode(AnalysisMode)} because the same rule is applied
	 * with two different verdicts: a schema assigning an analyzer to a slot it may not run in is an operator
	 * error reported as such, while the same violation reaching a lookup means validation was bypassed and is
	 * a programming error.
	 *
	 * @param requiredMode mode the consuming slot requires — {@link #INDEX_TIME} for the indexing slot,
	 *                     {@link #SEARCH_TIME} for the query and phrase slots
	 * @return true when this mode satisfies the slot's requirement
	 */
	public boolean isAllowedInMode(@Nonnull AnalysisMode requiredMode) {
		return this == ALL || this == requiredMode;
	}

	/**
	 * Verifies that something declaring this mode may be used in a slot requiring `requiredMode` — see
	 * {@link #isAllowedInMode(AnalysisMode)} for the rule itself.
	 *
	 * @param requiredMode mode the consuming slot requires — {@link #INDEX_TIME} for the indexing slot,
	 *                     {@link #SEARCH_TIME} for the query and phrase slots
	 * @throws GenericEvitaInternalError when this mode is incompatible with the slot's requirement
	 */
	public void checkAllowedInMode(@Nonnull AnalysisMode requiredMode) {
		if (!isAllowedInMode(requiredMode)) {
			throw new GenericEvitaInternalError(
				"Analyzer declared as " + this + " cannot be used in a slot requiring " + requiredMode + ".",
				"Analyzer cannot be used on this side of the full-text pipeline."
			);
		}
	}

}
