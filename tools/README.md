# Spark Qualification and Profiling tools

The qualification tool is used to look at a set of applications to determine if the RAPIDS Accelerator for Apache Spark 
might be a good fit for those applications.

The profiling tool generates information which can be used for debugging and profiling applications.
Information such as Spark version, executor information, properties and so on. This runs on either CPU or
GPU generated event logs.

(The code is based on Apache Spark 3.1.1 source code, and tested using Spark 3.0.x and 3.1.1 event logs)

## Prerequisites
- Spark 3.0.1 or newer installed
- Java 8 or above
- Complete Spark event log(s) from Spark 3.0 or above version
  (Support compressed event logs with `.lz4`, `.lzf`, `.snappy` and `.zstd` suffixes. 
  Spark event logs can be downloaded from Spark UI using a "Download" button on the right side, 
  or can be found in the location specified by `spark.eventLog.dir`.)

Optional:
- maven installed 
  (only if you want to compile the jar yourself)
- hadoop-aws-<version>.jar and aws-java-sdk-<version>.jar 
  (only if any input event log is from S3)
  
## Download the jar or compile it
You do not need to compile the jar yourself because you can download it from maven repository directly.

Here are 2 options:
1. Download the jar file from [maven repository](https://repo1.maven.org/maven2/com/nvidia/rapids-4-spark-tools_2.12/21.06.0/)

2. Compile the jar from github repo
```bash
git clone https://github.com/NVIDIA/spark-rapids.git
cd spark-rapids
mvn -pl .,tools clean verify -DskipTests
```
The jar is generated in below directory :

`./tools/target/rapids-4-spark-tools_2.12-<version>.jar`

If any input is a S3 file path or directory path, 2 extra steps are needed to access S3 in Spark:
1. Download the matched jars based on the Hadoop version:
   - `hadoop-aws-<version>.jar`
   - `aws-java-sdk-<version>.jar`
     
Take Hadoop 2.7.4 for example, we can download and include below jars in the '--jars' option to spark-shell or spark-submit:
[hadoop-aws-2.7.4.jar](https://repo.maven.apache.org/maven2/org/apache/hadoop/hadoop-aws/2.7.4/hadoop-aws-2.7.4.jar) and 
[aws-java-sdk-1.7.4.jar](https://repo.maven.apache.org/maven2/com/amazonaws/aws-java-sdk/1.7.4/aws-java-sdk-1.7.4.jar)

2. In $SPARK_HOME/conf, create `hdfs-site.xml` with below AWS S3 keys inside:
```xml
<?xml version="1.0"?>
<configuration>
<property>
  <name>fs.s3a.access.key</name>
  <value>xxx</value>
</property>
<property>
  <name>fs.s3a.secret.key</name>
  <value>xxx</value>
</property>
</configuration>
```
Please refer to this [doc](https://hadoop.apache.org/docs/current/hadoop-aws/tools/hadoop-aws/index.html) on 
more options about integrating hadoop-aws module with S3.


## Filter input event logs
Both of the qualification tool and profiling tool have this function which is to filter event logs.
Here are 2 filter options:
1. N newest or oldest event logs. (-f option)

This is based on timestamp of the event log files.

2. Event log file names match the string. (-m option)

This is based on the event log file name.

Below is an example of profiling tool:

Filter event logs to be processed. 10 newest file with filenames containing "local":
```bash
$SPARK_HOME/bin/spark-submit --class com.nvidia.spark.rapids.tool.profiling.ProfileMain \
rapids-4-spark-tools_2.12-<version>.jar \
-m "local" -f "10-newest" \
/directory/with/eventlogs/
```


## Qualification Tool

### Functions

The qualification tool is used to look at a set of applications to determine if the RAPIDS Accelerator for Apache Spark
might be a good fit for those applications. The tool works by processing the CPU generated event logs from Spark.

Currently it does this by looking at the amount of time spent doing SQL Dataframe
operations vs the entire application time: `(sum(SQL Dataframe Duration) / (application-duration))`.
The more time spent doing SQL Dataframe operations the higher the score is
and the more likely the plugin will be able to help accelerate that application.
Note that the application time is from application start to application end so if you are using an interactive
shell where there is nothing running from a while, this time will include that which might skew the score.

Each application(event log) could have multiple SQL queries. If a SQL's plan has Dataset API inside such as keyword
 `$Lambda` or `.apply`, that SQL query is categorized as a DataSet SQL query, otherwise it is a Dataframe SQL query.

Note: the duration(s) reported are in milli-seconds.

It can also print out any potential problems it finds in a separate column, which is not included in the score. This
currently only includes some UDFs. The tool won't catch all UDFs, and some of the UDFs can be handled with additional steps. 
Please refer to [supported_ops.md](../docs/supported_ops.md) for more details on UDF.

The output also contains a `Executor CPU Time Percent` column that is not included in the score. This is an estimate
at how much time the tasks spent doing processing on the CPU vs waiting on IO. This is not always a good indicator
because sometimes you may be doing IO that is encrypted and the CPU has to do work to decrypt it, so the environment
you are running on needs to be taken into account.

The last column `App Duration Estimated` is used to indicate if we had to estimate the application duration. If we
had to estimate it, it means the event log was missing the application finished event so we will use the last job
or sql execution time we find as the end time used to calculate the duration.

Note that SQL queries that contain failed jobs are not included.

Sample output in csv:
```
App Name,App ID,Score,Potential Problems,SQL Dataframe Duration,App Duration,Executor CPU Time Percent,App Duration Estimated
Spark shell,app-20210507105707-0001,78.03,"",810923,1039276,32.03,false
Spark shell,app-20210507103057-0000,75.87,"",316622,417307,64.07,false
```

Sample output in text:
```
+-----------+-----------------------+-----+------------------+----------------------+------------+-------------------------+----------------------+
|App Name   |App ID                 |Score|Potential Problems|SQL Dataframe Duration|App Duration|Executor CPU Time Percent|App Duration Estimated|
+-----------+-----------------------+-----+------------------+----------------------+------------+-------------------------+----------------------+
|Spark shell|app-20210507105707-0001|78.03|                  |810923                |1039276     |32.03                    |false                 |
|Spark shell|app-20210507103057-0000|75.87|                  |316622                |417307      |64.07                    |false                 |
+-----------+-----------------------+-----+------------------+----------------------+------------+-------------------------+----------------------+
```

### How to use this tool
This tool parses the Spark CPU event log(s) and creates an output report.
Acceptable input event log paths are files or directories containing spark events logs
in the local filesystem, HDFS, S3 or mixed.

### Use from spark-shell
1. Include `rapids-4-spark-tools_2.12-<version>.jar` in the '--jars' option to spark-shell or spark-submit
2. Starting spark-shell:
```bash
$SPARK_HOME/bin/spark-shell --driver-memory 5g --jars ~/rapids-4-spark-tools_2.12-<version>.jar
```

For multiple event logs:
```bash
com.nvidia.spark.rapids.tool.qualification.QualificationMain.main(Array("/path/to/eventlog1", "/path/to/eventlog2"))
```

### Use from spark-submit
```bash
$SPARK_HOME/bin/spark-submit --driver-memory 5g --class com.nvidia.spark.rapids.tool.qualification.QualificationMain \
rapids-4-spark-tools_2.12-<version>.jar \
/path/to/eventlog1 /path/to/eventlog2 /directory/with/eventlogs
```

### Options (`--help` output)

  Note: `--help` should be before the trailing event logs.
```bash
$SPARK_HOME/bin/spark-submit \
--class com.nvidia.spark.rapids.tool.qualification.QualificationMain \
rapids-4-spark-tools_2.12-<version>.jar \
--help

For usage see below:

  -f, --filter-criteria  <arg>     Filter newest or oldest N event logs for processing.
                                   Supported formats are:
                                   To process 10 recent event logs: --filter-criteria "10-newest"
                                   To process 10 oldest event logs: --filter-criteria "10-oldest"
  -m, --match-event-logs  <arg>    Filter event logs filenames which contains the input string.
       
      --no-exec-cpu-percent        Do not include the executor CPU time percent.
  -n, --num-output-rows  <arg>     Number of output rows for each Application.
                                   Default is 1000.
  -o, --output-directory  <arg>    Base output directory. Default is current
                                   directory for the default filesystem. The
                                   final output will go into a subdirectory
                                   called rapids_4_spark_qualification_output.
                                   It will overwrite any existing directory with
                                   the same name.
      --output-format  <arg>       Output format, supports csv and text. Default
                                   is csv. text output format creates a file
                                   named rapids_4_spark_qualification.log while
                                   csv will create a file using the standard
                                   Spark naming convention.
  -h, --help                       Show help message

 trailing arguments:
  eventlog (required)   Event log filenames(space separated) or directories
                        containing event logs. eg: s3a://<BUCKET>/eventlog1
                        /path/to/eventlog2
```

### Output
By default this outputs a csv file under sub-directory `./rapids_4_spark_qualification_output/` that contains 
the processed applications. The output will go into your default filesystem, it supports local filesystem
or HDFS. The csv output can be used later to read back into Spark and do further processing or combined with other reports
as needed.

The output location can be changed using the `--output-directory` option. Default is current directory.

The output format can be changed using the `--output-format` option. Default is csv. The other option is text.
  
Note: We suggest you also save the output of the `spark-submit` or `spark-shell` to a log file for troubleshooting.
  
Run `--help` for more information.


## Profiling Tool

The profiling tool generates information which can be used for debugging and profiling applications.

### Functions
#### A. Collect Information or Compare Information(if more than 1 event logs are as input and option -c is specified)
- Application information
- Executors information
- Rapids related parameters
- Rapids Accelerator Jar and cuDF Jar
- Job, stage and SQL ID information (not in `compare` mode yet)
- SQL Plan Metrics
- Optionally : SQL Plan for each SQL query
- Optionally : Generates DOT graphs for each SQL query

For example, GPU run vs CPU run performance comparison or different runs with different parameters.

We can input multiple Spark event logs and this tool can compare environments, executors, Rapids related Spark parameters,

- Compare the durations/versions/gpuMode on or off:
```
### A. Compare Information Collected ###
Compare Application Information:

+--------+-----------------------+-------------+-------------+--------+-----------+------------+-------+
|appIndex|appId                  |startTime    |endTime      |duration|durationStr|sparkVersion|gpuMode|
+--------+-----------------------+-------------+-------------+--------+-----------+------------+-------+
|1       |app-20210329165943-0103|1617037182848|1617037490515|307667  |5.1 min    |3.0.1       |false  |
|2       |app-20210329170243-0018|1617037362324|1617038578035|1215711 |20 min     |3.0.1       |true   |
+--------+-----------------------+-------------+-------------+--------+-----------+------------+-------+
```

- Compare Executor information:
```
Compare Executor Information:
+--------+----------+----------+-----------+------------+-------------+--------+--------+--------+------------+--------+--------+
|appIndex|executorID|totalCores|maxMem     |maxOnHeapMem|maxOffHeapMem|exec_cpu|exec_mem|exec_gpu|exec_offheap|task_cpu|task_gpu|
+--------+----------+----------+-----------+------------+-------------+--------+--------+--------+------------+--------+--------+
|1       |0         |4         |13984648396|13984648396 |0            |null    |null    |null    |null        |null    |null    |
|1       |1         |4         |13984648396|13984648396 |0            |null    |null    |null    |null        |null    |null    |
```

- Compare Rapids related Spark properties side-by-side:
```
Compare Rapids Properties which are set explicitly:
+-------------------------------------------+----------+----------+
|key                                        |value_app1|value_app2|
+-------------------------------------------+----------+----------+
|spark.rapids.memory.pinnedPool.size        |null      |2g        |
|spark.rapids.sql.castFloatToDecimal.enabled|null      |true      |
|spark.rapids.sql.concurrentGpuTasks        |null      |2         |
|spark.rapids.sql.decimalType.enabled       |null      |true      |
|spark.rapids.sql.enabled                   |false     |true      |
|spark.rapids.sql.explain                   |null      |NOT_ON_GPU|
|spark.rapids.sql.hasNans                   |null      |FALSE     |
|spark.rapids.sql.incompatibleOps.enabled   |null      |true      |
|spark.rapids.sql.variableFloatAgg.enabled  |null      |TRUE      |
+-------------------------------------------+----------+----------+
```
 
- List rapids-4-spark and cuDF jars based on classpath: 
```
Rapids Accelerator Jar and cuDF Jar:
/path/rapids-4-spark_2.12-0.5.0.jar
/path/cudf-0.19-cuda10-2.jar
```

- Job, stage and SQL ID information(not in `compare` mode yet):
```
+--------+-----+------------+-----+
|appIndex|jobID|stageIds    |sqlID|
+--------+-----+------------+-----+
|1       |0    |[0]         |null |
|1       |1    |[1, 2, 3, 4]|0    |
+--------+-----+------------+-----+
```

- SQL Plan Metrics for Application for each SQL plan node in each SQL:

These are also called accumulables in Spark.
```
SQL Plan Metrics for Application:
+-----+------+-----------------------------------------------------------+-------------+-----------------------+-------------+----------+
|sqlID|nodeID|nodeName                                                   |accumulatorId|name                   |max_value    |metricType|
+-----+------+-----------------------------------------------------------+-------------+-----------------------+-------------+----------+
|0    |1     |GpuColumnarExchange                                        |111          |output rows            |1111111111   |sum       |
|0    |1     |GpuColumnarExchange                                        |112          |output columnar batches|222222       |sum       |
|0    |1     |GpuColumnarExchange                                        |113          |data size              |333333333333 |size      |
|0    |1     |GpuColumnarExchange                                        |114          |shuffle bytes written  |444444444444 |size      |
|0    |1     |GpuColumnarExchange                                        |115          |shuffle records written|555555       |sum       |
|0    |1     |GpuColumnarExchange                                        |116          |shuffle write time     |666666666666 |nsTiming  |
```

- Print SQL Plans (-p option):
Prints the SQL plan as a text string to a file prefixed with `planDescriptions-`.
For example if your application id is app-20210507103057-0000, then the
filename will be `planDescriptions-app-20210507103057-0000`

- Generate DOT graph for each SQL (-g option):
```
Generated DOT graphs for app app-20210507103057-0000 to /path/. in 17 second(s)
```
Once the DOT file is generated, you can install [graphviz](http://www.graphviz.org) to convert the DOT file 
as a graph in pdf format using below command:
```bash
dot -Tpdf ./app-20210507103057-0000-query-0/0.dot > app-20210507103057-0000.pdf
```
The pdf file has the SQL plan graph with metrics.


#### B. Analysis
- Job + Stage level aggregated task metrics
- SQL level aggregated task metrics
- SQL duration, application during, if it contains a Dataset operation, potential problems, executor CPU time percent
- Shuffle Skew Check: (When task's Shuffle Read Size > 3 * Avg Stage-level size)

Below we will aggregate the task level metrics at different levels to do some analysis such as detecting possible shuffle skew.

- Job + Stage level aggregated task metrics:
```
### B. Analysis ###

Job + Stage level aggregated task metrics:
+--------+-------+--------+--------+--------------------+------------+------------+------------+------------+-------------------+------------------------------+---------------------------+-------------------+---------------------+-------------------+---------------------+-------------+----------------------+-----------------------+-------------------------+-----------------------+---------------------------+--------------+--------------------+-------------------------+---------------------+--------------------------+----------------------+----------------------------+---------------------+-------------------+---------------------+----------------+
|appIndex|ID     |numTasks|Duration|diskBytesSpilled_sum|duration_sum|duration_max|duration_min|duration_avg|executorCPUTime_sum|executorDeserializeCPUTime_sum|executorDeserializeTime_sum|executorRunTime_sum|gettingResultTime_sum|input_bytesRead_sum|input_recordsRead_sum|jvmGCTime_sum|memoryBytesSpilled_sum|output_bytesWritten_sum|output_recordsWritten_sum|peakExecutionMemory_max|resultSerializationTime_sum|resultSize_max|sr_fetchWaitTime_sum|sr_localBlocksFetched_sum|sr_localBytesRead_sum|sr_remoteBlocksFetched_sum|sr_remoteBytesRead_sum|sr_remoteBytesReadToDisk_sum|sr_totalBytesRead_sum|sw_bytesWritten_sum|sw_recordsWritten_sum|sw_writeTime_sum|
+--------+-------+--------+--------+--------------------+------------+------------+------------+------------+-------------------+------------------------------+---------------------------+-------------------+---------------------+-------------------+---------------------+-------------+----------------------+-----------------------+-------------------------+-----------------------+---------------------------+--------------+--------------------+-------------------------+---------------------+--------------------------+----------------------+----------------------------+---------------------+-------------------+---------------------+----------------+
|1       |job_0  |3333    |222222  |0                   |11111111    |111111      |111         |1111.1      |6666666            |55555                         |55555                      |55555555           |0                    |222222222222       |22222222222          |111111       |0                     |0                      |0                        |222222222              |1                          |11111         |11111               |99999                    |22222222222          |2222221                   |222222222222          |0                           |222222222222         |222222222222       |5555555              |444444          |
```
  

- SQL level aggregated task metrics:
```
SQL level aggregated task metrics:
+--------+------------------------------+-----+--------------------+--------+--------+---------------+---------------+----------------+--------------------+------------+------------+------------+------------+-------------------+------------------------------+---------------------------+-------------------+---------------------+-------------------+---------------------+-------------+----------------------+-----------------------+-------------------------+-----------------------+---------------------------+--------------+--------------------+-------------------------+---------------------+--------------------------+----------------------+----------------------------+---------------------+-------------------+---------------------+----------------+
|appIndex|appID                         |sqlID|description         |numTasks|Duration|executorCPUTime|executorRunTime|executorCPURatio|diskBytesSpilled_sum|duration_sum|duration_max|duration_min|duration_avg|executorCPUTime_sum|executorDeserializeCPUTime_sum|executorDeserializeTime_sum|executorRunTime_sum|gettingResultTime_sum|input_bytesRead_sum|input_recordsRead_sum|jvmGCTime_sum|memoryBytesSpilled_sum|output_bytesWritten_sum|output_recordsWritten_sum|peakExecutionMemory_max|resultSerializationTime_sum|resultSize_max|sr_fetchWaitTime_sum|sr_localBlocksFetched_sum|sr_localBytesRead_sum|sr_remoteBlocksFetched_sum|sr_remoteBytesRead_sum|sr_remoteBytesReadToDisk_sum|sr_totalBytesRead_sum|sw_bytesWritten_sum|sw_recordsWritten_sum|sw_writeTime_sum|
+--------+------------------------------+-----+--------------------+--------+--------+---------------+---------------+----------------+--------------------+------------+------------+------------+------------+-------------------+------------------------------+---------------------------+-------------------+---------------------+-------------------+---------------------+-------------+----------------------+-----------------------+-------------------------+-----------------------+---------------------------+--------------+--------------------+-------------------------+---------------------+--------------------------+----------------------+----------------------------+---------------------+-------------------+---------------------+----------------+
|1       |application_1111111111111_0001|0    |show at <console>:11|1111    |222222  |6666666        |55555555       |55.55           |0                   |13333333    |111111      |999         |3333.3      |6666666            |55555                         |66666                      |11111111           |0                    |111111111111       |11111111111          |111111       |0                     |0                      |0                        |888888888              |8                          |11111         |11111               |99999                    |11111111111          |2222222                   |222222222222          |0                           |222222222222         |444444444444       |5555555              |444444          |
```

- SQL duration, application during, if it contains a Dataset operation, potential problems, executor CPU time percent: 
```
SQL Duration and Executor CPU Time Percent
+--------+------------------------------+-----+------------+-------------------+------------+------------------+-------------------------+
|appIndex|App ID                        |sqlID|SQL Duration|Contains Dataset Op|App Duration|Potential Problems|Executor CPU Time Percent|
+--------+------------------------------+-----+------------+-------------------+------------+------------------+-------------------------+
|1       |application_1603128018386_7759|0    |11042       |false              |119990      |null              |68.48                    |
+--------+------------------------------+-----+------------+-------------------+------------+------------------+-------------------------+
```

- Shuffle Skew Check: 
```
Shuffle Skew Check: (When task's Shuffle Read Size > 3 * Avg Stage-level size)
+--------+-------+--------------+------+-------+---------------+--------------+-----------------+----------------+----------------+----------+----------------------------------------------------------------------------------------------------+
|appIndex|stageId|stageAttemptId|taskId|attempt|taskDurationSec|avgDurationSec|taskShuffleReadMB|avgShuffleReadMB|taskPeakMemoryMB|successful|endReason_first100char                                                                              |
+--------+-------+--------------+------+-------+---------------+--------------+-----------------+----------------+----------------+----------+----------------------------------------------------------------------------------------------------+
|1       |2      |0             |2222  |0      |111.11         |7.7           |2222.22          |111.11          |0.01            |false     |ExceptionFailure(ai.rapids.cudf.CudfException,cuDF failure at: /dddd/xxxxxxx/ccccc/bbbbbbbbb/aaaaaaa|
|1       |2      |0             |2224  |1      |222.22         |8.8           |3333.33          |111.11          |0.01            |false     |ExceptionFailure(ai.rapids.cudf.CudfException,cuDF failure at: /dddd/xxxxxxx/ccccc/bbbbbbbbb/aaaaaaa|
+--------+-------+--------------+------+-------+---------------+--------------+-----------------+----------------+----------------+----------+----------------------------------------------------------------------------------------------------+
```
#### C. Health Check
- List failed tasks, stages and jobs
- SQL Plan HealthCheck

Below are examples.
- Print failed tasks:
```
Failed tasks:
+-------+--------------+------+-------+----------------------------------------------------------------------------------------------------+
|stageId|stageAttemptId|taskId|attempt|endReason_first100char                                                                              |
+-------+--------------+------+-------+----------------------------------------------------------------------------------------------------+
|4      |0             |2842  |0      |ExceptionFailure(ai.rapids.cudf.CudfException,cuDF failure at: /home/jenkins/agent/workspace/jenkins|
|4      |0             |2858  |0      |TaskKilled(another attempt succeeded,List(AccumulableInfo(453,None,Some(22000),None,false,true,None)|
|4      |0             |2884  |0      |TaskKilled(another attempt succeeded,List(AccumulableInfo(453,None,Some(21148),None,false,true,None)|
|4      |0             |2908  |0      |TaskKilled(another attempt succeeded,List(AccumulableInfo(453,None,Some(20420),None,false,true,None)|
|4      |0             |3410  |1      |ExceptionFailure(ai.rapids.cudf.CudfException,cuDF failure at: /home/jenkins/agent/workspace/jenkins|
+-------+--------------+------+-------+----------------------------------------------------------------------------------------------------+
```

- Print failed stages:
```
Failed stages:
+-------+---------+-------------------------------------+--------+---------------------------------------------------+
|stageId|attemptId|name                                 |numTasks|failureReason_first100char                         |
+-------+---------+-------------------------------------+--------+---------------------------------------------------+
|4      |0        |attachTree at Spark300Shims.scala:624|1000    |Job 0 cancelled as part of cancellation of all jobs|
+-------+---------+-------------------------------------+--------+---------------------------------------------------+
```

- Print failed jobs:
```
Failed jobs:
+-----+---------+------------------------------------------------------------------------+
|jobID|jobResult|failedReason_first100char                                               |
+-----+---------+------------------------------------------------------------------------+
|0    |JobFailed|java.lang.Exception: Job 0 cancelled as part of cancellation of all jobs|
+-----+---------+------------------------------------------------------------------------+
```

- SQL Plan HealthCheck:

  Prints possibly unsupported query plan nodes such as `$Lambda` key word means dataset API.
```
+-----+------+--------+---------------------------------------------------------------------------------------------------+
|sqlID|nodeID|nodeName|nodeDesc_first100char                                                                              |
+-----+------+--------+---------------------------------------------------------------------------------------------------+
|1    |8     |Filter  |Filter $line21.$read$$iw$$iw$$iw$$iw$$iw$$iw$$iw$$iw$$Lambda$4578/0x00000008019f1840@4b63e04c.apply|
+-----+------+--------+---------------------------------------------------------------------------------------------------+
```

### How to use this tool
This tool parses the Spark CPU or GPU event log(s) and creates an output report.
Acceptable input event log paths are files or directories containing spark events logs
in the local filesystem, HDFS, S3 or mixed.

### Use from spark-shell
1. Include `rapids-4-spark-tools_2.12-<version>.jar` in the '--jars' option to spark-shell or spark-submit
2. After starting spark-shell:

For a single event log analysis:
```bash
com.nvidia.spark.rapids.tool.profiling.ProfileMain.main(Array("/path/to/eventlog1"))
```

For multiple event logs comparison and analysis:
```bash
com.nvidia.spark.rapids.tool.profiling.ProfileMain.main(Array("/path/to/eventlog1", "/path/to/eventlog2"))
```

### Use from spark-submit
```bash
$SPARK_HOME/bin/spark-submit --class com.nvidia.spark.rapids.tool.profiling.ProfileMain \
rapids-4-spark-tools_2.12-<version>.jar \
/path/to/eventlog1 /path/to/eventlog2 /directory/with/eventlogs
```

### Options (`--help` output)
  
  Note: `--help` should be before the trailing event logs.
```bash
$SPARK_HOME/bin/spark-submit \
--class com.nvidia.spark.rapids.tool.profiling.ProfileMain \
rapids-4-spark-tools_2.12-<version>.jar \
--help


For usage see below:
  -c, --compare                   Compare Applications (Recommended to compare
                                  less than 10 applications). Default is false
  -f, --filter-criteria  <arg>    Filter newest or oldest N event logs for processing.
                                  Supported formats are:
                                  To process 10 recent event logs: --filter-criteria "10-newest"
                                  To process 10 oldest event logs: --filter-criteria "10-oldest"
  -g, --generate-dot              Generate query visualizations in DOT format.
                                  Default is false
  -m, --match-event-logs  <arg>   Filter event logs filenames which contains the input string.
  -n, --num-output-rows  <arg>    Number of output rows for each Application.
                                  Default is 1000
  -o, --output-directory  <arg>   Base output directory. Default is current
                                  directory for the default filesystem. The
                                  final output will go into a subdirectory
                                  called rapids_4_spark_qualification_profile.
                                  It will overwrite any existing files with
                                  the same name.
  -p, --print-plans               Print the SQL plans to a file starting with
                                  'planDescriptions-'. Default is false
  -h, --help                      Show help message

 trailing arguments:
  eventlog (required)   Event log filenames(space separated) or directories
                        containing event logs. eg: s3a://<BUCKET>/eventlog1
                        /path/to/eventlog2
```

### Output
By default this outputs a log file under sub-directory `./rapids_4_spark_qualification_profile` named
`rapids_4_spark_tools_output.log` that contains the processed applications. The output will go into your
default filesystem, it supports local filesystem or HDFS. There are separate files that are generated
under the same sub-directory when using the options to generate query visualizations or printing the SQL plans.

The output location can be changed using the `--output-directory` option. Default is current directory.
  
Note: We suggest you also save the output of the `spark-submit` or `spark-shell` to a log file for troubleshooting.

Run `--help` for more information.

