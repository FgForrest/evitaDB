package io.evitadb.roaringbitmap.longlong;

import io.evitadb.roaringbitmap.art.*;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Tests for the Adaptive Radix Tree ({@link Art}) that backs the 64-bit RoaringBitmap high
 * bits. They verify that the tree grows through its node types (leaf, Node4, Node16, Node48,
 * Node256) as keys are inserted, that keys can be looked up by value, that each node type
 * survives stream and ByteBuffer serialization round-trips and shrinks back on removal, and
 * that the leaf-node iterator seeks correctly forward and backward, including across gaps.
 *
 * Ported from the upstream RoaringBitmap test-suite and retained as a regression guard for
 * the vendored `io.evitadb.roaringbitmap` module; the test bodies are kept byte-for-byte
 * identical to the upstream port to ease future syncs.
 */
@DisplayName("Adaptive Radix Tree (Art)")
public class ArtTest {

	private void assertArrayEquals(byte[] expected, byte[] actual) {
		Assertions.assertArrayEquals(expected, actual, Arrays.toString(actual));
	}

	private void insert5PrefixCommonBytesIntoArt(Art art, int keyNum) {
		byte b = 0;
		long containerIdx = 0;
		for (int i = 0; i < keyNum; i++, b++, containerIdx++) {
			byte[] key = new byte[]{1, 2, 3, 4, 5, b};
			art.insert(key, containerIdx);
		}
	}

	private void insert5PrefixCommonWithGapBytesIntoArt(Art art, int keyNum) {
		byte b = 0;
		long containerIdx = 0;
		for (int i = 0; i < keyNum; i++, b += (byte) 2, containerIdx++) {
			byte[] key = new byte[]{1, 2, 3, 4, 5, b};
			art.insert(key, containerIdx);
		}
	}

	@Nested
	@DisplayName("Node types (insert, lookup, serialization and shrink)")
	class NodeTypes {

		// one leaf node
		@DisplayName("A single leaf node is inserted, iterated, found and removed")
		@Test
		public void testLeafNode() {
			byte[] key1 = new byte[]{1, 2, 3, 4, 5, 0};
			Art art = new Art();
			insert5PrefixCommonBytesIntoArt(art, 1);
			LeafNodeIterator leafNodeIterator = art.leafNodeIterator(false, null);
			boolean hasNext = leafNodeIterator.hasNext();
			Assertions.assertTrue(hasNext);
			LeafNode leafNode = leafNodeIterator.next();
			assertArrayEquals(key1, leafNode.getKeyBytes());
			Assertions.assertEquals(0, leafNode.getContainerIdx());
			hasNext = leafNodeIterator.hasNext();
			Assertions.assertFalse(hasNext);
			art.remove(key1);
			Assertions.assertEquals(BranchNode.ILLEGAL_IDX, art.findByKey(key1));
		}

		// one node4 with two leaf nodes
		@DisplayName("Two keys form a Node4; iterated, found and shrunk to a leaf on removal")
		@Test
		public void testNode4() {
			byte[] key1 = new byte[]{1, 2, 3, 4, 5, 0};
			byte[] key2 = new byte[]{1, 2, 3, 4, 5, 1};
			Art art = new Art();
			insert5PrefixCommonBytesIntoArt(art, 2);
			LeafNodeIterator leafNodeIterator = art.leafNodeIterator(false, null);
			boolean hasNext = leafNodeIterator.hasNext();
			Assertions.assertTrue(hasNext);
			LeafNode leafNode = leafNodeIterator.next();
			assertArrayEquals(key1, leafNode.getKeyBytes());
			Assertions.assertEquals(0, leafNode.getContainerIdx());
			hasNext = leafNodeIterator.hasNext();
			Assertions.assertTrue(hasNext);
			leafNode = leafNodeIterator.next();
			assertArrayEquals(key2, leafNode.getKeyBytes());
			Assertions.assertEquals(1, leafNode.getContainerIdx());
			hasNext = leafNodeIterator.hasNext();
			Assertions.assertFalse(hasNext);
			art.remove(key1);
			// shrink to leaf node
			long containerIdx2 = art.findByKey(key2);
			Assertions.assertEquals(1, containerIdx2);
		}

		// 1 node16
		@DisplayName("Five keys form a Node16; it round-trips and shrinks to a Node4")
		@Test
		public void testNode16() throws Exception {
			byte[] key1 = new byte[]{1, 2, 3, 4, 5, 0};
			byte[] key2 = new byte[]{1, 2, 3, 4, 5, 1};
			byte[] key3 = new byte[]{1, 2, 3, 4, 5, 2};
			byte[] key4 = new byte[]{1, 2, 3, 4, 5, 3};
			byte[] key5 = new byte[]{1, 2, 3, 4, 5, 4};
			Art art = new Art();
			insert5PrefixCommonBytesIntoArt(art, 5);
			LeafNodeIterator leafNodeIterator = art.leafNodeIterator(false, null);
			boolean hasNext = leafNodeIterator.hasNext();
			Assertions.assertTrue(hasNext);
			LeafNode leafNode = leafNodeIterator.next();
			assertArrayEquals(key1, leafNode.getKeyBytes());
			Assertions.assertEquals(0, leafNode.getContainerIdx());
			hasNext = leafNodeIterator.hasNext();
			Assertions.assertTrue(hasNext);
			leafNode = leafNodeIterator.next();
			assertArrayEquals(key2, leafNode.getKeyBytes());
			Assertions.assertEquals(1, leafNode.getContainerIdx());
			hasNext = leafNodeIterator.hasNext();
			Assertions.assertTrue(hasNext);
			long containerIdx = art.findByKey(key4);
			Assertions.assertEquals(3, containerIdx);
			containerIdx = art.findByKey(key5);
			Assertions.assertEquals(4, containerIdx);
			// ser/deser
			int sizeInBytes = (int) art.serializeSizeInBytes();
			ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
			DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
			art.serializeArt(dataOutputStream);
			ByteArrayInputStream byteArrayInputStream =
				new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
			DataInputStream dataInputStream = new DataInputStream(byteArrayInputStream);
			Art deserArt = new Art();
			deserArt.deserializeArt(dataInputStream);
			Assertions.assertEquals(art.findByKey(key5), deserArt.findByKey(key5));

			ByteBuffer byteBuffer = ByteBuffer.allocate(sizeInBytes).order(ByteOrder.LITTLE_ENDIAN);
			art.serializeArt(byteBuffer);
			byteBuffer.flip();
			deserArt.deserializeArt(byteBuffer);
			Assertions.assertEquals(art.findByKey(key5), deserArt.findByKey(key5));

			art.remove(key5);
			art.remove(key4);
			// shrink to node4
			long containerIdx4 = art.findByKey(key3);
			Assertions.assertEquals(2, containerIdx4);
		}

		// node48
		@DisplayName("Seventeen keys form a Node48; it round-trips and shrinks to a Node16")
		@Test
		public void testNode48() throws Exception {
			Art art = new Art();
			insert5PrefixCommonBytesIntoArt(art, 17);
			byte[] key = new byte[]{1, 2, 3, 4, 5, 0};
			long containerIdx = art.findByKey(key);
			Assertions.assertEquals(0, containerIdx);
			key = new byte[]{1, 2, 3, 4, 5, 10};
			containerIdx = art.findByKey(key);
			Assertions.assertEquals(10, containerIdx);
			key = new byte[]{1, 2, 3, 4, 5, 12};
			containerIdx = art.findByKey(key);
			Assertions.assertEquals(12, containerIdx);
			byte[] key13 = new byte[]{1, 2, 3, 4, 5, 12};
			// ser/deser
			int sizeInBytes = (int) art.serializeSizeInBytes();
			ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(sizeInBytes);
			DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
			art.serializeArt(dataOutputStream);
			ByteArrayInputStream byteArrayInputStream =
				new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
			DataInputStream dataInputStream = new DataInputStream(byteArrayInputStream);
			Art deserArt = new Art();
			deserArt.deserializeArt(dataInputStream);
			Assertions.assertEquals(art.findByKey(key13), deserArt.findByKey(key13));

			ByteBuffer byteBuffer = ByteBuffer.allocate(sizeInBytes).order(ByteOrder.LITTLE_ENDIAN);
			art.serializeArt(byteBuffer);
			byteBuffer.flip();
			deserArt.deserializeArt(byteBuffer);
			Assertions.assertEquals(art.findByKey(key13), deserArt.findByKey(key13));
			Assertions.assertEquals(art.getKeySize(), deserArt.getKeySize());

			// shrink to node16
			for (int i = 0; i < 6; i++) {
				key13[5] = (byte) (12 + i);
				art.remove(key13);
			}
			byte[] key12 = new byte[]{1, 2, 3, 4, 5, 11};
			long containerIdx12 = art.findByKey(key12);
			Assertions.assertEquals(11, containerIdx12);
		}

		// node256
		@DisplayName("Fifty keys form a Node256; it round-trips and shrinks to a Node48")
		@Test
		public void testNode256() throws IOException {
			Art art = new Art();
			insert5PrefixCommonBytesIntoArt(art, 50);
			byte[] key = new byte[]{1, 2, 3, 4, 5, 0};
			long containerIdx = art.findByKey(key);
			Assertions.assertEquals(0, containerIdx);
			key = new byte[]{1, 2, 3, 4, 5, 10};
			containerIdx = art.findByKey(key);
			Assertions.assertEquals(10, containerIdx);
			key = new byte[]{1, 2, 3, 4, 5, 16};
			containerIdx = art.findByKey(key);
			Assertions.assertEquals(16, containerIdx);
			key = new byte[]{1, 2, 3, 4, 5, 36};
			containerIdx = art.findByKey(key);
			Assertions.assertEquals(36, containerIdx);
			key = new byte[]{1, 2, 3, 4, 5, 51};
			containerIdx = art.findByKey(key);
			Assertions.assertEquals(BranchNode.ILLEGAL_IDX, containerIdx);
			long sizeInBytesL = art.serializeSizeInBytes();
			int sizeInBytesI = (int) sizeInBytesL;
			ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(sizeInBytesI);
			DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
			art.serializeArt(dataOutputStream);
			Assertions.assertEquals(sizeInBytesI, byteArrayOutputStream.toByteArray().length);
			Art deserArt = new Art();
			DataInputStream dataInputStream =
				new DataInputStream(new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));
			deserArt.deserializeArt(dataInputStream);
			key = new byte[]{1, 2, 3, 4, 5, 36};
			containerIdx = deserArt.findByKey(key);
			Assertions.assertEquals(36, containerIdx);

			ByteBuffer byteBuffer = ByteBuffer.allocate(sizeInBytesI).order(ByteOrder.LITTLE_ENDIAN);
			art.serializeArt(byteBuffer);
			byteBuffer.flip();
			Art deserBBOne = new Art();
			deserBBOne.deserializeArt(byteBuffer);
			containerIdx = deserBBOne.findByKey(key);
			Assertions.assertEquals(36, containerIdx);

			// shrink to node48
			deserArt.remove(key);
			key = new byte[]{1, 2, 3, 4, 5, 10};
			containerIdx = deserArt.findByKey(key);
			Assertions.assertEquals(10, containerIdx);
		}
	}

	@Nested
	@DisplayName("Leaf-node iterator seeking")
	class LeafIteratorSeeking {

		@DisplayName("Forward leaf iterator seeks to the current, next and prior keys")
		@Test
		public void testNodeSeek() {
			byte[] key0 = new byte[]{1, 2, 3, 4, 5, 0};
			byte[] key1 = new byte[]{1, 2, 3, 4, 5, 1};
			byte[] key2 = new byte[]{1, 2, 3, 4, 5, 2};
			Art art = new Art();
			insert5PrefixCommonBytesIntoArt(art, 3);
			LeafNodeIterator lnIt = art.leafNodeIterator(false, null);
			boolean hasNext = lnIt.hasNext();

			// brand new iterator is pointing at first key "0"
			Assertions.assertTrue(hasNext);
			assertArrayEquals(key0, lnIt.peekNext().getKeyBytes());

			// seeking to the current spot is fine
			lnIt.seek(LongUtils.toLong(key0, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key0, lnIt.peekNext().getKeyBytes());

			// seeking to the next spot "1" is fine
			lnIt.seek(LongUtils.toLong(key1, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key1, lnIt.peekNext().getKeyBytes());

			// seeking to the next spot "2" is fine
			lnIt.seek(LongUtils.toLong(key2, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key2, lnIt.peekNext().getKeyBytes());
			assertArrayEquals(key2, lnIt.next().getKeyBytes());
			Assertions.assertFalse(lnIt.hasNext());

			// seeking to the prior "1" takes you there.. so this needs to be guarded against, in higher
			// levels
			// (as it is)
			lnIt.seek(LongUtils.toLong(key1, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key1, lnIt.peekNext().getKeyBytes());
		}

		@DisplayName("Reverse leaf iterator seeks to the current, next and prior keys")
		@Test
		public void testNodeSeekReverse() {
			byte[] key0 = new byte[]{1, 2, 3, 4, 5, 0};
			byte[] key1 = new byte[]{1, 2, 3, 4, 5, 1};
			byte[] key2 = new byte[]{1, 2, 3, 4, 5, 2};
			Art art = new Art();
			insert5PrefixCommonBytesIntoArt(art, 3);
			LeafNodeIterator lnIt = art.leafNodeIterator(true, null);
			boolean hasNext = lnIt.hasNext();

			// brand new iterator is pointing at first key "2"
			Assertions.assertTrue(hasNext);
			assertArrayEquals(key2, lnIt.peekNext().getKeyBytes());

			// seeking to the current spot is fine
			lnIt.seek(LongUtils.toLong(key2, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key2, lnIt.peekNext().getKeyBytes());

			// seeking to the next spot "1" is fine
			lnIt.seek(LongUtils.toLong(key1, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key1, lnIt.peekNext().getKeyBytes());

			// seeking to the next spot "0" is fine
			lnIt.seek(LongUtils.toLong(key0, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key0, lnIt.peekNext().getKeyBytes());
			assertArrayEquals(key0, lnIt.next().getKeyBytes());
			Assertions.assertFalse(lnIt.hasNext());

			// seeking to the prior "1" takes you there.. so this needs to be guarded against, in higher
			// levels
			// (as it is)
			lnIt.seek(LongUtils.toLong(key1, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key1, lnIt.peekNext().getKeyBytes());
		}

		@DisplayName("Forward leaf iterator seeks across gaps onto the next key")
		@Test
		public void testNodeSeekOverGaps() {
			byte[] start0 = new byte[]{0, 2, 3, 4, 5, 0};
			byte[] key0 = new byte[]{1, 2, 3, 4, 5, 0};
			byte[] gap1 = new byte[]{1, 2, 3, 4, 5, 1};
			byte[] key1 = new byte[]{1, 2, 3, 4, 5, 2};
			byte[] gap2 = new byte[]{1, 2, 3, 4, 5, 3};
			byte[] key2 = new byte[]{1, 2, 3, 4, 5, 4};
			byte[] end1 = new byte[]{2, 2, 3, 4, 5, 4};

			Art art = new Art();
			insert5PrefixCommonWithGapBytesIntoArt(art, 3);
			LeafNodeIterator lnIt = art.leafNodeIterator(false, null);
			boolean hasNext = lnIt.hasNext();

			// brand new iterator is pointing at first key "0"
			Assertions.assertTrue(hasNext);
			assertArrayEquals(key0, lnIt.peekNext().getKeyBytes());

			// seeking to the current spot is fine
			lnIt.seek(LongUtils.toLong(key0, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key0, lnIt.peekNext().getKeyBytes());

			// seeking to the next spot "1" is fine
			lnIt.seek(LongUtils.toLong(key1, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key1, lnIt.peekNext().getKeyBytes());

			// seeking to the next spot "2" is fine
			lnIt.seek(LongUtils.toLong(key2, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key2, lnIt.peekNext().getKeyBytes());
			assertArrayEquals(key2, lnIt.next().getKeyBytes());
			Assertions.assertFalse(lnIt.hasNext());

			// seeking to the prior "1" takes you there.. so this needs to be guarded against, in higher
			// levels
			// (as it is)
			lnIt.seek(LongUtils.toLong(key0, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key0, lnIt.peekNext().getKeyBytes());

			// NOW TO ENTER THE GAP ZONE... we should get "1" as it's after gap "1"
			lnIt.seek(LongUtils.toLong(gap1, (char) 0));

			Assertions.assertTrue(lnIt.hasNext());
			Assertions.assertFalse(BytesUtil.same(lnIt.peekNext().getKeyBytes(), key0));
			assertArrayEquals(key1, lnIt.peekNext().getKeyBytes());

			lnIt.seek(LongUtils.toLong(gap2, (char) 0));

			Assertions.assertTrue(lnIt.hasNext());
			Assertions.assertFalse(BytesUtil.same(lnIt.peekNext().getKeyBytes(), key0));
			Assertions.assertFalse(BytesUtil.same(lnIt.peekNext().getKeyBytes(), key1));
			assertArrayEquals(key2, lnIt.peekNext().getKeyBytes());

			// going to past the last value should "work", there just should be nothing to get next...
			lnIt.seek(LongUtils.toLong(end1, (char) 0));
			Assertions.assertFalse(lnIt.hasNext());

			// going the before the first should "return" the first
			lnIt.seek(LongUtils.toLong(start0, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key0, lnIt.peekNext().getKeyBytes());
		}

		@DisplayName("Reverse leaf iterator seeks across gaps onto the next key")
		@Test
		public void testNodeSeekOverGapsReverse() {
			byte[] start0 = new byte[]{2, 2, 3, 4, 5, 0};
			byte[] key0 = new byte[]{1, 2, 3, 4, 5, 4};
			byte[] gap1 = new byte[]{1, 2, 3, 4, 5, 3};
			byte[] key1 = new byte[]{1, 2, 3, 4, 5, 2};
			byte[] gap2 = new byte[]{1, 2, 3, 4, 5, 1};
			byte[] key2 = new byte[]{1, 2, 3, 4, 5, 0};
			byte[] end1 = new byte[]{0, 2, 3, 4, 5, 0};

			Art art = new Art();
			insert5PrefixCommonWithGapBytesIntoArt(art, 3);
			LeafNodeIterator lnIt = art.leafNodeIterator(true, null);
			boolean hasNext = lnIt.hasNext();

			// brand new iterator is pointing at first key "0"
			Assertions.assertTrue(hasNext);
			assertArrayEquals(key0, lnIt.peekNext().getKeyBytes());

			// seeking to the current spot is fine
			lnIt.seek(LongUtils.toLong(key0, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key0, lnIt.peekNext().getKeyBytes());

			// seeking to the next spot "1" is fine
			lnIt.seek(LongUtils.toLong(key1, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key1, lnIt.peekNext().getKeyBytes());

			// seeking to the next spot "2" is fine
			lnIt.seek(LongUtils.toLong(key2, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key2, lnIt.peekNext().getKeyBytes());
			assertArrayEquals(key2, lnIt.next().getKeyBytes());
			Assertions.assertFalse(lnIt.hasNext());

			// seeking to the prior "1" takes you there.. so this needs to be guarded against, in higher
			// levels
			// (as it is)
			lnIt.seek(LongUtils.toLong(key0, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			assertArrayEquals(key0, lnIt.peekNext().getKeyBytes());

			// NOW TO ENTER THE GAP ZONE... we should get "1" as it's after gap "1"
			lnIt.seek(LongUtils.toLong(gap1, (char) 0));

			Assertions.assertTrue(lnIt.hasNext());
			Assertions.assertFalse(BytesUtil.same(lnIt.peekNext().getKeyBytes(), key0));
			assertArrayEquals(key1, lnIt.peekNext().getKeyBytes());

			lnIt.seek(LongUtils.toLong(gap2, (char) 0));

			Assertions.assertTrue(lnIt.hasNext());
			Assertions.assertFalse(BytesUtil.same(lnIt.peekNext().getKeyBytes(), key0));
			Assertions.assertFalse(BytesUtil.same(lnIt.peekNext().getKeyBytes(), key1));
			assertArrayEquals(key2, lnIt.peekNext().getKeyBytes());

			// going to past the last value should "work", there just should be nothing to get next...
			lnIt.seek(LongUtils.toLong(end1, (char) 0));
			Assertions.assertFalse(lnIt.hasNext());

			// going the before the first should "return" the first
			lnIt.seek(LongUtils.toLong(start0, (char) 0));
			Assertions.assertTrue(lnIt.hasNext());
			Assertions.assertTrue(BytesUtil.same(lnIt.peekNext().getKeyBytes(), key0));
		}
	}

}
