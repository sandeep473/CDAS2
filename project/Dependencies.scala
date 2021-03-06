import sbt._

object Version {
  val hadoop    = "2.6.0"
  val logback   = "1.1.3"
  val mockito   = "1.10.19"
  val scala     = "2.11.7"
  val scalaTest = "2.2.4"
  val slf4j     = "1.7.6"
  val spark     = "1.6.0"
}

object Library {
  val logbackClassic = "ch.qos.logback"    %  "logback-classic" % Version.logback
  val mockitoAll     = "org.mockito"       %  "mockito-all"     % Version.mockito
  val scalaTest      = "org.scalatest"     %% "scalatest"       % Version.scalaTest
  val slf4jApi       = "org.slf4j"         %  "slf4j-api"       % Version.slf4j
  val sparkSQL       = "org.apache.spark"  %% "spark-sql"       % Version.spark
  val sparkCore      = "org.apache.spark"  %% "spark-core"      % Version.spark
  val scalaxml       = "org.scala-lang.modules" %% "scala-xml"  % "1.0.3"
  val scalaparser    = "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.3"
  val commonsIO      = "commons-io"         % "commons-io"      % "2.5"

  val cdm            = "edu.ucar"           % "cdm"             % "4.6.6"
  val clcommon       = "edu.ucar"           % "clcommon"        % "4.6.6"
  val netcdf4        = "edu.ucar"           % "netcdf4"         % "4.6.6"
  val opendap        = "edu.ucar"           % "opendap"         % "4.6.6"
  val netcdfAll      = "edu.ucar"           % "netcdfAll"       % "4.6.6"
  val nd4s           = "org.nd4j"           % "nd4s_2.11"       % "0.4-rc3.8"
  val nd4j           =  "org.nd4j"          % "nd4j-x86"        % "0.4-rc3.8"
  val httpservices   = "edu.ucar"           %  "httpservices"   % "4.6.0"
  val udunits        = "edu.ucar"           %  "udunits"        % "4.6.0"
  val joda           = "joda-time"          % "joda-time"       % "2.8.1"
  val natty          = "com.joestelmach"    % "natty"           % "0.12"
  val geotools       = "org.geotools"      %  "gt-shapefile"    % "13.2"
  val breeze         = "org.scalanlp"      %% "breeze"          % "0.12"
  val sprayCache     = "io.spray"       % "spray-caching_2.11" % "1.3.3"
  val sprayUtil      = "io.spray"       % "spray-util_2.11"    % "1.3.3"
  val scalactic      = "org.scalactic" %% "scalactic"          % "3.0.0"
  val scalatest      = "org.scalatest" %% "scalatest" % "3.0.0" % "test"
  val concurrentlinkedhashmap = "com.googlecode.concurrentlinkedhashmap" % "concurrentlinkedhashmap-lru" % "1.4.2"
  val reflections    = "org.reflections" % "reflections"       % "0.9.10"
}

object Dependencies {
  import Library._

  val scala = Seq( logbackClassic, slf4jApi, scalaxml, scalaparser, joda, natty, scalactic, commonsIO, scalatest )

  val spark = Seq( sparkCore )

  val cache = Seq( concurrentlinkedhashmap )

  val ndarray = Seq( nd4s, nd4j )

  val geo  = Seq( geotools )

  val netcdf = Seq( netcdfAll ) // cdm, clcommon, netcdf4, opendap )
}

//<groupId>com.googlecode.concurrentlinkedhashmap</groupId>
//  <artifactId>concurrentlinkedhashmap-lru</artifactId>
//  <version>1.4.2</version>










