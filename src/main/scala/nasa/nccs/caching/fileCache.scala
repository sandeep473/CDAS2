package nasa.nccs.caching

import java.io.{FileInputStream, RandomAccessFile}
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.{Buffer, ByteBuffer, FloatBuffer, MappedByteBuffer}

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap
import nasa.nccs.cds2.utilities.runtime
import nasa.nccs.cdapi.cdm.{Collection, _}
import nasa.nccs.cdapi.kernels.TransientFragment
import nasa.nccs.cdapi.tensors.{CDByteArray, CDFloatArray}
import nasa.nccs.cds2.loaders.Masks
import nasa.nccs.cds2.utilities.GeoTools
import nasa.nccs.esgf.process.{DataFragmentKey, _}
import nasa.nccs.utilities.Loggable
import sun.nio.ch.FileChannelImpl
import ucar.{ma2, nc2}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success, Try}

object MaskKey {
  def apply( bounds: Array[Double], mask_shape: Array[Int], spatial_axis_indices: Array[Int] ): MaskKey = {
    new MaskKey( bounds, Array( mask_shape(spatial_axis_indices(0)), mask_shape(spatial_axis_indices(1) ) ) )
  }
}
class MaskKey( bounds: Array[Double], dimensions: Array[Int] ) {}

class CacheChunk( val offset: Int, val elemSize: Int, val shape: Array[Int], val buffer: ByteBuffer ) {
  def size: Int = shape.product
  def data: Array[Byte] = buffer.array
  def byteSize = shape.product * elemSize
  def byteOffset = offset * elemSize
}

class Partitions( val id: String, val roi: ma2.Section, val parts: Array[Partition] ) {
  def getShape = roi.getShape
}

class Partition( val index: Int, val path: String, val coordIndex: Int, val startIndex: Int, val partSize: Int, val sliceMemorySize: Int, val missing_value: Float, roi: ma2.Section ) {
  val shape = getPartitionShape(roi)

  def getChannel: FileChannel  = new RandomAccessFile( path,"r" ).getChannel()

  def data(roi: ma2.Section, channelOpt: Option[FileChannel] = None ): CDFloatArray = {
    val channel = channelOpt match { case Some(c) => c; case None => getChannel }
    val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, partSize * sliceMemorySize)
    new CDFloatArray( shape, buffer.asFloatBuffer, missing_value )
  }

  def getPartitionShape(roi: ma2.Section): Array[Int] = {
    var full_shape = roi.getShape.clone()
    full_shape(0) = partSize
    full_shape
  }

}

class FileToCacheStream( val ncVariable: nc2.Variable, val roi: ma2.Section, val maskOpt: Option[CDByteArray], val cacheType: String = "fragment"  ) extends Loggable {
  private val chunkCache = new ConcurrentLinkedHashMap.Builder[Int,CacheChunk].initialCapacity(500).maximumWeightedCapacity(1000000).build()
  private val baseShape = roi.getShape
  private val dType: ma2.DataType  = ncVariable.getDataType
  private val elemSize = ncVariable.getElementSize
  private val range0 = roi.getRange(0)
  private val maxBufferSize = Int.MaxValue
  private val maxChunkSize = 250000000
  private val throttleSize = 2
  private val sliceMemorySize: Int = getMemorySize(1)
  private val nSlicesPerChunk: Int = if(sliceMemorySize >= maxChunkSize ) 1 else  math.min( ( maxChunkSize / sliceMemorySize ), baseShape(0) )
  private val chunkMemorySize: Int = if(sliceMemorySize >= maxChunkSize ) sliceMemorySize else getMemorySize(nSlicesPerChunk)
  private val nChunksPerPart = maxBufferSize/chunkMemorySize
  private val nSlicesPerPart = nChunksPerPart * nSlicesPerChunk
  private val nPartitions = math.ceil( baseShape(0) / nSlicesPerPart.toFloat ).toInt
  private val nProcessors = Math.min( nChunksPerPart, Runtime.getRuntime.availableProcessors )
  private val nCoresPerPart = 1

  def getMemorySize( nSlicesPerPart: Int): Int = {
    var full_shape = baseShape.clone()
    full_shape(0) = nSlicesPerPart
    full_shape.foldLeft(elemSize)(_ * _)
  }

  def getPartitionShape(partSize: Int): Array[Int] = {
    var full_shape = baseShape.clone()
    full_shape(0) = partSize
    full_shape
  }

  def getCacheId: String = { "a" + System.nanoTime.toHexString }

  def getCacheFilePath( cache_id: String, partIndex: Int ): String = {
    val cache_file =  cache_id + "-" + partIndex.toString
    DiskCacheFileMgr.getDiskCacheFilePath(cacheType, cache_file)
  }

  def getReadBuffer( cache_id: String ): ( FileChannel, MappedByteBuffer ) = {
    val channel = new FileInputStream( cache_id ).getChannel
    val size = math.min( channel.size, Int.MaxValue ).toInt
    channel -> channel.map(FileChannel.MapMode.READ_ONLY, 0, size )
  }

  def execute(missing_value: Float): Partitions = {
    val cache_id = getCacheId
    val future_partitions: IndexedSeq[Future[Partition]] = for( partIndex <- ( 0 until nPartitions ) ) yield Future { processPartition( cache_id, partIndex, missing_value )  }
    val partitions: Array[Partition] = Await.result( Future.sequence( future_partitions ), Duration.Inf ).toArray
    new Partitions( cache_id, roi, partitions )
  }

  def processPartition(cache_id: String, partIndex: Int, missing_value: Float ): Partition =  {
    val cacheFilePath = getCacheFilePath(cache_id,partIndex)
    val channel = new RandomAccessFile(cacheFilePath,"rw").getChannel()
    val partSize = cachePartition( 0, partIndex, channel )
    new Partition( partIndex, cacheFilePath, 0, partIndex*nSlicesPerPart, partSize, sliceMemorySize, missing_value, roi )
  }

  def cachePartition( coreIndex: Int, partIndex: Int, channel: FileChannel ): Int = {
    var subsection = new ma2.Section(roi)
    var nElementsWritten = 0
    val sizes = for( iChunk <- (coreIndex until nChunksPerPart by nCoresPerPart); startLoc = iChunk*nSlicesPerChunk + partIndex*nSlicesPerPart; if(startLoc < baseShape(0)) ) yield {
      val endLoc = Math.min( startLoc + nSlicesPerChunk - 1, baseShape(0)-1 )
      val chunkRange = new ma2.Range( startLoc, endLoc )
      subsection.replaceRange(0,chunkRange)
      val t0 = System.nanoTime()
      logger.info( "Reading data chunk %d, part %d, startTimIndex = %d, subsection [%s], nElems = %d ".format( iChunk, partIndex, startLoc, subsection.getShape.mkString(","), subsection.getShape.foldLeft(1L)( _ * _ ) ) )
      val data = ncVariable.read(subsection)
      val chunkShape = subsection.getShape
      val dataBuffer = data.getDataAsByteBuffer
      val t1 = System.nanoTime()
      logger.info( "Finished Reading data chunk %d, shape = [%s], buffer capacity = %d in time %.2f ".format( iChunk, chunkShape.mkString(","), dataBuffer.capacity(), (t1-t0)/1.0E9 ) )
      val t2 = System.nanoTime()
      val position: Long = iChunk.toLong * chunkMemorySize.toLong
      var buffer: MappedByteBuffer = channel.map( FileChannel.MapMode.READ_WRITE, position, chunkMemorySize  )
      val t3 = System.nanoTime()
      logger.info( " -----> Writing chunk %d, size = %.2f M, map time = %.2f ".format( iChunk, chunkMemorySize/1.0E6, (t2-t3)/1.0E9 ) )
      buffer.put( dataBuffer )
      buffer.force()
      val t4 = System.nanoTime()
      logger.info( s"Persisted chunk %d, write time = %.2f ".format( iChunk, (t4-t3)/1.0E9 ))
      runtime.printMemoryUsage(logger)
      endLoc - startLoc + 1
    }
    sizes.foldLeft(0)(_ + _)
  }

//  @tailrec
//  private def throttle: Unit = {
//    val cvals: Iterable[CacheChunk] = chunkCache.values.toIterable
//    //    val csize = cvals.foldLeft( 0L )( _ + _.byteSize )
//    val num_cached_chunks = cvals.size
//    if( num_cached_chunks >= throttleSize ) {
//      Thread.sleep(5000)
//      throttle
//    }
//  }
//
//  def readDataChunks( coreIndex: Int ): Int = {
//    var subsection = new ma2.Section(roi)
//    var nElementsWritten = 0
//    for( iChunk <- (coreIndex until nChunksPerPart by nReadProcessors); startLoc = iChunk*nSlicesPerChunk; if(startLoc < baseShape(0)) ) {
//      val endLoc = Math.min( startLoc + nSlicesPerChunk - 1, baseShape(0)-1 )
//      val chunkRange = new ma2.Range( startLoc, endLoc )
//      subsection.replaceRange(0,chunkRange)
//      val t0 = System.nanoTime()
//      logger.info( "Reading data chunk %d, startTimIndex = %d, subsection [%s], nElems = %d ".format( iChunk, startLoc, subsection.getShape.mkString(","), subsection.getShape.foldLeft(1L)( _ * _ ) ) )
//      val data = ncVariable.read(subsection)
//      val chunkShape = subsection.getShape
//      val dataBuffer = data.getDataAsByteBuffer
//
//      val chunk = new CacheChunk( startLoc, elemSize, chunkShape, dataBuffer )
//      chunkCache.put( iChunk, chunk )
//      val t1 = System.nanoTime()
//      logger.info( "Finished Reading data chunk %d, shape = [%s], buffer capacity = %d in time %.2f ".format( iChunk, chunkShape.mkString(","), dataBuffer.capacity(), (t1-t0)/1.0E9 ) )
//      throttle
//      nElementsWritten += chunkShape.product
//    }
//    nElementsWritten
//  }
//
//  @tailrec
//  final def processChunkFromReader( iChunk: Int, channel: FileChannel ): Unit = {
//    Option(chunkCache.get(iChunk)) match {
//      case Some( cacheChunk: CacheChunk ) =>
//        val t0 = System.nanoTime()
//        val position: Long = iChunk.toLong * chunkMemorySize.toLong
//        var buffer: MappedByteBuffer = channel.map( FileChannel.MapMode.READ_WRITE, position, chunkMemorySize  )
//        val t1 = System.nanoTime()
//        logger.info( " -----> Writing chunk %d, size = %.2f M, map time = %.2f ".format( iChunk, chunkMemorySize/1.0E6, (t1-t0)/1.0E9 ) )
//        buffer.put( cacheChunk.data )
//        buffer.force()
//        chunkCache.remove( iChunk )
//        val t2 = System.nanoTime()
//        logger.info( s"Persisted chunk %d, write time = %.2f ".format( iChunk, (t2-t1)/1.0E9 ))
//        runtime.printMemoryUsage(logger)
//      case None =>
//        Thread.sleep( 500 )
//        processChunkFromReader( iChunk, channel )
//    }
//  }
//  def execute1(): String = {
//    val readProcFuts: IndexedSeq[Future[Int]] = for( coreIndex <- (0 until Math.min( nChunksPerPart, nReadProcessors ) ) ) yield Future { readDataChunks(coreIndex) }
//    writeChunks
//  }
//
//  def cacheFloatData( missing_value: Float  ): ( String, CDFloatArray ) = {
//    assert( dType == ma2.DataType.FLOAT, "Attempting to cache %s data as float".format( dType.toString ) )
//    val cache_id = execute()
//    getReadBuffer(cache_id) match {
//      case (channel, buffer) =>
//        val storage: FloatBuffer = buffer.asFloatBuffer
//        channel.close
//        ( cache_id -> new CDFloatArray( getTruncatedArrayShape, storage, missing_value ) )
//    }
//  }
//
//
//  def writeChunks: String = {
//    val cacheFilePath = getCacheFilePath
//    val channel = new RandomAccessFile(cacheFilePath,"rw").getChannel()
//    logger.info( "Writing Buffer file '%s', nChunksPerPart = %d, chunkMemorySize = %d, nSlicesPerChunk = %d".format( cacheFilePath, nChunksPerPart, chunkMemorySize, nSlicesPerChunk  ))
//    (0.toInt until nChunksPerPart).foreach( processChunkFromReader( _, channel ) )
//    cacheFilePath
//  }



}

object FragmentPersistence extends DiskCachable with FragSpecKeySet {
  private val fragmentIdCache: Cache[String,PartitionedFragment] = new FutureCache("CacheIdMap","fragment",true)
  def getCacheType = "fragment"
  def keys: Set[String] = fragmentIdCache.keys
  def values: Iterable[Future[PartitionedFragment]] = fragmentIdCache.values

  def persist(fragSpec: DataFragmentSpec, frag: PartitionedFragment): Future[String] = {
    val keyStr =  fragSpec.getKey.toStrRep
    fragmentIdCache.get(keyStr) match {
      case Some(fragIdFut) => fragIdFut
      case None => fragmentIdCache(keyStr) {
        val fragIdFut = promiseCacheId(frag) _
        fragmentIdCache.persist()
        fragIdFut
      }
    }
  }

  def getBounds( fragKey: String ): String =  collectionDataCache.getExistingFragment(DataFragmentKey(fragKey)) match {
    case Some( fragFut ) => Await.result( fragFut, Duration.Inf ).toBoundsString
    case None => ""
  }

  def expandKey( fragKey: String ): String = {
    val bounds = getBounds(fragKey)
    val toks = fragKey.split('|')
    "variable= %s; origin= (%s); shape= (%s); url= %s; bounds= %s".format(toks(0),toks(2),toks(3),toks(1),bounds)
  }

  def expandKeyXml( fragKey: String ):  xml.Elem = {
    val toks = fragKey.split('|')
     <fragment variable={toks(0)} origin={toks(2)} shape={toks(3)} url={toks(1)}> { getBounds(fragKey) } </fragment>
  }

  def contractKey( fragDescription: String ): String = {
    val tok = fragDescription.split(';').map( _.split('=')(1).trim.stripPrefix("(").stripSuffix(")"))
    Array( tok(0),tok(3),tok(1),tok(2) ).mkString("|")
  }

  def getFragmentListXml(): xml.Elem = <fragments> { for(fkey <- fragmentIdCache.keys) yield expandKeyXml(fkey) } </fragments>
  def getFragmentIdList(): Array[String] = fragmentIdCache.keys.toArray
  def getFragmentList(): Array[String] =  fragmentIdCache.keys.map( k => expandKey(k) ).toArray
  def put( key: DataFragmentKey, cache_id: String ) = { fragmentIdCache.put( key.toStrRep, cache_id ); fragmentIdCache.persist() }
  def getEntries: Seq[(String,String)] = fragmentIdCache.getEntries

  def promiseCacheId( frag: PartitionedFragment )(p: Promise[String]): Unit = {
    try {
      val cacheFile = bufferToDiskFloat(frag.data.getSectionData())
      p.success(cacheFile)
    }  catch {
      case err: Throwable => logError(err, "Error writing cache data to disk:"); p.failure(err)
    }
  }

  def restore( cache_id: String, size: Int ): Option[FloatBuffer] = bufferFromDiskFloat( cache_id, size )
  def restore( fragKey: DataFragmentKey ): Option[FloatBuffer] =  fragmentIdCache.get(fragKey.toStrRep).flatMap( restore( _, fragKey.getSize ) )
  def restore( cache_id_future: Future[String], size: Int ): Option[FloatBuffer] = restore( Await.result(cache_id_future, Duration.Inf), size )
  def close(): Unit = Await.result( Future.sequence( fragmentIdCache.values ), Duration.Inf )

  def deleteEnclosing( fragSpec: DataFragmentSpec ) =
    delete( findEnclosingFragSpecs(  fragmentIdCache.keys.map( DataFragmentKey(_) ), fragSpec.getKey ) )

  def delete(fragKeys: Iterable[DataFragmentKey]) = {
    for (fragKey <- fragKeys) fragmentIdCache.get(fragKey.toStrRep) match {
      case Some(cache_id_future) =>
        val path = DiskCacheFileMgr.getDiskCacheFilePath(getCacheType, Await.result(cache_id_future, Duration.Inf))
        fragmentIdCache.remove(fragKey.toStrRep)
        if (new java.io.File(path).delete()) logger.info(s"Deleting persisted fragment file '$path', frag: " + fragKey.toString)
        else logger.warn(s"Failed to delete persisted fragment file '$path'")
      case None => logger.warn("No Cache ID found for Fragment: " + fragKey.toString)
    }
    fragmentIdCache.persist()
  }

  def getEnclosingFragmentData( fragSpec: DataFragmentSpec ): Option[ ( DataFragmentKey, FloatBuffer ) ] = {
    val fragKeys = findEnclosingFragSpecs(  fragmentIdCache.keys.map( DataFragmentKey(_) ), fragSpec.getKey )
    fragKeys.headOption match {
      case Some( fkey ) => restore(fkey) match {
        case Some(array) => Some( (fkey->array) )
        case None => None
      }
      case None => None
    }
  }
  def getFragmentData( fragSpec: DataFragmentSpec ): Option[ FloatBuffer ] = restore( fragSpec.getKey )
}

trait FragSpecKeySet extends nasa.nccs.utilities.Loggable {

  def getFragSpecsForVariable(keys: Set[DataFragmentKey], collection: String, varName: String): Set[DataFragmentKey] = keys.filter(
    _ match {
      case fkey: DataFragmentKey => fkey.sameVariable(collection, varName)
      case x => logger.warn("Unexpected fragment key type: " + x.getClass.getName); false
    }).map(_ match { case fkey: DataFragmentKey => fkey })


  def findEnclosingFragSpecs(keys: Set[DataFragmentKey], fkey: DataFragmentKey, admitEquality: Boolean = true): Set[DataFragmentKey] = {
    val variableFrags = getFragSpecsForVariable(keys, fkey.collectionUrl, fkey.varname)
    variableFrags.filter(fkeyParent => fkeyParent.contains(fkey, admitEquality))
  }

  def findEnclosedFragSpecs(keys: Set[DataFragmentKey], fkeyParent: DataFragmentKey, admitEquality: Boolean = false): Set[DataFragmentKey] = {
    val variableFrags = getFragSpecsForVariable(keys, fkeyParent.collectionUrl, fkeyParent.varname)
    variableFrags.filter(fkey => fkeyParent.contains(fkey, admitEquality))
  }

  def findEnclosingFragSpec(keys: Set[DataFragmentKey], fkeyChild: DataFragmentKey, selectionCriteria: FragmentSelectionCriteria.Value, admitEquality: Boolean = true): Option[DataFragmentKey] = {
    val enclosingFragments = findEnclosingFragSpecs(keys, fkeyChild, admitEquality)
    if (enclosingFragments.isEmpty) None
    else Some(selectionCriteria match {
      case FragmentSelectionCriteria.Smallest => enclosingFragments.minBy(_.getRoi.computeSize())
      case FragmentSelectionCriteria.Largest => enclosingFragments.maxBy(_.getRoi.computeSize())
    })
  }
}

class JobRecord( val id: String ) {
  override def toString: String = s"ExecutionRecord[id=$id]"
  def toXml: xml.Elem = <job id={id} />
}

class CollectionDataCacheMgr extends nasa.nccs.esgf.process.DataLoader with FragSpecKeySet {
  private val fragmentCache: Cache[DataFragmentKey,PartitionedFragment] = new FutureCache("Store","fragment",false)
  private val transientFragmentCache = new ConcurrentLinkedHashMap.Builder[ String, TransientFragment ].initialCapacity(64).maximumWeightedCapacity(10000).build()
  private val execJobCache = new ConcurrentLinkedHashMap.Builder[ String, JobRecord ].initialCapacity(64).maximumWeightedCapacity(10000).build()
  private val datasetCache: Cache[String,CDSDataset] = new FutureCache("Store","dataset",false)
  private val variableCache: Cache[String,CDSVariable] = new FutureCache("Store","variable",false)
  private val maskCache: Cache[MaskKey,CDByteArray] = new FutureCache("Store","mask",false)
  def clearFragmentCache() = fragmentCache.clear

  def addJob( jrec: JobRecord ): String = { execJobCache.put( jrec.id, jrec ); jrec.id }
  def removeJob( jid: String ) = execJobCache.remove( jid )

  def getFragmentList: Array[String] =  fragmentCache.getEntries.map
    { case (key,frag) => "%s, bounds:%s".format( key.toStrRep, frag.toBoundsString ) } toArray

  def makeKey(collection: String, varName: String) = collection + ":" + varName
  def keys: Set[DataFragmentKey] = fragmentCache.keys
  def values: Iterable[Future[PartitionedFragment]] = fragmentCache.values

  def extractFuture[T](key: String, result: Option[Try[T]]): T = result match {
    case Some(tryVal) => tryVal match {
      case Success(x) => x;
      case Failure(t) => throw t
    }
    case None => throw new Exception(s"Error getting cache value $key")
  }

  def deleteFragment( fragId: String ): Option[Future[PartitionedFragment]] = deleteFragment( DataFragmentKey(fragId) )
  def getDatasetFuture(collection: Collection, varName: String): Future[CDSDataset] =
    datasetCache(makeKey(collection.url, varName)) { produceDataset(collection, varName) _ }

  def getDataset(collection: Collection, varName: String): CDSDataset = {
    val futureDataset: Future[CDSDataset] = getDatasetFuture(collection, varName)
    Await.result(futureDataset, Duration.Inf)
  }

  private def produceDataset(collection: Collection, varName: String)(p: Promise[CDSDataset]): Unit = {
    val t0 = System.nanoTime()
    val dataset = CDSDataset.load(collection, varName)
    val t1 = System.nanoTime()
    logger.info(" Completed reading dataset (%s:%s), T: %.4f ".format( collection, varName, (t1-t0)/1.0E9 ))
    p.success(dataset)
  }

  def getExistingResult( resultId: String  ): Option[TransientFragment] = {
    val result: Option[TransientFragment] = Option(transientFragmentCache.get( resultId ))
    logger.info( ">>>>>>>>>>>>>>>> Get result from cache: search key = " + resultId + ", existing keys = " + transientFragmentCache.keySet.toArray.mkString("[",",","]") + ", Success = " + result.isDefined.toString )
    result
  }
  def deleteResult( resultId: String  ): Option[TransientFragment] = Option(transientFragmentCache.remove(resultId))
  def putResult( resultId: String, result: TransientFragment  ) = transientFragmentCache.put(resultId,result)
  def getResultListXml(): xml.Elem = <results> { for( rkey <- transientFragmentCache.keySet ) yield <result id={rkey} /> } </results>
  def getResultIdList = transientFragmentCache.keySet
  def getJobListXml(): xml.Elem = <jobs> { for( jrec: JobRecord <- execJobCache.values ) yield jrec.toXml } </jobs>

  private def promiseVariable(collection: Collection, varName: String)(p: Promise[CDSVariable]): Unit =
    getDatasetFuture(collection, varName) onComplete {
      case Success(dataset) =>
        try {
          val t0 = System.nanoTime()
          val variable = dataset.loadVariable(varName)
          val t1 = System.nanoTime()
          logger.info(" Completed reading variable %s, T: %.4f".format( varName, (t1-t0)/1.0E9 ) )
          p.success(variable)
        }
        catch {
          case e: Exception => p.failure(e)
        }
      case Failure(t) => p.failure(t)
    }

  def getVariableFuture(collection: Collection, varName: String): Future[CDSVariable] = variableCache(makeKey(collection.url, varName)) {
    promiseVariable(collection, varName) _
  }

  def getVariable(collection: Collection, varName: String): CDSVariable = {
    val futureVariable: Future[CDSVariable] = getVariableFuture(collection, varName)
    Await.result(futureVariable, Duration.Inf)
  }

  def getVariable(fragSpec: DataFragmentSpec): CDSVariable = getVariable(fragSpec.collection, fragSpec.varname)

  private def cutExistingFragment( fragSpec: DataFragmentSpec, abortSizeFraction: Float=0f ): Option[PartitionedFragment] = {
    val fragOpt = findEnclosingFragSpec( fragmentCache.keys, fragSpec.getKey, FragmentSelectionCriteria.Smallest) match {
      case Some(fkey: DataFragmentKey) => getExistingFragment(fkey) match {
        case Some(fragmentFuture) =>
          if (!fragmentFuture.isCompleted && (fkey.getSize * abortSizeFraction > fragSpec.getSize)) {
            logger.info("Cache Chunk[%s] found but not yet ready, abandoning cache access attempt".format(fkey.shape.mkString(",")))
            None
          } else {
            val fragment = Await.result(fragmentFuture, Duration.Inf)
            Some(fragment.cutNewSubset(fragSpec.roi))
          }
        case None => cutExistingFragment(fragSpec, abortSizeFraction)
      }
      case None => None
    }
    fragOpt match {
      case None =>
        FragmentPersistence.getEnclosingFragmentData(fragSpec) match {
          case Some((fkey, fltBuffer)) =>
            val cdvar: CDSVariable = getVariable(fragSpec.collection, fragSpec.varname )
            val newFragSpec = fragSpec.reSection(fkey)
            val maskOpt = newFragSpec.mask.flatMap( maskId => produceMask( maskId, newFragSpec.getBounds, newFragSpec.getGridShape, cdvar.getTargetGrid( newFragSpec ).getAxisIndices("xy") ) )
            val fragment = new PartitionedFragment( new CDFloatArray( newFragSpec.getShape, fltBuffer, cdvar.missing ), maskOpt, newFragSpec )
            fragmentCache.put( fkey, fragment )
            Some(fragment.cutNewSubset(fragSpec.roi))
          case None => None
        }
      case x => x
    }
  }

  private def promiseFragment( fragSpec: DataFragmentSpec, dataAccessMode: DataAccessMode )(p: Promise[PartitionedFragment]): Unit =
    getVariableFuture( fragSpec.collection, fragSpec.varname )  onComplete {
      case Success(variable) =>
        try {
          val t0 = System.nanoTime()
          val result = fragSpec.targetGridOpt match {
            case Some( targetGrid ) =>
              val maskOpt = fragSpec.mask.flatMap( maskId => produceMask( maskId, fragSpec.getBounds, fragSpec.getGridShape, targetGrid.getAxisIndices("xy") ) )
              targetGrid.loadRoi( variable, fragSpec, maskOpt, dataAccessMode )
            case None =>
              val targetGrid = new TargetGrid( variable, Some(fragSpec.getAxes) )
              val maskOpt = fragSpec.mask.flatMap( maskId => produceMask( maskId, fragSpec.getBounds, fragSpec.getGridShape, targetGrid.getAxisIndices("xy")  ) )
              targetGrid.loadRoi( variable, fragSpec, maskOpt, dataAccessMode )
          }
          logger.info("Completed variable (%s:%s) subset data input in time %.4f sec, section = %s ".format(fragSpec.collection, fragSpec.varname, (System.nanoTime()-t0)/1.0E9, fragSpec.roi ))
          //          logger.info("Data column = [ %s ]".format( ( 0 until result.shape(0) ).map( index => result.getValue( Array(index,0,100,100) ) ).mkString(", ") ) )
          p.success( result )

        } catch { case e: Exception => p.failure(e) }
      case Failure(t) => p.failure(t)
    }

  def produceMask(maskId: String, bounds: Array[Double], mask_shape: Array[Int], spatial_axis_indices: Array[Int]): Option[CDByteArray] = {
    if (Masks.isMaskId(maskId)) {
      val maskFuture = getMaskFuture( maskId, bounds, mask_shape, spatial_axis_indices  )
      val result = Await.result( maskFuture, Duration.Inf )
      logger.info("Loaded mask (%s) data".format( maskId ))
      Some(result)
    } else {
      None
    }
  }

  private def getMaskFuture( maskId: String, bounds: Array[Double], mask_shape: Array[Int], spatial_axis_indices: Array[Int]  ): Future[CDByteArray] = {
    val fkey = MaskKey(bounds, mask_shape, spatial_axis_indices)
    val maskFuture = maskCache( fkey ) { promiseMask( maskId, bounds, mask_shape, spatial_axis_indices ) _ }
    logger.info( ">>>>>>>>>>>>>>>> Put mask in cache: " + fkey.toString + ", keys = " + maskCache.keys.mkString("[",",","]") )
    maskFuture
  }

  private def promiseMask( maskId: String, bounds: Array[Double], mask_shape: Array[Int], spatial_axis_indices: Array[Int] )(p: Promise[CDByteArray]): Unit =
    try {
      Masks.getMask(maskId) match {
        case Some(mask) => mask.mtype match {
          case "shapefile" =>
            val geotools = new GeoTools()
            p.success( geotools.produceMask( mask.getPath, bounds, mask_shape, spatial_axis_indices ) )
          case x => p.failure(new Exception(s"Unrecognized Mask type: $x"))
        }
        case None => p.failure(new Exception(s"Unrecognized Mask ID: $maskId: options are %s".format(Masks.getMaskIds)))
      }
    } catch { case e: Exception => p.failure(e) }

  private def clearRedundantFragments( fragSpec: DataFragmentSpec ) = findEnclosedFragSpecs( fragmentCache.keys, fragSpec.getKey ).foreach( fragmentCache.remove )

  private def getFragmentFuture( fragSpec: DataFragmentSpec, dataAccessMode: DataAccessMode  ): Future[PartitionedFragment] = {
    logger.info( " getFragmentFuture: Start => dataAccessMode = " + dataAccessMode )
    val fragFuture = fragmentCache( fragSpec.getKey ) { promiseFragment( fragSpec, dataAccessMode ) _ }
    fragFuture onComplete {
      case Success(fragment) => try {
        logger.info( " getFragmentFuture: Success => dataAccessMode = " + dataAccessMode )
        //          clearRedundantFragments(fragSpec)
        if (dataAccessMode == DataAccessMode.Cache) {
          logger.info( " Persisting fragment spec: " + fragSpec.getKey.toString )
          FragmentPersistence.persist(fragSpec, fragment)
        }
      } catch {
        case err: Throwable =>  logger.warn( " Failed to persist fragment list due to error: " + err.getMessage )
      }
      case Failure(err) => logger.warn( " Failed to generate fragment due to error: " + err.getMessage )
    }
    logger.info( ">>>>>>>>>>>>>>>> Put frag in cache: " + fragSpec.toString + ", keys = " + fragmentCache.keys.mkString("[",",","]") + ", dataAccessMode = " + dataAccessMode.toString )
    fragFuture
  }

  def getFragment( fragSpec: DataFragmentSpec, dataAccessMode: DataAccessMode, abortSizeFraction: Float=0f  ): PartitionedFragment = {
    cutExistingFragment(fragSpec, abortSizeFraction) getOrElse {
      val fragmentFuture = getFragmentFuture(fragSpec, dataAccessMode)
      val result = Await.result(fragmentFuture, Duration.Inf)
      logger.info("Loaded variable (%s:%s) subset data, section = %s ".format(fragSpec.collection, fragSpec.varname, fragSpec.roi))
      result
    }
  }

  def getFragmentAsync( fragSpec: DataFragmentSpec, dataAccessMode: DataAccessMode  ): Future[PartitionedFragment] =
    cutExistingFragment(fragSpec) match {
      case Some(fragment) => Future { fragment }
      case None => getFragmentFuture(fragSpec, dataAccessMode)
    }


  //  def loadOperationInputFuture( dataContainer: DataContainer, domain_container: DomainContainer ): Future[OperationInputSpec] = {
  //    val variableFuture = getVariableFuture(dataContainer.getSource.collection, dataContainer.getSource.name)
  //    variableFuture.flatMap( variable => {
  //      val section = variable.getSubSection(domain_container.axes)
  //      val fragSpec = variable.createFragmentSpec( section, domain_container.mask )
  //      val axisSpecs: AxisIndices = variable.getAxisIndices(dataContainer.getOpSpecs)
  //      for (frag <- getFragmentFuture(fragSpec)) yield new OperationInputSpec( fragSpec, axisSpecs)
  //    })
  //  }
  //
  //  def loadDataFragmentFuture( dataContainer: DataContainer, domain_container: DomainContainer ): Future[PartitionedFragment] = {
  //    val variableFuture = getVariableFuture(dataContainer.getSource.collection, dataContainer.getSource.name)
  //    variableFuture.flatMap( variable => {
  //      val section = variable.getSubSection(domain_container.axes)
  //      val fragSpec = variable.createFragmentSpec( section, domain_container.mask )
  //      for (frag <- getFragmentFuture(fragSpec)) yield frag
  //    })
  //  }

  def getExistingMask( fkey: MaskKey  ): Option[Future[CDByteArray]] = {
    val rv: Option[Future[CDByteArray]] = maskCache.get( fkey )
    logger.info( ">>>>>>>>>>>>>>>> Get mask from cache: search key = " + fkey.toString + ", existing keys = " + maskCache.keys.mkString("[",",","]") + ", Success = " + rv.isDefined.toString )
    rv
  }

  def getExistingFragment( fkey: DataFragmentKey  ): Option[Future[PartitionedFragment]] = {
    val rv: Option[Future[PartitionedFragment]] = fragmentCache.get( fkey )
    logger.info( ">>>>>>>>>>>>>>>> Get frag from cache: search key = " + fkey.toString + ", existing keys = " + fragmentCache.keys.mkString("[",",","]") + ", Success = " + rv.isDefined.toString )
    rv
  }

  def deleteFragment( fkey: DataFragmentKey  ): Option[Future[PartitionedFragment]] = fragmentCache.remove(fkey)

  def printFragmentMetadata( fragKeyStr: String ) = {
    println( "XXX: " + fragKeyStr )
    val fragKey = DataFragmentKey( fragKeyStr )
    FragmentPersistence.restore( fragKey )
    val bounds = collectionDataCache.getExistingFragment(fragKey) match {
      case Some( fragFut ) =>
        val fragSpec = Await.result( fragFut, Duration.Inf ).fragmentSpec
        " Variable %s(%s), units: %s, bounds: (%s)".format( fragSpec.longname, fragSpec.dimensions, fragSpec.units, fragSpec.toBoundsString )
      case None => ""
    }
  }
}

object collectionDataCache extends CollectionDataCacheMgr()

object TestApp extends App {
  val it1 = Int.MaxValue
  val it2 = math.pow( 2, 31 ).toInt
  println( it1 + ", " + it2 )
}
