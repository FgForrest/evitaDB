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

package io.evitadb.spike.vector;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import javax.annotation.Nonnull;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Importer of a public corpus whose queries were each generated from one known article (source format
 * `ranked-pairs`): the article a query was generated from plays the role of the record a user clicked, so the click
 * scenarios score "does the search return the source article" with the evaluator they score clicks with.
 *
 * The source is a directory holding the articles in a fixed order, their rank, so that a sample of N articles is the
 * first N rows:
 *
 * - `articles.f32`: one unit-length vector per article, row = rank, float32 little-endian;
 * - `articles.done`: one byte per article, 1 when its vector was embedded;
 * - `queries.f32` and `queries.done`: the same for the queries, ordered by the rank of their article;
 * - `queries.jsonl`: one JSON object per query row, whose `rank` names the article it was generated from.
 *
 * It writes the blocks of {@link SpikeDataset}: the corpus, the mapping (one collection, the rank as the record id
 * and as the url the clicks resolve by, one locale), an empty out-of-corpus block, the query block and one click
 * per query. Every vector is its own record, so no article is split into chunks.
 *
 * @author Tomáš Pozler (pozler@fg.cz)
 */
public final class RankedPairsImporter {
	/** The source format this importer reads. */
	public static final String FORMAT = "ranked-pairs";
	/** The collection every article is written under. */
	public static final String COLLECTION = "article";
	/** The locale every article and click is written under. */
	private static final String LOCALE = "en";
	/** Marker naming the source and the sample the blocks were written from. */
	private static final String SOURCE_FILE = "import-source.txt";
	/** The corpus format of {@link VectorExportExtractor}, one copy per record. */
	private static final String CORPUS_FORMAT = "2";
	/** File name of the format marker of {@link VectorExportExtractor}. */
	private static final String FORMAT_FILE = "corpus-format.txt";

	private RankedPairsImporter() {
	}

	/**
	 * Writes the blocks of a sample unless the work directory already holds them.
	 *
	 * @param sourceDirectory the directory with the rank-ordered vectors
	 * @param targetDirectory the work directory of the dataset
	 * @param articles        the sample size: the articles of rank below it form the corpus
	 * @param queryArticles   the queries of the articles of rank below it form the query block; at most `articles`,
	 *                        because a query whose article is outside the corpus can never be answered
	 * @return the vector dimension
	 * @throws IOException           when the source cannot be read or a block cannot be written
	 * @throws IllegalStateException when the work directory holds another sample, or a needed row is not embedded
	 */
	public static int importIfMissing(
		@Nonnull Path sourceDirectory,
		@Nonnull Path targetDirectory,
		int articles,
		int queryArticles
	) throws IOException {
		final Path articleVectors = sourceDirectory.resolve("articles.f32");
		final Path articleDone = sourceDirectory.resolve("articles.done");
		final long totalArticles = Files.size(articleDone);
		if (articles < 1 || articles > totalArticles) {
			throw new IllegalArgumentException(
				"`sample.articles` must be between 1 and " + totalArticles + ", the articles of the source."
			);
		}
		if (queryArticles < 1 || queryArticles > articles) {
			throw new IllegalArgumentException("`queries.articles` must be between 1 and `sample.articles`.");
		}
		final long rowBytes = Files.size(articleVectors) / totalArticles;
		if (rowBytes * totalArticles != Files.size(articleVectors) || rowBytes % Float.BYTES != 0) {
			throw new IllegalStateException("articles.f32 is not a whole number of vectors per article.");
		}
		final int dimension = (int) (rowBytes / Float.BYTES);

		final Path marker = targetDirectory.resolve(SOURCE_FILE);
		final String identity = sourceDirectory.getFileName() + "\t" + articles + "\t" + queryArticles + "\t"
			+ Files.size(articleVectors) + "\n";
		final Path dimensionFile = targetDirectory.resolve(VectorExportExtractor.DIMENSION_FILE);
		if (Files.exists(marker)) {
			if (!identity.equals(Files.readString(marker, StandardCharsets.UTF_8))) {
				throw new IllegalStateException(
					"The work directory was imported from another source or sample (" + marker + "); a work "
						+ "directory holds one dataset, create a new one."
				);
			}
			return Integer.parseInt(Files.readString(dimensionFile).trim());
		}

		final int queries = countQueries(sourceDirectory.resolve("queries.jsonl"), queryArticles);
		requireDone(articleDone, articles);
		requireDone(sourceDirectory.resolve("queries.done"), queries);

		copyPrefix(articleVectors, targetDirectory.resolve(VectorExportExtractor.TEXT_FILE), articles * rowBytes);
		copyPrefix(
			sourceDirectory.resolve("queries.f32"), targetDirectory.resolve(ClickQueryEmbedder.QUERY_VECTORS_FILE),
			queries * rowBytes
		);
		Files.write(targetDirectory.resolve(VectorExportExtractor.VISUAL_FILE), new byte[0]);
		try (final BufferedWriter out = Files.newBufferedWriter(
			targetDirectory.resolve(VectorExportExtractor.TEXT_ORDINALS_FILE), StandardCharsets.UTF_8)) {
			for (int rank = 0; rank < articles; rank++) {
				// collection, record id, locale, url: the rank is both the id and the url a click resolves by
				out.write(COLLECTION + '\t' + rank + '\t' + LOCALE + '\t' + rank + '\n');
			}
		}
		writeClicks(
			sourceDirectory.resolve("queries.jsonl"), targetDirectory.resolve(ClickQueryEmbedder.CLICKS_FILE), queries
		);
		Files.writeString(dimensionFile, Integer.toString(dimension));
		Files.writeString(targetDirectory.resolve(FORMAT_FILE), CORPUS_FORMAT + "\n");
		// the marker last: an interrupted import leaves none and is redone
		Files.writeString(marker, identity, StandardCharsets.UTF_8);
		System.out.println(
			"Imported " + articles + " articles and " + queries + " queries generated from the first "
				+ queryArticles + " of them, " + dimension + " dimensions."
		);
		return dimension;
	}

	/**
	 * @param queryFile     the queries, one JSON object per row, ordered by the rank of their article
	 * @param queryArticles the rank limit
	 * @return the number of leading rows whose article has a rank below the limit
	 * @throws IOException           when the file cannot be read
	 * @throws IllegalStateException when the rows are not ordered by rank, so the queries are not a prefix
	 */
	private static int countQueries(@Nonnull Path queryFile, int queryArticles) throws IOException {
		int count = 0;
		int previous = -1;
		try (
			final InputStream in = Files.newInputStream(queryFile);
			final JsonParser parser = new JsonFactory().createParser(in)
		) {
			int rank;
			while ((rank = nextRank(parser)) >= 0) {
				if (rank < previous) {
					throw new IllegalStateException("queries.jsonl is not ordered by rank.");
				}
				previous = rank;
				if (rank >= queryArticles) {
					break;
				}
				count++;
			}
		}
		if (count == 0) {
			throw new IllegalStateException("No query was generated from the first " + queryArticles + " articles.");
		}
		return count;
	}

	/**
	 * Writes one click per query: the row of the query vector, the locale, the url of the source article (its rank)
	 * and an empty last column, the layout of {@link ClickQueryEmbedder}.
	 *
	 * @param queryFile the queries, one JSON object per row
	 * @param clickFile the click file to write
	 * @param queries   the number of leading rows to write
	 * @throws IOException when a file cannot be read or written
	 */
	private static void writeClicks(@Nonnull Path queryFile, @Nonnull Path clickFile, int queries) throws IOException {
		try (
			final InputStream in = Files.newInputStream(queryFile);
			final JsonParser parser = new JsonFactory().createParser(in);
			final BufferedWriter out = Files.newBufferedWriter(clickFile, StandardCharsets.UTF_8)
		) {
			for (int row = 0; row < queries; row++) {
				out.write(row + "\t" + LOCALE + '\t' + nextRank(parser) + "\t\n");
			}
		}
	}

	/**
	 * Reads the next root-level object of a stream of JSON objects.
	 *
	 * @param parser the parser, positioned between two objects
	 * @return the object's `rank`, or -1 at the end of the stream
	 * @throws IOException           when the stream cannot be read
	 * @throws IllegalStateException when an object has no numeric `rank`
	 */
	private static int nextRank(@Nonnull JsonParser parser) throws IOException {
		if (parser.nextToken() == null) {
			return -1;
		}
		int rank = -1;
		JsonToken token;
		while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
			if (token == null) {
				throw new IllegalStateException("queries.jsonl ends inside an object.");
			}
			if (token == JsonToken.FIELD_NAME && "rank".equals(parser.currentName())) {
				parser.nextToken();
				rank = parser.getIntValue();
			} else if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
				parser.skipChildren();
			}
		}
		if (rank < 0) {
			throw new IllegalStateException("A row of queries.jsonl has no `rank`.");
		}
		return rank;
	}

	/**
	 * Refuses a sample that reaches past what was embedded: a row not embedded yet is all zeros in the block, and it
	 * would be scored as a vector.
	 *
	 * @param doneFile one byte per row, 1 when the row was embedded
	 * @param rows     the number of leading rows the sample needs
	 * @throws IOException           when the file cannot be read
	 * @throws IllegalStateException when one of the rows is not embedded
	 */
	private static void requireDone(@Nonnull Path doneFile, int rows) throws IOException {
		try (final InputStream in = Files.newInputStream(doneFile)) {
			final byte[] done = in.readNBytes(rows);
			for (int i = 0; i < rows; i++) {
				if (i >= done.length || done[i] != 1) {
					throw new IllegalStateException(
						"Row " + i + " of " + doneFile.getFileName() + " is not embedded yet; the sample needs the "
							+ "first " + rows + " rows. Run the embedding up to the sample size first."
					);
				}
			}
		}
	}

	/**
	 * @param source the block to copy from
	 * @param target the block to write
	 * @param bytes  the length of the leading part to copy
	 * @throws IOException when the copy fails
	 */
	private static void copyPrefix(@Nonnull Path source, @Nonnull Path target, long bytes) throws IOException {
		try (
			final FileChannel in = FileChannel.open(source, StandardOpenOption.READ);
			final FileChannel out = FileChannel.open(
				target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
			)
		) {
			long position = 0;
			while (position < bytes) {
				position += in.transferTo(position, bytes - position, out);
			}
		}
	}
}
