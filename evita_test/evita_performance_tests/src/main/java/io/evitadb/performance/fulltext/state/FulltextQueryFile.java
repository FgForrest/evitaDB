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

import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/** Reads the census artifact without exposing query text through diagnostics or record rendering. */
final class FulltextQueryFile {
	/** The versioned-by-shape header written by the expansion census. */
	private static final String HEADER = "queryHash\tcohort\tkind\tweight\ttextBase64\tcleanBase64";

	/** Prevents instantiation of this file reader. */
	private FulltextQueryFile() {
	}

	/**
	 * Selects the first occurrences of a cohort, retaining every kind and the artifact's order and identities.
	 * All rows are validated, including those outside the selected pool. Text is never included in an error.
	 *
	 * @param file census queries.tsv artifact
	 * @param cohort the cohort to select, without blending with another cohort
	 * @param poolSize maximum selected occurrences, strictly positive
	 * @return immutable, nonempty selected pool
	 */
	@Nonnull
	static List<Entry> read(@Nonnull Path file, @Nonnull String cohort, int poolSize) {
		if (!cohort.matches("[a-z-]+") || poolSize < 1) {
			throw new IllegalArgumentException("Query-file mode requires a cohort and a positive poolSize.");
		}
		final List<Entry> selected = new ArrayList<>(Math.min(poolSize, 64));
		final Set<String> identities = CollectionUtils.createHashSet(10_000);
		try (final BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			if (!HEADER.equals(reader.readLine())) {
				throw new IllegalArgumentException("Invalid queries.tsv header.");
			}
			int row = 1;
			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				row++;
				final String[] cells = line.split("\t", -1);
				try {
					if (cells.length != 6 || !cells[0].matches("[0-9a-f]{64}") || !identities.add(cells[0])
						|| !cells[1].matches("[a-z-]+") || !cells[2].matches("[a-z-]+")
						|| Long.parseLong(cells[3]) <= 0) {
						throw new IllegalArgumentException();
					}
					final String text = decode(cells[4]);
					decode(cells[5]);
					if (cells[1].equals(cohort) && selected.size() < poolSize) {
						selected.add(new Entry(cells[0], cells[2], text));
					}
				} catch (IllegalArgumentException | CharacterCodingException exception) {
					// Parsing exceptions may quote a raw cell; do not retain their message or cause.
					throw new IllegalArgumentException("Invalid or duplicate queries.tsv row " + row + ".");
				}
			}
		} catch (IOException exception) {
			throw new IllegalArgumentException("Cannot read queries.tsv.");
		}
		if (selected.isEmpty()) {
			throw new IllegalArgumentException("No queries.tsv queries for cohort `" + cohort + "`.");
		}
		return List.copyOf(selected);
	}

	/**
	 * Decodes text exactly, including trailing separators that decide whether the final word is finished.
	 *
	 * @param encoded Base64 UTF-8 cell
	 * @return decoded text
	 * @throws CharacterCodingException when the cell is not UTF-8
	 */
	@Nonnull
	private static String decode(@Nonnull String encoded) throws CharacterCodingException {
		return StandardCharsets.UTF_8.newDecoder()
			.decode(ByteBuffer.wrap(Base64.getDecoder().decode(encoded))).toString();
	}

	/**
	 * One census occurrence; its identity belongs to the saved file, even if another occurrence has identical text.
	 *
	 * @param identity the census per-query SHA-256 identity
	 * @param kind census kind within the selected cohort
	 * @param text exact decoded query text, never suitable for logging
	 */
	record Entry(@Nonnull String identity, @Nonnull String kind, @Nonnull String text) {
		@Nonnull
		@Override
		public String toString() {
			return "queryHash=" + this.identity;
		}
	}
}
