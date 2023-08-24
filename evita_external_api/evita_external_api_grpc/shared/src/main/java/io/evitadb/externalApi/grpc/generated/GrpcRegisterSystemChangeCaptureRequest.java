// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcEvitaAPI.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest}
 */
public final class GrpcRegisterSystemChangeCaptureRequest extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest)
    GrpcRegisterSystemChangeCaptureRequestOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcRegisterSystemChangeCaptureRequest.newBuilder() to construct.
  private GrpcRegisterSystemChangeCaptureRequest(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcRegisterSystemChangeCaptureRequest() {
    content_ = 0;
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcRegisterSystemChangeCaptureRequest();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcRegisterSystemChangeCaptureRequest(
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
            int rawValue = input.readEnum();

            content_ = rawValue;
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
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRegisterSystemChangeCaptureRequest_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRegisterSystemChangeCaptureRequest_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest.class, io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest.Builder.class);
  }

  public static final int CONTENT_FIELD_NUMBER = 1;
  private int content_;
  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCaptureContent content = 1;</code>
   * @return The enum numeric value on the wire for content.
   */
  @java.lang.Override public int getContentValue() {
    return content_;
  }
  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCaptureContent content = 1;</code>
   * @return The content.
   */
  @java.lang.Override public io.evitadb.externalApi.grpc.generated.GrpcCaptureContent getContent() {
    @SuppressWarnings("deprecation")
    io.evitadb.externalApi.grpc.generated.GrpcCaptureContent result = io.evitadb.externalApi.grpc.generated.GrpcCaptureContent.valueOf(content_);
    return result == null ? io.evitadb.externalApi.grpc.generated.GrpcCaptureContent.UNRECOGNIZED : result;
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
    if (content_ != io.evitadb.externalApi.grpc.generated.GrpcCaptureContent.HEADER.getNumber()) {
      output.writeEnum(1, content_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (content_ != io.evitadb.externalApi.grpc.generated.GrpcCaptureContent.HEADER.getNumber()) {
      size += com.google.protobuf.CodedOutputStream
        .computeEnumSize(1, content_);
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest other = (io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest) obj;

    if (content_ != other.content_) return false;
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
    hash = (37 * hash) + CONTENT_FIELD_NUMBER;
    hash = (53 * hash) + content_;
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest prototype) {
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
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest)
      io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequestOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRegisterSystemChangeCaptureRequest_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRegisterSystemChangeCaptureRequest_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest.class, io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest.newBuilder()
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
      content_ = 0;

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRegisterSystemChangeCaptureRequest_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest build() {
      io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest result = new io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest(this);
      result.content_ = content_;
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest.getDefaultInstance()) return this;
      if (other.content_ != 0) {
        setContentValue(other.getContentValue());
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
      io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private int content_ = 0;
    /**
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcCaptureContent content = 1;</code>
     * @return The enum numeric value on the wire for content.
     */
    @java.lang.Override public int getContentValue() {
      return content_;
    }
    /**
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcCaptureContent content = 1;</code>
     * @param value The enum numeric value on the wire for content to set.
     * @return This builder for chaining.
     */
    public Builder setContentValue(int value) {
      
      content_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcCaptureContent content = 1;</code>
     * @return The content.
     */
    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcCaptureContent getContent() {
      @SuppressWarnings("deprecation")
      io.evitadb.externalApi.grpc.generated.GrpcCaptureContent result = io.evitadb.externalApi.grpc.generated.GrpcCaptureContent.valueOf(content_);
      return result == null ? io.evitadb.externalApi.grpc.generated.GrpcCaptureContent.UNRECOGNIZED : result;
    }
    /**
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcCaptureContent content = 1;</code>
     * @param value The content to set.
     * @return This builder for chaining.
     */
    public Builder setContent(io.evitadb.externalApi.grpc.generated.GrpcCaptureContent value) {
      if (value == null) {
        throw new NullPointerException();
      }
      
      content_ = value.getNumber();
      onChanged();
      return this;
    }
    /**
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcCaptureContent content = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearContent() {
      
      content_ = 0;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest)
  private static final io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcRegisterSystemChangeCaptureRequest>
      PARSER = new com.google.protobuf.AbstractParser<GrpcRegisterSystemChangeCaptureRequest>() {
    @java.lang.Override
    public GrpcRegisterSystemChangeCaptureRequest parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcRegisterSystemChangeCaptureRequest(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcRegisterSystemChangeCaptureRequest> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcRegisterSystemChangeCaptureRequest> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

