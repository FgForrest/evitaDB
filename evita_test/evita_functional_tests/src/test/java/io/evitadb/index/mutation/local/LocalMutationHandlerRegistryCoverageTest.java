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

package io.evitadb.index.mutation.local;

import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.index.mutation.local.handler.LocalMutationHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the invariant that every concrete `LocalMutation` subclass on the classpath under
 * `evita_api`'s mutation tree has a matching `LocalMutationHandler` registered. Without this
 * guardrail a missing registration would only surface at runtime — when an entity carrying the
 * new mutation reaches the executor and trips `LocalMutationHandlerRegistry.resolve(...)`'s
 * defensive throw.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("LocalMutationHandlerRegistry concrete-subclass coverage")
@Tag(INDEXING)
@Tag(SCHEMA)
class LocalMutationHandlerRegistryCoverageTest {

	private static final Logger log = LoggerFactory.getLogger(LocalMutationHandlerRegistryCoverageTest.class);

	/**
	 * Root package under which evita_api defines all `LocalMutation` subtypes. The scan walks
	 * every `.class` resource reachable through this package prefix and filters out abstract /
	 * non-`LocalMutation` classes.
	 */
	private static final String MUTATION_ROOT_PACKAGE = "io.evitadb.api.requestResponse.data.mutation";

	@Test
	@DisplayName("should register a handler for every concrete LocalMutation subclass on the classpath")
	void shouldRegisterHandlerForEveryConcreteLocalMutationSubclass() throws Exception {
		final List<Class<? extends LocalMutation<?, ?>>> concreteMutations = discoverConcreteLocalMutations();
		assertFalse(
			concreteMutations.isEmpty(),
			"classpath scan returned no LocalMutation subclasses — the scan is broken, not the registry"
		);
		final List<Class<? extends LocalMutation<?, ?>>> missing = new ArrayList<>();
		for (final Class<? extends LocalMutation<?, ?>> mutationClass : concreteMutations) {
			if (!LocalMutationHandlerRegistry.hasHandler(mutationClass)) {
				missing.add(mutationClass);
			}
		}
		assertTrue(
			missing.isEmpty(),
			() -> "LocalMutationHandlerRegistry is missing handlers for: "
				+ missing.stream().map(Class::getName).collect(Collectors.joining(", "))
		);
	}

	/**
	 * Walks the classpath under `io.evitadb.api.requestResponse.data.mutation` and returns every
	 * concrete (non-abstract, non-interface, non-enum) class that implements `LocalMutation`.
	 */
	@Nonnull
	private static List<Class<? extends LocalMutation<?, ?>>> discoverConcreteLocalMutations() throws Exception {
		final String packagePath = MUTATION_ROOT_PACKAGE.replace('.', '/');
		final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		final List<URL> roots = Collections.list(classLoader.getResources(packagePath));
		if (roots.isEmpty()) {
			fail("could not locate package on classpath: " + MUTATION_ROOT_PACKAGE);
		}
		final List<Class<? extends LocalMutation<?, ?>>> hits = new ArrayList<>();
		for (final URL root : roots) {
			collectClassesFromRoot(root, packagePath, classLoader, hits);
		}
		return hits;
	}

	/**
	 * Recursively scans a single classpath root entry (directory or JAR) for `.class` files
	 * underneath the mutation package, loads each candidate via reflection, and appends it to
	 * `sink` if it is a concrete `LocalMutation` subclass.
	 */
	private static void collectClassesFromRoot(
		@Nonnull URL root,
		@Nonnull String packagePath,
		@Nonnull ClassLoader classLoader,
		@Nonnull List<Class<? extends LocalMutation<?, ?>>> sink
	) throws URISyntaxException, IOException {
		final URI uri = root.toURI();
		if ("file".equals(uri.getScheme())) {
			final Path dir = Paths.get(uri);
			if (Files.isDirectory(dir)) {
				try (final Stream<Path> stream = Files.walk(dir)) {
					stream
						.filter(Files::isRegularFile)
						.filter(path -> path.toString().endsWith(".class"))
						.forEach(path -> {
							final String relative = dir.relativize(path).toString().replace('\\', '/');
							final String className = MUTATION_ROOT_PACKAGE + "." + relative
								.substring(0, relative.length() - ".class".length())
								.replace('/', '.');
							considerClass(className, classLoader, sink);
						});
				}
			}
		} else if ("jar".equals(uri.getScheme())) {
			// reuse an already-open file system for the same JAR if present; opening a second one
			// for the same URI throws FileSystemAlreadyExistsException
			FileSystem fs;
			try {
				fs = FileSystems.getFileSystem(uri);
			} catch (final FileSystemNotFoundException e) {
				fs = FileSystems.newFileSystem(uri, Map.of());
			}
			final Path packageRoot = fs.getPath("/" + packagePath);
			try (final Stream<Path> stream = Files.walk(packageRoot)) {
				stream
					.filter(Files::isRegularFile)
					.filter(path -> path.toString().endsWith(".class"))
					.forEach(path -> {
						final String full = path.toString();
						final int prefixLen = ("/" + packagePath + "/").length();
						final String className = MUTATION_ROOT_PACKAGE + "." + full
							.substring(prefixLen, full.length() - ".class".length())
							.replace('/', '.');
						considerClass(className, classLoader, sink);
					});
			}
		}
	}

	/**
	 * Loads the class by name and appends it to the sink when it is a concrete (instantiable)
	 * `LocalMutation` subclass. Inner classes, anonymous classes, abstract classes, interfaces,
	 * enums, and any class that cannot be loaded are silently skipped — the scan is best-effort
	 * and only the positive matches are asserted on.
	 */
	@SuppressWarnings("unchecked")
	private static void considerClass(
		@Nonnull String className,
		@Nonnull ClassLoader classLoader,
		@Nonnull List<Class<? extends LocalMutation<?, ?>>> sink
	) {
		// Skip synthetic / nested / package-info entries that cannot be top-level mutation classes
		if (className.contains("$") || className.endsWith(".package-info")) {
			return;
		}
		final Class<?> candidate;
		try {
			candidate = Class.forName(className, false, classLoader);
		} catch (final ClassNotFoundException | LinkageError e) {
			// missing classes / linkage failures during a best-effort scan are expected when shaded
			// or split-package artefacts appear under the same package prefix — log so a genuine
			// loading bug remains visible
			log.warn("LocalMutationHandlerRegistry coverage scan skipped {}: {}", className, e.toString());
			return;
		}
		final int mods = candidate.getModifiers();
		if (candidate.isInterface()
			|| java.lang.reflect.Modifier.isAbstract(mods)
			|| candidate.isEnum()
			|| candidate.isAnnotation()) {
			return;
		}
		if (!LocalMutation.class.isAssignableFrom(candidate)) {
			return;
		}
		sink.add((Class<? extends LocalMutation<?, ?>>) candidate);
	}

}
