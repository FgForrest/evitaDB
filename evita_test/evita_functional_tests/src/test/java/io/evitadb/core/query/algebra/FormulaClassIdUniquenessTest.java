/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.algebra;

import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the one invariant `getClassId()` cannot enforce for itself: **no two classes may return the same
 * `CLASS_ID`**.
 *
 * The constant is folded into the structural hash as `hashArray[0]`, so two classes sharing a value become
 * indistinguishable to {@link io.evitadb.core.cache.CacheEden} whenever the rest of their hash inputs agree -
 * and the symptom is a cache hit returning another formula's bitmap, which no read-time check would notice.
 * Nothing about a single declaration reveals the clash; only comparing all of them does.
 *
 * The scan reads **sources** rather than loading classes: the invariant is a source convention, a source hit
 * names the file to fix, and no class loading, module opening or reflective access is involved. New constants
 * are minted with `tools/generate-class-id.sh`; see `.claude/rules/formula-class-id.md`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("CLASS_ID constants must be unique across the whole codebase")
@Tag(ENGINE)
@Tag(QUERY)
class FormulaClassIdUniquenessTest implements EvitaTestSupport {
	/**
	 * Matches a `CLASS_ID` declaration regardless of its modifiers, capturing the literal with any digit
	 * separators still in it.
	 */
	private static final Pattern CLASS_ID_DECLARATION = Pattern.compile(
		"static\\s+final\\s+long\\s+CLASS_ID\\s*=\\s*(-?[0-9_]+)L"
	);
	/**
	 * Directories that never hold hand-written sources and would otherwise make the walk needlessly slow.
	 */
	private static final Set<String> IGNORED_DIRECTORIES = Set.of("target", ".git", ".idea", "node_modules");
	/**
	 * Floor on the number of declarations the walk must find. Without it a scan that resolved the wrong root
	 * would find nothing, report no duplicates and pass - proving the opposite of what it claims. The repository
	 * held 45 declarations when this was written; the floor sits below that with room for classes to come and go,
	 * and only has to be high enough that an empty or truncated walk fails.
	 */
	private static final int MINIMAL_EXPECTED_DECLARATIONS = 30;

	@DisplayName("No two classes declare the same CLASS_ID")
	@Test
	void shouldDeclareDistinctClassIdInEveryClass() {
		final Map<Long, List<Path>> declarationsByValue = collectClassIdDeclarations(getRootDirectory());

		final int declarationCount = declarationsByValue.values().stream().mapToInt(List::size).sum();
		assertTrue(
			declarationCount >= MINIMAL_EXPECTED_DECLARATIONS,
			"Only " + declarationCount + " CLASS_ID declaration(s) found - the scan cannot have covered the " +
				"repository, so a clean result proves nothing. Check the resolved root directory."
		);

		final StringBuilder clashes = new StringBuilder(256);
		for (final Entry<Long, List<Path>> entry : declarationsByValue.entrySet()) {
			if (entry.getValue().size() > 1) {
				clashes.append("\n\t").append(entry.getKey()).append("L is declared by:");
				for (final Path path : entry.getValue()) {
					clashes.append("\n\t\t").append(path);
				}
			}
		}
		if (!clashes.isEmpty()) {
			fail(
				"CLASS_ID must differ for every class - it is hashArray[0] of the structural hash, so a shared " +
					"value lets one formula's cached result answer for another. Mint a replacement with " +
					"`tools/generate-class-id.sh <fully.qualified.ClassName>`." + clashes
			);
		}
	}

	/**
	 * Walks every `*.java` file below the passed root and indexes the declared `CLASS_ID` values by value.
	 *
	 * @param rootDirectory root of the repository checkout
	 * @return declared values, each mapped to every source file declaring it
	 */
	@Nonnull
	private static Map<Long, List<Path>> collectClassIdDeclarations(@Nonnull Path rootDirectory) {
		final Map<Long, List<Path>> declarationsByValue = CollectionUtils.createHashMap(64);
		try {
			Files.walkFileTree(rootDirectory, new SimpleFileVisitor<>() {
				@Nonnull
				@Override
				public FileVisitResult preVisitDirectory(@Nonnull Path directory, @Nonnull BasicFileAttributes attrs) {
					return IGNORED_DIRECTORIES.contains(directory.getFileName().toString()) ?
						FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
				}

				@Nonnull
				@Override
				public FileVisitResult visitFile(@Nonnull Path file, @Nonnull BasicFileAttributes attrs) throws IOException {
					if (file.getFileName().toString().endsWith(".java")) {
						final String contents = Files.readString(file, StandardCharsets.UTF_8);
						final Matcher matcher = CLASS_ID_DECLARATION.matcher(contents);
						while (matcher.find()) {
							final long value = Long.parseLong(matcher.group(1).replace("_", ""));
							declarationsByValue.computeIfAbsent(value, it -> new ArrayList<>(2)).add(file);
						}
					}
					return FileVisitResult.CONTINUE;
				}

				@Nonnull
				@Override
				public FileVisitResult visitFileFailed(@Nonnull Path file, @Nonnull IOException exc) {
					// an unreadable file is not this test's business to diagnose - keep walking
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ex) {
			throw new UncheckedIOException("Cannot walk the repository at " + rootDirectory + "!", ex);
		}
		return declarationsByValue;
	}
}
