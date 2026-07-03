package io.evitadb.roaringbitmap;


import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.roaringbitmap.Util.toUnsignedLong;
import static io.evitadb.roaringbitmap.ValidationRangeConsumer.Value.ABSENT;
import static io.evitadb.roaringbitmap.ValidationRangeConsumer.Value.PRESENT;

import io.evitadb.roaringbitmap.art.LeafNode;
import io.evitadb.roaringbitmap.longlong.RoaringIntPacking;
import io.evitadb.roaringbitmap.art.LeafNodeIterator;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.apache.commons.lang3.SerializationUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

public class TestRoaring64Bitmap {

  // Inlined from TestRoaring64NavigableMap (not ported) — checks that the serialized byte length
  // matches the value reported by serializedSizeInBytes().
  private static void checkSerializeBytes(ImmutableLongBitmapDataProvider bitmap)
      throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (DataOutputStream oos = new DataOutputStream(baos)) {
      bitmap.serialize(oos);
    }

    assertEquals(baos.toByteArray().length, bitmap.serializedSizeInBytes());
  }

  private PersistentLongRoaringBitmap newDefaultCtor() {
    return new PersistentLongRoaringBitmap();
  }

  public static Set<Long> getSourceForAllKindsOfNodeTypes() {
    Random random = new Random(1234);
    Set<Long> source = new HashSet<>();
    int total = 10000;
    for (int i = 0; i < total; i++) {
      while (!source.add(random.nextLong())) {
        // Retry adding a different long which is not in the Set
      }
    }
    Assertions.assertEquals(total, source.size());
    return source;
  }

  @Test
  public void testEquality() {
    PersistentLongRoaringBitmap rb1 = new PersistentLongRoaringBitmap();
    PersistentLongRoaringBitmap rb2 = new PersistentLongRoaringBitmap();
    assertEquals(rb1, rb2);
    rb1.addLong(1);
    assertNotEquals(rb1, rb2);
    rb1.removeLong(1);
    assertEquals(rb1, rb2);
  }

  @Test
  public void testClone() {
    PersistentLongRoaringBitmap rb1 = new PersistentLongRoaringBitmap();
    PersistentLongRoaringBitmap rbref = new PersistentLongRoaringBitmap();
    PersistentLongRoaringBitmap rbrefc = rbref.clone();

    assertEquals(rb1, rbref);
    for (long x = 0; x < 100000; x++) {
      rb1.addLong(x);
      rbref.addLong(x);
    }
    assertEquals(rb1, rbref);

    for (long x = 100000; x < 200000; x += 100) {
      rb1.addLong(x);
      rbref.addLong(x);
    }
    assertEquals(rb1, rbref);

    for (long x = 200000; x < 300000; x += 2) {
      rb1.addLong(x);
      rbref.addLong(x);
    }
    assertEquals(rb1, rbref);
    PersistentLongRoaringBitmap rb1c = rb1.clone();
    assertTrue(rbrefc.isEmpty());
    assertEquals(rb1, rb1c);
    assertEquals(rbref, rb1c);
    rb1c.addLong(400000);
    assertNotEquals(rb1, rb1c);
    assertNotEquals(rbrefc, rb1c);
    rb1.clear();
    rb1c.removeLong(400000);

    assertEquals(rbref, rb1c);
    assertTrue(rb1.isEmpty());
  }

  @Test
  public void test() throws Exception {
    Random random = new Random(1234);
    PersistentLongRoaringBitmap roaring64Bitmap = new PersistentLongRoaringBitmap();
    Set<Long> source = new HashSet<>();
    int total = 1000000;
    for (int i = 0; i < total; i++) {
      long l = random.nextLong();
      roaring64Bitmap.addLong(l);
      source.add(l);
    }
    LongIterator longIterator = roaring64Bitmap.getLongIterator();
    int i = 0;
    while (longIterator.hasNext()) {
      long actual = longIterator.next();
      Assertions.assertTrue(source.contains(actual));
      i++;
    }
    Assertions.assertEquals(total, i);
  }

  @Test
  public void testAllKindOfNodeTypesSerDeser() throws Exception {
    Set<Long> source = getSourceForAllKindsOfNodeTypes();

    PersistentLongRoaringBitmap roaring64Bitmap = new PersistentLongRoaringBitmap();
    source.forEach(roaring64Bitmap::addLong);

    LongIterator longIterator = roaring64Bitmap.getLongIterator();
    int i = 0;
    while (longIterator.hasNext()) {
      long actual = longIterator.next();
      Assertions.assertTrue(source.contains(actual));
      i++;
    }
    Assertions.assertEquals(source.size(), i);
    // test all kind of nodes's serialization/deserialization
    long sizeL = roaring64Bitmap.serializedSizeInBytes();
    if (sizeL > Integer.MAX_VALUE) {
      return;
    }
    int sizeInt = (int) sizeL;
    long select2 = roaring64Bitmap.select(2);
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(sizeInt);
    DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
    roaring64Bitmap.serialize(dataOutputStream);
    ByteArrayInputStream byteArrayInputStream =
        new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
    DataInputStream dataInputStream = new DataInputStream(byteArrayInputStream);
    PersistentLongRoaringBitmap deserStreamOne = new PersistentLongRoaringBitmap();
    deserStreamOne.deserialize(dataInputStream);
    Assertions.assertEquals(select2, deserStreamOne.select(2));
    deserStreamOne = null;
    byteArrayInputStream = null;
    byteArrayOutputStream = null;
    ByteBuffer byteBuffer = ByteBuffer.allocate(sizeInt).order(ByteOrder.LITTLE_ENDIAN);
    roaring64Bitmap.serialize(byteBuffer);
    roaring64Bitmap = null;
    byteBuffer.flip();
    PersistentLongRoaringBitmap deserBBOne = new PersistentLongRoaringBitmap();
    deserBBOne.deserialize(byteBuffer);
    Assertions.assertEquals(select2, deserBBOne.select(2));
  }

  @Test
  public void testEmpty() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    assertFalse(map.getLongIterator().hasNext());
    assertEquals(0, map.getLongCardinality());
    assertTrue(map.isEmpty());
    assertFalse(map.contains(0));
    assertEquals(0, map.rankLong(Long.MIN_VALUE));
    assertEquals(0, map.rankLong(Long.MIN_VALUE + 1));
    assertEquals(0, map.rankLong(-1));
    assertEquals(0, map.rankLong(0));
    assertEquals(0, map.rankLong(1));
    assertEquals(0, map.rankLong(Long.MAX_VALUE - 1));
    assertEquals(0, map.rankLong(Long.MAX_VALUE));
  }

  @Test
  public void testZero() {
    PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(0);

    LongIterator iterator = map.getLongIterator();
    assertTrue(iterator.hasNext());
    assertEquals(0, iterator.next());
    assertEquals(0, map.select(0));
    assertTrue(map.contains(0));
    assertFalse(iterator.hasNext());
    assertEquals(1, map.getLongCardinality());
    assertFalse(map.isEmpty());
    assertEquals(1, map.rankLong(Long.MIN_VALUE));
    assertEquals(1, map.rankLong(Integer.MIN_VALUE - 1L));
    assertEquals(1, map.rankLong(-1));
    assertEquals(1, map.rankLong(0));
    assertEquals(1, map.rankLong(1));
    assertEquals(1, map.rankLong(Integer.MAX_VALUE + 1L));
    assertEquals(1, map.rankLong(Long.MAX_VALUE));
  }

  @Test
  public void testMinusOne_Unsigned() {
    PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(-1);

    LongIterator iterator = map.getLongIterator();
    assertTrue(iterator.hasNext());
    assertEquals(-1, iterator.next());
    assertEquals(-1, map.select(0));
    assertTrue(map.contains(-1));
    assertFalse(iterator.hasNext());
    assertEquals(1, map.getLongCardinality());
    assertFalse(map.isEmpty());
    assertEquals(0, map.rankLong(Long.MIN_VALUE));
    assertEquals(0, map.rankLong(Integer.MIN_VALUE - 1L));
    assertEquals(0, map.rankLong(0));
    assertEquals(0, map.rankLong(1));
    assertEquals(0, map.rankLong(Integer.MAX_VALUE + 1L));
    assertEquals(0, map.rankLong(Long.MAX_VALUE));
    assertEquals(0, map.rankLong(-2));
    assertEquals(1, map.rankLong(-1));
    assertArrayEquals(new long[] {-1L}, map.toArray());
  }

  @Test
  public void testSimpleIntegers() {
    PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(123);
    map.addLong(234);

    LongIterator iterator = map.getLongIterator();
    assertTrue(iterator.hasNext());
    assertEquals(123, iterator.next());
    assertEquals(123, map.select(0));
    assertTrue(map.contains(123));
    assertTrue(iterator.hasNext());
    assertEquals(234, iterator.next());
    assertEquals(234, map.select(1));
    assertTrue(map.contains(234));
    assertFalse(iterator.hasNext());
    assertFalse(map.contains(345));
    assertEquals(2, map.getLongCardinality());
    assertEquals(0, map.rankLong(0));
    assertEquals(1, map.rankLong(123));
    assertEquals(1, map.rankLong(233));
    assertEquals(2, map.rankLong(234));
    assertEquals(2, map.rankLong(235));
    assertEquals(2, map.rankLong(Integer.MAX_VALUE + 1L));
    assertEquals(2, map.rankLong(Long.MAX_VALUE));
    assertArrayEquals(new long[] {123L, 234L}, map.toArray());
  }

  @Test
  public void testAddOneSelect2() {
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          PersistentLongRoaringBitmap map = newDefaultCtor();
          map.addLong(123);
          map.select(1);
        });
  }

  @Test
  public void testAddInt() {
    PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addInt(-1);
    assertEquals(0xFFFFFFFFL, map.select(0));
  }

  @Test
  public void testIterator_NextWithoutHasNext_Filled() {
    PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(0);
    assertTrue(map.getLongIterator().hasNext());
    assertEquals(0, map.getLongIterator().next());
  }

  @Test
  public void testIterator_NextWithoutHasNext_Empty() {
    assertThrows(
        IllegalStateException.class,
        () -> {
          PersistentLongRoaringBitmap map = newDefaultCtor();
          map.getLongIterator().next();
        });
  }

  @Test
  public void testLongMaxValue() {
    PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(Long.MAX_VALUE);
    LongIterator iterator = map.getLongIterator();
    assertTrue(iterator.hasNext());
    assertEquals(Long.MAX_VALUE, iterator.next());
    assertEquals(Long.MAX_VALUE, map.select(0));
    assertFalse(iterator.hasNext());
    assertEquals(1, map.getLongCardinality());
    assertEquals(1, map.rankLong(Long.MIN_VALUE));
    assertEquals(1, map.rankLong(Long.MIN_VALUE + 1));
    assertEquals(1, map.rankLong(-1));
    assertEquals(0, map.rankLong(0));
    assertEquals(0, map.rankLong(1));
    assertEquals(0, map.rankLong(Long.MAX_VALUE - 1));
    assertEquals(1, map.rankLong(Long.MAX_VALUE));
    assertArrayEquals(new long[] {Long.MAX_VALUE}, map.toArray());
  }

  @Test
  public void testLongMinValue() {
    PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(Long.MIN_VALUE);
    LongIterator iterator = map.getLongIterator();
    assertTrue(iterator.hasNext());
    assertEquals(Long.MIN_VALUE, iterator.next());
    assertEquals(Long.MIN_VALUE, map.select(0));
    assertFalse(iterator.hasNext());
    assertEquals(1, map.getLongCardinality());
    assertEquals(1, map.rankLong(Long.MIN_VALUE));
    assertEquals(1, map.rankLong(Long.MIN_VALUE + 1));
    assertEquals(1, map.rankLong(-1));
    assertEquals(0, map.rankLong(0));
    assertEquals(0, map.rankLong(1));
    assertEquals(0, map.rankLong(Long.MAX_VALUE - 1));
    assertEquals(0, map.rankLong(Long.MAX_VALUE));
  }

  @Test
  public void testLongMinValueZeroOneMaxValue() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(Long.MIN_VALUE);
    map.addLong(0);
    map.addLong(1);
    map.addLong(Long.MAX_VALUE);
    LongIterator iterator = map.getLongIterator();
    assertEquals(0, iterator.next());
    assertEquals(0, map.select(0));
    assertEquals(1, iterator.next());
    assertEquals(1, map.select(1));
    assertEquals(Long.MAX_VALUE, iterator.next());
    assertEquals(Long.MAX_VALUE, map.select(2));
    assertEquals(Long.MIN_VALUE, iterator.next());
    assertEquals(Long.MIN_VALUE, map.select(3));
    assertFalse(iterator.hasNext());
    assertEquals(4, map.getLongCardinality());
    assertEquals(4, map.rankLong(Long.MIN_VALUE));
    assertEquals(4, map.rankLong(Long.MIN_VALUE + 1));
    assertEquals(4, map.rankLong(-1));
    assertEquals(1, map.rankLong(0));
    assertEquals(2, map.rankLong(1));
    assertEquals(2, map.rankLong(2));
    assertEquals(2, map.rankLong(Long.MAX_VALUE - 1));
    assertEquals(3, map.rankLong(Long.MAX_VALUE));

    final List<Long> foreach = new ArrayList<>();
    map.forEach(
        new LongConsumer() {

          @Override
          public void accept(long value) {
            foreach.add(value);
          }
        });
    assertEquals(Arrays.asList(0L, 1L, Long.MAX_VALUE, Long.MIN_VALUE), foreach);
  }

  @Test
  public void testReverseIterator_SingleBuket() {
    PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(123);
    map.addLong(234);
    LongIterator iterator = map.getReverseLongIterator();
    assertTrue(iterator.hasNext());
    assertEquals(234, iterator.next());
    assertTrue(iterator.hasNext());
    assertEquals(123, iterator.next());
    assertFalse(iterator.hasNext());
  }

  @Test
  public void testReverseIterator_MultipleBuket() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(123);
    map.addLong(Long.MAX_VALUE);
    LongIterator iterator = map.getReverseLongIterator();
    assertTrue(iterator.hasNext());
    assertEquals(Long.MAX_VALUE, iterator.next());
    assertTrue(iterator.hasNext());
    assertEquals(123, iterator.next());
    assertFalse(iterator.hasNext());
  }

  @Test
  public void testStream_matchesIterator() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(123);
    map.addLong(124);
    map.addLong(12123);
    map.addLong(9999999);
    map.addLong(Long.MAX_VALUE);

    int index = 0;
    long[] arrayFromIterator = new long[5];
    final PeekableLongIterator it = map.getLongIterator();
    while (it.hasNext()) {
      arrayFromIterator[index++] = it.next();
    }

    final long[] arrayFromStream = map.stream().toArray();
    assertArrayEquals(arrayFromIterator, arrayFromStream);
    assertArrayEquals(new long[] {123, 124, 12123, 9999999, Long.MAX_VALUE}, arrayFromStream);
  }

  @Test
  public void testReverseStream_matchesReverseIterator() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(123);
    map.addLong(124);
    map.addLong(12123);
    map.addLong(9999999);
    map.addLong(Long.MAX_VALUE);

    int index = 0;
    long[] arrayFromIterator = new long[5];
    final PeekableLongIterator it = map.getReverseLongIterator();
    while (it.hasNext()) {
      arrayFromIterator[index++] = it.next();
    }

    final long[] arrayFromStream = map.reverseStream().toArray();
    assertArrayEquals(arrayFromIterator, arrayFromStream);
    assertArrayEquals(new long[] {Long.MAX_VALUE, 9999999, 12123, 124, 123}, arrayFromStream);
  }

  @Test
  public void testRemove() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    // Add a value
    map.addLong(123);
    assertEquals(1L, map.getLongCardinality());

    // Remove it
    map.removeLong(123L);
    assertEquals(0L, map.getLongCardinality());
    assertTrue(map.isEmpty());

    // Add it back
    map.addLong(123);
    assertEquals(1L, map.getLongCardinality());
  }

  @Test
  public void testRemoveDifferentBuckets() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    // Add two values
    map.addLong(123);
    map.addLong(Long.MAX_VALUE);
    assertEquals(2L, map.getLongCardinality());

    // Remove biggest
    map.removeLong(Long.MAX_VALUE);
    assertEquals(1L, map.getLongCardinality());

    assertEquals(123L, map.select(0));

    // Add back to different bucket
    map.addLong(Long.MAX_VALUE);
    assertEquals(2L, map.getLongCardinality());

    assertEquals(123L, map.select(0));
    assertEquals(Long.MAX_VALUE, map.select(1));
  }

  @Test
  public void testRemoveDifferentBuckets_RemoveBigAddIntermediate() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    // Add two values
    map.addLong(123);
    map.addLong(Long.MAX_VALUE);
    assertEquals(2L, map.getLongCardinality());

    // Remove biggest
    map.removeLong(Long.MAX_VALUE);
    assertEquals(1L, map.getLongCardinality());

    assertEquals(123L, map.select(0));

    // Add back to different bucket
    map.addLong(Long.MAX_VALUE / 2L);
    assertEquals(2L, map.getLongCardinality());

    assertEquals(123L, map.select(0));
    assertEquals(Long.MAX_VALUE / 2L, map.select(1));
  }

  @Test
  public void testRemoveDifferentBuckets_RemoveIntermediateAddBug() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    // Add two values
    map.addLong(123);
    map.addLong(Long.MAX_VALUE / 2L);
    assertEquals(2L, map.getLongCardinality());

    // Remove biggest
    map.removeLong(Long.MAX_VALUE / 2L);
    assertEquals(1L, map.getLongCardinality());

    assertEquals(123L, map.select(0));

    // Add back to different bucket
    map.addLong(Long.MAX_VALUE);
    assertEquals(2L, map.getLongCardinality());

    assertEquals(123L, map.select(0));
    assertEquals(Long.MAX_VALUE, map.select(1));
  }

  @Test
  public void testPerfManyDifferentBuckets() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    long problemSize = 1000 * 1000L;
    for (long i = 1; i <= problemSize; i++) {
      map.addLong(i * Integer.MAX_VALUE + 1L);
    }

    long cardinality = map.getLongCardinality();
    assertEquals(problemSize, cardinality);

    long last = map.select(cardinality - 1);
    assertEquals(cardinality, map.rankLong(last));
  }

  @Test
  public void testLargeSelectLong() {
    long positive = 1;
    long negative = -1;
    PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(positive);
    map.addLong(negative);
    long first = map.select(0);
    long last = map.select(1);

    assertEquals(positive, first);
    assertEquals(negative, last);
  }

  @Test
  public void testLargeRankLong() {
    long positive = 1;
    long negative = -1;
    PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(positive);
    map.addLong(negative);
    assertEquals(2, map.rankLong(negative));
  }

  @Test
  public void testIterationOrder() {
    long positive = 1;
    long negative = -1;
    PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(positive);
    map.addLong(negative);
    LongIterator it = map.getLongIterator();
    long first = it.next();
    long last = it.next();
    assertEquals(positive, first);
    assertEquals(negative, last);
  }

  @Test
  public void testAddingLowValueAfterHighValue() {
    PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(Long.MAX_VALUE);
    assertEquals(Long.MAX_VALUE, map.select(0));
    map.addLong(666);
    assertEquals(666, map.select(0));
    assertEquals(Long.MAX_VALUE, map.select(1));
  }

  @Test
  public void testSerializationEmpty() throws IOException, ClassNotFoundException {
    final PersistentLongRoaringBitmap map = newDefaultCtor();

    checkSerializeBytes(map);

    final PersistentLongRoaringBitmap clone = SerializationUtils.clone(map);

    // Check the test has not simply copied the ref
    assertNotSame(map, clone);
    assertEquals(0, clone.getLongCardinality());
  }

  @Test
  public void testSerialization_ToBigEndianBuffer() throws IOException {
    final PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(123);
    ByteBuffer buffer =
        ByteBuffer.allocate((int) map.serializedSizeInBytes()).order(ByteOrder.BIG_ENDIAN);
    map.serialize(buffer);
    assertEquals(map.serializedSizeInBytes(), buffer.position());
  }

  @Test
  public void testSerialization_OneValue() throws IOException, ClassNotFoundException {
    final PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(123);

    checkSerializeBytes(map);

    final PersistentLongRoaringBitmap clone = SerializationUtils.clone(map);

    // Check the test has not simply copied the ref
    assertNotSame(map, clone);
    assertEquals(1, clone.getLongCardinality());
    assertEquals(123, clone.select(0));
  }

  @Test
  public void testSerialization() throws IOException, ClassNotFoundException {
    final PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(123);

    checkSerializeBytes(map);

    final PersistentLongRoaringBitmap clone = SerializationUtils.clone(map);

    // Check the test has not simply copied the ref
    assertNotSame(map, clone);
    assertEquals(1, clone.getLongCardinality());
    assertEquals(123, clone.select(0));
  }

  @Test
  public void testSerializationMultipleBuckets() throws IOException, ClassNotFoundException {
    final PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(-123);
    map.addLong(123);
    map.addLong(Long.MAX_VALUE);

    checkSerializeBytes(map);

    final PersistentLongRoaringBitmap clone = SerializationUtils.clone(map);

    // Check the test has not simply copied the ref
    assertNotSame(map, clone);
    assertEquals(3, clone.getLongCardinality());
    assertEquals(123, clone.select(0));
    assertEquals(Long.MAX_VALUE, clone.select(1));
    assertEquals(-123, clone.select(2));
    int sizeInByteInt = map.getSizeInBytes();
    ByteBuffer byteBuffer = ByteBuffer.allocate(sizeInByteInt).order(ByteOrder.LITTLE_ENDIAN);
    map.serialize(byteBuffer);
    byteBuffer.flip();
    PersistentLongRoaringBitmap anotherDeserMap = newDefaultCtor();
    anotherDeserMap.deserialize(byteBuffer);
    assertEquals(3, anotherDeserMap.getLongCardinality());
    assertEquals(123, anotherDeserMap.select(0));
    assertEquals(Long.MAX_VALUE, anotherDeserMap.select(1));
    assertEquals(-123, anotherDeserMap.select(2));
  }

  @Test
  public void testOrSameBucket() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    right.addLong(234);

    PersistentLongRoaringBitmap orNotInPlace = PersistentLongRoaringBitmap.or(left, right);
    left.or(right);

    assertEquals(2, left.getLongCardinality());

    assertEquals(123, left.select(0));
    assertEquals(234, left.select(1));

    assertEquals(2, orNotInPlace.getLongCardinality());

    assertEquals(123, orNotInPlace.select(0));
    assertEquals(234, orNotInPlace.select(1));
  }

  @Test
  public void testOrMultipleBuckets() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    left.addLong(Long.MAX_VALUE);
    right.addLong(234);

    PersistentLongRoaringBitmap orNotInPlace = PersistentLongRoaringBitmap.or(left, right);
    left.or(right);

    assertEquals(3, left.getLongCardinality());

    assertEquals(123, left.select(0));
    assertEquals(234, left.select(1));
    assertEquals(Long.MAX_VALUE, left.select(2));

    assertEquals(3, orNotInPlace.getLongCardinality());

    assertEquals(123, orNotInPlace.select(0));
    assertEquals(234, orNotInPlace.select(1));
    assertEquals(Long.MAX_VALUE, orNotInPlace.select(2));
  }

  @Test
  public void testOrDifferentBucket() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    right.addLong(Long.MAX_VALUE / 2);

    PersistentLongRoaringBitmap orNotInPlace = PersistentLongRoaringBitmap.or(left, right);
    left.or(right);

    assertEquals(2, left.getLongCardinality());

    assertEquals(123, left.select(0));
    assertEquals(Long.MAX_VALUE / 2, left.select(1));

    assertEquals(2, orNotInPlace.getLongCardinality());

    assertEquals(123, orNotInPlace.select(0));
    assertEquals(Long.MAX_VALUE / 2, orNotInPlace.select(1));
  }

  @Test
  public void testOrDifferentBucket2() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    right.addLong(Long.MAX_VALUE);

    PersistentLongRoaringBitmap orNotInPlace = PersistentLongRoaringBitmap.or(left, right);
    left.or(right);

    assertEquals(2, left.getLongCardinality());

    assertEquals(123, left.select(0));
    assertEquals(Long.MAX_VALUE, left.select(1));

    assertEquals(2, orNotInPlace.getLongCardinality());

    assertEquals(123, orNotInPlace.select(0));
    assertEquals(Long.MAX_VALUE, orNotInPlace.select(1));
  }

  @Test
  public void testOrCloneInput() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    right.addLong(123);

    // We push in left a bucket which does not exist
    left.or(right);

    // Then we mutate left: ensure it does not impact right as it should remain unchanged
    left.addLong(234);

    assertEquals(2, left.getLongCardinality());
    assertEquals(123, left.select(0));
    assertEquals(234, left.select(1));

    assertEquals(1, right.getLongCardinality());
    assertEquals(123, right.select(0));
  }

  @Test
  public void testXorBucket() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    left.addLong(234);
    right.addLong(234);
    right.addLong(345);

    // We have 1 shared value: 234
    PersistentLongRoaringBitmap xorNotInPlace = PersistentLongRoaringBitmap.xor(left, right);
    left.xor(right);

    assertEquals(2, left.getLongCardinality());
    assertEquals(123, left.select(0));
    assertEquals(345, left.select(1));

    assertEquals(2, xorNotInPlace.getLongCardinality());
    assertEquals(123, xorNotInPlace.select(0));
    assertEquals(345, xorNotInPlace.select(1));
  }

  @Test
  public void testXor() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    left.addLong(234);
    right.addLong(234);
    right.addLong(345);

    PersistentLongRoaringBitmap xorNotInPlace = PersistentLongRoaringBitmap.xor(left, right);
    left.xor(right);

    assertEquals(2, left.getLongCardinality());
    assertEquals(123, left.select(0));
    assertEquals(345, left.select(1));

    assertEquals(2, xorNotInPlace.getLongCardinality());
    assertEquals(123, xorNotInPlace.select(0));
    assertEquals(345, xorNotInPlace.select(1));
  }

  @Test
  public void testXorDifferentBucket() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    right.addLong(Long.MAX_VALUE);

    // We have 1 shared value: 234
    PersistentLongRoaringBitmap xorNotInPlace = PersistentLongRoaringBitmap.xor(left, right);
    left.xor(right);

    assertEquals(2, left.getLongCardinality());
    assertEquals(123, left.select(0));
    assertEquals(Long.MAX_VALUE, left.select(1));

    assertEquals(2, xorNotInPlace.getLongCardinality());
    assertEquals(123, xorNotInPlace.select(0));
    assertEquals(Long.MAX_VALUE, xorNotInPlace.select(1));
  }

  @Test
  public void testXor_MultipleBucket() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    left.addLong(Long.MAX_VALUE);
    right.addLong(Long.MAX_VALUE);

    // We have 1 shared value: 234
    PersistentLongRoaringBitmap xorNotInPlace = PersistentLongRoaringBitmap.xor(left, right);
    left.xor(right);

    assertEquals(1, left.getLongCardinality());
    assertEquals(123, left.select(0));

    assertEquals(1, xorNotInPlace.getLongCardinality());
    assertEquals(123, xorNotInPlace.select(0));
  }

  @Test
  public void testAndSingleBucket() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    left.addLong(234);
    right.addLong(234);
    right.addLong(345);

    // We have 1 shared value: 234
    PersistentLongRoaringBitmap andNotInPlace = PersistentLongRoaringBitmap.and(left, right);
    left.and(right);

    assertEquals(1, left.getLongCardinality());
    assertEquals(234, left.select(0));

    assertEquals(1, andNotInPlace.getLongCardinality());
    assertEquals(234, andNotInPlace.select(0));
  }

  @Test
  public void testAnd() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    right.addLong(123);

    // We have 1 shared value: 234
    PersistentLongRoaringBitmap andNotInPlace = PersistentLongRoaringBitmap.and(left, right);
    left.and(right);

    assertEquals(1, left.getLongCardinality());
    assertEquals(123, left.select(0));

    assertEquals(1, andNotInPlace.getLongCardinality());
    assertEquals(123, andNotInPlace.select(0));
  }

  @Test
  public void testAndDisjoint() {
    // There are no shared values between these maps.
    final long[] leftData =
        new long[] {1076595327100L, 1074755534972L, 5060192403580L, 5060308664444L};
    final long[] rightData = new long[] {3470563844L};

    PersistentLongRoaringBitmap left = PersistentLongRoaringBitmap.bitmapOf(leftData);
    PersistentLongRoaringBitmap right = PersistentLongRoaringBitmap.bitmapOf(rightData);

    PersistentLongRoaringBitmap andNotInPlace = PersistentLongRoaringBitmap.and(left, right);
    left.and(right);

    PersistentLongRoaringBitmap swapLeft = PersistentLongRoaringBitmap.bitmapOf(rightData);
    PersistentLongRoaringBitmap swapRight = PersistentLongRoaringBitmap.bitmapOf(leftData);

    PersistentLongRoaringBitmap swapAndNotInPlace = PersistentLongRoaringBitmap.and(left, right);
    swapLeft.and(swapRight);

    assertEquals(0, left.getLongCardinality());
    assertEquals(0, swapLeft.getLongCardinality());
    assertThrows(IllegalArgumentException.class, () -> left.select(0));
    assertThrows(IllegalArgumentException.class, () -> swapLeft.select(0));

    assertEquals(0, andNotInPlace.getLongCardinality());
    assertEquals(0, swapAndNotInPlace.getLongCardinality());
    assertThrows(IllegalArgumentException.class, () -> andNotInPlace.select(0));
    assertThrows(IllegalArgumentException.class, () -> swapAndNotInPlace.select(0));
  }

  @Test
  void testToArrayAfterAndOptHasEmptyContainer() {
    PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();
    bitmap.addLong(0);

    PersistentLongRoaringBitmap bitmap2 = new PersistentLongRoaringBitmap();
    bitmap2.addLong(1);
    // bit and
    PersistentLongRoaringBitmap andNotInPlace = PersistentLongRoaringBitmap.and(bitmap, bitmap2);
    bitmap.and(bitmap2);
    // to array
    Assertions.assertDoesNotThrow(bitmap::toArray);
    Assertions.assertDoesNotThrow(andNotInPlace::toArray);
  }

  @Test
  public void testAndDifferentBucket() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    right.addLong(Long.MAX_VALUE);

    // We have 1 shared value: 234
    left.and(right);

    assertEquals(0, left.getLongCardinality());
  }

  @Test
  public void testAndMultipleBucket() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    left.addLong(Long.MAX_VALUE);
    right.addLong(Long.MAX_VALUE);

    // We have 1 shared value: 234
    PersistentLongRoaringBitmap andNotInPlace = PersistentLongRoaringBitmap.and(left, right);
    left.and(right);

    assertEquals(1, left.getLongCardinality());
    assertEquals(Long.MAX_VALUE, left.select(0));

    assertEquals(1, andNotInPlace.getLongCardinality());
    assertEquals(Long.MAX_VALUE, andNotInPlace.select(0));
  }

  @Test
  public void intersecttest() {
    final PersistentLongRoaringBitmap rr1 = new PersistentLongRoaringBitmap();
    final PersistentLongRoaringBitmap rr2 = new PersistentLongRoaringBitmap();
    for (int k = 0; k < 40000; ++k) {
      rr1.add(2 * k);
      rr2.add(2 * k + 1);
    }
    assertFalse(PersistentLongRoaringBitmap.intersects(rr1, rr2));
    rr1.add(2 * 500 + 1);
    assertTrue(PersistentLongRoaringBitmap.intersects(rr1, rr2));
    final PersistentLongRoaringBitmap rr3 = new PersistentLongRoaringBitmap();
    rr3.add(2 * 501 + 1);
    assertTrue(PersistentLongRoaringBitmap.intersects(rr3, rr2));
    assertFalse(PersistentLongRoaringBitmap.intersects(rr3, rr1));
    for (int k = 0; k < 40000; ++k) {
      rr1.add(2 * k + 1);
    }
    rr1.runOptimize();
    assertTrue(PersistentLongRoaringBitmap.intersects(rr1, rr2));
  }

  @Test
  public void andcounttest() {
    // This is based on andtest
    final PersistentLongRoaringBitmap rr = new PersistentLongRoaringBitmap();
    for (int k = 0; k < 4000; ++k) {
      rr.add(k);
    }
    rr.add(100000);
    rr.add(110000);
    final PersistentLongRoaringBitmap rr2 = new PersistentLongRoaringBitmap();
    rr2.add(13);
    final PersistentLongRoaringBitmap rrand = PersistentLongRoaringBitmap.and(rr, rr2);
    assertEquals(rrand.getLongCardinality(), PersistentLongRoaringBitmap.andCardinality(rr, rr2));
    assertEquals(rrand.getLongCardinality(), PersistentLongRoaringBitmap.andCardinality(rr2, rr));
    rr.and(rr2);
    assertEquals(rrand.getLongCardinality(), PersistentLongRoaringBitmap.andCardinality(rr2, rr));
  }

  @Test
  public void andCounttest3() {
    // This is based on andtest3
    final int[] arrayand = new int[11256];
    int pos = 0;
    final PersistentLongRoaringBitmap rr = new PersistentLongRoaringBitmap();
    for (int k = 4000; k < 4256; ++k) {
      rr.add(k);
    }
    for (int k = 65536; k < 65536 + 4000; ++k) {
      rr.add(k);
    }
    for (int k = 3 * 65536; k < 3 * 65536 + 1000; ++k) {
      rr.add(k);
    }
    for (int k = 3 * 65536 + 1000; k < 3 * 65536 + 7000; ++k) {
      rr.add(k);
    }
    for (int k = 3 * 65536 + 7000; k < 3 * 65536 + 9000; ++k) {
      rr.add(k);
    }
    for (int k = 4 * 65536; k < 4 * 65536 + 7000; ++k) {
      rr.add(k);
    }
    for (int k = 6 * 65536; k < 6 * 65536 + 10000; ++k) {
      rr.add(k);
    }
    for (int k = 8 * 65536; k < 8 * 65536 + 1000; ++k) {
      rr.add(k);
    }
    for (int k = 9 * 65536; k < 9 * 65536 + 30000; ++k) {
      rr.add(k);
    }
    final PersistentLongRoaringBitmap rr2 = new PersistentLongRoaringBitmap();
    for (int k = 4000; k < 4256; ++k) {
      rr2.add(k);
      arrayand[pos++] = k;
    }
    for (int k = 65536; k < 65536 + 4000; ++k) {
      rr2.add(k);
      arrayand[pos++] = k;
    }
    for (int k = 3 * 65536 + 1000; k < 3 * 65536 + 7000; ++k) {
      rr2.add(k);
      arrayand[pos++] = k;
    }
    for (int k = 6 * 65536; k < 6 * 65536 + 1000; ++k) {
      rr2.add(k);
      arrayand[pos++] = k;
    }
    for (int k = 7 * 65536; k < 7 * 65536 + 1000; ++k) {
      rr2.add(k);
    }
    for (int k = 10 * 65536; k < 10 * 65536 + 5000; ++k) {
      rr2.add(k);
    }

    final PersistentLongRoaringBitmap rrand = PersistentLongRoaringBitmap.and(rr, rr2);
    final long rrandCount = PersistentLongRoaringBitmap.andCardinality(rr, rr2);

    assertEquals(rrand.getLongCardinality(), rrandCount);
  }

  @Test
  public void testAndNotSingleBucket() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    left.addLong(234);
    right.addLong(234);
    right.addLong(345);

    // We have 1 shared value: 234
    PersistentLongRoaringBitmap andNotNotInPlace = PersistentLongRoaringBitmap.andNot(left, right);
    left.andNot(right);

    assertEquals(1, left.getLongCardinality());
    assertEquals(123, left.select(0));

    assertEquals(1, andNotNotInPlace.getLongCardinality());
    assertEquals(123, andNotNotInPlace.select(0));
  }

  @Test
  public void testAndNot() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    right.addLong(234);

    // We have 1 shared value: 234
    PersistentLongRoaringBitmap andNotNotInPlace = PersistentLongRoaringBitmap.andNot(left, right);
    left.andNot(right);

    assertEquals(1, left.getLongCardinality());
    assertEquals(123, left.select(0));

    assertEquals(1, andNotNotInPlace.getLongCardinality());
    assertEquals(123, andNotNotInPlace.select(0));
  }

  @Test
  public void testAndNotDifferentBucket() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    right.addLong(Long.MAX_VALUE);

    // We have 1 shared value: 234
    PersistentLongRoaringBitmap andNotNotInPlace = PersistentLongRoaringBitmap.andNot(left, right);
    left.andNot(right);

    assertEquals(1, left.getLongCardinality());
    assertEquals(123, left.select(0));

    assertEquals(1, andNotNotInPlace.getLongCardinality());
    assertEquals(123, andNotNotInPlace.select(0));
  }

  @Test
  public void testAndNot_MultipleBucket() {
    PersistentLongRoaringBitmap left = newDefaultCtor();
    PersistentLongRoaringBitmap right = newDefaultCtor();

    left.addLong(123);
    left.addLong(Long.MAX_VALUE);
    right.addLong(Long.MAX_VALUE);

    // We have 1 shared value: 234
    PersistentLongRoaringBitmap andNotNotInPlace = PersistentLongRoaringBitmap.andNot(left, right);
    left.andNot(right);

    assertEquals(1, left.getLongCardinality());
    assertEquals(123, left.select(0));

    assertEquals(1, andNotNotInPlace.getLongCardinality());
    assertEquals(123, andNotNotInPlace.select(0));
  }

  @Test
  public void testFlipSameContainer() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(0);
    map.flip(1, 2);

    assertEquals(2, map.getLongCardinality());
    assertEquals(1, map.select(1));
  }

  @Test
  public void testFlipMiddleContainer() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(0);
    map.addLong(0x20001);

    map.flip(0x10001, 0x10002);

    assertEquals(3, map.getLongCardinality());
    assertEquals(0x10001, map.select(1));
  }

  @Test
  public void testFlipNextContainer() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(0);
    map.flip(0x10001, 0x10002);

    assertEquals(2, map.getLongCardinality());
    assertEquals(0x10001, map.select(1));
  }

  @Test
  public void testFlipToEdgeContainer() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(0);
    map.flip(0xFFFF, 0x10000);

    assertEquals(2, map.getLongCardinality());
    assertEquals(0xFFFF, map.select(1));
  }

  @Test
  public void testFlipOverEdgeContainer() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(0);
    map.flip(0xFFFF, 0x10002);

    assertEquals(4, map.getLongCardinality());
    assertEquals(0x10001, map.select(3));
  }

  @Test
  public void testFlipPriorContainer() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(0x10001);
    map.flip(1L, 2L);

    assertEquals(2, map.getLongCardinality());
    assertEquals(1, map.select(0));
    assertEquals(0x10001, map.select(1));
  }

  @Test
  public void testFlipSameNonZeroValuesNoChange() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(0);
    map.flip(1L, 1L);

    assertEquals(1, map.getLongCardinality());
    assertEquals(0, map.select(0));
  }

  @Test
  public void testFlipPositiveStartGreaterThanEndNoChange() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(0);
    map.flip(2L, 1L);

    assertEquals(1, map.getLongCardinality());
    assertEquals(0, map.select(0));
  }

  @Test
  public void testFlipNegStartGreaterThanEndNoChange() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(0);
    map.flip(-1L, -3L);

    assertEquals(1, map.getLongCardinality());
    assertEquals(0, map.select(0));
  }

  @Test
  public void testFlipNegStartGreaterThanPosEndNoChange() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(0);
    map.flip(-1L, 0x7FffFFffFFffFFffL);

    assertEquals(1, map.getLongCardinality());
    assertEquals(0, map.select(0));
  }

  @Test
  public void testFlipRangeCrossingFromPosToNegInHexWorks() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(0);
    map.flip(0x7FffFFffFFffFFffL, 0x8000000000000001L);

    assertEquals(3, map.getLongCardinality());
    assertEquals(0L, map.select(0));
    assertEquals(0x7FffFFffFFffFFffL, map.select(1));
    assertEquals(0x8000000000000000L, map.select(2));
  }

  @Test
  public void testFlipRangeCrossingFromPosToNegInDecWorks() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(0);
    map.flip(9223372036854775807L, -9223372036854775807L);

    assertEquals(3, map.getLongCardinality());
    assertEquals(0L, map.select(0));
    assertEquals(9223372036854775807L, map.select(1));
    assertEquals(-9223372036854775808L, map.select(2));
  }

  @Test
  public void testFlipSmallRangesInNegWorks() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(0);
    map.flip(-4294967297L, -4294967296L);

    assertEquals(2, map.getLongCardinality());
    assertEquals(0L, map.select(0));
    assertEquals(-4294967297L, map.select(1));
  }

  @Test
  public void testFlipEdgeOfLongWorks() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(0);
    map.flip(-2L, 0L);

    assertEquals(3, map.getLongCardinality());
    assertEquals(0L, map.select(0));
    assertEquals(-2L, map.select(1));
  }

  @Test
  public void testToString() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(123);
    map.addLong(Long.MAX_VALUE);
    map.addLong(Long.MAX_VALUE + 1L);

    assertEquals("{123,9223372036854775807,-9223372036854775808}", map.toString());
  }

  @Test
  public void testInvalidIntMask() {
    PersistentLongRoaringBitmap map = new PersistentLongRoaringBitmap();
    int a = 0xFFFFFFFF; // -1 in two's compliment
    map.addInt(a);
    assertEquals(map.getIntCardinality(), 1);
    long addedInt = map.getLongIterator().next();
    assertEquals(0xFFFFFFFFL, addedInt);
  }

  @Test
  public void testAddInvalidRange() {
    PersistentLongRoaringBitmap map = new PersistentLongRoaringBitmap();
    // Zero edge-case
    assertThrows(IllegalArgumentException.class, () -> map.addRange(0L, 0L));

    // Same higher parts, different lower parts
    assertThrows(IllegalArgumentException.class, () -> map.addRange(1L, 0L));
    assertThrows(IllegalArgumentException.class, () -> map.addRange(-1, -2));

    // Different higher parts
    assertThrows(IllegalArgumentException.class, () -> map.addRange(Long.MAX_VALUE, 0L));
    assertThrows(
        IllegalArgumentException.class, () -> map.addRange(Long.MIN_VALUE, Long.MAX_VALUE));
  }

  @Test
  public void testAddRangeSingleBucket() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addRange(5L, 12L);
    assertEquals(7L, map.getLongCardinality());

    assertEquals(5L, map.select(0));
    assertEquals(11L, map.select(6L));
  }

  // Edge case: the last high is excluded and should not lead to a new bitmap. However, it may be
  // seen only while trying to add for high=1
  @Test
  public void testAddRangeEndExcludingNextBitmapFirstLow() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    long end = toUnsignedLong(-1) + 1;

    map.addRange(end - 2, end);
    assertEquals(2, map.getLongCardinality());

    assertEquals(end - 2, map.select(0));
    assertEquals(end - 1, map.select(1));
  }

  @Test
  public void testAddRangeMultipleBuckets() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    int enableTrim = 5;

    long from = RoaringIntPacking.pack(0, -1 - enableTrim);
    long to = from + 2 * enableTrim;
    map.addRange(from, to);
    int nbItems = (int) (to - from);
    assertEquals(nbItems, map.getLongCardinality());

    assertEquals(from, map.select(0));
    assertEquals(to - 1, map.select(nbItems - 1));
  }

  public static final long outOfRoaringBitmapRange = 2L * Integer.MAX_VALUE + 3L;

  // Check this range is not handled by PersistentRoaringBitmap
  @Test
  public void testCardinalityAboveIntegerMaxValue_RoaringBitmap() {
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          PersistentRoaringBitmap map = new PersistentRoaringBitmap();

          map.add(0L, outOfRoaringBitmapRange);
        });
  }

  @Test
  public void testCardinalityAboveIntegerMaxValue() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    long outOfSingleRoaring = outOfRoaringBitmapRange - 3;

    // This should fill entirely one bitmap,and add one in the next bitmap
    map.addRange(0, outOfSingleRoaring);
    assertEquals(outOfSingleRoaring, map.getLongCardinality());

    assertEquals(outOfSingleRoaring, map.getLongCardinality());

    assertEquals(0, map.select(0));
    assertEquals(outOfSingleRoaring - 1, map.select(outOfSingleRoaring - 1));
  }

  @Test
  public void testRoaringBitmapSelectAboveIntegerMaxValue() {
    PersistentRoaringBitmap map = new PersistentRoaringBitmap();

    long maxForRoaringBitmap = toUnsignedLong(-1) + 1;
    map.add(0L, maxForRoaringBitmap);

    assertEquals(maxForRoaringBitmap, map.getLongCardinality());
    assertEquals(-1, map.select(-1));
  }

  @Test
  public void testTrim() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    // How many contiguous values do we have to set to enable .trim?
    int enableTrim = 100;

    long from = RoaringIntPacking.pack(0, -1 - enableTrim);
    long to = from + 2 * enableTrim;

    // Check we cover different buckets
    assertNotEquals(RoaringIntPacking.high(to), RoaringIntPacking.high(from));

    for (long i = from; i <= to; i++) {
      map.addLong(i);
    }

    map.trim();
  }

  @Test
  public void testAutoboxedIterator() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(123);
    map.addLong(234);

    Iterator<Long> it = map.iterator();

    assertTrue(it.hasNext());
    assertEquals(123L, it.next().longValue());
    assertTrue(it.hasNext());
    assertEquals(234, it.next().longValue());
    assertFalse(it.hasNext());
  }

  @Test
  public void testAutoboxedIteratorCanNotRemove() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> {
          PersistentLongRoaringBitmap map = newDefaultCtor();

          map.addLong(123);
          map.addLong(234);

          Iterator<Long> it = map.iterator();

          assertTrue(it.hasNext());

          // Should throw a UnsupportedOperationException
          it.remove();
        });
  }

  @Test
  public void testSelectMultipleBuckets() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(123);
    map.addLong(Long.MAX_VALUE);

    assertEquals(123L, map.select(0));
    assertEquals(Long.MAX_VALUE, map.select(1));
  }

  @Test
  public void testSelectEmpty() {
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          PersistentLongRoaringBitmap map = newDefaultCtor();

          map.select(0);
        });
  }

  @Test
  public void testSelectOutOfBoundsMatchCardinality() {
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          PersistentLongRoaringBitmap map = newDefaultCtor();
          map.addLong(123);
          map.select(1);
        });
  }

  @Test
  public void testSelectOutOfBoundsOtherCardinality() {
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          PersistentLongRoaringBitmap map = newDefaultCtor();
          map.addLong(123);
          map.select(2);
        });
  }

  @Test
  public void testRankMultipleBuckets() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(123);
    map.addLong(Long.MAX_VALUE);

    assertEquals(0, map.rankLong(0));
    assertEquals(1, map.rankLong(123));
    assertEquals(1, map.rankLong(Long.MAX_VALUE - 1));
    assertEquals(2, map.rankLong(Long.MAX_VALUE));
  }

  @Test
  public void testRankHighNotPresent() {
    PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(123);
    map.addLong(Long.MAX_VALUE);

    assertEquals(1, map.rankLong(Long.MAX_VALUE / 2L));
  }

  @Test
  public void testRunOptimize() {
    PersistentLongRoaringBitmap map = new PersistentLongRoaringBitmap();
    map.addLong(123);
    map.addLong(234);
    map.runOptimize();
  }

  @Test
  public void testFlipBackward() {
    final PersistentLongRoaringBitmap r = newDefaultCtor();
    final long value = 1L;
    r.addLong(value);
    assertEquals(1, r.getLongCardinality());
    r.flip(1);
    assertEquals(0, r.getLongCardinality());
  }

  @Test
  public void testFlip() {
    PersistentLongRoaringBitmap map = newDefaultCtor();

    map.addLong(0);
    map.flip(0);

    assertFalse(map.contains(0));
    assertTrue(map.getLongCardinality() == 0);
  }

  // Ensure the ordering behavior with default constructors is the same between PersistentRoaringBitmap and
  // PersistentLongRoaringBitmap. Typically ensures longs are managed as unsigned longs
  @Test
  public void testDefaultBehaviorLikeRoaring() {
    PersistentLongRoaringBitmap longBitmap = newDefaultCtor();
    PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();

    longBitmap.addLong(-1);
    bitmap.add(-1);

    longBitmap.addLong(1);
    bitmap.add(1);

    int[] bitmapAsIntArray = bitmap.toArray();

    long[] longBitmapAsArray = longBitmap.toArray();

    // The array seems equivalent, but beware one represents unsigned integers while the others
    // holds unsigned longs: -1 have a different meaning
    assertArrayEquals(bitmapAsIntArray, Ints.toArray(Longs.asList(longBitmapAsArray)));
    assertArrayEquals(Longs.toArray(Ints.asList(bitmapAsIntArray)), longBitmapAsArray);

    long[] bitmapAsLongArray = new long[bitmapAsIntArray.length];
    for (int i = 0; i < bitmapAsIntArray.length; i++) {
      bitmapAsLongArray[i] = toUnsignedLong(bitmapAsIntArray[i]);
    }
  }

  @Test
  public void testRandomAddRemove() {
    Random r = new Random(1234);

    // We need to max the considered range of longs, else each long would be in a different bucket
    long max = Integer.MAX_VALUE * 20L;

    long targetCardinality = 1000;

    PersistentLongRoaringBitmap map = newDefaultCtor();

    // Add a lot of items
    while (map.getIntCardinality() < targetCardinality) {
      long v = r.nextLong() % max;
      map.addLong(v);
    }
    // Remove them by chunks
    int chunks = 10;
    for (int j = 0; j < chunks; j++) {
      long chunksSize = targetCardinality / chunks;
      for (int i = 0; i < chunksSize; i++) {
        long v = map.select(r.nextInt(map.getIntCardinality()));
        assertTrue(map.contains(v));
        map.removeLong(v);
        assertFalse(map.contains(v));
      }
      assertEquals(targetCardinality - chunksSize * (j + 1), map.getIntCardinality());
    }
    assertTrue(map.isEmpty());
  }

  @Test
  public void testSerializationSizeInBytes() throws IOException, ClassNotFoundException {
    final PersistentLongRoaringBitmap map = newDefaultCtor();
    map.addLong(123);
    map.addLong(Long.MAX_VALUE);

    checkSerializeBytes(map);
  }

  @Test
  public void testHashCodeEquals() {
    PersistentLongRoaringBitmap left = newDefaultCtor();

    left.addLong(123);
    left.addLong(Long.MAX_VALUE);

    PersistentLongRoaringBitmap right = PersistentLongRoaringBitmap.bitmapOf(123, Long.MAX_VALUE);

    assertEquals(left.hashCode(), right.hashCode());
    assertEquals(left, right);
    assertEquals(right, left);
  }

  @Test
  public void testIssue428() {
    long input = 1353768194141061120L;

    long[] compare =
        new long[] {
          5192650370358181888L,
          5193776270265024512L,
          5194532734264934400L,
          5194544828892839936L,
          5194545653526560768L,
          5194545688960040960L,
          5194545692181266432L,
          5194545705066168320L,
          5194545722246037504L,
          5194545928404467712L,
          5194550326450978816L,
          5194620695195156480L,
          5206161169240293376L
        };

    PersistentLongRoaringBitmap inputRB = new PersistentLongRoaringBitmap();
    inputRB.add(input);

    PersistentLongRoaringBitmap compareRB = new PersistentLongRoaringBitmap();
    compareRB.add(compare);
    compareRB.and(inputRB);
    assertEquals(0, compareRB.getIntCardinality());

    compareRB = new PersistentLongRoaringBitmap();
    compareRB.add(compare);
    compareRB.or(inputRB);
    assertEquals(14, compareRB.getIntCardinality());

    compareRB = new PersistentLongRoaringBitmap();
    compareRB.add(compare);
    compareRB.andNot(inputRB);
    assertEquals(13, compareRB.getIntCardinality());
  }

  @Test
  public void shouldNotThrowNPE() {

    long[] inputs = new long[] {5183829215128059904L};
    long[] crossers =
        new long[] {
          4413527634823086080L,
          4418031234450456576L,
          4421408934170984448L,
          4421690409147695104L,
          4421479302915162112L,
          4421426526357028864L,
          4421413332217495552L,
          4421416630752378880L,
          4421416905630285824L,
          4421417111788716032L,
          4421417128968585216L,
          4421417133263552512L,
          4421417134337294336L
        };

    PersistentLongRoaringBitmap refRB = new PersistentLongRoaringBitmap();
    refRB.add(inputs);
    PersistentLongRoaringBitmap crossRB = new PersistentLongRoaringBitmap();
    crossRB.add(crossers);
    crossRB.and(refRB);
    assertEquals(0, crossRB.getIntCardinality());
  }

  @Test
  public void shouldNotThrowAIOOB() {
    long[] inputs = new long[] {5183829215128059904L};
    long[] crossers =
        new long[] {
          4413527634823086080L,
          4418031234450456576L,
          4421408934170984448L,
          4421127459194273792L,
          4420916352961740800L,
          4420863576403607552L,
          4420850382264074240L,
          4420847083729190912L,
          4420847358607097856L,
          4420847564765528064L,
          4420847616305135616L,
          4420847620600102912L,
          4420847623821328384L
        };
    PersistentLongRoaringBitmap referenceRB = new PersistentLongRoaringBitmap();
    referenceRB.add(inputs);
    PersistentLongRoaringBitmap crossRB = new PersistentLongRoaringBitmap();
    crossRB.add(crossers);
    crossRB.and(referenceRB);
    assertEquals(0, crossRB.getIntCardinality());
  }

  @Test
  public void shouldNotThrowIAE() {

    long[] inputs = new long[] {5183829215128059904L};
    long[] crossers = new long[] {4421416447812311717L, 4420658333523655893L, 4420658332008999025L};

    PersistentLongRoaringBitmap referenceRB = new PersistentLongRoaringBitmap();
    referenceRB.add(inputs);
    PersistentLongRoaringBitmap crossRB = new PersistentLongRoaringBitmap();
    crossRB.add(crossers);
    crossRB.and(referenceRB);
    assertEquals(0, crossRB.getIntCardinality());
  }

  @Test
  public void testSkips() {
    final Random source = new Random(0xcb000a2b9b5bdfb6l);
    final long[] data = takeSortedAndDistinct(source, 45000);
    PersistentLongRoaringBitmap bitmap = PersistentLongRoaringBitmap.bitmapOf(data);
    PeekableLongIterator pii = bitmap.getLongIterator();
    for (int i = 0; i < data.length; ++i) {
      pii.advanceIfNeeded(data[i]);
      assertEquals(data[i], pii.peekNext());
    }
    pii = bitmap.getLongIterator();
    for (int i = 0; i < data.length; ++i) {
      pii.advanceIfNeeded(data[i]);
      assertEquals(data[i], pii.next());
    }
    pii = bitmap.getLongIterator();
    for (int i = 1; i < data.length; ++i) {
      pii.advanceIfNeeded(data[i - 1]);
      assertEquals(data[i - 1], pii.next());
      assertEquals(data[i], pii.peekNext());
    }
    bitmap.getLongIterator().advanceIfNeeded(-1); // should not crash
    bitmap.getLongIteratorFrom(-1); // should not crash
  }

  @Test
  public void testSkipsDense() {
    PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();
    int n = 100000;
    for (long i = 0; i < n; ++i) {
      bitmap.add(2 * i + Integer.MAX_VALUE);
    }

    // use advance
    for (long i = 0; i < n; ++i) {
      PeekableLongIterator pii = bitmap.getLongIterator();
      long expected = 2 * i + Integer.MAX_VALUE;
      pii.advanceIfNeeded(expected);
      assertEquals(expected, pii.peekNext());
      assertEquals(expected, pii.next());
    }

    // use iterator from
    for (long i = 0; i < n; ++i) {
      long expected = 2 * i + Integer.MAX_VALUE;
      PeekableLongIterator pii = bitmap.getLongIteratorFrom(expected);
      assertEquals(expected, pii.peekNext());
      assertEquals(expected, pii.next());
    }
  }

  @Test
  public void testSkipsMultipleHighPoints() {
    PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();

    int n = 100000;
    int numHighPoints = 10;
    for (long h = 0; h < numHighPoints; ++h) {
      long base = h << 16;
      for (long i = 0; i < n; ++i) {
        bitmap.add(2 * i + base);
      }
    }
    for (long h = 0; h < numHighPoints; ++h) {
      long base = h << 16;

      // use advance
      for (long i = 0; i < n; ++i) {
        PeekableLongIterator pii = bitmap.getLongIterator();
        long expected = 2 * i + base;
        pii.advanceIfNeeded(expected);
        assertEquals(expected, pii.peekNext());
        assertEquals(expected, pii.next());
      }

      // use iterator from
      for (long i = 0; i < n; ++i) {
        long expected = 2 * i + base;
        PeekableLongIterator pii = bitmap.getLongIteratorFrom(expected);
        assertEquals(expected, pii.peekNext());
        assertEquals(expected, pii.next());
      }
    }
  }

  @Test
  public void testSkipsRun() {
    PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();
    bitmap.addRange(4L, 100000L);
    bitmap.runOptimize();
    // use advance
    for (int i = 4; i < 100000; ++i) {
      PeekableLongIterator pii = bitmap.getLongIterator();
      pii.advanceIfNeeded(i);
      assertEquals(i, pii.peekNext());
      assertEquals(i, pii.next());
    }
    // use iterator from
    for (int i = 4; i < 100000; ++i) {
      PeekableLongIterator pii = bitmap.getLongIteratorFrom(i);
      assertEquals(i, pii.peekNext());
      assertEquals(i, pii.next());
    }
  }

  @Test
  public void testEmptySkips() {
    PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();
    PeekableLongIterator it = bitmap.getLongIterator();
    it.advanceIfNeeded(0);

    bitmap.getLongIteratorFrom(0);
  }

  @Test
  public void testSkipsReverse() {
    final Random source = new Random(0xcb000a2b9b5bdfb6l);
    final long[] data = takeSortedAndDistinct(source, 45000);
    PersistentLongRoaringBitmap bitmap = PersistentLongRoaringBitmap.bitmapOf(data);
    PeekableLongIterator pii = bitmap.getReverseLongIterator();
    for (int i = data.length - 1; i >= 0; --i) {
      pii.advanceIfNeeded(data[i]);
      assertEquals(data[i], pii.peekNext());
    }
    pii = bitmap.getReverseLongIterator();
    for (int i = data.length - 1; i >= 0; --i) {
      pii.advanceIfNeeded(data[i]);
      assertEquals(data[i], pii.next());
    }
    pii = bitmap.getReverseLongIterator();
    for (int i = data.length - 2; i >= 0; --i) {
      pii.advanceIfNeeded(data[i + 1]);
      pii.next();
      assertEquals(data[i], pii.peekNext());
    }
    bitmap.getReverseLongIterator().advanceIfNeeded(-1); // should not crash
    bitmap.getReverseLongIteratorFrom(-1); // should not crash
  }

  @Test
  public void testSkipsDenseReverse() {
    PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();
    int n = 100000;
    for (long i = 0; i < n; ++i) {
      bitmap.add(2 * i + Integer.MAX_VALUE);
    }
    // use advance
    for (long i = n - 1; i >= 0; --i) {
      long expected = 2 * i + Integer.MAX_VALUE;
      PeekableLongIterator pii = bitmap.getReverseLongIterator();
      pii.advanceIfNeeded(expected);
      assertEquals(expected, pii.peekNext());
      assertEquals(expected, pii.next());
    }

    // use iterator from
    for (long i = n - 1; i >= 0; --i) {
      long expected = 2 * i + Integer.MAX_VALUE;
      PeekableLongIterator pii = bitmap.getReverseLongIteratorFrom(expected);
      assertEquals(expected, pii.peekNext());
      assertEquals(expected, pii.next());
    }
  }

  @Test
  public void testSkipsMultipleHighPointsReverse() {
    PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();

    int n = 100000;
    int numHighPoints = 10;
    for (long h = 0; h < numHighPoints; ++h) {
      long base = h << 16;
      for (long i = 0; i < n; ++i) {
        bitmap.add(2 * i + base);
      }
    }
    for (long h = 0; h < numHighPoints; ++h) {
      long base = h << 16;

      // use advance
      for (long i = n - 1; i >= 0; --i) {
        PeekableLongIterator pii = bitmap.getReverseLongIterator();
        long expected = 2 * i + base;
        pii.advanceIfNeeded(expected);
        assertEquals(expected, pii.peekNext());
        assertEquals(expected, pii.next());
      }

      // use iterator from
      for (long i = n - 1; i >= 0; --i) {
        long expected = 2 * i + base;
        PeekableLongIterator pii = bitmap.getReverseLongIteratorFrom(expected);
        assertEquals(expected, pii.peekNext());
        assertEquals(expected, pii.next());
      }
    }
  }

  @Test
  public void testSkipsRunReverse() {
    PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();
    bitmap.addRange(4L, 100000L);
    bitmap.runOptimize();

    // use advance
    for (int i = 99999; i >= 4; --i) {
      PeekableLongIterator pii = bitmap.getReverseLongIterator();
      pii.advanceIfNeeded(i);
      assertEquals(i, pii.peekNext());
      assertEquals(i, pii.next());
    }

    // use iterator from
    for (int i = 99999; i >= 4; --i) {
      PeekableLongIterator pii = bitmap.getReverseLongIteratorFrom(i);
      assertEquals(i, pii.peekNext());
      assertEquals(i, pii.next());
    }
  }

  @Test
  public void testEmptySkipsReverse() {
    PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();
    PeekableLongIterator it = bitmap.getReverseLongIterator();
    it.advanceIfNeeded(0);

    bitmap.getReverseLongIteratorFrom(0);
  }

  @Test
  public void testSkipIntoGaps() {
    PersistentLongRoaringBitmap bitset = new PersistentLongRoaringBitmap();
    long b1 = 2000000000L;
    long b1s = 18500L;
    long b1e = b1 + b1s;
    long p2 = b1 + (b1s / 2);
    long pgap = p2 + b1s;
    long b2 = 4000000000L;
    long b2s = 100L;
    long b2e = b2 + b2s;

    bitset.addRange(b1, b1e);
    bitset.addRange(b2, b2e);

    PeekableLongIterator bitIt = bitset.getLongIterator();

    assertEquals(b1, bitIt.peekNext());
    assertEquals(b1, bitIt.next());

    assertTrue(bitset.contains(p2));
    bitIt.advanceIfNeeded(p2);
    assertEquals(p2, bitIt.peekNext());
    assertEquals(p2, bitIt.next());

    // advancing to a value not in either range should go to the first value of second range
    assertFalse(bitset.contains(pgap));
    bitIt.advanceIfNeeded(pgap);

    assertTrue(bitset.contains(b2));
    assertTrue(bitset.contains(b2e - 1L));
    assertEquals(b2, bitIt.peekNext());

    assertTrue(bitset.contains(b2));
    bitIt.advanceIfNeeded(b2);
    assertEquals(b2, bitIt.peekNext());
    assertEquals(b2, bitIt.next());
  }

  @Test
  public void testSkipIntoFarAwayGaps() {
    PersistentLongRoaringBitmap bitset = new PersistentLongRoaringBitmap();
    // long runLength = 18500L;
    long runLength = 4 << 20; // ~ 4mio
    long b1 = 2000000000L;
    long b1e = b1 + runLength;
    long p2 = b1 + (runLength / 2);
    long b2 = 4000000000L;
    long b2e = b2 + runLength;
    long p3 = b2 + (runLength / 2);
    long pgapSameContainer = p3 + runLength;
    long pgapNextContainer = p3 + 5 * runLength;
    long b3 = 6000000000L;
    long b3e = b3 + runLength;

    bitset.addRange(b1, b1e);
    bitset.addRange(b2, b2e);
    bitset.addRange(b3, b3e);

    PeekableLongIterator bitIt = bitset.getLongIterator();

    assertEquals(b1, bitIt.peekNext());
    assertEquals(b1, bitIt.next());

    assertTrue(bitset.contains(p2));
    bitIt.advanceIfNeeded(p2);
    assertEquals(p2, bitIt.peekNext());
    assertEquals(p2, bitIt.next());

    // advancing to a value not in any range but beyond second range
    // should go to the first value of third range
    assertFalse(bitset.contains(pgapSameContainer));
    bitIt.advanceIfNeeded(pgapSameContainer);

    assertTrue(bitset.contains(b3));
    assertTrue(bitset.contains(b3e - 1L));
    assertEquals(b3, bitIt.peekNext());

    assertTrue(bitset.contains(b3));
    bitIt.advanceIfNeeded(b3);
    assertEquals(b3, bitIt.peekNext());
    assertEquals(b3, bitIt.next());

    // reset
    bitIt = bitset.getLongIterator();
    bitIt.advanceIfNeeded(p2);

    // advancing to a value not in any range but beyond second range
    // should go to the first value of third range
    assertFalse(bitset.contains(pgapNextContainer));
    bitIt.advanceIfNeeded(pgapNextContainer);

    assertEquals(b3, bitIt.peekNext());

    bitIt.advanceIfNeeded(b3);
    assertEquals(b3, bitIt.peekNext());
    assertEquals(b3, bitIt.next());
  }

  @Test
  public void testSkipIntoGapsReverse() {
    PersistentLongRoaringBitmap bitset = new PersistentLongRoaringBitmap();
    long b1 = 2000000000L;
    long b1s = 18500L;
    long b1e = b1 + b1s;
    long b2 = 4000000000L;
    long b2s = 100L;
    long b2e = b2 + b2s;
    long p2 = b2 + (b2s / 2);
    long pgap = p2 - b1s;

    bitset.addRange(b1, b1e);
    bitset.addRange(b2, b2e);

    PeekableLongIterator bitIt = bitset.getReverseLongIterator();

    assertEquals(b2e - 1L, bitIt.peekNext());
    assertEquals(b2e - 1L, bitIt.next());

    assertTrue(bitset.contains(p2));
    bitIt.advanceIfNeeded(p2);
    assertEquals(p2, bitIt.peekNext());
    assertEquals(p2, bitIt.next());

    // advancing to a value not in either range should go to the first value of second range
    assertFalse(bitset.contains(pgap));
    bitIt.advanceIfNeeded(pgap);

    assertTrue(bitset.contains(b1));
    assertTrue(bitset.contains(b1e - 1L));
    assertEquals(b1e - 1L, bitIt.peekNext());

    assertTrue(bitset.contains(b2));
    bitIt.advanceIfNeeded(b1e);
    assertEquals(b1e - 1L, bitIt.peekNext());
    assertEquals(b1e - 1L, bitIt.next());
  }

  @Test
  public void testSkipIntoFarAwayGapsReverse() {
    PersistentLongRoaringBitmap bitset = new PersistentLongRoaringBitmap();
    // long runLength = 18500L;
    long runLength = 4 << 20; // ~ 4mio
    long b1 = 2000000000L;
    long b1e = b1 + runLength;
    long b2 = 4000000000L;
    long b2e = b2 + runLength;
    long p3 = b2 + (runLength / 2);
    long pgapSameContainer = p3 - runLength;
    long pgapNextContainer = p3 - 5 * runLength;
    long b3 = 6000000000L;
    long b3e = b3 + runLength;

    bitset.addRange(b1, b1e);
    bitset.addRange(b2, b2e);
    bitset.addRange(b3, b3e);

    PeekableLongIterator bitIt = bitset.getReverseLongIterator();

    assertEquals(b3e - 1L, bitIt.peekNext());
    assertEquals(b3e - 1L, bitIt.next());

    assertTrue(bitset.contains(p3));
    bitIt.advanceIfNeeded(p3);
    assertEquals(p3, bitIt.peekNext());
    assertEquals(p3, bitIt.next());

    // advancing to a value not in any range but beyond second range
    // should go to the first value of third range
    assertFalse(bitset.contains(pgapSameContainer));
    bitIt.advanceIfNeeded(pgapSameContainer);

    assertTrue(bitset.contains(b1));
    assertTrue(bitset.contains(b1e - 1L));
    assertEquals(b1e - 1L, bitIt.peekNext());

    assertTrue(bitset.contains(b1));
    bitIt.advanceIfNeeded(b1e);
    assertEquals(b1e - 1L, bitIt.peekNext());
    assertEquals(b1e - 1L, bitIt.next());

    // reset
    bitIt = bitset.getReverseLongIterator();
    bitIt.advanceIfNeeded(p3);

    // advancing to a value not in any range but beyond second range
    // should go to the first value of third range
    assertFalse(bitset.contains(pgapNextContainer));
    bitIt.advanceIfNeeded(pgapNextContainer);

    assertEquals(b1e - 1L, bitIt.peekNext());

    bitIt.advanceIfNeeded(b1e);
    assertEquals(b1e - 1L, bitIt.peekNext());
    assertEquals(b1e - 1L, bitIt.next());
  }

  @Test
  public void testLongTreatedAsUnsignedOnAdvance() {
    PersistentLongRoaringBitmap bitset = new PersistentLongRoaringBitmap();
    bitset.addRange(Long.MAX_VALUE, Long.MIN_VALUE + 3);

    PeekableLongIterator bitIt = bitset.getLongIterator();

    bitIt.advanceIfNeeded(Long.MAX_VALUE);
    assertEquals(Long.MAX_VALUE, bitIt.peekNext());

    bitIt.advanceIfNeeded(Long.MIN_VALUE + 1);
    assertEquals(Long.MIN_VALUE + 1, bitIt.peekNext());
  }

  @Test
  public void testLongTreatedAsUnsignedOnAdvanceReverse() {
    PersistentLongRoaringBitmap bitset = new PersistentLongRoaringBitmap();
    bitset.addRange(Long.MAX_VALUE, Long.MIN_VALUE + 3);

    PeekableLongIterator bitIt = bitset.getReverseLongIterator();

    bitIt.advanceIfNeeded(Long.MIN_VALUE + 1);
    assertEquals(Long.MIN_VALUE + 1, bitIt.peekNext());

    bitIt.advanceIfNeeded(Long.MAX_VALUE);
    assertEquals(Long.MAX_VALUE, bitIt.peekNext());
  }

  private static long[] takeSortedAndDistinct(Random source, int count) {
    LinkedHashSet<Long> longs = new LinkedHashSet<>(count);
    for (int size = 0; size < count; size++) {
      long next;
      do {
        next = Math.abs(source.nextLong());
      } while (!longs.add(next));
    }
    long[] unboxed = Longs.toArray(longs);
    Arrays.sort(unboxed);
    return unboxed;
  }

  @Test
  public void leafNodeIteratorPeeking() {
    final Random source = new Random(0xcb000a2b9b5bdfb6l);
    final long[] data = takeSortedAndDistinct(source, 45000);
    PersistentLongRoaringBitmap bitmap = PersistentLongRoaringBitmap.bitmapOf(data);
    bitmap.runOptimize();

    LeafNodeIterator lni = bitmap.getLeafNodeIterator();
    lni.peekNext();
    while (lni.hasNext()) {
      LeafNode peeked = lni.peekNext();
      LeafNode next = lni.next();
      assertEquals(peeked, next);
    }
    assertThrows(NoSuchElementException.class, () -> lni.peekNext());
  }

  @Test
  public void testForAllInRangeContinuous() {
    PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();
    bitmap.addRange(100L, 10000L);

    ValidationRangeConsumer consumer = ValidationRangeConsumer.validateContinuous(9900, PRESENT);
    bitmap.forAllInRange(100, 9900, consumer);
    assertEquals(9900, consumer.getNumberOfValuesConsumed());

    ValidationRangeConsumer consumer2 = ValidationRangeConsumer.validateContinuous(1000, ABSENT);
    bitmap.forAllInRange(10001, 1000, consumer2);
    assertEquals(1000, consumer2.getNumberOfValuesConsumed());

    ValidationRangeConsumer consumer3 =
        ValidationRangeConsumer.validate(
            new ValidationRangeConsumer.Value[] {ABSENT, ABSENT, PRESENT, PRESENT, PRESENT});
    bitmap.forAllInRange(98, 5, consumer3);
    assertEquals(5, consumer3.getNumberOfValuesConsumed());

    ValidationRangeConsumer consumer4 =
        ValidationRangeConsumer.validate(
            new ValidationRangeConsumer.Value[] {PRESENT, PRESENT, ABSENT, ABSENT, ABSENT});
    bitmap.forAllInRange(9998, 5, consumer4);
    assertEquals(5, consumer4.getNumberOfValuesConsumed());
  }

  @Test
  public void testForAllInRangeDense() {
    PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();
    ValidationRangeConsumer.Value[] expected = new ValidationRangeConsumer.Value[100000];
    Arrays.fill(expected, ABSENT);
    for (int k = 0; k < 100000; k += 3) {
      bitmap.add(k);
      expected[k] = PRESENT;
    }

    ValidationRangeConsumer consumer = ValidationRangeConsumer.validate(expected);
    bitmap.forAllInRange(0, 100000, consumer);
    assertEquals(100000, consumer.getNumberOfValuesConsumed());

    ValidationRangeConsumer.Value[] expectedSubRange = Arrays.copyOfRange(expected, 2500, 6000);
    ValidationRangeConsumer consumer2 = ValidationRangeConsumer.validate(expectedSubRange);
    bitmap.forAllInRange(2500, 3500, consumer2);
    assertEquals(3500, consumer2.getNumberOfValuesConsumed());

    ValidationRangeConsumer consumer3 =
        ValidationRangeConsumer.validate(
            new ValidationRangeConsumer.Value[] {
              expected[99997], expected[99998], expected[99999], ABSENT, ABSENT, ABSENT
            });
    bitmap.forAllInRange(99997, 6, consumer3);
    assertEquals(6, consumer3.getNumberOfValuesConsumed());
  }

  @Test
  public void testForAllInRangeSparse() {
    PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();
    ValidationRangeConsumer.Value[] expected = new ValidationRangeConsumer.Value[100000];
    Arrays.fill(expected, ABSENT);
    for (int k = 0; k < 100000; k += 3000) {
      bitmap.add(k);
      expected[k] = PRESENT;
    }

    ValidationRangeConsumer consumer = ValidationRangeConsumer.validate(expected);
    bitmap.forAllInRange(0, 100000, consumer);
    assertEquals(100000, consumer.getNumberOfValuesConsumed());

    ValidationRangeConsumer.Value[] expectedSubRange = Arrays.copyOfRange(expected, 2500, 6001);
    ValidationRangeConsumer consumer2 = ValidationRangeConsumer.validate(expectedSubRange);
    bitmap.forAllInRange(2500, 3500, consumer2);
    assertEquals(3500, consumer2.getNumberOfValuesConsumed());

    ValidationRangeConsumer consumer3 = ValidationRangeConsumer.ofSize(1000);
    bitmap.forAllInRange(2500, 1000, consumer3);
    consumer3.assertAllAbsentExcept(new int[] {3000 - 2500});
    assertEquals(1000, consumer3.getNumberOfValuesConsumed());
  }

  @Test
  public void testIssue537() {
    PersistentLongRoaringBitmap a = PersistentLongRoaringBitmap.bitmapOf(275846320L);
    PersistentLongRoaringBitmap b = PersistentLongRoaringBitmap.bitmapOf(275846320L);
    PersistentLongRoaringBitmap c =
        PersistentLongRoaringBitmap.bitmapOf(
            275845652L,
            275845746L,
            275846148L,
            275847372L,
            275847380L,
            275847388L,
            275847459L,
            275847528L,
            275847586L,
            275847588L,
            275847600L,
            275847607L,
            275847610L,
            275847613L,
            275847631L,
            275847664L,
            275847672L,
            275847677L,
            275847680L,
            275847742L,
            275847808L,
            275847811L,
            275847824L,
            275847830L,
            275847856L,
            275847861L,
            275847863L,
            275847872L,
            275847896L,
            275847923L,
            275847924L,
            275847975L,
            275847990L,
            275847995L,
            275848003L,
            275848080L,
            275848081L,
            275848084L,
            275848095L,
            275848100L,
            275848120L,
            275848129L,
            275848134L,
            275848163L,
            275848174L,
            275848206L,
            275848218L,
            275848231L,
            275848272L,
            275848281L,
            275848308L,
            275848344L,
            275848376L,
            275848382L,
            275848395L,
            275848400L,
            275848411L,
            275848426L,
            275848445L,
            275848449L,
            275848451L,
            275848454L,
            275848469L);
    c.and(b);
    assertFalse(c.contains(275846320L));
    c.and(a);
    assertFalse(c.contains(275846320L));
  }

  @Test
  public void testIssue558() {
    PersistentLongRoaringBitmap rb = new PersistentLongRoaringBitmap();
    Random random = new Random(1234);
    for (int i = 0; i < 1000000; i++) {
      rb.addLong(random.nextLong());
      rb.removeLong(random.nextLong());
    }
  }

  @Test
  public void testIssue577Case1() {
    PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();
    bitmap.add(
        45011744312L,
        45008074636L,
        41842920068L,
        41829418930L,
        40860008694L,
        40232297287L,
        40182908832L,
        40171852270L,
        39933922233L,
        39794107638L);
    long maxLong = bitmap.getReverseLongIterator().peekNext();
    assertEquals(maxLong, 45011744312L);

    bitmap.forEachInRange(
        46000000000L, 1000000000, value -> fail("No values in this range, but got: " + value));
  }

  @Test
  public void testIssue577Case2() {
    PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();
    bitmap.add(30385375409L, 30399869293L, 34362979339L, 35541844320L, 36637965094L);

    bitmap.forEachInRange(33000000000L, 1000000000, value -> assertEquals(34362979339L, value));
  }

  @Test
  public void testIssue577Case3() {
    PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();
    bitmap.add(14510802367L, 26338197481L, 32716744974L, 32725817880L, 35679129730L);

    final long[] expected = new long[] {32716744974L, 32725817880L};

    bitmap.forEachInRange(
        32000000000L,
        1000000000,
        new LongConsumer() {

          int offset = 0;

          @Override
          public void accept(long value) {
            assertEquals(expected[offset], value);
            offset++;
          }
        });
  }

  @Test
  public void testWithYourself() {
    PersistentLongRoaringBitmap b1 = PersistentLongRoaringBitmap.bitmapOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    b1.runOptimize();
    b1.or(b1);
    assertTrue(b1.equals(PersistentLongRoaringBitmap.bitmapOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));
    b1.xor(b1);
    assertTrue(b1.isEmpty());
    b1 = PersistentLongRoaringBitmap.bitmapOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    b1.and(b1);
    assertTrue(b1.equals(PersistentLongRoaringBitmap.bitmapOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));
    b1.andNot(b1);
    assertTrue(b1.isEmpty());
  }

  @Test
  public void testIssue580() {
    PersistentLongRoaringBitmap rb =
        PersistentLongRoaringBitmap.bitmapOf(
            3242766498713841665L,
            3492544636360507394L,
            3418218112527884289L,
            3220956490660966402L,
            3495344165583036418L,
            3495023214002368514L,
            3485108231289675778L);
    LongIterator it = rb.getLongIterator();
    int count = 0;
    while (it.hasNext()) {
      it.next();
      count++;
    }
    assertEquals(count, 7);
  }

  @Test
  public void testAddExtremes() {
    PersistentLongRoaringBitmap x = new PersistentLongRoaringBitmap();
    x.addLong(0L);
    x.addLong(Long.MAX_VALUE);
    x.addLong(-1L);

    Assertions.assertEquals(3L, x.getLongCardinality());
    Assertions.assertArrayEquals(x.toArray(), new long[] {0, Long.MAX_VALUE, -1L});
  }

  @Test
  public void testRangeAroundLongMax() {
    PersistentLongRoaringBitmap x = new PersistentLongRoaringBitmap();
    x.addRange(Long.MAX_VALUE - 1L, Long.MAX_VALUE + 3L);

    Assertions.assertEquals(4L, x.getLongCardinality());
    Assertions.assertArrayEquals(
        x.toArray(),
        new long[] {Long.MAX_VALUE - 1L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1L});
  }

  @Test
  public void testRangeExtremeEnd() {
    PersistentLongRoaringBitmap x = newDefaultCtor();
    x.addRange(-3L, -1L);

    Assertions.assertEquals(2L, x.getLongCardinality());
    Assertions.assertArrayEquals(new long[] {-3L, -2L}, x.toArray());
  }

  @Test
  public void testEmptyFirst() {
    assertThrows(NoSuchElementException.class, () -> newDefaultCtor().first());
  }

  @Test
  public void testEmptyLast() {
    assertThrows(NoSuchElementException.class, () -> newDefaultCtor().last());
  }

  @Test
  public void testFirstLast_32b() {
    PersistentLongRoaringBitmap rb = newDefaultCtor();

    rb.add(2);
    rb.add(4);
    rb.add(8);
    assertEquals(2, rb.first());
    assertEquals(8, rb.last());
  }

  @Test
  public void testFirstLast_64b() {
    PersistentLongRoaringBitmap rb = newDefaultCtor();

    rb.add(-128);
    rb.add(-64);
    rb.add(-32);
    assertEquals(-128, rb.first());
    assertEquals(-32, rb.last());
  }

  @Test
  public void testFirstLast_32_64b() {
    PersistentLongRoaringBitmap rb = newDefaultCtor();

    rb.add(2);
    rb.add(4);
    rb.add(8);
    rb.add(-128);
    rb.add(-64);
    rb.add(-32);
    assertEquals(2, rb.first());
    assertEquals(-32, rb.last());
  }

  @Test
  public void testFirstLast_AllKindsOfNodeTypes() {
    PersistentLongRoaringBitmap rb = newDefaultCtor();
    Set<Long> source = getSourceForAllKindsOfNodeTypes();
    source.forEach(rb::addLong);

    assertEquals(source.stream().min((l, r) -> Long.compareUnsigned(l, r)).get(), rb.first());
    assertEquals(source.stream().max((l, r) -> Long.compareUnsigned(l, r)).get(), rb.last());
  }

  @Test
  public void testIssue619() {
    long[] CLEANER_VALUES = {140664568792144l};
    long[] ADDRESS_SPACE_VALUES = {140662937752432l};
    PersistentLongRoaringBitmap addressSpace = new PersistentLongRoaringBitmap();
    PersistentLongRoaringBitmap cleaner = new PersistentLongRoaringBitmap();
    int iteration = 0;
    cleaner.add(CLEANER_VALUES);
    while (true) {
      addressSpace.add(ADDRESS_SPACE_VALUES);
      addressSpace.add(CLEANER_VALUES);
      if (iteration == 33) {
        // This test case can safely break here.
        break;
      }
      addressSpace.andNot(cleaner);
      iteration++;
    }
    assertEquals(2, addressSpace.getIntCardinality());
  }

  @Test
  public void testEmptyRoaring64BitmapClonesWithoutException() {
    assertEquals(new PersistentLongRoaringBitmap(), new PersistentLongRoaringBitmap().clone());
  }
}
