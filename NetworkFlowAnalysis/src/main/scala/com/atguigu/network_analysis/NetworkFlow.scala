package com.atguigu.network_analysis

import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util

import org.apache.flink.api.common.functions.AggregateFunction
import org.apache.flink.api.common.state.{ListState, ListStateDescriptor}
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.{KeyedProcessFunction, ProcessFunction}
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.scala.function.WindowFunction
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.util.Collector

import scala.collection.mutable.ListBuffer

object NetworkFlow {

  def main(args: Array[String]): Unit = {
    val env: StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(1)
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    val stream: DataStream[String] = env.readTextFile("D:\\MyWork\\HBase\\Codes\\UserBehaviorAnalysis\\NetworkFlowAnalysis\\src\\main\\resources\\apache.log")

    stream.map{data =>
      val dataArr: Array[String] = data.split(" ")
      val format = new SimpleDateFormat("dd/MM/yyyy:HH:mm:ss")
      val timestamp: Long = format.parse(dataArr(3).trim).getTime
      ApacheLogEvent(dataArr(0).trim, dataArr(1).trim, timestamp, dataArr(5).trim, dataArr(6).trim)
    }
      .assignTimestampsAndWatermarks(new BoundedOutOfOrdernessTimestampExtractor[ApacheLogEvent](Time.seconds(60)) {
        override def extractTimestamp(element: ApacheLogEvent): Long = element.eventTime
      })
      .keyBy(_.url)
      .timeWindow(Time.minutes(10), Time.seconds(5))
      .aggregate(new CountAgg(), new WindowResult())
      .keyBy(_.windowEnd)
      .process(new TopNHotUrls(5))
      .print()

    env.execute("network flow job")
  }

}

//自定义预聚合函数
class CountAgg() extends AggregateFunction[ApacheLogEvent, Long, Long]{

  override def createAccumulator(): Long = 0L

  override def add(value: ApacheLogEvent, accumulator: Long): Long = accumulator + 1

  override def getResult(accumulator: Long): Long = accumulator

  override def merge(a: Long, b: Long): Long = a + b
}

//自定义窗口处理函数
class WindowResult() extends WindowFunction[Long, UrlViewCount, String, TimeWindow]{

  override def apply(key: String, window: TimeWindow, input: Iterable[Long], out: Collector[UrlViewCount]): Unit = {
    out.collect(UrlViewCount(key, window.getEnd, input.iterator.next))
  }

}

//自定义process function,实现排序输出
class TopNHotUrls(nSize : Int) extends KeyedProcessFunction[Long, UrlViewCount, String]{

  lazy val urlState : ListState[UrlViewCount] = getRuntimeContext.getListState(
    new ListStateDescriptor[UrlViewCount]("urlState", classOf[UrlViewCount])
  )

  override def processElement(value: UrlViewCount, ctx: KeyedProcessFunction[Long, UrlViewCount, String]#Context, out: Collector[String]): Unit = {
    urlState.add(value)
    ctx.timerService().registerEventTimeTimer(value.windowEnd + 1)
  }

  override def onTimer(timestamp: Long, ctx: KeyedProcessFunction[Long, UrlViewCount, String]#OnTimerContext, out: Collector[String]): Unit = {
    val allUrlViews: ListBuffer[UrlViewCount] = new ListBuffer[UrlViewCount]

    val iter: util.Iterator[UrlViewCount] = urlState.get().iterator()
    while(iter.hasNext){
      allUrlViews += iter.next()
    }

    urlState.clear()

    //按照count大小排序
    val sortedUrlViews: ListBuffer[UrlViewCount] = allUrlViews.sortWith(_.count > _.count).take(nSize)

    //格式化成String打印输出
    var result: StringBuilder = new StringBuilder
    result.append("====================================\n")
    result.append("时间: ").append(new Timestamp(timestamp - 1)).append("\n")

    for (i <- sortedUrlViews.indices) {
      val currentUrlView: UrlViewCount = sortedUrlViews(i)
      // e.g.  No1：  URL=/blog/tags/firefox?flav=rss20  流量=55
      result.append("No").append(i+1).append(":")
        .append("  URL=").append(currentUrlView.url)
        .append("  流量=").append(currentUrlView.count).append("\n")
    }
    result.append("====================================\n\n")
    // 控制输出频率，模拟实时滚动结果
    Thread.sleep(1000)
    out.collect(result.toString)
  }
}

//出入log数据样例类
case class ApacheLogEvent(
                         ip : String,
                         userId : String,
                         eventTime : Long,
                         method : String,
                         url : String
                         )

//中间统计结果样例类
case class UrlViewCount(
                       url : String,
                       windowEnd : Long,
                       count : Long
                       )