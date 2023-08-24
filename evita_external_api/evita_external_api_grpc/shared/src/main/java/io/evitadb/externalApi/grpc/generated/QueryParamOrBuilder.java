// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcEvitaSessionAPI.proto

package io.evitadb.externalApi.grpc.generated;

public interface QueryParamOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.QueryParam)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The string value.
   * </pre>
   *
   * <code>string stringValue = 1;</code>
   * @return Whether the stringValue field is set.
   */
  boolean hasStringValue();
  /**
   * <pre>
   * The string value.
   * </pre>
   *
   * <code>string stringValue = 1;</code>
   * @return The stringValue.
   */
  java.lang.String getStringValue();
  /**
   * <pre>
   * The string value.
   * </pre>
   *
   * <code>string stringValue = 1;</code>
   * @return The bytes for stringValue.
   */
  com.google.protobuf.ByteString
      getStringValueBytes();

  /**
   * <pre>
   * The integer value.
   * </pre>
   *
   * <code>int32 integerValue = 2;</code>
   * @return Whether the integerValue field is set.
   */
  boolean hasIntegerValue();
  /**
   * <pre>
   * The integer value.
   * </pre>
   *
   * <code>int32 integerValue = 2;</code>
   * @return The integerValue.
   */
  int getIntegerValue();

  /**
   * <pre>
   * The long value.
   * </pre>
   *
   * <code>int64 longValue = 3;</code>
   * @return Whether the longValue field is set.
   */
  boolean hasLongValue();
  /**
   * <pre>
   * The long value.
   * </pre>
   *
   * <code>int64 longValue = 3;</code>
   * @return The longValue.
   */
  long getLongValue();

  /**
   * <pre>
   * The boolean value.
   * </pre>
   *
   * <code>bool booleanValue = 4;</code>
   * @return Whether the booleanValue field is set.
   */
  boolean hasBooleanValue();
  /**
   * <pre>
   * The boolean value.
   * </pre>
   *
   * <code>bool booleanValue = 4;</code>
   * @return The booleanValue.
   */
  boolean getBooleanValue();

  /**
   * <pre>
   * The big decimal value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal bigDecimalValue = 5;</code>
   * @return Whether the bigDecimalValue field is set.
   */
  boolean hasBigDecimalValue();
  /**
   * <pre>
   * The big decimal value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal bigDecimalValue = 5;</code>
   * @return The bigDecimalValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimal getBigDecimalValue();
  /**
   * <pre>
   * The big decimal value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal bigDecimalValue = 5;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimalOrBuilder getBigDecimalValueOrBuilder();

  /**
   * <pre>
   * The date time range value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcDateTimeRange dateTimeRangeValue = 6;</code>
   * @return Whether the dateTimeRangeValue field is set.
   */
  boolean hasDateTimeRangeValue();
  /**
   * <pre>
   * The date time range value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcDateTimeRange dateTimeRangeValue = 6;</code>
   * @return The dateTimeRangeValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcDateTimeRange getDateTimeRangeValue();
  /**
   * <pre>
   * The date time range value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcDateTimeRange dateTimeRangeValue = 6;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcDateTimeRangeOrBuilder getDateTimeRangeValueOrBuilder();

  /**
   * <pre>
   * The integer number range value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange integerNumberRangeValue = 7;</code>
   * @return Whether the integerNumberRangeValue field is set.
   */
  boolean hasIntegerNumberRangeValue();
  /**
   * <pre>
   * The integer number range value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange integerNumberRangeValue = 7;</code>
   * @return The integerNumberRangeValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange getIntegerNumberRangeValue();
  /**
   * <pre>
   * The integer number range value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRange integerNumberRangeValue = 7;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRangeOrBuilder getIntegerNumberRangeValueOrBuilder();

  /**
   * <pre>
   * The long number range value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLongNumberRange longNumberRangeValue = 8;</code>
   * @return Whether the longNumberRangeValue field is set.
   */
  boolean hasLongNumberRangeValue();
  /**
   * <pre>
   * The long number range value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLongNumberRange longNumberRangeValue = 8;</code>
   * @return The longNumberRangeValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcLongNumberRange getLongNumberRangeValue();
  /**
   * <pre>
   * The long number range value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLongNumberRange longNumberRangeValue = 8;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcLongNumberRangeOrBuilder getLongNumberRangeValueOrBuilder();

  /**
   * <pre>
   * The big decimal number range value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimalNumberRange bigDecimalNumberRangeValue = 9;</code>
   * @return Whether the bigDecimalNumberRangeValue field is set.
   */
  boolean hasBigDecimalNumberRangeValue();
  /**
   * <pre>
   * The big decimal number range value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimalNumberRange bigDecimalNumberRangeValue = 9;</code>
   * @return The bigDecimalNumberRangeValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimalNumberRange getBigDecimalNumberRangeValue();
  /**
   * <pre>
   * The big decimal number range value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimalNumberRange bigDecimalNumberRangeValue = 9;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimalNumberRangeOrBuilder getBigDecimalNumberRangeValueOrBuilder();

  /**
   * <pre>
   * The offset date time value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime offsetDateTimeValue = 10;</code>
   * @return Whether the offsetDateTimeValue field is set.
   */
  boolean hasOffsetDateTimeValue();
  /**
   * <pre>
   * The offset date time value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime offsetDateTimeValue = 10;</code>
   * @return The offsetDateTimeValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime getOffsetDateTimeValue();
  /**
   * <pre>
   * The offset date time value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime offsetDateTimeValue = 10;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeOrBuilder getOffsetDateTimeValueOrBuilder();

  /**
   * <pre>
   * The locale value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale localeValue = 11;</code>
   * @return Whether the localeValue field is set.
   */
  boolean hasLocaleValue();
  /**
   * <pre>
   * The locale value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale localeValue = 11;</code>
   * @return The localeValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcLocale getLocaleValue();
  /**
   * <pre>
   * The locale value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale localeValue = 11;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcLocaleOrBuilder getLocaleValueOrBuilder();

  /**
   * <pre>
   * The currency value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCurrency currencyValue = 12;</code>
   * @return Whether the currencyValue field is set.
   */
  boolean hasCurrencyValue();
  /**
   * <pre>
   * The currency value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCurrency currencyValue = 12;</code>
   * @return The currencyValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcCurrency getCurrencyValue();
  /**
   * <pre>
   * The currency value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCurrency currencyValue = 12;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcCurrencyOrBuilder getCurrencyValueOrBuilder();

  /**
   * <pre>
   * The facet statistics depth enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcFacetStatisticsDepth facetStatisticsDepthValue = 13;</code>
   * @return Whether the facetStatisticsDepthValue field is set.
   */
  boolean hasFacetStatisticsDepthValue();
  /**
   * <pre>
   * The facet statistics depth enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcFacetStatisticsDepth facetStatisticsDepthValue = 13;</code>
   * @return The enum numeric value on the wire for facetStatisticsDepthValue.
   */
  int getFacetStatisticsDepthValueValue();
  /**
   * <pre>
   * The facet statistics depth enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcFacetStatisticsDepth facetStatisticsDepthValue = 13;</code>
   * @return The facetStatisticsDepthValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcFacetStatisticsDepth getFacetStatisticsDepthValue();

  /**
   * <pre>
   * The query price mode enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcQueryPriceMode queryPriceModelValue = 14;</code>
   * @return Whether the queryPriceModelValue field is set.
   */
  boolean hasQueryPriceModelValue();
  /**
   * <pre>
   * The query price mode enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcQueryPriceMode queryPriceModelValue = 14;</code>
   * @return The enum numeric value on the wire for queryPriceModelValue.
   */
  int getQueryPriceModelValueValue();
  /**
   * <pre>
   * The query price mode enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcQueryPriceMode queryPriceModelValue = 14;</code>
   * @return The queryPriceModelValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcQueryPriceMode getQueryPriceModelValue();

  /**
   * <pre>
   * The price content mode enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcPriceContentMode priceContentModeValue = 15;</code>
   * @return Whether the priceContentModeValue field is set.
   */
  boolean hasPriceContentModeValue();
  /**
   * <pre>
   * The price content mode enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcPriceContentMode priceContentModeValue = 15;</code>
   * @return The enum numeric value on the wire for priceContentModeValue.
   */
  int getPriceContentModeValueValue();
  /**
   * <pre>
   * The price content mode enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcPriceContentMode priceContentModeValue = 15;</code>
   * @return The priceContentModeValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcPriceContentMode getPriceContentModeValue();

  /**
   * <pre>
   * The attribute special value enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcAttributeSpecialValue attributeSpecialValue = 16;</code>
   * @return Whether the attributeSpecialValue field is set.
   */
  boolean hasAttributeSpecialValue();
  /**
   * <pre>
   * The attribute special value enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcAttributeSpecialValue attributeSpecialValue = 16;</code>
   * @return The enum numeric value on the wire for attributeSpecialValue.
   */
  int getAttributeSpecialValueValue();
  /**
   * <pre>
   * The attribute special value enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcAttributeSpecialValue attributeSpecialValue = 16;</code>
   * @return The attributeSpecialValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcAttributeSpecialValue getAttributeSpecialValue();

  /**
   * <pre>
   * The order direction enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOrderDirection orderDirectionValue = 17;</code>
   * @return Whether the orderDirectionValue field is set.
   */
  boolean hasOrderDirectionValue();
  /**
   * <pre>
   * The order direction enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOrderDirection orderDirectionValue = 17;</code>
   * @return The enum numeric value on the wire for orderDirectionValue.
   */
  int getOrderDirectionValueValue();
  /**
   * <pre>
   * The order direction enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOrderDirection orderDirectionValue = 17;</code>
   * @return The orderDirectionValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcOrderDirection getOrderDirectionValue();

  /**
   * <pre>
   * The empty hierarchical entity behaviour enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour = 18;</code>
   * @return Whether the emptyHierarchicalEntityBehaviour field is set.
   */
  boolean hasEmptyHierarchicalEntityBehaviour();
  /**
   * <pre>
   * The empty hierarchical entity behaviour enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour = 18;</code>
   * @return The enum numeric value on the wire for emptyHierarchicalEntityBehaviour.
   */
  int getEmptyHierarchicalEntityBehaviourValue();
  /**
   * <pre>
   * The empty hierarchical entity behaviour enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour = 18;</code>
   * @return The emptyHierarchicalEntityBehaviour.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEmptyHierarchicalEntityBehaviour getEmptyHierarchicalEntityBehaviour();

  /**
   * <pre>
   * The statistics base enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcStatisticsBase statisticsBase = 19;</code>
   * @return Whether the statisticsBase field is set.
   */
  boolean hasStatisticsBase();
  /**
   * <pre>
   * The statistics base enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcStatisticsBase statisticsBase = 19;</code>
   * @return The enum numeric value on the wire for statisticsBase.
   */
  int getStatisticsBaseValue();
  /**
   * <pre>
   * The statistics base enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcStatisticsBase statisticsBase = 19;</code>
   * @return The statisticsBase.
   */
  io.evitadb.externalApi.grpc.generated.GrpcStatisticsBase getStatisticsBase();

  /**
   * <pre>
   * The statistics type enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcStatisticsType statisticsType = 20;</code>
   * @return Whether the statisticsType field is set.
   */
  boolean hasStatisticsType();
  /**
   * <pre>
   * The statistics type enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcStatisticsType statisticsType = 20;</code>
   * @return The enum numeric value on the wire for statisticsType.
   */
  int getStatisticsTypeValue();
  /**
   * <pre>
   * The statistics type enum value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcStatisticsType statisticsType = 20;</code>
   * @return The statisticsType.
   */
  io.evitadb.externalApi.grpc.generated.GrpcStatisticsType getStatisticsType();

  /**
   * <pre>
   * The string array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcStringArray stringArrayValue = 21;</code>
   * @return Whether the stringArrayValue field is set.
   */
  boolean hasStringArrayValue();
  /**
   * <pre>
   * The string array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcStringArray stringArrayValue = 21;</code>
   * @return The stringArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcStringArray getStringArrayValue();
  /**
   * <pre>
   * The string array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcStringArray stringArrayValue = 21;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcStringArrayOrBuilder getStringArrayValueOrBuilder();

  /**
   * <pre>
   * The integer array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcIntegerArray integerArrayValue = 22;</code>
   * @return Whether the integerArrayValue field is set.
   */
  boolean hasIntegerArrayValue();
  /**
   * <pre>
   * The integer array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcIntegerArray integerArrayValue = 22;</code>
   * @return The integerArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcIntegerArray getIntegerArrayValue();
  /**
   * <pre>
   * The integer array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcIntegerArray integerArrayValue = 22;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcIntegerArrayOrBuilder getIntegerArrayValueOrBuilder();

  /**
   * <pre>
   * The long array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLongArray longArrayValue = 23;</code>
   * @return Whether the longArrayValue field is set.
   */
  boolean hasLongArrayValue();
  /**
   * <pre>
   * The long array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLongArray longArrayValue = 23;</code>
   * @return The longArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcLongArray getLongArrayValue();
  /**
   * <pre>
   * The long array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLongArray longArrayValue = 23;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcLongArrayOrBuilder getLongArrayValueOrBuilder();

  /**
   * <pre>
   * The boolean array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBooleanArray booleanArrayValue = 24;</code>
   * @return Whether the booleanArrayValue field is set.
   */
  boolean hasBooleanArrayValue();
  /**
   * <pre>
   * The boolean array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBooleanArray booleanArrayValue = 24;</code>
   * @return The booleanArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcBooleanArray getBooleanArrayValue();
  /**
   * <pre>
   * The boolean array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBooleanArray booleanArrayValue = 24;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcBooleanArrayOrBuilder getBooleanArrayValueOrBuilder();

  /**
   * <pre>
   * The big decimal array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimalArray bigDecimalArrayValue = 25;</code>
   * @return Whether the bigDecimalArrayValue field is set.
   */
  boolean hasBigDecimalArrayValue();
  /**
   * <pre>
   * The big decimal array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimalArray bigDecimalArrayValue = 25;</code>
   * @return The bigDecimalArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimalArray getBigDecimalArrayValue();
  /**
   * <pre>
   * The big decimal array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimalArray bigDecimalArrayValue = 25;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimalArrayOrBuilder getBigDecimalArrayValueOrBuilder();

  /**
   * <pre>
   * The date time range array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcDateTimeRangeArray dateTimeRangeArrayValue = 26;</code>
   * @return Whether the dateTimeRangeArrayValue field is set.
   */
  boolean hasDateTimeRangeArrayValue();
  /**
   * <pre>
   * The date time range array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcDateTimeRangeArray dateTimeRangeArrayValue = 26;</code>
   * @return The dateTimeRangeArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcDateTimeRangeArray getDateTimeRangeArrayValue();
  /**
   * <pre>
   * The date time range array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcDateTimeRangeArray dateTimeRangeArrayValue = 26;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcDateTimeRangeArrayOrBuilder getDateTimeRangeArrayValueOrBuilder();

  /**
   * <pre>
   * The integer number range array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRangeArray integerNumberRangeArrayValue = 27;</code>
   * @return Whether the integerNumberRangeArrayValue field is set.
   */
  boolean hasIntegerNumberRangeArrayValue();
  /**
   * <pre>
   * The integer number range array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRangeArray integerNumberRangeArrayValue = 27;</code>
   * @return The integerNumberRangeArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRangeArray getIntegerNumberRangeArrayValue();
  /**
   * <pre>
   * The integer number range array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRangeArray integerNumberRangeArrayValue = 27;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcIntegerNumberRangeArrayOrBuilder getIntegerNumberRangeArrayValueOrBuilder();

  /**
   * <pre>
   * The long number range array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLongNumberRangeArray longNumberRangeArrayValue = 28;</code>
   * @return Whether the longNumberRangeArrayValue field is set.
   */
  boolean hasLongNumberRangeArrayValue();
  /**
   * <pre>
   * The long number range array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLongNumberRangeArray longNumberRangeArrayValue = 28;</code>
   * @return The longNumberRangeArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcLongNumberRangeArray getLongNumberRangeArrayValue();
  /**
   * <pre>
   * The long number range array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLongNumberRangeArray longNumberRangeArrayValue = 28;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcLongNumberRangeArrayOrBuilder getLongNumberRangeArrayValueOrBuilder();

  /**
   * <pre>
   * The big decimal number range array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimalNumberRangeArray bigDecimalNumberRangeArrayValue = 29;</code>
   * @return Whether the bigDecimalNumberRangeArrayValue field is set.
   */
  boolean hasBigDecimalNumberRangeArrayValue();
  /**
   * <pre>
   * The big decimal number range array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimalNumberRangeArray bigDecimalNumberRangeArrayValue = 29;</code>
   * @return The bigDecimalNumberRangeArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimalNumberRangeArray getBigDecimalNumberRangeArrayValue();
  /**
   * <pre>
   * The big decimal number range array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimalNumberRangeArray bigDecimalNumberRangeArrayValue = 29;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimalNumberRangeArrayOrBuilder getBigDecimalNumberRangeArrayValueOrBuilder();

  /**
   * <pre>
   * The offset date time array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeArray offsetDateTimeArrayValue = 30;</code>
   * @return Whether the offsetDateTimeArrayValue field is set.
   */
  boolean hasOffsetDateTimeArrayValue();
  /**
   * <pre>
   * The offset date time array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeArray offsetDateTimeArrayValue = 30;</code>
   * @return The offsetDateTimeArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeArray getOffsetDateTimeArrayValue();
  /**
   * <pre>
   * The offset date time array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeArray offsetDateTimeArrayValue = 30;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeArrayOrBuilder getOffsetDateTimeArrayValueOrBuilder();

  /**
   * <pre>
   * The locale array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocaleArray localeArrayValue = 31;</code>
   * @return Whether the localeArrayValue field is set.
   */
  boolean hasLocaleArrayValue();
  /**
   * <pre>
   * The locale array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocaleArray localeArrayValue = 31;</code>
   * @return The localeArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcLocaleArray getLocaleArrayValue();
  /**
   * <pre>
   * The locale array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocaleArray localeArrayValue = 31;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcLocaleArrayOrBuilder getLocaleArrayValueOrBuilder();

  /**
   * <pre>
   * The currency array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCurrencyArray currencyArrayValue = 32;</code>
   * @return Whether the currencyArrayValue field is set.
   */
  boolean hasCurrencyArrayValue();
  /**
   * <pre>
   * The currency array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCurrencyArray currencyArrayValue = 32;</code>
   * @return The currencyArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcCurrencyArray getCurrencyArrayValue();
  /**
   * <pre>
   * The currency array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCurrencyArray currencyArrayValue = 32;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcCurrencyArrayOrBuilder getCurrencyArrayValueOrBuilder();

  /**
   * <pre>
   * The facet statistics depth array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcFacetStatisticsDepthArray facetStatisticsDepthArrayValue = 33;</code>
   * @return Whether the facetStatisticsDepthArrayValue field is set.
   */
  boolean hasFacetStatisticsDepthArrayValue();
  /**
   * <pre>
   * The facet statistics depth array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcFacetStatisticsDepthArray facetStatisticsDepthArrayValue = 33;</code>
   * @return The facetStatisticsDepthArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcFacetStatisticsDepthArray getFacetStatisticsDepthArrayValue();
  /**
   * <pre>
   * The facet statistics depth array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcFacetStatisticsDepthArray facetStatisticsDepthArrayValue = 33;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcFacetStatisticsDepthArrayOrBuilder getFacetStatisticsDepthArrayValueOrBuilder();

  /**
   * <pre>
   * The query price mode array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcQueryPriceModeArray queryPriceModelArrayValue = 34;</code>
   * @return Whether the queryPriceModelArrayValue field is set.
   */
  boolean hasQueryPriceModelArrayValue();
  /**
   * <pre>
   * The query price mode array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcQueryPriceModeArray queryPriceModelArrayValue = 34;</code>
   * @return The queryPriceModelArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcQueryPriceModeArray getQueryPriceModelArrayValue();
  /**
   * <pre>
   * The query price mode array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcQueryPriceModeArray queryPriceModelArrayValue = 34;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcQueryPriceModeArrayOrBuilder getQueryPriceModelArrayValueOrBuilder();

  /**
   * <pre>
   * The price content mode array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcPriceContentModeArray priceContentModeArrayValue = 35;</code>
   * @return Whether the priceContentModeArrayValue field is set.
   */
  boolean hasPriceContentModeArrayValue();
  /**
   * <pre>
   * The price content mode array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcPriceContentModeArray priceContentModeArrayValue = 35;</code>
   * @return The priceContentModeArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcPriceContentModeArray getPriceContentModeArrayValue();
  /**
   * <pre>
   * The price content mode array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcPriceContentModeArray priceContentModeArrayValue = 35;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcPriceContentModeArrayOrBuilder getPriceContentModeArrayValueOrBuilder();

  /**
   * <pre>
   * The attribute special value array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcAttributeSpecialValueArray attributeSpecialArrayValue = 36;</code>
   * @return Whether the attributeSpecialArrayValue field is set.
   */
  boolean hasAttributeSpecialArrayValue();
  /**
   * <pre>
   * The attribute special value array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcAttributeSpecialValueArray attributeSpecialArrayValue = 36;</code>
   * @return The attributeSpecialArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcAttributeSpecialValueArray getAttributeSpecialArrayValue();
  /**
   * <pre>
   * The attribute special value array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcAttributeSpecialValueArray attributeSpecialArrayValue = 36;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcAttributeSpecialValueArrayOrBuilder getAttributeSpecialArrayValueOrBuilder();

  /**
   * <pre>
   * The order direction array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOrderDirectionArray orderDirectionArrayValue = 37;</code>
   * @return Whether the orderDirectionArrayValue field is set.
   */
  boolean hasOrderDirectionArrayValue();
  /**
   * <pre>
   * The order direction array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOrderDirectionArray orderDirectionArrayValue = 37;</code>
   * @return The orderDirectionArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcOrderDirectionArray getOrderDirectionArrayValue();
  /**
   * <pre>
   * The order direction array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOrderDirectionArray orderDirectionArrayValue = 37;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcOrderDirectionArrayOrBuilder getOrderDirectionArrayValueOrBuilder();

  /**
   * <pre>
   * The empty hierarchical entity behaviour array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEmptyHierarchicalEntityBehaviourArray emptyHierarchicalEntityBehaviourArrayValue = 38;</code>
   * @return Whether the emptyHierarchicalEntityBehaviourArrayValue field is set.
   */
  boolean hasEmptyHierarchicalEntityBehaviourArrayValue();
  /**
   * <pre>
   * The empty hierarchical entity behaviour array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEmptyHierarchicalEntityBehaviourArray emptyHierarchicalEntityBehaviourArrayValue = 38;</code>
   * @return The emptyHierarchicalEntityBehaviourArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEmptyHierarchicalEntityBehaviourArray getEmptyHierarchicalEntityBehaviourArrayValue();
  /**
   * <pre>
   * The empty hierarchical entity behaviour array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEmptyHierarchicalEntityBehaviourArray emptyHierarchicalEntityBehaviourArrayValue = 38;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcEmptyHierarchicalEntityBehaviourArrayOrBuilder getEmptyHierarchicalEntityBehaviourArrayValueOrBuilder();

  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcStatisticsBaseArray statisticsBaseArrayValue = 39;</code>
   * @return Whether the statisticsBaseArrayValue field is set.
   */
  boolean hasStatisticsBaseArrayValue();
  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcStatisticsBaseArray statisticsBaseArrayValue = 39;</code>
   * @return The statisticsBaseArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcStatisticsBaseArray getStatisticsBaseArrayValue();
  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcStatisticsBaseArray statisticsBaseArrayValue = 39;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcStatisticsBaseArrayOrBuilder getStatisticsBaseArrayValueOrBuilder();

  /**
   * <pre>
   * The statistics type array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcStatisticsTypeArray statisticsTypeArrayValue = 40;</code>
   * @return Whether the statisticsTypeArrayValue field is set.
   */
  boolean hasStatisticsTypeArrayValue();
  /**
   * <pre>
   * The statistics type array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcStatisticsTypeArray statisticsTypeArrayValue = 40;</code>
   * @return The statisticsTypeArrayValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcStatisticsTypeArray getStatisticsTypeArrayValue();
  /**
   * <pre>
   * The statistics type array value.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcStatisticsTypeArray statisticsTypeArrayValue = 40;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcStatisticsTypeArrayOrBuilder getStatisticsTypeArrayValueOrBuilder();

  public io.evitadb.externalApi.grpc.generated.QueryParam.QueryParamCase getQueryParamCase();
}
