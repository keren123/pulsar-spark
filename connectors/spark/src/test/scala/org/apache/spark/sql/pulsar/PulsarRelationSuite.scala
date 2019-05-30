/**
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
package org.apache.spark.sql.pulsar

import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pulsar.client.api.MessageId
import org.apache.pulsar.common.naming.TopicName
import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.test.SharedSQLContext

class PulsarRelationSuite  extends QueryTest with SharedSQLContext with PulsarTest {
  import testImplicits._
  import PulsarOptions._

  private val topicId = new AtomicInteger(0)
  private def newTopic(): String = TopicName.get(s"topic-${topicId.getAndIncrement()}").toString


  private def createDF(
      topic: String,
      withOptions: Map[String, String] = Map.empty[String, String],
      brokerAddress: Option[String] = None) = {
    val df = spark
      .read
      .format("pulsar")
      .option(SERVICE_URL_OPTION_KEY, serviceUrl)
      .option(ADMIN_URL_OPTION_KEY, adminUrl)
      .option(TOPIC_MULTI, topic)
    withOptions.foreach {
      case (key, value) => df.option(key, value)
    }
    df.load().selectExpr("CAST(value AS STRING)")
  }

  test("explicit earliest to latest offsets") {
    val topic = newTopic()
    sendMessages(topic, (0 to 9).map(_.toString).toArray, None)
    sendMessages(topic, (10 to 19).map(_.toString).toArray, None)
    sendMessages(topic, Array("20"), None)

    // Specify explicit earliest and latest offset values
    val df = createDF(topic,
      withOptions = Map("startingOffsets" -> "earliest", "endingOffsets" -> "latest"))
    checkAnswer(df, (0 to 20).map(_.toString).toDF)

    // "latest" should late bind to the current (latest) offset in the df
    sendMessages(topic, (21 to 29).map(_.toString).toArray, None)
    checkAnswer(df, (0 to 29).map(_.toString).toDF)
  }

  test("default starting and ending offsets") {
    val topic = newTopic()
    sendMessages(topic, (0 to 9).map(_.toString).toArray, None)
    sendMessages(topic, (10 to 19).map(_.toString).toArray, None)
    sendMessages(topic, Array("20"), None)

    // Implicit offset values, should default to earliest and latest
    val df = createDF(topic)
    // Test that we default to "earliest" and "latest"
    checkAnswer(df, (0 to 20).map(_.toString).toDF)
  }

  test("explicit offsets") {
    val topic1 = newTopic()
    val topic2 = newTopic()

    sendMessages(topic1, (0 to 9).map(_.toString).toArray, None)
    val t2mid: Seq[(String, MessageId)] = sendMessages(topic2, (10 to 19).map(_.toString).toArray, None)
    val first = t2mid.head._2
    val last = t2mid.last._2

    // Test explicitly specified offsets
    val startPartitionOffsets = Map(
      topic1 -> MessageId.earliest,
      topic2 -> first // explicit offset happens to = the first
    )
    val startingOffsets = JsonUtils.topicOffsets(startPartitionOffsets)

    val endPartitionOffsets = Map(
      topic1 -> MessageId.latest, // -1 => latest
      topic2 -> last) // explicit offset happens to = the latest

    val endingOffsets = JsonUtils.topicOffsets(endPartitionOffsets)
    val df = createDF(s"$topic1,$topic2",
      withOptions = Map("startingOffsets" -> startingOffsets, "endingOffsets" -> endingOffsets))
    checkAnswer(df, (0 to 19).map(_.toString).toDF)

    // static offset partition 2, nothing should change
    sendMessages(topic2, (31 to 39).map(_.toString).toArray, None)
    checkAnswer(df, (0 to 19).map(_.toString).toDF)

    // latest offset partition 1, should change
    sendMessages(topic1, (20 to 29).map(_.toString).toArray, None)
    checkAnswer(df, (0 to 29).map(_.toString).toDF)
  }

  test("reuse same dataframe in query") {
    // This test ensures that we do not cache the Pulsar Consumer in PulsarRelation
    val topic = newTopic()
    sendMessages(topic, (0 to 10).map(_.toString).toArray, None)

    // Specify explicit earliest and latest offset values
    val df = createDF(topic,
      withOptions = Map("startingOffsets" -> "earliest", "endingOffsets" -> "latest"))
    checkAnswer(df.union(df), ((0 to 10) ++ (0 to 10)).map(_.toString).toDF)
  }

  test("bad batch query options") {
    def testBadOptions(options: (String, String)*)(expectedMsgs: String*): Unit = {
      val ex = intercept[IllegalArgumentException] {
        val reader = spark
          .read
          .format("pulsar")
          .option(SERVICE_URL_OPTION_KEY, serviceUrl)
          .option(ADMIN_URL_OPTION_KEY, adminUrl)
        options.foreach { case (k, v) => reader.option(k, v) }
        reader.load()
      }
      expectedMsgs.foreach { m =>
        assert(ex.getMessage.toLowerCase(Locale.ROOT).contains(m.toLowerCase(Locale.ROOT)))
      }
    }

    // Specifying an ending offset as the starting point
    testBadOptions("startingOffsets" -> "latest")("starting offset can't be latest " +
      "for batch queries on pulsar")

    // Now do it with an explicit json start offset indicating latest
    val startPartitionOffsets = Map( "t" -> MessageId.latest)
    val startingOffsets = JsonUtils.topicOffsets(startPartitionOffsets)
    testBadOptions(TOPIC_SINGLE -> "t", "startingOffsets" -> startingOffsets)(
      "starting offset for t can't be latest for batch queries on pulsar")


    // Make sure we catch ending offsets that indicate earliest
    testBadOptions("endingOffsets" -> "earliest")("ending offset can't be" +
      " earliest for batch queries on pulsar")

    // Make sure we catch ending offsets that indicating earliest
    val endPartitionOffsets = Map("t" -> MessageId.earliest)
    val endingOffsets = JsonUtils.topicOffsets(endPartitionOffsets)
    testBadOptions(TOPIC_SINGLE -> "t", "endingOffsets" -> endingOffsets)(
      "ending offset for t can't be earliest for batch queries on pulsar")

    // No strategy specified
    testBadOptions()("one of the topic options", TOPIC_SINGLE, TOPIC_MULTI, TOPIC_PATTERN)

    // Multiple strategies specified
    testBadOptions(TOPIC_MULTI -> "t", TOPIC_PATTERN -> "t.*")(
      "one of the topic options")

    testBadOptions(TOPIC_MULTI -> "t", TOPIC_SINGLE -> """{"a":[0]}""")(
      "one of the topic options")

    testBadOptions(TOPIC_SINGLE -> "")("no topic is specified")
    testBadOptions(TOPIC_MULTI -> "")("No topics is specified")
    testBadOptions(TOPIC_PATTERN -> "")("TopicsPattern is empty")
  }
}
