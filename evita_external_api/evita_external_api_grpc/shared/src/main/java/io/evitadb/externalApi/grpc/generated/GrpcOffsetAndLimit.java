/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcEntity.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * The OffsetAndLimit record represents pagination parameters including offset, limit, and the last page number.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit}
 */
public final class GrpcOffsetAndLimit extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit)
    GrpcOffsetAndLimitOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcOffsetAndLimit.newBuilder() to construct.
  private GrpcOffsetAndLimit(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcOffsetAndLimit() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcOffsetAndLimit();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcOffsetAndLimit(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    this();
    if (extensionRegistry == null) {
      throw new java.lang.NullPointerException();
    }
    com.google.protobuf.UnknownFieldSet.Builder unknownFields =
        com.google.protobuf.UnknownFieldSet.newBuilder();
    try {
      boolean done = false;
      while (!done) {
        int tag = input.readTag();
        switch (tag) {
          case 0:
            done = true;
            break;
          case 8: {

            offset_ = input.readInt32();
            break;
          }
          case 16: {

            limit_ = input.readInt32();
            break;
          }
          case 24: {

            pageNumber_ = input.readInt32();
            break;
          }
          case 32: {

            lastPageNumber_ = input.readInt32();
            break;
          }
          case 40: {

            totalRecordCount_ = input.readInt32();
            break;
          }
          default: {
            if (!parseUnknownField(
                input, unknownFields, extensionRegistry, tag)) {
              done = true;
            }
            break;
          }
        }
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw e.setUnfinishedMessage(this);
    } catch (java.io.IOException e) {
      throw new com.google.protobuf.InvalidProtocolBufferException(
          e).setUnfinishedMessage(this);
    } finally {
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcEntity.internal_static_io_evitadb_externalApi_grpc_generated_GrpcOffsetAndLimit_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEntity.internal_static_io_evitadb_externalApi_grpc_generated_GrpcOffsetAndLimit_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit.class, io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit.Builder.class);
  }

  public static final int OFFSET_FIELD_NUMBER = 1;
  private int offset_;
  /**
   * <pre>
   * The starting point for fetching records.
   * </pre>
   *
   * <code>int32 offset = 1;</code>
   * @return The offset.
   */
  @java.lang.Override
  public int getOffset() {
    return offset_;
  }

  public static final int LIMIT_FIELD_NUMBER = 2;
  private int limit_;
  /**
   * <pre>
   * The number of records to fetch from the starting point.
   * </pre>
   *
   * <code>int32 limit = 2;</code>
   * @return The limit.
   */
  @java.lang.Override
  public int getLimit() {
    return limit_;
  }

  public static final int PAGENUMBER_FIELD_NUMBER = 3;
  private int pageNumber_;
  /**
   * <pre>
   * The current page number based on the current pagination settings.
   * </pre>
   *
   * <code>int32 pageNumber = 3;</code>
   * @return The pageNumber.
   */
  @java.lang.Override
  public int getPageNumber() {
    return pageNumber_;
  }

  public static final int LASTPAGENUMBER_FIELD_NUMBER = 4;
  private int lastPageNumber_;
  /**
   * <pre>
   * The last page number based on the current pagination settings.
   * </pre>
   *
   * <code>int32 lastPageNumber = 4;</code>
   * @return The lastPageNumber.
   */
  @java.lang.Override
  public int getLastPageNumber() {
    return lastPageNumber_;
  }

  public static final int TOTALRECORDCOUNT_FIELD_NUMBER = 5;
  private int totalRecordCount_;
  /**
   * <pre>
   * The total number of records available.
   * </pre>
   *
   * <code>int32 totalRecordCount = 5;</code>
   * @return The totalRecordCount.
   */
  @java.lang.Override
  public int getTotalRecordCount() {
    return totalRecordCount_;
  }

  private byte memoizedIsInitialized = -1;
  @java.lang.Override
  public final boolean isInitialized() {
    byte isInitialized = memoizedIsInitialized;
    if (isInitialized == 1) return true;
    if (isInitialized == 0) return false;

    memoizedIsInitialized = 1;
    return true;
  }

  @java.lang.Override
  public void writeTo(com.google.protobuf.CodedOutputStream output)
                      throws java.io.IOException {
    if (offset_ != 0) {
      output.writeInt32(1, offset_);
    }
    if (limit_ != 0) {
      output.writeInt32(2, limit_);
    }
    if (pageNumber_ != 0) {
      output.writeInt32(3, pageNumber_);
    }
    if (lastPageNumber_ != 0) {
      output.writeInt32(4, lastPageNumber_);
    }
    if (totalRecordCount_ != 0) {
      output.writeInt32(5, totalRecordCount_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (offset_ != 0) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt32Size(1, offset_);
    }
    if (limit_ != 0) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt32Size(2, limit_);
    }
    if (pageNumber_ != 0) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt32Size(3, pageNumber_);
    }
    if (lastPageNumber_ != 0) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt32Size(4, lastPageNumber_);
    }
    if (totalRecordCount_ != 0) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt32Size(5, totalRecordCount_);
    }
    size += unknownFields.getSerializedSize();
    memoizedSize = size;
    return size;
  }

  @java.lang.Override
  public boolean equals(final java.lang.Object obj) {
    if (obj == this) {
     return true;
    }
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit other = (io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit) obj;

    if (getOffset()
        != other.getOffset()) return false;
    if (getLimit()
        != other.getLimit()) return false;
    if (getPageNumber()
        != other.getPageNumber()) return false;
    if (getLastPageNumber()
        != other.getLastPageNumber()) return false;
    if (getTotalRecordCount()
        != other.getTotalRecordCount()) return false;
    if (!unknownFields.equals(other.unknownFields)) return false;
    return true;
  }

  @java.lang.Override
  public int hashCode() {
    if (memoizedHashCode != 0) {
      return memoizedHashCode;
    }
    int hash = 41;
    hash = (19 * hash) + getDescriptor().hashCode();
    hash = (37 * hash) + OFFSET_FIELD_NUMBER;
    hash = (53 * hash) + getOffset();
    hash = (37 * hash) + LIMIT_FIELD_NUMBER;
    hash = (53 * hash) + getLimit();
    hash = (37 * hash) + PAGENUMBER_FIELD_NUMBER;
    hash = (53 * hash) + getPageNumber();
    hash = (37 * hash) + LASTPAGENUMBER_FIELD_NUMBER;
    hash = (53 * hash) + getLastPageNumber();
    hash = (37 * hash) + TOTALRECORDCOUNT_FIELD_NUMBER;
    hash = (53 * hash) + getTotalRecordCount();
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }

  @java.lang.Override
  public Builder newBuilderForType() { return newBuilder(); }
  public static Builder newBuilder() {
    return DEFAULT_INSTANCE.toBuilder();
  }
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit prototype) {
    return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
  }
  @java.lang.Override
  public Builder toBuilder() {
    return this == DEFAULT_INSTANCE
        ? new Builder() : new Builder().mergeFrom(this);
  }

  @java.lang.Override
  protected Builder newBuilderForType(
      com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
    Builder builder = new Builder(parent);
    return builder;
  }
  /**
   * <pre>
   * The OffsetAndLimit record represents pagination parameters including offset, limit, and the last page number.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit)
      io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimitOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEntity.internal_static_io_evitadb_externalApi_grpc_generated_GrpcOffsetAndLimit_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEntity.internal_static_io_evitadb_externalApi_grpc_generated_GrpcOffsetAndLimit_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit.class, io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit.newBuilder()
    private Builder() {
      maybeForceBuilderInitialization();
    }

    private Builder(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      super(parent);
      maybeForceBuilderInitialization();
    }
    private void maybeForceBuilderInitialization() {
      if (com.google.protobuf.GeneratedMessageV3
              .alwaysUseFieldBuilders) {
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      offset_ = 0;

      limit_ = 0;

      pageNumber_ = 0;

      lastPageNumber_ = 0;

      totalRecordCount_ = 0;

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEntity.internal_static_io_evitadb_externalApi_grpc_generated_GrpcOffsetAndLimit_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit build() {
      io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit result = new io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit(this);
      result.offset_ = offset_;
      result.limit_ = limit_;
      result.pageNumber_ = pageNumber_;
      result.lastPageNumber_ = lastPageNumber_;
      result.totalRecordCount_ = totalRecordCount_;
      onBuilt();
      return result;
    }

    @java.lang.Override
    public Builder clone() {
      return super.clone();
    }
    @java.lang.Override
    public Builder setField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.setField(field, value);
    }
    @java.lang.Override
    public Builder clearField(
        com.google.protobuf.Descriptors.FieldDescriptor field) {
      return super.clearField(field);
    }
    @java.lang.Override
    public Builder clearOneof(
        com.google.protobuf.Descriptors.OneofDescriptor oneof) {
      return super.clearOneof(oneof);
    }
    @java.lang.Override
    public Builder setRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        int index, java.lang.Object value) {
      return super.setRepeatedField(field, index, value);
    }
    @java.lang.Override
    public Builder addRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.addRepeatedField(field, value);
    }
    @java.lang.Override
    public Builder mergeFrom(com.google.protobuf.Message other) {
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit.getDefaultInstance()) return this;
      if (other.getOffset() != 0) {
        setOffset(other.getOffset());
      }
      if (other.getLimit() != 0) {
        setLimit(other.getLimit());
      }
      if (other.getPageNumber() != 0) {
        setPageNumber(other.getPageNumber());
      }
      if (other.getLastPageNumber() != 0) {
        setLastPageNumber(other.getLastPageNumber());
      }
      if (other.getTotalRecordCount() != 0) {
        setTotalRecordCount(other.getTotalRecordCount());
      }
      this.mergeUnknownFields(other.unknownFields);
      onChanged();
      return this;
    }

    @java.lang.Override
    public final boolean isInitialized() {
      return true;
    }

    @java.lang.Override
    public Builder mergeFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private int offset_ ;
    /**
     * <pre>
     * The starting point for fetching records.
     * </pre>
     *
     * <code>int32 offset = 1;</code>
     * @return The offset.
     */
    @java.lang.Override
    public int getOffset() {
      return offset_;
    }
    /**
     * <pre>
     * The starting point for fetching records.
     * </pre>
     *
     * <code>int32 offset = 1;</code>
     * @param value The offset to set.
     * @return This builder for chaining.
     */
    public Builder setOffset(int value) {
      
      offset_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The starting point for fetching records.
     * </pre>
     *
     * <code>int32 offset = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearOffset() {
      
      offset_ = 0;
      onChanged();
      return this;
    }

    private int limit_ ;
    /**
     * <pre>
     * The number of records to fetch from the starting point.
     * </pre>
     *
     * <code>int32 limit = 2;</code>
     * @return The limit.
     */
    @java.lang.Override
    public int getLimit() {
      return limit_;
    }
    /**
     * <pre>
     * The number of records to fetch from the starting point.
     * </pre>
     *
     * <code>int32 limit = 2;</code>
     * @param value The limit to set.
     * @return This builder for chaining.
     */
    public Builder setLimit(int value) {
      
      limit_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The number of records to fetch from the starting point.
     * </pre>
     *
     * <code>int32 limit = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearLimit() {
      
      limit_ = 0;
      onChanged();
      return this;
    }

    private int pageNumber_ ;
    /**
     * <pre>
     * The current page number based on the current pagination settings.
     * </pre>
     *
     * <code>int32 pageNumber = 3;</code>
     * @return The pageNumber.
     */
    @java.lang.Override
    public int getPageNumber() {
      return pageNumber_;
    }
    /**
     * <pre>
     * The current page number based on the current pagination settings.
     * </pre>
     *
     * <code>int32 pageNumber = 3;</code>
     * @param value The pageNumber to set.
     * @return This builder for chaining.
     */
    public Builder setPageNumber(int value) {
      
      pageNumber_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The current page number based on the current pagination settings.
     * </pre>
     *
     * <code>int32 pageNumber = 3;</code>
     * @return This builder for chaining.
     */
    public Builder clearPageNumber() {
      
      pageNumber_ = 0;
      onChanged();
      return this;
    }

    private int lastPageNumber_ ;
    /**
     * <pre>
     * The last page number based on the current pagination settings.
     * </pre>
     *
     * <code>int32 lastPageNumber = 4;</code>
     * @return The lastPageNumber.
     */
    @java.lang.Override
    public int getLastPageNumber() {
      return lastPageNumber_;
    }
    /**
     * <pre>
     * The last page number based on the current pagination settings.
     * </pre>
     *
     * <code>int32 lastPageNumber = 4;</code>
     * @param value The lastPageNumber to set.
     * @return This builder for chaining.
     */
    public Builder setLastPageNumber(int value) {
      
      lastPageNumber_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The last page number based on the current pagination settings.
     * </pre>
     *
     * <code>int32 lastPageNumber = 4;</code>
     * @return This builder for chaining.
     */
    public Builder clearLastPageNumber() {
      
      lastPageNumber_ = 0;
      onChanged();
      return this;
    }

    private int totalRecordCount_ ;
    /**
     * <pre>
     * The total number of records available.
     * </pre>
     *
     * <code>int32 totalRecordCount = 5;</code>
     * @return The totalRecordCount.
     */
    @java.lang.Override
    public int getTotalRecordCount() {
      return totalRecordCount_;
    }
    /**
     * <pre>
     * The total number of records available.
     * </pre>
     *
     * <code>int32 totalRecordCount = 5;</code>
     * @param value The totalRecordCount to set.
     * @return This builder for chaining.
     */
    public Builder setTotalRecordCount(int value) {
      
      totalRecordCount_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The total number of records available.
     * </pre>
     *
     * <code>int32 totalRecordCount = 5;</code>
     * @return This builder for chaining.
     */
    public Builder clearTotalRecordCount() {
      
      totalRecordCount_ = 0;
      onChanged();
      return this;
    }
    @java.lang.Override
    public final Builder setUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.setUnknownFields(unknownFields);
    }

    @java.lang.Override
    public final Builder mergeUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.mergeUnknownFields(unknownFields);
    }


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit)
  private static final io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcOffsetAndLimit>
      PARSER = new com.google.protobuf.AbstractParser<GrpcOffsetAndLimit>() {
    @java.lang.Override
    public GrpcOffsetAndLimit parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcOffsetAndLimit(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcOffsetAndLimit> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcOffsetAndLimit> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcOffsetAndLimit getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

