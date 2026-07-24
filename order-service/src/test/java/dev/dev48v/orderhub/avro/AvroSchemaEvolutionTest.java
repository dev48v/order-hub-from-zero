package dev.dev48v.orderhub.avro;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

// Day 33 — the CONTRACT half of the story: schema EVOLUTION + compatibility. The reason to put events behind a
// schema registry is not the binary encoding — it's that the registry turns "someone changed the event shape"
// from a silent, runtime, production-only break into a checked rule at publish time. These tests demonstrate
// the two things every engineer needs to internalise about evolving an Avro event, with NO Kafka and NO broker
// — just the Avro runtime and the Confluent MockSchemaRegistryClient:
//
//   • The GOOD change — add an OPTIONAL field WITH a default. A v1 producer (which never heard of the field)
//     writes old bytes; a v2 consumer reads them and the missing field is filled from the default. Backward
//     compatible: new readers can read old data.
//   • The BREAKING change — add a REQUIRED field with NO default. A v2 reader now demands a field that old
//     v1 data simply doesn't contain and can't default, so it can't read it. The registry REJECTS this under
//     BACKWARD compatibility, so it never reaches production.
//
// "Backward compatible" (Confluent's default) is defined reader-vs-writer: can a consumer on the NEW schema
// read data written with the OLD schema? Avro answers that directly via SchemaCompatibility; the registry
// enforces the same answer as a gate on registration.
class AvroSchemaEvolutionTest {

    // v1 — the shipped OrderPlaced contract (a representative subset for the evolution demo).
    private static final String V1 = """
            {"type":"record","name":"OrderPlaced","namespace":"dev.dev48v.orderhub.avro.evo","fields":[
              {"name":"orderId","type":"string"},
              {"name":"item","type":"string"},
              {"name":"quantity","type":"int"}
            ]}""";

    // v2 GOOD — adds an OPTIONAL field WITH a default ("channel" defaults to "WEB"). Backward compatible:
    // a v2 reader can read v1 data (the field it adds is supplied by the default).
    private static final String V2_GOOD = """
            {"type":"record","name":"OrderPlaced","namespace":"dev.dev48v.orderhub.avro.evo","fields":[
              {"name":"orderId","type":"string"},
              {"name":"item","type":"string"},
              {"name":"quantity","type":"int"},
              {"name":"channel","type":"string","default":"WEB"}
            ]}""";

    // v2 BAD — adds a REQUIRED field with NO default. A v2 reader now needs "channel" on every record, but
    // v1 data doesn't carry it and there's no default to fall back on => it CANNOT read old data => the
    // registry rejects this evolution under BACKWARD compatibility.
    private static final String V2_BAD = """
            {"type":"record","name":"OrderPlaced","namespace":"dev.dev48v.orderhub.avro.evo","fields":[
              {"name":"orderId","type":"string"},
              {"name":"item","type":"string"},
              {"name":"quantity","type":"int"},
              {"name":"channel","type":"string"}
            ]}""";

    @Test
    @DisplayName("v1-written data read by a v2 reader gets the new optional field's DEFAULT (backward compatible)")
    void v1DataReadByV2ReaderFillsDefault() throws Exception {
        Schema v1 = new Schema.Parser().parse(V1);
        Schema v2 = new Schema.Parser().parse(V2_GOOD);

        // A v1 PRODUCER writes a record — it knows nothing about "channel".
        GenericRecord written = new GenericData.Record(v1);
        written.put("orderId", "ORD-1");
        written.put("item", "KEYBOARD-001");
        written.put("quantity", 2);
        byte[] oldBytes = encode(written, v1);

        // A v2 CONSUMER reads those OLD bytes: writer schema = v1, reader schema = v2.
        GenericDatumReader<GenericRecord> reader = new GenericDatumReader<>(v1, v2);
        GenericRecord read = reader.read(null, DecoderFactory.get().binaryDecoder(oldBytes, null));

        assertThat(read.get("orderId").toString()).isEqualTo("ORD-1");
        assertThat(read.get("quantity")).isEqualTo(2);
        // The field absent in v1 data is filled from the v2 schema's default — no break, no null.
        assertThat(read.get("channel").toString()).isEqualTo("WEB");
    }

    @Test
    @DisplayName("Avro compatibility: an optional field w/ default is COMPATIBLE; a required field is INCOMPATIBLE")
    void avroSchemaCompatibilityRules() {
        Schema v1 = new Schema.Parser().parse(V1);
        Schema v2Good = new Schema.Parser().parse(V2_GOOD);
        Schema v2Bad = new Schema.Parser().parse(V2_BAD);

        // Backward = can the NEW reader read data written by the OLD writer? (reader=v2, writer=v1)
        assertThat(SchemaCompatibility.checkReaderWriterCompatibility(v2Good, v1).getType())
                .isEqualTo(SchemaCompatibilityType.COMPATIBLE);
        assertThat(SchemaCompatibility.checkReaderWriterCompatibility(v2Bad, v1).getType())
                .isEqualTo(SchemaCompatibilityType.INCOMPATIBLE);
    }

    @Test
    @DisplayName("the registry ENFORCES it: a BACKWARD subject accepts v2-with-default, rejects v2-required")
    void schemaRegistryEnforcesBackwardCompatibility() throws Exception {
        MockSchemaRegistryClient registry = new MockSchemaRegistryClient();
        String subject = "order-placed-avro-value";

        // v1 is the currently-registered schema for the subject.
        registry.register(subject, new AvroSchema(V1));
        // The mock defaults to BACKWARD; set it explicitly so the rule under test is unambiguous.
        registry.updateCompatibility(subject, "BACKWARD");

        // The registry would ACCEPT this evolution (auto-register on first publish would succeed):
        assertThat(registry.testCompatibility(subject, new AvroSchema(V2_GOOD))).isTrue();
        // …and REJECT this one at publish time — the break is caught before any consumer ever sees it:
        assertThat(registry.testCompatibility(subject, new AvroSchema(V2_BAD))).isFalse();
    }

    // Encode a record to binary Avro using the given writer schema (what a producer puts on the wire).
    private static byte[] encode(GenericRecord record, Schema writerSchema) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Encoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        new GenericDatumWriter<GenericRecord>(writerSchema).write(record, encoder);
        encoder.flush();
        return out.toByteArray();
    }
}
