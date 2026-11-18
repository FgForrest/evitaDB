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

package io.evitadb.test.identity;

import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaConstructor;
import com.thoughtworks.qdox.model.JavaMethod;
import com.thoughtworks.qdox.model.JavaSource;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.identity.*;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflectively checks declared identities, real field transplants and fixture coverage.
 * Source discovery and fixture loading are shared by the functional and performance test modules.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class IdentityContractVerifier {
	/** Entity filter alternatives, the only holder family keyed from `identity()` and `getClassId()` directly. */
	private static final Class<?> ENTITY_FILTER_TYPE = loadType(
		"io.evitadb.core.query.algebra.prefetch.EntityToBitmapFilter"
	);
	/** Holder interfaces included in the source inventory. */
	private static final List<Class<?>> HOLDER_TYPES = List.of(
		Formula.class, loadType("io.evitadb.core.query.algebra.deferred.BitmapSupplier"),
		HierarchyFilteringPredicate.class,
		loadType("io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer"),
		ENTITY_FILTER_TYPE
	);
	/** Closed threshold value interface, kept inside its production module's package boundary. */
	private static final Class<?> THRESHOLD_TYPE = loadType(
		"io.evitadb.core.query.algebra.attribute.RequestedThresholdPredicate"
	);
	/** Component value types admitted besides enums, nested identity records and lists. */
	private static final Set<Class<?>> PERMITTED_COMPONENT_TYPES = Set.of(
		boolean.class, int.class, long.class, String.class, Locale.class, Currency.class, OffsetDateTime.class,
		AttributeKey.class, PriceIndexKey.class, PriceEvaluationContext.class, THRESHOLD_TYPE, IdentityToken.class,
		TokenList.class, ValueList.class, FormulaKey.class
	);
	/** Prevents instantiation of the verifier. */
	private IdentityContractVerifier() { }

	/**
	 * Verifies all declared construction modes of one concrete holder.
	 *
	 * @param <T> the concrete holder type constructed by the fixture
	 * @param holderType the concrete class whose identity declarations and instance fields are checked
	 * @param fixture the construction modes and mutation probes to exercise
	 */
	public static <T> void verify(@Nonnull Class<T> holderType, @Nonnull IdentityFixture<T> fixture) {
		final Map<String, ConstructionMode<T>> modes = fixture.modes();
		assertFalse(modes.isEmpty(), "Fixture has no construction modes: " + holderType.getName());
		for (final Map.Entry<String, ConstructionMode<T>> entry : modes.entrySet()) {
			try {
				verifyAttestations(holderType, entry.getKey(), modes);
				verifyMode(holderType, entry.getValue());
			} catch (AssertionError ex) {
				throw new AssertionError(holderType.getName() + " [" + entry.getKey() + "]: " + ex.getMessage(), ex);
			}
		}
	}

	/**
	 * Requires attested fields to be decomposed and exercised without attestation in another mode that has a base.
	 * An inherited field is exempt only while no fixture instance sets it, because it is then no input of this holder;
	 * the superclass fixture cannot witness it, since every holder declares its own `identity()`.
	 *
	 * @param <T> the holder type supplied by each construction mode
	 * @param holderType the concrete class whose inherited and declared fields are inspected
	 * @param modeName the mode whose attested fields require coverage in another mode
	 * @param modes all named modes, including the mode identified by `modeName`
	 */
	private static <T> void verifyAttestations(
		@Nonnull Class<T> holderType, @Nonnull String modeName, @Nonnull Map<String, ConstructionMode<T>> modes
	) {
		for (final String name : modes.get(modeName).attested()) {
			boolean decomposed = false;
			Field inheritedField = null;
			for (Class<?> current = holderType; current != Object.class; current = current.getSuperclass()) {
				for (final Field field : current.getDeclaredFields()) {
					if (field.getName().equals(name) &&
						!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
						final IdentityExempt exemption = field.getAnnotation(IdentityExempt.class);
						decomposed |= exemption != null && exemption.value() == IdentityExempt.Reason.DECOMPOSED;
						if (current != holderType) {
							inheritedField = field;
						}
					}
				}
			}
			assertTrue(decomposed, "check 4: attested field is not DECOMPOSED: " + name);
			boolean checkedElsewhere = inheritedField != null && unsetInEveryInstance(inheritedField, modes);
			for (final Map.Entry<String, ConstructionMode<T>> other : modes.entrySet()) {
				// a mode without a base runs no transplant, so it cannot witness the field
				checkedElsewhere |= !other.getKey().equals(modeName) && other.getValue().base() != null &&
					!other.getValue().attested().contains(name);
			}
			assertTrue(checkedElsewhere, "check 4: attested field lacks a non-attesting mode: " + name);
		}
	}

	/**
	 * Builds every instance the fixture declares and requires the field to hold its type's default value in each.
	 * Equal values alone would not do: a fixture without variants has them for any field.
	 *
	 * @param <T> the holder type constructed by the mode suppliers
	 * @param field the inherited field to make accessible and compare with its default value
	 * @param modes the modes whose base, value, opaque and oversized suppliers are invoked
	 * @return `true` if every supplied instance leaves the field at its primitive default or `null`
	 */
	private static <T> boolean unsetInEveryInstance(
		@Nonnull Field field, @Nonnull Map<String, ConstructionMode<T>> modes
	) {
		field.setAccessible(true);
		final Object unset = field.getType().isPrimitive() ?
			Array.get(Array.newInstance(field.getType(), 1), 0) : null;
		for (final ConstructionMode<T> mode : modes.values()) {
			if (mode.base() != null && !Objects.equals(unset, read(field, mode.base().get()))) {
				return false;
			}
			for (final Map<String, Supplier<T>> suppliers :
				List.of(mode.variants(), mode.opaqueVariants(), mode.oversizedVariants())) {
				for (final Supplier<T> supplier : suppliers.values()) {
					if (!Objects.equals(unset, read(field, supplier.get()))) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/**
	 * Requires a discovered holder to declare an identity and verifies its conventional fixture.
	 * Named exemptions are left to the calling inventory test.
	 *
	 * @param holderType discovered concrete holder
	 * @throws AssertionError when the holder declares no identity, has no conventional fixture, or fails a check of its
	 *                        fixture
	 */
	public static void verifyDiscovered(@Nonnull Class<?> holderType) {
		assertTrue(declares(holderType, "identity"), "Undeclared identity: " + holderType.getName());
		final String fixtureName = holderType.getPackageName() + "." +
			holderType.getName().substring(holderType.getPackageName().length() + 1).replace("$", "") +
			"IdentityFixture";
		Class<?> fixtureType = null;
		try {
			fixtureType = Class.forName(fixtureName, false, holderType.getClassLoader());
		} catch (ClassNotFoundException ignored) {
			// A missing fixture fails below; all other loading failures propagate.
		}
		assertNotNull(fixtureType, "Declared identity has no fixture: " + fixtureName);
		final Object fixture = construct(fixtureType);
		assertInstanceOf(IdentityFixture.class, fixture, fixtureName);
		verifyUnchecked(holderType, (IdentityFixture<?>) fixture);
	}

	/**
	 * Verifies every discovered holder except the named exemptions by {@link #verifyDiscovered(Class)}.
	 *
	 * @param inventory discovered concrete holders
	 * @param exemptions binary names of holders the calling test verifies by a rule of its own
	 * @return number of verified holders, which is the inventory without the exemptions
	 */
	public static int verifyInventory(@Nonnull Set<Class<?>> inventory, @Nonnull Set<String> exemptions) {
		int verified = 0;
		for (final Class<?> type : inventory) {
			if (!exemptions.contains(type.getName())) {
				verifyDiscovered(type);
				verified++;
			}
		}
		return verified;
	}

	/**
	 * Verifies that the mutable wrapper forwards its current delegate's key and follows a delegate swap. The wrapper is
	 * built through its constructor taking a `FacetGroupFormula` and swapped through its `setDelegate` method taking
	 * the same type, so both delegates must be facet group formulas.
	 *
	 * @param type the discovered mutable wrapper class
	 * @param first the initial delegate, a facet group formula
	 * @param second a facet group formula with a different key
	 * @throws ReflectiveOperationException when the wrapper lacks that constructor or method, or either of them throws
	 */
	public static void verifyMutableFormula(
		@Nonnull Class<?> type, @Nonnull Formula first, @Nonnull Formula second
	) throws ReflectiveOperationException {
		final Class<?> delegateType = loadType("io.evitadb.core.query.algebra.facet.FacetGroupFormula");
		final Constructor<?> constructor = type.getDeclaredConstructor(delegateType);
		constructor.setAccessible(true);
		final Formula mutable = (Formula) constructor.newInstance(first);
		assertSame(first.getKey(), mutable.getKey(), "Mutable wrapper must forward the delegate key");
		final Method swap = type.getDeclaredMethod("setDelegate", delegateType);
		swap.setAccessible(true);
		swap.invoke(mutable, second);
		assertSame(second.getKey(), mutable.getKey(), "Mutable wrapper must follow a delegate swap");
		assertNotEquals(first.getKey(), mutable.getKey(), "Delegate swap must move the key");
	}

	/**
	 * Bridges fixture loading to the type-safe verification entry point.
	 *
	 * @param holderType the concrete holder class matched to the loaded fixture
	 * @param fixture the conventionally loaded fixture whose type is erased at the loading boundary
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void verifyUnchecked(@Nonnull Class<?> holderType, @Nonnull IdentityFixture<?> fixture) {
		verify((Class) holderType, fixture);
	}

	/**
	 * Parses sources and retains concrete holders from the primary root, including named nested classes.
	 * Related roots supply inherited holder type names for anonymous and local subclass rejection.
	 *
	 * @param sourceRoot the existing source directory whose concrete holders are included in the inventory
	 * @param relatedRoots additional source directories used to recognize inherited holder types; may be empty
	 * @return an unmodifiable set of loaded concrete holder classes from the primary root, empty if none are found
	 */
	@Nonnull
	public static Set<Class<?>> discover(@Nonnull Path sourceRoot, @Nonnull Path... relatedRoots) {
		assertTrue(Files.isDirectory(sourceRoot), "Missing source root: " + sourceRoot);
		final Path primaryRoot = sourceRoot.toAbsolutePath().normalize();
		final JavaProjectBuilder builder = new JavaProjectBuilder();
		final Set<Path> roots = CollectionUtils.createLinkedHashSet(relatedRoots.length + 1);
		roots.add(primaryRoot);
		for (final Path relatedRoot : relatedRoots) {
			roots.add(relatedRoot.toAbsolutePath().normalize());
		}
		final Map<JavaSource, Path> sourcePaths = parseSources(roots, builder);
		final Set<Class<?>> classes = CollectionUtils.createLinkedHashSet(128);
		final Set<String> typeNames = CollectionUtils.createHashSet(128);
		for (final Class<?> holderType : HOLDER_TYPES) {
			typeNames.add(holderType.getSimpleName());
		}
		for (final JavaSource source : builder.getSources()) {
			for (final JavaClass javaClass : source.getClasses()) {
				collect(javaClass, classes, typeNames, sourcePaths.get(source).startsWith(primaryRoot));
			}
		}
		final String alternatives = String.join("|", typeNames);
		final Pattern anonymous = Pattern.compile(
			"\\bnew\\s+(?:[\\w$]+\\.)*(?:" + alternatives + ")\\s*(?:<[^(){};]*>)?\\s*\\("
		);
		final Pattern local = Pattern.compile(
			"\\bclass\\s+\\w+[^;{}]*\\b(?:extends|implements)\\b[^;{}]*\\b(?:" +
				alternatives + ")\\b"
		);
		for (final JavaSource source : builder.getSources()) {
			try {
				final Path path = sourcePaths.get(source);
				final String contents = parserInput(Files.readString(path));
				rejectAnonymous(contents, anonymous, path);
				for (final JavaClass javaClass : source.getClasses()) {
					rejectLocal(javaClass, local);
				}
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}
		return Collections.unmodifiableSet(classes);
	}

	/**
	 * Parses every Java source below the roots into the builder, remembering the path each source came from.
	 *
	 * @param roots the existing source directories to walk recursively for Java files
	 * @param builder the parser populated with sanitized source contents
	 * @return a fresh identity-based map from parsed sources to their paths, empty when no Java sources are found
	 */
	@Nonnull
	private static Map<JavaSource, Path> parseSources(@Nonnull Set<Path> roots, @Nonnull JavaProjectBuilder builder) {
		final Map<JavaSource, Path> sourcePaths = new IdentityHashMap<>();
		for (final Path root : roots) {
			assertTrue(Files.isDirectory(root), "Missing related source root: " + root);
			try (final Stream<Path> paths = Files.walk(root)) {
				for (final Path path : paths.filter(it -> it.toString().endsWith(".java")).sorted().toList()) {
					final String source = Files.readString(path);
					final String parserInput = parserInput(source);
					try {
						sourcePaths.put(builder.addSource(new StringReader(parserInput)), path);
					} catch (RuntimeException ex) {
						throw new AssertionError("Cannot parse identity inventory source: " + path, ex);
					}
				}
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}
		return sourcePaths;
	}

	/**
	 * Distinguishes an anonymous body from surrounding control-flow blocks using balanced arguments.
	 *
	 * @param source source text with comments and literal contents erased
	 * @param constructors the pattern matching constructor calls of recognized holder types
	 * @param path the source path included in an assertion failure for an anonymous holder
	 */
	private static void rejectAnonymous(@Nonnull String source, @Nonnull Pattern constructors, @Nonnull Path path) {
		final Matcher matcher = constructors.matcher(source);
		while (matcher.find()) {
			int depth = 1;
			int offset = matcher.end();
			while (offset < source.length() && depth > 0) {
				final char character = source.charAt(offset++);
				if (character == '(') {
					depth++;
				} else if (character == ')') {
					depth--;
				}
			}
			while (offset < source.length() && Character.isWhitespace(source.charAt(offset))) {
				offset++;
			}
			assertFalse(offset < source.length() && source.charAt(offset) == '{',
				"Anonymous identity holder in " + path + ": " + matcher.group());
		}
	}

	/**
	 * Removes comments and literal contents before source inventory parsing, preserving code and line positions.
	 * QDox does not need literal values and can misinterpret quotes inside comments following text blocks.
	 *
	 * @param source the Java source text to sanitize without modifying the original string
	 * @return sanitized text retaining line positions, with literal placeholders and `sealed` method names renamed
	 */
	@Nonnull
	private static String parserInput(@Nonnull String source) {
		final char[] code = source.toCharArray();
		int offset = 0;
		while (offset < source.length()) {
			final char character = source.charAt(offset);
			final int start = offset;
			if (source.startsWith("//", offset)) {
				offset += 2;
				while (offset < source.length() && source.charAt(offset) != '\r' && source.charAt(offset) != '\n') {
					offset++;
				}
				eraseToken(code, start, offset);
			} else if (source.startsWith("/*", offset)) {
				final int end = source.indexOf("*/", offset + 2);
				assertTrue(end >= 0, "Unterminated source comment");
				offset = end + 2;
				eraseToken(code, start, offset);
			} else if (character == '"' || character == '\'') {
				final boolean textBlock = source.startsWith("\"\"\"", offset);
				final int delimiter = textBlock ? 3 : 1;
				offset += delimiter;
				boolean closed = false;
				while (offset < source.length()) {
					if (source.charAt(offset) == '\\') {
						offset += 2;
					} else if (textBlock ? source.startsWith("\"\"\"", offset) : source.charAt(offset) == character) {
						offset += delimiter;
						closed = true;
						break;
					} else {
						offset++;
					}
				}
				assertTrue(closed, "Unterminated source literal");
				eraseToken(code, start, offset);
				if (character == '"') {
					code[start] = '"';
					code[start + 1] = '"';
				} else {
					code[start] = '\'';
					code[start + 1] = 'x';
					code[start + 2] = '\'';
				}
			} else {
				offset++;
			}
		}
		// This contextual keyword is a legal method name, but QDox treats it as reserved.
		return new String(code).replaceAll("\\bsealed(?=\\s*\\()", "sealedMethod");
	}

	/**
	 * Blanks one lexical token without moving subsequent source lines.
	 *
	 * @param code the mutable source character array to blank in place, preserving line separators
	 * @param start the inclusive character offset at which the token begins
	 * @param end the exclusive character offset at which the token ends
	 */
	private static void eraseToken(@Nonnull char[] code, int start, int end) {
		for (int i = start; i < end; i++) {
			if (code[i] != '\n' && code[i] != '\r') {
				code[i] = ' ';
			}
		}
	}

	/**
	 * Loads every named class without running its static initialization.
	 *
	 * @param source the parsed named class whose loaded type and nested classes are inspected
	 * @param classes the destination set receiving concrete holders when `included` is `true`
	 * @param typeNames the destination set receiving simple holder type names for source rejection patterns
	 * @param included whether classes in this source belong to the primary inventory root
	 */
	private static void collect(
		@Nonnull JavaClass source, @Nonnull Set<Class<?>> classes, @Nonnull Set<String> typeNames, boolean included
	) {
		final Class<?> loaded;
		try {
			loaded = Class.forName(source.getBinaryName(), false, IdentityContractVerifier.class.getClassLoader());
		} catch (ClassNotFoundException | LinkageError ex) {
			throw new AssertionError("Cannot load discovered source class " + source.getBinaryName(), ex);
		}
		for (final Class<?> holderType : HOLDER_TYPES) {
			if (holderType.isAssignableFrom(loaded)) {
				typeNames.add(loaded.getSimpleName());
				if (included && !loaded.isInterface() && !Modifier.isAbstract(loaded.getModifiers())) {
					classes.add(loaded);
				}
			}
		}
		for (final JavaClass nested : source.getNestedClasses()) {
			collect(nested, classes, typeNames, included);
		}
	}

	/**
	 * Rejects local holder declarations hidden in methods or constructors.
	 *
	 * @param source the parsed class whose methods, constructors and nested classes are inspected
	 * @param local the pattern recognizing local declarations extending or implementing a holder type
	 */
	private static void rejectLocal(@Nonnull JavaClass source, @Nonnull Pattern local) {
		for (final JavaMethod method : source.getMethods()) {
			if (method.getSourceCode() != null) {
				assertFalse(local.matcher(method.getSourceCode()).find(),
					"Local identity holder in " + source.getBinaryName() + "#" + method.getName());
			}
		}
		for (final JavaConstructor constructor : source.getConstructors()) {
			if (constructor.getSourceCode() != null) {
				assertFalse(local.matcher(constructor.getSourceCode()).find(),
					"Local identity holder in " + source.getBinaryName() + "#" + constructor.getName());
			}
		}
		for (final JavaClass nested : source.getNestedClasses()) {
			rejectLocal(nested, local);
		}
	}

	/**
	 * Runs the twelve contract checks for one construction mode.
	 *
	 * @param <T> the concrete holder type constructed by this mode
	 * @param holderType the concrete class whose field and identity declarations are verified
	 * @param mode the suppliers, clone paths and coverage declarations exercised by the checks
	 */
	private static <T> void verifyMode(@Nonnull Class<T> holderType, @Nonnull ConstructionMode<T> mode) {
		final List<T> samples = new ArrayList<>();
		final T base = mode.base() == null ? null : mode.base().get();
		if (base != null) {
			assertFalse(key(base).isOpaque(), "check 1: base must have value inputs");
			final T equal = mode.base().get();
			assertNotSame(base, equal, "check 1: base supplier must construct fresh holders");
			assertEqualKeys(key(base), key(equal), "check 1: equal inputs");
			samples.add(base);
			samples.add(equal);
		} else {
			assertTrue(mode.variants().isEmpty(), "check 2: value variants require a base");
			assertTrue(mode.oversizedVariants().isEmpty(), "check 8: oversized variants require a base");
			assertFalse(mode.opaqueVariants().isEmpty(), "Always opaque mode needs an opaque supplier");
		}
		final List<T> variants = collectVariants(base, mode);
		samples.addAll(variants);
		assertFalse(samples.isEmpty(), "Mode has no instances");
		final IdentityRecord record = identity(samples.getFirst());
		assertTrue(record.getClass().isRecord(), "check 9: identity must be a record");
		final RecordComponent[] components = record.getClass().getRecordComponents();
		final Set<String> componentNames = CollectionUtils.createHashSet(components.length);
		for (final RecordComponent component : components) {
			componentNames.add(component.getName());
			checkDomain(component.getGenericType());
		}
		assertTrue(componentNames.containsAll(mode.pinned()), "check 2: unknown pinned component");
		assertTrue(componentNames.containsAll(mode.alwaysExact()), "check 8: unknown alwaysExact component");
		if (components.length > 0) {
			assertTrue(declares(record.getClass(), "describeTo", KeyAccumulator.class),
				"check 11: component-bearing record must declare describeTo");
		}
		for (final T sample : samples) {
			verifyDeclaredPair(holderType, sample, identity(sample));
			verifyComponentValues(identity(sample));
			verifyArraySnapshots(holderType, sample);
		}
		if (base != null) {
			verifyCoverage(base, variants, components, mode);
		}
		verifyLocalityCoverage(components, samples, mode);
		if (base != null) {
			verifyClones(base, mode, false);
			verifyChildren(base, mode.base());
		}
		verifyFields(holderType, base, variants, samples, componentNames, mode);
	}

	/**
	 * Builds the value, opaque and oversized variants of a mode, checking each kind's own obligations.
	 *
	 * @param <T> the holder type constructed by the variant suppliers
	 * @param base the reference holder for value and oversized comparisons; `null` for an always-opaque mode
	 * @param mode the suppliers and clone expectations used to construct and check the variants
	 * @return a fresh mutable list of value, opaque and oversized variants in that order, empty if none are declared
	 */
	@Nonnull
	private static <T> List<T> collectVariants(@Nullable T base, @Nonnull ConstructionMode<T> mode) {
		final List<T> variants = new ArrayList<>();
		for (final Supplier<T> supplier : mode.variants().values()) {
			final T variant = supplier.get();
			assertNotEquals(key(base), key(variant), "check 2: variant must move key");
			assertNotEquals(key(base).getHash64(), key(variant).getHash64(), "check 10: variant hash64 unchanged");
			variants.add(variant);
		}
		for (final Supplier<T> supplier : mode.opaqueVariants().values()) {
			final T opaque = supplier.get();
			final T other = supplier.get();
			assertNotSame(opaque, other, "check 8: opaque supplier must construct fresh holders");
			assertTrue(key(opaque).isOpaque(), "check 8: opaque input must make own record opaque");
			assertTrue(key(opaque).isQueryLocal(), "check 8: opaque key must be query-local");
			assertEqualKeys(key(opaque), key(opaque), "check 8: stable opaque owner");
			assertNotEquals(key(opaque), key(other), "check 8: distinct opaque owners");
			verifyPropagation(key(opaque));
			verifyClones(opaque, mode, true);
			variants.add(opaque);
		}
		for (final Supplier<T> supplier : mode.oversizedVariants().values()) {
			final T oversized = supplier.get();
			assertTrue(key(oversized).isQueryLocal(), "check 8: oversized key must be query-local");
			assertFalse(key(oversized).isOpaque(), "check 8: oversized must stay exact");
			assertEqualKeys(key(oversized), key(supplier.get()), "check 8: exact oversized inputs");
			assertTrue(key(oversized).getWeight() > key(base).getWeight(), "check 8: oversized weight");
			variants.add(oversized);
		}
		return variants;
	}

	/**
	 * Requires each token, list or key component to turn query-local in some sample, unless declared always exact.
	 *
	 * @param <T> the holder type represented by the samples
	 * @param components the identity components inspected for query-local values
	 * @param samples the constructed holders whose component values supply locality coverage
	 * @param mode the declarations identifying components that must remain exact
	 */
	private static <T> void verifyLocalityCoverage(
		@Nonnull RecordComponent[] components, @Nonnull List<T> samples, @Nonnull ConstructionMode<T> mode
	) {
		for (final RecordComponent component : components) {
			if (localityType(component.getGenericType())) {
				boolean local = false;
				for (final T sample : samples) {
					local |= componentLocality(componentValue(identity(sample), component));
				}
				if (mode.alwaysExact().contains(component.getName())) {
					assertFalse(local, "check 8: alwaysExact component became query-local: " + component.getName());
				} else {
					assertTrue(local, "check 8: no opaque/oversized variant for " + component.getName());
				}
			}
		}
	}

	/**
	 * Checks component variants and pinned components.
	 *
	 * @param <T> the holder type shared by the base and variants
	 * @param base the holder providing the unchanged reference identity
	 * @param variants the constructed holders expected to vary every unpinned component
	 * @param components the identity components compared independently
	 * @param mode the declarations identifying components allowed to remain pinned
	 */
	private static <T> void verifyCoverage(
		@Nonnull T base, @Nonnull List<T> variants, @Nonnull RecordComponent[] components,
		@Nonnull ConstructionMode<T> mode
	) {
		for (final RecordComponent component : components) {
			final Object original = componentValue(identity(base), component);
			boolean changed = false;
			for (final T variant : variants) {
				final Object value = componentValue(identity(variant), component);
				if (!sameValue(original, value)) {
					changed = true;
					verifyIsolatedHash(identity(base), component, value);
				}
				if (mode.pinned().contains(component.getName())) {
					assertTrue(sameValue(original, value), "check 2: pinned component changed: " + component.getName());
				}
			}
			assertTrue(changed || mode.pinned().contains(component.getName()),
				"check 2: uncovered component " + component.getName());
		}
	}

	/**
	 * Reconstructs a record with exactly one changed component and requires its description to change the hash.
	 *
	 * @param base the identity record reconstructed with one substituted component
	 * @param changed the component whose constructor argument is replaced
	 * @param replacement the substituted component value; `null` tests an absent value
	 */
	private static void verifyIsolatedHash(
		@Nonnull IdentityRecord base, @Nonnull RecordComponent changed, @Nullable Object replacement
	) {
		final RecordComponent[] components = base.getClass().getRecordComponents();
		final Class<?>[] types = new Class<?>[components.length];
		final Object[] arguments = new Object[components.length];
		for (int i = 0; i < components.length; i++) {
			types[i] = components[i].getType();
			arguments[i] = components[i].getName().equals(changed.getName()) ? replacement :
				componentValue(base, components[i]);
		}
		final IdentityRecord perturbed;
		try {
			final Constructor<?> constructor = base.getClass().getDeclaredConstructor(types);
			constructor.setAccessible(true);
			perturbed = (IdentityRecord) constructor.newInstance(arguments);
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError("check 10: isolated component construction refused: " + changed.getName(), ex);
		}
		final FormulaKey original = leafKey(base, base);
		final FormulaKey modified = FormulaKey.of(perturbed, base.classId(), FormulaKey.NO_CHILDREN, base);
		assertNotEquals(original, modified, "check 2: isolated component did not move key: " + changed.getName());
		assertNotEquals(original.getHash64(), modified.getHash64(),
			"check 10: isolated component hash64 unchanged: " + changed.getName());
		final Object initial = componentValue(base, changed);
		if (initial instanceof IdentityRecord nested && replacement instanceof IdentityRecord other) {
			for (final RecordComponent component : nested.getClass().getRecordComponents()) {
				final Object value = componentValue(other, component);
				if (!sameValue(componentValue(nested, component), value)) {
					verifyIsolatedHash(nested, component, value);
				}
			}
		}
	}

	/**
	 * Mutates retained constructor arrays and requires the already captured identity to remain stable.
	 *
	 * @param <T> the concrete holder type inspected for retained arrays
	 * @param holderType the class at which the inherited-field scan begins
	 * @param holder the instance whose retained array elements are mutated temporarily and restored
	 */
	private static <T> void verifyArraySnapshots(@Nonnull Class<T> holderType, @Nonnull T holder) {
		for (Class<?> type = holderType; type != Object.class; type = type.getSuperclass()) {
			for (final Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || !field.getType().isArray()) {
					continue;
				}
				final IdentityExempt exemption = field.getAnnotation(IdentityExempt.class);
				if (exemption != null && exemption.value() != IdentityExempt.Reason.DECOMPOSED) {
					continue;
				}
				field.setAccessible(true);
				final Object array = read(field, holder);
				if (array == null) {
					continue;
				}
				for (int i = 0; i < Array.getLength(array); i++) {
					final Object original = Array.get(array, i);
					final Object replacement;
					if (original instanceof Boolean flag) {
						replacement = !flag;
					} else if (original instanceof Character character) {
						replacement = (char) (character ^ 1);
					} else if (field.getType().getComponentType().isPrimitive()) {
						replacement = ((Number) original).doubleValue() == 0 ? 1 : 0;
					} else if (original != null) {
						replacement = null;
					} else {
						continue;
					}
					final IdentityRecord record = identity(holder);
					final FormulaKey captured = leafKey(record, holder);
					try {
						setArrayElement(array, i, replacement);
						assertEqualKeys(captured, leafKey(record, holder),
							"check 9: retained array changed captured identity: " + field);
					} finally {
						Array.set(array, i, original);
					}
				}
			}
		}
	}

	/**
	 * Writes a mutation probe with the exact primitive element type required by reflective arrays.
	 *
	 * @param array the retained primitive or reference array mutated in place
	 * @param index the zero-based position of the mutation probe
	 * @param value the replacement, converted to the primitive element type; `null` is valid for reference arrays
	 */
	private static void setArrayElement(@Nonnull Object array, int index, @Nullable Object value) {
		final Class<?> type = array.getClass().getComponentType();
		if (type == byte.class) {
			Array.setByte(array, index, ((Number) value).byteValue());
		} else if (type == short.class) {
			Array.setShort(array, index, ((Number) value).shortValue());
		} else if (type == long.class) {
			Array.setLong(array, index, ((Number) value).longValue());
		} else if (type == float.class) {
			Array.setFloat(array, index, ((Number) value).floatValue());
		} else if (type == double.class) {
			Array.setDouble(array, index, ((Number) value).doubleValue());
		} else {
			Array.set(array, index, value);
		}
	}

	/**
	 * Checks field declarations, individual field transplants and equal-key derived values.
	 *
	 * @param <T> the concrete holder type shared by the fixture instances
	 * @param holderType the class at which the inherited-field scan begins
	 * @param base the reference holder for field transplants; `null` skips transplant checks
	 * @param variants holders supplying alternative field values for fresh base instances
	 * @param samples all constructed holders used to compare derived values for equal keys
	 * @param componentNames the declared identity component names used to classify unannotated fields
	 * @param mode the base supplier and field exemptions controlling transplant coverage
	 */
	private static <T> void verifyFields(
		@Nonnull Class<T> holderType, @Nullable T base, @Nonnull List<T> variants, @Nonnull List<T> samples,
		@Nonnull Set<String> componentNames, @Nonnull ConstructionMode<T> mode
	) {
		for (Class<?> current = holderType; current != Object.class; current = current.getSuperclass()) {
			for (final Field field : current.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
					continue;
				}
				field.setAccessible(true);
				final IdentityExempt exemption = field.getAnnotation(IdentityExempt.class);
				assertTrue(exemption != null || componentNames.contains(field.getName()),
					"check 3: unannotated field outside identity: " + field);
				if (exemption != null && exemption.value() == IdentityExempt.Reason.DERIVED) {
					verifyDerived(field, samples);
				}
				if (base == null || mode.attested().contains(field.getName()) ||
					(exemption != null && exemption.value() != IdentityExempt.Reason.DECOMPOSED) ||
					(mode.pinned().contains(field.getName()) && exemption == null)) {
					continue;
				}
				boolean transplanted = false;
				for (final T variant : variants) {
					if (!sameValue(read(field, base), read(field, variant))) {
						final T fresh = mode.base().get();
						try {
							field.set(fresh, read(field, variant));
						} catch (IllegalAccessException | IllegalArgumentException ex) {
							throw new AssertionError("check 4: field transplant refused: " + field, ex);
						}
						assertNotEquals(key(base), key(fresh),
							"check 4: transplanted field did not move key: " + field);
						transplanted = true;
					}
				}
				assertTrue(transplanted, "check 4: no variant moves field: " + field);
			}
		}
	}

	/**
	 * Compares supported derived field values whenever two fixture instances have equal keys.
	 *
	 * @param <T> the holder type whose derived field is compared
	 * @param field the accessible derived field; unsupported value types are skipped
	 * @param samples the holders compared pairwise whenever their keys are equal
	 */
	private static <T> void verifyDerived(@Nonnull Field field, @Nonnull List<T> samples) {
		if (!valueField(field.getType())) {
			return;
		}
		for (int i = 0; i < samples.size(); i++) {
			for (int j = i + 1; j < samples.size(); j++) {
				if (key(samples.get(i)).equals(key(samples.get(j)))) {
					assertTrue(sameValue(read(field, samples.get(i)), read(field, samples.get(j))),
						"check 5: equal keys have different DERIVED field: " + field);
				}
			}
		}
	}

	/**
	 * Verifies declared clone outcomes, using instance identity to distinguish opaque self returns.
	 *
	 * @param <T> the holder type accepted by the declared clone paths
	 * @param holder the instance passed to each clone path
	 * @param mode the named clone operations and their expected outcomes
	 * @param opaque whether the holder has an opaque identity, requiring distinct clones to have unequal keys
	 */
	private static <T> void verifyClones(@Nonnull T holder, @Nonnull ConstructionMode<T> mode, boolean opaque) {
		for (final Map.Entry<String, ClonePath<T>> entry : mode.clonePaths().entrySet()) {
			final ClonePath<T> path = entry.getValue();
			if (path.expected() == CloneOutcome.REJECTED) {
				assertThrows(RuntimeException.class, () -> path.cloner().apply(holder), "check 7: " + entry.getKey());
			} else {
				final Object clone = path.cloner().apply(holder);
				assertNotNull(clone, "check 7: null clone: " + entry.getKey());
				if (opaque && clone != holder) {
					assertEquals(CloneOutcome.UNEQUAL, path.expected(),
						"check 8: newly allocated opaque clone must be declared UNEQUAL: " + entry.getKey());
					assertNotEquals(key(holder), key(clone), "check 8: newly allocated opaque clone compares equal");
				} else if (path.expected() == CloneOutcome.EQUAL) {
					assertEqualKeys(key(holder), key(clone), "check 7: " + entry.getKey());
				} else {
					assertNotEquals(key(holder), key(clone), "check 7: " + entry.getKey());
				}
			}
		}
	}

	/**
	 * Tests child replacement and ordering on fresh real holders without assuming cloning is supported.
	 *
	 * @param <T> the holder type constructed by the supplier
	 * @param holder the reference holder whose ordered children are inspected; non-formula holders are skipped
	 * @param fresh the supplier of independent equivalent holders with no previously captured key
	 */
	private static <T> void verifyChildren(@Nonnull T holder, @Nonnull Supplier<T> fresh) {
		if (!(holder instanceof Formula formula)) {
			return;
		}
		final Formula[] original = formula.getInnerFormulas();
		final FormulaKey[] keys = new FormulaKey[original.length];
		for (int i = 0; i < keys.length; i++) {
			keys[i] = original[i].getKey();
		}
		final IdentityRecord record = identity(holder);
		assertEqualKeys(key(holder), FormulaKey.of(record, record.classId(), keys, holder),
			"check 6: holder key must include its declared children");
		for (int i = 0; i < keys.length; i++) {
			final Formula[] replacement = original.clone();
			Formula different = constantFormula(Integer.MIN_VALUE + i);
			if (different.getKey().equals(keys[i])) {
				different = constantFormula(Integer.MAX_VALUE - i);
			}
			replacement[i] = different;
			assertNotEquals(key(holder), key(withChildren((Formula) fresh.get(), replacement)),
				"check 6: replacing a child must move key");
			for (int j = i + 1; j < keys.length; j++) {
				if (!keys[i].equals(keys[j])) {
					final Formula[] swapped = original.clone();
					swapped[i] = original[j];
					swapped[j] = original[i];
					assertNotEquals(key(holder), key(withChildren((Formula) fresh.get(), swapped)),
						"check 6: children must be ordered");
				}
			}
		}
	}

	/**
	 * Installs an owned children array before the fresh holder's first key build.
	 *
	 * @param fresh the formula whose child storage is replaced before its first key is captured
	 * @param children the owned replacement array installed directly into the formula
	 * @return the same formula instance after its exposed children have been checked against the replacement
	 */
	@Nonnull
	private static Formula withChildren(@Nonnull Formula fresh, @Nonnull Formula[] children) {
		for (Class<?> type = fresh.getClass(); type != Object.class; type = type.getSuperclass()) {
			try {
				final Field field = type.getDeclaredField("innerFormulas");
				field.setAccessible(true);
				field.set(fresh, children);
				final Formula[] actual = fresh.getInnerFormulas();
				assertEquals(children.length, actual.length, "check 6: child transplant length");
				for (int i = 0; i < children.length; i++) {
					assertSame(children[i], actual[i], "check 6: child transplant refused");
				}
				return fresh;
			} catch (NoSuchFieldException ignored) {
				// The children storage belongs to a superclass.
			} catch (IllegalAccessException | IllegalArgumentException ex) {
				throw new AssertionError("check 6: children transplant refused", ex);
			}
		}
		throw new AssertionError("check 6: no children storage on " + fresh.getClass().getName());
	}

	/**
	 * Builds a real constant leaf through its production constructor.
	 *
	 * @param value the single record identifier placed in the constant bitmap
	 * @return a fresh constant formula backed by a fresh bitmap containing the supplied identifier
	 */
	@Nonnull
	private static Formula constantFormula(int value) {
		try {
			return (Formula) loadType("io.evitadb.core.query.algebra.base.ConstantFormula")
				.getConstructor(Bitmap.class).newInstance(new BaseBitmap(value));
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError("Cannot construct a constant child", ex);
		}
	}

	/**
	 * Checks the key mechanics of opaque child and side-key propagation independently of holder constructors.
	 *
	 * @param opaque the opaque key tested both as an ordered child and as a side input
	 */
	private static void verifyPropagation(@Nonnull FormulaKey opaque) {
		final FormulaKey parent = FormulaKey.of(new ParentIdentity(), 0, new FormulaKey[]{opaque}, new Object());
		assertTrue(parent.isQueryLocal(), "check 8: opaque child locality");
		assertFalse(parent.isOpaque(), "check 8: opaque child must not make parent opaque");
		assertEqualKeys(parent, FormulaKey.of(new ParentIdentity(), 0, new FormulaKey[]{opaque}, new Object()),
			"check 8: equal parents over same opaque child");
		assertTrue(FormulaKey.of(new SideIdentity(opaque), 0, FormulaKey.NO_CHILDREN, new Object()).isOpaque(),
			"check 8: opaque side key must make holder opaque");
	}

	/**
	 * Ensures every concrete holder declares its own matching identity and class identifier.
	 *
	 * @param type the concrete class required to declare its own identity, class identifier and `CLASS_ID` field
	 * @param holder the instance on which the declared identity and class identifier methods are invoked
	 * @param identity the detached record whose class identifier and value must match the holder declarations
	 */
	private static void verifyDeclaredPair(
		@Nonnull Class<?> type, @Nonnull Object holder, @Nonnull IdentityRecord identity
	) {
		assertTrue(declares(type, "identity"), "check 12: identity not declared: " + type.getName());
		assertTrue(declares(type, "getClassId"), "check 12: getClassId not declared: " + type.getName());
		assertEquals(identity, invoke(findMethod(type, "identity"), holder),
			"check 12: key identity differs from the holder's declared identity");
		try {
			final Field classId = type.getDeclaredField("CLASS_ID");
			assertEquals(long.class, classId.getType(), "check 12: CLASS_ID type");
			assertTrue(Modifier.isStatic(classId.getModifiers()) && Modifier.isFinal(classId.getModifiers()),
				"check 12: CLASS_ID must be static final");
			classId.setAccessible(true);
			assertEquals(identity.classId(), classId.getLong(null), "check 12: record classId differs from CLASS_ID");
			assertEquals(identity.classId(), invoke(findMethod(type, "getClassId"), holder),
				"check 12: holder classId differs from identity");
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError("check 12: missing CLASS_ID: " + type.getName(), ex);
		}
	}

	/**
	 * Validates the closed component type domain recursively, including generic list elements.
	 *
	 * @param type the declared component type, recursively checked for nested records and supported list elements
	 */
	private static void checkDomain(@Nonnull Type type) {
		if (type instanceof ParameterizedType parameterized) {
			assertEquals(List.class, parameterized.getRawType(), "check 9: only List is a permitted generic component");
			final Type element = parameterized.getActualTypeArguments()[0];
			assertTrue(element == String.class || element == FormulaKey.class,
				"check 9: forbidden list element: " + element);
			return;
		}
		assertInstanceOf(Class.class, type, "check 9: unsupported type: " + type);
		final Class<?> component = (Class<?>) type;
		if (component.isEnum()) {
			assertTrue(component.getEnumConstants().length >= 2, "check 9: enum needs two constants");
		} else if (IdentityRecord.class.isAssignableFrom(component) && component.isRecord()) {
			for (final RecordComponent nested : component.getRecordComponents()) {
				checkDomain(nested.getGenericType());
			}
			if (component.getRecordComponents().length > 0) {
				assertTrue(declares(component, "describeTo", KeyAccumulator.class),
					"check 11: nested describeTo absent");
			}
		} else {
			assertTrue(PERMITTED_COMPONENT_TYPES.contains(component), "check 9: forbidden component type: " + type);
		}
	}

	/**
	 * Rejects mutable list components by attempting an actual mutation on each supplied record.
	 *
	 * @param record the identity record whose list components must reject mutation, including nested records
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void verifyComponentValues(@Nonnull IdentityRecord record) {
		for (final RecordComponent component : record.getClass().getRecordComponents()) {
			final Object value = componentValue(record, component);
			if (value instanceof List list) {
				assertThrows(UnsupportedOperationException.class, () -> list.add(null),
					"check 9: list component must be unmodifiable: " + component.getName());
				if (!list.isEmpty()) {
					assertThrows(UnsupportedOperationException.class, () -> list.set(0, list.get(0)),
						"check 9: list component must reject replacement: " + component.getName());
				}
			} else if (value instanceof IdentityRecord nested) {
				verifyComponentValues(nested);
			}
		}
	}

	/**
	 * Returns whether a component needs opaque/oversized fixture coverage.
	 *
	 * @param type the declared component type inspected for tokens, keys or lists of keys
	 * @return `true` if this component type requires query-local fixture coverage
	 */
	private static boolean localityType(@Nonnull Type type) {
		return type == IdentityToken.class || type == TokenList.class || type == ValueList.class ||
			type == FormulaKey.class || (type instanceof ParameterizedType parameterized &&
			parameterized.getRawType() == List.class && parameterized.getActualTypeArguments()[0] == FormulaKey.class);
	}

	/**
	 * Computes locality using the same closed component description as a holder.
	 *
	 * @param value the component value described to an accumulator; `null` contributes no locality
	 * @return `true` if the recognized token, token list, value list or key inputs make the component query-local
	 */
	private static boolean componentLocality(@Nullable Object value) {
		final KeyAccumulator accumulator = new KeyAccumulator(0);
		if (value instanceof IdentityToken token) {
			accumulator.add(token);
		} else if (value instanceof TokenList tokens) {
			accumulator.add(tokens);
		} else if (value instanceof ValueList values) {
			accumulator.add(values);
		} else if (value instanceof FormulaKey sideKey) {
			accumulator.add(sideKey);
		} else if (value instanceof List<?> list) {
			for (final Object element : list) {
				if (element instanceof FormulaKey sideKey) {
					accumulator.add(sideKey);
				}
			}
		}
		return (boolean) invoke(findMethod(KeyAccumulator.class, "isQueryLocal"), accumulator);
	}

	/**
	 * Returns whether a derived field has a supported value comparison.
	 *
	 * @param type the declared field type examined against the supported comparison domain
	 * @return `true` if values of this type can participate in the derived-field equality check
	 */
	private static boolean valueField(@Nonnull Class<?> type) {
		return type.isPrimitive() || type.isEnum() || type == String.class || type == Locale.class ||
			type == Currency.class || type == OffsetDateTime.class || type == AttributeKey.class ||
			type == PriceIndexKey.class || type == PriceEvaluationContext.class ||
			THRESHOLD_TYPE.isAssignableFrom(type) || IdentityRecord.class.isAssignableFrom(type) ||
			IdentityToken.class.isAssignableFrom(type) || type == TokenList.class || type == ValueList.class ||
			type == FormulaKey.class || BaseBitmap.class.isAssignableFrom(type) || List.class.isAssignableFrom(type);
	}

	/**
	 * Compares bitmap values by content and other supported values by equality.
	 *
	 * @param first the reference value; `null` is equal only to another `null` value
	 * @param second the comparison value, which may also be `null`
	 * @return `true` for equal bitmap contents or deeply equal non-bitmap values
	 */
	private static boolean sameValue(@Nullable Object first, @Nullable Object second) {
		if (first instanceof Bitmap firstBitmap && second instanceof Bitmap secondBitmap) {
			return Arrays.equals(firstBitmap.getArray(), secondBitmap.getArray());
		}
		return Objects.deepEquals(first, second);
	}

	/**
	 * Reads the detached identity retained by a key; reflection stays inside this test harness.
	 *
	 * @param holder the holder whose key retains the detached identity to inspect
	 * @return the identity record retained by the holder key, without copying it
	 */
	@Nonnull
	private static IdentityRecord identity(@Nonnull Object holder) {
		try {
			final Field identity = FormulaKey.class.getDeclaredField("identity");
			identity.setAccessible(true);
			return (IdentityRecord) read(identity, key(holder));
		} catch (NoSuchFieldException ex) {
			throw new AssertionError(ex);
		}
	}

	/**
	 * Invokes a holder's key API, including the leaf key of an entity filter alternative.
	 *
	 * @param holder the holder exposing a key, or an entity filter exposing identity and class identifier methods
	 * @return the holder API key, or a childless key constructed for an entity filter alternative
	 */
	@Nonnull
	private static FormulaKey key(@Nonnull Object holder) {
		if (ENTITY_FILTER_TYPE.isInstance(holder)) {
			final IdentityRecord identity = (IdentityRecord) invoke(findMethod(holder.getClass(), "identity"), holder);
			final long classId = (long) invoke(findMethod(holder.getClass(), "getClassId"), holder);
			return FormulaKey.of(identity, classId, FormulaKey.NO_CHILDREN, holder);
		}
		return (FormulaKey) invoke(findMethod(holder.getClass(), "getKey"), holder);
	}

	/**
	 * Builds a childless key over a record under the record's own class identifier.
	 *
	 * @param record the detached identity used as the entire childless key input
	 * @param owner the object supplying instance identity when the record contains opaque inputs
	 * @return a childless key constructed from the record and its declared class identifier
	 */
	@Nonnull
	private static FormulaKey leafKey(@Nonnull IdentityRecord record, @Nonnull Object owner) {
		return FormulaKey.of(record, record.classId(), FormulaKey.NO_CHILDREN, owner);
	}

	/**
	 * Asserts all equal-key hash obligations together.
	 *
	 * @param first the reference key for symmetric equality and both hash comparisons
	 * @param second the key required to equal the reference and produce the same hashes
	 * @param message the assertion context, extended with the hash kind when a hash comparison fails
	 */
	private static void assertEqualKeys(
		@Nonnull FormulaKey first, @Nonnull FormulaKey second, @Nonnull String message
	) {
		assertEquals(first, second, message);
		assertEquals(second, first, message);
		assertEquals(first.hashCode(), second.hashCode(), message + " hashCode");
		assertEquals(first.getHash64(), second.getHash64(), message + " hash64");
	}

	/**
	 * Returns whether the class declares the exact method rather than inheriting it.
	 *
	 * @param type the class whose own declarations are inspected, excluding inherited methods
	 * @param name the method name to look up
	 * @param parameters the exact parameter types in declaration order; empty for a no-argument method
	 * @return `true` if the class declares a method with exactly that name and parameter list
	 */
	private static boolean declares(@Nonnull Class<?> type, @Nonnull String name, @Nonnull Class<?>... parameters) {
		try {
			type.getDeclaredMethod(name, parameters);
			return true;
		} catch (NoSuchMethodException ignored) {
			return false;
		}
	}

	/**
	 * Finds a no-argument method up the class hierarchy, including public interface defaults.
	 *
	 * @param type the class at which the search begins before walking superclasses and public interface methods
	 * @param name the no-argument method name to resolve
	 * @return the first matching declaration, or a public inherited method when no class declaration matches
	 */
	@Nonnull
	private static Method findMethod(@Nonnull Class<?> type, @Nonnull String name) {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			try {
				return current.getDeclaredMethod(name);
			} catch (NoSuchMethodException ignored) {
				// Continue to the superclass, then check interface defaults through getMethod.
			}
		}
		try {
			return type.getMethod(name);
		} catch (NoSuchMethodException ex) {
			throw new AssertionError("Missing " + name + " on " + type.getName(), ex);
		}
	}

	/**
	 * Invokes an accessible method and preserves failures from the implementation.
	 *
	 * @param method the method made accessible and invoked without arguments
	 * @param receiver the instance on which the method is invoked
	 * @return the value returned by the method, or `null` for a null result or a void method
	 */
	@Nullable
	private static Object invoke(@Nonnull Method method, @Nonnull Object receiver) {
		method.setAccessible(true);
		try {
			return method.invoke(receiver);
		} catch (IllegalAccessException ex) {
			throw new AssertionError(ex);
		} catch (InvocationTargetException ex) {
			if (ex.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (ex.getCause() instanceof Error error) {
				throw error;
			}
			throw new AssertionError(ex.getCause());
		}
	}

	/**
	 * Reads a component through its record accessor.
	 *
	 * @param record the identity record on which the component accessor is invoked
	 * @param component the record component whose no-argument accessor supplies the value
	 * @return the component value without copying it, or `null` when the component is absent
	 */
	@Nullable
	private static Object componentValue(@Nonnull IdentityRecord record, @Nonnull RecordComponent component) {
		return invoke(component.getAccessor(), record);
	}

	/**
	 * Reads an accessible instance field.
	 *
	 * @param field the instance field, already made accessible by the caller
	 * @param holder the instance whose field value is read
	 * @return the retained field value, boxed for primitives, or `null` for an unset reference
	 */
	@Nullable
	private static Object read(@Nonnull Field field, @Nonnull Object holder) {
		try {
			return field.get(holder);
		} catch (IllegalAccessException ex) {
			throw new AssertionError(ex);
		}
	}

	/**
	 * Loads a type without requiring production exports for the test inventory.
	 *
	 * @param name the binary class name resolved with this verifier's class loader
	 * @return the loaded class without triggering its static initialization
	 */
	@Nonnull
	private static Class<?> loadType(@Nonnull String name) {
		try {
			return Class.forName(name, false, IdentityContractVerifier.class.getClassLoader());
		} catch (ClassNotFoundException ex) {
			throw new AssertionError("Missing type: " + name, ex);
		}
	}

	/**
	 * Constructs a conventional fixture through its no-argument constructor.
	 *
	 * @param type the fixture class whose no-argument constructor is made accessible
	 * @return a fresh fixture instance created through that constructor
	 */
	@Nonnull
	private static Object construct(@Nonnull Class<?> type) {
		try {
			final Constructor<?> constructor = type.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError("Cannot construct fixture " + type.getName(), ex);
		}
	}

	/**
	 * Component-free parent used to check opaque child propagation.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record ParentIdentity() implements IdentityRecord {
		/** Returns the synthetic parent's identifier. */
		@Override public long classId() { return 0; }
	}

	/**
	 * Side-key holder used to check opaque side-input propagation.
	 *
	 * @param sideKey the opaque side input whose locality is propagated through the synthetic holder
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record SideIdentity(@Nonnull FormulaKey sideKey) implements IdentityRecord {
		/** Returns the synthetic side holder's identifier. */
		@Override public long classId() { return 0; }
		/** Includes the side input. */
		@Override public void describeTo(@Nonnull KeyAccumulator accumulator) { accumulator.add(this.sideKey); }
	}
}
