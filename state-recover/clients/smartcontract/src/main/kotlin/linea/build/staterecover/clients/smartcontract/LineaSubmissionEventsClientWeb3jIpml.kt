package linea.build.staterecover.clients.smartcontract

import build.linea.domain.EthLogEvent
import build.linea.staterecover.clients.DataFinalizedV3
import build.linea.staterecover.clients.DataSubmittedV3
import build.linea.staterecover.clients.FinalizationAndDataEventsV3
import build.linea.staterecover.clients.LineaRollupSubmissionEventsClient
import build.linea.web3j.LogsSearcher
import build.linea.web3j.SearchDirection
import net.consensys.encodeHex
import net.consensys.linea.BlockParameter
import net.consensys.linea.BlockParameter.Companion.toBlockParameter
import net.consensys.toHexStringUInt256
import tech.pegasys.teku.infrastructure.async.SafeFuture

class LineaSubmissionEventsClientWeb3jIpml(
  private val logsSearcher: LogsSearcher,
  private val smartContractAddress: String,
  private val l1EarliestSearchBlock: BlockParameter = BlockParameter.Tag.EARLIEST,
  private val l1LatestSearchBlock: BlockParameter = BlockParameter.Tag.FINALIZED,
  private val logsBlockChunkSize: Int = 1000
) : LineaRollupSubmissionEventsClient {
  override fun findDataFinalizedEventContainingBlock(
    l2BlockNumber: ULong
  ): SafeFuture<EthLogEvent<DataFinalizedV3>?> {
    return logsSearcher.findLog(
      fromBlock = l1EarliestSearchBlock,
      toBlock = l1LatestSearchBlock,
      address = smartContractAddress,
      topics = listOf(DataFinalizedV3.topic),
      chunkSize = logsBlockChunkSize,
      shallContinueToSearchPredicate = { log ->
        val (event) = DataFinalizedV3.fromEthLog(log)
        when {
          l2BlockNumber < event.startBlockNumber -> SearchDirection.BACKWARD
          l2BlockNumber > event.endBlockNumber -> SearchDirection.FORWARD
          else -> null
        }
      }
    ).thenApply { it?.let { DataFinalizedV3.fromEthLog(it) } }
  }

  override fun findDataFinalizedEventByStartBlockNumber(
    l2BlockNumber: ULong
  ): SafeFuture<EthLogEvent<DataFinalizedV3>?> {
    return findDataFinalizedV3Event(
      fromL1BlockNumber = l1EarliestSearchBlock,
      toL1BlockNumber = l1LatestSearchBlock,
      startBlockNumber = l2BlockNumber
    )
  }

  override fun findDataSubmittedV3EventsUntilNextFinalization(
    l2StartBlockNumberInclusive: ULong
  ): SafeFuture<FinalizationAndDataEventsV3?> {
    return findDataFinalizedV3Event(
      fromL1BlockNumber = l1EarliestSearchBlock,
      toL1BlockNumber = l1LatestSearchBlock,
      startBlockNumber = l2StartBlockNumberInclusive
    )
      .thenCompose { finalizationEvent ->
        finalizationEvent
          ?.let {
            findAggregationDataSubmittedV3Events(it)
              .thenApply { dataSubmittedEvents ->
                FinalizationAndDataEventsV3(dataSubmittedEvents, it)
              }
          }
          ?: SafeFuture.completedFuture(null)
      }
  }

  private fun findDataFinalizedV3Event(
    fromL1BlockNumber: BlockParameter,
    toL1BlockNumber: BlockParameter,
    startBlockNumber: ULong? = null,
    endBlockNumber: ULong? = null
  ): SafeFuture<EthLogEvent<DataFinalizedV3>?> {
    assert(startBlockNumber != null || endBlockNumber != null) {
      "Either startBlockNumber or endBlockNumber must be provided"
    }

    /**
     event DataFinalizedV3(
     uint256 indexed startBlockNumber,
     uint256 indexed endBlockNumber,
     bytes32 indexed shnarf,
     bytes32 parentStateRootHash,
     bytes32 finalStateRootHash
     );
     */
    return logsSearcher.getLogs(
      fromBlock = fromL1BlockNumber,
      toBlock = toL1BlockNumber,
      address = smartContractAddress,
      topics = listOf(
        DataFinalizedV3.topic,
        startBlockNumber?.toHexStringUInt256(),
        endBlockNumber?.toHexStringUInt256()
      )
    ).thenCompose { rawLogs ->
      val finalizedEvents = rawLogs.map(DataFinalizedV3::fromEthLog)

      if (finalizedEvents.size > 1) {
        // just a safety check
        // this should never happen: Finalization events shall be sequential and deterministic
        val errorMessage =
          "More than one DataFinalizedV3 event found for startBlockNumber=$startBlockNumber events=$finalizedEvents"
        SafeFuture.failedFuture(IllegalStateException(errorMessage))
      } else {
        SafeFuture.completedFuture(finalizedEvents.firstOrNull())
      }
    }
  }

  private fun findAggregationDataSubmittedV3Events(
    finalizationEvent: EthLogEvent<DataFinalizedV3>
  ): SafeFuture<List<EthLogEvent<DataSubmittedV3>>> {
    val dataEvents = mutableListOf<EthLogEvent<DataSubmittedV3>>()
    val futureResult = SafeFuture<List<EthLogEvent<DataSubmittedV3>>>()
    fun fetchParentDataSubmission(dataSubmission: EthLogEvent<DataSubmittedV3>?) {
      if (
        dataSubmission == null ||
        dataSubmission.event.finalStateRootHash.contentEquals(finalizationEvent.event.parentStateRootHash)
      ) {
        // if dataSubmission == null
        // means there is no parent event so we are done.
        futureResult.complete(dataEvents)
      } else {
        // adding to the head of the list so client gets the events in the order of submission
        dataEvents.addFirst(dataSubmission)
        findDataSubmittedV3EventByShnarf(
          fromL1BlockParameter = BlockParameter.Tag.EARLIEST,
          tol1BlockParameter = dataSubmission.log.blockNumber.toLong().toBlockParameter(),
          shnarf = dataSubmission.event.parentShnarf
        ).thenPeek(::fetchParentDataSubmission)
      }
    }

    getDataSubmittedV3EventByShnarf(
      fromL1BlockParameter = BlockParameter.Tag.EARLIEST,
      tol1BlockParameter = finalizationEvent.log.blockNumber.toLong().toBlockParameter(),
      shnarf = finalizationEvent.event.shnarf
    ).thenPeek(::fetchParentDataSubmission)

    return futureResult
  }

  private fun getDataSubmittedV3EventByShnarf(
    fromL1BlockParameter: BlockParameter,
    tol1BlockParameter: BlockParameter,
    shnarf: ByteArray
  ): SafeFuture<EthLogEvent<DataSubmittedV3>> {
    return findDataSubmittedV3EventByShnarf(fromL1BlockParameter, tol1BlockParameter, shnarf)
      .thenApply { event ->
        event ?: throw IllegalStateException("DataSubmittedV3 event not found for shnarf=${shnarf.encodeHex()}")
      }
  }

  private fun findDataSubmittedV3EventByShnarf(
    fromL1BlockParameter: BlockParameter,
    tol1BlockParameter: BlockParameter,
    shnarf: ByteArray
  ): SafeFuture<EthLogEvent<DataSubmittedV3>?> {
    return logsSearcher
      .getLogs(
        fromBlock = fromL1BlockParameter,
        toBlock = tol1BlockParameter,
        address = smartContractAddress,
        topics = listOf(
          DataSubmittedV3.topic,
          shnarf.encodeHex()
        )
      )
      .thenApply { rawLogs ->
        val events = rawLogs.map(DataSubmittedV3::fromEthLog)
        if (events.size > 1) {
          // just a safety check
          // this should never happen: having more than blob with the same shnarf
          val errorMessage =
            "More than one DataSubmittedV3 event found with shnarf=${shnarf.encodeHex()} events=$events"
          throw IllegalStateException(errorMessage)
        } else {
          events.firstOrNull()
        }
      }
  }
}
