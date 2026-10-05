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

import io.evitadb.core.collection.EntityCollection;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.store.catalog.DefaultEntityCollectionPersistenceService;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards who writes the registry of single-owner partitions: in the production bytecode of `evita_engine` and
 * `evita_store_server`, only {@link VirtualReferencePartitionWriter} and the index mutators that decide the
 * transitions of a partition's owner count - the reference, attribute and entity index mutators - and the registry
 * itself may call the writer or a mutator of {@link VirtualReferencePartitionIndex}, directly or by a method reference.
 *
 * ## What it guarantees, and what it does not
 *
 * The rule restricts the **direct** callers. It is an architectural restriction that keeps the registry written from
 * the places that see a partition's owner count change, and it catches a new direct write from anywhere else - a schema
 * change, a load step, a query. It does **not** prove that no other path reaches the registry: a method of
 * `EntityCollection` calling an allowed helper of the reference index mutator that writes the registry passes it. That
 * behaviour is proven by running the paths with the maintenance on - see
 * `io.evitadb.index.virtual.VirtualReferencePartitionEnabledLoadTest`.
 *
 * ## Honesty checks
 *
 * A green run could be vacuous - a scanner reading the wrong code source, or one that stopped recognising a call. Hence
 * a minimum number of scanned classes, sentinel calls that must be found at their allowed sites, and a counterfactual
 * fixture ({@link VirtualReferencePartitionWriterGuardFixture}) whose every shape must be reported, next to a read that
 * must not be.
 *
 * The scan reads the **built** class files on the test class path - after changing engine code, rebuild it or run the
 * test with `-am`, otherwise it judges the stale artifact.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition writer guard")
@Tag(INDEXING)
@Tag(REFERENCE)
class VirtualReferencePartitionWriterGuardTest {
	/**
	 * Internal name of the writer.
	 */
	private static final String WRITER = "io/evitadb/index/mutation/local/VirtualReferencePartitionWriter";
	/**
	 * Internal name of the registry.
	 */
	private static final String REGISTRY = "io/evitadb/index/VirtualReferencePartitionIndex";
	/**
	 * The methods of the registry that change it.
	 */
	private static final Set<String> REGISTRY_MUTATORS = Set.of(
		"putPartitionOwner", "removePartitionOwner", "removePartition", "retireAllKeysIfNoPartition",
		"putRepresentativeTuple", "removeRepresentativeTuple",
		"declareSortKey", "retireSortKey", "putSortValue", "putNormalizedSortValue", "removeSortValue",
		"declareChainKey", "retireChainKey", "putChainPredecessor", "removeChainPredecessor"
	);
	/**
	 * Internal names of the classes allowed to call the writer and the registry mutators - their nested and synthetic
	 * classes included.
	 */
	private static final Set<String> ALLOWED_CALLERS = Set.of(
		WRITER,
		REGISTRY,
		"io/evitadb/index/mutation/local/ReferenceIndexMutator",
		"io/evitadb/index/mutation/local/AttributeIndexMutator",
		"io/evitadb/index/mutation/local/EntityIndexLocalMutationExecutor"
	);
	/**
	 * The scan must read at least this many classes of the two modules together, or it read the wrong code source.
	 */
	private static final int MINIMUM_SCANNED_CLASSES = 2_000;
	/**
	 * Matches the name of a synthetic lambda method, capturing the enclosing method.
	 */
	private static final Pattern LAMBDA = Pattern.compile("^lambda\\$(.+)\\$\\d+$");

	@Test
	@DisplayName("only the writer, the index mutators and the registry itself write the registry")
	void shouldFindNoRegistryWriteOutsideTheAllowedCallers() {
		final Scanner scanner = new Scanner();
		final Set<Path> sources = new TreeSet<>(
			List.of(codeSource(EntityCollection.class), codeSource(DefaultEntityCollectionPersistenceService.class))
		);
		for (final Path source : sources) {
			scanner.scanCodeSource(source);
		}
		assertTrue(
			scanner.scannedClasses >= MINIMUM_SCANNED_CLASSES,
			"Only " + scanner.scannedClasses + " classes were scanned in " + sources + " - the guard is reading the " +
				"wrong code source and its green would be vacuous"
		);
		// the transitions decided by the reference index mutator and the sort values the attribute index mutator
		// writes reach the writer, and the writer the registry - a scan that does not see these sees nothing
		assertTrue(
			scanner.allowed.containsKey(
				"io.evitadb.index.mutation.local.ReferenceIndexMutator#ownerAddedToPartition -> " +
					"VirtualReferencePartitionWriter.partitionEntered"
			) && scanner.allowed.containsKey(
				"io.evitadb.index.mutation.local.VirtualReferencePartitionWriter#partitionEntered -> " +
					"VirtualReferencePartitionIndex.putPartitionOwner"
			) && scanner.allowed.containsKey(
				"io.evitadb.index.mutation.local.AttributeIndexMutator#executeAttributeUpsert -> " +
					"VirtualReferencePartitionWriter.attributeValueAdded"
			) && scanner.allowed.containsKey(
				"io.evitadb.index.mutation.local.VirtualReferencePartitionWriter#attributeValueAdded -> " +
					"VirtualReferencePartitionIndex.putSortValue"
			),
			"The sentinel calls were not found - the scanner is broken: " + scanner.allowed.keySet()
		);
		if (!scanner.forbidden.isEmpty()) {
			final StringBuilder message = new StringBuilder(128 + 128 * scanner.forbidden.size());
			message.append(scanner.forbidden.size())
				.append(" write(s) of the registry of single-owner partitions outside the writer and the index ")
				.append("mutators - route the change through a transition the reference index mutator decides:\n");
			for (final Map.Entry<String, Set<String>> hit : scanner.forbidden.entrySet()) {
				message.append(hit.getKey()).append(' ').append(hit.getValue()).append('\n');
			}
			fail(message.toString());
		}
	}

	@Test
	@DisplayName("reports every write shape of the counterfactual fixture and no read")
	void shouldReportEveryShapeOfTheCounterfactualFixture() {
		final Scanner scanner = new Scanner();
		final String fixtureName = VirtualReferencePartitionWriterGuardFixture.class.getSimpleName();
		final Path fixturePackage = codeSource(VirtualReferencePartitionWriterGuardFixture.class)
			.resolve(VirtualReferencePartitionWriterGuardFixture.class.getPackageName().replace('.', '/'));
		try (Stream<Path> files = Files.list(fixturePackage)) {
			for (final Path file : (Iterable<Path>) files::iterator) {
				final String fileName = file.getFileName().toString();
				if (fileName.equals(fixtureName + ".class") || fileName.startsWith(fixtureName + "$")) {
					try (InputStream is = Files.newInputStream(file)) {
						scanner.scanClass(is);
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		final String fixture = VirtualReferencePartitionWriterGuardFixture.class.getName() + "#";
		assertEquals(
			Set.of(
				fixture + "mutatorCallShape -> VirtualReferencePartitionIndex.putPartitionOwner",
				fixture + "writerCallShape -> VirtualReferencePartitionWriter.partitionEntered",
				fixture + "methodReferenceShape -> VirtualReferencePartitionIndex.removePartition",
				fixture + "lambdaShape -> VirtualReferencePartitionIndex.retireAllKeysIfNoPartition"
			),
			scanner.forbidden.keySet(),
			"every write shape must be reported, attributed to its enclosing method, and the read must not"
		);
	}

	/**
	 * Returns the code source - a class directory or a jar - the class was loaded from.
	 *
	 * @param type the class
	 * @return the path of its code source
	 */
	@Nonnull
	private static Path codeSource(@Nonnull Class<?> type) {
		try {
			return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Bytecode scanner sorting the calls of the writer and of the registry mutators into allowed and forbidden ones,
	 * each keyed by `Class#member -> Callee.method` with the instructions that produced it.
	 */
	private static final class Scanner {
		/**
		 * The calls made from an allowed caller.
		 */
		private final Map<String, Set<String>> allowed = new TreeMap<>();
		/**
		 * The calls made from any other class.
		 */
		private final Map<String, Set<String>> forbidden = new TreeMap<>();
		/**
		 * Number of class files scanned.
		 */
		private int scannedClasses;

		/**
		 * Scans every class file of a class directory or a jar.
		 *
		 * @param codeSource the directory or the jar
		 */
		void scanCodeSource(@Nonnull Path codeSource) {
			try {
				if (Files.isDirectory(codeSource)) {
					try (Stream<Path> files = Files.walk(codeSource)) {
						for (final Path file : (Iterable<Path>) files::iterator) {
							if (isScannedClassFile(codeSource.relativize(file).toString().replace('\\', '/'))) {
								try (InputStream is = Files.newInputStream(file)) {
									scanClass(is);
								}
							}
						}
					}
				} else {
					try (JarFile jar = new JarFile(codeSource.toFile())) {
						final Enumeration<JarEntry> entries = jar.entries();
						while (entries.hasMoreElements()) {
							final JarEntry entry = entries.nextElement();
							if (isScannedClassFile(entry.getName())) {
								try (InputStream is = jar.getInputStream(entry)) {
									scanClass(is);
								}
							}
						}
					}
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		/**
		 * Tells whether a code source entry is a class file the guard scans.
		 *
		 * @param path the entry path relative to the code source
		 * @return true for a scanned class file
		 */
		private static boolean isScannedClassFile(@Nonnull String path) {
			return path.endsWith(".class") && !path.endsWith("module-info.class") && !path.startsWith("META-INF/");
		}

		/**
		 * Scans one class file.
		 *
		 * @param classFile the class file bytes
		 * @throws IOException when the bytes cannot be read
		 */
		void scanClass(@Nonnull InputStream classFile) throws IOException {
			this.scannedClasses++;
			new ClassReader(classFile).accept(new GuardClassVisitor(), ClassReader.SKIP_FRAMES);
		}

		/**
		 * Records a call of the writer or a registry mutator if the callee is one.
		 *
		 * @param className  internal name of the calling class
		 * @param methodName the calling method, a lambda body counted as its enclosing method
		 * @param owner      internal name of the callee's owner
		 * @param name       the callee
		 * @param evidence   the instruction that produced the call
		 */
		private void record(
			@Nonnull String className,
			@Nonnull String methodName,
			@Nonnull String owner,
			@Nonnull String name,
			@Nonnull String evidence
		) {
			final boolean writerCall = WRITER.equals(owner) && !"<init>".equals(name);
			final boolean registryWrite = REGISTRY.equals(owner) && REGISTRY_MUTATORS.contains(name);
			if (!writerCall && !registryWrite) {
				return;
			}
			final int nested = className.indexOf('$');
			final String outerClass = nested < 0 ? className : className.substring(0, nested);
			final String site = className.replace('/', '.') + "#" + methodName + " -> " +
				owner.substring(owner.lastIndexOf('/') + 1) + "." + name;
			(ALLOWED_CALLERS.contains(outerClass) ? this.allowed : this.forbidden)
				.computeIfAbsent(site, it -> new TreeSet<>())
				.add(evidence);
		}

		/**
		 * Visits a class and hands each of its methods to a {@link GuardMethodVisitor}.
		 */
		private final class GuardClassVisitor extends ClassVisitor {
			/**
			 * Internal name of the visited class.
			 */
			private String className;

			GuardClassVisitor() {
				super(Opcodes.ASM9);
			}

			@Override
			public void visit(
				int version,
				int access,
				String name,
				String signature,
				String superName,
				String[] interfaces
			) {
				this.className = name;
			}

			@Override
			public MethodVisitor visitMethod(
				int access,
				String name,
				String descriptor,
				String signature,
				String[] exceptions
			) {
				final Matcher lambda = LAMBDA.matcher(name);
				return new GuardMethodVisitor(this.className, lambda.matches() ? lambda.group(1) : name);
			}
		}

		/**
		 * Records the calls of one method - plain invocations and the method handles of `invokedynamic` call sites,
		 * which is what a method reference compiles to.
		 */
		private final class GuardMethodVisitor extends MethodVisitor {
			/**
			 * Internal name of the class the method belongs to.
			 */
			private final String className;
			/**
			 * The method, a lambda body counted as its enclosing method.
			 */
			private final String methodName;

			GuardMethodVisitor(@Nonnull String className, @Nonnull String methodName) {
				super(Opcodes.ASM9);
				this.className = className;
				this.methodName = methodName;
			}

			@Override
			public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
				record(this.className, this.methodName, owner, name, "call");
			}

			@Override
			public void visitInvokeDynamicInsn(
				String name,
				String descriptor,
				Handle bootstrapMethodHandle,
				Object... bootstrapMethodArguments
			) {
				for (final Object argument : bootstrapMethodArguments) {
					if (argument instanceof final Handle handle) {
						record(this.className, this.methodName, handle.getOwner(), handle.getName(), "indy");
					}
				}
			}
		}
	}
}
