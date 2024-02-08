package com.snowflake.kafka.connector.records;

import com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig;
import com.snowflake.kafka.connector.internal.SnowflakeErrors;
import com.snowflake.kafka.connector.internal.SnowflakeKafkaConnectorException;
import com.snowflake.kafka.connector.internal.TestUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.core.JsonProcessingException;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.core.type.TypeReference;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.JsonNode;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.Test;

import static com.snowflake.kafka.connector.Utils.*;

public class RecordContentTest {
  private ObjectMapper mapper = new ObjectMapper();
  private static String topic = "test";
  private static int partition = 0;

  @Test
  public void test() throws IOException {
    JsonNode data = mapper.readTree("{\"name\":123}");
    // json
    SnowflakeRecordContent content = new SnowflakeRecordContent(data);
    assert !content.isBroken();
    assert content.getSchemaID() == SnowflakeRecordContent.NON_AVRO_SCHEMA;
    assert content.getData().length == 1;
    assert content.getData()[0].asText().equals(data.asText());
    assert TestUtils.assertError(SnowflakeErrors.ERROR_5011, content::getBrokenData);

    // avro
    int schemaID = 123;
    content = new SnowflakeRecordContent(data, schemaID);
    assert !content.isBroken();
    assert content.getSchemaID() == schemaID;
    assert content.getData().length == 1;
    assert content.getData()[0].asText().equals(data.asText());
    assert TestUtils.assertError(SnowflakeErrors.ERROR_5011, content::getBrokenData);

    // avro without schema registry
    JsonNode[] data1 = new JsonNode[1];
    data1[0] = data;
    content = new SnowflakeRecordContent(data1);
    assert !content.isBroken();
    assert content.getSchemaID() == SnowflakeRecordContent.NON_AVRO_SCHEMA;
    assert content.getData().length == 1;
    assert content.getData()[0].asText().equals(data.asText());
    assert TestUtils.assertError(SnowflakeErrors.ERROR_5011, content::getBrokenData);

    // broken record
    byte[] brokenData = "123".getBytes(StandardCharsets.UTF_8);
    content = new SnowflakeRecordContent(brokenData);
    assert content.isBroken();
    assert content.getSchemaID() == SnowflakeRecordContent.NON_AVRO_SCHEMA;
    assert TestUtils.assertError(SnowflakeErrors.ERROR_5012, content::getData);
    assert new String(content.getBrokenData(), StandardCharsets.UTF_8).equals("123");

    // null value
    content = new SnowflakeRecordContent();
    assert content.getData().length == 1;
    assert content.getData()[0].size() == 0;
    assert content.getData()[0].toString().equals("{}");

    // AVRO struct object
    SchemaBuilder builder =
        SchemaBuilder.struct()
            .field("int8", SchemaBuilder.int8().defaultValue((byte) 2).doc("int8 field").build())
            .field("int16", Schema.INT16_SCHEMA)
            .field("int32", Schema.INT32_SCHEMA)
            .field("int64", Schema.INT64_SCHEMA)
            .field("float32", Schema.FLOAT32_SCHEMA)
            .field("float64", Schema.FLOAT64_SCHEMA)
            .field("boolean", Schema.BOOLEAN_SCHEMA)
            .field("string", Schema.STRING_SCHEMA)
            .field("bytes", Schema.BYTES_SCHEMA)
            .field("array", SchemaBuilder.array(Schema.STRING_SCHEMA).build())
            .field("map", SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.INT32_SCHEMA).build())
            .field(
                "mapNonStringKeys",
                SchemaBuilder.map(Schema.INT32_SCHEMA, Schema.INT32_SCHEMA).build());
    Schema schema = builder.build();
    Struct original =
        new Struct(schema)
            .put("int8", (byte) 12)
            .put("int16", (short) 12)
            .put("int32", 12)
            .put("int64", 12L)
            .put("float32", 12.2f)
            .put("float64", 12.2)
            .put("boolean", true)
            .put("string", "foo")
            .put("bytes", ByteBuffer.wrap("foo".getBytes()))
            .put("array", Arrays.asList("a", "b", "c"))
            .put("map", Collections.singletonMap("field", 1))
            .put("mapNonStringKeys", Collections.singletonMap(1, 1));

    content = new SnowflakeRecordContent(schema, original, false);
    assert content
        .getData()[0]
        .toString()
        .equals(
            "{\"int8\":12,\"int16\":12,\"int32\":12,\"int64\":12,\"float32\":12.2,\"float64\":12.2,\"boolean\":true,\"string\":\"foo\",\"bytes\":\"Zm9v\",\"array\":[\"a\",\"b\",\"c\"],\"map\":{\"field\":1},\"mapNonStringKeys\":[[1,1]]}");

    // JSON map object
    JsonNode jsonObject =
        mapper.readTree(
            "{\"int8\":12,\"int16\":12,\"int32\":12,\"int64\":12,\"float32\":12.2,\"float64\":12.2,\"boolean\":true,\"string\":\"foo\",\"bytes\":\"Zm9v\",\"array\":[\"a\",\"b\",\"c\"],\"map\":{\"field\":1},\"mapNonStringKeys\":[[1,1]]}");
    Map<String, Object> jsonMap =
        mapper.convertValue(jsonObject, new TypeReference<Map<String, Object>>() {});
    content = new SnowflakeRecordContent(null, jsonMap, false);
    assert content
        .getData()[0]
        .toString()
        .equals(
            "{\"int8\":12,\"int16\":12,\"int32\":12,\"int64\":12,\"float32\":12.2,\"float64\":12.2,\"boolean\":true,\"string\":\"foo\",\"bytes\":\"Zm9v\",\"array\":[\"a\",\"b\",\"c\"],\"map\":{\"field\":1},\"mapNonStringKeys\":[[1,1]]}");
  }

  @Test(expected = SnowflakeKafkaConnectorException.class)
  public void testEmptyValueDisabledTombstone() {
    RecordService service = new RecordService();
    service.setBehaviorOnNullValues(SnowflakeSinkConnectorConfig.BehaviorOnNullValues.IGNORE);

    SinkRecord record =
        new SinkRecord(topic, partition, null, null, Schema.STRING_SCHEMA, null, partition);
    service.getProcessedRecordForSnowpipe(record);
  }

  @Test(expected = SnowflakeKafkaConnectorException.class)
  public void testEmptyValueSchemaDisabledTombstone() throws IOException {
    JsonNode data = mapper.readTree("{\"name\":123}");
    SnowflakeRecordContent content = new SnowflakeRecordContent(data);
    RecordService service = new RecordService();
    service.setBehaviorOnNullValues(SnowflakeSinkConnectorConfig.BehaviorOnNullValues.IGNORE);

    SinkRecord record = new SinkRecord(topic, partition, null, null, null, content, partition);
    service.getProcessedRecordForSnowpipe(record);
  }

  @Test(expected = SnowflakeKafkaConnectorException.class)
  public void testWrongValueSchema() throws IOException {
    JsonNode data = mapper.readTree("{\"name\":123}");
    SnowflakeRecordContent content = new SnowflakeRecordContent(data);
    RecordService service = new RecordService();

    SinkRecord record =
        new SinkRecord(
            topic,
            partition,
            null,
            null,
            SchemaBuilder.string().name("aName").build(),
            content,
            partition);
    // TODO: SNOW-215915 Fix this after stability push, if schema does not have a name
    // There is OOM error in this test.
    service.getProcessedRecordForSnowpipe(record);
  }

  @Test(expected = SnowflakeKafkaConnectorException.class)
  public void testWrongValueType() {
    RecordService service = new RecordService();

    SinkRecord record =
        new SinkRecord(
            topic, partition, null, null, new SnowflakeJsonSchema(), "string", partition);
    service.getProcessedRecordForSnowpipe(record);
  }

  @Test(expected = SnowflakeKafkaConnectorException.class)
  public void testWrongKeySchema() throws IOException {
    JsonNode data = mapper.readTree("{\"name\":123}");
    SnowflakeRecordContent content = new SnowflakeRecordContent(data);
    RecordService service = new RecordService();

    SinkRecord record =
        new SinkRecord(
            topic,
            partition,
            SchemaBuilder.string().name("aName").build(),
            content,
            null,
            null,
            partition);
    service.putKey(record, mapper.createObjectNode());
  }

  @Test(expected = SnowflakeKafkaConnectorException.class)
  public void testWrongKeyType() {
    RecordService service = new RecordService();

    SinkRecord record =
        new SinkRecord(
            topic, partition, new SnowflakeJsonSchema(), "string", null, null, partition);
    service.putKey(record, mapper.createObjectNode());
  }

  @Test(expected = SnowflakeKafkaConnectorException.class)
  public void testConvertToJsonEmptyValue() {
    Schema schema = SchemaBuilder.int32().optional().defaultValue(123).build();
    assert RecordService.convertToJson(schema, null, false).toString().equals("123");

    schema = SchemaBuilder.int32().build();
    RecordService.convertToJson(schema, null, false);
  }

  @Test(expected = SnowflakeKafkaConnectorException.class)
  public void testConvertToJsonNonOptional() {
    Schema schema = SchemaBuilder.int32().build();
    RecordService.convertToJson(schema, null, false);
  }

  @Test(expected = SnowflakeKafkaConnectorException.class)
  public void testConvertToJsonNoSchemaType() {
    RecordService.convertToJson(null, new SnowflakeJsonSchema(), false);
  }

  @Test
  public void testConvertToJsonReadOnlyByteBuffer() {
    String original = "bytes";
    // Expecting a json string, which has additional quotes.
    String expected = "\"" + Base64.getEncoder().encodeToString(original.getBytes()) + "\"";
    ByteBuffer buffer = ByteBuffer.wrap(original.getBytes()).asReadOnlyBuffer();
    Schema schema = SchemaBuilder.bytes().build();
    assert RecordService.convertToJson(schema, buffer, false).toString().equals(expected);
  }

  @Test
  public void testSchematizationStringField() throws JsonProcessingException {
    RecordService service = new RecordService();
    SnowflakeJsonConverter jsonConverter = new SnowflakeJsonConverter();

    service.setEnableSchematization(true);
    String value = "{\"name\":\"sf\",\"answer\":42,\"TenantId\":101,\"EntityType\":\"testEntity\",\"RowCreated\":\"1692358480222\"}";
    byte[] valueContents = (value).getBytes(StandardCharsets.UTF_8);
    SchemaAndValue sv = jsonConverter.toConnectData(topic, valueContents);

    SinkRecord record =
        new SinkRecord(
            topic, partition, Schema.STRING_SCHEMA, "string", sv.schema(), sv.value(), partition);

    Map<String, Object> got = service.getProcessedRecordForStreamingIngest(record);
    // each field should be dumped into string format
    // json string should not be enclosed in additional brackets
    // a non-double-quoted column name will be transformed into uppercase
    assert got.get("\"NAME\"").equals("sf");
    assert got.get("\"ANSWER\"").equals("42");
  }

  @Test
  public void testSchematizationArrayOfObject() throws JsonProcessingException {
    RecordService service = new RecordService();
    SnowflakeJsonConverter jsonConverter = new SnowflakeJsonConverter();

    service.setEnableSchematization(true);
    String value =
        "{\"players\":[{\"name\":\"John Doe\",\"age\":30},{\"name\":\"Jane Doe\",\"age\":30}],\"TenantId\":101,\"EntityType\":\"testEntity\",\"RowCreated\":\"1692358480222\"}";
    byte[] valueContents = (value).getBytes(StandardCharsets.UTF_8);
    SchemaAndValue sv = jsonConverter.toConnectData(topic, valueContents);

    SinkRecord record =
        new SinkRecord(
            topic, partition, Schema.STRING_SCHEMA, "string", sv.schema(), sv.value(), partition);

    Map<String, Object> got = service.getProcessedRecordForStreamingIngest(record);
    assert got.get("\"PLAYERS\"")
        .equals("[{\"name\":\"John Doe\",\"age\":30},{\"name\":\"Jane Doe\",\"age\":30}]");
  }

  @Test
  public void testColumnNameFormatting() throws JsonProcessingException {
    RecordService service = new RecordService();
    SnowflakeJsonConverter jsonConverter = new SnowflakeJsonConverter();

    service.setEnableSchematization(true);
    String value = "{\"\\\"NaMe\\\"\":\"sf\",\"AnSwEr\":42,\"TenantId\":101,\"EntityType\":\"testEntity\",\"RowCreated\":\"1692358480222\"}";
    byte[] valueContents = (value).getBytes(StandardCharsets.UTF_8);
    SchemaAndValue sv = jsonConverter.toConnectData(topic, valueContents);

    SinkRecord record =
        new SinkRecord(
            topic, partition, Schema.STRING_SCHEMA, "string", sv.schema(), sv.value(), partition);
    Map<String, Object> got = service.getProcessedRecordForStreamingIngest(record);

    assert got.containsKey("\"NaMe\"");
    assert got.containsKey("\"ANSWER\"");
  }

  @Test
  public void testGetProcessedRecord() throws JsonProcessingException {
    SnowflakeJsonConverter jsonConverter = new SnowflakeJsonConverter();
    SchemaAndValue nullSchemaAndValue = jsonConverter.toConnectData(topic, null);
    String keyStr = "string";

    // all null
    this.testGetProcessedRecordRunner(
        new SinkRecord(topic, partition, null, null, null, null, partition), "{}", "");

    // null value
    this.testGetProcessedRecordRunner(
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            keyStr,
            nullSchemaAndValue.schema(),
            null,
            partition),
        "{}",
        keyStr);
    this.testGetProcessedRecordRunner(
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            keyStr,
            null,
            nullSchemaAndValue.value(),
            partition),
        "{}",
        keyStr);

    // null key
    this.testGetProcessedRecordRunner(
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            null,
            nullSchemaAndValue.schema(),
            nullSchemaAndValue.value(),
            partition),
        "{}",
        "");
    try {
      this.testGetProcessedRecordRunner(
          new SinkRecord(
              topic,
              partition,
              null,
              keyStr,
              nullSchemaAndValue.schema(),
              nullSchemaAndValue.value(),
              partition),
          "{}",
          keyStr);
    } catch (SnowflakeKafkaConnectorException ex) {
      assert ex.checkErrorCode(SnowflakeErrors.ERROR_0010);
    }
  }

  private void testGetProcessedRecordRunner(
      SinkRecord record, String expectedRecordContent, String expectedRecordMetadataKey)
      throws JsonProcessingException {
    RecordService service = new RecordService();
    Map<String, Object> recordData = service.getProcessedRecordForStreamingIngest(record);

    assert recordData.size() == 5;
    assert recordData.get("RECORD_CONTENT").equals(expectedRecordContent);
    assert recordData.get("RECORD_METADATA").toString().contains(expectedRecordMetadataKey);
  }

  @Test
  public void testPrepareCustomSFTableRow() throws JsonProcessingException {
    RecordService service = new RecordService();
    SnowflakeJsonConverter jsonConverter = new SnowflakeJsonConverter();

    service.setEnableSchematization(false);
    String value = "{\"\\\"Name\\\"\":\"sf\",\"Answer\":42,\"TenantId\":101,\"EntityType\":\"testEntity\",\"RowCreated\":\"1692358480222\"}";
    byte[] valueContents = (value).getBytes(StandardCharsets.UTF_8);
    SchemaAndValue sv = jsonConverter.toConnectData(topic, valueContents);

    SinkRecord record =
            new SinkRecord(
                    topic, partition, Schema.STRING_SCHEMA, "string", sv.schema(), sv.value(), partition);
    Map<String, Object> got = service.getProcessedRecordForStreamingIngest(record);

    assert got.containsKey(TABLE_COLUMN_TENANT_ID);
    assert got.containsKey(TABLE_COLUMN_ENTITY_TYPE);
    assert got.containsKey(TABLE_COLUMN_ROW_CREATED);
  }

  @Test
  public void testPrepareCustomSFTableRow2() {
    RecordService service = new RecordService();
    SnowflakeJsonConverter jsonConverter = new SnowflakeJsonConverter();

    service.setEnableSchematization(false);
    String value = "{\"TenantId\":\"803\",\"EntityType\":\"Events\",\"RealtimeRequestNumber\":\"61111e1cc-c21a-43bf-9ffc-56068dd32d88\",\"RowCreated\":\"1692358480222\",\"Payload\":[{\"Cookie\":\"HH33ct8wxek00000mp66ct8wxek00-RPAUL\",\"EventTimeStamp\":\"1692358480222\",\"ID\":\"KFK_0_SCN-batch_DEV_DEMO_104_122\",\"IpAddress\":\"4.1.6.11\",\"Referer\":\"https://agilone.github.io/index.html\",\"Type\":\"webpageBrowsed\",\"URL\":\"https://cmsadaptive.microcenter.com/site/brands/hp.aspx\",\"SourceCustomerNumber\":\"SCN-batch_DEV_DEMO_104_122\",\"RealtimeRequestNumber\":\"61111e1cc-c21a-43bf-9ffc-56068dd32d88_1\",\"UserClient\":\"B\",\"Variables\":\"UserAgent=PostmanRuntime%2F7.33.4\",\"subType\":\"brands\",\"Browser\":\"UNKNOWN\",\"OperatingSystem\":\"UNKNOWN\",\"Device\":\"UNKNOWN\",\"BrowserType\":\"UNKNOWN\",\"Domain\":\"UNKNOWN\"}]}";
    byte[] valueContents = (value).getBytes(StandardCharsets.UTF_8);
    SchemaAndValue sv = jsonConverter.toConnectData(topic, valueContents);

    SinkRecord record =
            new SinkRecord(
                    topic, partition, Schema.STRING_SCHEMA, "string", sv.schema(), sv.value(), partition);
    String got2 = service.getProcessedRecordForSnowpipe(record);

    assert got2.contains("\"tenantId\":\"803\"");
    assert got2.contains("\"entityType\":\"Events\"");
    assert got2.contains("\"rowCreated\":\"1692358480222\"");
  }
}
