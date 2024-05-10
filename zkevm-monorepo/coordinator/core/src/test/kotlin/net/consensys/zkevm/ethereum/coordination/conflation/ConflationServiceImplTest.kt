package net.consensys.zkevm.ethereum.coordination.conflation

import kotlinx.datetime.Instant
import net.consensys.linea.traces.fakeTracesCounters
import net.consensys.zkevm.domain.BlockCounters
import net.consensys.zkevm.domain.BlocksConflation
import net.consensys.zkevm.domain.ConflationCalculationResult
import net.consensys.zkevm.domain.ConflationTrigger
import net.consensys.zkevm.toULong
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tech.pegasys.teku.ethereum.executionclient.schema.executionPayloadV1
import tech.pegasys.teku.infrastructure.async.SafeFuture
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

class ConflationServiceImplTest {
  private val conflationBlockLimit = 2u
  private lateinit var conflationCalculator: TracesConflationCalculator
  private lateinit var conflationService: ConflationServiceImpl

  @BeforeEach
  fun beforeEach() {
    conflationCalculator = GlobalBlockConflationCalculator(
      lastBlockNumber = 0u,
      syncCalculators = listOf(
        ConflationCalculatorByBlockLimit(conflationBlockLimit)
      ),
      deferredTriggerConflationCalculators = emptyList()
    )
    conflationService = ConflationServiceImpl(conflationCalculator, mock(defaultAnswer = RETURNS_DEEP_STUBS))
  }

  @Test
  fun `emits event with blocks when calculator emits conflation`() {
    val payload1 = executionPayloadV1(blockNumber = 1)
    val payload2 = executionPayloadV1(blockNumber = 2)
    val payload3 = executionPayloadV1(blockNumber = 3)
    val payload1Time = Instant.parse("2021-01-01T00:00:00Z")
    val payloadCounters1 = BlockCounters(
      blockNumber = 1UL,
      payload1Time.plus(0.seconds),
      tracesCounters = fakeTracesCounters(40u),
      l1DataSize = 10u,
      blockRLPEncoded = ByteArray(0)
    )
    val payloadCounters2 = BlockCounters(
      blockNumber = 2UL,
      payload1Time.plus(2.seconds),
      tracesCounters = fakeTracesCounters(40u),
      l1DataSize = 10u,
      blockRLPEncoded = ByteArray(0)
    )
    val payloadCounters3 = BlockCounters(
      blockNumber = 3UL,
      payload1Time.plus(4.seconds),
      tracesCounters = fakeTracesCounters(100u),
      l1DataSize = 10u,
      blockRLPEncoded = ByteArray(0)
    )

    val conflationEvents = mutableListOf<BlocksConflation>()
    conflationService.onConflatedBatch { conflationEvent: BlocksConflation ->
      conflationEvents.add(conflationEvent)
      SafeFuture.completedFuture(Unit)
    }

    // 1st conflation
    conflationService.newBlock(payload1, payloadCounters1)
    conflationService.newBlock(payload2, payloadCounters2)
    conflationService.newBlock(payload3, payloadCounters3)

    assertThat(conflationEvents).isEqualTo(
      listOf(
        BlocksConflation(
          listOf(payload1, payload2),
          ConflationCalculationResult(
            startBlockNumber = 1u,
            endBlockNumber = 2u,
            conflationTrigger = ConflationTrigger.BLOCKS_LIMIT,
            dataL1Size = 0u,
            // these are not counted in conflation, so will be 0
            tracesCounters = fakeTracesCounters(0u)
          )
        )
      )
    )
  }

  @Test
  fun `sends blocks in correct order to calculator`() {
    val numberOfThreads = 10
    val numberOfBlocks = 2000
    val moduleTracesCounter = 10u
    assertThat(numberOfBlocks % numberOfThreads).isEqualTo(0)
    val expectedConflations = numberOfBlocks / conflationBlockLimit.toInt() - 1
    val blocks = (1..numberOfBlocks).map { executionPayloadV1(blockNumber = it.toLong()) }
    val fixedTracesCounters = fakeTracesCounters(moduleTracesCounter)
    val blockTime = Instant.parse("2021-01-01T00:00:00Z")
    val conflationEvents = mutableListOf<BlocksConflation>()
    conflationService.onConflatedBatch { conflationEvent: BlocksConflation ->
      conflationEvents.add(conflationEvent)
      SafeFuture.completedFuture(Unit)
    }
    val blockChunks = blocks.shuffled().chunked(numberOfBlocks / numberOfThreads)
    assertThat(blockChunks.size).isEqualTo(numberOfThreads)

    val executor = Executors.newFixedThreadPool(numberOfThreads)
    blockChunks.forEach { chunck ->
      executor.submit {
        chunck.forEach {
          conflationService.newBlock(
            it,
            BlockCounters(
              blockNumber = it.blockNumber.toULong(),
              blockTimestamp = blockTime,
              tracesCounters = fixedTracesCounters,
              l1DataSize = 1u,
              blockRLPEncoded = ByteArray(0)
            )
          )
        }
      }
    }

    Awaitility.waitAtMost(Duration.ofSeconds(30)).until {
      conflationEvents.size >= expectedConflations
    }
    executor.shutdown()

    var expectedNexStartBlockNumber: ULong = 1u
    assertThat(conflationEvents.size).isEqualTo(expectedConflations)
    conflationEvents.forEachIndexed { index, event ->
      assertThat(event.conflationResult.startBlockNumber).isEqualTo(expectedNexStartBlockNumber)
      expectedNexStartBlockNumber = event.conflationResult.endBlockNumber + 1u
    }
    assertThat(conflationService.blocksToConflate).isEmpty()
  }

  @Test
  fun `if calculator fails, error is propagated`() {
    val moduleTracesCounter = 10u
    val fixedTracesCounters = fakeTracesCounters(moduleTracesCounter)
    val blockTime = Instant.parse("2021-01-01T00:00:00Z")

    val expectedException = RuntimeException("Calculator failed!")
    val failingConflationCalculator: TracesConflationCalculator = mock()
    whenever(failingConflationCalculator.newBlock(any())).thenThrow(expectedException)
    conflationService = ConflationServiceImpl(failingConflationCalculator, mock(defaultAnswer = RETURNS_DEEP_STUBS))
    val block = executionPayloadV1(blockNumber = 1)

    assertThatThrownBy {
      conflationService.newBlock(
        block,
        BlockCounters(
          blockNumber = block.blockNumber.toULong(),
          blockTimestamp = blockTime,
          tracesCounters = fixedTracesCounters,
          l1DataSize = 1u,
          blockRLPEncoded = ByteArray(0)
        )
      )
    }.isEqualTo(expectedException)
  }
}
