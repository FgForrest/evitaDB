package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

public class TestPersistentRoaringBitmap {

  // -----------------------------------------------------------------------
  // Correctness: static ops produce same results as PersistentRoaringBitmap
  // -----------------------------------------------------------------------

  @Test
  public void orMatchesMutable() {
    PersistentRoaringBitmap a = buildBitmap(0, 100, 1);
    PersistentRoaringBitmap b = buildBitmap(50, 150, 1);
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.or(a, b);

    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);

    assertArrayEquals(expected.toArray(), result.toArray());
  }

  @Test
  public void andMatchesMutable() {
    PersistentRoaringBitmap a = buildBitmap(0, 100, 1);
    PersistentRoaringBitmap b = buildBitmap(50, 150, 1);
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.and(a, b);

    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);
    PersistentRoaringBitmap result = PersistentRoaringBitmap.and(ca, cb);

    assertArrayEquals(expected.toArray(), result.toArray());
  }

  @Test
  public void andNotMatchesMutable() {
    PersistentRoaringBitmap a = buildBitmap(0, 100, 1);
    PersistentRoaringBitmap b = buildBitmap(50, 150, 1);
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.andNot(a, b);

    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);
    PersistentRoaringBitmap result = PersistentRoaringBitmap.andNot(ca, cb);

    assertArrayEquals(expected.toArray(), result.toArray());
  }

  @Test
  public void xorMatchesMutable() {
    PersistentRoaringBitmap a = buildBitmap(0, 100, 1);
    PersistentRoaringBitmap b = buildBitmap(50, 150, 1);
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.xor(a, b);

    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);
    PersistentRoaringBitmap result = PersistentRoaringBitmap.xor(ca, cb);

    assertArrayEquals(expected.toArray(), result.toArray());
  }

  @Test
  public void orMatchesMutableMultiContainer() {
    PersistentRoaringBitmap a = buildMultiContainerBitmap(0, 10, 42);
    PersistentRoaringBitmap b = buildMultiContainerBitmap(5, 15, 99);
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.or(a, b);

    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);

    assertArrayEquals(expected.toArray(), result.toArray());
  }

  @Test
  public void andMatchesMutableMultiContainer() {
    PersistentRoaringBitmap a = buildMultiContainerBitmap(0, 10, 42);
    PersistentRoaringBitmap b = buildMultiContainerBitmap(5, 15, 99);
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.and(a, b);

    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);
    PersistentRoaringBitmap result = PersistentRoaringBitmap.and(ca, cb);

    assertArrayEquals(expected.toArray(), result.toArray());
  }

  @Test
  public void andNotMatchesMutableMultiContainer() {
    PersistentRoaringBitmap a = buildMultiContainerBitmap(0, 10, 42);
    PersistentRoaringBitmap b = buildMultiContainerBitmap(5, 15, 99);
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.andNot(a, b);

    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);
    PersistentRoaringBitmap result = PersistentRoaringBitmap.andNot(ca, cb);

    assertArrayEquals(expected.toArray(), result.toArray());
  }

  @Test
  public void xorMatchesMutableMultiContainer() {
    PersistentRoaringBitmap a = buildMultiContainerBitmap(0, 10, 42);
    PersistentRoaringBitmap b = buildMultiContainerBitmap(5, 15, 99);
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.xor(a, b);

    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);
    PersistentRoaringBitmap result = PersistentRoaringBitmap.xor(ca, cb);

    assertArrayEquals(expected.toArray(), result.toArray());
  }

  // -----------------------------------------------------------------------
  // Structural sharing: non-overlapping containers are == (same object)
  // -----------------------------------------------------------------------

  @Test
  public void orSharesNonOverlappingContainers() {
    // A has keys 0..4, B has keys 5..9 — completely disjoint
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 10, 99));
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);

    for (int i = 0; i < 5; i++) {
      assertSame(ca.getContainerAtIndex(i), result.getContainerAtIndex(i),
          "Container at index " + i + " should be shared from ca");
    }
    for (int i = 0; i < 5; i++) {
      assertSame(cb.getContainerAtIndex(i), result.getContainerAtIndex(5 + i),
          "Container at index " + (5 + i) + " should be shared from cb");
    }
  }

  @Test
  public void orDoesNotShareOverlappingContainers() {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        PersistentRoaringBitmap.bitmapOf(1));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        PersistentRoaringBitmap.bitmapOf(2));
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);

    assertNotSame(ca.getContainerAtIndex(0), result.getContainerAtIndex(0));
    assertNotSame(cb.getContainerAtIndex(0), result.getContainerAtIndex(0));
  }

  @Test
  public void andNotSharesNonOverlappingFromX1() {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 10, 42));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 8, 99));
    PersistentRoaringBitmap result = PersistentRoaringBitmap.andNot(ca, cb);

    // Keys 0..4 from ca are non-overlapping and should be shared
    for (int i = 0; i < 5; i++) {
      assertSame(ca.getContainerAtIndex(i), result.getContainerAtIndex(i),
          "Container at index " + i + " should be shared from ca");
    }
  }

  @Test
  public void xorSharesNonOverlappingContainers() {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 10, 99));
    PersistentRoaringBitmap result = PersistentRoaringBitmap.xor(ca, cb);

    for (int i = 0; i < 5; i++) {
      assertSame(ca.getContainerAtIndex(i), result.getContainerAtIndex(i));
    }
    for (int i = 0; i < 5; i++) {
      assertSame(cb.getContainerAtIndex(i), result.getContainerAtIndex(5 + i));
    }
  }

  // -----------------------------------------------------------------------
  // COW safety: mutations after sharing don't corrupt other bitmaps
  // -----------------------------------------------------------------------

  @Test
  public void addAfterOrDoesNotCorruptInputs() {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 10, 99));
    int[] originalA = ca.toArray();
    int[] originalB = cb.toArray();

    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);

    // Mutate the result — add a value in the first container's key space
    result.add(42);
    result.add(1 << 16 | 42);  // key 1

    // Inputs must be unchanged
    assertArrayEquals(originalA, ca.toArray(), "ca was corrupted by result mutation");
    assertArrayEquals(originalB, cb.toArray(), "cb was corrupted by result mutation");
  }

  @Test
  public void removeAfterOrDoesNotCorruptInputs() {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 10, 99));
    int[] originalA = ca.toArray();

    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);

    // Remove a value that came from ca (shared container)
    int firstVal = originalA[0];
    result.remove(firstVal);

    assertArrayEquals(originalA, ca.toArray(), "ca was corrupted by result remove");
  }

  @Test
  public void flipAfterOrDoesNotCorruptInputs() {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 10, 99));
    int[] originalA = ca.toArray();

    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);

    // Flip a value in a shared container
    int firstVal = originalA[0];
    result.flip(firstVal);

    assertArrayEquals(originalA, ca.toArray(), "ca was corrupted by result flip");
  }

  @Test
  public void mutateInputAfterOrDoesNotCorruptResult() {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 10, 99));

    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);
    int[] resultBefore = result.toArray();

    // Mutate input ca
    ca.add(42);
    ca.remove(ca.toArray()[1]);

    assertArrayEquals(resultBefore, result.toArray(), "result was corrupted by input mutation");
  }

  @Test
  public void checkedAddRemoveRespectCow() {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        PersistentRoaringBitmap.bitmapOf(1, 2, 3));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        PersistentRoaringBitmap.bitmapOf(100, 200, 300));

    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);
    int[] originalA = ca.toArray();

    // checkedAdd/checkedRemove on the result
    assertTrue(result.checkedAdd(4));
    assertFalse(result.checkedAdd(4)); // already present
    assertTrue(result.checkedRemove(1));
    assertFalse(result.checkedRemove(999)); // not present

    assertArrayEquals(originalA, ca.toArray(), "ca was corrupted");
  }

  // -----------------------------------------------------------------------
  // In-place binary ops: precise COW behavior
  // -----------------------------------------------------------------------

  @Test
  public void inPlaceOrPreservesNonOverlappingSharing() {
    // Build result from static OR — all containers are shared
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));   // keys 0..4
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 10, 99));  // keys 5..9
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);

    // Capture container refs before in-place OR
    Container[] before = new Container[result.getContainerCount()];
    for (int i = 0; i < before.length; i++) {
      before[i] = result.getContainerAtIndex(i);
    }

    // In-place OR with bitmap that overlaps only key 0
    PersistentRoaringBitmap extra = new PersistentRoaringBitmap();
    extra.add(0 << 16 | 42);
    result.or(extra);

    // Keys 1..9 were not touched and should still be the same objects
    for (int i = 1; i < before.length; i++) {
      assertSame(before[i], result.getContainerAtIndex(i),
          "Container at index " + i + " should be unchanged");
    }
    // Key 0 was mutated — should be a different object (cloned by COW)
    assertNotSame(before[0], result.getContainerAtIndex(0),
        "Overlapping container should have been cloned");
  }

  @Test
  public void inPlaceOrSharesFromV2() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 42));   // keys 0..2
    PersistentRoaringBitmap x2 = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(3, 6, 99));   // keys 3..5, fully disjoint

    result.or(x2);

    // Non-overlapping containers from x2 should be shared (same reference)
    assertEquals(6, result.getContainerCount());
    for (int i = 0; i < 3; i++) {
      assertSame(x2.getContainerAtIndex(i), result.getContainerAtIndex(3 + i),
          "Container from x2 at index " + i + " should be shared into result");
    }
  }

  @Test
  public void inPlaceOrSharesFromDirectlyBuiltBitmap() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 42));   // keys 0..2
    PersistentRoaringBitmap x2 = buildMultiContainerBitmap(3, 6, 99);  // keys 3..5

    result.or(x2);

    // In the single-class model every bitmap is copy-on-write, so non-overlapping
    // containers from x2 are shared by reference regardless of how x2 was built.
    assertEquals(6, result.getContainerCount());
    for (int i = 0; i < 3; i++) {
      assertSame(x2.getContainerAtIndex(i),
          result.getContainerAtIndex(3 + i),
          "Container from x2 at index " + i + " should be shared into result");
    }
  }

  @Test
  public void inPlaceOrDoesNotCorruptV2() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 42));
    PersistentRoaringBitmap x2 = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(3, 6, 99));
    int[] x2Before = x2.toArray();

    result.or(x2);

    // Mutate result in x2's key range
    result.add(3 << 16 | 42);
    result.add(4 << 16 | 42);

    assertArrayEquals(x2Before, x2.toArray(), "x2 was corrupted by result mutation after or");
  }

  @Test
  public void inPlaceOrCorrectness() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 10, 42));
    PersistentRoaringBitmap x2 = buildMultiContainerBitmap(5, 15, 99);
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.or(
        buildMultiContainerBitmap(0, 10, 42), x2);

    result.or(x2);

    assertArrayEquals(expected.toArray(), result.toArray());
  }

  @Test
  public void inPlaceAndDropsNonOverlapping() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 10, 42));  // keys 0..9
    PersistentRoaringBitmap x2 = buildMultiContainerBitmap(3, 7, 99);  // keys 3..6

    result.and(x2);

    // Only overlapping keys 3..6 survive
    assertTrue(result.getContainerCount() <= 4);
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.and(
        buildMultiContainerBitmap(0, 10, 42), x2);
    assertArrayEquals(expected.toArray(), result.toArray());
  }

  @Test
  public void inPlaceAndWithSharedContainersDoesNotCorrupt() {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 10, 99));
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);
    int[] caOriginal = ca.toArray();

    // In-place AND on shared result
    PersistentRoaringBitmap mask = buildMultiContainerBitmap(0, 3, 42);
    result.and(mask);

    assertArrayEquals(caOriginal, ca.toArray(), "ca was corrupted by in-place and");
  }

  @Test
  public void inPlaceAndCorrectness() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 10, 42));
    PersistentRoaringBitmap x2 = buildMultiContainerBitmap(5, 15, 99);
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.and(
        buildMultiContainerBitmap(0, 10, 42), x2);

    result.and(x2);

    assertArrayEquals(expected.toArray(), result.toArray());
  }

  @Test
  public void inPlaceAndNotPreservesNonOverlapping() {
    // Build result from static OR — all containers shared
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 10, 42));  // keys 0..9
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        new PersistentRoaringBitmap());
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);

    // Capture container refs before andNot
    Container[] before = new Container[10];
    for (int i = 0; i < 10; i++) {
      before[i] = result.getContainerAtIndex(i);
    }

    // andNot with bitmap covering keys 5..7
    PersistentRoaringBitmap mask = buildMultiContainerBitmap(5, 8, 99);
    result.and(buildMultiContainerBitmap(0, 10, 42)); // force clone of overlapping
    // reset for the real test
    result = PersistentRoaringBitmap.or(ca, cb);
    for (int i = 0; i < 10; i++) {
      before[i] = result.getContainerAtIndex(i);
    }
    result.andNot(mask);

    // Keys 0..4 are non-overlapping — should still be same objects
    for (int i = 0; i < 5; i++) {
      assertSame(before[i], result.getContainerAtIndex(i),
          "Non-overlapping container at index " + i + " should be preserved");
    }
  }

  @Test
  public void inPlaceAndNotDoesNotCorruptInputs() {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 10, 99));
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);
    int[] caOriginal = ca.toArray();

    PersistentRoaringBitmap mask = buildMultiContainerBitmap(0, 3, 42);
    result.andNot(mask);

    assertArrayEquals(caOriginal, ca.toArray(), "ca was corrupted by in-place andNot");
  }

  @Test
  public void inPlaceAndNotCorrectness() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 10, 42));
    PersistentRoaringBitmap x2 = buildMultiContainerBitmap(5, 15, 99);
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.andNot(
        buildMultiContainerBitmap(0, 10, 42), x2);

    result.andNot(x2);

    assertArrayEquals(expected.toArray(), result.toArray());
  }

  @Test
  public void inPlaceXorSharesFromV2() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 42));   // keys 0..2
    PersistentRoaringBitmap x2 = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(3, 6, 99));   // keys 3..5, fully disjoint

    result.xor(x2);

    // Non-overlapping containers from x2 should be shared
    assertEquals(6, result.getContainerCount());
    for (int i = 0; i < 3; i++) {
      assertSame(x2.getContainerAtIndex(i), result.getContainerAtIndex(3 + i),
          "Container from x2 at index " + i + " should be shared into result");
    }
  }

  @Test
  public void inPlaceXorSharesFromDirectlyBuiltBitmap() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 42));   // keys 0..2
    PersistentRoaringBitmap x2 = buildMultiContainerBitmap(3, 6, 99);  // keys 3..5

    result.xor(x2);

    // In the single-class model every bitmap is copy-on-write, so non-overlapping
    // containers from x2 are shared by reference regardless of how x2 was built.
    assertEquals(6, result.getContainerCount());
    for (int i = 0; i < 3; i++) {
      assertSame(x2.getContainerAtIndex(i),
          result.getContainerAtIndex(3 + i),
          "Container from x2 at index " + i + " should be shared into result");
    }
  }

  @Test
  public void inPlaceXorDoesNotCorruptV2() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 42));
    PersistentRoaringBitmap x2 = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(3, 6, 99));
    int[] x2Before = x2.toArray();

    result.xor(x2);
    result.add(3 << 16 | 42);

    assertArrayEquals(x2Before, x2.toArray(), "x2 was corrupted by result mutation after xor");
  }

  @Test
  public void inPlaceXorCorrectness() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 10, 42));
    PersistentRoaringBitmap x2 = buildMultiContainerBitmap(5, 15, 99);
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.xor(
        buildMultiContainerBitmap(0, 10, 42), x2);

    result.xor(x2);

    assertArrayEquals(expected.toArray(), result.toArray());
  }

  @Test
  public void inPlaceXorPreservesNonOverlappingSharing() {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 10, 99));
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);

    Container[] before = new Container[result.getContainerCount()];
    for (int i = 0; i < before.length; i++) {
      before[i] = result.getContainerAtIndex(i);
    }

    // XOR with bitmap overlapping only key 0
    PersistentRoaringBitmap extra = new PersistentRoaringBitmap();
    extra.add(0 << 16 | 42);
    result.xor(extra);

    // Keys 1..9 were not touched — same objects
    for (int i = 1; i < before.length; i++) {
      assertSame(before[i], result.getContainerAtIndex(i),
          "Container at index " + i + " should be unchanged");
    }
  }

  // -----------------------------------------------------------------------
  // Insert/remove bookkeeping for shared array
  // -----------------------------------------------------------------------

  @Test
  public void addNewKeyShiftsSharedArrayCorrectly() {
    // Build a result from OR — all containers are shared
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 42));   // keys 0, 1, 2
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(4, 6, 99));   // keys 4, 5
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);

    // All 5 containers should be shared
    assertEquals(5, result.getContainerCount());
    for (int i = 0; i < 5; i++) {
      assertTrue(result.isShared(i), "Container " + i + " should be shared");
    }

    // Insert a value with key 3 (between existing keys 2 and 4)
    result.add(3 << 16 | 42);

    // Now 6 containers. The new one at index 3 should NOT be shared.
    assertEquals(6, result.getContainerCount());
    assertFalse(result.isShared(3), "Newly inserted container should not be shared");

    // Containers around it should still be shared (they shifted)
    assertTrue(result.isShared(0), "Container 0 should still be shared");
    assertTrue(result.isShared(2), "Container 2 should still be shared");
    assertTrue(result.isShared(4), "Container 4 (shifted from 3) should still be shared");
  }

  @Test
  public void removeContainerShiftsSharedArrayCorrectly() {
    // Build a bitmap with one element per container
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    rb.add(0 << 16 | 1);   // key 0
    rb.add(1 << 16 | 1);   // key 1
    rb.add(2 << 16 | 1);   // key 2

    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        new PersistentRoaringBitmap()); // empty

    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);

    // Remove the only element in key 1's container — this should remove the container
    result.remove(1 << 16 | 1);

    assertEquals(2, result.getContainerCount());
    // Remaining containers (keys 0 and 2) should still be shared
    assertTrue(result.isShared(0), "Container 0 should still be shared");
    assertTrue(result.isShared(1), "Container 1 (was 2) should still be shared");
  }

  // -----------------------------------------------------------------------
  // Edge cases
  // -----------------------------------------------------------------------

  @Test
  public void orWithEmptyBitmaps() {
    PersistentRoaringBitmap empty = PersistentRoaringBitmap.fromBitmap(
        new PersistentRoaringBitmap());
    PersistentRoaringBitmap nonEmpty = PersistentRoaringBitmap.fromBitmap(
        PersistentRoaringBitmap.bitmapOf(1, 2, 3));

    assertEquals(nonEmpty, PersistentRoaringBitmap.or(empty, nonEmpty));
    assertEquals(nonEmpty, PersistentRoaringBitmap.or(nonEmpty, empty));

    PersistentRoaringBitmap bothEmpty = PersistentRoaringBitmap.or(empty, empty);
    assertTrue(bothEmpty.isEmpty());
  }

  @Test
  public void andWithEmptyBitmaps() {
    PersistentRoaringBitmap empty = PersistentRoaringBitmap.fromBitmap(
        new PersistentRoaringBitmap());
    PersistentRoaringBitmap nonEmpty = PersistentRoaringBitmap.fromBitmap(
        PersistentRoaringBitmap.bitmapOf(1, 2, 3));

    assertTrue(PersistentRoaringBitmap.and(empty, nonEmpty).isEmpty());
    assertTrue(PersistentRoaringBitmap.and(nonEmpty, empty).isEmpty());
  }

  @Test
  public void fullyOverlappingOr() {
    PersistentRoaringBitmap data = PersistentRoaringBitmap.bitmapOf(1, 2, 3, 100000, 200000);
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(data);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(data);
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);

    assertArrayEquals(data.toArray(), result.toArray());
  }

  @Test
  public void fullyDisjointAnd() {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 10, 99));

    assertTrue(PersistentRoaringBitmap.and(ca, cb).isEmpty());
  }

  @Test
  public void singleContainerBitmap() {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        PersistentRoaringBitmap.bitmapOf(42));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        PersistentRoaringBitmap.bitmapOf(42, 100));

    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);
    assertEquals(2, result.getCardinality());
    assertTrue(result.contains(42));
    assertTrue(result.contains(100));
  }

  // -----------------------------------------------------------------------
  // clone() behavior
  // -----------------------------------------------------------------------

  @Test
  public void cloneSharesContainers() {
    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap cloned = original.clone();

    assertEquals(original, cloned);
    assertNotSame(original, cloned);

    // Containers should be shared (same reference)
    for (int i = 0; i < original.getContainerCount(); i++) {
      assertSame(original.getContainerAtIndex(i), cloned.getContainerAtIndex(i),
          "Container at index " + i + " should be shared between clone and original");
    }
  }

  @Test
  public void mutatingCloneDoesNotAffectOriginal() {
    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(
        PersistentRoaringBitmap.bitmapOf(1, 2, 3));
    int[] originalBefore = original.toArray();

    PersistentRoaringBitmap cloned = original.clone();
    cloned.add(4);
    cloned.remove(1);

    assertArrayEquals(originalBefore, original.toArray(),
        "Original was corrupted by clone mutation");
  }

  @Test
  public void mutatingOriginalDoesNotAffectClone() {
    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(
        PersistentRoaringBitmap.bitmapOf(1, 2, 3));
    PersistentRoaringBitmap cloned = original.clone();
    int[] clonedBefore = cloned.toArray();

    original.add(4);
    original.remove(1);

    assertArrayEquals(clonedBefore, cloned.toArray(),
        "Clone was corrupted by original mutation");
  }

  // -----------------------------------------------------------------------
  // Serialization
  // -----------------------------------------------------------------------

  @Test
  public void serializeDeserializeRoundTrip() throws IOException {
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    cow.serialize(new DataOutputStream(baos));

    // Deserialize as plain PersistentRoaringBitmap (the wire format is the same)
    PersistentRoaringBitmap restored = new PersistentRoaringBitmap();
    restored.deserialize(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));

    assertArrayEquals(cow.toArray(), restored.toArray());
  }

  // -----------------------------------------------------------------------
  // fromBitmap isolation
  // -----------------------------------------------------------------------

  @Test
  public void fromBitmapIsIndependent() {
    PersistentRoaringBitmap source = PersistentRoaringBitmap.bitmapOf(1, 2, 3);
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(source);

    source.add(4);
    source.remove(1);

    assertTrue(cow.contains(1));
    assertFalse(cow.contains(4));
    assertEquals(3, cow.getCardinality());
  }

  // -----------------------------------------------------------------------
  // Range/bulk mutation after sharing
  // -----------------------------------------------------------------------

  @Test
  public void rangeMutationAfterSharingDoesNotCorruptInputs() {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 10, 99));
    int[] originalA = ca.toArray();

    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);
    result.add(0L, 100L); // range add

    assertArrayEquals(originalA, ca.toArray(), "ca was corrupted by range add on result");
  }

  @Test
  public void inPlaceOrAfterSharingDoesNotCorruptInputs() {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 10, 99));
    int[] originalA = ca.toArray();

    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);
    PersistentRoaringBitmap extra = PersistentRoaringBitmap.bitmapOf(1, 2, 3, 4, 5);
    result.or(extra); // in-place OR

    assertArrayEquals(originalA, ca.toArray(), "ca was corrupted by in-place or on result");
  }

  // -----------------------------------------------------------------------
  // Equals with PersistentRoaringBitmap (inherited)
  // -----------------------------------------------------------------------

  @Test
  public void equalsWithRoaringBitmap() {
    PersistentRoaringBitmap rb = PersistentRoaringBitmap.bitmapOf(1, 2, 3);
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(rb);
    assertEquals(cow, rb);
    assertEquals(rb, cow);
  }

  // -----------------------------------------------------------------------
  // Precise COW: range add/remove/flip only clone touched containers
  // -----------------------------------------------------------------------

  @Test
  public void rangeAddOnlyClonesTouchedContainers() {
    // 10-key shared bitmap, range add touches keys 3-5
    PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 10, 42));
    PersistentRoaringBitmap result = source.clone();

    Container[] before = new Container[10];
    for (int i = 0; i < 10; i++) {
      before[i] = result.getContainerAtIndex(i);
    }

    // Range add spanning keys 3..5
    long start = 3L << 16;
    long end = (5L << 16) + 100;
    result.add(start, end);

    // Keys 0-2 and 6-9 should still be the same object (not cloned)
    for (int i = 0; i < 3; i++) {
      assertSame(before[i], result.getContainerAtIndex(i),
          "Container at key " + i + " should NOT have been cloned");
    }
    for (int i = 6; i < 10; i++) {
      assertSame(before[i], result.getContainerAtIndex(i),
          "Container at key " + i + " should NOT have been cloned");
    }

    // Keys 3-5 were touched and should have been cloned
    for (int i = 3; i <= 5; i++) {
      assertNotSame(before[i], result.getContainerAtIndex(i),
          "Container at key " + i + " should have been cloned");
    }

    // Source is not corrupted
    PersistentRoaringBitmap expected = buildMultiContainerBitmap(0, 10, 42);
    assertArrayEquals(expected.toArray(), source.toArray(), "source was corrupted");
  }

  @Test
  public void rangeAddMatchesRoaringBitmap() {
    PersistentRoaringBitmap rb = buildMultiContainerBitmap(0, 10, 42);
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap result = cow.clone();

    long start = 3L << 16;
    long end = (5L << 16) + 100;
    rb.add(start, end);
    result.add(start, end);

    assertArrayEquals(rb.toArray(), result.toArray());
  }

  @Test
  public void rangeRemoveOnlyClonesTouchedContainers() {
    PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 10, 42));
    PersistentRoaringBitmap result = source.clone();

    Container[] before = new Container[10];
    for (int i = 0; i < 10; i++) {
      before[i] = result.getContainerAtIndex(i);
    }

    // Range remove only in key 2
    long start = 2L << 16;
    long end = (2L << 16) + 100;
    result.remove(start, end);

    // Keys 0-1 and 3-9 should still be the same object
    for (int i = 0; i < 2; i++) {
      assertSame(before[i], result.getContainerAtIndex(i),
          "Container at key " + i + " should NOT have been cloned");
    }
    for (int i = 3; i < 10; i++) {
      assertSame(before[i], result.getContainerAtIndex(i),
          "Container at key " + i + " should NOT have been cloned");
    }

    // Source is not corrupted
    PersistentRoaringBitmap expected = buildMultiContainerBitmap(0, 10, 42);
    assertArrayEquals(expected.toArray(), source.toArray(), "source was corrupted");
  }

  @Test
  public void rangeRemoveMatchesRoaringBitmap() {
    PersistentRoaringBitmap rb = buildMultiContainerBitmap(0, 10, 42);
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap result = cow.clone();

    long start = 2L << 16;
    long end = (7L << 16) + 500;
    rb.remove(start, end);
    result.remove(start, end);

    assertArrayEquals(rb.toArray(), result.toArray());
  }

  @Test
  public void rangeRemoveMultiContainerMatchesRoaringBitmap() {
    PersistentRoaringBitmap rb = buildMultiContainerBitmap(0, 10, 42);
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap result = cow.clone();

    // Remove across multiple containers (keys 3-7)
    long start = (3L << 16) + 100;
    long end = (7L << 16) + 200;
    rb.remove(start, end);
    result.remove(start, end);

    assertArrayEquals(rb.toArray(), result.toArray());
  }

  @Test
  public void rangeFlipOnlyClonesTouchedContainers() {
    PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 10, 42));
    PersistentRoaringBitmap result = source.clone();

    Container[] before = new Container[10];
    for (int i = 0; i < 10; i++) {
      before[i] = result.getContainerAtIndex(i);
    }

    // Flip spanning keys 4-6
    long start = 4L << 16;
    long end = (6L << 16) + 100;
    result.flip(start, end);

    // Keys 0-3 and 7-9 should still be the same object
    for (int i = 0; i < 4; i++) {
      assertSame(before[i], result.getContainerAtIndex(i),
          "Container at key " + i + " should NOT have been cloned");
    }

    // Source is not corrupted
    PersistentRoaringBitmap expected = buildMultiContainerBitmap(0, 10, 42);
    assertArrayEquals(expected.toArray(), source.toArray(), "source was corrupted");
  }

  @Test
  public void rangeFlipMatchesRoaringBitmap() {
    PersistentRoaringBitmap rb = buildMultiContainerBitmap(0, 10, 42);
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap result = cow.clone();

    long start = 4L << 16;
    long end = (6L << 16) + 100;
    rb.flip(start, end);
    result.flip(start, end);

    assertArrayEquals(rb.toArray(), result.toArray());
  }

  // -----------------------------------------------------------------------
  // addN precise COW
  // -----------------------------------------------------------------------

  @Test
  public void addNCowCorrectness() {
    PersistentRoaringBitmap rb = buildMultiContainerBitmap(0, 5, 42);
    PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap result = source.clone();
    int[] sourceOriginal = source.toArray();

    // Add values across two keys (key 1 and key 3)
    int[] dat = new int[]{
        (1 << 16) | 42, (1 << 16) | 43, (1 << 16) | 44,
        (3 << 16) | 100, (3 << 16) | 101
    };
    result.addN(dat, 0, dat.length);
    rb.addN(dat, 0, dat.length);

    assertArrayEquals(rb.toArray(), result.toArray(), "addN result differs from PersistentRoaringBitmap");
    assertArrayEquals(sourceOriginal, source.toArray(), "source corrupted by addN");
  }

  @Test
  public void addNNewKeyInsertion() {
    // Start with keys 0 and 2, add values in key 1 (gap)
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    rb.add(0 << 16 | 1);
    rb.add(2 << 16 | 1);
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap result = cow.clone();

    int[] dat = new int[]{(1 << 16) | 50, (1 << 16) | 51};
    result.addN(dat, 0, dat.length);

    assertEquals(3, result.getContainerCount());
    assertTrue(result.contains((1 << 16) | 50));
    assertTrue(result.contains((1 << 16) | 51));
  }

  // -----------------------------------------------------------------------
  // orNot precise COW
  // -----------------------------------------------------------------------

  @Test
  public void orNotCowCorrectness() {
    PersistentRoaringBitmap rb = buildMultiContainerBitmap(0, 5, 42);
    PersistentRoaringBitmap other = buildMultiContainerBitmap(2, 7, 99);
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap source = cow.clone();
    int[] sourceOriginal = source.toArray();

    long rangeEnd = 5L << 16;
    PersistentRoaringBitmap rbCopy = rb.clone();
    rbCopy.orNot(other, rangeEnd);
    cow.orNot(other, rangeEnd);

    assertArrayEquals(rbCopy.toArray(), cow.toArray(), "orNot result differs from PersistentRoaringBitmap");
    assertArrayEquals(sourceOriginal, source.toArray(), "source corrupted by orNot");
  }

  // -----------------------------------------------------------------------
  // lazyor / naivelazyor / repairAfterLazy
  // -----------------------------------------------------------------------

  @Test
  public void lazyOrSharesFromV2() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 42));   // keys 0..2
    PersistentRoaringBitmap x2 = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(3, 6, 99));   // keys 3..5, fully disjoint

    result.lazyor(x2);

    // Non-overlapping containers from x2 should be shared (same reference)
    assertEquals(6, result.getContainerCount());
    for (int i = 0; i < 3; i++) {
      assertSame(x2.getContainerAtIndex(i), result.getContainerAtIndex(3 + i),
          "Container from x2 at index " + i + " should be shared into result");
    }
  }

  @Test
  public void lazyOrDoesNotCorruptV2() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 42));
    PersistentRoaringBitmap x2 = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(3, 6, 99));
    int[] x2Before = x2.toArray();

    result.lazyor(x2);

    // Mutate result in x2's key range
    result.add(3 << 16 | 42);
    result.add(4 << 16 | 42);

    assertArrayEquals(x2Before, x2.toArray(),
        "x2 was corrupted by result mutation after lazyor");
  }

  @Test
  public void lazyOrOverlappingClones() {
    // Test that overlapping keys in lazyor get copyIfShared treatment
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap result = ca.clone();
    int[] caOriginal = ca.toArray();

    PersistentRoaringBitmap x2 = buildMultiContainerBitmap(2, 8, 99);
    result.lazyor(x2);
    result.repairAfterLazy();

    assertArrayEquals(caOriginal, ca.toArray(),
        "original was corrupted by lazyor on clone");
  }

  @Test
  public void repairAfterLazyDoesNotCorruptShared() {
    PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap result = source.clone();
    int[] sourceOriginal = source.toArray();

    // lazyor to get lazy containers, then repair
    PersistentRoaringBitmap x2 = buildMultiContainerBitmap(0, 5, 99);
    result.lazyor(x2);
    result.repairAfterLazy();

    assertArrayEquals(sourceOriginal, source.toArray(),
        "source was corrupted by repairAfterLazy on clone");
  }

  // -----------------------------------------------------------------------
  // deserialize resets shared
  // -----------------------------------------------------------------------

  @Test
  public void deserializeResetsShared() throws IOException {
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    cow.serialize(new DataOutputStream(baos));
    byte[] bytes = baos.toByteArray();

    // Create a V2 with shared containers, then deserialize over it
    PersistentRoaringBitmap target = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 99));
    target.clone(); // make containers shared

    target.deserialize(new DataInputStream(new ByteArrayInputStream(bytes)));

    // After deserialize, no containers should be marked shared
    for (int i = 0; i < target.getContainerCount(); i++) {
      assertFalse(target.isShared(i),
          "Container " + i + " should not be shared after deserialize");
    }
    assertArrayEquals(cow.toArray(), target.toArray());
  }

  @Test
  public void deserializeByteBufferResetsShared() throws IOException {
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    cow.serialize(new DataOutputStream(baos));
    byte[] bytes = baos.toByteArray();

    PersistentRoaringBitmap target = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 99));
    target.clone(); // make containers shared

    target.deserialize(ByteBuffer.wrap(bytes));

    for (int i = 0; i < target.getContainerCount(); i++) {
      assertFalse(target.isShared(i),
          "Container " + i + " should not be shared after ByteBuffer deserialize");
    }
    assertArrayEquals(cow.toArray(), target.toArray());
  }

  // -----------------------------------------------------------------------
  // Deprecated int-range methods dispatch correctly via virtual dispatch
  // -----------------------------------------------------------------------

  @Test
  @SuppressWarnings("deprecation")
  public void deprecatedIntRangeAddDispatches() {
    PersistentRoaringBitmap rb = buildMultiContainerBitmap(0, 5, 42);
    PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap result = source.clone();
    int[] sourceOriginal = source.toArray();

    int start = 2 << 16;
    int end = (3 << 16) + 100;
    rb.add(start, end);
    result.add(start, end);

    assertArrayEquals(rb.toArray(), result.toArray());
    assertArrayEquals(sourceOriginal, source.toArray(), "source was corrupted");
  }

  @Test
  @SuppressWarnings("deprecation")
  public void deprecatedIntRangeRemoveDispatches() {
    PersistentRoaringBitmap rb = buildMultiContainerBitmap(0, 5, 42);
    PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap result = source.clone();
    int[] sourceOriginal = source.toArray();

    int start = 2 << 16;
    int end = (3 << 16) + 100;
    rb.remove(start, end);
    result.remove(start, end);

    assertArrayEquals(rb.toArray(), result.toArray());
    assertArrayEquals(sourceOriginal, source.toArray(), "source was corrupted");
  }

  @Test
  @SuppressWarnings("deprecation")
  public void deprecatedIntRangeFlipDispatches() {
    PersistentRoaringBitmap rb = buildMultiContainerBitmap(0, 5, 42);
    PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap result = source.clone();
    int[] sourceOriginal = source.toArray();

    int start = 2 << 16;
    int end = (3 << 16) + 100;
    rb.flip(start, end);
    result.flip(start, end);

    assertArrayEquals(rb.toArray(), result.toArray());
    assertArrayEquals(sourceOriginal, source.toArray(), "source was corrupted");
  }

  @Test
  public void varArgsAddDispatches() {
    PersistentRoaringBitmap rb = buildMultiContainerBitmap(0, 5, 42);
    PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap result = source.clone();
    int[] sourceOriginal = source.toArray();

    int[] dat = new int[]{(1 << 16) | 42, (3 << 16) | 100};
    rb.add(dat);
    result.add(dat);

    assertArrayEquals(rb.toArray(), result.toArray());
    assertArrayEquals(sourceOriginal, source.toArray(), "source was corrupted");
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private static PersistentRoaringBitmap buildMultiContainerBitmap(int startKey, int endKey, int seed) {
    Random random = new Random(seed);
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    for (int key = startKey; key < endKey; key++) {
      int base = key << 16;
      for (int j = 0; j < 500; j++) {
        rb.add(base + random.nextInt(65536));
      }
    }
    return rb;
  }

  // -----------------------------------------------------------------------
  // Fuzz test: random operations, compare against PersistentRoaringBitmap
  // -----------------------------------------------------------------------

  @Test
  public void fuzzTestRandomOperationsMatchRoaringBitmap() {
    Random rng = new Random(12345);
    PersistentRoaringBitmap reference = new PersistentRoaringBitmap();
    PersistentRoaringBitmap cow = new PersistentRoaringBitmap();

    for (int iter = 0; iter < 2000; iter++) {
      int op = rng.nextInt(10);
      switch (op) {
        case 0: { // add single
          int val = rng.nextInt(5) << 16 | rng.nextInt(1000);
          reference.add(val);
          cow.add(val);
          break;
        }
        case 1: { // remove single
          int val = rng.nextInt(5) << 16 | rng.nextInt(1000);
          reference.remove(val);
          cow.remove(val);
          break;
        }
        case 2: { // flip single
          int val = rng.nextInt(5) << 16 | rng.nextInt(1000);
          reference.flip(val);
          cow.flip(val);
          break;
        }
        case 3: { // checkedAdd
          int val = rng.nextInt(5) << 16 | rng.nextInt(1000);
          boolean refResult = reference.checkedAdd(val);
          boolean cowResult = cow.checkedAdd(val);
          assertEquals(refResult, cowResult, "checkedAdd mismatch at iter " + iter);
          break;
        }
        case 4: { // checkedRemove
          int val = rng.nextInt(5) << 16 | rng.nextInt(1000);
          boolean refResult = reference.checkedRemove(val);
          boolean cowResult = cow.checkedRemove(val);
          assertEquals(refResult, cowResult, "checkedRemove mismatch at iter " + iter);
          break;
        }
        case 5: { // range add
          int key = rng.nextInt(5);
          long start = ((long) key << 16) + rng.nextInt(500);
          long end = start + rng.nextInt(500);
          reference.add(start, end);
          cow.add(start, end);
          break;
        }
        case 6: { // range remove
          int key = rng.nextInt(5);
          long start = ((long) key << 16) + rng.nextInt(500);
          long end = start + rng.nextInt(500);
          reference.remove(start, end);
          cow.remove(start, end);
          break;
        }
        case 7: { // range flip
          int key = rng.nextInt(5);
          long start = ((long) key << 16) + rng.nextInt(500);
          long end = start + rng.nextInt(500);
          reference.flip(start, end);
          cow.flip(start, end);
          break;
        }
        case 8: { // in-place OR with random bitmap
          PersistentRoaringBitmap extra = new PersistentRoaringBitmap();
          for (int j = 0; j < 50; j++) {
            extra.add(rng.nextInt(5) << 16 | rng.nextInt(1000));
          }
          reference.or(extra);
          cow.or(extra);
          break;
        }
        case 9: { // in-place AND with random bitmap
          PersistentRoaringBitmap mask = new PersistentRoaringBitmap();
          for (int j = 0; j < 50; j++) {
            mask.add(rng.nextInt(5) << 16 | rng.nextInt(1000));
          }
          reference.and(mask);
          cow.and(mask);
          break;
        }
      }
      assertArrayEquals(reference.toArray(), cow.toArray(),
          "Mismatch after operation " + op + " at iteration " + iter);
    }
  }

  @Test
  public void fuzzTestCloneIsolation() {
    Random rng = new Random(67890);
    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));

    for (int iter = 0; iter < 200; iter++) {
      int[] originalBefore = original.toArray();
      PersistentRoaringBitmap cloned = original.clone();

      // Do random mutations on the clone
      for (int j = 0; j < 20; j++) {
        int op = rng.nextInt(5);
        int val = rng.nextInt(5) << 16 | rng.nextInt(1000);
        switch (op) {
          case 0: cloned.add(val); break;
          case 1: cloned.remove(val); break;
          case 2: cloned.flip(val); break;
          case 3: cloned.checkedAdd(val); break;
          case 4: cloned.checkedRemove(val); break;
        }
      }

      // Original must be unchanged
      assertArrayEquals(originalBefore, original.toArray(),
          "Original was corrupted at iteration " + iter);
    }
  }

  @Test
  public void fuzzTestStaticOpsCorrectnessAndIsolation() {
    Random rng = new Random(11111);

    for (int iter = 0; iter < 100; iter++) {
      PersistentRoaringBitmap rbA = buildMultiContainerBitmap(
          rng.nextInt(5), rng.nextInt(5) + 5, rng.nextInt(10000));
      PersistentRoaringBitmap rbB = buildMultiContainerBitmap(
          rng.nextInt(5), rng.nextInt(5) + 5, rng.nextInt(10000));

      PersistentRoaringBitmap cowA = PersistentRoaringBitmap.fromBitmap(rbA);
      PersistentRoaringBitmap cowB = PersistentRoaringBitmap.fromBitmap(rbB);
      int[] aBefore = cowA.toArray();
      int[] bBefore = cowB.toArray();

      // Static OR
      PersistentRoaringBitmap expectedOr = PersistentRoaringBitmap.or(rbA, rbB);
      PersistentRoaringBitmap resultOr = PersistentRoaringBitmap.or(cowA, cowB);
      assertArrayEquals(expectedOr.toArray(), resultOr.toArray(),
          "OR mismatch at iter " + iter);

      // Mutate result
      resultOr.add(42);

      // Inputs must be unchanged
      assertArrayEquals(aBefore, cowA.toArray(),
          "cowA corrupted after OR + mutation at iter " + iter);
      assertArrayEquals(bBefore, cowB.toArray(),
          "cowB corrupted after OR + mutation at iter " + iter);

      // Static AND
      PersistentRoaringBitmap expectedAnd = PersistentRoaringBitmap.and(rbA, rbB);
      PersistentRoaringBitmap resultAnd = PersistentRoaringBitmap.and(cowA, cowB);
      assertArrayEquals(expectedAnd.toArray(), resultAnd.toArray(),
          "AND mismatch at iter " + iter);

      // Static XOR
      PersistentRoaringBitmap expectedXor = PersistentRoaringBitmap.xor(rbA, rbB);
      PersistentRoaringBitmap resultXor = PersistentRoaringBitmap.xor(cowA, cowB);
      assertArrayEquals(expectedXor.toArray(), resultXor.toArray(),
          "XOR mismatch at iter " + iter);

      // Static ANDNOT
      PersistentRoaringBitmap expectedAndNot = PersistentRoaringBitmap.andNot(rbA, rbB);
      PersistentRoaringBitmap resultAndNot = PersistentRoaringBitmap.andNot(cowA, cowB);
      assertArrayEquals(expectedAndNot.toArray(), resultAndNot.toArray(),
          "ANDNOT mismatch at iter " + iter);
    }
  }

  @Test
  public void fuzzTestInPlaceOpsOnSharedBitmaps() {
    Random rng = new Random(22222);

    for (int iter = 0; iter < 100; iter++) {
      // Create two bitmaps and produce a result via static OR (shared containers)
      PersistentRoaringBitmap cowA = PersistentRoaringBitmap.fromBitmap(
          buildMultiContainerBitmap(rng.nextInt(3), rng.nextInt(3) + 3, rng.nextInt(10000)));
      PersistentRoaringBitmap cowB = PersistentRoaringBitmap.fromBitmap(
          buildMultiContainerBitmap(rng.nextInt(3), rng.nextInt(3) + 3, rng.nextInt(10000)));

      PersistentRoaringBitmap result = PersistentRoaringBitmap.or(cowA, cowB);
      int[] aOriginal = cowA.toArray();

      // Create a third bitmap for in-place ops
      PersistentRoaringBitmap extra = buildMultiContainerBitmap(
          rng.nextInt(3), rng.nextInt(3) + 5, rng.nextInt(10000));

      // Reference: same ops on plain PersistentRoaringBitmap
      PersistentRoaringBitmap refResult = new PersistentRoaringBitmap();
      refResult.or(PersistentRoaringBitmap.or(cowA, cowB)); // copy result's values
      refResult.or(extra);

      result.or(extra);

      assertArrayEquals(refResult.toArray(), result.toArray(),
          "In-place OR mismatch at iter " + iter);
      assertArrayEquals(aOriginal, cowA.toArray(),
          "cowA corrupted by in-place OR at iter " + iter);
    }
  }

  // -----------------------------------------------------------------------
  // Edge cases: large/boundary values, negative ints (high unsigned)
  // -----------------------------------------------------------------------

  @Test
  public void cowWithHighUnsignedValues() {
    // Values near 0xFFFF0000 (high keys in unsigned space)
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    rb.add(-1);  // 0xFFFFFFFF — highest unsigned int
    rb.add(-2);  // 0xFFFFFFFE
    rb.add(-65536); // 0xFFFF0000 — key 0xFFFF, low 0
    rb.add(Integer.MAX_VALUE); // 0x7FFFFFFF
    rb.add(Integer.MIN_VALUE); // 0x80000000

    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap cloned = original.clone();
    int[] originalBefore = original.toArray();

    // Mutate clone
    cloned.add(-3); // 0xFFFFFFFD
    cloned.remove(-1);

    assertArrayEquals(originalBefore, original.toArray(),
        "Original was corrupted with high unsigned values");
  }

  @Test
  public void rangeAddNearMaxUnsigned() {
    // Test range add near the boundary of unsigned int space
    PersistentRoaringBitmap cow = new PersistentRoaringBitmap();
    cow.add(0xFFFFFFF0L, 0x100000000L);  // last 16 unsigned ints

    PersistentRoaringBitmap ref = new PersistentRoaringBitmap();
    ref.add(0xFFFFFFF0L, 0x100000000L);

    assertArrayEquals(ref.toArray(), cow.toArray(), "Range add near max unsigned failed");
    assertEquals(16, cow.getCardinality());
  }

  @Test
  public void staticOrWithHighKeys() {
    PersistentRoaringBitmap rbA = new PersistentRoaringBitmap();
    rbA.add(-1); // key 0xFFFF
    rbA.add(-2);

    PersistentRoaringBitmap rbB = new PersistentRoaringBitmap();
    rbB.add(-3);
    rbB.add(-4);

    PersistentRoaringBitmap cowA = PersistentRoaringBitmap.fromBitmap(rbA);
    PersistentRoaringBitmap cowB = PersistentRoaringBitmap.fromBitmap(rbB);

    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(cowA, cowB);
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.or(rbA, rbB);

    assertArrayEquals(expected.toArray(), result.toArray());
    assertEquals(4, result.getCardinality());
  }

  // -----------------------------------------------------------------------
  // Edge case: operations on empty PersistentRoaringBitmap
  // -----------------------------------------------------------------------

  @Test
  public void emptyBitmapOperations() {
    PersistentRoaringBitmap empty = new PersistentRoaringBitmap();

    // clone empty
    PersistentRoaringBitmap cloned = empty.clone();
    assertTrue(cloned.isEmpty());

    // add to clone
    cloned.add(1);
    assertTrue(empty.isEmpty(), "Empty was corrupted after clone mutation");

    // or with empty
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(empty, empty);
    assertTrue(result.isEmpty());

    // and with empty
    result = PersistentRoaringBitmap.and(empty, empty);
    assertTrue(result.isEmpty());

    // xor with empty
    result = PersistentRoaringBitmap.xor(empty, empty);
    assertTrue(result.isEmpty());
  }

  // -----------------------------------------------------------------------
  // Edge case: operations that cause container type transitions
  // -----------------------------------------------------------------------

  @Test
  public void addCausingArrayToBitmapTransitionOnSharedContainer() {
    // Create a shared ArrayContainer near the threshold (DEFAULT_MAX_SIZE = 4096)
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    for (int i = 0; i < 4095; i++) {
      rb.add(i);
    }
    // This should be an ArrayContainer with 4095 elements

    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap cloned = original.clone();
    int[] originalBefore = original.toArray();

    // Add one more element to trigger ArrayContainer -> BitmapContainer transition
    cloned.add(4095);

    assertArrayEquals(originalBefore, original.toArray(),
        "Original corrupted by container type transition on clone");
    assertEquals(4096, cloned.getCardinality());
    assertTrue(cloned.contains(4095));
  }

  @Test
  public void addMultipleTriggeringBitmapTransitionOnSharedContainer() {
    // Create a shared ArrayContainer with 4096 elements (DEFAULT_MAX_SIZE)
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    for (int i = 0; i < 4096; i++) {
      rb.add(i);
    }
    // At 4096, this is at the boundary; adding one more makes it a BitmapContainer

    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap cloned = original.clone();
    int[] originalBefore = original.toArray();

    // Add element to trigger transition
    cloned.add(4096);

    assertArrayEquals(originalBefore, original.toArray(),
        "Original corrupted by bitmap transition on clone");
    assertTrue(cloned.contains(4096));
  }

  @Test
  public void removeCausingBitmapToArrayTransitionOnSharedContainer() {
    // Create a shared BitmapContainer with exactly DEFAULT_MAX_SIZE + 1 elements
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    for (int i = 0; i < 4097; i++) {
      rb.add(i);
    }
    // This is a BitmapContainer

    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap cloned = original.clone();
    int[] originalBefore = original.toArray();

    // Remove one element — but this won't trigger the transition
    // Need to use flip to get the right cardinality transition
    // Actually, remove doesn't transition BitmapContainer to ArrayContainer
    // That only happens through flip. Let me use flip instead.
    cloned.flip(0);

    assertArrayEquals(originalBefore, original.toArray(),
        "Original corrupted by bitmap-to-array transition via flip on clone");
    assertEquals(4096, cloned.getCardinality());
    assertFalse(cloned.contains(0));
  }

  // -----------------------------------------------------------------------
  // Bug hunting: in-place xor/andNot that empties ALL containers
  // -----------------------------------------------------------------------

  @Test
  public void inPlaceXorEmptyingAllContainers() {
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap source = cow.clone();
    int[] sourceOriginal = source.toArray();

    // XOR with identical bitmap — should empty all containers
    PersistentRoaringBitmap identical = buildMultiContainerBitmap(0, 5, 42);
    cow.xor(identical);

    assertTrue(cow.isEmpty(), "XOR with identical should produce empty bitmap");
    assertArrayEquals(sourceOriginal, source.toArray(),
        "Source corrupted by XOR that emptied all containers");
  }

  @Test
  public void inPlaceAndNotEmptyingAllContainers() {
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap source = cow.clone();
    int[] sourceOriginal = source.toArray();

    // Use a superset for andNot
    PersistentRoaringBitmap superset = buildMultiContainerBitmap(0, 5, 42);
    for (int key = 0; key < 5; key++) {
      superset.add((long)(key << 16), (long)(key << 16) + 65536);
    }
    cow.andNot(superset);

    assertTrue(cow.isEmpty(), "andNot with superset should produce empty bitmap");
    assertArrayEquals(sourceOriginal, source.toArray(),
        "Source corrupted by andNot that emptied all containers");
  }

  // -----------------------------------------------------------------------
  // Bug hunting: mixed in-place ops on shared result
  // -----------------------------------------------------------------------

  @Test
  public void chainedInPlaceOpsOnSharedResult() {
    PersistentRoaringBitmap a = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap b = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(3, 8, 99));

    // Create shared result
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(a, b);
    int[] aOriginal = a.toArray();
    int[] bOriginal = b.toArray();

    // Chain multiple in-place ops
    PersistentRoaringBitmap mask1 = buildMultiContainerBitmap(1, 4, 77);
    PersistentRoaringBitmap mask2 = buildMultiContainerBitmap(5, 7, 88);

    // Compute reference
    PersistentRoaringBitmap refResult = PersistentRoaringBitmap.or(a, b);
    refResult.xor(mask1);
    refResult.andNot(mask2);

    // Apply to COW
    result.xor(mask1);
    result.andNot(mask2);

    assertArrayEquals(refResult.toArray(), result.toArray(),
        "Chained in-place ops produced wrong result");
    assertArrayEquals(aOriginal, a.toArray(),
        "a corrupted by chained in-place ops on shared result");
    assertArrayEquals(bOriginal, b.toArray(),
        "b corrupted by chained in-place ops on shared result");
  }

  // -----------------------------------------------------------------------
  // Polymorphism: PersistentRoaringBitmap used through PersistentRoaringBitmap reference
  // -----------------------------------------------------------------------

  @Test
  public void usedThroughRoaringBitmapReference() {
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap original = (PersistentRoaringBitmap) cow;
    PersistentRoaringBitmap cloned = original.clone();
    int[] originalBefore = original.toArray();

    // All operations through the PersistentRoaringBitmap reference should use COW
    cow.add(42);
    cow.remove(originalBefore[0]);
    cow.flip(originalBefore[1]);

    // Cloned should be unchanged
    assertArrayEquals(originalBefore, cloned.toArray(),
        "Clone was corrupted by mutations through PersistentRoaringBitmap reference");
  }

  @Test
  public void inPlaceOrThroughRoaringBitmapReference() {
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap original = (PersistentRoaringBitmap) cow;
    PersistentRoaringBitmap cloned = original.clone();
    int[] cloneBefore = cloned.toArray();

    PersistentRoaringBitmap extra = buildMultiContainerBitmap(3, 8, 99);
    cow.or(extra);

    assertArrayEquals(cloneBefore, cloned.toArray(),
        "Clone was corrupted by in-place OR through PersistentRoaringBitmap reference");
  }

  @Test
  public void inPlaceAndXorAndNotThroughRoaringBitmapReference() {
    // Test and
    {
      PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(
          buildMultiContainerBitmap(0, 5, 42));
      PersistentRoaringBitmap cloned = ((PersistentRoaringBitmap) cow).clone();
      int[] cloneBefore = cloned.toArray();

      PersistentRoaringBitmap mask = buildMultiContainerBitmap(2, 7, 99);
      cow.and(mask);

      assertArrayEquals(cloneBefore, cloned.toArray(),
          "Clone corrupted by in-place AND through PersistentRoaringBitmap reference");
    }

    // Test xor
    {
      PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(
          buildMultiContainerBitmap(0, 5, 42));
      PersistentRoaringBitmap cloned = ((PersistentRoaringBitmap) cow).clone();
      int[] cloneBefore = cloned.toArray();

      PersistentRoaringBitmap mask = buildMultiContainerBitmap(2, 7, 99);
      cow.xor(mask);

      assertArrayEquals(cloneBefore, cloned.toArray(),
          "Clone corrupted by in-place XOR through PersistentRoaringBitmap reference");
    }

    // Test andNot
    {
      PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(
          buildMultiContainerBitmap(0, 5, 42));
      PersistentRoaringBitmap cloned = ((PersistentRoaringBitmap) cow).clone();
      int[] cloneBefore = cloned.toArray();

      PersistentRoaringBitmap mask = buildMultiContainerBitmap(2, 7, 99);
      cow.andNot(mask);

      assertArrayEquals(cloneBefore, cloned.toArray(),
          "Clone corrupted by in-place ANDNOT through PersistentRoaringBitmap reference");
    }
  }

  // -----------------------------------------------------------------------
  // Bug hunting: passing COW bitmap to PersistentRoaringBitmap.or/and/xor/andNot static methods
  // -----------------------------------------------------------------------

  @Test
  public void cowPassedToRoaringBitmapStaticOr() {
    PersistentRoaringBitmap cowA = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap cowB = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(3, 8, 99));
    int[] aBefore = cowA.toArray();
    int[] bBefore = cowB.toArray();

    // Use the PersistentRoaringBitmap static methods (NOT PersistentRoaringBitmap static methods)
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(cowA, cowB);
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.or(
        buildMultiContainerBitmap(0, 5, 42),
        buildMultiContainerBitmap(3, 8, 99));

    assertArrayEquals(expected.toArray(), result.toArray(),
        "PersistentRoaringBitmap.or with COW inputs produced wrong result");

    // Inputs must be unchanged
    assertArrayEquals(aBefore, cowA.toArray(),
        "cowA corrupted by PersistentRoaringBitmap.or");
    assertArrayEquals(bBefore, cowB.toArray(),
        "cowB corrupted by PersistentRoaringBitmap.or");
  }

  @Test
  public void cowPassedAsArgumentToPlainBitmapOr() {
    // Plain PersistentRoaringBitmap.or(COW) — tests that the parent's or() handles COW arg correctly
    PersistentRoaringBitmap plain = buildMultiContainerBitmap(0, 5, 42);
    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(3, 8, 99));
    PersistentRoaringBitmap cowClone = cow.clone();
    int[] cowBefore = cow.toArray();

    // plain.or(cow) — plain is NOT a COW bitmap, so its or() is the parent method
    // The parent's or() calls x2.highLowContainer.getContainerAtIndex(pos2).clone()
    // for non-overlapping containers and .ior() for overlapping ones
    plain.or(cow);

    // cow should not be corrupted (parent's or() clones from x2)
    assertArrayEquals(cowBefore, cow.toArray(),
        "COW bitmap corrupted when used as argument to plain bitmap's or()");
  }

  // -----------------------------------------------------------------------
  // Bug hunting: bidirectional mutation after in-place COW sharing
  // -----------------------------------------------------------------------

  @Test
  public void bidirectionalMutationAfterInPlaceOrSharing() {
    // result.or(x2) where x2 is COW — shares containers into result,
    // marks shared in both. Then BOTH are mutated.
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 42));    // keys 0,1,2
    PersistentRoaringBitmap x2 = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(3, 6, 99));    // keys 3,4,5

    int[] x2Before = x2.toArray();

    // In-place OR: non-overlapping containers from x2 are shared into result
    result.or(x2);

    // Both result and x2 share the same container references for keys 3,4,5
    // Mutate result at a shared key
    result.add(3 << 16 | 42);  // touches key 3 (shared from x2)

    // x2 must be unchanged
    assertArrayEquals(x2Before, x2.toArray(),
        "x2 corrupted by result mutation after in-place OR sharing");

    // Now mutate x2 at a key that's shared with result
    int[] resultBeforeX2Mutation = result.toArray();
    x2.add(4 << 16 | 42);  // touches key 4 (shared with result)

    // result must be unchanged
    assertArrayEquals(resultBeforeX2Mutation, result.toArray(),
        "result corrupted by x2 mutation after in-place OR sharing");
  }

  @Test
  public void bidirectionalMutationAfterInPlaceXorSharing() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 42));
    PersistentRoaringBitmap x2 = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(3, 6, 99));

    int[] x2Before = x2.toArray();

    result.xor(x2);

    result.add(3 << 16 | 42);

    assertArrayEquals(x2Before, x2.toArray(),
        "x2 corrupted by result mutation after in-place XOR sharing");

    int[] resultBeforeX2Mutation = result.toArray();
    x2.add(4 << 16 | 42);

    assertArrayEquals(resultBeforeX2Mutation, result.toArray(),
        "result corrupted by x2 mutation after in-place XOR sharing");
  }

  @Test
  public void bidirectionalMutationAfterLazyOrSharing() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 42));
    PersistentRoaringBitmap x2 = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(3, 6, 99));

    int[] x2Before = x2.toArray();

    result.lazyor(x2);
    result.repairAfterLazy();

    result.add(3 << 16 | 42);

    assertArrayEquals(x2Before, x2.toArray(),
        "x2 corrupted after lazyor sharing + mutation");

    int[] resultBeforeX2Mutation = result.toArray();
    x2.add(4 << 16 | 42);

    assertArrayEquals(resultBeforeX2Mutation, result.toArray(),
        "result corrupted after lazyor sharing + x2 mutation");
  }

  @Test
  public void bidirectionalMutationAfterNaiveLazyOrSharing() {
    PersistentRoaringBitmap result = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 42));
    PersistentRoaringBitmap x2 = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(3, 6, 99));

    int[] x2Before = x2.toArray();

    result.naivelazyor(x2);
    result.repairAfterLazy();

    result.add(3 << 16 | 42);

    assertArrayEquals(x2Before, x2.toArray(),
        "x2 corrupted after naivelazyor sharing + mutation");

    int[] resultBeforeX2Mutation = result.toArray();
    x2.add(4 << 16 | 42);

    assertArrayEquals(resultBeforeX2Mutation, result.toArray(),
        "result corrupted after naivelazyor sharing + x2 mutation");
  }

  // -----------------------------------------------------------------------
  // Bug hunting: stress test with many containers
  // -----------------------------------------------------------------------

  @Test
  public void stressTestManyContainers() {
    // Create bitmap with 100 containers
    PersistentRoaringBitmap rb = buildMultiContainerBitmap(0, 100, 42);
    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap cloned = original.clone();
    int[] originalBefore = original.toArray();

    // Mutate every other container in the clone
    for (int key = 0; key < 100; key += 2) {
      cloned.add(key << 16 | 60000);
    }

    // Original must be unchanged
    assertArrayEquals(originalBefore, original.toArray(),
        "Original corrupted by mutations on every other container of clone");

    // Verify clone has the new values
    for (int key = 0; key < 100; key += 2) {
      assertTrue(cloned.contains(key << 16 | 60000),
          "Clone missing value at key " + key);
    }
  }

  // -----------------------------------------------------------------------
  // Bug hunting: in-place or where both bitmaps share same container objects
  // -----------------------------------------------------------------------

  @Test
  public void inPlaceOrBetweenClonesOfSameSource() {
    PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap a = source.clone();
    PersistentRoaringBitmap b = source.clone();
    int[] sourceOriginal = source.toArray();

    // a and b share the SAME container objects (both cloned from source)
    // a.or(b) processes overlapping keys: copyIfShared(pos1) clones a's container,
    // then .ior(b's container) reads from b's container (which is the SAME object
    // as what was originally in a before the clone)
    a.or(b);

    // Result should be the same (or of identical = same)
    assertArrayEquals(sourceOriginal, a.toArray(),
        "a.or(b) where both share same containers produced wrong result");

    // source must be unchanged
    assertArrayEquals(sourceOriginal, source.toArray(),
        "source corrupted by or between its clones");

    // b must be unchanged
    assertArrayEquals(sourceOriginal, b.toArray(),
        "b corrupted by a.or(b) where both share same containers");
  }

  @Test
  public void inPlaceXorBetweenClonesOfSameSource() {
    PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap a = source.clone();
    PersistentRoaringBitmap b = source.clone();
    int[] sourceOriginal = source.toArray();

    // a.xor(b) where both share same containers — should produce empty
    a.xor(b);

    assertTrue(a.isEmpty(), "a.xor(b) where both are identical should be empty");
    assertArrayEquals(sourceOriginal, source.toArray(),
        "source corrupted by xor between its clones");
    assertArrayEquals(sourceOriginal, b.toArray(),
        "b corrupted by a.xor(b) where both share same containers");
  }

  @Test
  public void inPlaceAndNotBetweenClonesOfSameSource() {
    PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap a = source.clone();
    PersistentRoaringBitmap b = source.clone();
    int[] sourceOriginal = source.toArray();

    // a.andNot(b) where both are identical — should produce empty
    a.andNot(b);

    assertTrue(a.isEmpty(), "a.andNot(b) where both are identical should be empty");
    assertArrayEquals(sourceOriginal, source.toArray(),
        "source corrupted by andNot between its clones");
    assertArrayEquals(sourceOriginal, b.toArray(),
        "b corrupted by a.andNot(b) where both share same containers");
  }

  @Test
  public void inPlaceAndBetweenClonesOfSameSource() {
    PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap a = source.clone();
    PersistentRoaringBitmap b = source.clone();
    int[] sourceOriginal = source.toArray();

    // a.and(b) where both are identical — should equal source
    a.and(b);

    assertArrayEquals(sourceOriginal, a.toArray(),
        "a.and(b) where both are identical should equal source");
    assertArrayEquals(sourceOriginal, source.toArray(),
        "source corrupted by and between its clones");
    assertArrayEquals(sourceOriginal, b.toArray(),
        "b corrupted by a.and(b) where both share same containers");
  }

  // -----------------------------------------------------------------------
  // Bug hunting: shared[] consistency check after various op sequences
  // -----------------------------------------------------------------------

  @Test
  public void sharedArrayConsistencyAfterComplexSequence() {
    PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 10, 42));
    PersistentRoaringBitmap cow = source.clone();

    // 1. All containers should be shared after clone
    for (int i = 0; i < cow.getContainerCount(); i++) {
      assertTrue(cow.isShared(i), "Initially, container " + i + " should be shared");
    }

    // 2. Add a value to key 5 — should clone that container
    cow.add(5 << 16 | 60000);
    assertFalse(cow.isShared(5), "Container at key 5 should not be shared after mutation");
    for (int i = 0; i < cow.getContainerCount(); i++) {
      if (i != 5) {
        assertTrue(cow.isShared(i), "Container " + i + " should still be shared");
      }
    }

    // 3. Remove entire container at key 3
    for (int val : source.toArray()) {
      if (val >>> 16 == 3) {
        cow.remove(val);
      }
    }
    // Also remove the newly added value at key 3 if any
    // Actually, we only added at key 5. Let me force-remove key 3.
    // The container at key 3 should be removed if it becomes empty.
    // But it won't become empty from removing one value. Let me remove ALL values.

    // Reset for cleaner test
    cow = source.clone();
    int containerCount = cow.getContainerCount();

    // Add new key in the middle
    cow.add(10 << 16 | 1); // key 10, should be at the end since keys are 0-9
    assertEquals(containerCount + 1, cow.getContainerCount());
    assertFalse(cow.isShared(containerCount),
        "New container at the end should not be shared");

    // Add new key between existing keys
    // Create a gap first by removing key 5
    cow = source.clone();
    // Remove ALL values from key 5
    // Use range remove to be sure
    cow.remove(5L << 16, 6L << 16);

    // Now cow has 9 containers (keys 0-4, 6-9)
    assertEquals(9, cow.getContainerCount());

    // Insert new key 5
    cow.add(5 << 16 | 42);
    assertEquals(10, cow.getContainerCount());

    // Key 5 container should not be shared
    // But we need to find which index key 5 is at
    for (int i = 0; i < cow.getContainerCount(); i++) {
      int key = cow.getKeyAtIndex(i);
      if (key == 5) {
        assertFalse(cow.isShared(i),
            "Newly inserted container at key 5 should not be shared");
      }
    }
  }

  @Test
  public void sharedArrayLengthNeverShorterThanContainerCount() {
    PersistentRoaringBitmap cow = new PersistentRoaringBitmap();

    // Build up a large number of containers
    for (int key = 0; key < 50; key++) {
      cow.add(key << 16 | 1);
    }
    assertEquals(50, cow.getContainerCount());

    // Clone and verify
    PersistentRoaringBitmap cloned = cow.clone();
    assertEquals(50, cloned.getContainerCount());

    // Remove half the containers
    for (int key = 0; key < 50; key += 2) {
      cloned.remove(key << 16 | 1); // removes the only value, removing the container
    }
    assertEquals(25, cloned.getContainerCount());

    // All remaining containers should have valid shared[] entries (no AIOOBE)
    for (int i = 0; i < cloned.getContainerCount(); i++) {
      // This call should not throw
      boolean isShared = cloned.isShared(i);
      assertTrue(isShared, "Remaining containers should be shared with original");
    }
  }

  private static PersistentRoaringBitmap buildBitmap(int start, int end, int step) {
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    for (int i = start; i < end; i += step) {
      rb.add(i);
    }
    return rb;
  }

  // -----------------------------------------------------------------------
  // Bug hunting: static or with self (or(x, x))
  // -----------------------------------------------------------------------

  @Test
  public void staticOrWithSelfProducesCorrectResult() {
    PersistentRoaringBitmap x = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    int[] before = x.toArray();

    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(x, x);

    // or(x, x) should equal x
    assertArrayEquals(before, result.toArray(), "or(x, x) should equal x");
  }

  @Test
  public void staticOrWithSelfDoesNotCorruptInput() {
    PersistentRoaringBitmap x = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    int[] before = x.toArray();

    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(x, x);

    // Mutate result
    result.add(42);
    result.remove(before[0]);

    // Input must be unchanged
    assertArrayEquals(before, x.toArray(), "Input was corrupted after or(x, x)");
  }

  @Test
  public void staticAndWithSelfProducesCorrectResult() {
    PersistentRoaringBitmap x = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    int[] before = x.toArray();

    PersistentRoaringBitmap result = PersistentRoaringBitmap.and(x, x);

    // and(x, x) should equal x
    assertArrayEquals(before, result.toArray(), "and(x, x) should equal x");
  }

  @Test
  public void staticXorWithSelfProducesEmpty() {
    PersistentRoaringBitmap x = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));

    PersistentRoaringBitmap result = PersistentRoaringBitmap.xor(x, x);

    assertTrue(result.isEmpty(), "xor(x, x) should be empty");
  }

  @Test
  public void staticAndNotWithSelfProducesEmpty() {
    PersistentRoaringBitmap x = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));

    PersistentRoaringBitmap result = PersistentRoaringBitmap.andNot(x, x);

    assertTrue(result.isEmpty(), "andNot(x, x) should be empty");
  }

  // -----------------------------------------------------------------------
  // Bug hunting: runOptimize on cloned bitmap
  // -----------------------------------------------------------------------

  @Test
  public void runOptimizeOnCloneDoesNotCorruptOriginal() {
    // Create a bitmap with run-friendly data (dense ranges)
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    rb.add(0L, 1000L);  // dense range in key 0
    rb.add(1L << 16, (1L << 16) + 1000L);  // dense range in key 1
    rb.runOptimize(); // ensure RunContainers

    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(rb);
    int[] originalBefore = original.toArray();

    PersistentRoaringBitmap cloned = original.clone();

    // runOptimize on clone should not affect original
    cloned.runOptimize();

    assertArrayEquals(originalBefore, original.toArray(),
        "Original was corrupted by runOptimize on clone");
  }

  @Test
  public void removeRunCompressionOnCloneDoesNotCorruptOriginal() {
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    rb.add(0L, 1000L);
    rb.add(1L << 16, (1L << 16) + 1000L);
    rb.runOptimize();

    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(rb);
    int[] originalBefore = original.toArray();

    PersistentRoaringBitmap cloned = original.clone();

    // removeRunCompression on clone should not affect original
    cloned.removeRunCompression();

    assertArrayEquals(originalBefore, original.toArray(),
        "Original was corrupted by removeRunCompression on clone");
  }

  // -----------------------------------------------------------------------
  // Bug hunting: xor removing containers from a cloned bitmap
  // -----------------------------------------------------------------------

  @Test
  public void xorRemovingAllOverlappingContainersKeepsSharedArrayConsistent() {
    // Create shared bitmap with keys 0, 1, 2
    PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 42));
    PersistentRoaringBitmap result = source.clone();
    int[] sourceOriginal = source.toArray();

    // XOR with a bitmap that has identical content for keys 0 and 1
    // This should remove containers 0 and 1 (xor of identical = empty)
    PersistentRoaringBitmap mask = new PersistentRoaringBitmap();
    // Copy exact content of key 0 and 1 from source
    for (int val : sourceOriginal) {
      int key = val >>> 16;
      if (key == 0 || key == 1) {
        mask.add(val);
      }
    }

    result.xor(mask);

    // Only key 2 should remain
    assertEquals(1, result.getContainerCount());
    // Key 2's container should still be shared with source
    assertTrue(result.isShared(0), "Remaining container should still be shared");

    // Source must be unchanged
    assertArrayEquals(sourceOriginal, source.toArray(),
        "Source was corrupted by xor that removed containers");
  }

  // -----------------------------------------------------------------------
  // Bug hunting: range remove emptying middle containers in cloned bitmap
  // -----------------------------------------------------------------------

  @Test
  public void rangeRemoveEmptyingMiddleContainersInClonedBitmap() {
    // Create a bitmap with one element per container for keys 0..4
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    for (int key = 0; key < 5; key++) {
      rb.add(key << 16 | 1);
    }
    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap cloned = original.clone();
    int[] originalBefore = original.toArray();

    // Remove range that spans keys 1, 2, 3 completely (emptying those containers)
    cloned.remove(1L << 16, 4L << 16);

    // Only keys 0 and 4 should remain
    assertEquals(2, cloned.getContainerCount());
    assertTrue(cloned.contains(0 << 16 | 1));
    assertTrue(cloned.contains(4 << 16 | 1));

    // Both remaining containers should still be shared
    assertTrue(cloned.isShared(0), "Container 0 should still be shared");
    assertTrue(cloned.isShared(1), "Container 1 (was key 4) should still be shared");

    // Original must be unchanged
    assertArrayEquals(originalBefore, original.toArray(),
        "Original was corrupted by range remove on clone");
  }

  // -----------------------------------------------------------------------
  // Bug hunting: sequential insert + remove on cloned bitmap
  // -----------------------------------------------------------------------

  @Test
  public void insertThenRemoveOnClonedBitmapKeepsSharedConsistent() {
    // Create shared bitmap with keys 0, 2, 4 (gaps at 1, 3)
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    rb.add(0 << 16 | 1);
    rb.add(2 << 16 | 1);
    rb.add(4 << 16 | 1);

    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap cloned = original.clone();
    int[] originalBefore = original.toArray();

    // Insert a new container at key 1 (between 0 and 2)
    cloned.add(1 << 16 | 1);
    assertEquals(4, cloned.getContainerCount());

    // Now remove the container at key 2 (the only element)
    cloned.remove(2 << 16 | 1);
    assertEquals(3, cloned.getContainerCount());

    // Verify containers: keys should be 0, 1, 4
    assertTrue(cloned.contains(0 << 16 | 1));
    assertTrue(cloned.contains(1 << 16 | 1));
    assertTrue(cloned.contains(4 << 16 | 1));

    // Key 0 and 4 containers should still be shared with original
    assertTrue(cloned.isShared(0), "Container at key 0 should still be shared");
    assertFalse(cloned.isShared(1), "Container at key 1 (new) should not be shared");
    assertTrue(cloned.isShared(2), "Container at key 4 should still be shared");

    // Original must be unchanged
    assertArrayEquals(originalBefore, original.toArray(),
        "Original was corrupted by insert+remove on clone");
  }

  // -----------------------------------------------------------------------
  // Bug hunting: in-place or between two COW bitmaps with overlapping shared containers
  // -----------------------------------------------------------------------

  @Test
  public void inPlaceOrBetweenTwoCowBitmapsSharingContainersFromSameSource() {
    // Setup: x is the source, a and b are results of static ops that share x's containers
    PersistentRoaringBitmap x = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap y = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 10, 99));
    int[] xOriginal = x.toArray();

    PersistentRoaringBitmap a = PersistentRoaringBitmap.or(x, y);
    // a shares containers from x (keys 0-4) and y (keys 5-9)

    PersistentRoaringBitmap b = x.clone();
    // b also shares containers from x (keys 0-4)

    // In-place or: a.or(b)
    // For overlapping keys 0-4, a's containers (shared from x) will be cloned before ior
    // b's containers (also shared from x) are passed as argument to ior
    int[] aExpected = a.toArray(); // should not change since b is subset of a

    a.or(b);

    // a should still have the same values (or with subset = no change)
    assertArrayEquals(aExpected, a.toArray(), "a's values changed unexpectedly");

    // x must be unchanged
    assertArrayEquals(xOriginal, x.toArray(),
        "x was corrupted by in-place or between bitmaps sharing its containers");
  }

  // -----------------------------------------------------------------------
  // Bug hunting: addN inserting new keys into cloned bitmap
  // -----------------------------------------------------------------------

  @Test
  public void addNNewKeysInClonedBitmapDoesNotCorruptOriginal() {
    // Create bitmap with keys 0, 2 (gap at key 1)
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    for (int j = 0; j < 100; j++) {
      rb.add(0 << 16 | j);
      rb.add(2 << 16 | j);
    }
    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap cloned = original.clone();
    int[] originalBefore = original.toArray();

    // addN with values spanning key 0 (existing, shared), key 1 (new), and key 2 (existing, shared)
    int[] dat = new int[]{
        0 << 16 | 999,   // key 0 (existing, shared -> needs COW)
        1 << 16 | 50,    // key 1 (new -> insert)
        1 << 16 | 51,    // key 1 (existing, just added)
        2 << 16 | 999    // key 2 (existing, shared -> needs COW)
    };
    cloned.addN(dat, 0, dat.length);

    // Original must be unchanged
    assertArrayEquals(originalBefore, original.toArray(),
        "Original was corrupted by addN on clone");

    // Clone should have the new values
    assertTrue(cloned.contains(0 << 16 | 999));
    assertTrue(cloned.contains(1 << 16 | 50));
    assertTrue(cloned.contains(1 << 16 | 51));
    assertTrue(cloned.contains(2 << 16 | 999));
  }

  // -----------------------------------------------------------------------
  // Bug hunting: trim after clone
  // -----------------------------------------------------------------------

  @Test
  public void trimAfterClonePreservesSharedTracking() {
    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 3, 42));
    PersistentRoaringBitmap cloned = original.clone();
    int[] originalBefore = original.toArray();

    cloned.trim();

    // After trim, shared array should be correctly sized
    for (int i = 0; i < cloned.getContainerCount(); i++) {
      assertTrue(cloned.isShared(i),
          "Container " + i + " should still be shared after trim");
    }

    // Mutate cloned — should COW correctly
    cloned.add(42);
    assertArrayEquals(originalBefore, original.toArray(),
        "Original was corrupted after trim + mutation on clone");
  }

  // -----------------------------------------------------------------------
  // Bug hunting: clear after sharing preserves other bitmap
  // -----------------------------------------------------------------------

  @Test
  public void clearAfterSharingDoesNotCorruptOtherBitmap() {
    PersistentRoaringBitmap a = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap b = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(5, 10, 99));

    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(a, b);
    int[] resultBefore = result.toArray();

    // Clear one of the inputs
    a.clear();
    assertTrue(a.isEmpty());

    // Result must be unchanged
    assertArrayEquals(resultBefore, result.toArray(),
        "Result was corrupted when input was cleared");
  }

  // -----------------------------------------------------------------------
  // Bug hunting: multiple clones creating a chain of sharing
  // -----------------------------------------------------------------------

  @Test
  public void multipleCloneChainPreservesCow() {
    PersistentRoaringBitmap a = PersistentRoaringBitmap.fromBitmap(
        PersistentRoaringBitmap.bitmapOf(1, 2, 3));
    PersistentRoaringBitmap b = a.clone();
    PersistentRoaringBitmap c = b.clone();
    int[] aOriginal = a.toArray();

    // Mutate c — should not affect a or b
    c.add(4);
    c.remove(1);

    assertArrayEquals(aOriginal, a.toArray(), "a was corrupted by c mutation");
    assertArrayEquals(aOriginal, b.toArray(), "b was corrupted by c mutation");
  }

  // -----------------------------------------------------------------------
  // Bug hunting: flip(long, long) inserting new containers in cloned bitmap
  // -----------------------------------------------------------------------

  @Test
  public void rangeFlipInsertingNewContainersInClonedBitmap() {
    // Create bitmap with keys 0 and 4 (gap at keys 1, 2, 3)
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    rb.add(0 << 16 | 1);
    rb.add(4 << 16 | 1);
    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap cloned = original.clone();
    int[] originalBefore = original.toArray();

    // Flip a range that covers keys 1, 2, 3 (where no containers exist)
    // This should INSERT new containers for those keys
    long start = 1L << 16;
    long end = 4L << 16; // up to but not including key 4
    cloned.flip(start, end);

    // Cloned should now have containers at keys 0, 1, 2, 3, 4
    assertEquals(5, cloned.getContainerCount());

    // Key 0 and 4 should still be shared
    assertTrue(cloned.isShared(0), "Container at key 0 should still be shared");
    // New containers should NOT be shared
    assertFalse(cloned.isShared(1), "New container at key 1 should not be shared");
    assertFalse(cloned.isShared(2), "New container at key 2 should not be shared");
    assertFalse(cloned.isShared(3), "New container at key 3 should not be shared");
    assertTrue(cloned.isShared(4), "Container at key 4 should still be shared");

    // Original must be unchanged
    assertArrayEquals(originalBefore, original.toArray(),
        "Original was corrupted by range flip on clone");
  }

  // -----------------------------------------------------------------------
  // Bug hunting: orNot on cloned bitmap
  // -----------------------------------------------------------------------

  @Test
  public void orNotOnClonedBitmapDoesNotCorruptOriginal() {
    PersistentRoaringBitmap rb = buildMultiContainerBitmap(0, 3, 42);
    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(rb);
    PersistentRoaringBitmap cloned = original.clone();
    int[] originalBefore = original.toArray();

    PersistentRoaringBitmap other = buildMultiContainerBitmap(1, 4, 99);
    cloned.orNot(other, 3L << 16);

    assertArrayEquals(originalBefore, original.toArray(),
        "Original was corrupted by orNot on clone");
  }

  // =======================================================================
  // Parameterized correctness: static ops match PersistentRoaringBitmap
  // =======================================================================

  static Stream<Arguments> bitmapPairs() {
    return Stream.of(
        // empty x empty
        Arguments.of(new PersistentRoaringBitmap(), new PersistentRoaringBitmap(), "empty_x_empty"),
        // empty x non-empty
        Arguments.of(new PersistentRoaringBitmap(), PersistentRoaringBitmap.bitmapOf(1, 2, 3), "empty_x_small"),
        // non-empty x empty
        Arguments.of(PersistentRoaringBitmap.bitmapOf(1, 2, 3), new PersistentRoaringBitmap(), "small_x_empty"),
        // same single container, sparse
        Arguments.of(
            PersistentRoaringBitmap.bitmapOf(1, 10, 100, 1000),
            PersistentRoaringBitmap.bitmapOf(5, 50, 500, 5000),
            "sparse_same_container"),
        // same single container, identical
        Arguments.of(
            PersistentRoaringBitmap.bitmapOf(1, 2, 3),
            PersistentRoaringBitmap.bitmapOf(1, 2, 3),
            "identical_single"),
        // dense single container (BitmapContainer)
        Arguments.of(denseRange(0, 10000), denseRange(5000, 15000), "dense_overlap"),
        // dense disjoint
        Arguments.of(denseRange(0, 5000), denseRange(10000, 15000), "dense_disjoint"),
        // run containers
        Arguments.of(runBitmap(0, 1000), runBitmap(500, 1500), "run_overlap"),
        // run disjoint
        Arguments.of(runBitmap(0, 1000), runBitmap(2000, 3000), "run_disjoint"),
        // multi-container overlapping
        Arguments.of(
            buildMultiContainerBitmap(0, 10, 42),
            buildMultiContainerBitmap(5, 15, 99),
            "multi_overlap"),
        // multi-container disjoint
        Arguments.of(
            buildMultiContainerBitmap(0, 5, 42),
            buildMultiContainerBitmap(5, 10, 99),
            "multi_disjoint"),
        // multi-container fully overlapping
        Arguments.of(
            buildMultiContainerBitmap(0, 10, 42),
            buildMultiContainerBitmap(0, 10, 99),
            "multi_full_overlap"),
        // high unsigned keys
        Arguments.of(
            highKeyBitmap(0xFFF0, 0xFFF5, 42),
            highKeyBitmap(0xFFF3, 0xFFF8, 99),
            "high_keys"),
        // mixed container types: sparse + dense
        Arguments.of(
            PersistentRoaringBitmap.bitmapOf(1, 100, 1000),
            denseRange(0, 10000),
            "sparse_x_dense"),
        // mixed: dense + run
        Arguments.of(denseRange(0, 5000), runBitmap(0, 10000), "dense_x_run"),
        // single element per container, many containers
        Arguments.of(
            singleElementPerContainer(0, 20),
            singleElementPerContainer(10, 30),
            "single_per_container"),
        // subset relationship
        Arguments.of(
            PersistentRoaringBitmap.bitmapOf(1, 2, 3, 4, 5),
            PersistentRoaringBitmap.bitmapOf(2, 4),
            "superset_x_subset")
    );
  }

  @ParameterizedTest(name = "staticOr: {2}")
  @MethodSource("bitmapPairs")
  public void paramStaticOrMatchesRoaringBitmap(PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);
    int[] aBefore = ca.toArray();
    int[] bBefore = cb.toArray();

    PersistentRoaringBitmap expected = PersistentRoaringBitmap.or(a, b);
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);

    assertArrayEquals(expected.toArray(), result.toArray(), "OR result mismatch");
    assertEquals(expected.getCardinality(), result.getCardinality(), "OR cardinality mismatch");
    assertArrayEquals(aBefore, ca.toArray(), "input a corrupted");
    assertArrayEquals(bBefore, cb.toArray(), "input b corrupted");
  }

  @ParameterizedTest(name = "staticAnd: {2}")
  @MethodSource("bitmapPairs")
  public void paramStaticAndMatchesRoaringBitmap(PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);

    PersistentRoaringBitmap expected = PersistentRoaringBitmap.and(a, b);
    PersistentRoaringBitmap result = PersistentRoaringBitmap.and(ca, cb);

    assertArrayEquals(expected.toArray(), result.toArray(), "AND result mismatch");
    assertEquals(expected.getCardinality(), result.getCardinality(), "AND cardinality mismatch");
  }

  @ParameterizedTest(name = "staticXor: {2}")
  @MethodSource("bitmapPairs")
  public void paramStaticXorMatchesRoaringBitmap(PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);

    PersistentRoaringBitmap expected = PersistentRoaringBitmap.xor(a, b);
    PersistentRoaringBitmap result = PersistentRoaringBitmap.xor(ca, cb);

    assertArrayEquals(expected.toArray(), result.toArray(), "XOR result mismatch");
    assertEquals(expected.getCardinality(), result.getCardinality(), "XOR cardinality mismatch");
  }

  @ParameterizedTest(name = "staticAndNot: {2}")
  @MethodSource("bitmapPairs")
  public void paramStaticAndNotMatchesRoaringBitmap(PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);

    PersistentRoaringBitmap expected = PersistentRoaringBitmap.andNot(a, b);
    PersistentRoaringBitmap result = PersistentRoaringBitmap.andNot(ca, cb);

    assertArrayEquals(expected.toArray(), result.toArray(), "ANDNOT result mismatch");
    assertEquals(expected.getCardinality(), result.getCardinality(), "ANDNOT cardinality mismatch");
  }

  // =======================================================================
  // Parameterized correctness: in-place ops match PersistentRoaringBitmap
  // =======================================================================

  @ParameterizedTest(name = "inPlaceOr: {2}")
  @MethodSource("bitmapPairs")
  public void paramInPlaceOrMatchesRoaringBitmap(PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap expected = a.clone();
    expected.or(b);

    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(a);
    cow.or(b);

    assertArrayEquals(expected.toArray(), cow.toArray(), "in-place OR result mismatch");
  }

  @ParameterizedTest(name = "inPlaceAnd: {2}")
  @MethodSource("bitmapPairs")
  public void paramInPlaceAndMatchesRoaringBitmap(PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap expected = a.clone();
    expected.and(b);

    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(a);
    cow.and(b);

    assertArrayEquals(expected.toArray(), cow.toArray(), "in-place AND result mismatch");
  }

  @ParameterizedTest(name = "inPlaceXor: {2}")
  @MethodSource("bitmapPairs")
  public void paramInPlaceXorMatchesRoaringBitmap(PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap expected = a.clone();
    expected.xor(b);

    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(a);
    cow.xor(b);

    assertArrayEquals(expected.toArray(), cow.toArray(), "in-place XOR result mismatch");
  }

  @ParameterizedTest(name = "inPlaceAndNot: {2}")
  @MethodSource("bitmapPairs")
  public void paramInPlaceAndNotMatchesRoaringBitmap(PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap expected = a.clone();
    expected.andNot(b);

    PersistentRoaringBitmap cow = PersistentRoaringBitmap.fromBitmap(a);
    cow.andNot(b);

    assertArrayEquals(expected.toArray(), cow.toArray(), "in-place ANDNOT result mismatch");
  }

  // =======================================================================
  // Parameterized: in-place ops on SHARED (cloned) bitmaps + isolation
  // =======================================================================

  @ParameterizedTest(name = "inPlaceOrSharedIsolation: {2}")
  @MethodSource("bitmapPairs")
  public void paramInPlaceOrOnSharedDoesNotCorrupt(
      PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cow = original.clone();
    int[] originalBefore = original.toArray();

    PersistentRoaringBitmap expected = a.clone();
    expected.or(b);

    cow.or(b);

    assertArrayEquals(expected.toArray(), cow.toArray(), "in-place OR result mismatch");
    assertArrayEquals(originalBefore, original.toArray(), "original corrupted by in-place OR");
  }

  @ParameterizedTest(name = "inPlaceAndSharedIsolation: {2}")
  @MethodSource("bitmapPairs")
  public void paramInPlaceAndOnSharedDoesNotCorrupt(
      PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cow = original.clone();
    int[] originalBefore = original.toArray();

    cow.and(b);

    assertArrayEquals(originalBefore, original.toArray(), "original corrupted by in-place AND");
  }

  @ParameterizedTest(name = "inPlaceXorSharedIsolation: {2}")
  @MethodSource("bitmapPairs")
  public void paramInPlaceXorOnSharedDoesNotCorrupt(
      PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cow = original.clone();
    int[] originalBefore = original.toArray();

    cow.xor(b);

    assertArrayEquals(originalBefore, original.toArray(), "original corrupted by in-place XOR");
  }

  @ParameterizedTest(name = "inPlaceAndNotSharedIsolation: {2}")
  @MethodSource("bitmapPairs")
  public void paramInPlaceAndNotOnSharedDoesNotCorrupt(
      PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cow = original.clone();
    int[] originalBefore = original.toArray();

    cow.andNot(b);

    assertArrayEquals(originalBefore, original.toArray(),
        "original corrupted by in-place ANDNOT");
  }

  // =======================================================================
  // Parameterized: commutativity
  // =======================================================================

  @ParameterizedTest(name = "orCommutativity: {2}")
  @MethodSource("bitmapPairs")
  public void paramOrIsCommutative(PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);

    PersistentRoaringBitmap ab = PersistentRoaringBitmap.or(ca, cb);
    PersistentRoaringBitmap ba = PersistentRoaringBitmap.or(cb, ca);

    assertArrayEquals(ab.toArray(), ba.toArray(), "or(a,b) != or(b,a)");
  }

  @ParameterizedTest(name = "andCommutativity: {2}")
  @MethodSource("bitmapPairs")
  public void paramAndIsCommutative(PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);

    PersistentRoaringBitmap ab = PersistentRoaringBitmap.and(ca, cb);
    PersistentRoaringBitmap ba = PersistentRoaringBitmap.and(cb, ca);

    assertArrayEquals(ab.toArray(), ba.toArray(), "and(a,b) != and(b,a)");
  }

  @ParameterizedTest(name = "xorCommutativity: {2}")
  @MethodSource("bitmapPairs")
  public void paramXorIsCommutative(PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);

    PersistentRoaringBitmap ab = PersistentRoaringBitmap.xor(ca, cb);
    PersistentRoaringBitmap ba = PersistentRoaringBitmap.xor(cb, ca);

    assertArrayEquals(ab.toArray(), ba.toArray(), "xor(a,b) != xor(b,a)");
  }

  // =======================================================================
  // Parameterized: cardinality operations match PersistentRoaringBitmap
  // =======================================================================

  @ParameterizedTest(name = "cardinalityOps: {2}")
  @MethodSource("bitmapPairs")
  public void paramCardinalityOpsMatchRoaringBitmap(
      PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);

    assertEquals(PersistentRoaringBitmap.andCardinality(a, b),
        PersistentRoaringBitmap.andCardinality(ca, cb), "andCardinality mismatch");
    assertEquals(PersistentRoaringBitmap.orCardinality(a, b),
        PersistentRoaringBitmap.orCardinality(ca, cb), "orCardinality mismatch");
    assertEquals(PersistentRoaringBitmap.xorCardinality(a, b),
        PersistentRoaringBitmap.xorCardinality(ca, cb), "xorCardinality mismatch");
    assertEquals(PersistentRoaringBitmap.andNotCardinality(a, b),
        PersistentRoaringBitmap.andNotCardinality(ca, cb), "andNotCardinality mismatch");
    assertEquals(PersistentRoaringBitmap.intersects(a, b),
        PersistentRoaringBitmap.intersects(ca, cb), "intersects mismatch");
  }

  // =======================================================================
  // Parameterized: query methods (contains, select, rank, iterator)
  // =======================================================================

  @ParameterizedTest(name = "queryMethods: {2}")
  @MethodSource("bitmapPairs")
  public void paramQueryMethodsMatchAfterStaticOr(
      PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap expected = PersistentRoaringBitmap.or(a, b);
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);
    PersistentRoaringBitmap result = PersistentRoaringBitmap.or(ca, cb);

    // cardinality
    assertEquals(expected.getCardinality(), result.getCardinality(), "cardinality mismatch");
    assertEquals(expected.getLongCardinality(), result.getLongCardinality(),
        "longCardinality mismatch");
    assertEquals(expected.isEmpty(), result.isEmpty(), "isEmpty mismatch");

    // contains
    int[] expectedArr = expected.toArray();
    for (int val : expectedArr) {
      assertTrue(result.contains(val), "missing value " + val);
    }
    // contains for values NOT in the bitmap
    int[] resultArr = result.toArray();
    if (resultArr.length > 0) {
      // Check a value just before the first and after the last
      int first = resultArr[0];
      if (first > 0) {
        assertEquals(expected.contains(first - 1), result.contains(first - 1));
      }
    }

    // first/last
    if (!expected.isEmpty()) {
      assertEquals(expected.first(), result.first(), "first() mismatch");
      assertEquals(expected.last(), result.last(), "last() mismatch");
    }

    // iterator consistency
    List<Integer> iterValues = new ArrayList<>();
    Iterator<Integer> it = result.iterator();
    while (it.hasNext()) {
      iterValues.add(it.next());
    }
    assertEquals(expectedArr.length, iterValues.size(), "iterator size mismatch");
    for (int i = 0; i < expectedArr.length; i++) {
      assertEquals(expectedArr[i], iterValues.get(i).intValue(),
          "iterator value mismatch at index " + i);
    }

    // select + rank for a few positions
    if (expectedArr.length > 0) {
      int[] positions = {0, expectedArr.length / 4, expectedArr.length / 2,
          3 * expectedArr.length / 4, expectedArr.length - 1};
      for (int pos : positions) {
        if (pos >= 0 && pos < expectedArr.length) {
          assertEquals(expected.select(pos), result.select(pos),
              "select(" + pos + ") mismatch");
          int val = expectedArr[pos];
          assertEquals(expected.rank(val), result.rank(val),
              "rank(" + val + ") mismatch");
        }
      }
    }
  }

  // =======================================================================
  // Parameterized: andNot(a,b) == and(a, xor(a,b)) ∩ a  (set identity)
  // Actually: andNot(a,b) == xor(a, and(a,b))
  // =======================================================================

  @ParameterizedTest(name = "andNotIdentity: {2}")
  @MethodSource("bitmapPairs")
  public void paramAndNotEqualsXorOfAAndIntersection(
      PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);

    // andNot(a, b)
    PersistentRoaringBitmap andNot = PersistentRoaringBitmap.andNot(ca, cb);

    // xor(a, and(a, b)) should equal andNot(a, b)
    PersistentRoaringBitmap intersection = PersistentRoaringBitmap.and(ca, cb);
    PersistentRoaringBitmap xorResult = PersistentRoaringBitmap.xor(ca, intersection);

    assertArrayEquals(andNot.toArray(), xorResult.toArray(),
        "andNot(a,b) != xor(a, and(a,b))");
  }

  // =======================================================================
  // Parameterized: or(a,b) == xor(a,b) | and(a,b)  (disjoint union + intersection)
  // =======================================================================

  @ParameterizedTest(name = "orDecomposition: {2}")
  @MethodSource("bitmapPairs")
  public void paramOrEqualsXorUnionAnd(PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);

    PersistentRoaringBitmap orResult = PersistentRoaringBitmap.or(ca, cb);

    PersistentRoaringBitmap xorResult = PersistentRoaringBitmap.xor(ca, cb);
    PersistentRoaringBitmap andResult = PersistentRoaringBitmap.and(ca, cb);
    PersistentRoaringBitmap reconstructed = PersistentRoaringBitmap.or(xorResult, andResult);

    assertArrayEquals(orResult.toArray(), reconstructed.toArray(),
        "or(a,b) != or(xor(a,b), and(a,b))");
  }

  // =======================================================================
  // Parameterized: cardinality identity |A ∪ B| = |A| + |B| - |A ∩ B|
  // =======================================================================

  @ParameterizedTest(name = "inclusionExclusion: {2}")
  @MethodSource("bitmapPairs")
  public void paramInclusionExclusionPrinciple(
      PersistentRoaringBitmap a, PersistentRoaringBitmap b, String desc) {
    PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(a);
    PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(b);

    long orCard = PersistentRoaringBitmap.or(ca, cb).getLongCardinality();
    long aCard = ca.getLongCardinality();
    long bCard = cb.getLongCardinality();
    long andCard = PersistentRoaringBitmap.and(ca, cb).getLongCardinality();

    assertEquals(aCard + bCard - andCard, orCard,
        "|A∪B| != |A| + |B| - |A∩B|");
  }

  // =======================================================================
  // Enhanced fuzz test: all in-place ops including xor, andNot, lazyor, addN
  // =======================================================================

  @ParameterizedTest(name = "comprehensiveFuzz_seed={0}")
  @MethodSource("fuzzSeeds")
  public void fuzzComprehensiveOpsMatchRoaringBitmap(long seed) {
    Random rng = new Random(seed);
    PersistentRoaringBitmap reference = new PersistentRoaringBitmap();
    PersistentRoaringBitmap cow = new PersistentRoaringBitmap();

    for (int iter = 0; iter < 1000; iter++) {
      int op = rng.nextInt(14);
      switch (op) {
        case 0: { // add single
          int val = rng.nextInt(8) << 16 | rng.nextInt(2000);
          reference.add(val);
          cow.add(val);
          break;
        }
        case 1: { // remove single
          int val = rng.nextInt(8) << 16 | rng.nextInt(2000);
          reference.remove(val);
          cow.remove(val);
          break;
        }
        case 2: { // flip single
          int val = rng.nextInt(8) << 16 | rng.nextInt(2000);
          reference.flip(val);
          cow.flip(val);
          break;
        }
        case 3: { // checkedAdd
          int val = rng.nextInt(8) << 16 | rng.nextInt(2000);
          boolean refR = reference.checkedAdd(val);
          boolean cowR = cow.checkedAdd(val);
          assertEquals(refR, cowR, "checkedAdd mismatch at iter " + iter);
          break;
        }
        case 4: { // checkedRemove
          int val = rng.nextInt(8) << 16 | rng.nextInt(2000);
          boolean refR = reference.checkedRemove(val);
          boolean cowR = cow.checkedRemove(val);
          assertEquals(refR, cowR, "checkedRemove mismatch at iter " + iter);
          break;
        }
        case 5: { // range add
          int key = rng.nextInt(8);
          long start = ((long) key << 16) + rng.nextInt(500);
          long end = start + rng.nextInt(500);
          reference.add(start, end);
          cow.add(start, end);
          break;
        }
        case 6: { // range remove
          int key = rng.nextInt(8);
          long start = ((long) key << 16) + rng.nextInt(500);
          long end = start + rng.nextInt(500);
          reference.remove(start, end);
          cow.remove(start, end);
          break;
        }
        case 7: { // range flip
          int key = rng.nextInt(8);
          long start = ((long) key << 16) + rng.nextInt(500);
          long end = start + rng.nextInt(500);
          reference.flip(start, end);
          cow.flip(start, end);
          break;
        }
        case 8: { // in-place OR
          PersistentRoaringBitmap extra = randomSmallBitmap(rng);
          reference.or(extra);
          cow.or(extra);
          break;
        }
        case 9: { // in-place AND
          PersistentRoaringBitmap mask = randomSmallBitmap(rng);
          reference.and(mask);
          cow.and(mask);
          break;
        }
        case 10: { // in-place XOR
          PersistentRoaringBitmap mask = randomSmallBitmap(rng);
          reference.xor(mask);
          cow.xor(mask);
          break;
        }
        case 11: { // in-place ANDNOT
          PersistentRoaringBitmap mask = randomSmallBitmap(rng);
          reference.andNot(mask);
          cow.andNot(mask);
          break;
        }
        case 12: { // addN
          int count = rng.nextInt(20) + 1;
          int[] dat = new int[count];
          for (int j = 0; j < count; j++) {
            dat[j] = rng.nextInt(8) << 16 | rng.nextInt(2000);
          }
          // addN requires sorted input
          java.util.Arrays.sort(dat);
          reference.addN(dat, 0, dat.length);
          cow.addN(dat, 0, dat.length);
          break;
        }
        case 13: { // clone + mutate (COW stress)
          PersistentRoaringBitmap snapshot = cow.clone();
          int val = rng.nextInt(8) << 16 | rng.nextInt(2000);
          reference.add(val);
          cow.add(val);
          // snapshot should be unaffected (not checked here, just exercises COW paths)
          break;
        }
      }
      assertArrayEquals(reference.toArray(), cow.toArray(),
          "Mismatch after op " + op + " at iteration " + iter + " (seed=" + seed + ")");
    }
  }

  // =======================================================================
  // Fuzz test: operations on shared bitmaps (static op result + in-place)
  // =======================================================================

  @ParameterizedTest(name = "fuzzSharedOps_seed={0}")
  @MethodSource("fuzzSeeds")
  public void fuzzInPlaceOpsOnSharedBitmapIsolation(long seed) {
    Random rng = new Random(seed);

    for (int trial = 0; trial < 50; trial++) {
      PersistentRoaringBitmap rbA = randomMultiContainerBitmap(rng);
      PersistentRoaringBitmap rbB = randomMultiContainerBitmap(rng);

      PersistentRoaringBitmap cowA = PersistentRoaringBitmap.fromBitmap(rbA);
      PersistentRoaringBitmap cowB = PersistentRoaringBitmap.fromBitmap(rbB);

      // Create shared result via static OR
      PersistentRoaringBitmap result = PersistentRoaringBitmap.or(cowA, cowB);
      int[] aOriginal = cowA.toArray();
      int[] bOriginal = cowB.toArray();

      PersistentRoaringBitmap extra = randomSmallBitmap(rng);
      PersistentRoaringBitmap refResult = PersistentRoaringBitmap.or(rbA, rbB);

      // Pick a random in-place op
      int op = rng.nextInt(4);
      switch (op) {
        case 0:
          refResult.or(extra);
          result.or(extra);
          break;
        case 1:
          refResult.and(extra);
          result.and(extra);
          break;
        case 2:
          refResult.xor(extra);
          result.xor(extra);
          break;
        case 3:
          refResult.andNot(extra);
          result.andNot(extra);
          break;
      }

      assertArrayEquals(refResult.toArray(), result.toArray(),
          "Result mismatch at trial " + trial + " op " + op + " (seed=" + seed + ")");
      assertArrayEquals(aOriginal, cowA.toArray(),
          "cowA corrupted at trial " + trial + " (seed=" + seed + ")");
      assertArrayEquals(bOriginal, cowB.toArray(),
          "cowB corrupted at trial " + trial + " (seed=" + seed + ")");
    }
  }

  // =======================================================================
  // Fuzz test: query methods consistency after random ops
  // =======================================================================

  @ParameterizedTest(name = "fuzzQueryConsistency_seed={0}")
  @MethodSource("fuzzSeeds")
  public void fuzzQueryMethodsMatchRoaringBitmapAfterRandomOps(long seed) {
    Random rng = new Random(seed);
    PersistentRoaringBitmap reference = new PersistentRoaringBitmap();
    PersistentRoaringBitmap cow = new PersistentRoaringBitmap();

    // Build up bitmaps with random ops
    for (int i = 0; i < 500; i++) {
      int op = rng.nextInt(6);
      int val = rng.nextInt(8) << 16 | rng.nextInt(2000);
      switch (op) {
        case 0: reference.add(val); cow.add(val); break;
        case 1: reference.remove(val); cow.remove(val); break;
        case 2: reference.flip(val); cow.flip(val); break;
        case 3: {
          PersistentRoaringBitmap extra = randomSmallBitmap(rng);
          reference.or(extra); cow.or(extra);
          break;
        }
        case 4: {
          PersistentRoaringBitmap mask = randomSmallBitmap(rng);
          reference.and(mask); cow.and(mask);
          break;
        }
        case 5: {
          PersistentRoaringBitmap mask = randomSmallBitmap(rng);
          reference.xor(mask); cow.xor(mask);
          break;
        }
      }
    }

    // Verify all query methods
    assertArrayEquals(reference.toArray(), cow.toArray(), "toArray mismatch");
    assertEquals(reference.getCardinality(), cow.getCardinality(), "cardinality mismatch");
    assertEquals(reference.isEmpty(), cow.isEmpty(), "isEmpty mismatch");

    // contains for random probes
    for (int i = 0; i < 200; i++) {
      int probe = rng.nextInt(8) << 16 | rng.nextInt(2000);
      assertEquals(reference.contains(probe), cow.contains(probe),
          "contains(" + probe + ") mismatch");
    }

    // first/last
    if (!reference.isEmpty()) {
      assertEquals(reference.first(), cow.first(), "first mismatch");
      assertEquals(reference.last(), cow.last(), "last mismatch");
    }

    // select + rank
    int[] refArr = reference.toArray();
    if (refArr.length > 0) {
      for (int i = 0; i < Math.min(50, refArr.length); i++) {
        int pos = rng.nextInt(refArr.length);
        assertEquals(reference.select(pos), cow.select(pos), "select(" + pos + ") mismatch");
      }
      for (int i = 0; i < 50; i++) {
        int probe = rng.nextInt(8) << 16 | rng.nextInt(2000);
        assertEquals(reference.rank(probe), cow.rank(probe), "rank(" + probe + ") mismatch");
      }
    }

    // nextValue / previousValue
    for (int i = 0; i < 50; i++) {
      int fromValue = rng.nextInt(8) << 16 | rng.nextInt(2000);
      assertEquals(reference.nextValue(fromValue), cow.nextValue(fromValue),
          "nextValue(" + fromValue + ") mismatch");
      assertEquals(reference.previousValue(fromValue), cow.previousValue(fromValue),
          "previousValue(" + fromValue + ") mismatch");
    }

    // iterator
    Iterator<Integer> refIt = reference.iterator();
    Iterator<Integer> cowIt = cow.iterator();
    while (refIt.hasNext()) {
      assertTrue(cowIt.hasNext(), "cow iterator ended early");
      assertEquals(refIt.next(), cowIt.next(), "iterator value mismatch");
    }
    assertFalse(cowIt.hasNext(), "cow iterator has extra values");
  }

  // =======================================================================
  // Fuzz test: chained static ops (associativity)
  // =======================================================================

  @ParameterizedTest(name = "fuzzAssociativity_seed={0}")
  @MethodSource("fuzzSeeds")
  public void fuzzStaticOrIsAssociative(long seed) {
    Random rng = new Random(seed);

    for (int trial = 0; trial < 30; trial++) {
      PersistentRoaringBitmap rbA = randomMultiContainerBitmap(rng);
      PersistentRoaringBitmap rbB = randomMultiContainerBitmap(rng);
      PersistentRoaringBitmap rbC = randomMultiContainerBitmap(rng);

      PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(rbA);
      PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(rbB);
      PersistentRoaringBitmap cc = PersistentRoaringBitmap.fromBitmap(rbC);

      // or(or(a,b), c) == or(a, or(b,c))
      PersistentRoaringBitmap left = PersistentRoaringBitmap.or(
          PersistentRoaringBitmap.or(ca, cb), cc);
      PersistentRoaringBitmap right = PersistentRoaringBitmap.or(
          ca, PersistentRoaringBitmap.or(cb, cc));

      assertArrayEquals(left.toArray(), right.toArray(),
          "or associativity failed at trial " + trial);
    }
  }

  @ParameterizedTest(name = "fuzzAndAssociativity_seed={0}")
  @MethodSource("fuzzSeeds")
  public void fuzzStaticAndIsAssociative(long seed) {
    Random rng = new Random(seed);

    for (int trial = 0; trial < 30; trial++) {
      PersistentRoaringBitmap rbA = randomMultiContainerBitmap(rng);
      PersistentRoaringBitmap rbB = randomMultiContainerBitmap(rng);
      PersistentRoaringBitmap rbC = randomMultiContainerBitmap(rng);

      PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(rbA);
      PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(rbB);
      PersistentRoaringBitmap cc = PersistentRoaringBitmap.fromBitmap(rbC);

      PersistentRoaringBitmap left = PersistentRoaringBitmap.and(
          PersistentRoaringBitmap.and(ca, cb), cc);
      PersistentRoaringBitmap right = PersistentRoaringBitmap.and(
          ca, PersistentRoaringBitmap.and(cb, cc));

      assertArrayEquals(left.toArray(), right.toArray(),
          "and associativity failed at trial " + trial);
    }
  }

  // =======================================================================
  // Fuzz test: COW clone isolation under random mixed ops
  // =======================================================================

  @ParameterizedTest(name = "fuzzCloneIsolation_seed={0}")
  @MethodSource("fuzzSeeds")
  public void fuzzCloneIsolationUnderMixedOps(long seed) {
    Random rng = new Random(seed);

    PersistentRoaringBitmap original = PersistentRoaringBitmap.fromBitmap(
        randomMultiContainerBitmap(rng));

    for (int iter = 0; iter < 100; iter++) {
      int[] originalBefore = original.toArray();
      PersistentRoaringBitmap cloned = original.clone();

      // Apply random mutations to the clone
      int numMutations = rng.nextInt(30) + 1;
      for (int j = 0; j < numMutations; j++) {
        int op = rng.nextInt(8);
        switch (op) {
          case 0: cloned.add(rng.nextInt(8) << 16 | rng.nextInt(2000)); break;
          case 1: cloned.remove(rng.nextInt(8) << 16 | rng.nextInt(2000)); break;
          case 2: cloned.flip(rng.nextInt(8) << 16 | rng.nextInt(2000)); break;
          case 3: cloned.or(randomSmallBitmap(rng)); break;
          case 4: cloned.and(randomSmallBitmap(rng)); break;
          case 5: cloned.xor(randomSmallBitmap(rng)); break;
          case 6: cloned.andNot(randomSmallBitmap(rng)); break;
          case 7: {
            int key = rng.nextInt(8);
            long start = ((long) key << 16) + rng.nextInt(500);
            long end = start + rng.nextInt(500);
            cloned.add(start, end);
            break;
          }
        }
      }

      assertArrayEquals(originalBefore, original.toArray(),
          "Original corrupted at iteration " + iter + " (seed=" + seed + ")");
    }
  }

  // =======================================================================
  // Fuzz test: static ops on COW bitmaps that were themselves built via ops
  // (multi-level sharing)
  // =======================================================================

  @ParameterizedTest(name = "fuzzMultiLevelSharing_seed={0}")
  @MethodSource("fuzzSeeds")
  public void fuzzMultiLevelSharingCorrectness(long seed) {
    Random rng = new Random(seed);

    for (int trial = 0; trial < 30; trial++) {
      PersistentRoaringBitmap rbA = randomMultiContainerBitmap(rng);
      PersistentRoaringBitmap rbB = randomMultiContainerBitmap(rng);
      PersistentRoaringBitmap rbC = randomMultiContainerBitmap(rng);

      PersistentRoaringBitmap ca = PersistentRoaringBitmap.fromBitmap(rbA);
      PersistentRoaringBitmap cb = PersistentRoaringBitmap.fromBitmap(rbB);
      PersistentRoaringBitmap cc = PersistentRoaringBitmap.fromBitmap(rbC);

      // Build a result from static ops — containers are shared
      PersistentRoaringBitmap orAB = PersistentRoaringBitmap.or(ca, cb);
      // Use the result of the first op as input to a second op
      PersistentRoaringBitmap result = PersistentRoaringBitmap.and(orAB, cc);

      // Reference
      PersistentRoaringBitmap refOrAB = PersistentRoaringBitmap.or(rbA, rbB);
      PersistentRoaringBitmap refResult = PersistentRoaringBitmap.and(refOrAB, rbC);

      assertArrayEquals(refResult.toArray(), result.toArray(),
          "multi-level static op mismatch at trial " + trial);

      // Now mutate result — all inputs must be unaffected
      if (!result.isEmpty()) {
        result.add(42);
      }
      int[] caCheck = ca.toArray();
      int[] cbCheck = cb.toArray();
      assertArrayEquals(rbA.toArray(), caCheck,
          "ca corrupted after multi-level op + mutation at trial " + trial);
      assertArrayEquals(rbB.toArray(), cbCheck,
          "cb corrupted after multi-level op + mutation at trial " + trial);
    }
  }

  // =======================================================================
  // Helper: seed provider for parameterized fuzz tests
  // =======================================================================

  static Stream<Arguments> fuzzSeeds() {
    return Stream.of(
        Arguments.of(12345L),
        Arguments.of(67890L),
        Arguments.of(11111L),
        Arguments.of(99999L),
        Arguments.of(42L),
        Arguments.of(0L),
        Arguments.of(Long.MAX_VALUE),
        Arguments.of(0xDEADBEEFL)
    );
  }

  // =======================================================================
  // Helpers for parameterized tests
  // =======================================================================

  private static PersistentRoaringBitmap denseRange(int start, int end) {
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    rb.add((long) start, (long) end);
    return rb;
  }

  private static PersistentRoaringBitmap runBitmap(int start, int end) {
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    rb.add((long) start, (long) end);
    rb.runOptimize();
    return rb;
  }

  private static PersistentRoaringBitmap highKeyBitmap(int startKey, int endKey, int seed) {
    Random random = new Random(seed);
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    for (int key = startKey; key < endKey; key++) {
      int base = key << 16;
      for (int j = 0; j < 200; j++) {
        rb.add(base + random.nextInt(65536));
      }
    }
    return rb;
  }

  private static PersistentRoaringBitmap singleElementPerContainer(int startKey, int endKey) {
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    for (int key = startKey; key < endKey; key++) {
      rb.add(key << 16 | 1);
    }
    return rb;
  }

  private static PersistentRoaringBitmap randomSmallBitmap(Random rng) {
    PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
    int count = rng.nextInt(50) + 1;
    for (int j = 0; j < count; j++) {
      rb.add(rng.nextInt(8) << 16 | rng.nextInt(2000));
    }
    return rb;
  }

  private static PersistentRoaringBitmap randomMultiContainerBitmap(Random rng) {
    int startKey = rng.nextInt(5);
    int endKey = startKey + rng.nextInt(5) + 1;
    return buildMultiContainerBitmap(startKey, endKey, rng.nextInt(100000));
  }

  // -----------------------------------------------------------------------
  // Bug hunting: deserialization on a bitmap with shared containers
  // -----------------------------------------------------------------------

  @Test
  public void deserializeOverSharedBitmapResetsCorrectly() throws IOException {
    // Create two bitmaps sharing containers
    PersistentRoaringBitmap a = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 5, 42));
    PersistentRoaringBitmap b = a.clone();
    int[] aBefore = a.toArray();

    // Serialize a different bitmap
    PersistentRoaringBitmap different = buildMultiContainerBitmap(0, 3, 99);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    different.serialize(new DataOutputStream(baos));
    byte[] bytes = baos.toByteArray();

    // Deserialize into b (which shares containers with a)
    b.deserialize(new DataInputStream(new ByteArrayInputStream(bytes)));

    // a must be unchanged
    assertArrayEquals(aBefore, a.toArray(),
        "a was corrupted when its clone was deserialized over");

    // b should have the new content
    assertArrayEquals(different.toArray(), b.toArray(),
        "b does not have the deserialized content");

    // b should have no shared containers
    for (int i = 0; i < b.getContainerCount(); i++) {
      assertFalse(b.isShared(i),
          "Container " + i + " should not be shared after deserialize");
    }
  }

  // -----------------------------------------------------------------------
  // Undersized shared[] from all-owned static builders
  //
  // Static builders such as flip / add(range) / addOffset construct their
  // result by appending freshly-owned containers, so the result's internal
  // shared[] flag array can be left shorter than the container array. A later
  // in-place mutation that shifts or drops containers must grow shared[] to
  // match (via ensureSharedCapacity) before indexing into it. These tests
  // drive the shifting helpers (sharedRemoveAt / sharedRemoveRange) and the
  // "index past shared[] is treated as owned" path of copyIfShared.
  // -----------------------------------------------------------------------

  @Test
  public void removeContainerFromStaticFlipResultWithUndersizedSharedArray() {
    // A single-element seed flipped over keys 0..7 yields a fully-populated
    // result with one owned container per key (> 4 containers, no shared[]).
    final PersistentRoaringBitmap seed = new PersistentRoaringBitmap();
    seed.add(3 << 16 | 1);

    final PersistentRoaringBitmap result =
        PersistentRoaringBitmap.flip(seed, 0L, 8L << 16);
    assertTrue(result.getContainerCount() > 4,
        "flip should produce more than four containers");

    // Oracle: the same flip on a plain bitmap, then the same whole-container
    // removal. A plain PersistentRoaringBitmap never carries an undersized
    // shared[], so it exercises the unshared code path as a reference.
    final PersistentRoaringBitmap oracle = new PersistentRoaringBitmap();
    oracle.add(3 << 16 | 1);
    final PersistentRoaringBitmap expected =
        PersistentRoaringBitmap.flip(oracle, 0L, 8L << 16);

    // Drop every value in key 5's container so the container itself is removed,
    // forcing the internal sharedRemoveAt shift over the undersized array.
    expected.remove(5L << 16, 6L << 16);
    result.remove(5L << 16, 6L << 16);

    assertArrayEquals(expected.toArray(), result.toArray());

    // Neighbours of the removed container are intact.
    assertTrue(result.contains(4 << 16 | 0), "key 4 should remain populated");
    assertTrue(result.contains(6 << 16 | 0), "key 6 should remain populated");
    assertFalse(result.contains(5 << 16 | 0), "key 5 should have been removed");
  }

  @Test
  public void removeRangeFromStaticAddResultWithUndersizedSharedArray() {
    // A small seed widened by a static range-add over keys 0..7 produces an
    // all-owned multi-container result whose shared[] is undersized.
    final PersistentRoaringBitmap seed = new PersistentRoaringBitmap();
    seed.add(1 << 16 | 7);

    final PersistentRoaringBitmap result =
        PersistentRoaringBitmap.add(seed, 0L, 8L << 16);
    assertTrue(result.getContainerCount() > 4,
        "range add should produce more than four containers");

    final PersistentRoaringBitmap oracle = new PersistentRoaringBitmap();
    oracle.add(1 << 16 | 7);
    final PersistentRoaringBitmap expected =
        PersistentRoaringBitmap.add(oracle, 0L, 8L << 16);

    // Remove the whole key-2..key-5 span; dropping several whole containers
    // drives the internal sharedRemoveRange shift over the undersized array.
    expected.remove(2L << 16, 6L << 16);
    result.remove(2L << 16, 6L << 16);

    assertArrayEquals(expected.toArray(), result.toArray());
  }

  @Test
  public void addOffsetResultRemainsConsistentAndIsolatedAfterInPlaceMutation() {
    // addOffset clones each container into the result, so the shifted bitmap is
    // all-owned with an undersized shared[]. Mutating it in place must not read
    // past shared[] incorrectly, and must not corrupt the source.
    final PersistentRoaringBitmap source = PersistentRoaringBitmap.fromBitmap(
        buildMultiContainerBitmap(0, 6, 42));
    assertTrue(source.getContainerCount() > 4,
        "source should hold more than four containers");
    final int[] sourceBefore = source.toArray();

    final PersistentRoaringBitmap shifted =
        PersistentRoaringBitmap.addOffset(source, 3L << 16);

    // Oracle: the same offset applied to an isolated copy of the source.
    final PersistentRoaringBitmap oracle = PersistentRoaringBitmap.addOffset(
        source.clone(), 3L << 16);
    final int[] expectedBase = oracle.toArray();

    // Mutate the shifted result in place across several of its containers,
    // exercising the copyIfShared "i >= shared.length so treat as owned" branch.
    oracle.add(4L << 16, 5L << 16);
    oracle.remove(6L << 16, 7L << 16);
    shifted.add(4L << 16, 5L << 16);
    shifted.remove(6L << 16, 7L << 16);

    // The in-place mutation produced exactly the oracle's result...
    assertArrayEquals(oracle.toArray(), shifted.toArray());
    // ...and was a genuine mutation, not a no-op.
    assertFalse(java.util.Arrays.equals(expectedBase, shifted.toArray()),
        "in-place mutation should have changed the shifted bitmap");

    // The source bitmap is untouched by either the offset or the mutation.
    assertArrayEquals(sourceBefore, source.toArray(),
        "source was corrupted by addOffset or the in-place mutation of the result");
  }

  @Test
  public void andNotOnBitmapFromAllOwnedBuilderWithUndersizedSharedArray() {
    // A static range-add over a 10-key seed yields an all-owned, >4-container
    // result whose shared[] is left at the initial length 4. In-place andNot
    // reads this.shared[] by raw index, so it must grow shared[] first.
    final PersistentRoaringBitmap base = new PersistentRoaringBitmap();
    for (int key = 0; key < 10; key++) {
      base.add(key << 16 | 1);
    }
    final PersistentRoaringBitmap a = PersistentRoaringBitmap.add(base, 0L, 1L);
    assertTrue(a.getContainerCount() > 4,
        "range add should produce more than four containers");

    final PersistentRoaringBitmap b = new PersistentRoaringBitmap();
    b.add(5 << 16 | 1);

    // Oracle: the same andNot applied through a plain bitmap, which never
    // carries an undersized shared[].
    final PersistentRoaringBitmap oracle = new PersistentRoaringBitmap();
    for (int key = 0; key < 10; key++) {
      oracle.add(key << 16 | 1);
    }
    oracle.add(0L, 1L);
    oracle.andNot(b);

    a.andNot(b);

    assertArrayEquals(oracle.toArray(), a.toArray());
  }

  @Test
  public void orNotOnBitmapFromAllOwnedBuilderWithUndersizedSharedArray() {
    // Same all-owned, undersized-shared[] result; in-place orNot copies the
    // uncomplemented remainder out of this.shared[] by raw index and must grow
    // shared[] first.
    final PersistentRoaringBitmap base = new PersistentRoaringBitmap();
    for (int key = 0; key < 10; key++) {
      base.add(key << 16 | 1);
    }
    final PersistentRoaringBitmap a = PersistentRoaringBitmap.add(base, 0L, 1L);
    assertTrue(a.getContainerCount() > 4,
        "range add should produce more than four containers");

    final PersistentRoaringBitmap b = new PersistentRoaringBitmap();
    b.add(0);

    final PersistentRoaringBitmap oracle = new PersistentRoaringBitmap();
    for (int key = 0; key < 10; key++) {
      oracle.add(key << 16 | 1);
    }
    oracle.add(0L, 1L);
    oracle.orNot(b, 1L);

    a.orNot(b, 1L);

    assertArrayEquals(oracle.toArray(), a.toArray());
  }
}
