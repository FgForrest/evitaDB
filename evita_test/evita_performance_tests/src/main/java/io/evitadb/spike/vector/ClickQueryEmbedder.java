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

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Turns production fulltext search logs into the query set of the click-relevance measurement
 * ({@link ClickRecallScenario}): every distinct query text gets an embedding from the external embedding service,
 * and every click is joined with the query that preceded it.
 *
 * This is the only step that needs the service and its credentials, so it is kept apart from the measurement and
 * depends on nothing but the JDK; it runs as a single source file, without a build:
 *
 * ```
 * export EMBEDDING_SERVICE_URL=<encode endpoint>
 * export SERVICE_AUTH=<Basic credentials, already base64-encoded>
 * java evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/vector/ClickQueryEmbedder.java \
 *   <result-clicks.csv> <search-queries.csv> <workDir> [--sample N]
 * ```
 *
 * Inputs, both CSV with a header row (RFC 4180 quoting):
 *
 * - the click log, the driving table: `result_id` points to the search that preceded the click, `selected_result`
 *   is the URL of the clicked record, `locale` and `position` are carried over;
 * - the search log: `record_id` is the key the clicks point to, `query` is what the user typed.
 *
 * Outputs in `workDir`, which the measurement reads:
 *
 * - `click-queries.f32`: one unit-length float32 vector per distinct query text, little-endian, the layout of
 *   {@link MappedVectors};
 * - `click-queries.tsv`: `index TAB query text`, the cache key: a rerun embeds only texts it has not seen;
 * - `clicks.tsv`: `query index TAB locale TAB clicked URL TAB position`, one line per click whose search was found.
 *
 * A work directory belongs to one dataset: `click-source.txt` records the names of both logs, and a run with
 * different logs fails instead of overwriting the clicks of the first dataset. Use one work directory per dataset,
 * the same one the measurement gets together with that dataset's export.
 *
 * Without `--sample`, every distinct query text of the search log is embedded, not only those that led to a click,
 * so the measurement also has the full query set for recall against the exact scan. With `--sample N`, only `N`
 * distinct texts that led to a click are drawn, at random with a fixed seed, and `clicks.tsv` keeps only their
 * clicks: a large log then costs `N` calls of the service instead of one per distinct text. The draw takes a prefix
 * of one fixed shuffle, so a larger `N` is a superset of a smaller one and a rerun with it embeds only the
 * difference. The search logs are user data: nothing of their content is printed, only counts, with one
 * exception: a call the service rejects prints its request body, the query text included, next to the response.
 *
 * The request is `POST` with a JSON body `{"query": ...}` and `Authorization: Basic $SERVICE_AUTH`; the response is
 * `{"success": true, "query": ..., "vector": [...], "dimensions": N, ...}`. The echoed query is compared with the
 * sent one, so a server that ignored the body would fail loudly instead of returning one vector for everything.
 *
 * Nullability annotations are left out on purpose: they are not in the JDK and the file must compile alone.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class ClickQueryEmbedder {
	/** Vector block of the distinct query texts. */
	public static final String QUERY_VECTORS_FILE = "click-queries.f32";
	/** Text of every row of the vector block. */
	public static final String QUERY_TEXTS_FILE = "click-queries.tsv";
	/** Clicks joined with the index of their query. */
	public static final String CLICKS_FILE = "clicks.tsv";
	/** Marker naming the click and search logs a work directory belongs to. */
	private static final String SOURCE_FILE = "click-source.txt";
	/** Attempts per query before the run gives up. */
	private static final int ATTEMPTS = 3;
	/** Pause between two consecutive calls of the service, so the run does not flood it. */
	private static final long PAUSE_BETWEEN_CALLS_MS = 500L;
	/** One word of a query the search engine rewrote for typo tolerance; group 1 is the word as typed. */
	private static final Pattern TYPO_TOLERANCE_GROUP =
		Pattern.compile("\\(\\s*([^\\s()]+)\\s+OR\\s+\\(title:[^()]*\\)\\s*\\)");
	/** Seed of the `--sample` draw, fixed so the drawn set is repeatable. */
	private static final long SAMPLE_SEED = 42L;

	private ClickQueryEmbedder() {
		throw new UnsupportedOperationException("Entry point, not to be instantiated.");
	}

	/**
	 * Embeds the queries and writes the three output files.
	 *
	 * @param args the click log, the search log, the work directory, optionally `--sample N`
	 * @throws Exception when a file cannot be read or written, or the service fails after all attempts
	 */
	public static void main(String[] args) throws Exception {
		final int sample;
		if (args.length == 3) {
			sample = -1;
		} else if (args.length == 5 && "--sample".equals(args[3]) && Integer.parseInt(args[4]) > 0) {
			sample = Integer.parseInt(args[4]);
		} else {
			System.err.println(
				"Usage: ClickQueryEmbedder <result-clicks.csv> <search-queries.csv> <workDir> [--sample N]"
			);
			System.exit(1);
			return;
		}
		final Path workDir = Path.of(args[2]).toAbsolutePath().normalize();
		Files.createDirectories(workDir);
		checkSource(workDir.resolve(SOURCE_FILE), Path.of(args[0]), Path.of(args[1]));

		// the search log: search key -> normalized query text; distinct texts in log order
		final List<Map<String, String>> searches = readCsv(Path.of(args[1]));
		final Map<String, String> queryBySearch = new HashMap<>(searches.size() * 2);
		final Map<String, Integer> allTexts = new LinkedHashMap<>();
		for (final Map<String, String> search : searches) {
			final String text = normalize(column(search, "query"));
			if (!text.isEmpty()) {
				queryBySearch.put(column(search, "record_id"), text);
				allTexts.putIfAbsent(text, allTexts.size());
			}
		}

		// the click log joined with the searches: per click its text and the columns carried over
		final List<Map<String, String>> clicks = readCsv(Path.of(args[0]));
		final List<String[]> joinedClicks = new ArrayList<>(clicks.size());
		final Map<String, Integer> clickTexts = new LinkedHashMap<>();
		for (final Map<String, String> click : clicks) {
			final String text = queryBySearch.get(column(click, "result_id"));
			if (text != null) {
				joinedClicks.add(new String[]{
					text, column(click, "locale"), column(click, "selected_result"), column(click, "position")
				});
				clickTexts.putIfAbsent(text, clickTexts.size());
			}
		}
		System.out.println(
			"searches " + searches.size() + ", distinct query texts " + allTexts.size() + ", clicks "
				+ clicks.size() + ", clicks joined with their search " + joinedClicks.size()
				+ ", distinct query texts with a click " + clickTexts.size()
		);

		// the texts to embed: all of them, or a fixed random draw of those with a click
		final Map<String, Integer> queryIndex;
		if (sample < 0) {
			queryIndex = allTexts;
		} else {
			final List<String> shuffled = new ArrayList<>(clickTexts.keySet());
			Collections.shuffle(shuffled, new Random(SAMPLE_SEED));
			final int drawn = Math.min(sample, shuffled.size());
			queryIndex = new LinkedHashMap<>(drawn * 2);
			for (int i = 0; i < drawn; i++) {
				queryIndex.put(shuffled.get(i), i);
			}
		}

		final StringBuilder joined = new StringBuilder(joinedClicks.size() * 64);
		int written = 0;
		for (final String[] click : joinedClicks) {
			final Integer index = queryIndex.get(click[0]);
			if (index != null) {
				joined.append(index).append('\t').append(click[1]).append('\t')
					.append(click[2]).append('\t').append(click[3]).append('\n');
				written++;
			}
		}
		Files.writeString(workDir.resolve(CLICKS_FILE), joined.toString(), StandardCharsets.UTF_8);
		System.out.println("query texts selected " + queryIndex.size() + ", clicks written " + written);

		final Map<String, float[]> cached = readCache(workDir);
		final List<float[]> vectors = embedAll(queryIndex, cached);
		writeVectors(workDir, queryIndex, vectors);
	}

	/**
	 * Binds the work directory to the logs: records their file names on first use and fails when a later run names
	 * different ones, so the clicks and the cache of two datasets never mix.
	 *
	 * @param marker   the marker file in the work directory
	 * @param clicks   the click log of this run
	 * @param searches the search log of this run
	 * @throws IOException when the marker cannot be read or written
	 */
	private static void checkSource(Path marker, Path clicks, Path searches) throws IOException {
		final String names = clicks.getFileName() + "\t" + searches.getFileName();
		if (Files.exists(marker)) {
			final String recorded = Files.readString(marker, StandardCharsets.UTF_8).trim();
			if (!recorded.equals(names)) {
				throw new IllegalStateException(
					"Work directory `" + marker.getParent() + "` belongs to other click and search logs; "
						+ "use one work directory per dataset."
				);
			}
		} else {
			Files.writeString(marker, names + "\n", StandardCharsets.UTF_8);
		}
	}

	/**
	 * Embeds every query text the cache does not hold yet, in index order.
	 *
	 * @param queryIndex distinct query texts with their row index, in row order
	 * @param cached     vectors of an earlier run, by query text
	 * @return one unit-length vector per row
	 * @throws Exception when the service fails after all attempts
	 */
	private static List<float[]> embedAll(Map<String, Integer> queryIndex, Map<String, float[]> cached)
		throws Exception {
		final List<float[]> vectors = new ArrayList<>(queryIndex.size());
		final List<String> missing = new ArrayList<>(queryIndex.size());
		for (final String text : queryIndex.keySet()) {
			if (!cached.containsKey(text)) {
				missing.add(text);
			}
		}
		System.out.println("cached " + (queryIndex.size() - missing.size()) + ", to embed " + missing.size());
		final Map<String, float[]> fresh = new HashMap<>(missing.size() * 2);
		if (!missing.isEmpty()) {
			final String url = requiredEnv("EMBEDDING_SERVICE_URL");
			final String auth = requiredEnv("SERVICE_AUTH");
			final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
			int done = 0;
			for (final String text : missing) {
				if (done > 0) {
					// throttle: two calls per second at most, the service is shared
					Thread.sleep(PAUSE_BETWEEN_CALLS_MS);
				}
				fresh.put(text, embed(client, url, auth, text, queryIndex.get(text)));
				if (++done % 10 == 0 || done == missing.size()) {
					System.out.println("embedded " + done + " / " + missing.size());
				}
			}
		}
		float minNorm = Float.MAX_VALUE;
		float maxNorm = 0;
		int dimension = -1;
		for (final String text : queryIndex.keySet()) {
			final float[] vector = cached.containsKey(text) ? cached.get(text) : fresh.get(text);
			if (dimension >= 0 && vector.length != dimension) {
				throw new IllegalStateException("Mixed dimensions: " + dimension + " and " + vector.length + ".");
			}
			dimension = vector.length;
			final float norm = normalizeInPlace(vector);
			minNorm = Math.min(minNorm, norm);
			maxNorm = Math.max(maxNorm, norm);
			vectors.add(vector);
		}
		System.out.printf("dimension %d, norm before normalization %.6f .. %.6f%n", dimension, minNorm, maxNorm);
		return vectors;
	}

	/**
	 * Calls the service for one query text, retrying a failed call.
	 *
	 * @param client the HTTP client
	 * @param url    the encode endpoint
	 * @param auth   the Basic credentials, base64-encoded
	 * @param text   the query text
	 * @param index  the row index, the only identification printed on failure
	 * @return the embedding as returned
	 * @throws Exception when every attempt failed
	 */
	private static float[] embed(HttpClient client, String url, String auth, String text, int index)
		throws Exception {
		final String requestBody = "{\"query\":" + jsonString(text) + "}";
		final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(60))
			.header("Authorization", "Basic " + auth)
			.header("Content-Type", "application/json")
			.method("POST", HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
			.build();
		String failure = null;
		for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
			try {
				final HttpResponse<String> response = client.send(
					request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
				);
				if (response.statusCode() == 200) {
					return parseResponse(response.body(), text, index);
				}
				failure = "HTTP " + response.statusCode();
				// the request body and the whole response, so a rejection by a firewall in front of the service can
				// be diagnosed; the request body carries the query text, the one place a query is printed
				System.err.println(
					"query #" + index + ", attempt " + attempt + "/" + ATTEMPTS + "\nrequest body: " + requestBody
						+ "\nresponse: " + response.version() + " " + response.statusCode() + "\n"
						+ formatHeaders(response.headers().map()) + "\n" + response.body()
				);
			} catch (IOException e) {
				failure = e.getClass().getSimpleName() + ": " + e.getMessage();
				System.err.println("query #" + index + ", attempt " + attempt + "/" + ATTEMPTS + ": " + failure);
			}
			// back off before the next attempt; the service may be rate limiting
			Thread.sleep(1000L * attempt);
		}
		throw new IOException("Embedding of query #" + index + " failed after " + ATTEMPTS + " attempts: " + failure);
	}

	/**
	 * @param headers response headers, by name
	 * @return the headers, one `name: value` line each
	 */
	private static String formatHeaders(Map<String, List<String>> headers) {
		final StringBuilder result = new StringBuilder(headers.size() * 48);
		for (final Map.Entry<String, List<String>> header : headers.entrySet()) {
			for (final String value : header.getValue()) {
				result.append(header.getKey()).append(": ").append(value).append('\n');
			}
		}
		return result.toString();
	}

	/**
	 * Validates a response and extracts its vector.
	 *
	 * @param body  the response body
	 * @param text  the query text that was sent
	 * @param index the row index, the only identification printed on failure
	 * @return the vector of the response
	 */
	static float[] parseResponse(String body, String text, int index) {
		if (!body.replace(" ", "").contains("\"success\":true")) {
			throw new IllegalStateException("Query #" + index + ": the service did not report success.");
		}
		final String echoed = stringField(body, "query");
		if (!text.equals(echoed)) {
			throw new IllegalStateException(
				"Query #" + index + ": the service echoed a different query; was the request body ignored?"
			);
		}
		final int start = body.indexOf('[', body.indexOf("\"vector\""));
		final int end = body.indexOf(']', start);
		if (body.indexOf("\"vector\"") < 0 || start < 0 || end < 0) {
			throw new IllegalStateException("Query #" + index + ": no vector in the response.");
		}
		final String[] parts = body.substring(start + 1, end).split(",");
		final float[] vector = new float[parts.length];
		for (int i = 0; i < parts.length; i++) {
			vector[i] = Float.parseFloat(parts[i].trim());
		}
		final String dimensions = numberField(body, "dimensions");
		if (dimensions != null && Integer.parseInt(dimensions) != vector.length) {
			throw new IllegalStateException(
				"Query #" + index + ": " + vector.length + " components, the response says " + dimensions + "."
			);
		}
		return vector;
	}

	/**
	 * Reads the vectors of an earlier run, keyed by their query text.
	 *
	 * @param workDir the work directory
	 * @return the cached vectors, empty when there is no earlier run
	 * @throws IOException when the files exist but cannot be read
	 */
	private static Map<String, float[]> readCache(Path workDir) throws IOException {
		final Path texts = workDir.resolve(QUERY_TEXTS_FILE);
		final Path vectors = workDir.resolve(QUERY_VECTORS_FILE);
		if (!Files.exists(texts) || !Files.exists(vectors)) {
			return new HashMap<>();
		}
		final List<String> lines = Files.readAllLines(texts, StandardCharsets.UTF_8);
		final Map<String, float[]> cached = new HashMap<>(lines.size() * 2);
		if (lines.isEmpty()) {
			return cached;
		}
		final ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(vectors)).order(ByteOrder.LITTLE_ENDIAN);
		final int dimension = bytes.capacity() / Float.BYTES / lines.size();
		for (final String line : lines) {
			final float[] vector = new float[dimension];
			bytes.asFloatBuffer().get(0, vector);
			bytes.position(bytes.position() + dimension * Float.BYTES);
			cached.put(line.substring(line.indexOf('\t') + 1), vector);
		}
		return cached;
	}

	/**
	 * Writes the vector block and the text index, replacing both.
	 *
	 * @param workDir    the work directory
	 * @param queryIndex distinct query texts in row order
	 * @param vectors    one vector per row
	 * @throws IOException when a file cannot be written
	 */
	private static void writeVectors(Path workDir, Map<String, Integer> queryIndex, List<float[]> vectors)
		throws IOException {
		final StringBuilder texts = new StringBuilder(queryIndex.size() * 16);
		for (final Map.Entry<String, Integer> entry : queryIndex.entrySet()) {
			texts.append(entry.getValue()).append('\t').append(entry.getKey()).append('\n');
		}
		final ByteBuffer block = ByteBuffer.allocate(vectors.size() * vectors.get(0).length * Float.BYTES)
			.order(ByteOrder.LITTLE_ENDIAN);
		for (final float[] vector : vectors) {
			for (final float component : vector) {
				block.putFloat(component);
			}
		}
		block.flip();
		try (
			final FileChannel channel = FileChannel.open(
				workDir.resolve(QUERY_VECTORS_FILE),
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
			)
		) {
			while (block.hasRemaining()) {
				channel.write(block);
			}
		}
		Files.writeString(workDir.resolve(QUERY_TEXTS_FILE), texts.toString(), StandardCharsets.UTF_8);
		System.out.println("wrote " + vectors.size() + " query vectors to " + workDir);
	}

	/**
	 * Parses a CSV file with a header row (RFC 4180: quoted fields, `""` inside quotes, line breaks inside quotes).
	 *
	 * @param file the CSV file
	 * @return one map per data row, keyed by the header names
	 * @throws IOException when the file cannot be read
	 */
	static List<Map<String, String>> readCsv(Path file) throws IOException {
		final String content = Files.readString(file, StandardCharsets.UTF_8);
		final List<List<String>> rows = new ArrayList<>(1024);
		List<String> row = new ArrayList<>(32);
		final StringBuilder field = new StringBuilder(64);
		boolean quoted = false;
		for (int i = 0; i < content.length(); i++) {
			final char c = content.charAt(i);
			if (quoted) {
				if (c == '"' && i + 1 < content.length() && content.charAt(i + 1) == '"') {
					field.append('"');
					i++;
				} else if (c == '"') {
					quoted = false;
				} else {
					field.append(c);
				}
			} else if (c == '"') {
				quoted = true;
			} else if (c == ',') {
				row.add(field.toString());
				field.setLength(0);
			} else if (c == '\n' || c == '\r') {
				if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
					i++;
				}
				row.add(field.toString());
				field.setLength(0);
				rows.add(row);
				row = new ArrayList<>(32);
			} else {
				field.append(c);
			}
		}
		if (field.length() > 0 || !row.isEmpty()) {
			row.add(field.toString());
			rows.add(row);
		}
		final List<String> header = rows.get(0);
		final List<Map<String, String>> records = new ArrayList<>(rows.size());
		for (int r = 1; r < rows.size(); r++) {
			final List<String> values = rows.get(r);
			if (values.size() != header.size()) {
				throw new IllegalStateException(
					file.getFileName() + ": row " + r + " has " + values.size() + " fields, the header "
						+ header.size() + "."
				);
			}
			final Map<String, String> record = new HashMap<>(header.size() * 2);
			for (int c = 0; c < header.size(); c++) {
				record.put(header.get(c), values.get(c));
			}
			records.add(record);
		}
		return records;
	}

	/**
	 * @param row  a CSV row
	 * @param name a column name
	 * @return the value of the column
	 */
	private static String column(Map<String, String> row, String name) {
		final String value = row.get(name);
		if (value == null) {
			throw new IllegalStateException("Missing CSV column `" + name + "`.");
		}
		return value;
	}

	/**
	 * Turns a logged query back into what the user typed. Some search logs record the query after the search engine
	 * rewrote it for typo tolerance: every word becomes a group `(word OR (title:variant~2 AND variant~2))`. Each
	 * such group is replaced by its first token, the word as typed, so `(a OR (title:a~2 AND a~2)) (b OR (...))`
	 * becomes `a b`; words outside a group and parentheses the user typed stay as they are.
	 *
	 * @param text a query text as logged
	 * @return the typed text, trimmed, with runs of whitespace (tabs and line breaks included) collapsed to one space
	 */
	static String normalize(String text) {
		return TYPO_TOLERANCE_GROUP.matcher(text).replaceAll("$1").trim().replaceAll("\\s+", " ");
	}

	/**
	 * Scales a vector to unit length in place, so dot product equals cosine as in the corpus.
	 *
	 * @param vector the vector to scale
	 * @return its length before scaling
	 */
	private static float normalizeInPlace(float[] vector) {
		double sum = 0;
		for (final float component : vector) {
			sum += component * component;
		}
		final float norm = (float) Math.sqrt(sum);
		for (int i = 0; i < vector.length; i++) {
			vector[i] /= norm;
		}
		return norm;
	}

	/**
	 * @param name the variable name
	 * @return its value
	 */
	private static String requiredEnv(String name) {
		final String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Environment variable `" + name + "` is not set.");
		}
		return value;
	}

	/**
	 * @param text any text
	 * @return the text as a JSON string literal
	 */
	static String jsonString(String text) {
		final StringBuilder out = new StringBuilder(text.length() + 8).append('"');
		for (int i = 0; i < text.length(); i++) {
			final char c = text.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		return out.append('"').toString();
	}

	/**
	 * Reads a top-level string field of a flat JSON object, decoding its escapes.
	 *
	 * @param json the JSON object
	 * @param name the field name
	 * @return the decoded value, or null when the field is missing
	 */
	static String stringField(String json, String name) {
		int i = json.indexOf("\"" + name + "\"");
		if (i < 0) {
			return null;
		}
		i = json.indexOf('"', json.indexOf(':', i) + 1) + 1;
		final StringBuilder out = new StringBuilder(32);
		for (; i < json.length(); i++) {
			final char c = json.charAt(i);
			if (c == '"') {
				return out.toString();
			} else if (c == '\\') {
				final char e = json.charAt(++i);
				switch (e) {
					case 'n' -> out.append('\n');
					case 'r' -> out.append('\r');
					case 't' -> out.append('\t');
					case 'b' -> out.append('\b');
					case 'f' -> out.append('\f');
					case 'u' -> {
						out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
						i += 4;
					}
					default -> out.append(e);
				}
			} else {
				out.append(c);
			}
		}
		throw new IllegalStateException("Unterminated string field `" + name + "`.");
	}

	/**
	 * @param json the JSON object
	 * @param name the field name
	 * @return the raw text of a top-level number field, or null when the field is missing
	 */
	static String numberField(String json, String name) {
		final int key = json.indexOf("\"" + name + "\"");
		if (key < 0) {
			return null;
		}
		int i = json.indexOf(':', key) + 1;
		while (Character.isWhitespace(json.charAt(i))) {
			i++;
		}
		int end = i;
		while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
			end++;
		}
		return json.substring(i, end);
	}
}
