package carldata.pirx

import java.util.Properties

import com.timgroup.statsd.{NonBlockingStatsDClient, ServiceCheck, StatsDClient}
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecords, KafkaConsumer}
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._


/**
  * Connect to 'data' kafka topics, listen to the messages and try to predict future number of records
  * on this topic
  */
object MainApp {

  /** How long to wait for new batch of data. In milliseconds */
  val POLL_TIMEOUT = 1000
  /** Data topic name */
  val DATA_TOPIC = "data"

  private val Log = LoggerFactory.getLogger(MainApp.getClass.getName)

  case class Params(kafkaBroker: String, statsDHost: String)

  def stringArg(args: Array[String], key: String, default: String): String = {
    val name = "--" + key + "="
    args.find(_.contains(name)).map(_.substring(name.length)).getOrElse(default).trim
  }

  /** Command line parser */
  def parseArgs(args: Array[String]): Params = {
    val kafka = stringArg(args, "kafka", "localhost:9092")
    val statsDHost = stringArg(args, "statsDHost", "none")
    Params(kafka, statsDHost)
  }

  /** Kafka configuration */
  def buildConfig(brokers: String): Properties = {
    val strDeserializer = (new StringDeserializer).getClass.getName
    val props = new Properties()
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "dumper")
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, strDeserializer)
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, strDeserializer)
    props
  }

  /** StatsD configuration */
  def initStatsD(host: String): Option[StatsDClient] = {
    try {
      val sc: ServiceCheck = ServiceCheck.builder.withName("service.check").withStatus(ServiceCheck.Status.OK).build
      val client = new NonBlockingStatsDClient("dumper", host, 8125)
      client.serviceCheck(sc)
      Some(client)
    }
    catch {
      case e: Exception => Log.warn(e.getMessage)
        None
    }
  }

  /** Listen to Kafka topics and execute all processing pipelines */
  def main(args: Array[String]): Unit = {
    val params = parseArgs(args)
    StatsD.init(params.statsDHost)

    Log.info("Application started")
    run(params.kafkaBroker)
    Log.info("Application Stopped")
  }

  /**
    * Predict 0 records in the next batch
    */
  def run(kafkaBroker: String): Unit = {
    val kafkaConfig = buildConfig(kafkaBroker)
    val consumer = new KafkaConsumer[String, String](kafkaConfig)
    consumer.subscribe(List(DATA_TOPIC).asJava)

    while (true) {
      try {
        val batch: ConsumerRecords[String, String] = consumer.poll(POLL_TIMEOUT)
        val records = batch.records(DATA_TOPIC).asScala
        StatsD.gauge("predict.error", records.size)
      }
      catch {
        case e: Exception => Log.error(e.toString)
      }
    }
    consumer.close()
  }

}