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
import io.evitadb.index.mutation.ReadResolverGuardFixture;
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
 * An entry names a member, and a member may have several overloads - an entry that does not say which of them it
 * vouches for could be satisfied by another one hitting in its place. Hence an entry vouches for the only overload of
 * a member that declares one, and otherwise pins the descriptors of the overloads it vouches for, as
 * `Class#member (overloads desc1 desc2)`: the overloads hit must then be exactly the pinned ones. An unpinned entry
 * of a member declaring several overloads, an overload hit that the entry does not pin and a pinned overload no longer
 * hit all fail the test, naming the descriptors. A lambda body counts as the overload it was written in; bridges and
 * other synthetic methods, which only forward, count as none.
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
 * - **R5 partition key handed out**: calls and method references of {@link ReferencePartitions#createKey} outside
 *   {@link ReferencePartitions} itself. A by-key lookup is not reported - it cannot confuse the two reduced index
 *   families - but a key built by the resolver's factory and looked up outside the resolver yields the partition as
 *   a raw index, which a caller can read without naming its type, building the key by hand or resolving it by
 *   primary key. The resolver builds the key inside the methods that narrow its result to the handle, so that is the
 *   only place the factory may be called from.
 * - **R6 read resolution on the write path**: calls and method references of a read resolver - one that reads the
 *   registry of a referenced-type entity index and may therefore answer a {@link VirtualReferencePartition} - from the
 *   write path (the packages of the mutation executors, the buffers and the storage) or from the physical index
 *   inspection (the index browse, detail and cardinality projections and the members of `EntityCollection` serving them
 *   and the membership rebuild). A write reaches a partition only through its reduced entity index, and the inspection
 *   reports the physical index while it exists. R6 has no legitimate site, so the allow-list carries no entry for it.
 * - **R7 owner read resolution**: calls and method references of an owner read resolver - one that answers a partition
 *   the registry holds a row for with its read-only virtual handle in a catalog representing its single-owner
 *   partitions virtually - outside {@link ReferencePartitions} itself, wherever they are. Only the few places that read
 *   nothing but the owners and the key of a partition may hold such a handle, and each of them is on the allow-list.
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
	 * Internal name of the partition resolver, the only class allowed to call its key factory (rule R5).
	 */
	private static final String RESOLVER = "io/evitadb/index/ReferencePartitions";
	/**
	 * Internal-name prefixes of the classes of the write path, which must not resolve partitions for a read (rule R6).
	 */
	private static final List<String> WRITE_PATH_PACKAGES = List.of(
		"io/evitadb/index/mutation/", "io/evitadb/core/buffer/", "io/evitadb/store/"
	);
	/**
	 * Internal names of the physical index inspection classes, which must not resolve partitions for a read (rule R6)
	 * - their nested classes included.
	 */
	private static final Set<String> PHYSICAL_INSPECTION_CLASSES = Set.of(
		"io/evitadb/core/collection/IndexBrowseProjection",
		"io/evitadb/core/collection/IndexDetailProjection",
		"io/evitadb/core/collection/IndexCardinalityProjection"
	);
	/**
	 * Members of `EntityCollection` serving the physical index inspection or the membership rebuild, which must not
	 * resolve partitions for a read (rule R6).
	 */
	private static final Set<String> COLLECTION_WRITE_OR_INSPECTION_MEMBERS = Set.of(
		"io.evitadb.core.collection.EntityCollection#browseIndexes",
		"io.evitadb.core.collection.EntityCollection#describeIndex",
		"io.evitadb.core.collection.EntityCollection#getStatistics",
		"io.evitadb.core.collection.EntityCollection#registerPartition",
		"io.evitadb.core.collection.EntityCollection#registerReducedIndex",
		"io.evitadb.core.collection.EntityCollection#rebuildReducedIndexMembership"
	);
	/**
	 * The read resolvers by the internal name of their owner, rule R6 - an empty set stands for every member of the
	 * owner.
	 */
	private static final Map<String, Set<String>> READ_RESOLVERS = Map.of(
		RESOLVER, Set.of(
			"resolveVirtualIfExists", "resolveForReadIfExists", "resolveForRead", "resolveOfTargetForRead", "split",
			"toVirtualPartition"
		),
		"io/evitadb/index/PartitionKeyMemo", Set.of(),
		"io/evitadb/index/ReferencePartitionSplit", Set.of(),
		"io/evitadb/index/VirtualReferencePartition", Set.of("<init>"),
		"io/evitadb/core/query/QueryPlanningContext", Set.of(
			"resolvePartitionForReadIfExists", "resolvePartitionsOfTargetForRead", "splitPartitionsForRead"
		)
	);
	/**
	 * The owner read resolvers by the internal name of their owner, rule R7.
	 */
	private static final Map<String, Set<String>> OWNER_READ_RESOLVERS = Map.of(
		RESOLVER, Set.of("resolveForOwnerReadIfExists", "resolveOfTargetForOwnerRead", "resolverByKeyForOwnerRead"),
		"io/evitadb/index/mutation/IndexMutationTarget", Set.of("resolvePartitionForOwnerReadIfExists")
	);
	/**
	 * Matches the member of an allow-list entry, capturing `Class#member` and the space-separated descriptors of the
	 * overloads it pins. No method descriptor contains a space or ends with `)`, so the closing parenthesis is
	 * unambiguous.
	 */
	private static final Pattern ALLOW_LIST_MEMBER = Pattern.compile("^(\\S+)(?: \\(overloads (\\S+(?: \\S+)*)\\))?$");
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

		final Verdict verdict = Verdict.of(scanner, readAllowList());
		if (!verdict.isClean()) {
			fail(verdict.describe());
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
		final Scanner scanner = scanFixture();
		final String fixture = ReferencePartitionGuardFixture.class.getName() + "#";
		final String nested = ReferencePartitionGuardFixture.class.getName() + "$";
		final Map<String, String> expected = CollectionUtils.createLinkedHashMap(24);
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
		expected.put("R1 " + fixture + "substitutedShape", "CHECKCAST");
		expected.put("R5 " + fixture + "resolverKeyShape", "call io/evitadb/index/ReferencePartitions.createKey");
		expected.put(
			"R5 " + fixture + "resolverKeyReferenceShape", "indy handle io/evitadb/index/ReferencePartitions.createKey"
		);
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
		// the shapes that must not be reported - the group family, a group key, a by-key lookup, a resolution through
		// the resolver, the handle - are covered here: any hit of theirs would be one more than expected
		assertEquals(expected.size(), scanner.hits.size(), "Unexpected hits in the fixture: " + scanner.hits);
	}

	/**
	 * Checks allow-list entries against the overloads of the counterfactual fixture, and shows that an entry is not
	 * satisfied by another overload hitting in place of the one it vouches for - neither a pinned entry whose number
	 * of overloads still matches, nor an unpinned one whose member declares several overloads.
	 */
	@Test
	@DisplayName("rejects an allow-list entry whose overloads are substituted by others of the same member")
	void shouldRejectAllowListEntryWhenAnotherOverloadHitsInPlaceOfVouchedOne() {
		final Scanner scanner = scanFixture();
		final String fixture = ReferencePartitionGuardFixture.class.getName() + "#";
		final String cast = "(Lio/evitadb/index/Index;)Lio/evitadb/index/bitmap/Bitmap;";
		final String lambda = "(Ljava/lang/Object;I)Ljava/util/function/Predicate;";
		final String handle = "(Lio/evitadb/index/ReferencePartition;)Lio/evitadb/index/bitmap/Bitmap;";
		final String overloadShape = "R1 " + fixture + "overloadShape";
		final String substitutedShape = "R1 " + fixture + "substitutedShape";
		assertEquals(Set.of(cast, lambda, handle), scanner.declaredOverloads.get(fixture + "overloadShape"));
		assertEquals(Set.of(cast, handle), scanner.declaredOverloads.get(fixture + "substitutedShape"));

		// the overloads hit pinned exactly: nothing to report
		assertNull(overloadMismatch(scanner, overloadShape, Set.of(cast, lambda)));
		assertNull(overloadMismatch(scanner, substitutedShape, Set.of(cast)));

		// the entry once vouched for the cast and the overload now behind the handle; the lambda overload took the
		// place of the latter - the same number of overloads, which a count could not tell apart
		final Set<String> substitutedPins = Set.of(cast, handle);
		assertEquals(substitutedPins.size(), scanner.overloads.get(overloadShape).size());
		assertMismatch(scanner, overloadShape, substitutedPins, lambda);
		// the same substitution with a single overload: pinned, and unpinned on a member declaring two
		assertMismatch(scanner, substitutedShape, Set.of(handle), cast);
		assertMismatch(scanner, substitutedShape, Set.of(), "pin the one it vouches for");
		// an unpinned entry of a member hit in several overloads
		assertMismatch(scanner, overloadShape, Set.of(), "pin them");
		// a member declaring a single overload needs no pin
		assertNull(overloadMismatch(scanner, "R1 " + fixture + "castShape", Set.of()));
	}

	/**
	 * Scans the counterfactual fixture of rules R6 and R7, which lives in a package of the write path, and asserts that
	 * every read resolution it carries is reported under R6 and every owner read under R7, each attributed to its member
	 * - and the object-only resolution next to them is not.
	 */
	@Test
	@DisplayName("reports every read resolution and owner read of the write-path fixture and nothing else")
	void shouldReportEveryReadResolutionOnTheWritePath() {
		final Scanner scanner = new Scanner();
		final String fixtureName = ReadResolverGuardFixture.class.getSimpleName();
		final Path fixturePackage = codeSource(ReadResolverGuardFixture.class)
			.resolve(ReadResolverGuardFixture.class.getPackageName().replace('.', '/'));
		try (Stream<Path> files = Files.list(fixturePackage)) {
			for (Path file : (Iterable<Path>) files::iterator) {
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
		final String fixture = ReadResolverGuardFixture.class.getName() + "#";
		final Map<String, String> expected = CollectionUtils.createLinkedHashMap(8);
		expected.put(
			"R6 " + fixture + "resolverReadShape", "call io/evitadb/index/ReferencePartitions.resolveVirtualIfExists"
		);
		expected.put("R6 " + fixture + "splitReferenceShape", "indy handle io/evitadb/index/ReferencePartitions.split");
		expected.put("R6 " + fixture + "memoShape", "indy handle io/evitadb/index/PartitionKeyMemo.<init>");
		expected.put(
			"R6 " + fixture + "queryContextShape",
			"call io/evitadb/core/query/QueryPlanningContext.resolvePartitionForReadIfExists"
		);
		expected.put(
			"R7 " + fixture + "ownerReadShape",
			"call io/evitadb/index/mutation/IndexMutationTarget.resolvePartitionForOwnerReadIfExists"
		);
		expected.put(
			"R7 " + fixture + "ownerReadByKeyShape",
			"call io/evitadb/index/ReferencePartitions.resolverByKeyForOwnerRead"
		);
		for (Map.Entry<String, String> shape : expected.entrySet()) {
			final Set<String> evidence = scanner.hits.get(shape.getKey());
			assertNotNull(evidence, "The guard does not report `" + shape.getKey() + "` - found only " + scanner.hits);
			assertTrue(
				evidence.stream().anyMatch(it -> it.contains(shape.getValue())),
				"`" + shape.getKey() + "` was reported, but not through `" + shape.getValue() + "`: " + evidence
			);
		}
		// the object-only resolution next to them is the sanctioned route of the write path
		assertEquals(expected.size(), scanner.hits.size(), "Unexpected hits in the fixture: " + scanner.hits);
	}

	/**
	 * Scans the counterfactual fixture, its nested classes and the synthetic classes javac generated for it.
	 *
	 * @return the scanner holding the hits of the fixture
	 */
	@Nonnull
	private static Scanner scanFixture() {
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
		return scanner;
	}

	/**
	 * Compares the scan with an allow-list holding a single entry and returns the overload mismatch it reports.
	 *
	 * @param scanner the scanner holding the hits
	 * @param key     the `rule Class#member` of the entry
	 * @param pinned  the overloads the entry pins, empty for an unpinned entry
	 * @return the explanation of the mismatch, or `null` when the entry vouches for exactly the overloads hit
	 */
	@Nullable
	private static String overloadMismatch(
		@Nonnull Scanner scanner,
		@Nonnull String key,
		@Nonnull Set<String> pinned
	) {
		return Verdict.of(scanner, Map.of(key, new AllowListEntry("fixture", pinned))).overloaded().get(key);
	}

	/**
	 * Asserts that an allow-list holding a single entry is reported as not vouching for the overloads hit.
	 *
	 * @param scanner     the scanner holding the hits
	 * @param key         the `rule Class#member` of the entry
	 * @param pinned      the overloads the entry pins, empty for an unpinned entry
	 * @param explanation a fragment the explanation of the mismatch must contain
	 */
	private static void assertMismatch(
		@Nonnull Scanner scanner,
		@Nonnull String key,
		@Nonnull Set<String> pinned,
		@Nonnull String explanation
	) {
		final String mismatch = overloadMismatch(scanner, key, pinned);
		assertNotNull(mismatch, "The entry `" + key + "` pinning " + pinned + " was accepted");
		assertTrue(mismatch.contains(explanation), "`" + key + "` was rejected for another reason: " + mismatch);
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
	 * Reads the allow-list resource into `rule Class#member` keys mapped to their entries. A member may pin the
	 * descriptors of the overloads the entry vouches for as `Class#member (overloads desc1 desc2)`; it vouches for the
	 * only overload of the member otherwise.
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
					ALLOW_LIST + ":" + lineNumber + " must name `Class#member` or `Class#member (overloads desc...)`"
				);
				final List<String> pinnedList = member.group(2) == null ?
					List.of() : List.of(member.group(2).split(" "));
				final Set<String> pinned = new TreeSet<>(pinnedList);
				assertEquals(
					pinnedList.size(), pinned.size(), ALLOW_LIST + ":" + lineNumber + " pins one overload twice"
				);
				final AllowListEntry previous = result.put(
					parts[0] + " " + member.group(1), new AllowListEntry(parts[2], pinned)
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
	 * @param overloads the descriptors of the overloads the entry pins, empty when it vouches for the only overload
	 *                  of the member
	 */
	private record AllowListEntry(@Nonnull String reason, @Nonnull Set<String> overloads) {

		/**
		 * Tells whether the entry pins the overloads it vouches for.
		 *
		 * @return true for a pinned entry
		 */
		boolean pinned() {
			return !this.overloads.isEmpty();
		}
	}

	/**
	 * The comparison of the hits of a scan with an allow-list, in both directions.
	 *
	 * @param unexpected hits by `rule Class#member` the allow-list has no entry for, with their evidence
	 * @param overloaded entries by `rule Class#member` whose overloads hit are not the ones they vouch for, with the
	 *                   explanation
	 * @param stale      entries no longer hit
	 */
	private record Verdict(
		@Nonnull Map<String, Set<String>> unexpected,
		@Nonnull Map<String, String> overloaded,
		@Nonnull Set<String> stale
	) {

		/**
		 * Compares the hits of a scan with an allow-list.
		 *
		 * @param scanner   the scanner holding the hits
		 * @param allowList the allow-list entries by `rule Class#member`
		 * @return the verdict
		 */
		@Nonnull
		static Verdict of(@Nonnull Scanner scanner, @Nonnull Map<String, AllowListEntry> allowList) {
			final Map<String, Set<String>> unexpected = new TreeMap<>();
			for (Map.Entry<String, Set<String>> hit : scanner.hits.entrySet()) {
				if (!allowList.containsKey(hit.getKey())) {
					unexpected.put(hit.getKey(), hit.getValue());
				}
			}
			final Set<String> stale = new TreeSet<>();
			final Map<String, String> overloaded = new TreeMap<>();
			for (Map.Entry<String, AllowListEntry> entry : allowList.entrySet()) {
				final String key = entry.getKey();
				if (!scanner.hits.containsKey(key)) {
					stale.add(key);
					continue;
				}
				// an entry names a member, not a method: an entry that does not say which overload it vouches for
				// would be satisfied by another one hitting in its place
				final Set<String> found = scanner.overloads.getOrDefault(key, Set.of());
				final Set<String> pinned = entry.getValue().overloads();
				if (entry.getValue().pinned()) {
					if (!found.equals(pinned)) {
						overloaded.put(key, "pins " + pinned + " but is hit in " + found);
					}
				} else if (found.size() > 1) {
					overloaded.put(key, "is hit in " + found.size() + " overloads, pin them: " + found);
				} else if (!found.isEmpty()) {
					final Set<String> declared = scanner.declaredOverloads.getOrDefault(
						key.substring(key.indexOf(' ') + 1), Set.of()
					);
					if (declared.size() > 1) {
						overloaded.put(
							key,
							"names a member declaring " + declared.size() + " overloads " + declared +
								", pin the one it vouches for: " + found
						);
					}
				}
			}
			return new Verdict(unexpected, overloaded, stale);
		}

		/**
		 * Tells whether the scan matches the allow-list in both directions.
		 *
		 * @return true when there is nothing to report
		 */
		boolean isClean() {
			return this.unexpected.isEmpty() && this.overloaded.isEmpty() && this.stale.isEmpty();
		}

		/**
		 * Describes everything the scan and the allow-list disagree on.
		 *
		 * @return the failure message
		 */
		@Nonnull
		String describe() {
			final StringBuilder message = new StringBuilder(
				256 + 128 * (this.unexpected.size() + this.overloaded.size() + this.stale.size())
			);
			if (!this.unexpected.isEmpty()) {
				message.append(this.unexpected.size())
					.append(" direct reduced index access(es) outside the allow-list - resolve the partition through ")
					.append("ReferencePartition / ReferencePartitions instead, or add a justified entry to ")
					.append(ALLOW_LIST).append(":\n");
				for (Map.Entry<String, Set<String>> hit : this.unexpected.entrySet()) {
					final String[] ruleAndSite = hit.getKey().split(" ", 2);
					message.append(ruleAndSite[0]).append(" | ").append(ruleAndSite[1])
						.append(" | ? ").append(hit.getValue()).append('\n');
				}
			}
			if (!this.overloaded.isEmpty()) {
				message.append(this.overloaded.size())
					.append(" allow-list entr(y/ies) not vouching for exactly the overloads hit - an entry is keyed ")
					.append("by the member name, so it pins the overloads it vouches for as ")
					.append("`Class#member (overloads desc1 desc2)` unless the member declares only one, and a new ")
					.append("overload must reach the partition through the handle:\n");
				for (Map.Entry<String, String> overload : this.overloaded.entrySet()) {
					final String[] ruleAndSite = overload.getKey().split(" ", 2);
					message.append(ruleAndSite[0]).append(" | ").append(ruleAndSite[1])
						.append(" | ").append(overload.getValue()).append('\n');
				}
			}
			if (!this.stale.isEmpty()) {
				message.append(this.stale.size())
					.append(" allow-list entr(y/ies) no longer hit - delete them from ")
					.append(ALLOW_LIST).append(":\n");
				for (String entry : this.stale) {
					message.append(entry).append('\n');
				}
			}
			return message.toString();
		}
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
		 * The descriptors of every overload each method name declares, hit or not, by `Class#member` - bridges and
		 * other forwarding synthetic methods and lambda bodies left out. An allow-list entry of a member declaring
		 * more than one must pin the overloads it vouches for.
		 */
		private final Map<String, Set<String>> declaredOverloads = new TreeMap<>();
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
		 * Tells whether a method is a read resolver - one that may answer a virtual partition from a registry (rule
		 * R6).
		 *
		 * @param owner the internal name of the owner of the method
		 * @param name  the method name
		 * @return true for a read resolver
		 */
		private static boolean isReadResolver(@Nonnull String owner, @Nonnull String name) {
			final Set<String> names = READ_RESOLVERS.get(owner);
			return names != null && (names.isEmpty() || names.contains(name));
		}

		/**
		 * Tells whether a method is an owner read resolver (rule R7).
		 *
		 * @param owner the internal name of the owner of the method
		 * @param name  the method name
		 * @return true for an owner read resolver
		 */
		private static boolean isOwnerReadResolver(@Nonnull String owner, @Nonnull String name) {
			final Set<String> names = OWNER_READ_RESOLVERS.get(owner);
			return names != null && names.contains(name);
		}

		/**
		 * Tells whether a member belongs to the write path or to the physical index inspection, neither of which may
		 * resolve partitions for a read (rule R6).
		 *
		 * @param internalName the internal name of the class declaring the member
		 * @param site         `Class#member` of the member
		 * @return true when the member must not call a read resolver
		 */
		private static boolean isWriteOrInspectionSite(@Nonnull String internalName, @Nonnull String site) {
			for (final String writePathPackage : WRITE_PATH_PACKAGES) {
				if (internalName.startsWith(writePathPackage)) {
					return true;
				}
			}
			final int nested = internalName.indexOf('$');
			final String outerName = nested < 0 ? internalName : internalName.substring(0, nested);
			return PHYSICAL_INSPECTION_CLASSES.contains(outerName) ||
				COLLECTION_WRITE_OR_INSPECTION_MEMBERS.contains(site);
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
				if (countedDescriptor != null && !LAMBDA.matcher(name).matches()) {
					Scanner.this.declaredOverloads.computeIfAbsent(site, key -> new TreeSet<>()).add(countedDescriptor);
				}
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
			 * Applies rules R1, R3, R4, R5, R6 and R7 to a called or referenced method.
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
				// the resolver builds the key inside the methods narrowing their result to the handle
				if (
					RESOLVER.equals(owner) && "createKey".equals(name) &&
						!RESOLVER.equals(this.classVisitor.internalName)
				) {
					bodyHit("R5", channel + " " + owner + "." + name);
				}
				// the write path and the physical inspection resolve partitions through the object-only resolvers
				if (isReadResolver(owner, name) && isWriteOrInspectionSite(this.classVisitor.internalName, this.site)) {
					bodyHit("R6", channel + " " + owner + "." + name);
				}
				// the owner reads are confined to the places the allow-list names
				if (isOwnerReadResolver(owner, name) && !RESOLVER.equals(this.classVisitor.internalName)) {
					bodyHit("R7", channel + " " + owner + "." + name);
				}
			}
		}
	}

}
