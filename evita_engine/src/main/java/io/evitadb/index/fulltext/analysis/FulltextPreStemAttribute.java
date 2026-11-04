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

import org.apache.lucene.util.Attribute;

import javax.annotation.Nonnull;

/** Reusable normalized lookup characters carried through stemming and saved Lucene token states. */
interface FulltextPreStemAttribute extends Attribute, CharSequence {
	/**
	 * Copies a normalized pre-stem input without materializing a string.
	 * @param source source characters
	 * @param offset first source character
	 * @param length character count; zero marks an unavailable capture
	 */
	void copyBuffer(@Nonnull char[] source, int offset, int length);

	/** Marks a tail capture that reuses the final posting term without copying its characters. */
	void useFinalTerm();

	/**
	 * Reports a capture after all words operations, where the final posting term is the lookup key.
	 * @return true when the caller must reuse the final term
	 */
	boolean isFinalTerm();
}
