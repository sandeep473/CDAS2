package nasa.nccs.cds2.kernels
import nasa.nccs.cdapi.kernels.Kernel

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import nasa.nccs.utilities.cdsutils
import nasa.nccs.wps.WPSProcess

class KernelMgr(  ) {

  val kernelModules = KernelPackageTools.getKernelMap

  def getModule( moduleName: String ): Option[KernelModule] = kernelModules.get( moduleName.toLowerCase )

  def getModuleNames: List[String] = kernelModules.keys.toList

  def toXml = <modules>{ kernelModules.values.map( _.toXml ) } </modules>

  def getModulesXml = {
    val elemList: List[xml.Elem] = kernelModules.values.map( _.toXml ).toList
    <kernels>{ elemList }</kernels>
  }

  def getKernelMap: Map[String,Kernel] =
    Map( kernelModules.values.map( _.getKernels ).flatten.map( k => k.identifier.toLowerCase -> k ).toSeq: _* )

}

object KernelPackageTools {
  import com.google.common.reflect.ClassPath
  val internalKernelsPackage = "nasa.nccs.cds2.modules"
  val externalKernelPackages = cdsutils.envList("CDAS_KERNEL_PACKAGES")
  val classpath = ClassPath.from( getClass.getClassLoader )
  val kernelPackagePaths: List[String] = List( internalKernelsPackage ) ++ externalKernelPackages

  def getKernelClasses: List[ClassPath.ClassInfo] = {
    kernelPackagePaths.flatMap( package_path => classpath.getTopLevelClassesRecursive( package_path ).toList )
  }

  def getKernelMap: Map[String,KernelModule] = {
    getKernelClasses.map(ClassInfoRec( _ )).groupBy( _.module.toLowerCase ).mapValues( KernelModule(_) )
  }
}

object ClasspathToolsTest extends App {
  val kmap = KernelPackageTools.getKernelMap
  kmap.get("CDSpark") match {
    case Some( kmod ) =>
      println( "Got module ")
      kmod.getKernel("min") match {
        case Some( kernel ) =>
          println( "Got kernel " + kernel.getClass.getName )
          cdsutils.testSerializable(kernel)
        case None => println( "No kernel ")
      }
    case None => println( "No Module ")
  }
}




