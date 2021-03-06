package com.rk.bigscreen.jobs

import java.io.IOException
import java.util.Date

import com.rk.bigscreen.common.{DateUtil, HBaseUtil}
import com.rk.bigscreen.constants.{FieldIndex, HBaseConf}
import com.rk.bigscreen.utils.KafkaManager
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.hadoop.hbase.TableName
import org.apache.hadoop.hbase.client.{Get, Put, Result, Table}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.streaming.kafka010.HasOffsetRanges
import org.apache.spark.streaming.{Seconds, StreamingContext}

/**
  * @program: bigscreen
  * @class_name CalcBigscreenApplication
  * @author: rk
  * @create: 2019-03-19 11:06
  * @Description:
  *           大屏业务统计作业
  *             主要通过 scala + SparkStreaming + HBase 来完成
  *
  **/
object CalcBigscreenApplication {

  def main(args: Array[String]): Unit = {
    Logger.getLogger("org.apache.hadoop").setLevel(Level.WARN)
    Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
    Logger.getLogger("org.spark-project").setLevel(Level.WARN)

    if (args == null || args.length < 1){
      println(
        """
          |Parameter Errors! Usage: <batchInterval> <topics> <group>
          |
        """.stripMargin)
      System.exit(-1)
    }

    val Array(batchInterval, topics, group) = args

    val conf = new SparkConf().setMaster("local[*]").setAppName(this.getClass.getSimpleName)
    val ssc: StreamingContext = new StreamingContext(conf,Seconds(batchInterval.toLong))

    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> "hadoop01:9092,hadoop02:9092,hadoop03:9092",
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> group,
      "auto.offset.reset" -> "earliest"
    )

    val messages: InputDStream[ConsumerRecord[String, String]] = KafkaManager.createMsg(ssc,topics.split(",").toSet,
            kafkaParams,group,curator)

    messages.foreachRDD((rdd, time) =>{
      if(!rdd.isEmpty()){
        println("-------------------------------------------")
        println(s"Time: $time")
//        println("###############rdd'count: " + rdd.count())
        process(rdd)
        println("-------------------------------------------")

        //  更新offset到zk中
        KafkaManager.storeOffset(curator,group,rdd.asInstanceOf[HasOffsetRanges].offsetRanges)
      }
    })

    ssc.start()
    ssc.awaitTermination()
  }


  /**
    *   业务处理的过程
    *       实时总交易额
    *       店铺销售排行
    *       实时交易额变化趋势
    *    第一步： 将原始数据进行转化操作
    *    第二步： 分布统计
    *    第三步:  存储结果
    *    数据格式：
    *         time, url, product_id, name, keyword, rank, current_price, original_price, sales_count, shipping_address, shop_id, comments_count, shop_name,
    *    数据样例：
    *         2018-02-28 14:51:00^https://item.taobao.com/item.htm?id=542076200484^542076000000^保罗长袖polo衫t恤男翻领男士纯棉秋衣休闲衣服中年男装秋装上衣^男装^第6页第31个^1680^1680^6^广东佛山^73077284^8^杰思卡服饰品牌店
    *
    * @param rdd
    */
  def process(rdd: RDD[ConsumerRecord[String, String]]) = {
    val baseRDD: RDD[String] = rdd.map(record => {
      val msg = record.value()
      val fields = msg.split("\\^")
      if (fields.length != 13) {
        //  异常数据处理
        ""
      } else {
        //时间字段
        val time = fields(FieldIndex.INDEX_TIME)
        val product = fields(FieldIndex.INDEX_PRODUCT_ID)
        val productName = fields(FieldIndex.INDEX_PRODUCT_NAME)
        val priceStr = fields(FieldIndex.INDEX_CURRENT_PRICE)
        var price =0.0
        if (priceStr.contains("-")){
          val minMax = priceStr.split("-")
          val min = minMax(0).trim.toDouble
          val max = minMax(1).trim.toDouble
          price = (min + max) / 2
        }else{
          price = priceStr.toDouble
        }


        val shopId = fields(FieldIndex.INDEX_SHOP_ID)
        val shopName = fields(FieldIndex.INDEX_SHOP_NAME)

        s"${time}^${product}^${productName}^${price}^${shopId}^${shopName}"
      }
    })
    baseRDD.cache()

    processAllAmt(baseRDD)
    processShopRank(baseRDD)
    processAmtTrend(baseRDD)

    //  卸载rdd
    baseRDD.unpersist()
  }

  /**
    *   时间趋势
    *   2018-02-28 14:51:00
    * @param baseRDD
    */
  def processAmtTrend(baseRDD: RDD[String]) = {
    println("--------执行交易趋势的统计---------")
    println("--------执行了"+baseRDD.count()+"条---------")
    val time2Amt: RDD[(String, Double)] = baseRDD.map(str => {
     if (str.equals("")){
       ("", 0.0)
     }else{
       val fields = str.split("\\^")
       //      val time = fields(0).trim
       val time = DateUtil.formatTime(new Date())
       val price = fields(3).toDouble
       (time, price)
     }
    }).filter(!_._1.equals(""))
      .reduceByKey(_ + _)
    time2Amt.foreachPartition(partition =>{
      if (!partition.isEmpty){
        val connection = HBaseUtil.getConnection
        val timeTbl = connection.getTable(TableName.valueOf(HBaseConf.TABLE_TIME_AMT_TREND))
        partition.foreach{case (time, amt) => {
          val timestamp = DateUtil.parseTime(time).toString.reverse
          HBaseUtil.addAmt(timeTbl,
            timestamp.getBytes(),
            HBaseConf.CF_TIME_AMT_TREND,
            HBaseConf.COL_TIME_AMT_TREND,
            amt
          )
        }}
        timeTbl.close()
        HBaseUtil.release(connection)
      }
    })

  }


  /**
    *   ${time}^${product}^${productName}^${price}^${shopId}^${shopName}
    *   店铺的名称 + 销售额
    * @param baseRDD
    */
  def processShopRank(baseRDD: RDD[String]) = {
    println("--------执行店铺排行的统计---------")
    println("--------执行了"+baseRDD.count()+"条---------")
    val shop2Price = baseRDD.map(str => {
      if (str.equals("")) {
        ("", 0.0)
      } else {
        val fields = str.split("\\^")
        val shopId = fields(4)
        val shopName = fields(5)
        val price = fields(3).toDouble
//        (shopId + "^" + shopName, price)
        (shopId,price)
      }
    }).reduceByKey(_ + _)
    shop2Price.foreachPartition(partition =>{
      if (!partition.isEmpty){
        val connection = HBaseUtil.getConnection
        var shopTbl: Table = connection.getTable(TableName.valueOf(HBaseConf.TABLE_SHOP_AMT_RANK))
          partition.foreach { case (shopInfo, price) => {
            if (!shopInfo.equals("")) {
              HBaseUtil.addAmt(shopTbl, shopInfo.getBytes(), HBaseConf.CF_SHOP_AMT_RANK, HBaseConf.COL_SHOP_AMT_RANK, price)
            }
          }}
        shopTbl.close()
        HBaseUtil.release(connection)
      }
    })
  }




  def processAllAmt(baseRDD: RDD[String]) = {
    println("--------执行总交易额的统计---------")
    println("--------执行了"+baseRDD.count()+"条---------")
    val curBatchAmt: Double = baseRDD.map(str => {
      if (str.equals("")) {
        0.0
      } else {
        val fields = str.split("\\^")
        fields(3).toDouble
      }
    }).sum()

    /**
      *   将结果写到hbase对应的表中即可 'sale_total_amt','cf'
      *   逻辑:
      *       总金额 = 历史数据 + 本批次金额
      *       1）  从Hbase中获取历史金额
      *       2）  更新历史数据
      *
      *     如果使用hbase等操作，需要两步才能完成
      *     而如果是redis，一步足矣(incrByFloat)
      *
      */
    /*
    val connection = HBaseUtil.getConnection
    val totalAmtTbl: Table = connection.getTable(TableName.valueOf(HBaseConf.TABLE_SALE_TOTAL_AMT))
    val histAmtResult: Result = totalAmtTbl.get(new Get(HBaseConf.RK_SALE_TOTAL_AMT))

    var curTotalAmt = curBatchAmt
    if(!histAmtResult.isEmpty){
      val histAmt = new String(histAmtResult.getValue(HBaseConf.CF_SALE_TOTAL_AMT,HBaseConf.COL_SALE_TOTAL_AMT)).toDouble
      //  总的
      curTotalAmt += histAmt
    }
    //  更新回去
    val put = new Put(HBaseConf.RK_SALE_TOTAL_AMT)
    put.addColumn(HBaseConf.CF_SALE_TOTAL_AMT,HBaseConf.COL_SALE_TOTAL_AMT,curTotalAmt.toString.getBytes())
    totalAmtTbl.put(put)
    totalAmtTbl.close()
    */

    val connection = HBaseUtil.getConnection
    val totalAmtTbl = connection.getTable(TableName.valueOf(HBaseConf.TABLE_SALE_TOTAL_AMT))
    HBaseUtil.addAmt(totalAmtTbl,
      HBaseConf.RK_SALE_TOTAL_AMT,
      HBaseConf.CF_SALE_TOTAL_AMT,
      HBaseConf.COL_SALE_TOTAL_AMT,
      curBatchAmt)
    totalAmtTbl.close()
    HBaseUtil.release(connection)
  }





  val curator = {
    val curator: CuratorFramework = CuratorFrameworkFactory.builder()
      .namespace("mykafka")
      .connectString("hadoop01:2181,hadoop02:2181,hadoop03:2181")
      .retryPolicy(new ExponentialBackoffRetry(1000, 3))
      .build()
    curator.start()//在使用之前必须先启动
    curator
  }



}
