package com.rk.bigscreen.utils

import java.util

import org.apache.curator.framework.CuratorFramework
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.streaming.kafka010._

import scala.collection.{JavaConversions, mutable}

/**
  * @program: bigscreen
  * @class_name KafkaManager
  * @author: rk
  * @create: 2019-03-19 11:12
  * @Description:
  **/
object KafkaManager {

  /**
    *   mykafka/offsets/${topic}/${group}/${partition}
    * @param curator
    * @param group
    * @param offsetRanges
    */
  def storeOffset(curator:CuratorFramework, group: String, offsetRanges: Array[OffsetRange]) = {
    for (offsetRange <- offsetRanges){
      val partition = offsetRange.partition
      val topic = offsetRange.topic
      val offset = offsetRange.untilOffset
      val fullPath = s"/offsets/${topic}/${group}/${partition}"
      checkExists(fullPath, curator)
      //更新数据
      curator.setData().forPath(fullPath, (""+offset).getBytes())

    }
  }


  def createMsg(ssc: StreamingContext, topics:Set[String],
                kafkaParams:Map[String,Object],
                group: String,
                curator:CuratorFramework ): InputDStream[ConsumerRecord[String, String]] = {

    val offsets:Map[TopicPartition, Long] = getOffsets(topics, group,curator)
    var message:InputDStream[ConsumerRecord[String, String]]  = null

    /**
      *  SparkStreaming + kafka
      *     locationStrategy(本地化策略):
      *       从kafka0.10的版本开始，消费者每次都是预先拉取对应分区中的数据，所以把该consumer的信息cache到相近的
      *       executor上面可以提高执行的效率，而不是每次调度作业都重新的为不同的partition创建新的consumer。
      *       PerferBrokers:  executor(计算)和kafka-broker(数据)在同一台节点上的时候使用。
      *       PreferConsistent(most):  大多数情况下使用这种情况，当kafka-partition分散在executor上面的时候，
      *       二者不一一对应的时候。
      *       PerferFixe:   如果partition存储在特定的主机之上，而且kafka集群环境配置参差不齐，这个时候就可以
      *       将partition对应的host对应起来进行消费。
      *     consumerStrategy(消费策略):
      *         Assign:   Assign a fixed collection of TopicPartitions  直接指定
      *         Subscribe:    Subscribe to a collection of topics.  订阅多个topic集合
      *         SubscribePattern:   Subscribe to all topics matching specified pattern to get dynamically assigned partitions.
      *                              The pattern matching will be done periodically against topics existing at the time of check.
      *                              使用一个正则表达式来进行匹配kafkaParams
      *
      */
    if (offsets.isEmpty){
      message = KafkaUtils.createDirectStream(ssc,
        LocationStrategies.PreferConsistent,
        ConsumerStrategies.Subscribe(topics,kafkaParams))
    }else{
      message = KafkaUtils.createDirectStream(ssc,
        LocationStrategies.PreferConsistent,
        ConsumerStrategies.Subscribe(topics,kafkaParams,offsets))

    }
    message

  }


  /**
    *   获取指定topic的partition的偏移量
    *   怎么操作zk -->  zookeeper的框架CuratorFramework
    *
    * @param topics
    * @param group
    * @param curator
    * @return
    */
  def getOffsets(topics:Set[String], group:String, curator:CuratorFramework): Map[TopicPartition, Long] = {

    val offsets = mutable.Map[TopicPartition, Long]()
    for (topic <- topics){
      val parent = s"/offsets/${topic}/${group}"
      // 判断parent是否存在
      checkExists(parent, curator)
      val partitions = JavaConversions.asScalaBuffer(curator.getChildren.forPath(parent))
      // 读取对应parent下面的所有的partition信息
      for (partition <- partitions){
        val fullPath = s"${parent}/${partition}"
        val offset = new String(curator.getData.forPath(fullPath)).toLong
        offsets.put(new TopicPartition(topic, partition.toInt), offset)
      }
      /*//因为有嵌套for，所以无法做返回，只能在外层的yield中做返回
            val offsets = for(partition <- JavaConversions.asScalaBuffer(curator.getChildren.forPath(parent))) yield {
                val fullPath = s"${parent}/${partition}"
                val offset = new String(curator.getData.forPath(fullPath)).toLong
                (new TopicPartition(topic, partition.toInt), offset)
            }*/
    }
    offsets.toMap
    }

  def checkExists(path: String, curator:CuratorFramework) = {
    if (curator.checkExists().forPath(path) == null){
      // 当前path必然存在
      curator.create().creatingParentsIfNeeded().forPath(path)
    }

  }








}
