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

/**
 * Stable identities of the index chain and its words phase, independent of the analyzer's name. The locale is a
 * lookup key, not part of either hash: a stored field is compared with its analyzer in the same locale. What a step
 * derives from that locale is therefore the same on both sides of a comparison. The stemmer selected by `stemming`
 * differs between languages and contributes through its delegates.
 *
 * A field persists both values of the chain that built it, so an unchanged chain must yield the same values on every
 * JVM and in every release. They are compared for equality only: a different value says the field was built another
 * way, never that it is older or newer.
 *
 * @param index the fingerprint of the text, splitting and words steps acting on the index side
 * @param wordPhase the identity of the words steps alone, shared by chains differing only before the words phase
 */
public record FulltextAnalyzerFingerprint(long index, long wordPhase) {
}
