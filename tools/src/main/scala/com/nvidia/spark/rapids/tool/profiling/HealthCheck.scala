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

package com.nvidia.spark.rapids.tool.profiling

import com.nvidia.spark.rapids.tool.ToolTextFileWriter
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.sql.rapids.tool.profiling.ApplicationInfo

/**
 * HealthCheck defined health check rules
 */
class HealthCheck(apps: ArrayBuffer[ApplicationInfo], textFileWriter: ToolTextFileWriter) {

  require(apps.nonEmpty)

  // Function to list all failed tasks , stages and jobs.
  def listFailedJobsStagesTasks(): Unit = {
    for (app <- apps) {
      if (app.allDataFrames.contains(s"taskDF_${app.index}")) {
        // Look for failed tasks.
        val tasksMessageHeader = s"Failed tasks:\n"
        app.runQuery(query = app.getFailedTasks, fileWriter = Some(textFileWriter),
          messageHeader = tasksMessageHeader)
      }
      if (app.allDataFrames.contains(s"stageDF_${app.index}")) {
        // Look for failed stages.
        val stagesMessageHeader = s"Failed stages:\n"
        app.runQuery(query = app.getFailedStages, fileWriter = Some(textFileWriter),
          messageHeader = stagesMessageHeader)
      }
      if (app.allDataFrames.contains(s"jobDF_${app.index}")) {
        // Look for failed jobs.
        val jobsMessageHeader = s"Failed jobs:\n"
        app.runQuery(query = app.getFailedJobs, fileWriter = Some(textFileWriter),
          messageHeader = jobsMessageHeader)
      }
    }
  }

  //Function to list all SparkListenerBlockManagerRemoved
  def listRemovedBlockManager(): Unit = {
    for (app <- apps) {
      if (app.allDataFrames.contains(s"blockManagersRemovedDF_${app.index}")) {
        val blockManagersMessageHeader =
          s"Removed BlockManager(s):\n"
        app.runQuery(query = app.getblockManagersRemoved, fileWriter = Some(textFileWriter),
          messageHeader = blockManagersMessageHeader)
      }
    }
  }

  //Function to list all SparkListenerExecutorRemoved
  def listRemovedExecutors(): Unit = {
    for (app <- apps) {
      if (app.allDataFrames.contains(s"executorsRemovedDF_${app.index}")) {
        val executorsRemovedMessageHeader =
          s"Removed Executors(s):\n"
        app.runQuery(query = app.getExecutorsRemoved, fileWriter = Some(textFileWriter),
          messageHeader = executorsRemovedMessageHeader)
      }
    }
  }

  //Function to list all *possible* not-supported plan nodes if GPU Mode=on
  def listPossibleUnsupportedSQLPlan(): Unit = {
    textFileWriter.write("\nSQL Plan HealthCheck\n")
    for (app <- apps) {
      if (app.allDataFrames.contains(s"sqlDF_${app.index}") && app.sqlPlan.nonEmpty) {
        app.runQuery(query = app.unsupportedSQLPlan, fileWriter = Some(textFileWriter),
          messageHeader = s"\nUnsupported SQL Plan\n")
      }
    }
  }
}
