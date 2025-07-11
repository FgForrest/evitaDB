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
// source: GrpcEvitaSessionAPI.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse}
 */
public final class GrpcGoLiveAndCloseWithProgressResponse extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse)
    GrpcGoLiveAndCloseWithProgressResponseOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcGoLiveAndCloseWithProgressResponse.newBuilder() to construct.
  private GrpcGoLiveAndCloseWithProgressResponse(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcGoLiveAndCloseWithProgressResponse() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcGoLiveAndCloseWithProgressResponse();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcGoLiveAndCloseWithProgressResponse(
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

            catalogVersion_ = input.readInt64();
            break;
          }
          case 16: {

            catalogSchemaVersion_ = input.readInt32();
            break;
          }
          case 24: {

            progressInPercent_ = input.readInt32();
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
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcGoLiveAndCloseWithProgressResponse_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcGoLiveAndCloseWithProgressResponse_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse.class, io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse.Builder.class);
  }

  public static final int CATALOGVERSION_FIELD_NUMBER = 1;
  private long catalogVersion_;
  /**
   * <pre>
   * Contains next catalog version
   * </pre>
   *
   * <code>int64 catalogVersion = 1;</code>
   * @return The catalogVersion.
   */
  @java.lang.Override
  public long getCatalogVersion() {
    return catalogVersion_;
  }

  public static final int CATALOGSCHEMAVERSION_FIELD_NUMBER = 2;
  private int catalogSchemaVersion_;
  /**
   * <pre>
   * Contains the version of the catalog schema that will be valid at the moment of closing the session.
   * If session relates to a writable transaction, this schema version becomes valid at the moment the next catalog
   * version (i.e. the one that is returned in the response) becomes visible.
   * </pre>
   *
   * <code>int32 catalogSchemaVersion = 2;</code>
   * @return The catalogSchemaVersion.
   */
  @java.lang.Override
  public int getCatalogSchemaVersion() {
    return catalogSchemaVersion_;
  }

  public static final int PROGRESSINPERCENT_FIELD_NUMBER = 3;
  private int progressInPercent_;
  /**
   * <pre>
   * The progress of the go live operation in percents.
   * </pre>
   *
   * <code>int32 progressInPercent = 3;</code>
   * @return The progressInPercent.
   */
  @java.lang.Override
  public int getProgressInPercent() {
    return progressInPercent_;
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
    if (catalogVersion_ != 0L) {
      output.writeInt64(1, catalogVersion_);
    }
    if (catalogSchemaVersion_ != 0) {
      output.writeInt32(2, catalogSchemaVersion_);
    }
    if (progressInPercent_ != 0) {
      output.writeInt32(3, progressInPercent_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (catalogVersion_ != 0L) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt64Size(1, catalogVersion_);
    }
    if (catalogSchemaVersion_ != 0) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt32Size(2, catalogSchemaVersion_);
    }
    if (progressInPercent_ != 0) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt32Size(3, progressInPercent_);
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse other = (io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse) obj;

    if (getCatalogVersion()
        != other.getCatalogVersion()) return false;
    if (getCatalogSchemaVersion()
        != other.getCatalogSchemaVersion()) return false;
    if (getProgressInPercent()
        != other.getProgressInPercent()) return false;
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
    hash = (37 * hash) + CATALOGVERSION_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashLong(
        getCatalogVersion());
    hash = (37 * hash) + CATALOGSCHEMAVERSION_FIELD_NUMBER;
    hash = (53 * hash) + getCatalogSchemaVersion();
    hash = (37 * hash) + PROGRESSINPERCENT_FIELD_NUMBER;
    hash = (53 * hash) + getProgressInPercent();
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse prototype) {
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
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse)
      io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponseOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcGoLiveAndCloseWithProgressResponse_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcGoLiveAndCloseWithProgressResponse_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse.class, io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse.newBuilder()
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
      catalogVersion_ = 0L;

      catalogSchemaVersion_ = 0;

      progressInPercent_ = 0;

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcGoLiveAndCloseWithProgressResponse_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse build() {
      io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse result = new io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse(this);
      result.catalogVersion_ = catalogVersion_;
      result.catalogSchemaVersion_ = catalogSchemaVersion_;
      result.progressInPercent_ = progressInPercent_;
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse.getDefaultInstance()) return this;
      if (other.getCatalogVersion() != 0L) {
        setCatalogVersion(other.getCatalogVersion());
      }
      if (other.getCatalogSchemaVersion() != 0) {
        setCatalogSchemaVersion(other.getCatalogSchemaVersion());
      }
      if (other.getProgressInPercent() != 0) {
        setProgressInPercent(other.getProgressInPercent());
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
      io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private long catalogVersion_ ;
    /**
     * <pre>
     * Contains next catalog version
     * </pre>
     *
     * <code>int64 catalogVersion = 1;</code>
     * @return The catalogVersion.
     */
    @java.lang.Override
    public long getCatalogVersion() {
      return catalogVersion_;
    }
    /**
     * <pre>
     * Contains next catalog version
     * </pre>
     *
     * <code>int64 catalogVersion = 1;</code>
     * @param value The catalogVersion to set.
     * @return This builder for chaining.
     */
    public Builder setCatalogVersion(long value) {
      
      catalogVersion_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Contains next catalog version
     * </pre>
     *
     * <code>int64 catalogVersion = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearCatalogVersion() {
      
      catalogVersion_ = 0L;
      onChanged();
      return this;
    }

    private int catalogSchemaVersion_ ;
    /**
     * <pre>
     * Contains the version of the catalog schema that will be valid at the moment of closing the session.
     * If session relates to a writable transaction, this schema version becomes valid at the moment the next catalog
     * version (i.e. the one that is returned in the response) becomes visible.
     * </pre>
     *
     * <code>int32 catalogSchemaVersion = 2;</code>
     * @return The catalogSchemaVersion.
     */
    @java.lang.Override
    public int getCatalogSchemaVersion() {
      return catalogSchemaVersion_;
    }
    /**
     * <pre>
     * Contains the version of the catalog schema that will be valid at the moment of closing the session.
     * If session relates to a writable transaction, this schema version becomes valid at the moment the next catalog
     * version (i.e. the one that is returned in the response) becomes visible.
     * </pre>
     *
     * <code>int32 catalogSchemaVersion = 2;</code>
     * @param value The catalogSchemaVersion to set.
     * @return This builder for chaining.
     */
    public Builder setCatalogSchemaVersion(int value) {
      
      catalogSchemaVersion_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Contains the version of the catalog schema that will be valid at the moment of closing the session.
     * If session relates to a writable transaction, this schema version becomes valid at the moment the next catalog
     * version (i.e. the one that is returned in the response) becomes visible.
     * </pre>
     *
     * <code>int32 catalogSchemaVersion = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearCatalogSchemaVersion() {
      
      catalogSchemaVersion_ = 0;
      onChanged();
      return this;
    }

    private int progressInPercent_ ;
    /**
     * <pre>
     * The progress of the go live operation in percents.
     * </pre>
     *
     * <code>int32 progressInPercent = 3;</code>
     * @return The progressInPercent.
     */
    @java.lang.Override
    public int getProgressInPercent() {
      return progressInPercent_;
    }
    /**
     * <pre>
     * The progress of the go live operation in percents.
     * </pre>
     *
     * <code>int32 progressInPercent = 3;</code>
     * @param value The progressInPercent to set.
     * @return This builder for chaining.
     */
    public Builder setProgressInPercent(int value) {
      
      progressInPercent_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The progress of the go live operation in percents.
     * </pre>
     *
     * <code>int32 progressInPercent = 3;</code>
     * @return This builder for chaining.
     */
    public Builder clearProgressInPercent() {
      
      progressInPercent_ = 0;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse)
  private static final io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcGoLiveAndCloseWithProgressResponse>
      PARSER = new com.google.protobuf.AbstractParser<GrpcGoLiveAndCloseWithProgressResponse>() {
    @java.lang.Override
    public GrpcGoLiveAndCloseWithProgressResponse parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcGoLiveAndCloseWithProgressResponse(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcGoLiveAndCloseWithProgressResponse> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcGoLiveAndCloseWithProgressResponse> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcGoLiveAndCloseWithProgressResponse getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

