package net.consensys.zkevm.coordinator.blockcreation

import io.vertx.core.Vertx
import net.consensys.linea.traces.TracesFiles
import org.apache.tuweni.bytes.Bytes32
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import tech.pegasys.teku.infrastructure.unsigned.UInt64
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.milliseconds

class TracesFilesManagerTest {
  private val tracesVersion = "0.0.1"
  private val tracesFileExtension = "json.gz"
  private lateinit var vertx: Vertx
  private lateinit var tracesDir: Path
  private lateinit var nonCanonicalBlocksTracesDir: Path
  private lateinit var tracesFilesManager: TracesFilesManager
  private val block1Hash =
    Bytes32.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000001")
  private val block2Hash1 =
    Bytes32.fromHexString("0x00000000000000000000000000000000000000000000000000000000000000a1")
  private val block2Hash2 =
    Bytes32.fromHexString("0x00000000000000000000000000000000000000000000000000000000000000a2")
  private val block20Hash =
    Bytes32.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000001")
  private lateinit var block1TracesFile: Path
  private lateinit var block2TracesFile1: Path
  private lateinit var block2TracesFile2: Path
  private lateinit var block20TracesFile: Path
  private lateinit var config: TracesFilesManager.Config

  @BeforeEach
  fun beforeEach(@TempDir tmpTestDir: Path) {
    tracesDir = tmpTestDir.resolve("raw-traces")
    nonCanonicalBlocksTracesDir = tmpTestDir.resolve("non-canonical-raw-traces")
    Files.createDirectories(tracesDir)

    val block1TracesFileName = TracesFiles.rawTracesFileNameSupplierV1(
      UInt64.ONE.longValue().toULong(),
      block1Hash,
      tracesVersion,
      tracesFileExtension
    )
    val block2TracesFile1Name = TracesFiles.rawTracesFileNameSupplierV1(
      UInt64.valueOf(2).longValue().toULong(),
      block2Hash1,
      tracesVersion,
      tracesFileExtension
    )
    val block2TracesFile2Name = TracesFiles.rawTracesFileNameSupplierV1(
      UInt64.valueOf(2).longValue().toULong(),
      block2Hash2,
      tracesVersion,
      tracesFileExtension
    )
    val block20TracesFileName = TracesFiles.rawTracesFileNameSupplierV1(
      UInt64.valueOf(20).longValue().toULong(),
      block2Hash1,
      tracesVersion,
      tracesFileExtension
    )
    block1TracesFile =
      tracesDir.resolve(Path.of(block1TracesFileName))
    block2TracesFile1 =
      tracesDir.resolve(Path.of(block2TracesFile1Name))
    block2TracesFile2 =
      tracesDir.resolve(Path.of(block2TracesFile2Name))
    block20TracesFile =
      tracesDir.resolve(Path.of(block20TracesFileName))

    vertx = Vertx.vertx()
    config =
      TracesFilesManager.Config(
        tracesDir,
        nonCanonicalBlocksTracesDir,
        pollingInterval = 10.milliseconds,
        tracesGenerationTimeout = 200.milliseconds,
        tracesFileExtension = tracesFileExtension,
        tracesEngineVersion = tracesVersion,
        createNonCanonicalTracesDirIfDoesNotExist = true
      )
    tracesFilesManager = TracesFilesManager(vertx, config, TracesFiles::rawTracesFileNameSupplierV1)
  }

  @Test
  fun `waitRawTracesGenerationOf - waits until traces file is found`() {
    // write file with wrong extension
    val inprogressFile =
      tracesDir.resolve(Path.of("1-${block1Hash.toHexString()}.inprogress")).createFile()
    assertThat(inprogressFile).exists()

    val future = tracesFilesManager.waitRawTracesGenerationOf(UInt64.ONE, block1Hash)
    vertx.setTimer((config.tracesGenerationTimeout.inWholeMilliseconds / 2)) {
      Files.createFile(block1TracesFile)
    }

    assertThat(future.get()).endsWith(block1TracesFile.toString())
  }

  @RepeatedTest(10)
  fun `waitRawTracesGenerationOf - returns error after timeout`() {
    val future = tracesFilesManager.waitRawTracesGenerationOf(UInt64.valueOf(2), block2Hash1)
    val exception = assertThrows<ExecutionException> { future.get() }
    assertThat(exception.cause).isInstanceOf(FileNotFoundException::class.java)
    assertThat(exception.message).matches(".* File matching '2-$block2Hash1.* not found .*")
  }

  @Test
  fun `cleanNonCanonicalSiblingsByHeight - returns error when file to keep is not found`() {
    val future = tracesFilesManager.cleanNonCanonicalSiblingsByHeight(UInt64.ONE, block1Hash)

    assertThat(future.get()).isEmpty()
  }

  @Test
  fun `cleanNonCanonicalSiblingsByHeight - removes found siblings`() {
    Files.createFile(block2TracesFile1)
    Files.createFile(block2TracesFile2)
    Files.createFile(block20TracesFile)
    assertThat(Files.exists(block2TracesFile1)).isTrue()
    assertThat(Files.exists(block2TracesFile2)).isTrue()
    assertThat(Files.exists(block20TracesFile)).isTrue()

    tracesFilesManager.cleanNonCanonicalSiblingsByHeight(UInt64.valueOf(2), block2Hash1).get()

    assertThat(block2TracesFile1).exists()
    assertThat(block2TracesFile2).doesNotExist()
    assertThat(block20TracesFile).exists()
  }

  // @Test
  // @Disabled("This feature is not necessary anymore. Just keeping the test in case we need to revert")
  // fun `waitRawTracesGenerationOf - cleans non canonical siblings`() {
  //   Files.createFile(block2TracesFile1)
  //   Files.createFile(block2TracesFile2)
  //   assertThat(block2TracesFile1).exists()
  //   assertThat(block2TracesFile2).exists()
  //
  //   tracesFilesManager.waitRawTracesGenerationOf(UInt64.valueOf(2), block2Hash1).get()
  //
  //   assertThat(block2TracesFile1).exists()
  //   assertThat(block2TracesFile2).doesNotExist()
  // }
}
