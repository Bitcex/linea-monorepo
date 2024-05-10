package net.consensys.zkevm.ethereum.coordination.blob

import net.consensys.zkevm.domain.BlobRecord
import net.consensys.zkevm.domain.BlockIntervals
import net.consensys.zkevm.persistence.blob.BlobsRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tech.pegasys.teku.infrastructure.async.SafeFuture
import java.util.concurrent.ExecutionException
import kotlin.IllegalStateException
import kotlin.random.Random

class RollingBlobShnarfCalculatorTest {

  private lateinit var rollingBlobShnarfCalculator: RollingBlobShnarfCalculator
  private lateinit var mockBlobsRepository: BlobsRepository
  private lateinit var firstBlob: BlobRecord
  private val firstBlobEndBlockNumber = 100UL
  private val fakeBlobShnarfCalculator = FakeBlobShnarfCalculator()

  @BeforeEach
  fun beforeEach() {
    firstBlob = mock<BlobRecord>()
    whenever(firstBlob.blobHash).thenReturn(Random.nextBytes(32))
    whenever(firstBlob.expectedShnarf).thenReturn(Random.nextBytes(32))
    whenever(firstBlob.endBlockNumber).thenReturn(firstBlobEndBlockNumber)

    mockBlobsRepository = mock<BlobsRepository>()
    whenever(mockBlobsRepository.findBlobByEndBlockNumber(any()))
      .thenAnswer {
        val blobEndBlockNumber = it.getArgument<Long>(0)
        if (blobEndBlockNumber == firstBlobEndBlockNumber.toLong()) {
          SafeFuture.completedFuture(firstBlob)
        } else {
          SafeFuture.completedFuture(null)
        }
      }

    rollingBlobShnarfCalculator = RollingBlobShnarfCalculator(
      blobShnarfCalculator = fakeBlobShnarfCalculator,
      blobsRepository = mockBlobsRepository,
      genesisShnarf = ByteArray(32)
    )
  }

  @Test
  fun `verify that blob repository is used only once when called multiple times for consecutive blobs`() {
    var previousBlobEndBlockNumber = firstBlobEndBlockNumber
    var previousBlobDataHash = firstBlob.blobHash
    var previousBlobShnarf = firstBlob.expectedShnarf
    for (i in 1..10) {
      val rollingBlobShnarfResult = rollingBlobShnarfCalculator.calculateShnarf(
        eip4844Enabled = true,
        compressedData = Random.nextBytes(100),
        parentStateRootHash = Random.nextBytes(32),
        finalStateRootHash = Random.nextBytes(32),
        conflationOrder = BlockIntervals(previousBlobEndBlockNumber + 1UL, listOf(previousBlobEndBlockNumber + 100UL))
      ).get()

      assertThat(rollingBlobShnarfResult.parentBlobHash.contentEquals(previousBlobDataHash)).isTrue()
      assertThat(rollingBlobShnarfResult.parentBlobShnarf.contentEquals(previousBlobShnarf)).isTrue()

      previousBlobEndBlockNumber += 100UL
      previousBlobDataHash = rollingBlobShnarfResult.shnarfResult.dataHash
      previousBlobShnarf = rollingBlobShnarfResult.shnarfResult.expectedShnarf
    }

    verify(mockBlobsRepository, times(1)).findBlobByEndBlockNumber(any())
    verify(mockBlobsRepository, times(1)).findBlobByEndBlockNumber(eq(firstBlobEndBlockNumber.toLong()))
  }

  @Test
  fun `throw exception when called first time and parent blob does not exist in DB`() {
    val exception = assertThrows<ExecutionException> {
      rollingBlobShnarfCalculator.calculateShnarf(
        eip4844Enabled = true,
        compressedData = Random.nextBytes(100),
        parentStateRootHash = Random.nextBytes(32),
        finalStateRootHash = Random.nextBytes(32),
        conflationOrder = BlockIntervals(firstBlobEndBlockNumber + 2UL, listOf(firstBlobEndBlockNumber + 100UL))
      ).get()
    }
    assertThat(exception).hasCauseInstanceOf(IllegalStateException::class.java)
    assertThat(exception.cause)
      .hasMessage("Failed to find the parent blob in db with end block=${firstBlobEndBlockNumber + 1UL}")
  }

  @Test
  fun `throws exception when out of order blobs are sent`() {
    val secondBlobStartBlockNumber = firstBlobEndBlockNumber + 1UL
    val secondBlobEndBlockNumber = firstBlobEndBlockNumber + 100UL
    val rollingBlobShnarfResult = rollingBlobShnarfCalculator.calculateShnarf(
      eip4844Enabled = true,
      compressedData = Random.nextBytes(100),
      parentStateRootHash = Random.nextBytes(32),
      finalStateRootHash = Random.nextBytes(32),
      conflationOrder = BlockIntervals(secondBlobStartBlockNumber, listOf(secondBlobEndBlockNumber))
    ).get()

    assertThat(rollingBlobShnarfResult.parentBlobHash.contentEquals(firstBlob.blobHash)).isTrue()
    assertThat(rollingBlobShnarfResult.parentBlobShnarf.contentEquals(firstBlob.expectedShnarf)).isTrue()

    verify(mockBlobsRepository, times(1)).findBlobByEndBlockNumber(any())
    verify(mockBlobsRepository, times(1)).findBlobByEndBlockNumber(eq(firstBlobEndBlockNumber.toLong()))

    val thirdBlobStartBlockNumber = secondBlobEndBlockNumber + 2UL
    val thirdBlobEndBlockNumber = secondBlobEndBlockNumber + 100UL
    val exception = assertThrows<ExecutionException> {
      rollingBlobShnarfCalculator.calculateShnarf(
        eip4844Enabled = true,
        compressedData = Random.nextBytes(100),
        parentStateRootHash = Random.nextBytes(32),
        finalStateRootHash = Random.nextBytes(32),
        conflationOrder = BlockIntervals(thirdBlobStartBlockNumber, listOf(thirdBlobEndBlockNumber))
      ).get()
    }
    assertThat(exception).hasCauseInstanceOf(IllegalStateException::class.java)
    assertThat(exception.cause)
      .hasMessage(
        "Blob block range start block number=$thirdBlobStartBlockNumber " +
          "is not equal to parent blob end block number=$secondBlobEndBlockNumber + 1"
      )
  }
}
