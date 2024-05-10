package net.consensys.zkevm.ethereum.coordination.conflation

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map
import com.github.michaelbull.result.runCatching
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.datetime.Instant
import net.consensys.linea.BlockNumberAndHash
import net.consensys.linea.async.toSafeFuture
import net.consensys.linea.errors.ErrorResponse
import net.consensys.zkevm.coordinator.clients.GetTracesCountersResponse
import net.consensys.zkevm.coordinator.clients.TracesCountersClientV1
import net.consensys.zkevm.coordinator.clients.TracesServiceErrorType
import net.consensys.zkevm.coordinator.clients.TracesWatcher
import net.consensys.zkevm.domain.BlockCounters
import net.consensys.zkevm.encoding.ExecutionPayloadV1Encoder
import net.consensys.zkevm.ethereum.coordination.blockcreation.BlockCreated
import net.consensys.zkevm.ethereum.coordination.blockcreation.BlockCreationListener
import net.consensys.zkevm.toULong
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import tech.pegasys.teku.infrastructure.async.SafeFuture
import java.util.concurrent.Callable

class BlockToBatchSubmissionCoordinator(
  private val conflationService: ConflationService,
  private val tracesFileManager: TracesWatcher,
  private val tracesCountersClient: TracesCountersClientV1,
  private val vertx: Vertx,
  private val payloadEncoder: ExecutionPayloadV1Encoder,
  private val log: Logger = LogManager.getLogger(BlockToBatchSubmissionCoordinator::class.java)
) : BlockCreationListener {
  private fun getTracesCounters(
    blockEvent: BlockCreated
  ): SafeFuture<GetTracesCountersResponse> {
    return tracesFileManager
      .waitRawTracesGenerationOf(
        blockEvent.executionPayload.blockNumber,
        blockEvent.executionPayload.blockHash
      )
      .thenCompose {
        log.trace("Traces file generated: block={}", blockEvent.executionPayload.blockNumber)
        tracesCountersClient.rollupGetTracesCounters(
          BlockNumberAndHash(
            blockEvent.executionPayload.blockNumber.longValue().toULong(),
            blockEvent.executionPayload.blockHash
          )
        )
      }
      .thenCompose { result ->
        when (result) {
          is Err<ErrorResponse<TracesServiceErrorType>> -> {
            SafeFuture.failedFuture(result.error.asException("Traces api error: "))
          }

          is Ok<GetTracesCountersResponse> -> {
            runCatching {
              parseTracesCountersResponseToJson(
                blockEvent.executionPayload.blockNumber.longValue(),
                blockEvent.executionPayload.blockHash.toHexString(),
                result.value
              )
            }.map {
              log.info("Traces counters returned in JSON: {}", it)
            }.getOrElse {
              log.error(
                "Error when parsing traces counters to JSON for block {}-{}: {}",
                blockEvent.executionPayload.blockNumber.longValue(),
                blockEvent.executionPayload.blockHash.toHexString(),
                it.message
              )
            }
            SafeFuture.completedFuture(result.value)
          }
        }
      }
  }

  override fun acceptBlock(blockEvent: BlockCreated): SafeFuture<Unit> {
    log.debug("Accepting new block={}", blockEvent.executionPayload.blockNumber)
    vertx.executeBlocking(
      Callable {
        payloadEncoder.encode(blockEvent.executionPayload)
      }
    ).toSafeFuture().thenCombine(getTracesCounters(blockEvent)) { blockRLPEncoded, traces ->
      conflationService.newBlock(
        blockEvent.executionPayload,
        BlockCounters(
          blockNumber = blockEvent.executionPayload.blockNumber.toULong(),
          blockTimestamp = Instant.fromEpochSeconds(blockEvent.executionPayload.timestamp.longValue()),
          tracesCounters = traces.tracesCounters,
          l1DataSize = traces.blockL1Size,
          blockRLPEncoded = blockRLPEncoded
        )
      )
    }.whenException { th ->
      log.error(
        "Failed to conflate block={} errorMessage={}",
        blockEvent.executionPayload.blockNumber,
        th.message,
        th
      )
    }

    // This is to parallelize `getTracesCounters` requests which would otherwise be sent sequentially
    return SafeFuture.completedFuture(Unit)
  }

  internal companion object {
    fun parseTracesCountersResponseToJson(
      blockNumber: Long,
      blockHash: String,
      tcResponse: GetTracesCountersResponse
    ): JsonObject {
      return JsonObject.of(
        "tracesEngineVersion",
        tcResponse.tracesEngineVersion,
        "blockNumber",
        blockNumber,
        "blockHash",
        blockHash,
        "blockL1Size",
        tcResponse.blockL1Size.toLong(),
        "tracesCounters",
        tcResponse.tracesCounters
          .map { it.key.name to it.value.toLong() }
          .sortedBy { it.first }
          .toMap()
      )
    }
  }
}
