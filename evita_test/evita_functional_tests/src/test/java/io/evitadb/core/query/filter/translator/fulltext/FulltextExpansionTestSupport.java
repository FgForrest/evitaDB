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

package io.evitadb.core.query.filter.translator.fulltext;

/** Shared production cap resolution and alphabetic fixtures for expansion tests. */
public final class FulltextExpansionTestSupport {
	/** Utility class. */
	private FulltextExpansionTestSupport() { }

	/** Resolves the JVM-start tuning property through the same production resolver as the initialized cap. */
	public static int effectiveTermsPerLeg() {
		return FulltextTermExpansion.resolveTermsPerLeg(Integer.getInteger("evita.fulltext.termsPerLeg"));
	}

	/** Encodes an ordinal as a fixed-width alphabetic suffix that remains one token under real analyzers. */
	public static String alphabeticSuffix(int ordinal) {
		final char[] suffix = new char[4];
		for (int i = suffix.length - 1; i >= 0; i--) {
			suffix[i] = (char) ('a' + ordinal % 26);
			ordinal /= 26;
		}
		return new String(suffix);
	}
}
