/*
 * Copyright (c) 2021, NVIDIA CORPORATION.
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

package org.apache.spark.sql.rapids.tool.profiling

import scala.collection.Map
import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.io.{Codec, Source}
import scala.math.Ordering

import com.nvidia.spark.rapids.tool.ToolTextFileWriter
import com.nvidia.spark.rapids.tool.profiling._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.json4s.jackson.JsonMethods.parse

import org.apache.spark.deploy.history.EventLogFileReader
import org.apache.spark.internal.Logging
import org.apache.spark.scheduler._
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.execution.SparkPlanInfo
import org.apache.spark.sql.execution.ui.SparkPlanGraph
import org.apache.spark.ui.UIUtils
import org.apache.spark.util._

/**
 * ApplicationInfo class saves all parsed events for future use.
 */

class ApplicationInfo(
    val numOutputRows: Int,
    val sparkSession: SparkSession,
    val eventlog: Path,
    val index: Int,
    val forQualification: Boolean = false) extends Logging {

  // From SparkListenerLogStart
  var sparkVersion: String = ""

  // allDataFrames is to store all the DataFrames
  // after event log parsing has completed.
  // Possible DataFrames include:
  // 1. resourceProfilesDF (Optional)
  // 2. blockManagersDF (Optional)
  // 3. appDF (Must exist, otherwise fail!)
  // 4. executorsDF (Must exist, otherwise fail!)
  // 5. propertiesDF (Must exist, otherwise fail!)
  // 6. blockManagersRemoved (Optional)
  // 7. sqlDF (Could be missing)
  // 8. jobDF (Must exist, otherwise fail!)
  // 9. stageDF (Must exist, otherwise fail!)
  // 10. taskDF (Must exist, otherwise fail!)
  val allDataFrames: HashMap[String, DataFrame] = HashMap.empty[String, DataFrame]

  // From SparkListenerResourceProfileAdded
  var resourceProfiles: ArrayBuffer[ResourceProfileCase] = ArrayBuffer[ResourceProfileCase]()

  // From SparkListenerBlockManagerAdded and SparkListenerBlockManagerRemoved
  var blockManagers: ArrayBuffer[BlockManagerCase] =
    ArrayBuffer[BlockManagerCase]()
  var blockManagersRemoved: ArrayBuffer[BlockManagerRemovedCase] =
    ArrayBuffer[BlockManagerRemovedCase]()

  // From SparkListenerEnvironmentUpdate
  var sparkProperties = Map.empty[String, String]
  var hadoopProperties = Map.empty[String, String]
  var systemProperties = Map.empty[String, String]
  var jvmInfo = Map.empty[String, String]
  var classpathEntries = Map.empty[String, String]
  var gpuMode = false
  var allProperties: ArrayBuffer[PropertiesCase] = ArrayBuffer[PropertiesCase]()

  // From SparkListenerApplicationStart and SparkListenerApplicationEnd
  var appStart: ArrayBuffer[ApplicationCase] = ArrayBuffer[ApplicationCase]()
  var appEndTime: Option[Long] = None
  var appId: String = ""

  // From SparkListenerExecutorAdded and SparkListenerExecutorRemoved
  var executors: ArrayBuffer[ExecutorCase] = ArrayBuffer[ExecutorCase]()
  var executorsRemoved: ArrayBuffer[ExecutorRemovedCase] = ArrayBuffer[ExecutorRemovedCase]()

  // From SparkListenerSQLExecutionStart and SparkListenerSQLExecutionEnd
  var sqlStart: ArrayBuffer[SQLExecutionCase] = ArrayBuffer[SQLExecutionCase]()
  val sqlEndTime: HashMap[Long, Long] = HashMap.empty[Long, Long]

  // From SparkListenerSQLExecutionStart and SparkListenerSQLAdaptiveExecutionUpdate
  // sqlPlan stores HashMap (sqlID <-> SparkPlanInfo)
  var sqlPlan: HashMap[Long, SparkPlanInfo] = HashMap.empty[Long, SparkPlanInfo]
  // physicalPlanDescription stores HashMap (sqlID <-> physicalPlanDescription)
  var physicalPlanDescription: HashMap[Long, String] = HashMap.empty[Long, String]

  // From SparkListenerSQLExecutionStart and SparkListenerSQLAdaptiveExecutionUpdate
  var sqlPlanMetrics: ArrayBuffer[SQLPlanMetricsCase] = ArrayBuffer[SQLPlanMetricsCase]()
  var planNodeAccum: ArrayBuffer[PlanNodeAccumCase] = ArrayBuffer[PlanNodeAccumCase]()
  // From SparkListenerSQLAdaptiveSQLMetricUpdates
  var sqlPlanMetricsAdaptive: ArrayBuffer[SQLPlanMetricsCase] = ArrayBuffer[SQLPlanMetricsCase]()

  // From SparkListenerDriverAccumUpdates
  var driverAccum: ArrayBuffer[DriverAccumCase] = ArrayBuffer[DriverAccumCase]()
  // From SparkListenerTaskEnd and SparkListenerTaskEnd
  var taskStageAccum: ArrayBuffer[TaskStageAccumCase] = ArrayBuffer[TaskStageAccumCase]()

  // From SparkListenerJobStart and SparkListenerJobEnd
  // JobStart contains mapping relationship for JobID -> StageID(s)
  var jobStart: ArrayBuffer[JobCase] = ArrayBuffer[JobCase]()
  val jobEndTime: HashMap[Int, Long] = HashMap.empty[Int, Long]
  val jobEndResult: HashMap[Int, String] = HashMap.empty[Int, String]
  val jobFailedReason: HashMap[Int, String] = HashMap.empty[Int, String]

  // From SparkListenerStageSubmitted and SparkListenerStageCompleted
  // stageSubmitted contains mapping relationship for Stage -> RDD(s)
  var stageSubmitted: ArrayBuffer[StageCase] = ArrayBuffer[StageCase]()
  val stageCompletionTime: HashMap[Int, Option[Long]] = HashMap.empty[Int, Option[Long]]
  val stageFailureReason: HashMap[Int, Option[String]] = HashMap.empty[Int, Option[String]]

  // From SparkListenerTaskStart & SparkListenerTaskEnd
  // taskStart was not used so comment out for now
  // var taskStart: ArrayBuffer[SparkListenerTaskStart] = ArrayBuffer[SparkListenerTaskStart]()
  // taskEnd contains task level metrics - only used for profiling
  var taskEnd: ArrayBuffer[TaskCase] = ArrayBuffer[TaskCase]()

  // this is used to aggregate metrics for qualification to speed up processing and
  // minimize memory usage
  var stageTaskQualificationEnd: HashMap[String, StageTaskQualificationSummary] =
    HashMap.empty[String, StageTaskQualificationSummary]

  // From SparkListenerTaskGettingResult
  var taskGettingResult: ArrayBuffer[SparkListenerTaskGettingResult] =
    ArrayBuffer[SparkListenerTaskGettingResult]()

  // Unsupported SQL plan
  var unsupportedSQLplan: ArrayBuffer[UnsupportedSQLPlan] = ArrayBuffer[UnsupportedSQLPlan]()

  // From all other events
  var otherEvents: ArrayBuffer[SparkListenerEvent] = ArrayBuffer[SparkListenerEvent]()

  // Generated warnings by predefined checks for this Application
  var warnings: ArrayBuffer[String] = ArrayBuffer[String]()

  // All the metrics column names in Task Metrics with the aggregation type
  val taskMetricsColumns: scala.collection.mutable.SortedMap[String, String]
  = scala.collection.mutable.SortedMap(
    "duration" -> "all",
    "gettingResultTime" -> "sum",
    "executorDeserializeTime" -> "sum",
    "executorDeserializeCPUTime" -> "sum",
    "executorRunTime" -> "sum",
    "executorCPUTime" -> "sum",
    "peakExecutionMemory" -> "max",
    "resultSize" -> "max",
    "jvmGCTime" -> "sum",
    "resultSerializationTime" -> "sum",
    "memoryBytesSpilled" -> "sum",
    "diskBytesSpilled" -> "sum",
    "sr_remoteBlocksFetched" -> "sum",
    "sr_localBlocksFetched" -> "sum",
    "sr_fetchWaitTime" -> "sum",
    "sr_remoteBytesRead" -> "sum",
    "sr_remoteBytesReadToDisk" -> "sum",
    "sr_localBytesRead" -> "sum",
    "sr_totalBytesRead" -> "sum",
    "sw_bytesWritten" -> "sum",
    "sw_writeTime" -> "sum",
    "sw_recordsWritten" -> "sum",
    "input_bytesRead" -> "sum",
    "input_recordsRead" -> "sum",
    "output_bytesWritten" -> "sum",
    "output_recordsWritten" -> "sum"
  )

  // By looping through SQL Plan nodes to find out the problematic SQLs. Currently we define
  // problematic SQL's as those which have RowToColumnar, ColumnarToRow transitions and Lambda's in
  // the Spark plan.
  var problematicSQL: ArrayBuffer[ProblematicSQLCase] = ArrayBuffer[ProblematicSQLCase]()

  // SQL containing any Dataset operation
  var datasetSQL: ArrayBuffer[DatasetSQLCase] = ArrayBuffer[DatasetSQLCase]()

  // Process all events
  processEvents()
  if (forQualification) {
    // Process the plan for qualification
    processSQLPlanForQualification
  } else {
    // Process all properties after all events are processed
    processAllProperties()
    // Process SQL Plan Metrics after all events are processed
    processSQLPlanMetrics()
  }
  // Create Spark DataFrame(s) based on ArrayBuffer(s)
  arraybufferToDF()

  /**
   * Functions to process all the events
   */
  def processEvents(): Unit = {
    logInfo("Parsing Event Log File: " + eventlog.toString)

    val fs = FileSystem.get(eventlog.toUri,new Configuration())
    var totalNumEvents = 0

    val eventsProcessor = new EventsProcessor(forQualification)
    Utils.tryWithResource(EventLogFileReader.openEventLog(eventlog, fs)) { in =>
      val lines = Source.fromInputStream(in)(Codec.UTF8).getLines().toList
      totalNumEvents = lines.size
      lines.foreach { line =>
        try {
          val event = JsonProtocol.sparkEventFromJson(parse(line))
          eventsProcessor.processAnyEvent(this, event)
          logDebug(line)
        }
        catch {
          case e: ClassNotFoundException =>
            logWarning(s"ClassNotFoundException: ${e.getMessage}")
        }
      }
    }
    logInfo("Total number of events parsed: " + totalNumEvents)
  }

  /**
   * Functions to process all properties after all events are processed
   */
  def processAllProperties(): Unit = {
    for ((k, v) <- sparkProperties) {
      val thisProperty = PropertiesCase("spark", k, v)
      allProperties += thisProperty
    }
    for ((k, v) <- hadoopProperties) {
      val thisProperty = PropertiesCase("hadoop", k, v)
      allProperties += thisProperty
    }
    for ((k, v) <- systemProperties) {
      val thisProperty = PropertiesCase("system", k, v)
      allProperties += thisProperty
    }
    for ((k, v) <- jvmInfo) {
      val thisProperty = PropertiesCase("jvm", k, v)
      allProperties += thisProperty
    }
    for ((k, v) <- classpathEntries) {
      val thisProperty = PropertiesCase("classpath", k, v)
      allProperties += thisProperty
    }
  }

  def processSQLPlanForQualification(): Unit ={
    for ((sqlID, planInfo) <- sqlPlan){
      val planGraph = SparkPlanGraph(planInfo)
      // SQLPlanMetric is a case Class of
      // (name: String,accumulatorId: Long,metricType: String)
      val allnodes = planGraph.allNodes
      for (node <- allnodes){
        // Firstly identify problematic SQLs if there is any
        if (isDataSetPlan(node.desc)) {
          datasetSQL += DatasetSQLCase(sqlID)
        }
        val issues = findPotentialIssues(node.desc)
        if (issues.nonEmpty) {
          problematicSQL += ProblematicSQLCase(sqlID, issues)
        }
      }
    }
  }

  /**
   * Function to process SQL Plan Metrics after all events are processed
   */
  def processSQLPlanMetrics(): Unit ={
    for ((sqlID, planInfo) <- sqlPlan){
      val planGraph = SparkPlanGraph(planInfo)
      // SQLPlanMetric is a case Class of
      // (name: String,accumulatorId: Long,metricType: String)
      val allnodes = planGraph.allNodes
      for (node <- allnodes) {
        if (isDataSetPlan(node.desc)) {
          datasetSQL += DatasetSQLCase(sqlID)
          if (gpuMode) {
            val thisPlan = UnsupportedSQLPlan(sqlID, node.id, node.name, node.desc)
            unsupportedSQLplan += thisPlan
          }
        }
        // Then process SQL plan metric type
        for (metric <- node.metrics){
          val thisMetric = SQLPlanMetricsCase(sqlID,metric.name,
            metric.accumulatorId,metric.metricType)
          sqlPlanMetrics += thisMetric
          val thisNode = PlanNodeAccumCase(sqlID, node.id,
            node.name, node.desc, metric.accumulatorId)
          planNodeAccum += thisNode
        }
      }
    }
    if (this.sqlPlanMetricsAdaptive.nonEmpty){
      logInfo(s"Merging ${sqlPlanMetricsAdaptive.size} SQL Metrics(Adaptive) for appID=$appId")
      sqlPlanMetrics = sqlPlanMetrics.union(sqlPlanMetricsAdaptive).distinct
    }
  }

  /**
   * Functions to convert ArrayBuffer to DataFrame
   * and then create a view for each of them
   */
  def arraybufferToDF(): Unit = {
    import sparkSession.implicits._

    // For appDF
    if (this.appStart.nonEmpty) {
      val appStartNew: ArrayBuffer[ApplicationCase] = ArrayBuffer[ApplicationCase]()
      for (res <- this.appStart) {

        val estimatedResult =
          this.appEndTime match {
            case Some(t) => this.appEndTime
            case None =>
              if (this.sqlEndTime.isEmpty && this.jobEndTime.isEmpty) {
                None
              } else {
                logWarning("Application End Time is unknown, estimating based on" +
                  " job and sql end times!")
                // estimate the app end with job or sql end times
                val sqlEndTime = if (this.sqlEndTime.isEmpty) 0L else this.sqlEndTime.values.max
                val jobEndTime = if (this.jobEndTime.isEmpty) 0L else this.jobEndTime.values.max
                val maxEndTime = math.max(sqlEndTime, jobEndTime)
                if (maxEndTime == 0) None else Some(maxEndTime)
              }
          }

        val durationResult = ProfileUtils.OptionLongMinusLong(estimatedResult, res.startTime)
        val durationString = durationResult match {
          case Some(i) => UIUtils.formatDuration(i.toLong)
          case None => ""
        }

        val newApp = res.copy(endTime = this.appEndTime, duration = durationResult,
          durationStr = durationString, sparkVersion = this.sparkVersion,
          gpuMode = this.gpuMode, endDurationEstimated = this.appEndTime.isEmpty)
        appStartNew += newApp
      }
      this.allDataFrames += (s"appDF_$index" -> appStartNew.toDF)
    }

    // For sqlDF
    if (sqlStart.nonEmpty) {
      val sqlStartNew: ArrayBuffer[SQLExecutionCase] = ArrayBuffer[SQLExecutionCase]()
      for (res <- sqlStart) {
        val thisEndTime = sqlEndTime.get(res.sqlID)
        val durationResult = ProfileUtils.OptionLongMinusLong(thisEndTime, res.startTime)
        val durationString = durationResult match {
          case Some(i) => UIUtils.formatDuration(i)
          case None => ""
        }
        val (containsDataset, sqlQDuration) = if (datasetSQL.exists(_.sqlID == res.sqlID)) {
          (true, Some(0L))
        } else {
          (false, durationResult)
        }
        val potProbs = problematicSQL.filter { p =>
          p.sqlID == res.sqlID && p.reason.nonEmpty
        }.map(_.reason).mkString(",")
        val finalPotProbs = if (potProbs.isEmpty) {
          null
        } else {
          potProbs
        }
        val sqlExecutionNew = res.copy(endTime = thisEndTime,
          duration = durationResult,
          durationStr = durationString,
          sqlQualDuration = sqlQDuration,
          hasDataset = containsDataset,
          problematic = finalPotProbs
        )
        sqlStartNew += sqlExecutionNew
      }
      allDataFrames += (s"sqlDF_$index" -> sqlStartNew.toDF)
    } else {
      logInfo("No SQL Execution Found. Skipping generating SQL Execution DataFrame.")
    }

    // For jobDF
    if (jobStart.nonEmpty) {
      val jobStartNew: ArrayBuffer[JobCase] = ArrayBuffer[JobCase]()
      for (res <- jobStart) {
        val thisEndTime = jobEndTime.get(res.jobID)
        val durationResult = ProfileUtils.OptionLongMinusLong(thisEndTime, res.startTime)
        val durationString = durationResult match {
          case Some(i) => UIUtils.formatDuration(i)
          case None => ""
        }

        val jobNew = res.copy(endTime = thisEndTime,
          duration = durationResult,
          durationStr = durationString,
          jobResult = jobEndResult.get(res.jobID),
          failedReason = jobFailedReason.get(res.jobID)
        )
        jobStartNew += jobNew
      }
      allDataFrames += (s"jobDF_$index" -> jobStartNew.toDF)
    }

    // For stageDF
    if (stageSubmitted.nonEmpty) {
      val stageSubmittedNew: ArrayBuffer[StageCase] = ArrayBuffer[StageCase]()
      for (res <- stageSubmitted) {
        val thisEndTime = stageCompletionTime.getOrElse(res.stageId, None)
        val thisFailureReason = stageFailureReason.getOrElse(res.stageId, None)

        val durationResult =
          ProfileUtils.optionLongMinusOptionLong(thisEndTime, res.submissionTime)
        val durationString = durationResult match {
          case Some(i) => UIUtils.formatDuration(i)
          case None => ""
        }

        // only for qualification set the runtime and cputime
        // could expand later for profiling
        val stageAndAttempt = s"${res.stageId}:${res.attemptId}"
        val stageTaskExecSum = stageTaskQualificationEnd.get(stageAndAttempt)
        val runTime = stageTaskExecSum.map(_.executorRunTime).getOrElse(0L)
        val cpuTime = stageTaskExecSum.map(_.executorCPUTime).getOrElse(0L)

        val stageNew = res.copy(completionTime = thisEndTime,
          failureReason = thisFailureReason,
          duration = durationResult,
          durationStr = durationString,
          executorRunTimeSum = runTime,
          executorCPUTimeSum = cpuTime)
        stageSubmittedNew += stageNew
      }
      allDataFrames += (s"stageDF_$index" -> stageSubmittedNew.toDF)
    }

    // For taskDF
    if (!forQualification) {
      if (taskEnd.nonEmpty) {
        allDataFrames += (s"taskDF_$index" -> taskEnd.toDF)
      }
    }

    // For sqlMetricsDF
    if (sqlPlanMetrics.nonEmpty) {
      logInfo(s"Total ${sqlPlanMetrics.size} SQL Metrics for appID=$appId")
      allDataFrames += (s"sqlMetricsDF_$index" -> sqlPlanMetrics.toDF)
    } else {
      logInfo("No SQL Metrics Found. Skipping generating SQL Metrics DataFrame.")
    }

    if (!forQualification) {
      // For resourceProfilesDF
      if (this.resourceProfiles.nonEmpty) {
        this.allDataFrames += (s"resourceProfilesDF_$index" -> this.resourceProfiles.toDF)
      } else {
        logWarning("resourceProfiles is empty!")
      }

      // For blockManagersDF
      if (this.blockManagers.nonEmpty) {
        this.allDataFrames += (s"blockManagersDF_$index" -> this.blockManagers.toDF)
      } else {
        logWarning("blockManagers is empty!")
      }

      // For blockManagersRemovedDF
      if (this.blockManagersRemoved.nonEmpty) {
        this.allDataFrames += (s"blockManagersRemovedDF_$index" -> this.blockManagersRemoved.toDF)
        this.blockManagersRemoved.clear()
      } else {
        logDebug("blockManagersRemoved is empty!")
      }

      // For propertiesDF
      if (this.allProperties.nonEmpty) {
        this.allDataFrames += (s"propertiesDF_$index" -> this.allProperties.toDF)
      }

      // For executorsDF
      if (this.executors.nonEmpty) {
        this.allDataFrames += (s"executorsDF_$index" -> this.executors.toDF)
      }

      // For executorsRemovedDF
      if (this.executorsRemoved.nonEmpty) {
        this.allDataFrames += (s"executorsRemovedDF_$index" -> this.executorsRemoved.toDF)
      } else {
        logDebug("executorsRemoved is empty!")
      }

      // For driverAccumDF
      allDataFrames += (s"driverAccumDF_$index" -> driverAccum.toDF)
      if (driverAccum.nonEmpty) {
        logInfo(s"Total ${driverAccum.size} driver accums for appID=$appId")
      } else {
        logInfo("No Driver accum Found. Create an empty driver accum DataFrame.")
      }

      // For taskStageAccumDF
      allDataFrames += (s"taskStageAccumDF_$index" -> taskStageAccum.toDF)
      if (taskStageAccum.nonEmpty) {
        logInfo(s"Total ${taskStageAccum.size} task&stage accums for appID=$appId")
      } else {
        logInfo("No task&stage accums Found.Create an empty task&stage accum DataFrame.")
      }

      // For planNodeAccumDF
      allDataFrames += (s"planNodeAccumDF_$index" -> planNodeAccum.toDF)
      if (planNodeAccum.nonEmpty) {
        logInfo(s"Total ${planNodeAccum.size} Plan node accums for appID=$appId")
      } else {
        logInfo("No Plan node accums Found. Create an empty Plan node accums DataFrame.")
      }

      // For unsupportedSQLPlanDF
      allDataFrames += (s"unsupportedSQLplan_$index" -> unsupportedSQLplan.toDF)
      if (unsupportedSQLplan.nonEmpty) {
        logInfo(s"Total ${unsupportedSQLplan.size} Unsupported ops for appID=$appId")
      } else {
        logInfo("No unSupportedSQLPlan node accums Found. " +
            "Create an empty node accums DataFrame.")
      }
    }

    for ((name, df) <- this.allDataFrames) {
      df.createOrReplaceTempView(name)
    }
  }

  // Function to drop all temp views of this application.
  def dropAllTempViews(): Unit ={
    for ((name,_) <- this.allDataFrames) {
      sparkSession.catalog.dropTempView(name)
    }
  }

  // Function to run a query and optionally print the result to the file.
  def runQuery(
      query: String,
      vertical: Boolean = false,
      fileWriter: Option[ToolTextFileWriter] = None,
      messageHeader: String = ""): DataFrame = {
    logDebug("Running:" + query)
    val df = sparkSession.sql(query)
    fileWriter.foreach { writer =>
      writer.write(messageHeader)
      writer.write(df.showString(numOutputRows, 0, vertical))
    }
    df
  }

  // Function to return a DataFrame based on query text
  def queryToDF(query: String): DataFrame = {
    logDebug("Creating a DataFrame based on query : \n" + query)
    sparkSession.sql(query)
  }

  // Function to generate a query for printing Application information
  def generateAppInfo: String =
    s"""select $index as appIndex, appId, startTime, endTime, duration,
       |durationStr, sparkVersion, gpuMode
       |from appDF_$index
       |""".stripMargin

  // Function to generate a query for printing Executors information
  def generateExecutorInfo: String = {
    // If both blockManagersDF and resourceProfilesDF exist:
    if (allDataFrames.contains(s"blockManagersDF_$index") &&
        allDataFrames.contains(s"resourceProfilesDF_$index")) {

      s"""select $index as appIndex, e.executorID, e.totalCores,
         |b.maxMem, b.maxOnHeapMem,b.maxOffHeapMem,
         |r.exec_cpu, r.exec_mem, r.exec_gpu, r.exec_offheap, r.task_cpu, r.task_gpu
         |from executorsDF_$index e, blockManagersDF_$index b, resourceProfilesDF_$index r
         |where e.executorID=b.executorID
         |and e.resourceProfileId=r.id
         |""".stripMargin
    } else if (allDataFrames.contains(s"blockManagersDF_$index") &&
        !allDataFrames.contains(s"resourceProfilesDF_$index")) {

      s"""select $index as appIndex,e.executorID, e.totalCores,
         |b.maxMem, b.maxOnHeapMem,b.maxOffHeapMem,
         |null as exec_cpu, null as exec_mem, null as exec_gpu,
         |null as exec_offheap, null as task_cpu, null as task_gpu
         |from executorsDF_$index e, blockManagersDF_$index b
         |where e.executorID=b.executorID
         |""".stripMargin
    } else if (!allDataFrames.contains(s"blockManagersDF_$index") &&
        allDataFrames.contains(s"resourceProfilesDF_$index")) {
      s"""select $index as appIndex,e.executorID, e.totalCores,
         |null as maxMem, null as maxOnHeapMem, null as maxOffHeapMem,
         |r.exec_cpu, r.exec_mem, r.exec_gpu, r.exec_offheap, r.task_cpu, r.task_gpu
         |from executorsDF_$index e, resourceProfilesDF_$index r
         |where e.resourceProfileId=r.id
         |""".stripMargin
    } else {
      s"""select $index as appIndex,executorID, totalCores
         |null as maxMem, null as maxOnHeapMem, null as maxOffHeapMem,
         |null as maxMem, null as maxOnHeapMem, null as maxOffHeapMem,
         |null as exec_cpu, null as exec_mem, null as exec_gpu,
         |null as exec_offheap, null as task_cpu, null as task_gpu
         |from executorsDF_$index
         |""".stripMargin
    }
  }

  // Function to generate a query for printing Rapids related Spark properties
  def generateRapidsProperties: String =
    s"""select key,value as value_app$index
       |from propertiesDF_$index
       |where source ='spark'
       |and key like 'spark.rapids%'
       |""".stripMargin

  // Function to generate the SQL string for aggregating task metrics columns.
  def generateAggSQLString: String = {
    var resultString = ""

    // Function to concat the Aggregation column string
    // eg: ",\n round(sum(column),1) as column_sum"
    def concatAggCol(col: String, aggType: String): Unit = {
      val colString = "round(" + aggType + "(t." + col + ")" + ",1)"
      resultString += ",\n" + colString + " as " + col + "_" + aggType
    }

    for ((col, aggType) <- this.taskMetricsColumns) {
      // If aggType=all, it means all 4 aggregation: sum, max, min, avg.
      if (aggType == "all") {
        concatAggCol(col, "sum")
        concatAggCol(col, "max")
        concatAggCol(col, "min")
        concatAggCol(col, "avg")
      }
      else {
        concatAggCol(col, aggType)
      }
    }
    resultString
  }

  // Function to generate a query for job level Task Metrics aggregation
  def jobtoStagesSQL: String = {
    s"""select $index as appIndex, j.jobID,
       |j.stageIds, j.sqlID
       |from jobDF_$index j
       |""".stripMargin
  }

  // Function to generate a query for job level Task Metrics aggregation
  def jobMetricsAggregationSQL: String = {
    s"""select $index as appIndex, concat('job_',j.jobID) as ID,
       |count(*) as numTasks, max(j.duration) as Duration
       |$generateAggSQLString
       |from taskDF_$index t, stageDF_$index s, jobDF_$index j
       |where t.stageId=s.stageId
       |and array_contains(j.stageIds, s.stageId)
       |group by j.jobID
       |""".stripMargin
  }

  // Function to generate a query for stage level Task Metrics aggregation
  def stageMetricsAggregationSQL: String = {
    s"""select $index as appIndex, concat('stage_',s.stageId) as ID,
       |count(*) as numTasks, max(s.duration) as Duration
       |$generateAggSQLString
       |from taskDF_$index t, stageDF_$index s
       |where t.stageId=s.stageId
       |group by s.stageId
       |""".stripMargin
  }

  // Function to generate a query for job+stage level Task Metrics aggregation
  def jobAndStageMetricsAggregationSQL: String = {
    jobMetricsAggregationSQL + " union " + stageMetricsAggregationSQL
  }

  // Function to generate a query for SQL level Task Metrics aggregation
  def sqlMetricsAggregationSQL: String = {
    s"""select $index as appIndex, '$appId' as appID,
       |sq.sqlID, sq.description,
       |count(*) as numTasks, max(sq.duration) as Duration,
       |sum(executorCPUTime) as executorCPUTime,
       |sum(executorRunTime) as executorRunTime,
       |round(sum(executorCPUTime)/sum(executorRunTime)*100,2) executorCPURatio
       |$generateAggSQLString
       |from taskDF_$index t, stageDF_$index s,
       |jobDF_$index j, sqlDF_$index sq
       |where t.stageId=s.stageId
       |and array_contains(j.stageIds, s.stageId)
       |and sq.sqlID=j.sqlID
       |group by sq.sqlID,sq.description
       |""".stripMargin
  }

  // Function to generate a query for getting the executor CPU time and run time
  // specifically for how we aggregate for qualification
  def sqlMetricsAggregationSQLQual: String = {
    s"""select $index as appIndex, '$appId' as appID,
       |sq.sqlID, sq.description,
       |sum(executorCPUTimeSum) as executorCPUTime,
       |sum(executorRunTimeSum) as executorRunTime
       |from stageDF_$index s,
       |jobDF_$index j, sqlDF_$index sq
       |where array_contains(j.stageIds, s.stageId)
       |and sq.sqlID=j.sqlID and sq.sqlID not in ($sqlIdsForUnsuccessfulJobs)
       |group by sq.sqlID,sq.description
       |""".stripMargin
  }

  // Function to generate a query for printing SQL metrics(accumulables)
  def generateSQLAccums: String = {
    s"""with allaccums as
       |(
       |select s.sqlID, p.nodeID, p.nodeName,
       |s.accumulatorId, s.name, d.value, s.metricType
       |from sqlMetricsDF_$index s, driverAccumDF_$index d,
       |planNodeAccumDF_$index p
       |where s.sqlID = d.sqlID and s.accumulatorId=d.accumulatorId
       |and s.sqlID=p.sqlID and s.accumulatorId=p.accumulatorId
       |union
       |select s.sqlID, p.nodeID, p.nodeName,
       |s.accumulatorId, s.name, t.value, s.metricType
       |from jobDF_$index j, sqlDF_$index sq ,
       |taskStageAccumDF_$index t, sqlMetricsDF_$index s,
       |planNodeAccumDF_$index p
       |where array_contains(j.stageIds, t.stageId)
       |and sq.sqlID=j.sqlID
       |and s.sqlID = sq.sqlID
       |and s.accumulatorId=t.accumulatorId
       |and s.sqlID=p.sqlID and s.accumulatorId=p.accumulatorId
       |)
       |select sqlID, nodeID, nodeName,
       |accumulatorId, name, max(value) as max_value, metricType
       |from allaccums
       |group by sqlID, nodeID, nodeName, accumulatorId, name, metricType
       |order by sqlID, nodeID, nodeName, accumulatorId, name, metricType
       |""".stripMargin
  }

  def getFailedTasks: String = {
    s"""select stageId, stageAttemptId, taskId, attempt,
       |substr(endReason, 1, 100) as endReason_first100char
       |from taskDF_$index
       |where successful = false
       |order by stageId, stageAttemptId, taskId, attempt
       |""".stripMargin
  }

  def getFailedStages: String = {
    s"""select stageId, attemptId, name, numTasks,
       |substr(failureReason, 1, 100) as failureReason_first100char
       |from stageDF_$index
       |where failureReason is not null
       |order by stageId, attemptId
       |""".stripMargin
  }

  def getFailedJobs: String = {
    s"""select jobID, jobResult,
       |substr(failedReason, 1, 100) as failedReason_first100char
       |from jobDF_$index
       |where jobResult <> 'JobSucceeded'
       |order by jobID
       |""".stripMargin
  }

  def getblockManagersRemoved: String = {
    s"""select executorID, time
       |from blockManagersRemovedDF_$index
       |order by cast(executorID as long)
       |""".stripMargin
  }

  def getExecutorsRemoved: String = {
    s"""select executorID, time,
       |substr(reason, 1, 100) reason_first100char
       |from executorsRemovedDF_$index
       |order by cast(executorID as long)
       |""".stripMargin
  }

  def unsupportedSQLPlan: String = {
    s"""select sqlID, nodeID, nodeName,
       |substr(nodeDesc, 1, 100) nodeDesc_first100char
       |from unsupportedSQLplan_$index""".stripMargin
  }

  def sqlIdsForUnsuccessfulJobs: String = {
    s"""select
       |sqlID
       |from jobDF_$index j
       |where j.jobResult != "JobSucceeded" or j.jobResult is null
       |""".stripMargin
  }

  def qualificationDurationNoMetricsSQL: String = {
    s"""select
       |first(appName) as `App Name`,
       |'$appId' as `App ID`,
       |ROUND((sum(sqlQualDuration) * 100) / first(app.duration), 2) as Score,
       |concat_ws(",", collect_set(problematic)) as `Potential Problems`,
       |sum(sqlQualDuration) as `SQL Dataframe Duration`,
       |first(app.duration) as `App Duration`,
       |first(app.endDurationEstimated) as `App Duration Estimated`
       |from sqlDF_$index sq, appdf_$index app
       |where sq.sqlID not in ($sqlIdsForUnsuccessfulJobs)
       |""".stripMargin
  }

  // only include jobs that are marked as succeeded
  def qualificationDurationSQL: String = {
    s"""select
       |$index as appIndex,
       |'$appId' as appID,
       |app.appName,
       |sq.sqlID, sq.description,
       |sq.sqlQualDuration as dfDuration,
       |app.duration as appDuration,
       |app.endDurationEstimated as appEndDurationEstimated,
       |problematic as potentialProblems,
       |m.executorCPUTime,
       |m.executorRunTime
       |from sqlDF_$index sq, appdf_$index app
       |left join sqlAggMetricsDF m on $index = m.appIndex and sq.sqlID = m.sqlID
       |where sq.sqlID not in ($sqlIdsForUnsuccessfulJobs)
       |""".stripMargin
  }

  def qualificationDurationSumSQL: String = {
    s"""select first(appName) as `App Name`,
       |'$appId' as `App ID`,
       |ROUND((sum(dfDuration) * 100) / first(appDuration), 2) as Score,
       |concat_ws(",", collect_set(potentialProblems)) as `Potential Problems`,
       |sum(dfDuration) as `SQL Dataframe Duration`,
       |first(appDuration) as `App Duration`,
       |round(sum(executorCPUTime)/sum(executorRunTime)*100,2) as `Executor CPU Time Percent`,
       |first(appEndDurationEstimated) as `App Duration Estimated`
       |from (${qualificationDurationSQL.stripLineEnd})
       |""".stripMargin
  }

  def profilingDurationSQL: String = {
    s"""select
       |$index as appIndex,
       |'$appId' as `App ID`,
       |sq.sqlID,
       |sq.duration as `SQL Duration`,
       |sq.hasDataset as `Contains Dataset Op`,
       |app.duration as `App Duration`,
       |problematic as `Potential Problems`,
       |round(executorCPUTime/executorRunTime*100,2) as `Executor CPU Time Percent`
       |from sqlDF_$index sq, appdf_$index app
       |left join sqlAggMetricsDF m on $index = m.appIndex and sq.sqlID = m.sqlID
       |""".stripMargin
  }

  def isDataSetPlan(desc: String): Boolean = {
    desc match {
      case l if l.matches(".*\\$Lambda\\$.*") => true
      case a if a.endsWith(".apply") => true
      case _ => false
    }
  }

  def findPotentialIssues(desc: String): String =  {
    desc match {
      case u if u.matches(".*UDF.*") => "UDF"
      case _ => ""
    }
  }
}
