package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
