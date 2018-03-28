package com.hxqh.bigdata.ma.spark

import java.io.IOException

import com.hxqh.bigdata.ma.common.Constants
import com.hxqh.bigdata.ma.domain.Literature
import com.hxqh.bigdata.ma.util.{DateUtils, ElasticSearchUtils, EsUtils}
import org.apache.spark.sql.SparkSession
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.xcontent.XContentFactory

/**
  * Created by Ocean lin on 2018/3/22.
  *
  * @author Ocean lin
  */
object MarketLiteratureSpark {

  def main(args: Array[String]): Unit = {

    val spark = SparkSession.builder.appName("MarketLiteratureSpark").getOrCreate
    EsUtils.registerESTable(spark, "literature", "market_literature", "literature")
    val startDate = DateUtils.getYesterdayDate();
    val endDate = DateUtils.getTodayDate();

    val sql = "select * from literature where addTime>='" + startDate + "' and addTime<= '" + endDate + "'"
    val literature = spark.sql(sql).rdd
    literature.cache
    val client = ElasticSearchUtils.getClient


    // [2018-03-20 17:15:56,风若叶,80000,61,连载 签约 VIP 都市 异术超能,都市频道,都市超品医圣,qidian,null,异术超能]
    // 点击量排名Top10
    literature.distinct().filter(e => (e.get(2) != null)).map(e => (e.getString(6), e.getLong(2))).reduceByKey(_ + _).map(e => (e._2, e._1))
      .sortByKey(false).take(Constants.LITERATURE_TOP_NUM).foreach(e => {
      addLiterature(new Literature(e._1.toDouble, e._2, Constants.LITERATURE_PLAYNUM), client)
    })

    // 各标签占比情况
    literature.distinct().flatMap(e => {
      val splits = e.getString(4).split(" ")
      for (i <- 0 until splits.length - 1)
        yield (splits(i), 1)
    }).reduceByKey(_ + _).map(e => (e._2, e._1)).sortByKey(false).filter(e => e._1 > 10).collect().
      foreach(e => {
        addLiterature(new Literature(e._1.toDouble, e._2, Constants.LITERATURE_LABEL_PIE), client)
      })

    // 各标签点击量占比
    literature.distinct().filter(e => (e.get(2) != null)).flatMap(e => {
      val splits = e.getString(4).split(Constants.FILM_SPLIT_SPACE)
      for (i <- 0 until splits.length - 1)
        yield (splits(i), e.getLong(2))
    }).reduceByKey(_ + _).map(e => (e._2, e._1)).sortByKey(false).take(20).foreach(e => {
      addLiterature(new Literature(e._1.toDouble, e._2, Constants.LITERATURE_LABEL_CLICKNUM_PIE), client)
    })


    // 累计评论量最多作品排名Top10
    literature.distinct().filter(e => (e.get(3) != null)).map(e => (e.getString(6), e.getInt(3))).reduceByKey(_ + _).
      map(e => (e._2, e._1)).sortByKey(false).take(Constants.LITERATURE_TOP_NUM).foreach(e => {
      addLiterature(new Literature(e._1.toDouble, e._2, Constants.LITERATURE_COMMENT_TITLE), client)
    })

    // 累计评论量最多作者排名Top10
    literature.distinct().filter(e => (e.get(3) != null)).map(e => (e.getString(1), e.getInt(3))).reduceByKey(_ + _).
      map(e => (e._2, e._1)).sortByKey(false).take(Constants.LITERATURE_TOP_NUM).foreach(e => {
      addLiterature(new Literature(e._1.toDouble, e._2, Constants.LITERATURE_COMMENT_AUTHOR), client)
    })


    // 累计点击量最多作品排名Top10
    literature.distinct().filter(e => (e.get(2) != null)).map(e => (e.getString(6), e.getLong(2))).reduceByKey(_ + _).
      map(e => (e._2, e._1)).sortByKey(false).take(Constants.LITERATURE_TOP_NUM).foreach(e => {
      addLiterature(new Literature(e._1.toDouble, e._2, Constants.LITERATURE_CLICKNUM_TITLE), client)
    })


    // 累计点击量最多作者排名Top10
    literature.distinct().filter(e => (e.get(2) != null)).map(e => (e.getString(1), e.getLong(2))).reduceByKey(_ + _).
      map(e => (e._2, e._1)).sortByKey(false).take(Constants.LITERATURE_TOP_NUM).foreach(e => {
      addLiterature(new Literature(e._1.toDouble, e._2, Constants.LITERATURE_CLICKNUM_AUTHOR), client)
    })

    // todo
    // 周度、月度各标签类别点击量变化曲线

  }

  /**
    *
    * @param literature 持久化的电视剧对象
    * @param client     elasticsearch client
    */
  def addLiterature(literature: Literature, client: TransportClient): Unit = try {
    val todayTime = DateUtils.getTodayTime
    val content = XContentFactory.jsonBuilder.startObject.
      field("numvalue", literature.numvalue).
      field("name", literature.name).
      field("category", literature.category).
      field("addTime", todayTime).endObject

    client.prepareIndex(Constants.BOOKS_ANALYSIS_INDEX, Constants.BOOKS_ANALYSIS_TYPE).setSource(content).get
    println(literature.name + " Persist to ES Success!")
  } catch {
    case e: IOException =>
      e.printStackTrace()
  }
}