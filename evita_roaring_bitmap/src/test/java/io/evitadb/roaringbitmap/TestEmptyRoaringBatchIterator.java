package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TestEmptyRoaringBatchIterator {

  @Test
  public void testEmptyRoaringBitmap() {
    PersistentRoaringBitmap roaringBitmap = new PersistentRoaringBitmap();
    BatchIterator iterator = roaringBitmap.getBatchIterator();
    int[] ints = new int[1024];
    int cnt = iterator.nextBatch(ints);
    assertEquals(0, cnt);

    roaringBitmap.add(1);
    iterator = roaringBitmap.getBatchIterator();
    cnt = iterator.nextBatch(ints);
    assertEquals(1, cnt);
  }
}
