package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TestForEach {

  @Test
  public void testContinuous() {
    PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
    bitmap.add(100L, 10000L);

    final MutableInteger cardinality = new MutableInteger();
    bitmap.forEach(
        new IntConsumer() {
          int expected = 100;

          @Override
          public void accept(int value) {
            cardinality.value++;
            assertEquals(value, expected++);
          }
        });
    assertEquals(cardinality.value, bitmap.getCardinality());
  }

  @Test
  public void testDense() {
    PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
    for (int k = 0; k < 100000; k += 3) bitmap.add(k);

    final MutableInteger cardinality = new MutableInteger();
    bitmap.forEach(
        new IntConsumer() {
          int expected = 0;

          @Override
          public void accept(int value) {
            cardinality.value++;
            assertEquals(value, expected);
            expected += 3;
          }
        });
    assertEquals(cardinality.value, bitmap.getCardinality());
  }

  @Test
  public void testSparse() {
    PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
    for (int k = 0; k < 100000; k += 3000) bitmap.add(k);

    final MutableInteger cardinality = new MutableInteger();
    bitmap.forEach(
        new IntConsumer() {
          int expected = 0;

          @Override
          public void accept(int value) {
            cardinality.value++;
            assertEquals(value, expected);
            expected += 3000;
          }
        });
    assertEquals(cardinality.value, bitmap.getCardinality());
  }
}

class MutableInteger {
  public int value = 0;
}
