package io.evitadb.roaringbitmap;

import static io.evitadb.roaringbitmap.TestAdversarialInputs.hex;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import javax.annotation.Nonnull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.CipherInputStream;
import javax.crypto.NullCipher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Random;

class ByteBufferBackedInputStream extends InputStream {

	ByteBuffer buf;

	ByteBufferBackedInputStream(ByteBuffer buf) {
		this.buf = buf;
	}

	@Override
	public int available() throws IOException {
		return this.buf.remaining();
	}

	@Override
	public boolean markSupported() {
		return false;
	}

	@Override
	public int read() throws IOException {
		if (!this.buf.hasRemaining()) {
			return -1;
		}
		return 0xFF & this.buf.get();
	}

	@Override
	public int read(byte[] bytes) throws IOException {
		int len = Math.min(bytes.length, this.buf.remaining());
		this.buf.get(bytes, 0, len);
		return len;
	}

	@Override
	public int read(byte[] bytes, int off, int len) throws IOException {
		len = Math.min(len, this.buf.remaining());
		this.buf.get(bytes, off, len);
		return len;
	}

	@Override
	public long skip(long n) {
		int len = Math.min((int) n, this.buf.remaining());
		this.buf.position(this.buf.position() + (int) n);
		return len;
	}
}

class ByteBufferBackedOutputStream extends OutputStream {
	ByteBuffer buf;

	ByteBufferBackedOutputStream(ByteBuffer buf) {
		this.buf = buf;
	}

	@Override
	public synchronized void write(byte[] bytes) throws IOException {
		this.buf.put(bytes);
	}

	@Override
	public synchronized void write(byte[] bytes, int off, int len) throws IOException {
		this.buf.put(bytes, off, len);
	}

	@Override
	public synchronized void write(int b) throws IOException {
		this.buf.put((byte) b);
	}
}

/**
 * Regression tests for {@link PersistentRoaringBitmap} serialization, ported from the upstream
 * RoaringBitmap test suite. They pin down serialize/deserialize round trips through data streams,
 * external streams and raw byte buffers, including run-optimized and immutable views.
 */
@DisplayName("PersistentRoaringBitmap serialization")
public class TestSerialization {
	static PersistentRoaringBitmap bitmap_a;

	static PersistentRoaringBitmap bitmap_a1;

	static PersistentRoaringBitmap bitmap_empty = new PersistentRoaringBitmap();

	static PersistentRoaringBitmap bitmap_b = new PersistentRoaringBitmap();

	static ByteBuffer outbb;

	static ByteBuffer presoutbb;

	// Very small buffer to higher to chance to encounter edge-case
	byte[] buffer = new byte[16];

	@BeforeAll
	public static void init() throws IOException {
		final int[] data = takeSortedAndDistinct(new Random(0xcb000a2b9b5bdfb6L), 100000);
		bitmap_a = PersistentRoaringBitmap.bitmapOf(data);
		bitmap_a1 = PersistentRoaringBitmap.bitmapOf(data);

		for (int k = 100000; k < 200000; ++k) {
			bitmap_a.add(3 * k); // bitmap density and too many little runs
			bitmap_a1.add(3 * k);
		}

		for (int k = 700000; k < 800000; ++k) { // runcontainer would be best
			bitmap_a.add(k);
			bitmap_a1.add(k);
		}

		bitmap_a.runOptimize(); // mix of all 3 container kinds
		// do not runoptimize bitmap_a1

		outbb =
			ByteBuffer.allocate(
				bitmap_a.serializedSizeInBytes() + bitmap_empty.serializedSizeInBytes());
		presoutbb =
			ByteBuffer.allocate(
				bitmap_a.serializedSizeInBytes() + bitmap_empty.serializedSizeInBytes());
		ByteBufferBackedOutputStream out = new ByteBufferBackedOutputStream(presoutbb);

		DataOutputStream dos = new DataOutputStream(out);
		bitmap_empty.serialize(dos);
		bitmap_a.serialize(dos);
		presoutbb.flip();
	}

	private static int[] takeSortedAndDistinct(Random source, int count) {

		LinkedHashSet<Integer> ints = new LinkedHashSet<Integer>(count);

		for (int size = 0; size < count; size++) {
			int next;
			do {
				next = Math.abs(source.nextInt());
			} while (!ints.add(next));
		}

		int[] unboxed = toArray(ints);
		Arrays.sort(unboxed);
		return unboxed;
	}

	private static int[] toArray(LinkedHashSet<Integer> integers) {
		int[] ints = new int[integers.size()];
		int i = 0;
		for (Integer n : integers) {
			ints[i++] = n;
		}
		return ints;
	}

	@Test
	public void testDeserialize() throws IOException {
		presoutbb.rewind();
		ByteBufferBackedInputStream in = new ByteBufferBackedInputStream(presoutbb);
		DataInputStream dis = new DataInputStream(in);
		bitmap_empty.deserialize(dis);
		bitmap_b.deserialize(dis);
		assertTrue(bitmap_b.validate());
		assertTrue(bitmap_empty.validate());
	}

	@Test
	public void testDeserialize_buffer() throws IOException {
		presoutbb.rewind();
		ByteBufferBackedInputStream in = new ByteBufferBackedInputStream(presoutbb);
		DataInputStream dis = new DataInputStream(in);
		bitmap_empty.deserialize(dis, this.buffer);
		bitmap_b.deserialize(dis, this.buffer);
		assertTrue(bitmap_empty.validate());
		assertTrue(bitmap_b.validate());
		assertEquals(bitmap_a, bitmap_b);
	}

	@Test
	public void testRunSerializationDeserialization() throws java.io.IOException {
		final int[] data = takeSortedAndDistinct(new Random(07734), 100000);
		PersistentRoaringBitmap bitmap_a = PersistentRoaringBitmap.bitmapOf(data);
		PersistentRoaringBitmap bitmap_ar = PersistentRoaringBitmap.bitmapOf(data);

		for (int k = 100000; k < 200000; ++k) {
			bitmap_a.add(3 * k); // bitmap density and too many little runs
			bitmap_ar.add(3 * k);
		}

		for (int k = 700000; k < 800000; ++k) { // will choose a runcontainer on this
			bitmap_a.add(k);
			bitmap_ar.add(k);
		}

		bitmap_a.runOptimize(); // mix of all 3 container kinds

		ByteBuffer outbuf = ByteBuffer.allocate(bitmap_a.serializedSizeInBytes());
		ByteBufferBackedOutputStream out = new ByteBufferBackedOutputStream(outbuf);
		try {
			bitmap_a.serialize(new DataOutputStream(out));
		} catch (Exception e) {
			e.printStackTrace();
		}
		outbuf.flip();

		PersistentRoaringBitmap bitmap_c = new PersistentRoaringBitmap();

		ByteBufferBackedInputStream in = new ByteBufferBackedInputStream(outbuf);
		bitmap_c.deserialize(new DataInputStream(in));
		assertTrue(bitmap_c.validate());

		assertEquals(bitmap_a, bitmap_c);
	}

	@Test
	public void testSerialize() throws IOException {
		outbb.rewind();
		ByteBufferBackedOutputStream out = new ByteBufferBackedOutputStream(outbb);
		DataOutputStream dos = new DataOutputStream(out);
		bitmap_empty.serialize(dos);
		bitmap_a.serialize(dos);
	}

	// Encode the PersistentRoaringBitmap to a string representation
	public static String encodeToString(PersistentRoaringBitmap bitmap) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream(bitmap.serializedSizeInBytes());
		bitmap.serialize(new DataOutputStream(baos));
		return Base64.getEncoder().encodeToString(baos.toByteArray());
	}

	// Decode the string representation and reconstruct the PersistentRoaringBitmap
	public static PersistentRoaringBitmap decodeFromString(String encodedString) throws IOException {
		byte[] decodedBytes = Base64.getDecoder().decode(encodedString);
		ByteArrayInputStream in = new ByteArrayInputStream(decodedBytes);
		DataInputStream dis = new DataInputStream(in);
		PersistentRoaringBitmap r = new PersistentRoaringBitmap();
		r.deserialize(dis);
		assertTrue(r.validate());
		return r;
	}

	@Test
	public void testStringification() throws IOException {
		PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		bitmap.add(1);
		bitmap.add(3);
		bitmap.add(5);
		String encodedString = encodeToString(bitmap);
		PersistentRoaringBitmap decoded = decodeFromString(encodedString);
		assertEquals(bitmap, decoded);
		outbb.rewind();
	}

	@Test
	public void testDeserializeSmallData() throws IOException {
		PersistentRoaringBitmap source = PersistentRoaringBitmap.bitmapOf(25286760);
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		source.serialize(new DataOutputStream(outputStream));
		boolean expected = source.intersects(26244001, 27293761);

		ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
		PersistentRoaringBitmap target = new PersistentRoaringBitmap();
		target.deserialize(new DataInputStream(inputStream));
		assertTrue(target.validate());

		boolean actual = target.intersects(26244001, 27293761);
		assertEquals(actual, expected);
	}

	/**
	 * Ports upstream #890: the 800-byte offset table crosses CipherInputStream's 512-byte buffer,
	 * whose skip stops at that buffer boundary. Both DataInput overloads must consume the remainder.
	 */
	@ParameterizedTest(name = "buffered={0}")
	@ValueSource(booleans = {false, true})
	public void testDeserializeWhenSkipBytesFallsShort(final boolean buffered) throws IOException {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		for (int i = 0; i < 200; i++) {
			bitmap.add(i << 16);
		}
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		bitmap.serialize(new DataOutputStream(output));
		final byte[] bytes = output.toByteArray();
		final PersistentRoaringBitmap restored = new PersistentRoaringBitmap();
		try (final DataInputStream input = new DataInputStream(
			new CipherInputStream(new ByteArrayInputStream(bytes), new NullCipher()))) {
			if (buffered) {
				restored.deserialize(input, new byte[8192]);
			} else {
				restored.deserialize(input);
			}
		}
		assertEquals(bitmap, restored);
	}

	/**
	 * Ports upstream #895's `toBytes` scenario.
	 */
	private static byte[] toBytes(@Nonnull final PersistentRoaringBitmap rb) {
		ByteBuffer buffer = ByteBuffer.allocate(rb.serializedSizeInBytes());
		rb.serialize(buffer);
		return buffer.array();
	}

	/**
	 * Ports upstream #895's `fromBytes` scenario.
	 */
	private static PersistentRoaringBitmap fromBytes(@Nonnull final byte[] bytes) throws IOException {
		PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
		rb.deserialize(ByteBuffer.wrap(bytes));
		return rb;
	}

	/**
	 * Ports upstream #895's `testGoldenBytesAroundTheNoOffsetThreshold` scenario.
	 */
	@Test
	public void testGoldenBytesAroundTheNoOffsetThreshold() throws IOException {
		// the same bytes are produced by CRoaring and Go
		String[] expected = {
			"3b3000000100000900010000000900",
			"3b300100030000090001000900010000000900010000000900",
			"3b30020007000009000100090002000900010000000900010000000900010000000900",
			"3b3003000f00000900010009000200090003000900250000002b0000003100000037000000"
					+ "010000000900010000000900010000000900010000000900"
		};
		for (int k = 1; k <= 5; k++) {
			PersistentRoaringBitmap runs = new PersistentRoaringBitmap();
			PersistentRoaringBitmap arrays = new PersistentRoaringBitmap();
			for (int j = 0; j < k; j++) {
				runs.add((long) j << 16, ((long) j << 16) + 10);
				arrays.add((j << 16) + 7);
			}
			assertTrue(runs.runOptimize());
			byte[] bytes = toBytes(runs);
			// without offsets below 4 containers (NO_OFFSET_THRESHOLD), with offsets from then on
			assertEquals(k < 4 ? 5 + 10 * k : 5 + 14 * k, bytes.length);
			if (k <= expected.length) {
				assertArrayEquals(hex(expected[k - 1]), bytes);
			}
			assertEquals(runs, fromBytes(bytes));
			// without run containers, offsets are always present
			assertEquals(8 + 10 * k, arrays.serializedSizeInBytes());
			assertEquals(arrays, fromBytes(toBytes(arrays)));
		}
	}

	/**
	 * Ports upstream #895's `testEmptyBitmapGoldenBytes` scenario.
	 */
	@Test
	public void testEmptyBitmapGoldenBytes() throws IOException {
		byte[] bytes = toBytes(new PersistentRoaringBitmap());
		assertArrayEquals(hex("3a30000000000000"), bytes);
		PersistentRoaringBitmap rb = fromBytes(bytes);
		assertTrue(rb.isEmpty());
		assertTrue(rb.validate());
		for (byte[] tooShort : new byte[][] {new byte[0], hex("3a300000"), hex("3a300000000000")}) {
			assertThrows(
					IOException.class,
					() ->
							new PersistentRoaringBitmap()
									.deserialize(new DataInputStream(new ByteArrayInputStream(tooShort))));
			assertThrows(RuntimeException.class, () -> fromBytes(tooShort));
		}
	}

	/**
	 * Ports upstream #895's `testArrayAndBitmapContainerBoundaryGoldenBytes` scenario.
	 */
	@Test
	public void testArrayAndBitmapContainerBoundaryGoldenBytes() throws IOException {
		PersistentRoaringBitmap array = new PersistentRoaringBitmap();
		for (int i = 0; i < 4096; i++) {
			array.add(2 * i);
		}
		PersistentRoaringBitmap bitmap = array.clone();
		bitmap.add(2 * 4096);
		byte[] arrayBytes = toBytes(array);
		byte[] bitmapBytes = toBytes(bitmap);
		assertEquals(8208, arrayBytes.length);
		assertEquals(16 + 8192, bitmapBytes.length);
		assertArrayEquals(hex("3A300000 01000000 0000 FF0F 10000000"), Arrays.copyOf(arrayBytes, 16));
		assertArrayEquals(hex("3A300000 01000000 0000 0010 10000000"), Arrays.copyOf(bitmapBytes, 16));
		PersistentRoaringBitmap arrayCopy = fromBytes(arrayBytes);
		PersistentRoaringBitmap bitmapCopy = fromBytes(bitmapBytes);
		assertTrue(arrayCopy.validate());
		assertTrue(bitmapCopy.validate());
		assertEquals(4096, arrayCopy.getCardinality());
		assertEquals(4097, bitmapCopy.getCardinality());
		assertArrayEquals(arrayBytes, toBytes(arrayCopy));
		assertArrayEquals(bitmapBytes, toBytes(bitmapCopy));
	}

	/**
	 * Ports upstream #895's `testTrailingBytesAreIgnored` scenario.
	 */
	@Test
	public void testTrailingBytesAreIgnored() throws IOException {
		byte[] bytes = hex("3A300000 01000000 0500 0000 10000000 0700 AABB");
		PersistentRoaringBitmap rb = fromBytes(bytes);
		assertEquals(PersistentRoaringBitmap.bitmapOf(327687), rb);
		assertEquals(18, rb.serializedSizeInBytes());
	}

	/**
	 * Ports upstream #895's `testPyRoaringBitMap124` scenario.
	 */
	@Test
	public void testPyRoaringBitMap124() throws IOException {
		// from CRoaring's toplevel_unit.c
		byte[] bytes =
				hex(
						"3a300000 01000000 0000 0900 10000000"
								+ "3200 3300 3400 3500 3600 3700 3800 3a00 3b00 3c00");
		PersistentRoaringBitmap rb = fromBytes(bytes);
		assertTrue(rb.validate());
		assertEquals(
				PersistentRoaringBitmap.bitmapOf(0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x3a, 0x3b, 0x3c), rb);
	}

	/**
	 * Ports upstream #895's `testIssue245` scenario.
	 */
	@Test
	public void testIssue245() throws IOException {
		// CRoaring issue 245: one run, then 2047 isolated values, then one more (a bitmap container)
		PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
		rb.add(0L, 9L);
		int offset = 10;
		for (int count = 1; count < 2048; count++, offset += 2) {
			rb.add(offset);
		}
		assertEquals(rb, fromBytes(toBytes(rb)));
		rb.runOptimize();
		assertEquals(rb, fromBytes(toBytes(rb)));
		offset += 2;
		rb.add(offset);
		assertEquals(rb, fromBytes(toBytes(rb)));
		rb.runOptimize();
		assertEquals(rb, fromBytes(toBytes(rb)));
		assertTrue(fromBytes(toBytes(rb)).validate());
	}
}
