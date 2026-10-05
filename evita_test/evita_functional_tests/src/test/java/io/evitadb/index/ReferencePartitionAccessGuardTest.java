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

package io.evitadb.index;

import io.evitadb.core.collection.EntityCollection;
import io.evitadb.store.catalog.DefaultEntityCollectionPersistenceService;
import io.evitadb.utils.CollectionUtils;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the reference partition handle: every place in the production bytecode of `evita_engine`
 * and `evita_store_server` that reaches a `REFERENCED_ENTITY` reduced index directly - rather than through
 * {@link ReferencePartition} and its resolvers - must be on the allow-list, and every allow-list entry must still be
 * hit. The allow-list therefore only ever shrinks, and a change that moves a site behind the handle proves it by
 * deleting the entry.
 *
 * ## Rules
 *
 * A hit is attributed to `Class#member`; a lambda body (`lambda$m$n`) counts as its enclosing method `m`, a field as
 * `Class#field:name`, the class header as `Class#<class>`.
 *
 * An entry names a member, not a method, so it vouches for exactly one overload of the name: a member whose hits come
 * from more overloads than its entry declares fails the test, naming their descriptors - otherwise a new overload
 * could hide behind the entry an old one earned. A member whose overloads are all allowed on purpose declares how
 * many there are, as `Class#member (N overloads)`. A lambda body counts as the overload it was written in; bridges
 * and other synthetic methods, which only forward, count as none.
 *
 * - **R1 type use** of {@link ReducedEntityIndex} or {@link AbstractReducedEntityIndex}: `NEW`, `CHECKCAST`,
 *   `INSTANCEOF`, `ANEWARRAY`, class tokens, the owner of a member access, field and method descriptors, generic
 *   signatures, local variable types, and `invokedynamic` call sites (method references).
 * - **R2 key construction**: reading `EntityIndexType.REFERENCED_ENTITY`, or naming it in an enum `switch`. A
 *   pattern `switch` names it at its own call site; a classic `switch` is compiled into a synthetic switch map class
 *   and is therefore reported as `Outer$N#<clinit>`. One allow-list entry for that site covers every classic `switch`
 *   over the index type in the outer class - a known coarseness of the rule.
 * - **R3 raw by-primary-key resolution**, inherently ambiguous between the two reduced index families: calls and
 *   method references of the by-primary-key accessors on any owner, including `getIndexIfExists(int, IntFunction)`
 *   that Lombok delegates onto `EntityCollection` - invisible in its source.
 * - **R4 partition primary key enumeration**: `forEachReferenceIndexPrimaryKey`, `getAllReferenceIndexes`.
 *
 * The reduced index classes themselves are not scanned - their references to their own type are not accesses.
 *
 * ## Why bytecode
 *
 * The delegated method exists only in bytecode, method references are `invokedynamic` handles, a by-primary-key read
 * followed by `getAllPrimaryKeys()` never names the type, and the type names appear in hundreds of comments. A source
 * scan would miss the first three and drown in the last.
 *
 * ## Honesty checks
 *
 * A green run could be vacuous - a scanner reading the wrong jar, or one that stopped recognising a shape. Hence: a
 * stale allow-list entry fails the test; a minimum number of scanned classes and a sentinel (the resolver itself must
 * be reported) are asserted; and {@link ReferencePartitionGuardFixture} carries one member per shape, each of which
 * must be reported, next to shapes that must not be.
 *
 * The scan reads the **built** class files on the test class path - after changing engine code, rebuild it
 * (`mvn -o install -pl evita_engine -DskipTests`) or run the test with `-am`, otherwise it judges the stale artifact.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference partition access guard")
@Tag(INDEXING)
@Tag(REFERENCE)
class ReferencePartitionAccessGuardTest {
	/**
	 * The allow-list resource, one entry per line: `rule | Class#member | reason`.
	 */
	private static final String ALLOW_LIST = "reference-partition-guard-allowlist.txt";
	/**
	 * The scan must read at least this many classes of the two modules together, or it read the wrong code source.
	 */
	private static final int MINIMUM_SCANNED_CLASSES = 2_000;
	/**
	 * Internal names of the classes whose own bytecode is not scanned.
	 */
	private static final Set<String> SELF_CLASSES = Set.of(
		"io/evitadb/index/ReducedEntityIndex",
		"io/evitadb/index/AbstractReducedEntityIndex",
		"io/evitadb/index/ReducedGroupEntityIndex"
	);
	/**
	 * Matches either reduced index type inside a descriptor or a generic signature.
	 */
	private static final Pattern TYPE_IN_DESCRIPTOR = Pattern.compile(
		"Lio/evitadb/index/(Abstract)?ReducedEntityIndex[;<]"
	);
	/**
	 * Names of the raw by-primary-key accessors reported by rule R3 whatever their descriptor.
	 * `getEntityIndexByPrimaryKey` and `getEntityIndexByPrimaryKeyIfExists` name no production accessor any more -
	 * they stay listed so that reintroducing one under either name is reported rather than pruned as a dead entry.
	 */
	private static final Set<String> RAW_PRIMARY_KEY_ACCESSORS = Set.of(
		"getIndexByPrimaryKeyIfExists",
		"getIndexByPrimaryKey",
		"getOrCreateIndexByPrimaryKey",
		"getEntityIndexByPrimaryKey",
		"getEntityIndexByPrimaryKeyIfExists",
		"getEntityIndexByPrimaryKeyForModification"
	);
	/**
	 * Parameter descriptor prefix of the `DataStoreReader#getIndexIfExists(int, IntFunction)` overload reported by R3.
	 */
	private static final String DELEGATED_RAW_ACCESSOR_PARAMETERS = "(ILjava/util/function/IntFunction;)";
	/**
	 * Names of the partition primary key enumerations reported by rule R4.
	 */
	private static final Set<String> ENUMERATIONS = Set.of("forEachReferenceIndexPrimaryKey", "getAllReferenceIndexes");
	/**
	 * Stands for the descriptor of a bridge or another forwarding synthetic method, which counts as no overload.
	 */
	private static final String FORWARDER = "forwarder";
	/**
	 * Matches the member of an allow-list entry, capturing `Class#member` and the declared number of overloads.
	 */
	private static final Pattern ALLOW_LIST_MEMBER = Pattern.compile("^(\\S+)(?: \\((\\d+) overloads\\))?$");
	/**
	 * Matches the synthetic name javac gives a lambda body, capturing the enclosing method.
	 */
	private static final Pattern LAMBDA = Pattern.compile("^lambda\\$(.+)\\$\\d+$");

	/**
	 * Scans the production classes of `evita_engine` and `evita_store_server` and compares the hits with the
	 * allow-list in both directions.
	 */
	@Test
	@DisplayName("finds no direct partition access outside the allow-list, and no stale allow-list entry")
	void shouldReportNoDirectPartitionAccessOutsideAllowList() {
		final Scanner scanner = new Scanner();
		final Set<Path> sources = new TreeSet<>(
			List.of(codeSource(EntityCollection.class), codeSource(DefaultEntityCollectionPersistenceService.class))
		);
		for (Path source : sources) {
			scanner.scanCodeSource(source);
		}
		assertTrue(
			scanner.scannedClasses >= MINIMUM_SCANNED_CLASSES,
			"Only " + scanner.scannedClasses + " classes were scanned in " + sources + " - the guard is reading the " +
				"wrong code source and its green would be vacuous"
		);
		// the resolver itself narrows and builds keys - a scan that does not see it sees nothing
		assertTrue(
			scanner.hits.containsKey("R1 io.evitadb.index.ReferencePartitions#asPartition") &&
				scanner.hits.containsKey("R2 io.evitadb.index.ReferencePartitions#createKey"),
			"The sentinel sites in ReferencePartitions were not reported - the scanner is broken"
		);

		final Map<String, AllowListEntry> allowList = readAllowList();
		final Map<String, Set<String>> unexpected = new TreeMap<>();
		for (Map.Entry<String, Set<String>> hit : scanner.hits.entrySet()) {
			if (!allowList.containsKey(hit.getKey())) {
				unexpected.put(hit.getKey(), hit.getValue());
			}
		}
		final Set<String> stale = new TreeSet<>();
		for (String entry : allowList.keySet()) {
			if (!scanner.hits.containsKey(entry)) {
				stale.add(entry);
			}
		}
		// an entry names a member, not a method: a name hit in several overloads would let a new overload hide behind
		// the entry an old one earned
		final Map<String, Set<String>> overloaded = new TreeMap<>();
		for (Map.Entry<String, Set<String>> overload : scanner.overloads.entrySet()) {
			final AllowListEntry entry = allowList.get(overload.getKey());
			final int vouchedFor = entry == null ? 1 : entry.overloads();
			final int found = overload.getValue().size();
			if (found > vouchedFor || (vouchedFor > 1 && found != vouchedFor)) {
				overloaded.put(overload.getKey(), overload.getValue());
			}
		}
		if (!unexpected.isEmpty() || !stale.isEmpty() || !overloaded.isEmpty()) {
			final StringBuilder message = new StringBuilder(256 + 128 * (unexpected.size() + stale.size()));
			if (!unexpected.isEmpty()) {
				message.append(unexpected.size())
					.append(" direct reduced index access(es) outside the allow-list - resolve the partition through ")
					.append("ReferencePartition / ReferencePartitions instead, or add a justified entry to ")
					.append(ALLOW_LIST).append(":\n");
				for (Map.Entry<String, Set<String>> hit : unexpected.entrySet()) {
					final String[] ruleAndSite = hit.getKey().split(" ", 2);
					message.append(ruleAndSite[0]).append(" | ").append(ruleAndSite[1])
						.append(" | ? ").append(hit.getValue()).append('\n');
				}
			}
			if (!overloaded.isEmpty()) {
				message.append(overloaded.size())
					.append(" member(s) hit in another number of overloads than their allow-list entry vouches for - ")
					.append("an entry is keyed by the member name, so a new overload must reach the partition through ")
					.append("the handle, and overloads allowed on purpose are declared as ")
					.append("`Class#member (N overloads)`:\n");
				for (Map.Entry<String, Set<String>> overload : overloaded.entrySet()) {
					final String[] ruleAndSite = overload.getKey().split(" ", 2);
					message.append(ruleAndSite[0]).append(" | ").append(ruleAndSite[1])
						.append(" | overloads ").append(overload.getValue()).append('\n');
				}
			}
			if (!stale.isEmpty()) {
				message.append(stale.size())
					.append(" allow-list entr(y/ies) no longer hit - delete them from ")
					.append(ALLOW_LIST).append(":\n");
				for (String entry : stale) {
					message.append(entry).append('\n');
				}
			}
			fail(message.toString());
		}
	}

	/**
	 * Scans the counterfactual fixture, its nested classes and the synthetic classes javac generated for it, and
	 * asserts that every shape it carries is reported, under the right rule and attributed to the right member - and
	 * that nothing else is.
	 */
	@Test
	@DisplayName("reports every access shape of the counterfactual fixture and nothing else")
	void shouldReportEveryShapeOfCounterfactualFixture() {
		final Scanner scanner = new Scanner();
		final String fixtureName = ReferencePartitionGuardFixture.class.getSimpleName();
		final Path fixturePackage = codeSource(ReferencePartitionGuardFixture.class)
			.resolve(ReferencePartitionGuardFixture.class.getPackageName().replace('.', '/'));
		try (Stream<Path> files = Files.list(fixturePackage)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				final String fileName = file.getFileName().toString();
				if (
					fileName.endsWith(".class") &&
						(fileName.equals(fixtureName + ".class") || fileName.startsWith(fixtureName + "$"))
				) {
					try (InputStream is = Files.newInputStream(file)) {
						scanner.scanClass(is);
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		assertTrue(
			scanner.scannedClasses >= 3,
			"The fixture, its nested class and its switch map must all be scanned, but only " +
				scanner.scannedClasses + " class file(s) were found in " + fixturePackage
		);
		final String fixture = ReferencePartitionGuardFixture.class.getName() + "#";
		final String nested = ReferencePartitionGuardFixture.class.getName() + "$";
		final Map<String, String> expected = CollectionUtils.createLinkedHashMap(21);
		expected.put("R1 " + fixture + "castShape", "CHECKCAST");
		expected.put("R1 " + fixture + "instanceofShape", "INSTANCEOF");
		expected.put("R1 " + fixture + "classTokenShape", "LDC");
		expected.put("R1 " + fixture + "field:genericField", "signature");
		expected.put("R1 " + fixture + "genericSignatureShape", "signature");
		expected.put("R1 " + fixture + "methodReferenceShape", "indy");
		expected.put("R2 " + fixture + "keyShape", "REFERENCED_ENTITY");
		expected.put("R3 " + fixture + "delegatedRawPrimaryKeyShape", "getIndexIfExists");
		expected.put("R3 " + fixture + "untypedRawPrimaryKeyShape", "getIndexByPrimaryKeyIfExists");
		expected.put("R4 " + fixture + "enumerationShape", "getAllReferenceIndexes");
		expected.put("R1 " + fixture + "lambdaShape", "INSTANCEOF");
		expected.put("R1 " + fixture + "newShape", "NEW");
		expected.put("R1 " + fixture + "arrayShape", "ANEWARRAY");
		expected.put("R1 " + fixture + "descriptorShape", "descriptor");
		expected.put("R1 " + fixture + "localVariableShape", "local");
		expected.put("R1 " + nested + "ExtendsShape#<class>", "extends");
		expected.put("R1 " + nested + "ExtendsShape#<init>", "call");
		expected.put("R2 " + nested + "1#<clinit>", "REFERENCED_ENTITY");
		expected.put("R3 " + fixture + "modificationByPrimaryKeyShape", "getIndexForModification");
		expected.put("R4 " + fixture + "enumerationByCallbackShape", "forEachReferenceIndexPrimaryKey");
		expected.put("R1 " + fixture + "overloadShape", "CHECKCAST");
		for (Map.Entry<String, String> shape : expected.entrySet()) {
			final Set<String> evidence = scanner.hits.get(shape.getKey());
			assertNotNull(evidence, "The guard does not report `" + shape.getKey() + "` - found only " + scanner.hits);
			assertTrue(
				evidence.stream().anyMatch(it -> it.contains(shape.getValue())),
				"`" + shape.getKey() + "` was reported, but not through `" + shape.getValue() + "`: " + evidence
			);
		}
		// both overloads of one name are told apart - the one hit in a lambda body by the method it was written in
		assertEquals(
			Set.of(
				"(Lio/evitadb/index/Index;)Lio/evitadb/index/bitmap/Bitmap;",
				"(Ljava/lang/Object;I)Ljava/util/function/Predicate;"
			),
			scanner.overloads.get("R1 " + fixture + "overloadShape"),
			"The overloads of `overloadShape` were not told apart: " + scanner.overloads
		);
		// a lambda body in a single overload counts as that overload, not as a second one
		assertEquals(1, scanner.overloads.get("R1 " + fixture + "lambdaShape").size(), scanner.overloads.toString());
		// no hit may be attributed to a synthetic lambda method instead of its enclosing one
		for (String site : scanner.hits.keySet()) {
			assertFalse(site.contains("lambda$"), "A lambda body was not attributed to its enclosing method: " + site);
		}
		// the shapes that must not be reported - the group family, a group key, a by-key lookup, the handle - are
		// covered here: any hit of theirs would be one more than expected
		assertEquals(expected.size(), scanner.hits.size(), "Unexpected hits in the fixture: " + scanner.hits);
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
	 * Reads the allow-list resource into `rule Class#member` keys mapped to their entries. A member may declare the
	 * number of its overloads the entry vouches for as `Class#member (N overloads)`; it vouches for one otherwise.
	 *
	 * @return the allow-list entries
	 */
	@Nonnull
	private static Map<String, AllowListEntry> readAllowList() {
		final Map<String, AllowListEntry> result = new TreeMap<>();
		try (
			InputStream is = ReferencePartitionAccessGuardTest.class.getResourceAsStream(ALLOW_LIST);
			BufferedReader reader = new BufferedReader(
				new InputStreamReader(
					Objects.requireNonNull(is, "Allow-list resource " + ALLOW_LIST + " not found"),
					StandardCharsets.UTF_8
				)
			)
		) {
			String line;
			int lineNumber = 0;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				final String trimmed = line.strip();
				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					continue;
				}
				final String[] parts = trimmed.split("\\s*\\|\\s*", 3);
				assertEquals(
					3, parts.length,
					ALLOW_LIST + ":" + lineNumber + " must read `rule | Class#member | reason`"
				);
				assertFalse(parts[2].isBlank(), ALLOW_LIST + ":" + lineNumber + " carries no reason");
				final Matcher member = ALLOW_LIST_MEMBER.matcher(parts[1]);
				assertTrue(
					member.matches(),
					ALLOW_LIST + ":" + lineNumber + " must name `Class#member` or `Class#member (N overloads)`"
				);
				final int overloads = member.group(2) == null ? 1 : Integer.parseInt(member.group(2));
				assertTrue(
					member.group(2) == null || overloads > 1,
					ALLOW_LIST + ":" + lineNumber + " declares fewer than two overloads - leave the count out"
				);
				final AllowListEntry previous = result.put(
					parts[0] + " " + member.group(1), new AllowListEntry(parts[2], overloads)
				);
				assertNull(previous, ALLOW_LIST + ":" + lineNumber + " duplicates an earlier entry");
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return result;
	}

	/**
	 * One allow-list entry.
	 *
	 * @param reason    why the site reaches a reduced entity index directly
	 * @param overloads how many overloads of the member the entry vouches for
	 */
	private record AllowListEntry(@Nonnull String reason, int overloads) {
	}

	/**
	 * Bytecode scanner collecting rule hits as `rule Class#member` keys with the evidence found at each.
	 */
	private static final class Scanner {
		/**
		 * Hits by `rule Class#member`, each with the instructions or descriptors that produced it.
		 */
		private final Map<String, Set<String>> hits = new TreeMap<>();
		/**
		 * The descriptors of the methods behind every hit by `rule Class#member` - a lambda body counted under the
		 * descriptor of the method it was written in. More than one descriptor means that several overloads sharing
		 * one name hit the rule, which one allow-list entry, keyed by the name alone, cannot tell apart.
		 */
		private final Map<String, Set<String>> overloads = new TreeMap<>();
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
						for (Path file : (Iterable<Path>) files::iterator) {
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
			if (!path.endsWith(".class") || path.endsWith("module-info.class") || path.startsWith("META-INF/")) {
				return false;
			}
			final String internalName = path.substring(0, path.length() - ".class".length());
			final int nested = internalName.indexOf('$');
			return !SELF_CLASSES.contains(nested < 0 ? internalName : internalName.substring(0, nested));
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
		 * Records a hit.
		 *
		 * @param rule     the rule
		 * @param site     `Class#member`
		 * @param evidence what produced the hit
		 */
		private void hit(@Nonnull String rule, @Nonnull String site, @Nonnull String evidence) {
			this.hits.computeIfAbsent(rule + " " + site, key -> new TreeSet<>()).add(evidence);
		}

		/**
		 * Records the descriptor of the method a hit was found in.
		 *
		 * @param rule       the rule
		 * @param site       `Class#member`
		 * @param descriptor the descriptor of the method - the enclosing one for a lambda body
		 */
		private void overload(@Nonnull String rule, @Nonnull String site, @Nonnull String descriptor) {
			this.overloads.computeIfAbsent(rule + " " + site, key -> new TreeSet<>()).add(descriptor);
		}

		/**
		 * Tells whether an internal name, possibly of an array type, names a reduced index type.
		 *
		 * @param internalName the internal name
		 * @return true for either reduced index type
		 */
		private static boolean isReducedIndexType(@Nullable String internalName) {
			if (internalName == null) {
				return false;
			}
			final String elementName = internalName.startsWith("[") ?
				Type.getType(internalName).getElementType().getInternalName() : internalName;
			return "io/evitadb/index/ReducedEntityIndex".equals(elementName) ||
				"io/evitadb/index/AbstractReducedEntityIndex".equals(elementName);
		}

		/**
		 * Tells whether a descriptor or a generic signature mentions a reduced index type.
		 *
		 * @param descriptorOrSignature the descriptor or signature, possibly `null`
		 * @return true when it mentions either reduced index type
		 */
		private static boolean mentionsReducedIndexType(@Nullable String descriptorOrSignature) {
			return descriptorOrSignature != null && TYPE_IN_DESCRIPTOR.matcher(descriptorOrSignature).find();
		}

		/**
		 * Visits one class, attributing every hit to a member of it.
		 */
		private final class GuardClassVisitor extends ClassVisitor {
			/**
			 * Binary name of the visited class.
			 */
			private String className;
			/**
			 * Internal name of the visited class.
			 */
			private String internalName;
			/**
			 * The method each lambda body of the class was created in, as `name descriptor`, by the lambda method
			 * name - learned from the `invokedynamic` instructions creating them.
			 */
			private final Map<String, String> lambdaCreators = new TreeMap<>();
			/**
			 * The hits found in lambda bodies, as `rule`, `site` and the lambda method name, whose descriptor is
			 * resolved once the whole class has been visited.
			 */
			private final List<String[]> lambdaHits = new ArrayList<>(4);

			GuardClassVisitor() {
				super(Opcodes.ASM9);
			}

			@Override
			public void visit(
				int version, int access, String name, String signature, String superName, String[] interfaces
			) {
				this.className = name.replace('/', '.');
				this.internalName = name;
				final String site = this.className + "#<class>";
				if (isReducedIndexType(superName)) {
					hit("R1", site, "extends " + superName);
				}
				if (mentionsReducedIndexType(signature)) {
					hit("R1", site, "signature " + signature);
				}
			}

			@Override
			public FieldVisitor visitField(
				int access, String name, String descriptor, String signature, Object value
			) {
				final String site = this.className + "#field:" + name;
				if (mentionsReducedIndexType(descriptor)) {
					hit("R1", site, "descriptor " + descriptor);
				}
				if (mentionsReducedIndexType(signature)) {
					hit("R1", site, "signature " + signature);
				}
				return null;
			}

			@Override
			public MethodVisitor visitMethod(
				int access, String name, String descriptor, String signature, String[] exceptions
			) {
				final String site = this.className + "#" + enclosingMethod(name);
				final String countedDescriptor = countedDescriptor(access, name, descriptor);
				if (mentionsReducedIndexType(descriptor)) {
					methodHit("R1", site, name, countedDescriptor, "descriptor " + descriptor);
				}
				if (mentionsReducedIndexType(signature)) {
					methodHit("R1", site, name, countedDescriptor, "signature " + signature);
				}
				return new GuardMethodVisitor(this, site, name, countedDescriptor(access, name, descriptor));
			}

			@Override
			public void visitEnd() {
				for (final String[] lambdaHit : this.lambdaHits) {
					final String descriptor = resolveLambdaCreator(lambdaHit[2]);
					// a lambda body created in a forwarder counts as no overload, like the forwarder itself
					if (!FORWARDER.equals(descriptor)) {
						overload(lambdaHit[0], lambdaHit[1], descriptor);
					}
				}
			}

			/**
			 * Records a hit found in a method of the class, together with the descriptor of the method - for a lambda
			 * body, of the method it was written in, resolved once the whole class has been visited.
			 *
			 * @param rule             the rule
			 * @param site             `Class#member`
			 * @param methodName       the name of the method the hit was found in
			 * @param methodDescriptor the descriptor of the method the hit was found in
			 * @param evidence         what produced the hit
			 */
			void methodHit(
				@Nonnull String rule,
				@Nonnull String site,
				@Nonnull String methodName,
				@Nullable String methodDescriptor,
				@Nonnull String evidence
			) {
				hit(rule, site, evidence);
				if (LAMBDA.matcher(methodName).matches()) {
					this.lambdaHits.add(new String[]{rule, site, methodName});
				} else if (methodDescriptor != null) {
					overload(rule, site, methodDescriptor);
				}
			}

			/**
			 * Returns the descriptor a method's hits are counted under as an overload: its own, except for a bridge or
			 * another synthetic method that is not a lambda body - those only forward to a method that counts.
			 *
			 * @param access     the access flags of the method
			 * @param name       the name of the method
			 * @param descriptor the descriptor of the method
			 * @return the descriptor, or `null` when the method counts as no overload
			 */
			@Nullable
			private static String countedDescriptor(int access, @Nonnull String name, @Nonnull String descriptor) {
				final boolean forwarder = (access & Opcodes.ACC_BRIDGE) != 0 ||
					((access & Opcodes.ACC_SYNTHETIC) != 0 && !LAMBDA.matcher(name).matches());
				return forwarder ? null : descriptor;
			}

			/**
			 * Remembers the method a lambda body of the class is created in.
			 *
			 * @param handle           the implementation handle of an `invokedynamic` instruction
			 * @param methodName       the name of the method holding the instruction
			 * @param methodDescriptor the descriptor of the method holding the instruction
			 */
			void lambdaCreated(
				@Nonnull Handle handle,
				@Nonnull String methodName,
				@Nullable String methodDescriptor
			) {
				if (this.internalName.equals(handle.getOwner()) && LAMBDA.matcher(handle.getName()).matches()) {
					this.lambdaCreators.put(
						handle.getName(), methodName + " " + (methodDescriptor == null ? FORWARDER : methodDescriptor)
					);
				}
			}

			/**
			 * Returns the descriptor of the method a lambda body was written in, following lambdas created in lambdas
			 * to the method that is not one.
			 *
			 * @param lambdaName the name of the lambda method
			 * @return the descriptor, or a marker naming the lambda when no creating instruction was found
			 */
			@Nonnull
			private String resolveLambdaCreator(@Nonnull String lambdaName) {
				String current = lambdaName;
				for (int depth = 0; depth < 16; depth++) {
					final String creator = this.lambdaCreators.get(current);
					if (creator == null) {
						return "unresolved " + lambdaName;
					}
					final String creatorName = creator.substring(0, creator.indexOf(' '));
					if (!LAMBDA.matcher(creatorName).matches()) {
						return creator.substring(creator.indexOf(' ') + 1);
					}
					current = creatorName;
				}
				return "unresolved " + lambdaName;
			}

			/**
			 * Maps a synthetic lambda body to the method it was written in.
			 *
			 * @param methodName the method name
			 * @return the enclosing method name
			 */
			@Nonnull
			private static String enclosingMethod(@Nonnull String methodName) {
				final Matcher matcher = LAMBDA.matcher(methodName);
				if (!matcher.matches()) {
					return methodName;
				}
				final String enclosing = matcher.group(1);
				return switch (enclosing) {
					case "new" -> "<init>";
					case "static" -> "<clinit>";
					default -> enclosing;
				};
			}
		}

		/**
		 * Visits the body of one method.
		 */
		private final class GuardMethodVisitor extends MethodVisitor {
			/**
			 * The class visitor the hits are reported through.
			 */
			private final GuardClassVisitor classVisitor;
			/**
			 * `Class#member` every hit of this body is attributed to.
			 */
			private final String site;
			/**
			 * The name of the visited method.
			 */
			private final String methodName;
			/**
			 * The descriptor the hits of the visited method are counted under, `null` when they count as no overload.
			 */
			@Nullable private final String methodDescriptor;

			GuardMethodVisitor(
				@Nonnull GuardClassVisitor classVisitor,
				@Nonnull String site,
				@Nonnull String methodName,
				@Nullable String methodDescriptor
			) {
				super(Opcodes.ASM9);
				this.classVisitor = classVisitor;
				this.site = site;
				this.methodName = methodName;
				this.methodDescriptor = methodDescriptor;
			}

			/**
			 * Records a hit of this body.
			 *
			 * @param rule     the rule
			 * @param evidence what produced the hit
			 */
			private void bodyHit(@Nonnull String rule, @Nonnull String evidence) {
				this.classVisitor.methodHit(rule, this.site, this.methodName, this.methodDescriptor, evidence);
			}

			@Override
			public void visitTypeInsn(int opcode, String type) {
				if (isReducedIndexType(type)) {
					final String instruction = switch (opcode) {
						case Opcodes.NEW -> "NEW";
						case Opcodes.CHECKCAST -> "CHECKCAST";
						case Opcodes.INSTANCEOF -> "INSTANCEOF";
						case Opcodes.ANEWARRAY -> "ANEWARRAY";
						default -> "opcode " + opcode;
					};
					bodyHit("R1", instruction + " " + type);
				}
			}

			@Override
			public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
				if (mentionsReducedIndexType(descriptor)) {
					bodyHit("R1", "MULTIANEWARRAY " + descriptor);
				}
			}

			@Override
			public void visitLdcInsn(Object value) {
				if (value instanceof Type type && mentionsReducedIndexType(type.getDescriptor())) {
					bodyHit("R1", "LDC " + type.getDescriptor());
				}
			}

			@Override
			public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
				if (isReducedIndexType(owner) || mentionsReducedIndexType(descriptor)) {
					bodyHit("R1", "field " + owner + "." + name + " " + descriptor);
				}
				if (
					opcode == Opcodes.GETSTATIC && "io/evitadb/api/index/EntityIndexType".equals(owner) &&
						"REFERENCED_ENTITY".equals(name)
				) {
					bodyHit("R2", "GETSTATIC EntityIndexType.REFERENCED_ENTITY");
				}
			}

			@Override
			public void visitMethodInsn(
				int opcode, String owner, String name, String descriptor, boolean isInterface
			) {
				checkMethod(owner, name, descriptor, "call");
			}

			@Override
			public void visitInvokeDynamicInsn(
				String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments
			) {
				if (mentionsReducedIndexType(descriptor)) {
					bodyHit("R1", "indy descriptor " + descriptor);
				}
				for (Object argument : bootstrapMethodArguments) {
					if (argument instanceof Handle handle) {
						this.classVisitor.lambdaCreated(handle, this.methodName, this.methodDescriptor);
						if (isReducedIndexType(handle.getOwner()) || mentionsReducedIndexType(handle.getDesc())) {
							bodyHit("R1", "indy handle " + handle.getOwner() + "." + handle.getName());
						}
						checkMethod(handle.getOwner(), handle.getName(), handle.getDesc(), "indy handle");
					} else if (argument instanceof Type type && mentionsReducedIndexType(type.getDescriptor())) {
						bodyHit("R1", "indy type " + type.getDescriptor());
					} else if (
						"REFERENCED_ENTITY".equals(argument) &&
							"java/lang/runtime/SwitchBootstraps".equals(bootstrapMethodHandle.getOwner())
					) {
						bodyHit("R2", "enum switch label REFERENCED_ENTITY");
					}
				}
			}

			@Override
			public void visitLocalVariable(
				String name, String descriptor, String signature, Label start, Label end, int index
			) {
				if (mentionsReducedIndexType(descriptor) || mentionsReducedIndexType(signature)) {
					bodyHit("R1", "local " + name + " " + (signature == null ? descriptor : signature));
				}
			}

			/**
			 * Applies rules R1, R3 and R4 to a called or referenced method.
			 *
			 * @param owner      the owner of the method
			 * @param name       the method name
			 * @param descriptor the method descriptor
			 * @param channel    how the method is reached, for the evidence
			 */
			private void checkMethod(
				@Nonnull String owner,
				@Nonnull String name,
				@Nonnull String descriptor,
				@Nonnull String channel
			) {
				if (isReducedIndexType(owner) || mentionsReducedIndexType(descriptor)) {
					bodyHit("R1", channel + " " + owner + "." + name + descriptor);
				}
				if (
					RAW_PRIMARY_KEY_ACCESSORS.contains(name) ||
						("getIndexForModification".equals(name) && descriptor.startsWith("(I")) ||
						("getIndexIfExists".equals(name) && descriptor.startsWith(DELEGATED_RAW_ACCESSOR_PARAMETERS))
				) {
					bodyHit("R3", channel + " " + owner + "." + name + descriptor);
				}
				if (ENUMERATIONS.contains(name)) {
					bodyHit("R4", channel + " " + owner + "." + name);
				}
			}
		}
	}

}
