package nasa.nccs.cds2.utilities
import java.lang.management.ManagementFactory
import org.slf4j.Logger

object runtime {
  def printMemoryUsage(logger: Logger) = {
    val mb = 1024 * 1024
    val runtime = Runtime.getRuntime
    logger.info("--------------------------------- MEMORY USAGE ---------------------------------")
    logger.info("** Used Memory:  " + (runtime.totalMemory - runtime.freeMemory) / mb)
    logger.info("** Free Memory:  " + runtime.freeMemory / mb)
    logger.info("** Total Memory: " + runtime.totalMemory / mb)
    logger.info("** Max Memory:   " + runtime.maxMemory / mb)
    logger.info("** Num cores:   " +  runtime.availableProcessors )
    logger.info("--------------------------------- ------------ ---------------------------------")
  }
}


