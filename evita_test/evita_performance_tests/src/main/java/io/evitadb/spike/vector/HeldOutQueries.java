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
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The query set of a dataset without users' queries: a seeded sample of records is taken out of the corpus, and the
 * first vector of each becomes a query.
 *
 * The sampled records leave the corpus with **all** their vectors, so no query finds itself: an in-corpus query is
 * its own exact nearest neighbour and would raise every recall measured with it. Other records stay, including near
 * duplicates of a sampled one, as they would for a real query. The first vector of a record is its first chunk, for
 * an article the title and the lead, which is the closest a corpus vector comes to a search query.
 *
 * The query block is written as `click-queries.f32` (the file every query-driven scenario reads) and the held-out
 * records as `held-out-queries.tsv` (the ordinal line of each query's record). The marker `held-out.txt` records the
 * sample and the corpus size after it, so the hold-out runs once, and runs again after the corpus was re-extracted.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class HeldOutQueries {
	/** File name of the held-out records, one ordinal line per query. */
	public static final String HELD_OUT_FILE = "held-out-queries.tsv";
	/** File name of the marker of a completed hold-out. */
	private static final String MARKER_FILE = "held-out.txt";

	private HeldOutQueries() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Takes the sample out of the corpus unless it was already taken from the corpus as it is now.
	 *
	 * @param dir       the work directory holding the extracted blocks
	 * @param dimension the vector dimension
	 * @param count     the number of queries (records) to take out
	 * @param seed      the seed of the sample
	 * @throws IOException when a block cannot be read or written
	 */
	public static void holdOutIfMissing(@Nonnull Path dir, int dimension, int count, long seed) throws IOException {
		final Path text = dir.resolve(VectorExportExtractor.TEXT_FILE);
		final Path ordinals = dir.resolve(VectorExportExtractor.TEXT_ORDINALS_FILE);
		final Path marker = dir.resolve(MARKER_FILE);
		final long vectorBytes = (long) dimension * Float.BYTES;
		if (Files.exists(marker)) {
			final String[] recorded = Files.readString(marker, StandardCharsets.UTF_8).trim().split("\\s+");
			if (Long.parseLong(recorded[0]) == count && Long.parseLong(recorded[1]) == seed
				&& Long.parseLong(recorded[2]) == Files.size(text) / vectorBytes) {
				return;
			}
		}
		if (Files.exists(dir.resolve(ClickQueryEmbedder.CLICKS_FILE))) {
			throw new IllegalStateException("The dataset has users' clicks; its queries are not held out.");
		}
		final List<String> lines = Files.readAllLines(ordinals, StandardCharsets.UTF_8);
		// the extractor writes the vectors of one record consecutively: a record is a run of equal keys
		final List<int[]> records = new ArrayList<>(lines.size());
		int start = 0;
		for (int i = 1; i <= lines.size(); i++) {
			if (i == lines.size() || !key(lines.get(i)).equals(key(lines.get(start)))) {
				records.add(new int[]{start, i});
				start = i;
			}
		}
		if (records.size() <= count) {
			throw new IllegalArgumentException(
				"The corpus has " + records.size() + " records, not enough to hold out " + count + " of them."
			);
		}
		// a seeded partial Fisher-Yates shuffle picks the records; kept in corpus order
		final int[] order = new int[records.size()];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}
		final Random random = new Random(seed);
		for (int i = 0; i < count; i++) {
			final int j = i + random.nextInt(order.length - i);
			final int swap = order[i];
			order[i] = order[j];
			order[j] = swap;
		}
		final int[] picked = Arrays.copyOf(order, count);
		Arrays.sort(picked);
		final boolean[] heldOut = new boolean[lines.size()];
		for (final int record : picked) {
			Arrays.fill(heldOut, records.get(record)[0], records.get(record)[1], true);
		}

		final Path textTmp = dir.resolve(VectorExportExtractor.TEXT_FILE + ".tmp");
		final Path ordinalsTmp = dir.resolve(VectorExportExtractor.TEXT_ORDINALS_FILE + ".tmp");
		final MappedVectors corpus = new MappedVectors(text, dimension);
		final ByteBuffer buffer = ByteBuffer.allocate((int) vectorBytes).order(ByteOrder.LITTLE_ENDIAN);
		final float[] vector = new float[dimension];
		int kept = 0;
		try (
			final OutputStream textOut = new BufferedOutputStream(Files.newOutputStream(textTmp), 1 << 20);
			final OutputStream queryOut = new BufferedOutputStream(
				Files.newOutputStream(dir.resolve(ClickQueryEmbedder.QUERY_VECTORS_FILE)), 1 << 20);
			final BufferedWriter ordinalsOut = Files.newBufferedWriter(ordinalsTmp, StandardCharsets.UTF_8);
			final BufferedWriter heldOutOut = Files.newBufferedWriter(
				dir.resolve(HELD_OUT_FILE), StandardCharsets.UTF_8)
		) {
			for (final int record : picked) {
				final int first = records.get(record)[0];
				write(corpus, first, vector, buffer, queryOut);
				heldOutOut.write(lines.get(first));
				heldOutOut.write('\n');
			}
			for (int i = 0; i < lines.size(); i++) {
				if (!heldOut[i]) {
					write(corpus, i, vector, buffer, textOut);
					ordinalsOut.write(lines.get(i));
					ordinalsOut.write('\n');
					kept++;
				}
			}
		}
		Files.move(textTmp, text, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		Files.move(ordinalsTmp, ordinals, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		Files.writeString(marker, count + " " + seed + " " + kept + "\n", StandardCharsets.UTF_8);
		System.out.println(
			"Held out " + count + " of " + records.size() + " records (" + (lines.size() - kept) + " vectors) as "
				+ "queries; the corpus keeps " + kept + " vectors."
		);
	}

	/**
	 * @param ordinalLine a line of the ordinal mapping
	 * @return the record identity: collection, id and locale
	 */
	@Nonnull
	private static String key(@Nonnull String ordinalLine) {
		final int third = ordinalLine.indexOf('\t', ordinalLine.indexOf('\t', ordinalLine.indexOf('\t') + 1) + 1);
		return third < 0 ? ordinalLine : ordinalLine.substring(0, third);
	}

	/**
	 * Copies one corpus vector to a block.
	 *
	 * @param corpus  the mapped corpus
	 * @param ordinal the vector's ordinal
	 * @param vector  scratch of one vector
	 * @param buffer  scratch of one vector's bytes, little-endian
	 * @param out     the block
	 * @throws IOException when the block cannot be written
	 */
	private static void write(
		@Nonnull MappedVectors corpus,
		int ordinal,
		@Nonnull float[] vector,
		@Nonnull ByteBuffer buffer,
		@Nonnull OutputStream out
	) throws IOException {
		corpus.read(ordinal, vector);
		buffer.clear();
		for (final float component : vector) {
			buffer.putFloat(component);
		}
		out.write(buffer.array(), 0, buffer.position());
	}
}
