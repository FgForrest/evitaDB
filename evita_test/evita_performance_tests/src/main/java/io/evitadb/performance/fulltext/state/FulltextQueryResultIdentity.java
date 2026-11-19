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

package io.evitadb.performance.fulltext.state;

import javax.annotation.Nonnull;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Expanded query result identity, independent of query text and exact-match replicas.
 *
 * @param totalRecordCount all matching records, including those beyond page 1
 * @param pageHash SHA-256 of page-1 primary keys in returned order, each a big-endian 32-bit integer
 */
record FulltextQueryResultIdentity(int totalRecordCount, @Nonnull String pageHash) {
	/**
	 * Hashes the ordered page without sorting it: a relevance or plain-order change must change the identity.
	 * The total count remains a separate identity component so short pages and empty results compare naturally.
	 *
	 * @param totalRecordCount total match count reported by the engine
	 * @param primaryKeys the returned page's keys in order
	 * @return count and ordered-page identity
	 */
	@Nonnull
	static FulltextQueryResultIdentity from(int totalRecordCount, @Nonnull int[] primaryKeys) {
		final ByteBuffer bytes = ByteBuffer.allocate(primaryKeys.length * Integer.BYTES);
		for (final int primaryKey : primaryKeys) {
			bytes.putInt(primaryKey);
		}
		try {
			return new FulltextQueryResultIdentity(totalRecordCount,
				HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array())));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("The JVM must provide SHA-256.", exception);
		}
	}
}
