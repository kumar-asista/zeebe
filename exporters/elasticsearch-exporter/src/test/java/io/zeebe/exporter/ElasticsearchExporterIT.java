/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.exporter.record.Record;
import io.zeebe.test.exporter.ExporterIntegrationRule;
import io.zeebe.util.ZbLogger;
import java.io.IOException;
import java.util.Map;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

public class ElasticsearchExporterIT {
  private static final String ES_VERSION = "6.6.0";
  private static final String ES_IMAGE = "docker.elastic.co/elasticsearch/elasticsearch";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ElasticsearchContainer container =
      new ElasticsearchContainer(String.format("%s:%s", ES_IMAGE, ES_VERSION))
          .withEnv("xpack.license.self_generated.type", "trial")
          .withEnv("xpack.security.enabled", "true");

  private final ExporterIntegrationRule exporterBrokerRule = new ExporterIntegrationRule();
  private ElasticsearchExporterConfiguration configuration;
  private ElasticsearchTestClient esClient;

  @Before
  public void setUp() {}

  @After
  public void tearDown() throws IOException {
    exporterBrokerRule.stop();

    if (esClient != null) {
      esClient.close();
      esClient = null;
    }
  }

  @Test
  public void shouldExportRecords() {
    // given
    container.start();
    configuration = getDefaultConfiguration();
    exporterBrokerRule.configure("es", ElasticsearchExporter.class, configuration);
    exporterBrokerRule.start();
    esClient = createElasticsearchClient(configuration);

    // when
    exporterBrokerRule.performSampleWorkload();

    // then

    // assert index settings for all created indices
    assertIndexSettings();

    // assert all records which where recorded during the tests where exported
    exporterBrokerRule.visitExportedRecords(
        r -> {
          if (configuration.shouldIndexRecord(r)) {
            assertRecordExported(r);
          }
        });
  }

  @Test
  public void shouldUseBasicAuthenticationIfConfigured() {
    // given
    final String password = "1234567";
    container.withEnv("ELASTIC_PASSWORD", password).start();
    configuration = getDefaultConfiguration();

    // when
    esClient = createElasticsearchClient(configuration);

    // then
    assertThatThrownBy(() -> esClient.client.ping(RequestOptions.DEFAULT)).isNotNull();

    // when
    configuration.authentication.username = "elastic";
    configuration.authentication.password = password;
    esClient = createElasticsearchClient(configuration);

    // when
    exporterBrokerRule.configure("es", ElasticsearchExporter.class, configuration);
    exporterBrokerRule.start();
    exporterBrokerRule.performSampleWorkload();

    // then
    exporterBrokerRule.visitExportedRecords(
        r -> {
          if (configuration.shouldIndexRecord(r)) {
            assertRecordExported(r);
          }
        });
  }

  private void assertIndexSettings() {
    final ImmutableOpenMap<String, Settings> settingsForIndices = esClient.getSettingsForIndices();
    for (ObjectCursor<String> key : settingsForIndices.keys()) {
      final Settings settings = settingsForIndices.get(key.value);
      final Integer numberOfShards = settings.getAsInt("index.number_of_shards", -1);
      final Integer numberOfReplicas = settings.getAsInt("index.number_of_replicas", -1);

      assertThat(numberOfShards)
          .withFailMessage(
              "Expected number of shards of index %s to be 1 but was %d", key.value, numberOfShards)
          .isEqualTo(1);
      assertThat(numberOfReplicas)
          .withFailMessage(
              "Expected number of replicas of index %s to be 0 but was %d",
              key.value, numberOfReplicas)
          .isEqualTo(0);
    }
  }

  private void assertRecordExported(Record<?> record) {
    final Map<String, Object> source = esClient.get(record);
    assertThat(source)
        .withFailMessage("Failed to fetch record %s from elasticsearch", record)
        .isNotNull();

    assertThat(source).isEqualTo(recordToMap(record));
  }

  protected ElasticsearchTestClient createElasticsearchClient(
      ElasticsearchExporterConfiguration configuration) {
    return new ElasticsearchTestClient(
        configuration, new ZbLogger("io.zeebe.exporter.elasticsearch"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> recordToMap(final Record<?> record) {
    final JsonNode jsonNode;
    try {
      jsonNode = MAPPER.readTree(record.toJson());
    } catch (IOException e) {
      throw new AssertionError("Failed to deserialize json of record " + record.toJson(), e);
    }

    return MAPPER.convertValue(jsonNode, Map.class);
  }

  public ElasticsearchExporterConfiguration getDefaultConfiguration() {
    final ElasticsearchExporterConfiguration configuration =
        new ElasticsearchExporterConfiguration();

    configuration.url = String.format("http://%s", container.getHttpHostAddress());

    configuration.bulk.delay = 1;
    configuration.bulk.size = 1;

    configuration.index.prefix = "test-record";
    configuration.index.createTemplate = true;
    configuration.index.command = true;
    configuration.index.event = true;
    configuration.index.rejection = true;
    configuration.index.deployment = true;
    configuration.index.incident = true;
    configuration.index.job = true;
    configuration.index.jobBatch = true;
    configuration.index.message = true;
    configuration.index.messageSubscription = true;
    configuration.index.raft = true;
    configuration.index.variable = true;
    configuration.index.workflowInstance = true;
    configuration.index.workflowInstanceSubscription = true;

    return configuration;
  }

  public static class ElasticsearchTestClient extends ElasticsearchClient {

    public ElasticsearchTestClient(ElasticsearchExporterConfiguration configuration, Logger log) {
      super(configuration, log);
    }

    protected ImmutableOpenMap<String, Settings> getSettingsForIndices() {
      final GetSettingsRequest settingsRequest = new GetSettingsRequest();
      try {
        return client
            .indices()
            .getSettings(settingsRequest, RequestOptions.DEFAULT)
            .getIndexToSettings();
      } catch (IOException e) {
        throw new ElasticsearchExporterException("Failed to get index settings", e);
      }
    }

    protected Map<String, Object> get(Record<?> record) {
      final GetRequest request = new GetRequest(indexFor(record), typeFor(record), idFor(record));
      try {
        final GetResponse response = client.get(request, RequestOptions.DEFAULT);
        if (response.isExists()) {
          return response.getSourceAsMap();
        } else {
          return null;
        }
      } catch (IOException e) {
        throw new ElasticsearchExporterException(
            "Failed to get record " + idFor(record) + " from index " + indexFor(record));
      }
    }
  }
}
