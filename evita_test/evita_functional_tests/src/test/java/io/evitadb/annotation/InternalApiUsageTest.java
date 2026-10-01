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

package io.evitadb.annotation;

import io.evitadb.annotation.ClassFileReaderFixtures.Marked;
import io.evitadb.annotation.ClassFileReaderFixtures.Referencing;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the promise {@link Internal} makes: a member marked with it is `public` only so that a sibling module can
 * reach it, and **no module outside {@link #MODULES_ALLOWED_TO_REACH_INTERNAL_MEMBERS} may reference one**. Without
 * this check the annotation would be a comment with extra ceremony - nothing would stop a new module from binding
 * itself to a contract that is allowed to change without notice, and the first anyone would hear of it is a broken
 * downstream build.
 *
 * The scan reads **compiled class files** rather than sources, because a reference is exactly what a constant pool
 * records and nothing else can see it precisely. A source-level grep for a member name would flag every unrelated
 * method that happens to share it, and would miss a call written through a supertype; the constant pool names the
 * owner, the member and its descriptor, so an overload that is *not* marked stays unaffected by one that is. Every
 * module's `target/classes` directory below the repository root is scanned - its `target/test-classes` deliberately
 * is not, because tests are not a published surface and exercising an internal member is what many of them are for.
 *
 * Two rules decide a reference:
 *
 * - **The declaring module may always reach its own members.** `@Internal` is about cross-module reach; within one
 *   module the annotation carries documentation value only.
 * - **Every other module must be listed below.** Adding one is a deliberate, reviewable edit that has to name the
 *   reason, which is the whole point - the alternative is a rule that quietly widens itself.
 *
 * Read `.claude/rules/module-boundaries.md` before adding a module: where a whole *package* is internal, a
 * qualified export (`exports some.package to some.module;`) is the compiler-enforced answer and this annotation is
 * strictly weaker.
 *
 * {@link ClassFileReader} carries its own tests below, against the fixed class files of
 * {@link ClassFileReaderFixtures} rather than against whatever the reactor happens to hold. The reactor-wide scan
 * cannot check the reader that performs it - a scan that had gone blind reports the same clean result as a scan
 * that found nothing to report, which is why the floors above exist and why the reader is pinned separately.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Members marked @Internal must stay inside the modules allowed to reach them")
@Tag(CONTRACT)
@Tag(TEST_HARNESS)
class InternalApiUsageTest implements EvitaTestSupport {
	/**
	 * Field descriptor of the marker this test enforces, as it appears in a `RuntimeInvisibleAnnotations` attribute.
	 */
	private static final String INTERNAL_ANNOTATION_DESCRIPTOR = "Lio/evitadb/annotation/Internal;";
	/**
	 * Modules allowed to reference an `@Internal` member **declared in another module**, each with the reason it is
	 * here. A module is always allowed to reference its own members, so a declaring module needs no entry.
	 *
	 * Keys are module directories relative to the repository root - the same string the failure message prints.
	 */
	private static final Map<String, String> MODULES_ALLOWED_TO_REACH_INTERNAL_MEMBERS = Map.of(
		"evita_api",
		"builds the named reference content requirement map from the query the driver handed it",
		"evita_engine",
		"the fetch pipeline that materializes named reference sets lives here",
		"evita_store/evita_store_server",
		"the Kryo serializers have to round-trip a constraint exactly as it was built, instance name included",
		"evita_external_api/evita_external_api_graphql",
		"named reference content exists for the GraphQL field alias; this is the layer that puts one there",
		"evita_external_api/evita_external_api_grpc/server",
		"decides the session flags a remote session opens with, which is what makes them server-set, not client-set",
		"evita_external_api/evita_external_api_grpc/shared",
		"the client-side chunker rebuilds a paginated reference list from the slice the server sent",
		"evita_store/evita_store_entity",
		"rebuilds an enriched entity and has to carry the transformer the entity was originally read with"
	);
	/**
	 * Directories that hold no module output and would only make the walk slower - or, in the case of
	 * `.claude/worktrees`, hold whole parallel checkouts whose class files are not this build's.
	 */
	private static final Set<String> IGNORED_DIRECTORIES = Set.of(
		".git", ".idea", ".claude", ".github", "node_modules", "src", "documentation", "data"
	);
	/**
	 * Floor on the number of `@Internal` members the scan must find. Without it a run against an unbuilt or
	 * mis-resolved tree would find nothing, report no violation and pass - proving the opposite of what it claims.
	 * Eleven members carried the annotation when this was written; the floor sits below that with room for members
	 * to come and go, and only has to be high enough that an empty scan fails.
	 */
	private static final int MINIMAL_EXPECTED_INTERNAL_MEMBERS = 4;
	/**
	 * Floors on the number of **allowed** cross-module references the scan must observe, one per reference kind.
	 * A clean result means nothing unless the scan can be shown to see references at all, and the two kinds are
	 * counted apart because they are read by two different branches of the constant pool walk - a scan that had
	 * lost the member branch would still find plenty of type references and look healthy. The build held five
	 * allowed type references and fifteen member ones when this was written.
	 */
	private static final int MINIMAL_EXPECTED_ALLOWED_TYPE_REFERENCES = 1;
	private static final int MINIMAL_EXPECTED_ALLOWED_MEMBER_REFERENCES = 3;
	/**
	 * Floor on the number of module output directories the walk must find. The reactor held more than twenty when
	 * this was written; a run that resolved the wrong root, or one started before `mvn install`, finds far fewer.
	 */
	private static final int MINIMAL_EXPECTED_MODULES = 15;
	/**
	 * How long to wait before re-reading a class file that would not parse, on the assumption that a concurrent
	 * compilation was still writing it. Long enough for javac to finish one file, short enough that a genuinely
	 * broken class file does not hold the build up.
	 */
	private static final long TORN_FILE_RETRY_DELAY_MILLIS = 250L;
	/**
	 * Module name handed to the reader by the fixture tests. It never reaches the enforced set - those tests read a
	 * single class file directly and assert on what comes back, rather than walking the reactor.
	 */
	private static final String FIXTURE_MODULE = "fixture";

	@DisplayName("No module outside the allowlist references an @Internal member of another module")
	@Test
	void shouldNotReachInternalMembersFromDisallowedModules() {
		final Path rootDirectory = getRootDirectory();
		final Map<String, Path> moduleOutputs = collectModuleOutputDirectories(rootDirectory);

		assertTrue(
			moduleOutputs.size() >= MINIMAL_EXPECTED_MODULES,
			"Only " + moduleOutputs.size() + " compiled module(s) found below `" + rootDirectory.normalize() +
				"` - the scan cannot have covered the reactor, so a clean result proves nothing. Build the whole " +
				"reactor (`mvn install -DskipTests`) and check the resolved root directory."
		);

		final Map<MemberKey, InternalMember> internalMembers = collectInternalMembers(moduleOutputs);
		assertTrue(
			internalMembers.size() >= MINIMAL_EXPECTED_INTERNAL_MEMBERS,
			"Only " + internalMembers.size() + " member(s) marked `@Internal` found across " +
				moduleOutputs.size() + " module(s) - the scan cannot be reading the current build, so a clean " +
				"result proves nothing."
		);

		final Verdict verdict = collectViolations(moduleOutputs, internalMembers);
		assertTrue(
			verdict.allowedTypeReferenceCount() >= MINIMAL_EXPECTED_ALLOWED_TYPE_REFERENCES &&
				verdict.allowedMemberReferenceCount() >= MINIMAL_EXPECTED_ALLOWED_MEMBER_REFERENCES,
			"The scan observed only " + verdict.allowedTypeReferenceCount() + " allowed reference(s) to an " +
				"`@Internal` type and " + verdict.allowedMemberReferenceCount() + " to an `@Internal` member. The " +
				"modules listed in MODULES_ALLOWED_TO_REACH_INTERNAL_MEMBERS are there because they do reach both " +
				"kinds, so seeing none means that branch of the scan is blind and a clean result proves nothing."
		);

		if (!verdict.violations().isEmpty()) {
			fail(describeViolations(verdict.violations()));
		}
	}

	@DisplayName("Every `@Internal` declaration of a known class file is found, and only those")
	@Test
	void shouldReadTheInternalDeclarationsOfAKnownClassFile() {
		final Map<MemberKey, InternalMember> declarations = CollectionUtils.createHashMap(8);
		ClassFileReader.readDeclarations(classFileOf(Marked.class), FIXTURE_MODULE, declarations);

		final String marked = internalNameOf(Marked.class);
		assertEquals(
			4, declarations.size(),
			"The fixture marks its type, one field, one constructor and one method: " + declarations.keySet()
		);
		assertEquals(
			"use a supported fixture - this type exists to be found by the scan",
			declarations.get(MemberKey.ofType(marked)).alternative()
		);
		assertEquals(
			"read the supported accessor",
			declarations.get(new MemberKey(marked, "MARKED_FIELD", "[Ljava/lang/String;")).alternative()
		);
		// each marked member has an unmarked overload that differs by descriptor alone: a reader that indexed by
		// name would hand the allowlist members nobody marked, and the enforcement would be defending the wrong set
		assertNotNull(declarations.get(new MemberKey(marked, "<init>", "(I)V")));
		assertNull(declarations.get(new MemberKey(marked, "<init>", "(Ljava/lang/String;)V")));
		assertNotNull(declarations.get(new MemberKey(marked, "overload", "([Ljava/lang/String;)Ljava/lang/String;")));
		assertNull(declarations.get(new MemberKey(marked, "overload", "(I)Ljava/lang/String;")));
		assertEquals(
			"new io.evitadb.annotation.ClassFileReaderFixtures.Marked(I)V",
			declarations.get(new MemberKey(marked, "<init>", "(I)V")).displayName()
		);
	}

	@DisplayName("Every reference a known class file holds is recorded at descriptor precision")
	@Test
	void shouldReadTheReferencesOfAKnownClassFile() {
		final Set<MemberKey> references = CollectionUtils.createHashSet(64);
		final String referencingClass = ClassFileReader.readReferences(classFileOf(Referencing.class), references);

		final String marked = internalNameOf(Marked.class);
		assertEquals(internalNameOf(Referencing.class), referencingClass);
		assertTrue(references.contains(new MemberKey(marked, "<init>", "(I)V")), references.toString());
		assertTrue(
			references.contains(new MemberKey(marked, "<init>", "(Ljava/lang/String;)V")), references.toString()
		);
		assertTrue(
			references.contains(new MemberKey(marked, "MARKED_FIELD", "[Ljava/lang/String;")), references.toString()
		);
		assertTrue(
			references.contains(
				new MemberKey(marked, "overload", "([Ljava/lang/String;)Ljava/lang/String;")
			),
			references.toString()
		);
		assertTrue(
			references.contains(new MemberKey(marked, "overload", "(I)Ljava/lang/String;")), references.toString()
		);
		// the fixture names this type only as `new MarkedArrayElement[1][1]`, so its pool holds the array
		// descriptor and nothing else - this hit exists only because the array wrapper was stripped off
		assertTrue(
			references.contains(MemberKey.ofType(internalNameOf(InternalArrayElementFixture.class))),
			references.toString()
		);
	}

	@DisplayName("An array type reference is recorded against the type the array holds")
	@Test
	void shouldStripArrayDescriptorsDownToTheElementType() {
		assertEquals("io/evitadb/Foo", ClassFileReader.stripArrayDescriptor("io/evitadb/Foo"));
		assertEquals("io/evitadb/Foo", ClassFileReader.stripArrayDescriptor("[Lio/evitadb/Foo;"));
		assertEquals("io/evitadb/Foo", ClassFileReader.stripArrayDescriptor("[[[Lio/evitadb/Foo;"));
		// an array of primitives names no declared type at all, and must not be read as one
		assertEquals("I", ClassFileReader.stripArrayDescriptor("[I"));
	}

	@DisplayName("A class file still unreadable on the second attempt is named rather than passed over")
	@Test
	void shouldReportAClassFileThatIsUnreadableOnBothAttempts(@TempDir Path temporaryDirectory) throws IOException {
		final byte[] wholeClassFile = Files.readAllBytes(classFileOf(Marked.class));
		final Path truncated = temporaryDirectory.resolve("Truncated.class");
		// the magic number survives, so the file is recognisably a class file and fails inside the pool walk -
		// exactly what a class file caught half-written by a concurrent compilation looks like
		Files.write(truncated, Arrays.copyOf(wholeClassFile, 64));

		final IllegalStateException failure = assertThrows(
			IllegalStateException.class,
			() -> ClassFileReader.readReferences(truncated, CollectionUtils.createHashSet(8))
		);
		assertTrue(failure.getMessage().contains("Truncated.class"), failure.getMessage());
	}

	/**
	 * Locates the compiled form of a fixture class on the test classpath.
	 *
	 * @param type the fixture to read
	 * @return path of its `.class` file
	 */
	@Nonnull
	private static Path classFileOf(@Nonnull Class<?> type) {
		final String resourceName = internalNameOf(type) + ".class";
		final URL resource = type.getClassLoader().getResource(resourceName);
		assertNotNull(resource, "Fixture `" + resourceName + "` is not on the test classpath!");
		try {
			return Path.of(resource.toURI());
		} catch (URISyntaxException ex) {
			throw new IllegalStateException("Fixture `" + resourceName + "` is not readable as a file!", ex);
		}
	}

	/**
	 * Names a class the way a constant pool does.
	 *
	 * @param type the class to name
	 * @return its internal name, e.g. `io/evitadb/annotation/ClassFileReaderFixtures$Marked`
	 */
	@Nonnull
	private static String internalNameOf(@Nonnull Class<?> type) {
		return type.getName().replace('.', '/');
	}

	/**
	 * Renders the violations into the message the failing build prints, one block per offending module.
	 *
	 * @param violations violations grouped by the module that committed them
	 * @return human-readable report naming the alternative each internal member points at
	 */
	@Nonnull
	private static String describeViolations(@Nonnull Map<String, List<Violation>> violations) {
		final StringBuilder report = new StringBuilder(512);
		report.append("A member marked `@Internal` may change without notice and without a deprecation cycle, so ")
			.append("only the modules that implement evitaDB itself may reference one:");
		for (final Entry<String, List<Violation>> moduleEntry : violations.entrySet()) {
			report.append("\n\n\t").append(moduleEntry.getKey()).append(" reaches:");
			// the references come out of a hash set, so order them before printing - a build failure that
			// reads differently on every run is harder to compare against the previous one
			final List<Violation> moduleViolations = new ArrayList<>(moduleEntry.getValue());
			moduleViolations.sort(
				Comparator.comparing(Violation::referencingClass)
					.thenComparing(violation -> violation.member().displayName())
			);
			for (final Violation violation : moduleViolations) {
				report.append("\n\t\t").append(violation.referencingClass())
					.append("\n\t\t\t-> ").append(violation.member().displayName())
					.append("  (declared in ").append(violation.member().module()).append(')')
					.append("\n\t\t\t   instead: ").append(violation.member().alternative());
			}
		}
		report.append("\n\nUse the named alternative. When the reach is genuinely part of evitaDB's own ")
			.append("implementation, add the module to MODULES_ALLOWED_TO_REACH_INTERNAL_MEMBERS together with ")
			.append("the reason - and read `.claude/rules/module-boundaries.md` first, because a qualified ")
			.append("export is the stronger answer wherever a whole package is internal.");
		return report.toString();
	}

	/**
	 * Finds every module's main compiled output - a `classes` directory whose parent is named `target`.
	 *
	 * @param rootDirectory root of the repository checkout
	 * @return module directory relative to the root, mapped to its compiled output directory
	 */
	@Nonnull
	private static Map<String, Path> collectModuleOutputDirectories(@Nonnull Path rootDirectory) {
		final Path normalizedRoot = rootDirectory.normalize();
		final Map<String, Path> moduleOutputs = new TreeMap<>();
		try {
			Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
				@Nonnull
				@Override
				public FileVisitResult preVisitDirectory(
					@Nonnull Path directory,
					@Nonnull BasicFileAttributes attrs
				) {
					final String directoryName = directory.getFileName().toString();
					if (directory.equals(normalizedRoot)) {
						return FileVisitResult.CONTINUE;
					}
					if (IGNORED_DIRECTORIES.contains(directoryName)) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					final Path parent = directory.getParent();
					final boolean insideTarget = parent != null && "target".equals(parent.getFileName().toString());
					if (insideTarget) {
						// only the main output is a published surface - `test-classes` and generated sources are not
						if ("classes".equals(directoryName)) {
							moduleOutputs.put(
								normalizedRoot.relativize(parent.getParent()).toString().replace('\\', '/'),
								directory
							);
						}
						return FileVisitResult.SKIP_SUBTREE;
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return moduleOutputs;
	}

	/**
	 * Reads every compiled class of every module and indexes the members carrying {@link Internal}.
	 *
	 * @param moduleOutputs module directory mapped to its compiled output directory
	 * @return internal members, keyed by the owner/name/descriptor triple a constant pool records
	 */
	@Nonnull
	private static Map<MemberKey, InternalMember> collectInternalMembers(@Nonnull Map<String, Path> moduleOutputs) {
		final Map<MemberKey, InternalMember> internalMembers = CollectionUtils.createHashMap(32);
		for (final Entry<String, Path> moduleEntry : moduleOutputs.entrySet()) {
			for (final Path classFile : listClassFiles(moduleEntry.getValue())) {
				ClassFileReader.readDeclarations(classFile, moduleEntry.getKey(), internalMembers);
			}
		}
		return internalMembers;
	}

	/**
	 * Reads every compiled class again, this time for the references its constant pool records, and sorts each hit
	 * on an internal member into "allowed" or "violation".
	 *
	 * @param moduleOutputs   module directory mapped to its compiled output directory
	 * @param internalMembers the members to look for
	 * @return the violations found, plus the allowed references the scan observed, counted per reference kind
	 */
	@Nonnull
	private static Verdict collectViolations(
		@Nonnull Map<String, Path> moduleOutputs,
		@Nonnull Map<MemberKey, InternalMember> internalMembers
	) {
		final Map<String, List<Violation>> violations = new TreeMap<>();
		int allowedTypeReferenceCount = 0;
		int allowedMemberReferenceCount = 0;
		for (final Entry<String, Path> moduleEntry : moduleOutputs.entrySet()) {
			final String module = moduleEntry.getKey();
			final boolean allowed = MODULES_ALLOWED_TO_REACH_INTERNAL_MEMBERS.containsKey(module);
			for (final Path classFile : listClassFiles(moduleEntry.getValue())) {
				final Set<MemberKey> references = CollectionUtils.createHashSet(64);
				final String referencingClass = ClassFileReader.readReferences(classFile, references);
				for (final MemberKey reference : references) {
					final InternalMember member = internalMembers.get(reference);
					// the declaring module always reaches its own members - `@Internal` governs cross-module reach
					if (member == null || member.module().equals(module)) {
						continue;
					}
					if (allowed) {
						if (reference.memberName().isEmpty()) {
							allowedTypeReferenceCount++;
						} else {
							allowedMemberReferenceCount++;
						}
					} else {
						violations
							.computeIfAbsent(module, key -> new ArrayList<>(8))
							.add(new Violation(toJavaName(referencingClass), member));
					}
				}
			}
		}
		return new Verdict(violations, allowedTypeReferenceCount, allowedMemberReferenceCount);
	}

	/**
	 * Lists every `*.class` file below the passed directory in a stable order.
	 *
	 * @param outputDirectory compiled output directory of a single module
	 * @return the class files it holds
	 */
	@Nonnull
	private static List<Path> listClassFiles(@Nonnull Path outputDirectory) {
		final List<Path> classFiles = new ArrayList<>(1024);
		try {
			Files.walkFileTree(outputDirectory, new SimpleFileVisitor<>() {
				@Nonnull
				@Override
				public FileVisitResult visitFile(@Nonnull Path file, @Nonnull BasicFileAttributes attrs) {
					if (file.getFileName().toString().endsWith(".class")) {
						classFiles.add(file);
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		classFiles.sort(Path::compareTo);
		return classFiles;
	}

	/**
	 * Converts an internal class name (`io/evitadb/Foo$Bar`) into the source form a reader recognises.
	 *
	 * @param internalName class name as the constant pool records it
	 * @return the same name with package and nesting separators a reader expects
	 */
	@Nonnull
	private static String toJavaName(@Nonnull String internalName) {
		return internalName.replace('/', '.').replace('$', '.');
	}

	/**
	 * Identity of a member as a constant pool records it. A type carries the empty name and descriptor, so that a
	 * class reference and a member reference can share one index.
	 *
	 * @param ownerClass  internal name of the declaring class, e.g. `io/evitadb/api/query/require/ReferenceContent`
	 * @param memberName  member name, the JVM's `<init>` for a constructor and empty for a type
	 * @param descriptor  member descriptor, empty for a type
	 */
	private record MemberKey(
		@Nonnull String ownerClass,
		@Nonnull String memberName,
		@Nonnull String descriptor
	) {

		/**
		 * Creates the key standing for the type itself rather than one of its members.
		 *
		 * @param ownerClass internal name of the class
		 * @return key matching every reference to the type
		 */
		@Nonnull
		static MemberKey ofType(@Nonnull String ownerClass) {
			return new MemberKey(ownerClass, "", "");
		}

	}

	/**
	 * A member found to carry {@link Internal}, with what the failure message needs to say about it.
	 *
	 * @param module      module directory the member is declared in, relative to the repository root
	 * @param displayName how the member is named in the report
	 * @param alternative the {@link Internal#value()} the declaration carries
	 */
	private record InternalMember(
		@Nonnull String module,
		@Nonnull String displayName,
		@Nonnull String alternative
	) {
	}

	/**
	 * One disallowed reference.
	 *
	 * @param referencingClass class that holds the reference
	 * @param member           internal member it reaches
	 */
	private record Violation(
		@Nonnull String referencingClass,
		@Nonnull InternalMember member
	) {
	}

	/**
	 * Outcome of the reference scan.
	 *
	 * @param violations                  disallowed references grouped by the module that holds them
	 * @param allowedTypeReferenceCount   allowed cross-module references to an internal **type**
	 * @param allowedMemberReferenceCount allowed cross-module references to an internal **member**
	 */
	private record Verdict(
		@Nonnull Map<String, List<Violation>> violations,
		int allowedTypeReferenceCount,
		int allowedMemberReferenceCount
	) {
	}

	/**
	 * Minimal reader of the parts of a class file this test needs: the constant pool, the class name, and the
	 * `RuntimeInvisibleAnnotations` attributes of the class and of each field and method.
	 *
	 * It exists rather than a library call because the alternative would be a new dependency for a hundred lines of
	 * well-specified format parsing - see the JVM specification, chapter 4. Only the structures that carry a
	 * reference or an annotation are interpreted; everything else is skipped by its declared length.
	 */
	private static final class ClassFileReader {
		/**
		 * Constant pool tags this reader interprets; the rest are skipped by their fixed width.
		 */
		private static final int CONSTANT_UTF8 = 1;
		private static final int CONSTANT_INTEGER = 3;
		private static final int CONSTANT_FLOAT = 4;
		private static final int CONSTANT_LONG = 5;
		private static final int CONSTANT_DOUBLE = 6;
		private static final int CONSTANT_CLASS = 7;
		private static final int CONSTANT_STRING = 8;
		private static final int CONSTANT_FIELDREF = 9;
		private static final int CONSTANT_METHODREF = 10;
		private static final int CONSTANT_INTERFACE_METHODREF = 11;
		private static final int CONSTANT_NAME_AND_TYPE = 12;
		private static final int CONSTANT_METHOD_HANDLE = 15;
		private static final int CONSTANT_METHOD_TYPE = 16;
		private static final int CONSTANT_DYNAMIC = 17;
		private static final int CONSTANT_INVOKE_DYNAMIC = 18;
		private static final int CONSTANT_MODULE = 19;
		private static final int CONSTANT_PACKAGE = 20;

		private final byte[] data;
		private int position;
		private int[] tags;
		private String[] utf8Constants;
		private int[] firstIndexes;
		private int[] secondIndexes;

		private ClassFileReader(@Nonnull byte[] data) {
			this.data = data;
		}

		/**
		 * Indexes the members of one class file that carry {@link Internal}.
		 *
		 * @param classFile       the compiled class to read
		 * @param module          module directory the class was compiled from
		 * @param internalMembers index the findings are added to
		 */
		static void readDeclarations(
			@Nonnull Path classFile,
			@Nonnull String module,
			@Nonnull Map<MemberKey, InternalMember> internalMembers
		) {
			readTolerantly(
				classFile,
				data -> {
					final ClassFileReader reader = new ClassFileReader(data);
					final String className = reader.readHeader();
					reader.skipInterfaces();
					reader.readMembers(className, module, internalMembers, true);
					reader.readMembers(className, module, internalMembers, false);
					final String typeAlternative = reader.readAnnotatedAttributes();
					if (typeAlternative != null) {
						internalMembers.put(
							MemberKey.ofType(className),
							new InternalMember(module, toJavaName(className), typeAlternative)
						);
					}
					return className;
				}
			);
		}

		/**
		 * Collects every type and member the passed class refers to. The constant pool alone answers this - a
		 * reference is a pool entry whether it is called, read, cast to or captured by a method handle.
		 *
		 * @param classFile  the compiled class to read
		 * @param references set the findings are added to
		 * @return internal name of the class that holds the references
		 */
		@Nonnull
		static String readReferences(@Nonnull Path classFile, @Nonnull Set<MemberKey> references) {
			return readTolerantly(classFile, data -> collectReferences(data, references));
		}

		/**
		 * Collects every type and member the passed class bytes refer to. Split out of
		 * {@link #readReferences(Path, Set)} so that the retry can simply run it again - it only ever adds to
		 * `references`, so a second pass over the same class is idempotent.
		 *
		 * @param data       bytes of one compiled class
		 * @param references set the findings are added to
		 * @return internal name of the class that holds the references
		 */
		@Nonnull
		private static String collectReferences(@Nonnull byte[] data, @Nonnull Set<MemberKey> references) {
			final ClassFileReader reader = new ClassFileReader(data);
			final String className = reader.readHeader();
			for (int i = 1; i < reader.tags.length; i++) {
				switch (reader.tags[i]) {
					case CONSTANT_CLASS -> {
						final String referencedType = reader.utf8Constants[reader.firstIndexes[i]];
						// an array type records its descriptor rather than a bare name - the element type is what
						// the reference is about
						references.add(MemberKey.ofType(stripArrayDescriptor(referencedType)));
					}
					case CONSTANT_FIELDREF, CONSTANT_METHODREF, CONSTANT_INTERFACE_METHODREF -> {
						final int nameAndType = reader.secondIndexes[i];
						references.add(
							new MemberKey(
								reader.utf8Constants[reader.firstIndexes[reader.firstIndexes[i]]],
								reader.utf8Constants[reader.firstIndexes[nameAndType]],
								reader.utf8Constants[reader.secondIndexes[nameAndType]]
							)
						);
					}
					default -> {
						// every other constant kind describes a value, not a reference to a declared member
					}
				}
			}
			return className;
		}

		/**
		 * Runs one class file through the passed reader, and runs it once more after a pause when the first
		 * attempt fails.
		 *
		 * A parallel reactor build (`mvn -T 1C`, which is what CI uses) compiles modules this test module does
		 * not depend on while its tests are already running - `evita_java_driver_observability` and the
		 * performance tests have no dependents at all - so the walk can catch a class file javac is still
		 * writing. A torn file is transient and the second read gets the whole one. A file that fails twice is a
		 * genuinely broken class file, and the test says which one rather than reporting a clean scan.
		 *
		 * @param classFile the compiled class to read
		 * @param reader    what to do with its bytes; must be safe to run twice
		 * @param <T>       whatever the reader produces
		 * @return the reader's result
		 */
		@Nonnull
		private static <T> T readTolerantly(@Nonnull Path classFile, @Nonnull Function<byte[], T> reader) {
			try {
				return reader.apply(readAllBytes(classFile));
			} catch (RuntimeException firstAttempt) {
				try {
					Thread.sleep(TORN_FILE_RETRY_DELAY_MILLIS);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw firstAttempt;
				}
				try {
					return reader.apply(readAllBytes(classFile));
				} catch (RuntimeException secondAttempt) {
					throw new IllegalStateException(
						"Class file `" + classFile + "` cannot be read on a second attempt either, so it is a " +
							"broken class file rather than one a concurrent compilation was still writing.",
						secondAttempt
					);
				}
			}
		}

		/**
		 * Reads the file's bytes, turning the checked failure into an unchecked one so that
		 * {@link #readTolerantly(Path, Function)} handles a vanished or half-written file the same way it
		 * handles a half-parsed one.
		 *
		 * @param classFile the compiled class to read
		 * @return its bytes
		 */
		@Nonnull
		private static byte[] readAllBytes(@Nonnull Path classFile) {
			try {
				return Files.readAllBytes(classFile);
			} catch (IOException ex) {
				throw new UncheckedIOException("Cannot read class file `" + classFile + "`!", ex);
			}
		}

		/**
		 * Drops the leading `[` characters and the `L…;` wrapper an array type records instead of a bare name.
		 *
		 * @param typeName type name as the constant pool records it
		 * @return the element type's internal name
		 */
		@Nonnull
		private static String stripArrayDescriptor(@Nonnull String typeName) {
			int start = 0;
			while (start < typeName.length() && typeName.charAt(start) == '[') {
				start++;
			}
			if (start == 0) {
				return typeName;
			}
			if (start < typeName.length() && typeName.charAt(start) == 'L' && typeName.endsWith(";")) {
				return typeName.substring(start + 1, typeName.length() - 1);
			}
			// an array of primitives names no declared type at all
			return typeName.substring(start);
		}

		/**
		 * Reads the magic number, the constant pool and the class name, leaving the cursor on the superclass index.
		 *
		 * @return internal name of the class this file declares
		 */
		@Nonnull
		private String readHeader() {
			final int magic = readInt();
			if (magic != 0xCAFEBABE) {
				throw new IllegalStateException("Not a class file - magic number is 0x" + Integer.toHexString(magic));
			}
			skip(4);
			readConstantPool();
			skip(2);
			final String className = this.utf8Constants[this.firstIndexes[readUnsignedShort()]];
			skip(2);
			return className;
		}

		/**
		 * Reads the constant pool into the parallel arrays this reader resolves references through.
		 */
		private void readConstantPool() {
			final int constantPoolCount = readUnsignedShort();
			this.tags = new int[constantPoolCount];
			this.utf8Constants = new String[constantPoolCount];
			this.firstIndexes = new int[constantPoolCount];
			this.secondIndexes = new int[constantPoolCount];
			for (int i = 1; i < constantPoolCount; i++) {
				final int tag = readUnsignedByte();
				this.tags[i] = tag;
				switch (tag) {
					case CONSTANT_UTF8 -> this.utf8Constants[i] = readUtf8();
					case CONSTANT_CLASS, CONSTANT_STRING, CONSTANT_METHOD_TYPE, CONSTANT_MODULE, CONSTANT_PACKAGE ->
						this.firstIndexes[i] = readUnsignedShort();
					case CONSTANT_FIELDREF, CONSTANT_METHODREF, CONSTANT_INTERFACE_METHODREF,
						 CONSTANT_NAME_AND_TYPE, CONSTANT_DYNAMIC, CONSTANT_INVOKE_DYNAMIC -> {
						this.firstIndexes[i] = readUnsignedShort();
						this.secondIndexes[i] = readUnsignedShort();
					}
					case CONSTANT_INTEGER, CONSTANT_FLOAT -> skip(4);
					case CONSTANT_LONG, CONSTANT_DOUBLE -> {
						skip(8);
						// a long or a double occupies two pool slots, the second one unusable
						i++;
					}
					case CONSTANT_METHOD_HANDLE -> skip(3);
					default -> throw new IllegalStateException("Unknown constant pool tag " + tag + '!');
				}
			}
		}

		/**
		 * Skips the interface table, leaving the cursor on the field count.
		 */
		private void skipInterfaces() {
			skip(2 * readUnsignedShort());
		}

		/**
		 * Reads one member table - fields first, then methods - and indexes the entries carrying {@link Internal}.
		 *
		 * @param className       internal name of the declaring class
		 * @param module          module directory the class was compiled from
		 * @param internalMembers index the findings are added to
		 * @param fields          TRUE while reading the field table, FALSE for the method table
		 */
		private void readMembers(
			@Nonnull String className,
			@Nonnull String module,
			@Nonnull Map<MemberKey, InternalMember> internalMembers,
			boolean fields
		) {
			final int memberCount = readUnsignedShort();
			for (int i = 0; i < memberCount; i++) {
				skip(2);
				final String memberName = this.utf8Constants[readUnsignedShort()];
				final String descriptor = this.utf8Constants[readUnsignedShort()];
				final String alternative = readAnnotatedAttributes();
				if (alternative != null) {
					internalMembers.put(
						new MemberKey(className, memberName, descriptor),
						new InternalMember(
							module,
							describeMember(className, memberName, descriptor, fields),
							alternative
						)
					);
				}
			}
		}

		/**
		 * Names a member the way the failure report should print it.
		 *
		 * @param className  internal name of the declaring class
		 * @param memberName member name as the constant pool records it
		 * @param descriptor member descriptor
		 * @param field      TRUE for a field, FALSE for a method or constructor
		 * @return readable name of the member
		 */
		@Nonnull
		private static String describeMember(
			@Nonnull String className,
			@Nonnull String memberName,
			@Nonnull String descriptor,
			boolean field
		) {
			final String javaName = toJavaName(className);
			if (field) {
				return javaName + '#' + memberName;
			}
			if ("<init>".equals(memberName)) {
				return "new " + javaName + descriptor;
			}
			return javaName + '#' + memberName + descriptor;
		}

		/**
		 * Reads an attribute table and returns the {@link Internal#value()} of the marker it carries, if any.
		 * Attributes other than `RuntimeInvisibleAnnotations` are skipped by their declared length.
		 *
		 * @return the annotation's value, or `null` when the element is not marked
		 */
		@Nullable
		private String readAnnotatedAttributes() {
			final int attributeCount = readUnsignedShort();
			String alternative = null;
			for (int i = 0; i < attributeCount; i++) {
				final String attributeName = this.utf8Constants[readUnsignedShort()];
				final int attributeLength = readInt();
				final int attributeEnd = this.position + attributeLength;
				if ("RuntimeInvisibleAnnotations".equals(attributeName)) {
					final String found = readAnnotations();
					if (found != null) {
						alternative = found;
					}
				}
				this.position = attributeEnd;
			}
			return alternative;
		}

		/**
		 * Reads a `RuntimeInvisibleAnnotations` attribute body.
		 *
		 * @return value of the {@link Internal} marker it holds, or `null` when it holds none
		 */
		@Nullable
		private String readAnnotations() {
			final int annotationCount = readUnsignedShort();
			String alternative = null;
			for (int i = 0; i < annotationCount; i++) {
				final String typeDescriptor = this.utf8Constants[readUnsignedShort()];
				final boolean internal = INTERNAL_ANNOTATION_DESCRIPTOR.equals(typeDescriptor);
				final int pairCount = readUnsignedShort();
				for (int pair = 0; pair < pairCount; pair++) {
					final String elementName = this.utf8Constants[readUnsignedShort()];
					final String elementValue = readElementValue();
					if (internal && "value".equals(elementName) && elementValue != null) {
						alternative = elementValue;
					}
				}
				if (internal && alternative == null) {
					// `value()` has no default, so this cannot happen for a compiled call site - but a report that
					// silently dropped the member would be worse than one naming it without an alternative
					alternative = "no alternative recorded";
				}
			}
			return alternative;
		}

		/**
		 * Reads one `element_value` structure, returning its content when it is a string and skipping it otherwise.
		 *
		 * @return the string the element holds, or `null` for every other element kind
		 */
		@Nullable
		private String readElementValue() {
			final int tag = readUnsignedByte();
			switch (tag) {
				case 's' -> {
					return this.utf8Constants[readUnsignedShort()];
				}
				case 'e' -> skip(4);
				case '@' -> {
					skip(2);
					final int pairCount = readUnsignedShort();
					for (int pair = 0; pair < pairCount; pair++) {
						skip(2);
						readElementValue();
					}
				}
				case '[' -> {
					final int valueCount = readUnsignedShort();
					for (int value = 0; value < valueCount; value++) {
						readElementValue();
					}
				}
				default -> skip(2);
			}
			return null;
		}

		/**
		 * Reads a modified-UTF8 constant. Only the single-byte form can occur in the identifiers and descriptors
		 * this test reads, but the full decoding costs nothing and keeps an exotic string from derailing the scan.
		 *
		 * @return the decoded string
		 */
		@Nonnull
		private String readUtf8() {
			final int length = readUnsignedShort();
			final StringBuilder decoded = new StringBuilder(length);
			final int end = this.position + length;
			while (this.position < end) {
				final int first = readUnsignedByte();
				if (first < 0x80) {
					decoded.append((char) first);
				} else if ((first & 0xE0) == 0xC0) {
					decoded.append((char) (((first & 0x1F) << 6) | (readUnsignedByte() & 0x3F)));
				} else {
					final int second = readUnsignedByte();
					final int third = readUnsignedByte();
					decoded.append(
						(char) (((first & 0x0F) << 12) | ((second & 0x3F) << 6) | (third & 0x3F))
					);
				}
			}
			return decoded.toString();
		}

		private int readUnsignedByte() {
			return this.data[this.position++] & 0xFF;
		}

		private int readUnsignedShort() {
			return (readUnsignedByte() << 8) | readUnsignedByte();
		}

		private int readInt() {
			return (readUnsignedShort() << 16) | readUnsignedShort();
		}

		private void skip(int bytes) {
			this.position += bytes;
		}

	}

}
