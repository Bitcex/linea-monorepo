package net.consensys.zkevm.ethereum.finalization

import io.vertx.core.Vertx
import net.consensys.linea.contract.LineaRollupAsyncFriendly
import net.consensys.toULong
import net.consensys.zkevm.PeriodicPollingService
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes32
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import tech.pegasys.teku.infrastructure.async.SafeFuture
import java.math.BigInteger
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class FinalizationMonitorImpl(
  private val config: Config,
  private val contract: LineaRollupAsyncFriendly,
  private val l1Client: Web3j,
  private val l2Client: Web3j,
  private val vertx: Vertx,
  private val log: Logger = LogManager.getLogger(FinalizationMonitor::class.java)
) : FinalizationMonitor, PeriodicPollingService(
  vertx = vertx,
  pollingIntervalMs = config.pollingInterval.inWholeMilliseconds,
  log = log
) {
  data class Config(
    val pollingInterval: Duration = 500.milliseconds,
    val blocksToFinalization: UInt
  )

  private val finalizationHandlers:
    MutableMap<String, (FinalizationMonitor.FinalizationUpdate) -> SafeFuture<*>> =
      Collections.synchronizedMap(LinkedHashMap())
  private val lastFinalizationUpdate = AtomicReference<FinalizationMonitor.FinalizationUpdate>(null)

  override fun handleError(error: Throwable) {
    log.error("Error with finalization monitor: errorMessage={}", error.message, error)
  }

  override fun start(): SafeFuture<Unit> {
    return getFinalizationState().thenApply {
      lastFinalizationUpdate.set(it)
      super.start()
    }
  }

  override fun action(): SafeFuture<Unit> {
    log.debug("Checking finalization updates")
    return getFinalizationState().thenCompose { currentState ->
      if (lastFinalizationUpdate.get() != currentState) {
        log.info(
          "finalization update: previousFinalizedBlock={} newFinalizedBlock={}",
          lastFinalizationUpdate.get().blockNumber,
          currentState
        )
        lastFinalizationUpdate.set(currentState)
        onUpdate(currentState)
      } else {
        SafeFuture.completedFuture(Unit)
      }
    }
  }

  private fun getFinalizationState(): SafeFuture<FinalizationMonitor.FinalizationUpdate> {
    return SafeFuture.of(l1Client.ethBlockNumber().sendAsync())
      .thenCompose { latestBlockNumber ->
        val safeBlockNumber =
          DefaultBlockParameter.valueOf(
            latestBlockNumber.blockNumber.minus(BigInteger.valueOf(config.blocksToFinalization.toLong()))
          )
        contract.setDefaultBlockParameter(safeBlockNumber)
        contract.currentL2BlockNumber().sendAsync()
      }.thenCompose { blockNumber ->
        l2Client
          .ethGetBlockByNumber(DefaultBlockParameter.valueOf(blockNumber), false)
          .sendAsync()
          .thenCombine(contract.stateRootHashes(blockNumber).sendAsync()) { finalizedBlock, stateRootHash ->
            FinalizationMonitor.FinalizationUpdate(
              blockNumber.toULong(),
              Bytes32.wrap(stateRootHash),
              Bytes32.fromHexString(finalizedBlock.block.hash)
            )
          }
      }
  }

  private fun onUpdate(finalizationUpdate: FinalizationMonitor.FinalizationUpdate): SafeFuture<Unit> {
    return finalizationHandlers.entries.fold(SafeFuture.completedFuture(Unit)) { agg, entry ->
      val handlerName = entry.key
      val finalizationHandler = entry.value
      agg.thenCompose {
        log.trace(
          "calling finalization handler: handler={} update={}",
          handlerName,
          finalizationUpdate.blockNumber
        )
        try {
          finalizationHandler(finalizationUpdate)
            .thenApply { }
        } catch (th: Throwable) {
          log.error("Finalization handler={} failed. errorMessage={}", handlerName, th.message, th)
          SafeFuture.completedFuture(Unit)
        }
      }
    }.thenApply {}
  }

  override fun getLastFinalizationUpdate(): FinalizationMonitor.FinalizationUpdate {
    return lastFinalizationUpdate.get()
  }

  override fun addFinalizationHandler(
    handlerName: String,
    handler: (FinalizationMonitor.FinalizationUpdate) -> SafeFuture<*>
  ) {
    synchronized(finalizationHandlers) {
      finalizationHandlers[handlerName] = handler
    }
  }

  override fun removeFinalizationHandler(handlerName: String) {
    synchronized(finalizationHandlers) {
      finalizationHandlers.remove(handlerName)
    }
  }
}
