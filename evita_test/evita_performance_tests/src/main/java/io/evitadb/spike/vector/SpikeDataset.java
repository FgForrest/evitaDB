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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * A dataset of the P6 harness: one work directory holding a descriptor (`dataset.properties`) and the blocks every
 * scenario reads. Adding a dataset means creating the directory and its descriptor; no code names a dataset.
 *
 * The descriptor's keys:
 *
 * - `label`: how the results doc names the dataset ("corpus A"); never a customer name, because it is
 *   printed into the output that the doc quotes;
 * - `source.format`: how the blocks are produced; `evitadb-export` (an evitaDB entity export with embeddings,
 *   read by {@link VectorExportExtractor}) is the only importer today, and a dataset in another format (a public
 *   ANN benchmark set) needs an importer that writes the same blocks;
 * - `source.file`: the source file, absolute or relative to the work directory;
 * - `collection` (optional): the collection the query-driven scenarios filter to, their real-shaped filter; with
 *   clicks it must be the collection whose records the users clicked. Without it those scenarios run unfiltered
 *   only, and the synthetic filters of `filter-matrix` are the only filtered measurement;
 * - `queries.holdOut` (optional): for a dataset without users' queries, the number of records taken out of the
 *   corpus to serve as queries ({@link HeldOutQueries}), 1,000 as spec §4.3 fixes; `queries.seed` (default 42)
 *   seeds the sample. A dataset with clicks gets its queries from {@link ClickQueryEmbedder} instead.
 *
 * The blocks: `text.f32` (the corpus), `text-ordinals.tsv` (ordinal → record), `visual.f32` (out-of-corpus query
 * vectors of parts 1–8, empty when the export has no visual embeddings), `dimension.txt` and `corpus-format.txt`;
 * `click-queries.f32` (the query block), with clicks also `click-queries.tsv` and `clicks.tsv`, held out also
 * `held-out-queries.tsv`. Scenarios write their own derived files (code files, graph files) beside them.
 *
 * @param workDir      the work directory
 * @param label        the label of the dataset in the results doc
 * @param sourceFormat how the blocks were produced
 * @param sourceFile   the source file
 * @param collection   the filter collection, or null when the query-driven scenarios run unfiltered only
 * @param dimension    the vector dimension
 * @param base         the mapped corpus
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public record SpikeDataset(
	@Nonnull Path workDir,
	@Nonnull String label,
	@Nonnull String sourceFormat,
	@Nonnull Path sourceFile,
	@Nullable String collection,
	int dimension,
	@Nonnull MappedVectors base
) {
	/** File name of the descriptor. */
	public static final String DESCRIPTOR = "dataset.properties";
	/** The source format of an evitaDB entity export. */
	public static final String EVITADB_EXPORT = "evitadb-export";

	/**
	 * Opens a dataset, producing its blocks from the source when they are missing or outdated.
	 *
	 * @param workDir the work directory holding the descriptor
	 * @return the dataset
	 * @throws IOException when the descriptor, the source or a block cannot be read
	 */
	@Nonnull
	public static SpikeDataset open(@Nonnull Path workDir) throws IOException {
		final Path dir = workDir.toAbsolutePath().normalize();
		final Path descriptor = dir.resolve(DESCRIPTOR);
		if (!Files.exists(descriptor)) {
			throw new IllegalArgumentException(
				"No " + DESCRIPTOR + " in " + dir + "; every dataset needs one (see p6-measurement-harness.md)."
			);
		}
		final Properties properties = new Properties();
		try (final Reader reader = Files.newBufferedReader(descriptor, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		final String label = required(properties, "label");
		final String format = required(properties, "source.format");
		final Path source = dir.resolve(required(properties, "source.file")).normalize();
		final String collection = properties.getProperty("collection");
		final String holdOut = properties.getProperty("queries.holdOut");
		final int dimension;
		if (EVITADB_EXPORT.equals(format)) {
			dimension = VectorExportExtractor.extractIfMissing(source, dir);
		} else {
			// another importer slots in here; until one exists, blocks produced elsewhere are accepted as they are
			dimension = VectorExportExtractor.dimensionOfExtracted(dir);
		}
		if (holdOut != null && !holdOut.isBlank()) {
			HeldOutQueries.holdOutIfMissing(
				dir, dimension, Integer.parseInt(holdOut.trim()),
				Long.parseLong(properties.getProperty("queries.seed", "42").trim())
			);
		}
		final MappedVectors base = new MappedVectors(dir.resolve(VectorExportExtractor.TEXT_FILE), dimension);
		return new SpikeDataset(dir, label, format, source, collection == null || collection.isBlank()
			? null : collection.trim(), dimension, base);
	}

	/**
	 * @return the query block every query-driven scenario reads: the embedded click queries or the held-out vectors
	 * @throws IllegalStateException when the dataset has neither
	 */
	@Nonnull
	public Path requireQueries() {
		final Path queries = this.workDir.resolve(ClickQueryEmbedder.QUERY_VECTORS_FILE);
		if (!Files.exists(queries)) {
			throw new IllegalStateException(
				"Dataset `" + this.label + "` has no query block; embed its click queries or declare "
					+ "`queries.holdOut` (see p6-measurement-harness.md)."
			);
		}
		return queries;
	}

	/**
	 * @param properties the descriptor
	 * @param key        a key
	 * @return its trimmed value
	 * @throws IllegalArgumentException when the key is missing or blank
	 */
	@Nonnull
	private static String required(@Nonnull Properties properties, @Nonnull String key) {
		final String value = properties.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("The dataset descriptor lacks `" + key + "`.");
		}
		return value.trim();
	}
}
