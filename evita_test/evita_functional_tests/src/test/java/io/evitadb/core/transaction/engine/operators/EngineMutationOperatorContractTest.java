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

package io.evitadb.core.transaction.engine.operators;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one engine-operation rule that cannot be checked by looking at a single operator: **an operator
 * that does not implement `replayCompletionState` wedges the whole engine.**
 *
 * After a crash between the WAL append and the bootstrap rewrite, boot asks the crashed mutation's operator to
 * re-derive the engine state its completion phase would have produced. An operator that leaves the method at its
 * `Optional.empty()` default causes the transaction manager to refuse **every** subsequent mutation, engine-wide,
 * until a human hand-reconciles the bootstrap file — for a crash window measured in microseconds.
 *
 * The debt is real and is being paid down deliberately rather than all at once, so this test does not demand
 * that every operator implements it. It demands that every operator either implements it **or is named below
 * with the reason it cannot yet** — which is what turns silent, invisible debt into a decision someone has to
 * make on purpose. A new operator added without either is a build failure, which is the point.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Engine mutation operator contract")
@Tag(ENGINE)
@Tag(TRANSACTION)
class EngineMutationOperatorContractTest {
	/**
	 * Operators that knowingly do not support forward replay, each with the reason. Removing an entry is the
	 * definition of done for that operator; adding one requires a reason that survives review.
	 *
	 * The first three share a single blocker: their work phase **allocates a folder**, and the mutation does not
	 * carry the token it allocated. At replay time the folder exists on disk but nothing says which one it is,
	 * and deriving it by scanning the storage directory is the approach the folder-decoupling record rejects
	 * outright — a crash remnant can collide with a fresh allocation, which is the exact failure generations
	 * exist to prevent. Fixing them therefore means carrying `CatalogFolderId` on the mutation: a persisted
	 * format change with a Kryo serializer and a backward-compatible reader, not a change inside the operator.
	 */
	private static final Map<String, String> KNOWN_WITHOUT_FORWARD_REPLAY = Map.of(
		"CreateCatalogMutationOperator",
		"work phase allocates a folder whose token the mutation does not carry",
		"DuplicateCatalogMutationOperator",
		"work phase allocates a folder whose token the mutation does not carry",
		"RestoreCatalogSchemaMutationOperator",
		"work phase allocates a folder whose token the mutation does not carry",
		"MakeCatalogAliveMutationOperator",
		"not yet analysed - out of scope of the branch that introduced this test",
		"SetCatalogStateMutationOperator",
		"activation needs a loaded Catalog that replay is forbidden from opening",
		"UpgradeCatalogFormatMutationOperator",
		"work phase performs non-idempotent disk work that replay must not repeat"
	);

	/**
	 * Lists the operator classes that sit next to {@link EngineMutationOperator} on the classpath, whether the
	 * package is a directory (module built in place) or a jar (module resolved as a dependency).
	 *
	 * @return simple names of every concrete `*MutationOperator` found
	 * @throws IOException when the classpath cannot be read
	 */
	@Nonnull
	private static List<String> discoverOperatorClassNames() throws IOException {
		final String packagePath = EngineMutationOperator.class.getPackageName().replace('.', '/');
		final List<String> found = new ArrayList<>(16);
		final Enumeration<URL> resources = EngineMutationOperator.class.getClassLoader().getResources(packagePath);
		while (resources.hasMoreElements()) {
			final URL resource = resources.nextElement();
			if ("jar".equals(resource.getProtocol())) {
				final JarURLConnection connection = (JarURLConnection) resource.openConnection();
				try (final JarFile jar = connection.getJarFile()) {
					final Enumeration<JarEntry> entries = jar.entries();
					while (entries.hasMoreElements()) {
						final String name = entries.nextElement().getName();
						if (name.startsWith(packagePath) && name.endsWith("MutationOperator.class")) {
							found.add(name.substring(name.lastIndexOf('/') + 1, name.length() - ".class".length()));
						}
					}
				}
			} else if ("file".equals(resource.getProtocol())) {
				final Path directory = Paths.get(resource.getPath());
				try (final Stream<Path> files = Files.list(directory)) {
					files.map(it -> it.getFileName().toString())
						.filter(it -> it.endsWith("MutationOperator.class"))
						.map(it -> it.substring(0, it.length() - ".class".length()))
						.forEach(found::add);
				}
			}
		}
		return found;
	}

	@Test
	@DisplayName("Every operator either replays forward or is named as knowingly unable to")
	void shouldNotSilentlyWedgeTheEngineOnAnyOperator() throws IOException, ClassNotFoundException {
		final List<String> operatorNames = discoverOperatorClassNames();
		// A scan that finds nothing passes every assertion below without checking anything, which is the failure
		// mode this kind of test is famous for.
		assertTrue(
			operatorNames.size() >= KNOWN_WITHOUT_FORWARD_REPLAY.size() + 1,
			() -> "The operator scan found only " + operatorNames + " - it is not seeing the package, so this " +
				"test proves nothing!"
		);

		final String packageName = EngineMutationOperator.class.getPackageName();
		final List<String> silentlyWedging = new ArrayList<>(8);
		final List<String> exemptedButImplemented = new ArrayList<>(8);
		for (final String simpleName : operatorNames) {
			final Class<?> operatorClass = Class.forName(packageName + '.' + simpleName);
			if (operatorClass.isInterface() || !EngineMutationOperator.class.isAssignableFrom(operatorClass)) {
				continue;
			}
			final boolean implementsReplay = Stream.of(operatorClass.getDeclaredMethods())
				.anyMatch(it -> "replayCompletionState".equals(it.getName()));
			final boolean exempted = KNOWN_WITHOUT_FORWARD_REPLAY.containsKey(simpleName);
			if (!implementsReplay && !exempted) {
				silentlyWedging.add(simpleName);
			} else if (implementsReplay && exempted) {
				exemptedButImplemented.add(simpleName);
			}
		}

		assertTrue(
			silentlyWedging.isEmpty(),
			() -> "These operators neither implement `replayCompletionState` nor declare why they cannot, so a " +
				"crash in the commit window wedges the entire engine with no record of the decision: " +
				silentlyWedging + ". Implement it, or add an entry with a reason to " +
				"KNOWN_WITHOUT_FORWARD_REPLAY."
		);
		// The list must not rot in the other direction either: an operator that has since been implemented but is
		// still listed reads as unfinished work and invites someone to "fix" it a second time.
		assertTrue(
			exemptedButImplemented.isEmpty(),
			() -> "These operators implement `replayCompletionState` but are still listed as unable to - remove " +
				"them from KNOWN_WITHOUT_FORWARD_REPLAY: " + exemptedButImplemented
		);
	}

	@Test
	@DisplayName("The rename and replace operator replays forward")
	void shouldReplayForwardOnTheRenameAndReplaceOperator() {
		// Called out separately from the sweep above because it is the operator this line of work set out to fix,
		// and because a sweep that is satisfied by an exemption entry would not notice it regressing into one.
		final boolean implementsReplay = Stream.of(ModifyCatalogSchemaNameMutationOperator.class.getDeclaredMethods())
			.anyMatch(it -> "replayCompletionState".equals(it.getName()));
		assertTrue(
			implementsReplay,
			"A crash while renaming or replacing a catalog must not wedge the engine!"
		);
		assertTrue(
			Set.copyOf(KNOWN_WITHOUT_FORWARD_REPLAY.keySet()).stream()
				.noneMatch("ModifyCatalogSchemaNameMutationOperator"::equals),
			"The rename/replace operator must not be listed as unable to replay forward!"
		);
	}

}
