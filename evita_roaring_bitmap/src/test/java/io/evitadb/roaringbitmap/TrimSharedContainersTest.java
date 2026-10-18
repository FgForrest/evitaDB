package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins trim's copy-on-write boundary: shared container storage must stay untouched while owned
 * containers and the bitmap's own parallel arrays still release unused capacity.
 */
public class TrimSharedContainersTest {

	private static final HeapLayout LAYOUT = new HeapLayout(4, 12, 16, 8);

	/**
	 * Creates a sparse container with enough unused capacity for trim to replace its backing array.
	 */
	private static ArrayContainer arrayWithSpareCapacity() {
		final ArrayContainer container = new ArrayContainer(32);
		container.add((char) 3);
		container.add((char) 17);
		container.add((char) 29);
		assertTrue(container.content.length > container.getCardinality());
		return container;
	}

	/**
	 * Creates one run in an oversized array; the heap footprint exposes its private array's capacity
	 * without reflection or changes to the container API.
	 */
	private static RunContainer runWithSpareCapacity() {
		final RunContainer container = new RunContainer(16);
		assertSame(container, container.iadd(100, 1000));
		assertTrue(container.getHeapSizeInBytes(LAYOUT) > new RunContainer(100, 1000).getHeapSizeInBytes(LAYOUT));
		return container;
	}

	/**
	 * Trimming either side of a clone must leave shared array and run storage untouched, even when
	 * the same bitmap also owns an array container that should actually shrink.
	 */
	@ParameterizedTest(name = "trimClone={0}")
	@ValueSource(booleans = {false, true})
	public void trimPreservesSharedStorageAndTrimsOwnedContainer(final boolean trimClone) {
		final PersistentRoaringBitmap original = new PersistentRoaringBitmap();
		final ArrayContainer sharedArray = arrayWithSpareCapacity();
		final RunContainer sharedRun = runWithSpareCapacity();
		original.append((char) 0, sharedArray);
		original.append((char) 1, sharedRun);
		final PersistentRoaringBitmap clone = original.clone();
		final PersistentRoaringBitmap target = trimClone ? clone : original;
		final PersistentRoaringBitmap peer = trimClone ? original : clone;
		final ArrayContainer ownedArray = arrayWithSpareCapacity();
		target.append((char) 2, ownedArray);

		assertTrue(target.isShared(0));
		assertTrue(target.isShared(1));
		assertFalse(target.isShared(2));
		assertSame(sharedArray, peer.getContainerAtIndex(0));
		assertSame(sharedRun, peer.getContainerAtIndex(1));
		assertTrue(target.highLowContainer.keys.length > target.highLowContainer.size());
		assertTrue(target.shared.length > target.highLowContainer.size());
		final char[] sharedContent = sharedArray.content;
		final long sharedRunBytes = sharedRun.getHeapSizeInBytes(LAYOUT);
		final char[] ownedContent = ownedArray.content;
		final char[] peerKeys = peer.highLowContainer.keys;
		final Container[] peerValues = peer.highLowContainer.values;
		final boolean[] peerShared = peer.shared;
		final int[] targetBefore = target.toArray();
		final int[] peerBefore = peer.toArray();

		target.trim();

		assertAll(
			() -> assertSame(sharedContent, sharedArray.content, "trim replaced a shared array container's backing array"),
			() -> assertEquals(sharedRunBytes, sharedRun.getHeapSizeInBytes(LAYOUT), "trim shrank a shared run container's backing array"),
			() -> assertSame(sharedArray, target.getContainerAtIndex(0)),
			() -> assertSame(sharedRun, target.getContainerAtIndex(1)),
			() -> assertSame(sharedArray, peer.getContainerAtIndex(0)),
			() -> assertSame(sharedRun, peer.getContainerAtIndex(1)),
			() -> assertNotSame(ownedContent, ownedArray.content, "owned container storage should be trimmed"),
			() -> assertEquals(ownedArray.getCardinality(), ownedArray.content.length),
			() -> assertSame(ownedArray, target.getContainerAtIndex(2)),
			() -> assertArrayEquals(targetBefore, target.toArray(), "trim changed the target's values"),
			() -> assertArrayEquals(peerBefore, peer.toArray(), "trim changed the peer's values"),
			() -> assertEquals(target.highLowContainer.size(), target.highLowContainer.keys.length),
			() -> assertEquals(target.highLowContainer.size(), target.highLowContainer.values.length),
			() -> assertArrayEquals(new boolean[]{true, true, false}, target.shared),
			() -> assertSame(peerKeys, peer.highLowContainer.keys),
			() -> assertSame(peerValues, peer.highLowContainer.values),
			() -> assertSame(peerShared, peer.shared)
		);
	}

	/**
	 * Static builders can leave flags shorter than the live container prefix; those missing flags
	 * mean owned, so both array and run containers beyond the flag array must still be trimmed.
	 */
	@Test
	public void trimTreatsSlotsBeyondSharedLengthAsOwned() {
		final PersistentRoaringBitmap original = new PersistentRoaringBitmap();
		for (int key = 0; key < 6; key++) {
			original.append((char) key, arrayWithSpareCapacity());
		}
		final PersistentRoaringBitmap target = PersistentRoaringBitmap.addOffset(original, 0);
		assertTrue(target.shared.length < target.highLowContainer.size());
		final int arraySlot = target.shared.length;
		final int runSlot = arraySlot + 1;
		final ArrayContainer ownedArray = arrayWithSpareCapacity();
		final RunContainer ownedRun = runWithSpareCapacity();
		target.highLowContainer.setContainerAtIndex(arraySlot, ownedArray);
		target.highLowContainer.setContainerAtIndex(runSlot, ownedRun);
		final char[] ownedContent = ownedArray.content;
		final long runBytesBefore = ownedRun.getHeapSizeInBytes(LAYOUT);
		final int[] before = target.toArray();
		final int[] originalBefore = original.toArray();

		target.trim();

		assertNotSame(ownedContent, ownedArray.content);
		assertEquals(ownedArray.getCardinality(), ownedArray.content.length);
		assertTrue(ownedRun.getHeapSizeInBytes(LAYOUT) < runBytesBefore, "owned run storage should shrink");
		assertEquals(new RunContainer(100, 1000).getHeapSizeInBytes(LAYOUT), ownedRun.getHeapSizeInBytes(LAYOUT));
		assertArrayEquals(before, target.toArray());
		assertArrayEquals(originalBefore, original.toArray());
		assertEquals(target.highLowContainer.size(), target.highLowContainer.keys.length);
		assertEquals(target.highLowContainer.size(), target.highLowContainer.values.length);
		assertArrayEquals(new boolean[target.highLowContainer.size()], target.shared);
	}
}
