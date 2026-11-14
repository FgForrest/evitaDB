package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.fail;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression tests for {@link PersistentRoaringBitmap} deserialization of adversarial inputs,
 * ported from the upstream RoaringBitmap test suite. They pin down that well-formed streams
 * deserialize correctly while crash-prone, malformed inputs are rejected rather than crashing.
 */
@DisplayName("PersistentRoaringBitmap deserialization of adversarial inputs")
public class TestAdversarialInputs {

	public static Stream<Arguments> badFiles() {
		return IntStream.rangeClosed(1, 7)
			.mapToObj(i -> Arguments.of("/testdata/crashproneinput" + i + ".bin"));
	}

	// open a stream without copying files
	public static InputStream openInputstream(String resourceName) throws IOException {
		InputStream resourceAsStream = TestAdversarialInputs.class.getResourceAsStream(resourceName);
		if (resourceAsStream == null) {
			throw new IOException("Cannot get resource \"" + resourceName + "\".");
		}
		return resourceAsStream;
	}

	@Test
	public void testInputGoodFile1() throws IOException {
		InputStream inputStream = openInputstream("/testdata/bitmapwithruns.bin");
		PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
		// should not throw an exception
		rb.deserialize(new DataInputStream(inputStream));
		assertEquals(200100, rb.getCardinality());
	}

	@Test
	public void testInputGoodFile2() throws IOException {
		InputStream inputStream = openInputstream("/testdata/bitmapwithoutruns.bin");
		PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
		// should not throw an exception
		rb.deserialize(new DataInputStream(inputStream));
		assertEquals(200100, rb.getCardinality());
	}

	@ParameterizedTest
	@MethodSource("badFiles")
	public void testInputBadFile8(String fileName) {
		assertThrows(IOException.class, () -> deserialize(fileName));
	}

	private static void deserialize(String fileName) throws IOException {
		InputStream inputStream = openInputstream(fileName);
		PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
		// should not work
		rb.deserialize(new DataInputStream(inputStream));
	}

	/**
	 * One heap deserialization entry point, exercised against the same malformed bytes.
	 */
	@FunctionalInterface
	private interface Deserializer {
		/**
		 * Overwrites the target with the supplied wire bytes.
		 */
		void deserialize(@Nonnull final PersistentRoaringBitmap target, @Nonnull final byte[] bytes)
			throws IOException;
	}

	/**
	 * The four heap paths covered by upstream #895, including small and container-sized buffers.
	 */
	@Nonnull
	static Stream<Arguments> deserializers() {
		return Stream.of(
			Arguments.of("DataInput", (Deserializer) (target, bytes) -> target.deserialize(
				new DataInputStream(new ByteArrayInputStream(bytes)))),
			Arguments.of("DataInput, 16-byte buffer", (Deserializer) (target, bytes) -> target.deserialize(
				new DataInputStream(new ByteArrayInputStream(bytes)), new byte[16])),
			Arguments.of("DataInput, 8192-byte buffer", (Deserializer) (target, bytes) -> target.deserialize(
				new DataInputStream(new ByteArrayInputStream(bytes)), new byte[8192])),
			Arguments.of("ByteBuffer", (Deserializer) (target, bytes) -> target.deserialize(ByteBuffer.wrap(bytes)))
		);
	}

	/**
	 * Adds the Externalizable hook to the copy-on-write checks without requiring a binary fixture.
	 */
	@Nonnull
	static Stream<Arguments> allDeserializers() {
		return Stream.concat(deserializers(), Stream.of(Arguments.of("ObjectInput", (Deserializer) (target, bytes) -> {
			final ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (final ObjectOutputStream objects = new ObjectOutputStream(output)) {
				objects.write(bytes);
			}
			try (final ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(output.toByteArray()))) {
				target.readExternal(input);
			}
		})));
	}

	/**
	 * Serializes into an array so a read can be truncated after some containers have been consumed.
	 */
	@Nonnull
	private static byte[] serialize(@Nonnull final PersistentRoaringBitmap bitmap) {
		final ByteBuffer buffer = ByteBuffer.allocate(bitmap.serializedSizeInBytes());
		bitmap.serialize(buffer);
		return buffer.array();
	}

	/**
	 * Ports upstream's checks that a failed read leaves an empty bitmap that can accept writes.
	 */
	private static void assertUsableAndEmpty(@Nonnull final PersistentRoaringBitmap bitmap) {
		assertEquals(0, bitmap.getLongCardinality());
		assertTrue(bitmap.validate());
		assertFalse(bitmap.contains(7));
		bitmap.add(5);
		assertTrue(bitmap.contains(5));
		assertEquals(1, bitmap.getLongCardinality());
	}

	/**
	 * Ports upstream #895's partial-read regression; two containers are read before the third fails.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("deserializers")
	public void failedDeserializationLeavesAnEmptyValidBitmap(
		@Nonnull final String path, @Nonnull final Deserializer deserializer
	) {
		final byte[] bytes = serialize(PersistentRoaringBitmap.bitmapOf(7, 70000, 140000));
		assertEquals(38, bytes.length);
		final byte[] truncated = Arrays.copyOf(bytes, bytes.length - 1);
		final PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOf(1, 2, 3);
		assertThrows(Exception.class, () -> deserializer.deserialize(bitmap, truncated), path);
		assertUsableAndEmpty(bitmap);
	}

	/**
	 * Ports upstream #895's negative count regression (little-endian Integer.MIN_VALUE).
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("deserializers")
	public void negativeContainerCountIsRejected(
		@Nonnull final String path, @Nonnull final Deserializer deserializer
	) {
		final byte[] bytes = {0x3a, 0x30, 0, 0, 0, 0, 0, (byte) 0x80};
		final PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOf(1, 2, 3);
		final IOException error = assertThrows(IOException.class, () -> deserializer.deserialize(bitmap, bytes), path);
		assertTrue(error.getMessage().contains("Size too large"));
		assertUsableAndEmpty(bitmap);
	}

	/**
	 * Failed replacement must forget all former sharing before owned containers reuse those slots.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("allDeserializers")
	public void failedDeserializationResetsSharedContainers(
		@Nonnull final String path, @Nonnull final Deserializer deserializer
	) {
		final PersistentRoaringBitmap peer = PersistentRoaringBitmap.bitmapOf(7, 70000, 140000);
		final int[] peerBefore = peer.toArray();
		final PersistentRoaringBitmap bitmap = peer.clone();
		for (int i = 0; i < peer.highLowContainer.size(); i++) {
			assertSame(peer.getContainerAtIndex(i), bitmap.getContainerAtIndex(i));
			assertTrue(bitmap.isShared(i));
		}
		final byte[] bytes = serialize(peer);
		assertThrows(Exception.class, () -> deserializer.deserialize(bitmap, Arrays.copyOf(bytes, bytes.length - 1)), path);
		assertTrue(bitmap.isEmpty());
		assertTrue(bitmap.validate());
		SharedContainerLockstepFuzzTest.assertLockstep(bitmap, path + " after failed read");
		assertArrayEquals(new boolean[bitmap.shared.length], bitmap.shared);

		// append does not clear a pre-existing flag; owned slots must already have false flags.
		final ArrayContainer owned = new ArrayContainer();
		owned.add((char) 11);
		bitmap.append((char) 0, owned);
		assertFalse(bitmap.isShared(0));
		bitmap.add(12);
		assertSame(owned, bitmap.getContainerAtIndex(0));
		bitmap.add(70001);
		bitmap.or(PersistentRoaringBitmap.bitmapOf(13, 70002, 200000));
		bitmap.and(PersistentRoaringBitmap.bitmapOf(12, 13, 70002));
		assertEquals(PersistentRoaringBitmap.bitmapOf(12, 13, 70002), bitmap);
		assertTrue(bitmap.validate());
		SharedContainerLockstepFuzzTest.assertLockstep(bitmap, path + " after writes");
		SharedContainerLockstepFuzzTest.assertLockstep(peer, path + " peer");
		assertArrayEquals(peerBefore, peer.toArray());
	}

	/**
	 * A rejected scratch buffer must clear the old containers before the finally block resets flags.
	 */
	@Test
	public void invalidScratchBufferLeavesAnEmptyUnsharedBitmap() {
		final PersistentRoaringBitmap peer = PersistentRoaringBitmap.bitmapOf(7, 70000);
		final int[] peerBefore = peer.toArray();
		final PersistentRoaringBitmap bitmap = peer.clone();
		assertThrows(IllegalArgumentException.class, () -> bitmap.deserialize(
			new DataInputStream(new ByteArrayInputStream(new byte[0])), new byte[7]));
		assertTrue(bitmap.isEmpty());
		SharedContainerLockstepFuzzTest.assertLockstep(bitmap, "after invalid scratch buffer");
		bitmap.add(7);
		bitmap.add(8);
		assertArrayEquals(peerBefore, peer.toArray());
	}

	/**
	 * Ports upstream #895's `hex` scenario.
	 */
	public static byte[] hex(@Nonnull final String hex) {
		String digits = hex.replace(" ", "");
		byte[] bytes = new byte[digits.length() / 2];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) Integer.parseInt(digits.substring(2 * i, 2 * i + 2), 16);
		}
		return bytes;
	}

	/**
	 * Ports upstream #895's `containerCountAbove65536IsRejected` scenario.
	 */
	@Test
	public void containerCountAbove65536IsRejected() {
		byte[] header = hex("3A300000 01000100"); // 65537 containers
		byte[] bytes = Arrays.copyOf(header, header.length + 65537 * 12);
		for (Arguments arguments : deserializers().toList()) {
			Deserializer deserializer = (Deserializer) arguments.get()[1];
			PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
			assertThrows(IOException.class, () -> deserializer.deserialize(rb, bytes));
			assertUsableAndEmpty(rb);
		}
	}

	/**
	 * Ports upstream #895's `containerCountOf65536IsAccepted` scenario.
	 */
	@Test
	public void containerCountOf65536IsAccepted() throws IOException {
		// 65536 containers with key 0 holding the value 0: accepted, but the keys are not increasing
		byte[] header = hex("3A300000 00000100");
		byte[] bytes = Arrays.copyOf(header, header.length + 65536 * 10);
		for (Arguments arguments : deserializers().toList()) {
			Deserializer deserializer = (Deserializer) arguments.get()[1];
			PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
			deserializer.deserialize(rb, bytes);
			assertEquals(65536, rb.getContainerCount());
			assertFalse(rb.validate());
		}
	}

	/**
	 * Ports upstream #895's `malformedInputIsAcceptedButInvalid` scenario.
	 */
	@ParameterizedTest
	@MethodSource("malformedVectors")
	public void malformedInputIsAcceptedButInvalid(@Nonnull final String name, @Nonnull final String hex, final int cardinality)
			throws IOException {
		byte[] bytes = hex(hex);
		for (Arguments arguments : deserializers().toList()) {
			Deserializer deserializer = (Deserializer) arguments.get()[1];
			PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
			deserializer.deserialize(rb, bytes);
			assertEquals(cardinality, rb.getLongCardinality(), name);
			assertFalse(rb.validate(), name);
		}
		// dropping the last byte makes the input too short
		byte[] truncated = Arrays.copyOf(bytes, bytes.length - 1);
		for (Arguments arguments : deserializers().toList()) {
			Deserializer deserializer = (Deserializer) arguments.get()[1];
			assertThrows(
					Exception.class, () -> deserializer.deserialize(new PersistentRoaringBitmap(), truncated), name);
		}
	}

	/**
	 * Ports upstream #895's `malformedVectors` scenario.
	 */
	public static Stream<Arguments> malformedVectors() {
		return Stream.of(
				Arguments.of("duplicate keys", "3B30 0100 00 0000 0000 0000 0000 0000 0000", 2),
				Arguments.of("unsorted keys", "3B30 0100 00 0100 0000 0000 0000 0000 0000", 2),
				Arguments.of("descending array", "3B30 0000 00 0000 0100 0100 0000", 2),
				Arguments.of("array duplicates", "3B30 0000 00 0000 0100 0000 0000", 2),
				Arguments.of("zero runs", "3B30 0000 01 0000 0000 0000", 0),
				Arguments.of("contiguous runs", "3B30 0000 01 0000 0100 0200 0000 0000 0100 0000", 2),
				Arguments.of("overlapping runs", "3B30 0000 01 0000 0400 0200 0000 0400 0100 0000", 6),
				Arguments.of("run overflow", "3B30 0000 01 0000 0400 0100 FEFF 0400", 5),
				Arguments.of("run crossing the end", "3b300000 01 0000 fa2e 0100 0002 ffff", 65536));
	}

	/**
	 * Ports upstream #895's `bitmapContainerWithWrongHeaderCardinalityIsInvalid` scenario.
	 */
	@Test
	public void bitmapContainerWithWrongHeaderCardinalityIsInvalid() throws IOException {
		// one full bitmap container (65536 values), from robust_deserialization_unit.c
		byte[] bytes = new byte[9 + 8192];
		System.arraycopy(hex("3B30 0000 00 0000 FFFF"), 0, bytes, 0, 9);
		Arrays.fill(bytes, 9, bytes.length, (byte) 0xFF);
		for (Arguments arguments : deserializers().toList()) {
			Deserializer deserializer = (Deserializer) arguments.get()[1];
			PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
			deserializer.deserialize(rb, bytes);
			assertTrue(rb.validate());
			assertEquals(65536, rb.getLongCardinality());
		}
		bytes[bytes.length - 1] = (byte) 0xFE;
		for (Arguments arguments : deserializers().toList()) {
			Deserializer deserializer = (Deserializer) arguments.get()[1];
			PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
			deserializer.deserialize(rb, bytes);
			assertFalse(rb.validate());
		}
	}

	/**
	 * Ports upstream #895's `runHeaderCardinalityIsIgnoredOnTheHeap` scenario.
	 */
	@Test
	public void runHeaderCardinalityIsIgnoredOnTheHeap() throws IOException {
		// the header says 1 value, the run holds 9: the heap readers ignore the header
		// (as CRoaring and Go do), while ImmutableRoaringBitmap trusts it and fails validation
		byte[] bytes = hex("3B30 0000 01 0000 0000 0100 0000 0800");
		for (Arguments arguments : deserializers().toList()) {
			Deserializer deserializer = (Deserializer) arguments.get()[1];
			PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
			deserializer.deserialize(rb, bytes);
			assertEquals(9, rb.getLongCardinality());
			assertTrue(rb.validate());
		}
	}

	/**
	 * Ports upstream #895's `randomInputOnlyThrowsIOExceptions` scenario.
	 */
	@Test
	public void randomInputOnlyThrowsIOExceptions() throws IOException {
		// port of CRoaring's fuzz_deserializer
		Random random = new Random(1234);
		for (int i = 0; i < 10000; i++) {
			byte[] bytes = new byte[random.nextInt(10000)];
			random.nextBytes(bytes);
			if (bytes.length >= 8) {
				// most random cookies are rejected immediately, so we mostly use valid cookies
				switch (i % 3) {
					case 0:
						bytes[0] = 0x3B;
						bytes[1] = 0x30;
						bytes[2] = (byte) random.nextInt(16);
						bytes[3] = 0;
						break;
					case 1:
						System.arraycopy(hex("3A300000"), 0, bytes, 0, 4);
						bytes[4] = (byte) random.nextInt(16);
						bytes[5] = 0;
						bytes[6] = 0;
						bytes[7] = 0;
						break;
					default:
						break;
				}
			}
			PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
			try {
				rb.deserialize(new DataInputStream(new ByteArrayInputStream(bytes)));
			} catch (IOException expected) {
				assertTrue(rb.isEmpty());
				continue;
			} catch (RuntimeException e) {
				fail("unexpected exception for input " + i, e);
			}
			if (rb.validate()) {
				long cardinality = rb.getLongCardinality();
				long absent = IntStream.range(100, 1000).filter(v -> !rb.contains(v)).count();
				rb.add(100L, 1000L);
				assertEquals(cardinality + absent, rb.getLongCardinality());
				assertTrue(rb.validate());
			}
		}
	}

	/**
	 * Inlines the small portable twins of Go's frozen fixtures, which upstream also reads on the
	 * heap. The bitmap-only and mixed fixtures exceed this phase's small-input limit.
	 */
	@Nonnull
	static Stream<Arguments> smallPortableFiles() {
		return Stream.of(
			Arguments.of("runs_only", hex("3b300100030000feef0300feef01000000feef01000000feef"), 122878, 0, 2),
			Arguments.of("arrays_only", hex("3a300000020000000100410004004100180000009c0000000000e803d007b80ba00f88137017581b401f28231027f82ae02ec832b036983a803e68425046384a204e0852f055d859c05da86190657869606d487130751879007de880d084b888a08c889070945898409c28a010a4f8a7e0abc8afb0b398b780bb68bf50c338c720cb08cff0d2d8d6c0daa8de90e278e660ea48ee30f218f600fae8fd0000e803d007b80ba00f88137017581b401f28231027f82ae02ec832b036983a803e68425046384a204e0852f055d859c05da86190657869606d487130751879007de880d084b888a08c889070945898409c28a010a4f8a7e0abc8afb0b398b780bb68bf50c338c720cb08cff0d2d8d6c0daa8de90e278e660ea48ee30f218f600fae8fd"), 132, 2, 0)
		);
	}

	/**
	 * Ports #895's heap portable fixture round-trip, cardinality and container encoding checks.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("smallPortableFiles")
	public void testPortableFilesSharedWithGo(
		@Nonnull final String name, @Nonnull final byte[] bytes, final int cardinality,
		final int arrays, final int runs
	) throws IOException {
		PersistentRoaringBitmap reference = null;
		for (final Arguments arguments : deserializers().toList()) {
			final Deserializer deserializer = (Deserializer) arguments.get()[1];
			final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
			deserializer.deserialize(bitmap, bytes);
			assertTrue(bitmap.validate(), name);
			assertEquals(cardinality, bitmap.getLongCardinality(), name);
			int actualArrays = 0;
			int actualRuns = 0;
			for (int i = 0; i < bitmap.getContainerCount(); i++) {
				actualArrays += bitmap.getContainerAtIndex(i) instanceof ArrayContainer ? 1 : 0;
				actualRuns += bitmap.getContainerAtIndex(i) instanceof RunContainer ? 1 : 0;
			}
			assertEquals(arrays, actualArrays, name);
			assertEquals(runs, actualRuns, name);
			assertEquals(arrays + runs, bitmap.getContainerCount(), name);
			assertArrayEquals(bytes, serialize(bitmap), name);
			if (reference != null) {
				assertEquals(reference, bitmap, name);
			}
			reference = bitmap;
		}
	}

	/**
	 * Every strict prefix of the two small upstream fixtures must fail and leave a usable bitmap.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("smallPortableFiles")
	public void everyStrictPrefixIsRejected(
		@Nonnull final String name, @Nonnull final byte[] bytes, final int cardinality,
		final int arrays, final int runs
	) {
		for (int length = 0; length < bytes.length; length++) {
			final byte[] prefix = Arrays.copyOf(bytes, length);
			for (final Arguments arguments : deserializers().toList()) {
				final Deserializer deserializer = (Deserializer) arguments.get()[1];
				final PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOf(42);
				assertThrows(Exception.class, () -> deserializer.deserialize(bitmap, prefix), name + " prefix=" + length);
				assertUsableAndEmpty(bitmap);
			}
		}
	}

	/**
	 * Go's invalid ASCII cookie and empty-file crash reproducers need no resource files.
	 */
	@Test
	public void testInputBadCookieFile() {
		for (final byte[] bytes : new byte[][]{{0x30, 0x30, 0x30, 0x30}, {}}) {
			for (final Arguments arguments : deserializers().toList()) {
				final Deserializer deserializer = (Deserializer) arguments.get()[1];
				final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
				assertThrows(Exception.class, () -> deserializer.deserialize(bitmap, bytes));
				assertUsableAndEmpty(bitmap);
			}
		}
	}
}
