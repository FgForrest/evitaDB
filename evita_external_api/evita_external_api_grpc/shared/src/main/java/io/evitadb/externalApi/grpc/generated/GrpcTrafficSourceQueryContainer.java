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
// source: GrpcTrafficRecording.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * This container holds information about the source query.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer}
 */
public final class GrpcTrafficSourceQueryContainer extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer)
    GrpcTrafficSourceQueryContainerOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcTrafficSourceQueryContainer.newBuilder() to construct.
  private GrpcTrafficSourceQueryContainer(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcTrafficSourceQueryContainer() {
    sourceQuery_ = "";
    queryType_ = "";
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcTrafficSourceQueryContainer();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcTrafficSourceQueryContainer(
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
          case 10: {
            io.evitadb.externalApi.grpc.generated.GrpcUuid.Builder subBuilder = null;
            if (sourceQueryId_ != null) {
              subBuilder = sourceQueryId_.toBuilder();
            }
            sourceQueryId_ = input.readMessage(io.evitadb.externalApi.grpc.generated.GrpcUuid.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(sourceQueryId_);
              sourceQueryId_ = subBuilder.buildPartial();
            }

            break;
          }
          case 18: {
            java.lang.String s = input.readStringRequireUtf8();

            sourceQuery_ = s;
            break;
          }
          case 26: {
            java.lang.String s = input.readStringRequireUtf8();

            queryType_ = s;
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
    return io.evitadb.externalApi.grpc.generated.GrpcTrafficRecording.internal_static_io_evitadb_externalApi_grpc_generated_GrpcTrafficSourceQueryContainer_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcTrafficRecording.internal_static_io_evitadb_externalApi_grpc_generated_GrpcTrafficSourceQueryContainer_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer.class, io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer.Builder.class);
  }

  public static final int SOURCEQUERYID_FIELD_NUMBER = 1;
  private io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId_;
  /**
   * <pre>
   * The unique identifier of the source query
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
   * @return Whether the sourceQueryId field is set.
   */
  @java.lang.Override
  public boolean hasSourceQueryId() {
    return sourceQueryId_ != null;
  }
  /**
   * <pre>
   * The unique identifier of the source query
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
   * @return The sourceQueryId.
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcUuid getSourceQueryId() {
    return sourceQueryId_ == null ? io.evitadb.externalApi.grpc.generated.GrpcUuid.getDefaultInstance() : sourceQueryId_;
  }
  /**
   * <pre>
   * The unique identifier of the source query
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder getSourceQueryIdOrBuilder() {
    return getSourceQueryId();
  }

  public static final int SOURCEQUERY_FIELD_NUMBER = 2;
  private volatile java.lang.Object sourceQuery_;
  /**
   * <pre>
   * unparsed, raw source query in particular format
   * </pre>
   *
   * <code>string sourceQuery = 2;</code>
   * @return The sourceQuery.
   */
  @java.lang.Override
  public java.lang.String getSourceQuery() {
    java.lang.Object ref = sourceQuery_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs =
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      sourceQuery_ = s;
      return s;
    }
  }
  /**
   * <pre>
   * unparsed, raw source query in particular format
   * </pre>
   *
   * <code>string sourceQuery = 2;</code>
   * @return The bytes for sourceQuery.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getSourceQueryBytes() {
    java.lang.Object ref = sourceQuery_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b =
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      sourceQuery_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  public static final int QUERYTYPE_FIELD_NUMBER = 3;
  private volatile java.lang.Object queryType_;
  /**
   * <pre>
   * type of the query (e.g. GraphQL, REST, etc.)
   * </pre>
   *
   * <code>string queryType = 3;</code>
   * @return The queryType.
   */
  @java.lang.Override
  public java.lang.String getQueryType() {
    java.lang.Object ref = queryType_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs =
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      queryType_ = s;
      return s;
    }
  }
  /**
   * <pre>
   * type of the query (e.g. GraphQL, REST, etc.)
   * </pre>
   *
   * <code>string queryType = 3;</code>
   * @return The bytes for queryType.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getQueryTypeBytes() {
    java.lang.Object ref = queryType_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b =
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      queryType_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
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
    if (sourceQueryId_ != null) {
      output.writeMessage(1, getSourceQueryId());
    }
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(sourceQuery_)) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 2, sourceQuery_);
    }
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(queryType_)) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 3, queryType_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (sourceQueryId_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(1, getSourceQueryId());
    }
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(sourceQuery_)) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(2, sourceQuery_);
    }
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(queryType_)) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(3, queryType_);
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer other = (io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer) obj;

    if (hasSourceQueryId() != other.hasSourceQueryId()) return false;
    if (hasSourceQueryId()) {
      if (!getSourceQueryId()
          .equals(other.getSourceQueryId())) return false;
    }
    if (!getSourceQuery()
        .equals(other.getSourceQuery())) return false;
    if (!getQueryType()
        .equals(other.getQueryType())) return false;
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
    if (hasSourceQueryId()) {
      hash = (37 * hash) + SOURCEQUERYID_FIELD_NUMBER;
      hash = (53 * hash) + getSourceQueryId().hashCode();
    }
    hash = (37 * hash) + SOURCEQUERY_FIELD_NUMBER;
    hash = (53 * hash) + getSourceQuery().hashCode();
    hash = (37 * hash) + QUERYTYPE_FIELD_NUMBER;
    hash = (53 * hash) + getQueryType().hashCode();
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer prototype) {
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
   * This container holds information about the source query.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer)
      io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainerOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcTrafficRecording.internal_static_io_evitadb_externalApi_grpc_generated_GrpcTrafficSourceQueryContainer_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcTrafficRecording.internal_static_io_evitadb_externalApi_grpc_generated_GrpcTrafficSourceQueryContainer_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer.class, io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer.newBuilder()
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
      if (sourceQueryIdBuilder_ == null) {
        sourceQueryId_ = null;
      } else {
        sourceQueryId_ = null;
        sourceQueryIdBuilder_ = null;
      }
      sourceQuery_ = "";

      queryType_ = "";

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcTrafficRecording.internal_static_io_evitadb_externalApi_grpc_generated_GrpcTrafficSourceQueryContainer_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer build() {
      io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer result = new io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer(this);
      if (sourceQueryIdBuilder_ == null) {
        result.sourceQueryId_ = sourceQueryId_;
      } else {
        result.sourceQueryId_ = sourceQueryIdBuilder_.build();
      }
      result.sourceQuery_ = sourceQuery_;
      result.queryType_ = queryType_;
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer.getDefaultInstance()) return this;
      if (other.hasSourceQueryId()) {
        mergeSourceQueryId(other.getSourceQueryId());
      }
      if (!other.getSourceQuery().isEmpty()) {
        sourceQuery_ = other.sourceQuery_;
        onChanged();
      }
      if (!other.getQueryType().isEmpty()) {
        queryType_ = other.queryType_;
        onChanged();
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
      io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId_;
    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcUuid, io.evitadb.externalApi.grpc.generated.GrpcUuid.Builder, io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder> sourceQueryIdBuilder_;
    /**
     * <pre>
     * The unique identifier of the source query
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     * @return Whether the sourceQueryId field is set.
     */
    public boolean hasSourceQueryId() {
      return sourceQueryIdBuilder_ != null || sourceQueryId_ != null;
    }
    /**
     * <pre>
     * The unique identifier of the source query
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     * @return The sourceQueryId.
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUuid getSourceQueryId() {
      if (sourceQueryIdBuilder_ == null) {
        return sourceQueryId_ == null ? io.evitadb.externalApi.grpc.generated.GrpcUuid.getDefaultInstance() : sourceQueryId_;
      } else {
        return sourceQueryIdBuilder_.getMessage();
      }
    }
    /**
     * <pre>
     * The unique identifier of the source query
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     */
    public Builder setSourceQueryId(io.evitadb.externalApi.grpc.generated.GrpcUuid value) {
      if (sourceQueryIdBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        sourceQueryId_ = value;
        onChanged();
      } else {
        sourceQueryIdBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <pre>
     * The unique identifier of the source query
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     */
    public Builder setSourceQueryId(
        io.evitadb.externalApi.grpc.generated.GrpcUuid.Builder builderForValue) {
      if (sourceQueryIdBuilder_ == null) {
        sourceQueryId_ = builderForValue.build();
        onChanged();
      } else {
        sourceQueryIdBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <pre>
     * The unique identifier of the source query
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     */
    public Builder mergeSourceQueryId(io.evitadb.externalApi.grpc.generated.GrpcUuid value) {
      if (sourceQueryIdBuilder_ == null) {
        if (sourceQueryId_ != null) {
          sourceQueryId_ =
            io.evitadb.externalApi.grpc.generated.GrpcUuid.newBuilder(sourceQueryId_).mergeFrom(value).buildPartial();
        } else {
          sourceQueryId_ = value;
        }
        onChanged();
      } else {
        sourceQueryIdBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <pre>
     * The unique identifier of the source query
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     */
    public Builder clearSourceQueryId() {
      if (sourceQueryIdBuilder_ == null) {
        sourceQueryId_ = null;
        onChanged();
      } else {
        sourceQueryId_ = null;
        sourceQueryIdBuilder_ = null;
      }

      return this;
    }
    /**
     * <pre>
     * The unique identifier of the source query
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUuid.Builder getSourceQueryIdBuilder() {

      onChanged();
      return getSourceQueryIdFieldBuilder().getBuilder();
    }
    /**
     * <pre>
     * The unique identifier of the source query
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder getSourceQueryIdOrBuilder() {
      if (sourceQueryIdBuilder_ != null) {
        return sourceQueryIdBuilder_.getMessageOrBuilder();
      } else {
        return sourceQueryId_ == null ?
            io.evitadb.externalApi.grpc.generated.GrpcUuid.getDefaultInstance() : sourceQueryId_;
      }
    }
    /**
     * <pre>
     * The unique identifier of the source query
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcUuid, io.evitadb.externalApi.grpc.generated.GrpcUuid.Builder, io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder>
        getSourceQueryIdFieldBuilder() {
      if (sourceQueryIdBuilder_ == null) {
        sourceQueryIdBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            io.evitadb.externalApi.grpc.generated.GrpcUuid, io.evitadb.externalApi.grpc.generated.GrpcUuid.Builder, io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder>(
                getSourceQueryId(),
                getParentForChildren(),
                isClean());
        sourceQueryId_ = null;
      }
      return sourceQueryIdBuilder_;
    }

    private java.lang.Object sourceQuery_ = "";
    /**
     * <pre>
     * unparsed, raw source query in particular format
     * </pre>
     *
     * <code>string sourceQuery = 2;</code>
     * @return The sourceQuery.
     */
    public java.lang.String getSourceQuery() {
      java.lang.Object ref = sourceQuery_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        sourceQuery_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <pre>
     * unparsed, raw source query in particular format
     * </pre>
     *
     * <code>string sourceQuery = 2;</code>
     * @return The bytes for sourceQuery.
     */
    public com.google.protobuf.ByteString
        getSourceQueryBytes() {
      java.lang.Object ref = sourceQuery_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b =
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        sourceQuery_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <pre>
     * unparsed, raw source query in particular format
     * </pre>
     *
     * <code>string sourceQuery = 2;</code>
     * @param value The sourceQuery to set.
     * @return This builder for chaining.
     */
    public Builder setSourceQuery(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }

      sourceQuery_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * unparsed, raw source query in particular format
     * </pre>
     *
     * <code>string sourceQuery = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearSourceQuery() {

      sourceQuery_ = getDefaultInstance().getSourceQuery();
      onChanged();
      return this;
    }
    /**
     * <pre>
     * unparsed, raw source query in particular format
     * </pre>
     *
     * <code>string sourceQuery = 2;</code>
     * @param value The bytes for sourceQuery to set.
     * @return This builder for chaining.
     */
    public Builder setSourceQueryBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);

      sourceQuery_ = value;
      onChanged();
      return this;
    }

    private java.lang.Object queryType_ = "";
    /**
     * <pre>
     * type of the query (e.g. GraphQL, REST, etc.)
     * </pre>
     *
     * <code>string queryType = 3;</code>
     * @return The queryType.
     */
    public java.lang.String getQueryType() {
      java.lang.Object ref = queryType_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        queryType_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <pre>
     * type of the query (e.g. GraphQL, REST, etc.)
     * </pre>
     *
     * <code>string queryType = 3;</code>
     * @return The bytes for queryType.
     */
    public com.google.protobuf.ByteString
        getQueryTypeBytes() {
      java.lang.Object ref = queryType_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b =
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        queryType_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <pre>
     * type of the query (e.g. GraphQL, REST, etc.)
     * </pre>
     *
     * <code>string queryType = 3;</code>
     * @param value The queryType to set.
     * @return This builder for chaining.
     */
    public Builder setQueryType(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }

      queryType_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * type of the query (e.g. GraphQL, REST, etc.)
     * </pre>
     *
     * <code>string queryType = 3;</code>
     * @return This builder for chaining.
     */
    public Builder clearQueryType() {

      queryType_ = getDefaultInstance().getQueryType();
      onChanged();
      return this;
    }
    /**
     * <pre>
     * type of the query (e.g. GraphQL, REST, etc.)
     * </pre>
     *
     * <code>string queryType = 3;</code>
     * @param value The bytes for queryType to set.
     * @return This builder for chaining.
     */
    public Builder setQueryTypeBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);

      queryType_ = value;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer)
  private static final io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcTrafficSourceQueryContainer>
      PARSER = new com.google.protobuf.AbstractParser<GrpcTrafficSourceQueryContainer>() {
    @java.lang.Override
    public GrpcTrafficSourceQueryContainer parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcTrafficSourceQueryContainer(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcTrafficSourceQueryContainer> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcTrafficSourceQueryContainer> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryContainer getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

