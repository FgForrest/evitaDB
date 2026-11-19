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
import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Streams an embedding export (JSON, `vectorFormat = JSON_ARRAY`) and writes every embedding vector into a dense
 * little-endian float32 block, the layout {@link MappedVectors} maps.
 *
 * Two blocks are written, because they come from different embedding inputs and must not be mixed into one index:
 *
 * - `text.f32` — every `vector` under `embeddings[].chunks[]`, including those of `related[].records[]`; this is
 *   the indexed corpus,
 * - `visual.f32` — every `vector` under `visualEmbeddings[]`; these serve as out-of-corpus query vectors.
 *
 * Beside the corpus block, `text-ordinals.tsv` names the record every corpus vector belongs to, one line per
 * ordinal in block order: the collection (schema) code, the record id, the locale of the embedding and the
 * record's `url` field (empty when the record has none). A record nested under `related[].records[]` is named by
 * its own `schemaCode` and id, not by the record that embeds it.
 *
 * A work directory belongs to one export: `export-source.txt` records the export's file name, and a run with a
 * different export fails instead of silently reusing the blocks of the first one. Use one work directory per
 * dataset.
 *
 * The export is parsed with a streaming parser, so its size does not reach the heap; a gzip-compressed export is
 * recognised by its magic number and decompressed on the fly. Records are written in export order, one vector per
 * chunk. A record the export repeats, typically a category nested under every product that references it, is
 * written once, at its first occurrence: an index built in evitaDB holds that entity once in its own collection,
 * not once per referencing entity. Distinct records whose vectors happen to be identical all stay, because the
 * index would hold each of them.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class VectorExportExtractor {
	/** File name of the indexed corpus block. */
	public static final String TEXT_FILE = "text.f32";
	/** File name of the query block. */
	public static final String VISUAL_FILE = "visual.f32";
	/** File name of the ordinal-to-record mapping of the corpus block. */
	public static final String TEXT_ORDINALS_FILE = "text-ordinals.tsv";
	/** File name of the marker naming the export the blocks of a work directory come from. */
	private static final String SOURCE_FILE = "export-source.txt";
	/** File name of the vector dimension of the extracted blocks. */
	public static final String DIMENSION_FILE = "dimension.txt";
	/** File name of the marker carrying the corpus format the blocks were written in. */
	private static final String FORMAT_FILE = "corpus-format.txt";
	/**
	 * Corpus format; bumped whenever the content of the blocks changes, so blocks of an older format are extracted
	 * again instead of reused. Format 2 writes a repeated record once.
	 */
	private static final String CORPUS_FORMAT = "2";

	private VectorExportExtractor() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Opens the export for reading; an export compressed with gzip, whatever its file name, is decompressed while
	 * it streams, so it never has to be unpacked to disk.
	 *
	 * @param exportFile the export
	 * @return a buffered stream of the JSON text
	 * @throws IOException when the export cannot be opened
	 */
	@Nonnull
	private static InputStream openExport(@Nonnull Path exportFile) throws IOException {
		final InputStream raw = new BufferedInputStream(Files.newInputStream(exportFile), 1 << 20);
		raw.mark(2);
		final int first = raw.read();
		final int second = raw.read();
		raw.reset();
		// gzip magic number 0x1f 0x8b
		return first == 0x1f && second == 0x8b
			? new BufferedInputStream(new GZIPInputStream(raw, 1 << 16), 1 << 20)
			: raw;
	}

	/**
	 * Binds the work directory to the export: records the export's file name on first use and fails when a later
	 * run names a different one, so the blocks of two datasets never mix.
	 *
	 * @param marker     the marker file in the work directory
	 * @param exportFile the export of this run
	 * @throws IOException when the marker cannot be read or written
	 */
	private static void checkSource(@Nonnull Path marker, @Nonnull Path exportFile) throws IOException {
		final String name = exportFile.getFileName().toString();
		if (Files.exists(marker)) {
			final String recorded = Files.readString(marker, StandardCharsets.UTF_8).trim();
			if (!recorded.equals(name)) {
				throw new IllegalStateException(
					"Work directory `" + marker.getParent() + "` holds the vectors of export `" + recorded
						+ "`, not `" + name + "`; use one work directory per dataset."
				);
			}
		} else {
			Files.createDirectories(marker.getParent());
			Files.writeString(marker, name + "\n", StandardCharsets.UTF_8);
		}
	}

	/**
	 * Reads the dimension of blocks extracted earlier, without the export. Used by benchmarks, which must not touch
	 * the export.
	 *
	 * @param targetDirectory directory holding the blocks
	 * @return the vector dimension of the blocks
	 * @throws IOException           when a marker cannot be read
	 * @throws IllegalStateException when the blocks are missing or were written in an older format
	 */
	public static int dimensionOfExtracted(@Nonnull Path targetDirectory) throws IOException {
		final Path formatFile = targetDirectory.resolve(FORMAT_FILE);
		final Path dimensionFile = targetDirectory.resolve(DIMENSION_FILE);
		if (
			!Files.exists(formatFile) || !Files.exists(dimensionFile)
				|| !CORPUS_FORMAT.equals(Files.readString(formatFile).trim())
		) {
			throw new IllegalStateException(
				"The work directory holds no blocks in corpus format " + CORPUS_FORMAT + "; run "
					+ "P6Harness over the dataset first."
			);
		}
		return Integer.parseInt(Files.readString(dimensionFile).trim());
	}

	/**
	 * Extracts both blocks and the ordinal mapping unless `targetDirectory` already holds them in the current
	 * format.
	 *
	 * @param exportFile      the JSON export to read
	 * @param targetDirectory directory receiving `text.f32`, `visual.f32` and `text-ordinals.tsv`
	 * @return the vector dimension found in the export
	 * @throws IOException when the export cannot be read or the blocks cannot be written
	 */
	public static int extractIfMissing(@Nonnull Path exportFile, @Nonnull Path targetDirectory) throws IOException {
		final Path text = targetDirectory.resolve(TEXT_FILE);
		final Path visual = targetDirectory.resolve(VISUAL_FILE);
		final Path ordinals = targetDirectory.resolve(TEXT_ORDINALS_FILE);
		final Path dimensionFile = targetDirectory.resolve(DIMENSION_FILE);
		final Path formatFile = targetDirectory.resolve(FORMAT_FILE);
		checkSource(targetDirectory.resolve(SOURCE_FILE), exportFile);
		if (
			Files.exists(text) && Files.exists(visual) && Files.exists(ordinals) && Files.exists(dimensionFile)
				&& Files.exists(formatFile) && CORPUS_FORMAT.equals(Files.readString(formatFile).trim())
		) {
			return Integer.parseInt(Files.readString(dimensionFile).trim());
		}
		Files.createDirectories(targetDirectory);
		int dimension = -1;
		int textCount = 0;
		int visualCount = 0;
		// first occurrence of every record (collection, id, locale) and the number of vectors it wrote
		final Map<String, Frame> firstOccurrence = new HashMap<>(1 << 16);
		final Map<String, Integer> firstOccurrenceVectors = new HashMap<>(1 << 16);
		// the repeated occurrence currently being skipped, to compare its vector count with the first one
		Frame repeat = null;
		String repeatKey = null;
		int repeatVectors = 0;
		int repeats = 0;
		int partialRepeats = 0;
		int skippedVectors = 0;
		try (
			final JsonParser parser = new JsonFactory().createParser(openExport(exportFile));
			final OutputStream textOut = new BufferedOutputStream(Files.newOutputStream(text), 1 << 20);
			final OutputStream visualOut = new BufferedOutputStream(Files.newOutputStream(visual), 1 << 20);
			final BufferedWriter ordinalsOut = Files.newBufferedWriter(ordinals, StandardCharsets.UTF_8)
		) {
			// the containers enclosing the current token, innermost first
			final Deque<Frame> containers = new ArrayDeque<>(32);
			ByteBuffer scratch = ByteBuffer.allocate(0);
			JsonToken token;
			while ((token = parser.nextToken()) != null) {
				if (token == JsonToken.START_ARRAY && "vector".equals(parser.currentName())) {
					final boolean isVisual = isUnder(containers, "visualEmbeddings");
					int components = 0;
					while (parser.nextToken() != JsonToken.END_ARRAY) {
						if (scratch.remaining() < Float.BYTES) {
							scratch = grow(scratch);
						}
						scratch.putFloat(parser.getFloatValue());
						components++;
					}
					if (dimension < 0) {
						dimension = components;
					} else if (dimension != components) {
						throw new IllegalStateException(
							"Mixed dimensions in export: " + dimension + " and " + components + "."
						);
					}
					if (isVisual) {
						visualOut.write(scratch.array(), 0, scratch.position());
						visualCount++;
					} else {
						final Occurrence occurrence = Occurrence.locate(containers);
						final String key = occurrence.key();
						final Frame first = firstOccurrence.putIfAbsent(key, occurrence.record());
						if (first == null || first == occurrence.record()) {
							textOut.write(scratch.array(), 0, scratch.position());
							firstOccurrenceVectors.merge(key, 1, Integer::sum);
							ordinalsOut.write(occurrence.ordinalLine());
							textCount++;
						} else {
							// a repeat of a record already written: skipped, counted per occurrence
							if (repeat != occurrence.record() || !key.equals(repeatKey)) {
								if (repeat != null && repeatVectors != firstOccurrenceVectors.get(repeatKey)) {
									partialRepeats++;
								}
								repeat = occurrence.record();
								repeatKey = key;
								repeatVectors = 0;
								repeats++;
							}
							repeatVectors++;
							skippedVectors++;
						}
					}
					scratch.clear();
				} else if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) {
					containers.push(new Frame(parser.currentName() == null ? "" : parser.currentName()));
				} else if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) {
					final Frame closed = containers.pop();
					// a `fields[]` item `{code: url, value: ...}` hands its value to the record that owns it
					if (
						token == JsonToken.END_OBJECT && "url".equals(closed.scalars.get("code"))
							&& containers.size() >= 2 && "fields".equals(containers.peek().name)
					) {
						final Iterator<Frame> enclosing = containers.iterator();
						enclosing.next();
						enclosing.next().scalars.put("url", closed.scalars.get("value"));
					}
				} else if (token.isScalarValue() && !containers.isEmpty() && parser.currentName() != null) {
					containers.peek().scalars.put(parser.currentName(), parser.getText());
				}
			}
		}
		if (repeat != null && repeatVectors != firstOccurrenceVectors.get(repeatKey)) {
			partialRepeats++;
		}
		Files.writeString(dimensionFile, Integer.toString(dimension));
		Files.writeString(formatFile, CORPUS_FORMAT + "\n");
		System.out.println(
			"Extracted " + textCount + " text vectors of " + firstOccurrence.size() + " records and " + visualCount
				+ " visual vectors of " + dimension + " dimensions; skipped " + skippedVectors + " text vectors of "
				+ repeats + " repeated record occurrences, " + partialRepeats + " of which had a different vector "
				+ "count than the first occurrence."
		);
		return dimension;
	}

	/**
	 * @param value a value that may be missing
	 * @return the value, or an empty string when it is missing
	 */
	@Nonnull
	private static String orEmpty(@Nullable String value) {
		return value == null ? "" : value;
	}

	/**
	 * @param containers the enclosing containers, innermost first
	 * @param fieldName  the field name searched for
	 * @return true when any enclosing container was opened by `fieldName`
	 */
	private static boolean isUnder(@Nonnull Deque<Frame> containers, @Nonnull String fieldName) {
		for (final Frame container : containers) {
			if (fieldName.equals(container.name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @param buffer the full little-endian buffer
	 * @return a buffer of double capacity (at least 4 KB) holding the same content, little-endian
	 */
	@Nonnull
	private static ByteBuffer grow(@Nonnull ByteBuffer buffer) {
		final ByteBuffer larger = ByteBuffer.allocate(Math.max(4096, buffer.capacity() * 2))
			.order(ByteOrder.LITTLE_ENDIAN);
		buffer.flip();
		larger.put(buffer);
		return larger;
	}

	/**
	 * The record a corpus vector belongs to, located from the containers enclosing the vector.
	 *
	 * @param record the record's object frame; a repeated occurrence of the same record is a different frame
	 * @param schema collection (schema) code of the record
	 * @param locale locale of the embedding, empty when the export has none
	 */
	private record Occurrence(@Nonnull Frame record, @Nonnull String schema, @Nonnull String locale) {
		/**
		 * Finds the innermost record, its collection and the embedding locale.
		 *
		 * @param containers the containers enclosing the vector, innermost first
		 * @return the occurrence of the record
		 */
		@Nonnull
		static Occurrence locate(@Nonnull Deque<Frame> containers) {
			String locale = null;
			Frame record = null;
			String schemaCode = null;
			Frame child = null;
			for (final Frame frame : containers) {
				if (child != null) {
					if (locale == null && "embeddings".equals(frame.name)) {
						locale = child.scalars.get("locale");
					} else if (record == null && "records".equals(frame.name)) {
						record = child;
						schemaCode = child.scalars.get("schemaCode");
					} else if (schemaCode == null && "schemas".equals(frame.name)) {
						schemaCode = child.scalars.get("code");
					}
				}
				child = frame;
			}
			if (record == null || schemaCode == null) {
				throw new IllegalStateException("A text vector outside of any record in the export.");
			}
			return new Occurrence(record, schemaCode, orEmpty(locale));
		}

		/**
		 * @return the identity of the record: collection, id and locale
		 */
		@Nonnull
		String key() {
			return this.schema + '\t' + this.record.scalars.get("id") + '\t' + this.locale;
		}

		/**
		 * @return the mapping line of a vector of this record: collection, id, locale and `url` field
		 */
		@Nonnull
		String ordinalLine() {
			return key() + '\t' + orEmpty(this.record.scalars.get("url")) + '\n';
		}
	}

	/**
	 * One open JSON container during the streaming parse.
	 *
	 * @param name    the field name that opened it, empty for an array element
	 * @param scalars scalar fields of an object seen so far (`id`, `code`, `locale`, ...), last value wins
	 */
	private record Frame(@Nonnull String name, @Nonnull Map<String, String> scalars) {
		/**
		 * @param name the field name that opened the container, empty for an array element
		 */
		Frame(@Nonnull String name) {
			this(name, new HashMap<>());
		}
	}
}
