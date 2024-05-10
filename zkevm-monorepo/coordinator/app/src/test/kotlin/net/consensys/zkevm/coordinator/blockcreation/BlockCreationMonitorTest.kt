package net.consensys.zkevm.coordinator.blockcreation

import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import net.consensys.linea.async.get
import net.consensys.zkevm.ethereum.coordination.blockcreation.BlockCreated
import net.consensys.zkevm.ethereum.coordination.blockcreation.BlockCreationListener
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.bytes.Bytes32
import org.apache.tuweni.units.bigints.UInt256
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.atLeastOnce
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.atMost
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.EthBlock
import tech.pegasys.teku.ethereum.executionclient.schema.ExecutionPayloadV1
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.bytes.Bytes20
import tech.pegasys.teku.infrastructure.unsigned.UInt64
import java.math.BigInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@ExtendWith(VertxExtension::class)
class BlockCreationMonitorTest {
  private val parentHash =
    Bytes32.fromHexString("0x1000000000000000000000000000000000000000000000000000000000000000")
  private val startingBlockNumberInclusive: Long = 100
  private lateinit var log: Logger
  private lateinit var web3jNativeClientMock: Web3j
  private lateinit var web3jClient: ExtendedWeb3J
  private lateinit var blockCreationListener: BlockCreationListener
  private var lastProvenBlock: Long = startingBlockNumberInclusive
  private var config: BlockCreationMonitor.Config =
    BlockCreationMonitor.Config(
      pollingInterval = 100.milliseconds,
      blocksToFinalization = 2L,
      blocksFetchLimit = 5L
    )
  private val executor = Executors.newSingleThreadScheduledExecutor()
  private lateinit var monitor: BlockCreationMonitor

  @BeforeEach
  fun beforeEach(vertx: Vertx) {
    val ethGetBlockByNumberMock: Request<Any, EthBlock> = mock {
      on { sendAsync() } doReturn SafeFuture.completedFuture(EthBlock())
      on { sendAsync() } doReturn SafeFuture.completedFuture(EthBlock())
      on { sendAsync() } doReturn SafeFuture.completedFuture(
        EthBlock().apply {
          result = EthBlock.Block()
            .apply {
              setNumber("0x63")
              hash = "0x1000000000000000000000000000000000000000000000000000000000000000"
            }
        }
      )
    }

    web3jNativeClientMock = mock {
      on { ethGetBlockByNumber(any(), any()) } doReturn ethGetBlockByNumberMock
    }
    web3jClient = mock {
      on { web3jClient } doReturn web3jNativeClientMock
    }
    blockCreationListener =
      mock { on { acceptBlock(any()) } doReturn SafeFuture.completedFuture(Unit) }

    log = mock()

    val lastProvenBlockNumberProviderAsync = object : LastProvenBlockNumberProviderAsync {
      override fun getLastProvenBlockNumber(): SafeFuture<Long> {
        return SafeFuture.completedFuture(lastProvenBlock)
      }
    }

    monitor =
      BlockCreationMonitor(
        vertx,
        web3jClient,
        startingBlockNumberExclusive = startingBlockNumberInclusive - 1,
        blockCreationListener,
        lastProvenBlockNumberProviderAsync,
        config
      )
  }

  @AfterEach
  fun afterEach(vertx: Vertx) {
    monitor.stop()
    vertx.close().get()
  }

  @Test
  fun `notifies listener after block is finalized sync`(vertx: Vertx, testContext: VertxTestContext) {
    val payload =
      executionPayloadV1(blockNumber = startingBlockNumberInclusive, parentHash = parentHash)
    val payload2 =
      executionPayloadV1(
        blockNumber = startingBlockNumberInclusive + 1,
        parentHash = payload.blockHash
      )
    val headBlockNumber = startingBlockNumberInclusive + config.blocksToFinalization
    whenever(web3jClient.ethBlockNumber())
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber)))
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 1)))
    whenever(web3jClient.ethGetExecutionPayloadByNumber(any()))
      .thenReturn(SafeFuture.completedFuture(payload))
      .thenReturn(SafeFuture.completedFuture(payload2))
    whenever(blockCreationListener.acceptBlock(any())).thenReturn(SafeFuture.completedFuture(Unit))

    val lastProvenBlockNumberProviderAsync = mock<LastProvenBlockNumberProviderAsync>()

    val monitor =
      BlockCreationMonitor(
        vertx,
        web3jClient,
        startingBlockNumberExclusive = startingBlockNumberInclusive - 1,
        blockCreationListener,
        lastProvenBlockNumberProviderAsync,
        config
      )
    whenever(lastProvenBlockNumberProviderAsync.getLastProvenBlockNumber()).thenAnswer {
      SafeFuture.completedFuture(lastProvenBlock)
    }
    monitor.start().thenApply {
      await()
        .untilAsserted {
          verify(lastProvenBlockNumberProviderAsync, atLeast(3)).getLastProvenBlockNumber()
          verify(web3jClient).ethGetExecutionPayloadByNumber(eq(startingBlockNumberInclusive))
          verify(web3jClient).ethGetExecutionPayloadByNumber(eq(startingBlockNumberInclusive + 1))
          verify(blockCreationListener).acceptBlock(BlockCreated(payload))
          verify(blockCreationListener).acceptBlock(BlockCreated(payload2))
          assertThat(monitor.nexBlockNumberToFetch).isEqualTo(startingBlockNumberInclusive + 2)
        }
      testContext.completeNow()
    }
      .whenException(testContext::failNow)
  }

  @Test
  fun `does not notify listener when block is not safely finalized`(testContext: VertxTestContext) {
    val payload =
      executionPayloadV1(blockNumber = startingBlockNumberInclusive, parentHash = parentHash)
    val headBlockNumber = startingBlockNumberInclusive + config.blocksToFinalization - 1
    whenever(web3jClient.ethBlockNumber())
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber)))
    whenever(web3jClient.ethGetExecutionPayloadByNumber(any()))
      .thenReturn(SafeFuture.completedFuture(payload))
    whenever(blockCreationListener.acceptBlock(any())).thenReturn(SafeFuture.completedFuture(Unit))

    monitor.start().thenApply {
      await()
        .timeout(1.seconds.toJavaDuration())
        .untilAsserted {
          verify(web3jClient, never()).ethGetExecutionPayloadByNumber(any())
          verify(blockCreationListener, never()).acceptBlock(BlockCreated(payload))
          assertThat(monitor.nexBlockNumberToFetch).isEqualTo(startingBlockNumberInclusive)
        }
      testContext.completeNow()
    }
      .whenException(testContext::failNow)
  }

  @Test
  fun `when listener throws retries on the next tick and moves on`(testContext: VertxTestContext) {
    val payload =
      executionPayloadV1(blockNumber = startingBlockNumberInclusive, parentHash = parentHash)
    val headBlockNumber = startingBlockNumberInclusive + config.blocksToFinalization + 1
    whenever(web3jClient.ethBlockNumber())
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber)))
    whenever(web3jClient.ethGetExecutionPayloadByNumber(any()))
      .thenReturn(SafeFuture.completedFuture(payload))

    whenever(blockCreationListener.acceptBlock(any()))
      .thenReturn(SafeFuture.failedFuture(Exception("Notification 1 Error")))
      .thenThrow(RuntimeException("Notification 2 Error"))
      .thenReturn(SafeFuture.failedFuture(Exception("Notification 3 Error")))
      .thenReturn(SafeFuture.completedFuture(Unit))

    monitor.start().thenApply {
      await()
        .timeout(1.seconds.toJavaDuration())
        .untilAsserted {
          verify(blockCreationListener, atLeast(4)).acceptBlock(BlockCreated(payload))
          assertThat(monitor.nexBlockNumberToFetch).isEqualTo(startingBlockNumberInclusive + 1)
        }
      testContext.completeNow()
    }
      .whenException(testContext::failNow)
  }

  @Test
  fun `is resilient to connection failures`(testContext: VertxTestContext) {
    val payload =
      executionPayloadV1(blockNumber = startingBlockNumberInclusive, parentHash = parentHash)
    val headBlockNumber = startingBlockNumberInclusive + config.blocksToFinalization
    whenever(web3jClient.ethBlockNumber())
      .thenReturn(SafeFuture.failedFuture(Exception("ethBlockNumber Error 1")))
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber)))
    whenever(web3jClient.ethGetExecutionPayloadByNumber(any()))
      .thenReturn(SafeFuture.failedFuture(Exception("ethGetExecutionPayloadByNumber Error 1")))
      .thenReturn(SafeFuture.completedFuture(payload))
    whenever(blockCreationListener.acceptBlock(any())).thenReturn(SafeFuture.completedFuture(Unit))

    monitor.start().thenApply {
      await()
        .timeout(1.seconds.toJavaDuration())
        .untilAsserted {
          verify(blockCreationListener, times(1)).acceptBlock(BlockCreated(payload))
          assertThat(monitor.nexBlockNumberToFetch).isEqualTo(startingBlockNumberInclusive + 1)
        }
      testContext.completeNow()
    }
      .whenException(testContext::failNow)
  }

  @Test
  fun `should stop when reorg is detected above blocksToFinalization limit - manual intervention necessary`(
    testContext: VertxTestContext
  ) {
    val payload =
      executionPayloadV1(blockNumber = startingBlockNumberInclusive, parentHash = parentHash)
    val payload2 =
      executionPayloadV1(
        blockNumber = startingBlockNumberInclusive + 1,
        parentHash = Bytes32.random()
      )
    val headBlockNumber = startingBlockNumberInclusive + config.blocksToFinalization
    whenever(web3jClient.ethBlockNumber())
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber)))
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 1)))
    whenever(web3jClient.ethGetExecutionPayloadByNumber(any()))
      .thenReturn(SafeFuture.completedFuture(payload))
      .thenReturn(SafeFuture.completedFuture(payload2))
    whenever(blockCreationListener.acceptBlock(any())).thenReturn(SafeFuture.completedFuture(Unit))

    monitor.start().thenApply {
      await()
        .timeout(1.seconds.toJavaDuration())
        .untilAsserted {
          verify(blockCreationListener, times(1)).acceptBlock(BlockCreated(payload))
          verify(blockCreationListener, never()).acceptBlock(BlockCreated(payload2))
          assertThat(monitor.nexBlockNumberToFetch).isEqualTo(startingBlockNumberInclusive + 1)
        }
      testContext.completeNow()
    }
      .whenException(testContext::failNow)
  }

  private fun <V> delay(delay: Duration, action: () -> SafeFuture<V>): SafeFuture<V> {
    val future = SafeFuture<V>()
    executor.schedule(
      { action().propagateTo(future) },
      delay.inWholeMilliseconds,
      TimeUnit.MILLISECONDS
    )
    return future
  }

  @Test
  fun `should poll in order when response takes longer that polling interval`(testContext: VertxTestContext) {
    val payload =
      executionPayloadV1(blockNumber = startingBlockNumberInclusive, parentHash = parentHash)
    val payload2 =
      executionPayloadV1(
        blockNumber = startingBlockNumberInclusive + 1,
        parentHash = payload.blockHash
      )
    val headBlockNumber = startingBlockNumberInclusive + config.blocksToFinalization

    whenever(web3jClient.ethBlockNumber())
      .then {
        delay(config.pollingInterval.times(2)) {
          SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber))
        }
      }
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 1)))
    whenever(web3jClient.ethGetExecutionPayloadByNumber(any()))
      .then { delay(config.pollingInterval.times(2)) { SafeFuture.completedFuture(payload) } }
      .thenReturn(SafeFuture.completedFuture(payload2))
    whenever(blockCreationListener.acceptBlock(any())).thenReturn(SafeFuture.completedFuture(Unit))

    monitor.start().thenApply {
      await()
        .untilAsserted {
          verify(blockCreationListener).acceptBlock(BlockCreated(payload))
          verify(blockCreationListener).acceptBlock(BlockCreated(payload2))
          assertThat(monitor.nexBlockNumberToFetch).isEqualTo(startingBlockNumberInclusive + 2)
        }
      testContext.completeNow()
    }
      .whenException(testContext::failNow)
  }

  @Test
  fun `start allow 2nd call when already started`() {
    monitor.start().get()
    monitor.start().get()
  }

  @Test
  fun `stop should be idempotent`(testContext: VertxTestContext) {
    val payload =
      executionPayloadV1(blockNumber = startingBlockNumberInclusive, parentHash = parentHash)
    val payload2 =
      executionPayloadV1(
        blockNumber = startingBlockNumberInclusive + 1,
        parentHash = payload.blockHash
      )
    val headBlockNumber = startingBlockNumberInclusive + config.blocksToFinalization
    whenever(web3jClient.ethBlockNumber())
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber)))
      .then {
        delay(config.pollingInterval.times(30)) {
          SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 1))
        }
      }
    whenever(web3jClient.ethGetExecutionPayloadByNumber(any()))
      .thenReturn(SafeFuture.completedFuture(payload))
      .then { delay(config.pollingInterval.times(30)) { SafeFuture.completedFuture(payload2) } }
    whenever(blockCreationListener.acceptBlock(any())).thenReturn(SafeFuture.completedFuture(Unit))

    monitor.start().thenApply {
      await()
        .timeout(1.seconds.toJavaDuration())
        .untilAsserted {
          verify(blockCreationListener, times(1)).acceptBlock(any())
        }
    }
      .whenException(testContext::failNow)

    monitor.stop().thenApply {
      await()
        .timeout(1.seconds.toJavaDuration())
        .untilAsserted {
          verify(blockCreationListener, times(1)).acceptBlock(any())
        }
      testContext.completeNow()
    }
      .whenException(testContext::failNow)
  }

  @Test
  fun `block shouldn't be fetched when block gap is greater than fetch limit`(testContext: VertxTestContext) {
    val payload = executionPayloadV1(blockNumber = startingBlockNumberInclusive, parentHash = parentHash)
    val payload2 = executionPayloadV1(blockNumber = startingBlockNumberInclusive + 1, parentHash = payload.blockHash)
    val payload3 = executionPayloadV1(blockNumber = startingBlockNumberInclusive + 2, parentHash = payload2.blockHash)
    val payload4 = executionPayloadV1(blockNumber = startingBlockNumberInclusive + 3, parentHash = payload3.blockHash)
    val payload5 = executionPayloadV1(blockNumber = startingBlockNumberInclusive + 4, parentHash = payload4.blockHash)
    val payload6 = executionPayloadV1(blockNumber = startingBlockNumberInclusive + 5, parentHash = payload5.blockHash)
    val payload7 = executionPayloadV1(blockNumber = startingBlockNumberInclusive + 6, parentHash = payload6.blockHash)

    val headBlockNumber = startingBlockNumberInclusive + config.blocksToFinalization
    whenever(web3jClient.ethGetExecutionPayloadByNumber(any()))
      .thenReturn(SafeFuture.completedFuture(payload))
      .then { SafeFuture.completedFuture(payload2) }
      .then { SafeFuture.completedFuture(payload3) }
      .then { SafeFuture.completedFuture(payload4) }
      .then { SafeFuture.completedFuture(payload5) }
      .then { SafeFuture.completedFuture(payload6) }
      .then { SafeFuture.completedFuture(payload7) }

    whenever(web3jClient.ethBlockNumber())
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber)))
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 2)))
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 3)))
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 4)))
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 5)))
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 6)))
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 7)))
    whenever(blockCreationListener.acceptBlock(any())).thenReturn(SafeFuture.completedFuture(Unit))

    monitor.start().thenApply {
      await()
        .timeout(4.seconds.toJavaDuration())
        .untilAsserted {
          verify(blockCreationListener, atLeastOnce()).acceptBlock(any())
          // Number of invocations should remain at 5 as the blocks are now above the limit
          verify(blockCreationListener, atMost(6)).acceptBlock(any())
        }
      testContext.completeNow()
    }
      .whenException(testContext::failNow)
  }

  @Test
  fun `last block not fetched until finalization catches up to limit`(vertx: Vertx, testContext: VertxTestContext) {
    val payload = executionPayloadV1(blockNumber = startingBlockNumberInclusive, parentHash = parentHash)
    val payload2 = executionPayloadV1(blockNumber = startingBlockNumberInclusive + 1, parentHash = payload.blockHash)
    val payload3 = executionPayloadV1(blockNumber = startingBlockNumberInclusive + 2, parentHash = payload2.blockHash)
    val payload4 = executionPayloadV1(blockNumber = startingBlockNumberInclusive + 3, parentHash = payload3.blockHash)
    val payload5 = executionPayloadV1(blockNumber = startingBlockNumberInclusive + 4, parentHash = payload4.blockHash)
    val payload6 = executionPayloadV1(blockNumber = startingBlockNumberInclusive + 5, parentHash = payload5.blockHash)
    val payload7 = executionPayloadV1(blockNumber = startingBlockNumberInclusive + 6, parentHash = payload6.blockHash)

    val headBlockNumber = startingBlockNumberInclusive + config.blocksToFinalization + config.blocksFetchLimit
    whenever(web3jClient.ethGetExecutionPayloadByNumber(any()))
      .thenReturn(SafeFuture.completedFuture(payload))
      .thenReturn(SafeFuture.completedFuture(payload2))
      .thenReturn(SafeFuture.completedFuture(payload3))
      .thenReturn(SafeFuture.completedFuture(payload4))
      .thenReturn(SafeFuture.completedFuture(payload5))
      .thenReturn(SafeFuture.completedFuture(payload6))
      .thenReturn(SafeFuture.completedFuture(payload7))

    whenever(web3jClient.ethBlockNumber())
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber)))
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 1)))
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 2)))
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 3)))
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 4)))
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 5)))
      .thenReturn(SafeFuture.completedFuture(BigInteger.valueOf(headBlockNumber + 6)))
    whenever(blockCreationListener.acceptBlock(any())).thenReturn(SafeFuture.completedFuture(Unit))

    val lastProvenBlockNumberProviderAsync = mock<LastProvenBlockNumberProviderAsync>()

    val monitor =
      BlockCreationMonitor(
        vertx,
        web3jClient,
        startingBlockNumberExclusive = startingBlockNumberInclusive - 1,
        blockCreationListener,
        lastProvenBlockNumberProviderAsync,
        config
      )

    whenever(lastProvenBlockNumberProviderAsync.getLastProvenBlockNumber()).thenAnswer {
      SafeFuture.completedFuture(lastProvenBlock)
    }

    monitor.start().thenApply {
      await()
        .timeout(4.seconds.toJavaDuration())
        .untilAsserted {
          verify(blockCreationListener, atLeastOnce()).acceptBlock(any())
          verify(blockCreationListener, times(6)).acceptBlock(any())
        }
    }.thenApply {
      whenever(lastProvenBlockNumberProviderAsync.getLastProvenBlockNumber()).thenAnswer {
        SafeFuture.completedFuture(lastProvenBlock + 1)
      }
      await()
        .timeout(2.seconds.toJavaDuration())
        .untilAsserted {
          verify(blockCreationListener, times(7)).acceptBlock(any())
        }
      testContext.completeNow()
    }
      .whenException(testContext::failNow)
  }

  private fun executionPayloadV1(
    blockNumber: Long = 0,
    parentHash: Bytes32 = Bytes32.random(),
    feeRecipient: Bytes20 = Bytes20(Bytes.random(20)),
    stateRoot: Bytes32 = Bytes32.random(),
    receiptsRoot: Bytes32 = Bytes32.random(),
    logsBloom: Bytes = Bytes32.random(),
    prevRandao: Bytes32 = Bytes32.random(),
    gasLimit: UInt64 = UInt64.valueOf(0),
    gasUsed: UInt64 = UInt64.valueOf(0),
    timestamp: UInt64 = UInt64.valueOf(0),
    extraData: Bytes = Bytes32.random(),
    baseFeePerGas: UInt256 = UInt256.valueOf(256),
    blockHash: Bytes32 = Bytes32.random(),
    transactions: List<Bytes> = emptyList()
  ): ExecutionPayloadV1 {
    return ExecutionPayloadV1(
      parentHash,
      feeRecipient,
      stateRoot,
      receiptsRoot,
      logsBloom,
      prevRandao,
      UInt64.valueOf(blockNumber),
      gasLimit,
      gasUsed,
      timestamp,
      extraData,
      baseFeePerGas,
      blockHash,
      transactions
    )
  }
}
