/*
 * Copyright 2017 The Project Authors, see separate AUTHORS file
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
package demo;

import com.google.api.gax.grpc.Batch;
import demo.UserScore.ParseEventFn;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.NullableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.kafka.KafkaIO;
import org.apache.beam.sdk.io.kafka.KafkaRecord;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.StreamingOptions;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.WithTimestamps;
import org.apache.beam.sdk.transforms.windowing.AfterPane;
import org.apache.beam.sdk.transforms.windowing.AfterProcessingTime;
import org.apache.beam.sdk.transforms.windowing.AfterWatermark;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is the third in a series of four pipelines that tell a story in a 'gaming' domain,
 * following {@link UserScore} and {@link HourlyTeamScore}. Concepts include: processing unbounded
 * data using fixed windows; use of custom timestamps and event-time processing; generation of
 * early/speculative results; using .accumulatingFiredPanes() to do cumulative processing of late-
 * arriving data.
 *
 * <p>This pipeline processes an unbounded stream of 'game events'. The calculation of the team
 * scores uses fixed windowing based on event time (the time of the game play event), not
 * processing time (the time that an event is processed by the pipeline). The pipeline calculates
 * the sum of scores per team, for each window. By default, the team scores are calculated using
 * one-hour windows.
 *
 * <p>In contrast-- to demo another windowing option-- the user scores are calculated using a
 * global window, which periodically (every ten minutes) emits cumulative user score sums.
 *
 * <p>In contrast to the previous pipelines in the series, which used static, finite input data,
 * here we're using an unbounded data source, which lets us provide speculative results, and allows
 * handling of late data, at much lower latency. We can use the early/speculative results to keep a
 * 'leaderboard' updated in near-realtime. Our handling of late data lets us generate correct
 * results, e.g. for 'team prizes'. We're now outputting window results as they're
 * calculated, giving us much lower latency than with the previous batch examples.
 *
 * <p>Run {@code injector.Injector} to generate pubsub data for this pipeline.  The Injector
 * documentation provides more detail on how to do this.
 *
 * <p>To execute this pipeline using the Dataflow service, specify the pipeline configuration
 * like this:
 * <pre>{@code
 *   --project=YOUR_PROJECT_ID
 *   --tempLocation=gs://YOUR_TEMP_DIRECTORY
 *   --runner=BlockingDataflowRunner
 *   --dataset=YOUR-DATASET
 *   --topic=projects/YOUR-PROJECT/topics/YOUR-TOPIC
 * }
 * </pre>
 * where the BigQuery dataset you specify must already exist.
 * The PubSub topic you specify should be the same topic to which the Injector is publishing.
 */
public class LeaderBoard extends HourlyTeamScore {

  static final Duration TWO_MINUTES = Duration.standardMinutes(2);
  static final Duration FIVE_MINUTES = Duration.standardMinutes(5);
  static final Duration TEN_MINUTES = Duration.standardMinutes(10);


  interface Options extends BatchOptions, StreamingOptions {
    @Description("Topic to read from")
    @Default.String("game")
    String getTopic();
    void setTopic(String value);
  }

  public static void main(String[] args) throws Exception {

    Options options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);
    Pipeline pipeline = Pipeline.create(options);

    pipeline
    .apply(PubsubIO.readStrings()
        .withTimestampAttribute("timestamp_ms")
        .fromTopic(options.getTopic()))
    .apply("SetTimestamps", WithTimestamps.of(new SetTimestampFn()))
    //.apply("FixedWindows", Window.<String>into(FixedWindows.of(ONE_HOUR)))
    .apply("FixedWindows", Window.<String>into(FixedWindows.of(FIVE_MINUTES))
        .triggering(AfterWatermark.pastEndOfWindow()
            .withEarlyFirings(AfterProcessingTime.pastFirstElementInPane()
                .plusDelayOf(TWO_MINUTES))
            .withLateFirings(AfterPane.elementCountAtLeast(1)))
        .withAllowedLateness(TEN_MINUTES)
        .accumulatingFiredPanes())

    .apply("TeamScore", new CalculateTeamScores(options.getOutputPrefix()));

    pipeline.run();
  }
}
